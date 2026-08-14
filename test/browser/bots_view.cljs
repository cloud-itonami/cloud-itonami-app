;; Drive the real Bots view in a real browser.
;;
;;     NODE_PATH=<superproject>/node_modules nbb test/browser/bots_view.cljs
;;
;; Needs a server on :1338 whose store already holds a human session. It is not
;; in `test-runner`: that suite is pure JVM and this needs a browser and a
;; running app, which is a different thing to have and a different thing to
;; fail at.
;;
;; nbb rather than .mjs: CLAUDE.md forbids new Node harnesses in raw JS.
;;
;; ## Why this exists, measured
;;
;; The JVM suite proves the contract and the host. It cannot prove the CLIENT
;; runs, and on the first run of this file it found two defects that 1343
;; passing tests did not — both of the same shape, a value crossing the JSON
;; boundary and quietly becoming something else:
;;
;;   - `bot/avatar` read `:avatar/color`; the wire sends `{color}`. Every Bot
;;     came back the default blue however it was drawn, in the function whose
;;     docstring says it refuses rather than substitutes.
;;   - `grant-widens?` compared the grant against tools narrowed by CONNECTION
;;     as well as by what the deployment enables, so a brand-new Bot warned
;;     "this Bot names tools this deployment has not enabled" — every time.
;;
;; Neither raises an exception. Both render. That is the class of bug this
;; catches and why the assertions below are about the page rather than about
;; a return value.

