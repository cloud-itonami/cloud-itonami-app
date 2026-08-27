(ns cloud.itonami.app.issue-comment-http-test
  "Comment mode over the real HTTP surface.

  The route RECORDS; it does not run the turn. So the assertion that matters
  most here is a negative one — `bots/send!` is redefined to fail the test if
  it is reached at all. A handler that quietly dispatched would still return
  200 with the same body, and every other assertion in this file would still
  pass while an HTTP request sat open for the length of a Goal."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ^:private origin "http://localhost:1338")
(def ^:private csrf "issue-comment-csrf")
(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

;; A crop shaped like the ones the client builds, with Japanese text in it so
;; the bytes that come back can be compared rather than assumed.
(def ^:private crop-svg
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
       "<foreignObject width=\"100\" height=\"50\">"
       "<div xmlns=\"http://www.w3.org/1999/xhtml\">実行に失敗しました。</div>"
       "</foreignObject></svg>"))

(def ^:private posted (atom nil))

(defonce ^:private client (HttpClient/newHttpClient))

(defn- port [] (.getPort (.getAddress @server/server)))

(defn- call
  ([method path body with-csrf?] (call method path body with-csrf? :string))
  ([method path body with-csrf? as]
   (let [builder (-> (HttpRequest/newBuilder
                      (URI/create (str "http://127.0.0.1:" (port) path)))
                     (.header "Content-Type" "application/json")
                     (.header "Origin" origin))
         builder (if with-csrf?
                   (.header builder "X-CLOUD-ITONAMI-CSRF" csrf)
                   builder)
         request (case method
                   :get (.GET builder)
                   :post (.POST builder (HttpRequest$BodyPublishers/ofString
                                         (json/write-str (or body {})))))]
     (if (= as :bytes)
       (let [response (.send client (.build request)
                             (HttpResponse$BodyHandlers/ofByteArray))]
         {:status (.statusCode response)
          :content-type (-> response .headers (.firstValue "Content-Type")
                            (.orElse ""))
          :csp (-> response .headers (.firstValue "Content-Security-Policy")
                   (.orElse ""))
          :nosniff (-> response .headers (.firstValue "X-Content-Type-Options")
                       (.orElse ""))
          :bytes (.body response)})
       (let [response (.send client (.build request)
                             (HttpResponse$BodyHandlers/ofString))]
         {:status (.statusCode response)
          :body (try (json/read-str (.body response) :key-fn keyword)
                     (catch Exception _ (.body response)))})))))

(defn- with-server [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-issue-comment"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        session {:csrf csrf :user-id "alice" :organization-id "org-1"
                 :kind :passkey :authn-level :phishing-resistant}]
    (reset! posted nil)
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    identity/session (fn [_] session)
                    identity/require-passkey! identity
                    identity/configure! (fn [_] nil)
                    ;; Ownership is real in production and there is no Bot in
                    ;; this store, so the check is satisfied rather than
                    ;; removed: the refusal itself is exercised below.
                    bots/assert-owned! (fn [_session bot-id]
                                         (when (= "bot-not-mine" bot-id)
                                           (throw (ex-info "この Bot はこのセッションのものではありません。"
                                                           {:type :bot/forbidden})))
                                         nil)
                    bots/send! (fn [& args]
                                 (reset! posted args)
                                 (throw (ex-info "bots/send! must not be reached"
                                                 {:type :test/unexpected-dispatch})))]
        (server/stop!)
        (server/start! config)
        (try (f) (finally (server/stop!))))
      (finally (server/stop!) (reset! store/state previous)))))

(def ^:private valid
  {:comment "ここ、失敗の理由が出ていない"
   :view "bots"
   :bot-id "bot-1"
   :element {:selector "section[data-view-panel=\"bots\"] > div.bot-row"
             :tag "div" :classes ["bot-row"] :text "実行に失敗しました。"}
   :region {:x 412 :y 288 :width 340 :height 96
            :viewport-width 1300 :viewport-height 900
            :device-pixel-ratio 2}
   :svg crop-svg})

(deftest a-comment-is-recorded-and-composed-but-not-run
  (with-server
    (fn []
      (let [response (call :post "/api/bots/comments" valid true)
            text (get-in response [:body :text])]
        (is (= 200 (:status response)))
        (is (str/starts-with? (get-in response [:body :id]) "issue-"))
        (is (true? (get-in response [:body :image :stored?])))
        (is (= "bot-1" (get-in response [:body :bot-id])))
        (testing "the turn is NOT run here"
          (is (nil? @posted)
              "a Goal is minutes long; this request is a write to disk"))
        (testing "the composed Goal carries what a Bot can search the repository for"
          (is (str/includes? text "section[data-view-panel=\"bots\"]"))
          (is (str/includes? text "実行に失敗しました"))
          (is (str/includes? text "x=412 y=288 w=340 h=96"))
          (is (str/includes? text (get-in response [:body :id])))
          (is (str/includes? text (get-in response [:body :image :url]))
              "and points at the crop that was just stored"))))))

