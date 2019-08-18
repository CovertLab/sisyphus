(ns sisyphus.cloud
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [sisyphus.log :as log])
  (:import
   [java.io File FileInputStream]
   [com.google.cloud.storage
    Storage StorageOptions StorageException
    Storage$BlobField
    Storage$BlobGetOption
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

(defn is-directory-path?
  [path]
  (= (last path) \/))

(defn dir-slash
  [path]
  (if (is-directory-path? path)
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

(def dirname-cache (atom #{}))

(defn cache-dirname
  "Insert the bucket:key into dirname-cache. Return true if it was already there."
  [bucket dir]
  (let [dirname (str bucket ":" dir)
        previous (first (swap-vals! dirname-cache conj dirname))]
    (contains? previous dirname)))

(defn make-dir!
  "Make a storage 'directory/' entry if it wasn't created recently and it's absent."
  ; TODO(jerry): When to clear the cache?
  [^Storage storage bucket key]
  (let [dir-name (dir-slash key)]
    (when-not (cache-dirname bucket dir-name)
      (try
        (let [blob-info (-> (BlobInfo/newBuilder bucket dir-name 0) ; match gen 0 means if-absent
                            (.setContentType default-content-type)
                            .build)
              options (into-array [(Storage$BlobTargetOption/generationMatch)])]
          (.create storage blob-info options)
          :created)
        (catch StorageException e
          (when-not (string/includes? (.getMessage e) "Precondition Failed")
            (log/exception! e "mkdir failed" (str bucket ":" dir-name))
            :failed)
          :present)))))

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

(def blob-fields
  "Desired fields when getting/listing Blobs. BUCKET and NAME are implied but
  it's useful to be explicit about the results."
  (into-array
   [Storage$BlobField/BUCKET
    Storage$BlobField/NAME
    Storage$BlobField/GENERATION
    Storage$BlobField/SIZE]))

(defn directory-options
  "Storage options to list a directory and get desired fields of its entries."
  [directory]
  (into-array
   Storage$BlobListOption
   [(Storage$BlobListOption/prefix directory)
    (Storage$BlobListOption/fields blob-fields)]))

(defn download-blob!
  "Download a Blob (file or directory) from the cloud storage bucket to the
  local path. The Blob must have BUCKET and NAME fields."
  [^Blob blob path]
  (let [file (io/file path)
        base (.getParentFile file)
        key (.getName blob)
        remote-path (str (.getBucket blob) ":" key)
        directory? (or (is-directory-path? key) (is-directory-path? path))]
    (if directory?
      (.mkdirs file)
      (try
        (.mkdirs base)
        (.downloadTo blob (.toPath file))
        (catch StorageException e
          (log/exception! e "failed to download" remote-path "to" path))))))

(defn download!
  "Download a named object from the cloud storage bucket to the local path."
  [^Storage storage bucket key path]
  (let [blob-id ^BlobId (BlobId/of bucket key)
        options (into-array [(Storage$BlobGetOption/fields blob-fields)])
        blob ^Blob (.get storage blob-id options)
        remote-path (str bucket ":" key)]
    (if blob
      (download-blob! blob path)
      (log/error! "file unavailable to download" remote-path))))

(defn list-prefix
  "List cloud storage contents in a bucket with a prefix (acting as a directory).
  Return a Blob iterator."
  [storage bucket prefix]
  (let [options (directory-options prefix)
        blobs (.list storage bucket options)]
    (.iterateAll blobs)))

(defn download-tree!
  [storage bucket key path]
  ; ASSUMES the key doesn't end with "/" but it names an entry that does end with "/".
  (let [blobs (list-prefix storage bucket key)
        preamble (inc (count key))]
    (doseq [blob blobs]
      (let [remote-key (.getName blob)
            local-key (.substring remote-key preamble)
            local-path (join-path [path local-key])]
        (download-blob! blob local-path)))))
