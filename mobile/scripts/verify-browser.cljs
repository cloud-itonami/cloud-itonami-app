(ns verify-browser
  "Drive the built mobile bundle in a real headless Chromium at phone size.

  The JVM suite renders every phase of the view, and the compiler proves the
  bundle builds. Neither can say whether the app, mounted in a browser, reaches
  the edge and puts real actors on the screen — and that is the part that broke
  on this workspace's last mobile attempt: `local-manimani` rendered its real UI
  on an iOS Simulator and showed `TypeError: Load failed`, because the bundle
  was on the device and the server it fetched from was not (ADR-2608072000).

  Three things are checked, in one browser, against the real bundle:

    1. it mounts and shows actors the API actually returned
    2. searching narrows the list
    3. an unreachable API produces the FAILURE screen and not the empty one

  (3) is the one worth the harness. It is asserted here rather than only in the
  JVM test because the JVM test can only prove the view CAN say it; only a
  browser with a blocked route proves the app DOES, on the path a phone with no
  signal takes.

  Waiting is done with `waitForSelector` and a text selector, never with
  `waitForFunction` and a string. Measured here on 2026-08-31: a string handed
  to `waitForFunction` is evaluated as an EXPRESSION, so a source string
  holding an arrow function evaluates to a function object, which is truthy,
  and the wait returns on its first poll having waited for nothing. Both offline assertions then read a screen that was still loading
  and reported the app broken while a screenshot taken moments later showed it
  working — a wait that could not wait, answering the way a wait that succeeded
  answers.

  Run (with the bundle built and dist/ served):
    MOBILE_URL=http://127.0.0.1:8099/index.html npx nbb scripts/verify-browser.cljs"
  (:require ["node:fs" :as fs]
            ["node:process" :as process]
            ["playwright-core$default" :as pw]
            [clojure.string :as str]
            [promesa.core :as p]))

(def url (or (not-empty (.. process -env -MOBILE_URL))
             "http://127.0.0.1:8099/index.html"))

(def executable
  "Chrome for Testing, by path.

  Not playwright's own download: on this workstation `playwright install`
  extracts ABOUT and LICENSE and no binary, exits 0, and a check that cannot
  run reports nothing — which reads exactly like a check that passed
  (kami-genko's verify-browser found the same thing on 2026-08-06). An explicit
  path fails loudly when it is wrong."
  (or (not-empty (.. process -env -MOBILE_CHROME))
      (str (.. process -env -HOME)
           "/.agent-browser/browsers/chrome-152.0.7977.54"
           "/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing")))

(defonce failures (atom 0))

(def ^:private bundle-contents
  "Exactly what the app is supposed to carry.

  `kotoba-shell app scaffold` copies :web/dist-dir wholesale into the .ipa and
  the .apk, so anything that lands in dist/ ships. Measured on 2026-08-31: this
  script's own screenshots did, and nothing anywhere would have said so — the
  scaffold reported `:placeholder? false` and the app worked. They are written
  to target/ now, and this asserts the rest."
  #{"index.html" "dds.css" "js"})

(defn check! [label ok? detail]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "—" detail))))

