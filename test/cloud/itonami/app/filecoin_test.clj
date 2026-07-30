(ns cloud.itonami.app.filecoin-test
  "Offline only. Nothing here touches the network, so it is safe in CI — the
  live chain surface is exercised by hand and recorded in the PR, because a
  test that depends on mainnet being reachable fails for reasons that have
  nothing to do with this code."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.filecoin :as filecoin]
            [drive.object :as object]
            [kotoba.bytes :as b]))

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
  ;; This called `(object/write-item store ref payload)` — three arguments to a
  ;; six-argument function — so it threw ArityException and passed without ever
  ;; reaching the store. The refusal it names lives in `put-object`, and until
  ;; now nothing exercised it: the one guarantee that makes this store
  ;; content-addressed was untested.
  (let [store (filecoin/store)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"reference is not this content's PieceCID"
         (object/-put-object store "bafkzcibcnotthecid" payload)))
    (testing "and the right reference is accepted"
      (let [ref (filecoin/piece-ref payload)]
        (try
          (is (:ok? (object/-put-object store ref payload)))
          (finally (object/-delete-object store ref)))))))

(deftest put-then-get-round-trips-through-staging
  (let [store (filecoin/store)
        ref (filecoin/piece-ref payload)]
    (try
      (is (:ok? (object/-put-object store ref payload)))
      (is (object/-object-exists? store ref))
      ;; `-get-object` answers in the protocol's shape — a vector of unsigned
      ;; ints — not in the shape this backend happens to hold internally.
      ;; Comparing raw `seq`s asserted the opposite: `payload` is a signed
      ;; byte[], so `·` reads as -62 -73 there and 194 183 through the seam.
      (is (= (b/->bytes payload) (object/-get-object store ref)))
      (is (vector? (object/-get-object store ref))
          "and a consumer never sees this backend's byte[]")
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

;; ── retrieval URLs ───────────────────────────────────────────────────────────

(deftest with-nothing-configured-there-is-nowhere-to-read-from
  ;; An empty list is the honest answer. The version this replaced built
  ;; https://<domain>/piece/<cid> unconditionally, which resolves, 404s, and
  ;; so reads as a missing piece rather than a wrong URL.
  (is (= [] (filecoin/retrieval-urls "bafkzcibtest" {:provider-url nil
                                                    :client-address nil}))))

(deftest a-provider-serves-pieces-under-its-own-path
  (is (= [{:kind "provider" :url "https://main2.ezpdpz.net/piece/bafkzcibtest"}]
         (filecoin/retrieval-urls "bafkzcibtest"
                                  {:provider-url "https://main2.ezpdpz.net"
                                   :client-address nil}))))

(deftest the-cdn-gives-each-client-its-own-subdomain
  (let [[u] (filecoin/retrieval-urls "bafkzcibtest" {:provider-url nil
                                                     :client-address "0xABC"})]
    (is (= "cdn" (:kind u)))
    (is (str/starts-with? (:url u) "https://0xabc."))
    (is (str/ends-with? (:url u) "/bafkzcibtest"))
    (is (not (str/includes? (:url u) "/piece/")))))

;; ── read-through verifies ────────────────────────────────────────────────────

(deftest read-through-returns-bytes-that-hash-back-to-the-reference
  (let [payload (byte-array (map #(unchecked-byte (mod % 251)) (range 300)))
        ref (filecoin/piece-ref payload)
        store (filecoin/store {:provider-url "https://sp.example"
                               :fetch (fn [_] {:status 200 :bytes payload})})]
    (is (= (b/->bytes payload) (object/-get-object store ref)))))

(deftest read-through-discards-bytes-that-do-not
  ;; The measured mainnet case: 11 of 13 providers reporting they held a live
  ;; piece served bytes that were not it — one of them a 27-byte nginx
  ;; placeholder — every one with status 200. Handing those to `drive` under a
  ;; PieceCID they do not hash to is the one failure a content-addressed store
  ;; must not have.
  (doseq [[label wrong]
          [["nginx placeholder" (.getBytes "Server is ready for certbot" "UTF-8")]
           ["truncation" (byte-array (map #(unchecked-byte (mod % 251)) (range 200)))]]]
    (testing label
      (let [payload (byte-array (map #(unchecked-byte (mod % 251)) (range 300)))
            ref (filecoin/piece-ref payload)
            store (filecoin/store {:provider-url "https://sp.example"
                                   :fetch (fn [_] {:status 200 :bytes wrong})})]
        (is (nil? (object/-get-object store ref)))))))
