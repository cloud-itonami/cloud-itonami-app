(ns cloud.itonami.app.mailbox-http-test
  "Drives the inbox endpoints over real HTTP against a running server.

  `mailbox_test.clj` covers the model questions — what a mark is, what
  trashing means over an archive nobody here owns. These cover what only the
  server can get wrong: whether a route is wired at all, whether its path
  regex matches a message id that is a filename with dots and underscores in
  it, whether a write without the CSRF token fails closed, and whether a
  refusal arrives as a status code a client can act on rather than a 500.

  Same shape as `business_http_test`: the passkey gate is stubbed rather
  than satisfied, because a real ceremony needs an authenticator and what is
  under test is the route layer behind that gate."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace :as workspace])
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
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}]})

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
                  (catch Exception _ (.body response)))})))

(defn- authed [method path & [body]]
  (request method path {:body (some-> body json/write-str)
                        :headers {"Origin" origin "X-CLOUD-ITONAMI-CSRF" csrf}}))

(def ^:private message-id "20260728T010203Z_a.eml")

(defn- with-server [who body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-mailbox-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temporary)
        inbox (io/file root "m365-archive/mail/受信トレイ")
        previous @store/state]
    (.mkdirs inbox)
    (spit (io/file inbox message-id)
          (str "From: Example Person <sender@example.com>\r\n"
               "Subject: 進捗の確認\r\nMessage-ID: <a@example.com>\r\n\r\n"
               "来週の進捗について確認します。"))
    (try
      (reset! store/state (store/initial-state))
      (workspace/clear-cache!)
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    workspace/workspace-root (constantly root)
                    local-identity/session (fn [_] {:csrf csrf :user-id who})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (workspace/clear-cache!)
        (reset! store/state previous)))))


(defn- path-for [action] (str "/api/workspace/inbox/messages/" message-id "/" action))

(deftest a-message-is-marked-starred-and-filed-over-http
  (with-server "person-alice"
    (fn []
      (let [listed (authed :get "/api/workspace/inbox")]
        (is (= 200 (:status listed)))
        (is (= [message-id] (mapv :id (get-in listed [:body :items]))))
        (is (= 0 (get-in listed [:body :unread]))))
      (testing "the path regex matches an id that is a filename"
        ;; Dots and underscores and a `Z` in the middle: a regex that
        ;; stopped at the first dot would 404 here and nowhere else.
        (let [r (authed :post (path-for "read") {:read? false})]
          (is (= 200 (:status r)))
          (is (false? (get-in r [:body :message :read?])))))
      (is (= 1 (get-in (authed :get "/api/workspace/inbox") [:body :unread])))
      (testing "starring, and finding it where the star put it"
        ;; Empty first. Without this the next assertion passes just as well
        ;; when the label parameter is ignored altogether — the inbox list
        ;; and the starred list would be the same list.
        (is (= [] (get-in (authed :get "/api/workspace/inbox?label=starred")
                          [:body :items])))
        (is (= 200 (:status (authed :post (path-for "label")
                                    {:label "starred" :on? true}))))
        (let [starred (authed :get "/api/workspace/inbox?label=starred")]
          (is (= [message-id] (mapv :id (get-in starred [:body :items]))))))
      (testing "trashing takes it out of the inbox and leaves the file alone"
        (is (= 200 (:status (authed :post (path-for "trash") {:trashed? true}))))
        (is (= [] (get-in (authed :get "/api/workspace/inbox") [:body :items])))
        (is (= [message-id]
               (mapv :id (get-in (authed :get "/api/workspace/inbox?label=trash")
                                 [:body :items])))))
      (testing "and the search reaches the server, not a filtered copy"
        (authed :post (path-for "trash") {:trashed? false})
        (is (= [message-id]
               (mapv :id (get-in (authed :get "/api/workspace/inbox?q=進捗")
                                 [:body :items]))))
        (is (= [] (get-in (authed :get "/api/workspace/inbox?q=みつからない")
                          [:body :items])))))))

(deftest marks-are-one-persons
  (with-server "person-alice"
    (fn []
      (authed :post (path-for "read") {:read? false})
      (is (= 1 (get-in (authed :get "/api/workspace/inbox") [:body :unread])))
      (with-redefs [local-identity/session (fn [_] {:csrf csrf :user-id "person-bob"})]
        (is (= 0 (get-in (authed :get "/api/workspace/inbox") [:body :unread]))
            "the archive is shared; what has been read is not")))))

(deftest a-refusal-arrives-as-something-a-client-can-act-on
  (with-server "person-alice"
    (fn []
      (testing "a mark on a message that is not in the archive is 404"
        (let [r (authed :post "/api/workspace/inbox/messages/no-such.eml/read" {})]
          (is (= 404 (:status r)))
          (is (= "not-found" (get-in r [:body :error :type])))))
      (testing "the two places are not labels, and saying so is a 400"
        (let [r (authed :post (path-for "label") {:label "trash" :on? true})]
          (is (= 400 (:status r)))
          (is (= "reserved-label" (get-in r [:body :error :type]))))))))

(deftest a-thread-can-be-asked-for-over-http
  (with-server "person-alice"
    (fn []
      (let [thread (:thread (first (get-in (authed :get "/api/workspace/inbox")
                                           [:body :items])))
            r (authed :get (str "/api/workspace/inbox/threads/"
                                (java.net.URLEncoder/encode thread "UTF-8")))]
        (is (= 200 (:status r)))
        (is (= [message-id] (mapv :id (get-in r [:body :items]))))))))

(deftest a-write-without-the-csrf-token-fails-closed
  (with-server "person-alice"
    (fn []
      (doseq [[action body] [["read" {:read? false}]
                             ["label" {:label "starred" :on? true}]
                             ["trash" {:trashed? true}]]]
        (let [r (request :post (path-for action)
                         {:body (json/write-str body)
                          :headers {"Origin" origin}})]
          (is (not= 200 (:status r)) (str action " without a CSRF token"))))
      (let [listed (authed :get "/api/workspace/inbox")]
        (is (= [message-id] (mapv :id (get-in listed [:body :items]))))
        (is (= 0 (get-in listed [:body :unread])) "and nothing was marked")))))
