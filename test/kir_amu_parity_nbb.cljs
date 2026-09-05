;; KIR parity, JVM compiler vs the Amu nbb compiler, over the same fixtures.
;;
;;     bin/test-oracle-cljs's problem, inverted: the JVM route
;;     (`clojure -M:test:gen`, the oracle that WRITES `resources/`) and this
;;     JVM-free route (`amu compile`) must lower the SAME KIR from the SAME
;;     decision cores, or one of them is not compiling what ships.
;;
;; Identity is `kotoba.artifact.core/sha256` of the KIR — canonical pr-str,
;; SHA-256 — re-implemented in `gen-kir-amu` (12 lines, same definition on
;; both runtimes). The shipped artifact's digest is compared against the
;; `:kir-sha256` the Amu provenance recorded for a fresh JVM-free compile.
;;
;; Run:  AMU=<amu launcher> nbb --classpath bin:test test/kir_amu_parity_nbb.cljs
;;       (writes only under target/kir-amu/; `resources/` is never touched)

(ns kir-amu-parity-nbb
  (:require [clojure.test :refer [deftest is run-tests]]
            [gen-kir-amu :as gen]))

(def amu
  "The Amu launcher. `AMU` wins; otherwise `amu` must be on PATH."
  (or (.-AMU js/process.env) "amu"))

(deftest amu-compiled-kir-matches-shipped-kir
  (let [rows (gen/parity-report {:amu amu})]
    (is (pos? (count rows)) "no decision cores found — is the checkout intact?")
    (doseq [{:keys [id shipped-kir-sha256 built-kir-sha256 target]} rows]
      (is (= :wasm32-kotoba-v1 target)
          (str (name id) ": amu compiled for an unexpected target"))
      (is (= shipped-kir-sha256 built-kir-sha256)
          (str (name id) ": JVM-free amu KIR differs from the shipped KIR")))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'kir-amu-parity-nbb)]
    (when-not (zero? (+ fail error))
      (set! (.-exitCode js/process) 1))))

(-main)
