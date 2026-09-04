(ns itonami-chat
  "The `itonami chat` agent REPL (stage 3 of the hermes-parity refactor).

  Slash commands are DATA, one registry modeled on hermes's
  hermes_cli/commands.py COMMAND_REGISTRY: each entry carries
  {:name :args-hint :description :handler}. The /help text and the top-level
  help listing are GENERATED from this table — adding a command is one map
  entry, and help can never drift from what actually dispatches.

  Handler contract (unchanged from the pre-refactor chat-slash!):
    returns :exit      -> the REPL ends
    returns :handled   -> the REPL re-prompts (sync commands)
    returns a Promise  -> the REPL awaits it, then re-prompts
    returns nil        -> the line was not a command; the REPL sends it to
                          the agent as a run input.
  The (= :handled r) dispatch arm is LOAD-BEARING: without it every sync
  command falls through and is ALSO sent to the agent (the double-answer
  bug fixed 2026-09-04)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(def ^:private chat-default-profile "default")
(def ^:private chat-profile (atom chat-default-profile))

;; The card the current run is held on, set by a `waiting-approval` phase
;; event, consumed by /approve and /deny in the REPL.
(def ^:private chat-approval (atom nil))

(defn chat-emit [text]
  (js/process.stdout.write (str text)))

(defn profile [] @chat-profile)
(defn set-profile! [p] (reset! chat-profile p))
(defn approval [] @chat-approval)
(defn set-approval! [v] (reset! chat-approval v))
(defn clear-approval-if-run! [run-id]
  (swap! chat-approval (fn [a] (when (= run-id (:run-id a)) nil))))
(defn default-profile [] chat-default-profile)

;; ---------------------------------------------------------------------------
;; the slash-command registry — hermes COMMAND_REGISTRY shape
;; ---------------------------------------------------------------------------

(defonce ^:private registry
  (atom
   (sorted-map-by
    (fn [a b] (compare a b))
    "/help"    {:name "/help"    :args-hint ""          :description "この一覧を表示"
                :handler (fn [args ctx] ((:help ctx) args))}
    "/sessions" {:name "/sessions" :args-hint ""         :description "セッション一覧 (hermes 互換)"
                :handler (fn [args ctx] ((:sessions ctx) args))}
    "/history" {:name "/history" :args-hint "[N]"      :description "直近 N 件のメッセージ表示 (hermes 互換)"
                :handler (fn [args ctx] ((:history ctx) args))}
    "/steer"   {:name "/steer"   :args-hint "<text>"   :description "実行中 run への割り込み注入 (hermes 互換)"
                :handler (fn [args ctx] ((:steer ctx) args))}
    "/stop"    {:name "/stop"    :args-hint ""          :description "実行中 run の停止 (hermes 互換)"
                :handler (fn [args ctx] ((:stop ctx) args))}
    "/bots"    {:name "/bots"    :args-hint ""          :description "Bot 一覧"
                :handler (fn [args ctx] ((:bots ctx) args))}
    "/profiles" {:name "/profiles" :args-hint ""        :description "profile 一覧"
                :handler (fn [args ctx] ((:profiles ctx) args))}
    "/profile" {:name "/profile" :args-hint "<id>"      :description "対話先を切り替え"
                :handler (fn [args ctx] ((:profile ctx) args))}
    "/skin"    {:name "/skin"    :args-hint "[<name>]"  :description "テーマの表示・切替 (hermes /skin 互換)"
                :handler (fn [args ctx] ((:skin ctx) args))}
    "/approve" {:name "/approve" :args-hint ""          :description "承認待ちの card を許可"
                :handler (fn [args ctx] ((:approve ctx) args))}
    "/deny"    {:name "/deny"    :args-hint ""          :description "承認待ちの card を拒否"
                :handler (fn [args ctx] ((:deny ctx) args))}
    "/status"  {:name "/status"  :args-hint ""          :description "常駐サーバの状態"
                :handler (fn [args ctx] ((:status ctx) args))}
    "/exit"    {:name "/exit"    :args-hint ""          :description "終了"
                :handler (fn [args ctx] ((:exit ctx) args))}
    ;; --- stage 4: gap-client commands (server routes already exist) ---
    "/whoami"  {:name "/whoami"  :args-hint ""          :description "operator の確認 (hermes 互換)"
                :handler (fn [args ctx] ((:whoami ctx) args))}
    "/model"   {:name "/model"   :args-hint "[<model>]" :description "model-routing の表示・設定 (hermes 互換)"
                :handler (fn [args ctx] ((:model ctx) args))}
    "/version" {:name "/version" :args-hint ""          :description "サーバの version / update 状態"
                :handler (fn [args ctx] ((:version ctx) args))}
    "/context" {:name "/context" :args-hint ""          :description "session context sources の表示"
                :handler (fn [args ctx] ((:context ctx) args))})))

(defn commands [] (vals @registry))

(defn pr-edn [v] (binding [*print-length* 12] (pr-str v)))

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
  Handlers receive [args ctx]; the contract is chat-slash!'s (see ns)."
  [name args-hint description handler]
  (swap! registry assoc name
         {:name name :args-hint args-hint
          :description description :handler handler}))

(defn dispatch
  "One dispatch per line. Returns :exit / :handled / Promise / nil."
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
