(ns cloud.itonami.app.esign-retention-kotoba-parity-test
  "The 電子帳簿保存法 attestation decision, in .kotoba and through the host.

  `compliance-gaps` in `cloud.itonami.app.esign.retention` reports two
  independent limbs — 可視性 (is the retention entry present) and 真実性
  (is the tamper-evidence measure closed). This test digs that decision core
  down to the booleans and pins that the .kotoba spelling and the host's
  `compliance-gaps` agree on every one of the 8 input states, limb by limb.

  Each limb is asserted on its own — a caller that reads `visibility-gap?`
  must get an answer about 可視性 only, and `integrity-gap` only about
  真実性. That is the whole point of keeping them separate: a single combined
  number would let one closed limb read as compliance."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.esign.retention :as retention]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/esign/retention_attestation_core.kotoba"))

(def ^:private attestation-ty
  "[:record :esign/attestation [[:retention-entry :bool] [:is-accredited :bool] [:procedure-documented :bool]]]")

(defn- guest-value
  "Compile the core with one zero-arg probe appended and execute it. The
   probe body builds the input record itself (mirroring model-routing's
   run-probes), and the probe name is spliced into the :export list so
   kir/execute can reach it."
  [probe-body]
  (let [src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [integrity-none integrity-no-evidence "
                      "integrity-relying visibility-gap? integrity-gap __probe])"))
                (str "\n(defn __probe [] :i64 " probe-body ")\n"))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (ir/execute kir '__probe [])))

(def ^:private states
  ;; 8 rows: has-entry(2) x accredited(2) x procedure-documented(2).
  (for [entry [true false] acc [true false] proc [true false]]
    {:entry entry :acc acc :proc proc}))

(defn- state-record [s]
  (str "(record-new " attestation-ty " "
       (if (:entry s) "true" "false") " "
       (if (:acc s) "true" "false") " "
       (if (:proc s) "true" "false") ")"))

(defn- host-facts [s]
  ;; Mirror what the application would hold for this input state and ask the
  ;; host for its own limb answer.
  {:retention-entry (when (:entry s) {:retention/schema "v"})
   :timestamp-attestation (if (:acc s) :accredited :app-attested)
   :procedure-documented? (:proc s)})

(defn- host-visibility-gap? [s]
  (boolean (some #(= :可視性 (:limb %)) (retention/compliance-gaps (host-facts s)))))

(defn- host-integrity-code [s]
  ;; host's :真実性 limb -> .kotoba code (0=none, 1=no-evidence, 2=relying)
  ;; `some` over the equality would return `true`, not the element — so the
  ;; predicate uses `when` to return the matched map, which `(:gap …)` can read.
  (let [g (some #(when (= :真実性 (:limb %)) %)
                (retention/compliance-gaps (host-facts s)))]
    (cond (nil? g) 0
          (= :no-tamper-evidence-measure (:gap g)) 1
          (= :relying-on-procedure (:gap g)) 2
          :else -99)))

;; -- 可視性 limb --------------------------------------------------------

(deftest kotoba-and-host-agree-on-the-visibility-limb
  (doseq [s states]
    (let [guest (guest-value (str "(if (visibility-gap? " (state-record s) ") 1 0)"))]
      (is (= (if (host-visibility-gap? s) 1 0) guest)
          (str "visibility-gap? disagreed with host on " (pr-str s))))))

;; -- 真実性 limb --------------------------------------------------------

(deftest kotoba-and-host-agree-on-the-integrity-limb
  (doseq [s states]
    (let [guest (guest-value (str "(integrity-gap " (state-record s) ")"))]
      (is (= (host-integrity-code s) guest)
          (str "integrity-gap disagreed with host on " (pr-str s))))))

;; -- the two limbs are independent (the property, not just the rows) -----

(deftest the-two-limbs-are-independent
  ;; The one state that proves they are not a single answer: no entry (可視性
  ;; open — the searchable fields are not recorded) alongside an accredited
  ;; timestamp (真実性 closed). Both the host and the guest must report the
  ;; same two-limb shape: visibility OPEN, integrity CLOSED.
  (let [s {:entry false :acc true :proc false}]
    (is (true? (host-visibility-gap? s))
        "no entry -> 可視性 gap is OPEN (host)")
    (is (= 0 (host-integrity-code s))
        "accredited -> 真実性 gap is CLOSED (host)")
    (is (= 1 (guest-value (str "(if (visibility-gap? " (state-record s) ") 1 0)")))
        "guest visibility open")
    (is (= 0 (guest-value (str "(integrity-gap " (state-record s) ")")))
        "guest integrity closed"))
  ;; And the mirror: an entry present but only app-attested (真実性 open) has
  ;; the same two-limb shape.
  (let [s {:entry true :acc false :proc false}]
    (is (false? (host-visibility-gap? s))
        "entry present -> 可視性 CLOSED (host)")
    (is (= 1 (host-integrity-code s))
        "unaccredited, no procedure -> 真実性 OPEN (host)")
    (is (= 0 (guest-value (str "(if (visibility-gap? " (state-record s) ") 1 0)")))
        "guest visibility closed")
    (is (= 1 (guest-value (str "(integrity-gap " (state-record s) ")")))
        "guest integrity open")))