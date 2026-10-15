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
            [kotoba.protocol.vocab :as vocab]
            [cloud.itonami.app.awai-content-policy :as content-policy]))

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

(defn- registry-record-sequence [record]
  (get-in record [:body :sequence]))

(defn- registry-previous-cid [record field]
  (let [value (get-in record [:body field])]
    (when (and (string? value) (not-empty value)) value)))

(defn- registry-linear-tip
  "Select a unique complete sequence-1-to-tip chain by immutable predecessor CID.
  Accepted records with missing links, multiple roots, forks, or disconnected
  history are conflicts; arrival order never selects the winner."
  [records previous-field]
  (let [cids (mapv :cid records)
        roots (filter #(and (= 1 (registry-record-sequence %))
                            (nil? (registry-previous-cid % previous-field)))
                      records)]
    (cond
      (empty? records) {:decision :missing}
      (not= (count cids) (count (set cids)))
      {:decision :conflict :reason :duplicate-entry-cid}
      (not= 1 (count roots))
      {:decision :conflict :reason :non-unique-chain-root}
      :else
      (loop [current (first roots)
             visited #{(:cid (first roots))}]
        (let [sequence (registry-record-sequence current)
              children (filter #(and (= (registry-previous-cid % previous-field)
                                        (:cid current))
                                     (= (registry-record-sequence %) (inc sequence)))
                               records)]
          (cond
            (> (count children) 1)
            {:decision :conflict :reason :forked-chain}

            (= 1 (count children))
            (let [child (first children)]
              (if (contains? visited (:cid child))
                {:decision :conflict :reason :cyclic-chain}
                (recur child (conj visited (:cid child)))))

            (not= (count visited) (count records))
            {:decision :conflict :reason :disconnected-chain}

            :else
            {:decision :tip :record current}))))))

(defn- valid-registry-record? [record type]
  (let [body (:body record)
        sequence (:sequence body)
        previous-field (case type
                         "app/release" :previous-release-cid
                         "domain/bind" :previous-binding-cid
                         nil)
        previous (get body previous-field)
        unexpected-previous-field (case type
                                    "app/release" :previous-binding-cid
                                    "domain/bind" :previous-release-cid
                                    nil)]
    (and (map? record)
         (= type (:type record))
         (cidv1? (:cid record))
         ;; Awai source-chain sequence is zero-based; the body-level app release
         ;; or domain-binding sequence is a separate counter and starts at 1.
         (nat-int? (:sequence record))
         (map? body)
         (integer? sequence) (pos? sequence)
         (not (contains? body unexpected-previous-field))
         ;; The app.registry host projects nullable genesis predecessor CIDs as
         ;; the empty string. Treat it like absence here; the chain resolver
         ;; still requires every non-root entry to connect to the unique root.
         (or (nil? previous)
             (= "" previous)
             (and (string? previous) (cidv1? previous))))))

(defn- valid-binding-record? [record]
  (let [body (:body record)]
    (and (valid-registry-record? record "domain/bind")
         (install/canonical-domain? (:domain body))
         (vocab/reverse-dns-id? (:app-id body))
         (vocab/did-key? (:publisher-did body))
         (vocab/did-key? (:domain-owner-did body))
         (string? (:channel body)) (not-empty (:channel body))
         (nat-int? (:expires-at body)))))

(defn- valid-release-record? [record]
  (let [body (:body record)]
    (and (valid-registry-record? record "app/release")
         (vocab/reverse-dns-id? (:app-id body))
         (vocab/did-key? (:publisher-did body))
         (string? (:channel body)) (not-empty (:channel body))
         (cidv1? (:manifest-cid body)))))

