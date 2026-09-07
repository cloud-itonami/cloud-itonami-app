(ns cloud.itonami.app.email-login
  "Delivery adapter for one-time email sign-in links.

  This namespace does not decide who may sign in and never sees the identity
  store.  It only hands an already-created, expiring link to a deployment-owned
  HTTPS endpoint.  The endpoint credential is named in configuration and read
  from the environment at call time; the credential itself is never persisted."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.http-client :as http]
            [cloud.itonami.app.policy :as policy])
  (:import [java.net URI]))

(defn- access-token [configuration]
  (some-> (get-in configuration [:email-login :access-token-env])
          System/getenv str/trim not-empty))

(defn- safe-endpoint? [value]
  (try
    (let [uri (URI/create (str value))]
      (and (not (str/blank? (.getHost uri)))
           (nil? (.getRawUserInfo uri))
           (nil? (.getRawFragment uri))
           (or (= "https" (.getScheme uri))
               (and (= "http" (.getScheme uri))
                    (policy/loopback-host? (.getHost uri))))))
    (catch Exception _ false)))

(defn configured?
  "True only when sign-in delivery is explicitly enabled and has a safe,
  authenticated endpoint. HTTP is accepted for loopback test adapters only."
  [configuration]
  (let [endpoint (get-in configuration [:email-login :delivery-endpoint])]
    (boolean (and (true? (get-in configuration [:email-login :enabled?]))
                  (safe-endpoint? endpoint)
                  (access-token configuration)))))

(defn deliver!
  "Deliver one magic link. The adapter contract deliberately contains no user,
  membership, or session object: a mail provider has no need to receive them."
  [configuration {:keys [to magic-link expires-at]}]
  (when-not (configured? configuration)
    (throw (ex-info "Email ログイン配信が設定されていません。"
                    {:type :email-login/not-configured})))
  (let [endpoint (get-in configuration [:email-login :delivery-endpoint])
        response (http/request
                  {:url endpoint
                   :method :post
                   :timeout-seconds 15
                   :headers {"Accept" "application/json"
                             "Content-Type" "application/json"
                             "Authorization"
                             (str "Bearer " (access-token configuration))}
                   :body (json/write-str
                          {:template "cloud-itonami-email-login"
                           :to to
                           :magicLink magic-link
                           :expiresAt expires-at})})]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Email ログインリンクを配信できませんでした。"
                      {:type :email-login/delivery-failed
                       :status (:status response)})))
    true))
