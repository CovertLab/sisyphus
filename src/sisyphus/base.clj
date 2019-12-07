(ns sisyphus.base
  (:require
   [clojure.java.io :as io])
  (:import
   [java.time Duration]))

(defn format-duration
  "Format a java.time.Duration as a string like '1H26M12.345S'."
  [^Duration duration]
  (.substring (str duration) 2)) ; strip "PT" from the "PTnHnMnS" format

(def full-name
  "Extract the string representation of the given keyword. This is an extension to the
   built-in `name` function which fails to return the full string representation for
   namespaced keywords."
  (comp str symbol))

(defn split-key
  [key]
  (let [full-key (full-name key)
        colon (.indexOf full-key ":")]
    [(.substring full-key 0 colon) (.substring full-key (inc colon))]))

(defn write-lines!
  "Write a sequence of lines to a file, adding a newline to each line. Like
  `spit`, this opens f via io/writer, writes the lines, then closes f.
  See clojure.java.io/writer for the possibilities for f. Options docs?"
  [f lines & options]
  (with-open [^java.io.Writer w (apply io/writer f options)]
    (doseq [line lines]
      (.write w ^String (str line "\n")))))

(defn make-timer
  "Make a `future` as a timer that waits then calls `f`. Cancelling it can stop
  the timer but won't interrupt `f` midway (which could be bad for docker/stop!
  or apoptosis) since it runs `f` in an independent thread."
  [milliseconds f]
  (future
    (Thread/sleep milliseconds)
    (future (f))))

(defn cancel-timer
  "Cancel a timer if possible. nil safe. Return true if this cancelled it."
  [timer]
  (and timer (future-cancel timer)))


