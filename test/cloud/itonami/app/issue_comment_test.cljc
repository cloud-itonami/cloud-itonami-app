(ns cloud.itonami.app.issue-comment-test
  "Comment mode's decisions, without a browser, a session, a provider — or a JVM.

  Listed in BOTH runners: `test-runner` (JVM) and `portable_nbb.cljs`
  (ClojureScript). The namespace under test is `.cljc` and needs neither, and
  as `portable_nbb` puts it, a `.cljc` file that one runtime ever executes is a
  `.clj` file with a longer name. `utf8-length` is why that is not theoretical
  here: it is `String.getBytes` on one runtime and `TextEncoder` on the other,
  and only running both proves the crop size cap means the same thing in a
  browser as it does on the server.

  Each refusal test asserts the REASON, not merely that something was thrown.
  A test that only asserts `thrown?` counts a run that failed for an unrelated
  cause as a discrimination it never made — the shape ADR-2608136000 item 6
  names, and the shape three of these functions exist to avoid."
  ;; Plain `clojure.test`: nbb resolves it to `cljs.test`, so no reader
  ;; conditional is needed here and the sibling portable tests do not use one.
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cloud.itonami.app.issue-comment :as issue-comment]))

(def ^:private ok
  {:comment "ここ、失敗の理由が出ていない"
   :view "bots"
   :bot-id "bot-1"
   :element {:selector "section[data-view-panel=\"bots\"] > div.bot-row"
             :tag "DIV" :classes ["bot-row" "is-failed"]
             :data {:view-panel "bots"}
             :text "実行に失敗しました。依頼は記録されています。"}
   :region {:x 412 :y 288 :width 340 :height 96
            :viewport-width 1300 :viewport-height 900
            :device-pixel-ratio 2}})

(defn- refusal-type
  "The `:type` the refusal carried, or nil when it did not refuse.

  `:default` rather than `ExceptionInfo`: the class is JVM-only and this file
  has to run on both runtimes. `ex-data` answers nil for anything that is not
  an `ex-info`, so a different throwable still fails the assertion rather than
  passing as the wrong reason."
  [f]
  (try (f) nil (catch #?(:clj Exception :cljs :default) error
                 (:type (ex-data error)))))

