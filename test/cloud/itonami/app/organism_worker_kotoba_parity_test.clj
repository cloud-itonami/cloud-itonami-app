(ns cloud.itonami.app.organism-worker-kotoba-parity-test
  "What binds `organism_worker.kotoba` to `organism_worker.cljc`.

  The property under test is the ORDER of `intent-decision`'s refusals. Every
  scenario below is rejected or admitted identically by both sides — but the
  point is the reason, not the verdict: reorder two `cond` clauses and every
  status stays the same while the reason an operator reads changes.

  ## The one weakness, stated rather than hidden

  The core takes predicates, not maps, so this test has to derive the record
  from the same scenario the cljc reads — `capability-granted` from the
  capability set, `organization-matches` from a string comparison, and so on.
  That derivation is written HERE, which means it is not the application's.

  It is not unchecked: the assertion compares against the cljc running over the
  original maps, so a wrong derivation shows up as a disagreement. What it does
  not prove is that the application would derive them the same way if it ever
  called the core. It does not call the core — nothing does yet — and when
  something does, that call site is what should own this derivation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.organism-worker :as ow]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-path "src/cloud/itonami/app/organism_worker.kotoba")

(def ^:private core-source (slurp core-path))

(def ^:private check-type
  (str "[:record :ao/intent-check "
       "[[:status-active :bool] [:has-id :bool] [:has-issued-by :bool] "
       "[:organization-matches :bool] [:worker-matches :bool] "
       "[:capability-granted :bool] [:has-expires-at :bool] "
       "[:expires-at :i64] [:now-ms :i64]]]"))

