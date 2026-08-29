(ns cloud.itonami.app.store-core-kotoba-parity-test
  "What binds `store_core.kotoba` to `store_core.cljc`.

  Only the window-eviction arithmetic is in the core; the map/vector assembly
  around it — `:sessions` keyed by an arbitrary runtime string, the merge that
  preserves a session's other fields, the vector append, `dissoc` — stays
  cljc. See the `.kotoba` header for why no exportable value describes
  `:sessions` today.

  `append-message`'s message window and `record-response`'s completion-event
  ring share the same eviction rule, so this both checks `retention-drop-count`
  directly against the arithmetic it stands for, AND checks that
  `store-core/append-message` and `store-core/record-response` — which now
  call it through `kotoba-oracle` instead of `take-last` — still trim exactly
  where the old `take-last`-based implementation trimmed."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.store-core :as core]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/store_core.kotoba")

(def ^:private core-source (slurp core-path))

;; ---------------------------------------------------------------------------
;; retention-drop-count against the arithmetic it replaced

(defn- host-drop-count
  "What `(take-last cap items)` used to compute implicitly: the number of
  leading items a collection of `size` would lose to stay at `cap`."
  [size cap]
  (max 0 (- size cap)))

(def ^:private size-cap-cases
  (for [size [0 1 3 10 11 99 100 101 250]
        cap [0 1 10 100]]
    {:size size :cap cap}))

(deftest retention-drop-count-agrees-with-the-take-last-arithmetic
  (let [{:keys [kir]} (compiler/compile-source core-source :wasm32-kotoba-v1 {})]
    (doseq [{:keys [size cap]} size-cap-cases]
      (testing (pr-str {:size size :cap cap})
        (is (= (host-drop-count size cap)
               (ir/execute kir 'retention-drop-count [size cap]))
            "retention-drop-count disagreed with (max 0 (- size cap))")))))

(deftest a-collection-under-the-cap-loses-nothing
  (let [{:keys [kir]} (compiler/compile-source core-source :wasm32-kotoba-v1 {})]
    (is (zero? (ir/execute kir 'retention-drop-count [3 10])))
    (is (zero? (ir/execute kir 'retention-drop-count [10 10])))))

(deftest a-collection-over-the-cap-loses-exactly-the-overflow
  (let [{:keys [kir]} (compiler/compile-source core-source :wasm32-kotoba-v1 {})]
    (is (= 5 (ir/execute kir 'retention-drop-count [15 10])))
    (is (= 1 (ir/execute kir 'retention-drop-count [11 10])))))

;; ---------------------------------------------------------------------------
;; the host functions that now call through the oracle

(deftest append-message-still-trims-from-the-front-at-the-cap
  (let [blank (core/initial-state)
        state (reduce (fn [s n]
                       (core/append-message s "s1"
                                            {:id (str "msg-" n) :role "person"
                                             :content (str n) :at "t"}
                                            3))
                      blank (range 5))
        kept (core/session-messages state "s1")]
    (is (= 3 (count kept)))
    (is (= ["2" "3" "4"] (map :content kept)))))

(deftest record-response-still-bounds-the-event-ring-at-max-events
  (let [blank (core/initial-state)
        state (reduce (fn [s n] (core/record-response s {:provider "p" :model (str n)} "t"))
                      blank (range (+ core/max-events 5)))]
    (is (= core/max-events (count (:events state))))
    (is (= (str (+ core/max-events 4)) (:model (last (:events state)))))
    (is (= "5" (:model (first (:events state)))))))

;; ---------------------------------------------------------------------------
;; the core stays a core

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "store-core decision core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))
