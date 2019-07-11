(ns sisyphus.log
  (:import
    [java.util.logging Level Logger]))

(def ^{:dynamic true :tag Logger} *logger*
  "Set up the loggers."
  ; REQUIRES: project.clj to declare
  ;   :jvm-opts ["-Djava.util.logging.config.file=resources/config/logging.properties"]
  ; TODO(jerry): Pass maps directly to the Stackdriver API or use logger object
  ;   params? Support Stackdriver logging levels?
  ; TODO(jerry): Fetch the Logging.Handler and flush() before exiting?
  (Logger/getLogger "sisyphus"))

(def fine Level/FINE)
(def info Level/INFO)
(def warn Level/WARNING)
(def severe Level/SEVERE)

(defn log!
  [^Level level & x]
  (.log *logger* level (str x "\n")))

(defn fine!
  [& x]
  (.fine *logger* (str x "\n")))

(defn info!
  [& x]
  (.info *logger* (str x "\n")))

(defn warn!
  [& x]
  (.warning *logger* (str x "\n")))

(defn severe!
  [& x]
  (.severe *logger* (str x "\n")))

(defn exception!
  [message ^Throwable throwable]
  (.log *logger* severe (str message) throwable))