(defn- valid-content-origin-record? [record]
  (let [body (:body record)]
    (and (map? record)
         (= "content/origin" (:type record))
         (cidv1? (:cid record))
         (nat-int? (:sequence record))
         (map? body)
         (vocab/reverse-dns-id? (:app-id body))
         (vocab/did-key? (:publisher-did body))
         (vocab/did-key? (:operator-did body))
         (install/canonical-domain? (:host body))
         (string? (:spki-sha256 body))
         (re-matches #"[0-9a-f]{64}" (:spki-sha256 body))
         (nat-int? (:expires-at body)))))

(defn- release-content-origins [release records now]
  (let [body (:body release)
        cids (:content-origin-cids body)
        by-cid (into {} (map (juxt :cid identity) records))]
    (cond
      (nil? cids)
      {:decision :missing :reason :missing-content-origins}

      (not (and (vector? cids)
                (<= content-policy/minimum-origin-count (count cids) 8)
                (every? cidv1? cids)))
      {:decision :invalid :reason :invalid-content-origin-set}

      (not= (count cids) (count (set cids)))
      {:decision :invalid :reason :duplicate-content-origin-cid}

      (not-every? by-cid cids)
      {:decision :missing :reason :content-origin-dependencies-missing}

      :else
      (let [origin-records (mapv (fn [cid]
                                   (let [record (get by-cid cid)]
                                     (when (= "content/origin" (:type record))
                                       (assoc (:body record) :cid cid))))
                                 cids)]
        (if (some nil? origin-records)
          {:decision :invalid :reason :content-origin-dependency-type-mismatch}
          (let [result (content-policy/content-origins
                        origin-records (:app-id body) (:publisher-did body) now)]
            (if (= :origins-ready (:decision result))
              {:decision :ready
               :records origin-records
               :origins (:origins result)}
              {:decision (if (= :expired-content-origin (:reason result))
                           :expired :invalid)
               :reason (:reason result)})))))))

(defn- domain-candidate [domain records now]
  (let [bindings (filter #(and (= "domain/bind" (:type %))
                               (= domain (get-in % [:body :domain])))
                         records)
        binding-result (registry-linear-tip bindings :previous-binding-cid)]
    (if-not (= :tip (:decision binding-result))
      (cond-> {:domain domain
               :decision (if (= :missing (:decision binding-result))
                           :binding-missing :conflict)}
        (:reason binding-result) (assoc :reason (:reason binding-result)))
      (let [binding-record (:record binding-result)
            binding (:body binding-record)
            releases (filter #(let [body (:body %)]
                                (and (= "app/release" (:type %))
                                     (= (:app-id binding) (:app-id body))
                                     (= (:publisher-did binding) (:publisher-did body))
                                     (= (:channel binding) (:channel body))))
                              records)
            release-result (registry-linear-tip releases :previous-release-cid)
            release-record (:record release-result)
            content-origin-result (when (= :tip (:decision release-result))
                                    (release-content-origins release-record records now))
            base {:domain domain
                  :binding-cid (:cid binding-record)
                  :binding-sequence (:sequence binding)
                  :binding-expires-at (:expires-at binding)
                  :app-id (:app-id binding)
                  :publisher-did (:publisher-did binding)
                  :channel (:channel binding)
                  :dns-proof-status :not-checked
                  :manifest-status :not-fetched
                  :revocation-status :unsupported
                  :content-origin-status (if content-origin-result
                                           (name (:decision content-origin-result))
                                           "not-checked")}]
        (cond
          (= :conflict (:decision release-result))
          (assoc base :decision :conflict :reason (:reason release-result))

          (= :missing (:decision release-result))
          (assoc base :decision :release-missing)

          (<= (:expires-at binding) now)
          (assoc base :decision :expired
                 :release-cid (get-in release-result [:record :cid])
                 :release-sequence (get-in release-result [:record :body :sequence])
                 :manifest-cid (get-in release-result [:record :body :manifest-cid]))

          (not= :ready (:decision content-origin-result))
          (assoc base
                 :decision (case (:decision content-origin-result)
                             :missing :content-origins-missing
                             :expired :content-origins-expired
                             :invalid :content-origins-invalid
                             :content-origins-invalid)
                 :reason (:reason content-origin-result)
                 :release-cid (:cid release-record)
                 :release-sequence (get-in release-record [:body :sequence])
                 :manifest-cid (get-in release-record [:body :manifest-cid]))

          :else
          (assoc base :decision :candidate
                 :release-cid (get-in release-result [:record :cid])
                 :release-sequence (get-in release-result [:record :body :sequence])
                 :manifest-cid (get-in release-result [:record :body :manifest-cid])
                 :content-origin-cids (get-in release-record [:body :content-origin-cids])
                 :content-origin-records (:records content-origin-result)
                 :content-origins (:origins content-origin-result)))))))

