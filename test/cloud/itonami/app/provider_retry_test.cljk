(ns cloud.itonami.app.provider-retry-test
  "Which failed model requests are worth sending again.

  Measured 2026-08-27: 101 turns failed at `:provider/http-error`. 91 were 502
  and had ALREADY been retried -- once, 250 ms later, into the same gateway.
  Of the seven 400s, two said `unavailable_error` / `code 503` (the model was
  loading) and were never retried at all, while three were authentication
  failures that must never be."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.provider-retry :as retry]))

;; ── the body decides when it has something to say ──────────────────────────

(deftest a-loading-model-is-retried-even-though-the-status-says-400
  ;; The exact body, from the store:
  ;;   HTTP 400 {:message "Loading model", :type "unavailable_error", :code 503}
  ;; The provider named the condition and named it transient. Nothing read it.
  (is (retry/transient-response?
       400 {:message "Loading model" :type "unavailable_error" :code 503})))

(deftest a-spent-basho-budget-is-never-retried-whatever-its-type
  ;; murakumo answers 503 for a spent monthly budget and for an unattested
  ;; backend (ADR-0092). Neither clears in a retry window; the fallback route
  ;; is the answer. Pinned by CODE so a change of `type` upstream cannot
  ;; turn either into three calls and six seconds of sleep per turn.
  (doseq [code ["self_model_monthly_budget_exhausted"
                "self_model_backend_unavailable"
                "self_model_budget_unconfigured"]]
    (is (false? (retry/transient-response?
                 503 {:error {:type "server_error" :code code}}))
        code)
    (is (false? (retry/transient-response?
                 503 {:error {:type "invalid_request_error" :code code}}))
        code))
  (testing "an ordinary 503 without such a code is still transient"
    (is (true? (retry/transient-response? 503 {:error {:message "upstream"}})))))

(deftest an-authentication-failure-is-never-retried
  ;; The control that keeps the rule above from being a hole. These arrived on
  ;; the SAME status as the case it exists for, so "retry 400s" would have
  ;; turned one refusal into three against a rate limiter.
  (testing "by type"
    (is (not (retry/transient-response?
              400 {:message "Invalid API Key" :type "authentication_error" :code 401}))))
  (testing "nested under :error, which is the other shape providers use"
    (is (not (retry/transient-response?
              400 {:error {:type "authentication_error" :message "Unauthorized"}}))))
  (testing "and a permanent type wins even when the body also claims a transient code"
    (is (not (retry/transient-response?
              400 {:type "authentication_error" :code 503}))
        "otherwise a body could smuggle itself through by naming both")))

(deftest a-permanent-type-is-not-retried-on-a-transient-status
  ;; The status set is the fallback, not an override.
  (is (not (retry/transient-response?
            503 {:type "invalid_request_error" :message "unknown model"}))))

;; ── the status still decides when the body says nothing ────────────────────

(deftest a-gateway-page-is-retried-on-its-status-alone
  ;; 91 of the 101. None of these bodies is JSON, so the decoder hands back a
  ;; raw string and there is no type to read.
  (testing "Cloudflare"
    (is (retry/transient-response? 502 {:raw "error code: 502 "}))
    (is (retry/transient-response? 504 {:raw "error code: 524 "})))
  (testing "Modal"
    (is (retry/transient-response? 502 {:raw "modal-http: unreachable"})))
  (testing "and a plain 400 with nothing to say is still not retried"
    (is (not (retry/transient-response? 400 {:raw ""})))
    (is (not (retry/transient-response? 404 {})))))

;; ── how long ───────────────────────────────────────────────────────────────

(deftest the-backoff-is-seconds-not-milliseconds
  ;; The defect in one line: every one of the 91 retries happened 250 ms after
  ;; the failure, which is shorter than any recovery measured here.
  (is (= [2000 4000 8000 8000] (mapv retry/retry-delay-ms [0 1 2 3])))
  (is (every? #(>= % 2000) (map retry/retry-delay-ms (range 4)))
      "nothing in this policy retries inside a second"))

(deftest attempts-are-bounded
  (testing "a transient failure is retried up to the bound"
    (is (retry/retry? 0 502 {:raw "error code: 502 "}))
    (is (retry/retry? 1 502 {:raw "error code: 502 "})))
  (testing "and then stops -- the resident workforce has capacity one"
    (is (not (retry/retry? 2 502 {:raw "error code: 502 "})))
    (is (not (retry/retry? 9 502 {:raw "error code: 502 "}))))
  (testing "a permanent failure is not retried even on the first attempt"
    (is (not (retry/retry? 0 400 {:type "authentication_error"})))))

(deftest the-total-wait-is-bounded-and-known
  ;; Named because it is the cost this policy pays on a capacity-one slot, and
  ;; a bound nobody states is a bound nobody checks.
  (is (= 6000 (reduce + (map retry/retry-delay-ms (range (dec retry/max-attempts)))))
      "two retries at 2s and 4s"))

;; ── an answer that was cut off is not an answer that was wrong ─────────
;;
;; `:provider/invalid-tool-arguments` was the most common live failure on the
;; resident fleet on 2026-08-28 -- 20 of 45 failed turns -- and every
;; reproduction was `:max-output-tokens 1024` cutting a `decision_frame` whose
;; frames need 1350-1676 tokens. The model authored nothing malformed.

(deftest an-exhausted-output-budget-is-detected-from-any-of-three-signals
  (let [exhausted? retry/output-budget-exhausted?]
    (testing "the provider saying `length`"
      (is (exhausted? "length" nil nil nil))
      (is (exhausted? "length" 10 4096 false)))

    (testing "the count reaching the cap the request set"
      ;; Four of six measured streamed calls cut at exactly max_tokens still
      ;; said `tool_calls`: a server that emitted a tool call reports one.
      (is (exhausted? "tool_calls" 1024 1024 false))
      (is (exhausted? "tool_calls" 1025 1024 false) "a cap can be reported as passed"))

    (testing "the JSON having ended early"
      ;; The signal that needs no agreement about numbers. api.murakumo.cloud
      ;; enforces its OWN 2048 ceiling -- requests for 3072, 4096, 8192 and
      ;; 16384 all returned completion_tokens 2048 -- so an app configured
      ;; above 2048 never sees its own cap reached and the count goes quiet in
      ;; exactly the deployment that needs it most.
      (is (exhausted? "tool_calls" 2048 8192 true))
      (is (not (exhausted? "tool_calls" 2048 8192 false))
          "without the third signal this deployment is the blind spot"))

    (testing "an unexhausted budget is not one"
      (is (not (exhausted? "tool_calls" 1023 1024 false)))
      (is (not (exhausted? "stop" 40 2048 false))))

    (testing "missing telemetry answers false rather than guessing"
      ;; A false answer only leaves the original, less specific classification
      ;; in place; a true one would blame a cap for a model's mistake.
      (is (not (exhausted? nil nil nil nil)))
      (is (not (exhausted? "tool_calls" 1024 nil nil)))
      (is (not (exhausted? "tool_calls" nil 1024 nil)))
      (is (not (exhausted? "tool_calls" 40 2048 nil))))))

;; ── how many output tokens a model will actually give ──────────────────
;;
;; `/infer/models` declares `context: 262144` for murakumo-main and NO output
;; limit at all. Measured 2026-08-28, one request each with a prompt written to
;; run long: max_tokens 3072, 4096, 8192 and 16384 every one returned
;; completion_tokens 2048, finish_reason length. Two other selectors on the
;; same fleet answered the same 2048. The number exists nowhere but in the
;; shape of a reply.

(deftest a-limit-may-be-one-number-or-one-per-model
  (testing "a scalar applies to every model, as it always did"
    (is (= 2048 (retry/model-scoped 2048 "murakumo-main")))
    (is (= 2048 (retry/model-scoped 2048 "anything-else"))))
  (testing "a map applies per model, the shape :context-window-tokens uses"
    (is (= 2048 (retry/model-scoped {"murakumo-main" 2048 "big" 32768} "murakumo-main")))
    (is (= 32768 (retry/model-scoped {"murakumo-main" 2048 "big" 32768} "big"))))
  (testing "a model the map does not name is unconfigured, not defaulted to a sibling"
    ;; A limit measured on one deployment is evidence about that deployment.
    (is (nil? (retry/model-scoped {"murakumo-main" 2048} "some-other-model"))))
  (is (nil? (retry/model-scoped nil "m")))
  (is (nil? (retry/model-scoped "2048" "m")) "a string is not a token count"))

(deftest a-reply-that-stopped-short-of-our-cap-reveals-the-servers-own
  (testing "the measured case"
    (is (= 2048 (retry/served-output-ceiling "length" 2048 8192)))
    (is (= 2048 (retry/served-output-ceiling "length" 2048 16384))))
  (testing "reaching the cap WE set reveals nothing about theirs"
    (is (nil? (retry/served-output-ceiling "length" 2048 2048))))
  (testing "stopping for any other reason means the model finished"
    (is (nil? (retry/served-output-ceiling "stop" 300 8192)))
    (is (nil? (retry/served-output-ceiling "tool_calls" 300 8192))))
  (testing "nothing is inferred from absent or empty telemetry"
    (is (nil? (retry/served-output-ceiling "length" nil 8192)))
    (is (nil? (retry/served-output-ceiling "length" 2048 nil)))
    (is (nil? (retry/served-output-ceiling "length" 0 8192))
        "zero tokens is a failure to generate, not a ceiling of zero")))

(deftest the-output-budget-follows-the-model-and-then-what-it-will-give
  (let [budget (fn [m model] (retry/output-token-budget m model))]
    (testing "resolution runs most specific first"
      (is (= 1024 (budget {:requested 1024 :configured 2048 :default 512} "m")))
      (is (= 2048 (budget {:configured 2048 :default 512} "m")))
      (is (= 512 (budget {:default 512} "m"))))

    (testing "per-model configuration resolves per model"
      (is (= 32768 (budget {:configured {"m" 2048 "big" 32768} :default 512} "big")))
      (is (= 512 (budget {:configured {"m" 2048} :default 512} "unnamed"))
          "an unnamed model falls through to the default, not to a sibling's cap"))

    (testing "an observed ceiling bounds it, because asking for more gets the same reply"
      ;; This is the measured murakumo case: configured 8192, served 2048.
      (is (= 2048 (budget {:configured 8192 :default 512 :observed-ceiling 2048} "m")))
      (is (= 1024 (budget {:configured 1024 :default 512 :observed-ceiling 2048} "m"))
          "a ceiling above our own number does not raise it"))

    (testing "the context window bounds it, since unfittable output was never available"
      (is (= 4096 (budget {:configured 32768 :default 512 :context-window 4096} "m")))
      (is (= 2048 (budget {:configured 32768 :default 512
                           :observed-ceiling 2048 :context-window 4096} "m"))
          "the lowest bound that is known wins"))

    (testing "an unknown bound is not a bound of zero"
      (is (= 8192 (budget {:configured 8192 :default 512
                           :observed-ceiling nil :context-window nil} "m"))))

    (testing "the result is never zero or negative"
      (is (= 1 (budget {:configured 8192 :default 512 :observed-ceiling 0} "m"))))

    (testing "no number anywhere is no budget, not a made-up one"
      (is (nil? (budget {:configured nil :default nil} "m"))))))

(deftest a-ceiling-is-a-number-seen-twice-not-a-single-truncation
  ;; `finish_reason length` below the requested cap does not prove a server
  ;; ceiling: a prompt bigger than the ESTIMATE leaves less window than the
  ;; arithmetic reserved, and a reasoning model can spend the allowance on
  ;; thinking that `completion_tokens` does not count. One such reply against a
  ;; requested 8192 could stop at 300 -- and a bound taken from it would cap
  ;; every later request at 300, truncating all of them.
  ;;
  ;; The reading is still correct; what the caller does with ONE reading is the
  ;; part that had to change, so this states the contract the caller relies on.
  (testing "the reading itself is per-reply and says nothing about repetition"
    (is (= 300 (retry/served-output-ceiling "length" 300 8192)))
    (is (= 2048 (retry/served-output-ceiling "length" 2048 8192))))
  (testing "and a budget bounded by a ceiling is bounded by exactly that number"
    (is (= 300 (retry/output-token-budget
                {:configured 8192 :default 512 :observed-ceiling 300} "m")))))
