(ns oracle-cases-nbb
  "The ClojureScript half of the two-runtime gate. Run by `bin/test-oracle-cljs`.

  This is the half that was missing, and its absence was not visible from the
  JVM: `fleet_core.cljc` delegated to the shipped core on 2026-08-11 and from
  that moment the Worker build could not resolve `kotoba.kir` and the seam
  threw on every call, while `clojure -M:test` stayed green.

  It runs `oracle-cases/cases` — the same table the JVM half runs — through
  `oracle/call`, with `:i64` values built by `oracle/i64` and read back by
  `oracle/i64-value`, which is precisely what the hosts do."
  (:require [cljs.reader :as reader]
            ["fs" :as fs]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [cloud.itonami.app.oracle-cases :as cases]))

;; ClojureScript has no classpath. Node has a filesystem, so this reads the
;; SAME bytes `io/resource` hands the JVM rather than embedding a copy — a
;; gate that ran against its own copy of the artifact would not be a gate on
;; the artifact that ships.
(oracle/set-resource-loader!
 (fn [path]
   (let [file (str "resources/" path)]
     (when (.existsSync fs file)
       (.readFileSync fs file "utf8")))))

(defn -main []
  (let [uncovered (cases/uncovered)
        failures (cases/failures)]
    (println "oracle cases —" (count cases/cases) "cases over"
             (count oracle/cores) "shipped cores, on ClojureScript")
    (doseq [[id export] uncovered]
      (println "  UNCOVERED" id export "— shipped export with no case"))
    (doseq [f failures]
      (println "  FAIL" (:oracle f) (:export f)
               "expected" (pr-str (:expect f))
               "got" (pr-str (:actual f))))
    (when (< (count cases/cases) 70)
      (println "  FAIL the case table shrank —" (count cases/cases) "cases"))
    (if (or (seq uncovered) (seq failures) (< (count cases/cases) 70))
      (do (println "\nFAILED:" (count failures) "wrong,"
                   (count uncovered) "uncovered")
          (set! (.-exitCode js/process) 1))
      (println "\nall" (count cases/cases) "cases passed"))))

(-main)
