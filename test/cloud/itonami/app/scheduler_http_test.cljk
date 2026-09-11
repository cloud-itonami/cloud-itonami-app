(ns cloud.itonami.app.scheduler-http-test
  "Drives the appointment endpoints over real HTTP against a running server.

  `scheduler_test.clj` covers the model questions — who may invite, what an
  unanswered invitation is, when a clash is a clash. These cover what only
  the server can get wrong: whether a route is wired at all, whether its
  path regex matches what the client sends, whether a write without the CSRF
  token fails closed, and whether a refusal arrives as a status code a
  client can act on rather than a 500.

  Same shape as `business_http_test`: the passkey gate is stubbed rather
  than satisfied, because a real ceremony needs an authenticator and what is
  under test is the route layer behind that gate."
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
   :providers [{:id "ollama" :kind :ollama :local? true :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

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

(defn- with-server [who body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-scheduler-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/session (fn [_] {:csrf csrf :user-id who})
                    local-identity/require-passkey! identity
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous)))))

(def ^:private morning
  {:title "四半期の打ち合わせ"
   :start "2026-08-03T09:00:00Z"
   :end "2026-08-03T10:00:00Z"
   :attendees ["person-bob"]})

(deftest an-appointment-is-made-invited-to-and-answered-over-http
  (with-server "person-alice"
    (fn []
      (let [made (authed :post "/api/workspace/scheduler/events" morning)
            id (get-in made [:body :event :id])]
        (is (= 200 (:status made)))
        (is (string? id))
        (testing "it appears in the same list the machine's calendar does"
          (let [listed (authed :get "/api/workspace/scheduler")]
            (is (= 200 (:status listed)))
            (is (some #(= id (:id %)) (get-in listed [:body :items])))
            (is (= "person-alice" (get-in listed [:body :you])))))
        (testing "the organizer may invite; the path regex matches the id"
          (let [invited (authed :post
                                (str "/api/workspace/scheduler/events/" id "/invite")
                                {:person "person-carol"})]
            (is (= 200 (:status invited)))
            (is (= ["person-bob" "person-carol"]
                   (get-in invited [:body :event :attendees])))))
        (testing "and answering their own invitation is 403, not 500"
          (let [refused (authed :post
                                (str "/api/workspace/scheduler/events/" id "/respond")
                                {:status "accepted"})]
            (is (= 403 (:status refused)))
            ;; The name without its namespace, which is what this server
            ;; sends for every error type. It means `:scheduler/not-found`
            ;; and `:drive/not-found` reach a client as the same word; the
            ;; route it came back from is what tells them apart.
            (is (= "not-invited" (get-in refused [:body :error :type])))))
        (testing "conflicts answer for the asker"
          (let [clashes (authed :get
                                (str "/api/workspace/scheduler/events/" id "/conflicts"))]
            (is (= 200 (:status clashes)))
            (is (= [] (get-in clashes [:body :conflicts])))))))))

(deftest an-invitee-answers-and-the-organizer-sees-it
  (with-server "person-alice"
    (fn []
      (let [id (get-in (authed :post "/api/workspace/scheduler/events" morning)
                       [:body :event :id])]
        (with-redefs [local-identity/session (fn [_] {:csrf csrf :user-id "person-bob"})]
          (let [answered (authed :post
                                 (str "/api/workspace/scheduler/events/" id "/respond")
                                 {:status "declined"})]
            (is (= 200 (:status answered)))
            (is (= "declined" (get-in answered [:body :event :your-rsvp])))))
        (let [listed (authed :get "/api/workspace/scheduler")
              event (first (filter #(= id (:id %)) (get-in listed [:body :items])))]
          (is (= {:person-bob "declined"} (:rsvp event))
              "the organizer reads the answer off the same event"))))))

(deftest a-refusal-arrives-as-something-a-client-can-act-on
  (with-server "person-alice"
    (fn []
      (testing "an appointment with no times is 422 — understood, and not one
                the model accepts"
        (let [r (authed :post "/api/workspace/scheduler/events" {:title "いつか"})]
          (is (= 422 (:status r)))
          (is (= "invalid-event" (get-in r [:body :error :type])))))
      (testing "somebody else's event is 404 and not 403, so its existence
                stays private"
        (let [r (authed :post "/api/workspace/scheduler/events/evt-nothing/respond"
                        {:status "accepted"})]
          (is (= 404 (:status r)))))
      (testing "an answer the model does not know is 400"
        (let [id (get-in (authed :post "/api/workspace/scheduler/events" morning)
                         [:body :event :id])]
          (with-redefs [local-identity/session
                        (fn [_] {:csrf csrf :user-id "person-bob"})]
            (is (= 400 (:status (authed :post
                                        (str "/api/workspace/scheduler/events/"
                                             id "/respond")
                                        {:status "maybe-ish"}))))))))))

(deftest a-write-without-the-csrf-token-fails-closed
  (with-server "person-alice"
    (fn []
      (doseq [[path body] [["/api/workspace/scheduler/events" morning]
                           ["/api/workspace/scheduler/events/evt-x/invite"
                            {:person "person-bob"}]
                           ["/api/workspace/scheduler/events/evt-x/respond"
                            {:status "accepted"}]
                           ["/api/workspace/scheduler/events/evt-x/cancel" {}]]]
        (let [r (request :post path {:body (json/write-str body)
                                     :headers {"Origin" origin}})]
          (is (not= 200 (:status r)) (str path " without a CSRF token"))))
      ;; NOT `(= [] items)`. This endpoint merges the app's own events with the
      ;; MACHINE's calendar — the test above says so in as many words — so an
      ;; empty list is an assertion about the developer's diary rather than about
      ;; the app. It passed only on a machine with no calendar, and on one with
      ;; a calendar it failed by printing the owner's real appointments into the
      ;; failure output. (Measured 2026-07-31: fifteen of them.)
      ;;
      ;; What the assertion is actually for is that the rejected writes created
      ;; nothing, so that is what it now says.
      (let [items (get-in (authed :get "/api/workspace/scheduler") [:body :items])]
        (is (not-any? #(= (:title morning) (:title %)) items)
            "the rejected write created nothing")))))
