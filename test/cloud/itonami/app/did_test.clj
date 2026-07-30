(ns cloud.itonami.app.did-test
  "did:key derivation from a WebAuthn credential public key.

  This file did not exist, and its absence was the whole problem.
  `did-key-from-cose` had never worked: Jackson deserialises CBOR integer map
  keys to strings, so every COSE label lookup returned nil, `(long kty)` threw,
  and the catch-all reported `:did/invalid-public-key`. Passkey registration in
  this app could not complete -- for any credential, on any machine, since the
  function was written -- and nothing said so until a real Touch ID enrolment was
  attempted on 2026-07-30.

  So these tests are built on a REAL CBOR-encoded COSE_Key, produced by the same
  encoder shape an authenticator uses, rather than on a hand-made map. A fixture
  that skipped the CBOR round-trip would have skipped the bug."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.did :as did])
  (:import [com.fasterxml.jackson.dataformat.cbor CBORFactory]
           [java.io ByteArrayOutputStream]
           [java.util Base64]))

(defn- cose-p256
  "A CBOR COSE_Key for an EC2/P-256 public key, base64url encoded.

  Labels are written as INTEGERS, which is what an authenticator emits and what
  the regression is about."
  [^bytes x ^bytes y & {:keys [kty crv] :or {kty 2 crv 1}}]
  (let [out (ByteArrayOutputStream.)
        g (.createGenerator (CBORFactory.) out)]
    (.writeStartObject g)
    (.writeFieldId g 1) (.writeNumber g (int kty))
    (.writeFieldId g 3) (.writeNumber g (int -7))
    (.writeFieldId g -1) (.writeNumber g (int crv))
    (.writeFieldId g -2) (.writeBinary g x)
    (.writeFieldId g -3) (.writeBinary g y)
    (.writeEndObject g)
    (.close g)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.toByteArray out))))

(defn- fill [b] (byte-array (repeat 32 (unchecked-byte b))))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

;; ---------------------------------------------------------------------------
;; the regression
;; ---------------------------------------------------------------------------

(deftest a-real-cbor-cose-key-yields-a-did
  (testing "the case that was broken: integer COSE labels through a real CBOR
            round-trip, which is what every authenticator sends"
    (let [d (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22)))]
      (is (string? d))
      (is (.startsWith ^String d "did:key:z"))
      (is (> (count d) 40)))))

(deftest the-did-is-derived-from-x-and-the-parity-of-y
  (testing "a compressed point: the prefix is 0x02 for even y, 0x03 for odd, so
            two keys sharing x but not y-parity must differ"
    (let [even-y (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22)))
          odd-y  (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x23)))]
      (is (not= even-y odd-y))))
  (testing "and it is deterministic -- the same key always gives the same DID,
            which is what makes it usable as an identity"
    (is (= (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22)))
           (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22))))))
  (testing "a different x gives a different DID"
    (is (not= (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22)))
              (did/did-key-from-cose (cose-p256 (fill 0x12) (fill 0x22)))))))

(deftest the-shape-guard-is-actually-reachable
  (testing "before the fix this could not fire: the labels never resolved, so
            (long kty) threw first and every key -- valid or not -- came back as
            :did/invalid-public-key. A guard that cannot run is not a guard."
    (is (= :did/unsupported-public-key
           (refuses #(did/did-key-from-cose
                      (cose-p256 (fill 0x11) (fill 0x22) :kty 1))))
        "kty 1 is OKP, not EC2")
    (is (= :did/unsupported-public-key
           (refuses #(did/did-key-from-cose
                      (cose-p256 (fill 0x11) (fill 0x22) :crv 8))))
        "crv 8 is not P-256")))

(deftest unreadable-input-is-refused-rather-than-crashing-a-route
  (doseq [bad ["" "not-base64url!!" "AAAA"]]
    (is (= :did/invalid-public-key (refuses #(did/did-key-from-cose bad)))
        (pr-str bad))))

;; ---------------------------------------------------------------------------
;; the coordinate check
;; ---------------------------------------------------------------------------

(deftest coordinates-must-be-32-bytes
  (is (= :did/invalid-public-key
         (refuses #(did/did-key-from-p256 (byte-array 31) (byte-array 32)))))
  (is (= :did/invalid-public-key
         (refuses #(did/did-key-from-p256 (byte-array 32) (byte-array 31)))))
  (is (string? (did/did-key-from-p256 (fill 0x11) (fill 0x22)))))

(deftest the-multicodec-prefix-is-p256-pub
  (testing "0x80 0x24 is unsigned-varint(0x1200), the p256-pub multicodec. Two
            distinct keys must therefore share a leading run -- if they did not,
            the prefix would not be in the encoding at all"
    (let [a (did/did-key-from-cose (cose-p256 (fill 0x11) (fill 0x22)))
          b (did/did-key-from-cose (cose-p256 (fill 0xaa) (fill 0x22)))]
      (is (= (subs a 0 10) (subs b 0 10))))))
