(ns sisyphus.cloud
  (:require
   [clojure.java.io :as io])
  (:import
   [java.io File FileInputStream]
   [com.google.cloud.storage
    Storage StorageOptions
    Storage$BlobWriteOption
    Bucket BucketInfo
    Blob BlobId BlobInfo]))

(defn connect-storage!
  "Connect to the cloud storage service given the options specified in the config map."
  [config]
  (let [storage (.getService (StorageOptions/getDefaultInstance))]
    storage))

(defn get-path
  "Get the java.nio.file.Path object corresponding to the provided absolute filesystem path."
  [path]
  (.toPath (File. path)))
  ;; (Paths/get (URI. (str "file://" path)))

(defn upload!
  "Upload the file at the given local filesystem path to the cloud storage bucket and key."
  [storage bucket key path {:keys [content-type]}]
  (try
    (let [blob-id (BlobId/of bucket key)
          builder (BlobInfo/newBuilder blob-id)
          blob-info (.build (.setContentType builder (or content-type "application/octet-stream")))
          options (make-array Storage$BlobWriteOption 0)
          stream (FileInputStream. (.toFile (get-path path)))]
      (.create storage blob-info stream options)
      blob-info)
    (catch Exception e
      (println "failed to upload" path "to" key)
      (.printStackTrace e))))

(defn download!
  "Download from the cloud storage bucket and key to the provided path."
  [storage bucket key path]
  (let [blob-id (BlobId/of bucket key)
        blob (.get storage blob-id)]
    (.downloadTo blob (get-path path))))
