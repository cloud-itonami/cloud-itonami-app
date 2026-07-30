(ns cloud.itonami.app.esign-retention-test
  "Erasure by key destruction, the 電子帳簿保存法 search index, and the rule that
  keeps the two from cancelling each other out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [asn1.core :as asn1]
            [cloud.itonami.app.esign :as esign]
            [cloud.itonami.app.esign.retention :as retention]
            [cloud.itonami.app.esign.timestamp :as timestamp]
            [cloud.itonami.app.esign.vault :as vault]
            [cloud.itonami.app.store :as store]
            [rfc3161.core]))

(use-fixtures :each
  (fn [f]
    (store/transact! (fn [current] (assoc current :esign {:envelopes {} :retention {}})))
    (f)))

;; ── crypto-shredding ─────────────────────────────────────────────────────────

(deftest sealed-content-opens-until-the-key-is-destroyed
  (let [id (str "env-vault-" (rand-int 1000000))
        plaintext "取引先/株式会社テスト\t金額/1000000"
        sealed (vault/seal! id plaintext)]
    (testing "the stored form is ciphertext and carries no key"
      (is (= "AES-256-GCM" (:vault/algorithm sealed)))
      (is (not (str/includes? (pr-str sealed) "株式会社テスト")))
      (is (not (contains? sealed :vault/key))))

    (testing "it opens"
      (is (= plaintext (vault/open id sealed))))

    (testing "after forget! every copy of that ciphertext is unreadable"
      (vault/forget! id)
      (is (nil? (vault/open id sealed)))
      (is (vault/shredded? id sealed)))

    (testing "and forgetting twice is the same state — a retried request must not fail"
      (is (:shredded? (vault/forget! id))))))

(deftest a-ciphertext-moved-to-another-envelope-does-not-decrypt-into-it
  ;; The envelope id is the AAD. Without it, a record copied between envelopes
  ;; would decrypt and read as that envelope's content.
  (let [a (str "env-a-" (rand-int 1000000))
        b (str "env-b-" (rand-int 1000000))
        sealed-a (vault/seal! a "alice の契約")]
    (vault/seal! b "bob の契約")
    (is (= "alice の契約" (vault/open a sealed-a)))
    (is (nil? (vault/open b sealed-a))
        "authentication must fail rather than producing plaintext")
    (vault/forget! a)
    (vault/forget! b)))

(deftest each-envelope-gets-its-own-key
  ;; A GCM nonce reused under one key destroys that key's security outright, so
  ;; a key here encrypts exactly one plaintext.
  (let [ids (repeatedly 3 #(str "env-" (rand-int 1000000)))
        sealed (mapv #(vault/seal! % "same text") ids)]
    (testing "the same plaintext produces different ciphertext under each key"
      (is (= 3 (count (distinct (map :vault/ciphertext sealed))))))
    (testing "and destroying one leaves the others readable"
      (vault/forget! (first ids))
      (is (nil? (vault/open (first ids) (first sealed))))
      (is (= "same text" (vault/open (second ids) (second sealed)))))
    (doseq [id ids] (vault/forget! id))))

;; ── the retention index ──────────────────────────────────────────────────────

(def ^:private base-entry
  {:envelope-id "env-1"
   :document-digest "sha256:aa"
   :transaction-date "2026-07-30"
   :amount-minor 1650000
   :currency "JPY"
   :counterparty "株式会社ことば"
   :basis "法人税法上の保存義務（7年）"})

(deftest the-three-required-fields-are-required
  (is (some? (retention/entry base-entry)))
  (doseq [field [:transaction-date :amount-minor :counterparty]]
    (testing (str "missing " field)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"検索要件に必要な項目"
                            (retention/entry (dissoc base-entry field))))))

  (testing "and so is the operator's stated basis — retaining personal data in
            the clear is a legal position, not a default this code takes"
    (is (thrown? clojure.lang.ExceptionInfo (retention/entry (dissoc base-entry :basis)))))

  (testing "the date must be YYYY-MM-DD, because range search is string comparison"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"YYYY-MM-DD"
                          (retention/entry (assoc base-entry :transaction-date "2026/7/30"))))))

(deftest search-combines-the-three-criteria
  (let [state {:esign {:retention
                       {"a" (retention/entry (assoc base-entry :envelope-id "a"
                                                    :transaction-date "2026-03-01"
                                                    :amount-minor 100000
                                                    :counterparty "株式会社ことば"))
                        "b" (retention/entry (assoc base-entry :envelope-id "b"
                                                    :transaction-date "2026-07-30"
                                                    :amount-minor 1650000
                                                    :counterparty "GFTD 株式会社"))
                        "c" (retention/entry (assoc base-entry :envelope-id "c"
                                                    :transaction-date "2026-12-01"
                                                    :amount-minor 500000
                                                    :counterparty "株式会社ことば"))}}}]
    (testing "date range"
      (is (= #{"b" "c"} (set (map :retention/envelope-id
                                  (retention/search state {:date-from "2026-06-01"}))))))
    (testing "amount range"
      (is (= #{"a" "c"} (set (map :retention/envelope-id
                                  (retention/search state {:amount-max 500000}))))))
    (testing "counterparty, case-folded substring"
      (is (= #{"b"} (set (map :retention/envelope-id
                              (retention/search state {:counterparty "gftd"}))))))
    (testing "and all three together — which is what 検索要件 actually asks for"
      (is (= ["c"] (map :retention/envelope-id
                        (retention/search state {:date-from "2026-06-01"
                                                 :amount-max 600000
                                                 :counterparty "ことば"})))))
    (testing "no criteria returns everything, newest first"
      (is (= ["c" "b" "a"] (map :retention/envelope-id (retention/search state {})))))))

