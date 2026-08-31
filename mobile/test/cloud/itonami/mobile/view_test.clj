(ns cloud.itonami.mobile.view-test
  "The mobile screen, rendered on the JVM.

  The view is pure hiccup over a state map, so every phase the app can be in is
  reachable here without a device, a simulator or a network. What this file
  asserts is the small set of things that would be silently wrong on a phone:
  that a failed read does not read as an empty fleet, that no class outside the
  design system reaches the document, and that an address that cannot be opened
  is not rendered as a link."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.mobile.view :as view]))

(def ^:private handlers {:on-query identity :on-search identity :on-retry identity})

(defn- nodes
  "Every hiccup vector in a tree, depth first."
  [form]
  (cond
    (vector? form) (cons form (mapcat nodes form))
    (seq? form) (mapcat nodes form)
    (map? form) nil
    :else nil))

(defn- text
  "All string leaves of a tree, joined."
  [form]
  (str/join " " (filter string? (tree-seq coll? seq form))))

(defn- attrs-of [node]
  (let [a (second node)] (when (map? a) a)))

(def ^:private sample-actors
  [{:repo "cloud-itonami-isic-6419" :name "銀行業"
    :domain "finance" :execution "resident" :status "public-oss"
    :endpoint "https://isic-6419.itonami.cloud"}
   {:repo "action-loop-system-dynamics" :execution "on-demand"}])

(deftest a-failed-read-is-not-an-empty-fleet
  ;; The failure this repository keeps finding, in its smallest form: a read
  ;; that could not happen answering the way a read that happened and found
  ;; nothing answers. On a phone the two are one screen apart and the person
  ;; holding it cannot tell them apart unless the screen says so.
  (let [failed (view/screen {:phase :error :query "銀行"
                             :error {:kind :network :message "目録に届きませんでした（Load failed）。"}}
                            handlers)
        empty' (view/screen {:phase :ready :query "銀行" :actors [] :matched 0 :total 1215}
                            handlers)]
    (is (str/includes? (text failed) "取得できませんでした"))
    (is (str/includes? (text failed) "これは「該当が 0 件」ではありません"))
    (is (str/includes? (text empty') "該当がありません"))
    (is (not (str/includes? (text empty') "取得できませんでした")))
    (is (not= (text failed) (text empty')))))

(deftest every-phase-renders
  (doseq [state [{:phase :loading :query ""}
                 {:phase :ready :query "" :applied-query "" :actors sample-actors
                  :matched 2 :total 2 :shown 2}
                 {:phase :ready :query "x" :applied-query "" :actors sample-actors
                  :matched 1215 :total 1215 :shown 2}
                 {:phase :ready :query "x" :actors [] :matched 0 :total 1215}
                 {:phase :error :query "" :error {:kind :http :message "目録が 500 を返しました。"}}]]
    (let [tree (view/screen state handlers)]
      (is (vector? tree) (str "phase " (:phase state)))
      (is (seq (text tree))))))

(deftest the-summary-describes-the-results-not-what-is-being-typed
  ;; `:query` is the search field; `:applied-query` is what the results came
  ;; from. Keying the sentence on the first one made the page state something
  ;; false: 1,294 unfiltered results on screen, `finance` half-typed, and a
  ;; summary reading `1294 件が一致しました（全 1294 件中）。条件: finance` —
  ;; before any search had been issued. Measured in the browser, 2026-08-31.
  (let [typing (view/screen {:phase :ready :query "finance" :applied-query ""
                             :actors sample-actors :matched 1294 :total 1294 :shown 2}
                            handlers)
        applied (view/screen {:phase :ready :query "finance" :applied-query "finance"
                              :actors sample-actors :matched 34 :total 1294 :shown 2}
                             handlers)]
    (is (str/includes? (text typing) "全 1294 件を表示しています"))
    (is (not (str/includes? (text typing) "条件: finance"))
        "the summary described results the query had not been applied to")
    (is (str/includes? (text applied) "34 件が一致しました（全 1294 件中）。条件: finance"))))

(deftest a-page-that-shows-fewer-than-it-matched-says-so
  (let [tree (view/screen {:phase :ready :query "" :applied-query ""
                           :actors sample-actors
                           :matched 1215 :total 1215 :shown 2}
                          handlers)]
    (is (str/includes? (text tree) "残り 1213 件は表示していません"))))

(deftest an-undeployed-actor-says-undeployed
  (let [tree (view/screen {:phase :ready :query "" :actors sample-actors
                           :matched 2 :total 2 :shown 2}
                          handlers)]
    (is (str/includes? (text tree) "未デプロイ"))
    (is (str/includes? (text tree) "https://isic-6419.itonami.cloud"))))

(deftest an-endpoint-is-not-a-link
  ;; Deliberate, and asserted rather than remembered: this WebView has no
  ;; browser chrome to come back with and the in-app bridge implements no
  ;; browser/open-url, so a link to an actor would be a one-way trip out of the
  ;; app or a tap that does nothing (ADR-2608072000).
  (let [tree (view/screen {:phase :ready :query "" :actors sample-actors
                           :matched 2 :total 2 :shown 2}
                          handlers)
        links (filter #(= :a (first %)) (nodes tree))]
    (is (empty? links) (str "links found: " (pr-str links)))))

(deftest nothing-outside-the-design-system-reaches-the-document
  ;; The rule this enforces is ADR-2608060000's, not a style preference. The
  ;; --hig-* bridge carries no --hig-spacing-*, so an app that reaches for one
  ;; ships `padding: ;` — a build that passes and a layout that is quietly
  ;; wrong. An app that never writes CSS at all cannot rot that way.
  (let [trees (for [state [{:phase :loading :query ""}
                           {:phase :ready :query "" :actors sample-actors
                            :matched 2 :total 2 :shown 2}
                           {:phase :error :query "" :error {:message "x"}}]]
                (view/screen state handlers))
        all (mapcat nodes trees)
        styled (filter #(contains? (attrs-of %) :style) all)
        classes (mapcat #(str/split (str (:class (attrs-of %))) #"\s+") all)
        foreign (remove #(or (str/blank? %)
                             (str/starts-with? % "dads-")
                             (str/starts-with? % "dds-ext-"))
                        classes)]
    (is (empty? styled) (str "inline styles: " (pr-str styled)))
    (is (empty? foreign) (str "classes outside DADS: " (pr-str (distinct foreign))))))
