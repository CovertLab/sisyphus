(ns sisyphus.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.shell :as sh]
   [clojure.tools.cli :as cli]
   [cheshire.core :as json]
   [langohr.basic :as langohr]
   [sisyphus.base :as base]
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

(defn- terminate?
  [message]
  (and
   (:id message)
   (= "terminate" (:event message))))

(defn sisyphus-handle-kafka
  "Handle an incoming request via kafka to terminate a task."
  [state topic message]
  (try
    (let [id (:id message)]
      (when (terminate? message)
        (task/terminate-by-request! state id)))
    (catch Exception e
      (log/exception! e "HANDLE KAFKA FAILED" message))))

(defn apoptosis-timer
  "Start a timer to self-destruct this server if it remains idle."
  [delay]
  (base/make-timer delay apoptosis))

(defn- run-state!
  "Return the state-map for starting to run a task."
  ; NOTE: The doc for swap! says "f may be called multiple times, and thus
  ; should be free of side effects." Is an idempotent function OK?
  [state-map task]
  (base/cancel-timer (:timer state-map))
  (assoc
   state-map
   :task task
   :timer nil
   :status :starting))

(defn- reset-state!
  "Return the state-map for idling after running a task."
  ; NOTE: The doc for swap! says "f may be called multiple times, and thus
  ; should be free of side effects." Is an idempotent function OK?
  [state-map config]
  (base/cancel-timer (:timer state-map))
  (assoc
   state-map
   :task {}
   :timer (apoptosis-timer (get-in config [:timer :delay] apoptosis-interval))
   :status :waiting))

(defn task-tag
  [task]
  (let [workflow-name (:workflow task "no-workflow")
        task-name (:name task "no-name")]
    (str log/gce-instance-name "." workflow-name "." task-name)))

(defn sisyphus-handle-rabbit
  "Handle an incoming request from RabbitMQ to run a task (aka step)."
  ; TODO(jerry): Don't pass task to perform-task! in addition to the copy
  ; that's in state.
  ;
  ; TODO(jerry): Document the task spec message payload
  ;   {
  ;    ; required:
  ;    :id "id", :workflow "w", :name "n", :image "d", :command "c",
  ;    ; optional:
  ;    :inputs [i], :outputs [o], :timeout seconds}.
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
                {:task {}
                 :timer (apoptosis-timer
                         (get-in config [:timer :initial] wait-interval))
                 :status :waiting})}
        producer (kafka/boot-producer (:kafka config))
        state (assoc state :kafka {:producer producer :config (:kafka config)})
        handle (partial sisyphus-handle-kafka state)
        consumer (kafka/boot-consumer (:kafka config) handle)]
    (update state :kafka merge consumer)))

(defn start!
  "Start the system by making all the required connections and returning the state map.
   The priority of values for rabbit config are
     1. command line options
     2. gce metadata fields
     3. values from file resources/config/sisyphus.clj
     4. defaults from ns rabbit/default-config"
  [config]
  (let [metadata (rabbit/rabbit-metadata)
        rabbit-config
        (merge
         rabbit/default-config
         (:rabbit config)
         metadata
         (:args config))
        config (assoc config :rabbit rabbit-config)
        state (connect! config)]
    (log/debug! "rabbit config:" rabbit-config)
    (rabbit/start-consumer!
     (:rabbit state)
     (partial sisyphus-handle-rabbit state))
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

(def parse-options
  [["-w" "--workflow WORKFLOW" "which workflow this worker is assigned to"]])

(defn -main
  [& args]
  (try
    (log/info! "sisyphus worker rises:" log/gce-instance-name)
    (let [options (:options (cli/parse-opts args parse-options))
          rabbit-options
          (when-let [workflow (:workflow options)]
            (rabbit/rabbit-metadata workflow))
          path "resources/config/sisyphus.clj"
          config (read-path path)
          config (assoc config :args rabbit-options)
          state (start! config)]
      @(promise))))
