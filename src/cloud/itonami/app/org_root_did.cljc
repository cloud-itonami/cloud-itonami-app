(ns cloud.itonami.app.org-root-did
  "An organization's root identity, as `did:webvh` with a witness threshold.

  ## What changed and why

  Until now an organization was named `did:web:<domain>`, and that name had
  three properties nobody wanted: whoever controlled the DNS zone and the TLS
  certificate was the controller, there was no history, and PROVING a custom
  domain (`bind-verified-domain!`) changed the DID — the organization got a new
  identity because it got a new name.

  `did:webvh` separates the two. The SCID is the hash of the first log entry,
  so the identifier survives the move; `portable true` is set at genesis
  because it can only be set there. Every update is an entry in a hash-chained,
  signed log, and a resolver replays that chain rather than trusting whoever
  answers at the domain today.

  ## updateKeys is not the multi-party control

  Several `updateKeys` may be listed and ANY ONE of them signs a valid entry.
  The m-of-n is the `witness` parameter, which resolvers enforce: fewer than
  `threshold` weight of valid witness proofs and the update does not resolve.
  That is why this namespace configures both, and why the update key and the
  witness keys are different keys.

  ## The update key is a LADDER, not a key

  Pre-rotation makes every version rotate. Entry N commits `nextKeyHashes` for
  entry N+1, and entry N+1's `updateKeys` must hash to that commitment, so the
  key that signs version 2 is not the key that signed version 1. The seeds are
  therefore numbered — `update-0`, `update-1`, … — and version `v` is signed by
  generation `v-1` while committing generation `v`.

  That is the property worth having: a thief holding the CURRENT update key
  cannot name a key of their own as the successor, because the successor was
  named before the theft.

  ## Read this before trusting the threshold: the five witness keys are here

  Owner decision, 2026-08-20 (ADR-0068): the five witness keys are generated in
  this deployment and this process signs all five proofs. The threshold is
  therefore REAL to a resolver and NOT a defence against this machine — five
  signatures with one custody is one point of failure wearing five hats. It
  buys the shape (a verifier can name who approved, and a stolen update key
  alone still cannot publish), not the independence.

  The seam that fixes it is here and costs no rewrite: every signer is
  `{:multikey :sign-fn}`, so moving `:security` to a Security-team HSM is
  replacing one map. `local-witness-signers` is the only function that changes,
  and `accept-witness-proof` is already the intake for a witness that signs
  somewhere else — it verifies before it stores, so it needs no authentication
  to be safe."
  (:require [clojure.string :as str]
            [didwebvh.did :as webvh-did]
            [didwebvh.entry :as webvh-entry]
            [didwebvh.hash :as webvh-hash]
            [didwebvh.log :as webvh-log]
            [didwebvh.proof :as webvh-proof]
            [didwebvh.signer :as webvh-signer]
            [didwebvh.time :as webvh-time]
            [didwebvh.witness :as webvh-witness]
            #?@(:clj [[clojure.data.json :as json]
                      [clojure.java.io :as io]
                      [cloud.itonami.app.config :as config]]))
  #?(:clj (:import [java.security SecureRandom]
                   [java.util Base64])))

;; ── the shape of the control set ─────────────────────────────────────────────

(def witness-roles
  "The five approvers, in the order the owner named them. They are ROLES, not
  people and not machines: what makes a role a real witness is where its key
  lives, and today that is one place for all five."
  [:security :legal :operations :auditor :recovery])

(def witness-threshold
  "3-of-5. Weights are all 1, so the threshold is a plain count."
  3)

(defn co-located-custody?
  "Do all the witness keys live in one custody domain?

  True today, by owner decision. Kept as a function rather than a comment so
  the fact travels with the DID record and shows up in the UI and the audit,
  instead of being something a reader has to already know."
  []
  true)

