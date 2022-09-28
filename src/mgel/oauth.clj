(ns mgel.oauth
  (:require [hato.client :as hc]))

(def scopes ["me"
             "tournaments:read"
             "tournaments:write"
             "matches:read"
             "matches:write"
             "participants:read"
             "participants:write"
             "attachments:read"
             "attachments:write"
             "communities:manage"])

(defn make-options [tokens] {:headers {"Authorization-Type" "v2"}
                             :oauth-token (:access_token tokens)
                             :content-type "application/vnd.api+json"
                             :accept :json
                             :as :json})

(def client_id (:client_id (clojure.edn/read-string (slurp "secrets.edn"))))
(def client_secret (:client_secret (clojure.edn/read-string (slurp "secrets.edn"))))
(def redirect_uri "https://oauth.pstmn.io/v1/callback")


(comment (spit "token.edn" (-> req :body)))

(defn get-tokens []
  (def tokens (clojure.edn/read-string (slurp "token.edn")))
  (try
    (hc/get "https://api.challonge.com/v2/me.json" (make-options tokens))
    tokens
    (catch clojure.lang.ExceptionInfo e
      (def e e)
      e
      (let [req (hc/post "https://api.challonge.com/oauth/token"
                         {:form-params {:grant_type "refresh_token"
                                        :client_id client_id
                                        :refresh_token (:refresh_token tokens)
                                        :redirect_uri redirect_uri}
                          :as :json})]
        (spit "token.edn" (:body req))
        (clojure.edn/read-string (slurp "token.edn")))))
  tokens)

(def t (hc/get "https://api.challonge.com/v2/me.json" (make-options tokens)))


(comment
  (defn comunity-oauth-url []
    "to be pasted into browser once to give my application access to the tf2 community"
    (str "https://api.challonge.com/oauth/authorize?scope=" (clojure.string/join " " scopes)
         "&client_id=" client_id
         "&redirect_uri=" redirect_uri
         "&response_type=code"
         "&community_id=tf2"))
  (= "https://api.challonge.com/oauth/authorize?scope=me tournaments:read tournaments:write matches:read matches:write participants:read participants:write attachments:read attachments:write communities:manage&client_id=3bd276c270e74d5e90d7482425ee86d68a99898b315379a07432787fca67cfc1&redirect_uri=https://oauth.pstmn.io/v1/callback&response_type=code&community_id=tf2")

  (def code "0aeab7f51bec65badc5087a8631d968762fd646c8010f451480c0dfc8a482f56")
  (defn get-oauth-token []
    (-> (hc/post "https://api.challonge.com/oauth/token"
                 {:form-params {:grant_type "client_credentials"
                                :client_id client_id
                                :client_secret client_secret}
                  :as :json})
        :body))
  
  (defn get-oauth-token-2 [code]
    (-> (hc/post "https://api.challonge.com/oauth/token"
                 {:form-params {:code code
                                :client_id client_id
                                :grant_type "authorization_code"
                                :redirect_uri redirect_uri}
                  :as :json})
        :body))
  
  (def swage (get-oauth-token-2 code))
  (def tokens swage)
  (spit "token.edn" (pr-str tokens)))

;; IDEA: login with just myself. then get browser permission for that account.
;; rn im trying to use the browser permission token to then get another one.. thus 500?



