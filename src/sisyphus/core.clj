(ns sisyphus.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.shell :as sh]
   [cheshire.core :as json]
   [langohr.basic :as langohr]
   [sisyphus.archive :as archive]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.task :as task]
   [sisyphus.rabbit :as rabbit]))

(def apoptosis-interval 300000)
(def wait-interval (* 10 apoptosis-interval))

(defn apoptosis
  []
  (try
    (let [self log/gce-instance-name]
      (log/notice! "sisyphus worker shutting down:" self)
      (try
        (sh/sh
         "/snap/bin/gcloud"
         "--quiet"
         "compute"
         "instances"
         "delete"
         self
         "--zone"
         log/gce-zone)
        (catch InterruptedException e
          (println "in gcloud delete" (str e))))
      (System/exit 0))
    (catch Exception e
      (log/exception! e "exception while shutting down"))))

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
   (= "terminate" (:event message))))

(defn sisyphus-handle-kafka
  "Handle an incoming kafka message that might ask to terminate the task."
  [state topic message]
  (let [task (:task @(:state state))
        {:keys [id docker-id]} task]
    (try
      (when (terminate? message id)
        (log/debug! "terminating step by request" id)
        (docker/stop! (:docker state) docker-id)
        (log/notice! "STEP TERMINATED BY REQUEST")
        (task/status! (:kafka state) task "step-terminated" message)
        (swap! (:state state) assoc :status :waiting :task {}))
      (catch Exception e
        (log/exception! e "STEP TERMINATION FAILED" id)))))

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

(defn task-tag
  [task]
  (let [workflow-name (:workflow task "no-workflow")
        task-name (:name task "no-name")]
    (str log/gce-instance-name "." workflow-name "." task-name)))

(defn sisyphus-handle-rabbit
  "Handle an incoming task message by running the requested step."
  [state raw]
  (let [task (json/parse-string raw true)
        tag (task-tag task)]
    (log/tag
     tag
     (fn []
       (log/notice! "STARTING STEP" tag task)
       (swap! (:state state) run-state! task)
       (task/perform-task! state task)
       (swap! (:state state) reset-state! (:config state))))))

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
        producer (kafka/boot-producer (:kafka config))
        state (assoc state :kafka {:producer producer :config (:kafka config)})
        handle (partial sisyphus-handle-kafka state)
        consumer (kafka/boot-consumer (:kafka config) handle)]
    (update state :kafka merge consumer)))

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
    (log/info! "sisyphus worker rises:" log/gce-instance-name)
    (let [path "resources/config/sisyphus.clj"
          config (read-path path)
          state (start! config)]
      @(promise))))
