(ns cloud.itonami.app.bot-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-cache :as bot-cache])
  (:import [java.time Instant]))

(def now (Instant/parse "2026-09-03T04:00:00Z"))
(def session {:user-id "alice" :organization-id "org-1"})

(defn- timestamp [seconds-ago]
  (str (.minusSeconds now seconds-ago)))

(defn- turn
  ([id] (turn id nil nil))
  ([id usage] (turn id usage nil))
  ([id usage model]
   (cond-> {:turn/id (str "turn-" id)
            :turn/bot-id "bot-1"
            :turn/state :completed
            :turn/started-at (timestamp 3600)
            :turn/updated-at (timestamp 60)}
     usage (assoc :turn/usage usage)
     model (assoc :turn/model model))))

(defn- openai-usage [prompt cached]
  {:prompt_tokens prompt :completion_tokens 10 :total_tokens (+ prompt 10)
   :prompt_tokens_details {:cached_tokens cached}})

(defn- anthropic-usage [prompt cached]
  {:prompt_tokens prompt :completion_tokens 10 :total_tokens (+ prompt 10)
   :cache_read_input_tokens cached})

(defn- state-with-turns [turns]
  {:bots {:bots {"bot-1" {:bot/id "bot-1"
                          :bot/owner "alice"
                          :bot/organization "org-1"}
                 "other" {:bot/id "other"
                          :bot/owner "mallory"
                          :bot/organization "org-1"}}
          :turn-history {"bot-1" (vec turns)
                         "other" (vec (map #(turn (+ 9000 %) (openai-usage 1000 900))
                                           (range 3)))}}})

(defn- rate [result]
  (get-in result [:windows :hours-24 :hit-rate]))

(deftest both-wire-shapes-read-as-cached-tokens
  (testing "OpenAI shape: prompt_tokens_details.cached_tokens"
    (is (= 700 (bot-cache/cached-usage-value (openai-usage 1000 700)))))
  (testing "Anthropic shape: cache_read_input_tokens"
    (is (= 700 (bot-cache/cached-usage-value (anthropic-usage 1000 700)))))
  (testing "string-keyed shapes read the same way"
    (is (= 700 (bot-cache/cached-usage-value
                {"prompt_tokens" 1000
                 "prompt_tokens_details" {"cached_tokens" 700}}))))
  (testing "absent cache data is unmeasured, not zero"
    (is (nil? (bot-cache/cached-usage-value {:prompt_tokens 1000})))
    (is (nil? (bot-cache/cached-usage-value nil))))
  (testing "an empty details map is a measured zero"
    (is (zero? (bot-cache/cached-usage-value
                {:prompt_tokens 1000 :prompt_tokens_details {}})))))

(deftest denominator-is-the-provider-total-not-a-hand-derived-remainder
  ;; A cache read is real input against the plan (the bridge rule), so the
  ;; denominator is `prompt_tokens` as the provider reports it.
  (let [usage (openai-usage 1000 700)]
    (is (= 1000 (bot-cache/usage-prompt-tokens usage))))
  (is (nil? (bot-cache/usage-prompt-tokens {:cache_read_input_tokens 500}))))

(deftest hit-rate-sums-both-sides-of-the-ratio-over-the-window
  (let [turns [(turn 1 (openai-usage 1000 700) "z-ai/glm-5.3-flash")
               (turn 2 (anthropic-usage 3000 2400) "z-ai/glm-5.3-flash")
               (turn 3 (openai-usage 500 0) "z-ai/glm-5.3-flash")]
        w (get-in (bot-cache/evaluate (state-with-turns turns) session now)
                  [:windows :hours-24])]
    (is (= 3 (:measured-turns w)))
    (is (= 4500 (:prompt-tokens w)))
    (is (= 3100 (:cached-tokens w)))
    ;; 3100/4500 = 68.888... -> 68.9, computed over totals, never averaged
    ;; from per-turn rates (68.7 would be the wrong average).
    (is (= 68.9 (:hit-rate w)))))

