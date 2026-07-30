(ns cloud.itonami.app.esign.commitment
  "What a signer actually signs, and the exhaustive rendering they are shown.

  ## The document is not what is signed

  A Passkey signs `authenticatorData || SHA-256(clientDataJSON)` and nothing
  else — `cloud.itonami.app.capability` and `cloud.itonami.app.credential` both
  record that already, as the reason neither a CACAO nor a Data Integrity proof
  can come out of WebAuthn. For a signature over a *document* that limitation is
  not an obstacle, because `clientDataJSON` carries the **challenge**, and a
  challenge is 32 bytes of the relying party's choosing.

  So the challenge is `SHA-256(JCS(commitment))`, and the commitment names the
  document by digest. The authenticator's signature therefore covers the
  document digest transitively, with nothing in between that this server could
  change afterwards.

  **This is the part that is easy to get subtly wrong, so it is worth naming
  what the alternative would have been.** `passkey/start-authorization!` stores
  its operation context server-side and lets the library generate a random
  challenge. That is enough to stop a *client* from swapping the operation, and
  it is right for a PSBT approval, where this server is the only party who will
  ever re-check. It is NOT enough for a signature that is meant to be evidence:
  a third party holding the response has no way to tell which document it was
  about, because the signed bytes do not mention one. The binding would rest on
  this server's own record of it — which is exactly the thing a signature is
  supposed to make unnecessary.

  A prior art note, because it was measured rather than assumed: the
  `kotoba-lang/esign` lexicons (etzhayyim, Phase 0) define
  `challenge = sha256(envelopeUri || signerDid || nonce)` and carry
  `documentSha256` as a *separate* field. That has the same gap — the assertion
  binds an envelope URI, and the document is attached to the URI by a record
  only the server can vouch for. Putting the digest inside the hashed
  commitment is the fix, and it costs nothing.

  ## Why an exhaustive outline rather than a rendering

  A signature is worth what the signer's view of the document was worth, so
  something has to be shown, and its digest has to be in the commitment. The
  obvious answer is a typeset rendering — but a typeset rendering is the wrong
  primitive here, and not because writing one is work.

  These documents are structured data: a Sheets workbook, a Docs tree, a Forms
  definition. The hazard is not that the signer sees the wrong *font*, it is
  that they see a **subset** — a cell outside the visible range, a collapsed
  block, a field whose stored value differs from its displayed one. A page
  faithfully typeset from the resource still hides all of those. An exhaustive,
  order-stable outline cannot: every scalar in the resource appears in it,
  because that is its construction.

  So `outline` is not a poor substitute for a renderer. It is the thing that
  makes \"there is nothing in this document you were not shown\" a property of
  the format rather than a claim about the UI. It is deliberately plain text.

  Determinism matters for a second reason. `pr-str` of a Clojure map is NOT
  order-stable — above eight entries a map becomes a hash map and the print
  order follows hashing — so digesting a re-serialization of a resource would
  produce a value that depended on nothing the signer could see. `outline`
  sorts, and the digest of the *stored bytes* is taken from the bytes as
  stored, never from a re-serialization.

  Everything here is pure: no store, no clock, no network, no crypto keys."
  (:require [clojure.string :as str]
            [jcs.core :as jcs])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def schema "cloud.itonami.app.esign.commitment.v1")

(def purposes
  "Why a signature is being asked for, and the sentence that goes in the
  commitment when the caller does not write its own.

  A closed set, because `purpose` is what a later verifier compares against
  when deciding whether this signature covers what it is being offered for —
  the same reason `data-integrity.core/verify` requires an expected proof
  purpose rather than accepting whatever the proof claims. A free-text purpose
  would make that comparison a string match against something a caller made
  up."
  {:contract/execute "本契約の内容を確認し、これに署名して合意します。"
   :consent/give "本書面の内容に同意します。"
   :minutes/approve "本議事録が会議の内容と相違ないことを確認します。"
   :acknowledgement/receive "本書面を受領したことを確認します。"
   :application/submit "本申請の内容が事実であることを確認し、提出します。"})

;; ── digests ──────────────────────────────────────────────────────────────────

(def ^:private hex-digits "0123456789abcdef")

(defn hex
  "Lowercase hex. Written out rather than pulled from a formatter because
  `String/format` with `%02x` allocates per byte and this runs over documents."
  [^bytes value]
  (let [out (StringBuilder. (* 2 (alength value)))]
    (dotimes [i (alength value)]
      (let [b (bit-and (aget value i) 0xff)]
        (doto out
          (.append (.charAt hex-digits (bit-shift-right b 4)))
          (.append (.charAt hex-digits (bit-and b 0x0f))))))
    (str out)))

(defn byte-array-of
  "A `byte[]` from either a `byte[]` or `drive`'s vector of unsigned ints.

  `drive.object` is portable `.cljc` and hands back a vector of ints in
  0–255 — `documents/envelope-bytes` says so — while `jcs/canonicalize-bytes`
  and `MessageDigest` deal in `byte[]`. Accepting both here rather than at each
  call site is the difference between one conversion and a
  `ClassCastException` at the first store that is not the one the caller had in
  mind. (Measured: that exception, on the first run of the envelope tests.)"
  ^bytes [value]
  (if (bytes? value)
    value
    (byte-array (map unchecked-byte value))))

