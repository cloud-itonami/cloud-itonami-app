(ns cloud.itonami.app.worker-http-test
  "Drives the worker endpoints over real HTTP against a running server.

  The function-level worker tests cover queue semantics; these cover the parts
  only the server can get wrong — routing, status codes, the JSON envelope, and
  the origin/CSRF gate. The passkey gate is stubbed rather than satisfied: a
  real ceremony needs an authenticator, and what is under test here is the
  route layer behind that gate."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.worker :as worker])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "test-csrf-token")

(def ^:private config
  {:brand {:name "Test"}
   ;; Port 0 lets the OS pick a free port, so a stray server on 1338 — or
   ;; another session — cannot make this test flake.
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :worker {:max-runs 50}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- bound-port []
  (.getPort (.getAddress @server/server)))

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
                   "cloud-itonami-app-worker-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state
        previous-runs @worker/runs]
    (try
      (reset! store/state (store/initial-state))
      (reset! worker/runs [])
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    ;; Stand in for a completed passkey ceremony.
                    local-identity/session (fn [_] {:csrf csrf :user-id "test-user"})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)
                    provider/chat-stream!
                    (fn [_ _ on-delta]
                      (on-delta "整理")
                      (on-delta "しました")
                      {:content "整理しました" :usage {:total_tokens 4}})]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)
        (reset! worker/runs previous-runs)))))

(deftest worker-endpoints-round-trip-over-http
  (with-server
    (fn []
      (let [queued (authed :post "/api/workers"
                           (json/write-str {:prompt "受信トレイを整理して"
                                            :title "受信整理"}))]
        (is (= 202 (:status queued)))
        (is (= "queued" (get-in queued [:body :status])))
        (is (= "受信整理" (get-in queued [:body :title])))
        (is (string? (get-in queued [:body :id])))

        (is (true? (worker/await-idle! 10000)))

        (let [listed (authed :get "/api/workspace/worker")
              run (first (get-in listed [:body :items]))]
          (is (= 200 (:status listed)))
          (is (= "cloud.itonami.app.worker.v1" (get-in listed [:body :schema])))
          (is (= 0 (get-in listed [:body :active])))
          (is (= 1 (get-in listed [:body :counts :done])))
          (is (= (get-in queued [:body :id]) (:id run)))
          (is (= "done" (:status run)))
          (is (= "整理しました" (:output run)))
          (is (= "ollama" (:provider run))))

        ;; A finished run cannot be cancelled, and the queue survives the try.
        (let [late (authed :post (str "/api/workers/"
                                      (get-in queued [:body :id]) "/cancel"))]
          (is (= 409 (:status late)))
          (is (= "not-cancellable" (get-in late [:body :error :type]))))

        (let [cleared (authed :post "/api/workers/clear")]
          (is (= 200 (:status cleared)))
          (is (empty? (get-in cleared [:body :items]))))))))

(deftest worker-endpoints-reject-bad-requests
  (with-server
    (fn []
      (let [blank (authed :post "/api/workers" (json/write-str {:prompt "   "}))]
        (is (= 400 (:status blank)))
        (is (= "invalid-request" (get-in blank [:body :error :type]))))

      (let [missing (authed :post "/api/workers/wrk-does-not-exist/cancel")]
        (is (= 404 (:status missing)))
        (is (= "not-found" (get-in missing [:body :error :type]))))

      ;; Enqueueing is a state change, so it must fail closed without the CSRF
      ;; header and without a matching Origin.
      (let [no-csrf (request :post "/api/workers"
                             {:body (json/write-str {:prompt "x"})
                              :headers {"Origin" origin}})]
        (is (= 403 (:status no-csrf)))
        (is (= "invalid-csrf" (get-in no-csrf [:body :error :type]))))

      (let [bad-origin (request :post "/api/workers"
                                {:body (json/write-str {:prompt "x"})
                                 :headers {"Origin" "http://evil.example"
                                           "X-CLOUD-ITONAMI-CSRF" csrf}})]
        (is (= 403 (:status bad-origin)))
        (is (= "invalid-origin" (get-in bad-origin [:body :error :type]))))

      (is (empty? (get-in (authed :get "/api/workspace/worker") [:body :items]))))))

(deftest worker-queue-is-not-readable-without-a-session
  (with-redefs [local-identity/session (fn [_] nil)
                local-identity/configure! (fn [_] nil)]
    (server/stop!)
    (server/start! config)
    (try
      (is (= 401 (:status (request :get "/api/workspace/worker" {}))))
      (is (= 401 (:status (request :post "/api/workers"
                                   {:body (json/write-str {:prompt "x"})}))))
      (finally (server/stop!)))))
