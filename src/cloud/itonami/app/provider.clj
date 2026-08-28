(ns cloud.itonami.app.provider
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.provider-retry :as retry])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 4))
      .build))

;; The decision and the delay live in `provider-retry`, which depends on
;; nothing, so both can be exercised without a socket. What is retried and how
;; long it waits had been wrong in two different ways at once -- see that
;; namespace for the measurement.
(def ^:private max-transient-retries (dec retry/max-attempts))

(def request-timeout-seconds
  "How long one model request may take before it is abandoned.

  Public because the number is a claim about the provider, and the arithmetic
  behind it should be checkable from outside. Measured against `murakumo-main`
  on 2026-08-19 under live load: 11.4 output tokens/second, 75.8 prompt
  tokens/second, one production slot. A resident tick is given 768 output
  tokens -- 68 seconds -- and carries roughly 3.3k prompt tokens per turn, or
  another 44. That is 112 seconds before queueing, response framing, or a
  larger governed tool call. A 120 second wall therefore classified healthy
  work as a provider failure whenever the one-slot service was briefly busy.
  7 of 24 resident runs did on the day this was written.

  The extra wall time is paired with a smaller unattended output budget; with
  one slot, raising this limit alone would only let one request hold the whole
  workforce longer."
  180)

(defn- provider-timeout-seconds
  ([provider] (provider-timeout-seconds provider nil))
  ([provider model]
   (long (or (get (:model-request-timeout-seconds provider) model)
             (:request-timeout-seconds provider)
             request-timeout-seconds))))

(defn- timeout->typed
  "Rethrow a request timeout as something the ledger can tell apart.

  `java.net.http` throws `HttpTimeoutException` with the message \"request
  timed out\" and no ex-data, so `(:type (ex-data error))` was nil and every
  one of these was recorded as `:internal-error` -- the same value a genuine
  bug in this application produces. Measured 2026-08-19: 7 of 24 resident runs
  that day were timeouts filed under that name, and finding out cost a walk
  through the event log of each one.

  A slow provider and a broken host are different problems with different
  fixes. They must not arrive under one name."
  ([^Exception error url]
   (timeout->typed error url request-timeout-seconds))
  ([^Exception error url timeout-seconds]
   (cond
    ;; Checked FIRST, because `HttpConnectTimeoutException` extends
    ;; `HttpTimeoutException` and the general branch would swallow it. They are
    ;; not one problem: a model too slow to answer is a capacity question, and
    ;; a provider that cannot be reached at all is a configuration or network
    ;; one. Collapsing them here would repeat, one layer down, exactly the
    ;; conflation this function exists to undo.
    (instance? java.net.http.HttpConnectTimeoutException error)
    (throw (ex-info "model provider could not be reached"
                    {:type :provider/unreachable :url url}
                    error))

    (instance? java.net.http.HttpTimeoutException error)
    (throw (ex-info "model provider request timed out"
                    {:type :provider/timeout
                     :url url
                     :timeout-seconds timeout-seconds}
                    error))

    ;; The transport failed some other way -- a reset, a broken pipe, an EOF
    ;; part-way through. Still not a fault in this application, and it was
    ;; being recorded as one: measured 2026-08-19, a `Connection reset` two
    ;; tool calls into a resident tick was filed as `:internal-error`, which is
    ;; where a reader looks for OUR bugs.
    ;;
    ;; One name for the whole family rather than one per errno. What a reader
    ;; does about it is the same -- look at the network and the provider -- and
    ;; the message that says which it was travels with it.
    (instance? java.io.IOException error)
    (throw (ex-info (str "model provider transport failed: " (.getMessage error))
                    {:type :provider/network-error
                     :url url
                     :cause-class (.getName (class error))}
                    error))

    :else (throw error))))

(defn- request-json
  ([method url body] (request-json method url body nil))
  ([method url body api-key]
   (request-json method url body api-key nil))
  ([method url body api-key headers]
   (request-json method url body api-key headers request-timeout-seconds))
  ([method url body api-key headers timeout-seconds]
   (request-json method url body api-key headers timeout-seconds
                 max-transient-retries))
  ([method url body api-key headers timeout-seconds transient-retries]
   (loop [attempt 0]
     (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                       (.timeout (Duration/ofSeconds timeout-seconds))
                       (.header "Accept" "application/json")
                       (.header "Content-Type" "application/json"))
           _ (when api-key (.header builder "Authorization" (str "Bearer " api-key)))
           _ (doseq [[header value] headers :when (some? value)]
               (.header builder (name header) (str value)))
           request (case method
                     :get (.GET builder)
                     :post (.POST builder
                                  (HttpRequest$BodyPublishers/ofString
                                   (json/write-str body))))
           response (try (.send client (.build request)
                                (HttpResponse$BodyHandlers/ofString))
                         (catch Exception error
                           (timeout->typed error url timeout-seconds)))
           status (.statusCode response)
           parsed (try (json/read-str (.body response) :key-fn keyword)
                       (catch Exception _ {:raw (.body response)}))]
       (cond
         (<= 200 status 299) parsed

         (and (< attempt transient-retries)
              (retry/transient-response? status parsed))
         (do
           ;; Generation has no external effect until a returned tool call is
           ;; admitted, so re-sending one costs capacity and nothing else.
           ;; The resident workforce IS capacity one, so the bound matters and
           ;; is named in `provider-retry/max-attempts`: at worst this holds
           ;; the slot 14 seconds, against a failed turn that costs the whole
           ;; tick and a requeue.
           (Thread/sleep (long (retry/retry-delay-ms attempt)))
           (recur (inc attempt)))

         :else
         (throw (ex-info "model provider request failed"
                         {:type :provider/http-error
                          :status status :url url :response parsed
                          :attempts (inc attempt)})))))))

