(ns sisyphus.archive
  (:require
   [clojure.java.io :as io])
  (:import
   (java.util UUID)
   (java.util.zip GZIPInputStream GZIPOutputStream)
   (org.apache.commons.io FilenameUtils)
   (org.apache.commons.compress.archivers.tar
    TarArchiveInputStream
    TarArchiveOutputStream
    TarArchiveEntry)))

(defn base-name
  [path]
  (when-not (empty? path)
    (FilenameUtils/getBaseName path)))

(defn tar-gz-seq
  [tis]
  (when-let [item (.getNextTarEntry tis)]
    (cons item (lazy-seq (tar-gz-seq tis)))))

(defn create-archive
  [tar-path folder-path]
  (let [out (TarArchiveOutputStream.
             (GZIPOutputStream.
              (io/output-stream tar-path)))
        folder-base (base-name folder-path)]
    (doseq [inf (file-seq (io/file folder-path))
            :when (.isFile inf)]
      (let [entry-path (.replaceFirst
                        (.getPath inf)
                        (str folder-path "/")
                        (str (base-name folder-path) "/"))
            entry (TarArchiveEntry. entry-path)]
        (.setSize entry (.length inf))
        (.putArchiveEntry out entry)
        (io/copy (io/input-stream inf) out)
        (.closeArchiveEntry out)))
    (.finish out)
    (.close out)
    (io/file tar-path)))

(defn unpack-archive
  [tar-file out-path]
  (let [tar-file (io/file tar-file)
        out-path (io/file out-path)]
    (.mkdirs out-path)
    (when-not (.exists tar-file)
      (throw
       (Exception. (format "'%s' isn't actually there" tar-file))))
    (when-not (.exists out-path)
      (throw
       (Exception.
        (format "the path '%s' does not exist." out-path))))
    (let [tis (TarArchiveInputStream.
               (GZIPInputStream.
                (io/input-stream tar-file)))]
      (doseq [entry (tar-gz-seq tis)]
        (let [out-file (io/file (str out-path "/" (.getName entry)))]
          (.mkdirs (.getParentFile out-file))
          (with-open [outf (io/output-stream out-file)]
            (let [bytes (byte-array 32768)]
              (loop [nread (.read tis bytes 0 32768)]
                (when (> nread -1)
                  (.write outf bytes 0 nread)
                  (recur (.read tis bytes 0 32768)))))))))))
