(ns cloud.itonami.app.vf-journal-test
  "ADR-2609111230 slice 1: the event journal's gates, tamper-evidence, and
  two-implementation agreement.

  The proofs this slice owes the ADR:
  - an event that fails `valueflows.event` conformance produces a rejected
    result and the journal stays empty (no state root movement),
  - conformance-passing / fold-rejecting is a DIFFERENT stage than
    conformance-failing,
  - two accepted events fold in order and the replayed root equals the
    appended root,
  - a hand-edited line (the `root-permit-index` class: internally consistent
    EDN that is not what the fold would produce) refuses to load.

  The vocabulary is consumed, never reimplemented: every OK/reject verdict
  here comes from `valueflows.conform` and `valueflows.event` at the west pin."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.vf-journal :as vf-journal])
  (:import [java.nio.file Files]))

;; ONE FRESH TEMP DIR PER TEST, not one per namespace: these tests assert
;; exact counts ("two events recorded", "the journal reads as empty"), and
;; clojure.test runs deftests in an unspecified order within a namespace —
;; measured 2026-09-03: a single shared tmp-dir leaked accepted-events'
;; journal into conform-reject's "empty" assertion and 6 assertions failed
;; on ordering alone. fresh-dir! makes every deftest self-contained.
(defn- fresh-dir! []
  (str (Files/createTempDirectory "vf-journal-test"
                                 (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:dynamic *tmp-dir* nil)
(def ^:private org :org-test)

(defn- bind-journal [f]
  (binding [vf-journal/*journal-dir* (java.io.File. *tmp-dir*)]
    (f)))

(defn- with-fresh-journal [f]
  (binding [*tmp-dir* (fresh-dir!)]
    (bind-journal f)))

(defn- event
  "A minimal conforming EconomicEvent, shaped like the vocabulary's own
  event_test fixtures. `produce` increments both registers and creates the
  row; `:resource-inventoried-as` names the resource."
  [over]
  (merge {:action :produce
          :resource-inventoried-as "r1"
          :resource-quantity {:has-numerical-value 5 :has-unit :one}
          :receiver :org-test}
         over))

(deftest conform-reject-leaves-journal-empty
  (with-fresh-journal
   (fn []
     (let [r (vf-journal/append-event! org (event {:action :dance}))]
       (testing "unknown action refused at the conform stage, not the fold"
         (is (false? (:appended? r)))
         (is (= :conform (:stage r)))
         (is (seq (:errors r))))
       (testing "nothing was written: the journal reads as empty"
         (is (zero? (count (vf-journal/events org))))
         (is (= {} (vf-journal/load-inventory org))))))))

(deftest missing-quantity-is-conform-stage-too
  (with-fresh-journal
   (fn []
     (let [r (vf-journal/append-event! org (event {:resource-quantity nil}))]
       (is (false? (:appended? r)))
       (is (= :conform (:stage r)))))))

(deftest accepted-events-fold-in-order-and-root-agrees
  (with-fresh-journal
   (fn []
     (let [r1 (vf-journal/append-event! org (event {}))
           r2 (vf-journal/append-event! org (event {}))]
       (testing "both accepted"
         (is (:appended? r1))
         (is (:appended? r2)))
       (testing "the second fold ran over the first's inventory: root moved"
         (is (not= (:root r1) (:root r2))))
       (testing "replay from disk reproduces the appended root exactly"
         (is (= (:root r2) (vf-journal/root (vf-journal/load-inventory org)))))
       (testing "two events recorded, oldest first"
         (is (= 2 (count (vf-journal/events org))))
         (is (= (:entry-id r1) (:vf.journal/entry-id (first (vf-journal/events org))))))
       (testing "the quantity folded through the vocabulary's own arithmetic"
         (is (= 10 (get-in (vf-journal/load-inventory org)
                           ["r1" :onhand-quantity :has-numerical-value]))))))))

(deftest hand-edited-root-refuses-to-load
  (with-fresh-journal
   (fn []
     (is (:appended? (vf-journal/append-event! org (event {}))))
     ;; Overwrite the recorded root with a valid-looking but wrong hash —
     ;; the internally-consistent-EDN class that a 'does it parse' check
     ;; cannot catch. The root is a full 64-hex sha256; anchor the match to
     ;; the recorded key so an unlucky leading substring in a UUID or
     ;; timestamp can never leave the real root untouched (measured
     ;; 2026-09-03: #"64[0-9a-f]{60}" missed every root not starting "64"
     ;; and the tampered journal loaded clean).
     (let [file (java.io.File. *tmp-dir* "org-test.vf-journal.edn")
           text (slurp file)
           patched (clojure.string/replace text
                                           #":root \"[0-9a-f]{64}\""
                                           (str ":root \"" (apply str (repeat 64 "0")) "\""))]
       (spit file patched)
       (is (thrown? clojure.lang.ExceptionInfo
                    (vf-journal/load-inventory org)))))))

(deftest status-names-where-the-economy-stands
  (with-fresh-journal
   (fn []
     (vf-journal/append-event! org (event {}))
     (let [s (vf-journal/status org)]
       (is (= 1 (:events s)))
       (is (string? (:root s)))
       (is (string? (:last-entry-id s)))))))
