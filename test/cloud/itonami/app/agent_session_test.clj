(ns cloud.itonami.app.agent-session-test
  "The CLI/MCP way in, and the two things it must not become.

  It must not become a way for a HALF-ENROLLED BROWSER SESSION to act — that is
  the gate `require-passkey!` exists for and it is unchanged.

  It must not become a way for ANY CALLER to mint a session — enrollment is
  refused without the data directory's 0600 key, which is the boundary that
  actually holds: something able to read that file can rewrite state.edn."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.payment-tools :as payment-tools]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files LinkOption]))

(def ^:private origin "http://localhost:1338")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]
   :business {:workspace-root nil}})

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

(defn- bearer [method path token & [body]]
  (request method path {:body body
                        :headers {"Authorization" (str "Bearer " token)}}))

(defn- with-server
  "A live server over a fresh data directory, with one owner user who has NOT
  enrolled a Passkey — the state this whole feature exists for."
  [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-agent-session"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (store/transact!
         assoc :identity
         {:users {"user-1" {:id "user-1" :display-name "Owner"
                            :passkey-enrolled? false}}
          :organizations {"org-1" {:id "org-1" :name "Personal"}}
          :memberships {"m-1" {:id "m-1" :user-id "user-1"
                               :organization-id "org-1" :role :owner}}
          :sessions {}})
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- enroll [& [overrides]]
  (request :post "/api/agent-session"
           {:body (json/write-str
                   (merge {:enrollment-key (agent-session/ensure-key!)
                           :label "test-cli"}
                          overrides))}))

;; ---------------------------------------------------------------------------
;; the key is the boundary
;; ---------------------------------------------------------------------------

(deftest enrollment-needs-the-key-file
  (with-server
    (fn []
      (testing "the key file is created by the server, owner-readable only"
        (let [path (agent-session/key-file)]
          (is (Files/isRegularFile path (into-array LinkOption [])))
          (is (= #{"OWNER_READ" "OWNER_WRITE"}
                 (into #{} (map str) (Files/getPosixFilePermissions
                                      path (into-array LinkOption [])))))))

      (testing "no key, a wrong key and a blank key are all refused.
                403 rather than 400: the field was fine, the secret was not"
        (doseq [k [nil "not-the-key" ""]]
          (let [r (enroll {:enrollment-key k})]
            (is (= 403 (:status r)) (str "key=" (pr-str k)))
            (is (= "invalid-key" (get-in r [:body :error :type]))
                (str "key=" (pr-str k))))))

      (testing "an unlabelled session is refused — nothing to revoke it by"
        (let [r (enroll {:label "  "})]
          (is (= 400 (:status r)))
          (is (= "label-missing" (get-in r [:body :error :type]))))))))

;; ---------------------------------------------------------------------------
;; what the minted session can and cannot do
;; ---------------------------------------------------------------------------

(deftest an-agent-session-acts-without-a-passkey
  (with-server
    (fn []
      (let [issued (enroll)
            token (get-in issued [:body :token])]
        (is (= 200 (:status issued)))
        (is (string? token))

        (testing "the user still has no Passkey — this is not enrolment by proxy"
          (is (false? (get-in (store/snapshot)
                              [:identity :users "user-1" :passkey-enrolled?]))))

        (testing "and yet /api/business answers, because the gate reads :kind"
          (let [r (bearer :get "/api/business" token)]
            (is (= 200 (:status r)))
            (is (= "org-1" (get-in r [:body :organization-id])))))

        (testing "a write needs no CSRF and no Origin: nothing attaches a
                  bearer token by itself"
          (let [r (bearer :post "/api/business" token
                          (json/write-str {:slug "agent-made"}))]
            (is (= 200 (:status r)))
            (is (string? (get-in r [:body :id])))))))))

(deftest the-browser-gate-is-unchanged
  (with-server
    (fn []
      (testing "a cookie session for the same user, with no Passkey, is still
                refused — the agent path did not lower the browser bar"
        (let [{:keys [token]} (identity/issue-session! "user-1")
              r (request :get "/api/business"
                         {:headers {"Cookie" (str identity/cookie-name "=" token)}})]
          ;; 428: the client must do something first. The error type on the
          ;; wire is the keyword's NAME, not its namespace -- :passkey/required
          ;; reads as "required".
          (is (= 428 (:status r)))
          (is (= "required" (get-in r [:body :error :type])))))

      (testing "an unknown bearer token is unauthenticated, not accepted"
        (let [r (bearer :get "/api/business" "not-a-real-token")]
          (is (= 401 (:status r))))))))

;; ---------------------------------------------------------------------------
;; the operator can see and withdraw what was given
;; ---------------------------------------------------------------------------

