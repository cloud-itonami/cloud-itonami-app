(ns cloud.itonami.app.bot-slo-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-slo :as bot-slo])
  (:import [java.time Instant]))

(def now (Instant/parse "2026-08-23T04:00:00Z"))
(def session {:user-id "alice" :organization-id "org-1"})

(defn- timestamp [seconds-ago]
  (str (.minusSeconds now seconds-ago)))

(defn- turn
  ([id state duration] (turn id state duration nil))
  ([id state duration error-type]
   (cond-> {:turn/id (str "turn-" id)
            :turn/bot-id "bot-1"
            :turn/state state
            :turn/phase state
            :turn/started-at (timestamp (+ 120 duration))
            :turn/updated-at (timestamp 120)}
     (not= state :running) (assoc :turn/finished-at (timestamp 120))
     error-type (assoc :turn/error-type error-type))))

(defn- state-with-turns [turns & [slo]]
  {:bots {:bots {"bot-1" {:bot/id "bot-1"
                           :bot/owner "alice"
                           :bot/organization "org-1"}}
          :turn-history {"bot-1" (vec turns)}
          :slo (or slo {})}})

(def quality-75
  {:as-of "2026-08-23T03:30:00Z"
   :sample-size 3
   :required-sample-size 20
   :source "dated read-only audit"
   :components {:accuracy {:points 18}
                :instruction-adherence {:points 16}
                :actionability {:points 15}
                :clarity {:points 14}
                :safety-calibration {:points 12}}
   :metrics {:factual-grounding-rate nil
             :instruction-adherence-rate nil
             :actionable-answer-rate nil}})

(def quality-100
  {:as-of "2026-08-23T04:00:00Z"
   :sample-size 20
   :required-sample-size 20
   :source "fixed twenty-task acceptance suite"
   :components {:accuracy {:points 25}
                :instruction-adherence {:points 20}
                :actionability {:points 20}
                :clarity {:points 20}
                :safety-calibration {:points 15}}
   :metrics {:factual-grounding-rate 0.95
             :instruction-adherence-rate 0.90
             :actionable-answer-rate 0.80}})

(deftest adr-baseline-is-reproducible-and-fails-closed
  (let [completed (map #(turn % :completed 1510) (range 107))
        timeouts (map #(turn (+ 107 %) :failed 1510 :provider/timeout) (range 55))
        budgets (map #(turn (+ 162 %) :failed 1510 :bot/tool-budget-exhausted) (range 19))
        other (map #(turn (+ 181 %) :failed 1510 :provider/error) (range 10))
        stale (map (fn [id]
                     (assoc (turn id :running 0)
                            :turn/started-at (timestamp 7200)
                            :turn/updated-at (timestamp 3600)))
                   (range 191 193))
        result (bot-slo/evaluate
                (state-with-turns (concat completed timeouts budgets other stale))
                session now quality-75)]
    (is (= :fail (:status result)))
    (is (= {:stability 55 :quality 75 :effective 42} (:scores result)))
    (is (= 193 (get-in result [:windows :hours-24 :turns])))
    (is (= 55.4 (get-in result [:windows :hours-24 :completion-rate])))
    (is (= 28.5 (get-in result [:windows :hours-24 :provider-timeout-rate])))
    (is (= 9.8 (get-in result [:windows :hours-24 :tool-budget-rate])))
    (is (= 2 (get-in result [:workforce :stale-running])))
    (is (= :baseline (get-in result [:quality :state])))
    (is (not (every? :pass? (:gates result))))))

(deftest acceptance-requires-every-gate-and-regresses-on-boundaries
  (let [turns (map #(turn % :completed 60) (range 100))
        instrumentation {:duplicate-no-op-suppressed? true
                         :stage-timing-complete? true}
        state (state-with-turns turns instrumentation)
        result (bot-slo/evaluate state session now quality-100)]
    (is (= :pass (:status result)))
    (is (= {:stability 90 :quality 100 :effective 100} (:scores result)))
    (is (every? :pass? (:gates result)))
    (testing "two percent provider timeout is outside the strict less-than gate"
      (let [mutated (update-in state [:bots :turn-history "bot-1"]
                               #(-> %
                                    (assoc-in [0 :turn/error-type] :provider/timeout)
                                    (assoc-in [1 :turn/error-type] :provider/timeout)))
            failed (bot-slo/evaluate mutated session now quality-100)]
        (is (= :fail (:status failed)))
        (is (false? (:pass? (some #(when (= :provider-timeout (:id %)) %)
                                  (:gates failed)))))))
    (testing "nineteen quality samples cannot pass a twenty-task suite"
      (let [failed (bot-slo/evaluate state session now
                                     (assoc quality-100 :sample-size 19))]
        (is (= :fail (:status failed)))
        (is (= :insufficient-sample
               (:state (some #(when (= :quality-suite (:id %)) %)
                             (:gates failed)))))))))

(deftest missing-telemetry-is-never-green
  (let [result (bot-slo/evaluate (state-with-turns []) session now nil)]
    (is (= :insufficient-sample (:status result)))
    (is (nil? (get-in result [:scores :quality])))
    (is (= :unmeasured
           (:state (some #(when (= :interactive-p90 (:id %)) %)
                         (:gates result)))))
    (is (= :unmeasured
           (:state (some #(when (= :quality-suite (:id %)) %)
                         (:gates result)))))))
