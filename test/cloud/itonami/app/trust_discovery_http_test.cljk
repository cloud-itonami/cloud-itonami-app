(ns cloud.itonami.app.trust-discovery-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private configuration
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0
            :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"
             :cloud-enabled? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- request [method path]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:"
                                  (.getPort (.getAddress @server/server)) path)))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString "{}")))
        response (.send client (.build built) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :cache (.firstValue (.headers response) "cache-control")
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-trust-discovery"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! configuration)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(deftest publishes-the-same-profile-on-discovery-and-api-paths
  (with-server
    (fn []
      (doseq [path ["/.well-known/kotoba-trust.json" "/api/v1/trust"]]
        (let [{:keys [status cache body]} (request :get path)]
          (is (= 200 status) path)
          (is (= "public, max-age=300" (.orElse cache nil)))
          (is (= "https://itonami.cloud" (get-in body [:service :origin])))
          (is (= "human-organization-operator" (get-in body [:service :role])))
          (is (false? (get-in body [:semantics :universalTrustScore])))
          (is (nil? (get-in body [:sources :erc8004 :registryBinding])))))
      (is (not= 200 (:status (request :post "/.well-known/kotoba-trust.json"))))
      (is (= "/.well-known/kotoba-trust.json"
             (get-in (request :get "/api/identity") [:body :trust-profile]))))))
