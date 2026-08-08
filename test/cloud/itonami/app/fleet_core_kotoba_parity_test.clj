(ns cloud.itonami.app.fleet-core-kotoba-parity-test
  "What binds `fleet_core.kotoba` to `fleet_core.cljc`.

  Only the four per-actor decisions are in the core; `search`, `facets`,
  `find-by-id` and `actor-by-repo` stay cljc because they walk the collection.
  See the `.kotoba` header for why `matches-text?` is not here either.

  `isic-of` is the one worth the most attention. Its precedence — `:isic`, then
  `:isic-rev5`, then `:isic-rev4` — is a decision rather than a lookup, and it
  is the kind that drifts without anyone noticing: swap two branches and every
  query still returns an ISIC code, just the wrong one for the actors that
  declare more than one. So it is covered over every combination of the three
  fields being present or absent, not over a chosen few.

  Same discipline as `policy-kotoba-parity-test`, and the same caveat: the
  native compile rows assert that the core is expressible on native, not that
  anything runs there. No capability kit is qualified for `:native-aot`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.fleet-core :as fleet-core]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/fleet_core.kotoba")

(def ^:private core-source (slurp core-path))

(def ^:private actor-type
  "The record descriptor, spelled as `fleet_core.kotoba` declares it. Field
  ORDER matters — `record-new` takes values positionally — so this string is
  also what documents the order the wrappers below rely on."
  (str "[:record :fleet/actor "
       "[[:endpoint [:option :string]] [:health-path [:option :string]] "
       "[:isic [:option :string]] [:isic-rev5 [:option :string]] "
       "[:isic-rev4 [:option :string]]]]"))

(defn- kotoba-string [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- opt
  "A `[:option :string]` literal: `nil` becomes none, anything else some."
  [v]
  (if (nil? v)
    "(option-none-of [:option :string])"
    (str "(option-some-of [:option :string] " (kotoba-string v) ")")))

(defn- actor-literal [{:keys [endpoint health-path isic isic-rev5 isic-rev4]}]
  (str "(record-new " actor-type " "
       (opt endpoint) " " (opt health-path) " "
       (opt isic) " " (opt isic-rev5) " " (opt isic-rev4) ")"))

(defn- option->clj
  "Decode what the interpreter hands back for `[:option :string]`:
  `[[:option :string] true \"x\"]` or `[[:option :string] false]`."
  [v]
  (let [[_ present? value] v]
    (when present? value)))

(defn- run-probes
  "Compile once with every wrapper appended, then execute each."
  [probes]
  (let [names (str/join " " (map first probes))
        defs (str/join "\n" (map (fn [[n result-type body]]
                                   (str "(defn " n " [] " result-type " " body ")"))
                                 probes))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [catalog-schema-ok? callable? probeable? isic-of "
                       names "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _ _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ---------------------------------------------------------------------------
;; catalog-schema-ok?

(def ^:private schema-candidates
  [;; the real one, read from the cljc rather than retyped — if the literal in
   ;; the .kotoba ever drifts from this var, this row is what fails
   fleet-core/schema
   "cloud.itonami.fleet-catalog.v2"
   "cloud.itonami.fleet-catalog"
   "CLOUD.ITONAMI.FLEET-CATALOG.V1"
   ""])

(deftest catalog-schema-agrees
  (let [probes (map-indexed (fn [i s]
                              [(str "s" i) ":bool"
                               (str "(catalog-schema-ok? " (kotoba-string s) ")")])
                            schema-candidates)
        actual (run-probes probes)]
    (doseq [[i s] (map-indexed vector schema-candidates)]
      (testing (pr-str s)
        (is (= (= fleet-core/schema s)
               (boolean (get actual (str "s" i)))))))))

(deftest validate-catalog-raises-exactly-when-the-core-says-no
  ;; The core decides; the host raises. This is the seam that keeps `throw` out
  ;; of the `.kotoba` without changing what a caller of `validate-catalog` sees.
  (is (= {:schema fleet-core/schema}
         (fleet-core/validate-catalog {:schema fleet-core/schema})))
  (is (thrown? clojure.lang.ExceptionInfo
               (fleet-core/validate-catalog {:schema "cloud.itonami.fleet-catalog.v2"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (fleet-core/validate-catalog {}))))

;; ---------------------------------------------------------------------------
;; callable? / probeable?

(def ^:private address-cases
  (for [endpoint ["https://example.invalid" nil]
        health-path ["/health" nil]]
    {:endpoint endpoint :health-path health-path}))

(deftest callable-and-probeable-agree
  (let [probes (mapcat (fn [i a]
                         [[(str "c" i) ":bool" (str "(callable? " (actor-literal a) ")")]
                          [(str "b" i) ":bool" (str "(probeable? " (actor-literal a) ")")]])
                       (range) address-cases)
        actual (run-probes probes)]
    (is (= 4 (count address-cases)))
    (doseq [[i a] (map-indexed vector address-cases)]
      (testing (pr-str a)
        (is (= (boolean (fleet-core/callable? a))
               (boolean (get actual (str "c" i))))
            "callable? disagrees")
        (is (= (boolean (fleet-core/probeable? a))
               (boolean (get actual (str "b" i))))
            "probeable? disagrees")))))

(deftest a-callable-actor-need-not-be-probeable
  ;; Stated directly because it is the property the Pages actors depend on.
  (let [a {:endpoint "https://example.invalid" :health-path nil}]
    (is (true? (boolean (fleet-core/callable? a))))
    (is (false? (boolean (fleet-core/probeable? a))))))

;; ---------------------------------------------------------------------------
;; isic-of

(def ^:private isic-of* #'cloud.itonami.app.fleet-core/isic-of)

(def ^:private isic-cases
  (for [isic ["ISIC" nil]
        isic-rev5 ["REV5" nil]
        isic-rev4 ["REV4" nil]]
    {:isic isic :isic-rev5 isic-rev5 :isic-rev4 isic-rev4}))

(deftest isic-precedence-agrees-on-every-combination
  (let [probes (map-indexed (fn [i a]
                              [(str "i" i) "[:option :string]"
                               (str "(isic-of " (actor-literal a) ")")])
                            isic-cases)
        actual (run-probes probes)]
    (is (= 8 (count isic-cases)))
    (doseq [[i a] (map-indexed vector isic-cases)]
      (testing (pr-str a)
        (is (= (isic-of* a)
               (option->clj (get actual (str "i" i))))
            "isic-of disagrees")))))

(deftest an-empty-isic-is-present-not-absent
  ;; `(or "" ...)` in Clojure takes the empty string, because "" is truthy —
  ;; and `option-some-of` with "" is likewise some. Pinned so neither side
  ;; starts treating blank as missing on its own.
  (let [a {:isic "" :isic-rev5 "REV5" :isic-rev4 nil}
        actual (run-probes [["e0" "[:option :string]"
                             (str "(isic-of " (actor-literal a) ")")]])]
    (is (= "" (isic-of* a)))
    (is (= "" (option->clj (get actual "e0"))))))

;; ---------------------------------------------------------------------------
;; the core stays a core

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "fleet decision core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))
