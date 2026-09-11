(ns cloud.itonami.app.human-work-http-test
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
(def csrf "human-work-test-csrf")
(def organization "org-gftd")
(def active-organization (atom organization))
(def actor (atom "person-requester"))
(def role (atom :member))

(def config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers []})

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
  {:display-name "Qualified Worker"
   :locations [{:location-id "tokyo-onsite"
                :country "JP" :region "13" :locality "Shibuya"
                :service-areas ["shibuya-jingumae"]
                :work-modes ["onsite"]
                :evidence-ref "evidence:location:worker"}]
   :availability [{:start "2026-09-07T00:00:00Z"
                   :end "2026-09-07T06:00:00Z"}]
   :credentials
   [{:credential-id "waste-license"
     :type "license" :name "Waste collection licence"
     :code "LIC-001" :issuer "Tokyo authority"
     :jurisdiction {:country "JP" :region "13"}
     :scopes ["bulky-waste-collection"]
     :expires-at "2026-09-30T00:00:00Z"
     :evidence-ref "evidence:license:001"}
    {:credential-id "safety-training"
     :type "qualification" :name "Safe lifting"
     :issuer "Training body"
     :jurisdiction {:country "JP" :region "13"}
     :scopes ["safe-lifting"]
     :evidence-ref "evidence:qualification:001"}]})

(def work-request
  {:title "Collect six bed frames"
   :summary "Onsite collection and handoff"
   :category "bulky-waste"
   :work-mode "onsite"
   :location {:country "JP" :region "13"
              :service-area "shibuya-jingumae"
              :minimum-verification "verified"}
   :work-window {:start "2026-09-07T01:00:00Z"
                 :end "2026-09-07T03:00:00Z"}
   :requirements
   {:credentials
    [{:type "license" :scopes ["bulky-waste-collection"]
      :jurisdiction {:country "JP" :region "13"}}
     {:type "qualification" :scopes ["safe-lifting"]
      :jurisdiction {:country "JP" :region "13"}}]}
   :private-details {:address "東京都渋谷区神宮前2丁目"
                     :access-note "accepted worker only"}
   :evidence-contract ["completion-proof" "handoff-receipt"]})

(def verification
  {:decision "verified"
   :valid-until "2026-09-10T00:00:00Z"
   :evidence-ref "evidence:organization-check"})

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-human-work-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! actor "person-requester")
      (reset! role :member)
      (reset! active-organization organization)
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session
                    (fn [_] {:csrf csrf :user-id @actor
                             :organization-id @active-organization})
                    local-identity/public-state
                    (fn [_] {:user {:id @actor}
                             :organization {:id @active-organization
                                            :organization-id @active-organization
                                            :role @role}})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))

(defn- register-worker! []
  (reset! actor "person-worker")
  (reset! role :member)
  (api :post "/api/workspace/human-work/workers" worker-profile))

(defn- verify-worker! []
  (reset! actor "person-verifier")
  (reset! role :admin)
  (api :post
       "/api/workspace/human-work/workers/person-worker/locations/tokyo-onsite/verify"
       verification)
  (doseq [credential-id ["waste-license" "safety-training"]]
    (api :post
         (str "/api/workspace/human-work/workers/person-worker/credentials/"
              credential-id "/verify")
         verification)))

(deftest verified-human-work-runs-end-to-end-over-http
  (with-server
    (fn []
      (is (= 200 (:status (register-worker!))))
      (verify-worker!)
      (reset! actor "person-requester")
      (reset! role :member)
      (let [created (api :post "/api/workspace/human-work/requests" work-request)
            id (get-in created [:body :id])]
        (is (= 201 (:status created)))
        (is (= organization (get-in created [:body :organization-id])))
        (reset! active-organization "org-other")
        (is (= 404
               (:status
                (api :get (str "/api/workspace/human-work/requests/"
                               id "/matches")))))
        (reset! active-organization organization)
        (is (= 200 (:status
                    (api :post (str "/api/workspace/human-work/requests/"
                                    id "/publish") {}))))
        (is (= ["person-worker"]
               (mapv :worker-id
                     (get-in (api :get
                                  (str "/api/workspace/human-work/requests/"
                                       id "/matches"))
                             [:body :items]))))
        (reset! actor "person-worker")
        (testing "private location details are withheld until acceptance"
          (is (nil? (get-in (api :get "/api/workspace/human-work")
                            [:body :items 0 :private-details]))))
        (is (= "accepted"
               (get-in (api :post
                            (str "/api/workspace/human-work/requests/"
                                 id "/accept") {})
                       [:body :status])))
        (is (= "東京都渋谷区神宮前2丁目"
               (get-in (api :get "/api/workspace/human-work")
                       [:body :items 0 :private-details :address])))
        (is (= "in-progress"
               (get-in (api :post
                            (str "/api/workspace/human-work/requests/"
                                 id "/start")
                            {:presence-evidence-ref "proof:arrival"})
                       [:body :status])))
        (is (= "submitted"
               (get-in (api :post
                            (str "/api/workspace/human-work/requests/"
                                 id "/submit")
                            {:summary "Collected and handed off"
                             :evidence {:completion-proof "proof:done"
                                        :handoff-receipt "receipt:facility"}})
                       [:body :status])))
        (reset! actor "person-requester")
        (is (= "verified"
               (get-in (api :post
                            (str "/api/workspace/human-work/requests/"
                                 id "/review")
                            {:decision "verified"
                             :verification-evidence-ref
                             "receipt:requester-review"})
                       [:body :status])))))))

(deftest verification-requires-admin-and-a-distinct-human
  (with-server
    (fn []
      (register-worker!)
      (reset! actor "person-verifier")
      (reset! role :member)
      (is (= 403
             (:status
              (api :post
                   "/api/workspace/human-work/workers/person-worker/locations/tokyo-onsite/verify"
                   verification))))
      (reset! actor "person-worker")
      (reset! role :admin)
      (is (= 403
             (:status
              (api :post
                   "/api/workspace/human-work/workers/person-worker/locations/tokyo-onsite/verify"
                   verification)))))))

(deftest every-write-fails-closed-without-csrf
  (with-server
    (fn []
      (is (= 403
             (:status
              (request :post "/api/workspace/human-work/workers"
                       worker-profile false)))))))
