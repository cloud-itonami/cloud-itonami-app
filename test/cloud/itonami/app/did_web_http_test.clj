(ns cloud.itonami.app.did-web-http-test
  "The did:web route follows the shipped core, not two string equals.

  A handler that kept `(= method \"GET\") (= path \"/.well-known/did.json\")`
  and dropped the oracle call would still 200 once a domain is published, and
  `credential-http-test` would stay green. Invert the artifact; this path must
  stop answering, and `/health` must keep answering (different oracle id).

  Host→tenant resolution and the 404 when no domain is published stay on the
  host. This file publishes a domain so the route would 200 without Kotoba."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.did-web :as did-web]
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

(def ^:private path "/.well-known/did.json")

(def ^:private config
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

(defn- request [method p]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:"
                                  (.getPort (.getAddress @server/server))
                                  p)))
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
                   "cloud-itonami-app-did-web-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/configure! (fn [_] nil)
                    local-identity/did-web-domain-for-host (fn [_] "acme.example")]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(def ^:private inverted-kir
  (:kir (compiler/compile-source
         (str "(ns cloud.itonami.app.did-web"
              "  (:export [did-web-route?]))"
              "(defn did-web-route? [method :string path :string] :bool"
              "  (if (and (string=? method \"GET\")"
              "           (string=? path \"/.well-known/did.json\"))"
              "    false true))")
         gen/target {})))

(deftest get-did-web-answers-when-a-domain-is-published
  (with-server
    (fn []
      (let [ok (request :get path)]
        (is (= 200 (:status ok)))
        (is (= "did:web:acme.example" (get-in ok [:body :id]))))
      (is (not= 200 (:status (request :post path)))
          "POST is not the did:web document"))))

(deftest the-handler-follows-the-artifact-and-leaves-health-alone
  (with-server
    (fn []
      (is (= 200 (:status (request :get path))) "the shipped answer")
      (is (= 200 (:status (request :get "/health"))) "health is a different core")
      (try
        (oracle/register-kir! :did-web inverted-kir)
        (is (false? (did-web/did-web-route? "GET" path))
            "the host followed the artifact")
        (is (not= 200 (:status (request :get path)))
            "the handler followed it too — a copy of the two equals in
             server.clj would still 200")
        (is (true? (health/health-route? "GET" "/health")))
        (is (= 200 (:status (request :get "/health")))
            "inverting did:web discovery must not stop liveness")
        (finally (oracle/deregister-kir! :did-web)))
      (is (= 200 (:status (request :get path))) "restored")
      (is (= 200 (:status (request :get "/health"))) "health still restored"))))
