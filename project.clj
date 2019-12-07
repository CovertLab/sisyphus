(defproject sisyphus "0.0.22"
  :description "Eternally execute tasks"
  :url "http://github.com/CovertLab/sisyphus"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.2"]
                 [lispyclouds/clj-docker-client "0.2.3"]
                 [com.google.cloud/google-cloud-core "1.87.0"]
                 [com.google.cloud/google-cloud-logging "1.87.0"] ; do not exclude io.grpc/grpc-core io.grpc/grpc-api io.grpc/grpc-netty-shaded or else logging will fail to load classes
                 [com.google.cloud/google-cloud-storage "1.87.0"]
                 [com.google.apis/google-api-services-compute "v1-rev214-1.25.0"]
                 [com.google.api-client/google-api-client "1.30.4"] ; needed for cloud storage downloading
                 [com.novemberain/langohr "5.1.0"]
                 [spootnik/kinsky "0.1.22"]]
  :pedantic? false
  :main sisyphus.core)
