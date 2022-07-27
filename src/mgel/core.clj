(ns mgel.core
  (:require [hato.client :as hc]
            [cheshire.core :refer [generate-string]]
            [babashka.pods :as pods]
            [portal.api :as p]
            [clojure.string :as str]
            [babashka.fs :as fs]))

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
      :body
      :access_token))
#_(tap> (hc/get "https://api.challonge.com/oauth/authorize"
              {:query-params {:scope "me tournaments:read matches:read participants:read"
                              :client_id client_id
                              :response_type "code"
                              :community_id "tf2"
                              :redirect_uri "https://oauth.pstmn.io/v1/callback"}}))

(def tokens {:access_token "eyJhbGciOiJIUzUxMiJ9.eyJpc3MiOiJtZ2VsIiwianRpIjoiZDMxOTZmNTAtYWZiNS00NTU0LTg3NmItZGNiN2JkNTYxMDY1IiwiaWF0IjoxNjU4NTQyNDUzLCJleHAiOjE2NTkxNDcyNTMsInVzZXIiOnsiaWQiOjU2NjM1NDMsImVtYWlsIjoidGhtb3JyaXNzQGxpdmUuY29tIiwiYXBwbGljYXRpb25faWQiOjIyNywiZ3JhbnVsYXJfcGVybWlzc2lvbiI6WyIxNTI5OTgiXX19.9dfXNyBfRructJ9N9CgWv_6_8uEXt4pjbwxR61_-pMIQvMhtqXdoz8qJ0DZwOVKc37BmpJv0MoY202DYgVje6Q"
             , :token_type "Bearer",
             :expires_in 604800,
             :refresh_token "b273f1dec3ca19f60d98e99738968673895de8ea284486dfd9e3dac8769edfe5",
             :scope "me tournaments:read tournaments:write matches:read matches:write participants:read participants:write attachments:read attachments:write communities:manage",
             :created_at 1658542453})

(def token (get-oauth-token))
(def options {:headers {"Authorization-Type" "v2"}
              :oauth-token (:access_token tokens)
              :content-type "application/vnd.api+json"
              :accept :json
              :as :json})

(comment (:body (hc/get "https://api.challonge.com/v2/me.json" options)))


(defn make-tournament []
  (hc/post (str api-root "/tournaments.json")
           (merge options
                  {:body (generate-string {:data {:type "tournaments"
                                                  :attributes
                                                  {:name "mge weekly tournament"
                                                   :tournament_type "double elimination"}}})})))


(def current-tourney-id (-> (hc/get (str api-root "/tournaments.json") options)
                            :body
                            :data
                            first
                            :id))

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

(def players [{:name "tommy"}
              {:name "hallu"}
              {:name "cmingus"}
              {:name "raphaim"}
              {:name "ander"}
              {:name "waki"}
              {:name "taz"}
              {:name "nooben"}])

(defn add-all-players [tourney-id]
  (let [payload {:data
                 {:type "Participants"
                  :attributes
                  {:participants
                   (vec (for [{:keys [name]} players]
                          {:name name :misc (str name "_steamid")
                           :seed 1}))}}} ]
    
    (hc/post (str api-root "/tournaments/" tourney-id "/participants/bulk_add.json")
             (merge options
                    {:body (generate-string payload)}))))


(comment (add-all-players current-tourney-id)

         (count (get-participants current-tourney-id)))

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

(defn sync-db [matches]
  (sqlite/execute! "matches.db"
                   ["create table if not exists matches (player1 TEXT, player2 TEXT)"])
  (sqlite/execute! "matches.db"
                   ["delete from matches where true"])
  (doseq [[player1 player2] matches]
      (sqlite/execute! "matches.db"
                       ["insert into matches (player1, player2) values (?, ?)" player1 player2]))
  (sqlite/query "matches.db"
                ["select * from matches"]))


(sync-db (get-active-matches current-tourney-id))

(get-active-matches current-tourney-id)

(def matches (get-active-matches current-tourney-id))

(def mge-db-fname "../tf/addons/sourcemod/data/sqlite/sourcemod-local.sq3")

(comment (sqlite/query mge-db-fname ["select * from sqlite_schema"])
         (sqlite/query mge-db-fname ["select * from players_in_server"])
         (map str (fs/glob "../tf/addons/sourcemod/data/sqlite" "*"))
         (+ 3 3))

(defn foo
  " I don't do a whole lot."
  [x]
  (println x "Hello, World!")) 