(require '["playwright$default" :as pw]
         '[clojure.string :as str]
         '[promesa.core :as p])

(def base "http://localhost:1338")
(def token "botsdemo-token-0123456789")

(def failures (atom []))
(def console-errors (atom []))

(defn check! [label ok?]
  (println (if ok? "  ok  " "  FAIL") label)
  (when-not ok? (swap! failures conj label)))

(defn -main []
  ;; The Chrome that is on this machine, headless, with its own profile —
  ;; rather than downloading Playwright's bundled build. Headless also keeps it
  ;; out of the focus contention this desktop has with its other sessions.
  (p/let [browser (.launch (.-chromium pw)
                           #js {:headless true
                                :executablePath
                                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"})
          context (.newContext browser)
          _ (.addCookies context #js [#js {:name "cloud_itonami_identity"
                                           :value token
                                           :domain "localhost"
                                           :path "/"}])
          page (.newPage context)
          _ (.on page "console"
                 (fn [msg]
                   (when (= "error" (.type msg))
                     (swap! console-errors conj (.text msg)))))
          _ (.on page "pageerror"
                 (fn [err] (swap! console-errors conj (str "pageerror: " err))))
          ;; A bare "403 Forbidden" in the console names nothing. Record the
          ;; method and URL, or the next person has to guess which of a dozen
          ;; polled endpoints it was.
          _ (.on page "response"
                 (fn [res]
                   (when (>= (.status res) 400)
                     (swap! console-errors conj
                            (str "HTTP " (.status res) " "
                                 (.method (.request res)) " " (.url res))))))
          _ (.goto page (str base "/#/bots") #js {:waitUntil "networkidle"})
          _ (.waitForTimeout page 1500)]

    (println "\n── the view is reachable and rendered ──")
    (p/let [nav-visible (.isVisible page ".local-nav__item[data-view='bots']")
            _ (.click page ".local-nav__item[data-view='bots']")
            _ (.waitForTimeout page 1200)
            panel-hidden (.getAttribute (.locator page "[data-view-panel='bots']") "hidden")
            current (.textContent page "#current-view")]
      (check! "Bots nav item is present" nav-visible)
      (check! "the Bots panel is the visible one" (nil? panel-hidden))
      (check! "the header names the view" (= "Bots" (str/trim (or current "")))))

    (println "\n── onboarding is derived from the registry ──")
    (p/let [tiles (.count (.locator page ".bots-tile"))
            names (.allTextContents (.locator page ".bots-tile__name"))
            usable (.count (.locator page ".bots-tile:not([disabled])"))]
      (check! (str "the service grid rendered " tiles " connectors") (= 8 tiles))
      (check! "it names the real connectors, not placeholders"
              (and (some #(= "Gmail" %) names) (some #(= "GitHub" %) names)))
      (check! "connectors with no enabled tool are shown disabled, not dropped"
              (and (pos? usable) (< usable tiles))))

    (println "\n── the avatar picker ──")
    (p/let [_ (.fill page "#bots-service-search" "gmail")
            _ (.waitForTimeout page 300)
            filtered (.count (.locator page ".bots-tile"))
            _ (.click page ".bots-tile")
            _ (.waitForTimeout page 300)
            next-disabled (.isDisabled page "#bots-services-next")
            _ (.click page "#bots-services-next")
            _ (.waitForTimeout page 800)
            colors (.count (.locator page "#bots-color-row .bots-swatch"))
            glyphs (.count (.locator page "#bots-glyph-row .bots-swatch"))
            suggestions (.count (.locator page ".bots-suggestion"))]
      (check! "search narrows the grid" (= 1 filtered))
      (check! "Next is enabled once something is picked" (not next-disabled))
      (check! "ten colours" (= 10 colors))
      (check! "eight glyphs" (= 8 glyphs))
      (check! "a suggestion is offered for the picked connector" (pos? suggestions))
      (check! "the isolated-browser permission is on the create step"
              (.isVisible page "#bots-browser")))

    (println "\n── making a Bot ──")
    (p/let [_ (.click page (str "#bots-color-row .bots-swatch >> nth=2"))
            _ (.click page (str "#bots-glyph-row .bots-swatch >> nth=7"))
            _ (.waitForTimeout page 200)
            preview-color (.getAttribute (.locator page "#bots-avatar-preview") "data-color")
            preview-glyph (.getAttribute (.locator page "#bots-avatar-preview") "data-glyph")
            _ (.fill page "#bots-name" "workspace worker")
            _ (.fill page "#bots-brief" "毎朝わたしの受信箱を見て、返事が要るものと待てるものを分けて。")
            _ (.click page "#bots-create")
            _ (.waitForTimeout page 2500)
            rail (.count (.locator page ".bots-rail__item"))
            rail-name (.textContent page ".bots-rail__name")
            thread-hidden (.getAttribute (.locator page "#bots-thread") "hidden")
            thread-name (.textContent page "#bots-titlebar-name")
            avatar-color (.getAttribute (.locator page "#bots-titlebar-avatar") "data-color")]
      (check! "the picker changes the preview" (and (= "orange" preview-color)
                                                    (= "drop" preview-glyph)))
      (check! "the Bot appears in the rail" (= 1 rail))
      (check! "under the name it was given" (= "workspace worker" (str/trim (or rail-name ""))))
      (check! "and the thread opened on it" (and (nil? thread-hidden)
                                                 (= "workspace worker" (str/trim (or thread-name "")))))
      (check! "carrying the colour that was picked" (= "orange" avatar-color)))

    (println "\n── the titlebar keeps human controls minimal ──")
    (p/let [titlebar-visible (.isVisible page "#bots-titlebar-context")
            identity-visible (.isVisible page "#bots-titlebar-identity")
            new-visible (.isVisible page "#bots-new")
            routine-controls (.count (.locator page "#bots-routines-panel, #bots-thread-routines, #bots-handoff-send"))]
      (check! "the selected Bot lives in the app titlebar" (and titlebar-visible identity-visible))
      (check! "new Bot is a titlebar action" new-visible)
      (check! "routine and handoff controls are not exposed to the person" (zero? routine-controls)))

    ;; This message asks for the inbox, so the Bot has to reach for a Gmail
    ;; tool, and reaching for one nobody authorized is what produces the card.
    ;; The card is no longer a precondition of the turn — a Bot with an
    ;; unauthorized connector answers ordinary messages — so what is checked
    ;; here depends on the model actually calling the tool. If this ever fails
    ;; with prose instead of a card, read the transcript before touching the
    ;; assertion: a Bot that answers a question about mail without trying to
    ;; read any mail is the defect, not the check.
    (println "\n── a turn that needs Gmail, and the card it comes back with ──")
    (p/let [_ (.fill page "#bots-input" "受信箱を見て、返事が要るものを教えて")
            _ (.waitForTimeout page 200)
            send-disabled (.isDisabled page "#bots-send")
            _ (.click page "#bots-send")
            _ (.waitForTimeout page 3000)
            messages (.count (.locator page ".bots-msg"))
            person (.count (.locator page ".bots-msg[data-role='person']"))
            card-count (.count (.locator page ".bots-card"))
            card-title (.textContent page ".bots-card__title")
            scopes (.count (.locator page ".bots-card__scopes li"))
            button-label (.textContent page ".bots-card .tool-button")
            status (.textContent page "#bots-titlebar-status")]
      (check! "Send enables once there is text" (not send-disabled))
      (check! "both turns are in the thread" (and (= 2 messages) (= 1 person)))
      (check! "the Bot came back with a card, not just prose" (= 1 card-count))
      (check! "the card names the service" (str/includes? (or card-title "") "Gmail"))
      (check! "and the scopes it would be granted, so the ask is consent"
              (pos? scopes))
      (check! "with an authorize button" (str/includes? (or button-label "") "認証"))
      (check! "and the Bot reports itself as waiting for a connection"
              (str/includes? (or status "") "接続待ち")))

    (println "\n── the reach panel ──")
    (p/let [_ (.click page "#bots-thread-tools")
            _ (.waitForTimeout page 400)
            panel (.textContent page "#bots-thread-panel")]
      (check! "it states the reach in tools" (str/includes? (or panel "") "届く範囲"))
      (check! "and says writes are not permitted for this Bot"
              (str/includes? (or panel "") "読み取りのみ")))

    (println "\n── the state a single-page app has to keep ──")
    (p/let [_ (.evaluate page "window.__botsProbe = 'kept'")
            _ (.click page ".local-nav__item[data-view='chat']")
            _ (.waitForTimeout page 600)
            _ (.click page ".local-nav__item[data-view='bots']")
            _ (.waitForTimeout page 1200)
            probe (.evaluate page "window.__botsProbe")
            thread-name (.textContent page "#bots-titlebar-name")
            messages (.count (.locator page ".bots-msg"))]
      (check! "crossing views did not reload the document" (= "kept" probe))
      (check! "the Bot is still selected after crossing"
              (= "workspace worker" (str/trim (or thread-name ""))))
      (check! "and its conversation is still there" (= 2 messages)))

    ;; Under target/ rather than /tmp: it is a build output of this repository
    ;; and belongs where the other ones are, and where .gitignore already covers
    ;; it.
    (p/let [path (or js/process.env.BOTS_VIEW_SCREENSHOT "target/bots-view.png")
            _ (.screenshot page #js {:path path :fullPage false})]
      (println (str "\nscreenshot: " path)))

    (println "\n── console ──")
    ;; No exclusions. There was one — `GET /api/identity/domain-verifications`
    ;; 403'd on every load because it asked for an `Origin` header a browser
    ;; does not send on a same-origin GET — and this harness is how that was
    ;; found. It is fixed, so the exclusion is gone rather than kept as a
    ;; standing allowance: a list of errors we have agreed to ignore is how a
    ;; console check stops checking.
    (check! (str "no console errors" (when (seq @console-errors)
                                       (str ": " (pr-str (take 5 @console-errors)))))
            (empty? @console-errors))

    (p/do
      (.close browser)
      (println (str "\n" (if (empty? @failures) "ALL CHECKS PASSED"
                             (str (count @failures) " FAILED: "
                                  (str/join " | " @failures)))))
      (when (seq @failures) (js/process.exit 1)))))

(-main)
