(ns cloud.itonami.app.kotobase-objects-test
  "Offline. The transport is a map, so what is under test is the DAG, the
  identity/location split and the refusals — none of which need
  kotobase.net to be reachable to be wrong."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.archive :as archive]
            [cloud.itonami.app.kotobase-objects :as ko]
            [drive.object :as object]
            [drive.workspace :as ws]
            [unixfs.file :as unixfs]))

(defn- bytes-of [n]
  (let [out (byte-array n)]
    (loop [i 0 s 0x2468ace0]
      (if (= i n)
        out
        (let [s (bit-and 0xffffffff (bit-xor s (bit-shift-left s 13)))
              s (bit-xor s (unsigned-bit-shift-right s 17))
              s (bit-and 0xffffffff (bit-xor s (bit-shift-left s 5)))]
          (aset-byte out i (unchecked-byte (bit-and s 0xff)))
          (recur (inc i) s))))))

(defn- fake-archive
  "An archive that only ever holds raw CIDs, which is the constraint the
  real one enforces and the one this store has to live inside."
  []
  (let [held (atom {})
        order (atom [])]
    {:held held
     :order order
     :transport
     {:put! (fn [{:keys [cid bytes]}]
              (if (str/starts-with? cid "bafkrei")
                (do (swap! held assoc cid bytes)
                    (swap! order conj cid)
                    {:status 201 :body ""})
                {:status 400 :body "not-raw-sha256"}))
      :get-bytes (fn [cid]
                   (if-let [b (get @held cid)]
                     {:status 200 :bytes b}
                     {:status 404 :bytes nil}))}}))

;; ── the reference is the real CID ─────────────────────────────────────────

(deftest the-reference-is-what-ipfs-would-print
  (let [store (ko/store (:transport (fake-archive)))]
    (doseq [n [10 262144 262145 700000]]
      (is (= (unixfs/cid (bytes-of n) {:chunk-size 262144})
             (ko/content-ref store (bytes-of n)))))))

(deftest a-small-file-is-one-raw-object
  (let [{:keys [held transport]} (fake-archive)
        store (ko/store transport)
        body (bytes-of 1000)
        ref (ko/content-ref store body)]
    (object/-put-object store ref body)
    (is (str/starts-with? ref "bafkrei"))
    (is (= 1 (count @held)))
    (is (contains? @held ref) "identity and location are the same string here")))

