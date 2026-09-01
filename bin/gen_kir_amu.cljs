#!/usr/bin/env nbb
;; JVM-free decision-core build path (opt-in): compile every shipped decision
;; core with the Amu compiler and prove KIR identity with the shipped artifacts.
;;
;;     nbb --classpath bin bin/gen_kir_amu.cljs          (from the repository root)
;;     AMU=/path/to/amu nbb --classpath bin bin/gen_kir_amu.cljs
;;
;; ## What this is
;;
;; The shipped decision cores (`src/cloud/itonami/app/*.kotoba` ->
;; `resources/cloud/itonami/app/oracle/*.kir.edn`) are regenerated today by the
;; JVM compiler pin under `clojure -M:test:gen`
;; (`cloud.itonami.app.kotoba-oracle-gen`). That route stays the ORACLE and the
;; only writer of `resources/`. This command is the JVM-free route: it runs
;; `amu compile` (the Amu nbb Wasm path — no JVM, no `clojure -Spath`) on the
;; same sources, and proves that the KIR it lowered is the KIR that shipped.
;;
;; ## How identity is proven without a KIR emitter
;;
;; `amu compile` emits Wasm plus `<output>.provenance.edn`, whose
;; `:kir-sha256` is `kotoba.artifact.core/sha256` of the lowered KIR — a
;; canonical `pr-str` (sorted maps, sorted sets) hashed with SHA-256, defined
;; identically on the JVM and in ClojureScript. This file re-implements that
;; canonicalization locally (it is 12 lines) and hashes the SHIPPED artifact
;; read back from EDN. Equal digests mean equal KIR, independent of EDN
;; pretty-printing. Measured 2026-08-31: `:policy` digests match exactly.
;;
;; ## What this is not
;;
;; It does not write `resources/` — Amu has no KIR-EDN emit, so regeneration
;; remains the JVM oracle's job (`clojure -M:test:gen`). Rollback is therefore
;; the status quo: stop running this command. Artifacts land under
;; `target/kir-amu/` (gitignored build output) and nothing else changes.
;;
;; `AMU` selects the Amu launcher (default: `amu` on PATH). The compile inputs
;; are absolute paths because the Amu nbb Wasm path rejects relative ones
;; ("input must be a regular file", measured 2026-08-31).

(ns gen-kir-amu
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def oracle-ns-path
  "Single source of truth for the core list: the `cores` map in the oracle
  namespace itself, read as EDN. If a core is added there, this picks it up
  with no second edit."
  "src/cloud/itonami/app/kotoba_oracle.cljc")

(def out-dir "target/kir-amu")

(defn- cores-map
  "The `{...}` literal of `(def cores ...)` in kotoba_oracle.cljc, as EDN.
  The literal is plain EDN (keywords and string paths), so a brace balance
  that skips string literals reads it without a Clojure reader."
  [text]
  (let [start (str/index-of text "(def cores")
        _ (when-not start
            (throw (ex-info "no (def cores form found" {:path oracle-ns-path})))
        open-idx (+ start (str/index-of (subs text start) "{"))]
    (loop [i open-idx depth 0 in-string? false]
      (let [ch (nth text i)]
        (cond
          in-string? (recur (inc i) depth (not= ch \"))
            (= ch \") (recur (inc i) depth true)
            (= ch \{) (recur (inc i) (inc depth) false)
            (= ch \}) (if (= depth 1)
                        (edn/read-string (subs text open-idx (inc i)))
                        (recur (inc i) (dec depth) false))
            :else (recur (inc i) depth false))))))

(defn cores
  "Oracle id -> absolute .kotoba source path."
  []
  (let [text (fs/readFileSync oracle-ns-path "utf8")]
    (into (sorted-map)
          (map (fn [[id rel]]
                 [id (path/resolve "src" rel)]))
          (cores-map text))))

(defn- artifact-path
  "resources path of the shipped KIR for `id` — the same shape
  kotoba-oracle/resource-path builds."
  [id]
  (str "resources/cloud/itonami/app/oracle/" (name id) ".kir.edn"))

(defn- canonical [x]
  (cond
    (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x) (vec (sort-by pr-str (map canonical x)))
    (vector? x) (mapv canonical x)
    (sequential? x) (mapv canonical x)
    :else x))

(defn kir-sha256
  "kotoba.artifact.core/sha256 of a KIR map (ClojureScript branch)."
  [kir]
  (-> (crypto/createHash "sha256")
      (.update (pr-str (canonical kir)) "utf8")
      (.digest "hex")))

(defn- sh
  "Run `cmd args`, returning {:exit n :stdout s :stderr s}."
  [cmd args]
  (let [{:keys [status stdout stderr]}
        (js->clj (cp/spawnSync cmd (clj->js args)
                               #js {:encoding "utf8" :stdio "pipe"})
                 :keywordize-keys true)]
    {:exit (or status 1) :stdout (str stdout) :stderr (str stderr)}))

(defn compile-core!
  "JVM-free compile of one decision core via `amu compile`. Returns the
  provenance map read back from the emitted `.provenance.edn`."
  [amu id source]
  (fs/mkdirSync out-dir #js {:recursive true})
  (let [wasm (path/resolve out-dir (str (name id) ".wasm"))
        {:keys [exit stderr stdout]} (sh amu ["compile" source
                                              "--target" "wasm32"
                                              "--output" wasm])]
    (when-not (zero? exit)
      (throw (ex-info (str "amu compile failed for " (name id))
                      {:id id :exit exit :stderr (or stderr stdout)})))
    (edn/read-string (fs/readFileSync (str wasm ".provenance.edn") "utf8"))))

(defn- verify-core
  [{:keys [amu id source]}]
  (let [prov (compile-core! amu id source)
        shipped-kir (edn/read-string
                     (fs/readFileSync (artifact-path id) "utf8"))
        shipped (kir-sha256 shipped-kir)
        built (:kir-sha256 prov)
        match? (= shipped built)]
    {:id id :source source :match? match?
     :shipped-kir-sha256 shipped :built-kir-sha256 built
     :target (:target prov) :compiler (:compiler prov)}))

(defn parity-report
  "Compile every core JVM-free and compare KIR identity. Returns the result
  rows; throws on any mismatch or compile failure."
  [{:keys [amu] :or {amu "amu"}}]
  (mapv verify-core
        (for [[id source] (cores)]
          {:amu amu :id id :source source})))

(defn -main [& _]
  (let [amu (or (.-AMU js/process.env) "amu")
        rows (parity-report {:amu amu})]
    (doseq [{:keys [id match? target compiler]} rows]
      (println (str (name id) ": " (if match? "OK" "MISMATCH")
                    " target=" target " compiler=" compiler)))
    (let [ok (count (filter :match? rows))]
      (println (str ok "/" (count rows) " decision cores match the shipped KIR")))
    (when-not (every? :match? rows)
      (set! (.-exitCode js/process) 1))))

(when (nil? *command-line-args*)
  (-main))
