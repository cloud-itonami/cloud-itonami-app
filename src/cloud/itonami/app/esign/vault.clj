(ns cloud.itonami.app.esign.vault
  "Erasure by key destruction, and why `dissoc` was not it.

  ## What the previous `forget-content!` actually did

  It removed the outline from the state map. That is a deletion in the sense
  that the running process stops returning it, and it is not one in any sense a
  regulator or a disk means:

  - `cloud.itonami.app.store` persists by writing a new `state.edn` and moving
    it over the old. The old file's blocks are unlinked, not overwritten.
  - `documents/source-bytes` reads from an object store that still holds every
    version of the document the outline was made from.
  - Anything already replicated — a backup, a `state.edn` copied to another
    machine — is untouched.

  So the honest description of the old behaviour is **redaction from the live
  view**, and this namespace exists because that is not what a deletion request
  under 個人情報保護法 or GDPR Article 17 asks for.

  ## Crypto-shredding, and what it actually promises

  The outline is stored encrypted under a key that exists in exactly one place:
  a per-envelope data key in the app's key file. `forget!` destroys the key.
  Every copy of the ciphertext — in an old `state.edn`, in a backup, on a disk
  block nobody overwrote — becomes undecryptable at once.

  The promise is precise and worth not overstating: **the ciphertext still
  exists everywhere it existed before, and it is unreadable without a key that
  no longer exists.** That is a stronger claim than `dissoc` and a weaker one
  than physical destruction. It is the claim that is achievable when copies
  cannot be recalled, which is the situation any replicated store creates.

  It also fails in one way worth naming: an adversary who copied the KEY before
  the deletion can still read the ciphertext. Key destruction protects against
  future access to old media, not against an attacker who was already inside.

  ## AES-256-GCM, and the nonce rule that makes it safe

  A GCM nonce reused under one key destroys the key's security entirely — not
  gradually. So a key here encrypts **exactly one plaintext**, generated fresh
  per envelope, and there is no update path that re-encrypts under the same key.
  A revised outline is a new envelope, which is already true for a different
  reason: an envelope freezes one version of a document.

  The envelope id is the AAD, so a ciphertext moved to another envelope's record
  fails to authenticate rather than decrypting into it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config])
  (:import [java.nio.charset StandardCharsets]
           [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def schema "cloud.itonami.app.esign.vault.v1")

(def ^:private key-bytes 32)
(def ^:private nonce-bytes 12)
(def ^:private tag-bits 128)

(defn- keys-file [] (io/file (config/data-dir) "esign-keys.edn"))

(defn- read-keys []
  (let [f (keys-file)]
    (if (.isFile f) (edn/read-string (slurp f)) {})))

(defn- write-keys! [m]
  (let [f (keys-file)
        temporary (io/file (.getParentFile f) "esign-keys.edn.tmp")]
    (.mkdirs (.getParentFile f))
    (spit temporary (pr-str m))
    ;; Restrict before the rename, so the file is never briefly world-readable
    ;; under its final name.
    (doto temporary
      (.setReadable false false) (.setReadable true true)
      (.setWritable false false) (.setWritable true true))
    (.renameTo temporary f)
    m))

(defonce ^:private lock (Object.))

(defn- ->b64 [^bytes b] (.encodeToString (Base64/getEncoder) b))
(defn- <-b64 [^String s] (.decode (Base64/getDecoder) s))

(defn- random-bytes [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn- cipher [mode ^bytes key ^bytes nonce ^String aad]
  (doto (Cipher/getInstance "AES/GCM/NoPadding")
    (.init (int mode)
           (SecretKeySpec. key "AES")
           (GCMParameterSpec. tag-bits nonce))
    (.updateAAD (.getBytes aad StandardCharsets/UTF_8))))

(defn seal!
  "Encrypt `plaintext` under a fresh key recorded against `envelope-id`.

  Returns the ciphertext record to store beside the envelope. The key is NOT in
  it — that is the entire point, and a caller that logged the return value would
  be logging something safe."
  [envelope-id ^String plaintext]
  (locking lock
    (let [key (random-bytes key-bytes)
          nonce (random-bytes nonce-bytes)
          ciphertext (.doFinal (cipher Cipher/ENCRYPT_MODE key nonce envelope-id)
                               (.getBytes plaintext StandardCharsets/UTF_8))]
      (write-keys! (assoc (read-keys) envelope-id (->b64 key)))
      {:vault/schema schema
       :vault/algorithm "AES-256-GCM"
       :vault/nonce (->b64 nonce)
       :vault/ciphertext (->b64 ciphertext)})))

(defn open
  "Decrypt, or nil when the key is gone.

  nil rather than an exception: a shredded envelope is an ordinary state of the
  world after a deletion request, and a caller has to render it rather than
  fail. `shredded?` distinguishes it from an envelope that never had content."
  [envelope-id {:vault/keys [nonce ciphertext]}]
  (when-let [key (get (read-keys) envelope-id)]
    (try
      (String. (.doFinal (cipher Cipher/DECRYPT_MODE (<-b64 key) (<-b64 nonce) envelope-id)
                         (<-b64 ciphertext))
               StandardCharsets/UTF_8)
      (catch Exception _
        ;; Authentication failure. The AAD is the envelope id, so this is what a
        ;; ciphertext moved from another envelope looks like — and it must read
        ;; as absent rather than as a decryption that happened to fail.
        nil))))

(defn shredded?
  "Whether this envelope had a key and no longer does."
  [envelope-id sealed]
  (boolean (and sealed (nil? (get (read-keys) envelope-id)))))

(defn forget!
  "Destroy the key. Irreversible, and that is the requirement.

  Idempotent: a deletion request repeated is the same state, and a second call
  must not fail for a caller retrying."
  [envelope-id]
  (locking lock
    (let [current (read-keys)]
      (when (contains? current envelope-id)
        (write-keys! (dissoc current envelope-id)))
      {:envelope-id envelope-id :shredded? true})))

(defn key-count
  "How many envelope keys exist. For an operator checking that a deletion
  actually reduced something."
  []
  (count (read-keys)))
