(ns sisyphus.log)

(def ^:dynamic *debug* false)
(def ^:dynamic *trace* false)
(def ^:dynamic *info* false)
(def ^:dynamic *warn* false)
(def ^:dynamic *error* false)

(def log-levels
  [[:debug #'*debug*]
   [:trace #'*trace*]
   [:info #'*info*]
   [:warn #'*warn*]
   [:error #'*error*]])

(defn log-level
  [levels level]
  (into {}
   (map
    (juxt last (constantly true))
    (drop-while
     (comp (partial not= level) first)
     levels))))

(defn debug
  [& x]
  (when *debug*
    (apply println x)))

(defn trace
  [& x]
  (when *trace*
    (apply println x)))

(defn info
  [& x]
  (when *info*
    (apply println x)))

(defn warn
  [& x]
  (when *warn*
    (apply println x)))

(defn error
  [& x]
  (when *error*
    (apply println x)))

;; (def log-levels
;;   [:*debug*
;;    :*trace*
;;    :*info*
;;    :*warn*
;;    :*error*])

;; (defmacro define-levels
;;   [levels]
;;   (mapv
;;    (fn [level]
;;      `(def ^:dynamic (symbol (name @level)) false)`
;;      [level ((comp resolve symbol name) level)])
;;    levels))

;; (defn app
;;   [config]
;;   (when *info*
;;     (println "info level engaged"))
;;   (when *error*
;;     (println "error level engaged")))

;; (defn top-level
;;   [config log-levels]
;;   (let [level (get-in config [:log :level])
;;         levels (define-levels log-levels)
;;         bindings (log-level levels level)]
;;     (with-bindings levels
;;       (app config))))

