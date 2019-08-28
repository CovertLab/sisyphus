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
   [sisyphus.log :as log]))

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
    (try
      (cloud/delete-tree! [(.getAbsolutePath input)])
      (catch Exception e
        (log/exception! e "couldn't delete" (.getAbsolutePath input))))
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

(def uid_gid
  "The current process' uid:gid numbers. Make this the Docker --user so the
  command will create files with the same ownership and also not run as root
  with too much host access. The only user and group info shared in/out of the
  container are these id numbers. This user and group don't even need to be
  created in the container."
  (str (shell-out "id" "-u") ":" (shell-out "id" "-g")))

(defn perform-task!
  "Given a state containing a connection to both cloud storage and some docker service, 
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container, and then uploading all
   outputs back to storage."
  [{:keys [storage kafka docker config state]} task]
  (try
    (let [root (get-in config [:local :root])
          inputs (find-locals! (str root "/inputs") (:inputs task))
          outputs (find-locals! (str root "/outputs") (:outputs task))

          image (:image task)
          ;; commands (join-commands (:commands task))

          command (or (:command task) (first-command (:commands task)))]

         (status! kafka task "step-start" {})

         (docker/pull! docker image)

         (doseq [input inputs]
           (pull-input! storage input))

         (let [mounted (concat inputs (remove :stdout? outputs))
               mounts (mount-map mounted :local :internal)
               config {:image image
                       ;; TODO: Run the container with the same uid:gid as the
                       ;; host, not as root, so the outputs can be deleted BUT
                       ;; the Theano pip expects to be able to write into its
                       ;; site-packages dir, so we'd have to install it with the
                       ;; same (unpredictable) uid:gid, or maybe initialize it
                       ;; with root and open up "other" access to those dirs.
                       ;;
                       ;; :user uid_gid
                       :mounts mounts
                       :command command}

               id (docker/create! docker config)
               lines (atom [])]

           (log/info! "created container" id config)
           (status! kafka task "container-create" {:docker-id id :docker-config config})
           (swap! state assoc-in [:task :docker-id] id)

           (status! kafka task "execution-start" {:docker-id id})
           (docker/start! docker id)

           (doseq [line (docker/logs docker id)]
             (swap! lines conj line)
             (log/info! line)) ; TODO(jerry): Detect stack tracebacks heuristically;

           (status!
            kafka task "execution-complete"
            {:docker-id id
             :status (.toString (docker/info docker id))})

           (let [code (docker/exit-code (docker/info docker id))]
             (log/log! (if (zero? code) log/notice log/error) "container exit code" code)
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

             (if (zero? code)
               (do
                 (log/notice! "STEP COMPLETED" (:workflow task) (:name task) task)
                 (status!
                  kafka task "step-complete" {}))

               (let [log (take-last 100 @lines)]
                 (log/notice! "STEP FAILED" (:workflow task) (:name task) log)
                 (status!
                  kafka task "step-error"
                  {:code code
                   :log log}))))))

    (catch Exception e
      (log/exception! e "STEP FAILED")
      (exception! kafka task "task-error" {:message (.getMessage e)} e))))
