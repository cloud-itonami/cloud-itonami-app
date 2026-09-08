(ns cloud.itonami.app.vf-authority-kotoba-parity-test
  "What binds `vf_authority_core.kotoba` to the admission decision inside
  `cloud.itonami.app.vf-authority` (`vf-scope`).

  `vf_authority.clj` is the VF economic authority bridge (ADR-2609111230
  slice 2): it maps loop-yakuwari capability keywords to biscuit scopes
  `kotoba://vf/<org>/<plane>/<action>`. Most of that file is mechanism the
  native slice refuses — keyword conversion, wire-string assembly, the
  capability→scope map, and every `authority.scope` call — and stays host.
  What moves here is the ADMISSION DECISION:

    does this (plane, action) pair exist in the ADR's table?

      event       read, append
      commitment  read, commit
      agreement   read
      resource    read

  The core answers it over two `:string` names (the host prepares
  `(name p)` / `(name a)`); on `true`, `vf-scope` assembles the wire
  string. The refusal is byte-faithful: a pair the ADR does not define
  answers false, so `vf-scope` returns nil — the original's `(when-let
  [acts (planes p)] (when (contains? acts a) ...))` IS this table.

  Same caveat as the sibling suites: the native compile rows assert the core
  is expressible on native, not that anything runs there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/vf_authority_core.kotoba")
(def ^:private core-source (slurp core-path))

(defn- kotoba-string [s]
  (pr-str (str s)))

;; #:: raw probe: build a zero-arg wrapper around the export under test, with
;; the two names in guest shape.
(defn- call-probe [i plane action]
  (str "(defn p" i " [] :bool "
       "(scope-admitted? " (kotoba-string plane) " " (kotoba-string action) "))"))

(defn- run-probes [cases]
  (let [defs (str/join "\n" (map-indexed (fn [i [pl ac]] (call-probe i pl ac))
                                         cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (clojure.string/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [scope-admitted? " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

;; The ORIGINAL admission table, written out independently of the new oracle
;; calls: the cljc's `(when-let [acts (planes p)] (when (contains? acts a) …))`
;; is exactly this function of the two names.
(defn- original-admitted? [plane action]
  (contains? (get {:event #{:read :append}
                   :commitment #{:read :commit}
                   :agreement #{:read}
                   :resource #{:read}}
                  (keyword plane))
             (keyword action)))

(def ^:private combos
  (for [plane ["event" "commitment" "agreement" "resource" "economy" "silo"]
        action ["read" "append" "commit" "delete" "transfer"]]
    [plane action]))

(deftest scope-admitted-agrees-with-the-original-table
  (let [actual (run-probes combos)]
    (doseq [[i [pl ac]] (map-indexed vector combos)]
      (testing (str "plane=" pl " action=" ac)
        (is (= (boolean (original-admitted? pl ac))
               (boolean (get actual (str "p" i))))
            (str "scope-admitted? disagrees on plane=" pl " action=" ac))))))

(deftest undefined-pairs-are-refused-not-throw
  ;; The contract row: the pairs the ADR does not define must be refused on
  ;; both sides — the refusal is the whole point of the admission table, and
  ;; the oracle call must not throw on a foreign plane/action.
  (doseq [[pl ac] [["event" "delete"] ["economy" "read"] ["silo" "read"]
                   ["resource" "append"] ["commitment" "transfer"]]]
    (is (false? (boolean (get (run-probes [[pl ac]]) "p0")))
        (str "undefined pair must be refused: " pl "/" ac))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "vf-authority core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))