{:kafka
 {:host "localhost"
  :port "9092"
  :subscribe ["gaia-control"]
  :status-topic "sisyphus-status"
  :log-topic "sisyphus-log"}

 :rabbit
 {}

 :local
 {:root "/tmp/sisyphus"}

 :timer
 {:initial 3000000
  :delay 300000}}
