(ns sisyphus.cloud
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [sisyphus.log :as log])
  (:import
   [java.io File FileInputStream]
   [com.google.cloud.storage
    Storage StorageOptions StorageException
    Storage$BlobListOption
    Storage$BlobTargetOption
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

(defn dir-slash
  [path]
  (if (string/ends-with? path "/")
    path
    (str path "/")))

(defn split-key
  [key]
  (let [colon (.indexOf key ":")]
    [(.substring key 0 colon)
     (.substring key (inc colon))]))

(defn key-path
  [key]
  (let [[bucket path] (split-key key)
        parts (string/split path #"/+")]
    (cons bucket parts)))

(defn path-tree
  [paths]
  (reduce
   (fn [tree key]
     (let [path (key-path key)]
       (update-in
        tree path
        (fn [x]
          (or x {})))))
   {} paths))

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

(defn make-dir!
  "Make a storage 'directory/' entry if it's absent."
  ; TODO(jerry): Cache created directory names for a while to reduce repeats.
  [^Storage storage bucket key]
  (let [dir-name (dir-slash key)]
    (try
      (let [blob-info (-> (BlobInfo/newBuilder bucket dir-name 0) ; match gen 0 means if-absent
                          (.setContentType default-content-type)
                          .build)
            options (into-array [(Storage$BlobTargetOption/generationMatch)])]
        ; (println "make-dir!" dir-name) ; *** DEBUG ***
        (.create storage blob-info options)
        :created)
      (catch StorageException e
        (when-not (string/includes? (.getMessage e) "Precondition Failed")
          (log/exception! e "failed to make-dir" (str bucket ":" dir-name))
          :failed)
        :present))))

(defn make-dirs!
  "Make a storage 'key/' entry if last?, and its parents, if absent."
  ([^Storage storage bucket key]
   (make-dirs! storage bucket key false))
  ([^Storage storage bucket key last?]
   (let [java-file (io/file key)
         parent (.getParent java-file)]
     (if parent
       (make-dirs! storage bucket parent true))
     (if last?
       (make-dir! storage bucket (.getPath java-file))))))

(defn slurp-bytes
  "Slurp a byte array from anything that clojure.java.io/input-stream can read."
  [readable]
  (with-open [in (clojure.java.io/input-stream readable)
              out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

(defn upload!
  "Upload the file at the given local filesystem path to the cloud storage bucket and key."
  ; TODO(jerry): If the files get big, use storage.writer() and a truncated
  ; exponential backoff retry loop.
  ([^Storage storage bucket key path]
   (upload! storage bucket key path {:content-type default-content-type}))
  ([storage bucket key path {:keys [content-type]}]
   (try
     (let [blob-info (-> (BlobInfo/newBuilder bucket key)
                         (.setContentType (or content-type default-content-type))
                         .build)
           options (make-array Storage$BlobTargetOption 0)
           bytes (slurp-bytes path)]
       ; (println "uploading" key) ; *** DEBUG ***
       (.create storage blob-info bytes options)
       (make-dirs! storage bucket key false)
       blob-info)
     (catch StorageException e
       (log/exception! e "failed to upload" path "to" (str bucket ":" key))))))

(defn find-subpath
  [path prefix]
  "Assume the path begins with the prefix, strip off the prefix, and strip off
  a leading '/'."
  (let [subpath (.substring path (count prefix))]
    (if (= \/ (first subpath))
      (.substring subpath 1)
      subpath)))

(defn upload-tree!
  [storage bucket key path]
  (doseq [file (file-seq (io/file path))]
    (if (.isFile file)
      (let [fullpath (.getAbsolutePath file) ; local absolute path for child file
            subpath (find-subpath fullpath path) ; local name relative to path
            subkey (join-path [key subpath])] ; remote absolute name for child file
        (upload! storage bucket subkey fullpath)))))

(defn download!
  "Download from the cloud storage bucket and key to the provided path."
  [^Storage storage bucket key path]
  (let [blob-id ^BlobId (BlobId/of bucket key)
        blob ^Blob (.get storage blob-id) ; TODO: get a list of blob IDs in one request
        file (io/file path)
        base (io/file (.getParent file))
        remote-path (str bucket ":" key)]
    (.mkdirs base)
    (if blob
      (try
        (.downloadTo blob (get-path path))
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
