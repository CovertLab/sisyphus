(defproject sisyphus "0.0.15"
  :description "Eternally execute tasks"
  :url "http://github.com/CovertLab/sisyphus"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [lispyclouds/clj-docker-client "0.2.3"]
                 [com.google.cloud/google-cloud-core "1.87.0"]
                 [com.google.cloud/google-cloud-logging "1.87.0"] ; do not exclude io.grpc/grpc-core io.grpc/grpc-api io.grpc/grpc-netty-shaded or else logging will fail to load classes
                 [com.google.cloud/google-cloud-storage "1.87.0"]
                 [com.novemberain/langohr "5.1.0"]
                 [spootnik/kinsky "0.1.22"]]
  :pedantic? false
  :main sisyphus.core)
