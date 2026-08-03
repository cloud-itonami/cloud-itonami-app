(ns cloud.itonami.app.repository-qualification-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.repository-qualification :as qualification]))

(def qualified-evidence
  {:peak-logical-write-bps 100
   :reconcile-bps 160
   :local-view-apply-bps 250
   :encrypted-output-bps 60
   :sustained-sync-bps 100
   :hydrate-ms 900
   :rto-ms 1000
   :semantic-convergence? true
   :conflict-surfaced? true
   :datalad-audit {:qualified? true}
   :vmk-rotation-payload-stable? true
   :usage-reconciliation {:reconciled? true
                          :sealed/bytes 42 :physical/bytes 42}
   :transport-failure-head-stable? true
   :profiles-report {:qualified? true
                     :repositories [{:repository "cloud-itonami-app"
                                     :qualified? true}]}
   :query-backend-parity? true})

(deftest all-twelve-gates-are-required
  (let [result (qualification/evaluate qualified-evidence)]
    (is (:qualified? result))
    (is (= (range 1 13) (map :gate (:gates result)))))
  (let [result (qualification/evaluate
                (dissoc qualified-evidence :hydrate-ms))]
    (is (false? (:qualified? result)))
    (is (= [4] (:failed result)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not qualified"
                          (qualification/require-qualified!
                           (dissoc qualified-evidence :hydrate-ms))))))

(deftest repository-profile-audit-fails-closed
  (binding [qualification/*profile-violations-fn* (constantly [])]
    (let [current (qualification/audit-profile-roots ["."])
          missing (qualification/audit-profile-roots ["target"])]
      (is (:qualified? current))
      (is (= :cloud-itonami
             (get-in current [:repositories 0 :repo/kind])))
      (is (false? (:qualified? missing)))
      (is (seq (:failed missing))))))

(deftest production-attestation-is-fresh-cold-and-commit-addressed
  (let [valid {:evidence/scope :production
               :evidence/measured-at (str (java.time.Instant/now))
               :evidence/source-commit (apply str (repeat 40 "a"))
               :evidence/cold-hydrate? true}]
    (is (= valid (qualification/validate-production-attestation! valid)))
    (doseq [invalid [(assoc valid :evidence/cold-hydrate? false)
                     (assoc valid :evidence/source-commit "main")
                     (assoc valid :evidence/measured-at "2020-01-01T00:00:00Z")]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"production recovery evidence"
           (qualification/validate-production-attestation! invalid))))))
