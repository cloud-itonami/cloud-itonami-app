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
            [cloud.itonami.app.awai-content-policy :as content-policy]
            [cloud.itonami.app.awai-dnssec-policy :as dnssec-policy]))

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
                (pos? (:binding-expires-at origin))
                (nat-int? (:dns-proof-checked-at origin))
                (nat-int? (:dns-proof-expires-at origin))
                (nat-int? (:installed-at record))
                (< (:dns-proof-checked-at origin)
                   (:dns-proof-expires-at origin))
                (<= (:dns-proof-checked-at origin) (:installed-at record))
                (< (:installed-at record) (:dns-proof-expires-at origin))))
      (conj {:field :origin :error :invalid-binding-clock})

      (not (and (vector? approved)
                (= (count approved) (count (set approved)))
                (empty? (app/unknown-caps approved))
                (every? requested approved)))
      (conj {:field :approved-caps :error :invalid-local-approvals})

      (not (nat-int? (:installed-at record)))
      (conj {:field :installed-at :error :invalid-clock}))))

(defn authorize-cell-install
  "Derive Awai's exact cell-install authorization receipt from a protected local
  Itonami installation record. This checks the declared app-local cell ID and
  DNA bundle CID; the host must first authenticate the record's private store."
  [record cell-id bundle-cid]
  (let [lock (:install-lock record)
        cells (:cells lock)
        cell (some #(when (= cell-id (:id %)) %) cells)
        bundle-cid-info (when (string? bundle-cid) (cid/parse-cid bundle-cid))]
    (cond
      (seq (installation-problems record))
      {:decision :refuse :error :invalid-installation}

      (not (and (string? cell-id)
                (vocab/reverse-dns-id? cell-id)
                (string? bundle-cid)
                (not (:error bundle-cid-info))
                (= :dag-cbor (:codec bundle-cid-info))))
      {:decision :refuse :error :invalid-cell-install-reference}

      (not= bundle-cid (:dna-cid cell))
      {:decision :refuse :error :cell-not-declared-by-install-lock}

      :else
      {:decision :authorized
       :app-id (:app-id lock)
       :release-cid (:release-cid lock)
       :cell-id cell-id
       :bundle-cid bundle-cid})))

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
                (pos? (:binding-expires-at resolved))
                (nat-int? (:dns-proof-checked-at resolved))
                (nat-int? (:dns-proof-expires-at resolved))
                (dnssec-policy/fresh-proof? (:domain resolved)
                                            (:dns-proof resolved)
                                            (:resolved-at resolved))
                (< (:dns-proof-checked-at resolved)
                   (:dns-proof-expires-at resolved))))
      {:decision :refuse :error :invalid-domain-origin}

      :else
      {:decision :install-prepared
       :install-lock lock
       :origin (select-keys resolved
                            [:domain :binding-cid :binding-sequence
                             :binding-expires-at :dns-proof-checked-at
                             :dns-proof-expires-at])
       :dns-proof (:dns-proof resolved)
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
                         "app/revoke" :previous-revoke-cid
                         nil)
        previous (get body previous-field)
        unexpected-previous-fields (case type
                                     "app/release" #{:previous-binding-cid :previous-revoke-cid}
                                     "domain/bind" #{:previous-release-cid :previous-revoke-cid}
                                     "app/revoke" #{:previous-release-cid :previous-binding-cid}
                                     #{:previous-release-cid :previous-binding-cid :previous-revoke-cid})]
    (and (map? record)
         (= type (:type record))
         (cidv1? (:cid record))
         ;; Awai source-chain sequence is zero-based; the body-level app release
         ;; or domain-binding sequence is a separate counter and starts at 1.
         (nat-int? (:sequence record))
         (map? body)
         (integer? sequence) (pos? sequence)
         (not-any? #(contains? body %) unexpected-previous-fields)
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
         (cidv1? (:release-cid body))
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

(defn- valid-revocation-record? [record]
  (let [body (:body record)]
    (and (map? record)
         (= "app/revoke" (:type record))
         (cidv1? (:cid record))
         (nat-int? (:sequence record))
         (valid-registry-record? record "app/revoke")
         (map? body)
         (or (= #{:app-id :publisher-did :release-cid :sequence :reason-code :issued-at}
                (set (keys body)))
             (= #{:app-id :publisher-did :release-cid :sequence :previous-revoke-cid
                  :reason-code :issued-at}
                (set (keys body))))
         (vocab/reverse-dns-id? (:app-id body))
         (vocab/did-key? (:publisher-did body))
         (cidv1? (:release-cid body))
         (string? (:reason-code body))
         (re-matches #"[a-z][a-z0-9-]{0,31}" (:reason-code body))
         (nat-int? (:issued-at body)))))

(defn- release-revocation [release records]
  (let [body (:body release)
        revocations (filter #(and (= "app/revoke" (:type %))
                                  (= (:app-id body) (get-in % [:body :app-id]))
                                  (= (:publisher-did body)
                                     (get-in % [:body :publisher-did])))
                            records)
        chain (when (seq revocations)
                (registry-linear-tip revocations :previous-revoke-cid))
        revocation (some #(when (= (:cid release) (get-in % [:body :release-cid])) %)
                         revocations)]
    (cond
      (= :conflict (:decision chain))
      {:decision :conflict :reason :revocation-chain-conflict}

      revocation
      {:decision :revoked :record revocation}

      :else
      {:decision :clear})))

(defn- valid-revocation-targets? [records]
  (every? (fn [revocation]
            (let [body (:body revocation)
                  target (some #(when (= (:release-cid body) (:cid %)) %)
                               records)]
              (and target
                   (= "app/release" (:type target))
                   (= (:app-id body) (get-in target [:body :app-id]))
                   (= (:publisher-did body)
                      (get-in target [:body :publisher-did])))))
          (filter #(= "app/revoke" (:type %)) records)))

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
            release-chain-result (registry-linear-tip releases :previous-release-cid)
            pinned-release (some #(when (and (= "app/release" (:type %))
                                             (= (:release-cid binding) (:cid %))) %)
                                 records)
            release-result (cond
                             (not= :tip (:decision release-chain-result))
                             release-chain-result

                             (nil? pinned-release)
                             {:decision :missing :reason :pinned-release-missing}

                             (not (some #(= (:cid pinned-release) (:cid %)) releases))
                             {:decision :conflict :reason :pinned-release-scope-mismatch}

                             :else
                             {:decision :tip :record pinned-release})
            release-record (:record release-result)
            revocation-result (when (= :tip (:decision release-result))
                                (release-revocation release-record records))
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
                  :revocation-status (cond
                                       (= :revoked (:decision revocation-result)) "revoked"
                                       (= :conflict (:decision revocation-result)) "conflict"
                                       (= :clear (:decision revocation-result)) "clear"
                                       :else "not-checked")
                  :content-origin-status (if content-origin-result
                                           (name (:decision content-origin-result))
                                           "not-checked")}]
        (cond
          (= :conflict (:decision release-result))
          (assoc base :decision :conflict :reason (:reason release-result))

          (= :missing (:decision release-result))
          (assoc base :decision :release-missing)

          (= :conflict (:decision revocation-result))
          (assoc base :decision :conflict
                 :reason (:reason revocation-result))

          (= :revoked (:decision revocation-result))
          (assoc base :decision :revoked
                 :reason :release-revoked
                 :release-cid (:cid release-record)
                 :release-sequence (get-in release-record [:body :sequence])
                 :manifest-cid (get-in release-record [:body :manifest-cid]))

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
  "Derive deterministic domain→release-pin candidates from a read snapshot
  whose canonical entry CIDs and signatures were independently verified by
  the Itonami host. A unique linear chain and revocation state are only
  discovery facts: DNSSEC, manifest bytes, and bundles are verified separately."
  [registry-result now]
  (let [snapshot (:snapshot registry-result)
        records (:records snapshot)]
    (cond
      (not= "configured-peer-checkpoint-quorum" (:trust registry-result))
      {:decision :refuse :error :untrusted-registry-snapshot}

      (not (and (map? snapshot)
                (= "awai.app-registry.read.v4" (:schema snapshot))
                (cidv1? (:dna-cid snapshot))
                (vector? records) (<= (count records) 1024)))
      {:decision :refuse :error :invalid-registry-snapshot}

      (not (nat-int? now))
      {:decision :refuse :error :invalid-clock}

      (not-every? (fn [record]
                    (or (valid-binding-record? record)
                        (valid-release-record? record)
                        (valid-content-origin-record? record)
                        (valid-revocation-record? record)))
                  records)
      {:decision :refuse :error :invalid-registry-record}

      (not= (count records) (count (set (map :cid records))))
      {:decision :refuse :error :duplicate-registry-cid}

      (not (valid-revocation-targets? records))
      {:decision :refuse :error :invalid-revocation-target}

      :else
      (let [domains (->> records
                         (filter #(= "domain/bind" (:type %)))
                         (map #(get-in % [:body :domain]))
                         distinct
                         sort)]
        {:decision :candidates
         :candidates (mapv #(domain-candidate % records now) domains)}))))

(defn verified-release-descriptor
  "Join one verified manifest block to the exact app/release record that names
  it. `registry-result` must come from the Awai adapter after it verified every
  signed envelope; `manifest-result` must come from `awai-manifest/verify-block`.
  The returned descriptor is the trusted input expected by kotoba.protocol.install."
  [registry-result release-cid manifest-result]
  (let [snapshot (:snapshot registry-result)
        records (:records snapshot)
        release (some #(when (= release-cid (:cid %)) %) records)
        body (:body release)
        revocation (when release (release-revocation release records))
        manifest (:manifest manifest-result)
        descriptor (when release
                     {:verified? true
                      :app-id (:app-id body)
                      :publisher-did (:publisher-did body)
                      :channel (:channel body)
                      :release-cid (:cid release)
                      :manifest-cid (:manifest-cid body)
                      :sequence (:sequence body)
                      :manifest manifest})]
    (cond
      (not= "configured-peer-checkpoint-quorum" (:trust registry-result))
      {:decision :refuse :error :untrusted-registry-snapshot}

      (not (and (map? snapshot)
                (= "awai.app-registry.read.v4" (:schema snapshot))
                (vector? records)
                (<= (count records) 1024)))
      {:decision :refuse :error :invalid-registry-snapshot}

      (not (and release (valid-release-record? release)))
      {:decision :refuse :error :release-record-not-found-or-invalid}

      (not (every? valid-revocation-record?
                   (filter #(= "app/revoke" (:type %)) records)))
      {:decision :refuse :error :invalid-revocation-record}

      (not (valid-revocation-targets? records))
      {:decision :refuse :error :invalid-revocation-target}

      (= :revoked (:decision revocation))
      {:decision :refuse :error :release-revoked}

      (= :conflict (:decision revocation))
      {:decision :refuse :error :revocation-chain-conflict}

      (not= :verified (:decision manifest-result))
      {:decision :refuse :error :manifest-block-not-verified}

      (not= (:manifest-cid body) (:manifest-cid manifest-result))
      {:decision :refuse :error :manifest-cid-does-not-match-release}

      (not= (:app-id body) (:app-id manifest-result))
      {:decision :refuse :error :manifest-app-id-does-not-match-release}

      :else
      (let [problems (install/verified-release-problems descriptor)]
        (if (seq problems)
          {:decision :refuse :error :invalid-release-manifest :problems problems}
          {:decision :verified :release descriptor})))))

(defn resolve-domain
  "Resolve one hostname from already verified adapter facts and attach its
  install plan. The DNS adapter supplies a fresh DNSSEC-verified TXT proof; the
  Awai adapter supplies the verified binding and selected release."
  [domain {:keys [binding txt-proof release now]}]
  (if-not (dnssec-policy/fresh-proof? domain txt-proof now)
    {:decision :refuse :error :dnssec-proof-not-fresh-or-invalid}
    (let [resolved (install/resolve-entry domain binding txt-proof release now)
          resolved (cond-> resolved
                     (= :resolved (:decision resolved))
                     (assoc :dns-proof-checked-at (:checked-at txt-proof)
                            :dns-proof-expires-at (:expires-at txt-proof)
                            :dns-proof txt-proof
                            :resolved-at now))]
      (if (= :resolved (:decision resolved))
        (assoc resolved :install-plan (prepare-install resolved))
        resolved))))

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

      (not (and (nat-int? (get-in prepared [:origin :dns-proof-expires-at]))
                (< now (get-in prepared [:origin :dns-proof-expires-at]))
                (= (get-in prepared [:origin :binding-cid])
                   (get-in prepared [:dns-proof :binding-cid]))
                (dnssec-policy/fresh-proof?
                 (get-in prepared [:origin :domain])
                 (:dns-proof prepared) now)))
      {:decision :refuse :error :dnssec-proof-expired}

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
