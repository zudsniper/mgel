(ns mgel.core
  (:require [hato.client :as hc]
            [cheshire.core :refer [generate-string]]
            [babashka.pods :as pods]
            [portal.api :as p]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [process check]]))
            [clojure.edn :as edn]))

(comment (def p (p/open))

         (add-tap #'p/submit))

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(def api-root "https://api.challonge.com/v2/communities/tf2")
(def client_id "3bd276c270e74d5e90d7482425ee86d68a99898b315379a07432787fca67cfc1")
(def client_secret "d64234bd3e92d1dbd6cd2950db9c1e6bfd72dc30a68ab3d9fa604a1b63057a51")

(defn get-oauth-token []
  (-> (hc/post "https://api.challonge.com/oauth/token"
               {:form-params {:grant_type "client_credentials"
                              :client_id client_id
                              :client_secret client_secret}
                :as :json})
      :body))

#_ (tap> (hc/get "https://api.challonge.com/oauth/authorize"
              {:query-params {:scope "me tournaments:read matches:read participants:read"
                              :client_id client_id
                              :response_type "code"
                              :community_id "tf2"
                              :redirect_uri "https://oauth.pstmn.io/v1/callback"}}))

(defn make-options [tokens] {:headers {"Authorization-Type" "v2"}
                             :oauth-token (:access_token tokens)
                             :content-type "application/vnd.api+json"
                             :accept :json
                             :as :json})

(def options (make-options (get-tokens)))

(defn me [token]
  (:body (hc/get "https://api.challonge.com/v2/me.json"
                           (assoc options
                                  :oauth-token token))))

(me (:oauth-token options))


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
  (let [payload {:data
                 {:type "TournamentState"
                  :attributes
                  {:state "start"}}} ]
    
    (hc/post (participants-api-url tourney-id)
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


(defn run-tourney []

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
  
  )

(defn sync-db [matches]
  (sqlite/execute! mge-db-fname
                   ["create table if not exists matches (player1 TEXT, player2 TEXT)"])
  (sqlite/execute! mge-db-fname
                   ["delete from matches where true"])
  (doseq [[player1 player2] matches]
    (sqlite/execute! mge-db-fname
                     ["insert into matches (player1, player2) values (?, ?)" player1 player2]))
  (sqlite/query mge-db-fname
                ["select * from matches"]))

(comment (sqlite/query mge-db-fname ["select * from sqlite_schema"])
         (sqlite/query mge-db-fname ["select * from players_in_server"])
         (map str (fs/glob "../tf/addons/sourcemod/data/sqlite" "*"))
         (+ 3 3))

(defn send-server-command [cmd]
  (process ["screen" "-S" "tf2" "-p" "0" "-X" "stuff" (str cmd "\n")]))

(send-server-command "start_tournament woah woah woah")

(defn foo
  " I don't do a whole lot."
  [x]
  (println x "Hello, World!")) 

