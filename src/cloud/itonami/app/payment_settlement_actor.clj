(ns cloud.itonami.app.payment-settlement-actor
  "Loopback-only authority for organization payables.

  It accepts only an already Passkey-approved `:payment/settle` proposal,
  re-checks the safety-bearing shape, and writes an idempotent settlement
  RECORD. It has no bank credential, no transfer endpoint and no ability to
  move money. A committed response means only that the governed record exists."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest SecureRandom]
           [java.time Instant]))

(def schema "cloud.itonami.app.payment-settlement.v1")

(defn initial-state []
  {:schema schema :records {} :references {} :events []})

(defn- token [x]
  (cond
    (keyword? x) (subs (str x) 1)
    (string? x) x
    :else nil))

(defn- held [rule detail]
  {:status "held"
   :refusal (cond-> {:rule (name rule)}
              detail (assoc :detail detail))})

(defn- nonblank? [x]
  (not (str/blank? (str (or x "")))))

(defn proposal-issues
  "Pure fail-closed validation at the second gate."
  [proposal]
  (let [value (:value proposal)
        payee (:payee value)]
    (cond-> []
      (not (map? proposal)) (conj [:proposal-missing "proposal is required"])
      (not (nonblank? (:id proposal))) (conj [:proposal-id-missing "proposal id is required"])
      (not (nonblank? (:organization-id proposal)))
      (conj [:organization-missing "organization id is required"])
      (not= "payment" (token (:authority proposal)))
      (conj [:authority-mismatch "authority must be payment"])
      (not= "payment/settle" (token (:op proposal)))
      (conj [:op-mismatch "op must be payment/settle"])
      (not= "approved" (token (:status proposal)))
      (conj [:status-not-approved "proposal must already be Passkey-approved"])
      (not (nonblank? (:approved-at proposal)))
      (conj [:approval-time-missing "approved-at is required"])
      (not (nonblank? (:passkey-credential-id proposal)))
      (conj [:passkey-evidence-missing "Passkey credential evidence is required"])
      (not (nonblank? (:digest proposal)))
      (conj [:digest-missing "content digest is required"])
      (not (map? value)) (conj [:value-missing "proposal value is required"])
      (not (and (integer? (:amount-minor value))
                (pos? (:amount-minor value))))
      (conj [:amount-invalid "amount-minor must be a positive integer"])
      (not (nonblank? (:currency value))) (conj [:currency-missing "currency is required"])
      (not (nonblank? (:reference value))) (conj [:reference-missing "reference is required"])
      (not (nonblank? (:funding-account-id value)))
      (conj [:funding-account-missing "funding account is required"])
      (not (nonblank? (:name payee))) (conj [:payee-missing "payee name is required"])
      (not (nonblank? (:number-digest payee)))
      (conj [:payee-account-missing "payee account fingerprint is required"]))))

(defn decide
  "Return `[next-state wire-response]`.

  A repeated identical proposal is idempotent. Reusing an id with a different
  digest, or reusing an organization/reference for a different proposal, is
  held rather than guessed."
  [state proposal now]
  (if-let [[rule detail] (first (proposal-issues proposal))]
    [state (held rule detail)]
    (let [id (:id proposal)
          org (:organization-id proposal)
          reference (get-in proposal [:value :reference])
          digest (:digest proposal)
          prior (get-in state [:records id])
          prior-id (get-in state [:references org reference])]
      (cond
        (and prior (= digest (:proposal-digest prior)))
        [state {:status "committed" :record prior}]

        prior
        [state (held :proposal-id-conflict
                     "proposal id already exists with different content")]

        prior-id
        [state (held :duplicate-reference
                     "organization/reference already has a settlement record")]

        :else
        (let [value (:value proposal)
              record {:schema schema
                      :id (str "settlement-" id)
                      :proposal-id id
                      :proposal-digest digest
                      :organization-id org
                      :reference (:reference value)
                      :amount-minor (:amount-minor value)
                      :currency (:currency value)
                      :funding-account-id (:funding-account-id value)
                      :payee (select-keys (:payee value)
                                          [:name :institution :branch
                                           :account-type :number-last4
                                           :number-digest])
                      :approved-at (:approved-at proposal)
                      :passkey-credential-id (:passkey-credential-id proposal)
                      :recorded-at now
                      :effect :record-only
                      :money-moved? false}
              next-state (-> state
                             (assoc-in [:records id] record)
                             (assoc-in [:references org reference] id)
                             (update :events conj
                                     {:type :payment/settlement-recorded
                                      :proposal-id id
                                      :organization-id org
                                      :reference reference
                                      :at now}))]
          [next-state {:status "committed" :record record}])))))