(defn registry-domain-candidates
  "Derive deterministic domain→release-tip candidates from a read snapshot
  accepted by the local Awai node. A unique linear chain is only a discovery
  fact: this does not verify DNSSEC, L5 manifest bytes, revocation, or bundles."
  [registry-result now]
  (let [snapshot (:snapshot registry-result)
        records (:records snapshot)]
    (cond
      (not= "accepted-by-local-awai-node" (:trust registry-result))
      {:decision :refuse :error :untrusted-registry-snapshot}

      (not (and (map? snapshot)
                (= "awai.app-registry.read.v1" (:schema snapshot))
                (cidv1? (:dna-cid snapshot))
                (vector? records) (<= (count records) 1024)))
      {:decision :refuse :error :invalid-registry-snapshot}

      (not (nat-int? now))
      {:decision :refuse :error :invalid-clock}

      (not-every? (fn [record]
                    (or (valid-binding-record? record)
                        (valid-release-record? record)
                        (valid-content-origin-record? record)))
                  records)
      {:decision :refuse :error :invalid-registry-record}

      (not= (count records) (count (set (map :cid records))))
      {:decision :refuse :error :duplicate-registry-cid}

      :else
      (let [domains (->> records
                         (filter #(= "domain/bind" (:type %)))
                         (map #(get-in % [:body :domain]))
                         distinct
                         sort)]
        {:decision :candidates
         :candidates (mapv #(domain-candidate % records now) domains)}))))

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

(defn authorize-operation
  "Authorize one host-mediated app operation against the current local grant.

  Call this at the effect boundary for every operation, using the protected
  installation record and the runtime's current verified-content and
  host-capability facts. The returned capability set is the intersection of the
  signed release request, local approval, and host support; a manifest or
  domain binding never grants authority by itself."
  [record {:keys [capability verified-cids host-supported domain-status]}]
  (let [launch (launch-decision record {:verified-cids verified-cids
                                        :host-supported host-supported
                                        :domain-status domain-status})
        effective (set (:capabilities launch))]
    (cond
      (not= :launch (:decision launch))
      {:decision :refuse :error :app-not-launchable :launch-decision launch}

      (not (and (string? capability) (not-empty capability)))
      {:decision :refuse :error :invalid-capability}

      (not (contains? effective capability))
      {:decision :refuse :error :capability-not-granted}

      :else
      {:decision :allow
       :app-id (:app-id launch)
       :release-cid (:release-cid launch)
       :capability capability})))

(defn- operation-completed [authorization capability result]
  {:decision :completed
   :app-id (:app-id authorization)
   :release-cid (:release-cid authorization)
   :capability capability
   :result result})

(defn- operation-indeterminate [authorization capability]
  {:decision :indeterminate
   :error :adapter-failed
   :effect-status :unknown
   :retry-safe? false
   :app-id (:app-id authorization)
   :release-cid (:release-cid authorization)
   :capability capability})

(defn dispatch-operation
  "Route one app request through the host capability check before an adapter.

  `adapters` is host-owned and keyed by capability. The adapter receives only
  app/release identity, the capability name, and the request value; it never
  receives the protected installation record. An adapter exception is
  indeterminate because an external effect may have happened before it failed."
  [record runtime-facts capability request adapters]
  (let [authorization (authorize-operation
                       record (assoc runtime-facts :capability capability))
        adapter (get adapters capability)]
    (cond
      (not= :allow (:decision authorization))
      {:decision :refuse
       :error (:error authorization)
       :authorization authorization}

      (not (fn? adapter))
      {:decision :refuse
       :error :adapter-unavailable
       :app-id (:app-id authorization)
       :release-cid (:release-cid authorization)
       :capability capability}

      :else
      (let [context {:app-id (:app-id authorization)
                     :release-cid (:release-cid authorization)
                     :capability capability
                     :request request}]
        #?(:cljs
           (try
             (-> (js/Promise.resolve (adapter context))
                 (.then (fn [result]
                          (operation-completed authorization capability result)))
                 (.catch (fn [_]
                           (operation-indeterminate authorization capability))))
             (catch :default _
               (operation-indeterminate authorization capability)))
           :clj
           (try
             (operation-completed authorization capability (adapter context))
             (catch Throwable _
               (operation-indeterminate authorization capability))))))))
