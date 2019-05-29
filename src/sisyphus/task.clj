(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [sisyphus.archive :as archive]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]))

(def full-name
  "Extract the string representation of the given keyword. This is an extension to the
   built-in `name` function which fails to return the full string representation for
   namespaced keywords."
  (comp str symbol))

(defn split-key
  [key]
  (let [[prefix & parts] (string/split (full-name key) #":")]
    [prefix (string/join ":" parts)]))

(defn find-local
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
    {:remote remote
     :bucket bucket
     :key key
     :archive archive
     :root root
     :local local
     :input input
     :directory? directory?
     :internal intern}))

(defn find-locals
  [root locals]
  (mapv (partial find-local root) locals))

(defn pull-input!
  [storage {:keys [bucket key archive local directory? internal]}]
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
    ["bash" "-c" (string/join " && " series)]))

(defn mount-map
  [locals from to]
  (into
   {}
   (map
    (fn [mapping]
      [(get mapping from)
       (get mapping to)])
    locals)))

(defn perform-task!
  "Given a state containing a connection to both cloud storage and some docker service, 
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container, and then uploading all
   outputs back to storage."
  [{:keys [storage docker config] :as state} task]
  (let [root (get-in config [:local :root])
        inputs (find-locals (str root "/inputs") (:inputs task))
        outputs (find-locals (str root "/outputs") (:outputs task))

        image (:image task)
        commands (join-commands (:commands task))]

    (println "pulling docker image" image)
    (docker/pull! docker image)

    (doseq [input inputs]
      (println "downloading" input)
      (pull-input! storage input))

    (let [mounts (merge
                  (mount-map inputs :local :internal)
                  (mount-map outputs :local :internal))

          config {:image image
                  :mounts mounts
                  :command commands}

          _ (println "creating docker container from" config)
          id (docker/create! docker config)]

      (println "starting container" id)
      (docker/start! docker id)

      (println "executing container" id)
      (docker/wait! docker id)

      (println "execution complete!" id)

      (doseq [output outputs]
        (println "uploading" output)
        (push-output! storage output)))))

;; (defn perform-task!
;;   "Given a state containing a connection to both cloud storage and some docker service, 
;;    execute the task specified by the given `task` map, downloading all inputs from cloud
;;    storage, executing the command in the specified container, and then uploading all
;;    outputs back to storage."
;;   [{:keys [storage docker] :as state} task]
;;   (let [inputs (process-inputs! state (:inputs task))
;;         outputs (process-outputs! state (:outputs task))
;;         remote (remote-mapping (:outputs task))
;;         image (:image task)
;;         _ (println "pulling docker image" image)
;;         _ (docker/pull! docker image)
;;         config {:image image
;;                 :mounts (merge inputs outputs)}
;;         _ (println "creating docker container from" config)
;;         id (docker/create! docker config)]

;;     (println "starting container" id)
;;     (docker/start! docker id)

;;     (doseq [command (:commands task)]
;;       (let [stdout (:stdout command)
;;             tokens (:command command)
;;             tokens (if stdout
;;                      (redirect-stdout tokens stdout)
;;                      tokens)]
;;         (println "running command:" tokens)
;;         (doseq [line (docker/exec! docker id tokens)]
;;           (println (str id ">") line))))

;;     (println "stopping container" id)
;;     (docker/stop! docker id)

;;     (doseq [[local internal] outputs]
;;       (let [[bucket key] (get remote internal)]
;;         (println "uploading" local "to" (str bucket ":" key))
;;         (cloud/upload! storage bucket key local {})))))