(deftest sessions-are-listed-and-revocable
  (with-server
    (fn []
      (let [token (get-in (enroll {:label "first"}) [:body :token])
            second-token (get-in (enroll {:label "second"}) [:body :token])
            listed (bearer :get "/api/agent-session" token)
            sessions (get-in listed [:body :sessions])]
        (is (= 200 (:status listed)))
        (is (= ["first" "second"] (mapv :label sessions)))
        (is (= ["agent" "agent"] (mapv :kind sessions)))
        (is (= ["local-ownership" "local-ownership"] (mapv :issued-via sessions)))

        (let [id (:id (first (filter #(= "second" (:label %)) sessions)))
              revoked (bearer :post (str "/api/agent-session/" id "/revoke") token)]
          (is (= 200 (:status revoked)))
          (is (true? (get-in revoked [:body :revoked?])))

          (testing "the revoked token stops working immediately"
            (is (= 401 (:status (bearer :get "/api/business" second-token)))))

          (testing "and is still listed — 'what was ever given access' is the
                    question, so dropping the dead ones would answer a different one"
            (let [after (get-in (bearer :get "/api/agent-session" token)
                                [:body :sessions])]
              (is (= 2 (count after)))
              (is (= [false true] (mapv :revoked? after))))))))))

(deftest a-short-ttl-is-honoured
  (with-server
    (fn []
      (testing "ttl-days lands on the record rather than the 30-day default"
        (let [issued (enroll {:ttl-days 1})
              id (get-in issued [:body :session-id])
              record (get-in (store/snapshot) [:identity :sessions id])
              days (.between java.time.temporal.ChronoUnit/DAYS
                             (java.time.Instant/parse (:created-at record))
                             (java.time.Instant/parse (:expires-at record)))]
          (is (= 1 days))))

      (testing "a non-positive ttl is refused rather than quietly defaulted"
        (is (= "ttl-invalid"
               (get-in (enroll {:ttl-days 0}) [:body :error :type])))))))

;; ---------------------------------------------------------------------------
;; the money surface is deliberately stricter
;; ---------------------------------------------------------------------------

(deftest an-agent-session-does-not-reach-the-money-surface
  (with-server
    (fn []
      (let [token (get-in (enroll {:label "money-probe"}) [:body :token])
            configuration {:mcp {:session-token-env "CLOUD_ITONAMI_TEST_SESSION"}}]
        (testing "the session is real and satisfies the app-wide rule"
          (is (true? (identity/may-act? (identity/session token)))))

        (testing "and payment-tools still refuses it, because the decision that
                  made local ownership enough was about the business surface —
                  not funding and settlement"
          (with-redefs [agent-session/session-token (fn [_] token)]
            (is (nil? (payment-tools/session configuration)))
            (is (false? (payment-tools/available? configuration)))))

        (testing "a Passkey-enrolled user IS accepted there, so the refusal above
                  is about :kind and not about the token being unreadable"
          (store/transact! assoc-in
                           [:identity :users "user-1" :passkey-enrolled?] true)
          (with-redefs [agent-session/session-token (fn [_] token)]
            (is (some? (payment-tools/session configuration)))))))))

;; The same line, held where it is actually crossed. Until 2026-07-31 it was
;; held only in the MCP adapter, while /api/funding/* used the gate that passes
;; agent sessions — so a token could reach over HTTP what the surface it was
;; minted for refused. A boundary enforced in the client and not at the route is
;; not a boundary.
(deftest the-money-routes-refuse-an-agent-session
  (with-server
    (fn []
      (let [token (get-in (enroll {:label "money-route-probe"}) [:body :token])]
        (testing "the same token works on the business surface"
          (is (= 200 (:status (bearer :get "/api/business" token)))))

        (testing "and is refused on funding and authority, by route — reads
                  and writes alike, so the refusal is not only about mutation"
          (doseq [[method path] [[:get "/api/funding"]
                                 [:get "/api/authority"]
                                 [:post "/api/funding/accounts"]]]
            (let [r (bearer method path token "{}")]
              (is (= 403 (:status r)) (str method " " path))
              (is (= "agent-session-forbidden" (get-in r [:body :error :type]))
                  (str method " " path)))))

        (testing "a Passkey session reaches them, so the refusal is about :kind"
          (store/transact! assoc-in
                           [:identity :users "user-1" :passkey-enrolled?] true)
          (let [{:keys [token]} (identity/issue-session! "user-1")
                r (request :get "/api/funding"
                           {:headers {"Cookie" (str identity/cookie-name "=" token)}})]
            (is (= 200 (:status r)))))))))
