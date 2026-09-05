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
   :apply (fn [{:keys [config data-dir]}]
            (let [custom (fn [name]
                           (when-let [t (try (some-> (fs/readFileSync
                                                      (path/resolve data-dir
                                                                   "skins" (str name ".edn"))
                                                     "utf8")
                                                     edn/read-string)
                                             (catch :default _ nil))]
                             (merge default-theme t)))
                  state (atom (or (custom (:skin config)) default-theme))]
              {:state state
               :skin (fn [] @state)
               :set-skin! (fn [name]
                            (let [t (or (get skins name) (custom name))]
                              (if t
                                (do (reset! state t) {:ok true :skin t})
                                {:ok false :available (vec (sort (keys skins)))})))}))})

;; ---------------------------------------------------------------------------
;; layer 3: chat — the slash registry and REPL state
;; ---------------------------------------------------------------------------

(defn chat-plugin
  "Provides :ctx/chat. Deps: :ctx/config and :ctx/theme. Holds the registry
  of slash commands (a map from command name to handler) and the REPL-facing
  state (current profile, the card a held run waits on). Handlers receive
  [ctx words] and return :exit, :handled, a promise, or nil (not a command)."
  []
  {:name :itonami.chat
   :inject [:ctx/config :ctx/theme]
   :provides :ctx/chat
   :description "slash registry, REPL state (profile, held card)"
   :apply (fn [_]
            (let [registry (atom {})
                  profile (atom "default")
                  held (atom nil)]
              {:registry registry
               :profile profile
               :held held
               :register! (fn [cmd handler]
                            (swap! registry assoc cmd handler)
                            (fn [] (swap! registry dissoc cmd)))
               :commands (fn [] (vec (sort (keys @registry))))}))})
