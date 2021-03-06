(ns sisyphus.cloud
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [sisyphus.base :as base]
   [sisyphus.log :as log])
  (:import
   [java.io File FileInputStream IOException]
   [java.util Arrays]
   [com.google.api.client.googleapis.auth.oauth2 GoogleCredential]
   [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
   [com.google.api.client.http HttpTransport]
   [com.google.api.client.json JsonFactory]
   [com.google.api.client.json.jackson2 JacksonFactory]
   [com.google.api.services.compute Compute Compute$Builder]
   [com.google.api.services.compute.model Instance InstanceList]
   [com.google.cloud.storage
    Storage StorageOptions StorageException
    Storage$BlobField
    Storage$BlobGetOption
    Storage$BlobListOption
    Storage$BlobTargetOption
    Bucket BucketInfo
    Blob BlobId BlobInfo
    Blob$BlobSourceOption]))

(def default-content-type "application/octet-stream")

(defn join-path
  [elements]
  (.getPath (apply io/file elements)))

(defn connect-storage!
  "Connect to the cloud storage service given the options specified in the config map."
  [config]
  (let [storage (.getService (StorageOptions/getDefaultInstance))]
    {:storage storage
     :dirname-cache (atom #{})}))

(defn get-path
  "Get the java.nio.file.Path object corresponding to the provided absolute
   filesystem path."
  [path]
  (.toPath (File. path)))

(defn is-directory-path?
  "Return true if the given pathname is a directory."
  [path]
  (= (last path) \/))

(defn dir-slash
  "Append a / if needed to the directory path. (A GCS 'directory' entry is just
  a file whose name ends with /.)"
  [path]
  (if (is-directory-path? path)
    path
    (str path "/")))

(defn trim-slash
  "Trim the trailing / off a pathname, if present."
  [path]
  (if (is-directory-path? path)
    (.substring path 0 (dec (.length path)))
    path))

(defn key-path
  [key]
  (let [[bucket path] (base/split-key key)
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
  "Delete the local file path trees."
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

(defn blob-id
  "Get the blob id for this bucket/key combination"
  [[bucket key]]
  (BlobId/of bucket key))

(defn exists?
  "Check to see if key exists in bucket"
  [{:keys [^Storage storage]} bucket key]
  (let [bid (blob-id [bucket key])
        blob (.get storage bid)]
    (.exists blob (into-array Blob$BlobSourceOption []))))

(defn partition-keys
  "Partition bucket:key path strings into existing and not existing paths."
  [{:keys [^Storage storage]} data]
  (let [bids (map (comp blob-id base/split-key) data)
        existence (.get storage bids)]
    (reduce
     (fn [[exist non] [key blob]]
       (if blob
         [(conj exist key) non]
         [exist (conj non key)]))
     [[] []]
     (map vector data existence))))

(defn cache-dirname
  "Insert the bucket:key into dirname-cache. Return true if it was already there."
  [dirname-cache bucket dir]
  (let [dirname (str bucket ":" dir)
        previous (first (swap-vals! dirname-cache conj dirname))]
    (contains? previous dirname)))

(defn make-dir!
  "Make a storage 'directory/' entry if it wasn't created recently and it's absent."
  ; TODO(jerry): When to clear the cache?
  [{:keys [^Storage storage dirname-cache]} bucket key]
  (let [dir-name (dir-slash key)]
    (when-not (cache-dirname dirname-cache bucket dir-name)
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
  ([state bucket key]
   (make-dirs! state bucket key false))
  ([state bucket key last?]
   (let [java-file (io/file key)
         parent (.getParent java-file)]
     (if parent
       (make-dirs! state bucket parent true))
     (if last?
       (make-dir! state bucket (.getPath java-file))))))

(defn read-bytes
  "Fully read a byte array from anything that clojure.java.io/input-stream can read."
  [readable]
  (with-open [in (clojure.java.io/input-stream readable)
              out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

(defn upload!
  "Upload the file at the given local filesystem path to the cloud storage bucket and key."
  ; TODO(jerry): If the files get big, use storage.writer() and a truncated
  ; exponential backoff retry loop.
  ([state bucket key path]
   (upload! state bucket key path {:content-type default-content-type}))
  ([{:keys [^Storage storage] :as state} bucket key path {:keys [content-type]}]
   (try
     (let [blob-info (-> (BlobInfo/newBuilder bucket key)
                         (.setContentType (or content-type default-content-type))
                         .build)
           options (make-array Storage$BlobTargetOption 0)
           bytes (read-bytes path)]
       (.create storage blob-info bytes options)
       (make-dirs! state bucket key false)
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
  [state bucket key path]
  (doseq [file (file-seq (io/file path))]
    (if (.isFile file)
      (let [fullpath (.getAbsolutePath file) ; local absolute path for child file
            subpath (find-subpath fullpath path) ; local name relative to path
            subkey (join-path [key subpath])] ; remote absolute name for child file
        (upload! state bucket subkey fullpath)))))

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
        (when base
          (.mkdirs base))
        (.downloadTo blob (.toPath file))
        (catch StorageException e
          (log/exception! e "failed to download" remote-path "to" path))))))

(defn download!
  "Download a named object from the cloud storage bucket to the local path."
  [{:keys [^Storage storage]} bucket key path]
  (let [bid ^BlobId (blob-id [bucket key])
        options (into-array [(Storage$BlobGetOption/fields blob-fields)])
        blob ^Blob (.get storage bid options)]
    (if blob
      (download-blob! blob path)
      (throw (IOException. (str "file is unavailable to download: "
                                bucket ":" key))))))

(defn list-prefix
  "List cloud storage contents in a bucket with the given prefix string (which
  needn't be a directory name). Return a Blob iterator."
  [{:keys [^Storage storage]} bucket prefix]
  (let [options (directory-options prefix)
        blobs (.list storage bucket options)]
    (.iterateAll blobs)))

(defn list-directory
  "Return a list of storage keys in bucket:path format from the provided directory"
  [state bucket directory]
  (map
   (fn [x]
     (str bucket ":" (.getName x)))
   (list-prefix state bucket (dir-slash directory))))

(defn download-tree!
  "Download a file tree from the bucket:key directory to the local path."
  [state bucket key path]
  (let [directory (dir-slash key)
        blobs (list-prefix state bucket directory)
        prefix-length (count directory)]
    (doseq [blob blobs]
      (let [remote-key (.getName blob)
            relative-path (.substring remote-key prefix-length)
            local-path (join-path [path relative-path])]
        (download-blob! blob local-path)))))

(defn project-zone
  []
  (let [zone (log/shell-out "gcloud" "config" "get-value" "compute/zone")
        zone (if (= zone "")
               log/gce-zone
               zone)]
    {:project (log/shell-out "gcloud" "config" "get-value" "core/project")
     :zone zone}))

(defn create-compute-service
  "Create an instance of com.google.api.services.compute.Compute to make requests with."
  ([]
   (let [{:keys [project zone]} (project-zone)]
     (create-compute-service project zone)))
  ([project zone]
   (let [transport (GoogleNetHttpTransport/newTrustedTransport)
         factory (JacksonFactory/getDefaultInstance)
         auth (into-array ["https://www.googleapis.com/auth/cloud-platform"])
         credential (GoogleCredential/getApplicationDefault)
         credential (if (.createScopedRequired credential)
                      (.createScoped credential (Arrays/asList auth))
                      credential)
         builder (Compute$Builder. transport factory credential)]
     (.setApplicationName builder "Gaia/0.0.1")
     {:service ^Compute (.build builder)
      :project project
      :zone zone})))

(defn render-filter
  "Render a map of options into the weird format that the compute instances api expects"
  [instance-filter]
  (string/join
   " "
   (map
    (fn [[k v]]
      (format
       "(%s = %s)"
       (name k)
       (with-out-str (pr v))))
    instance-filter)))

(defn list-instances
  "Given a compute service instance, project and zone for instances, return information on those
  instances. Also accepts an optional `options` arg that could contain the following keys:
      * :filter - a map of keys to values to filter the list of instances."
  ([compute]
   (list-instances compute {}))
  ([{:keys [^Compute service project zone]} options]
   (let [request (.list (.instances service) project zone)]
     (if-let [instance-filter (:filter options)]
       (.setFilter request (render-filter instance-filter)))
     (loop [response (.execute request)
            instances []]
       (let [items (.getItems response)
             next (.getNextPageToken response)
             expansion (concat instances items)]
         (.setPageToken request next)
         (if (and items next)
           (recur (.execute request) expansion)
           expansion))))))
