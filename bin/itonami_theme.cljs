(ns itonami-theme
  "hermes skin-engine parity, as a module (stage 3 of the hermes-parity
  refactor). Skins are pure data with hermes_cli/skin_engine.py's key set:
  :branding (agent-name / response-label / welcome), :prompt-symbol,
  :tool-prefix, :colors (banner-accent / banner-dim / reset).

  A user skin is ~/.cloud-itonami/skins/<name>.edn; any section given
  replaces the builtin section's keys (per-key merge inside a section),
  and keys the user does not name inherit from :default — the same
  missing-keys-inherit rule hermes documents for ~/.hermes/skins/*.yaml.

  EDN strings here cannot carry raw ESC bytes or \\uXXXX escapes (the nbb
  reader rejects both), so colors are given as TERM-CODE names resolved
  through +term-codes+ below."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]))

(def ^:private term-codes
  {"gold" "\u001b[33m" "dim" "\u001b[2m" "reset" "\u001b[0m"
   "magenta" "\u001b[35m" "cyan" "\u001b[36m" "red" "\u001b[31m"
   "green" "\u001b[32m" "blue" "\u001b[34m" "gray" "\u001b[90m" "" ""})

(defn- code [name] (get term-codes (str name) ""))

(def ^:private builtin-skins
  {:default {:name "default"
             :branding {:agent-name "Itonami Agent"
                        :response-label " ⚕ Itonami "
                        :welcome "cloud-itonami-app resident に接続しました"}
             :prompt-symbol "❯"
             :tool-prefix "┊"
             :colors {:banner-accent "gold" :banner-dim "dim" :reset "reset"}}
   :mono {:name "mono"
          :branding {:agent-name "Itonami Agent"
                     :response-label " Itonami "
                     :welcome "cloud-itonami-app resident に接続しました"}
          :prompt-symbol "❯"
          :tool-prefix "▏"
          :colors {:banner-accent "" :banner-dim "dim" :reset "reset"}}})

(defn skins-directory [data-dir]
  (path/resolve data-dir ".." "skins"))

(defn- skin-file-path [data-dir name]
  (path/resolve (skins-directory data-dir) (str name ".edn")))

(defn- merge-skin
  "Shallow per-section merge: user sections override builtin sections,
  exactly like hermes_cli/skin_engine.py's missing-keys-inherit rule."
  [base user]
  (reduce (fn [acc [k v]]
            (if (and (map? v) (map? (get acc k)))
              (assoc acc k (merge (get acc k) v))
              (assoc acc k v)))
          base
          user))

(defn load-skin
  ([data-dir name] (load-skin data-dir name nil))
  ([data-dir name raw-read-edn]
   (let [base (get builtin-skins (keyword name) (:default builtin-skins))
         user (when (and (not= name "default") raw-read-edn)
                (try (raw-read-edn (skin-file-path data-dir name))
                     (catch :default _ nil)))]
     (if (map? user)
       (update-in (merge-skin base user) [:colors]
                  (fn [colors] (into {} (map (fn [[k v]] [k (code v)]) colors))))
       (update-in base [:colors]
                  (fn [colors] (into {} (map (fn [[k v]] [k (code v)]) colors))))))))

(defonce ^:private store
  (atom {:data-dir nil :name "default" :skin nil}))

(defn init! [data-dir raw-read-edn]
  (let [env-raw (aget js/process.env "CLOUD_ITONAMI_SKIN")
        name (let [t (and env-raw (.-trim env-raw))]
               (if (and t (pos? (.-length t))) t "default"))
        skin (load-skin data-dir name raw-read-edn)]
    (reset! store {:data-dir data-dir :name name :skin skin})
    skin))

(defn active [] (:skin @store))

(defn active-name []
  (or (:name @store) (get-in (active) [:name]) "default"))

(defn switch! [name raw-read-edn]
  (let [skin (load-skin (:data-dir @store) name raw-read-edn)]
    (swap! store assoc :name (or (:name skin) name) :skin skin)
    skin))

(defn get-in-active [ks]
  (get-in (active) ks))

(defn emit
  "Print a line in the skin's accent color (hermes banner-accent style)."
  [text]
  (println (str (get-in (active) [:colors :banner-accent])
                text
                (get-in (active) [:colors :reset]))))

(defn dim [text]
  (str (get-in (active) [:colors :banner-dim])
       text
       (get-in (active) [:colors :reset])))

(defn prompt-symbol [] (get-in (active) [:prompt-symbol] "❯"))
(defn agent-name [] (get-in (active) [:branding :agent-name] "Itonami Agent"))
(defn welcome [] (get-in (active) [:branding :welcome] ""))
