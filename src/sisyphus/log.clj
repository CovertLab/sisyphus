(ns sisyphus.log
  (:require
    [clojure.string :as string]
    [clj-http.client :as http])
  (:import
    [java.io PrintWriter StringWriter]
    [java.util Collections]
    [com.google.cloud MonitoredResource MonitoredResource$Builder]
    [com.google.cloud.logging LogEntry LogEntry$Builder Logging LoggingOptions
     Logging$WriteOption Payload Payload$StringPayload Severity]))

(defn gce-metadata
  "Retrieve a GCE instance metadata field."
  [fieldname default]
  (try
    (:body
      (http/get
       (str "http://metadata.google.internal/computeMetadata/v1/instance/" fieldname)
       {:headers
        {:metadata-flavor "Google"}}))
    (catch Exception e default)))

(def gce-instance-name
  (gce-metadata "name" "local"))

(def gce-zone
  (last (string/split (gce-metadata "zone" "not-on-GCE") #"/")))

(defn- monitored-resource
  "Build a loggable MonitoredResource with a name-tag label."
  ^MonitoredResource [^String tag]
  (-> ^MonitoredResource$Builder (MonitoredResource/newBuilder "gce_instance")
      (.addLabel "tag" tag)
      (.addLabel "instance_id" gce-instance-name)
      (.addLabel "zone" gce-zone)
      .build))

(def -logging
  ^Logging (.getService (LoggingOptions/getDefaultInstance)))

(defn- make-logger
  "Make a named logger with a name-tag label.
  The name must match '[-.\\w]+'; / is also OK if URL-encoded."
  [^String name]
  {:name name
   :resource (monitored-resource name)})

(def ^:dynamic *logger*
  (make-logger gce-instance-name))

(defn tag
  "Call f in a context of a named logger."
  [name f]
  (binding [*logger* (make-logger name)]
    (f)))

(defn stack-trace
  "Get a Throwable's stack trace."
  [^Throwable throwable]
  (let [w (StringWriter.)
        p (PrintWriter. w)]
    (.printStackTrace throwable p)
    (.flush w)
    (.toString w)))

(defn- log-entry!
  "Log an entry."
  ; TODO(jerry): Log a sequence of entries at once from Docker output lines.
  [^Severity severity ^Payload payload]
  (let [{:keys [name resource]} *logger*
        entry
        (-> ^LogEntry$Builder (LogEntry/newBuilder payload)
            (.setSeverity severity)
            (.setLogName name)
            (.setResource resource)
            ; TODO(jerry): .setOperation w/ID, producer e.g. to distinguish
            ;  task uploads, downloads, and lines.
            .build)
        entries (Collections/singletonList entry)]
    (.write -logging entries (make-array Logging$WriteOption 0))
    (if (>= (.ordinal severity) (.ordinal Severity/NOTICE))
      (.flush -logging))))

(defn- log-string!
  "Log a string message."
  [^Severity severity ^String message]
  (log-entry! severity (Payload$StringPayload/of message))
  (println (str severity ": " message)))

;; Stackdriver log severity levels from lowest to highest priority.
(def debug Severity/DEBUG)
(def info Severity/INFO) ; routine info
(def notice Severity/NOTICE) ; significant events like start up, shut down, or configuration
(def warning Severity/WARNING) ; might cause problems
(def error Severity/ERROR) ; likely to cause problems
;(def critical Severity/CRITICAL) ; severe problems or brief outagaes
;(def alert Severity/ALERT) ; a person should take action immediately
;(def emergeny Severity/EMERGENCY) ; one or more systems are unusable

(defn log!
  [^Severity severity & x]
  (log-string! severity (clojure.string/join " " x)))

; TODO(jerry): log-map! via Payload$JsonPayload/of

(defn debug!
  [& x]
  (apply log! debug x))

(def fine! debug!)

(defn info!
  [& x]
  (apply log! info x))

(defn notice!
  [& x]
  (apply log! notice x))

(defn warn!
  [& x]
  (apply log! warning x))

(defn error!
  [& x]
  (apply log! error x))

(def severe! error!)

(defn exception!
  [^Throwable throwable & x]
  ; TODO(jerry): LogEntryBuilder.setSourceLocation().
  (apply log! error (concat x [(stack-trace throwable)])))
