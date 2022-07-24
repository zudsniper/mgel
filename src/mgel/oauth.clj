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

(comment (defn comunity-oauth-url []
           "to be pasted into browser once to give my application access to the tf2 community"
           (str "https://api.challonge.com/oauth/authorize?scope=" (str/join " " scopes)
                "&client_id=" client_id
                "&redirect_uri=" "https://oauth.pstmn.io/v1/callback"
                "&response_type=code"
                "&community_id=tf2"))
         
         (defn get-oauth-token-2 [code]
           (-> (hc/post "https://api.challonge.com/oauth/token"
                        {:form-params {:grant_type "authorization_code"
                                       :client_id client_id
                                       :code code
                                       :redirect_uri "https://oauth.pstmn.io/v1/callback"}
                         :oauth-token token
                         :as :json})
               :body))
         (def tokens (get-oauth-token-2 code))
         (def code "0a5d244c585d586b1074b2c644864c51df557861e3bc3adf73b0e185b9db9beb"))

