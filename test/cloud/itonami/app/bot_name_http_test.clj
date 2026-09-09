(ns cloud.itonami.app.bot-name-http-test
  "The rename and the trajectory over real HTTP against a running server.

  `bots-test` covers what the two functions decide. These cover what only the
  server can get wrong: that the routes are wired at all, that they are not
  shadowed by the `/api/bots/([^/]+)` patterns that follow them, that the
  rename fails closed without the CSRF token, and that a refusal arrives as
  the status it means rather than a 500.

  The passkey gate is stubbed rather than satisfied, as in the other HTTP
  tests: a real ceremony needs an authenticator, and what is under test is the
  route layer behind that gate."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "test-csrf-token")

(def ^:private session
  {:csrf csrf :user-id "test-user" :organization-id "org-http" :kind :passkey})

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
                :default-model "test-model" :models ["test-model"]
                :reviewed? true :enabled? true}]})

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
                   "cloud-itonami-app-bot-name-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session (fn [_] session)
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- a-bot []
  ;; `create!` defaults a new Bot to autonomy, so these two are set false here
  ;; on purpose: the shadowing test below asserts that a rename carrying
  ;; `writes?`/`omakase?` in its body cannot turn them ON, and a fixture that
  ;; started with them on could not tell that from a no-op.
  (:bot/id (bots/create! config session
                         {:name "調べもの" :connectors []
                          :writes? false :omakase? false})))

(deftest renaming-over-http-stores-the-name-and-says-who-chose-it
  (with-server
    (fn []
      (let [bot-id (a-bot)
            r (authed :post (str "/api/bots/" bot-id "/name")
                      (json/write-str {:name "価格の見直し"}))
            renamed (some #(when (= bot-id (:id %)) %) (:bots (:body r)))]
        (is (= 200 (:status r)))
        (is (= "価格の見直し" (:name renamed)))
        (is (= "person" (:name-source renamed)))
        (testing "and a second read sees it, so it was stored rather than echoed"
          (is (= "価格の見直し"
                 (:name (some #(when (= bot-id (:id %)) %)
                              (:bots (:body (authed :get "/api/bots")))))))))))) 

(deftest a-rename-without-the-csrf-token-is-refused-and-changes-nothing
  (with-server
    (fn []
      (let [bot-id (a-bot)
            r (request :post (str "/api/bots/" bot-id "/name")
                       {:body (json/write-str {:name "borrowed"})
                        :headers {"Origin" origin}})]
        (is (= 403 (:status r)))
        (is (= "調べもの"
               (:name (some #(when (= bot-id (:id %)) %)
                            (:bots (:body (authed :get "/api/bots")))))))))))

(deftest an-empty-name-is-refused-by-the-route-as-a-bad-request
  ;; 400 rather than 500: the caller can fix this one, and a 500 would send
  ;; somebody to read a server log for a field they left blank.
  (with-server
    (fn []
      (let [bot-id (a-bot)
            r (authed :post (str "/api/bots/" bot-id "/name")
                      (json/write-str {:name "   "}))]
        (is (= 400 (:status r)))))))

(deftest the-name-route-is-not-shadowed-by-a-bot-id-pattern
  ;; `/api/bots/([^/]+)` follows this clause in the same `cond`. Were the
  ;; ordering wrong, this would be handled as the whole-attributes update and
  ;; answer 200 having written nothing.
  (with-server
    (fn []
      (let [bot-id (a-bot)
            r (authed :post (str "/api/bots/" bot-id "/name")
                      (json/write-str {:name "役割" :writes? true :omakase? true}))
            renamed (some #(when (= bot-id (:id %)) %) (:bots (:body r)))]
        (is (= 200 (:status r)))
        (is (= "役割" (:name renamed)))
        ;; The reason this route exists at all. Extra keys in the body are
        ;; not a grant: the handler builds the update itself.
        (is (false? (:writes? renamed)))
        (is (false? (:omakase? renamed)))))))

(deftest a-trajectory-is-readable-over-http-at-the-stage-the-run-is-in
  (with-server
    (fn []
      (let [bot-id (a-bot)]
        (store/transact!
         (fn [state]
           (-> state
               (assoc-in [:bots :goal-jobs "run-http-1"]
                         {:job/id "run-http-1" :job/bot bot-id
                          :job/objective "verify"
                          :job/plan [{:step/id "s1" :step/title "read"
                                      :step/state :pending :step/depends-on #{}}]
                          :job/events
                          [{:event/id "e1" :event/kind :action/started
                            :event/at "2026-09-09T00:00:01Z"
                            :event/data {:action/id "c1" :tool "workspace_list"
                                         :step-id "s1"}}]})
               (assoc-in [:bots :turn-history bot-id]
                         [{:turn/id "run-http-1" :turn/bot bot-id
                           :turn/state :running :turn/phase :tool-executed
                           :turn/goal? true :turn/objective "verify"
                           :turn/started-at "2026-09-09T00:00:00Z"}]))))
        (let [r (authed :get (str "/api/bots/" bot-id "/runs/run-http-1/trajectory"))]
          (is (= 200 (:status r)))
          (is (true? (get-in r [:body :available?])))
          (is (= 1 (count (get-in r [:body :steps]))))
          (is (= "workspace_list" (:tool (first (get-in r [:body :steps])))))
          ;; Mid-run is the case this surface is for. A step that started and
          ;; has not finished must not come back looking finished.
          (is (= "running" (:outcome (first (get-in r [:body :steps]))))))
        (testing "a run with no step ledger says so instead of answering empty"
          (store/transact!
           assoc-in [:bots :turn-history bot-id]
           [{:turn/id "run-http-2" :turn/bot bot-id
             :turn/state :completed :turn/phase :completed
             :turn/goal? false :turn/started-at "2026-09-09T00:00:00Z"}])
          (let [r (authed :get (str "/api/bots/" bot-id "/runs/run-http-2/trajectory"))]
            (is (= 200 (:status r)))
            (is (false? (get-in r [:body :available?])))
            (is (nil? (get-in r [:body :steps])))
            (is (string? (get-in r [:body :reason])))))))))

(deftest a-trajectory-for-somebody-elses-bot-is-refused
  ;; And 404 for a Bot that does not exist -- two different answers, because
  ;; "not yours" and "not there" are two different things to be told, and a
  ;; single status for both would leak the first as the second.
  (with-server
    (fn []
      (let [bot-id (a-bot)
            theirs (assoc (get-in (store/snapshot) [:bots :bots bot-id])
                          :bot/id "bot-someone-else"
                          :bot/owner "other-user"
                          :bot/organization "org-other")]
        (store/transact! assoc-in [:bots :bots "bot-someone-else"] theirs)
        (is (= 403 (:status (authed :get "/api/bots/bot-someone-else/runs/r/trajectory"))))
        (is (= 404 (:status (authed :get "/api/bots/bot-nowhere/runs/r/trajectory"))))))))
