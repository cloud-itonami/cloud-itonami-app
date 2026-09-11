(ns cloud.itonami.mobile.terminal
  "What a typed command line means, on a phone.

  Pure. It is handed a line of text and returns data: a request to make, or a
  refusal that says which kind of refusal it is. No fetch, no atom, no window —
  the same split `cloud.itonami.mobile.view` already keeps, so every outcome
  below can be asserted on the JVM without a browser.

  ## There is no second decider here

  `cloud.itonami.app.commands` owns the command tables, the longest-prefix
  match, the path templating and the query/body split. `bin/itonami` says why
  in its own header: a second resolver in ClojureScript would be \"two answers
  that agree until the day they do not, with no test that would notice\"
  (ADR-2608197300 §3). The phone is a third front end and it resolves through
  the same namespace — this file adds the *phone's* two facts and nothing else:
  how a line of text becomes argv, and which commands this surface declines to
  offer at all.

  ## Why not `kotoba-lang/kuro`

  `kuro.terminal` is this workspace's terminal model and the obvious candidate.
  It does not fit, and the reason is worth writing down so the next reader does
  not think it was overlooked: `kuro/session` takes a `repo-root-cid` and its
  four modes are shell-shaped (`repo/read`, `repo/write`, `host/shell`). This
  surface runs no shell and has no repo — its \"commands\" are HTTP routes on
  the itonami resident. Adopting kuro would mean inventing a repo root that
  does not exist and picking a capability set that describes something else.
  The thing that DOES already exist for this problem is `commands.cljc`, and
  that is what this uses.

  ## The three outcomes are the contract

  `bin/itonami` publishes exit codes 0 / 1 / 2 and says they must not collapse:
  \"'The server said no' is a measurement; 'nothing was listening' is not, and
  an operator sent to read a refusal that never happened debugs the wrong
  thing\" (ADR-2608136000). The phone has no exit code, so the same distinction
  is carried in `:outcome`:

  | `:outcome`   | means                                   | CLI exit |
  |--------------|-----------------------------------------|----------|
  | `:request`   | a request to make; the server answers   | 0 or 1   |
  | `:refused`   | this client refused before any socket   | 1        |
  | `:unavailable` | could not be resolved or is not offered here | 2  |

  A `:refused` and an `:unavailable` must never be rendered the same way. The
  first is an answer; the second is the absence of one."
  (:require [kotoba.lang.text :as str]
            [cloud.itonami.app.commands :as commands]))

(def schema "cloud.itonami.mobile.terminal.v1")

;; ---------------------------------------------------------------------------
;; what this surface offers
;; ---------------------------------------------------------------------------

(def offered-methods
  "The HTTP methods this surface will issue.

  Reads, and the one write that IS the conversation: posting a message to a Bot.
  Everything else — `bots decide`, `bots provision`, workforce, approvals — is
  withheld, and withheld HERE as well as at the ingress.

  Two gates for one rule is deliberate. The ingress Worker is the authority and
  answers 404 for a path it does not carry (cloud-itonami-apex owns it now;
  its `services/mcp-edge/src/index.js` chose 404 over 405 so a scanner learns
  nothing). But a 404 read back on a phone says \"no such thing\", which is
  false — the command exists, this surface declines to offer it. So the client
  refuses first, in its own words, and says that it was the client.

  The rule itself is ADR-0083: approving a write needs a Passkey present, and a
  phone paired by token has not presented one. Widening this set is a decision
  in a diff, not a default."
  #{:get})

(def offered-writes
  "The exact write paths this surface offers, as literal templates.

  A set of templates rather than a method: `:post` alone would offer every
  write the registry knows, which is what this is for keeping out. Adding a
  template here publishes one more thing a paired phone can do without a
  Passkey."
  #{"/api/agent-bots/{id}/messages"
    "/p/{profile}/api/sessions"
    "/p/{profile}/api/sessions/{session}/messages"})

;; ---------------------------------------------------------------------------
;; a line of text becomes argv
;; ---------------------------------------------------------------------------

