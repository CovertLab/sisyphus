(ns sisyphus.core
  (:require
   [taoensso.timbre :as log]))

(defn -main
  [& args]
  (try
    (log/info "start")
    (let [signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (println "sleeeeeeeep")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      (log/info "signal added")
      @(promise))))
