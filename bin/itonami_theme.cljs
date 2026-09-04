(ns itonami-theme
  "hermes skin-engine parity, as a harness PLUGIN (deepseek-harness contract).

  The plugin claims :ctx/theme, a service of pure look-&-feel functions. The
  skins are data with hermes_cli/skin_engine.py's key set: :branding
  (:agent-name / :response-label / :welcome), :prompt-symbol, :tool-prefix,
  :colors. Colors are TERM-CODE names (\"gold\", \"dim\") because the nbb EDN
  reader rejects raw ESC and \\uXXXX; the plugin resolves them to ANSI here.

  Drop-in skins: ~/.cloud-itonami/skins/<name>.edn. Missing keys inherit
  from :default. CLOUD_ITONAMI_SKIN selects at boot; /skin switches at
  runtime by reloading the service from a new skin map (harness live patch
  reload)."
  (:require [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [itonami-harness :as h]))

(def ^:private term-codes
  {"gold"  "\u001b[33m"
   "dim"   "\u001b[2m"
   "reset" "\u001b[0m"
   "bold"  "\u001b[1m"
   "cyan"  "\u001b[36m"
   "magenta" "\u001b[35m"
   "red"   "\u001b[31m"
   "green" "\u001b[32m"})

(def builtin-skins
  {:default
   {:name :default
    :description "Classic Hermes gold/kawaii"
    :branding {:agent-name "Itonami Agent"
               :response-label " Itonami "
               :welcome "cloud-itonami-app resident に接続しました"}
    :prompt-symbol "❯"
    :tool-prefix "┊"
    :colors {:banner-accent "gold"
             :banner-dim "dim"}}
   :mono
   {:name :mono
    :description "Clean grayscale monochrome"
    :branding {:agent-name "Itonami"
               :response-label " itonami "
               :welcome "connected"}
    :prompt-symbol ">"
    :tool-prefix "▏"
    :colors {:banner-accent "bold"
             :banner-dim "dim"}}})

(defn code [name] (get term-codes (str name) ""))
(defn wrap [name s] (str (code name) s (code "reset")))

(defn- merge-in
  "Missing keys inherit from :default — the same rule hermes skins use.
  Shallow per-section merge: :branding and :colors merge key-wise, scalars
  replace."
  [user]
  (let [base (:default builtin-skins)
        user (or user {})]
    (-> base
        (merge (select-keys user [:name :description :prompt-symbol :tool-prefix]))
        (update :branding merge (:branding user))
        (update :colors merge (:colors user)))))

(defn load-skin
  "Load `<data-dir>/../skins/<name>.edn`, fall back to builtin, then default.
  The file contains only term-code names, never control bytes — the nbb EDN
  reader rejects raw ESC and \\uXXXX (measured 2026-09-04)."
  [data-dir read-edn skin-name]
  (let [kw (some-> skin-name keyword)
        user-skin
        (when (and skin-name (not (contains? builtin-skins kw)))
          (try
            (let [p (path/resolve (str data-dir) ".." "skins" (str skin-name ".edn"))]
              (when (fs/existsSync p)
                (read-edn p)))
            (catch :default _ nil)))]
    (if user-skin
      (assoc (merge-in user-skin) :name (or kw :default))
      (get builtin-skins (or kw :default) (:default builtin-skins)))))

(defn- service-impl [skin]
  {:skin skin
   :agent-name #(get-in skin [:branding :agent-name])
   :response-label #(get-in skin [:branding :response-label])
   :welcome #(get-in skin [:branding :welcome])
   :prompt-symbol #(get-in skin [:prompt-symbol])
   :tool-prefix #(get-in skin [:tool-prefix])
   :accent #(wrap (get-in skin [:colors :banner-accent]) %)
   :dim #(wrap (get-in skin [:colors :banner-dim]) %)
   :emit (fn [s] (js/process.stdout.write (str s)))})

(def plugin
  "The theme plugin. Claims :ctx/theme. The service carries :switch!, which
  re-provides :ctx/theme from a new skin name — /skin triggers a live patch
  reload, the same mechanism a dsh profile uses."
  {:name "itonami.theme"
   :inject [:ctx/config]
   :provides [:ctx/theme]
   :description "hermes skin-engine parity as a data-driven skin service"
   :apply
   (fn [ctx {:keys [ctx/config]}]
     (let [{:keys [data-dir read-edn]} config
           env-skin (some-> (aget js/process.env "CLOUD_ITONAMI_SKIN")
                            str/trim not-empty)
           provide-skin
           (fn provide-skin* [skin]
             (h/provide! ctx :ctx/theme
                         (assoc (service-impl skin)
                                :switch!
                                (fn [new-name]
                                  (provide-skin* (load-skin data-dir read-edn new-name))))))]
       (provide-skin (load-skin data-dir read-edn env-skin))))})
