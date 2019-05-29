(ns sisyphus.archive
  (:require
   [clojure.java.io :as io])
  (:import
   [java.util UUID]
   [java.util.zip GZIPInputStream GZIPOutputStream]
   [java.nio.file Paths]
   [org.apache.commons.io FilenameUtils]
   [org.apache.commons.compress.archivers.tar
    TarArchiveInputStream
    TarArchiveOutputStream
    TarArchiveEntry]))

(defn path-for
  [path]
  (.toPath (io/file path)))

(defn up-path
  [s up]
  (let [path (path-for s)]
    (try
      (.toString (.subpath path up (.getNameCount path)))
      (catch IllegalArgumentException e
        ""))))

(defn base-name
  [path]
  (when-not (empty? path)
    (FilenameUtils/getBaseName path)))

(defn tar-input-stream
  [path]
  (TarArchiveInputStream.
   (GZIPInputStream.
    (io/input-stream path))))

(defn tar-output-stream
  [path]
  (TarArchiveOutputStream.
   (GZIPOutputStream.
    (io/output-stream path))))

(defn tar-gz-seq
  [archive]
  (when-let [item (.getNextTarEntry archive)]
    (cons item (lazy-seq (tar-gz-seq archive)))))

(defn directory-path?
  [path]
  (= (last path) \/))

(defn trim-slash
  [path]
  (if (directory-path? path)
    (.substring path 0 (dec (.length path)))
    path))

(defn pack!
  [tar-path folder-path]
  (let [out (tar-output-stream tar-path)
        folder-path (trim-slash folder-path)
        folder-base (base-name folder-path)]
    (doseq [inf (file-seq (io/file folder-path)) :when (.isFile inf)]
      (let [entry-path (.replaceFirst
                        (.getPath inf)
                        (str folder-path "/")
                        (str folder-base "/"))
            entry (TarArchiveEntry. entry-path)]
        (.setSize entry (.length inf))
        (.putArchiveEntry out entry)
        (io/copy (io/input-stream inf) out)
        (.closeArchiveEntry out)))
    (.finish out)
    (.close out)
    (io/file tar-path)))

(defn unpack!
  [tar-file out-path]
  (let [tar-file (io/file tar-file)
        out-path (io/file out-path)
        archive (tar-input-stream tar-file)]
    (.mkdirs out-path)
    (doseq [entry (tar-gz-seq archive)]
      (let [entry-path (up-path (.getName entry) 1)
            out-file (io/file (str out-path "/" entry-path))]
        (.mkdirs (.getParentFile out-file))
        (with-open [outf (io/output-stream out-file)]
          (let [bytes (byte-array 32768)]
            (loop [nread (.read archive bytes 0 32768)]
              (when (> nread -1)
                (.write outf bytes 0 nread)
                (recur (.read archive bytes 0 32768))))))))))
