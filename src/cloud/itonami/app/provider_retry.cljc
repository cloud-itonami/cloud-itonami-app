(ns cloud.itonami.app.provider-retry
  "Whether a failed model request is worth sending again, and how long to wait.

  Zero dependencies, for the same reason as `host-bounds`: these are the
  judgements that have been wrong in production, and `bin/test-portable-cljs`
  grants no classpath beyond `src` and `test`.

  ## What was measured, 2026-08-27

  101 turns failed at `:provider/http-error`, and the retry that was supposed
  to absorb them was one attempt after a fixed 250 ms.

  | status | n | what the body said |
  |---|---|---|
  | 502 | 91 | Cloudflare `error code: 502`, `error code: 524`, and Modal `modal-http` -- none of it JSON |
  | 400 | 7 | 2 `unavailable_error` / `code 503` (the model was LOADING), 3 authentication, 2 blank |
  | 500 | 2 | |

  Two separate defects, and the 400 row holds both.

  1. **250 ms is shorter than any gateway recovery.** 502 was already in the
     retryable set, so all 91 of those were retried -- once, a quarter of a
     second later, into the same failure. A Cloudflare 502/524 or a Modal cold
     start is measured in seconds.

  2. **The decision read the status and not the body.** A provider that
     answers `HTTP 400 {:message \"Loading model\", :type \"unavailable_error\",
     :code 503}` is telling you exactly when to come back, in a field nothing
     looked at. It was never retried.

  The 400 row is also why this cannot be `(not (client-error? status))`: the
  other three 400s were `Unauthorized` and `Invalid API Key`, and an API key
  does not become valid because you asked twice. Retrying an authentication
  failure turns one refusal into three, against a rate limiter.

  ## The policy is the fleet's, not a new one

  `network-awai/local-murakumo` already owns a resident-bot retry policy in
  `kotoba/grok_bot_runtime_core.kotoba`: at most three attempts, backing off
  2s, 4s, 8s. Same shape here, deliberately -- a second answer to `how long
  should a bot wait for a flaky model` is a second thing to keep true."
  (:require [clojure.string :as str]))

