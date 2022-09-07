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
            [mgel.db :refer [node]]))

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


(defn get-active-matches [tourney-id]
  (let [resp (-> (hc/get (str api-root "/tournaments/" tourney-id "/matches.json")
                         options)
                 :body)
        id->steamid (->> resp
                         :included
                         (filter (comp #{"participant"} :type))
                         (map (juxt (comp #(Integer/parseInt %) :id) identity))
                         (into {}))]
    (->> resp
         :data
         (filter (comp #{"open"} :state :attributes))
         (map (fn [x]
                (->> x
                     :relationships
                     ((juxt :player1 :player2))
                     (map (comp :id :data))
                     (map #(Integer/parseInt %))
                     (map id->steamid)
                     (map (comp :misc :attributes))))))))

(def mge-db-fname "../tf/addons/sourcemod/data/sqlite/sourcemod-local.sq3")

(defn prep-sqlite-for-testing []
  (def mge-db-fname "testing.db")
  
  (sqlite/execute! mge-db-fname ["create table if not exists players_in_server (name TEXT, steamid TEXT)"])
  (sqlite/execute! mge-db-fname ["delete from players_in_server where true"])
  (doseq [name ["tommy" "hallu" "cmingus" "raphaim" "nooben" "taz"]]
    (sqlite/execute! mge-db-fname ["insert into players_in_server values (?, ?)" name (str name "_steamid")])))


(defn sync-db [matches]
  (sqlite/execute! mge-db-fname
                   ["create table if not exists matches (player1 TEXT, player2 TEXT)"])
  (sqlite/execute! mge-db-fname
                   ["CREATE TABLE IF NOT EXISTS mgemod_duels (winner TEXT, loser TEXT, winnerscore INTEGER, loserscore INTEGER, winlimit INTEGER, gametime INTEGER, mapname TEXT, arenaname TEXT)"])
  (sqlite/execute! mge-db-fname
                   ["delete from matches where true"])
  
  (sqlite/execute! mge-db-fname
                   ["delete from mgemod_duels where true"])
  (doseq [[player1 player2] matches]
    (sqlite/execute! mge-db-fname
                     ["insert into matches (player1, player2) values (?, ?)" player1 player2]))
  
  (sqlite/query mge-db-fname
                ["select * from matches"]))

(defn simulate-game []
  (def match (rand-nth (sqlite/query mge-db-fname ["select * from matches"])))
  (sqlite/execute! mge-db-fname ["insert into mgemod_duels (winner, loser, winnerscore, loserscore, winlimit) values (?,?,?,?,?)" (:player1 match) (:player2 match) 20 18 20]))




(defn start-tourney []
  (prep-sqlite-for-testing)

  (def current-tourney-id (or (-> (hc/get (str api-root "/tournaments.json") options)
                                  :body
                                  :data
                                  first
                                  :id)
                              (-> (make-tournament)
                                  :body
                                  :data
                                  :id)))
  
  (def players (sqlite/query mge-db-fname ["select * from players_in_server"]))
  
  (add-all-players current-tourney-id players)

  (change-tourney-status current-tourney-id "start")

  (def matches (get-active-matches current-tourney-id))
  (sync-db matches)
  
  (comment (while (not-empty (get-active-matches current-tourney-id))))
  ;; TODO simulate these games... to completion using while loop.
  ;; todo rewrite all this code to use the xtdb in memory to store all results from api denormalized... just query.. 
  ;; use metosin FSM library to handle the tournament states...
  

  (simulate-game)
  (def matches-whole (-> (hc/get (str api-root "/tournaments/" current-tourney-id "/matches.json")
                                 options)
                         :body))
  (def players-by-id (->> matches-whole
                               :included
                               (filter #(= (:type %) "participant"))
                               (map (juxt :id identity))
                               (into {})))
  
  (def players-by-steamid (->> matches-whole
                               :included
                               (filter #(= (:type %) "participant"))
                               (map (juxt (comp :misc :attributes) identity))
                               (into {})))
  
  (def matches-by-steamid (->> matches-whole
                               :data
                               (filter (comp #{"open"} :state :attributes))
                               (map (fn [match]
                                      [#{(-> match :relationships :player1 :data :id players-by-id :attributes :misc)
                                         (-> match :relationships :player2 :data :id players-by-id :attributes :misc)}
                                       match]))
                               (into {})))

  
  ;; convert matches from sqlite into network calls and xtdb database storage..
  (doseq [match (sqlite/query mge-db-fname ["select * from mgemod_duels"])]
    (def match match)
    (def cmatch (matches-by-steamid #{(:winner match) (:loser match)}))
    (let [match-json [{:participant_id (Integer/parseInt (:id (players-by-steamid (:winner match))))
                       :score_set (str (:winnerscore match))
                       :advancing true}
                      
                      {:participant_id (Integer/parseInt (:id (players-by-steamid (:loser match))))
                       :score_set (str (:loserscore match))
                       :advancing true}]
          body {:data
                {:type "Match"
                 :attributes {:match match-json}}}]
      (hc/put (str api-root "/tournaments/" current-tourney-id "/matches/" (:id cmatch) ".json")
                    (merge options
                           {:body (generate-string body)}))))
  (sqlite/execute! mge-db-fname ["delete from mgemod_duels where true"]))

(defn send-server-command [cmd]
  (process ["screen" "-S" "tf2" "-p" "0" "-X" "stuff" (str cmd "\n")]))

(send-server-command "start_tournament woah woah woah")

(defn foo
  " I don't do a whole lot."
  [x]
  (println x "Hello, World!")) 