(defn- whitespace?
  "Portable space test. `Character/isWhitespace` is JVM-only and this file
  compiles to both — a reader conditional here would be a second answer to
  \"what separates two words\", which is the one thing this namespace exists to
  avoid having two of."
  [c]
  (contains? #{\space \tab \newline \return \formfeed} c))

(defn split-line
  "Split a typed line into argv, honouring double quotes.

  A brief is a sentence and a phone keyboard is where someone will type one, so
  `bots task --id b --text \"進捗を教えて\"` has to survive. Only the double
  quote is special: a single quote is an apostrophe far more often than it is a
  delimiter, and treating it as one turns `don't` into an unterminated string.

  An unterminated quote yields the words up to the end rather than an error.
  The line is being typed by a person who can see it; refusing to parse a line
  they are halfway through writing is not help."
  [line]
  (loop [chars (seq (str line)) current nil quoted? false acc []]
    (if-let [c (first chars)]
      (cond
        (= \" c) (recur (rest chars) (or current "") (not quoted?) acc)
        (and (not quoted?) (whitespace? c))
        (recur (rest chars) nil false (cond-> acc current (conj current)))
        :else (recur (rest chars) (str (or current "") c) quoted? acc))
      (cond-> acc current (conj current)))))

;; ---------------------------------------------------------------------------
;; the plan
;; ---------------------------------------------------------------------------

(defn- unavailable [message data]
  (merge {:outcome :unavailable :message message} data))

(defn- offered?
  "Whether this surface issues `request` at all."
  [{:keys [method]} template]
  (or (contains? offered-methods method)
      (contains? offered-writes template)))

(defn plan
  "What `line` means, as data. Never throws.

  `commands/alias-request` throws `:commands/missing-parameter` for a flag it
  needs and does not have; that is a refusal this client made, so it is caught
  here and returned as one rather than escaping into a promise chain where it
  would arrive looking like a network failure."
  [line]
  (let [argv (split-line line)]
    (if (empty? argv)
      {:outcome :empty}
      (let [{:keys [kind command flags rest words] :as invocation}
            (commands/resolve-invocation argv)]
        (case kind
          :unknown
          (unavailable (str "そのようなコマンドはありません: "
                            (str/join " " words))
                       {:reason :no-such-command
                        :suggestions (->> (commands/matching (take 1 words))
                                          (map commands/command-name)
                                          (take 5)
                                          vec)})

          :host-side
          (unavailable
           (str "`" (str/join " " words) "` は端末の中で動くコマンドで、"
                "この画面からは実行できません。")
           {:reason :host-side})

          (try
            (let [request (if (= :alias kind)
                            (commands/alias-request command flags {})
                            (commands/request command
                                              (merge (zipmap (map :name (:params command))
                                                             rest)
                                                     (into {} (map (fn [[k v]] [(name k) v]))
                                                           flags))
                                              nil))
                  template (or (:template command) (:path request))]
              (if (offered? request template)
                {:outcome :request
                 :request request
                 :label (str/join " " words)
                 :invocation invocation}
                (unavailable
                 (str "`" (str/join " " words) "` はこの画面では提供していません。"
                      "書き込みの承認には Passkey が必要で、token で対になった"
                      "端末は提示していません（ADR-0083）。")
                 {:reason :not-offered-on-this-surface
                  :method (:method request)})))
            (catch #?(:clj Exception :cljs :default) e
              ;; The reason is the UPSTREAM literal, carried through rather
              ;; than translated. `commands` refuses an absent flag with
              ;; `:commands/missing-argument` on the alias path and
              ;; `:commands/missing-parameter` on the registry path -- two
              ;; names for two code paths, and flattening them here would mean
              ;; a test could not tell which one it exercised. Carrying the
              ;; literal also makes a rename upstream fail this suite, which is
              ;; the assertion working rather than the assertion being brittle
              ;; (ADR-2608136000 §6).
              (let [data (ex-data e)
                    message #?(:clj (.getMessage ^Exception e) :cljs (ex-message e))]
                {:outcome :refused
                 :message (or message "コマンドを組み立てられませんでした。")
                 :reason (or (:type data) :malformed)}))))))))

;; ---------------------------------------------------------------------------
;; the transcript
;; ---------------------------------------------------------------------------

(def max-entries
  "How many entries the transcript keeps.

  A phone scrolls, and an unbounded transcript on a long-lived WebView is a
  memory leak with a nice name. The oldest are dropped, not the newest: the
  answer someone is looking at is the one that just arrived."
  200)

(defn entry
  "One transcript entry. `kind` is `:sent`, `:answered`, `:refused`,
  `:unavailable` or `:failed`, and the view branches on it — the whole point of
  keeping them distinct rather than folding everything into text."
  [kind text & [attrs]]
  (merge {:schema schema :kind kind :text text} attrs))

(defn append
  "Add `e` to `transcript`, bounded."
  [transcript e]
  (let [next (conj (vec transcript) e)]
    (if (> (count next) max-entries)
      (vec (drop (- (count next) max-entries) next))
      next)))