(defn sha256
  "The raw 32 bytes."
  ^bytes [value]
  (.digest (MessageDigest/getInstance "SHA-256") (byte-array-of value)))

(defn digest-of
  "`sha256:<hex>` — a multihash-shaped string, self-describing so that a
  verifier reading an evidence record years from now does not have to infer
  which function produced it from the length."
  [value]
  (str "sha256:" (hex (sha256 value))))

(defn digest-of-string [^String value]
  (digest-of (.getBytes value StandardCharsets/UTF_8)))

(defn digest-hex
  "The hex half of a `sha256:…` string, or nil when it is not one."
  [value]
  (when (and (string? value) (str/starts-with? value "sha256:"))
    (subs value (count "sha256:"))))

;; ── the exhaustive outline ───────────────────────────────────────────────────

(defn- scalar-text [value]
  (cond
    (nil? value) "nil"
    (string? value) value
    (keyword? value) (subs (str value) 1)
    (boolean? value) (str value)
    :else (pr-str value)))

(defn- segment-text [value]
  (if (keyword? value) (subs (str value) 1) (pr-str value)))

(defn outline
  "Every scalar in `resource`, one per line, in an order that depends only on
  the resource.

  `path<TAB>value`, paths joined by `/`. Maps are sorted by the printed form of
  their keys, vectors keep their index, and sets are sorted and then indexed —
  a set has no order of its own, so one is imposed rather than inherited from
  whatever the reader built.

  A tab separator rather than `: ` because a document's own text contains
  colons and a signer comparing two outlines should not have to guess where the
  path ended."
  [resource]
  (let [out (StringBuilder.)]
    (letfn [(walk [path value]
              (cond
                (map? value)
                (if (empty? value)
                  (emit path "{}")
                  (doseq [[k v] (sort-by (comp pr-str key) value)]
                    (walk (conj path (segment-text k)) v)))

                (set? value)
                (if (empty? value)
                  (emit path "#{}")
                  (doseq [[i v] (map-indexed vector (sort-by pr-str value))]
                    (walk (conj path (str "#" i)) v)))

                (sequential? value)
                (if (empty? value)
                  (emit path "[]")
                  (doseq [[i v] (map-indexed vector value)]
                    (walk (conj path (str i)) v)))

                :else (emit path (scalar-text value))))
            (emit [path text]
              (doto out
                (.append (str/join "/" path))
                (.append \tab)
                (.append text)
                (.append \newline)))]
      (walk [] resource))
    (str out)))

;; ── the commitment ───────────────────────────────────────────────────────────

(defn- required! [value field]
  (when (str/blank? (str value))
    (throw (ex-info (str "電子署名の commitment に " (name field) " が必要です。")
                    {:type :esign/incomplete-commitment :field field})))
  (str value))

(defn commitment
  "The map whose JCS canonicalization is hashed into the WebAuthn challenge.

  String keys and a fixed vocabulary, because `jcs.core/canonicalize` is what
  makes this reproducible by somebody who is not this process, and RFC 8785
  canonicalizes JSON — a keyword key would be canonicalized as whatever it
  prints as, which is this ecosystem's convention rather than the wire's.

  Both digests are required and neither has a default. A commitment without
  `documentDigest` binds no document; one without `presentationDigest` binds no
  view of it, which means the signature says nothing about what the signer
  read. Refusing is the only honest answer to either."
  [{:keys [envelope-id document-id document-digest presentation-digest
           media-type signer-did purpose intent nonce role-credential-id
           organization-did]}]
  (when-not (contains? purposes purpose)
    (throw (ex-info "未知の署名目的です。"
                    {:type :esign/unknown-purpose
                     :purpose purpose
                     :known (vec (sort (map str (keys purposes))))})))
  (cond-> {"schema" schema
           "envelopeId" (required! envelope-id :envelope-id)
           "documentId" (required! document-id :document-id)
           "documentDigest" (required! document-digest :document-digest)
           "presentationDigest" (required! presentation-digest :presentation-digest)
           "mediaType" (required! media-type :media-type)
           "signerDid" (required! signer-did :signer-did)
           "purpose" (subs (str purpose) 1)
           "intent" (required! (or (not-empty (str/trim (str intent)))
                                   (get purposes purpose))
                               :intent)
           "nonce" (required! nonce :nonce)}
    (not (str/blank? (str role-credential-id)))
    (assoc "roleCredentialId" (str role-credential-id))

    (not (str/blank? (str organization-did)))
    (assoc "organization" (str organization-did))))

(defn canonical-bytes
  "The exact bytes hashed into the challenge. Exposed so a test — and a
  verifier written in another language — can compare them."
  ^bytes [commitment]
  (jcs/canonicalize-bytes commitment))

(defn challenge-bytes
  "The 32 bytes a signing ceremony puts in the WebAuthn challenge."
  ^bytes [commitment]
  (sha256 (canonical-bytes commitment)))

(defn commitment-digest
  "`sha256:<hex>` of the canonical commitment — the same value as
  `challenge-bytes`, in the form an evidence record records it."
  [commitment]
  (digest-of (canonical-bytes commitment)))
