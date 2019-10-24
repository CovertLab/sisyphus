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
  [task kafka code lines]
  (if (zero? code)
    (do
      (log/notice! "STEP COMPLETED" (:workflow task) (:name task) task)
      (status! kafka task "step-complete" {}))

    (let [log (take-last 100 @lines)]
      (log/notice! "STEP FAILED" (:workflow task) (:name task) log)
      (status!
       kafka task "step-error"
       {:code code
        :log log}))))

(defn make-timer
  "Make a `future` as a timer that calls `f` in another thread. Cancelling it
  can stop the timer but won't interrupt `f` midway."
  [milliseconds f]
  (future
    (Thread/sleep milliseconds)
    (future (f))))

(defn cancel-timer
  "Cancel the given timer, if possible."
  [timer]
  (future-cancel timer))

(defn kill!
  "Kill the task's docker process and clear the state's docker-id to record that
  it got killed and to prevent re-kill. (perform-task! will finish up soon.)"
  ([{:keys [state] :as sisy-state} reason]
   (kill! sisy-state reason (:docker-id (:task @state))))
  ([{:keys [docker kafka state]} reason docker-id]
   (let [task (:task @state)
         current-docker-id (:docker-id task)]
     (when (and current-docker-id (= current-docker-id docker-id))
       (log/debug! "terminating step" reason)
       (docker/stop! docker docker-id)
       (swap! state assoc-in [:task :docker-id] nil)  ; prevent double-kill
       (log/notice! "STEP TERMINATED" reason)
       (status! kafka task "step-terminated" {:reason reason})))))

(def default-timeout-millis (* 1000 60 60))

(defn- task-timeout-timer
  "Start a task-timeout timer for the given docker-id."
  [milliseconds docker-id sisy-state]
  (make-timer milliseconds #(kill! sisy-state "task timeout" docker-id)))

(defn- run-command!
  "Run the command inside the ready docker container, with a timeout, and append
  all the log lines plus an elapsed time measurement."
  [{:keys [kafka docker state] :as sisy-state} lines docker-id]
  (let [task (:task @state)
        timeout-millis (:timeout task default-timeout-millis)]
    (status! kafka task "execution-start" {:docker-id docker-id})

    (let [timer (task-timeout-timer timeout-millis docker-id sisy-state)
          start-nanos (System/nanoTime)]
      (docker/start! docker docker-id)

      (doseq [line (docker/logs docker docker-id)]
        (swap! lines conj line)
        (log/info! line)) ; TODO(jerry): Detect stack tracebacks heuristically

      (cancel-timer timer)

      (let [end-nanos (System/nanoTime)
            elapsed-duration (Duration/ofNanos (- end-nanos start-nanos))
            timeout-duration (Duration/ofMillis timeout-millis)
            cancelled? (not (get-in @state [:task :docker-id]))
            note (str "ELAPSED " (format-duration elapsed-duration)
                      (if cancelled? "; CANCELLED by the " " out of the ")
                      (format-duration timeout-duration) " timeout")]
        (swap! lines conj "" note)))))

(defn perform-task!
  "Given a state containing a connection to both cloud storage and some docker service, 
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container, and then uploading all
   outputs back to storage.

   Run the container with the host's uid:gid, not as root, so the app has limited
   permissions on the host and its outputs can be deleted.
   ASSUMES: Every directory the app writes to within the container is world-writeable."
  [{:keys [storage kafka docker config state] :as sisy-state} task]
  (try
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

          (try
            (swap! state assoc-in [:task :docker-id] id)
            (log/info! "created container" id config)
            (status! kafka task "container-create" {:docker-id id :docker-config config})

            (run-command! sisy-state lines id)

            (let [code (docker/exit-code (docker/info docker id))]
              (log/log! (if (zero? code) log/debug log/error) "container exit code" code)
              (status! kafka task "container-exit" {:docker-id id :code code})

              ; push the outputs to storage if the task succeeded; push stderr even on error
              (doseq [output outputs]
                (if (:stdout? output)
                  (let [stdout (string/join "\n" @lines)]
                    (spit (:local output) stdout)))

                (when (or (zero? code) (:stdout? output))
                  (push-output! storage output)

                  (status!
                   kafka task "data-complete"
                   {:workflow (:workflow task)
                    :path (:key output)
                    :key (str (:bucket output) ":" (:key output))})))

              (report-task-completion task kafka code lines))

            (finally
              (swap! state assoc-in [:task :docker-id] nil)
              (try
                (docker/remove! docker id true)
                (catch DockerException e
                  (log/exception! e "docker/remove! failed"))))))
        (finally
          (delete-local-trees! input-root output-root))))

    (catch Exception e
      (log/exception! e "STEP FAILED")
      (exception! kafka task "task-error" {:message (.getMessage e)} e))))
