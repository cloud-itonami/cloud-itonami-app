(ns cloud.itonami.app.bot-bounds
  "What one Bot may spend and reach in a turn — the row, as a pure decision.

  ADR-2609062600. grok bots are not safe because they run on Cloudflare; they
  are safe because each one carries a row: `allowed_tools`, `allowed_hosts`,
  `budget_tokens`, `max_output_tokens`, and a lease. itonami's bots carry
  `:bot/tools` (the tool allowlist, which already exists) and nothing else from
  that list.

  Measured 2026-09-06 over the resident's own 3,117 recorded turns:

  | | total_tokens per turn |
  |---|---|
  | p50 | 43,299 |
  | p90 | 250,926 |
  | p99 | 6,802,364 |
  | **max** | **29,231,364** |

  Twenty-nine million tokens in one recorded turn, with nothing in the system
  that could have stopped it. Re-derive rather than quote:
  `nbb scripts/measure-bot-usage.cljs` — these numbers are what the machine
  looked like on one day, and this docstring is not where the current ones live.

  ## A bound is not an authorisation, and they default differently

  `manifest/bot-allowances.edn` is deny-by-default because it authorises
  SPENDING SOMEONE'S MONEY: 'no policy is not permission'. This is a different
  kind of thing — a ceiling on work already authorised and already paid for
  under the operator's own key. Defaulting it to zero would stop 237 live bots
  on nobody's decision.

  So: **enforced when set, and REPORTED when absent.** `unbounded?` exists so a
  surface can count the bots that carry no ceiling; an absence nobody can see
  is the failure this whole family of rows is against, and silently reading it
  as `unlimited` is how a ceiling ends up meaning nothing."
  (:require [clojure.string :as str]))

(def schema "cloud.itonami.app.bot-bounds.v1")

(def keys*
  "The row's keys, as they appear on a Bot."
  #{:bot/budget-tokens :bot/budget-window-turns
    :bot/max-output-tokens :bot/max-turns :bot/max-tool-calls
    :bot/allowed-hosts})

(def default-window-turns
  "How many recent turns a budget is measured over when the Bot does not say.

  40, because `bots/max-turn-history` keeps exactly 40 and a window longer than
  the history would silently measure less than it claims."
  40)

(defn unbounded?
  "Whether `b` carries no spend ceiling. For counting, not for deciding."
  [b]
  (nil? (:bot/budget-tokens b)))

(defn window-turns [b]
  (long (or (:bot/budget-window-turns b) default-window-turns)))

(defn spent
  "Total `total_tokens` over the most recent `window` turns of `history`.

  Turns without a usage record contribute 0 rather than being dropped: a turn
  that ran and did not report is not a turn that cost nothing, and treating the
  two the same is how a budget stops noticing the calls it cannot see. The
  count of those is returned beside the sum so a caller can say so."
  [history window]
  (let [recent (take-last (long window) (or history []))
        tok (fn [t] (long (or (get-in t [:turn/usage :total_tokens])
                              (get-in t [:turn/usage "total_tokens"])
                              0)))]
    {:tokens (reduce + 0 (map tok recent))
     :turns (count recent)
     :unreported (count (remove #(pos? (tok %)) recent))}))

(defn admit-spend
  "May this Bot start another turn?

  `{:allowed? true}` or a refusal naming its reason. Three outcomes and not
  two: `:bounds/unbounded` says there is no ceiling to check, which is NOT the
  same fact as a ceiling that was checked and had room."
  [b history]
  (let [budget (:bot/budget-tokens b)]
    (if (nil? budget)
      {:allowed? true :reason :bounds/unbounded
       :message "この Bot に budget がありません。上限は検査していません。"}
      (let [w (window-turns b)
            {:keys [tokens turns unreported]} (spent history w)]
        (if (< tokens (long budget))
          {:allowed? true :reason :bounds/within
           :spent tokens :budget (long budget) :window w :turns turns
           :unreported unreported}
          {:allowed? false :reason :bounds/budget-exhausted
           :spent tokens :budget (long budget) :window w :turns turns
           :unreported unreported
           :message (str "直近 " turns " turn で " tokens " tokens を使い、"
                         "budget " budget " を超えています。"
                         (when (pos? unreported)
                           (str "（うち " unreported
                                " turn は usage を報告しておらず 0 として数えています）")))})))))

(defn cap
  "The Bot's own ceiling for `k`, narrowing `deployment-cap` and never raising it.

  A Bot asking for more than the deployment allows would be a Bot raising its
  own ceiling, which is the thing a ceiling is for. One function for every
  ceiling, so a key added later cannot arrive with different arithmetic."
  [b k deployment-cap]
  (let [b* (get b k)]
    (cond
      (nil? b*) deployment-cap
      (nil? deployment-cap) (long b*)
      :else (min (long b*) (long deployment-cap)))))

(defn output-cap
  "The output-token ceiling for this Bot."
  [b deployment-cap]
  (cap b :bot/max-output-tokens deployment-cap))

(defn turn-cap
  "How many model turns one slice may take.

  Global constants until 2026-09-06 (`bots/max-turns` 8, `max-goal-turns` 24)
  with no config and no per-Bot value — the same shape `max-output-tokens` had.
  A loop bound that every Bot shares is a bound the noisy one sets for everyone."
  [b deployment-cap]
  (cap b :bot/max-turns deployment-cap))

(defn tool-call-cap
  "How many tool calls one slice may make.

  The deployment could already narrow this (`[:bots :goal :max-tool-calls]`);
  the Bot could not."
  [b deployment-cap]
  (cap b :bot/max-tool-calls deployment-cap))

(defn- host-of [url]
  (some-> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)" (str url))
          second
          (as-> a (if-let [i (str/last-index-of a "@")] (subs a (inc i)) a))
          (str/split #":")
          first
          str/lower-case
          not-empty))

(defn admit-host
  "May this Bot reach `url`?

  `:bot/allowed-hosts` absent means this Bot has no destination list and the
  answer is `:bounds/no-host-list` — reported, not silently permitted, and not
  silently denied either. An empty SET is a different statement: someone wrote
  a list with nothing in it, and that denies everything.

  Userinfo is stripped before the host is read: `https://allowed@evil.example/`
  has host `evil.example`, and a check that read the authority would admit it
  on the strength of a name that is not the destination."
  [b url]
  (let [allow (:bot/allowed-hosts b)
        h (host-of url)]
    (cond
      (nil? allow) {:allowed? true :reason :bounds/no-host-list
                    :message "この Bot に allowed-hosts がありません。宛先は検査していません。"}
      (nil? h) {:allowed? false :reason :bounds/unparsable-url
                :message (str "URL を読めません: " (pr-str url))}
      (contains? (set allow) h) {:allowed? true :reason :bounds/host-allowed :host h}
      :else {:allowed? false :reason :bounds/host-not-allowed :host h
             :message (str h " は allowed-hosts にありません: "
                           (str/join " " (sort allow)))})))