(deftest missing-usage-fails-closed
  (let [w (get-in (bot-cache/evaluate
                   (state-with-turns [(turn 1 nil "z-ai/glm-5.3-flash")
                                      (turn 2 {:completion_tokens 5} nil)])
                   session now)
                  [:windows :hours-24])]
    (is (= 2 (:turns w)))
    (is (zero? (:measured-turns w)))
    (is (nil? (:hit-rate w)) "no measured turns is :unmeasured, never 0")
    (is (nil? (:prompt-tokens w)))
    (is (= [] (:by-model w)))))

(deftest a-zero-hit-is-a-measurement-and-a-real-rate
  ;; A provider that answered with no cache hit must read 0.0 — distinct
  ;; from the nil the unmeasured path returns.
  (let [w (get-in (bot-cache/evaluate
                   (state-with-turns [(turn 1 (openai-usage 2000 0))])
                   session now)
                  [:windows :hours-24])]
    (is (= 1 (:measured-turns w)))
    (is (= 0.0 (:hit-rate w)))))

(deftest only-turns-with-both-sides-count
  ;; A turn that reports cached tokens but no prompt total cannot be placed
  ;; in either ratio and is not manufactured into one.
  (let [w (get-in (bot-cache/evaluate
                   (state-with-turns [(turn 1 (openai-usage 1000 500))
                                      (turn 2 {:cache_read_input_tokens 500})])
                   session now)
                  [:windows :hours-24])]
    (is (= 1 (:measured-turns w)))
    (is (= 50.0 (:hit-rate w)))))

(deftest the-model-is-part-of-the-breakdown
  ;; The provider cache is keyed per model; a rotation shows up as a second
  ;; row with its own rate instead of blending into one number.
  (let [by-model (get-in (bot-cache/evaluate
                          (state-with-turns
                           [(turn 1 (openai-usage 1000 800) "z-ai/glm-5.3-flash")
                            (turn 2 (openai-usage 1000 100) "qwen3.8-max")])
                          session now)
                         [:windows :hours-24 :by-model])]
    (is (= [{:model "qwen3.8-max" :turns 1 :prompt-tokens 1000
             :cached-tokens 100 :hit-rate 10.0}
            {:model "z-ai/glm-5.3-flash" :turns 1 :prompt-tokens 1000
             :cached-tokens 800 :hit-rate 80.0}]
           by-model))))

(deftest window-boundaries-match-bot-slo
  (let [inside (turn 1 (openai-usage 100 50))
        edge (assoc (turn 2 (openai-usage 100 50))
                    :turn/started-at (timestamp (* 24 3600)))
        outside (assoc (turn 3 (openai-usage 100 50))
                       :turn/started-at (timestamp (inc (* 24 3600))))]
    (testing "the edge second is inside, one second past is out"
      (let [w (get-in (bot-cache/evaluate
                       (state-with-turns [inside edge outside]) session now)
                      [:windows :hours-24])]
        (is (= 2 (:measured-turns w)))
        ;; every measured turn is 100/50, so the pooled rate is 50.0 however
        ;; many of them land in the window
        (is (= 50.0 (:hit-rate w)))))))

(deftest an-owners-rate-never-reads-another-owners-turns
  (let [result (bot-cache/evaluate (state-with-turns []) session now)
        w (get-in result [:windows :hours-24])]
    ;; mallory's three 90%-hit turns exist in the same partition.
    (is (nil? (:hit-rate w)))
    (is (zero? (:measured-turns w)))))

(deftest seven-day-window-sees-older-turns
  (let [old (assoc (turn 1 (openai-usage 1000 500))
                   :turn/started-at (timestamp (* 72 3600)))
        result (bot-cache/evaluate (state-with-turns [old]) session now)]
    (is (nil? (get-in result [:windows :hours-24 :hit-rate]))
        "72h ago is outside the 24h window — unmeasured, not 0")
    (is (= 50.0 (get-in result [:windows :days-7 :hit-rate])))
    (is (= 1 (get-in result [:windows :days-7 :measured-turns])))))
