(ns sisyphus.kafka
  (:require
   [cheshire.core :as json]
   [cheshire.factory :as factory]
   [kinsky.client :as kafka]))

(def poll-interval Long/MAX_VALUE)
(def non-numeric-factory
  (factory/make-json-factory {:allow-non-numeric-numbers true}))

(defn server-for
  [config]
  (str (:host config) ":" (:port config)))

(defn producer-config
  [config]
  {:bootstrap.servers (server-for config)})

(defn boot-producer
  [config]
  (kafka/producer
   (producer-config config)
   (kafka/keyword-serializer)
   (kafka/json-serializer)))

(defn send!
  [producer topic message]
  (kafka/send!
   producer
   {:topic topic
    :value message}))

(defn consumer-config
  [config]
  {:bootstrap.servers (server-for config)
   :enable.auto.commit "true"
   :auto.commit.interval.ms "1000"
   :group.id (get config :group-id "flow")
   :auto.offset.reset "latest"})

(defn boot-consumer
  [config]
  (kafka/consumer
   (consumer-config config)
   (kafka/keyword-deserializer)
   (kafka/json-deserializer)))

(defn handle-message
  [state producer handle record]
  (try
    (if (= (first record) :by-topic)
      (let [topics (last record)]
        (doseq [[topic messages] topics]
          (doseq [message messages]
            (println topic ":" message)
            (let [value {topic (:value message)}]
              (handle state producer topic (:value message)))))))
    (catch Exception e
      (println (.getMessage e))
      (.printStackTrace e))))

(defn consume
  [consumer handle]
  (binding [factory/*json-factory* non-numeric-factory]
    (loop [records
           (try
             (kafka/poll! consumer poll-interval)
             (catch Exception e
               (println (.getMessage e))))]
      (when-not (empty? records)
        (doseq [record records]
          (handle record)))
      (recur (kafka/poll! consumer poll-interval)))))

(defn boot-kafka
  [state config]
  (let [producer (boot-producer config)
        consumer (boot-consumer config)
        handle (get config :handle-message (fn [_ _]))]
    (doseq [topic (:subscribe config)]
      (println "subscribing to topic" topic)
      (kafka/subscribe! consumer topic))
    {:config config
     :producer producer
     :consumer
     (future
       (consume
        consumer
        (partial handle-message state producer handle)))}))