(deftest a-large-file-is-archived-under-raw-locations
  (testing "the dag-pb root is kept under the raw spelling of its own digest,
            and the Drive still records the dag-pb identity"
    (let [{:keys [held transport]} (fake-archive)
          store (ko/store transport)
          body (bytes-of 262145)
          ref (ko/content-ref store body)]
      (object/-put-object store ref body)
      (is (str/starts-with? ref "bafybei") "identity is dag-pb")
      (is (not (contains? @held ref)) "the archive never sees that string")
      (is (contains? @held (archive/location-cid ref)))
      (is (= 3 (count @held)) "two leaves and a root")
      (is (every? #(str/starts-with? % "bafkrei") (keys @held))))))

(deftest location-keeps-the-digest-and-changes-only-the-codec
  (let [body (bytes-of 262145)
        identity-cid (unixfs/cid body {:chunk-size 262144})
        location (archive/location-cid identity-cid)]
    (is (not= identity-cid location))
    (is (= (subs identity-cid 9) (subs location 9))
        "same base32 tail — the digest is untouched")
    (is (= location (archive/location-cid location)) "raw is its own location")))

(deftest children-are-stored-before-the-root
  (testing "a root reachable before its children is a CID that resolves to a
            hole for as long as the write takes — `unixfs/build` orders the
            blocks, and this is the assertion that the store keeps that order
            rather than merely receiving it"
    (let [{:keys [order transport]} (fake-archive)
          store (ko/store transport)
          body (bytes-of 700000)
          ref (ko/content-ref store body)]
      (object/-put-object store ref body)
      (let [position (into {} (map-indexed (fn [i c] [c i])) @order)
            root-location (archive/location-cid ref)]
        (is (= 4 (count @order)) "three leaves and a root")
        (is (= root-location (last @order)) "the root is written last")
        (doseq [[c i] position :when (not= c root-location)]
          (is (< i (position root-location))
              (str c " must precede the root")))))))

;; ── refusals ──────────────────────────────────────────────────────────────

(deftest a-reference-the-bytes-do-not-have-is-refused
  (let [{:keys [held transport]} (fake-archive)
        store (ko/store transport)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (object/-put-object store "bafkreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                     (bytes-of 100))))
    (is (empty? @held) "nothing is stored under a name that lies")))

(deftest a-missing-object-reads-as-absent
  (let [{:keys [transport]} (fake-archive)
        store (ko/store transport)
        body (bytes-of 5000)]
    (is (nil? (object/-get-object store (ko/content-ref store body)))
        "nil, so drive.object/read-item can call it :missing-object")))

(deftest corruption-does-not-read-as-absence
  (testing "a store returning the wrong bytes must not look like an empty one"
    (let [{:keys [held transport]} (fake-archive)
          store (ko/store transport)
          body (bytes-of 1000)
          ref (ko/content-ref store body)]
      (object/-put-object store ref body)
      (swap! held assoc (archive/location-cid ref) (bytes-of 999))
      (is (thrown? clojure.lang.ExceptionInfo (object/-get-object store ref))))))

(deftest deletion-is-refused-rather-than-pretended
  (let [{:keys [held transport]} (fake-archive)
        store (ko/store transport)
        body (bytes-of 1000)
        ref (ko/content-ref store body)]
    (object/-put-object store ref body)
    (is (false? (object/-delete-object store ref)))
    (is (contains? @held ref) "the bytes are still there, which is the point")))

;; ── through the Drive ─────────────────────────────────────────────────────

(deftest a-file-round-trips-through-the-acl
  (doseq [n [500 262145 600000]]
    (let [{:keys [transport]} (fake-archive)
          store (ko/store transport)
          body (bytes-of n)
          ref (ko/content-ref store body)
          workspace (-> (ws/workspace "drive-alice" "alice" (* 10 1024 1024))
                        (ws/create-file "f1" "root" {:drive/title "x"} "alice"))
          written (object/write-item workspace store "f1" "alice" body
                                     {:object-ref ref})
          read (object/read-item (:workspace written) store "f1" "alice")]
      (is (:ok? written))
      (is (:ok? read))
      ;; Both sides in one representation. `read-item` returns
      ;; `kotoba.bytes/->bytes`, a vector of *unsigned* ints; a `byte-array`
      ;; seqs as signed, so a direct `=` reports every byte above 127 as a
      ;; difference and says nothing about the store.
      (is (= (mapv #(bit-and (int %) 0xff) (seq body))
             (mapv #(bit-and (int %) 0xff) (seq (:bytes read))))
          (str "size " n))
      (is (= ref (:object-ref read))))))

(deftest two-items-holding-one-file-hold-one-reference
  (testing "content addressing, which drive allows only because the bytes
            are compared against the store rather than taken on trust"
    (let [{:keys [held transport]} (fake-archive)
          store (ko/store transport)
          body (bytes-of 300)
          ref (ko/content-ref store body)
          workspace (-> (ws/workspace "drive-alice" "alice" (* 10 1024 1024))
                        (ws/create-file "a" "root" {:drive/title "a"} "alice")
                        (ws/create-file "b" "root" {:drive/title "b"} "alice"))
          w1 (object/write-item workspace store "a" "alice" body {:object-ref ref})
          w2 (object/write-item (:workspace w1) store "b" "alice" body {:object-ref ref})]
      (is (:ok? w1))
      (is (:ok? w2) "the second write is not refused as a duplicate reference")
      (is (= 1 (count @held))))))
