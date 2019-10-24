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

(defn terminate?
  [message id]
  (and
   id
   (= id (:id message))
   (= "terminate" (:event message))))

(defn sisyphus-handle-kafka
  "Handle an incoming request via kafka to terminate a task."
  [state topic message]
  (let [id (get-in @(:state state) [:task :id])]
    (try
      (when (terminate? message id)
        (task/kill! state "by request"))
      (catch Exception e
        (log/exception! e "STEP TERMINATION FAILED" id)))))

(defn apoptosis-timer
  "Start a timer to self-destruct this server if it remains idle."
  [delay]
  (task/make-timer delay apoptosis))

(defn run-state!
  [state task]
  (if-let [time (:timer state)]
    (task/cancel-timer time))
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
  "Handle an incoming request from RabbitMQ to run a task (aka step)."
  ; TODO(jerry): To clarify/simplify, don't pass task to perform-task! in
  ; addition to the copy that's in state. Put :docker-id in state rather than in
  ; (:task state) since it's not part of the task spec and having varying copies
  ; asks for trouble.
  ;
  ; TODO(jerry): Document the task message payload
  ;   {
  ;    ; required:
  ;    :id "id" :workflow "w" :name "n" :image "d" :command "c"
  ;    ; optional:
  ;    :inputs [i] :outputs [o] :timeout milliseconds}.
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
        ; TODO: A state map containing a state map is too confusing. The key
        ; names are the only thing we have for navigating the data.
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
