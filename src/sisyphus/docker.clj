(ns sisyphus.docker
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [byte-streams :as bytes]
   [clj-docker-client.core :as docker]
   [clj-docker-client.utils :as docker-utils]
   [sisyphus.log :as log])
  (:import
   [java.nio.charset StandardCharsets]
   [com.spotify.docker.client
    LogMessage
    DockerClient
    DefaultDockerClient
    DockerClient$LogsParam
    DockerClient$AttachParameter
    DockerClient$ExecCreateParam
    DockerClient$ExecStartParameter]
   [com.spotify.docker.client.exceptions DockerException]
   [com.spotify.docker.client.messages
    ContainerConfig
    ContainerInfo
    HostConfig]))

(defn connect!
  "Connect to the docker service provided by (:uri config)."
  ^DockerClient [config]
  (if-let [uri (:uri config)]
    (docker/connect uri)
    (docker/connect)))

(defn port-mapping
  "Create a mapping of local ports to ports internal to the docker container."
  [ports]
  (into
   {}
   (map
    (fn [[from to]]
      [(docker-utils/filter-host (str from))
       (docker-utils/port-binding-default-public (str to))])
    ports)))

(defn exposed-ports
  "Find a list of exposed ports given by the provided port mapping."
  [ports]
  (set
   (map
    (comp str last)
    ports)))

(defn mount-mapping
  "Generate a formatted mapping string for the given mount points
   between local and container paths."
  [mounts]
  (map
   (fn [[from to]]
     (str from ":" to))
   mounts))

(def memory-limit 17179869184)
(def memory-swap 34359738368)

(defn build-config
  "Given a config map for running a docker container, create and return the config
   object required by the java docker client. Options for config are:
     * :ports - a mapping of port numbers from outside to inside the container.
     * :mounts - a mapping of paths from outside to inside the container
     * :env - any environment variables that need to be set inside the container.
     * :working-dir - the working dir for the execution inside the container.
     * :user - user who will perform the commands inside the container.
     * :image - what docker image to use to build the container.
     * :command - the command to run inside the container."
  [config]
  (let [host-config (HostConfig/builder)
        container-config (ContainerConfig/builder)]
    (when-let [ports (:ports config)]
      (.portBindings host-config (port-mapping ports))
      (.exposedPorts (exposed-ports ports)))
    (when-let [mounts (:mounts config)]
      (.binds host-config (mount-mapping mounts)))
    (.hostConfig container-config (.build host-config))
    (when-let [env (:env config)]
      (.env container-config (docker-utils/format-env-vars env)))
    (when-let [working-dir (:working-dir config)]
      (.workingDir container-config working-dir))
    (when-let [user (:user config)]
      (.user container-config user))
    (-> container-config
        (.image (:image config))
        (.cmd (:command config))
        (.build))))

(defn docker-retry
  "Call a function with the requested number of DockerException retries."
  [retries f]
  (let [res (try
              {:value (f)}
              (catch DockerException e
                (if (<= retries 0)
                  (throw e)
                  {:exception e})))]
    (if (:exception res)
      (do
        (log/debug! "retrying" (str (:exception res)))
        (recur (dec retries) f))
      (:value res))))

(defn pull!
  "Pull the docker image given by the `image` argument. Throw a DockerException
  after a few retries if it keeps failing."
  [^DockerClient docker image]
  (log/info! "docker pull" image)
  (docker-retry 3 (fn [] (docker/pull docker image)))
  (log/debug! "pulled docker image" image)) ; hypothesis testing

(def default-options
  {:image "alpine" ; BEWARE: alpine has its own libc which gets the math wrong
   :command ["date"]})

(defn create!
  "Create a docker container from the given options and return the container id."
  ([^DockerClient docker] (create! docker {}))
  ([^DockerClient docker options]
   (log/debug! "creating docker container" options) ; hypothesis testing
   (let [config (build-config (merge default-options options))
         create (.createContainer docker config)
         id (docker-utils/format-id (.id create))]
     (log/info! "created docker container" id options)
     id)))

