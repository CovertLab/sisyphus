(ns sisyphus.task
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [sisyphus.cloud :as cloud]
   [sisyphus.docker :as docker]))

(def full-name
  (comp str symbol))

(defn setup-path!
  [remote root base]
  (let [[bucket key] (string/split (full-name remote) #":")
        input (io/file root base key)
        local (.getAbsolutePath input)
        base (io/file (.getParent input))]
    (.mkdirs base)
    (.createNewFile input)
    [bucket key local]))

(defn process-input!
  [{:keys [config storage]} remote internal]
  (let [root (get-in config [:local :root])
        [bucket key local] (setup-path! remote root "inputs")]
    (cloud/download! storage bucket key local)
    [local (str internal ":ro")]))

(defn process-output!
  [{:keys [config storage]} remote internal]
  (let [root (get-in config [:local :root])
        [bucket key local] (setup-path! remote root "outputs")]
    [local internal]))

(defn process-local!
  [process! state mapping]
  (into
   {}
   (map
    (fn [[local internal]]
      (process! state local internal))
    mapping)))

(defn remote-mapping
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
  [tokens stdout]
  ["sh" "-c" (string/join " " (concat tokens [">" stdout]))])

(defn load-task
  [path]
  (json/parse-string (slurp path) true))

(defn perform-task!
  [{:keys [storage docker] :as state} task]
  (let [inputs (process-inputs! state (:inputs task))
        outputs (process-outputs! state (:outputs task))
        remote (remote-mapping (:outputs task))
        image (:image task)]
    (docker/pull! docker image)
    (doseq [command (:commands task)]
      (let [stdout (:stdout command)
            tokens (:command command)
            tokens (if stdout
                     (redirect-stdout tokens stdout)
                     tokens)
            run {:image image
                 :mounts (merge inputs outputs)
                 :command tokens}]
        (println "running" run)
        (docker/run! docker run)))
    (doseq [[local internal] outputs]
      (let [[bucket key] (get remote internal)]
        (println "uploading" local "to" (str bucket ":" key))
        (cloud/upload! storage bucket key local {})))))