(defn -main []
  (let [extra (remove bundle-contents (js->clj (fs/readdirSync "dist")))]
    (check! "the bundle carries only the app" (empty? extra) (pr-str extra)))
  (p/let [browser (.launch (.-chromium pw) #js {:headless true
                                                :executablePath executable})
          ;; iPhone 16-ish logical size. The layout is DADS' and not ours, but
          ;; a check that only ever ran at desktop width would not notice a
          ;; screen that needs horizontal scrolling on a phone.
          context (.newContext browser #js {:viewport #js {:width 390 :height 844}
                                            :deviceScaleFactor 3})
          page (.newPage context)
          errors (atom [])
          _ (.on page "console" (fn [^js m]
                                  (when (= "error" (.type m))
                                    (swap! errors conj (.text m)))
))
          _ (.on page "pageerror" (fn [^js e] (swap! errors conj (str e))))

          ;; ── 1. it mounts and shows real actors ───────────────────────────
          _ (.goto page url #js {:waitUntil "networkidle"})
          _ (.waitForSelector page ".dds-ext-card" #js {:timeout 15000})
          cards (.count (.locator page ".dds-ext-card"))
          heading (.textContent (.locator page "h1"))
          summary (.textContent (.locator page "#app p" #js {:hasText "件を表示しています"}))

          _ (check! "the bundle mounts and renders actor cards"
                    (pos? cards) (str "cards=" cards))
          _ (check! "the heading is the app's own"
                    (str/includes? (str heading) "営みフリート") (pr-str heading))
          _ (check! "the summary states a fleet size the API returned"
                    (some? (re-find #"全 \d+ 件" (str summary))) (pr-str summary))
          _ (check! "no console errors on first paint"
                    (empty? @errors) (pr-str @errors))

          ;; ── 2. search narrows ────────────────────────────────────────────
          ;; Typed, not `fill`. `fill` sets the value with one native event,
          ;; and against a reagent-controlled input that raced: two runs on
          ;; 2026-08-31 searched for nothing and for something that matched
          ;; nothing, from the same bundle that had just passed. Typing is also
          ;; what a person does, so what this measures is what they get.
          _ (.pressSequentially (.locator page "#q") "finance" #js {:delay 25})
          typed (.inputValue (.locator page "#q"))
          _ (check! "the query reaches the field" (= "finance" typed) (pr-str typed))
          _ (.click page "button.dads-button")
          ;; Wait for the exact element the assertions are about, not for text
          ;; anywhere on the page. `waitForSelector` returns as soon as SOME
          ;; node matches, and this app re-renders the whole screen on every
          ;; state change — so between that return and the read, the node can
          ;; be gone. Measured 2026-08-31: three runs in five read a screen
          ;; that was loading again and reported the app broken while the
          ;; request log showed it had answered correctly.
          ;; `条件: finance` and not `件が一致しました`: the second appears the
          ;; moment the summary mentions a query, which — before :applied-query
          ;; existed — was while someone was still typing. Waiting for the
          ;; applied query waits for the search to have actually landed.
          hit-line (.locator page "#app p" #js {:hasText "条件: finance"})
          _ (.waitFor hit-line #js {:state "visible" :timeout 15000})
          matched-text (.innerText hit-line)
          narrowed (.count (.locator page ".dds-ext-card"))
          ;; The COUNTS are compared, not the number of cards. One page holds
          ;; 50 either way, so `cards` is the same before and after any search
          ;; that still matches 50 — an assertion on it fails on a fleet that
          ;; has grown rather than on a search that stopped working (measured
          ;; 2026-08-31 against the deployed catalog of 1,294).
          fleet-size (some-> (re-find #"全 (\d+) 件" (str summary)) second js/parseInt)
          hit-count (some-> (re-find #"(\d+) 件が一致しました" (str matched-text)) second js/parseInt)
          _ (check! "searching narrows the fleet"
                    (and (pos? narrowed) hit-count fleet-size (< hit-count fleet-size))
                    (str "matched=" hit-count " of " fleet-size
                         " (cards before=" cards " after=" narrowed ")"))
          _ (check! "the screen says what it matched on"
                    (str/includes? matched-text "条件: finance") "no 条件 line")

          ;; ── 3. an unreachable API is not an empty fleet ──────────────────
          ;; The route is aborted rather than the server stopped: this is the
          ;; failure a phone actually has (the request never completes), and it
          ;; is the one whose screen must not be mistakable for "0 matched".
          _ (.route page "**/api/fleet/search**" (fn [^js route] (.abort route)))
          _ (.click page "button.dads-button")
          _ (.waitForSelector page "text=取得できませんでした" #js {:timeout 15000})
          offline-text (.innerText (.locator page "#app"))
          _ (check! "an unreachable API shows the failure screen"
                    (str/includes? offline-text "取得できませんでした") "banner missing")
          _ (check! "and does not read as an empty result"
                    (not (str/includes? offline-text "該当がありません"))
                    "the offline screen says 該当がありません")
          _ (check! "and says so explicitly"
                    (str/includes? offline-text "これは「該当が 0 件」ではありません")
                    "no disambiguating line")

          _ (.screenshot page #js {:path "target/verify-offline.png" :fullPage true})
          _ (.unroute page "**/api/fleet/search**")
          _ (.click page "button.dads-button")
          _ (.waitForSelector page ".dds-ext-card" #js {:timeout 15000})
          _ (.screenshot page #js {:path "target/verify-ready.png"})
          _ (.close browser)]
    (if (pos? @failures)
      (do (println "\nFAILED:" @failures) (js/process.exit 1))
      (println "\nthe mobile bundle behaved in a real browser"))))

(-main)
