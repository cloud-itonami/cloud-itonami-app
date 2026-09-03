(ns cloud.itonami.app.appearance-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.appearance :as appearance]))

(deftest resolution-is-configured-or-light
  (testing "the shipped default"
    (is (= "light" (appearance/resolve-mode{}))))
  (testing "every spelling an operator is likely to write"
    (doseq [v [:8bit "8bit" "8-bit" "EIGHTBIT" " pixel " :retro]]
      (is (= "8bit" (appearance/resolve-mode{:ui {:appearance v}})) (pr-str v))))
  (testing "a value that names nothing is not an appearance, so light"
    (doseq [v ["dark" :neon 8 nil ""]]
      (is (= "light" (appearance/resolve-mode{:ui {:appearance v}})) (pr-str v)))))

(deftest residency-is-cloud-only-when-the-configuration-says-so
  (is (= :local (appearance/residency-plane {})))
  (is (= :cloud (appearance/residency-plane {:residency {:plane :cloud}})))
  (is (= :cloud (appearance/residency-plane {:residency {:plane "Cloud"}})))
  (testing "junk is local, not a crash in the page render"
    (doseq [v [nil 1 true :orbit ""]]
      (is (= :local (appearance/residency-plane {:residency {:plane v}})) (pr-str v)))))

(deftest the-layer-styles-the-elements-that-exist
  ;; Review finding 2026-09-02: a rule for a class combination that never
  ;; occurs styles nothing, and a generic `button` rule reaches the
  ;; full-viewport backdrop, which is a <button>.
  (is (str/includes? appearance/css ".bots-msg[data-role='person'] .bots-msg__bubble"))
  (is (not (str/includes? appearance/css ".message-row--user")))
  (is (str/includes? appearance/css "button:not(.mobile-nav-backdrop)"))
  (is (str/includes? appearance/css ".tool-button:not([disabled]):hover")))

(deftest the-toggle-cycles-through-every-mode-and-comes-back
  (is (= "8bit" (appearance/next-mode "light")))
  (is (= "light" (appearance/next-mode "8bit")))
  (is (= "8bit" (appearance/next-mode "nonsense"))
      "unknown input restarts the cycle from light")
  (is (= (set appearance/modes)
         (set (take (count appearance/modes) (iterate appearance/next-mode "light"))))))

(deftest the-layer-declares-every-token-it-references
  ;; The same guard core-test applies to app-css, applied to this layer on
  ;; its own so a token typo is caught by the portable suite too.
  (let [defined (set (map second (re-seq #"(--eightbit-[a-z-]+)\s*:" appearance/css)))
        referenced (set (map second (re-seq #"var\((--eightbit-[a-z-]+)\)" appearance/css)))]
    (is (seq referenced))
    (is (empty? (remove defined referenced))
        (pr-str (remove defined referenced)))))

(deftest the-layer-is-scoped-and-changes-no-document-structure
  (testing "every rule is under the attribute, so light is untouched"
    (let [selectors (->> (str/split appearance/css #"\}")
                         (map #(first (str/split % #"\{")))
                         (map str/trim)
                         (remove str/blank?)
                         (remove #(str/starts-with? % "@"))
                         (remove #(re-find #"^\d+%$" %)))]
      (is (seq selectors))
      (is (every? #(or (str/includes? % "data-appearance=\"8bit\"")
                       (str/starts-with? % ".appearance-toggle")
                       ;; keyframe steps inside @keyframes blocks
                       (re-find #"^(0|100)%" %))
                  selectors)
          (pr-str (remove #(or (str/includes? % "data-appearance=\"8bit\"")
                               (str/starts-with? % ".appearance-toggle")
                               (re-find #"^(0|100)%" %))
                          selectors)))))
  (testing "8-bit is shape and colour, never a hidden element"
    (is (not (re-find #"display\s*:\s*none" appearance/css)))
    (is (not (re-find #"visibility\s*:\s*hidden" appearance/css))))
  (testing "no network font"
    (is (not (str/includes? appearance/css "@import")))
    (is (not (str/includes? appearance/css "fonts.googleapis")))))

(deftest palette-is-the-cockpit-floor-palette
  ;; The public itonami.cloud 8-BIT MODE floor draws with these eleven; the
  ;; other five are this workspace's state colours. A Bot must look like the
  ;; same Bot in both places, so the shared ones are pinned by value.
  (is (= "#181425" (:night appearance/palette)))
  (is (= "#fff1d2" (:cream appearance/palette)))
  (is (= "#ffd866" (:sun appearance/palette)))
  (is (= "#8bd450" (:grass appearance/palette)))
  (is (= 16 (count appearance/palette)) "sixteen colours is the whole system"))

(deftest the-toggle-says-what-it-will-do
  (let [[tag attrs label] (appearance/toggle-button "light")]
    (is (= :button tag))
    (is (= "appearance-toggle" (:id attrs)))
    (is (= "8bit" (:data-next attrs)))
    (is (= "false" (:aria-pressed attrs)))
    (is (= "8-BIT" label)))
  (let [[_ attrs label] (appearance/toggle-button "8bit")]
    (is (= "light" (:data-next attrs)))
    (is (= "true" (:aria-pressed attrs)))
    (is (= "DADS" label))))