(defn- openai-shaped? [provider]
  (contains? #{:openai-compatible :xai} (:kind provider)))

(defn- openai-url [provider path]
  (str (str/replace (:base-url provider) #"/$" "") path))

(defn- xai-headers [provider request]
  (when (and (= :xai (:kind provider)) (:conversation-id request))
    {"x-grok-conv-id" (:conversation-id request)}))

(defn list-models [provider]
  (cond
    (openai-shaped? provider)
    (mapv (fn [model]
            (cond-> {:id (:id model) :object "model" :owned_by (:id provider)
                     :provider (:id provider)}
              (or (:context_length model) (:context-window-tokens model))
              (assoc :context-window-tokens
                     (long (or (:context_length model)
                               (:context-window-tokens model))))))
          (:data (request-json :get (openai-url provider "/models")
                               nil (config/env-secret provider))))

    (= :ollama (:kind provider))
    (mapv (fn [model]
            {:id (:name model) :object "model" :owned_by (:id provider)
             :provider (:id provider)})
          (:models (request-json :get (str (:base-url provider) "/api/tags") nil)))

    :else []))

(def ^:private model-context-cache-ms (* 5 60 1000))
(defonce ^:private model-context-cache (atom {}))

(defonce ^:private model-output-ceiling
  ;; What each endpoint has been SEEN to cap output at, keyed the same way the
  ;; context cache is. Not a TTL cache: this is not a metadata lookup that can
  ;; be repeated on demand, it is a fact a reply happened to reveal, and there
  ;; is no request that asks for it. It is learned by asking for more than the
  ;; server gives -- which the app does exactly once, and then stops doing.
  ;;
  ;; A ceiling that RISES is therefore not noticed until the process restarts.
  ;; That is the correct trade for now: it fails toward the number the endpoint
  ;; has actually served, restarts here are frequent (86 in 103.8 hours, which
  ;; is its own finding), and the alternative is periodically spending a slot on
  ;; a request built to be truncated.
  (atom {}))

(defn- endpoint-key [provider model]
  [(:id provider) (:base-url provider) model])

(defn- note-output-ceiling!
  "Record what a response revealed about this endpoint's own output cap.

  CORROBORATED, not inferred from one reply. `finish_reason length` below the
  requested cap does not prove a server-side ceiling: a prompt bigger than
  `agent-request`'s ESTIMATE leaves less room in the window than the arithmetic
  reserved, and a reasoning model can spend the allowance on thinking that
  `completion_tokens` does not count -- this file already documents that exact
  behaviour at `default-agent-max-tokens`. One such reply against a requested
  8192 could stop at 300, and a bound taken from it would cap every later
  request on that endpoint at 300, truncating all of them, with no way back
  inside the process.

  A number seen ONCE is an event; the same number twice is a limit. So the
  first observation is held and only a second, equal one takes effect. A
  different number replaces the held candidate rather than lowering a bound --
  a run of unequal truncations is what the noisy causes above look like, and it
  must not ratchet."
  [provider model finish-reason usage requested]
  (when-let [ceiling (retry/served-output-ceiling
                      finish-reason (:completion_tokens usage) requested)]
    (swap! model-output-ceiling
           (fn [seen]
             (let [key (endpoint-key provider model)
                   {:keys [candidate confirmed]} (get seen key)]
               (assoc seen key
                      (cond
                        ;; Confirmed twice at this number, or lower than what is
                        ;; already confirmed and seen twice: take it.
                        (= candidate ceiling)
                        {:candidate ceiling
                         :confirmed (if confirmed
                                      (min (long confirmed) ceiling)
                                      ceiling)}
                        :else {:candidate ceiling :confirmed confirmed})))))))

(defn- confirmed-output-ceiling [provider model]
  (:confirmed (get @model-output-ceiling (endpoint-key provider model))))

(defn- context-window-from-model-info
  "Read the context limit from provider model metadata without assuming a
  family-specific key. Ollama returns keys such as
  `:gemma3.context_length`; OpenAI-shaped providers may return the direct
  `:context_length` field."
  [value]
  (let [direct (or (:context_length value)
                   (:context-window-tokens value)
                   (:context_window value))
        nested (some (fn [[k v]]
                       (when (and (number? v)
                                  (str/ends-with? (name k) ".context_length"))
                         v))
                     (:model_info value))]
    (some-> (or direct nested) long)))

(defn- discover-model-context-window [provider model]
  (try
    (cond
      (= :ollama (:kind provider))
      (context-window-from-model-info
       (request-json :post (str (:base-url provider) "/api/show")
                     {:model model :verbose false}))

      (openai-shaped? provider)
      (some (fn [candidate]
              (when (= model (:id candidate))
                (context-window-from-model-info candidate)))
            (:data (request-json :get (openai-url provider "/models")
                                 nil (config/env-secret provider))))

      :else nil)
    ;; Context discovery is an optimisation, not provider admission. Older
    ;; OpenAI-compatible servers omit the field entirely; generation must keep
    ;; the measured bounded fallback rather than fail for missing metadata.
    (catch Exception _ nil)))

(defn model-context-window
  "The selected model's maximum context window.

  Exact operator configuration wins. Otherwise query provider metadata once
  per five minutes (Ollama `/api/show`, or an OpenAI-shaped `/models` entry).
  A nil result is cached too so a compatible-but-minimal server is not probed
  before every tool iteration."
  [provider model]
  (or (get-in provider [:context-window-tokens model])
      (let [key [(:id provider) (:base-url provider) model]
            now (System/currentTimeMillis)
            cached (get @model-context-cache key)]
        (if (and cached (< (- now (:at cached)) model-context-cache-ms))
          (:value cached)
          (let [value (discover-model-context-window provider model)]
            (swap! model-context-cache assoc key {:at now :value value})
            value)))))

(declare assert-response-model! with-model-fallback)

(defn chat
  [provider {:keys [model messages temperature] :as request}]
  (cond
    (= :ollama (:kind provider))
    (let [result (request-json
                  :post (str (:base-url provider) "/api/chat")
                  {:model model :messages messages :stream false
                   :options {:temperature (or temperature 0.7)}})]
      {:content (get-in result [:message :content])
       :usage {:prompt_tokens (get result :prompt_eval_count 0)
               :completion_tokens (get result :eval_count 0)
               :total_tokens (+ (get result :prompt_eval_count 0)
                                (get result :eval_count 0))}})

    (openai-shaped? provider)
    (with-model-fallback
      provider model
      (fn [candidate]
        (let [result (request-json
                      :post (openai-url provider "/chat/completions")
                      (cond-> {:model candidate :messages messages :stream false
                               :temperature (or temperature 0.7)}
                        (= :xai (:kind provider))
                        (assoc :max_tokens (or (retry/model-scoped (:max-output-tokens provider) model) 8192)
                               :reasoning_effort (or (:reasoning-effort request)
                                                     (:reasoning-effort provider)
                                                     "medium")))
                      (config/env-secret provider)
                      (xai-headers provider request)
                      (provider-timeout-seconds provider candidate)
                      (long (or (:max-transient-retries provider)
                                max-transient-retries)))
              _ (assert-response-model! provider candidate result)]
          {:content (get-in result [:choices 0 :message :content])
           :usage (:usage result)
           :served-model (:model result)})))

    :else (throw (ex-info "unsupported provider kind" {:provider provider}))))

(defn- tool-definition [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name :description description :parameters parameters}})

(defn- provider-message [message]
  (let [calls (:tool-calls message)]
    (cond-> (dissoc message :tool-calls :tool-call-id)
      (seq calls)
      (assoc :tool_calls
             (mapv (fn [{:keys [id name input]}]
                     {:id id :type "function"
                      :function {:name name
                                 :arguments (json/write-str input)}})
                   calls))

      (:tool-call-id message)
      (assoc :tool_call_id (:tool-call-id message)))))

(def ^:private max-reported-arguments 400)

(defn- unfenced
  "`value` with a markdown code fence stripped, when it is wrapped in one.

  The one malformation worth recovering from: it is unambiguous (the JSON is
  intact, a decoration was added around it) so nothing is guessed. Every other
  malformation is left to fail -- these arguments can write a file or make a
  commit, and repairing a call the model got wrong is how a tool runs with
  something nobody asked for."
  [value]
  (let [t (str/trim (str value))]
    (if-let [m (re-matches #"(?s)```(?:json)?\s*(.*?)\s*```" t)]
      (str/trim (second m))
      t)))

(defn- parse-arguments
  "The model's tool arguments as a map.

  KEEPS THE OFFENDING STRING when it cannot. The first version threw
  `{:type :provider/invalid-tool-arguments}` and nothing else, so 19 failed
  resident turns (root ADR-2608197700) recorded that arguments were invalid
  and not ONE recorded what they were -- and a probe with the real tool
  schemas could not reproduce it afterwards (15 attempts, 0 failures). The
  reason was in the value we dropped. Same defect this codebase keeps finding
  elsewhere: status kept, body discarded.

  TAKES THE TOOL NAME for the same reason. Measured 2026-08-28, this is the
  most common live failure -- and `:turn/tool` was nil for all 138 of them,
  so `which tool did the model mis-call` had no answer either. The caller
  knows the name; this did not ask for it."
  [tool-name value]
  (cond
    (map? value) value
    (str/blank? (str value)) {}
    :else
    (let [candidate (unfenced value)]
      (try
        (let [parsed (json/read-str candidate :key-fn keyword)]
          (if (map? parsed)
            parsed
            ;; Valid JSON of the wrong shape is its own failure: a bare array
            ;; or string would silently become an empty argument map further
            ;; down and the tool would run with no arguments at all.
            (throw (ex-info "model returned tool arguments that are not an object"
                            {:type :provider/invalid-tool-arguments
                             :tool-name tool-name
                             :arguments-kind (cond (sequential? parsed) "array"
                                                   (string? parsed) "string"
                                                   :else (str (type parsed)))
                             :arguments-sample (subs (str value) 0
                                                     (min max-reported-arguments
                                                          (count (str value))))}))))
        (catch clojure.lang.ExceptionInfo error (throw error))
        (catch Exception error
          (throw (ex-info "model returned invalid tool arguments"
                          {:type :provider/invalid-tool-arguments
                           :tool-name tool-name
                           ;; Whether the reader ran out of INPUT, as opposed to
                           ;; meeting something it could not accept. The only
                           ;; truncation signal that needs no agreement with the
                           ;; server about token counts -- see
                           ;; `provider-retry/output-budget-exhausted?` for why
                           ;; the counts cannot be relied on alone.
                           :json-ended-early? (instance? java.io.EOFException error)
                           :arguments-length (count (str value))
                           ;; Truncated: these are model-authored and can be
                           ;; long. Enough to see the malformation, not the
                           ;; whole payload.
                           :arguments-sample (subs (str value) 0
                                                   (min max-reported-arguments
                                                        (count (str value))))}
                          error)))))))

(defn- budget-exhausted-error
  "`error` renamed to the thing that actually went wrong, when the budget ran out.

  A tool call whose arguments were cut off mid-JSON is unparseable, and
  `parse-arguments` is right that it cannot run. But `invalid-tool-arguments`
  says the model authored something malformed, and an operator reading that
  goes looking at the model. Measured 2026-08-28 on the resident fleet: this
  was the single most common live failure (20 of 45 failed turns), and every
  reproduction of it came from `:max-output-tokens 1024` against a
  `decision_frame` whose frames need 1350-1676 tokens. Three runs at 1024 were
  unparseable; three each at 2048, 3072 and 4096 were complete. The model was
  never the problem -- a configured number was, and the name hid it.

  The message reports what was SPENT as well as what was asked for, because
  those differ: api.murakumo.cloud enforces a 2048-token ceiling of its own and
  requests for 3072, 4096, 8192 and 16384 all came back at 2048. An operator
  told only `max-output-tokens 8192` would go looking for a budget that was
  never the one that applied.

  Only a call that already failed to parse reaches this, and only when the
  budget was in fact exhausted. Everything the original error carried is kept:
  the tool name and the offending string are the point of that error, and this
  renames the failure rather than replacing it."
  [error max-output-tokens]
  (let [{:keys [type finish-reason completion-tokens json-ended-early?] :as data}
        (ex-data error)]
    (if (and (= :provider/invalid-tool-arguments type)
             (retry/output-budget-exhausted? finish-reason completion-tokens
                                             max-output-tokens
                                             json-ended-early?))
      (ex-info (str "モデルの出力トークン上限に達し、tool の引数が途中で切れました"
                    (when (or completion-tokens max-output-tokens)
                      (str "（" (or completion-tokens "?") "/"
                           (or max-output-tokens "?") " tokens）")))
               (assoc data
                      :type :provider/output-budget-exhausted
                      :max-output-tokens max-output-tokens)
               error)
      error)))

(defn- normalize-tool-calls
  ([calls finish-reason completion-tokens max-output-tokens]
   (mapv (fn [index call]
           {:id (or (:id call) (str "tool-call-" index))
            :name (get-in call [:function :name])
            :input (try
                     (parse-arguments (get-in call [:function :name])
                                      (get-in call [:function :arguments]))
                     (catch clojure.lang.ExceptionInfo error
                       (throw (budget-exhausted-error
                               (ex-info (.getMessage error)
                                        (assoc (ex-data error)
                                               :finish-reason finish-reason
                                               :completion-tokens completion-tokens)
                                        error)
                               max-output-tokens))))})
         (range) (or calls []))))

(def ^:private default-agent-max-tokens
  ;; api.murakumo.cloud routes murakumo-main to a reasoning model. When this is
  ;; omitted the public gateway supplies 512, and the model can spend the whole
  ;; allowance on reasoning: HTTP 200, finish_reason=length, content="". A Bot
  ;; then appears to accept the person's message without answering. 2048 is the
  ;; gateway's documented public ceiling and leaves room for the visible reply.
  2048)

(defn- requested-max-tokens
  "The output cap this request will actually carry, for THIS model.

  One function because two places need the same answer and they must not drift:
  `agent-request-body` puts it on the wire, and `agent-result` compares
  `completion_tokens` against it to tell a truncated tool call from a malformed
  one. A second copy of the resolution would make that comparison silently
  wrong the first time any input to it moved -- and one of them, the observed
  ceiling, moves at runtime.

  `:max-output-tokens` may be one number or a map of them by model, the shape
  `:context-window-tokens` already uses, because a fleet does not serve one
  model and a cap that is right for one is not evidence about another.

  The bounds are in `provider-retry`, with the measurement that produced them."
  [provider request]
  (let [model (:model request)]
    (retry/output-token-budget
     {:requested (:max-output-tokens request)
      :configured (:max-output-tokens provider)
      :default default-agent-max-tokens
      :observed-ceiling (confirmed-output-ceiling provider model)
      :context-window (model-context-window provider model)}
     model)))

(defonce ^:private active-agent-streams (atom {}))

(defn cancel-agent-stream!
  "Close the provider body owned by `thread`, if headers have already arrived."
  [thread]
  (when-let [stream (get @active-agent-streams thread)]
    (try (.close ^java.io.Closeable stream) (catch Exception _ nil)))
  true)

(defn- with-active-agent-reader [response consume!]
  (let [thread (Thread/currentThread)
        stream (.body response)]
    (swap! active-agent-streams assoc thread stream)
    (try
      (with-open [input stream
                  reader (BufferedReader. (InputStreamReader. input))]
        (consume! reader))
      (finally (swap! active-agent-streams dissoc thread)))))

(defn- agent-request-body
  [provider {:keys [model messages tools temperature reasoning-effort
                    disable-thinking? text-only?]
             :as request}]
  (cond-> {:model model
           :messages (mapv provider-message messages)
           :stream false
           :temperature (or temperature 0.2)
           :max_tokens (requested-max-tokens provider request)}
    (and (not text-only?) (seq tools))
    (assoc :tools (mapv tool-definition tools))

    ;; llama.cpp vendor extension, passed through by the murakumo bridge. A
    ;; reasoning model spends output tokens on `thinking` BEFORE it emits any
    ;; text, so a tight :max-output-tokens does not produce a short answer --
    ;; it produces no answer at all, and the caller sees :provider/empty-response.
    ;;
    ;; Measured 2026-08-18 against murakumo-main (Qwen3.8-27B), one realistic
    ;; resident payload (goal + two tool outputs), same cap both times:
    ;;
    ;;   thinking on,  max_tokens 1024 -> stop=max_tokens, 4656 thinking chars, 0 TEXT
    ;;   thinking off, max_tokens 1024 -> stop=end_turn,      0 thinking chars, 2150 TEXT
    ;;
    ;; club-shinshi's companion.cljs hit this exact failure on 2026-07-15 and
    ;; fixed it the same way; the comment there is the older half of this note.
    ;;
    ;; The caller decides, not a threshold here: only the caller knows it capped
    ;; the budget, and inventing a cutoff would make this a constant nobody
    ;; re-measures.
    disable-thinking?
    (assoc :chat_template_kwargs {:enable_thinking false})
    (and (openai-shaped? provider) (not text-only?) (seq tools))
    ;; Cloud Itonami admits, runs and audits one capability at a time. This is
    ;; also a compatibility boundary: some OpenAI-shaped inference servers can
    ;; emit parallel calls but reject the continuation containing several tool
    ;; results. Serial calls keep every continuation portable and replayable.
    (assoc :parallel_tool_calls false)

    (= :xai (:kind provider))
    (assoc :reasoning_effort (or reasoning-effort
                                (:reasoning-effort provider)
                                "medium"))))

(defn- agent-result
  ([message finish-reason] (agent-result message finish-reason nil nil))
  ([message finish-reason usage] (agent-result message finish-reason usage nil))
  ;; `max-output-tokens` is the cap the REQUEST carried, which is the only
  ;; number `completion_tokens` can be compared against. It is threaded here
  ;; rather than read from the response because no provider reports it back.
  ([message finish-reason usage max-output-tokens]
  (let [result (cond-> {:content (:content message)
                        :tool-calls (normalize-tool-calls
                                     (:tool_calls message) finish-reason
                                     (:completion_tokens usage) max-output-tokens)}
                 usage (assoc :usage usage))]
    (when (and (str/blank? (:content result))
               (empty? (:tool-calls result)))
      (throw (ex-info
              "モデルが回答本文を返しませんでした。もう一度送ってください。"
              {:type :provider/empty-response
               :finish-reason finish-reason})))
    result)))

(defn- ollama-agent-options [provider request]
  (cond-> {:temperature (or (:temperature request) 0.2)
           :num_predict (requested-max-tokens provider request)}
    (:context-window-tokens request)
    (assoc :num_ctx (:context-window-tokens request))))

(def ^:private fallback-error-types
  #{:provider/timeout :provider/unreachable :provider/network-error
    :provider/http-error :provider/model-mismatch :provider/empty-response
    :provider/model-unready})

(defn- model-ready-response?
  "Accept either aggregate readiness or exact hosted-model readiness.

  A provider may expose several independent inference planes. Its aggregate
  status can therefore be unavailable while the model this Bot requested has
  warm, generation-qualified capacity. The exact model entry is narrower
  evidence and must not be masked by an unrelated plane's failure."
  [model response]
  (or (true? (:ok response))
      (true? (get-in response [:hosted-models model :ok]))
      ;; request-json keywordizes JSON object keys, including model ids.
      (true? (get-in response [:hosted-models (keyword model) :ok]))))

(defn- assert-model-ready!
  "Fail fast when a specialized model has no immediately usable capacity.

  A model catalog entry proves routing, not generation. Providers may attach a
  read-only readiness URL to a model so a scale-to-zero or saturated accelerator
  falls through to its explicit fallback before the much wider generation
  timeout. The readiness request deliberately carries no provider credential:
  this endpoint is public operational evidence, not the inference boundary."
  [provider model]
  (when-let [{:keys [url timeout-seconds]}
             (get (:model-readiness provider) model)]
    (try
      (let [result (request-json :get url nil nil nil
                                 (long (or timeout-seconds 5)) 0)]
        (when-not (model-ready-response? model result)
          (throw (ex-info "requested model readiness failed"
                          {:type :provider/model-unready
                           :requested-model model
                           :readiness-url url}))))
      (catch Exception error
        (cond
          (model-ready-response? model (:response (ex-data error))) true

          (= :provider/model-unready (:type (ex-data error)))
          (throw error)

          :else
          (throw (ex-info "requested model is not ready"
                          {:type :provider/model-unready
                           :requested-model model
                           :readiness-url url
                           :readiness-error-type (:type (ex-data error))
                           :readiness-status (:status (ex-data error))}
                          error))))))
  true)

(defn- assert-response-model!
  [provider requested response]
  (when (:assert-response-model? provider)
    (let [served (:model response)
          accepted (conj (get (:accepted-response-models provider)
                              requested #{})
                         requested)]
      (when-not (contains? accepted served)
        (throw (ex-info "model provider returned a different model"
                        {:type :provider/model-mismatch
                         :requested-model requested
                         :served-model served})))))
  response)

(defn- with-model-fallback
  "Run `invoke` with requested model, then its explicitly configured fallback.

  The result always carries the model that actually served it. A fallback is
  never relabelled as the requested accelerator, and arbitrary application
  exceptions do not silently change routing."
  [provider requested invoke]
  (let [invoke-ready (fn [model]
                       (assert-model-ready! provider model)
                       (invoke model))]
    (try
    (let [result (invoke-ready requested)]
      (assoc result
             :model (or (:served-model result) requested)
             :requested-model requested :fallback? false))
    (catch Exception primary
      (let [error-type (:type (ex-data primary))
            fallback (get (:model-fallbacks provider) requested)]
        (if (and fallback
                 (contains? fallback-error-types error-type)
                 (not (:stream-emitted? (ex-data primary))))
          (try
            (let [result (invoke-ready fallback)]
              (assoc result
                     :model (or (:served-model result) fallback)
                     :requested-model requested :fallback? true
                     :fallback-error-type error-type))
            (catch Exception secondary
              (throw (ex-info "model provider and explicit fallback both failed"
                              {:type :provider/fallback-failed
                               :requested-model requested
                               :fallback-model fallback
                               :primary-error-type error-type
                               :fallback-error-type (:type (ex-data secondary))}
                              secondary))))
          (throw primary)))))))

(defn- openai-agent-turn-once
  [provider request model]
  (let [body (agent-request-body provider (assoc request :model model))
        result (request-json
                :post
                (openai-url provider "/chat/completions")
                body (config/env-secret provider)
                (xai-headers provider request)
                (provider-timeout-seconds provider model)
                (long (or (:max-transient-retries provider)
                          max-transient-retries)))
        _ (assert-response-model! provider model result)
        message (get-in result [:choices 0 :message])
        finish-reason (get-in result [:choices 0 :finish_reason])
        ;; `(assoc request :model model)`, exactly as the body was built four
        ;; lines up. `with-model-fallback` calls this with a DIFFERENT model
        ;; than the request named, and every input to the resolution is
        ;; model-scoped -- the per-model `:max-output-tokens` map and the
        ;; per-endpoint observed ceiling. Reading the original model here
        ;; compares `completion_tokens` against a cap that was never on the
        ;; wire, and teaches `note-output-ceiling!` a ceiling for one model
        ;; from another model's budget.
        max-tokens (requested-max-tokens provider (assoc request :model model))]
    ;; Before `agent-result`, which throws on a tool call this very reply may
    ;; have truncated. The observation is what stops the NEXT request asking
    ;; for a budget this endpoint does not serve, so it must survive the throw.
    (note-output-ceiling! provider model finish-reason (:usage result) max-tokens)
    (assoc (agent-result message finish-reason (:usage result) max-tokens)
           :served-model (:model result))))

(defn agent-turn
  "One non-streaming tool-capable model turn, normalized for Agent Control."
  [provider request]
  (cond
      (= :ollama (:kind provider))
      (let [body (agent-request-body provider request)
            result (request-json :post (str (:base-url provider) "/api/chat")
                                 (-> body
                                     (dissoc :temperature :max_tokens
                                             :parallel_tool_calls :reasoning_effort)
                                     (assoc :options
                                            (ollama-agent-options provider request))))
            message (:message result)
            usage {:prompt_tokens (get result :prompt_eval_count 0)
                   :completion_tokens (get result :eval_count 0)
                   :total_tokens (+ (get result :prompt_eval_count 0)
                                    (get result :eval_count 0))}
            max-tokens (requested-max-tokens provider request)]
        (note-output-ceiling! provider (:model request) (:done_reason result)
                              usage max-tokens)
        (agent-result message (:done_reason result) usage max-tokens))

      (openai-shaped? provider)
      (with-model-fallback provider (:model request)
        #(openai-agent-turn-once provider request %))

      :else (throw (ex-info "unsupported provider kind" {:provider provider}))))

(defn- streaming-response
  ([url body api-key] (streaming-response url body api-key nil))
  ([url body api-key headers]
   (streaming-response url body api-key headers request-timeout-seconds))
  ([url body api-key headers timeout-seconds]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds timeout-seconds))
                     (.header "Accept" "*/*")
                     (.header "Content-Type" "application/json"))
         _ (when api-key
             (.header builder "Authorization" (str "Bearer " api-key)))
         _ (doseq [[header value] headers :when (some? value)]
             (.header builder (name header) (str value)))
         request (-> builder
                     (.POST (HttpRequest$BodyPublishers/ofString
                             (json/write-str body)))
                     .build)
         response (try (.send client request
                              (HttpResponse$BodyHandlers/ofInputStream))
                       (catch Exception error
                         (timeout->typed error url timeout-seconds)))]
     (when-not (<= 200 (.statusCode response) 299)
       (throw (ex-info "model provider streaming request failed"
                       {:type :provider/http-error
                        :status (.statusCode response) :url url})))
     response)))

(defn- emit! [on-delta content]
  (when (and (string? content) (seq content))
    (on-delta content)
    content))

(defn- openai-chat-stream-once!
  [provider request model on-delta]
  (let [content (StringBuilder.)
        usage (volatile! nil)
        served-model (volatile! nil)]
    (try
      (let [response
            (streaming-response
             (openai-url provider "/chat/completions")
             (cond-> {:model model :messages (:messages request) :stream true
                      :stream_options {:include_usage true}
                      :temperature (or (:temperature request) 0.7)}
               (= :xai (:kind provider))
               (assoc :max_tokens (or (retry/model-scoped (:max-output-tokens provider) model) 8192)
                      :reasoning_effort (or (:reasoning-effort request)
                                            (:reasoning-effort provider)
                                            "medium")))
             (config/env-secret provider)
             (xai-headers provider request)
             (provider-timeout-seconds provider model))]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :let [data (when (str/starts-with? line "data:")
                               (str/trim (subs line 5)))]
                  :when (and data (not= data "[DONE]"))]
            (let [chunk (json/read-str data :key-fn keyword)
                  _ (when-let [observed (:model chunk)]
                      (vreset! served-model observed)
                      (assert-response-model! provider model chunk))
                  delta (get-in chunk [:choices 0 :delta :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when-let [chunk-usage (:usage chunk)]
                (vreset! usage chunk-usage))))))
      (when (:assert-response-model? provider)
        (assert-response-model! provider model {:model @served-model}))
      {:content (.toString content) :usage @usage
       :served-model @served-model}
      (catch Exception error
        (throw (ex-info (.getMessage error)
                        (assoc (or (ex-data error) {})
                               :stream-emitted? (pos? (.length content)))
                        error))))))

(defn chat-stream!
  "Stream provider deltas to `on-delta` and return the complete result."
  [provider {:keys [model messages temperature] :as request} on-delta]
  (let [content (StringBuilder.)
        usage (volatile! nil)
        routing (volatile! nil)]
    (cond
      (= :ollama (:kind provider))
      (let [response
            (streaming-response
             (str (:base-url provider) "/api/chat")
             {:model model :messages messages :stream true
              :options {:temperature (or temperature 0.7)}}
             nil)]
        (with-open [reader (BufferedReader.
                            (InputStreamReader. (.body response)))]
          (doseq [line (line-seq reader)
                  :when (not (str/blank? line))]
            (let [chunk (json/read-str line :key-fn keyword)
                  delta (get-in chunk [:message :content])]
              (when-let [emitted (emit! on-delta delta)]
                (.append content emitted))
              (when (:done chunk)
                (vreset! usage
                         {:prompt_tokens (get chunk :prompt_eval_count 0)
                          :completion_tokens (get chunk :eval_count 0)
                          :total_tokens (+ (get chunk :prompt_eval_count 0)
                                           (get chunk :eval_count 0))}))))))

      (openai-shaped? provider)
      (let [result (with-model-fallback
                     provider model
                     #(openai-chat-stream-once! provider request % on-delta))]
        (.append content (:content result))
        (vreset! usage (:usage result))
        (vreset! routing (select-keys result
                                      [:model :requested-model :fallback?
                                       :fallback-error-type])))

      :else (throw (ex-info "unsupported provider kind" {:provider provider})))
    (merge {:content (.toString content) :usage @usage} @routing)))

(defn- append-fragment [current fragment]
  (str (or current "") (or fragment "")))

(defn- merge-tool-fragment [current call]
  (let [function (:function call)]
    {:id (or (:id current) (:id call))
     :type "function"
     :function {:name (append-fragment (get-in current [:function :name])
                                       (:name function))
                :arguments (append-fragment (get-in current [:function :arguments])
                                             (:arguments function))}}))

(defn- openai-agent-turn-stream-once!
  [provider request model on-delta]
  (let [content (StringBuilder.)
        calls (atom {})
        finish-reason (volatile! nil)
        usage (volatile! nil)
        served-model (volatile! nil)
        body (assoc (agent-request-body provider (assoc request :model model))
                    :stream true :stream_options {:include_usage true})]
    (try
      (let [response (streaming-response
                      (openai-url provider "/chat/completions") body
                      (config/env-secret provider)
                      (xai-headers provider request)
                      (provider-timeout-seconds provider model))]
        (with-active-agent-reader
          response
          (fn [reader]
            (doseq [line (line-seq reader)
                    :let [data (when (str/starts-with? line "data:")
                                 (str/trim (subs line 5)))]
                    :when (and data (not= data "[DONE]"))]
              (let [chunk (json/read-str data :key-fn keyword)
                    _ (when-let [observed (:model chunk)]
                        (vreset! served-model observed)
                        (assert-response-model! provider model chunk))
                    choice (get-in chunk [:choices 0])
                    delta (:delta choice)]
                (when-let [emitted (emit! on-delta (:content delta))]
                  (.append content emitted))
                (doseq [[fallback call] (map-indexed vector (:tool_calls delta))]
                  (let [index (or (:index call) fallback)]
                    (swap! calls update index merge-tool-fragment call)))
                (when-let [reason (:finish_reason choice)]
                  (vreset! finish-reason reason))
                (when-let [chunk-usage (:usage chunk)]
                  (vreset! usage chunk-usage)))))))
      (when (:assert-response-model? provider)
        (assert-response-model! provider model {:model @served-model}))
      ;; `(assoc request :model model)` for the same reason as the non-streaming
      ;; path: this runs under `with-model-fallback` and `model` may not be the
      ;; one the request named.
      (let [max-tokens (requested-max-tokens provider (assoc request :model model))]
        ;; Before `agent-result`, which throws on a tool call this stream may
        ;; have truncated. This is the path that produced the finding: four of
        ;; six streamed calls cut at the cap reported `tool_calls`, so the
        ;; ceiling learned here is what the next request is bounded by.
        (note-output-ceiling! provider model @finish-reason @usage max-tokens)
        (assoc (agent-result {:content (.toString content)
                              :tool_calls (mapv val (sort-by key @calls))}
                             @finish-reason @usage max-tokens)
               :served-model @served-model))
      (catch Exception error
        (throw (ex-info (.getMessage error)
                        (assoc (or (ex-data error) {})
                               :stream-emitted? (pos? (.length content)))
                        error))))))

(defn agent-turn-stream!
  "Stream a tool-capable model turn. Text deltas are visible immediately;
  fragmented OpenAI tool calls are assembled before Agent Control sees them."
  [provider request on-delta]
  (cond
    (= :ollama (:kind provider))
    (let [content (StringBuilder.)
          calls (atom {})
          finish-reason (volatile! nil)
          usage (volatile! nil)
          body (assoc (agent-request-body provider request) :stream true)
          response (streaming-response
                    (str (:base-url provider) "/api/chat")
                    (-> body
                        (dissoc :temperature :max_tokens :parallel_tool_calls
                                :reasoning_effort)
                        (assoc :options (ollama-agent-options provider request)))
                    nil)]
      (with-active-agent-reader
        response
        (fn [reader]
          (doseq [line (line-seq reader) :when (not (str/blank? line))]
            (let [chunk (json/read-str line :key-fn keyword)
                  message (:message chunk)
                  delta (:content message)]
              (when-let [emitted (emit! on-delta delta)] (.append content emitted))
              (when-let [tool-calls (seq (:tool_calls message))]
                (reset! calls (into {} (map-indexed vector tool-calls))))
              (when (:done chunk)
                (vreset! finish-reason (:done_reason chunk))
                (vreset! usage
                         {:prompt_tokens (get chunk :prompt_eval_count 0)
                          :completion_tokens (get chunk :eval_count 0)
                          :total_tokens (+ (get chunk :prompt_eval_count 0)
                                           (get chunk :eval_count 0))}))))))
      (let [max-tokens (requested-max-tokens provider request)]
        (note-output-ceiling! provider (:model request) @finish-reason
                              @usage max-tokens)
        (agent-result {:content (.toString content)
                       :tool_calls (mapv val (sort-by key @calls))}
                      @finish-reason @usage max-tokens)))

    (openai-shaped? provider)
    (with-model-fallback provider (:model request)
      #(openai-agent-turn-stream-once! provider request % on-delta))

    :else (throw (ex-info "unsupported provider kind" {:provider provider}))))
