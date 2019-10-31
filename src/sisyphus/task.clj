(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [cheshire.core :as json]
   [sisyphus.archive :as archive]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.kafka :as kafka]
   [sisyphus.log :as log])
  (:import
    [java.time Duration]
    [com.spotify.docker.client.exceptions DockerException]))

(defn format-duration
  "Format a java.time.Duration as a string like '1H26M12.345S'."
  [^Duration duration]
  (.substring (str duration) 2)) ; strip "PT" from the "PTnHnMnS" format

(def full-name
  "Extract the string representation of the given keyword. This is an extension to the
   built-in `name` function which fails to return the full string representation for
   namespaced keywords."
  (comp str symbol))

(defn split-key
  [key]
  (let [full-key (full-name key)
        colon (.indexOf full-key ":")]
    [(.substring full-key 0 colon) (.substring full-key (inc colon))]))

(def stdout-tokens
  #{">" "STDOUT"})

(defn delete-local-trees!
  "Delete the local file trees given their path strings. Log errors but proceed."
  [& paths]
  (doseq [path paths]
   (try
     (cloud/delete-tree! [path])
     (catch java.io.IOException e
       (log/exception! e "couldn't delete local" path)))))

(defn find-local!
  "Make the named input or output file or directory so mounting it in
  Docker will work right, e.g. it won't assume it's going to be a directory."
  [root [remote internal]]
  (let [[bucket key] (split-key remote)
        input (io/file root key)
        local (.getAbsolutePath input)
        archive (str local ".tar.gz")
        stdout? (stdout-tokens internal)
        directory? (cloud/is-directory-path? internal)
        intern (cloud/trim-slash internal)]
    (if directory?
      (.mkdirs input)
      (let [base (io/file (.getParent input))]
        (.mkdirs base)
        (.createNewFile input)))
    {:bucket bucket
     :key key
     :archive archive
     :local local
     :stdout? stdout?
     :directory? directory?
     :internal intern}))

(defn find-locals!
  [root locals]
  (mapv (partial find-local! root) locals))

(defn pull-input!
  [storage {:keys [bucket key archive local directory?]}]
  (log/info! "downloading" local "from" (str bucket ":" key))
  (if directory?
    (cloud/download-tree! storage bucket key local)
    ;; (do
    ;;   (cloud/download! storage bucket key archive)
    ;;   (archive/unpack! archive local))
    (cloud/download! storage bucket key local)))

(defn push-output!
  [storage {:keys [local directory? archive bucket key]}]
  (log/info! "uploading" local "to" (str bucket ":" key))
  (if directory?
    (cloud/upload-tree! storage bucket key local)
    ;; (do
    ;;   (archive/pack! archive local)
    ;;   (cloud/upload! storage bucket key archive))
    (cloud/upload! storage bucket key local)))

(defn redirect-stdout
  "Translate the tokens of a command into a command that redirects stdout to the given file `stdout`"
  [tokens stdout]
  ["sh" "-c" (string/join " " (concat tokens [">" stdout]))])

(defn load-task
  "Load a task specification from the given path."
  [path]
  (json/parse-string (slurp path) true))

(defn join-commands
  [commands]
  (let [series
        (for [command commands]
          (let [stdout (:stdout command)
                tokens (:command command)
                passage (string/join
                         " "
                         (if stdout
                           (concat tokens [">" stdout])
                           tokens))]
            passage))]
    ["sh" "-c" (string/join " && " series)]))

(defn first-command
  [commands]
  (:command (first commands)))

(defn mount-map
  [locals from to]
  (into
   {}
   (map
    (fn [mapping]
      [(get mapping from)
       (get mapping to)])
    locals)))

(defn send!
  ([kafka task status message]
   (send! kafka task message :status-topic))
  ([kafka task status message topic]
   (kafka/send!
    (get kafka :producer)
    (get-in kafka [:config topic])
    (merge
     {:id (:id task)
      :workflow (:workflow task)
      :task (:name task)
      :event status}
     message))))

(defn status!
  [kafka task status message]
  (send! kafka task status message :status-topic))

(defn exception!
  [kafka task status message throwable]
  (send!
   kafka task status
   (assoc message :exception (.toString throwable)) :status-topic))

(defn shell-out
  [& tokens]
  "Shell out for a single line of text."
  (.trim (:out (apply shell/sh tokens))))

(def uid-gid
  "The current process' uid:gid numbers. Pass this to Docker --user so the
  command will create files with the same ownership and also not run as root
  with too much host access. The only user and group info shared in/out of the
  container are these id numbers. This user and group don't even need to be
  created in the container."
  (str (shell-out "id" "-u") ":" (shell-out "id" "-g")))

(defn- report-task-completion
  "Report task completion to Gaia and log it."
  [task kafka success? lines]
  (if success?
    (do
      (log/notice! "STEP COMPLETED" (:workflow task) (:name task) task)
      (status! kafka task "step-complete" {}))

    (let [log (take-last 100 @lines)]
      (log/error! "STEP FAILED" (:workflow task) (:name task) log)
      (status!
       kafka task "step-error"
       {:log log}))))

(defn make-timer
  "Make a `future` as a timer that waits then calls `f`. Cancelling it can stop
  the timer but won't interrupt `f` midway (which could be bad for docker/stop!
  or apoptosis) since it runs `f` in an independent thread."
  [milliseconds f]
  (future
    (Thread/sleep milliseconds)
    (future (f))))

