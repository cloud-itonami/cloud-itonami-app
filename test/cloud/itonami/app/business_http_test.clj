(ns cloud.itonami.app.business-http-test
  "Drives the 事業 endpoints over real HTTP against a running server.

  `business_test.clj` covers the entity and its face states; these cover what
  only the server can get wrong — that the routes are wired at all, that the
  status codes distinguish the refusals, and that the writes fail closed without
  the CSRF token. The passkey gate is stubbed rather than satisfied, like the
  worker HTTP tests: a real ceremony needs an authenticator, and what is under
  test is the route layer behind that gate."
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
   ;; No workspace checkout, which is the shipped default and the state the
   ;; portfolio has to report honestly rather than as an empty plane.
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

(defn- authed [method path & [body]]
  (request method path {:body body
                        :headers {"Origin" origin
                                  "X-CLOUD-ITONAMI-CSRF" csrf}}))

(defn- with-server
  "Runs `body` against a live server whose stubbed session carries `organization`
  (nil for a session that has not set an Organization ID yet)."
  [organization body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-business-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session
                    (fn [_] (cond-> {:csrf csrf :user-id "test-user"}
                              organization (assoc :organization-id organization)))
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(deftest a-session-without-an-organization-is-told-to-set-one
  (with-server nil
    (fn []
      (let [r (authed :get "/api/business")]
        (testing "409 and organization-required, not 401 — the client is logged
                  in, so sending them back to sign in again fixes nothing"
          (is (= 409 (:status r)))
          (is (= "organization-required" (get-in r [:body :error :type]))))))))

;; The keys asserted below are NOT the namespaced ones the records carry:
;; `clojure.data.json/write-str` drops a keyword key's namespace, so
;; `:business/id` is `:id` on the wire. This test is what caught the browser
;; renderer reading `b['business/id']` and getting undefined.
(deftest the-portfolio-round-trips-over-http
  (with-server "org-http"
    (fn []
      (testing "an organization with no business gets an empty portfolio that
                still names the workspace state"
        (let [r (authed :get "/api/business")]
          (is (= 200 (:status r)))
          (is (= "cloud.itonami.app.business.v1" (get-in r [:body :schema])))
          (is (= [] (get-in r [:body :businesses])))
          (is (= "unset" (get-in r [:body :workspace :state])))
          (is (false? (get-in r [:body :workspace :configured?])))))

      (let [created (authed :post "/api/business"
                            (json/write-str {:slug "cloud-itonami-5820"
                                             :name "ISIC 5820 事業"}))
            id (get-in created [:body :id])]
        (is (= 200 (:status created)))
        (is (string? id))

        (testing "the created business appears with five unbound faces"
          (let [r (authed :get "/api/business")
                b (first (get-in r [:body :businesses]))]
            (is (= 1 (get-in r [:body :counts :businesses])))
            (is (= 5 (count (:faces b))))
            (is (= #{"unbound"} (set (map :state (:faces b)))))
            (is (= 0 (get-in b [:coverage :bound])))))

        (testing "a bound face with no workspace is unresolvable, not missing"
          (let [bound (authed :post (str "/api/business/" id "/bind")
                              (json/write-str {:canvas "cloud-itonami"}))]
            (is (= 200 (:status bound))))
          (let [b (first (get-in (authed :get "/api/business") [:body :businesses]))
                canvas (first (filter #(= "canvas" (:face %)) (:faces b)))]
            (is (= "unresolvable" (:state canvas)))
            (is (= 1 (get-in b [:coverage :bound])))
            (is (= 0 (get-in b [:coverage :resolved])))))

        (testing "a duplicate slug is a conflict with an existing record"
          (let [r (authed :post "/api/business"
                          (json/write-str {:slug "cloud-itonami-5820"}))]
            (is (= 409 (:status r)))
            (is (= "slug-taken" (get-in r [:body :error :type])))))

        (testing "an invalid slug is a bad request"
          (let [r (authed :post "/api/business" (json/write-str {:slug "no"}))]
            (is (= 400 (:status r)))
            (is (= "slug-invalid" (get-in r [:body :error :type])))))

        (testing "binding a business this organization does not have is 404"
          (let [r (authed :post "/api/business/business-nope/bind"
                          (json/write-str {:lei "X"}))]
            (is (= 404 (:status r)))
            (is (= "not-found" (get-in r [:body :error :type])))))

        (testing "both writes fail closed without the CSRF token"
          (doseq [[path body] [["/api/business" (json/write-str {:slug "abc-def"})]
                               [(str "/api/business/" id "/bind")
                                (json/write-str {:lei "X"})]]]
            (let [r (request :post path {:body body
                                         :headers {"Origin" origin}})]
              (is (= 403 (:status r)) path))))))))
