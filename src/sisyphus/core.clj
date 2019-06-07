(ns sisyphus.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [cheshire.core :as json]
   [taoensso.timbre :as log]
   [langohr.basic :as langohr]
   [sisyphus.archive :as archive]
   [sisyphus.kafka :as kafka]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.task :as task]
   [sisyphus.rabbit :as rabbit]))

(defn terminate?
  [message id]
  (and
   id
   (= id (:id message))
   (= :event "terminate")))

(defn sisyphus-handle-kafka
  [state producer topic message]
  (let [task (:task @(:state state))
        {:keys [id docker-id]}
        (select-keys task [:id :docker-id])]
    (when (terminate? message id)
      (docker/kill! (:docker state) docker-id)
      (swap! (:state state) assoc :status :waiting :task {})
      (kafka/send!
       producer
       (get-in state [:config :kafka :status-topic])
       {:id id
        :task task
        :status "killed"
        :by message}))))

(defn sisyphus-handle-rabbit
  "Handle an incoming task message by performing the task it represents."
  [state channel metadata ^bytes payload]
  (let [raw (String. payload "UTF-8")
        task (json/parse-string raw true)]
    (println "performing task" task)
    (try
      (do
        (swap! state assoc :task task)
        (task/perform-task! state task)
        (langohr/ack channel (:delivery-tag metadata))
        (println "task complete!"))
      (catch Exception e (.printStackTrace e)))))

(defn connect!
  [config]
  (let [docker (docker/connect! (:docker config))
        storage (cloud/connect-storage! (:storage config))
        rabbit (rabbit/connect! (:rabbit config))
        state {:config config
               :docker docker
               :storage storage
               :rabbit rabbit
               :state
               (atom
                {:status :waiting
                 :task {}})}
        kafka-config (assoc
                      (:kafka config)
                      :handle-message
                      (partial sisyphus-handle-kafka state))
        kafka (kafka/boot-kafka state kafka-config)]
    (assoc state :kafka kafka)))

(defn start!
  "Start the system by making all the required connections and returning the state map."
  [config]
  (let [state (connect! config)]
    (rabbit/start-consumer! (:rabbit state) (partial sisyphus-handle-rabbit state))
    state))

(defn make-config
  []
  {:kafka
   {:status-topic "sisyphus-status"
    :log-topic "sisyphus-log"}
   :local
   {:root "/tmp/sisyphus"}})

(defn read-path
  [path]
  (edn/read-string
   (slurp path)))

(defn -main
  [& args]
  (try
    (println "sisyphus rises....")
    (let [path "resources/config/sisyphus.clj"
          config (read-path path)
          state (start! config)
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (rabbit/close! (:rabbit state))
                     (println "sisyphus rests")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
