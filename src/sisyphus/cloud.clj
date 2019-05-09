(ns sisyphus.cloud
  (:require
   [clojure.java.io :as io])
  (:import
   [java.io FileInputStream]
   [java.net URI]
   [java.nio.file Path Paths]
   [com.google.cloud.storage
    Storage StorageOptions
    Storage$BlobWriteOption
    Bucket BucketInfo
    Blob BlobId BlobInfo]))

(defn connect!
  [config]
  (let [storage (.getService (StorageOptions/getDefaultInstance))]
    storage))

(defn get-path
  [path]
  (Paths/get (URI. (str "file://" path))))

(defn upload!
  [storage bucket key path {:keys [content-type]}]
  (let [blob-id (BlobId/of bucket key)
        builder (BlobInfo/newBuilder blob-id)
        blob-info (.build (.setContentType builder (or content-type "application/octet-stream")))
        options (make-array Storage$BlobWriteOption 0)
        stream (FileInputStream. (.toFile (get-path path)))]
    (.create storage blob-info stream options)
    blob-info))

(defn download!
  [storage bucket key path]
  (let [blob-id (BlobId/of bucket key)
        blob (.get storage blob-id)]
    (.downloadTo blob (get-path path))))
