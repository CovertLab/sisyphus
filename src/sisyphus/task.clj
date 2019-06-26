(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [sisyphus.archive :as archive]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]
   [sisyphus.kafka :as kafka]))

(def full-name
  "Extract the string representation of the given keyword. This is an extension to the
   built-in `name` function which fails to return the full string representation for
   namespaced keywords."
  (comp str symbol))

(defn split-key
  [key]
  (let [[prefix & parts] (string/split (full-name key) #":")]
    [prefix (string/join ":" parts)]))

(defn find-local!
  [root [remote internal]]
  (let [[bucket key] (split-key remote)
        input (io/file root key)
        local (.getAbsolutePath input)
        archive (str local ".tar.gz")
        directory? (archive/directory-path? internal)
        intern (archive/trim-slash internal)]
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
  (if directory?
    (do
      (cloud/download! storage bucket key archive)
      (archive/unpack! archive local))
    (cloud/download! storage bucket key local)))

(defn push-output!
  [storage {:keys [local directory? archive bucket key]}]
  (if directory?
    (do
      (archive/pack! archive local)
      (cloud/upload! storage bucket key archive))
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
      :status status}
     message))))

(defn log!
  [kafka task status message]
  (println status ":" message)
  (send! kafka task status message :log-topic))

(defn status!
  [kafka task status message]
  (println status ":" message task)
  (send! kafka task status message :status-topic))

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
          commands (join-commands (:commands task))]

      (log! kafka task "pull" {:image image})
      (docker/pull! docker image)

      (doseq [input inputs]
        (log! kafka task "download" {:input input})
        (pull-input! storage input))

      (let [mounts (mount-map (concat inputs outputs) :local :internal)
            config {:image image
                    :mounts mounts
                    :command commands}

            _ (println "creating docker container from" config)
            id (docker/create! docker config)]

        (status! kafka task "create" {:docker-id id :docker-config config})
        (swap! state assoc-in [:task :docker-id] id)

        (status! kafka task "start" {:docker-id id})
        (docker/start! docker id)

        (doseq [line (docker/logs docker id)]
          (log! kafka task "log" {:line line}))

        (status! kafka task "stop" {:docker-id id})

        (doseq [output outputs]
          (log! kafka task "upload" {:path output})
          (push-output! storage output)

          (status!
           kafka task "complete"
           {:event "data-complete"
            :root (:bucket output)
            :path (:key output)
            :key (str (:bucket output) ":" (:key output))}))

        (status!
         kafka task "complete"
         {:event "process-complete"})))
    (catch Exception e
      (let [message (.getMessage e)
            trace (.getStackTrace e)]
        (status!
         kafka task "error"
         {:event "process-error"
          :message message
          :trace trace})))))
