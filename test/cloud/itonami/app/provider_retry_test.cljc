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
