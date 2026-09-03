(ns cloud.itonami.app.central-auth-http-test
  "Hosted sign-in starts as a GET navigation. Requiring Origin on that start
  is what made 127.0.0.1 unable to reach auth.itonami.cloud at all."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.server :as server]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:private public-origin "http://localhost:1338")

(def ^:private config
  {:brand {:name "Test"}
   :server {:host "127.0.0.1" :port 0 :public-origin public-origin
            :webauthn-rp-id "localhost"}
   :routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false :bind-loopback-only? true}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defonce ^:private client
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/NEVER)
      .build))

(defn- bound-port []
  (.getPort (.getAddress @server/server)))

(defn- with-server [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-central-auth-http"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/configure! (fn [_] nil)]
        (server/stop!)
        (server/start! config)
        (try (body) (finally (server/stop!))))
      (finally
        (server/stop!)
        (reset! store/state previous-state)))))

(defn- send-request [method path headers]
  (let [builder (HttpRequest/newBuilder
                 (URI/create (str "http://127.0.0.1:" (bound-port) path)))]
    (doseq [[k v] headers]
      (.header builder k v))
    (let [built (case method
                  :get (.GET builder)
                  :post (.POST builder (HttpRequest$BodyPublishers/ofString "{}")))
          response (.send client (.build built)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :location (.firstValue (.headers response) "location")
       :body (try (json/read-str (.body response) :key-fn keyword)
                  (catch Exception _ {:raw (.body response)}))})))

(defn- location [response]
  (.orElse (:location response) nil))

(deftest get-start-from-the-bind-address-opens-hosted-auth
  (with-server
    (fn []
      (let [response (send-request :get "/api/auth/itonami/start" {})
            loc (location response)]
        (is (= 303 (:status response)))
        (is (str/starts-with? loc "https://auth.itonami.cloud/authorize"))
        (is (str/includes? loc
                           "redirect_uri=http%3A%2F%2Flocalhost%3A1338%2Fapi%2Fauth%2Fitonami%2Fcallback")
            "callback is public-origin, not the 127.0.0.1 Host this GET used")))))

(deftest post-start-from-the-bind-origin-is-still-rejected
  (with-server
    (fn []
      (let [response (send-request :post "/api/auth/itonami/start"
                                   {"Origin" "http://127.0.0.1:1338"
                                    "Content-Type" "application/json"})]
        (is (= 403 (:status response)))
        (is (= "invalid-origin" (get-in response [:body :error :type])))))))

(deftest legacy-email-and-provider-sso-cannot-start-an-app-session
  (with-server
    (fn []
      (doseq [path ["/api/email-authenticate/start"
                    "/api/email-authenticate/finish"
                    "/api/auth/sso/google/start"]]
        (is (= 404 (:status (send-request :post path
                                         {"Origin" public-origin
                                          "Content-Type" "application/json"})))
            path))
      (is (empty? (get-in (store/snapshot) [:identity :sessions]))))))

(deftest callback-passes-its-browser-session-to-central-completion
  (with-server
    (fn []
      (let [browser-session {:user-id "user-1" :kind :passkey}
            seen (atom nil)]
        (with-redefs [local-identity/session
                      (fn [token]
                        (when (= "browser-session-token" token)
                          browser-session))
                      local-identity/complete-central-authentication!
                      (fn [params session]
                        (reset! seen {:params params :session session})
                        {:token "native-session-token" :linked? true})]
          (let [response
                (send-request
                 :get
                 "/api/auth/itonami/callback?state=state-1&code=code-1"
                 {"Cookie" (str local-identity/cookie-name
                                "=browser-session-token")})]
            (is (= 303 (:status response)))
            (is (= "/?auth=itonami-cloud#/settings" (location response)))
            (is (= {:state "state-1" :code "code-1"}
                   (:params @seen)))
            (is (= browser-session (:session @seen)))))))))
