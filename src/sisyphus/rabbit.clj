(ns sisyphus.rabbit
  (:require
   [taoensso.timbre :as log]
   [cheshire.core :as json]
   [langohr.core :as lcore]
   [langohr.channel :as lchannel]
   [langohr.exchange :as lexchange]
   [langohr.queue :as lqueue]
   [langohr.consumers :as lconsumers]
   [langohr.basic :as lbasic]))

(defn rabbit-connect!
  [config]
  (let [connection (lcore/connect)
        channel (lchannel/open connection)
        _ (lbasic/qos channel 1)
        queue-name (get config :queue "sisyphus")
        exchange (get config :exchange "")
        queue (lqueue/declare channel "sisyphus" {:exclusive false :durable true})
        routing-key (get config :routing-key "sisyphus")]
    (if-not (= exchange "")
      (lqueue/bind channel queue-name exchange {:routing-key routing-key}))
    {:queue queue
     :queue-name queue-name
     :exchange exchange
     :routing-key routing-key
     :connection connection
     :channel channel
     :config config}))

(defn handle-message
  [channel metadata ^bytes payload]
  (let [raw (String. payload "UTF-8")
        message (json/parse-string raw true)]
    (log/info "message received:" message)
    (lbasic/ack channel (:delivery-tag metadata))))

(defn start-consumer!
  [rabbit]
  (let [channel (:channel rabbit)
        queue-name (:queue-name rabbit)
        thread (Thread.
                (fn []
                  (lconsumers/subscribe channel queue-name handle-message)))]
    (.start thread)))

(defn publish!
  [rabbit message]
  (lbasic/publish
   (:channel rabbit)
   (:exchange rabbit)
   (:routing-key rabbit)
   (json/generate-string message)
   {:content-type "text/plain"}))

(defn close!
  [rabbit]
  (lcore/close (:channel rabbit))
  (lcore/close (:connection rabbit)))

(defn -main
  [& args]
  (try
    (println "sisyphus rises")
    (let [rabbit (rabbit-connect! {})
          consumer (start-consumer! rabbit)
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (close! rabbit)
                     (println "sisyphus sleeps....")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
