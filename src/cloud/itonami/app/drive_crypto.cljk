(ns cloud.itonami.app.drive-crypto
  "Client-side encrypted packages for Drive object stores.

  Packages contain public envelope metadata and ciphertext chunks. Private
  recipient keys live in one exact local file per principal; this namespace
  never enumerates the key directory."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.secure-file :as secure-file]
            [envelope.model :as envelope-model]
            [envelope.seal-jvm :as envelope])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files StandardCopyOption)

           (java.security MessageDigest)
           (java.util UUID)))

(def package-version 1)
(def ^:private marker :kotoba.drive.encrypted/version)

(defn- as-bytes ^bytes [value]
  (if (bytes? value) value (byte-array (map unchecked-byte value))))

(defn- bytes-vector [^bytes value]
  (mapv #(bit-and (int %) 0xff) value))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- key-dir [] (io/file (config/data-dir) "drive-keys"))

(defn key-file
  "The one key file for `principal`. Public for exact-target diagnostics."
  [principal]
  (io/file (key-dir) (str (sha256-hex principal) ".edn")))

(defn- write-key! [principal {:keys [priv pub]}]
  (let [dir (.toPath (key-dir))
        target (.toPath (key-file principal))
        tmp (.resolve dir (str "." (.getFileName target) "." (UUID/randomUUID)))]
    (Files/createDirectories dir (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString tmp
                       (pr-str {:key/version 1
                                :key/principal principal
                                :key/private (envelope/b64url priv)
                                :key/public (envelope/b64url pub)})
                       StandardCharsets/UTF_8
                       (make-array java.nio.file.OpenOption 0))
    (secure-file/harden! tmp "rw-------")
    (Files/move tmp target
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    {:priv priv :pub pub :id principal}))

(defn keypair
  "Read exactly one principal's local key, or nil."
  [principal]
  (let [file (key-file principal)]
    (when (.isFile file)
      (let [stored (edn/read-string (slurp file))]
        (when-not (and (= 1 (:key/version stored))
                       (= principal (:key/principal stored)))
          (throw (ex-info "Drive key file does not match its principal"
                          {:type :drive/key-invalid :principal principal})))
        {:id principal
         :priv (envelope/unb64url (:key/private stored))
         :pub (envelope/unb64url (:key/public stored))}))))

(defn ensure-keypair! [principal]
  (or (keypair principal)
      (write-key! principal (envelope/generate-recipient))))

(defn public-key [principal]
  (some-> (keypair principal) :pub envelope/b64url))

(defn- parse [bytes]
  (edn/read-string (String. (as-bytes bytes) StandardCharsets/UTF_8)))

(defn encrypted?
  "True only for a recognized package. Arbitrary EDN remains plaintext."
  [bytes]
  (try (= package-version (marker (parse bytes)))
       (catch Exception _ false)))

(defn- read-package [bytes]
  (let [package (parse bytes)]
    (when-not (and (= package-version (marker package))
                   (envelope-model/valid? (:kotoba.drive.encrypted/envelope package))
                   (vector? (:kotoba.drive.encrypted/chunks package)))
      (throw (ex-info "Invalid encrypted Drive package"
                      {:type :drive/encrypted-package-invalid})))
    package))

(defn- write-package [package]
  (bytes-vector (.getBytes (pr-str package) StandardCharsets/UTF_8)))

(defn seal-for
  "Encrypt plaintext to each local principal in `principals`."
  [principals envelope-id plaintext]
  (let [principals (vec (distinct principals))
        recipients (mapv (fn [principal]
                           (let [{:keys [pub]} (ensure-keypair! principal)]
                             {:id principal :pub pub}))
                         principals)
        {:keys [envelope chunks]}
        (envelope/seal-object envelope-id [(as-bytes plaintext)]
                              recipients)]
    (write-package
     {marker package-version
      :kotoba.drive.encrypted/envelope envelope
      :kotoba.drive.encrypted/chunks (mapv envelope/b64url chunks)})))

(defn seal [principal envelope-id plaintext]
  (seal-for [principal] envelope-id plaintext))

(defn open
  "Decrypt for principal. Legacy plaintext passes through for migration."
  [principal bytes]
  (if-not (encrypted? bytes)
    (bytes-vector (as-bytes bytes))
    (let [package (read-package bytes)
          env (:kotoba.drive.encrypted/envelope package)
          kp (keypair principal)
          entry (envelope/entry-for env principal)]
      (when-not (and kp entry)
        (throw (ex-info "No local recipient key for encrypted Drive object"
                        {:type :drive/key-unavailable :principal principal
                         :envelope-id (:envelope/id env)})))
      (-> (envelope/open-object env entry (:priv kp)
                                (mapv envelope/unb64url
                                      (:kotoba.drive.encrypted/chunks package)))
          first bytes-vector))))

(defn open-link
  "Decrypt with a link grant kept in a URL fragment. Neither the private link
  key nor this grant is intended to be sent to the storage origin."
  [package-bytes {:keys [grant/recipient-id grant/secret]}]
  (let [package (read-package package-bytes)
        env (:kotoba.drive.encrypted/envelope package)
        entry (envelope/entry-for env recipient-id)]
    (when-not (and entry secret)
      (throw (ex-info "Encrypted Drive link grant is unavailable"
                      {:type :drive/link-key-unavailable
                       :recipient-id recipient-id})))
    (-> (envelope/open-object env entry secret
                              (mapv envelope/unb64url
                                    (:kotoba.drive.encrypted/chunks package)))
        first bytes-vector)))

(defn grant
  "Add recipient without changing ciphertext. Recipient must have a key."
  [package-bytes owner recipient]
  (let [package (read-package package-bytes)
        env (:kotoba.drive.encrypted/envelope package)
        owner-key (keypair owner)
        recipient-pub (some-> (keypair recipient) :pub)]
    (when-not owner-key
      (throw (ex-info "Owner Drive key is unavailable"
                      {:type :drive/key-unavailable :principal owner})))
    (when-not recipient-pub
      (throw (ex-info "Recipient must activate encrypted Drive first"
                      {:type :drive/recipient-key-missing :principal recipient})))
    (let [updated (envelope/share-with env (envelope/entry-for env owner)
                                       (:priv owner-key)
                                       {:id recipient :pub recipient-pub})]
      (write-package (assoc package :kotoba.drive.encrypted/envelope updated)))))

(defn revoke [package-bytes recipient]
  (let [package (read-package package-bytes)
        result (envelope-model/revoke (:kotoba.drive.encrypted/envelope package)
                                      recipient)]
    {:bytes (write-package (assoc package :kotoba.drive.encrypted/envelope
                                  (:envelope result)))
     :requires-rotation? (:requires-rotation? result)}))

(defn mint-link
  "Add a link recipient. Grant secret is returned once for a URL fragment."
  [package-bytes owner]
  (let [package (read-package package-bytes)
        env (:kotoba.drive.encrypted/envelope package)
        owner-key (keypair owner)
        result (envelope/mint-link env (envelope/entry-for env owner)
                                   (:priv owner-key))]
    {:bytes (write-package (assoc package :kotoba.drive.encrypted/envelope
                                  (:envelope result)))
     :grant (:grant result)}))
