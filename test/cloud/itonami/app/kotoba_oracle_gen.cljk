(ns cloud.itonami.app.kotoba-oracle-gen
  "Regenerate the shipped KIR from `src/cloud/itonami/app/*.kotoba`.

      clojure -M:test:gen

  Runs under :test because the compiler lives there and must not reach the
  application. What it writes IS what production loads, so nothing here
  transforms it: same compile call as the drift test, pretty-printed EDN, no
  post-processing. If this file and that test disagreed about how to compile,
  the test would be checking something other than what ships."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [kotoba.compiler.core :as compiler])
  (:gen-class))

(def target
  "The portable target the shipped KIR is compiled for.

  KIR is target-independent in practice for these cores -- they are inside the
  native word-typed slice and the parity tests compile all four targets -- but
  ONE of them has to be the artifact, and naming it here rather than in three
  places is what keeps regeneration reproducible."
  :wasm32-kotoba-v1)

(defn compile-kir [source-path]
  (let [result (compiler/compile-source (slurp (io/file "src" source-path)) target {})]
    (or (:kir result)
        (throw (ex-info "compile-source returned no :kir" {:source source-path})))))

(defn write-artifact! [id source-path]
  (let [out (io/file "resources" (oracle/resource-path id))]
    (io/make-parents out)
    (spit out (with-out-str (pp/pprint (compile-kir source-path))))
    (.getPath out)))

(defn regenerate-all! []
  (mapv (fn [[id source]] (write-artifact! id source)) (sort-by key oracle/cores)))

(defn -main [& _]
  (run! println (regenerate-all!))
  (shutdown-agents))
