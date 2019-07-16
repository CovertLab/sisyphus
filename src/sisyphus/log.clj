(ns sisyphus.log
  (:import
    [java.io PrintWriter StringWriter]
    [java.util Collections]
    [com.google.cloud MonitoredResource MonitoredResource$Builder]
    [com.google.cloud.logging LogEntry LogEntry$Builder Logging LoggingOptions
     Logging$WriteOption Payload Payload$StringPayload Severity]))

(defn- monitored-resource
  "Build a loggable MonitoredResource with a name-tag label."
  [^String tag]
  (let [builder ^MonitoredResource$Builder (MonitoredResource/newBuilder "gce_instance")]
    ; TODO(jerry): Add the "instance_id" and "zone" labels.
    (-> builder (.addLabel "tag" tag) .build)))

(defn- make-logger
  "Make a named logger with a name-tag label."
  [^String name]
  ; TODO(jerry): Assert that the name matches '[-.\w]+'. / is also OK if URL-encoded.
  {:logging ^Logging (.getService (LoggingOptions/getDefaultInstance))
   :name name
   :resource (monitored-resource name)})

(def ^:dynamic *logger*
  (make-logger "sisyphus"))

(defn tag
  "Call f in a context of a named logger."
  ; TODO(jerry): Use this at the top level with the server type name or
  ;  type + instance name, also for each task to perform with the task name.
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
  (let [{:keys [logging name resource]} *logger*
        entry
        (-> ^LogEntry$Builder (LogEntry/newBuilder payload)
            (.setSeverity severity)
            (.setLogName name)
            (.setResource resource)
            ; TODO(jerry): .setOperation w/ID, producer e.g. to distinguish
            ;  task uploads, downloads, and lines.
            .build)
        entries (Collections/singletonList entry)]
    (.write logging entries (make-array Logging$WriteOption 0))
    ; Could (.flush logging) or configure BatchingSettings.
    ))

(defn- log-string!
  "Log a string message."
  [^Severity severity ^String message]
  (log-entry! severity (Payload$StringPayload/of message))
  (println (str severity ": " message)))

(def debug Severity/DEBUG)
(def info Severity/INFO) ; routine info
(def notice Severity/NOTICE) ; significant events like start up, shut down, or configuration
(def warning Severity/WARNING) ; might cause problems
(def severe Severity/ERROR) ; likely to cause problems
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

(defn severe!
  [& x]
  (apply log! severe x))

(defn exception!
  [^Throwable throwable & x]
  ; TODO(jerry): LogEntryBuilder.setSourceLocation().
  (apply log! severe (concat x [(stack-trace throwable)])))
