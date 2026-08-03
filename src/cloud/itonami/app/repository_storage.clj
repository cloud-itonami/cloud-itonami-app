(ns cloud.itonami.app.repository-storage
  "ADR-0013 runtime: local EDN reconciliation and encrypted repository publish.

  Plaintext is accepted and returned only at this local boundary. Block
  transports receive ciphertext byte arrays. A signed Kotobase head is
  advanced only after the transport confirms every referenced block."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [kagi.crypto :as crypto]
            [kotobase.store :as kstore])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.security MessageDigest]
           [java.util Base64 UUID]))

(def format-version 1)
(def default-max-chunk-bytes (* 256 1024))
(def ^:private heads-collection "cloud-itonami.repository-heads.v1")
(def ^:private heads-stream "cloud-itonami.repository-head-history.v1")
(def ^:private missing (Object.))

(defprotocol BlockTransport
  (stage-block! [transport cid ciphertext])
  (publish-blocks! [transport cids])
  (fetch-block [transport cid]))

(defprotocol HeadRegistry
  (registry-snapshot [registry owner])
  (registry-advance! [registry owner expected-revision expected-previous head]))

(defn- bytes?* [value]
  (= (class (byte-array 0)) (class value)))

(defn- compare-edn [left right]
  (compare (pr-str left) (pr-str right)))

(defn canonical-value
  "Return a deterministic, EDN-only representation without changing collection
  semantics. Unknown JVM objects are denied instead of being printed as
  unreadable `#object` values."
  [value]
  (cond
    (map? value)
    (into (sorted-map-by compare-edn)
          (map (fn [[key child]]
                 [(canonical-value key) (canonical-value child)]))
          value)

    (vector? value) (mapv canonical-value value)
    (set? value) (into (sorted-set-by compare-edn) (map canonical-value) value)
    (list? value) (apply list (map canonical-value value))
    (or (nil? value) (string? value) (keyword? value) (symbol? value)
        (number? value) (boolean? value) (char? value) (uuid? value)
        (inst? value) (bytes?* value)) value
    :else (throw (ex-info "repository value is not safe EDN"
                          {:type :repository-storage/unsafe-edn
                           :class (str (class value))}))))

(defn- wire-value [value]
  (cond
    (bytes?* value) {:kagi/b64 (.encodeToString (Base64/getEncoder) value)}
    (map? value) (into (sorted-map-by compare-edn)
                       (map (fn [[k v]] [(wire-value k) (wire-value v)])) value)
    (vector? value) (mapv wire-value value)
    (set? value) (into (sorted-set-by compare-edn) (map wire-value) value)
    (list? value) (apply list (map wire-value value))
    :else value))

(defn canonical-bytes ^bytes [value]
  (.getBytes ^String (pr-str (wire-value (canonical-value value)))
             StandardCharsets/UTF_8))

(defn wire-string [value]
  (String. (canonical-bytes value) StandardCharsets/UTF_8))

