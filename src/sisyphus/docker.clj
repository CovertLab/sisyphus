(ns sisyphus.docker
  (:require
   [clj-docker-client.core :as docker]
   [clj-docker-client.utils :as docker-utils])
  (:import
   [com.spotify.docker.client
    DefaultDockerClient]
   [com.spotify.docker.client.messages
    ContainerConfig
    HostConfig]))

(defn connect!
  [config]
  (if-let [uri (:uri config)]
    (docker/connect uri)
    (docker/connect)))

(defn port-mapping
  [ports]
  (into
   {}
   (map
    (fn [[from to]]
      [(docker-utils/filter-host (str from))
       (docker-utils/port-binding-default-public (str to))])
    ports)))

(defn exposed-ports
  [ports]
  (set
   (map
    (comp str last)
    ports)))

(defn mount-mapping
  [mounts]
  (map
   (fn [[from to]]
     (str from ":" to))
   mounts))

(defn build-config
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
  [docker image]
  (docker/pull docker image))

(defn create!
  [docker options]
  (let [config (build-config options)
        create (.createContainer docker config)]
    (docker-utils/format-id (.id create))))

(defn start!
  [docker id]
  (.startContainer docker id))

(defn run!
  [docker options]
  (let [id (create! docker options)]
    (start! docker id)
    (if (:detach options)
      id
      (docker/wait-container docker id))))
