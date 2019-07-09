(defproject sisyphus "0.0.5"
  :description "Eternally execute tasks"
  :url "http://github.com/CovertLab/sisyphus"
  :license {:name "MIT License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [lispyclouds/clj-docker-client "0.2.3"]
                 [com.google.cloud/google-cloud-logging "1.79.0" :exclusions [io.grpc/grpc-netty-shaded io.grpc/grpc-core io.grpc/grpc-api]]
                 [com.google.cloud/google-cloud-storage "1.74.0"] ; TODO: update
                 [com.novemberain/langohr "5.1.0"]
                 [spootnik/kinsky "0.1.22"]]
  :main sisyphus.core)
