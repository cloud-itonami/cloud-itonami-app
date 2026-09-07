;; The start screen's contract: it is square, it is honest about what it cut,
;; and the layer it says it depends on is one it actually reads.
;;
;;   nbb test/itonami_splash_nbb.cljs
;;
;;   0  all checks passed
;;   1  a check failed

(ns itonami-splash-nbb
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is run-tests]]
            [nbb.classpath :as classpath]
            [nbb.core :refer [*file*]]))

(classpath/add-classpath (path/resolve (path/dirname *file*) ".." "bin"))
(require '[itonami-harness :as harness]
         '[itonami-profile :as profile]
         '[itonami-splash :as splash])

;; ---------------------------------------------------------------------------
;; width
;; ---------------------------------------------------------------------------

(deftest width-counts-columns-not-code-units
  ;; The whole frame rests on this. `.length` is 4 for both of these strings
  ;; and one of them is eight columns wide, so a pad written against `.length`
  ;; aligns the ASCII rows and pushes every Japanese row off the right border.
  (is (= 4 (splash/display-width "abcd")))
  (is (= 8 (splash/display-width "営みの記")))
  (is (= 4 (.-length "営みの記")) "the control: code units do NOT answer this")
  ;; Box drawing and block elements are UAX#11 Ambiguous. A Latin-locale
  ;; terminal draws them in one column, and the frame is made of them.
  (is (= 3 (splash/display-width "╭─╮")))
  (is (= 2 (splash/display-width "██"))))

(deftest colour-is-not-width
  (let [painted (splash/paint true splash/accent-code "abc")]
    (is (< 3 (.-length painted)) "the control: the escape bytes are really there")
    (is (= 3 (splash/display-width painted)))
    (is (= "abc" (splash/strip-ansi painted)))))

(deftest truncate-says-that-it-cut
  (is (= "abcd" (splash/truncate "abcd" 4)))
  (is (= "abc…" (splash/truncate "abcdef" 4)))
  ;; A wide glyph cannot be half-printed, so the cut lands at 3 columns, not
  ;; 4. Never exceeding is the contract; hitting the number exactly is not.
  (is (>= 4 (splash/display-width (splash/truncate "営みの記録" 4))))
  (is (str/ends-with? (splash/truncate "営みの記録" 4) "…")))

(deftest a-path-loses-its-head-not-its-tail
  ;; Two checkouts of the same repository differ in the part `truncate` would
  ;; have thrown away, so this drops the head instead.
  (is (= "~/github/root" (splash/shorten-path "/Users/j/github/root" "/Users/j" 40)))
  (let [short (splash/shorten-path "/Users/j/github/com-junkawasaki/orgs/a/b" "/Users/j" 16)]
    (is (>= 16 (splash/display-width short)))
    (is (str/ends-with? short "b"))))

;; ---------------------------------------------------------------------------
;; the frame
;; ---------------------------------------------------------------------------

(deftest every-frame-row-is-the-same-width
  ;; Measured control (2026-09-06): making `wide?` answer false for everything
  ;; does NOT fail this test, because `frame` pads with the same ruler this
  ;; asserts with -- the ruler grades itself. What it does catch is arithmetic
  ;; in `frame` (an off-by-one in the title fill fails it, measured). The ruler
  ;; is pinned separately, against literal column counts, in
  ;; `width-counts-columns-not-code-units`; that is the test the broken `wide?`
  ;; fails. Neither check covers the other.
  (let [rows (splash/frame {:title "t" :inner 40 :color? false}
                           ["ascii"
                            "営みの記録"                       ; wide
                            (splash/paint true splash/dim-code "coloured")
                            ""])
        widths (set (map splash/display-width rows))]
    (is (= 1 (count widths)) (str "rows disagree on width: " (pr-str widths)))
    (is (= #{43} widths))))

(deftest the-title-does-not-push-the-border-out
  (doseq [title ["t" "cloud-itonami-app v0.5.7 · http://127.0.0.1:1338" "営み"]]
    (let [rows (splash/frame {:title title :inner 60 :color? false} ["x"])]
      (is (= 1 (count (set (map splash/display-width rows))))
          (str "title made the frame ragged: " title)))))

;; ---------------------------------------------------------------------------
;; the inventory
;; ---------------------------------------------------------------------------

(deftest the-tail-of-the-list-is-named-not-dropped
  ;; A screen that shows five of twenty-five groups and says nothing about the
  ;; other twenty is telling the operator those twenty do not exist.
  (let [cmds (concat (repeat 4 {:command ["workspace" "drive"]})
                     [{:command ["mail" "send"]}
                      {:command ["esign" "verify"]}
                      {:command ["sites" "list"]}])
        rows (splash/summarise cmds 1)]
    (is (= "workspace" (ffirst rows)))
    (is (nil? (first (last rows))) "the tail row carries no invented group name")
    (is (str/starts-with? (second (last rows)) "+3 more:"))
    (is (str/includes? (second (last rows)) "esign"))))

(deftest a-command-name-is-never-cut-in-half
  ;; The column used to receive six names per line, fixed, and then truncate
  ;; the line to fit -- which cut names mid-word (`/bots…`, `/hist…`). A
  ;; truncated command name is not a name you can type.
  (let [words ["/approvals" "/approve" "/botinfo" "/bots" "/context" "/deny"
               "/exit" "/handoff" "/help" "/history" "/timestamps"]
        lines (splash/wrap-words words 34)]
    (is (every? #(<= (splash/display-width %) 34) lines))
    (is (= words (str/split (str/join " " lines) #" "))
        "a name was cut, dropped or reordered")
    (is (not-any? #(str/includes? % "…") lines))))

(deftest a-word-wider-than-the-column-is-cut-rather-than-overflowing
  ;; The one case where cutting is right: nothing would ever fit, and letting
  ;; it through would push the frame's right border off.
  (let [lines (splash/wrap-words ["/a-very-long-command-name"] 8)]
    (is (= 1 (count lines)))
    (is (>= 8 (splash/display-width (first lines))))
    (is (str/ends-with? (first lines) "…"))))

(deftest wrapping-nothing-produces-nothing
  (is (= [] (splash/wrap-words [] 40))))

(deftest a-group-with-no-tail-words-still-says-how-big-it-is
  (is (= [["contracts" "1 command"]]
         (splash/summarise [{:command ["contracts"]}] 3))))

;; ---------------------------------------------------------------------------
;; render
;; ---------------------------------------------------------------------------

(def ^:private facts
  {:color? false :columns 88 :version "0.5.7" :base-url "http://127.0.0.1:1338"
   :profile "default" :profile-source ".itonami/profile.edn"
   :cwd "/Users/j/github/com-junkawasaki" :home "/Users/j"
   :session "keychain"
   :groups [["workspace" "drive, mail, +2 more"] [nil "+20 more: mail · esign"]]
   :named [["auth" "login, status"]]
   :slash ["/help" "/bots" "/exit"]
   :counts ["209 commands" "/help for commands"]})

(deftest the-screen-fits-the-terminal
  (doseq [columns [68 80 88 110 200]]
    (let [out (splash/render (assoc facts :columns columns))
          over (filter #(> (splash/display-width %) (max 68 (min 110 columns)))
                       (str/split-lines out))]
      (is (empty? over)
          (str columns " columns: " (count over) " line(s) overflow")))))

(deftest the-screen-names-what-it-was-given
  (let [out (splash/render facts)]
    (is (str/includes? out "cloud-itonami-app v0.5.7"))
    (is (str/includes? out "Available Commands"))
    (is (str/includes? out "Named Commands"))
    (is (str/includes? out "REPL Commands"))
    (is (str/includes? out "209 commands"))
    (is (str/includes? out "~/github/com-junkawasaki") "the home directory is folded to ~")
    ;; five rows of wordmark
    (is (= 5 (count (splash/wordmark "ITONAMI" false))))))

(deftest colour-is-off-unless-it-is-asked-for
  (is (not (str/includes? (splash/render facts) (splash/sgr 0))))
  (is (str/includes? (splash/render (assoc facts :color? true)) (splash/sgr 0))))

;; ---------------------------------------------------------------------------
;; the plugin
;; ---------------------------------------------------------------------------

(defn- ctx-with-skin [skin]
  (let [ctx (harness/make-context)]
    (harness/mount! ctx (profile/config-plugin {:app-directory "/a" :data-dir "/d"
                                                :configuration (if skin {:skin skin} {})}))
    (harness/mount! ctx (profile/theme-plugin))
    (harness/mount! ctx (splash/splash-plugin))
    ctx))

(deftest plugin-map-shape
  (is (= #{:name :inject :provides :description :apply}
         (set (keys (splash/splash-plugin))))))

(deftest the-splash-layer-actually-reads-the-theme-it-injects
  ;; `itonami-profile` records what a DECLARED-but-unread :inject cost the last
  ;; time: `:skin` in configuration selected nothing while `/skin` worked, and
  ;; every assertion in reach only checked that mounting had succeeded. So this
  ;; asserts through the rendered screen, in both directions.
  (let [screen (fn [skin]
                 ;; `facts` deliberately carries no :tagline and no :bullet:
                 ;; those are what the layer has to get from the theme it
                 ;; injected, and passing them here would answer the question
                 ;; this test is asking.
                 ((:render (harness/ctx-get (ctx-with-skin skin) :ctx/splash))
                  facts))]
    (is (str/includes? (screen nil) "itonami — cloud-itonami-app の front end"))
    (is (str/includes? (screen "kawaii") "itonami ♡ ようこそ")
        "the skin's banner did not reach the screen")
    (is (not (str/includes? (screen "kawaii")
                            "itonami — cloud-itonami-app の front end"))
        "the control: the default banner is gone when a skin replaced it")
    ;; the accent glyph is the separator the counts line is joined with
    (is (str/includes? (screen "kawaii") "209 commands ♡ /help for commands"))
    ;; and the skin's palette reaches the wordmark, not only its words
    (let [tinted (fn [skin]
                   ((:render (harness/ctx-get (ctx-with-skin skin) :ctx/splash))
                    (assoc facts :color? true)))]
      (is (str/includes? (tinted nil) (splash/sgr "38;5;120")) "green, the default")
      (is (str/includes? (tinted "gold") (splash/sgr "38;5;220")))
      (is (not (str/includes? (tinted "gold") (splash/sgr "38;5;120")))
          "the control: the default ramp is gone when a skin replaced it"))))

(deftest the-mark-is-a-rectangle
  ;; The frame pads by display width, so a mark whose rows disagree about their
  ;; width does not fail loudly — it leans. The old mark's rows DID vary and
  ;; relied on `centre` to hide it, which is why nothing caught it. This pins
  ;; the property, measured with the same function the renderer uses, because
  ;; `count` and `display-width` disagree on exactly these characters.
  (let [widths (map splash/display-width splash/mark)]
    (is (= 1 (count (distinct widths)))
        (str "every row of the mark must be the same display width, got "
             (pr-str (distinct widths))))
    (is (pos? (first widths)))
    (is (= 10 (count splash/mark)) "and it is ten rows tall")))

(deftest the-weave-rule-matches-the-wordmark
  ;; The rule is the cloth the name sits on. If it is not exactly as wide as the
  ;; wordmark it reads as a mistake rather than a selvedge.
  (let [rows (splash/wordmark "ITONAMI" false)
        w (splash/display-width (first rows))
        rule (splash/weave-rule w false nil)]
    (is (= w (splash/display-width rule)))
    (is (re-matches #"[▀▄]+" rule) "twill floats only")
    (is (nil? (splash/weave-rule 0 false nil)) "zero width draws nothing")))

(deftest unmount-removes-the-screen
  (let [ctx (ctx-with-skin nil)]
    (harness/unmount! ctx :itonami.splash)
    (is (nil? (harness/ctx-get ctx :ctx/splash)))))

;; `run-tests` returns nil under nbb, so the old
;;   (let [{:keys [fail error]} (run-tests 'ns)] (process.exit (if (pos? …) 1 0)))
;; destructured nil, added 0 to 0, and **exited 0 whether or not tests failed**.
;; Measured 2026-09-07 by breaking one assertion on purpose: the runner printed
;; "1 failures, 0 errors" and still exited 0. A suite that cannot report failure
;; is the seventh of CLAUDE.md's questions in its purest form — the green means
;; the process ended, not that the tests passed.
;;
;; cljs.test DOES hand the summary to the :end-run-tests report method, so the
;; exit code is taken there instead.
(defmethod t/report [::t/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(run-tests 'itonami-splash-nbb)
