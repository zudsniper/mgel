(defproject mgel "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [hato "0.8.2"]
                 [cheshire "5.11.0"]
                 [babashka/babashka.pods "0.1.0"]
                 [babashka/fs "0.1.6"]
                 [babashka/process "0.1.7"]
                 [com.cognitect/transit-clj "1.0.329"]
                 [djblue/portal "0.28.0"]]
  
  :repl-options {:init-ns mgel.core})