(defn logs-streams
  []
  (into-array
   DockerClient$LogsParam
   [(DockerClient$LogsParam/stdout)
    (DockerClient$LogsParam/stderr)
    (DockerClient$LogsParam/follow)]))

(defn docker-logs
  [^DockerClient docker id]
  (.logs docker id (logs-streams)))

(defn iteration->seq
  [iteration]
  (when (.hasNext iteration)
    (lazy-seq
     (cons
      (.next iteration)
      (iteration->seq iteration)))))

(defn decode-bytes
  [bytes]
  (.toString
   (.decode StandardCharsets/UTF_8 bytes)))

(defn logs-seq
  "Convert a docker-client ^LogStream to a seq of lines."
  [logs]
  (let [it (iteration->seq logs)
        content (map #(decode-bytes (.content ^LogMessage %)) it)]
    (mapcat #(string/split % #"\n") content)))

(defn logs
  "Return a lazy seq of log lines."
  [^DockerClient docker id]
  (logs-seq (docker-logs docker id)))

(defn read-fully!
  [^DockerClient docker id]
  (let [stream (.logs docker id (logs-streams))]
    (.readFully stream)))

(defn attach-params
  []
  (into-array
   DockerClient$AttachParameter
   [DockerClient$AttachParameter/LOGS
    DockerClient$AttachParameter/STDOUT
    DockerClient$AttachParameter/STDERR
    DockerClient$AttachParameter/STREAM]))

(defn attach-logs
  ([^DockerClient docker id] (attach-logs docker id System/out System/err))
  ([^DockerClient docker id out err]
   (let [attach (.attachContainer docker id (attach-params))]
     (.attach attach out err true))))

(defn start!
  "Start the docker container with the given id."
  [^DockerClient docker id]
  (.startContainer docker id))

(defn stop!
  "Try to terminate a container's process. Force-kill it if that times out."
  [^DockerClient docker id]
  (docker/stop docker id))

(defn kill!
  "Force-kill a container's process."
  [^DockerClient docker id]
  (when id
    (.killContainer docker id)))

(defn remove!
  "Remove a container by id or name. Optionally force-kill its process."
  ([^DockerClient docker id]
   (remove! docker id false))
  ([^DockerClient docker id force?]
   (when force?
     (try
       (kill! docker id)
       (catch DockerException e))) ; no problem if the container isn't running
   (.removeContainer docker id)))

(defn info
  ^ContainerInfo [^DockerClient docker id]
  (.inspectContainer docker id))

(defn exit-code
  [^ContainerInfo info]
  (.exitCode (.state info)))

(defn error-string
  [^ContainerInfo info]
  (.error (.state info)))

(defn oom-killed?
  [^ContainerInfo info]
  (.oomKilled (.state info)))

(defn exec-streams
     []
     (into-array
      DockerClient$ExecCreateParam
      [(DockerClient$ExecCreateParam/attachStdout)
       (DockerClient$ExecCreateParam/attachStderr)]))

(defn exec!
  "Execute the given command in a running container."
  [^DockerClient docker id command]
  (let [exec (.execCreate
              docker id
              (into-array String command)
              (exec-streams))
        exec-id (.id exec)
        output (.execStart
                docker exec-id
                (into-array DockerClient$ExecStartParameter []))
        state (.execInspect docker exec-id)]
    (log/info! "docker" state)
    (logs-seq output)))

(defn run-container!
  "Run a container with the given options. If :detach is provided, detach the running
   container from the main thread."
  [^DockerClient docker options]
  (let [id (create! docker options)]
    (log/info! "docker-container" id)
    (start! docker id)
    (future
      (let [logs (docker/logs docker id)]
        (doseq [line logs]
          (log/info! line))))
    (if (:detach options)
      id
      (docker/wait-container docker id))))

(defn wait!
  [^DockerClient docker id]
  (docker/wait-container docker id))
