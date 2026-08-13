(ns cloud.itonami.app.capture-http-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.chronicle :as chronicle]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "capture-csrf")
(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})
(defonce ^:private client (HttpClient/newHttpClient))

(defn- call [method path body with-csrf?]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server)) path)))
                    (.header "Content-Type" "application/json")
                    (.header "Origin" origin))
        builder (if with-csrf? (.header builder "X-CLOUD-ITONAMI-CSRF" csrf) builder)
        request (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build request) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-capture-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        session {:csrf csrf :user-id "alice" :organization-id "org-1"
                 :kind :passkey :authn-level :phishing-resistant}]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(deftest capture-and-clarify-over-http
  (with-server
    (fn []
      (let [created (call :post "/api/captures"
                          {:text "声に出して考えたまま" :mode "think-aloud"} true)
            id (get-in created [:body :id])]
        (is (= 201 (:status created)))
        (is (= "unclarified" (get-in created [:body :state])))
        (is (= 1 (get-in (call :get "/api/captures" nil false)
                         [:body :counts :inbox])))
        (let [organized (call :post (str "/api/captures/" id "/clarify")
                              {:outcome "waiting-for" :title "返答を待つ"
                               :waiting-for "佐藤さん"} true)]
          (is (= 200 (:status organized)))
          (is (= "waiting-for" (get-in organized [:body :outcome]))))
        (is (= 200 (:status (call :post (str "/api/captures/" id "/review")
                                 {} true))))
        (is (= 200 (:status (call :post (str "/api/captures/" id "/complete")
                                 {} true))))
        (is (= 200 (:status (call :post (str "/api/captures/" id "/reopen")
                                 {} true))))))))

(deftest capture-write-requires-csrf
  (with-server
    (fn []
      (testing "a page cannot cause a silent thought record from another origin"
        (is (= 403 (:status (call :post "/api/captures" {:text "no"} false))))))))

(deftest chronicle-context-is-previewed-and-explicitly-admitted
  (with-server
    (fn []
      (chronicle/configure! "alice" {:screen-context-enabled? true})
      (store/transact! assoc-in [:chronicle :users "alice" :frames "frame-1"]
                       {:id "frame-1" :captured-at "2026-08-08T12:00:00Z"
                        :captured-at-ms 1 :application "Editor"
                        :ocr "選択するまでCaptureには入らない"
                        :image-path "/private/not-on-the-wire.jpg"})
      (with-redefs [chronicle/permission-status (constantly "granted")]
        (let [preview (call :get "/api/captures/chronicle" nil false)
              created (call :post "/api/captures"
                            {:text "この作業を続けたい"
                             :chronicle-frame-id "frame-1"} true)]
          (is (= 200 (:status preview)))
          (is (= "frame-1" (get-in preview [:body :frames 0 :id])))
          (is (nil? (get-in preview [:body :frames 0 :image-path])))
          (is (= 201 (:status created)))
          (is (= "chronicle-frame" (get-in created [:body :source :type])))
          (is (= "Editor" (get-in created [:body :source :application])))
          (is (= 404 (:status (call :post "/api/captures"
                                    {:text "spoof" :chronicle-frame-id "missing"}
                                    true)))))))))
