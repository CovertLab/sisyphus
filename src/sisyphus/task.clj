(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [sisyphus.archive :as archive]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]))

(def full-name
  "Extract the string representation of the given keyword. This is an extension to the build-in
   `name` function which fails to return the full string representation for namespaced keywords."
  (comp str symbol))

(defn directory-path?
  [path]
  (= (last path) \/))

(defn setup-path!
  "Sets up a path from a storage key to be mounted into a docker container.
     * remote - the storage key the local path will be based on.
     * root - the root of the local filesystem where this path will reside.
     * kind - the type of path we are creating (appended to the path before the remote key)."
  [remote root kind]
  (let [[bucket key] (string/split (full-name remote) #":")
        input (io/file root kind key)
        local (.getAbsolutePath input)
        base (io/file (.getParent input))]
    (.mkdirs base)
    (.createNewFile input)
    [bucket key local]))

(defn input-directory!
  [{:keys [config storage]} remote internal]
  (let [root (get-in config [:local :root])
        [bucket key] (string/split (full-name remote) #":")
        input (io/file root "inputs" key)
        local (.getAbsolutePath input)
        archive (str local ".tar.gz")
        base (io/file (.getParent input))]
    (.mkdirs local)
    (cloud/download! storage bucket key archive)
    (archive/unpack-archive archive local)
    [local internal]))

(defn process-input!
  "Given a remote key on the object store and a path internal to the container, create a mapping
   between the local path where that file from the object store will reside after being
   downloaded and the internal path inside the container, as read-only."
  [{:keys [config storage]} remote internal]
  (let [root (get-in config [:local :root])
        [bucket key local] (setup-path! remote root "inputs")]
    (cloud/download! storage bucket key local)
    [local (str internal ":ro")]))

(defn process-output!
  "Given a remote key on the object store and a path internal to the container, create a mapping
   between the local path where that file from the object store will reside after being
   downloaded and the internal path inside the container, as read-write."
  [{:keys [config storage]} remote internal]
  (let [root (get-in config [:local :root])
        [bucket key local] (setup-path! remote root "outputs")]
    [local internal]))

(defn process-local!
  "Process inputs or outputs (depending on the value of the function `process!`) translating a
   mapping of (remote keys --> internal container paths) to (local path --> internal)."
  [process! state mapping]
  (into
   {}
   (map
    (fn [[local internal]]
      (process! state local internal))
    mapping)))

(defn remote-mapping
  "Create a map of internal paths to a remote key in the object store [bucket key], to be used
   to know where to upload newly created outputs."
  [outputs]
  (into
   {}
   (map
    (fn [[remote internal]]
      (let [[bucket key] (string/split (full-name remote) #":")]
        [internal [bucket key]]))
    outputs)))

(def process-inputs! (partial process-local! process-input!))
(def process-outputs! (partial process-local! process-output!))

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

(defn perform-task!
  "Given a state containing a connection to both cloud storage and some docker service, 
   execute the task specified by the given `task` map, downloading all inputs from cloud
   storage, executing the command in the specified container, and then uploading all
   outputs back to storage."
  [{:keys [storage docker] :as state} task]
  (let [inputs (process-inputs! state (:inputs task))
        outputs (process-outputs! state (:outputs task))
        remote (remote-mapping (:outputs task))
        image (:image task)
        _ (println "pulling docker image" image)
        _ (docker/pull! docker image)
        commands (join-commands (:commands task))
        config {:image image
                :mounts (merge inputs outputs)
                :command commands}
        _ (println "creating docker container from" config)
        id (docker/create! docker config)]

    (println "starting container" id)
    (docker/start! docker id)

    (println "executing container" id)
    (docker/wait! docker id)

    (println "execution complete!" id)
    (doseq [[local internal] outputs]
      (let [[bucket key] (get remote internal)]
        (println "uploading" local "to" (str bucket ":" key))
        (cloud/upload! storage bucket key local {})))))

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
