(ns cloud.itonami.app.sdk-fixture-server
  "Deterministic loopback server used only by the upstream SDK CI matrix."
  (:require [cloud.itonami.app.config :as config]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]))

(defn- fixture-result []
  {:content "cloud-itonami-sdk-ok"
   :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}})

(defn -main [& _]
  (let [port (Integer/parseInt
              (or (System/getenv "CLOUD_ITONAMI_SDK_FIXTURE_PORT")
                  "18473"))
        stopped (promise)
        base (config/load-config)
        configuration
        (-> base
            (assoc :server {:host "127.0.0.1" :port port
                            :public-origin (str "http://127.0.0.1:" port)
                            :webauthn-rp-id "127.0.0.1"})
            (assoc :routing {:default-provider "fixture"
                             :default-model "fixture-model"
                             :cloud-enabled? false})
            (assoc :providers
                   [{:id "fixture" :name "Fixture" :kind :ollama
                     :local? true :enabled? true}])
            (assoc :mcp {:enabled? true
                         :access-token-env "SDK_MCP_TOKEN"
                         :actor-user-id "sdk-user"
                         :clients []}))
        hook (Thread. #(deliver stopped true)
                      "cloud-itonami-sdk-fixture-shutdown")]
    (reset! store/state
            (assoc-in (store/initial-state)
                      [:identity :users "sdk-user"]
                      {:id "sdk-user" :passkey-enrolled? true}))
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (with-redefs
       [provider/chat (fn [_ _] (fixture-result))
        provider/chat-stream!
        (fn [_ _ on-delta]
          (on-delta "cloud-itonami-")
          (on-delta "sdk-ok")
          (fixture-result))]
        (server/start! configuration)
        (println (str "sdk-fixture-ready:" port))
        (flush)
        @stopped)
      (finally
        (server/stop!)
        (try
          (.removeShutdownHook (Runtime/getRuntime) hook)
          (catch IllegalStateException _))))))
