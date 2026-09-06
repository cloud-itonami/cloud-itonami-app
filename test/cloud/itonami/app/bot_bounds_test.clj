(ns cloud.itonami.app.bot-bounds-test
  "The row, and the three outcomes it must keep apart.

  Every refusal pins its `:reason`. So does every ADMISSION, which is unusual
  and deliberate: `:bounds/unbounded` and `:bounds/within` both allow the turn
  and mean opposite things — one checked a ceiling and found room, the other
  found no ceiling. A caller that folded them would report a bot as bounded
  because it was allowed to run."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-bounds :as bounds]))

(defn- turn [n] {:turn/usage {:total_tokens n}})

(deftest no-budget-is-not-the-same-answer-as-room-in-the-budget
  (let [a (bounds/admit-spend {} [(turn 999999999)])
        b (bounds/admit-spend {:bot/budget-tokens 1000000} [(turn 10)])]
    (is (:allowed? a))
    (is (:allowed? b))
    (is (= :bounds/unbounded (:reason a)))
    (is (= :bounds/within (:reason b)))
    (is (not= (:reason a) (:reason b))
        "allowed for two different reasons must not read as one")))

(deftest unbounded?-counts-what-carries-no-ceiling
  (is (bounds/unbounded? {}))
  (is (not (bounds/unbounded? {:bot/budget-tokens 1}))))

(deftest a-budget-that-is-used-up-refuses
  (let [r (bounds/admit-spend {:bot/budget-tokens 100} [(turn 60) (turn 60)])]
    (is (false? (:allowed? r)))
    (is (= :bounds/budget-exhausted (:reason r)))
    (is (= 120 (:spent r)))
    (is (= 100 (:budget r)))))

(deftest the-window-is-the-most-recent-turns-only
  ;; A budget over "the last 2 turns" must not be spent by turns 1..8.
  (let [hist (mapv turn [1000 1000 1000 1000 1000 1000 1000 5])
        r (bounds/admit-spend {:bot/budget-tokens 100 :bot/budget-window-turns 2} hist)]
    (is (= 1005 (:spent r)) "the window took the wrong turns")
    (is (false? (:allowed? r))))
  (let [hist (mapv turn [1000 1000 5 5])
        r (bounds/admit-spend {:bot/budget-tokens 100 :bot/budget-window-turns 2} hist)]
    (is (= 10 (:spent r)))
    (is (:allowed? r))))

(deftest a-turn-that-reported-no-usage-counts-as-zero-AND-is-said
  ;; It cost something. Counting it as zero is the only thing this can do with
  ;; the data it has, and hiding that is how a budget stops noticing the calls
  ;; it cannot see.
  (let [r (bounds/admit-spend {:bot/budget-tokens 100}
                              [(turn 10) {:turn/usage nil} {}])]
    (is (= 10 (:spent r)))
    (is (= 2 (:unreported r)) "the unreported turns are not reported")
    (is (= 3 (:turns r)))))

(deftest the-default-window-matches-the-history-that-is-kept
  ;; A window longer than max-turn-history would silently measure less than it
  ;; claims.
  (is (= 40 bounds/default-window-turns))
  (is (= 40 (bounds/window-turns {}))))

;; ---------------------------------------------------------------------------
;; output cap
;; ---------------------------------------------------------------------------

(deftest a-bot-may-only-narrow-the-deployment-cap
  (is (= 1024 (bounds/output-cap {:bot/max-output-tokens 1024} 16384)))
  (is (= 16384 (bounds/output-cap {:bot/max-output-tokens 99999} 16384))
      "a Bot raised its own ceiling")
  (is (= 16384 (bounds/output-cap {} 16384)) "no per-bot value leaves the deployment's")
  (is (= 512 (bounds/output-cap {:bot/max-output-tokens 512} nil))))

;; ---------------------------------------------------------------------------
;; destination
;; ---------------------------------------------------------------------------

(deftest no-host-list-is-its-own-answer
  (let [r (bounds/admit-host {} "https://example.invalid/")]
    (is (:allowed? r))
    (is (= :bounds/no-host-list (:reason r)))))

(deftest an-empty-list-denies-everything
  ;; Absent and empty are different statements: nobody wrote one, versus
  ;; somebody wrote one with nothing in it.
  (let [r (bounds/admit-host {:bot/allowed-hosts #{}} "https://example.invalid/")]
    (is (false? (:allowed? r)))
    (is (= :bounds/host-not-allowed (:reason r)))))

(deftest a-listed-host-is-allowed-and-others-are-not
  (let [b {:bot/allowed-hosts #{"itonami.cloud"}}]
    (is (:allowed? (bounds/admit-host b "https://itonami.cloud/x")))
    (is (:allowed? (bounds/admit-host b "https://ITONAMI.CLOUD:443/x")))
    (is (= :bounds/host-not-allowed (:reason (bounds/admit-host b "https://evil.example/"))))
    (testing "a suffix is not a match"
      (is (= :bounds/host-not-allowed
             (:reason (bounds/admit-host b "https://itonami.cloud.evil.example/")))))
    (testing "userinfo cannot carry an allowed name"
      (let [r (bounds/admit-host b "https://itonami.cloud@evil.example/")]
        (is (= :bounds/host-not-allowed (:reason r)))
        (is (= "evil.example" (:host r)))))
    (testing "an unreadable URL is refused with its own reason"
      (is (= :bounds/unparsable-url (:reason (bounds/admit-host b "not a url")))))))

(deftest the-row-is-four-keys-and-tools-is-not-one-of-them
  ;; `:bot/tools` already exists and is already enforced. Restating it here
  ;; would make two places answer "which tools" and they would agree until they
  ;; did not.
  (is (= #{:bot/budget-tokens :bot/budget-window-turns
           :bot/max-output-tokens :bot/allowed-hosts}
         bounds/keys*))
  (is (not (contains? bounds/keys* :bot/tools))))
