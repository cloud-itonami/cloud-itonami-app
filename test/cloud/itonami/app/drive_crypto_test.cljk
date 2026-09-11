(ns cloud.itonami.app.drive-crypto-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.drive-crypto :as crypto])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)))

(defn- with-temp-keys [f]
  (let [dir (.toFile (Files/createTempDirectory "drive-crypto-test"
                                                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [config/data-dir (constantly (.getPath dir))]
      (f))))

(deftest encrypted-package-round-trips-and-tampering-fails
  (with-temp-keys
    (fn []
      (let [plaintext (mapv int (.getBytes "Kotobase cannot read this"
                                       StandardCharsets/UTF_8))
            sealed (crypto/seal "alice" "drive:test:v1" plaintext)
            package (edn/read-string
                     (String. (byte-array (map unchecked-byte sealed))
                              StandardCharsets/UTF_8))
            chunk (first (:kotoba.drive.encrypted/chunks package))
            changed-package (assoc package :kotoba.drive.encrypted/chunks
                                   [(str (if (= \A (first chunk)) \B \A)
                                         (subs chunk 1))])
            changed (mapv int (.getBytes (pr-str changed-package)
                                         StandardCharsets/UTF_8))]
        (is (crypto/encrypted? sealed))
        (is (not= plaintext sealed))
        (is (= plaintext (crypto/open "alice" sealed)))
        (is (thrown? Exception (crypto/open "alice" changed)))
        (is (= #{"OWNER_READ" "OWNER_WRITE"}
               (set (map str (Files/getPosixFilePermissions
                              (.toPath (crypto/key-file "alice"))
                              (make-array java.nio.file.LinkOption 0))))))))))

(deftest sharing-rewraps-only-the-content-key
  (with-temp-keys
    (fn []
      (crypto/ensure-keypair! "bob")
      (let [sealed (crypto/seal "alice" "drive:share:v1" [1 2 3 4])
            before (edn/read-string (String. (byte-array (map unchecked-byte sealed))
                                             StandardCharsets/UTF_8))
            shared (crypto/grant sealed "alice" "bob")
            after (edn/read-string (String. (byte-array (map unchecked-byte shared))
                                            StandardCharsets/UTF_8))]
        (is (= [1 2 3 4] (crypto/open "bob" shared)))
        (testing "ciphertext chunks are byte-identical after sharing"
          (is (= (:kotoba.drive.encrypted/chunks before)
                 (:kotoba.drive.encrypted/chunks after))))
        (is (= 2 (count (:envelope/recipients
                         (:kotoba.drive.encrypted/envelope after)))))))))

(deftest missing-recipient-key-fails-closed-and-legacy-reads
  (with-temp-keys
    (fn []
      (let [sealed (crypto/seal "alice" "drive:missing:v1" [9 8 7])]
        (is (= [5 4 3] (crypto/open "legacy" [5 4 3])))
        (is (= :drive/recipient-key-missing
               (:type (ex-data
                       (try (crypto/grant sealed "alice" "bob")
                            (catch Exception e e))))))))))

(deftest link-secret-opens-content-but-is-not-in-the-package
  (with-temp-keys
    (fn []
      (let [sealed (crypto/seal "alice" "drive:link:v1" [7 6 5])
            {:keys [bytes grant]} (crypto/mint-link sealed "alice")
            package-text (String. (byte-array (map unchecked-byte bytes))
                                  StandardCharsets/UTF_8)]
        (is (= [7 6 5] (crypto/open-link bytes grant)))
        (is (= :url-fragment (:grant/placement grant)))
        (is (not (.contains package-text (:grant/secret grant))))
        (let [{revoked :bytes} (crypto/revoke bytes (:grant/recipient-id grant))]
          (is (thrown? Exception (crypto/open-link revoked grant))))))))
