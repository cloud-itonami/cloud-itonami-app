(ns cloud.itonami.app.domain-verification-http-test
  "The domain-ownership routes as a BROWSER reaches them.

  Every other `*-http-test` in this suite sets an `Origin` header on every
  request, because that is what makes the write routes pass. That convention is
  why nothing caught this: a browser sends `Origin` on non-GET requests and on
  cross-origin ones, and **not** on a same-origin GET. So the read route, which
  asked for it, answered 403 to its only caller on every page load — and every
  test of it passed, because every test sent a header the browser never would.

  These tests therefore send requests shaped like the browser's: no `Origin` on
  the GET, `Origin` on the writes. The point is the asymmetry, so both halves
  are asserted — dropping the check from the read must not weaken the writes."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "domain-csrf")
(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- call
  "`send-origin?` is the whole point of this namespace: a browser omits the
  header on a same-origin GET and sends it on a POST, so a test that always
  sends it is testing a client nobody has."
  [method path {:keys [body send-origin? csrf?]}]
  (let [builder (cond-> (-> (HttpRequest/newBuilder
                             (URI/create (str "http://127.0.0.1:"
                                              (.getPort (.getAddress @server/server))
                                              path)))
                            (.header "Content-Type" "application/json"))
                  send-origin? (.header "Origin" origin)
                  csrf? (.header "X-CLOUD-ITONAMI-CSRF" csrf))
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (try (json/read-str (.body response) :key-fn keyword)
                (catch Exception _ (.body response)))}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-domain-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        session {:csrf csrf :user-id "user-a" :organization-id "org-a"
                 :membership-id "membership-a"
                 :kind :passkey :authn-level :phishing-resistant}]
    (try
      (reset! store/state
              (assoc (store/initial-state)
                     :identity
                     {:organizations {"org-a" {:id "org-a" :organization-id "acme"
                                               :name "Acme" :status :active}}
                      :users {"user-a" {:id "user-a" :passkey-enrolled? true}}
                      :memberships {"membership-a" {:id "membership-a"
                                                    :organization-id "org-a"
                                                    :user-id "user-a" :role :owner}}
                      :domain-verifications {}}))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest the-read-answers-a-browser-shaped-request
  (with-server
    (fn []
      (testing "no Origin, as a same-origin GET from a page actually arrives"
        (let [{:keys [status body]} (call :get "/api/identity/domain-verifications"
                                          {:send-origin? false})]
          (is (= 200 status)
              "this answered 403 on every page load until 2026-08-12")
          (is (vector? (:verifications body)))))
      (testing "and still answers when a client does send one"
        (is (= 200 (:status (call :get "/api/identity/domain-verifications"
                                  {:send-origin? true}))))))))

(deftest the-writes-still-require-origin-and-csrf
  (with-server
    (fn []
      (testing "a write without Origin is refused — the read's relaxation must
                not have reached the routes that change something"
        (is (= 403 (:status (call :post "/api/identity/domain-verifications"
                                  {:body {:domain "example.com"}
                                   :send-origin? false :csrf? true})))))
      (testing "and a write without the CSRF token is refused too"
        (is (= 403 (:status (call :post "/api/identity/domain-verifications"
                                  {:body {:domain "example.com"}
                                   :send-origin? true :csrf? false})))))
      (testing "with both, it is admitted"
        (is (= 201 (:status (call :post "/api/identity/domain-verifications"
                                  {:body {:domain "example.com"}
                                   :send-origin? true :csrf? true}))))))))