(defn- kotoba-string [s]
  (str \" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- kb [b] (if b "true" "false"))

(defn- run-probes [probes]
  (let [names (str/join " " (map first probes))
        defs (str/join "\n" (map (fn [[n result-type body]]
                                   (str "(defn " n " [] " result-type " " body ")"))
                                 probes))
        src (str (str/replace-first
                  core-source
                  #"\(:export \[[^\]]+\]\)"
                  (str "(:export [required-value-present? rejection-reason " names "])"))
                 "\n" defs "\n")
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _ _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ---------------------------------------------------------------------------
;; required-value-present?

(def ^:private required-value-cases ["x" "  " "" nil])

(deftest required-value-agrees
  (let [probes (map-indexed
                (fn [i v]
                  [(str "r" i) ":bool"
                   (str "(required-value-present? "
                        (if (nil? v)
                          "(option-none-of [:option :string])"
                          (str "(option-some-of [:option :string] "
                               (kotoba-string v) ")"))
                        ")")])
                required-value-cases)
        actual (run-probes probes)]
    (doseq [[i v] (map-indexed vector required-value-cases)]
      (testing (pr-str v)
        ;; the cljc's rule, read off `require-value`: nil is absent, and an
        ;; empty string is absent; whitespace is present
        (let [cljc-present? (not (or (nil? v) (and (string? v) (empty? v))))]
          (is (= cljc-present? (boolean (get actual (str "r" i))))))))))

;; ---------------------------------------------------------------------------
;; rejection-reason

(def ^:private org "org-a")
(def ^:private worker-id "worker-1")

(defn- assignment-value [status]
  {:ao.worker/id worker-id
   :ao.worker/organization org
   :ao.worker/subject "subject-1"
   :ao.worker/repository "repo-1"
   :ao.worker/kind :artificial-organism
   :ao.worker/runtime :external-supervisor
   :ao.worker/status status
   :ao.worker/capabilities #{:cap-a}
   :ao.worker/authority {:memory :organism-local
                         :lifecycle :organism-local
                         :source :repository-local}})

(def ^:private now-ms 1000)

(def ^:private scenarios
  "Each row turns exactly one thing wrong, plus rows where several are wrong at
  once — those are the ones that pin the ORDER rather than the rule."
  [{:label "admitted" :status :active :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "worker not active" :status :retired :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "no id" :status :active :id nil :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "no issued-by" :status :active :id "i1" :issued-by nil
    :organization org :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "other organization" :status :active :id "i1" :issued-by "u1"
    :organization "org-b" :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "other worker" :status :active :id "i1" :issued-by "u1"
    :organization org :worker "worker-2" :capability :cap-a :expires-at 2000}
   {:label "capability not granted" :status :active :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-b :expires-at 2000}
   {:label "expired" :status :active :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 500}
   {:label "expires exactly now" :status :active :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at now-ms}
   {:label "no expiry" :status :active :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at nil}
   ;; Order-pinning rows: more than one thing wrong at once. There must be one
   ;; for every ADJACENT pair in the precedence, or that pair is not pinned and
   ;; a reorder passes.
   ;;
   ;; Measured: the first version of this table had no row where the capability
   ;; and the expiry were both wrong, and moving `:intent-expired` above
   ;; `:capability-not-granted` in the cljc changed nothing that any assertion
   ;; could see — 20 tests, 93 assertions, 0 failures against a genuinely
   ;; reordered `cond`. The gate was theatre for that one pair until this row
   ;; existed.
   {:label "inactive AND no id" :status :retired :id nil :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "inactive AND expired" :status :retired :id "i1" :issued-by "u1"
    :organization org :worker worker-id :capability :cap-a :expires-at 500}
   {:label "capability not granted AND expired" :status :active :id "i1"
    :issued-by "u1" :organization org :worker worker-id
    :capability :cap-b :expires-at 500}
   {:label "no id AND other organization" :status :active :id nil :issued-by "u1"
    :organization "org-b" :worker worker-id :capability :cap-a :expires-at 2000}
   {:label "other organization AND other worker" :status :active :id "i1"
    :issued-by "u1" :organization "org-b" :worker "worker-2"
    :capability :cap-a :expires-at 2000}
   {:label "out of scope AND expired" :status :active :id "i1" :issued-by "u1"
    :organization org :worker "worker-2" :capability :cap-b :expires-at 500}])

(defn- intent-of [{:keys [id organization worker capability issued-by expires-at]}]
  {:intent/id id :intent/organization organization :intent/worker worker
   :intent/capability capability :intent/issued-by issued-by
   :intent/expires-at expires-at})

(defn- check-literal
  "Derive the core's predicates from the scenario. See the namespace docstring
  for why this derivation living here is a real limitation."
  [{:keys [status id issued-by organization worker capability expires-at]}]
  (let [a (assignment-value status)]
    (str "(record-new " check-type " "
         (kb (= :active (:ao.worker/status a))) " "
         (kb (some? id)) " "
         (kb (some? issued-by)) " "
         (kb (= organization (:ao.worker/organization a))) " "
         (kb (= worker (:ao.worker/id a))) " "
         (kb (contains? (:ao.worker/capabilities a) capability)) " "
         (kb (some? expires-at)) " "
         (or expires-at 0) " " now-ms ")")))

(defn- cljc-reason [scenario]
  (let [r (ow/intent-decision (assignment-value (:status scenario))
                              (intent-of scenario)
                              now-ms)]
    (if (= :admitted (:intent/status r))
      :admitted
      (:intent/reason r))))

(deftest rejection-reason-agrees-on-every-scenario
  (let [probes (map-indexed (fn [i s]
                              [(str "k" i) ":keyword"
                               (str "(rejection-reason " (check-literal s) ")")])
                            scenarios)
        actual (run-probes probes)]
    (doseq [[i s] (map-indexed vector scenarios)]
      (testing (:label s)
        (is (= (cljc-reason s) (get actual (str "k" i)))
            "organism_worker.cljc and organism_worker.kotoba disagree")))))

(deftest expiry-is-checked-last
  ;; Stated directly: an intent that is out of scope AND expired must say it is
  ;; out of scope. This is the property a reordered `cond` breaks silently.
  (let [s (first (filter #(= "out of scope AND expired" (:label %)) scenarios))]
    (is (= :worker-boundary (cljc-reason s)))))

(deftest an-expiry-equal-to-now-is-expired
  ;; `(<= expires-at now-ms)` in the cljc, `(> expires-at now-ms)` for admitted
  ;; in the core — the boundary is the one place those two spellings could
  ;; disagree, so it gets its own row.
  (let [s (first (filter #(= "expires exactly now" (:label %)) scenarios))]
    (is (= :intent-expired (cljc-reason s)))))

;; ---------------------------------------------------------------------------
;; the core stays a core

(deftest decision-core-compiles-for-both-native-isas
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))
          (str "organism-worker decision core no longer compiles for "
               (name target))))))

(deftest decision-core-compiles-for-portable-targets
  (doseq [target [:wasm32-kotoba-v1 :js-kotoba-v1]]
    (testing (name target)
      (is (some? (:kir (compiler/compile-source core-source target {})))))))
