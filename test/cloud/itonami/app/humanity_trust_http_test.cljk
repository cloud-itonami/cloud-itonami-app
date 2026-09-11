(ns cloud.itonami.app.humanity-trust-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.humanity-trust :as humanity]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "trust-csrf")
(def ^:private uid (str "0x" (apply str (repeat 64 "1"))))
(def ^:private configuration
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :privacy {:bind-loopback-only? true}
   :routing {:default-provider "ollama" :default-model "test"
             :cloud-enabled? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true
                :enabled? true}]})
(defonce ^:private client (HttpClient/newHttpClient))
(defonce ^:private current-session (atom nil))

(defn- call [headers]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create
                      (str "http://127.0.0.1:"
                           (.getPort (.getAddress @server/server))
                           "/api/v1/trust/human-passport/verify")))
                    (.header "Content-Type" "application/json"))
        builder (reduce (fn [b [k v]] (.header b k v)) builder headers)
        request (.POST builder
                       (HttpRequest$BodyPublishers/ofString
                        (json/write-str {:attestationUid uid})))
        response (.send client (.build request)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :cache (.orElse (.firstValue (.headers response) "cache-control") nil)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-humanity-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (reset! current-session {:csrf csrf :user-id "alice"
                               :organization-id "org-1" :kind :passkey
                               :authn-level :phishing-resistant})
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] @current-session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)
                    humanity/verify!
                    (fn [_ _ supplied]
                      {:schema "cloud.itonami.app.human-passport-step-up.v1"
                       :verified (= uid supplied)
                       :effect "evidence-only"
                       :grants-capability false})]
        (server/stop!)
        (server/start! configuration)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest step-up-requires-origin-and-csrf-and-grants-no-capability
  (with-server
    (fn []
      (is (= 403 (:status (call {}))))
      (let [response (call {"Origin" origin
                            "X-CLOUD-ITONAMI-CSRF" csrf})]
        (is (= 200 (:status response)))
        (is (= "no-store" (:cache response)))
        (is (:verified (:body response)))
        (is (= "evidence-only" (get-in response [:body :effect])))
        (is (false? (get-in response [:body :grants-capability])))))))

(deftest an-agent-session-cannot-submit-human-evidence
  (with-server
    (fn []
      (reset! current-session {:user-id "agent-a" :organization-id "org-1"
                               :kind :agent})
      (is (= 403 (:status
                  (call {"Origin" origin
                         "X-CLOUD-ITONAMI-CSRF" csrf})))))))
