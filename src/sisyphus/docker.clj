(ns sisyphus.docker
  (:require
   [clojure.java.io :as io]
   [byte-streams :as bytes]
   [clj-docker-client.core :as docker]
   [clj-docker-client.utils :as docker-utils])
  (:import
   [com.spotify.docker.client
    LogMessage
    DefaultDockerClient
    DockerClient$LogsParam
    DockerClient$AttachParameter
    DockerClient$ExecCreateParam
    DockerClient$ExecStartParameter]
   [com.spotify.docker.client.messages
    ContainerConfig
    HostConfig]))

(defn connect!
  "Connect to the docker service provided by (:uri config)."
  [config]
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

(defn pull!
  "Pull the docker image given by the `image` argument."
  [docker image]
  (docker/pull docker image))

(def default-options
  {:image "alpine"
   :command ["tail" "-f" "/dev/null"]})

(defn create!
  "Create a docker container from the given options and return the container id."
  ([docker] (create! docker {}))
  ([docker options]
   (let [config (build-config (merge default-options options))
         create (.createContainer docker config)]
     (docker-utils/format-id (.id create)))))

(defn start!
  "Start the docker container with the given id."
  [docker id]
  (.startContainer docker id))

(defn stop!
  [docker id]
  (docker/stop docker id))

(defn logs
  [docker id]
  (docker/logs docker id))

(defn logs-streams
  []
  (into-array
   DockerClient$LogsParam
   [(DockerClient$LogsParam/stdout)
    (DockerClient$LogsParam/stderr)]))

(defn read-fully!
  [docker id]
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
  ([docker id] (attach-logs docker id System/out System/err))
  ([docker id out err]
   (let [attach (.attachContainer docker id (attach-params))]
     (.attach attach out err))))

(defn logs-seq
  "Convert a docker-client ^LogStream to a seq of lines."
  [logs]
  (let [it (iterator-seq logs)
        content (map #(.content ^LogMessage %) it)
        stream (bytes/to-input-stream content)
        reader (io/reader stream)]
    (line-seq reader)))

(defn exec-streams
  []
  (into-array
   DockerClient$ExecCreateParam
   [(DockerClient$ExecCreateParam/attachStdout)
    (DockerClient$ExecCreateParam/attachStderr)]))

(defn exec!
  "Execute the given command in a running container."
  [docker id command]
  (let [exec (.execCreate
              docker id
              (into-array String command)
              (exec-streams))
        exec-id (.id exec)
        output (.execStart
                docker exec-id
                (into-array DockerClient$ExecStartParameter []))
        state (.execInspect docker exec-id)]
    (println state)
    (logs-seq output)))

(defn run-container!
  "Run a container with the given options. If :detach is provided, detach the running
   container from the main thread."
  [docker options]
  (let [id (create! docker options)]
    (println "docker container id:" id)
    (start! docker id)
    (future
      (let [logs (docker/logs docker id)]
        (doseq [line logs]
          (println line))))
    (if (:detach options)
      id
      (docker/wait-container docker id))))

(defn wait!
  [docker id]
  (attach-logs docker id)
  (docker/wait-container docker id))
