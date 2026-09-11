(ns cloud.itonami.app.human-work-marketplace-kotoba-parity-test
  "What binds `human_work_marketplace_core.kotoba` to the title judgement
  inside `cloud.itonami.app.human-work-marketplace/page-html`.

  `page-html` is rendering: one large HTML document assembled around an
  embedded `<title>`. The two decisions in that assembly move here:

    pick-brand-name — the cljc's `(or brand-name \"Cloud Itonami\")`. Absent
    means the product wordmark; the choice is a real decision, and it is what
    the page shows as its name.

    needs-escape? — whether the name the page will show contains any of the
    three characters the cljc's escape map rewrites (`&` `<` `>`). The
    substitution itself needs string mutation, which the native slice does
    not admit, so the host keeps the one-pass `str/escape`; this core decides
    whether to apply it. The rendered page is byte-identical either way,
    because escaping a name that contains none of the three characters is the
    identity — `str/escape` maps only that set.

  The nil row is the contract: a caller with no brand name gets the wordmark
  and must not be told to escape it (\"Cloud Itonami\" contains none of the
  three characters).

  Same caveat as the sibling suites: the native compile rows assert the core
  is expressible on native, not that anything runs there."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.human-work-marketplace :as marketplace]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/human_work_marketplace_core.kotoba")
(def ^:private core-source (slurp core-path))

(def ^:private escape-map {\& "&amp;" \< "&lt;" \> "&gt;"})

(defn- kotoba-string [s]
  (pr-str (str s)))

;; #:: raw probe: build a zero-arg wrapper around the export under test, with
;; the brand name in guest shape. `nil` becomes `(option-none-of …)`;
;; strings become `(option-some-of [:option :string] …)`.
(defn- opt-str [v]
  (cond
    (nil? v) "(option-none-of [:option :string])"
    (string? v) (str "(option-some-of [:option :string] " (kotoba-string v) ")")
    :else (pr-str v)))

(defn- call-probe [i v export]
  (str "(defn p" i " [] "
       (if (= export "pick-brand-name") ":string" ":bool")
       " (" export " " (opt-str v) "))"))

(defn- run-probes [cases export]
  (let [defs (str/join "\n" (map-indexed (fn [i v] (call-probe i v export)) cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [pick-brand-name needs-escape? " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

;; The reference is the ORIGINAL title expression, independent of the new
;; oracle calls: the `or` default and the single-pass `str/escape` map.
(def ^:private cases
  [nil "" "Cloud Itonami" "Acme & Sons" "Tom <Jr>" "x > y" "A&B<C>D"])

(defn- chosen [v] (or v "Cloud Itonami"))

(defn- escape-changes? [v]
  (not= (str/escape (chosen v) escape-map) (chosen v)))

(deftest pick-brand-name-agrees-with-or-default
  (let [actual (run-probes cases "pick-brand-name")]
    (doseq [[i v] (map-indexed vector cases)]
      (testing (pr-str v)
        (is (= (chosen v) (get actual (str "p" i)))
            (str "pick-brand-name disagrees on " (pr-str v)))))))

(deftest needs-escape-agrees-with-the-host-escape-map
  (let [actual (run-probes cases "needs-escape?")]
    (doseq [[i v] (map-indexed vector cases)]
      (testing (pr-str v)
        (is (= (escape-changes? v) (boolean (get actual (str "p" i))))
            (str "needs-escape? disagrees on " (pr-str v)))))))

(deftest the-page-still-embeds-the-escaped-title
  ;; End-to-end through the ORACLE path (the shipped KIR artifact): the page
  ;; renders the same escaped title the pre-split expression produced.
  (doseq [v [nil "" "Cloud Itonami" "Acme & Sons" "Tom <Jr>" "A&B<C>D"]]
    (testing (pr-str v)
      (is (str/includes? (marketplace/page-html v)
                         (str/escape (chosen v) escape-map))
          (str "page-html lost the escaped title for " (pr-str v))))))

(deftest absent-name-answers-wordmark-and-no-escape
  ;; The contract row: missing brand name is the wordmark, and the wordmark
  ;; needs no escaping. The oracle call must not throw on the absent value.
  (is (= "Cloud Itonami" (get (run-probes [nil] "pick-brand-name") "p0")))
  (is (false? (boolean (get (run-probes [nil] "needs-escape?") "p0")))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "human-work-marketplace core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))