(deftest region-refuses-what-cannot-be-a-rectangle
  (testing "a described rectangle survives intact"
    (is (= {:x 412 :y 288 :width 340 :height 96
            :viewport-width 1300 :viewport-height 900 :device-pixel-ratio 2.0}
           (issue-comment/region (:region ok)))))
  (testing "a drag that never moved is not a region"
    (is (nil? (issue-comment/region {:x 1 :y 1 :width 0 :height 40})))
    (is (nil? (issue-comment/region {:x 1 :y 1 :width 40 :height 0}))))
  (testing "non-finite and negative coordinates are refused, not clamped"
    (is (nil? (issue-comment/region {:x ##NaN :y 1 :width 40 :height 40})))
    (is (nil? (issue-comment/region {:x ##Inf :y 1 :width 40 :height 40})))
    (is (nil? (issue-comment/region {:x -5 :y 1 :width 40 :height 40}))))
  (testing "floating-point pixels round rather than throw"
    (is (= {:x 12 :y 8 :width 41 :height 40}
           (issue-comment/region {:x 11.6 :y 8.2 :width 40.5 :height 40.0})))))

(deftest element-needs-a-selector-because-that-is-what-is-searchable
  (is (nil? (issue-comment/element {:tag "div" :text "何か"})))
  (is (nil? (issue-comment/element {:selector "   "})))
  (let [described (issue-comment/element (:element ok))]
    (is (= "div" (:tag described)) "the tag is normalised for searching")
    (is (= ["bot-row" "is-failed"] (:classes described)))
    (is (= {"view-panel" "bots"} (:data described)))))

(deftest long-quotations-are-cut-visibly
  (let [long-text (apply str (repeat 900 "あ"))
        described (issue-comment/element {:selector "div" :text long-text})]
    (is (= (inc issue-comment/max-element-text-chars) (count (:text described))))
    (is (str/ends-with? (:text described) "…")
        "a cut quotation must be distinguishable from one that ended")))

(deftest request-refuses-for-the-reason-it-names
  (testing "a valid comment is accepted"
    (is (= "bot-1" (:bot-id (issue-comment/request ok)))))
  (is (= :issue-comment/empty
         (refusal-type #(issue-comment/request (assoc ok :comment "   ")))))
  (is (= :issue-comment/no-bot
         (refusal-type #(issue-comment/request (assoc ok :bot-id nil)))))
  (is (= :issue-comment/no-target
         (refusal-type #(issue-comment/request (dissoc ok :element :region))))
      "a comment about nothing on the screen is refused rather than widened to the view")
  (testing "either half of the target is enough on its own"
    (is (some? (issue-comment/request (dissoc ok :element))))
    (is (some? (issue-comment/request (dissoc ok :region))))))

(deftest goal-text-carries-what-a-bot-can-search-for
  (let [text (issue-comment/goal-text
              (assoc (issue-comment/request ok)
                     :id "issue-abc"
                     :image {:url "/api/bots/comments/issue-abc/image"}))]
    (is (str/includes? text "issue-abc"))
    (is (str/includes? text (:comment ok)))
    (is (str/includes? text "section[data-view-panel=\"bots\"] > div.bot-row"))
    (is (str/includes? text "実行に失敗しました"))
    (is (str/includes? text "x=412 y=288 w=340 h=96"))
    (is (str/includes? text "src/cloud/itonami/app/web.clj")
        "the Bot is told where in this repository the screen is rendered")
    (is (< (count text) 8000)
        "stays inside bots/max-message-chars with the descriptor beside it")))

(deftest goal-text-does-not-claim-the-model-saw-the-picture
  (testing "with a stored image, the text says it is a human record"
    (let [text (issue-comment/goal-text
                (assoc (issue-comment/request ok) :id "issue-abc"
                       :image {:url "/api/bots/comments/issue-abc/image"}))]
      (is (str/includes? text "あなたはこの画像を見ていません"))
      ;; The disclaimer above CONTAINS the substring 「画像を見て」, so a naive
      ;; negative on that fragment fails against correct text. Pin the two
      ;; instructions that would actually be wrong -- telling the model to look
      ;; at the picture, or calling it an attachment it received.
      (is (not (str/includes? text "画像を見てください")))
      (is (not (str/includes? text "添付")))))
  (testing "with no image, the absence is stated rather than omitted"
    (let [text (issue-comment/goal-text
                (assoc (issue-comment/request ok) :id "issue-abc" :image nil))]
      (is (str/includes? text "画像: ありません")))))

(deftest svg-payload-separates-absent-from-rejected
  (testing "no crop is a normal outcome, not a failure"
    (is (= {:reason :absent} (issue-comment/svg-payload nil)))
    (is (= {:reason :absent} (issue-comment/svg-payload "   "))))
  (testing "something that is not an SVG document is rejected under its own name"
    (is (= {:reason :not-svg} (issue-comment/svg-payload "<html><body/></html>")))
    (is (= {:reason :not-svg} (issue-comment/svg-payload "data:image/png;base64,AAAA"))))
  (testing "an executable crop is refused, and says that is why"
    (doseq [hostile ["<svg><script>alert(1)</script></svg>"
                     "<svg><SCRIPT >x</SCRIPT></svg>"
                     "<svg><a href=\"javascript:alert(1)\">x</a></svg>"
                     "<svg><rect onload=\"alert(1)\"/></svg>"
                     "<svg><iframe src=\"x\"/></svg>"]]
      (is (= :script-shaped (:reason (issue-comment/svg-payload hostile)))
          (str "admitted: " hostile))))
  (testing "an ordinary crop comes back with its size in BYTES, not characters"
    (let [{:keys [svg bytes]} (issue-comment/svg-payload "<svg>あ</svg>")]
      (is (= "<svg>あ</svg>" svg))
      ;; "<svg>" 5 + "あ" 3 + "</svg>" 6 = 14 bytes, against 12 characters.
      (is (= 14 bytes) "あ is three UTF-8 bytes; a character count would say 12")))
  (testing "oversize is refused"
    (let [huge (str "<svg>" (apply str (repeat (* 5 1024 1024) "a")) "</svg>")
          result (issue-comment/svg-payload huge)]
      (is (= :too-large (:reason result)))
      (is (nil? (:svg result))))))

(deftest utf8-length-counts-bytes
  (is (= 0 (issue-comment/utf8-length "")))
  (is (= 3 (issue-comment/utf8-length "abc")))
  (is (= 9 (issue-comment/utf8-length "あいう"))))
