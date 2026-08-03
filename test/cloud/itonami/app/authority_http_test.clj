(ns cloud.itonami.app.authority-http-test
  "Drives the /api/authority endpoints over real HTTP against a running server.

  Seventeen routes carry the eSIM / card / voice integration, and until now every test
  reached past them into `cloud.itonami.app.authority.api`. That leaves the layer only
  the server can get wrong: whether a route is wired at all, whether its path regex
  matches what the client sends, whether a write without a CSRF token fails closed, and
  whether a refusal comes back with a status code a client can act on.

  It matters more here than for most routes, because THERE IS NO UI FOR ANY OF THIS.
  Measured 2026-07-31: web.clj references none of these seventeen paths, so no human can
  reach the integration from the app's own interface. Whatever client eventually does --
  a panel, a script, another service -- these routes are its whole contract, and nothing
  was checking that the contract is even reachable.

  Same shape as business_http_test: the passkey gate is stubbed rather than satisfied,
  because a real ceremony needs an authenticator and what is under test is the route
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
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]
   ;; Shipped posture: every authority off. A route that answers usefully with them off
   ;; is the one a settings screen needs in order to say so.
   :authorities {:esim {:enabled? false :endpoint nil}
                 :card {:enabled? false :endpoint nil}
                 :voice {:enabled? false :endpoint nil}
                 :payment {:enabled? false :endpoint nil}}})

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
          response (.send client (.build built) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (try (json/read-str (.body response) :key-fn keyword)
                  (catch Exception _ {:raw (.body response)}))})))

(defn- authed [method path & [body]]
  (request method path {:body body
                        :headers {"Origin" origin "X-CLOUD-ITONAMI-CSRF" csrf}}))

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-authority-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session (fn [_] {:csrf csrf :user-id "test-user"})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

;; ---------------------------------------------------------------------------
;; the routes are wired
;; ---------------------------------------------------------------------------

(deftest the-overview-answers-with-every-authority-off
  (testing "this is the read a settings screen needs in order to show that an
            authority is OFF -- refusing it would leave nothing to render"
    (with-server
      (fn []
        (let [r (authed :get "/api/authority")]
          (is (= 200 (:status r)))
          (is (= #{:esim :card :number :payment :voice}
                 (set (keys (get-in r [:body :authorities])))))
          (doseq [[k v] (get-in r [:body :authorities])]
            (is (false? (:enabled? v)) (str k))))))))

(deftest the-overview-carries-the-posture-so-a-client-cannot-derive-it-differently
  (with-server
    (fn []
      (let [r (authed :get "/api/authority")]
        ;; JSON drops keyword namespaces, so :authority/posture arrives as "posture"
        ;; -- the client sees {"posture":{"posture":"normal"}}.
        (is (= "normal" (get-in r [:body :posture :posture])))))))

(deftest a-per-authority-read-refuses-while-that-authority-is-off
  (testing "501, measured. The whole-fleet overview answers 200 with everything off --
            a settings screen must be able to render 'this is disabled' -- but the
            per-authority read refuses instead. Two reads, two different contracts."
    (with-server
      (fn []
        (let [r (authed :get "/api/authority/esim")]
          (is (= 501 (:status r)))
          (is (= "disabled" (get-in r [:body :error :type]))))))))

(deftest an-unknown-authority-reads-as-disabled-rather-than-unknown
  (testing "a typo does NOT reach a different authority, which is the property that
            matters -- but it is reported as 'disabled', so a client cannot tell a
            misspelled authority from one the deployment switched off. Pinned as
            measured rather than asserted as desirable; changing it is a contract
            change for whatever client eventually reads these codes."
    (with-server
      (fn []
        (let [r (authed :get "/api/authority/nope")]
          (is (= 501 (:status r)))
          (is (= "disabled" (get-in r [:body :error :type])))
          (is (= "nope" (get-in r [:body :error :details :authority]))))))))

(deftest review-on-a-disabled-authority-is-refused-through-the-route
  (testing "the refusal reaches the client as a status code it can act on, rather than
            a 500"
    (with-server
      (fn []
        (let [r (authed :post "/api/authority/esim/review"
                        (json/write-str {:op "profile/lifecycle"
                                         :eid "89049032000000000000000000000001"
                                         :iccid "8981012345678901230"
                                         :event "enable"}))]
          (is (not= 500 (:status r)) "a refusal, not an unhandled error")
          (is (= 501 (:status r)))
          (is (= "disabled" (get-in r [:body :error :type]))))))))

(deftest resolve-pending-is-reachable
  (testing "the route added so that :authority-pending can resolve at all -- nothing
            called the per-proposal refresh, so this is the one a client will use"
    (with-server
      (fn []
        (let [r (authed :post "/api/authority/resolve-pending")]
          (is (= 200 (:status r)))
          (is (= 0 (get-in r [:body :asked])))
          (is (= "cloud.itonami.app.authority.api.resolve-pending.v1"
                 (get-in r [:body :schema]))))))))

;; ---------------------------------------------------------------------------
;; the guards fail closed
;; ---------------------------------------------------------------------------

(deftest a-write-without-the-csrf-token-is-refused
  (testing "every one of these routes carries a proposal outward; a missing token must
            refuse rather than proceed"
    (with-server
      (fn []
        (doseq [path ["/api/authority/resolve-pending"
                      "/api/authority/esim/review"]]
          (let [r (request :post path {:headers {"Origin" origin}})]
            (is (contains? #{401 403} (:status r))
                (str path " -> " (:status r)))))))))

(deftest a-write-from-another-origin-is-refused
  (with-server
    (fn []
      (let [r (request :post "/api/authority/resolve-pending"
                       {:headers {"Origin" "http://evil.example"
                                  "X-CLOUD-ITONAMI-CSRF" csrf}})]
        (is (contains? #{401 403} (:status r)))))))

(deftest a-read-is-not-a-write-route
  (testing "GET on a decide/commit path must not be accepted -- the method is part of
            what makes these safe"
    (with-server
      (fn []
        (let [r (authed :get "/api/authority/resolve-pending")]
          (is (not= 200 (:status r))))))))
