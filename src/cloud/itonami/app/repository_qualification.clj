(ns cloud.itonami.app.repository-qualification
  "Executable admission decision for ADR-0013. Evidence is explicit: missing
  production measurements fail closed rather than being inferred from unit
  tests or the System Dynamics model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.time Duration Instant]))

(def ^:dynamic *profile-violations-fn*
  "Test/embedding seam. Production resolves the common langchain contract and
  fails the repository rather than accepting a locally duplicated schema."
  nil)

(defn- profile-violations [profile]
  (let [validate (or *profile-violations-fn*
                     (requiring-resolve 'langchain.repo-profile/violations))]
    (when-not validate
      (throw (ex-info "common repository profile validator is unavailable"
                      {:type :repository-storage/profile-validator-required})))
    (validate profile)))

(defn audit-profile-roots
  "Validate each explicitly supplied deployable repository root. Missing or
  malformed profiles are ordinary failed evidence, not skipped repositories."
  [roots]
  (let [repositories
        (mapv
         (fn [root]
           (let [directory (.getCanonicalFile (io/file root))
                 profile-file (io/file directory "storage-profile.edn")]
             (try
               (when-not (.isDirectory directory)
                 (throw (ex-info "repository root is not a directory" {})))
               (when-not (.isFile profile-file)
                 (throw (ex-info "storage-profile.edn is missing" {})))
               (let [profile (edn/read-string (slurp profile-file))
                     violations (profile-violations profile)]
                 {:repository (.getPath directory)
                  :qualified? (empty? violations)
                  :repo/kind (:repo/kind profile)
                  :violations violations})
               (catch Exception error
                 {:repository (.getPath directory)
                  :qualified? false
                  :error (.getMessage error)}))))
         roots)]
    {:qualified? (and (seq repositories)
                      (every? :qualified? repositories))
     :repositories repositories
     :failed (mapv :repository (remove :qualified? repositories))}))

(defn audit-profile-inventory
  "Read an EDN vector of paths relative to the inventory file and audit the
  exact deployment set. This makes absence visible without relying on a broad
  filesystem search."
  [inventory-path]
  (let [inventory-file (.getCanonicalFile (io/file inventory-path))
        parent (.getParentFile inventory-file)
        entries (edn/read-string (slurp inventory-file))]
    (when-not (and (vector? entries) (seq entries)
                   (every? string? entries))
      (throw (ex-info "repository profile inventory must be a non-empty vector of paths"
                      {:type :repository-storage/invalid-profile-inventory
                       :inventory (.getPath inventory-file)})))
    (assoc (audit-profile-roots
            (mapv #(.getPath (io/file parent %)) entries))
           :inventory (.getPath inventory-file))))

(defn- ratio-at-least? [numerator denominator ratio]
  (and (number? numerator) (number? denominator) (pos? denominator)
       (>= (/ numerator denominator) ratio)))

(defn validate-production-attestation!
  "Require fresh, commit-addressed, cache-empty recovery evidence. Capacity
  numbers remain subject to the twelve gates after this provenance check."
  ([evidence] (validate-production-attestation! evidence nil))
  ([evidence expected-source-commit]
   (let [measured-at (try
                      (Instant/parse (:evidence/measured-at evidence))
                      (catch Exception _ nil))
        now (Instant/now)
        age (when measured-at (Duration/between measured-at now))]
    (when-not (and (= :production (:evidence/scope evidence))
                   measured-at
                   (not (.isNegative ^Duration age))
                   (not (pos? (.compareTo ^Duration age
                                          (Duration/ofDays 30))))
                   (true? (:evidence/cold-hydrate? evidence))
                   (string? (:evidence/source-commit evidence))
                   (re-matches #"[0-9a-f]{40}"
                               (:evidence/source-commit evidence))
                   (or (nil? expected-source-commit)
                       (= expected-source-commit
                          (:evidence/source-commit evidence))))
      (throw (ex-info "fresh commit-addressed production recovery evidence is required"
                      {:type :repository-storage/production-evidence-required
                       :source-commit-match?
                       (= expected-source-commit
                          (:evidence/source-commit evidence))})))
    evidence)))

(defn evaluate
  [{:keys [peak-logical-write-bps reconcile-bps local-view-apply-bps
           encrypted-output-bps sustained-sync-bps hydrate-ms rto-ms
           semantic-convergence? conflict-surfaced? datalad-audit
           vmk-rotation-payload-stable? usage-reconciliation
           transport-failure-head-stable? profiles-report
           query-backend-parity?]}]
  (let [gates
        [{:gate 1 :name :reconcile-headroom
          :qualified? (ratio-at-least? reconcile-bps peak-logical-write-bps 1.5)}
         {:gate 2 :name :local-view-headroom
          :qualified? (ratio-at-least? local-view-apply-bps reconcile-bps 1.5)}
         {:gate 3 :name :sync-headroom
          :qualified? (and (number? encrypted-output-bps)
                           (number? sustained-sync-bps)
                           (pos? sustained-sync-bps)
                           (<= encrypted-output-bps (* 0.7 sustained-sync-bps)))}
         {:gate 4 :name :hydrate-rto
          :qualified? (and (number? hydrate-ms) (number? rto-ms)
                           (pos? rto-ms) (<= hydrate-ms rto-ms))}
         {:gate 5 :name :mutation-convergence
          :qualified? (true? semantic-convergence?)}
         {:gate 6 :name :conflict-surfacing
          :qualified? (true? conflict-surfaced?)}
         {:gate 7 :name :plaintext-leak-scan
          :qualified? (true? (:qualified? datalad-audit))}
         {:gate 8 :name :vmk-rewrap
          :qualified? (true? vmk-rotation-payload-stable?)}
         {:gate 9 :name :storage-accounting
          :qualified? (and (true? (:reconciled? usage-reconciliation))
                           (= (:sealed/bytes usage-reconciliation)
                              (:physical/bytes usage-reconciliation)))}
         {:gate 10 :name :transport-before-head
          :qualified? (true? transport-failure-head-stable?)}
         {:gate 11 :name :repository-profiles
          :qualified? (true? (:qualified? profiles-report))}
         {:gate 12 :name :query-backend-parity
          :qualified? (true? query-backend-parity?)}]]
    {:qualified? (every? :qualified? gates)
     :gates gates
     :failed (mapv :gate (remove :qualified? gates))}))

(defn require-qualified! [evidence]
  (let [result (evaluate evidence)]
    (when-not (:qualified? result)
      (throw (ex-info "ADR-0013 storage cutover is not qualified"
                      {:type :repository-storage/cutover-denied
                       :qualification result})))
    result))
