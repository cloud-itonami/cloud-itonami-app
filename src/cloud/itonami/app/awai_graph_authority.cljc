(ns cloud.itonami.app.awai-graph-authority
  "Verify the authorization history of an app's L5 graph.

  `chain.authorized` checks causal integrity and joins each commit to its
  authority CID. This host adapter makes the security-sensitive parts explicit:
  the resolver must verify the grant signature, app scope, and write capability;
  the caller must state an evaluation instant and revocation policy."
  (:require [chain.authorized :as chain-authorized]
            [kotoba.protocol.cid :as cid]
            [kotoba.protocol.vocab :as vocab]))

(def app-graph-write-capability "kotoba://can/datom:transact")
(def maximum-history-visits 4096)
(def maximum-safe-epoch-millis 9007199254740991)
(def revocation-policies #{:prospective :retroactive})

(defn- cidv1? [value]
  (boolean (and (vocab/cid? value)
                (not (:error (cid/parse-cid value))))))

(defn- valid-epoch-millis? [value]
  (and (integer? value)
       (<= 0 value maximum-safe-epoch-millis)))

(defn- verified-grant? [authority-cid app-id grant]
  (and (map? grant)
       (= authority-cid (:cid grant))
       (= app-id (:app-id grant))
       (= app-graph-write-capability (:capability grant))
       (true? (:signature-verified? grant))
       (true? (:scope-verified? grant))
       (vocab/did-key? (:subject grant))
       (or (nil? (:not-before grant))
           (valid-epoch-millis? (:not-before grant)))
       (or (nil? (:expires-at grant))
           (valid-epoch-millis? (:expires-at grant)))
       (or (nil? (:revoked-at grant))
           (valid-epoch-millis? (:revoked-at grant)))))

(defn- normalize-grant-times [grant]
  (select-keys grant [:subject :not-before :expires-at :revoked-at]))

(defn verify-history
  "Verify every commit reachable from an L5 app graph head.

  `resolve-grant` is a host-owned adapter keyed by the commit's authority CID.
  It returns the normalized grant only after independently verifying the
  authority artifact's CID/signature, app identity, and
  `kotoba://can/datom:transact` scope. The normalized map must include
  `:cid`, `:app-id`, `:capability`, `:subject`, optional ISO-8601
  `:not-before`/`:expires-at`/`:revoked-at` as epoch milliseconds, and true
  `:signature-verified?`/`:scope-verified?` flags. The resolver must normalize
  signed timestamps before returning them. Unrecognized or unverified
  authority returns nil and fails the history closed.

  `:as-of` is an epoch-millisecond integer; `:revocation` is required. This
  function returns verification evidence, not a bare boolean; the report is
  usable only when `:decision` is `:verified` and `:verified?` is true. It does
  not fetch graph blocks, decrypt the arrangement snapshot, or validate the
  app manifest entity."
  [get-fn head-cid {:keys [app-id resolve-grant as-of revocation max-visits]}]
  (let [visit-limit (or max-visits maximum-history-visits)]
    (cond
      (not (ifn? get-fn))
      {:decision :refuse :error :invalid-block-reader}

      (not (cidv1? head-cid))
      {:decision :refuse :error :invalid-graph-head-cid}

      (not (vocab/reverse-dns-id? app-id))
      {:decision :refuse :error :invalid-app-id}

      (not (ifn? resolve-grant))
      {:decision :refuse :error :grant-resolver-required}

      (not (valid-epoch-millis? as-of))
      {:decision :refuse :error :evaluation-time-required}

      (not (contains? revocation-policies revocation))
      {:decision :refuse :error :revocation-policy-required}

      (not (and (nat-int? visit-limit)
                (pos? visit-limit)
                (<= visit-limit maximum-history-visits)))
      {:decision :refuse :error :invalid-history-bound}

      :else
      (try
        (let [resolve-verified-grant
              (fn [authority-cid]
                (let [grant (resolve-grant authority-cid)]
                  (when (verified-grant? authority-cid app-id grant)
                    (normalize-grant-times grant))))
              report (chain-authorized/verify-authorized
                      get-fn head-cid
                      {:resolve-grant resolve-verified-grant
                       :as-of as-of
                       :revocation revocation
                       :max-visits visit-limit})
              reason (or (:reason report)
                         (some-> report :findings first :reason))
              verified? (and (true? (:ok? report))
                             (pos-int? (:visited report))
                             (true? (get-in report [:causal :ok?]))
                             (not (true? (get-in report [:causal :truncated?]))))]
          (cond-> {:decision (if verified? :verified :refuse)
                   :verified? verified?
                   :app-id app-id
                   :head-cid head-cid
                   :as-of as-of
                   :revocation revocation
                   :visited (:visited report 0)
                   :findings (:findings report [])}
            reason (assoc :reason reason)))
        (catch Exception _
          {:decision :unavailable
           :error :graph-history-verification-failed
           :app-id app-id
           :head-cid head-cid
           :as-of as-of
           :revocation revocation})))))
