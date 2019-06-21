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

(def config-keys
  [:host :port :username :vhost :password])

(defn connect!
  "Connect to the rabbitmq service. Accepts a `config` map containing several possible options:
     * :queue - name of the rabbit queue to connect to (default 'sisyphus')
     * :exchange - name of the exchange to connect to (defaults to global exchange '')
     * :routing-key - routing key to use for messages (defaults to 'sisyphus')
   Returns a map containing all of the rabbitmq connection information."
  [config]
  (let [connection (lcore/connect (select-keys config config-keys))
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

(defn start-consumer!
  "Given the rabbit connection map and a `handle` function, start a rabbit consumer listening
   to the queue and exchange represented by that connection, which calls `handle` each time it
   receives a message. The `handle` function takes three arguments, `channel`, `metadata` and
   `payload`, and an example is given in the below function `default-handle-message` in this ns."
  [rabbit handle]
  (let [channel (:channel rabbit)
        queue-name (:queue-name rabbit)
        thread (Thread.
                (fn []
                  (lconsumers/subscribe channel queue-name handle)))]
    (.start thread)))

(defn publish!
  "Publish a message on the given rabbitmq connection. The message will be rendered to JSON before
   sending, so you can pass in any renderable data structure, and strings will be further quoted."
  [rabbit message]
  (lbasic/publish
   (:channel rabbit)
   (:exchange rabbit)
   (:routing-key rabbit)
   (json/generate-string message)
   {:content-type "text/plain"}))

(defn close!
  "Close the connection represented by the given rabbitmq connection map."
  [rabbit]
  (lcore/close (:channel rabbit))
  (lcore/close (:connection rabbit)))

(defn default-handle-message
  "An example of the `handle` argument passed into `start-consumer!`. Takes three arguments that
   are provided by the rabbit consumer client:
     * channel - the channel to the rabbit service.
     * metadata - any information about the incoming message beyond its payload.
     * payload - bytes representing the message just received."
  [channel metadata ^bytes payload]
  (let [raw (String. payload "UTF-8")
        message (json/parse-string raw true)]
    (log/info "message received:" message)
    (lbasic/ack channel (:delivery-tag metadata))))

(defn -main
  [& args]
  (try
    (println "rabbbbbbbbbbit")
    (let [rabbit (connect! {})
          consumer (start-consumer! rabbit default-handle-message)
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (close! rabbit)
                     (println "(disappears into hole)")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
