(ns cloud.itonami.app.awai-envelope
  "Independently verify canonical Awai app.registry entry envelopes.

  A local peer's accepted-state projection is only a discovery source. This
  verifier rechecks the entry CID, Awai envelope signature, registry DNA,
  signer-to-record identity, and equality between signed bytes and projection."
  (:require [clojure.set :as set]
            [ed25519.core :as ed]
            [ipld.core :as ipld]
            [kotoba.bytes :as kbytes]
            [kotoba.protocol.cid :as cid]
            [multiformats.core :as mf]))

(def maximum-entry-bytes 4096)
(def ^:private maximum-safe-integer 9007199254740991)
(def ^:private entry-domain "awai/dna/entry/v1")
(def ^:private supported-entry-types #{"app/release" "app/revoke" "domain/bind" "content/origin"})
(def ^:private invalid ::invalid)

(def ^:private body-specs
  {"app/release"
   {:required #{"app-id" "manifest-cid" "publisher-did" "channel" "sequence"}
    :optional #{"content-origin-cids" "previous-release-cid"}
    :links #{"manifest-cid" "previous-release-cid"}
    :nullable-links #{"previous-release-cid"}
    :link-vectors #{"content-origin-cids"}}
   "app/revoke"
   {:required #{"app-id" "publisher-did" "release-cid" "sequence" "reason-code" "issued-at"}
    :optional #{"previous-revoke-cid"}
    :links #{"release-cid" "previous-revoke-cid"}
    :nullable-links #{"previous-revoke-cid"}}
   "domain/bind"
   {:required #{"domain" "app-id" "publisher-did" "channel"
                "domain-owner-did" "sequence" "expires-at"}
    :optional #{"previous-binding-cid"}
    :links #{"previous-binding-cid"}
    :nullable-links #{"previous-binding-cid"}}
   "content/origin"
   {:required #{"app-id" "publisher-did" "operator-did" "host"
                "spki-sha256" "expires-at"}
    :optional #{}
    :byte-hex #{"spki-sha256"}}})

(defn- byte-array? [value]
  #?(:clj (bytes? value)
     :cljs (instance? js/Uint8Array value)))

(defn- byte-count [value]
  #?(:clj (alength value)
     :cljs (.-length value)))

(defn- safe-integer? [value maximum]
  (and (integer? value) (<= 0 value maximum)))

(defn- unsigned-bytes [value]
  (mapv #(bit-and (int %) 0xff) (seq value)))

(defn- bytes-from-unsigned [values]
  #?(:clj (byte-array (map unchecked-byte values))
     :cljs (js/Uint8Array.from (clj->js values))))

(defn- bytes->hex [value]
  (apply str (map (fn [b]
                    (let [text (.toString (bit-and (int b) 0xff) 16)]
                      (if (= 1 (count text)) (str "0" text) text)))
                  (seq value))))

(defn- hex->bytes [text]
  (let [digits (mapv #?(:clj (fn [pair] (Integer/parseInt pair 16))
                       :cljs (fn [pair] (js/parseInt pair 16)))
                     (map #(apply str %) (partition 2 text)))]
    (bytes-from-unsigned digits)))

(defn- valid-cid? [value]
  (let [{:keys [codec error]} (when (string? value) (cid/parse-cid value))]
    (and (not error) (contains? #{:raw :dag-cbor} codec))))

(defn- cid-bytes [value]
  (when (valid-cid? value)
    (some-> (cid/base32-decode (subs value 1))
            bytes-from-unsigned)))

(defn- concat-bytes [& parts]
  #?(:clj
     (let [out (java.io.ByteArrayOutputStream.)]
       (doseq [part parts]
         (.write out ^bytes part 0 (alength ^bytes part)))
       (.toByteArray out))
     :cljs
     (let [out (js/Uint8Array. (reduce + (map byte-count parts)))]
       (loop [remaining parts offset 0]
         (when-let [part (first remaining)]
           (.set out part offset)
           (recur (rest remaining) (+ offset (byte-count part)))))
       out)))

(defn- agent-did [agent]
  (when (and (byte-array? agent) (= 32 (byte-count agent)))
    (str "did:key:z"
         (mf/base58btc (vec (concat [237 1] (unsigned-bytes agent)))))))

(defn- link-cid [value nullable?]
  (cond
    (and nullable? (nil? value)) ""
    (ipld/link? value) (let [value (ipld/link-cid value)]
                         (if (valid-cid? value) value invalid))
    :else invalid))

(defn- project-body [type body]
  (let [{:keys [required optional links nullable-links link-vectors byte-hex]}
        (get body-specs type)
        keys* (when (map? body) (set (keys body)))
        allowed (into required optional)]
    (when (and (map? body)
               (every? string? keys*)
               (set/subset? required keys*)
               (set/subset? keys* allowed))
      (let [projected
            (reduce (fn [out key]
                      (let [value (get body key)
                            value (cond
                                    (contains? links key)
                                    (link-cid value (contains? nullable-links key))

                                    (contains? link-vectors key)
                                    (if (and (vector? value)
                                             (every? ipld/link? value))
                                      (mapv #(link-cid % false) value)
                                      invalid)

                                    (contains? byte-hex key)
                                    (if (and (byte-array? value)
                                             (= 32 (byte-count value)))
                                      (bytes->hex value)
                                      invalid)

                                    :else value)]
                        (if (= invalid value)
                          (reduced invalid)
                          (assoc out (keyword key) value))))
                    {} (sort keys*))]
        (when-not (= invalid projected) projected)))))

(defn verify-record
  "Return `{:ok? true :signer-did ...}` only when bytes independently prove
  this projected record. Failure reasons are stable keywords and never expose
  the signed envelope or key bytes."
  [registry-dna-cid record]
  (try
    (let [envelope-hex (:envelope-hex record)
          envelope-bytes (when (and (string? envelope-hex)
                                    (<= 2 (count envelope-hex) (* 2 maximum-entry-bytes))
                                    (even? (count envelope-hex))
                                    (re-matches #"[0-9a-f]+" envelope-hex))
                           (hex->bytes envelope-hex))]
      (cond
        (not (valid-cid? registry-dna-cid))
        {:ok? false :reason :invalid-registry-dna-cid}

        (nil? envelope-bytes)
        {:ok? false :reason :invalid-envelope-hex}

        (> (byte-count envelope-bytes) maximum-entry-bytes)
        {:ok? false :reason :entry-too-large}

        (not= (:cid record) (ipld/cid envelope-bytes))
        {:ok? false :reason :entry-cid-mismatch}

        :else
        (let [envelope (ipld/decode envelope-bytes)
              entry-type (get envelope "type")
              agent (get envelope "agent")
              signer (agent-did agent)
              signature (get envelope "sig")
              dna-link (get envelope "dna")
              dna (when (ipld/link? dna-link) (ipld/link-cid dna-link))
              previous (get envelope "prev")
              body (project-body entry-type (get envelope "body"))
              unsigned (ipld/encode (dissoc envelope "sig"))
              payload (when-let [dna-wire (cid-bytes dna)]
                        (concat-bytes (kbytes/utf8-bytes entry-domain)
                                      dna-wire unsigned))
              signature-ok? (and signer
                                 (byte-array? signature)
                                 (= 64 (byte-count signature))
                                 payload
                                 (try (boolean (ed/verify-did signer payload signature))
                                      (catch #?(:clj Exception :cljs :default) _ false)))
              identity-ok? (case entry-type
                             "app/release" (= signer (:publisher-did body))
                             "app/revoke" (= signer (:publisher-did body))
                             "domain/bind" (= signer (:domain-owner-did body))
                             "content/origin" (= signer (:operator-did body))
                             false)]
          (cond
            (not (and (map? envelope)
                      (= #{"v" "dna" "agent" "seq" "prev" "ts" "type" "body" "sig"}
                         (set (keys envelope)))
                      (= 1 (get envelope "v"))
                      (contains? supported-entry-types entry-type)
                      (or (nil? previous) (ipld/link? previous))
                      (safe-integer? (get envelope "seq") maximum-safe-integer)
                      (safe-integer? (get envelope "ts") 4102444800)
                      (map? (get envelope "body"))))
            {:ok? false :reason :invalid-envelope-shape}

            (not= registry-dna-cid dna)
            {:ok? false :reason :wrong-registry-dna}

            (or (not (map? body))
                (not= body (:body record)))
            {:ok? false :reason :projection-body-mismatch}

            (or (not= entry-type (:type record))
                (not= (get envelope "seq") (:sequence record)))
            {:ok? false :reason :projection-envelope-mismatch}

            (not signature-ok?)
            {:ok? false :reason :invalid-envelope-signature}

            (not identity-ok?)
            {:ok? false :reason :signer-identity-mismatch}

            (and (= "app/revoke" entry-type)
                 (not (safe-integer? (:issued-at body) maximum-safe-integer)))
            {:ok? false :reason :invalid-revocation-time}

            (and (= "app/revoke" entry-type)
                 (> (:issued-at body) (get envelope "ts")))
            {:ok? false :reason :revocation-time-after-envelope}

            :else
            {:ok? true :signer-did signer}))))
    (catch #?(:clj Exception :cljs :default) _
      {:ok? false :reason :invalid-envelope})))
