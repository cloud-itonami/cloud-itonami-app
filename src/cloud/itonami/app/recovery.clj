(ns cloud.itonami.app.recovery
  "Authenticated encrypted recovery snapshots for local installation state.

  The backup key is held in macOS Keychain, or supplied explicitly through
  CLOUD_ITONAMI_RECOVERY_KEY on non-macOS hosts. Backup files contain only an
  AES-256-GCM envelope and never contain the key."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64]
           [java.util.concurrent TimeUnit]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def envelope-magic (.getBytes "CITB1" StandardCharsets/US_ASCII))
(def associated-data
  (.getBytes "cloud.itonami.app.state-backup.v1" StandardCharsets/UTF_8))
(def keychain-service "cloud-itonami-app")
(def keychain-account "state-recovery-key-v1")
(def default-retention 5)
(def ^:dynamic *backup-enabled?* true)

(defn- random-bytes [size]
  (let [result (byte-array size)]
    (.nextBytes (SecureRandom.) result)
    result))

(defn encrypt-bytes [^bytes key-bytes ^bytes plaintext]
  (when-not (= 32 (alength key-bytes))
    (throw (ex-info "recovery key must contain exactly 32 bytes"
                    {:type :recovery/invalid-key-length})))
  (let [nonce (random-bytes 12)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        key (SecretKeySpec. key-bytes "AES")]
    (.init cipher Cipher/ENCRYPT_MODE key (GCMParameterSpec. 128 nonce))
    (.updateAAD cipher associated-data)
    (let [ciphertext (.doFinal cipher plaintext)
          result (byte-array (+ (alength envelope-magic)
                                (alength nonce)
                                (alength ciphertext)))]
      (System/arraycopy envelope-magic 0 result 0 (alength envelope-magic))
      (System/arraycopy nonce 0 result (alength envelope-magic) (alength nonce))
      (System/arraycopy ciphertext 0 result
                        (+ (alength envelope-magic) (alength nonce))
                        (alength ciphertext))
      result)))

(defn decrypt-bytes [^bytes key-bytes ^bytes envelope]
  (let [magic-size (alength envelope-magic)
        nonce-size 12]
    (when (< (alength envelope) (+ magic-size nonce-size 16))
      (throw (ex-info "recovery envelope is truncated"
                      {:type :recovery/invalid-envelope})))
    (when-not (java.util.Arrays/equals
               envelope-magic
               (java.util.Arrays/copyOfRange envelope 0 magic-size))
      (throw (ex-info "recovery envelope magic is invalid"
                      {:type :recovery/invalid-envelope})))
    (let [nonce (java.util.Arrays/copyOfRange
                 envelope magic-size (+ magic-size nonce-size))
          ciphertext (java.util.Arrays/copyOfRange
                      envelope (+ magic-size nonce-size) (alength envelope))
          cipher (Cipher/getInstance "AES/GCM/NoPadding")
          key (SecretKeySpec. key-bytes "AES")]
      (.init cipher Cipher/DECRYPT_MODE key (GCMParameterSpec. 128 nonce))
      (.updateAAD cipher associated-data)
      (.doFinal cipher ciphertext))))

(defn- run-security [arguments]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List arguments)
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 5 TimeUnit/SECONDS)]
      (when-not completed? (.destroyForcibly process))
      {:exit (when completed? (.exitValue process))
       :out (when completed? (str/trim (deref output 500 "")))})
    (catch Exception _ nil)))

(defn- keychain-get []
  (let [result (run-security
                ["security" "find-generic-password"
                 "-s" keychain-service "-a" keychain-account "-w"])]
    (when (zero? (or (:exit result) -1))
      (not-empty (:out result)))))

(defn- keychain-put! [secret]
  (let [result (run-security
                ["security" "add-generic-password" "-U"
                 "-s" keychain-service "-a" keychain-account "-w" secret])]
    (when-not (zero? (or (:exit result) -1))
      (throw (ex-info "cannot store the recovery key in macOS Keychain"
                      {:type :recovery/keychain-write-failed})))
    secret))

(defn recovery-key []
  (if-let [encoded (not-empty (System/getenv "CLOUD_ITONAMI_RECOVERY_KEY"))]
    (.decode (Base64/getDecoder) encoded)
    (when (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac")
      (let [encoded (or (keychain-get)
                        (keychain-put!
                         (.encodeToString (Base64/getEncoder)
                                          (random-bytes 32))))]
        (.decode (Base64/getDecoder) encoded)))))

(defn- backup-files [directory]
  (->> (or (.listFiles directory) (make-array java.io.File 0))
       (filter #(and (.isFile %)
                     (str/starts-with? (.getName %) "state-")
                     (str/ends-with? (.getName %) ".citb")))
       (sort-by #(.lastModified %) >)))

(defn backup!
  "Encrypt the current state bytes before replacement. Returns nil when this
  host has no configured recovery-key provider."
  [data-directory ^bytes plaintext]
  (when-let [key (when *backup-enabled?* (recovery-key))]
    (let [directory (doto (io/file data-directory "backups") .mkdirs)
          file (io/file directory
                        (str "state-" (.toEpochMilli (Instant/now)) "-"
                             (java.util.UUID/randomUUID) ".citb"))]
      (Files/write (.toPath file) (encrypt-bytes key plaintext)
                   (make-array java.nio.file.OpenOption 0))
      (doseq [old (drop default-retention (backup-files directory))]
        (Files/deleteIfExists (.toPath old)))
      file)))

(defn restore-bytes [backup-file]
  (let [key (or (recovery-key)
                (throw (ex-info "no recovery key is available"
                                {:type :recovery/key-unavailable})))]
    (decrypt-bytes key (Files/readAllBytes (.toPath (io/file backup-file))))))
