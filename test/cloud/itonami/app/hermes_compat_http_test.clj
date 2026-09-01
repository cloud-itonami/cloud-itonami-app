(ns cloud.itonami.app.hermes-compat-http-test
  "Wire-level proof that a Hermes client can use Itonami without a browser."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.hermes-compat :as hermes]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private owner
  {:kind :agent :user-id "user-1" :organization-id "org-1"
   :did "did:key:user-1"})

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0
            :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :reviewed? true :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- request [method path body token]
  (let [builder (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server))
                                      path)))
                    (.header "Content-Type" "application/json"))
        _ (when token (.header builder "Authorization" (str "Bearer " token)))
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                        (json/write-str (or body {})))))
        response (.send client (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :content-type (-> response .headers (.firstValue "Content-Type")
                       (.orElse ""))
     :raw (.body response)
     :body (when (str/includes? (-> response .headers
                                    (.firstValue "Content-Type")
                                    (.orElse ""))
                                 "application/json")
             (json/read-str (.body response) :key-fn keyword))}))

(defn- seed-bot! []
  (store/transact!
   assoc-in [:bots :bots "bot-1"]
   (bot/bot {:bot/id "bot-1" :bot/organization "org-1"
             :bot/owner "user-1" :bot/name "Researcher"
             :bot/tools #{} :bot/accounts #{}
             :bot/writes? false :bot/browser? false
             :bot/computer? false :bot/peers? false
             :bot/coding? false :bot/virtual-shell? false
             :bot/goal? false :bot/pinned? true
             :bot/omakase? false :bot/enabled? true
             :bot/created-at "2026-09-01T00:00:00Z"
             :bot/updated-at "2026-09-01T00:00:00Z"})))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-hermes-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (hermes/reset-runs!)
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/configure! (fn [_] nil)
                    identity/session (fn [token]
                                       (when (= "test-token" token) owner))
                    provider/agent-turn-stream!
                    (fn [_ _ on-delta]
                      (on-delta "done")
                      {:content "done" :tool-calls []
                       :usage {:total_tokens 3}})]
        (server/stop!)
        (server/start! config)
        ;; start! folds/reloads the durable journal before accepting work, so
        ;; seed the test Bot after that startup boundary.
        (seed-bot!)
        (try (f) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous)
        (hermes/reset-runs!)))))

(deftest hermes-profile-session-and-run-contract-is-reachable-over-http
  (with-server
    (fn []
      (testing "the compatibility surface is authenticated"
        (is (= 401 (:status (request :get "/api/profiles" nil nil)))))

      (testing "profile multiplex resolves one canonical Bot Chat"
        (let [profiles (request :get "/api/profiles" nil "test-token")
              sessions (request :get "/p/bot-1/api/sessions" nil
                                "test-token")]
          (is (= 200 (:status profiles)))
          (is (= "bot-1" (get-in profiles [:body :data 0 :id])))
          (is (= 200 (:status sessions)))
          (is (= "Bot Chat" (get-in sessions [:body :data 0 :title])))))

      (testing "run start, poll and SSE use Hermes envelopes"
        (let [started (request :post "/p/bot-1/v1/runs"
                               {:input "inspect"} "test-token")
              run-id (get-in started [:body :run_id])]
          (is (= 202 (:status started)))
          (is (str/starts-with? run-id "run_"))
          (loop [remaining 1000]
            (let [status (request :get (str "/v1/runs/" run-id)
                                  nil "test-token")]
              (if (or (= "completed" (get-in status [:body :status]))
                      (zero? remaining))
                (do
                  (is (= "completed" (get-in status [:body :status])))
                  (is (= "done" (get-in status [:body :output]))))
                (do (Thread/sleep 10) (recur (dec remaining))))))
          (let [events (request :get (str "/v1/runs/" run-id "/events")
                                nil "test-token")]
            (is (= 200 (:status events)))
            (is (str/starts-with? (:content-type events) "text/event-stream"))
            (is (str/includes? (:raw events) "\"event\":\"run.started\""))
            (is (str/includes? (:raw events) "\"event\":\"run.completed\""))))))))