(defn cancel-timer
  "Cancel a timer if possible. nil safe. Return true if this cancelled it."
  [timer]
  (and timer (future-cancel timer)))


; [FSM] The task :status normally goes through:
;   :starting   # pulling the docker image & input files and starting a container
;   :running    # running the task's command process in the container
;   :completed  # finished the command; pushing the output files if successful
;   :done
;
; on a "terminate" request from Gaia while starting:
;   :starting
;   :terminate-when-ready  # ready to terminate it after starting the container
;   :terminated-by-request
;   :done
;
; on a "terminate" request while running:
;   :running
;   :terminated-by-request
;   :done
;
; on a timeout while running:
;   :running
;   :terminated-by-timeout
;   :done


(defn- terminating-state
  "Return the new state-map for an attempted state transition from :running
  to/towards a termination status. If the task id matches, then status goes
  :starting -> :terminate-when-ready, or :running -> terminating-status, or no
  change. A transition to :action = :kill means it's time to kill the process;
  another value is the termination status when the process is ready to kill."
  [state-map task-id termination-status]
  (cond
    (not (and task-id (= task-id (:id (:task state-map)))))
    state-map

    (= (:status state-map) :starting)
    (assoc state-map :status :terminate-when-ready :action termination-status)

    (= (:status state-map) :running)
    (assoc state-map :status termination-status :action :kill)

    :else
    state-map))

(defn- terminate!
  "Terminate the task's docker process if it's running; setting status to track
  how it finished and prevent double-termination. Call this in a forked thread.
  (perform-task! will finish up soon.)"
  [{:keys [docker kafka state]} task-id termination-status]
  (let [[old new] (swap-vals! state terminating-state task-id termination-status)
        task (:task new)
        docker-id (:docker-id new)]
    (when (and (= (:action new) :kill) (not= (:action old) :kill))
      (log/debug! "terminating step..." termination-status)
      (docker/stop! docker docker-id)
      (log/notice! "STEP TERMINATED" termination-status)
      (status! kafka task "step-terminated" {:reason termination-status})
      true)))

(defn- terminate-by-timeout!
  [sisy-state task-id]
  (terminate! sisy-state task-id :terminated-by-timeout))

(defn terminate-by-request!
  [sisy-state task-id]
  (terminate! sisy-state task-id :terminated-by-request))

(def default-timeout-seconds (* 60 60))

