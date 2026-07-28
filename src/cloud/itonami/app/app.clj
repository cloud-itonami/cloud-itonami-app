(ns cloud.itonami.app.app
  (:require [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]))

(defn- node-ops [id tag attrs text children]
  (concat [[:dom/create-element id tag]]
          (map (fn [[key value]] [:dom/set-attr id key (str value)]) attrs)
          (when text
            [[:dom/create-text (+ 10000 id) text]
             [:dom/append-child id (+ 10000 id)]])
          (map (fn [child] [:dom/append-child id child]) children)))

(defn- shortened [value limit]
  (let [value (str (or value ""))]
    (if (> (count value) limit)
      (str (subs value 0 limit) "…")
      value)))

(defn start []
  (let [configuration (config/load-config)
        state (store/snapshot)
        response (:last-response state)
        providers (:providers configuration)
        enabled (filter :enabled? providers)
        api (str "http://" (get-in configuration [:server :host]) ":"
                 (get-in configuration [:server :port]))]
    {:kotoba.app/surface-ops
     (vec
      (concat
       (node-ops 1 :main {:class "local-ai"} nil [2 3 4 5])
       (node-ops 2 :header {} nil [20 21 22])
       (node-ops 20 :h1 {} "Cloud Itonami" [])
       (node-ops 21 :p {} "あなたのモデル、記憶、道具。まずローカルで。" [])
       (node-ops 22 :p {}
                 (str "● PRIVATE · " (count enabled) " provider"
                      (when (not= 1 (count enabled)) "s")
                      " · " (count (:datoms state)) " memory datoms") [])
       (node-ops 3 :section {:class "liquid-glass__panel"} nil [30 31 32])
       (node-ops 30 :h2 {} "Chat" [])
       (node-ops 31 :textarea {:id "prompt"
                               :placeholder "ローカル AI に依頼する…"} nil [])
       (node-ops 32 :button {:class "liquid-glass__button"
                             :data-action "local/chat"
                             :data-input-id "prompt"}
                 "送信" [])
       (node-ops 4 :section {:class "liquid-glass__panel"} nil [40 41 42])
       (node-ops 40 :h2 {} "Latest response" [])
       (node-ops 41 :p {}
                 (if response
                   (shortened (get-in response [:message :content]) 1200)
                   "まだ会話はありません。Ollama を起動して最初の依頼を送ってください。")
                 [])
       (node-ops 42 :p {}
                 (if response
                   (str (:provider response) " / " (:model response))
                   (str "API: " api))
                 [])
       (node-ops 5 :section {:class "liquid-glass__panel"} nil [50 51 52 53])
       (node-ops 50 :h2 {} "Local-first runtime" [])
       (node-ops 51 :p {}
                 (str "Default: " (get-in configuration [:routing :default-provider])
                      " · " (get-in configuration [:routing :default-model])) [])
       (node-ops 52 :p {}
                 (str "Cloud: "
                      (if (get-in configuration [:routing :cloud-enabled?])
                        "explicitly enabled" "blocked")) [])
       (node-ops 53 :button {:class "liquid-glass__button"
                             :data-action "local/clear-session"
                             :data-input-id "prompt"}
                 "会話を消去" [])
       [[:dom/set-root 1]]))}))
