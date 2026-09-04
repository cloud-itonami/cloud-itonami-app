(ns itonami-chat
  "The `itonami chat` agent REPL, as a harness PLUGIN (deepseek-harness
  contract).

  Slash commands are DATA, one registry modeled on hermes's
  hermes_cli/commands.py COMMAND_REGISTRY: each entry carries
  {:name :args-hint :description :handler}. /help and the top-level help
  listing are GENERATED from this table — adding a command is one register!
  call, and help can never drift from dispatch.

  Handler contract:
    returns :exit      -> the REPL ends
    returns :handled   -> the REPL re-prompts (sync commands)
    returns :resend    -> the REPL re-submits chat/last-input (the /retry
                          and /prompt hand-off)
    returns a Promise  -> the REPL awaits it, then re-prompts
    returns nil        -> not a command; the line goes to the agent as a run.
  The (= :handled r) dispatch arm is LOAD-BEARING: without it every sync
  command also falls through to the agent (the double-answer bug fixed
  2026-09-04)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [itonami-harness :as h]))

;; ---------------------------------------------------------------------------
;; state — the REPL's own service-internal state
;; ---------------------------------------------------------------------------

(def ^:private chat-default-profile "default")
(def ^:private chat-profile (atom chat-default-profile))
(def ^:private chat-approval (atom nil))

;; REPL display prefs (hermes /verbose /timestamps parity) + last input (/retry)
(def ^:private verbose-state (atom false))
(def ^:private timestamps-state (atom false))
(def ^:private last-line (atom nil))

(defn chat-emit [text]
  (js/process.stdout.write (str text)))

(defn profile [] @chat-profile)
(defn set-profile! [p] (reset! chat-profile p))
(defn approval [] @chat-approval)
(defn set-approval! [v] (reset! chat-approval v))
(defn clear-approval-if-run! [run-id]
  (swap! chat-approval (fn [a] (when (= run-id (:run-id a)) nil))))
(defn default-profile [] chat-default-profile)

(defn verbose? [] @verbose-state)
(defn set-verbose! [v] (reset! verbose-state v))
(defn timestamps? [] @timestamps-state)
(defn set-timestamps! [v] (reset! timestamps-state v))
(defn record-input! [line] (reset! last-line line))
(defn last-input [] @last-line)

(defn stamp
  "Timestamp prefix when /timestamps is on."
  []
  (if @timestamps-state
    (let [d (js/Date.)]
      (str "["
           (.padStart (str (.getHours d)) 2 "0") ":"
           (.padStart (str (.getMinutes d)) 2 "0") ":"
           (.padStart (str (.getSeconds d)) 2 "0") "] "))
    ""))

(defn pr-edn [v] (binding [*print-length* 12] (pr-str v)))

;; ---------------------------------------------------------------------------
;; the slash-command registry — hermes COMMAND_REGISTRY shape
;; ---------------------------------------------------------------------------

(defonce ^:private registry
  (atom
   (sorted-map-by
    (fn [a b] (compare a b))
    "/help"    {:name "/help"    :args-hint ""          :description "この一覧を表示"
                :handler (fn [args ctx] ((:help ctx) args))})))

(defn commands [] (vals @registry))

(defn help-text
  "Generated from the registry — help cannot drift from dispatch."
  []
  (str/join "\n"
            (map (fn [{:keys [name args-hint description]}]
                   (str "  " name
                        (when (seq args-hint) (str " " args-hint))
                        " — " description))
                 (commands))))

(defn register!
  "Add or replace a slash command: (register! \"/new\" \"\" \"説明\" handler).
  Handlers receive [args ctx]; the contract is in the ns comment."
  [name args-hint description handler]
  (swap! registry assoc name
         {:name name :args-hint args-hint
          :description description :handler handler}))

(defn unregister! [name]
  (swap! registry dissoc name)
  nil)

(defn dispatch
  "One dispatch per line. Returns :exit / :handled / :resend / Promise / nil."
  [line ctx]
  (let [words (str/split (str/trim line) #"\s+")
        cmd (first words)
        args (vec (rest words))
        say-error (fn [e] (println (str "error: " (ex-message e))))
        entry (get @registry cmd)]
    (cond
      entry
      (try
        ((:handler entry) args ctx)
        (catch :default e
          (say-error e)
          :handled))

      (str/starts-with? (str cmd) "/")
      (do (println (str "そのような command はありません: " cmd " (/help)"))
          :handled)

      :else nil)))

;; ---------------------------------------------------------------------------
;; the plugin — claims :ctx/chat (registry + state), contributes the
;; system-prompt section that tells the agent which slash commands exist
;; (dsh: prompt sections are plugin registrations, reversible on unmount)
;; ---------------------------------------------------------------------------

(def plugin
  {:name "itonami.chat"
   :inject [:ctx/config]
   :provides [:ctx/chat]
   :description "the chat REPL: slash registry, display prefs, last-input"
   :apply
   (fn [ctx {:keys [ctx/config]}]
     (h/provide! ctx :ctx/chat
                 {:help-text help-text
                  :commands commands
                  :register! register!
                  :unregister! unregister!
                  :dispatch dispatch
                  :profile profile
                  :set-profile! set-profile!
                  :approval approval
                  :set-approval! set-approval!
                  :clear-approval-if-run! clear-approval-if-run!
                  :default-profile default-profile
                  :verbose? verbose?
                  :set-verbose! set-verbose!
                  :timestamps? timestamps?
                  :set-timestamps! set-timestamps!
                  :record-input! record-input!
                  :last-input last-input
                  :stamp stamp
                  :pr-edn pr-edn
                  :emit chat-emit})
     (h/on ctx "itonami.chat" "prompt/help-listing"
           (fn [_] (help-text))))})
