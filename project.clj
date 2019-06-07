(defproject sisyphus "0.0.1"
  :description "Eternally execute tasks"
  :url "http://github.com/CovertLab/sisyphus"
  :license {:name "MIT License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.taoensso/timbre "4.8.0"]
                 [lispyclouds/clj-docker-client "0.2.3"]
                 [com.novemberain/langohr "5.1.0"]
                 [com.google.cloud/google-cloud-storage "1.70.0"]
                 [spootnik/kinsky "0.1.22"]]
  :main sisyphus.core)