(deftest the-index-survives-erasure-and-that-is-the-design
  ;; A deletion request destroys the document a signer read. The transaction
  ;; record the law requires be retained for seven years is a different object
  ;; and the operator stated a basis for keeping it.
  (let [envelope-id "env-retained"
        sealed (vault/seal! envelope-id "契約書の全文")]
    (store/transact! assoc-in [:esign :retention envelope-id]
                     (retention/entry (assoc base-entry :envelope-id envelope-id)))
    (vault/forget! envelope-id)
    (is (nil? (vault/open envelope-id sealed)) "content is gone")
    (is (= "株式会社ことば"
           (:retention/counterparty
            (esign/retention-entry (store/snapshot) envelope-id)))
        "and the retained transaction record is still searchable")
    (testing "the counterparty is marked as the field that may be minimised later"
      (is (= #{:counterparty} retention/redactable-fields)))))

;; ── compliance is a list of gaps, never a tick ───────────────────────────────

(deftest compliance-reports-both-limbs-and-never-passes-silently
  (testing "no retention entry and no timestamp: both limbs missing"
    (let [gaps (retention/compliance-gaps {:retention-entry nil
                                           :timestamp-attestation :app-attested})]
      (is (= #{:可視性 :真実性} (set (map :limb gaps))))))

  (testing "an entry but only an app-attested time leaves 真実性 open"
    (let [gaps (retention/compliance-gaps {:retention-entry (retention/entry base-entry)
                                           :timestamp-attestation :app-attested})]
      (is (= [:真実性] (map :limb gaps)))
      (is (= :no-tamper-evidence-measure (:gap (first gaps))))))

  (testing "a VERIFIED but unaccredited TSA is still not the measure the law names"
    (let [gaps (retention/compliance-gaps {:retention-entry (retention/entry base-entry)
                                           :timestamp-attestation :tsa-attested})]
      (is (= :no-tamper-evidence-measure (:gap (first gaps))))))

  (testing "an accredited timestamp plus an entry closes both"
    (is (empty? (retention/compliance-gaps
                 {:retention-entry (retention/entry base-entry)
                  :timestamp-attestation :accredited}))))

  (testing "a 事務処理規程 closes 真実性 but is reported as what it rests on"
    (let [gaps (retention/compliance-gaps {:retention-entry (retention/entry base-entry)
                                           :timestamp-attestation :app-attested
                                           :procedure-documented? true})]
      (is (= [:relying-on-procedure] (map :gap gaps)))
      (is (= :informational (:severity (first gaps)))))))

;; ── attestation ──────────────────────────────────────────────────────────────

(deftest an-envelope-is-as-timestamped-as-its-weakest-signature
  ;; A regulator reads the envelope, not the best signature in it. Reporting the
  ;; strongest would let one accredited token launder two that are not.
  (is (= "app-attested" (esign/weakest-attestation {:esign/signatures []})))
  (is (= "accredited"
         (esign/weakest-attestation
          {:esign/signatures [{"timeAttestation" "accredited"}
                              {"timeAttestation" "accredited"}]})))
  (is (= "app-attested"
         (esign/weakest-attestation
          {:esign/signatures [{"timeAttestation" "accredited"}
                              {"timeAttestation" "app-attested"}]})))
  (is (= "tsa-attested"
         (esign/weakest-attestation
          {:esign/signatures [{"timeAttestation" "accredited"}
                              {"timeAttestation" "tsa-attested"}]})))
  (testing "a signature with no field at all is treated as the floor, not skipped"
    (is (= "app-attested"
           (esign/weakest-attestation
            {:esign/signatures [{"timeAttestation" "accredited"} {}]})))))

(deftest a-timestamp-is-verified-against-the-digest-it-should-cover
  ;; A real `openssl ts` token over sha256("kotoba esign test content\n"), from a
  ;; TSA this test's own CA issued — which is exactly the shape of an
  ;; unaccredited token and why :trusted stays false.
  (let [token-response (asn1/unhex "3082065730030201003082064e06092a864886f70d010702a082063f3082063b020103310f300d060960864801650304020105003081c8060b2a864886f70d0109100104a081b80481b53081b2020101060a2b06010401868d1f01013031300d060960864801650304020105000420404d8f2246f3a6948de6aee686a6e3d116ed2eb56a74b9b1d3df75f2130203e2020102180f32303236303733303133343935355a300a020101800201f48101640101ff02087d82213101890cc3a041a43f303d310b3009060355040613024a5031143012060355040a0c0b4b6f746f626120546573743118301606035504030c0f4b6f746f6261205465737420545341a08203e6308201f83082019ea003020102021459b29c4d07173c1b16871d7129d6213e51e1f25f300a06082a8648ce3d0403023041310b3009060355040613024a5031173015060355040a0c0e4b6f746f626120546573742043413119301706035504030c104b6f746f6261205465737420526f6f74301e170d3236303733303133313233335a170d3336303732373133313233335a303d310b3009060355040613024a5031143012060355040a0c0b4b6f746f626120546573743118301606035504030c0f4b6f746f62612054657374205453413059301306072a8648ce3d020106082a8648ce3d030107034200047d5f6c637af986a8847f6f23755d24a192e348c86f9ff35468f4b5592de6fbd447a02e8139feee9baff1ef0c79179e0746293bc7dafba0a0e7f9d69e1d3a032da3783076300c0603551d130101ff04023000300e0603551d0f0101ff0404030206c030160603551d250101ff040c300a06082b06010505070308301d0603551d0e04160414817bdc5258db8e6f9e32edcd1d046f30cdaf8f31301f0603551d230418301680148033d385f87b532fc1a9fb42fee110ffe73040c3300a06082a8648ce3d04030203480030450221009300639acf8fd27cdb85761a9ccd298ee89cd549b964cb78b29b489b08671adb02203111acf98ca2aaa0b9d227a29ce1d9ddc8a63b802ecda444528885c1c23d4178308201e63082018da00302010202142ee1b06995d7b8c61ef21ceb91b93703b38a9a67300a06082a8648ce3d0403023041310b3009060355040613024a5031173015060355040a0c0e4b6f746f626120546573742043413119301706035504030c104b6f746f6261205465737420526f6f74301e170d3236303733303133313233335a170d3336303732373133313233335a3041310b3009060355040613024a5031173015060355040a0c0e4b6f746f626120546573742043413119301706035504030c104b6f746f6261205465737420526f6f743059301306072a8648ce3d020106082a8648ce3d03010703420004099900d98e0fda9b1f77526e5404608d169d3ec3881147b564e0ae5887290ecd267dc6976f912c2d4cb855e716dbbd8bb7c32f4c537524fd8dd87f97d7d98b11a3633061301d0603551d0e041604148033d385f87b532fc1a9fb42fee110ffe73040c3301f0603551d230418301680148033d385f87b532fc1a9fb42fee110ffe73040c3300f0603551d130101ff040530030101ff300e0603551d0f0101ff040403020106300a06082a8648ce3d04030203470030440220772238ee68742f994e673f8454a97f038e7e4ed01781770a0bc604d7d71a61b70220224f27531c8cb1574c3d777079bd08d5df702b10270752f6f9dd880f5eeacc893182016e3082016a02010130593041310b3009060355040613024a5031173015060355040a0c0e4b6f746f626120546573742043413119301706035504030c104b6f746f6261205465737420526f6f74021459b29c4d07173c1b16871d7129d6213e51e1f25f300d06096086480165030402010500a081a4301a06092a864886f70d010903310d060b2a864886f70d0109100104301c06092a864886f70d010905310f170d3236303733303133343935355a302f06092a864886f70d01090431220420a8dfd59d741549b77e84c742bb06b849fe867d1d34369d6cc9397c0056e952393037060b2a864886f70d010910022f3128302630243022042084260489709a80a7a815504f7a758e0490fce1bff4fee13ffee6c390e0a79ded300a06082a8648ce3d04030204483046022100a2b35c89229b49e4fdbe5ffcc1b39fb695dd2a58dc3b5baca8d813fc5ff26b7a02210091a5060bd212364c85ab80ac1d77003d062dec591db84546c07ab3eb6e4f2671")
        token-der (:token-der (rfc3161.core/parse-response token-response))
        digest (asn1/unhex "404d8f2246f3a6948de6aee686a6e3d116ed2eb56a74b9b1d3df75f2130203e2")
        stored {:timestamp/token-der token-der}]
    (testing "against the right digest it verifies"
      (let [result (timestamp/verify-stored {} stored digest)]
        (is (:verified result))
        (is (= "2026-07-30T13:49:55Z" (:gen-time result)))))

    (testing "against a different digest it does not — the token is about something else"
      (is (= :message-imprint-mismatch
             (:reason (timestamp/verify-stored {} stored (asn1/unhex (apply str (repeat 32 "00"))))))))

    (testing "with no accredited roots configured, :trusted is false — not :unknown,
              because the deployment DID express a set and this TSA is not in it"
      (is (false? (:trusted (timestamp/verify-stored {} stored digest)))))

    (testing "attestation-of turns that into :tsa-attested, never :accredited"
      (is (= :tsa-attested
             (timestamp/attestation-of {} (timestamp/verify-stored {} stored digest)))))))

(deftest with-no-tsa-configured-signing-still-works
  ;; The decision worth pinning: an operator who has not chosen a TSA can still
  ;; sign internally. Refusing would trade a real capability for a compliance
  ;; property they may not need.
  (is (not (timestamp/configured? {})))
  (is (nil? (timestamp/timestamp! {} (asn1/unhex (apply str (repeat 32 "ab")))))))
