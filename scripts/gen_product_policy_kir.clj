(ns gen-product-policy-kir
  "Compile the indexed product .kotoba policy sources into shipped KIR.

  Run with `clj -M:product-kir-gen`. This small JVM entrypoint avoids loading
  the Node server/test graph merely to invoke the same Kotoba compiler."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [kotoba.compiler.core :as compiler]))

(def target :wasm32-kotoba-v1)
(def index-path "resources/cloud/itonami/app/product-policy-index.edn")
(def output-dir "resources/cloud/itonami/app/oracle")

(defn compile-source! [core source-path]
  (let [source-file (io/file "src" source-path)
        result (compiler/compile-source (slurp source-file) target {})
        kir (:kir result)]
    (when-not kir
      (throw (ex-info "Kotoba compiler returned no KIR"
                      {:core core :source (.getPath source-file)})))
    kir))

(defn- product-policy-core? [index core]
  (or (some #(= core (:core %)) (vals (:domains index)))
      (= core (get-in index [:shared-policies :awai-app-admission :core]))))

(defn- production-library-kir [index core kir]
  ;; Product source files also export a benchmark-only `main` so AMU's native
  ;; harness can link them. The app host calls the named decision exports and
  ;; must not ship that benchmark entrypoint as part of its callable ABI.
  (if-not (product-policy-core? index core)
    kir
    (-> kir
        (update :functions
                (fn [functions]
                  (filterv #(not= 'main (:name %)) functions)))
        (update :exports
                (fn [exports]
                  (filterv #(not= 'main %) exports)))
        (assoc :signature nil :entry nil))))

(defn write-artifact! [index core source-path]
  (let [kir (production-library-kir index core (compile-source! core source-path))
        output (io/file output-dir (str (name core) ".kir.edn"))]
    (.mkdirs (.getParentFile output))
    (with-open [writer (io/writer output)]
      (binding [*out* writer]
        (pprint/pprint kir)))
    {:core core :source source-path :artifact (.getPath output)}))

(defn indexed-sources [index]
  (let [entries (vec (concat (vals (:domains index))
                             (:shared-itonami-cores index)))
        incomplete (filter #(not (and (:core %) (:source %))) entries)]
    (when (seq incomplete)
      (throw (ex-info "Kotoba policy index entry is missing its core or source"
                      {:entries incomplete})))
    (->> entries
         (map (juxt :core :source))
         distinct
         (sort-by (comp name first)))))

(defn -main [& _]
  (let [index (edn/read-string (slurp index-path))
        entries (indexed-sources index)
        results (mapv (fn [[core source]] (write-artifact! index core source)) entries)]
    (doseq [{:keys [core source artifact]} results]
      (println (str (name core) " <- src/" source " -> " artifact)))
    (shutdown-agents)))
