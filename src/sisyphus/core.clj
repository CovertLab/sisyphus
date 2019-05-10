(ns sisyphus.core
  (:require
   [cheshire.core :as json]
   [taoensso.timbre :as log]
   [langohr.basic :as langohr]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.task :as task]
   [sisyphus.rabbit :as rabbit]))

(defn sisyphus-handle-message
  [state channel metadata ^bytes payload]
  (let [raw (String. payload "UTF-8")
        task (json/parse-string raw true)]
    (println "performing task" task)
    (task/perform-task! state task)
    (langohr/ack channel (:delivery-tag metadata))
    (println "task complete!")))

(defn start
  [config]
  (let [docker (docker/connect! (:docker config))
        storage (cloud/connect! (:storage config))
        rabbit (rabbit/connect! (:rabbit config))
        state {:docker docker :storage storage :rabbit rabbit :config config}]
    (rabbit/start-consumer! rabbit (partial sisyphus-handle-message state))
    state))

(defn -main
  [& args]
  (try
    (println "sisyphus rises....")
    (let [state (start {:local {:root "/tmp/sisyphus"}})
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (rabbit/close! (:rabbit state))
                     (println "sisyphus rests")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