(deftest a-bot-this-session-does-not-own-is-refused-before-anything-is-written
  (with-server
    (fn []
      (let [response (call :post "/api/bots/comments"
                           (assoc valid :bot-id "bot-not-mine") true)]
        (is (= 403 (:status response)))
        (is (= "forbidden" (get-in response [:body :error :type])))))))

(deftest the-stored-crop-comes-back-byte-for-byte-and-inert
  (with-server
    (fn []
      (let [posted-response (call :post "/api/bots/comments" valid true)
            url (get-in posted-response [:body :image :url])
            fetched (call :get url nil false :bytes)]
        (is (= 200 (:status fetched)))
        (is (str/starts-with? (:content-type fetched) "image/svg+xml"))
        (is (= (seq (.getBytes crop-svg "UTF-8")) (seq (:bytes fetched))))
        (testing "and it is served with no authority of its own"
          ;; The crop is a clone of the live DOM, which shows content this
          ;; application did not write. Serving it from this origin without
          ;; these two headers would hand that content the session.
          (is (str/includes? (:csp fetched) "sandbox"))
          (is (str/includes? (:csp fetched) "default-src 'none'"))
          (is (= "nosniff" (:nosniff fetched))))))))

(deftest an-unknown-or-traversing-id-is-refused
  (with-server
    (fn []
      (is (= 404 (:status (call :get "/api/bots/comments/issue-00000000-0000-0000-0000-000000000000/image"
                                nil false))))
      (testing "an id that is not one this handler minted never reaches the filesystem"
        ;; The property is that no file is served, not which refusal is used.
        ;; `%2F` is decoded before the handler sees it, so a traversal attempt
        ;; stops being one path segment and falls off the end of the `cond` as
        ;; 405 rather than being matched and rejected as 404. Both are
        ;; refusals; asserting only 404 would fail on a correct server.
        (doseq [path ["/api/bots/comments/..%2F..%2Fconfig/image"
                      "/api/bots/comments/config/image"
                      "/api/bots/comments/issue-..%2F..%2Fconfig/image"]]
          (let [response (call :get path nil false)]
            (is (contains? #{404 405} (:status response))
                (str path " was answered " (:status response)))
            (is (not= 200 (:status response)))))))))

(deftest each-refusal-arrives-under-its-own-name
  (with-server
    (fn []
      (testing "an empty comment"
        (let [response (call :post "/api/bots/comments"
                             (assoc valid :comment "  ") true)]
          (is (= 400 (:status response)))
          (is (= "empty" (get-in response [:body :error :type])))))
      (testing "a comment about nothing on the screen"
        (let [response (call :post "/api/bots/comments"
                             (dissoc valid :element :region) true)]
          (is (= 400 (:status response)))
          (is (= "no-target" (get-in response [:body :error :type])))))
      (testing "no destination"
        (let [response (call :post "/api/bots/comments"
                             (dissoc valid :bot-id) true)]
          (is (= 400 (:status response)))
          (is (= "no-bot" (get-in response [:body :error :type])))))
      (testing "nothing was composed on any of those"
        (is (nil? @posted))))))

(deftest a-write-without-csrf-is-refused
  (with-server
    (fn []
      (let [response (call :post "/api/bots/comments" valid false)]
        (is (= 403 (:status response)))
        (is (nil? (get-in response [:body :text]))
            "the refusal happens before anything is composed")))))

(deftest a-comment-with-no-crop-still-becomes-a-goal
  (with-server
    (fn []
      (let [response (call :post "/api/bots/comments" (dissoc valid :svg) true)]
        (is (= 200 (:status response))
            "the comment is still worth sending; the crop was best-effort")
        (is (false? (get-in response [:body :image :stored?])))
        (is (= "absent" (get-in response [:body :image :reason]))
            "which of the two it was must survive to the caller")
        (is (str/includes? (get-in response [:body :text]) "画像: ありません")
            "and the Goal says so rather than pointing at a URL with nothing behind it")))))

(deftest an-executable-crop-is-refused-and-nothing-reaches-the-bot
  (with-server
    (fn []
      (let [response (call :post "/api/bots/comments"
                           (assoc valid :svg "<svg><script>alert(1)</script></svg>")
                           true)]
        (is (= 413 (:status response)))
        (is (nil? (get-in response [:body :text]))
            "a refused crop refuses the whole comment rather than composing it bare")))))
