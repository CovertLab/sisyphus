(ns sisyphus.cloud
  (:require
   [clojure.java.io :as io]
   [sisyphus.log :as log])
  (:import
   [java.io File FileInputStream]
   [com.google.cloud.storage
    Storage StorageOptions StorageException
    Storage$BlobListOption
    Storage$BlobWriteOption
    Bucket BucketInfo
    Blob BlobId BlobInfo]))

(def default-content-type "application/octet-stream")

(defn join-path
  [elements]
  (.getPath (apply io/file elements)))

(defn connect-storage!
  "Connect to the cloud storage service given the options specified in the config map."
  [config]
  (let [storage (.getService (StorageOptions/getDefaultInstance))]
    storage))

(defn get-path
  "Get the java.nio.file.Path object corresponding to the provided absolute
   filesystem path."
  [path]
  (.toPath (File. path)))

(defn delete-tree!
  "Extremely dangerous function"
  [paths]
  (when-let [path (first paths)]
    (let [file (io/file path)]
      (if-let [subpaths (seq (.listFiles file))]
        (recur (concat subpaths paths))
        (do
          (if (.exists file)
            ;; (io/delete-file path true)
            (io/delete-file path))
          (recur (rest paths)))))))

(defn upload!
  "Upload the file at the given local filesystem path to the cloud storage bucket and key."
  ([^Storage storage bucket key path]
   (upload! storage bucket key path {:content-type default-content-type}))
  ([storage bucket key path {:keys [content-type]}]
   (try
     (let [blob-id ^BlobId (BlobId/of bucket key)
           builder (BlobInfo/newBuilder blob-id)
           blob-info (.build
                      (.setContentType
                       builder
                       (or content-type default-content-type)))
           options (make-array Storage$BlobWriteOption 0)
           stream (FileInputStream. (.toFile (get-path path)))]
       (.create storage blob-info stream options)  ; TODO(jerry): "This method is marked as Deprecated because it cannot safely retry, given that it accepts an InputStream which can only be consumed once."
       blob-info)
     (catch StorageException e
       (log/exception! e "failed to upload" path "to" (str bucket ":" key))))))

(defn find-subpath
  [path prefix]
  (let [subpath (.substring path (count prefix))]
    (if (= \/ (first subpath))
      (.substring subpath 1)
      subpath)))

(defn upload-tree!
  [storage bucket key path]
  (doseq [file (file-seq (io/file path))]
    (if (.isFile file)
      (let [fullpath (.getAbsolutePath file)
            subpath (find-subpath fullpath path)
            subkey (join-path [key subpath])]
        (upload! storage bucket subkey fullpath)))))

(defn download!
  "Download from the cloud storage bucket and key to the provided path."
  [^Storage storage bucket key path]
  (let [blob-id ^BlobId (BlobId/of bucket key)
        blob ^Blob (.get storage blob-id)
        file (io/file path)
        base (io/file (.getParent file))
        remote-path (str bucket ":" key)]
    (.mkdirs base)
    (if blob
      (try
        (.downloadTo blob (get-path path))  ; TODO(jerry): "This method is replaced with downloadTo(Path, BlobSourceOption...), but is kept here for binary compatibility with the older versions of the client library."
        (catch StorageException e
          (log/exception! e "failed to download" remote-path "to" path)))
      (log/error! "file unavailable to download" remote-path))))

(defn directory-options
  [directory]
  (into-array
   Storage$BlobListOption
   [(Storage$BlobListOption/prefix directory)]))

(defn list-directory
  [storage bucket directory]
  (let [options (directory-options directory)
        blobs (.list storage bucket options)]
    (map
     #(.getName %)
     (.getValues blobs))))

(defn download-tree!
  [storage bucket key path]
  (let [remote-keys (list-directory storage bucket key)
        preamble (inc (count key))]
    (doseq [remote-key remote-keys]
      (let [local-key (.substring remote-key preamble)
            local-path (join-path [path local-key])]
        (download! storage bucket remote-key local-path)))))
