(ns cloud.itonami.app.account-link-sync
  "Global transport for signed Account Link records. The relay stores public
  proofs only; local identity code verifies every wallet signature again."
  (:require [clojure.data.json :as json]
            [kotoba.lang.text :as str]
            [cloud.itonami.app.http-client :as http]
            [cloud.itonami.app.identity :as identity])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(defn- keychain-secret []
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" "cloud-itonami-app.webhooks"
                        "-a" "relay-access" "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(defn- settings [configuration]
  (get configuration :identity-sync {}))

(defn- token [configuration]
  (or (some-> (get-in (settings configuration) [:access-token-env])
              System/getenv str/trim not-empty)
      (keychain-secret)))

(defn configured? [configuration]
  (boolean (and (get-in (settings configuration) [:base-url])
                (token configuration))))

(defn- request! [configuration method path body]
  (when-not (configured? configuration)
    (throw (ex-info "Account Link sync provider が設定されていません。"
                    {:type :wallet/sync-not-configured})))
  (let [base (str/replace (get-in (settings configuration) [:base-url]) #"/$" "")
        headers (cond-> {"Accept" "application/json"
                         "Authorization"
                         (str "Bearer " (token configuration))}
                  body (assoc "Content-Type" "application/json"))
        response (http/request
                  {:url (str base path)
                   :method (if body :post :get)
                   :timeout-seconds 20
                   :headers headers
                   :body (when body (json/write-str body))})
        payload (try
                  (json/read-str (:body response) :key-fn keyword)
                  (catch Exception _ {:error "invalid_response"}))]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "Account Link sync request に失敗しました。"
                      {:type :wallet/sync-failed
                       :status (:status response)})))
    payload))

(defn push-link! [configuration link]
  (request! configuration "POST" "/v1/account-links/upsert" {:link link}))

(defn pull-links! [configuration subject-did]
  (:links
   (request!
    configuration "GET"
    (str "/v1/account-links?subjectDid="
         (URLEncoder/encode subject-did StandardCharsets/UTF_8))
    nil)))

(defn sync!
  [configuration session expected-domain]
  (let [links (identity/wallet-links session)
        pending (filter #(= :pending (:sync-status %)) links)]
    (doseq [link pending]
      (push-link! configuration link)
      (identity/mark-wallet-synced! session (:id link)))
    (let [subject (identity/subject-did session)
          remote (if subject (pull-links! configuration subject) [])
          verified (identity/merge-wallet-links!
                    session remote expected-domain)]
      {:configured? true
       :pushed (count pending)
       :received (count remote)
       :verified (count verified)
       :links (identity/wallet-links session)})))