(defn- decode-wire [^bytes bytes]
  (let [decoded (edn/read-string
                 {:readers {}
                  :default (fn [tag _]
                             (throw (ex-info "tagged repository value denied"
                                             {:type :repository-storage/tagged-edn
                                              :tag tag})))}
                 (String. bytes StandardCharsets/UTF_8))]
    (walk/postwalk
     (fn [value]
       (if (and (map? value) (= #{:kagi/b64} (set (keys value))))
         (.decode (Base64/getDecoder) ^String (:kagi/b64 value))
         value))
     decoded)))

(defn decode-wire-string [value]
  (decode-wire (.getBytes ^String value StandardCharsets/UTF_8)))

(defn sha256 ^bytes [^bytes bytes]
  (.digest (MessageDigest/getInstance "SHA-256") bytes))

(defn hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn content-cid [value]
  (str "sha256:" (hex (sha256 (canonical-bytes value)))))

(defn bytes-cid [^bytes value]
  (str "sha256:" (hex (sha256 value))))

(defn valid-owner? [owner]
  (and (string? owner)
       (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{7,127}" owner))
       (not (str/includes? owner ".."))))

(defn validate-state!
  [state]
  (when-not (map? state)
    (throw (ex-info "repository state must be an EDN map"
                    {:type :repository-storage/invalid-state})))
  (doseq [datom (:datoms state)]
    (when-not (and (vector? datom) (= 3 (count datom))
                   (or (string? (nth datom 0)) (keyword? (nth datom 0)))
                   (keyword? (nth datom 1)))
      (throw (ex-info "repository datom requires a stable entity and keyword attribute"
                      {:type :repository-storage/invalid-datom :datom datom}))))
  (-> state
      (update :datoms #(vec (sort-by pr-str (set (or % [])))))
      canonical-value))

(defn semantic-cid [state]
  (content-cid (validate-state! state)))

(defn- datom-index [datoms]
  (reduce (fn [index [entity attribute value]]
            (update index [entity attribute] (fnil conj #{}) value))
          {} datoms))

(defn- choose-three-way [path base local remote]
  (cond
    (= local remote) local
    (= local base) remote
    (= remote base) local
    :else (throw (ex-info "repository reconciliation conflict"
                          {:type :repository-storage/conflict
                           :path path :base base :local local :remote remote}))))

(declare merge-node)

(defn- merge-map [path base local remote]
  (reduce
   (fn [result key]
     (let [merged (merge-node (conj path key)
                              (get base key missing)
                              (get local key missing)
                              (get remote key missing))]
       (if (identical? missing merged) result (assoc result key merged))))
   (sorted-map-by compare-edn)
   (sort compare-edn (set/union (set (keys base))
                                (set (keys local))
                                (set (keys remote))))))

(defn- merge-node [path base local remote]
  (cond
    (= local remote) local
    (= local base) remote
    (= remote base) local
    (and (map? base) (map? local) (map? remote))
    (merge-map path base local remote)
    :else (choose-three-way path base local remote)))

(defn- merge-datoms [base local remote]
  (let [base-index (datom-index base)
        local-index (datom-index local)
        remote-index (datom-index remote)
        identities (set/union (set (keys base-index))
                              (set (keys local-index))
                              (set (keys remote-index)))]
    (->> identities
         (mapcat (fn [[entity attribute :as identity]]
                   (for [value (choose-three-way
                               [:datoms identity]
                               (get base-index identity #{})
                               (get local-index identity #{})
                               (get remote-index identity #{}))]
                     [entity attribute value])))
         (sort-by pr-str)
         vec)))

(defn reconcile
  "Reconcile an editable candidate against its basis and the current state.
  Returns the canonical state and its assertion/retraction change set."
  [{:keys [base candidate current basis-cid]}]
  (let [base (validate-state! base)
        candidate (validate-state! candidate)
        current (validate-state! current)
        current-cid (semantic-cid current)
        _ (when-not (= basis-cid (semantic-cid base))
            (throw (ex-info "basis metadata does not match the supplied base"
                            {:type :repository-storage/invalid-basis
                             :expected basis-cid
                             :actual (semantic-cid base)})))
        merged (if (= basis-cid current-cid)
                 candidate
                 (assoc (merge-node [] (dissoc base :datoms)
                                    (dissoc candidate :datoms)
                                    (dissoc current :datoms))
                        :datoms (merge-datoms (:datoms base)
                                             (:datoms candidate)
                                             (:datoms current))))
        merged (validate-state! merged)
        before (set (:datoms current))
        after (set (:datoms merged))]
    {:state merged
     :basis/cid current-cid
     :semantic/cid (semantic-cid merged)
     :assertions (vec (sort-by pr-str (set/difference after before)))
     :retractions (vec (sort-by pr-str (set/difference before after)))}))

(defn apply-datom-transaction
  "Apply portable `:db/add`/`:db/retract` operations through the same
  reconciliation membrane used by direct EDN edits."
  [{:keys [base current basis-cid tx-data]}]
  (let [candidate
        (reduce
         (fn [state [operation entity attribute value :as operation-value]]
           (when-not (and (= 4 (count operation-value))
                          (#{:db/add :db/retract} operation))
             (throw (ex-info "unsupported portable datom operation"
                             {:type :repository-storage/invalid-transaction
                              :operation operation-value})))
           (update state :datoms
                   (fn [datoms]
                     (let [facts (set datoms)
                           datom [entity attribute value]]
                       (vec (sort-by pr-str
                                     ((if (= :db/add operation) conj disj)
                                      facts datom)))))))
         base tx-data)]
    (reconcile {:base base :candidate candidate :current current
                :basis-cid basis-cid})))

;; Chunk format -------------------------------------------------------------

(defn- flatten-nodes
  ([value] (flatten-nodes [] (canonical-value value)))
  ([path value]
   (cond
     (map? value)
     (into [{:path path :node/type :map}]
           (mapcat (fn [[key child]] (flatten-nodes (conj path key) child)))
           value)

     (vector? value)
     (into [{:path path :node/type :vector}]
           (mapcat (fn [[index child]]
                     (flatten-nodes (conj path index) child)))
           (map-indexed vector value))

     (set? value)
     (into [{:path path :node/type :set}]
           (mapcat (fn [[index child]]
                     (flatten-nodes (conj path index) child)))
           (map-indexed vector value))

     (list? value)
     (into [{:path path :node/type :list}]
           (mapcat (fn [[index child]]
                     (flatten-nodes (conj path index) child)))
           (map-indexed vector value))

     :else [{:path path :node/type :value :value value}])))

(defn- pack-records [records maximum]
  (loop [remaining records current [] chunks []]
    (if-let [record (first remaining)]
      (let [candidate (conj current record)
            size (alength (canonical-bytes
                           {:chunk/version format-version :records candidate}))]
        (cond
          (<= size maximum) (recur (next remaining) candidate chunks)
          (empty? current)
          (throw (ex-info "one EDN value exceeds the chunk limit"
                          {:type :repository-storage/chunk-too-large
                           :path (:path record) :bytes size :maximum maximum}))
          :else (recur remaining [] (conj chunks current))))
      (cond-> chunks (seq current) (conj current)))))

(defn chunk-state
  ([state] (chunk-state state default-max-chunk-bytes))
  ([state maximum]
   (when-not (and (integer? maximum) (>= maximum 1024))
     (throw (ex-info "chunk limit must be at least 1024 bytes"
                     {:type :repository-storage/invalid-chunk-limit})))
   (mapv (fn [index records]
           {:chunk/index index :chunk/version format-version :records records})
         (range)
         (pack-records (flatten-nodes (validate-state! state)) maximum))))

(defn rebuild-state [chunks]
  (let [records (mapcat :records (sort-by :chunk/index chunks))
        nodes (into {} (map (juxt :path identity)) records)
        direct-children
        (reduce (fn [index path]
                  (if (seq path)
                    (update index (pop path) (fnil conj []) (peek path))
                    index))
                {} (keys nodes))]
    (letfn [(build [path]
              (let [{node-type :node/type value :value} (get nodes path)
                    children (sort compare-edn (get direct-children path))]
                (case node-type
                  :value value
                  :map (into (sorted-map-by compare-edn)
                             (map (fn [key] [key (build (conj path key))]))
                             children)
                  :vector (mapv #(build (conj path %)) (sort children))
                  :set (into (sorted-set-by compare-edn)
                             (map #(build (conj path %))) (sort children))
                  :list (apply list (map #(build (conj path %))
                                         (sort children)))
                  (throw (ex-info "incomplete encrypted chunk graph"
                                  {:type :repository-storage/incomplete-chunks
                                   :path path})))))]
      (validate-state! (build [])))))

;; Kagi envelopes -----------------------------------------------------------

(defn- aad-bytes ^bytes [owner purpose]
  (.getBytes ^String (str "cloud-itonami/repository/v1\u0000" owner
                          "\u0000" (name purpose))
             StandardCharsets/UTF_8))

(defn- seal-bytes
  [provider vmk owner purpose plaintext]
  (let [aad (aad-bytes owner purpose)
        {:keys [dek nonce ciphertext]} (crypto/seal-item provider plaintext aad)
        kek (crypto/compartment-key provider vmk owner)]
    {:sealed/cid (bytes-cid ciphertext)
     :sealed/bytes (alength ^bytes ciphertext)
     :sealed/ciphertext ciphertext
     :sealed/nonce nonce
     :sealed/wrap (crypto/wrap-dek provider kek dek)
     :sealed/purpose purpose}))

(defn- descriptor [sealed]
  (dissoc sealed :sealed/ciphertext))

(defn- open-bytes
  [provider vmk owner expected-purpose descriptor ciphertext]
  (when-not (= (:sealed/cid descriptor) (bytes-cid ciphertext))
    (throw (ex-info "ciphertext CID mismatch"
                    {:type :repository-storage/ciphertext-mismatch
                     :expected (:sealed/cid descriptor)
                     :actual (bytes-cid ciphertext)})))
  (when-not (= (:sealed/bytes descriptor) (alength ^bytes ciphertext))
    (throw (ex-info "ciphertext byte count mismatch"
                    {:type :repository-storage/ciphertext-size-mismatch})))
  (when-not (= expected-purpose (:sealed/purpose descriptor))
    (throw (ex-info "ciphertext purpose mismatch"
                    {:type :repository-storage/purpose-mismatch})))
  (let [kek (crypto/compartment-key provider vmk owner)
        dek (crypto/unwrap-dek provider kek (:sealed/wrap descriptor))]
    (crypto/open-item provider dek (:sealed/nonce descriptor) ciphertext
                      (aad-bytes owner expected-purpose))))

(defn- seal-chunks [provider vmk owner state maximum]
  (mapv
   (fn [chunk]
     (let [sealed (seal-bytes provider vmk owner :chunk
                              (canonical-bytes chunk))]
       {:descriptor (assoc (descriptor sealed)
                           :chunk/index (:chunk/index chunk))
        :block [(:sealed/cid sealed) (:sealed/ciphertext sealed)]}))
   (chunk-state state maximum)))

(defn- manifest-value [owner semantic-cid chunk-results]
  {:manifest/version format-version
   :owner/storage-id owner
   :semantic/cid semantic-cid
   :chunks (mapv :descriptor chunk-results)})

(defn- sign-head [provider signing-secret head]
  (let [unsigned (dissoc head :head/cid :head/signature)
        signature (crypto/sign* provider signing-secret (canonical-bytes unsigned))
        signed (assoc unsigned :head/signature signature)]
    (assoc signed :head/cid (content-cid signed))))

(defn verify-head!
  [provider signing-public head]
  (let [claimed (:head/cid head)
        signed (dissoc head :head/cid)
        unsigned (dissoc signed :head/signature)]
    (when-not (= claimed (content-cid signed))
      (throw (ex-info "repository head CID mismatch"
                      {:type :repository-storage/head-cid-mismatch})))
    (when-not (crypto/verify* provider signing-public
                              (canonical-bytes unsigned)
                              (:head/signature head))
      (throw (ex-info "repository head signature denied"
                      {:type :repository-storage/head-signature-denied})))
    head))

(defn- seal-previous-head
  [provider vmk owner previous-head]
  (when previous-head
    (let [sealed (seal-bytes provider vmk owner :head
                             (canonical-bytes previous-head))]
      {:descriptor (descriptor sealed)
       :block [(:sealed/cid sealed) (:sealed/ciphertext sealed)]})))

(defn prepare-publication
  "Reconcile and seal a state. The returned preparation contains ciphertext
  only and may be persisted as an idempotent retry journal."
  [{:keys [provider vmk signing-secret owner key-epoch key-envelope
           key-envelopes max-chunk-bytes
           base candidate current basis-cid previous-head]
    :or {key-epoch 1 max-chunk-bytes default-max-chunk-bytes}}]
  (when-not (valid-owner? owner)
    (throw (ex-info "opaque owner storage id is invalid"
                    {:type :repository-storage/invalid-owner})))
  (let [result (reconcile {:base base :candidate candidate :current current
                           :basis-cid basis-cid})
        semantic (:semantic/cid result)
        key-envelope (or key-envelope (get key-envelopes key-epoch))
        ;; Random and journaled: deriving this public id from semantic/cid
        ;; would turn the signed head into a plaintext equality oracle.
        tx-id (str "tx:" (UUID/randomUUID))
        chunks (seal-chunks provider vmk owner (:state result) max-chunk-bytes)
        previous (seal-previous-head provider vmk owner previous-head)
        manifest (manifest-value owner semantic chunks)
        sealed-manifest (seal-bytes provider vmk owner :manifest
                                    (canonical-bytes manifest))
        blocks (cond-> (conj (mapv :block chunks)
                             [(:sealed/cid sealed-manifest)
                              (:sealed/ciphertext sealed-manifest)])
                 previous (conj (:block previous)))
        total-bytes (reduce + (map (comp alength second) blocks))
        head (sign-head
              provider signing-secret
              {:head/version format-version
               :tx/id tx-id
               :owner/storage-id owner
               :head/previous-cid (:head/cid previous-head)
               :head/previous-block (:descriptor previous)
               :manifest (descriptor sealed-manifest)
               :schema/version format-version
               :key/epoch key-epoch
               :key/envelope key-envelope
               :sealed/bytes total-bytes})]
    {:tx/id tx-id :basis/cid (:basis/cid result)
     :semantic/cid semantic :state (:state result)
     :assertions (:assertions result) :retractions (:retractions result)
     :head head :blocks blocks}))

(defn preparation->journal
  "Ciphertext-only EDN suitable for a local retry journal."
  [preparation]
  (canonical-bytes (dissoc preparation :state)))

(defn journal->preparation [bytes]
  (decode-wire bytes))

;; Kotobase head registry ---------------------------------------------------

(defn- http-json!
  [^HttpClient client endpoint token method body]
  (let [request-builder (-> (HttpRequest/newBuilder)
                            (.uri (URI/create
                                   (str (str/replace endpoint #"/$" "")
                                        "/xrpc/ai.gftd.apps.kotobase.encryptedGraph."
                                        method)))
                            (.header "content-type" "application/json")
                            (.header "accept" "application/json"))
        request-builder (if (seq token)
                          (.header request-builder "authorization"
                                   (str "Bearer " token))
                          request-builder)
        request (-> request-builder
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        response-body (json/read-str (.body response) :key-fn keyword)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Kotobase encryptedGraph request failed"
                      {:type :repository-storage/kotobase-request-failed
                       :method method :status status :body response-body})))
    response-body))

(defrecord EncryptedGraphHeadRegistry [endpoint token client]
  HeadRegistry
  (registry-snapshot [_ owner]
    (try
      (let [body (http-json! client endpoint token "get" {:graph owner})
            head-edn (get-in body [:manifest :blocks 0 :envelope :head_edn])]
        {:revision (long (or (:epoch body) 0))
         :head (when head-edn (decode-wire-string head-edn))
         :history []})
      (catch clojure.lang.ExceptionInfo error
        (let [{:keys [status body]} (ex-data error)]
          (if (and (= 404 status)
                   (= "EncryptedGraphNotFound" (:error body)))
            {:revision 0 :head nil :history []}
            (throw error))))))
  (registry-advance! [registry owner expected-revision expected-previous head]
    (let [{actual-revision :revision actual-head :head}
          (registry-snapshot registry owner)]
      (if (= (:head/cid actual-head) (:head/cid head))
        {:tx-id (:tx/id head) :revision actual-revision :idempotent? true}
        (do
          (when-not (= expected-revision actual-revision)
            (throw (ex-info "repository head revision is stale"
                            {:type :repository-storage/stale-head
                             :expected-revision expected-revision
                             :actual-revision actual-revision})))
          (when-not (= expected-previous (:head/cid actual-head))
            (throw (ex-info "repository previous head is stale"
                            {:type :repository-storage/stale-head
                             :expected-head expected-previous
                             :actual-head (:head/cid actual-head)})))
          (let [epoch (inc actual-revision)
                body (http-json!
                      client endpoint token "put"
                      {:graph owner :epoch epoch
                       :expected_epoch actual-revision
                       :manifest
                       {:version 1 :graph owner :epoch epoch
                        :blocks [{:id (get-in head [:manifest :sealed/cid])
                                  :envelope {:head_edn (wire-string head)}}]
                        :key-grants []}})]
            {:tx-id (:tx/id head) :revision (:epoch body)
             :manifest-cid (:manifest_cid body)}))))))

(defn encrypted-graph-head-registry
  "Use kotobase.net's deployed encryptedGraph expected_epoch CAS as the public
  signed-head registry. TOKEN is a tenant-authenticated bearer session/PAT."
  [endpoint token]
  (when-not (and (string? endpoint) (seq endpoint)
                 (string? token) (seq token))
    (throw (ex-info "Kotobase endpoint and bearer token are required"
                    {:type :repository-storage/kotobase-context-required})))
  (->EncryptedGraphHeadRegistry endpoint token (HttpClient/newHttpClient)))

(defn head-snapshot
  [store owner]
  (if (satisfies? HeadRegistry store)
    (registry-snapshot store owner)
    (do
      (when-not (kstore/transactional-store? store)
        (throw (ex-info "transactional Kotobase store is required"
                        {:type :repository-storage/transactional-store-required})))
      (let [snapshot (kstore/-snapshot
                      store {:collections [heads-collection]
                             :streams [heads-stream]})]
        {:revision (:revision snapshot)
         :head (get-in snapshot [:docs heads-collection owner])
         :history (->> (get-in snapshot [:streams heads-stream])
                       (filter #(= owner (:owner/storage-id %)))
                       vec)}))))

(defn- advance-head!
  [store owner expected-revision expected-previous head]
  (if (satisfies? HeadRegistry store)
    (registry-advance! store owner expected-revision expected-previous head)
    (let [{actual-revision :revision actual-head :head} (head-snapshot store owner)]
    (if (= (:head/cid actual-head) (:head/cid head))
      {:tx-id (:tx/id head) :revision actual-revision :idempotent? true}
      (do
        (when-not (= expected-revision actual-revision)
          (throw (ex-info "repository head revision is stale"
                          {:type :repository-storage/stale-head
                           :expected-revision expected-revision
                           :actual-revision actual-revision})))
        (when-not (= expected-previous (:head/cid actual-head))
          (throw (ex-info "repository previous head is stale"
                          {:type :repository-storage/stale-head
                           :expected-head expected-previous
                           :actual-head (:head/cid actual-head)})))
        (kstore/-transact
         store {:tx-id (:tx/id head)
                :expected-revision expected-revision
                :puts [[heads-collection owner head]]
                :deletes []
                :appends [[heads-stream
                           {:owner/storage-id owner
                            :head/cid (:head/cid head)
                            :head/previous-cid (:head/previous-cid head)
                            :manifest/cid (get-in head [:manifest :sealed/cid])
                            :sealed/bytes (:sealed/bytes head)
                            :tx/id (:tx/id head)}]]}))))))

(defn publish-prepared!
  "Publish all ciphertext blocks, then atomically advance the signed head.
  A transport failure or negative verification happens before Kotobase CAS."
  [{:keys [transport head-store owner expected-revision signing-public provider]}
   preparation]
  (when-not (= owner (get-in preparation [:head :owner/storage-id]))
    (throw (ex-info "preparation owner mismatch"
                    {:type :repository-storage/owner-mismatch})))
  (verify-head! provider signing-public (:head preparation))
  (doseq [[cid bytes] (:blocks preparation)]
    (when-not (= cid (bytes-cid bytes))
      (throw (ex-info "prepared block CID mismatch"
                      {:type :repository-storage/prepared-block-mismatch
                       :cid cid})))
    (stage-block! transport cid bytes))
  (let [cids (mapv first (:blocks preparation))
        publication (publish-blocks! transport cids)]
    (when-not (and (:published? publication)
                   (= (set cids) (set (:verified-cids publication))))
      (throw (ex-info "ciphertext transport did not verify every block"
                      {:type :repository-storage/transport-unverified
                       :publication publication :required-cids cids})))
    (let [receipt (advance-head!
                   head-store owner expected-revision
                   (get-in preparation [:head :head/previous-cid])
                   (:head preparation))]
      {:published? true :publication publication :receipt receipt
       :head (:head preparation) :state (:state preparation)})))

(defn- fetch-verified! [transport {:sealed/keys [cid bytes]}]
  (let [ciphertext (fetch-block transport cid)]
    (when-not ciphertext
      (throw (ex-info "referenced ciphertext block is unavailable"
                      {:type :repository-storage/missing-block :cid cid})))
    (when-not (= bytes (alength ^bytes ciphertext))
      (throw (ex-info "referenced ciphertext block size differs"
                      {:type :repository-storage/ciphertext-size-mismatch
                       :cid cid})))
    (when-not (= cid (bytes-cid ciphertext))
      (throw (ex-info "referenced ciphertext block hash differs"
                      {:type :repository-storage/ciphertext-mismatch
                       :cid cid})))
    ciphertext))

(defn- vmk-for-head
  [{:keys [vmk vmks key-epoch unwrap-repository-vmk]} head]
  (or (get vmks (:key/epoch head))
      (when (or (nil? key-epoch) (= key-epoch (:key/epoch head))) vmk)
      (when (and unwrap-repository-vmk (:key/envelope head))
        (unwrap-repository-vmk (:key/epoch head) (:key/envelope head)))))

(defn hydrate-head
  "Verify a signed head, decrypt its manifest and chunks, rebuild the EDN
  projection, and verify the private semantic CID."
  [{:keys [transport provider signing-public owner] :as context} head]
  (verify-head! provider signing-public head)
  (when-not (= owner (:owner/storage-id head))
    (throw (ex-info "head belongs to a different storage owner"
                    {:type :repository-storage/owner-mismatch})))
  (let [vmk (vmk-for-head context head)
        _ (when-not vmk
            (throw (ex-info "VMK for the head key epoch is unavailable"
                            {:type :repository-storage/key-epoch-unavailable
                             :key/epoch (:key/epoch head)})))
        manifest-descriptor (:manifest head)
        manifest-bytes (open-bytes
                        provider vmk owner :manifest manifest-descriptor
                        (fetch-verified! transport manifest-descriptor))
        manifest (decode-wire manifest-bytes)
        _ (when-not (and (= format-version (:manifest/version manifest))
                         (= owner (:owner/storage-id manifest)))
            (throw (ex-info "encrypted manifest metadata is invalid"
                            {:type :repository-storage/invalid-manifest})))
        chunks
        (mapv
         (fn [chunk-descriptor]
           (-> (open-bytes provider vmk owner :chunk chunk-descriptor
                           (fetch-verified! transport chunk-descriptor))
               decode-wire))
         (:chunks manifest))
        state (rebuild-state chunks)
        actual (semantic-cid state)]
    (when-not (= (:semantic/cid manifest) actual)
      (throw (ex-info "hydrated semantic CID differs from manifest"
                      {:type :repository-storage/semantic-cid-mismatch
                       :expected (:semantic/cid manifest) :actual actual})))
    {:state state :basis/cid actual :head head :manifest manifest}))

(defn hydrate-current
  [context]
  (let [{:keys [head] :as snapshot}
        (head-snapshot (:head-store context) (:owner context))]
    (when head
      (assoc (hydrate-head context head) :head/revision (:revision snapshot)))))

(defn retained-heads
  "Recover the complete signed head chain from the current Kotobase head and
  encrypted previous-head blocks in DataLad. Legacy heads which claim a parent
  but lack its encrypted locator fail closed instead of under-counting usage."
  [{:keys [transport provider signing-public owner] :as context} current-head]
  (loop [head current-head result [] seen #{}]
    (if-not head
      result
      (do
        (verify-head! provider signing-public head)
        (when-not (= owner (:owner/storage-id head))
          (throw (ex-info "retained head belongs to a different owner"
                          {:type :repository-storage/owner-mismatch})))
        (when (contains? seen (:head/cid head))
          (throw (ex-info "retained head chain contains a cycle"
                          {:type :repository-storage/head-cycle
                           :head/cid (:head/cid head)})))
        (let [previous-cid (:head/previous-cid head)
              previous-descriptor (:head/previous-block head)
              previous
              (when previous-cid
                (when-not previous-descriptor
                  (throw (ex-info "retained head locator is unavailable"
                                  {:type :repository-storage/head-history-unavailable
                                   :head/cid (:head/cid head)
                                   :head/previous-cid previous-cid})))
                (let [head-vmk (vmk-for-head context head)
                      _ (when-not head-vmk
                          (throw (ex-info "VMK for retained head is unavailable"
                                          {:type :repository-storage/key-epoch-unavailable
                                           :key/epoch (:key/epoch head)})))
                      value (-> (open-bytes
                                 provider head-vmk owner :head
                                 previous-descriptor
                                 (fetch-verified! transport
                                                  previous-descriptor))
                                decode-wire)]
                  (when-not (= previous-cid (:head/cid value))
                    (throw (ex-info "retained head chain CID differs"
                                    {:type :repository-storage/head-chain-mismatch
                                     :expected previous-cid
                                     :actual (:head/cid value)})))
                  value))]
          (recur previous (conj result head)
                 (conj seen (:head/cid head))))))))

(defn prepare-vmk-rotation
  "Re-wrap chunk DEKs under NEW-VMK without rewriting chunk ciphertext. Only a
  small encrypted manifest block and the signed head change."
  [{:keys [transport provider vmk new-vmk signing-secret signing-public
           owner head key-epoch key-envelope rotation-event]}]
  (let [{old-manifest :manifest}
        (hydrate-head {:transport transport :provider provider :vmk vmk
                       :signing-public signing-public :owner owner} head)
        old-kek (crypto/compartment-key provider vmk owner)
        new-kek (crypto/compartment-key provider new-vmk owner)
        chunks (mapv
                (fn [chunk]
                  (let [dek (crypto/unwrap-dek provider old-kek
                                               (:sealed/wrap chunk))]
                    (assoc chunk :sealed/wrap
                           (crypto/wrap-dek provider new-kek dek))))
                (:chunks old-manifest))
        previous (seal-previous-head provider new-vmk owner head)
        manifest (assoc old-manifest :chunks chunks)
        sealed-manifest (seal-bytes provider new-vmk owner :manifest
                                    (canonical-bytes manifest))
        head* (sign-head
               provider signing-secret
               {:head/version format-version
                :tx/id (str "rotate:" (UUID/randomUUID))
                :owner/storage-id owner
                :head/previous-cid (:head/cid head)
                :head/previous-block (:descriptor previous)
                :manifest (descriptor sealed-manifest)
                :schema/version format-version
                :key/epoch (inc (long key-epoch))
                :key/envelope key-envelope
                :key/rotation-event rotation-event
                :sealed/bytes (+ (:sealed/bytes sealed-manifest)
                                 (reduce + (map :sealed/bytes chunks))
                                 (:sealed/bytes (:descriptor previous)))})]
    {:tx/id (:tx/id head*) :basis/cid (:semantic/cid old-manifest)
     :semantic/cid (:semantic/cid old-manifest)
     :head head*
     :blocks [[(:sealed/cid sealed-manifest)
               (:sealed/ciphertext sealed-manifest)]
              (:block previous)]}))

(defn storage-usage
  "Reconcile logical signed usage with unique physical ciphertext bytes for a
  collection of retained heads. Requires the VMK because chunk membership is
  intentionally hidden in encrypted manifests."
  [{:keys [transport owner] :as context} heads]
  (let [manifests
        (mapv (fn [head]
                (:manifest
                 (hydrate-head context
                               head)))
              heads)
        descriptors (concat (map :manifest heads)
                            (keep :head/previous-block heads)
                            (mapcat :chunks manifests))
        unique-descriptors (vals (into {} (map (juxt :sealed/cid identity))
                                      descriptors))
        expected (reduce + (map :sealed/bytes unique-descriptors))
        physical (reduce
                  +
                  (map (fn [descriptor]
                         (alength ^bytes (fetch-verified! transport descriptor)))
                       unique-descriptors))]
    {:owner/storage-id owner :sealed/bytes expected
     :physical/bytes physical :reconciled? (= expected physical)
     :unique-blocks (count unique-descriptors)}))

;; Block transport implementations -----------------------------------------

(defrecord MemoryBlockTransport [staged published fail-publish?]
  BlockTransport
  (stage-block! [_ cid ciphertext]
    (swap! staged assoc cid ciphertext)
    cid)
  (publish-blocks! [_ cids]
    (if @fail-publish?
      (throw (ex-info "injected block transport failure"
                      {:type :repository-storage/transport-failure}))
      (let [available (filterv #(contains? @staged %) cids)]
        (swap! published into (select-keys @staged available))
        {:published? (= (set cids) (set available))
         :verified-cids available})))
  (fetch-block [_ cid] (get @published cid)))

(defn memory-block-transport []
  (->MemoryBlockTransport (atom {}) (atom {}) (atom false)))

(defn fail-memory-publish! [transport fail?]
  (reset! (:fail-publish? transport) fail?)
  transport)

(defn- block-filename [cid]
  (when-not (re-matches #"sha256:[0-9a-f]{64}" cid)
    (throw (ex-info "invalid ciphertext CID path"
                    {:type :repository-storage/invalid-cid :cid cid})))
  (str (subs cid 7) ".block"))

(defn- run-command!
  [directory arguments]
  (let [builder (doto (ProcessBuilder. ^java.util.List (vec arguments))
                  (.directory (io/file directory))
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "DataLad command failed"
                      {:type :repository-storage/datalad-command-failed
                       :arguments (vec arguments) :exit exit :output output})))
    output))

(defn- atomic-write-bytes! [file bytes]
  (let [file (io/file file)
        temporary (io/file (.getParentFile file)
                           (str "." (.getName file) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs (.getParentFile file))
    (Files/write (.toPath temporary) ^bytes bytes
                 (make-array java.nio.file.OpenOption 0))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    file))

(defrecord DataLadBlockTransport [dataset remote]
  BlockTransport
  (stage-block! [_ cid ciphertext]
    (when-not (= cid (bytes-cid ciphertext))
      (throw (ex-info "refusing block whose bytes do not match its CID"
                      {:type :repository-storage/prepared-block-mismatch
                       :cid cid})))
    (let [target (io/file dataset ".itonami" "blocks" (block-filename cid))]
      (when (and (.isFile target)
                 (not= cid (bytes-cid (Files/readAllBytes (.toPath target)))))
        (throw (ex-info "existing DataLad block is corrupt"
                        {:type :repository-storage/ciphertext-mismatch
                         :cid cid})))
      (when-not (.isFile target) (atomic-write-bytes! target ciphertext))
      cid))
  (publish-blocks! [_ cids]
    (when-not (and (string? remote) (not (str/blank? remote)))
      (throw (ex-info "DataLad remote is required before head publication"
                      {:type :repository-storage/datalad-remote-required})))
    (let [relative-paths (mapv #(str ".itonami/blocks/" (block-filename %)) cids)]
      (run-command! dataset
                    (into ["datalad" "save" "-m"
                           (str "cloud-itonami sealed blocks " (count cids)) "--"]
                          relative-paths))
      (run-command! dataset
                    (into ["datalad" "push" "--to" remote "--"] relative-paths))
      (let [verified
            (filterv
             (fn [cid]
               (try
                 (run-command! dataset
                               ["git" "annex" "whereis" (str "--in=" remote)
                                (str ".itonami/blocks/" (block-filename cid))])
                 true
                 (catch Exception _ false)))
             cids)]
        {:published? (= (set cids) (set verified))
         :verified-cids verified})))
  (fetch-block [_ cid]
    (let [file (io/file dataset ".itonami" "blocks" (block-filename cid))]
      (when-not (.isFile file)
        (run-command! dataset
                      ["datalad" "get" "--"
                       (str ".itonami/blocks/" (block-filename cid))]))
      (when (.isFile file) (Files/readAllBytes (.toPath file))))))

(defn datalad-block-transport
  "Create a ciphertext-only DataLad transport. DATASET must already be a
  configured dataset; initialization and remote choice are explicit operator
  actions so this function cannot accidentally publish to an unintended Git
  remote."
  [dataset remote]
  (let [dataset (.getCanonicalFile (io/file dataset))]
    (when-not (and (.isDirectory dataset)
                   (.isDirectory (io/file dataset ".git")))
      (throw (ex-info "existing DataLad dataset is required"
                      {:type :repository-storage/datalad-dataset-required
                       :path (.getPath dataset)})))
    (->DataLadBlockTransport (.getPath dataset) remote)))

(defn assert-empty-datalad-block-cache!
  "Prove that an isolated recovery dataset has no locally materialized block
  content before a cold hydrate. Broken git-annex symlinks are allowed: Java's
  `isFile` follows the link and becomes true only when its annex object is
  present. The result deliberately exposes counts, never dataset paths."
  [dataset]
  (let [root (io/file dataset ".itonami" "blocks")
        materialized (if (.isDirectory root)
                       (filter #(.isFile ^java.io.File %)
                               (file-seq root))
                       [])
        count* (count materialized)]
    (when (pos? count*)
      (throw (ex-info "cold recovery dataset already contains local block content"
                      {:type :repository-storage/cold-cache-not-empty
                       :materialized-blocks count*})))
    {:cache-empty? true :materialized-blocks 0}))

(defn audit-datalad-blocks
  "Verify the application-owned DataLad tree contains only CID-named blocks,
  each matching its bytes, and none of the supplied plaintext marker bytes.
  Git/DataLad metadata lives outside `.itonami` and is not treated as payload."
  [dataset plaintext-markers]
  (let [root (io/file dataset ".itonami")
        files (if (.isDirectory root)
                (filter #(.isFile ^java.io.File %) (file-seq root))
                [])
        marker-bytes (mapv #(.getBytes ^String (str %) StandardCharsets/UTF_8)
                           plaintext-markers)
        contains-bytes?
        (fn [^bytes haystack ^bytes needle]
          (and (pos? (alength needle))
               (some (fn [offset]
                       (loop [index 0]
                         (cond
                           (= index (alength needle)) true
                           (= (aget haystack (+ offset index))
                              (aget needle index)) (recur (inc index))
                           :else false)))
                     (range (inc (- (alength haystack) (alength needle)))))))
        result (reduce
                  (fn [result file]
                    (let [relative (.toString
                                    (.relativize (.toPath root) (.toPath file)))
                          filename (.getName ^java.io.File file)
                          bytes (Files/readAllBytes (.toPath ^java.io.File file))
                          expected (when-let [[_ digest]
                                              (re-matches #"([0-9a-f]{64})\.block"
                                                          filename)]
                                     (str "sha256:" digest))
                          problems (cond-> []
                                     (not (str/starts-with?
                                           relative
                                           (str "blocks" java.io.File/separator)))
                                     (conj :path-outside-blocks)
                                     (nil? expected) (conj :invalid-filename)
                                     (and expected (not= expected (bytes-cid bytes)))
                                     (conj :cid-mismatch)
                                     (some #(contains-bytes? bytes %) marker-bytes)
                                     (conj :plaintext-marker))]
                      (-> result
                          (update :sealed/bytes + (alength bytes))
                          (update :blocks inc)
                          (cond-> (seq problems)
                            (update :violations conj
                                    {:path relative :problems problems})))))
                  {:violations [] :sealed/bytes 0 :blocks 0}
                  files)]
    (assoc result :qualified? (and (pos? (:blocks result))
                                   (empty? (:violations result))))))

(defn plaintext-markers
  "Derive non-trivial textual markers from a private state for a local leak
  scan. The returned values must never be logged or persisted as evidence."
  [state]
  (->> (tree-seq coll? seq (validate-state! state))
       (keep (fn [value]
               (cond
                 (string? value) value
                 (keyword? value) (str value)
                 (symbol? value) (str value)
                 :else nil)))
       (filter #(>= (count %) 3))
       distinct
       vec))

(defn- nearest-git-root [file]
  (loop [current (.getCanonicalFile ^java.io.File file)]
    (cond
      (.exists (io/file current ".git")) current
      (.getParentFile current) (recur (.getParentFile current))
      :else nil)))

(defn- git-ignored? [git-root file]
  (let [process (-> (ProcessBuilder.
                     ^java.util.List
                     ["git" "-C" (.getPath ^java.io.File git-root)
                      "check-ignore" "-q" "--"
                      (.getPath ^java.io.File file)])
                    (.redirectErrorStream true)
                    .start)]
    ;; Drain bounded diagnostic output without exposing the private path.
    (with-open [input (.getInputStream process)]
      (.readAllBytes input))
    (zero? (.waitFor process))))

(defn- require-workspace-untracked! [owner-dir]
  (when-let [git-root (nearest-git-root owner-dir)]
    (when-not (git-ignored? git-root owner-dir)
      (throw (ex-info "plaintext workspace is not ignored by its Git worktree"
                      {:type :repository-storage/plaintext-git-trackable}))))
  owner-dir)

(defn write-workspace!
  "Atomically materialize the local plaintext projection and basis metadata.
  The workspace must live outside the DataLad dataset."
  [workspace-root datalad-root owner state head revision]
  (when-not (valid-owner? owner)
    (throw (ex-info "opaque owner storage id is invalid"
                    {:type :repository-storage/invalid-owner})))
  (let [workspace-root (.getCanonicalFile (io/file workspace-root))
        datalad-root (.getCanonicalFile (io/file datalad-root))
        owner-dir (.getCanonicalFile (io/file workspace-root owner))]
    (when (str/starts-with? (.getPath owner-dir)
                            (str (.getPath datalad-root) java.io.File/separator))
      (throw (ex-info "plaintext workspace must be outside DataLad"
                      {:type :repository-storage/plaintext-in-datalad})))
    (require-workspace-untracked! owner-dir)
    (.mkdirs owner-dir)
    (let [state-file (io/file owner-dir "state.edn")
          basis-file (io/file owner-dir ".basis.edn")
          base-file (io/file owner-dir ".base.edn")]
      (atomic-write-bytes! state-file (canonical-bytes state))
      (atomic-write-bytes! base-file (canonical-bytes state))
      (atomic-write-bytes!
       basis-file
       (canonical-bytes {:basis/cid (semantic-cid state)
                         :head/cid (:head/cid head)
                         :head/revision revision}))
      {:state-file (.getPath state-file) :basis-file (.getPath basis-file)
       :base-file (.getPath base-file)})))

(defn- read-safe-edn-file [file]
  (edn/read-string
   {:readers {}
    :default (fn [tag _]
               (throw (ex-info "tagged workspace EDN denied"
                               {:type :repository-storage/tagged-edn :tag tag})))}
   (slurp file)))

(defn workspace-snapshot
  [workspace-root owner]
  (let [owner-dir (io/file workspace-root owner)
        state-file (io/file owner-dir "state.edn")
        basis-file (io/file owner-dir ".basis.edn")
        base-file (io/file owner-dir ".base.edn")]
    (when (.isFile state-file)
      {:state (validate-state! (read-safe-edn-file state-file))
       :base (when (.isFile base-file)
               (validate-state! (read-safe-edn-file base-file)))
       :basis (when (.isFile basis-file) (read-safe-edn-file basis-file))
       :owner-dir owner-dir})))

(defn commit-workspace!
  "Commit one user's editable workspace with a ciphertext-only retry journal.
  On retry, random-nonce encryption is not repeated: the exact prepared blocks
  and tx-id are reused."
  [{:keys [workspace-root datalad-root transport head-store provider vmk
           signing-secret signing-public owner key-epoch key-envelopes
           max-chunk-bytes]
    :as context}]
  (let [{:keys [revision head]} (head-snapshot head-store owner)
        workspace (workspace-snapshot workspace-root owner)]
    (when-not workspace
      (throw (ex-info "editable user workspace is missing"
                      {:type :repository-storage/workspace-missing
                       :owner owner})))
    (let [owner-dir (:owner-dir workspace)
          journal-file (io/file owner-dir ".publish-pending.edn")
          current (if head
                    (:state (hydrate-head context head))
                    (:state workspace))
          base (or (:base workspace) current)
          _ (when-let [basis-cid (get-in workspace [:basis :basis/cid])]
              (when-not (= basis-cid (semantic-cid base))
                (throw (ex-info "retained base projection differs from basis metadata"
                                {:type :repository-storage/invalid-basis
                                 :basis/cid basis-cid
                                 :base/cid (semantic-cid base)}))))
          prepared
          (if (.isFile journal-file)
            (let [journal (journal->preparation
                           (Files/readAllBytes (.toPath journal-file)))]
              (when-not (= (:semantic/cid journal)
                           (semantic-cid (:state workspace)))
                (throw (ex-info "pending publish differs from workspace"
                                {:type :repository-storage/pending-workspace-mismatch})))
              (assoc journal :state (:state workspace)))
            (let [value (prepare-publication
                         {:provider provider :vmk vmk
                          :signing-secret signing-secret :owner owner
                          :key-epoch key-epoch
                          :key-envelopes key-envelopes
                          :max-chunk-bytes (or max-chunk-bytes
                                               default-max-chunk-bytes)
                          :base base :candidate (:state workspace)
                          :current current :basis-cid (semantic-cid base)
                          :previous-head head})]
              (atomic-write-bytes! journal-file
                                   (preparation->journal value))
              value))
          result (publish-prepared!
                  {:transport transport :head-store head-store
                   :provider provider :signing-public signing-public
                   :owner owner :expected-revision revision}
                  prepared)
          new-revision (:revision (:receipt result))]
      (write-workspace! workspace-root datalad-root owner (:state result)
                        (:head result) new-revision)
      (Files/deleteIfExists (.toPath journal-file))
      result)))

(defn hydrate-workspace!
  [{:keys [workspace-root datalad-root owner] :as context}]
  (if-let [hydrated (hydrate-current context)]
    (do
      (write-workspace! workspace-root datalad-root owner (:state hydrated)
                        (:head hydrated) (:head/revision hydrated))
      hydrated)
    (throw (ex-info "no published head exists for this user"
                    {:type :repository-storage/head-missing :owner owner}))))

(defn migrate-legacy-state!
  "Materialize the old whole `state.edn` as one explicit user's editable
  workspace. Publication remains a separate command so migration cannot push
  merely by reading the legacy file."
  [{:keys [workspace-root datalad-root owner]} legacy-file]
  (let [state (validate-state! (read-safe-edn-file legacy-file))]
    (write-workspace! workspace-root datalad-root owner state
                      {:head/cid nil} 0)))
