(ns cloud.itonami.app.agent-eval
  "Result-based loop evaluation. Scores are projections, not model opinions."
  (:require [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.agent-eval.v1")

(defn evaluate
  [{:keys [verification provider-events result duration-ms]}]
  (let [types (frequencies (map :type provider-events))
        artifacts (get types :artifact/changed 0)
        tools (get types :tool/completed 0)
        failures (get types :tool/failed 0)
        verified? (true? (:passed? verification))
        status (:status verification)
        raw-score (+ (if verified? 45 0)
                     (min 20 (* 20 artifacts))
                     (min 20 (* 5 tools))
                     (- (min 30 (* 10 failures)))
                     (if (= :succeeded status) 15 0))
        score (-> raw-score (max 0) (min 100))]
    {:schema schema
     :score score
     :grade (cond (>= score 85) :a
                  (>= score 70) :b
                  (>= score 50) :c
                  :else :needs-kaizen)
     :verified? verified?
     :status status
     :artifact-events artifacts
     :successful-tools tools
     :failed-tools failures
     :duration-ms duration-ms
     :tokens (get-in result [:usage :total_tokens])
     :evaluated-at (store/now)}))

(defn record! [run-id evaluation]
  (store/transact! assoc-in [:agent-loops :runs run-id :evaluation] evaluation)
  evaluation)
