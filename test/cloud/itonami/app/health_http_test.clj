(ns cloud.itonami.app.health-http-test
  "The production health route follows the shipped core, not two string equals.

  A handler that kept `(= method \"GET\") (= path \"/health\")` and dropped the
  oracle call would still 200, and every other test in this repository would
  stay green. Invert the artifact; the process must stop answering."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.health :as health]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.kotoba-oracle-gen :as gen]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [kotoba.compiler.core :as compiler])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port []
  (.getPort (.getAddress @server/server)))

(defn- request [method path]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:" (bound-port) path)))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString "{}")))
        response (.send client (.build built)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (try (json/read-str (.body response) :key-fn keyword)
                (catch Exception _ {:raw (.body response)}))}))

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-health-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(def ^:private inverted-kir
  (:kir (compiler/compile-source
         (str "(ns cloud.itonami.app.health"
              "  (:export [health-route?]))"
              "(defn health-route? [method :string path :string] :bool"
              "  (if (and (string=? method \"GET\") (string=? path \"/health\"))"
              "    false true))")
         gen/target {})))

(deftest get-health-answers-the-liveness-contract
  (with-server
    (fn []
      (let [ok (request :get "/health")]
        (is (= 200 (:status ok)))
        (is (true? (get-in ok [:body :ok])))
        (is (= "cloud-itonami-app" (get-in ok [:body :service])))
        (is (= "cloud.itonami.app.health.v1" (get-in ok [:body :schema])))
        (is (= (config-loader/store-fingerprint) (get-in ok [:body :store]))
            "the probe says WHOSE store this process opened. Without it a client
             that finds a server here cannot tell it apart from a different
             install on the same port, which is how a CLI in a second checkout
             came to send its enrollment key to the resident server")
        (is (not (str/includes? (str (get-in ok [:body :store])) "/"))
            "a fingerprint, not a path: this route takes no session"))
      (is (not= 200 (:status (request :post "/health")))
          "POST /health is not the probe"))))

(deftest the-handler-follows-the-artifact
  (with-server
    (fn []
      (is (= 200 (:status (request :get "/health"))) "the shipped answer")
      (try
        (oracle/register-kir! :health inverted-kir)
        (is (false? (health/health-route? "GET" "/health"))
            "the host followed the artifact")
        (is (not= 200 (:status (request :get "/health")))
            "the handler followed it too — a copy of the two equals in
             server.clj would still 200")
        (finally (oracle/deregister-kir! :health)))
      (is (= 200 (:status (request :get "/health"))) "restored"))))
