(ns cloud.itonami.app.bot-cache
  "Prompt-cache efficiency scoring for Bots, read-only like `bot-slo`.

  This namespace reads a snapshot; it never changes Bot state. A rate is a
  description of observed work, not authority to call a Bot cheap. Missing
  telemetry stays `nil` (`:unmeasured` by consumers) instead of becoming
  zero or green — the same fail-closed rule `bot-slo` is held to.

  Why this exists: provider prompt caches bill a cache hit at a fraction of
  full input price, and hits require byte-stable request prefixes. The turn
  store already carries per-turn usage (`:turn/usage` —
  `prompt_tokens` plus `prompt_tokens_details.cached_tokens` on the OpenAI
  wire shape, `cache_read_input_tokens` on the Anthropic shape, merged by
  `bots/merge-usage`), so the measurement costs no provider call. What was
  missing was a reader; without one the rate was unmeasurable and prefix
  regressions (a per-turn system message, a rotating model) landed silently."
  (:import [java.time Duration Instant]))

(def schema "cloud.itonami.app.bot-cache.v1")

(defn- instant [value]
  (cond
    (instance? Instant value) value
    (some? value) (try (Instant/parse (str value)) (catch Exception _ nil))))

(defn- pct [n d]
  (if (pos? d) (/ (double n) d) 0.0))

(defn- round1 [value]
  (/ (Math/round (* 10.0 (double value))) 10.0))

(defn cached-usage-value
  "Cached prompt tokens from either wire shape the app merges.

  OpenAI reports `prompt_tokens_details.cached_tokens`; Anthropic reports
  `cache_read_input_tokens`. Both arrive as maps with keyword or string keys
  depending on the client that decoded them, so every shape is read the way
  `bots/merge-usage` writes it. Zero is a real measurement (the provider
  answered with no cache hit) and is returned as 0 — only absence is nil."
  [usage]
  (when (map? usage)
    (let [v (or (get-in usage [:prompt_tokens_details :cached_tokens])
                (get-in usage ["prompt_tokens_details" "cached_tokens"])
                (get usage :cache_read_input_tokens)
                (get usage "cache_read_input_tokens"))]
      (cond
        (number? v) (long (max 0 v))
        ;; Some providers emit prompt_tokens_details: {} with no field. The
        ;; details map is present, so the shape is known and cached is 0.
        (map? (get usage :prompt_tokens_details)) 0
        (map? (get usage "prompt_tokens_details")) 0
        :else nil))))

(defn usage-prompt-tokens
  "Full priced prompt tokens for one usage map, or nil when absent.

  A cache read is still real input against the plan (the same rule the
  bridge scripts are held to), so the denominator is the provider's own
  `prompt_tokens` total — never a hand-derived `prompt - cached`."
  [usage]
  (when (map? usage)
    (let [v (or (get usage :prompt_tokens) (get usage "prompt_tokens"))]
      (when (number? v) (long (max 0 v))))))

(defn- usable-turn?
  "A turn contributes to the rate only when both sides of the ratio are
  measured on it. `prompt_tokens` absent means the usage never arrived (the
  failure modes `run-attribution` documents write no usage); counting such a
  turn as zero-hit would manufacture a regression out of a timeout."
  [turn]
  (let [usage (:turn/usage turn)
        prompt (usage-prompt-tokens usage)]
    (and (number? prompt) (pos? prompt)
         (number? (cached-usage-value usage)))))

(defn- within? [^Instant now hours timestamp]
  (when-let [at (instant timestamp)]
    (and (not (.isAfter at now))
         (not (.isBefore at (.minusSeconds now (* 3600 hours)))))))

(defn- owner-bot-ids [partition session]
  (into #{}
        (keep (fn [[bot-id bot]]
                (when (and (= (:user-id session) (:bot/owner bot))
                           (= (:organization-id session) (:bot/organization bot)))
                  bot-id)))
        (:bots partition)))

(defn- owner-turns [partition session]
  (mapcat #(get-in partition [:turn-history %] [])
          (owner-bot-ids partition session)))

(defn- window [partition session ^Instant now hours]
  (let [turns (->> (owner-turns partition session)
                   (filter #(within? now hours (:turn/started-at %)))
                   vec)
        usable (filterv usable-turn? turns)
        prompt (reduce + 0 (map #(usage-prompt-tokens (:turn/usage %)) usable))
        cached (reduce + 0 (map #(cached-usage-value (:turn/usage %)) usable))
        ;; Per-model breakdown. The model is part of the provider cache key
        ;; (Hermes documents this; a mid-conversation swap re-reads the whole
        ;; conversation at full price), so a rotation shows up here as a
        ;; model row with a low rate beside stable rows.
        by-model (->> usable
                      (group-by #(some-> (:turn/model %) str))
                      (map (fn [[model ts]]
                             (let [p (reduce + 0 (map #(usage-prompt-tokens (:turn/usage %)) ts))
                                   c (reduce + 0 (map #(cached-usage-value (:turn/usage %)) ts))]
                               {:model model
                                :turns (count ts)
                                :prompt-tokens p
                                :cached-tokens c
                                :hit-rate (round1 (* 100.0 (pct c p)))})))
                      (sort-by (fn [row] [(:model row)]))
                      vec)]
    {:hours hours
     :turns (count turns)
     :measured-turns (count usable)
     ;; 0.0 only when measured turns exist and genuinely hit nothing.
     :hit-rate (when (seq usable) (round1 (* 100.0 (pct cached prompt))))
     :prompt-tokens (when (seq usable) prompt)
     :cached-tokens (when (seq usable) cached)
     :by-model by-model}))

(defn evaluate
  "Prompt-cache efficiency for one owner's Bots from a complete app snapshot.

  `now` is injectable so boundary behaviour is an executable test. Windows
  mirror `bot-slo` (24h / 7d). `:hit-rate` is nil — :unmeasured by
  consumers, never 0 — when no turn in the window carries both sides of the
  ratio."
  ([state session] (evaluate state session (Instant/now)))
  ([state session now]
   (let [now (or (instant now) (Instant/now))
         partition (or (:bots state) {})]
     {:schema schema
      :as-of (str now)
      :windows {:hours-24 (window partition session now 24)
                :days-7 (window partition session now (* 24 7))}})))
