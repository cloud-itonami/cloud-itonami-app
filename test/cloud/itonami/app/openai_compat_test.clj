(ns cloud.itonami.app.openai-compat-test
  "Drives `/v1/chat/completions` over real HTTP, in both modes.

  The compatibility claim is about a wire format, so these tests read frames
  rather than call functions: an OpenAI-compatible client sees `Content-Type`,
  frame boundaries, chunk order and the `[DONE]` sentinel, and nothing in the
  Clojure API can be substituted for them.

  The provider is stubbed. What is under test is this app's wire contract, not
  a local model's output — a real provider would make the assertions about
  chunk order depend on a model's token boundaries."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private config
  {:brand {:name "Test"}
   ;; Port 0: the OS picks a free port, so a server already on 1338 — or
   ;; another session — cannot make this flake.
   :server {:host "127.0.0.1" :port 0 :public-origin "http://localhost:1338"
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}
               ;; Enabled but not local, with the cloud gate shut: the one
               ;; shape that makes `select-provider` refuse.
               {:id "cloud" :kind :openai-compatible :local? false
                :enabled? true}]})

(defonce ^:private client (HttpClient/newHttpClient))

(defn- post [path body]
  (let [request (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:"
                                      (.getPort (.getAddress @server/server))
                                      path)))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :content-type (-> response .headers (.firstValue "Content-Type")
                       (.orElse ""))
     :raw (.body response)}))

(defn- frames
  "The `data:` payloads of an SSE body, in order, parsed except `[DONE]`."
  [raw]
  (->> (str/split raw #"\n\n")
       (map str/trim)
       (remove str/blank?)
       (map (fn [frame]
              (is (str/starts-with? frame "data: ")
                  "every SSE frame is a data frame")
              (let [payload (subs frame 6)]
                (if (= "[DONE]" payload)
                  :done
                  (json/read-str payload :key-fn keyword)))))
       vec))

(defn- with-server [chat-stream body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-openai-compat"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/configure! (fn [_] nil)
                    provider/chat-stream! chat-stream
                    provider/chat (fn [_ _] {:content "整理しました"
                                             :usage {:total_tokens 4}})]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- two-deltas [_ _ on-delta]
  (on-delta "整理")
  (on-delta "しました")
  {:content "整理しました" :usage {:total_tokens 4}})

(deftest streams-an-openai-chunk-sequence
  (with-server two-deltas
    (fn []
      (let [response (post "/v1/chat/completions"
                           {:model "test-model" :stream true
                            :messages [{:role "user" :content "こんにちは"}]})
            events (frames (:raw response))]
        (is (= 200 (:status response)))
        (is (str/starts-with? (:content-type response) "text/event-stream"))

        (testing "role first, then one chunk per delta, then a stop chunk"
          (is (= [{:role "assistant"} {:content "整理"} {:content "しました"} {}]
                 (mapv #(get-in % [:choices 0 :delta]) (butlast events))))
          (is (= [nil nil nil "stop"]
                 (mapv #(get-in % [:choices 0 :finish_reason])
                       (butlast events)))))

        (testing "terminated by the [DONE] sentinel"
          (is (= :done (last events))))

        (testing "usage is withheld unless the caller asked for it"
          (is (every? #(not (contains? % :usage)) (butlast events))))

        (testing "every chunk carries the same completion id, object and model"
          (is (= 1 (count (into #{} (map :id) (butlast events)))))
          (is (= #{"chat.completion.chunk"}
                 (into #{} (map :object) (butlast events))))
          (is (= #{"test-model"} (into #{} (map :model) (butlast events)))))

        (testing "and that id is the one the store recorded for the turn"
          (is (= (:id (first events))
                 (get-in @store/state [:last-response :id]))))))))

(deftest streams-usage-when-stream-options-ask-for-it
  (with-server two-deltas
    (fn []
      (let [events (frames (:raw (post "/v1/chat/completions"
                                       {:stream true
                                        :stream_options {:include_usage true}
                                        :messages [{:role "user"
                                                    :content "こんにちは"}]})))
            usage-chunk (last (butlast events))]
        (is (= :done (last events)))
        (is (= {:total_tokens 4} (:usage usage-chunk)))
        (testing "usage belongs to the completion, so it names no choice"
          (is (= [] (:choices usage-chunk))))))))

(deftest an-empty-completion-is-still-a-well-formed-stream
  (with-server (fn [_ _ _] {:content "" :usage nil})
    (fn []
      (let [events (frames (:raw (post "/v1/chat/completions"
                                       {:stream true
                                        :messages [{:role "user"
                                                    :content "こんにちは"}]})))]
        (is (= [{:role "assistant"} {}]
               (mapv #(get-in % [:choices 0 :delta]) (butlast events))))
        (is (= :done (last events)))))))

(deftest a-refusal-before-the-first-delta-is-a-status-not-a-stream
  (with-server two-deltas
    (fn []
      (let [response (post "/v1/chat/completions"
                           {:stream true :provider "cloud"
                            :messages [{:role "user" :content "こんにちは"}]})]
        (testing "a denied provider keeps its status code under stream: true"
          (is (= 403 (:status response)))
          (is (str/starts-with? (:content-type response) "application/json")))
        ;; "denied", not "provider/denied": the handler's error envelope has
        ;; always sent the bare `name` of the ex-data type, and a streamed
        ;; refusal is the same refusal.
        (is (= "denied"
               (get-in (json/read-str (:raw response) :key-fn keyword)
                       [:error :type])))))))

(deftest a-failure-after-the-first-delta-is-reported-in-the-stream
  (with-server (fn [_ _ on-delta]
                 (on-delta "部分")
                 (throw (ex-info "provider stopped mid-stream"
                                 {:type :provider/error})))
    (fn []
      (let [response (post "/v1/chat/completions"
                           {:stream true
                            :messages [{:role "user" :content "こんにちは"}]})
            events (frames (:raw response))]
        ;; The status was spent on the first delta, so the error can only be
        ;; said in-band — but it is said, rather than closing on a truncated
        ;; answer that reads as success.
        (is (= 200 (:status response)))
        (is (= {:content "部分"} (get-in (second events) [:choices 0 :delta])))
        (is (= "error" (get-in (nth events 2) [:error :type])))
        (is (= :done (last events)))
        (testing "no stop chunk claims the answer finished"
          (is (not-any? #(= "stop" (get-in % [:choices 0 :finish_reason]))
                        (butlast events))))))))

(deftest non-streaming-requests-are-unchanged
  (with-server two-deltas
    (fn []
      (let [response (post "/v1/chat/completions"
                           {:messages [{:role "user" :content "こんにちは"}]})
            body (json/read-str (:raw response) :key-fn keyword)]
        (is (= 200 (:status response)))
        (is (str/starts-with? (:content-type response) "application/json"))
        (is (= "chat.completion" (:object body)))
        (is (= "整理しました" (get-in body [:choices 0 :message :content])))
        (is (= "stop" (get-in body [:choices 0 :finish_reason])))))))
