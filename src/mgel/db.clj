(ns mgel.db
  (:require [clojure.java.io :as io]
            [xtdb.api :as xt]
            [xtdb-inspector.core :refer [inspector-handler]]
            [org.httpkit.server :as http-kit]))

(defn start-xtdb! []
  (letfn [(kv-store [dir]
            {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                        :db-dir (io/file dir)
                        :sync? true}})]
    (xt/start-node
     (or {:xtdb-inspector.metrics/reporter {}}
         {:xtdb/tx-log (kv-store "data/dev/tx-log")
          :xtdb/document-store (kv-store "data/dev/doc-store")
          :xtdb/index-store (kv-store "data/dev/index-store")}))))

(def node (start-xtdb!))

(defn stop-xtdb! []
  (.close node))


(defonce server (atom nil))

(defn stop-server []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))

(defn start-server []
  (reset! server (http-kit/run-server (inspector-handler node)
                                      {:port 3001 :join? false})))

(comment (start-server))



