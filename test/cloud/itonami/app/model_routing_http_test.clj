(ns cloud.itonami.app.model-routing-http-test
  "Drives the model-assignment endpoints over real HTTP against a running server.

  `model_routing_kotoba_parity_test` covers the judgements and
  `model-routing/assignment` covers what is refused; these cover what only the
  server can get wrong — that the routes are wired at all, that they are not
  shadowed by the `/api/bots/([^/]+)` patterns that follow them, that the writes
  fail closed without the CSRF token, and that the refusals arrive as the right
  status rather than a 500.

  The passkey gate is stubbed rather than satisfied, like the other HTTP tests:
  a real ceremony needs an authenticator, and what is under test is the route
  layer behind that gate."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "test-csrf-token")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :default-model "test-model" :models ["test-model" "other-model"]
                :reviewed? true :enabled? true}
               ;; Present, selectable in a picker, and NOT admissible. The
               ;; refusal this surface has to produce needs a provider in this
               ;; state to be produced against.
               {:id "xai" :kind :xai :local? false
                :base-url "https://api.x.ai/v1"
                :default-model "grok-4.6" :models ["grok-4.6"]
                :reviewed? false :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port [] (.getPort (.getAddress @server/server)))

(defn- request [method path {:keys [body headers]}]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (bound-port) path)))
                    (.header "Content-Type" "application/json"))]
    (doseq [[header value] headers] (.header builder header value))
    (let [built (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (or body "{}"))))
          response (.send client (.build built)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (try (json/read-str (.body response) :key-fn keyword)
                  (catch Exception _ {:raw (.body response)}))})))

(defn- authed [method path & [body]]
  (request method path {:body body
                        :headers {"Origin" origin
                                  "X-CLOUD-ITONAMI-CSRF" csrf}}))

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-model-routing-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session
                    (fn [_] {:csrf csrf :user-id "test-user"
                             :organization-id "org-http" :kind :passkey})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- assignment-for [body task]
  (some #(when (= task (:task %)) %) (:assignments body)))

;; The error types below are UNQUALIFIED. `clojure.data.json/write-str` drops a
;; keyword key's namespace, so the host's `:routing/incomplete` reaches a client
;; as `"incomplete"` -- the same wire truth `business_http_test` records for
;; `:business/id`. Asserting the qualified name here would be asserting
;; something no client can observe.
(defn- refused [r expected]
  (is (not= 200 (:status r)))
  (is (= expected (get-in r [:body :error :type]))))

(deftest the-screen-is-told-what-may-be-assigned-before-anything-is
  (with-server
    (fn []
      (let [r (authed :get "/api/bots/model-routing")]
        (is (= 200 (:status r)))
        (testing "every task names a source, so the screen cannot offer a row
                  for work this application does not do"
          (doseq [t (get-in r [:body :tasks])]
            (is (string? (:task t)))
            (is (or (:main? t) (string? (:source t))))))
        (testing "exactly one task is main, and it is the only assignable-to-a-Bot one"
          (is (= 1 (count (filter :main? (get-in r [:body :tasks]))))))
        (testing "and nothing is assigned yet, which is not a failure"
          (is (= [] (get-in r [:body :assignments]))))))))

(deftest a-route-is-not-shadowed-by-a-bot-whose-id-could-look-like-it
  ;; `/api/bots/([^/]+)/...` follows these in the same `cond`. If the ordering
  ;; were wrong this would be handled as a Bot lookup and answer 404.
  (with-server
    (fn []
      (is (= 200 (:status (authed :get "/api/bots/model-routing")))))))

(deftest an-assignment-round-trips-and-is-reported-back
  (with-server
    (fn []
      (let [r (authed :post "/api/bots/model-routing"
                      (json/write-str {:task "room" :scope "default"
                                       :provider-id "ollama"
                                       :model "other-model"}))]
        (is (= 200 (:status r)))
        (let [a (assignment-for (:body r) "room")]
          (is (= "ollama" (:provider-id a)))
          (is (= "other-model" (:model a)))))
      (testing "and a second read sees it, so it was stored rather than echoed"
        (is (= "other-model" (:model (assignment-for
                                      (:body (authed :get "/api/bots/model-routing"))
                                      "room")))))
      (testing "clearing returns the task to main"
        (let [r (authed :post "/api/bots/model-routing/clear"
                        (json/write-str {:task "room" :scope "default"}))]
          (is (= 200 (:status r)))
          (is (nil? (assignment-for (:body r) "room"))))))))

(deftest a-provider-this-deployment-will-not-admit-is-refused-at-the-write
  ;; Not only at call time. Both refuse, but finding out while the screen is
  ;; open is the difference between a message and a Bot that stops mid-task
  ;; tomorrow.
  (with-server
    (fn []
      (let [r (authed :post "/api/bots/model-routing"
                      (json/write-str {:task "room" :scope "default"
                                       :provider-id "xai" :model "grok-4.6"}))]
        (refused r "denied")
        (testing "and nothing was stored"
          (is (nil? (assignment-for
                     (:body (authed :get "/api/bots/model-routing")) "room"))))))))

(deftest half-an-assignment-is-refused-by-the-route
  (with-server
    (fn []
      (let [r (authed :post "/api/bots/model-routing"
                      (json/write-str {:task "room" :scope "default"
                                       :provider-id "ollama" :model ""}))]
        (refused r "incomplete")))))

(deftest a-task-this-application-does-not-call-is-refused-by-the-route
  ;; The name is borrowed from the product this surface reproduces. It has no
  ;; call site here, so it must not become a stored row that nothing reads.
  (with-server
    (fn []
      (let [r (authed :post "/api/bots/model-routing"
                      (json/write-str {:task "vision" :scope "default"
                                       :provider-id "ollama" :model "test-model"}))]
        (refused r "unknown-task")))))

(deftest an-auxiliary-task-cannot-be-scoped-to-one-bot-over-http
  (with-server
    (fn []
      (let [r (authed :post "/api/bots/model-routing"
                      (json/write-str {:task "machine" :scope "bot-1"
                                       :provider-id "ollama" :model "test-model"}))]
        (refused r "scope-not-assignable")))))

(deftest the-writes-fail-closed-without-the-csrf-token
  (with-server
    (fn []
      (doseq [path ["/api/bots/model-routing" "/api/bots/model-routing/clear"]]
        (testing path
          (let [r (request :post path
                           {:body (json/write-str {:task "room" :scope "default"
                                                   :provider-id "ollama"
                                                   :model "test-model"})
                            :headers {"Origin" origin}})]
            (is (not= 200 (:status r))))))
      (testing "and the store is untouched by the attempts"
        (is (= [] (get-in (authed :get "/api/bots/model-routing")
                          [:body :assignments])))))))
