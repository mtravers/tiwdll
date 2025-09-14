(defproject social-abm "0.1.0-SNAPSHOT"
  :description "Agent-based modeling framework for social systems"
  :url "https://github.com/user/social-abm"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]]
  :main ^:skip-aot social-abm.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             :dev {:dependencies [[org.clojure/tools.namespace "1.5.0"]
                                  [org.clojure/test.check "1.1.1"]]}})