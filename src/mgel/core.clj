(ns mgel.core
  (:require [hato.client :as hc]
            [cheshire.core :refer [generate-string]]
            [babashka.pods :as pods]
            [portal.api :as p]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [process check]]
            [clojure.edn :as edn]
            [mgel.oauth :as oauth]
            [medley.core :refer [index-by]]
            [xtdb.api :as xt]
            [mgel.db :refer [node]]
            [tilakone.core :as tk :refer [_]]))

(comment (def p (p/open))

         (add-tap #'p/submit))

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def api-root (or "https://api.challonge.com/v2" "https://api.challonge.com/v2/communities/tf2"))

(def tokens (oauth/get-tokens))

(def options (oauth/make-options tokens))

(defn make-tournament []
  (hc/post (str api-root "/tournaments.json")
           (merge options
                  {:body (generate-string {:data {:type "tournaments"
                                                  :attributes
                                                  {:name "mge weekly tournament"
                                                   :tournament_type "double elimination"}}})})))


(defn get-tourney [tourney-id]
  (-> (hc/get (str api-root "/tournaments/" tourney-id ".json")
              options)
      :body))

(defn get-tourney-status [tourney-id]
  (-> (get-tourney tourney-id)
      :data
      :attributes
      :state))

(defn participants-api-url [tourney-id]
  (str api-root "/tournaments/" tourney-id "/participants.json"))

(defn get-participants [tourney-id]
  (-> (hc/get (participants-api-url tourney-id)
              options)
      :body
      :data))

(comment
  (map (comp :misc :attributes) (get-participants current-tourney-id)))

(defn add-participant [tourney-id {:keys [name] :as participant}]
  (let [payload {:data
                 {:type "Participant"
                  :attributes
                  {:name name
                   :misc (str name "_steamid")}}} ]
    
    (hc/post (participants-api-url tourney-id)
             (merge options
                    {:body (generate-string payload)}))))

(defn change-tourney-status [tourney-id new-status]
  (assert (#{"start" "process_checkin" "abort_checkin" "open_predictions" "finalize" "reset"} new-status))
  (let [payload {:data
                 {:type "TournamentState"
                  :attributes
                  {:state new-status}}}]
    
    (hc/put (str api-root "/tournaments/" tourney-id "/change_state.json")
            (merge options
                   {:body (generate-string payload)}))))

(defn add-all-players [tourney-id players]
  (let [payload {:data
                 {:type "Participants"
                  :attributes
                  {:participants
                   (vec (for [{:keys [name steamid]} players]
                          {:name name :misc steamid
                           :seed 1}))}}} ]
    
    (hc/post (str api-root "/tournaments/" tourney-id "/participants/bulk_add.json")
             (merge options
                    {:body (generate-string payload)}))))


(defn flatten-keys* [a ks m]
  "http://blog.jayfields.com/2010/09/clojure-flatten-keys.html"
  (if (map? m)
    (reduce into (map (fn [[k v]] (flatten-keys* a (conj ks k) v)) (seq m)))
    (assoc a (keyword (clojure.string/join "." (map name ks))) m)))

(defn flatten-keys [m] (flatten-keys* {} [] m))

(defn ingest [tid req]
  (let [payload (->> req
                     :body
                     ((juxt :data :included))
                     (map (fn [res] (cond (map? res) [res] (vector? res) res)))
                     flatten
                     (map flatten-keys)
                     (map #(assoc % :mgel/tournament-url tid))
                     (map #(assoc (dissoc % :id) :xt/id (:id %))))]
    (def payload payload)
    (xt/await-tx node (xt/submit-tx node (for [doc payload]
                                           [::xt/put doc])))))


(defn get-active-matches [tourney-id]
  (xt/q (xt/db node) '{:find [steamid1 steamid2]
                       :where [[match :type "match"]
                               [match :mgel/tournament-url tourney-id]
                               [match :attributes.state "open"]
                               [match :relationships.player1.data.id p1]
                               [match :relationships.player2.data.id p2]
                               [p1 :attributes.misc steamid1]
                               [p2 :attributes.misc steamid2]]
                       :in [tourney-id]}
        tourney-id))

(def mge-db-fname "../tf/addons/sourcemod/data/sqlite/sourcemod-local.sq3")

(defn prep-sqlite-for-testing []
  (comment (def mge-db-fname "testing.db"))
  
  (sqlite/query mge-db-fname ["select * from players_in_server"])
  (map :name (sqlite/query mge-db-fname ["select * from sqlite_schema"]))
  (sqlite/query mge-db-fname ["select * from mgemod_duels"])
  
  (sqlite/execute! mge-db-fname ["create table if not exists players_in_server (name TEXT, steamid TEXT)"])
  (sqlite/execute! mge-db-fname ["delete from players_in_server where true"])
  (doseq [name (or
                ["tommy" "hallu" "cmingus" "raphaim" "waki" "nano" "delpo"]
                ["tommy" "hallu" "cmingus" "raphaim" "nooben" "taz" "b4nny" "arekk" "habib" "waki" "nano" "delpo"])]
    (sqlite/execute! mge-db-fname ["insert into players_in_server values (?, ?)" name (str name "_steamid")])))

(defn setup-db! []
  (sqlite/execute! mge-db-fname
                   ["create table if not exists matches (player1 TEXT, player2 TEXT)"])
  
  (sqlite/execute! mge-db-fname
                   ["delete from matches where true"])
  
  (sqlite/execute! mge-db-fname
                   ["CREATE TABLE IF NOT EXISTS mgemod_duels (winner TEXT, loser TEXT, winnerscore INTEGER, loserscore INTEGER, winlimit INTEGER, gametime INTEGER, mapname TEXT, arenaname TEXT)"])
  (sqlite/execute! mge-db-fname ["delete from mgemod_duels where true"]))

(defn add-matches-to-db! [matches]
  (comment (sqlite/query mge-db-fname ["select * from matches"]))
  (sqlite/execute! mge-db-fname ["delete from matches where true"])
  (doseq [[player1 player2] matches]
    (sqlite/execute! mge-db-fname
                     ["insert into matches (player1, player2) values (?, ?)" player1 player2])) [matches])

(defn simulate-game []
  (def match (rand-nth (sqlite/query mge-db-fname ["select * from matches"])))
  
  (sqlite/execute! mge-db-fname ["insert into mgemod_duels (winner, loser, winnerscore, loserscore, winlimit) values (?,?,?,?,?)" (:player1 match) (:player2 match) 20 18 20]))

(defn matches-by-steamid [tourney-id match]
  (ffirst (xt/q (xt/db node)
                '{:find [(pull match [*])]
                  :where [[match :type "match"]
                          [match :mgel/tournament-url tourney-id]
                          [match :attributes.state "open"]
                          [match :relationships.player1.data.id p1]
                          [match :relationships.player2.data.id p2]
                          (or (and [p1 :attributes.misc winnersteamid]
                                   [p2 :attributes.misc losertsteamid])
                              (and [p2 :attributes.misc winnersteamid]
                                   [p1 :attributes.misc losertsteamid]))]
                  :in [winnersteamid losersteamid tourney-id]}
                (:winner match)
                (:loser match)
                tourney-id)))

(defn player-by-steamid [tourney-id steamid]
  (ffirst (xt/q (xt/db node) '{:find [(pull p [*])]
                               :where [[p :mgel/tournament-url tourney-id]
                                       [p :attributes.misc steamid]]
                               :in [steamid tourney-id]}
                steamid
                tourney-id)))

(defn refresh-matches! [tourney-id]
  (ingest tourney-id
          (hc/get (str api-root "/tournaments/" tourney-id "/matches.json") options)))

(def responses (atom []))

(defn update-match-score! [tourney-id]
  (doseq [mge-match (sqlite/query mge-db-fname ["select * from mgemod_duels"])]
    (def mge-match mge-match)
    (def challonge-match (matches-by-steamid tourney-id mge-match))
    (let [match-json [{:participant_id (Integer/parseInt (:xt/id (player-by-steamid tourney-id (:winner mge-match))))
                       :score_set (str (:winnerscore mge-match))
                       :advancing true}
                      
                      {:participant_id (Integer/parseInt (:xt/id (player-by-steamid tourney-id (:loser mge-match))))
                       :score_set (str (:loserscore mge-match))
                       :advancing false}]
          body {:data
                {:type "Match"
                 :attributes {:match match-json}}}]
      (swap! responses conj (hc/put (str api-root "/tournaments/" tourney-id "/matches/" (:xt/id challonge-match) ".json")
                                    (merge options {:body (generate-string body)})))))
  (sqlite/execute! mge-db-fname ["delete from mgemod_duels where true"]))



;; TODO
;; merge with tf2 server
;; integration with demo recording and demo 3d player
(defn start-tourney []
  (setup-db!)
  
  (comment (prep-sqlite-for-testing))
  
  (def current-tourney-id (or (-> (make-tournament)
                                  :body :data :id)
                              (-> (hc/get (str api-root "/tournaments.json") options)
                                  :body :data first :id)))
  
  
  (ingest current-tourney-id (hc/get (str api-root "/tournaments/" current-tourney-id ".json") options))
  
  (def players (sqlite/query mge-db-fname ["select * from players_in_server"]))
  
  (ingest current-tourney-id (add-all-players current-tourney-id players))

  (change-tourney-status current-tourney-id "start")


  

  (refresh-matches! current-tourney-id)
  
  (while (not-empty (get-active-matches current-tourney-id))
    

    ;; put active matches into sqlite 
    (add-matches-to-db! (get-active-matches current-tourney-id))
    
    ;; a match is won
    (comment (simulate-game))

    (while (not-empty (sqlite/query mge-db-fname ["select * from mgemod_duels"]))
      (update-match-score! current-tourney-id)
      (Thread/sleep 100))
    
    (refresh-matches! current-tourney-id)
    (Thread/sleep 500))
  
  (change-tourney-status current-tourney-id "finalize")
  
  (comment
    (change-tourney-status current-tourney-id "reset")
    (hc/delete (str api-root "/tournaments/" current-tourney-id ".json") options)))

(defn send-server-command [cmd]
  (process ["screen" "-S" "tf2" "-p" "0" "-X" "stuff" (str cmd "\n")]))

(comment
  (send-server-command "start_tournament woah woah woah"))

(defn foo
  " I don't do a whole lot."
  [x]
  (start-tourney)
  (println x "Hello, World!")) 

