(ns cloud.itonami.app.appearance-kotoba-parity-test
  "What binds `appearance_core.kotoba` to the three string decisions inside
  `cloud.itonami.app.appearance`: which mode a name denotes (`normalize`),
  where a toggle moves next (`next-mode`), and whether the plane is the
  cloud (`residency-plane`).

  `appearance.cljc` is mostly data and mechanism the native word-typed slice
  deliberately refuses: the palette maps, the CSS layers, the hiccup
  `toggle-button`, the `modes` vector, and the string PREPARATION steps
  (`keyword?` / `name`, `str`, `str/trim`, `str/lower` — the case
  folding is a host concern, ADR-2609081000). All of that stays host.

  What moves here are the three DECISIONS about plain strings:

    mode-of      — the normalize case table: a (trimmed, lower-cased) name ->
                   the canonical mode, or none. The cljc's `(case s ...)`
                   group IS this function.

    next-of      — the toggle cycle: light -> 8bit -> grok -> light, with
                   the unknown-name fallback answering 8bit (the host feeds
                   `(or (normalize mode) default-mode)`, whose successor the
                   original index formula also answered 8bit).

    plane-cloud? — the residency judgement: is this (trimmed, lower-cased)
                   plane name \"cloud\"? The cljc's `(= \"cloud\" s)` IS this
                   predicate; the host maps the boolean to :cloud / :local.

  Same caveat as the sibling suites: the native compile rows assert the core
  is expressible on native, not that anything runs there."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.appearance :as appearance]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/appearance_core.kotoba")
(def ^:private core-source (slurp core-path))

;; The ORIGINAL normalize case table, written out independently of the new
;; oracle calls — the cljc's `(case s ...)` is exactly this function of the
;; prepared (trimmed, lower-cased) string.
(defn- original-mode [s]
  (case s
    ("8bit" "8-bit" "eightbit" "eight-bit" "pixel" "retro") "8bit"
    ("grok" "grob" "dark-chat" "chat-dark") "grok"
    ;; `dark-chat` above names the AUTHORED chat-dark appearance and keeps
    ;; doing so; `dark` below names the inverted DADS palette. Two different
    ;; things whose spellings look alike, which is why both rows stay here.
    ("dark" "night") "dark"
    ("light" "default" "dads") "light"
    nil))

;; The ORIGINAL toggle cycle, independent of the oracle — the cljc's
;; keep-indexed/nth/mod formula over the `modes` vector.
(def ^:private original-modes ["light" "dark" "8bit" "grok"])

(defn- original-next-mode [current]
  (nth original-modes
       (mod (inc (or (first (keep-indexed (fn [i m] (when (= m current) i))
                                          original-modes))
                     0))
            (count original-modes))))

;; The ORIGINAL residency predicate, independent of the oracle.
(defn- original-plane-cloud? [s]
  (= "cloud" s))

(defn- kotoba-string [s]
  (pr-str (str s)))

;; #:: raw probe: build a zero-arg wrapper around the export under test, with
;; the argument in guest shape. Return-type is per export.
(defn- call-probe [i export ret-type arg]
  (str "(defn p" i " [] " ret-type " "
       "(" export " " (kotoba-string arg) "))"))

(defn- run-probes [export ret-type cases]
  (let [defs (str/join "\n" (map-indexed (fn [i v] (call-probe i export ret-type v))
                                         cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [mode-of next-of plane-cloud? " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

(defn- option-value [opt]
  (when (and (vector? opt) (true? (second opt))) (nth opt 2)))

(def ^:private mode-spellings
  ["8bit" "8-bit" "eightbit" "eight-bit" "pixel" "retro"
   "grok" "grob" "dark-chat" "chat-dark"
   "light" "default" "dads"
   "dark" "night"
   "neon" "" " " "orbit"])

(deftest mode-of-agrees-with-the-original-case-table
  (let [actual (run-probes "mode-of" "[:option :string]" mode-spellings)]
    (doseq [[i pl] (map-indexed vector mode-spellings)]
      (testing pl
        (is (= (original-mode pl)
               (option-value (get actual (str "p" i))))
            (str "mode-of disagrees on " (pr-str pl)))))))

(deftest unknown-modes-are-none-not-throw
  ;; The contract row: a name the table does not contain answers none on the
  ;; core, so the host's `(or (normalize ...) default-mode)` reduces to the
  ;; default — and the oracle call must not throw on an unknown name.
  (doseq [pl ["neon" "" " " "midnight" "8" "pixelated"]]
    (is (nil? (option-value (get (run-probes "mode-of" "[:option :string]" [pl]) "p0")))
        (str "unknown mode must answer none: " (pr-str pl)))))

(deftest next-of-agrees-with-the-original-cycle
  (let [cases ["light" "dark" "8bit" "grok" "nonsense" "" "retro"]]
    (doseq [[i current] (map-indexed vector cases)]
      (testing (pr-str current)
        ;; The host feeds `(or (normalize mode) default-mode)` — a canonical
        ;; mode or "light". For the canonical modes the original cycle and the
        ;; core must agree; for an unnormalizable name both answer as "light".
        (let [current (or (original-mode current) "light")]
          (is (= (original-next-mode current)
                 (get (run-probes "next-of" ":string" [current]) "p0"))
              (str "next-of disagrees on " (pr-str current))))))))

(deftest plane-cloud-agrees-with-the-original-predicate
  (let [cases ["cloud" "Cloud" "CLOUD" "local" "" " " "orbit" "cloud "]]
    (doseq [[i pl] (map-indexed vector cases)]
      (testing (pr-str pl)
        ;; The host trims/lower-cases first; the core sees the prepared name.
        (let [prepared (str/lower (str/trim pl))]
          (is (= (original-plane-cloud? prepared)
                 (boolean (get (run-probes "plane-cloud?" ":bool" [prepared]) "p0")))
              (str "plane-cloud? disagrees on " (pr-str pl))))))))

(deftest host-decisions-agree-through-the-shipped-artifact
  ;; The cljc public surface through the ORACLE artifact path — for each
  ;; representative input, the host result must equal the ORIGINAL formula.
  (testing "resolve-mode"
    (doseq [v [:8bit "8bit" "8-bit" "EIGHTBIT" " pixel " :retro
               :grok "grok" "GROK" " chat-dark "
               :light "light" "default" "dads"
               "dark" "night" :neon 8 nil "" ; 8 -> "8" -> none -> light
               ]]
      (is (= (or (original-mode (some-> v (cond-> (keyword? v) name)
                                        str str/trim str/lower))
                 "light")
             (appearance/resolve-mode {:ui {:appearance v}}))
          (str "resolve-mode disagrees on " (pr-str v)))))
  (testing "next-mode"
    (doseq [m ["light" "dark" "8bit" "grok" "nonsense" "" "retro" :8bit]]
      (let [current (or (original-mode (if (keyword? m) (name m) (str/trim (str/lower (str m)))))
                        "light")]
        (is (= (original-next-mode current)
               (appearance/next-mode m))
            (str "next-mode disagrees on " (pr-str m))))))
  (testing "residency-plane"
    (doseq [v [nil 1 true :orbit "" "cloud" "Cloud" "CLOUD" " local "]]
      (is (= (if (original-plane-cloud? (some-> v (cond-> (keyword? v) name)
                                                  str str/trim str/lower))
               :cloud :local)
             (appearance/residency-plane {:residency {:plane v}}))
          (str "residency-plane disagrees on " (pr-str v))))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "appearance core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))
