(ns sisyphus.rabbit
  (:require
   [cheshire.core :as json]
   [langohr.core :as lcore]
   [langohr.channel :as lchannel]
   [langohr.exchange :as lexchange]
   [langohr.queue :as lqueue]
   [langohr.consumers :as lconsumers]
   [langohr.basic :as lbasic]
   [sisyphus.log :as log]))

(def config-keys
  [:host :port :username :vhost :password])

(def default-config
  {:routing-key "sisyphus-task"
   :queue "sisyphus-queue"
   :exchange "sisyphus-exchange"})

(defn connect!
  "Connect to the rabbitmq service. Accepts a `config` map containing several possible options:
     * :queue - name of the rabbit queue to connect to (default 'sisyphus-queue')
     * :exchange - name of the exchange to connect to (defaults to global exchange 'sisyphus-exchange')
     * :routing-key - routing key to use for messages (defaults to 'sisyphus-task')
   Returns a map containing all of the rabbitmq connection information."
  [config]
  (let [config (merge default-config config)
        connection (lcore/connect (select-keys config config-keys))
        channel (lchannel/open connection)
        _ (lbasic/qos channel 1)
        queue-name (:queue config)
        exchange (:exchange config)
        _ (lexchange/declare channel exchange "direct")
        queue (lqueue/declare
               channel queue-name
               {:exclusive false
                :durable true
                :auto-delete false})
        routing-key (:routing-key config)]
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
   {:content-type "text/plain"
    :peristent true}))

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
    (log/info! "rabbit message received:" message)
    (lbasic/ack channel (:delivery-tag metadata))))

(defn -main
  [& args]
  (try
    (log/debug! "rabbbbbbbbbbit")
    (let [rabbit (connect! {})
          consumer (start-consumer! rabbit default-handle-message)
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (close! rabbit)
                     (log/debug! "(disappears into hole)")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
