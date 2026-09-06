;; itonami profile layers as harness plugins (ADR-2609042200 Decision 2).
;;
;; The shipped profile (`default`) is an ordered three-layer composition:
;;
;;   itonami.config → :ctx/config   install path, EDN reader, configuration
;;   itonami.theme  → :ctx/theme    skin engine (hermes parity keys)
;;   itonami.chat   → :ctx/chat     slash registry, REPL state
;;
;; Layer replacement is a later plugin with the same :provides key — the
;; dsh patch shape. /skin is the worked example: it re-provides :ctx/theme
;; live, without remounting the layers above or below it.

(ns itonami-profile
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; layer 1: config
;; ---------------------------------------------------------------------------

(defn config-plugin
  "Provides :ctx/config. Deps: none. Carries what every later layer needs to
  know about where this install lives and what it is configured with."
  [{:keys [app-directory data-dir configuration]}]
  {:name :itonami.config
   :inject []
   :provides :ctx/config
   :description "install path, EDN reader, configuration"
   :apply (constantly {:app-directory app-directory
                       :data-dir data-dir
                       :config configuration
                       ;; the EDN reader later layers share, so a layer never
                       ;; reaches for node:fs on its own
                       :read-edn (fn [p]
                                   (try (some-> (fs/readFileSync p "utf8")
                                                edn/read-string)
                                        (catch :default _ nil)))})})

;; ---------------------------------------------------------------------------
;; layer 2: theme — the skin engine, hermes parity keys
;; ---------------------------------------------------------------------------

(def ^:private default-theme
  {:name "default"
   :prompt "you ❯ "
   :accent "·"
   :banner "itonami — cloud-itonami-app の front end"})

(def ^:private skins
  ;; hermes parity keys: :prompt :accent :banner. A skin file may live in
  ;; <data-dir>/skins/<name>.edn; the shipped ones are here.
  (let [kawaii {:name "kawaii"
                :prompt "you ♡ "
                :accent "♡"
                :banner "itonami ♡ ようこそ"}
        grok {:name "grok"
              :prompt "you ▮ "
              :accent "▮"
              :banner "itonami — one workspace"}]
    {"default" default-theme "kawaii" kawaii "grok" grok}))

(defn theme-plugin
  "Provides :ctx/theme. Deps: :ctx/config (for the skin search path).
  The value is an atom holding the active skin — /skin's live re-provide
  resets it and every reader of :ctx/theme sees the change without
  remounting anything."
  []
  {:name :itonami.theme
   :inject [:ctx/config]
   :provides :ctx/theme
   :description "skin engine (hermes parity keys: prompt accent banner)"
   ;; `mount!` passes `(select-keys services inject)`, so the argument is
   ;; `{:ctx/config <service>}` -- NOT the service itself. Destructuring
   ;; `{:keys [config data-dir]}` off it read nil for both, which is why
   ;; `:skin` in configuration never selected anything while `/skin` did:
   ;; a DECLARED dependency this layer could not actually read (measured
   ;; 2026-09-06, through a repository profile's `:cli/config`).
   :apply (fn [deps]
            (let [{:keys [config data-dir]} (:ctx/config deps)]
            (let [custom (fn [name]
                           (when-let [t (try (some-> (fs/readFileSync
                                                      (path/resolve data-dir
                                                                   "skins" (str name ".edn"))
                                                     "utf8")
                                                     edn/read-string)
                                             (catch :default _ nil))]
                             (merge default-theme t)))
                  requested (:skin config)
                  ;; SHIPPED first, then a file. `config`'s `:skin` used to
                  ;; consult `custom` alone, so `{:skin "grok"}` in
                  ;; configuration resolved to nothing and fell back to the
                  ;; default -- while `/skin grok` worked. Two ways to name a
                  ;; skin, one of them silently inert (measured 2026-09-06,
                  ;; through a repository profile's `:cli/config`).
                  resolved (when requested
                             (or (get skins requested) (custom requested)))
                  _ (when (and requested (nil? resolved))
                      ;; A name that resolved to nothing must not look like no
                      ;; name at all. There is no screen at mount time, so this
                      ;; is stderr -- but it is said.
                      (binding [*print-fn* *print-err-fn*]
                        (println (str "itonami: skin \"" requested
                                      "\" がありません。既定で続行します。"
                                      " 使えるもの: "
                                      (str/join " " (sort (keys skins)))))))
                  state (atom (or resolved default-theme))]
              {:state state
               :skin (fn [] @state)
               :set-skin! (fn [name]
                            (let [t (or (get skins name) (custom name))]
                              (if t
                                (do (reset! state t) {:ok true :skin t})
                                {:ok false :available (vec (sort (keys skins)))})))})))})

;; ---------------------------------------------------------------------------
;; layer 3: chat — the slash registry and REPL state
;; ---------------------------------------------------------------------------

(defn chat-plugin
  "Provides :ctx/chat. Deps: :ctx/config and :ctx/theme.

  The registry is the ONE decider for slash commands: an entry carries its own
  name, argument hint, description and handler, and `/help` is GENERATED from
  it. The shape is hermes's `COMMAND_REGISTRY` (hermes_cli/commands.py).

  This replaced a `case` in `bin/itonami` that dispatched, plus a registry of
  no-op wrappers that only listed -- two views of one thing, and the listing
  was the one that could quietly go stale. It had: `/help` printed a literal
  block that did not mention `/help`.

  Handlers receive [args ctx] and return:
    :exit      the REPL ends
    :handled   the REPL re-prompts
    :resend    the REPL re-submits `last-input` as a message (/retry, /prompt)
    a Promise  the REPL awaits it, then re-prompts
    nil        not a command; the line goes to the agent

  The REPL also keeps its display preferences here (`/verbose`,
  `/timestamps`) and the last line the operator sent (`/retry`)."
  []
  {:name :itonami.chat
   :inject [:ctx/config :ctx/theme]
   :provides :ctx/chat
   :description "slash registry (name, args, description, handler), REPL state"
   :apply (fn [_]
            (let [registry (atom {})
                  profile (atom "default")
                  held (atom nil)
                  verbose (atom false)
                  timestamps (atom false)
                  last-input (atom nil)]
              {:registry registry
               :profile profile
               :held held
               :verbose verbose
               :timestamps timestamps
               :last-input last-input
               :register!
               (fn register!
                 ;; The 2-arity is what the older call sites used. Keeping it
                 ;; means a command registered without a description shows up
                 ;; in `/help` with an empty one rather than not at all --
                 ;; visible, which is the point.
                 ([cmd handler] (register! cmd "" "" handler))
                 ([cmd args-hint description handler]
                  (swap! registry assoc cmd
                         {:name cmd :args-hint args-hint
                          :description description :handler handler})
                  (fn [] (swap! registry dissoc cmd))))
               :commands (fn [] (vec (sort (keys @registry))))
               :entries (fn [] (vec (vals (into (sorted-map) @registry))))
               :help-text
               (fn []
                 (str/join
                  "\n"
                  (map (fn [{:keys [name args-hint description]}]
                         (str "  " name
                              (when-not (str/blank? (str args-hint))
                                (str " " args-hint))
                              (when-not (str/blank? (str description))
                                (str " — " description))))
                       (vals (into (sorted-map) @registry)))))
               :stamp
               (fn []
                 (if @timestamps
                   (let [d (js/Date.)
                         two #(.padStart (str %) 2 "0")]
                     (str "[" (two (.getHours d)) ":" (two (.getMinutes d))
                          ":" (two (.getSeconds d)) "] "))
                   ""))}))})
