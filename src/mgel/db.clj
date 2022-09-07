(ns mgel.db
  (:require [clojure.java.io :as io]
            [xtdb.api :as xt]))

(defn start-xtdb! []
  (letfn [(kv-store [dir]
            {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                        :db-dir (io/file dir)
                        :sync? true}})]
    (xt/start-node
     (or {} {:xtdb/tx-log (kv-store "data/dev/tx-log")
             :xtdb/document-store (kv-store "data/dev/doc-store")
             :xtdb/index-store (kv-store "data/dev/index-store")}))))


(def node (start-xtdb!))


(defn stop-xtdb! []
  (.close node))

