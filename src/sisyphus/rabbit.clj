(ns sisyphus.rabbit
  (:require
   [clojure.tools.cli :as cli]
   [cheshire.core :as json]
   [langohr.core :as lcore]
   [langohr.channel :as lchannel]
   [langohr.exchange :as lexchange]
   [langohr.queue :as lqueue]
   [langohr.consumers :as lconsumers]
   [langohr.basic :as lbasic]
   [sisyphus.log :as log]))

(def connection-keys
  [:host :port :username :vhost :password])

(def default-config
  {:routing-key "sisyphus-task"
   :queue "sisyphus-queue"
   :exchange "sisyphus-exchange"})

(defn connect-rabbit!
  "Make a connection to rabbitmq using a map containing keys from `connection-keys`:
     * :host - Host of the rabbitmq server
     * :port - Port number for the server
     * :username - User account registered with rabbitmq
     * :vhost - Vhost is the first segment in the rabbit connection
     * :password - Password for account"
  [config]
  (lcore/connect (select-keys config connection-keys)))

(defn connect-queue!
  [connection config]
  "Create a new channel on the given connection."
  (let [config (merge default-config config)
        channel (lchannel/open connection)
        _ (lbasic/qos channel 1)
        queue-name (:queue config)
        exchange (:exchange config)
        _ (lexchange/declare channel exchange "direct")
        queue (lqueue/declare
               channel queue-name
               ; Critical: Not exclusive to one consumer, durable to survive a
               ; broker restart, and don't auto-delete so it won't drop messages
               ; when there are no consumers.
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

(defn connect!
  "Connect to the rabbitmq service. Accepts a `config` map containing several possible options:
     * :queue - name of the rabbit queue to connect to (default 'sisyphus-queue')
     * :exchange - name of the exchange to connect to (defaults to global exchange 'sisyphus-exchange')
     * :routing-key - routing key to use for messages (defaults to 'sisyphus-task')
   Returns a map containing all of the rabbitmq connection information."
  [config]
  (let [connection (connect-rabbit! config)]
    (connect-queue! connection config)))

(defn handle-message-wrapper
  "Given a function that takes a message, return a rabbit handler.
   Takes three arguments that are provided by the rabbit consumer client:
     * channel - the channel to the rabbit service.
     * metadata - any information about the incoming message beyond its payload.
     * payload - bytes representing the message just received."
  [handle]
  (fn
    [channel metadata ^bytes payload]
    (try
      (let [message (String. payload "UTF-8")]
        (handle message)
        (lbasic/ack channel (:delivery-tag metadata)))
      (catch Exception e
        (log/exception! e "step")))))

(defn start-consumer!
  "Given the rabbit connection map and a `handle` function, start a rabbit consumer
   listening to the queue and exchange represented by that connection, which calls
   `handle` each time it receives a message. The `handle` is a function of a single
   string argument, any parsing will have to be done in `handle`."
  [rabbit handle]
  (let [channel (:channel rabbit)
        queue-name (:queue-name rabbit)
        thread (Thread.
                (fn []
                  (lconsumers/subscribe
                   channel
                   queue-name
                   (handle-message-wrapper handle))))]
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
    :persistent true}))

(defn close!
  "Close the connection represented by the given rabbitmq connection map."
  [rabbit]
  (lcore/close (:channel rabbit))
  (lcore/close (:connection rabbit)))

(defn default-handle-message
  "An example of the `handle` argument passed into `start-consumer!`."
  [raw]
  (let [message (json/parse-string raw true)]
    (log/info! "rabbit message received:" message)))

(def parse-options
  [["-q" "--queue QUEUE" "queue to subscribe to"
    :default "sisyphus-queue"]
   ["-r" "--routing-key ROUTING_KEY" "routing key for messages"
    :default "sisyphus-task"]
   ["-e" "--exchange EXCHANGE" "message exchange"
    :default "sisyphus-exchange"]])

(defn -main
  "This main is for testing rabbit consumers. You can provide queue, exchange and routing
  options from `parse-options` at the command line and this will emit any messages it receives
  from that queue to console."
  [& args]
  (try
    (log/debug! "rabbbbbbbbbbit")
    (let [options (:options (cli/parse-opts args parse-options))
          rabbit (connect! options)
          maw (atom [])
          consumer (start-consumer!
                    rabbit
                    (fn [s]
                      (log/info! "RABBIT:" s)
                      (swap! maw conj s)))
          signal (reify sun.misc.SignalHandler
                   (handle [this signal]
                     (close! rabbit)
                     (log/debug! "(disappears into hole)")))]
      (sun.misc.Signal/handle (sun.misc.Signal. "INT") signal)
      @(promise))))
