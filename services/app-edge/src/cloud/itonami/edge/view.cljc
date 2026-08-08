(ns cloud.itonami.edge.view
  "The fleet directory as a page, in DADS.

  Pure hiccup over actors handed in — no fetch, no cache, no catalog loading.
  Same discipline as `cloud.itonami.app.fleet-core`, and for the same reason:
  a view that can reach for data is a view that cannot be rendered in a test.

  There is deliberately **no app CSS**. Everything is a DADS component or a
  `dds-ext-*` layout primitive, so nothing here has to name a spacing, a font
  size or a radius. That matters more than it sounds on this base: the
  `--hig-*` bridge carries colour, palette, font-family and hairline — 27
  tokens — and carries **no** `--hig-spacing-*`, `--hig-text-*-size` or
  `--hig-radius-*`. An app that reaches for one of those gets `padding: ;`,
  which is not an error anywhere: the build passes, the page ships, and the
  layout is quietly wrong (ADR-2608060000). Not writing spacing at all is the
  only version of this that cannot rot."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]))

(defn- count-chips
  "The three numbers that describe the fleet, as chips.

  `callable` is coloured differently from the totals because it is the one
  that answers a different question — not how many actors exist, but how many
  can be reached."
  [{:keys [total callable resident]}]
  (dds/row
   (dds/chip-label (str "actors " total) {:color "gray"})
   (dds/chip-label (str "callable " callable) {:color "blue"})
   (dds/chip-label (str "resident " resident) {:color "gray"})))

(defn- criteria-summary
  "What the current query actually selected, in words.

  Shown even when nothing is filtered, so the page never leaves a reader
  guessing whether they are looking at the whole fleet or a slice of it."
  [criteria matched total]
  (if (empty? criteria)
    (str "全 " total " 件を表示しています。")
    (str matched " 件が一致しました（全 " total " 件中）。条件: "
         (str/join "、"
                   (for [[k v] (sort-by (comp name key) criteria)]
                     (str (name k) " = " (if (keyword? v) (name v) (str v))))))))

(defn- actor-rows
  "Rows for the directory table.

  An absent endpoint renders as 未デプロイ rather than an empty cell. The
  catalog's whole point is that a missing address is a fact — 'not deployed,
  or deployed with no route' — and a blank cell reads as missing data instead."
  [actors]
  (for [a actors]
    [(:repo a)
     (if-some [d (:domain a)] (name d) "—")
     (if-some [e (:execution a)] (name e) "—")
     (if-some [ep (:endpoint a)]
       [:a {:href ep :rel "noreferrer"} (str/replace ep #"^https?://" "")]
       "未デプロイ")]))

(defn body
  "The page body, as hiccup. Separate from `render` so a test can assert on
  structure without going through the string renderer."
  [{:keys [actors matched total callable resident criteria limit execution-facets]}]
  (dds/container
   (dds/stack
    (dds/heading 1 "営みフリート ディレクトリ")
    [:p "cloud-itonami の actor 群を、blueprint が宣言している内容で引きます。"
     "アドレスを持たない actor は「未デプロイ」であって、不明ではありません。"]
    (count-chips {:total total :callable callable :resident resident})
    (dds/divider)
    (dds/section
     {:title "実行形態"}
     (dds/table
      {:caption "execution の内訳（多い順）"
       :headers ["execution" "件数"]
       :rows (for [[v n] execution-facets] [(name v) (str n)])}))
    (dds/section
     {:title "actor"}
     [:p (criteria-summary criteria matched total)]
     (dds/table
      {:caption (str "先頭 " (min limit (count actors)) " 件")
       :headers ["repo" "domain" "execution" "endpoint"]
       :rows (actor-rows actors)})
     (when (> matched (count actors))
       [:p (str "残り " (- matched (count actors))
                " 件は表示していません。limit= で増やせます（上限 200）。")]))
    (dds/divider)
    [:p [:small "Cloudflare Workers 上の ClojureScript が描画しています"
         "（ADR-2608081500）。判定は "
         [:code "cloud.itonami.app.fleet-core"]
         " —— JVM 側と同じ 1 つの実装です。"]])))

(defn render
  "Full HTML document.

  `:css \"\"` and a <link> rather than the 70 KB inlined: the stylesheet is a
  static asset the browser caches once, and inlining it would re-send it on
  every query. `page` still emits its own ext-css inline, which is small and
  has to come after the upstream sheet."
  [data]
  (page/->page
   {:title "営みフリート ディレクトリ — cloud-itonami"
    :description "cloud-itonami の actor ディレクトリ。blueprint が宣言する内容で引く。"
    :css ""
    :head [[:link {:rel "stylesheet" :href "/dds.css"}]]}
   (body data)))
