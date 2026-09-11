(ns cloud.itonami.app.connectors-kotoba-parity-test
  "What binds `connectors_core.kotoba` to the scope-admission decision inside
  `cloud.itonami.app.connectors` (`covered?`).

  `connectors.clj` is the external-services registry bridge (ADR-2608097000):
  it derives the default enabled set from `historical-grant` so a connector
  added later can never silently widen anybody's grant. Most of the file is
  mechanism the native slice refuses — set membership (`contains?`),
  registry traversal (`creg/providers`), descriptor maps, wire-string
  assembly. All of that stays host.

  What moves here is the one thing that is a DECISION about plain strings:
  the `scope-implications` table — scopes already contained in a scope the
  application held (`gmail.readonly` inside `gmail.modify`; `Mail.Read` and
  `Mail.ReadBasic` inside `Mail.ReadWrite`). The host still answers \"is this
  scope in the grant?\" on both sides of the implication; the core owns the
  table itself:

    (implier-of scope) -> the scope that would cover it, or none

  The refusal is byte-faithful: a scope outside the table answers none, so
  `covered?`'s `when-let` reduces to the direct-membership check alone —
  exactly what the old `(get scope-implications scope)` produced.

  Same caveat as the sibling suites: the native compile rows assert the core
  is expressible on native, not that anything runs there."
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.kotoba-oracle :as oracle]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/connectors_core.kotoba")
(def ^:private core-source (slurp core-path))

;; The ORIGINAL scope-implications table, written out independently of the
;; new oracle calls — the cljc's `(get scope-implications scope)` is exactly
;; this function of the scope string.
(def ^:private original-table
  {"https://www.googleapis.com/auth/gmail.readonly"
   "https://www.googleapis.com/auth/gmail.modify"
   "Mail.Read" "Mail.ReadWrite"
   "Mail.ReadBasic" "Mail.ReadWrite"})

(defn- kotoba-string [s]
  (pr-str (str s)))

;; #:: raw probe: build a zero-arg wrapper around the export under test, with
;; the scope in guest shape. The export returns [:option :string], so the
;; probe's return type is that too.
(defn- call-probe [i scope]
  (str "(defn p" i " [] [:option :string] "
       "(implier-of " (kotoba-string scope) "))"))

(defn- option-value [opt]
  (when (and (vector? opt) (true? (second opt))) (nth opt 2)))

(defn- run-probes [cases]
  (let [defs (str/join "\n" (map-indexed (fn [i pl] (call-probe i pl)) cases))
        probes (str/join " " (map (fn [i] (str "p" i)) (range (count cases))))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [implier-of " probes "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map-indexed (fn [i _] [(str "p" i)
                                     (ir/execute kir (symbol (str "p" i)) [])])
                          cases))))

(def ^:private scopes
  ["https://www.googleapis.com/auth/gmail.readonly"
   "https://www.googleapis.com/auth/gmail.modify"
   "https://www.googleapis.com/auth/gmail.send"
   "Mail.Read" "Mail.ReadWrite" "Mail.ReadBasic"
   "Files.Read" "Calendars.ReadBasic" "User.Read"
   "openid" "email" "profile" "read:user" "offline_access"])

(deftest implier-of-agrees-with-the-original-table
  (let [actual (run-probes scopes)]
    (doseq [[i pl] (map-indexed vector scopes)]
      (testing pl
        (is (= (original-table pl)
               (option-value (get actual (str "p" i))))
            (str "implier-of disagrees on " pl))))))

(deftest unknown-scopes-are-none-not-throw
  ;; The contract row: scopes outside the table answer none on the core, so
  ;; the host's when-let reduces to the direct check — and the oracle call
  ;; must not throw on an unknown scope.
  (doseq [pl ["openid" "Mail.ReadWrite" "Files.Read" "read:user" "https://example.com/x"]]
    (is (nil? (option-value (get (run-probes [pl]) "p0")))
        (str "unknown scope must answer none: " pl))))

(deftest covered-agrees-with-the-original-formula
  ;; The cljc covered? contract through the ORACLE artifact path: for each
  ;; (granted, scope) pair, the host's reduction (direct membership, or the
  ;; core's implier in the grant) must agree with the original `covered?`
  ;; formula written against the original table.
  (let [grants [["https://www.googleapis.com/auth/gmail.modify"]
                ["https://www.googleapis.com/auth/gmail.readonly"]
                ["Mail.ReadWrite"]
                ["Mail.Read" "Mail.ReadWrite"]
                ["Mail.Read" "Mail.ReadBasic"]
                []]]
    (doseq [granted grants [pl scope] (map-indexed vector scopes)]
      (let [asked (boolean (contains? (set granted) scope))
            original-implier (original-table scope)
            original-implied (boolean (and original-implier
                                           (contains? (set granted)
                                                      original-implier)))
            original-covered (boolean (or asked original-implied))
            core-implier (oracle/option-value
                          (oracle/call :connectors-core 'implier-of [scope]))
            actual-covered (boolean (or asked
                                        (and core-implier
                                             (contains? (set granted)
                                                        core-implier))))]
        (testing (pr-str (list granted scope))
          (is (= original-covered actual-covered)
              (str "covered? through the shipped artifact disagrees on "
                   (pr-str (list granted scope)))))))))

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "connectors core no longer compiles for " (name target)
               " — it has probably grown a map, a set literal or a closure")))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))