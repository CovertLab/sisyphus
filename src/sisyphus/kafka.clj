(ns sisyphus.kafka
  (:require
   [cheshire.core :as json]
   [cheshire.factory :as factory]
   [kinsky.client :as kafka]
   [sisyphus.log :as log]))

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

(defn handle-message
  [handle record]
  (try
    (if (= (first record) :by-topic)
      (let [topics (last record)]
        (doseq [[topic messages] topics]
          (doseq [message messages]
            (let [value {topic (:value message)}]
              (handle topic (:value message)))))))
    (catch Exception e
      (log/exception! e "kafka-handle-message"))))

(defn consume
  [consumer handle]
  (binding [factory/*json-factory* non-numeric-factory]
    (loop [records
           (try
             (kafka/poll! consumer poll-interval)
             (catch Exception e
               (log/exception! e "kafka-consume")))]
      (when-not (empty? records)
        (doseq [record records]
          (handle record)))
      (recur (kafka/poll! consumer poll-interval)))))

(defn boot-consumer
  [config handle]
  (let [consumer (kafka/consumer
                  (consumer-config config)
                  (kafka/keyword-deserializer)
                  (kafka/json-deserializer))]
    (doseq [topic (:subscribe config)]
      (kafka/subscribe! consumer topic))
    {:consumer consumer
     :future (future
               (consume
                consumer
                (partial handle-message handle)))}))

(defn boot-kafka
  [config handle]
  (let [producer (boot-producer config)
        consumer (boot-consumer config handle)]
    (merge
     consumer
     {:config config
      :producer producer})))
