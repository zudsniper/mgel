(defproject mgel "0.1.0-SNAPSHOT"
  :description "MGE with live, multibracket tournament tracking via mge.tf web viewer!"
  :url "https://help.mge.tf/"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [hato "0.8.2"]
                 [cheshire "5.11.0"]
                 [babashka/babashka.pods "0.1.0"]
                 [babashka/fs "0.1.6"]
                 [babashka/process "0.1.7"]
                 [com.cognitect/transit-clj "1.0.329"]
                 [medley "1.4.0"]
                 [com.xtdb/xtdb-core "1.21.0"]
                 [com.xtdb/xtdb-rocksdb "1.21.0"]
                 [djblue/portal "0.28.0"]
                 
                 [tatut/xtdb-inspector "6a60f0e04ad0ea3921422f757217a29601146e35"]
                 [http-kit "2.6.0"]
                 
                 [metosin/tilakone "0.0.4"]]
  
  
  :plugins [[reifyhealth/lein-git-down "0.4.1"]]
  :repositories [["public-github" {:url "git://github.com"}]]
  :repl-options {:init-ns mgel.core})
