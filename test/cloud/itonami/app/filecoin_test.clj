(ns cloud.itonami.app.filecoin-test
  "Offline only. Nothing here touches the network, so it is safe in CI — the
  live chain surface is exercised by hand and recorded in the PR, because a
  test that depends on mainnet being reachable fails for reasons that have
  nothing to do with this code."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.filecoin :as filecoin]
            [drive.object :as object]))

(def payload (.getBytes "cloud-itonami-app · filecoin piece addressing" "UTF-8"))

(deftest a-piece-cid-is-computed-not-guessed
  ;; Pinned against @filoz/synapse-core, via cloud-filecoin's own vectors.
  ;; If the PieceCID algorithm drifts, this changes and the reference under
  ;; which every staged object was filed changes with it.
  (let [p (filecoin/piece-of payload)]
    (is (= "bafkzcibckebhflnungvruhu6sv22dbjz73vc65i42eqwcvxmed6cr2hdewwn6ma"
           (:cid p)))
    (is (= 46 (:size p)))
    (is (= 2 (:height p)))
    (is (= 81 (:padding p)) "zero padding up to the 127-byte quad floor")
    (is (= 128 (:padded-size p)) "2^height × 32")))

(deftest the-reference-is-the-content
  (is (= (:cid (filecoin/piece-of payload)) (filecoin/piece-ref payload)))
  (testing "and different bytes get a different reference"
    (is (not= (filecoin/piece-ref payload)
              (filecoin/piece-ref (.getBytes "something else" "UTF-8"))))))

(deftest the-store-refuses-a-reference-that-is-not-the-content
  ;; A store that let a caller file bytes under someone else's PieceCID would
  ;; be content-addressed in name only, and the lie would only surface when a
  ;; provider was asked to prove the piece.
  (let [store (filecoin/store)]
    (is (thrown? Exception
                 (object/write-item store "bafkzcibcnotthecid" payload)))))

(deftest put-then-get-round-trips-through-staging
  (let [store (filecoin/store)
        ref (filecoin/piece-ref payload)]
    (try
      (is (:ok? (object/-put-object store ref payload)))
      (is (object/-object-exists? store ref))
      (is (= (seq payload) (seq (object/-get-object store ref))))
      (finally
        (object/-delete-object store ref)))
    (testing "and delete removes the staged copy only"
      (is (not (object/-object-exists? store ref))))))

(deftest a-put-does-not-claim-a-deal
  ;; The whole reason this namespace is not called \"Drive on Filecoin\".
  (let [store (filecoin/store)
        ref (filecoin/piece-ref payload)]
    (try
      (is (= "not-implemented" (:deal/status (object/-put-object store ref payload))))
      (finally (object/-delete-object store ref)))))

(deftest the-sample-is-real-and-offline
  (let [s (filecoin/sample "abc")]
    (is (= 3 (:bytes s)))
    (is (re-find #"^bafkzcib" (:cid s)))
    (testing "and a different text gives a different cid"
      (is (not= (:cid s) (:cid (filecoin/sample "abd")))))))
