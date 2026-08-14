(ns cloud.itonami.app.binding-sweep-test
  "The one timer that re-measures both authorities.

  The sweeps themselves are tested where they live. What this file owns is that
  BOTH run from one tick and that neither count is folded into the other — an
  operator reading a single number cannot tell four names and no mail domains
  from two of each."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.binding-sweep :as sweep]
            [cloud.itonami.app.domain-verification :as naming]
            [cloud.itonami.app.mail-domain-authority :as mail-authority]))

(deftest a-tick-reports-each-authority-separately
  (with-redefs [naming/recheck-all! (fn [_] {:scanned 4 :changed [] :failed []})
                mail-authority/recheck-all! (fn [] {:scanned 2 :changed []
                                                    :failed []})]
    (is (= {:naming {:scanned 4 :changed [] :failed []}
            :mail {:scanned 2 :changed [] :failed []}}
           (sweep/sweep! {})))))

(deftest both-sweeps-run-and-neither-is-skipped
  ;; The failure this guards is a tick that quietly stopped calling one of them
  ;; — the counts would still look plausible.
  (let [called (atom #{})]
    (with-redefs [naming/recheck-all! (fn [_] (swap! called conj :naming)
                                        {:scanned 0 :changed [] :failed []})
                  mail-authority/recheck-all! (fn [] (swap! called conj :mail)
                                                {:scanned 0 :changed []
                                                 :failed []})]
      (sweep/sweep! {})
      (is (= #{:naming :mail} @called)))))

(deftest the-sweep-can-be-switched-off-and-stops-cleanly
  (try
    (is (false? (:running? (sweep/start! {:domain-binding {:recheck? false}}))))
    (is (true? (:running? (sweep/start! {}))))
    (is (true? (:running? (sweep/start! {})))
        "starting twice does not stack a second executor")
    (finally (sweep/stop!)))
  (testing "and it is genuinely stopped afterwards"
    (is (false? (:running? (sweep/start! {:domain-binding {:recheck? false}}))))))
