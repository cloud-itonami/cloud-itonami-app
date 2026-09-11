(ns cloud.itonami.app.bulky-waste-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def origin "http://localhost:1338")
(def csrf "bulky-waste-test-csrf")
(def actor (atom "person-owner"))
(def organization "org-gftd")

(def config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :reviewed? true :enabled? true}]})

(defonce client (HttpClient/newHttpClient))
(defn- port [] (.getPort (.getAddress @server/server)))

(defn- request [method path body csrf?]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" (port) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin))
        _ (when csrf? (.header builder "X-CLOUD-ITONAMI-CSRF" csrf))
        built (case method
                :get (.GET builder)
                :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                      (json/write-str (or body {})))))
        response (.send client (.build built) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- api
  ([method path] (request method path nil true))
  ([method path body] (request method path body true)))

(def worker-profile
  {:service-areas ["shibuya-jingumae"]
   :categories ["bedding"]
   :capacity-grams 120000
   :availability [{:start "2026-09-07T00:00:00Z"
                   :end "2026-09-07T05:00:00Z"}]
   :evidence {:vehicle "vehicle:1" :insurance "insurance:1"
              :waste-carrier "carrier:1"
              :service-location "service-location:1"}
   :country "JP"
   :region "13"})

(def job-request
  {:service-area "shibuya-jingumae"
   :country "JP"
   :region "13"
   :pickup-address "東京都渋谷区神宮前2丁目"
   :access-notes "private access note"
   :pickup-window {:start "2026-09-07T01:00:00Z"
                   :end "2026-09-07T03:00:00Z"}
   :items [{:category "bedding" :description "寝具用すのこ"
            :quantity 6 :unit-weight-grams 8000}]
   :facility-id "mrf-shibuya-1"
   :facility-operator-id "person-facility"
   :facility-permit-evidence-ref "permit:1"})

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-bulky-waste-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! actor "person-owner")
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session
                    (fn [_] {:csrf csrf :user-id @actor
                             :organization-id organization})
                    local-identity/public-state
                    (fn [_] {:user {:id @actor}
                             :organization {:id organization
                                            :organization-id organization
                                            :role :admin}})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest the-http-surface-connects-request-worker-collection-and-recovery
  (with-server
    (fn []
      (reset! actor "person-worker")
      (is (= 200 (:status (api :post "/api/workspace/bulky-waste/workers"
                               worker-profile))))
      (reset! actor "person-verifier")
      (let [verification {:decision "verified"
                          :valid-until "2027-01-01T00:00:00Z"
                          :evidence-ref "evidence:organization-check"}]
        (is (= 200 (:status
                    (api :post
                         "/api/workspace/human-work/workers/person-worker/locations/bulky-waste-service-area/verify"
                         verification))))
        (doseq [credential-id ["bulky-waste-carrier-license"
                               "bulky-waste-vehicle-insurance"
                               "bulky-waste-collection-vehicle"]]
          (is (= 200 (:status
                      (api :post
                           (str "/api/workspace/human-work/workers/person-worker/credentials/"
                                credential-id "/verify")
                           verification))))))
      (reset! actor "person-owner")
      (let [created (api :post "/api/workspace/bulky-waste/jobs" job-request)
            id (get-in created [:body :id])]
        (is (= 201 (:status created)))
        (is (= 200 (:status (api :post (str "/api/workspace/bulky-waste/jobs/"
                                              id "/publish") {}))))
        (is (= ["person-worker"]
               (mapv :worker-id
                     (get-in (api :get (str "/api/workspace/bulky-waste/jobs/"
                                            id "/matches")) [:body :items]))))
        (reset! actor "person-worker")
        (testing "an eligible worker sees the offer but not the address"
          (let [offer (first (get-in (api :get "/api/workspace/bulky-waste")
                                     [:body :items]))]
            (is (nil? (:pickup-address offer)))
            (is (nil? (:access-notes offer)))))
        (is (= "booked" (get-in (api :post (str "/api/workspace/bulky-waste/jobs/"
                                                   id "/book") {})
                                 [:body :status])))
        (is (= "東京都渋谷区神宮前2丁目"
               (get-in (api :get "/api/workspace/bulky-waste")
                       [:body :items 0 :pickup-address])))
        (api :post (str "/api/workspace/bulky-waste/jobs/" id "/check-in")
             {:presence-proof-ref "proof:arrival"})
        (api :post (str "/api/workspace/bulky-waste/jobs/" id "/collect")
             {:manifest-id "manifest:3811:1" :actual-weight-grams 50000
              :collection-proof-ref "proof:collection"})
        (reset! actor "person-facility")
        (api :post (str "/api/workspace/bulky-waste/jobs/" id "/deliver")
             {:facility-receipt-ref "receipt:facility:1" :batch-id "batch:3830:1"
              :accepted-weight-grams 50000})
        (let [done (api :post (str "/api/workspace/bulky-waste/jobs/" id "/recover")
                        {:recovery-receipt-ref "receipt:recovery:1"
                         :recovered-weight-grams 42000 :disposed-weight-grams 8000
                         :outputs [{:material "wood" :weight-grams 42000}]})]
          (is (= 200 (:status done)))
          (is (= "recovered" (get-in done [:body :status])))
          (is (= "batch:3830:1" (get-in done [:body :delivery :batch-id]))))))))

(deftest every-write-fails-closed-without-csrf
  (with-server
    (fn []
      (let [response (request :post "/api/workspace/bulky-waste/jobs"
                              job-request false)]
        (is (= 403 (:status response)))
        (is (= [] (get-in (api :get "/api/workspace/bulky-waste")
                           [:body :items])))))))
