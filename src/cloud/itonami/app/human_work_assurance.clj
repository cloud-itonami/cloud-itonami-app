(ns cloud.itonami.app.human-work-assurance
  "Online identity and qualification checks through explicitly configured providers.

  A provider is an authority adapter, never an ambient URL supplied by a
  browser. Requests contain the minimum claim coordinates needed to check one
  version. Responses are accepted only when they bind the same worker, claim,
  version, and organization. Raw identity documents are never stored here."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.human-work :as human-work]
            [cloud.itonami.app.store :as store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def schema "cloud.itonami.app.human-work-assurance.v1")

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn- provider! [configuration provider-id kind]
  (let [provider (some #(when (= provider-id (:id %)) %)
                       (get-in configuration [:human-work :assurance :providers]))]
    (when-not (and provider (:enabled? provider) (= kind (:kind provider)))
      (fail! :human-work/provider-unavailable
             "The requested assurance provider is not enabled"
             {:provider-id provider-id :kind kind}))
    provider))

(defn- https-endpoint! [value]
  (let [uri (try (URI/create (str value)) (catch Exception _ nil))]
    (when-not (and uri (= "https" (.getScheme uri)) (.getHost uri))
      (fail! :human-work/provider-invalid
             "Assurance provider endpoint must be an absolute HTTPS URL"))
    uri))

(defn- online-check!
  [provider body]
  (let [uri (https-endpoint! (:endpoint provider))
        token (some-> (:token-env provider) System/getenv str/trim not-empty)
        _ (when (and (:token-env provider) (nil? token))
            (fail! :human-work/provider-credential-missing
                   "Assurance provider credential is unavailable"))
        request (cond-> (-> (HttpRequest/newBuilder uri)
                            (.timeout (Duration/ofSeconds
                                       (long (or (:timeout-seconds provider) 20))))
                            (.header "Accept" "application/json")
                            (.header "Content-Type" "application/json")
                            (.POST (HttpRequest$BodyPublishers/ofString
                                    (json/write-str body))))
                  token (.header "Authorization" (str "Bearer " token)))
        response (.send (HttpClient/newHttpClient) (.build request)
                        (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (when-not (<= 200 status 299)
      (fail! :human-work/provider-failed
             "Assurance provider refused the online check"
             {:provider-id (:id provider) :status status}))
    (try
      (json/read-str (.body response) :key-fn keyword)
      (catch Exception _
        (fail! :human-work/provider-invalid
               "Assurance provider returned invalid JSON"
               {:provider-id (:id provider)})))))

(def ^:dynamic *online-check!* online-check!)

(defn- claim! [worker-id kind claim-id]
  (let [profile (human-work/worker-profile worker-id)
        [path id-key] (case kind
                        :credential [:credentials :credential-id]
                        :location [:locations :location-id])
        claim (some #(when (= claim-id (get % id-key)) %) (get profile path))]
    (when-not profile
      (fail! :human-work/worker-not-found "Worker is not registered"))
    (when-not claim
      (fail! :human-work/claim-not-found "Worker claim is not registered"))
    claim))

(defn check-credential!
  "Ask one configured issuer/administrative registry about one claim version,
  then bind the verified result to the requesting organization."
  [configuration {:keys [worker-id credential-id provider-id organization-id
                         verifier-id]}]
  (let [provider (provider! configuration provider-id :credential-registry)
        claim (claim! worker-id :credential credential-id)
        allowed-types (set (:credential-types provider))
        _ (when (and (seq allowed-types)
                     (not (contains? allowed-types (:type claim))))
            (fail! :human-work/provider-scope-denied
                   "Provider is not configured for this credential type"))
        request {:schema schema :operation "credential-check"
                 :organization-id organization-id :worker-id worker-id
                 :credential-id credential-id
                 :claim-version (:claim-version claim)
                 :type (:type claim) :code (:code claim)
                 :issuer (:issuer claim) :jurisdiction (:jurisdiction claim)
                 :scopes (:scopes claim) :evidence-ref (:evidence-ref claim)}
        result (*online-check!* provider request)
        decision (some-> (:decision result) name str/lower-case)]
    (when-not (and (= worker-id (:worker-id result))
                   (= credential-id (:credential-id result))
                   (= (:claim-version claim) (:claim-version result))
                   (= organization-id (:organization-id result))
                   (#{"verified" "rejected" "revoked"} decision)
                   (not (str/blank? (str (:reference result)))))
      (fail! :human-work/provider-binding-mismatch
             "Provider result is not bound to the requested claim version"))
    (human-work/verify-credential!
     worker-id credential-id
     {:decision decision
      :valid-until (:valid-until result)
      :evidence-ref (str "provider:" provider-id ":" (:reference result))
      :note "online issuer or administrative registry check"}
     verifier-id organization-id)))

(defn check-identity!
  "Ask one configured identity provider and retain only an assurance receipt."
  [configuration {:keys [worker-id provider-id organization-id]}]
  (let [provider (provider! configuration provider-id :identity)
        _ (when-not (human-work/worker-profile worker-id)
            (fail! :human-work/worker-not-found "Worker is not registered"))
        request {:schema schema :operation "identity-check"
                 :organization-id organization-id :worker-id worker-id}
        result (*online-check!* provider request)
        status (some-> (:status result) name str/lower-case)
        level (some-> (:level result) name str/lower-case)]
    (when-not (and (= worker-id (:worker-id result))
                   (= organization-id (:organization-id result))
                   (contains? human-work/identity-levels level)
                   (#{"verified" "rejected" "revoked"} status)
                   (not (str/blank? (str (:reference result)))))
      (fail! :human-work/provider-binding-mismatch
             "Provider result is not bound to the requested worker"))
    (human-work/record-identity-assurance!
     worker-id {:provider-id provider-id
                :provider-reference (str (:reference result))
                :level level :status status
                :checked-at (or (:checked-at result) (store/now))
                :valid-until (:valid-until result)
                :evidence-ref (str "provider:" provider-id ":"
                                   (:reference result))})))