(def retryable-http-statuses
  "Statuses that are transient regardless of what the body says."
  #{429 500 502 503 504})

(def max-attempts
  "Total attempts, including the first.

  Three, from the fleet policy cited above. The cost is bounded and worth
  naming: the resident workforce has capacity one, so a request that backs off
  holds the slot for up to 14 seconds. A turn that FAILS costs the whole tick
  and a requeue, which is far more, and 91 of these happened over twelve days."
  3)

(defn retry-delay-ms
  "Milliseconds to wait before ATTEMPT (0-based) is retried.

  2s, 4s, 8s -- `grok-bot-runtime-core/retry-due-at`. The old value was a
  fixed 250 ms, which is shorter than every recovery this has been measured
  against."
  [attempt]
  (cond
    (<= attempt 0) 2000
    (= attempt 1) 4000
    :else 8000))

(def ^:private transient-error-types
  "Error `type` values that name a condition which passes on its own."
  #{"unavailable_error" "overloaded_error" "rate_limit_error" "server_error"})

(def ^:private permanent-error-types
  "Named so that a body claiming transience cannot smuggle these through.

  An authentication failure is the case this exists for: it arrived as HTTP
  400, which is not retryable by status either -- but the rule below reaches
  PAST the status into the body, and without this it would reach past it in
  both directions."
  #{"authentication_error" "permission_error" "invalid_request_error"
    "not_found_error"})

(defn- body-error-type [parsed]
  (let [t (or (get-in parsed [:error :type]) (:type parsed))]
    (when (string? t) (str/lower-case t))))

(defn- body-error-code [parsed]
  (let [c (or (get-in parsed [:error :code]) (:code parsed))]
    (when (integer? c) c)))

(defn transient-response?
  "Is this failed response worth sending again?

  STATUS is the HTTP status; PARSED is the decoded body, or whatever the
  decoder produced when the body was not JSON (which is most of them -- a
  Cloudflare 502 page is plain text).

  A body that NAMES itself permanent is permanent whatever else it says. Then
  a body that names itself transient is transient even when the status does
  not say so, which is the `HTTP 400 / unavailable_error / code 503` case.
  Otherwise the status decides, as before."
  [status parsed]
  (let [type (body-error-type parsed)
        code (body-error-code parsed)]
    (cond
      (contains? permanent-error-types type) false
      (contains? transient-error-types type) true
      (and code (contains? retryable-http-statuses code)) true
      :else (contains? retryable-http-statuses status))))

(defn retry?
  "Should ATTEMPT (0-based, already made) be followed by another one?"
  [attempt status parsed]
  (and (< (inc attempt) max-attempts)
       (transient-response? status parsed)))

;; ── an answer that was cut off is not an answer that was wrong ─────────

(defn output-budget-exhausted?
  "Did the model stop because it ran out of room, rather than finish badly?

  Three independent signals, any one of which is sufficient. None is necessary,
  because each has a measured way of being absent.

  **The provider saying `length`.** Measured 2026-08-28 against
  api.murakumo.cloud: of six streamed `decision_frame` calls cut at exactly
  `max_tokens`, two said `length` and four said `tool_calls`. A server that has
  emitted a tool call reports having emitted one, and whether it also mentions
  the cap is a detail of its parser.

  **The count reaching the cap the request set.** Reliable when the request's
  cap is the one that actually applied -- and that is exactly the assumption
  that failed. api.murakumo.cloud enforces its OWN ceiling of 2048 output
  tokens: requests for 3072, 4096, 8192 and 16384 all returned
  `completion_tokens` 2048. So an application configured above 2048 gets cut at
  2048 while believing its budget is 8192, `2048 >= 8192` is false, and this
  signal goes quiet in precisely the deployment that needs it most.

  **The JSON ending early.** The one signal that needs no agreement with the
  server about numbers at all: a tool call's arguments that fail to parse
  because the input RAN OUT were cut off, whoever cut them. Not every truncation
  looks like this -- a cut landing between a key and its colon reads as an
  ordinary syntax error -- so it is one signal among three, not a replacement.

  A false answer here only leaves the original, less specific classification in
  place, so absent telemetry answers false rather than guessing."
  [finish-reason completion-tokens max-output-tokens json-ended-early?]
  (boolean
   (or (= "length" finish-reason)
       (true? json-ended-early?)
       (and (number? completion-tokens)
            (number? max-output-tokens)
            (>= (long completion-tokens) (long max-output-tokens))))))

;; ── how many output tokens a model will actually give ──────────────────

(defn model-scoped
  "VALUE for MODEL, where VALUE is one number for every model or a map of them.

  The same shape `:context-window-tokens` already uses, so an operator who has
  configured one knows how to configure the other. A map missing MODEL answers
  nil rather than falling back to some other model's number: a limit configured
  for one deployment is evidence about that deployment only."
  [value model]
  (cond
    (map? value) (get value model)
    (number? value) value))

(defn served-output-ceiling
  "The server's OWN output cap, when a response has just revealed it.

  A response that stopped at `length` having produced FEWER tokens than were
  asked for was stopped by a limit that is not ours. That count is the limit.

  Measured 2026-08-28 against api.murakumo.cloud, one request each with a
  prompt written to run long: `max_tokens` 3072, 4096, 8192 and 16384 all
  returned `completion_tokens` 2048 with `finish_reason` `length`. Two other
  selectors on the same fleet answered the same 2048. Nothing in
  `/infer/models` declares it -- the registry states `context: 262144` and no
  output limit at all -- so this number exists nowhere except in the shape of a
  reply, and an application that does not read it back is configured against a
  limit it has never seen.

  nil when the response reveals nothing: stopping for any other reason says the
  model finished, and reaching the cap we asked for says only that OUR number
  applied."
  [finish-reason completion-tokens requested]
  (when (and (= "length" finish-reason)
             (number? completion-tokens)
             (number? requested)
             (pos? (long completion-tokens))
             (< (long completion-tokens) (long requested)))
    (long completion-tokens)))

(defn output-token-budget
  "How many output tokens to ask MODEL for.

  Resolution order, most specific first: what the caller asked for, what the
  operator configured for this provider (per-model or one number), then the
  shipped DEFAULT. Then two bounds that are not preferences:

  - the ceiling this endpoint has been OBSERVED to enforce, because asking for
    more than a server gives does not get more -- it gets the same answer while
    the application believes a budget it does not have, and every check written
    against the requested number goes quiet;

  - the model's context window, since output that cannot fit in the window was
    never available to spend.

  Bounds are applied only when known. An unobserved ceiling is not a ceiling of
  zero, and a provider that does not publish a window does not thereby have a
  small one."
  [{:keys [requested configured default observed-ceiling context-window]} model]
  (let [chosen (or (model-scoped requested model)
                   (model-scoped configured model)
                   default)]
    (when (number? chosen)
      (->> [observed-ceiling context-window]
           (filter number?)
           (reduce (fn [value bound] (min value (long bound))) (long chosen))
           (max 1)))))