(defn state-file []
  (io/file (config/data-dir) "payment-settlement.edn"))

(defn token-file []
  (io/file (config/data-dir) "payment-settlement.token"))

(defn- new-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defn ensure-token!
  "Load the actor capability, creating it on first start.

  The file lives beside local state under `CLOUD_ITONAMI_DATA_DIR`, never in
  tracked configuration. Both processes must be the same local operator."
  []
  (let [file (token-file)]
    (.mkdirs (.getParentFile file))
    (when-not (.isFile file)
      (spit file (new-token))
      (.setReadable file false false)
      (.setWritable file false false)
      (.setReadable file true true)
      (.setWritable file true true))
    (str/trim (slurp file))))

(defn token-matches?
  "Constant-time comparison for the loopback actor capability."
  [expected actual]
  (and (nonblank? expected)
       (nonblank? actual)
       (MessageDigest/isEqual
        (.getBytes ^String expected StandardCharsets/UTF_8)
        (.getBytes ^String actual StandardCharsets/UTF_8))))

(defn- load-state []
  (let [file (state-file)]
    (if (.isFile file)
      (merge (initial-state) (edn/read-string (slurp file)))
      (initial-state))))

(defonce state (atom (load-state)))

(defn- persist! [value]
  (let [file (state-file)
        temporary (io/file (.getParentFile file) "payment-settlement.edn.tmp")]
    (.mkdirs (.getParentFile file))
    (spit temporary (pr-str value))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE])))
  value)

(defn commit!
  "Validate and atomically record one proposal."
  [proposal]
  (locking state
    (let [[next-state response] (decide @state proposal (str (Instant/now)))]
      (when-not (identical? @state next-state)
        (reset! state (persist! next-state)))
      response)))

(defn- send-json! [exchange status body]
  (let [bytes (.getBytes (json/write-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- handler [actor-token]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod exchange)
              path (.getPath (.getRequestURI exchange))]
          (cond
            (and (= "GET" method) (= "/health" path))
            (send-json! exchange 200
                        {:status "ok" :service schema
                         :records (count (:records @state))
                         :effect "record-only" :money-moved false})

            (and (= "POST" method) (= "/commit" path))
            (if-not (token-matches?
                     actor-token
                     (.getFirst (.getRequestHeaders exchange)
                                "X-Cloud-Itonami-Actor-Token"))
              (send-json! exchange 401
                          {:status "held"
                           :refusal {:rule "actor-authentication-failed"}})
              (let [body (json/read-str (slurp (.getRequestBody exchange))
                                        :key-fn keyword)]
                (send-json! exchange 200 (commit! (:proposal body)))))

            :else
            (send-json! exchange 404 {:error "not-found"})))
        (catch Exception e
          (send-json! exchange 400
                      {:status "held"
                       :refusal {:rule "request-invalid"
                                 :detail (.getMessage e)}}))
        (finally (.close exchange))))))

(defn -main [& [port-arg]]
  (let [port (Integer/parseInt
              (or port-arg
                  (System/getenv "CLOUD_ITONAMI_PAYMENT_ACTOR_PORT")
                  "1340"))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)
        actor-token (ensure-token!)]
    (.createContext server "/" (handler actor-token))
    (.setExecutor server nil)
    (.start server)
    (println (str "payment-settlement actor listening on http://127.0.0.1:"
                  port "/commit"))
    server))
