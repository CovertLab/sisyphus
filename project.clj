(defproject sisyphus "0.0.1"
  :description "Eternally execute tasks"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.taoensso/timbre "4.8.0"]
                 [cheshire "5.7.1"]
                 [com.novemberain/langohr "5.1.0"]
                 [com.google.cloud/google-cloud-storage "1.70.0"]
                 [com.spotify/docker-client "8.15.2"]
                 [spootnik/kinsky "0.1.22"]]
  :main sisyphus.core)
