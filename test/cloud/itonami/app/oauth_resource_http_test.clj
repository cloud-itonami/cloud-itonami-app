(ns cloud.itonami.app.oauth-resource-http-test
  "The RFC 9728 discovery route follows the shipped core, not two string equals.

  A handler that kept `(= method \"GET\") (= path \"/.well-known/...\")` and
  dropped the oracle call would still 200, and `mcp-http-test` would stay
  green. Invert the artifact; this path must stop answering, and `/health`
  must keep answering (different oracle id)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.health :as health]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.kotoba-oracle-gen :as gen]
            [cloud.itonami.app.oauth-resource :as oauth-resource]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [kotoba.compiler.core :as compiler])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private discovery "/.well-known/oauth-protected-resource/mcp")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0
            :public-origin "https://itonami.cloud"
            :webauthn-rp-id "itonami.cloud"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"
             :cloud-enabled? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]
   :mcp {:oauth {:authorization-servers ["https://auth.itonami.cloud"]}}})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- request [method path]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:"
                                  (.getPort (.getAddress @server/server))
                                  path)))
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
                   "cloud-itonami-app-oauth-http"
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
         (str "(ns cloud.itonami.app.oauth-resource"
              "  (:export [oauth-resource-route?]))"
              "(defn oauth-resource-route? [method :string path :string] :bool"
              "  (if (and (string=? method \"GET\")"
              "           (string=? path \"/.well-known/oauth-protected-resource/mcp\"))"
              "    false true))")
         gen/target {})))

(deftest get-rfc-9728-discovery-answers
  (with-server
    (fn []
      (let [ok (request :get discovery)]
        (is (= 200 (:status ok)))
        (is (= "https://itonami.cloud/mcp" (get-in ok [:body :resource])))
        (is (= ["https://auth.itonami.cloud"]
               (get-in ok [:body :authorization_servers]))))
      (is (not= 200 (:status (request :post discovery)))
          "POST is not the RFC 9728 document"))))

(deftest the-handler-follows-the-artifact-and-leaves-health-alone
  (with-server
    (fn []
      (is (= 200 (:status (request :get discovery))) "the shipped answer")
      (is (= 200 (:status (request :get "/health"))) "health is a different core")
      (try
        (oracle/register-kir! :oauth-resource inverted-kir)
        (is (false? (oauth-resource/oauth-resource-route? "GET" discovery))
            "the host followed the artifact")
        (is (not= 200 (:status (request :get discovery)))
            "the handler followed it too — a copy of the two equals in
             server.clj would still 200")
        (is (true? (health/health-route? "GET" "/health")))
        (is (= 200 (:status (request :get "/health")))
            "inverting RFC 9728 discovery must not stop liveness")
        (finally (oracle/deregister-kir! :oauth-resource)))
      (is (= 200 (:status (request :get discovery))) "restored")
      (is (= 200 (:status (request :get "/health"))) "health still restored"))))