;; ── key custody ──────────────────────────────────────────────────────────────
;;
;; Same mechanism as `capability/issuer-seed`: a base64 seed beside the state
;; file, generated on first use, never regenerated when unreadable. A new key
;; here would not invalidate past credentials — it would orphan the DID, since
;; the log's next entry could no longer be signed by a pre-committed key.

#?(:clj
   (defn- key-file [nam]
     (io/file (config/data-dir) "org-root-did" (str nam ".key"))))

#?(:clj
   (defn- read-seed [nam]
     (let [f (key-file nam)]
       (when (.isFile f)
         (.decode (Base64/getDecoder) ^String (str/trim (slurp f)))))))

#?(:clj
   (defn- write-seed! [nam ^bytes seed]
     (let [f (key-file nam)]
       (.mkdirs (.getParentFile f))
       (spit f (.encodeToString (Base64/getEncoder) seed))
       (doto f (.setReadable false false) (.setReadable true true)
               (.setWritable false false) (.setWritable true true))
       seed)))

#?(:clj
   (defn- seed [nam]
     (or (read-seed nam)
         (write-seed! nam (let [b (byte-array 32)]
                            (.nextBytes (SecureRandom.) b)
                            b)))))

#?(:clj
   (defn update-signer
     "The update key of one generation. Version `v` of a log is signed by
     generation `v-1` and commits generation `v`.

     Distinct from the Drive's issuer key on purpose: a compromise of the
     credential-issuing key must not also be control of the identity."
     [generation]
     (webvh-signer/from-seed (seed (str "update-" generation)))))

#?(:clj
   (defn local-witness-signers
     "The witness roles whose keys this deployment holds.

     Externalising a role means dropping it from here; the log does not change
     shape, and that role's proof then has to arrive through
     `accept-witness-proof`. Nothing else moves."
     []
     (mapv (fn [role]
             (assoc (webvh-signer/from-seed (seed (str "witness-" (name role))))
                    :role role))
           witness-roles)))

#?(:clj
   (defn witness-ids
     "The `did:key` of every declared witness, held here or not. This is what
     goes in the log; custody is invisible to a resolver, which is the point."
     []
     (mapv :did-key (local-witness-signers))))

(defn witness-parameter
  "The `witness` parameter for a set of signers — ids only. No private
  material appears in the DID log, so this shape is identical whether the
  keys are here or in five separate HSMs."
  [signers]
  {"threshold" witness-threshold
   "witnesses" (mapv (fn [s] {"id" (:did-key s)}) signers)})

;; ── documents ────────────────────────────────────────────────────────────────

(defn document-state
  "The DID document for a log entry.

  `assertion-multikey` is the deployment's credential-signing key (the same
  one the `did:web` document published), so a verifier that resolves this DID
  finds the key that actually signs this organization's credentials. It is NOT
  an update key: signing a credential and controlling the identity are
  different authorities and are different keys here.

  `scid` is `{SCID}` for a genesis entry and the real value afterwards, which
  is the only difference between the two cases."
  [{:keys [scid domain path assertion-multikey also-known-as]}]
  (let [id (str webvh-did/prefix (or scid webvh-hash/scid-placeholder) ":"
                (webvh-did/percent-encode domain)
                (when (seq path)
                  (str ":" (str/join ":" (map webvh-did/percent-encode path)))))
        key-id (str id "#" assertion-multikey)]
    (cond-> {"@context" ["https://www.w3.org/ns/did/v1"
                         "https://w3id.org/security/multikey/v1"]
             "id" id
             "verificationMethod" [{"id" key-id
                                    "type" "Multikey"
                                    "controller" id
                                    "publicKeyMultibase" assertion-multikey}]
             "assertionMethod" [key-id]
             "authentication" [key-id]}
      (seq also-known-as) (assoc "alsoKnownAs" (vec also-known-as)))))

;; ── minting and appending (pure) ─────────────────────────────────────────────

