(ns cloud.itonami.app.secret-card-http-test
  "The credential card on the wire.

  `secret-card-test` proves the store and the transcript; this proves the two
  things only the route decides: which status a refusal gets, and what the
  response body contains. Both were wrong in the first version of this change
  and neither was visible from the namespace — every new refusal fell through
  to the default 400, so an agent session being refused the ACT and a malformed
  request were reported identically."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.cloudflare :as cloudflare]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.domain-tools :as domain-tools]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.secret-store :as secret-store]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace-tools :as workspace-tools])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "secret-csrf")
(def ^:private token "abcdefghij0123456789_-ABCDEFGHIJKLMNOPQR")
(def ^:private account "0123456789abcdef0123456789abcdef")

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

;; The session KIND is what the route's first refusal turns on, so it is a knob
;; rather than a constant. Everything else about the session is fixed.
(def ^:private session-kind (atom :passkey))

(defn- call [method path body]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server)) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin)
                    (.header "X-CLOUD-ITONAMI-CSRF" csrf))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :raw (.body response)
     :body (try (json/read-str (.body response) :key-fn keyword)
                (catch Exception _ nil))}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-secret-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (reset! session-kind :passkey)
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] {:csrf csrf :user-id "alice"
                                              :organization-id "org-1"
                                              :kind @session-kind
                                              :authn-level :phishing-resistant})
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)
                    workspace-tools/admit-root (fn [path] path)
                    domain-tools/answerable? (constantly true)
                    policy/select-provider (fn [_ _] {:id :local})
                    provider/agent-turn
                    (fn [_ _] {:content "調べます。"
                               :tool-calls [{:id "c1" :name "domain_registrations"
                                             :input {}}]})
                    ;; `with-redefs`, NOT `binding`. The server answers on its
                    ;; own thread, so a thread-local binding of these seams does
                    ;; not reach the request -- and the failure is silent and
                    ;; specific: the real `security` runs, the write succeeds,
                    ;; and the test passes while having put an item in the
                    ;; developer's login keychain. Measured 2026-09-09, on the
                    ;; first run of this file, which did exactly that.
                    secret-store/*environment* (constantly nil)
                    secret-store/*run* (constantly nil)
                    secret-store/*write* (constantly true)
                    cloudflare/*environment* (constantly nil)]
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (reset! store/state previous)))))

(defn- steward-bot-with-card []
  (bots/provision-workforce!
   {} {:user-id "alice" :organization-id "org-1" :kind :passkey}
   {:schema "network.awai.workforce-bots.v1"
    :businesses 1
    :roles [{:key "cloud-itonami/domain-steward"
             :business {:id :cloud-itonami :name "Cloud Itonami"}
             :role {:id :domain-steward :name "Domain Steward" :job :operations}
             :objective "Inspect the domain portfolio."
             :responsibilities ["Never bypass Passkey"]
             :capabilities [{:capability :domain.read :decision :autonomous}]
             :workspace "orgs/cloud-itonami/cloud-itonami-app"
             :cadence-minutes 15}]
    :source {:path "/registry"}})
  (let [bot-id (:id (first (:bots (bots/overview {} {:user-id "alice"
                                                     :organization-id "org-1"
                                                     :kind :passkey}))))]
    (bots/send! nil {:user-id "alice" :organization-id "org-1" :kind :passkey}
                bot-id "ドメインを確認して")
    [bot-id (->> (get-in (store/snapshot) [:bots :conversations bot-id])
                 (mapcat :message/cards)
                 (filter #(= :secret (:card/kind %)))
                 last :card/id)]))

(deftest a-refusal-gets-the-status-it-means
  (with-server
    (fn []
      (let [[bot-id card-id] (steward-bot-with-card)
            path (str "/api/bots/" bot-id "/cards/" card-id "/secret")]
        (is (some? card-id) "the turn produced a card to answer")
        (testing "an agent session is forbidden, not malformed"
          (reset! session-kind :agent)
          (let [{:keys [status body]} (call :post path {:value account})]
            (is (= 403 status))
            ;; `agent-session-forbidden` rather than the namespace's own
            ;; `human-session-required`: the whole Bots handler is behind a
            ;; human-session gate, so the request never reaches
            ;; `provide-secret!`. That is the answer this route should give and
            ;; it is recorded here rather than assumed -- the second refusal,
            ;; inside `provide-secret!`, is for callers that are not this route.
            (is (= "agent-session-forbidden" (:type (:error body))))))
        (reset! session-kind :passkey)
        (testing "a card that does not exist is not found"
          (let [{:keys [status body]}
                (call :post (str "/api/bots/" bot-id "/cards/card-nope/secret")
                      {:value account})]
            (is (= 404 status))
            (is (= "no-card" (:type (:error body))))))
        (testing "a value of the wrong shape IS the caller's to fix"
          (let [{:keys [status body]} (call :post path {:value "nope"})]
            (is (= 400 status))
            (is (= "refused" (:type (:error body))))))
        (testing "and a keychain that will not write is this machine's fault"
          ;; Same reason as the fixture: the write happens on the server's
          ;; thread, so this has to be a root rebinding.
          (with-redefs [secret-store/*write* (constantly false)]
            (let [{:keys [status body]} (call :post path {:value account})]
              (is (= 500 status))
              (is (= "keychain-error" (:type (:error body)))))))))))

(deftest no-response-on-this-route-carries-the-value
  ;; The route returns the conversation, and the conversation is what the next
  ;; turn sends to a model. Checked on the RAW body rather than a parsed field,
  ;; because a value could arrive in a key this test did not think to read.
  (with-server
    (fn []
      (let [[bot-id card-id] (steward-bot-with-card)
            path (str "/api/bots/" bot-id "/cards/" card-id "/secret")]
        (testing "not in a refusal"
          (let [{:keys [raw]} (call :post path {:value (str token "x")})]
            (is (not (str/includes? raw token)))))
        (testing "and not in the success it returns"
          (let [{:keys [status raw]} (call :post path {:value account})]
            (is (= 200 status))
            (is (not (str/includes? raw account)))
            ;; `clojure.data.json` escapes the solidus, so the wire spells it
            ;; `keychain:\/\/`. Matching on the scheme rather than the escaped
            ;; form keeps this about the locator and not about JSON.
            (is (str/includes? raw "keychain:")
                "what it does carry is where the value went")))))))
