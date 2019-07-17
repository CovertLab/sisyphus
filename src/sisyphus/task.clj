(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
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

(defn find-local!
  [root [remote internal]]
  (let [[bucket key] (split-key remote)
        input (io/file root key)
        local (.getAbsolutePath input)
        archive (str local ".tar.gz")
        directory? (archive/directory-path? internal)
        intern (archive/trim-slash internal)]
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
      :root (:root task)
      :event status}
     message))))

(defn status!
  [kafka task status message]
  (log/notice! status message)
  (send! kafka task status message :status-topic))

(defn error!
  [kafka task status message]
  (log/error! status message)
  (send! kafka task status message :status-topic))

(defn exception!
  [kafka task status message throwable]
  (log/exception! throwable status)
  (send!
   kafka task status
   (assoc message :exception (.toString throwable)) :status-topic))

(defn perform-task!
  "Given a state containing a connection to both cloud storage and some docker service, 
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container, and then uploading all
   outputs back to storage."
  [{:keys [storage kafka docker config state]} task]
  (try
    (let [root (get-in config [:local :root])
          user (or (System/getenv "USER") "root")
          inputs (find-locals! (str root "/inputs") (:inputs task))
          outputs (find-locals! (str root "/outputs") (:outputs task))

          image (:image task)
          ;; commands (join-commands (:commands task))
          commands (first-command (:commands task))]

      (log/info! "pull" image)
      (docker/pull! docker image)

      (doseq [input inputs]
        (pull-input! storage input))

      (let [mounts (mount-map (concat inputs outputs) :local :internal)
            config {:image image
                    ;; TODO: get sisyphus user to work in docker container
                    ;; :user user
                    :mounts mounts
                    :command commands}

            id (docker/create! docker config)
            lines (atom [])]

        (status! kafka task "container-create" {:docker-id id :docker-config config})
        (swap! state assoc-in [:task :docker-id] id)

        (status! kafka task "execution-start" {:docker-id id})
        (docker/start! docker id)

        (doseq [line (docker/logs docker id)]
          (swap! lines conj line)
          (log/info! line)) ; TODO(jerry): Detect stack tracebacks heuristically;
            ; join those lines into one message and log as error!

        (status!
         kafka task "execution-complete"
         {:docker-id id
          :status (.toString (docker/info docker id))})

        (let [code (docker/exit-code (docker/info docker id))]
          (status! kafka task "container-exit" {:docker-id id :code code})

          (if (> code 0)
            (error!
             kafka task "process-error"
             {:code code
              :log @lines}) ; TODO(jerry): Don't re-log the lines, to reduce confusion.

            (doseq [output outputs]
              (push-output! storage output)

              (status!
               kafka task "data-complete"
               {:root (:root task)
                :path (:key output)
                :key (str (:bucket output) ":" (:key output))}))))

        (status! kafka task "process-complete" {})))

    (catch Exception e
      (exception! kafka task "task-error" {:message (.getMessage e)} e))))
