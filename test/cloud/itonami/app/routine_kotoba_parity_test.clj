(ns cloud.itonami.app.routine-kotoba-parity-test
  "When a recorded piece of work may run again, in .kotoba and through the host.

  ## What is actually at risk

  Two properties carry this rule, and each is one comparison from being lost:

  - a routine whose grant narrowed must REFUSE, not run short. `stale?` is a
    `<` between two counts; as `not=` it would also refuse a host bug, and as
    `>` it would run exactly the routines it exists to stop.
  - a schedule must not start work the last schedule has not finished. This is
    the failure that does not announce itself — an hourly routine that needs an
    approval leaves a queue of held runs by morning — so `may-fire?` is
    asserted to be strictly stricter than `may-start?`, as a relation between
    the two functions rather than as a row-by-row agreement.

  Both are asserted as properties, not only as agreement with the host:
  agreement would still hold if both sides were wrong together.

  ## The corpus is exhaustive where it is cheap

  Three booleans and the sign of one comparison: 8 x 3 rows, checked in full.
  A conjunction is exactly the shape where sampling misses a dropped term. The
  counts are the boundary triple (short, equal, and the host-impossible over)
  rather than a range, because `stale?` cannot see any other distinction."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.routine :as routine]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/routine_core.kotoba"))

(def ^:private export-prefix
  (str "status-disabled status-idle status-running status-waiting-approval "
       "status-stale stale? may-fire? may-start? status main"))

(def ^:private presence-ty
  (str "[:record :routine/presence [[:enabled :bool] [:held-run :bool] "
       "[:active-run :bool] [:steps-admitted :i64] [:steps-recorded :i64]]]"))

(defn- run-probes
  "Compile the core with zero-arg probes appended and execute each one."
  [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ── the corpus ───────────────────────────────────────────────────────

(def ^:private rows
  "Every combination of the three booleans, against three count relations.

  `:short` is the grant having narrowed, `:equal` is the ordinary case, and
  `:over` is more admitted than recorded — which the host cannot produce
  (admission filters a fixed list) and which must therefore NOT read as stale."
  (for [enabled [true false]
        held [true false]
        active [true false]
        [rel admitted recorded] [[:short 2 3] [:equal 3 3] [:over 3 2]]]
    {:enabled enabled :held held :active active
     :rel rel :admitted admitted :recorded recorded}))

(defn- probe-name [prefix i] (str prefix "_" i))

(defn- presence-literal [{:keys [enabled held active admitted recorded]}]
  (str "(record-new " presence-ty " " enabled " " held " " active " "
       admitted " " recorded ")"))

;; ── the host side, driven through its public door ────────────────────

(defn- host-state [{:keys [held active admitted]}]
  {:held-run? held :active-run? active :admitted (vec (repeat admitted :step))})

(defn- host-args [{:keys [enabled recorded] :as row}]
  [{:routine/enabled? enabled
    :routine/steps (vec (repeat recorded {:step/tool "t" :step/effect :read
                                          :step/intent "i"}))}
   {:bot/enabled? true}
   (host-state row)])

;; ── agreement ────────────────────────────────────────────────────────

(deftest kotoba-and-host-agree-on-every-row
  (doseq [[fn-name host-fn] [["stale?" routine/stale?]
                             ["may_start" routine/may-start?]
                             ["may_fire" routine/may-fire?]]]
    (testing fn-name
      (let [export (case fn-name "may_start" "may-start?" "may_fire" "may-fire?" fn-name)
            probes (map-indexed
                    (fn [i row]
                      [(probe-name (str/replace fn-name #"[?]" "") i)
                       (str "(" export " " (presence-literal row) ")")])
                    rows)
            guest (run-probes probes ":bool")]
        (doseq [[i row] (map-indexed vector rows)]
          (let [k (probe-name (str/replace fn-name #"[?]" "") i)
                expected (get guest k)
                actual (apply host-fn (host-args row))]
            (is (= expected actual)
                (str fn-name " disagreed on " (pr-str row)))))))))

(deftest kotoba-and-host-agree-on-status
  (let [probes (map-indexed
                (fn [i row] [(probe-name "st" i) (str "(status " (presence-literal row) ")")])
                rows)
        guest (run-probes probes ":i64")]
    (doseq [[i row] (map-indexed vector rows)]
      (let [expected (get routine/status-codes (get guest (probe-name "st" i)))
            actual (apply routine/status (host-args row))]
        (is (= expected actual) (str "status disagreed on " (pr-str row)))))))

;; ── the properties, which agreement alone would not catch ────────────

(deftest a-narrowed-grant-refuses-rather-than-running-short
  (doseq [row rows :when (= :short (:rel row))]
    (is (true? (apply routine/stale? (host-args row)))
        (str "a short count must be stale: " (pr-str row)))
    (is (false? (apply routine/may-start? (host-args row)))
        (str "a stale routine must not start: " (pr-str row)))
    (is (false? (apply routine/may-fire? (host-args row)))
        (str "a stale routine must not fire: " (pr-str row)))))

(deftest more-admitted-than-recorded-is-not-staleness
  ;; `<` rather than `not=`. Were this reversed, a host bug would surface as a
  ;; permission refusal, which is the reading that sends somebody to re-grant a
  ;; tool that was never the problem.
  (doseq [row rows :when (= :over (:rel row))]
    (is (false? (apply routine/stale? (host-args row)))
        (str "over-admission is not staleness: " (pr-str row)))))

(deftest firing-is-strictly-stricter-than-starting
  ;; The relation, not the rows. A schedule may never start work a person could
  ;; not have started by hand.
  (doseq [row rows]
    (when (apply routine/may-fire? (host-args row))
      (is (true? (apply routine/may-start? (host-args row)))
          (str "fired but could not start: " (pr-str row)))))
  ;; And it is strictly stricter: a held run is exactly the difference.
  (let [held {:enabled true :held true :active false :rel :equal :admitted 3 :recorded 3}
        free (assoc held :held false)]
    (is (true? (apply routine/may-start? (host-args held))) "a person may still start")
    (is (false? (apply routine/may-fire? (host-args held))) "a schedule may not")
    (is (true? (apply routine/may-fire? (host-args free))) "and does when nothing is held")))

(deftest a-held-run-never-fires-a-second-time
  ;; The pile-up this exists to prevent, stated on its own so that a change to
  ;; `may-start?` cannot quietly take it with it.
  (doseq [row rows :when (and (:enabled row) (:held row))]
    (is (false? (apply routine/may-fire? (host-args row)))
        (str "held runs must not accumulate: " (pr-str row)))))

(deftest status-reports-the-blocking-fact-first
  (let [base {:enabled true :held false :active false :rel :equal :admitted 3 :recorded 3}
        at #(apply routine/status (host-args (merge base %)))]
    (is (= :disabled (at {:enabled false})))
    (is (= :idle (at {})))
    (is (= :running (at {:active true})))
    (is (= :stale (at {:rel :short :admitted 2 :recorded 3})))
    (is (= :waiting-approval (at {:held true})))
    ;; the orderings, each of which compiles when reversed and is then wrong
    (is (= :waiting-approval (at {:held true :rel :short :admitted 2 :recorded 3}))
        "an approval a person can give outranks a grant they must fix")
    (is (= :stale (at {:active true :rel :short :admitted 2 :recorded 3}))
        "a narrowed grant outranks a progress light")
    (is (= :waiting-approval (at {:held true :active true}))
        "waiting outranks working")))