(defn mint
  "The genesis log entry and its witness file. Pure: every key arrives as a
  signer map and the time arrives as a string.

  Returns `{:did :scid :version-id :log :witness-file}`."
  [{:keys [domain path version-time assertion-multikey also-known-as
           update-signer next-multikey witness-signers portable?]
    :or {portable? true}}]
  (let [entry (-> (webvh-entry/genesis
                   {:version-time version-time
                    :parameters (cond-> {"method" webvh-hash/method-1-0
                                         "scid" webvh-hash/scid-placeholder
                                         "updateKeys" [(:multikey update-signer)]
                                         "portable" portable?
                                         "witness" (witness-parameter witness-signers)}
                                  next-multikey
                                  (assoc "nextKeyHashes" [(webvh-hash/key-hash next-multikey)]))
                    :state (document-state {:domain domain
                                            :path path
                                            :assertion-multikey assertion-multikey
                                            :also-known-as also-known-as})})
                  (webvh-entry/sign update-signer))
        version-id (get entry "versionId")]
    {:did (get-in entry ["state" "id"])
     :scid (get-in entry ["parameters" "scid"])
     :version-id version-id
     :log [entry]
     :witness-file [{"versionId" version-id
                     "proof" (mapv #(webvh-entry/witness-proof version-id %) witness-signers)}]}))

(defn append
  "The next log entry, appended to `log`. Pure.

  `state` is the DID document for the new version. `update-signer` must be the
  generation the previous entry committed to, and `next-multikey` is the
  generation this one commits — both are stated explicitly because
  pre-rotation forbids inheriting `updateKeys`, and a resolver terminates
  rather than guessing.

  `deactivate?` also clears `nextKeyHashes`. It has to: with pre-rotation
  active the keys authorized to sign an entry ARE that entry's `updateKeys`,
  so an entry that both empties `updateKeys` and keeps pre-rotation on could
  be signed by nothing at all. Turning the commitment off in the same entry
  that deactivates keeps the entry signable by the key that was already
  pre-committed."
  [{:keys [log version-time state update-signer next-multikey witness-signers
           deactivate?]}]
  (let [previous (peek (vec log))
        entry (-> (webvh-entry/next-entry
                   previous
                   {:version-time version-time
                    :parameters (cond-> {"updateKeys" [(:multikey update-signer)]
                                         "nextKeyHashes"
                                         (if deactivate?
                                           []
                                           [(webvh-hash/key-hash next-multikey)])}
                                  deactivate? (assoc "deactivated" true))
                    :state state})
                  (webvh-entry/sign update-signer))
        version-id (get entry "versionId")]
    {:did (get-in entry ["state" "id"])
     :version-id version-id
     :log (conj (vec log) entry)
     :new-proofs {"versionId" version-id
                  "proof" (mapv #(webvh-entry/witness-proof version-id %) witness-signers)}}))

;; ── witness intake ───────────────────────────────────────────────────────────

(defn accept-witness-proof
  "Add one externally-produced witness proof to `witness-file`.

  Verified BEFORE it is stored, which is what makes this safe to expose
  without authentication: a proof counts only if it verifies under the key of
  a witness this DID actually declares, over this exact `version-id`. Anything
  else is refused rather than kept and ignored — a store that accepts junk it
  will later skip is a store whose size says nothing about its contents.

  Returns `{:ok? true :witness-file …}` or `{:ok? false :error kw}`."
  [{:keys [witness version-id witness-file proof]}]
  (let [declared (set (map #(get % "id") (get witness "witnesses")))
        mk (webvh-proof/multikey-of (get proof "verificationMethod"))
        signer-did (when mk (str "did:key:" mk))
        existing (or (webvh-witness/proofs-for witness-file version-id) [])
        verdict (when mk
                  (webvh-proof/verify (webvh-witness/document version-id)
                                      proof {:allowed #{mk}}))]
    (cond
      (nil? mk)
      {:ok? false :error :didwebvh/bad-verification-method}

      (not (contains? declared signer-did))
      {:ok? false :error :didwebvh/not-a-declared-witness :witness signer-did}

      (not (:ok? verdict))
      {:ok? false :error (:error verdict) :witness signer-did}

      ;; One proof per witness. Without this a single witness could fill the
      ;; file, and the cap has to be on the STORE rather than on the count of
      ;; distinct approvals, which is already what `witness/verify` counts.
      (some #(= mk (webvh-proof/multikey-of (get % "verificationMethod"))) existing)
      {:ok? false :error :didwebvh/witness-already-approved :witness signer-did}

      :else
      (let [others (remove #(= version-id (get % "versionId")) (or witness-file []))]
        {:ok? true
         :witness signer-did
         :witness-file (vec (conj (vec others)
                                  {"versionId" version-id
                                   "proof" (conj (vec existing) proof)}))}))))

(defn witness-request
  "What an external witness needs in order to approve a version: the document
  to sign and who is expected to sign it. No key material, so this is safe to
  hand to whoever holds the role."
  [{:keys [did witness version-id]}]
  {:did did
   :version-id version-id
   :document (webvh-witness/document version-id)
   :cryptosuite webvh-proof/cryptosuite
   :threshold (get witness "threshold")
   :witnesses (mapv #(get % "id") (get witness "witnesses"))})

;; ── verifying, resolving, publishing ─────────────────────────────────────────

(defn verify
  "Verify a stored log the way any other resolver would — same function, same
  rules, no shortcut for having written it ourselves.

  `:ok? false` here is not a bug in storage: an organization whose witness
  proofs do not reach the threshold SHOULD fail, and this is where that shows."
  [{:keys [log witness-file did now]}]
  (webvh-log/verify log (cond-> {:witness-file witness-file}
                          now (assoc :now now)
                          did (assoc :expect-did did))))

(defn resolve-external
  "Resolve somebody ELSE's `did:webvh`.

  `fetch` is `(fn [url] {:status n :body s})` — injected, so this namespace
  makes no outbound request and a caller decides the timeout, the redirect
  policy and whether the request happens at all. Issuing a DID and being able
  to check one are different capabilities and this is the second."
  [did {:keys [fetch now]}]
  (let [log-response (fetch (webvh-did/log-url did))
        witness-response (fetch (webvh-did/witness-url did))]
    (if-not (= 200 (:status log-response))
      {:ok? false :error :didwebvh/log-unavailable :status (:status log-response)}
      (let [parse-json #?(:clj #(json/read-str %) :cljs #(js->clj (js/JSON.parse %)))
            log (try (mapv parse-json
                           (remove str/blank? (str/split-lines (:body log-response))))
                     (catch #?(:clj Exception :cljs :default) e
                       {:parse-error (ex-message e)}))
            witness-file (when (= 200 (:status witness-response))
                           (try (parse-json (:body witness-response))
                                (catch #?(:clj Exception :cljs :default) _ nil)))]
        (if (map? log)
          {:ok? false :error :didwebvh/log-unparseable :message (:parse-error log)}
          (webvh-log/verify log (cond-> {:witness-file witness-file :expect-did did}
                                  now (assoc :now now))))))))

#?(:clj
   (defn log-jsonl
     "`did.jsonl` — one JSON object per line, no trailing whitespace."
     [log]
     (str (str/join "\n" (map json/write-str log)) "\n")))

#?(:clj
   (defn witness-json
     "`did-witness.json` — the JSON array of `{versionId, proof}`."
     [witness-file]
     (json/write-str (or witness-file []))))

;; ── durability ───────────────────────────────────────────────────────────────
;;
;; The log is not derived state. A second `mint` hashes to a different SCID, so
;; a lost log is not a slow rebuild — it is the end of that identity's ability
;; to publish another version. Keeping the only copy inside `state.edn` makes
;; the DID exactly as durable as one file that everything else in this app also
;; writes.

#?(:clj
   (defn- published-file [organization-record-id nam]
     (io/file (config/data-dir) "org-root-did" "published"
              (str organization-record-id) nam)))

#?(:clj
   (defn persist!
     "Write the log and the witness proofs beside the state file, in the exact
     form they are served. A second copy in a second file, which is the
     cheapest thing that is actually independent of `state.edn`.

     Not a mirror in the sense ADR-0068 still owes (IPLD/IPFS, an off-machine
     witness store): one disk is one disk. It removes the single-FILE failure,
     not the single-machine one."
     [organization-record-id {:keys [log witness-file]}]
     (let [log-file (published-file organization-record-id "did.jsonl")
           witness-file* (published-file organization-record-id "did-witness.json")]
       (.mkdirs (.getParentFile log-file))
       (spit log-file (log-jsonl log))
       (spit witness-file* (witness-json witness-file))
       {:log-path (.getPath log-file) :witness-path (.getPath witness-file*)})))

#?(:clj
   (defn read-persisted
     "The log and proofs as last written by `persist!`, or nil. For recovery
     and for a gate that wants to compare the two copies."
     [organization-record-id]
     (let [log-file (published-file organization-record-id "did.jsonl")
           witness-file (published-file organization-record-id "did-witness.json")]
       (when (.isFile log-file)
         {:log (mapv #(json/read-str %)
                     (remove str/blank? (str/split-lines (slurp log-file))))
          :witness-file (when (.isFile witness-file)
                          (json/read-str (slurp witness-file)))}))))

;; ── the deployment's own operations ──────────────────────────────────────────

#?(:clj
   (defn issue!
     "Mint an organization's root DID with this deployment's keys.

     Version 1 is signed by update generation 0 and commits generation 1."
     [{:keys [domain path assertion-multikey also-known-as now]}]
     (let [now (or now (quot (System/currentTimeMillis) 1000))
           signers (local-witness-signers)
           minted (mint {:domain domain
                         :path path
                         :version-time (webvh-time/->iso8601 now)
                         :assertion-multikey assertion-multikey
                         :also-known-as also-known-as
                         :update-signer (update-signer 0)
                         :next-multikey (:multikey (update-signer 1))
                         :witness-signers signers})]
       (assoc minted
              :method :webvh
              :witness-threshold witness-threshold
              :witness-count (count signers)
              :co-located-custody? (co-located-custody?)))))

#?(:clj
   (defn move!
     "Append the entry that moves this DID to a new location.

     The SCID does not change — that is what `portable true` at genesis bought
     — and the DID it used to be is kept in `alsoKnownAs`, so a verifier
     holding the old string can see where it went.

     Pure inputs, one impure act: reading the next generation of update key."
     [{:keys [log domain path assertion-multikey now]}]
     (let [now (or now (quot (System/currentTimeMillis) 1000))
           previous (peek (vec log))
           previous-did (get-in previous ["state" "id"])
           scid (get-in (first log) ["parameters" "scid"])
           version (inc (webvh-entry/version-number (get previous "versionId")))
           also-known (distinct (conj (vec (get-in previous ["state" "alsoKnownAs"]))
                                      previous-did))]
       (append {:log log
                :version-time (webvh-time/->iso8601 now)
                :state (document-state {:scid scid
                                        :domain domain
                                        :path path
                                        :assertion-multikey assertion-multikey
                                        :also-known-as also-known})
                :update-signer (update-signer (dec version))
                :next-multikey (:multikey (update-signer version))
                :witness-signers (local-witness-signers)}))))

(defn merge-proofs
  "Fold the proofs a new entry arrived with into the witness file."
  [witness-file new-proofs]
  (let [version-id (get new-proofs "versionId")
        others (remove #(= version-id (get % "versionId")) (or witness-file []))]
    (vec (conj (vec others) new-proofs))))

(defn log-url [did] (webvh-did/log-url did))
(defn witness-url [did] (webvh-did/witness-url did))
