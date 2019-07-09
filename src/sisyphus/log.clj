(ns sisyphus.log
  (:import
    [java.util.logging Level Logger]))

(def ^:dynamic *logger*
  "Set up the logger(s)."
  ; TODO(jerry): Pass maps directly to the Stackdriver API or use logger object
  ;   params? Support Stackdriver logging levels?
  ; TODO(jerry): Get the Logging.Handler to call flush() before exiting?
  (do
    (System/setProperty "java.util.logging.config.file"
                        "resources/config/logging.properties")
    (Logger/getLogger "sisyphus")))

(def fine Level/FINE)
(def info Level/INFO)
(def warn Level/WARNING)
(def severe Level/SEVERE)

(defn log!
  [^Level level & x]
  (.log *logger* level (str x)))

(defn fine!
  [& x]
  (.fine *logger* (str x)))

(defn info!
  [& x]
  (.info *logger* (str x)))

(defn warn!
  [& x]
  (.warning *logger* (str x)))

(defn severe!
  [& x]
  (.severe *logger* (str x)))

(defn exception!
  [message ^Throwable throwable]
  (.log *logger* severe (str message) throwable))
