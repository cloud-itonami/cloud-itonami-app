(ns cloud.itonami.app.awai-apps
  "Host-neutral lifecycle policy for externally published Awai apps.

  DNS, Awai registry access, content retrieval, local secure storage, cell
  migration, and UI execution are injected host responsibilities. This module
  composes their verified facts with kotoba.protocol.install; it has no
  Cloudflare API or service dependency."
  (:require [clojure.set :as set]
            [kotoba.protocol.app :as app]
            [kotoba.protocol.cid :as cid]
            [kotoba.protocol.install :as install]
            [kotoba.protocol.vocab :as vocab]))

(def installation-schema "itonami.app.external-install.v1")

(defn- cidv1? [value]
  (boolean (and (vocab/cid? value)
                (not (:error (cid/parse-cid value))))))

(defn- value-set? [value]
  (or (set? value) (sequential? value)))

(defn- string-set [value]
  (when (and (value-set? value) (every? string? value))
    (set value)))

(defn installation-problems
  "Validate the host-owned installation record. This checks shape only; the
  host must authenticate its local store and keep this record out of app code."
  [record]
  (let [lock (:install-lock record)
        origin (:origin record)
        approved (:approved-caps record)
        requested (or (string-set (:requested-caps lock)) #{})]
    (cond-> []
      (not (map? record))
      (conj {:field :installation :error :not-a-map})

      (not= installation-schema (:schema record))
      (conj {:field :schema :error :unsupported-schema})

      (seq (install/install-lock-problems lock))
      (conj {:field :install-lock :error :invalid-install-lock})

      (not (and (map? origin)
                (install/canonical-domain? (:domain origin))))
      (conj {:field :origin :error :invalid-domain-origin})

      (not (cidv1? (:binding-cid origin)))
      (conj {:field :origin :error :invalid-binding-cid})

      (not (and (integer? (:binding-sequence origin))
                (pos? (:binding-sequence origin))
                (integer? (:binding-expires-at origin))
                (pos? (:binding-expires-at origin))))
      (conj {:field :origin :error :invalid-binding-clock})

      (not (and (vector? approved)
                (= (count approved) (count (set approved)))
                (empty? (app/unknown-caps approved))
                (every? requested approved)))
      (conj {:field :approved-caps :error :invalid-local-approvals})

      (not (nat-int? (:installed-at record)))
      (conj {:field :installed-at :error :invalid-clock}))))

(defn prepare-install
  "Turn a verified domain resolution into a fetch-and-consent plan.

  Every requested capability is presented for explicit local consent on a new
  install. The returned CIDs must be byte-verified by the content adapter."
  [resolved]
  (let [lock (:install-lock resolved)]
    (cond
      (not (and (map? resolved) (= :resolved (:decision resolved))))
      {:decision :refuse :error :unresolved-entry}

      (seq (install/install-lock-problems lock))
      {:decision :refuse :error :invalid-install-lock}

      (not (and (install/canonical-domain? (:domain resolved))
                (cidv1? (:binding-cid resolved))
                (integer? (:binding-sequence resolved))
                (pos? (:binding-sequence resolved))
                (integer? (:binding-expires-at resolved))
                (pos? (:binding-expires-at resolved))))
      {:decision :refuse :error :invalid-domain-origin}

      :else
      {:decision :install-prepared
       :install-lock lock
       :origin (select-keys resolved
                            [:domain :binding-cid :binding-sequence
                             :binding-expires-at])
       :required-content-cids (install/required-content-cids lock)
       :consent-required-caps (:requested-caps lock)})))

(defn resolve-domain
  "Resolve one hostname from already verified adapter facts and attach its
  install plan. The DNS adapter supplies a fresh DNSSEC-verified TXT proof; the
  Awai adapter supplies the verified binding and selected release."
  [domain {:keys [binding txt-proof release now]}]
  (let [resolved (install/resolve-entry domain binding txt-proof release now)]
    (if (= :resolved (:decision resolved))
      (assoc resolved :install-plan (prepare-install resolved))
      resolved)))

(defn finish-install
  "Produce a host installation record after content verification and the
  user's capability decision. The caller must write it atomically to protected
  local storage before invoking the app runtime."
  [prepared {:keys [verified-cids approved-caps host-supported now]}]
  (let [lock (:install-lock prepared)
        verified (string-set verified-cids)
        approved (string-set approved-caps)
        supported (string-set host-supported)
        required (install/required-content-cids lock)
        missing (when verified (into [] (remove verified) required))
        requested (or (string-set (:requested-caps lock)) #{})]
    (cond
      (not= :install-prepared (:decision prepared))
      {:decision :refuse :error :install-not-prepared}

      (seq (install/install-lock-problems lock))
      {:decision :refuse :error :invalid-install-lock}

      (or (nil? verified) (nil? approved) (nil? supported))
      {:decision :refuse :error :invalid-runtime-input}

      (not (nat-int? now))
      {:decision :refuse :error :invalid-clock}

      (seq missing)
      {:decision :refuse :error :content-not-verified :missing-cids missing}

      (not (every? requested approved))
      {:decision :refuse :error :capability-not-requested}

      :else
      (let [record {:schema installation-schema
                    :install-lock lock
                    :origin (:origin prepared)
                    :approved-caps (vec (sort approved))
                    :installed-at now}]
        (if (seq (installation-problems record))
          {:decision :refuse :error :invalid-installation-record}
          {:decision :installed
           :installation record
           :effective-capabilities
           (install/effective-capabilities lock approved supported)})))))

(defn prepare-update
  "Plan an update for an installed app. The content adapter fetches and verifies
  every CID in `:required-content-cids`; the host presents new requested
  capabilities and runs any cell migration before calling `finish-update`."
  [record candidate-release]
  (let [record-problems (installation-problems record)
        policy (install/plan-update (:install-lock record) candidate-release)]
    (cond
      (seq record-problems)
      {:decision :refuse :error :invalid-installation
       :problems record-problems}

      (= :refuse (:decision policy))
      policy

      :else
      {:decision (:decision policy)
       :source-release-cid (get-in record [:install-lock :release-cid])
       :policy-plan policy
       :required-content-cids (install/required-content-cids (:lock policy))
       :consent-required-caps (:consent-required-caps policy)
       :changed-cells (:changed-cells policy)})))

(defn- valid-migration-receipt? [record prepared receipt]
  (let [policy (:policy-plan prepared)
        expected-cells (:changed-cells prepared)
        receipt-cells (:changed-cells receipt)]
    (and (map? receipt)
         (true? (:verified? receipt))
         (= (get-in record [:install-lock :app-id]) (:app-id receipt))
         (= (:source-release-cid prepared) (:from-release-cid receipt))
         (= (get-in policy [:lock :release-cid]) (:to-release-cid receipt))
         (vector? expected-cells)
         (vector? receipt-cells)
         (= expected-cells receipt-cells))))

(defn finish-update
  "Produce the next host installation record after all update gates pass.

  A cell migration receipt must be issued by the runtime adapter after the
  migration has completed and been verified. `:rollback` is the previous
  record, which the host retains until the new release starts successfully."
  [record prepared {:keys [verified-cids approved-new-caps host-supported
                           migration-receipt now]}]
  (let [record-problems (installation-problems record)
        policy (:policy-plan prepared)
        candidate (:lock policy)
        verified (string-set verified-cids)
        approved-new (string-set approved-new-caps)
        supported (string-set host-supported)
        required (install/required-content-cids candidate)
        missing (when verified (into [] (remove verified) required))
        consent-required (or (string-set (:consent-required-caps prepared)) #{})
        previous-approved (or (string-set (:approved-caps record)) #{})
        next-requested (or (string-set (:requested-caps candidate)) #{})]
    (cond
      (seq record-problems)
      {:decision :refuse :error :invalid-installation
       :problems record-problems}

      (not= (:source-release-cid prepared)
            (get-in record [:install-lock :release-cid]))
      {:decision :refuse :error :installation-changed}

      (not (#{:ready :requires-cell-migration} (:decision policy)))
      {:decision :refuse :error :invalid-update-plan}

      (seq (install/install-lock-problems candidate))
      {:decision :refuse :error :invalid-candidate-lock}

      (or (nil? verified) (nil? approved-new) (nil? supported))
      {:decision :refuse :error :invalid-runtime-input}

      (not (nat-int? now))
      {:decision :refuse :error :invalid-clock}

      (seq missing)
      {:decision :refuse :error :content-not-verified :missing-cids missing}

      (not (every? consent-required approved-new))
      {:decision :refuse :error :capability-not-requested}

      (and (= :requires-cell-migration (:decision policy))
           (not (valid-migration-receipt? record prepared migration-receipt)))
      {:decision :refuse :error :migration-receipt-required}

      :else
      (let [approved (into (set/intersection previous-approved next-requested)
                           approved-new)
            next-record (cond-> (assoc record
                                       :install-lock candidate
                                       :approved-caps (vec (sort approved))
                                       :updated-at now)
                          (and migration-receipt
                               (= :requires-cell-migration (:decision policy)))
                          (assoc :last-migration-receipt migration-receipt))]
        {:decision :updated
         :installation next-record
         :effective-capabilities
         (install/effective-capabilities candidate approved supported)
         :rollback record}))))

(defn launch-decision
  "Decide whether a protected local installation can launch from verified
  cached bytes. Domain proof state is display metadata and does not become app
  authority; an offline installation can launch without network access."
  [record {:keys [verified-cids host-supported domain-status]}]
  (let [problems (installation-problems record)
        verified (string-set verified-cids)
        supported (string-set host-supported)]
    (cond
      (seq problems)
      {:decision :refuse :error :invalid-installation :problems problems}

      (or (nil? verified) (nil? supported))
      {:decision :refuse :error :invalid-runtime-input}

      :else
      (assoc (install/launch-decision
              (:install-lock record)
              {:verified-cids verified
               :locally-approved (:approved-caps record)
               :host-supported supported
               :domain-status domain-status})
             :origin (:origin record)))))
