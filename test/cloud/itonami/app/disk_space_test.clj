(ns cloud.itonami.app.disk-space-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.disk-space :as disk-space]))

(deftest maintenance-is-a-fixed-threshold-operation
  (testing "healthy disks are observed without invoking deletion"
    (let [calls (atom [])]
      (with-redefs [disk-space/usable-bytes (constantly (+ disk-space/threshold-bytes 1))
                    disk-space/run-helper! (fn [mode] (swap! calls conj mode))]
        (let [result (disk-space/maintain!)]
          (is (= "none" (:action result)))
          (is (empty? @calls))))))

  (testing "pressure invokes only the reviewed extended cleanup mode"
    (let [measurements (atom [(- disk-space/threshold-bytes 1024)
                              (+ disk-space/threshold-bytes 2048)])
          calls (atom [])]
      (with-redefs [disk-space/usable-bytes #(let [n (first @measurements)]
                                                (swap! measurements rest)
                                                n)
                    disk-space/run-helper! (fn [mode]
                                              (swap! calls conj mode)
                                              {:exit 0 :output "ok"})]
        (let [result (disk-space/maintain!)]
          (is (= "cleanup" (:action result)))
          (is (= ["apply-extended"] @calls))
          (is (pos? (:reclaimed-bytes result)))
          (is (false? (get-in result [:after :pressure?])))))))

  (testing "a scheduler-supplied status is not measured a second time"
    (let [usable-calls (atom 0)
          before {:schema "cloud.itonami.app.disk-space.v1"
                  :usable-bytes (- disk-space/threshold-bytes 1024)
                  :threshold-bytes disk-space/threshold-bytes
                  :pressure? true}]
      (with-redefs [disk-space/usable-bytes
                    (fn [] (swap! usable-calls inc)
                      (+ disk-space/threshold-bytes 2048))
                    disk-space/run-helper! (constantly {:exit 0 :output "ok"})]
        (let [result (disk-space/maintain! before)]
          (is (= 1 @usable-calls) "only the post-cleanup measurement remains")
          (is (= before (:before result)))
          (is (= "cleanup" (:action result))))))))

(deftest helper-modes-are-not-an-arbitrary-process-surface
  (is (= :disk-space/invalid-mode
         (:type (ex-data
                 (try (disk-space/run-helper! "../../anything")
                      (catch Exception error error)))))))
