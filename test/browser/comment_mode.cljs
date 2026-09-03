;; Drive comment mode in a real browser.
;;
;;     NODE_PATH=<superproject>/node_modules ITONAMI_BASE=http://localhost:1338 \
;;       nbb test/browser/comment_mode.cljs
;;
;; Point it at any running server. Unlike `bots_view.cljs` this needs NO
;; session: comment mode's markup is in the page whatever the identity is —
;; `authenticated-only` only sets `hidden` — and the properties below are about
;; the client, not about authority. Authority is covered by
;; `issue_comment_http_test`, which proves the route refuses without CSRF and
;; that `handle-bots!` gates it behind a human session.
;;
;; nbb rather than .mjs: CLAUDE.md forbids new Node harnesses in raw JS.
;;
;; ## Why this exists
;;
;; The JVM suite proves the Goal text and the route. It cannot prove the
;; SELECTOR the client writes into that Goal is real. A selector that names no
;; element still serialises, still posts, still renders in the thread, and
;; still sends a Bot to search the repository for a string that was never on
;; the screen — evidence-shaped, and never measured. `the-selector-selects-what-
;; it-described` is the assertion that catches it, and it is the reason this
;; file is worth a browser.

(require '["playwright$default" :as pw]
         '[clojure.string :as str]
         '[promesa.core :as p])

(def base (or (aget js/process.env "ITONAMI_BASE") "http://localhost:1338"))

(def failures (atom []))
(def console-errors (atom []))

(defn check! [label ok?]
  (println (if ok? "  ok  " "  FAIL") label)
  (when-not ok? (swap! failures conj label)))

(defn -main []
  (p/let [browser (.launch (.-chromium pw)
                           #js {:headless true
                                :executablePath
                                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"})
          context (.newContext browser)
          page (.newPage context)
          _ (.setViewportSize page #js {:width 1280 :height 900})
          posted (atom nil)
          _ (.on page "pageerror"
                 (fn [err] (swap! console-errors conj (str "pageerror: " err))))
          ;; `writeJSON` refuses BEFORE it posts when the page holds no CSRF
          ;; (`interaction.js`: `if (authenticated && !identityState?.csrf)
          ;; await refreshIdentityForWrite()`), which is correct and is why the
          ;; submit path cannot be reached on a signed-out page at all. One
          ;; token is handed back so the payload below can be observed. This
          ;; stubs a READ; whether the server would accept the write is asked in
          ;; `issue_comment_http_test`, against the real gate.
          _ (.route page "**/api/identity"
                    (fn [route _request]
                      (.fulfill route #js {:status 200
                                           :contentType "application/json"
                                           :body (js/JSON.stringify
                                                  #js {:csrf "browser-test-csrf"})})))
          ;; The POST is intercepted rather than served: what this file is
          ;; checking is the body the client builds. Whether the server accepts
          ;; it is a different question, asked in the JVM suite.
          _ (.route page "**/api/bots/comments"
                    (fn [route request]
                      (reset! posted (js->clj (.postDataJSON request)
                                              :keywordize-keys true))
                      (.fulfill route #js {:status 200
                                           :contentType "application/json"
                                           :body (js/JSON.stringify
                                                  #js {:id "issue-test"
                                                       :bot-id "bot-1"
                                                       :image #js {:stored? false}
                                                       :text "画面コメント（合成済み）\n受付ID: issue-test"})})))
          ;; `selectBot` reads the chosen Bot's thread on the way to the
          ;; composer. There is no Bot in this store, so it is answered rather
          ;; than left to 404 — otherwise the dispatch this section is about
          ;; never happens and the check below would fail for the wrong reason.
          _ (.route page "**/api/bots/*/messages"
                    (fn [route _request]
                      (.fulfill route #js {:status 200
                                           :contentType "application/json"
                                           :body "{\"messages\":[],\"turn\":null,\"handoffs\":[]}"})))
          streamed (atom nil)
          _ (.route page "**/api/bots/*/messages/stream"
                    (fn [route request]
                      (reset! streamed (js->clj (.postDataJSON request)
                                                :keywordize-keys true))
                      (.fulfill route #js {:status 200
                                           :contentType "text/event-stream"
                                           :body ""})))
          _ (.goto page base #js {:waitUntil "networkidle"})
          _ (.waitForTimeout page 1200)]

    (println "\n── the layer is in the page, and inert until asked for ──")
    (p/let [layer (.count (.locator page "#comment-layer"))
            popover (.count (.locator page "#comment-popover"))
            mode (.evaluate page "document.body.dataset.commentMode || 'unset'")
            visible (.isVisible page "#comment-layer")]
      (check! "the overlay is rendered" (= 1 layer))
      (check! "the popover is rendered" (= 1 popover))
      (check! "it is not on by default" (not= "on" mode))
      (check! "and it does not cover the page while off" (not visible)))

    (println "\n── turning it on ──")
    ;; The toggle is `authenticated-only`. On a signed-out page it is `hidden`,
    ;; and un-hiding it does not stick: `loadIdentity` polls and re-applies
    ;; `node.hidden = !identityReady` to every `.authenticated-only`, so a
    ;; Playwright click times out waiting for visibility that keeps going away.
    ;; The click is dispatched directly instead. That still runs the real
    ;; handler, which is what this file is about; that the control is hidden
    ;; without a session is the gate, and the gate is asserted server-side.
    (p/let [_ (.evaluate page "document.querySelector('#comment-mode-toggle').click()")
            _ (.waitForTimeout page 200)
            mode (.evaluate page "document.body.dataset.commentMode")
            pressed (.getAttribute (.locator page "#comment-mode-toggle") "aria-pressed")
            visible (.isVisible page "#comment-layer")
            hint (.isVisible page "#comment-hint")]
      (check! "the body carries the mode" (= "on" mode))
      (check! "the toggle reports itself pressed" (= "true" pressed))
      (check! "the overlay is now over the page" visible)
      (check! "and it says how to use it" hint))

    (println "\n── a drag selects a region and opens the popover ──")
    (p/let [_ (.move (.-mouse page) 300 300)
            _ (.down (.-mouse page))
            _ (.move (.-mouse page) 620 420 #js {:steps 8})
            _ (.up (.-mouse page))
            _ (.waitForTimeout page 900)
            open (.isVisible page "#comment-popover")
            target (.textContent page "#comment-target")
            cutout (.isVisible page "#comment-cutout")]
      (check! "the popover opened" open)
      (check! "the selected region is cut out of the scrim" cutout)
      (check! "and the popover names what was selected"
              (and target (seq (str/trim target)))))

    (println "\n── the selector selects what it described ──")
    ;; The property the whole feature rests on.
    (p/let [resolved (.evaluate page
                                "(() => {
                                   const selector = document.querySelector('#comment-target').textContent.trim();
                                   if (!selector || selector.startsWith('範囲')) return 'no-selector';
                                   try {
                                     return document.querySelector(selector) ? 'matches' : 'matches-nothing';
                                   } catch (error) { return `invalid: ${error.message}`; }
                                 })()")]
      (check! (str "the selector resolves to a real element (" resolved ")")
              (= "matches" resolved)))

    (println "\n── right-click picks the element under the pointer ──")
    (p/let [_ (.evaluate page "document.querySelector('#comment-cancel').click()")
            _ (.waitForTimeout page 200)
            _ (.click page "#comment-layer" #js {:button "right"
                                                 :position #js {:x 200 :y 400}})
            _ (.waitForTimeout page 900)
            open (.isVisible page "#comment-popover")
            target (.textContent page "#comment-target")
            resolved (.evaluate page
                                "(() => {
                                   const s = document.querySelector('#comment-target').textContent.trim();
                                   try { return document.querySelector(s) ? 'matches' : 'matches-nothing'; }
                                   catch (e) { return 'invalid'; }
                                 })()")]
      (check! "right-click opened the popover" open)
      (check! "it named an element rather than a bare rectangle"
              (not (str/starts-with? (str/trim (or target "")) "範囲")))
      (check! "and that selector resolves too" (= "matches" resolved)))

    (println "\n── what the client posts ──")
    (p/let [;; No session here, so `botsState.bots` is empty and the select has
            ;; nothing in it. One option is injected so the submit path can be
            ;; exercised; which Bots appear is asserted server-side.
            _ (.evaluate page
                         "(() => {
                            const select = document.querySelector('#comment-bot');
                            select.replaceChildren();
                            const option = document.createElement('option');
                            option.value = 'bot-1'; option.textContent = 'Test Bot';
                            select.append(option); select.disabled = false;
                          })()")
            _ (.fill page "#comment-text" "ここ、失敗の理由が出ていない")
            _ (.evaluate page "document.querySelector('#comment-send').click()")
            _ (.waitForTimeout page 1500)
            body @posted
            ;; When nothing posted, the client already said why in the popover.
            ;; Reporting it turns "the comment posted / FAIL" from a fact into
            ;; a lead.
            status (.textContent page "#comment-status")
            picked (.evaluate page "document.querySelector('#comment-bot').value")]
      (check! (str "the comment posted"
                   (when-not body
                     (str " [status: " (pr-str (str/trim (or status "")))
                          ", bot: " (pr-str picked) "]")))
              (some? body))
      (when body
        (check! "it carries the comment"
                (= "ここ、失敗の理由が出ていない" (:comment body)))
        (check! "it names the destination Bot" (= "bot-1" (:bot-id body)))
        (check! "it carries a region with a positive area"
                (and (pos? (get-in body [:region :width]))
                     (pos? (get-in body [:region :height]))))
        (check! "the region is reported in the viewport's own basis"
                (= 1280 (get-in body [:region :viewport-width])))
        (check! "it carries the element descriptor"
                (seq (str (get-in body [:element :selector]))))
        (check! "and the element's own text, which is what a Bot can search for"
                (string? (get-in body [:element :text])))
        ;; The crop is best-effort, so this reports which outcome happened
        ;; rather than only asserting the happy one. A run where the picture
        ;; silently stopped being produced would otherwise pass here forever,
        ;; which is the failure the reason string exists to prevent — and it
        ;; did happen: under this page's CSP the first raster implementation
        ;; produced `null` on every run and said so only in the popover.
        (let [svg (:svg body)]
          (check! (str "the region crop was produced in this browser"
                       " (" (if svg (str (quot (count (str svg)) 1024) " KB") "absent") ")")
                  (and svg (str/starts-with? (str svg) "<svg")))
          (check! "the crop carries inlined styles, not bare markup"
                  (str/includes? (str svg) "font-family"))
          (check! "and it is cropped to the selection, not to the whole element"
                  (str/includes? (str svg) "viewBox=")))))

    (println "\n── the comment is dispatched through the Bots composer ──")
    ;; The POST records; it does not run the turn. A Goal is minutes long, and
    ;; running it from that request would leave this popover disabled with no
    ;; progress and no cancel for the whole of it. The composer already owns
    ;; that, so what is asserted here is that the handoff actually happened.
    (p/let [_ (.waitForTimeout page 1200)
            body @streamed
            composer (.inputValue page "#bots-input")
            view (.evaluate page "document.body.dataset.currentView")]
      ;; `showView` refuses a gated view without a session
      ;; (`if (!appUnlocked && !publicViews.has(name)) name = 'signin'`), so on
      ;; this signed-out fixture the answer is `signin` and that is the correct
      ;; answer. Asserting `bots` here would have demanded that comment mode
      ;; walk past the gate. What the authenticated view looks like is not a
      ;; question this fixture can ask.
      (check! "comment mode does not walk past the view gate" (= "signin" view))
      (check! (str "the streaming run was opened"
                   (when-not body (str " [composer: " (pr-str composer)
                                       "]")))
              (some? body))
      (when body
        (check! "it carries the composed Goal, not the raw comment"
                (str/includes? (str (:text body)) "受付ID: issue-test"))
        (check! "and it is opened as a Goal rather than a chat reply"
                (true? (:goal body)))))

    (println "\n── the mode closes itself after sending ──")
    (p/let [mode (.evaluate page "document.body.dataset.commentMode")
            open (.isVisible page "#comment-popover")]
      (check! "comment mode turned itself off" (= "off" mode))
      (check! "and the popover is gone" (not open)))

    (p/let [_ (.close browser)]
      (when (seq @console-errors)
        (println "\nconsole/network errors:")
        (doseq [error (distinct @console-errors)] (println "   " error)))
      (println)
      (if (seq @failures)
        (do (println (count @failures) "FAILED:")
            (doseq [failure @failures] (println "  -" failure))
            (js/process.exit 1))
        (do (println "all checks passed") (js/process.exit 0))))))

(-main)
