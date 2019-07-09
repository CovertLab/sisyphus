(ns sisyphus.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.shell :as sh]
   [cheshire.core :as json]
   [langohr.basic :as langohr]
   [clj-http.client :as http]
   [sisyphus.archive :as archive]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.task :as task]
   [sisyphus.rabbit :as rabbit]))

(def apoptosis-interval 300000)
(def wait-interval (* 10 apoptosis-interval))

(defn signature
  []
  (try
    (:body
     (http/get
      "http://metadata.google.internal/computeMetadata/v1/instance/name"
      {:headers
       {:metadata-flavor "Google"}}))
    (catch Exception e "local")))

(defn apoptosis
  []
  (try
    (let [self (signature)]
      (log/info! self "terminating")
      (log/info!
       (sh/sh
        "/snap/bin/gcloud"
        "--quiet"
        "compute"
        "instances"
        "delete"
        self
        "--zone"
        "us-west1-b"))
      (System/exit 0))
    (catch Exception e
      (log/exception! "terminating" e))))

(defn timer
  [wait f]
  (future
    (Thread/sleep wait)
    (f)))

(defn terminate?
  [message id]
  (and
   id
   (= id (:id message))
   (= :event "terminate")))

(defn sisyphus-handle-kafka
  [state topic message]
  (let [task (:task @(:state state))
        {:keys [id docker-id]}
        (select-keys task [:id :docker-id])]
    (when (terminate? message id)
      (docker/kill! (:docker state) docker-id)
      (task/status! (:kafka state) (:task @state) "kill" task)
      (swap! (:state state) assoc :status :waiting :task {})
      (kafka/send!
       (get-in state [:kafka :producer])
       (get-in state [:config :kafka :status-topic])
       {:id id
        :task task
        :status "killed"
        :by message}))))

(defn apoptosis-timer
  [delay]
  (timer delay apoptosis))

(defn run-state!
  [state task]
  (if-let [time (:timer state)]
    (future-cancel time))
  (assoc
   state
   :task task
   :status :running))

(defn reset-state!
  [state config]
  (assoc
   state
   :timer (apoptosis-timer (get-in config [:timer :delay] apoptosis-interval))
   :task {}
   :status :waiting))

(defn sisyphus-handle-rabbit
  "Handle an incoming task message by performing the task it represents."
  [state channel metadata ^bytes payload]
  (try
    (let [raw (String. payload "UTF-8")
          task (json/parse-string raw true)]
      (log/warn! "starting-task" task)
      (do
        (swap! (:state state) run-state! task)
        (task/perform-task! state task)
        (log/warn! "task-complete" task)
        (langohr/ack channel (:delivery-tag metadata))
        (swap! (:state state) reset-state! (:config state))))
    (catch Exception e
      (log/exception! "rabbit-task" e))))

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
                 :task {}
                 :timer (apoptosis-timer
                         (get-in config [:timer :initial] wait-interval))})}
        handle (partial sisyphus-handle-kafka state)
        kafka (kafka/boot-kafka (:kafka config) handle)]
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
   :timer
   {:initial wait-interval
    :delay apoptosis-interval}
   :local
   {:root "/tmp/sisyphus"}})

(defn read-path
  [path]
  (edn/read-string
   (slurp path)))

(defn -main
  [& args]
  (try
    (log/fine! "sisyphus rises....")
    (let [path "resources/config/sisyphus.clj"
          config (read-path path)
          state (start! config)]
      @(promise))))