(defn- task-timeout-timer
  "Start a task-timeout timer for the given docker-id."
  [milliseconds sisy-state task-id]
  (make-timer milliseconds #(terminate-by-timeout! sisy-state task-id)))

(defn- running-state
  "Transition :starting to :running or :terminate-when-ready to termination."
  [{:keys [status action] :as state-map}]
  (assoc state-map
         :status
         (case status
           :starting :running
           :terminate-when-ready action
           :unexpected-state)  ; throw an exception?

         :action
         nil))

(defn- run-command!
  "Run the command in the ready docker container, with a timeout, and append the
  log lines. Return a note to log with completion code (:completed = good),
  elapsed time, and the timeout parameter (to debug timeouts)."
  [{:keys [kafka docker state] :as sisy-state} task lines docker-id]
  (let [timeout-millis (* (or (:timeout task) default-timeout-seconds) 1000)
        timer (task-timeout-timer timeout-millis sisy-state (:id task))
        start-nanos (System/nanoTime)]
    (docker/start! docker docker-id)

    ; Run it, ending up in state :completed or :terminated-by-...; unless
    ; already :terminate-when-ready then go straight to :terminated-by-...
    (let [state-map (swap! state running-state)]
      (if (= (:status state-map) :running)
        (do
          (doseq [line (docker/logs docker docker-id)]
            (swap! lines conj line)
            (log/info! line))
          (swap! state update :status #(if (= % :running) :completed %))))

      (let [end-nanos (System/nanoTime)
            _ (cancel-timer timer)
            elapsed-duration (Duration/ofNanos (- end-nanos start-nanos))
            timeout-duration (Duration/ofMillis timeout-millis)]
        (str "PROCESS " (full-name (:status @state))  ; completion reason
             ", elapsed " (format-duration elapsed-duration)
             ", timeout parameter " (format-duration timeout-duration))))))

(defn perform-task!
  "Given sisy-state containing connections to cloud storage and a docker service,
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container image, then uploading
   outputs to storage.

   Run the container with the host's uid:gid, not as root, so the app has limited
   permissions on the host and its outputs can be deleted.
   ASSUMES: Every directory the app writes to within the container is world-writeable."
  [{:keys [storage kafka docker config state] :as sisy-state} task]
  (try
    ; Defer any termination requests until there's a docker process to kill.
    (swap! state assoc :status :starting :docker-id nil :action nil)

    (let [root (get-in config [:local :root])
          input-root (str root "/inputs")
          output-root (str root "/outputs")
          inputs (find-locals! input-root (:inputs task))
          outputs (find-locals! output-root (:outputs task))
          image (:image task)]

      (try
        (status! kafka task "step-start" {})
        (docker/pull! docker image)

        (doseq [input inputs]
          (pull-input! storage input))

        (let [mounted (concat inputs (remove :stdout? outputs))
              mounts (mount-map mounted :local :internal)
              config {:image image
                      :user uid-gid
                      :mounts mounts
                      :command (:command task)}
              lines (atom [])
              id (docker/create! docker config)]
          (swap! state assoc :docker-id id)

          (try
            (log/info! "created container" id config)
            (status! kafka task "container-create" {:docker-id id :docker-config config})
            (status! kafka task "execution-start" {:docker-id id})

            (let [note (run-command! sisy-state task lines id)
                  status (:status @state)
                  info (docker/info docker id)
                  code (docker/exit-code info)
                  error-string (docker/error-string info)
                  oom-killed? (docker/oom-killed? info)
                  success? (and (= status :completed)
                                (zero? code)
                                ; (zero? (count error-string))  ; use this?
                                (not oom-killed?))
                  message (str note
                               ", exit code " code
                               (if (= code 137) " (SIGKILL)" "")
                               ", error string \"" error-string "\""
                               (if oom-killed? "; got out-of-memory (OOM) error" ""))]
              (log/log! (if success? log/info log/error) message)
              (status! kafka task "container-exit" {:docker-id id :code code})

              ; push the outputs to storage if the task succeeded; push stderr even on error
              ; TODO(jerry): Unconditionally push stdout+stderr+message to a log dir.
              ;   Conditionally push stdout+stderr like other requested outputs.
              (doseq [output outputs]
                (if (:stdout? output)
                  (let [stdout (string/join "\n" @lines)]
                    (spit (:local output) stdout)))

                (when (or success? (:stdout? output))
                  (push-output! storage output)

                  (status!
                   kafka task "data-complete"
                   {:workflow (:workflow task)
                    :path (:key output)
                    :key (str (:bucket output) ":" (:key output))})))

              (report-task-completion task kafka success? lines))

            (finally
              (swap! state assoc :status :done :docker-id nil :action nil)
              (try
                (docker/remove! docker id true)
                (catch DockerException e
                  (log/exception! e "docker/remove! failed"))))))
        (finally
          (delete-local-trees! input-root output-root))))

    (catch Exception e
      (log/exception! e "STEP FAILED")
      (exception! kafka task "task-error" {:message (.getMessage e)} e))))
