(ns cloud.itonami.app.store-test
  "The clock everything is ordered by.

  Versions, comments and the document listing are all sorted by these
  strings, and the keyset cursor that pages the listing is built from one.
  So two properties matter and neither was true: that no two are equal, and
  that comparing them as strings gives the same answer as comparing the
  instants they stand for."
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.store :as store])
  (:import [java.time Instant]))

(deftest no-two-timestamps-are-equal
  ;; Measured on the raw clock: `(str (Instant/now))` gave 18,848 distinct
  ;; values for 20,000 calls. A tie is an order nothing decides, in a list
  ;; that is paged by a cursor built from these.
  (let [xs (vec (repeatedly 20000 store/now))]
    (is (= (count xs) (count (distinct xs))))))

(deftest string-order-is-instant-order
  ;; The defect this exists for. `Instant/toString` drops trailing zeros in
  ;; groups of three, so about one timestamp in eleven hundred prints
  ;; `…:00.123Z` rather than `…:00.123456Z` — and "Z" sorts after "4", so
  ;; that one sorts *after* every longer timestamp in its own second. The
  ;; listing order is then the opposite of the truth, rarely and silently.
  ;; The defect itself, in two lines: these two instants are in one order
  ;; and their printed forms are in the other.
  (let [earlier (Instant/parse "2026-07-30T12:00:00.123Z")
        later (Instant/parse "2026-07-30T12:00:00.123456Z")]
    (is (.isBefore earlier later))
    (is (pos? (compare (str earlier) (str later)))
        "printed by Instant/toString, the earlier one sorts last"))
  (let [xs (vec (repeatedly 20000 store/now))]
    (is (= xs (vec (sort xs))) "sorted as strings, still in the order produced")
    (is (= 1 (count (distinct (map count xs)))) "one width, so no short one jumps")))

(deftest a-timestamp-is-still-an-instant
  (let [x (store/now)]
    (is (some? (Instant/parse x)))
    (is (= 27 (count x)))))

(deftest the-clock-does-not-go-backwards
  ;; Instant/now is not monotonic — an NTP correction can move it back, and
  ;; a version stamped before the one it replaced is a history that reads
  ;; wrong for ever.
  (let [xs (repeatedly 5000 #(Instant/parse (store/now)))]
    (is (every? (fn [[a b]] (.isBefore a b)) (partition 2 1 xs)))))
