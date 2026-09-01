(ns cloud.itonami.app.human-work
  "Durable human-work requests and verified worker eligibility.

  A Bot or Person may request work, but only a Human User may accept it. Worker
  claims are self-attested until an organization verifier binds a decision to
  the exact claim version. Changing a licence, qualification, insurance claim,
  or service location invalidates the old verification automatically.

  This namespace is the provider-neutral control plane. Evidence references
  are retained; online authorities and payment rails live in explicit adapters
  and never promote a claim merely because the worker supplied a file."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            #?(:clj [cloud.itonami.app.store :as store])))

(def schema "cloud.itonami.app.human-work.v1")
(def credential-types
  #{"license" "qualification" "permit" "insurance" "training" "asset"})
(def work-modes #{"onsite" "remote" "hybrid"})
(def visibility-levels #{"organization" "public"})
(def identity-levels {"basic" 1 "substantial" 2 "high" 3})
(def terminal-statuses #{"verified" "rejected" "cancelled"})
(def active-statuses #{"accepted" "in-progress" "submitted"})

(def ^:dynamic *snapshot* #?(:clj store/snapshot :cljs nil))
(def ^:dynamic *transact!* #?(:clj store/transact! :cljs nil))
(def ^:dynamic *new-id* #?(:clj store/new-id :cljs nil))
(def ^:dynamic *now* #?(:clj store/now :cljs nil))

(defn- host-call [f label & args]
  (when-not f
    (throw (ex-info (str "No human-work host adapter for " label)
                    {:type :human-work/host-unavailable :effect label})))
  (apply f args))

(defn- snapshot [] (host-call *snapshot* :snapshot))
(defn- transact! [f & args] (apply host-call *transact!* :transact f args))
(defn- new-id [prefix] (host-call *new-id* :new-id prefix))
(defn- now [] (host-call *now* :now))

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn- present? [value]
  (and (string? value) (not (str/blank? value))))

(defn- text! [value field]
  (let [value (some-> value str str/trim)]
    (when (str/blank? value)
      (fail! :human-work/invalid (str field " is required") {:field field}))
    value))

(defn- evm-address! [value field]
  (let [value (some-> value str str/trim)]
    (when-not (or (nil? value) (re-matches #"0x[0-9a-fA-F]{40}" value))
      (fail! :human-work/invalid-compensation
             (str field " must be an EVM address") {:field field}))
    value))

(defn- atomic-amount! [value field]
  (let [value (cond
                (integer? value) (str value)
                (string? value) (str/trim value)
                :else nil)]
    (when-not (and value (re-matches #"[1-9][0-9]*" value))
      (fail! :human-work/invalid-compensation
             (str field " must be a positive integer string in atomic units")
             {:field field}))
    value))

(defn- epoch-millis [value]
  (try
    (when (present? value)
      #?(:clj (.toEpochMilli (java.time.Instant/parse value))
         :cljs (.parse js/Date value)))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- valid-window? [{:keys [start end]}]
  (let [start' (epoch-millis start) end' (epoch-millis end)]
    (and start' end' (< start' end'))))

(defn- contains-window? [outer inner]
  (let [outer-start (epoch-millis (:start outer))
        outer-end (epoch-millis (:end outer))
        inner-start (epoch-millis (:start inner))
        inner-end (epoch-millis (:end inner))]
    (and outer-start outer-end inner-start inner-end
         (<= outer-start inner-start) (>= outer-end inner-end))))

(defn- overlaps? [a b]
  (let [a-start (epoch-millis (:start a)) a-end (epoch-millis (:end a))
        b-start (epoch-millis (:start b)) b-end (epoch-millis (:end b))]
    (and a-start a-end b-start b-end
         (< a-start b-end) (< b-start a-end))))

(defn- normalized-country [value]
  (let [country (some-> value str str/trim str/upper-case)]
    (when-not (and country (re-matches #"[A-Z]{2}" country))
      (fail! :human-work/invalid-location
             "country must be an ISO 3166-1 alpha-2 code"))
    country))

(defn- normalize-jurisdiction [value]
  (when-not (map? value)
    (fail! :human-work/invalid-credential "credential jurisdiction is required"))
  (cond-> {:country (normalized-country (:country value))}
    (present? (:region value)) (assoc :region (str/trim (:region value)))))

(defn- claim-body [claim]
  (dissoc claim :claim-version :verifications :created-at :updated-at))

(defn- preserve-or-revise [normalized previous]
  (let [same? (= (claim-body normalized) (some-> previous claim-body))]
    (assoc normalized
           :claim-version (if same? (:claim-version previous) (new-id "claim"))
           :verifications (if same? (vec (:verifications previous)) []))))

(defn- normalize-location [value previous]
  (when-not (map? value)
    (fail! :human-work/invalid-location "location claims must be objects"))
  (let [id (text! (:location-id value) "location-id")
        modes (set (map str (:work-modes value)))
        areas (vec (distinct (map #(text! % "service-area")
                                  (:service-areas value))))
        normalized
        {:location-id id
         :country (normalized-country (:country value))
         :region (some-> (:region value) str str/trim not-empty)
         :locality (some-> (:locality value) str str/trim not-empty)
         :service-areas areas
         :work-modes (vec (sort modes))
         :evidence-ref (text! (:evidence-ref value) "location evidence-ref")}]
    (when (or (empty? modes) (not (set/subset? modes work-modes)))
      (fail! :human-work/invalid-location "location work-modes are invalid"))
    (when (and (seq (set/intersection modes #{"onsite" "hybrid"}))
               (empty? areas))
      (fail! :human-work/invalid-location
             "onsite and hybrid locations require service-areas"))
    (preserve-or-revise normalized previous)))

(defn- normalize-credential [value previous]
  (when-not (map? value)
    (fail! :human-work/invalid-credential "credentials must be objects"))
  (let [type (some-> (:type value) str str/lower-case)
        expires-at (some-> (:expires-at value) str str/trim not-empty)
        issued-at (some-> (:issued-at value) str str/trim not-empty)
        scopes (vec (distinct (map #(text! % "credential scope")
                                   (:scopes value))))
        normalized
        {:credential-id (text! (:credential-id value) "credential-id")
         :type type
         :name (text! (:name value) "credential name")
         :code (some-> (:code value) str str/trim not-empty)
         :issuer (text! (:issuer value) "credential issuer")
         :jurisdiction (normalize-jurisdiction (:jurisdiction value))
         :scopes scopes
         :issued-at issued-at
         :expires-at expires-at
         :evidence-ref (text! (:evidence-ref value) "credential evidence-ref")}]
    (when-not (contains? credential-types type)
      (fail! :human-work/invalid-credential "unknown credential type" {:type type}))
    (when (empty? scopes)
      (fail! :human-work/invalid-credential "credential scopes are required"))
    (when (and issued-at (nil? (epoch-millis issued-at)))
      (fail! :human-work/invalid-credential "issued-at must be ISO-8601"))
    (when (and expires-at (nil? (epoch-millis expires-at)))
      (fail! :human-work/invalid-credential "expires-at must be ISO-8601"))
    (when (and issued-at expires-at
               (>= (epoch-millis issued-at) (epoch-millis expires-at)))
      (fail! :human-work/invalid-credential "credential validity is reversed"))
    (preserve-or-revise normalized previous)))

(defn register-worker!
  "Register the Human User's own work locations and credential claims.

  Caller-supplied verification fields are ignored. An unchanged claim retains
  its exact-version verifications; changing any claim field creates a new
  version with no verification."
  [{:keys [display-name locations credentials availability active?
           payout-address]} actor]
  (when-not (present? actor)
    (fail! :identity/unauthenticated "Sign in required"))
  (when-not (and (vector? locations) (seq locations))
    (fail! :human-work/invalid-location "at least one work location is required"))
  (when-not (and (vector? availability) (seq availability)
                 (every? valid-window? availability))
    (fail! :human-work/invalid "availability must contain valid ISO-8601 windows"))
  (when-not (vector? credentials)
    (fail! :human-work/invalid-credential "credentials must be a vector"))
  (let [existing (get-in (snapshot) [:human-work :workers actor])
        old-locations (into {} (map (juxt :location-id identity))
                            (:locations existing))
        old-credentials (into {} (map (juxt :credential-id identity))
                              (:credentials existing))
        locations (mapv #(normalize-location % (get old-locations (:location-id %)))
                        locations)
        credentials
        (mapv #(normalize-credential % (get old-credentials (:credential-id %)))
              credentials)
        duplicate? (fn [ids] (not= (count ids) (count (set ids))))]
    (when (duplicate? (map :location-id locations))
      (fail! :human-work/invalid-location "location-id must be unique"))
    (when (duplicate? (map :credential-id credentials))
      (fail! :human-work/invalid-credential "credential-id must be unique"))
    (let [profile {:schema schema
                   :worker-id actor
                   :performer-kind "person"
                   :display-name (some-> display-name str str/trim not-empty)
                   :locations locations
                   :credentials credentials
                   :identity-assurances (vec (:identity-assurances existing))
                   :payout-address (or (evm-address! payout-address "payout-address")
                                       (:payout-address existing))
                   :availability availability
                   :active? (not= false active?)
                   :created-at (or (:created-at existing) (now))
                   :updated-at (now)}]
      (transact! assoc-in [:human-work :workers actor] profile)
      profile)))

(defn record-identity-assurance!
  "Record a configured external identity provider's data-minimized result.

  The provider adapter, not a browser caller, supplies this function. Raw
  identity documents, names, birth dates, and document numbers are never
  accepted or retained here."
  [worker-id {:keys [provider-id provider-reference level status checked-at
                     valid-until evidence-ref]}]
  (when-not (and (present? provider-id) (present? provider-reference)
                 (contains? identity-levels level)
                 (#{"verified" "rejected" "revoked"} status)
                 (present? checked-at) (epoch-millis checked-at)
                 (present? evidence-ref))
    (fail! :human-work/invalid-identity-assurance
           "External identity assurance result is incomplete"))
  (when (and (= "verified" status)
             (or (nil? (epoch-millis valid-until))
                 (<= (epoch-millis valid-until) (epoch-millis checked-at))))
    (fail! :human-work/invalid-identity-assurance
           "Verified identity assurance needs a later valid-until"))
  (let [answer (volatile! nil)]
    (transact!
     (fn [state]
       (let [profile (get-in state [:human-work :workers worker-id])]
         (when-not profile
           (fail! :human-work/worker-not-found "Worker is not registered"))
         (let [record {:provider-id provider-id
                       :provider-reference provider-reference
                       :level level :status status
                       :checked-at checked-at
                       :valid-until (when (= "verified" status) valid-until)
                       :evidence-ref evidence-ref}
               records (conj (vec (remove #(= provider-id (:provider-id %))
                                           (:identity-assurances profile)))
                             record)]
           (vreset! answer record)
           (-> state
               (assoc-in [:human-work :workers worker-id :identity-assurances]
                         records)
               (assoc-in [:human-work :workers worker-id :updated-at] (now)))))))
    @answer))

(defn worker-profile [worker-id]
  (get-in (snapshot) [:human-work :workers worker-id]))

(defn- claim-at [profile claim-kind claim-id]
  (let [path (case claim-kind
               :credential :credentials
               :location :locations
               (fail! :human-work/invalid-verification "unknown claim kind"))
        id-key (case claim-kind :credential :credential-id :location :location-id)]
    (some #(when (= claim-id (get % id-key)) %) (get profile path))))

(defn verify-claim!
  "Bind an organization-scoped verifier decision to one exact claim version."
  [worker-id claim-kind claim-id
   {:keys [decision evidence-ref valid-until note]} verifier organization-id]
  (when-not (and (present? verifier) (present? organization-id))
    (fail! :identity/unauthenticated "Verifier and organization are required"))
  (when (= verifier worker-id)
    (fail! :human-work/self-verification "A worker cannot verify their own claim"))
  (let [decision (some-> decision name str/lower-case)
        valid-until (some-> valid-until str str/trim not-empty)]
    (when-not (#{"verified" "rejected" "revoked"} decision)
      (fail! :human-work/invalid-verification
             "decision must be verified, rejected, or revoked"))
    (when-not (present? evidence-ref)
      (fail! :human-work/invalid-verification
             "verification evidence-ref is required"))
    (when (and (= "verified" decision)
               (or (nil? (epoch-millis valid-until))
                   (<= (epoch-millis valid-until)
                       (or (epoch-millis (now)) 0))))
      (fail! :human-work/invalid-verification
             "verified claims require a future valid-until"))
    (let [answer (volatile! nil)]
      (transact!
       (fn [state]
         (let [profile (get-in state [:human-work :workers worker-id])
               claim (claim-at profile claim-kind claim-id)]
           (when-not profile
             (fail! :human-work/worker-not-found "Worker is not registered"))
           (when-not claim
             (fail! :human-work/claim-not-found "Worker claim is not registered"))
           (when (and (= claim-kind :credential) (= "verified" decision)
                      (:expires-at claim)
                      (> (epoch-millis valid-until)
                         (epoch-millis (:expires-at claim))))
             (fail! :human-work/invalid-verification
                    "verification cannot outlive the credential"))
           (let [record {:organization-id organization-id
                         :claim-version (:claim-version claim)
                         :status decision
                         :checked-by verifier
                         :checked-at (now)
                         :valid-until (when (= "verified" decision) valid-until)
                         :evidence-ref evidence-ref
                         :note (some-> note str str/trim not-empty)}
                 path-key (case claim-kind :credential :credentials :location :locations)
                 id-key (case claim-kind :credential :credential-id :location :location-id)
                 updated-claims
                 (mapv (fn [candidate]
                         (if (= claim-id (get candidate id-key))
                           (update candidate :verifications
                                   (fn [records]
                                     (conj (vec (remove #(= organization-id
                                                           (:organization-id %))
                                                        records))
                                           record)))
                           candidate))
                       (get profile path-key))]
             (vreset! answer record)
             (-> state
                 (assoc-in [:human-work :workers worker-id path-key]
                           updated-claims)
                 (assoc-in [:human-work :workers worker-id :updated-at] (now)))))))
      @answer)))

(defn verify-credential! [worker-id credential-id input verifier organization-id]
  (verify-claim! worker-id :credential credential-id input verifier organization-id))

(defn verify-location! [worker-id location-id input verifier organization-id]
  (verify-claim! worker-id :location location-id input verifier organization-id))

(defn- applicable-verification [claim organization-id]
  (some #(when (and (= organization-id (:organization-id %))
                    (= (:claim-version claim) (:claim-version %))) %)
        (:verifications claim)))

(defn- verified-through? [claim organization-id through-ms]
  (let [verification (applicable-verification claim organization-id)
        valid-until (epoch-millis (:valid-until verification))
        claim-expiry (epoch-millis (:expires-at claim))]
    (and (= "verified" (:status verification))
         valid-until (>= valid-until through-ms)
         (or (nil? claim-expiry) (>= claim-expiry through-ms)))))

(defn- jurisdiction-match? [claim requirement]
  (let [required (:jurisdiction requirement)
        actual (:jurisdiction claim)]
    (and (= (get required :country) (get actual :country))
         (or (not (present? (:region required)))
             (= (:region required) (:region actual))))))

(defn- credential-match?
  [claim requirement organization-id from-ms through-ms]
  (let [issued-at (epoch-millis (:issued-at claim))]
    (and (= (:type requirement) (:type claim))
         (set/subset? (set (:scopes requirement)) (set (:scopes claim)))
         (jurisdiction-match? claim requirement)
         (or (nil? issued-at) (<= issued-at from-ms))
         (if (= "self-attested" (:minimum-verification requirement))
           (let [expiry (epoch-millis (:expires-at claim))]
             (or (nil? expiry) (>= expiry through-ms)))
           (verified-through? claim organization-id through-ms)))))

(defn- location-match? [claim location work-mode organization-id through-ms]
  (and (contains? (set (:work-modes claim)) work-mode)
       (= (:country location) (:country claim))
       (or (not (present? (:region location)))
           (= (:region location) (:region claim)))
       (or (= "remote" work-mode)
           (contains? (set (:service-areas claim)) (:service-area location)))
       (if (= "self-attested" (:minimum-verification location))
         true
         (verified-through? claim organization-id through-ms))))

(defn- identity-match? [assurance requirement through-ms]
  (and (= "verified" (:status assurance))
       (>= (get identity-levels (:level assurance) 0)
           (get identity-levels (:minimum-level requirement) 0))
       (or (nil? (:providers requirement))
           (contains? (set (:providers requirement))
                      (:provider-id assurance)))
       (some-> (:valid-until assurance) epoch-millis (>= through-ms))))

(defn- conflicting-assignment? [state worker-id request]
  (some (fn [other]
          (and (= worker-id (:worker-id other))
               (contains? active-statuses (:status other))
               (not= (:id request) (:id other))
               (overlaps? (:work-window request) (:work-window other))))
        (vals (get-in state [:human-work :requests] {}))))

(defn eligibility-report
  "Explain whether one registered Human User is eligible for one request."
  [state profile request]
  (let [from-ms (or (epoch-millis (get-in request [:work-window :start])) 0)
        through-ms (or (epoch-millis (get-in request [:work-window :end]))
                       9007199254740991)
        organization-id (:organization-id request)
        work-mode (:work-mode request)
        location (:location request)
        location-claim
        (some #(when (location-match? % location work-mode organization-id through-ms) %)
              (:locations profile))
        identity-requirement (get-in request [:requirements :identity])
        identity-assurance
        (when identity-requirement
          (some #(when (identity-match? % identity-requirement through-ms) %)
                (:identity-assurances profile)))
        requirement-results
        (mapv (fn [requirement]
                (let [match (some #(when (credential-match?
                                          % requirement organization-id
                                          from-ms through-ms) %)
                                  (:credentials profile))]
                  {:requirement requirement
                   :satisfied? (boolean match)
                   :credential-id (:credential-id match)
                   :verification (some-> match
                                         (applicable-verification organization-id)
                                         (select-keys [:status :checked-by :checked-at
                                                       :valid-until :evidence-ref]))}))
              (get-in request [:requirements :credentials] []))
        available? (some #(contains-window? % (:work-window request))
                         (:availability profile))
        conflict? (conflicting-assignment? state (:worker-id profile) request)
        reasons (cond-> []
                  (not (:active? profile)) (conj :inactive)
                  (nil? location-claim) (conj :location-not-verified)
                  (and identity-requirement (nil? identity-assurance))
                  (conj :identity-not-verified)
                  (not available?) (conj :unavailable)
                  conflict? (conj :overlapping-assignment)
                  (some (complement :satisfied?) requirement-results)
                  (conj :credential-requirement-not-met))]
    {:eligible? (empty? reasons)
     :reasons reasons
     :location-id (:location-id location-claim)
     :location-verification
     (some-> location-claim (applicable-verification organization-id)
             (select-keys [:status :checked-by :checked-at :valid-until
                           :evidence-ref]))
     :identity-assurance (some-> identity-assurance
                                (select-keys [:provider-id :level :status
                                              :checked-at :valid-until
                                              :evidence-ref]))
     :credentials requirement-results}))

(defn- normalize-identity-requirement [value]
  (when value
    (let [level (or (some-> (:minimum-level value) str str/lower-case)
                    "substantial")
          providers (when (seq (:providers value))
                      (vec (distinct (map #(text! % "identity provider")
                                          (:providers value)))))]
      (when-not (contains? identity-levels level)
        (fail! :human-work/invalid-requirement
               "identity minimum-level is invalid"))
      {:minimum-level level :providers providers})))

(defn- normalize-requirement [value]
  (let [type (some-> (:type value) str str/lower-case)
        minimum (or (some-> (:minimum-verification value) str str/lower-case)
                    "verified")
        requirement {:type type
                     :scopes (vec (distinct (map #(text! % "required scope")
                                                 (:scopes value))))
                     :jurisdiction (normalize-jurisdiction (:jurisdiction value))
                     :minimum-verification minimum}]
    (when-not (contains? credential-types type)
      (fail! :human-work/invalid-requirement "unknown required credential type"))
    (when (empty? (:scopes requirement))
      (fail! :human-work/invalid-requirement "required credential scopes are empty"))
    (when-not (#{"verified" "self-attested"} minimum)
      (fail! :human-work/invalid-requirement "minimum verification is invalid"))
    requirement))

(defn- normalize-request-location [value work-mode]
  (when-not (map? value)
    (fail! :human-work/invalid-location "request location is required"))
  (let [location {:country (normalized-country (:country value))
                  :region (some-> (:region value) str str/trim not-empty)
                  :locality (some-> (:locality value) str str/trim not-empty)
                  :service-area (some-> (:service-area value) str str/trim not-empty)
                  :minimum-verification
                  (or (some-> (:minimum-verification value) str str/lower-case)
                      (if (= "remote" work-mode) "self-attested" "verified"))}]
    (when (and (not= "remote" work-mode) (nil? (:service-area location)))
      (fail! :human-work/invalid-location "onsite work requires a service-area"))
    (when-not (#{"verified" "self-attested"} (:minimum-verification location))
      (fail! :human-work/invalid-location "location minimum verification is invalid"))
    location))

(defn- normalize-source [value]
  (into {}
        (keep (fn [[key value]]
                (when-let [value (some-> value str str/trim not-empty)]
                  [key value])))
        (select-keys (or value {})
                     [:bot-id :goal-id :bot-run-id :goal-step-id
                      :work-item-id :conversation-id :vertical :vertical-id])))

(defn create-request!
  [{:keys [organization-id title summary category work-mode location work-window
           requirements private-details evidence-contract compensation source
           visibility]}
   requester-id]
  (when-not (and (present? requester-id) (present? organization-id))
    (fail! :identity/unauthenticated "Requester and organization are required"))
  (let [work-mode (some-> work-mode str str/lower-case)
        visibility (or (some-> visibility str str/lower-case) "organization")
        _ (when-not (contains? visibility-levels visibility)
            (fail! :human-work/invalid "visibility is invalid"))
        _ (when-not (contains? work-modes work-mode)
            (fail! :human-work/invalid "work-mode is invalid"))
        _ (when-not (valid-window? work-window)
            (fail! :human-work/invalid "work-window must be valid ISO-8601"))
        evidence-contract (vec (distinct (map #(text! % "evidence contract")
                                               evidence-contract)))
        _ (when (empty? evidence-contract)
            (fail! :human-work/invalid "evidence-contract is required"))
        amount-atomic (when compensation
                        (atomic-amount! (:amount-atomic compensation)
                                        "amount-atomic"))
        network (or (some-> (:network compensation) str str/trim)
                    "eip155:8453")
        fee-bps (or (:platform-fee-bps compensation) 0)
        _ (when (and compensation
                     (not (and (re-matches #"eip155:[1-9][0-9]*" network)
                               (integer? fee-bps) (<= 0 fee-bps 10000))))
            (fail! :human-work/invalid-compensation
                   "compensation needs an EVM CAIP-2 network and platform-fee-bps from 0 to 10000"))
        identity-requirement
        (normalize-identity-requirement
         (or (:identity requirements)
             (when (or compensation (= "public" visibility))
               {:minimum-level "substantial"})))
        request {:schema schema
                 :id (new-id "human-work")
                 :organization-id organization-id
                 :requester-id requester-id
                 :status "draft"
                 :visibility visibility
                 :title (text! title "title")
                 :summary (text! summary "summary")
                 :category (text! category "category")
                 :work-mode work-mode
                 :location (normalize-request-location location work-mode)
                 :work-window work-window
                 :requirements
                 {:credentials (mapv normalize-requirement
                                     (get requirements :credentials []))
                  :identity identity-requirement}
                 :private-details (or private-details {})
                 :evidence-contract evidence-contract
                 :compensation (when compensation
                                 {:amount-atomic amount-atomic
                                  :asset "USDC"
                                  :asset-decimals 6
                                  :network network
                                  :platform-fee-bps fee-bps
                                  :settlement-status "unfunded"})
                 :source (normalize-source source)
                 :worker-id nil
                 :created-at (now)
                 :audit [{:action "created" :actor requester-id :at (now)}]}]
    (transact! assoc-in [:human-work :requests (:id request)] request)
    request))

(defn request [id]
  (get-in (snapshot) [:human-work :requests id]))

(defn- request! [state id]
  (or (get-in state [:human-work :requests id])
      (fail! :human-work/not-found "Human work request was not found")))

(defn- requester! [request actor]
  (when-not (= actor (:requester-id request))
    (fail! :human-work/forbidden "Only the requester may do that")))

(defn- assigned-worker! [request actor]
  (when-not (= actor (:worker-id request))
    (fail! :human-work/forbidden "Only the accepted worker may do that")))

(defn- require-status! [request expected]
  (when-not (= expected (:status request))
    (fail! :human-work/invalid-transition
           (str "Expected status " expected ", got " (:status request)))))

(defn- mutate-request! [id f]
  (let [answer (volatile! nil)]
    (transact!
     (fn [state]
       (let [request (request! state id)
             next-request (f state request)]
         (vreset! answer next-request)
         (assoc-in state [:human-work :requests id] next-request))))
    @answer))

(defn- event [action actor evidence]
  (cond-> {:action action :actor actor :at (now)}
    (seq evidence) (assoc :evidence evidence)))

(defn publish! [id actor]
  (mutate-request! id
                   (fn [_ request]
                     (requester! request actor)
                     (require-status! request "draft")
                     (-> request
                         (assoc :status "open" :published-at (now))
                         (update :audit conj (event "published" actor nil))))))

(defn matches [id actor]
  (let [state (snapshot) request (request! state id)]
    (requester! request actor)
    (require-status! request "open")
    {:schema schema
     :request-id id
     :items
     (->> (vals (get-in state [:human-work :workers] {}))
          (map (fn [profile]
                 [profile (eligibility-report state profile request)]))
          (filter (comp :eligible? second))
          (map (fn [[profile report]]
                 {:worker-id (:worker-id profile)
                  :display-name (:display-name profile)
                  :eligibility report}))
          (sort-by :worker-id)
          vec)}))

(defn accept! [id actor]
  (mutate-request!
   id
   (fn [state request]
     (require-status! request "open")
     (let [profile (get-in state [:human-work :workers actor])
           report (when profile (eligibility-report state profile request))]
       (when-not (:eligible? report)
         (fail! :human-work/not-eligible
                "The Human User does not meet this request's current requirements"
                {:eligibility report}))
       (when (and (:compensation request) (nil? (:payout-address profile)))
         (fail! :human-work/payment-required
                "Compensated work requires the worker's USDC payout address"))
       (-> request
           (assoc :status "accepted" :worker-id actor
                  :accepted-payout-address (:payout-address profile)
                  :accepted-at (now)
                  :accepted-eligibility report)
           (update :audit conj (event "accepted" actor
                                      {:eligibility report})))))))

(defn start! [id {:keys [presence-evidence-ref]} actor]
  (mutate-request!
   id
   (fn [_ request]
     (assigned-worker! request actor)
     (require-status! request "accepted")
     (when (and (:compensation request)
                (not= "funded" (get-in request [:compensation :settlement-status])))
       (fail! :human-work/payment-required
              "Compensated work must be funded in x402 escrow before it starts"))
     (when (and (not= "remote" (:work-mode request))
                (not (present? presence-evidence-ref)))
       (fail! :human-work/evidence-required
              "onsite work requires presence-evidence-ref"))
     (-> request
         (assoc :status "in-progress" :started-at (now))
         (update :audit conj (event "started" actor
                                    (when presence-evidence-ref
                                      {:presence-evidence-ref
                                       presence-evidence-ref})))))))

(defn- evidence-present? [evidence required]
  (some (fn [[key value]]
          (and (= (name key) required) (present? value)))
        evidence))

(defn submit! [id {:keys [evidence summary]} actor]
  (when-not (map? evidence)
    (fail! :human-work/evidence-required "submission evidence must be an object"))
  (mutate-request!
   id
   (fn [_ request]
     (assigned-worker! request actor)
     (require-status! request "in-progress")
     (let [missing (vec (remove #(evidence-present? evidence %)
                                (:evidence-contract request)))]
       (when (seq missing)
         (fail! :human-work/evidence-required
                "submission does not satisfy the evidence contract"
                {:missing missing}))
       (-> request
           (assoc :status "submitted" :submitted-at (now)
                  :submission {:summary (text! summary "submission summary")
                               :evidence evidence})
           (update :audit conj (event "submitted" actor evidence)))))))

(defn review-submission!
  [id {:keys [decision verification-evidence-ref reason]} actor]
  (let [decision (some-> decision name str/lower-case)]
    (when-not (#{"verified" "rejected"} decision)
      (fail! :human-work/invalid-review "decision must be verified or rejected"))
    (when (and (= "verified" decision)
               (not (present? verification-evidence-ref)))
      (fail! :human-work/evidence-required
             "verified work requires verification-evidence-ref"))
    (when (and (= "rejected" decision) (not (present? reason)))
      (fail! :human-work/invalid-review "rejected work requires a reason"))
    (mutate-request!
     id
     (fn [_ request]
       (requester! request actor)
       (require-status! request "submitted")
       (-> request
           (assoc :status decision :reviewed-at (now)
                  :review {:decision decision
                           :evidence-ref verification-evidence-ref
                           :reason reason})
           (update :audit conj
                   (event (str "submission-" decision) actor
                          (cond-> {}
                            verification-evidence-ref
                            (assoc :verification-evidence-ref
                                   verification-evidence-ref)
                            reason (assoc :reason reason)))))))))

(defn cancel! [id actor]
  (mutate-request!
   id
   (fn [_ request]
     (requester! request actor)
     (when-not (#{"draft" "open" "accepted"} (:status request))
       (fail! :human-work/invalid-transition
              "Started or completed human work cannot be cancelled here"))
     (-> request
         (assoc :status "cancelled" :cancelled-at (now))
         (cond-> (= "funded" (get-in request [:compensation :settlement-status]))
           (assoc-in [:compensation :settlement-status] "refund-required"))
         (update :audit conj (event "cancelled" actor nil))))))

(defn public-requests
  "Public, redacted open requests. Exact addresses, requester ids, evidence
  references, source ids, accepted-worker state, and submissions never leave
  the authenticated surface."
  []
  {:schema schema
   :items
   (->> (vals (get-in (snapshot) [:human-work :requests] {}))
        (filter #(and (= "public" (:visibility %)) (= "open" (:status %))))
        (map #(-> (select-keys % [:id :organization-id :title :summary
                                  :category :work-mode :location :work-window
                                  :requirements :evidence-contract :compensation
                                  :published-at])
                  (update :compensation
                          (fn [value]
                            (some-> value
                                    (select-keys [:amount-atomic :asset
                                                  :asset-decimals :network
                                                  :platform-fee-bps
                                                  :settlement-status]))))))
        (sort-by (juxt :published-at :id))
        vec)})

(defn- public-request [request actor]
  (if (or (= actor (:requester-id request))
          (= actor (:worker-id request)))
    request
    (dissoc request :private-details :submission)))

(defn requests
  "Requests visible to one Human User or requester, with private details held
  until acceptance. Optional `organization-id` scopes requester listings."
  ([actor] (requests actor nil))
  ([actor organization-id]
   (let [state (snapshot) profile (get-in state [:human-work :workers actor])]
     {:schema schema
      :you actor
      :items
      (->> (vals (get-in state [:human-work :requests] {}))
           (filter (fn [request]
                     (and (or (nil? organization-id)
                              (= organization-id (:organization-id request)))
                          (or (= actor (:requester-id request))
                              (= actor (:worker-id request))
                              (and profile (= "open" (:status request))
                                   (:eligible? (eligibility-report
                                                state profile request)))))))
           (map #(public-request % actor))
           (sort-by (juxt :created-at :id))
           vec)})))
