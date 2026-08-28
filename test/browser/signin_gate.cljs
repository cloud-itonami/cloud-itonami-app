;; Drive the real sign-in screen in a real browser.
;;
;;     NODE_PATH=<superproject>/node_modules ITONAMI_BASE=http://localhost:1347 \
;;       nbb test/browser/signin_gate.cljs
;;
;; Point it at a server with an EMPTY store. It then produces the state it is
;; about to assert on, the same way a person does: POST /api/identity/register
;; creates the account, and the Passkey that should follow never arrives,
;; because a headless browser has no authenticator to create one with. That is
;; the interrupted owner ceremony, and no fixture file is needed to reach it.
;;
;; It refuses to run against a store that holds a finished registration, so it
;; cannot add a user to somebody's real app.
;;
;; nbb rather than .mjs: CLAUDE.md forbids new Node harnesses in raw JS.
;;
;; ## Why this exists, measured
;;
;; On 2026-08-12 a real store was in this state and the startup screen offered
;; exactly one control, labelled "Passkey 登録を再開", beside three disabled SSO
;; buttons and a hidden Email form, under a notice that said the entrances were
;; "Passkey、Email、SSO". Every one of those sentences was rendered by code the
;; JVM suite covers, and none of them was wrong in the way a unit test asks
;; about: the screen simply described a different deployment than the one the
;; user was looking at. The assertions below are about what a person can read
;; and click, because that is where the defect lived.

(require '["playwright$default" :as pw]
         '[clojure.string :as str]
         '[promesa.core :as p])

;; Must be the server's `:server :public-origin` verbatim — that is what
;; `require-origin!` compares the POST's Origin header against, and it defaults
;; to `http://localhost:<port>` even though the socket binds 127.0.0.1. The two
;; spellings are not interchangeable here; measured, the mismatch is a 403.
(def base (or js/process.env.ITONAMI_BASE "http://localhost:1347"))

(def failures (atom []))
(def console-errors (atom []))

(defn check! [label ok?]
  (println (if ok? "  ok  " "  FAIL") label)
  (when-not ok? (swap! failures conj label)))

(defn identity-state []
  (p/let [response (js/fetch (str base "/api/identity"))]
    (.json response)))

(defn -main []
  (p/let [before (identity-state)
          registered? (aget before "registered?")
          resumable? (aget before "passkey-required?")]
    ;; A store with a real user is somebody's app, not a fixture.
    (when (and registered? (not resumable?))
      (println "refusing to run: this store already holds a finished registration")
      (js/process.exit 2))

    (p/let [_ (when-not registered?
                (p/let [response (js/fetch (str base "/api/identity/register")
                                           #js {:method "POST"
                                                :headers #js {"Content-Type" "application/json"
                                                              "Origin" base}
                                                :body "{}"})]
                  (when-not (.-ok response)
                    (println "could not create the pending account:" (.-status response)
                             "— ITONAMI_BASE must be the server's own origin")
                    (js/process.exit 2))
                  (println "created the pending account")))
            data (identity-state)
            email? (aget data "email-login-configured?")
            browser (.launch (.-chromium pw)
                             #js {:headless true
                                  :executablePath
                                  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"})
            context (.newContext browser)
            page (.newPage context)
            _ (.on page "console"
                   (fn [msg] (when (= "error" (.type msg))
                               (swap! console-errors conj (.text msg)))))
            _ (.on page "pageerror"
                   (fn [err] (swap! console-errors conj (str "pageerror: " err))))
            _ (.goto page base #js {:waitUntil "networkidle"})
            _ (.waitForTimeout page 1500)]

      (println "\n── the startup screen is the sign-in screen ──")
      (p/let [panel-hidden (.getAttribute (.locator page "[data-view-panel='signin']") "hidden")
              ;; Read presence before content. A `textContent` on a locator that
              ;; matches nothing waits 30s and then throws, which ends the run
              ;; with a TimeoutError naming a selector instead of a failure
              ;; naming the defect — measured against the pre-fix build.
              headline-count (.count (.locator page "#signin-gate-headline"))
              note-count (.count (.locator page "#signin-gate-note"))
              headline (if (pos? headline-count)
                         (.textContent (.locator page "#signin-gate-headline")) "")
              note (if (pos? note-count)
                     (.textContent (.locator page "#signin-gate-note")) "")
              passkey-visible (.isVisible page "#passkey-signin")
              passkey-disabled (.isDisabled page "#passkey-signin")
              email-card (.isVisible page "#email-login-form")
              ;; Playwright reads a string argument as an EXPRESSION, so an
              ;; arrow function passed here would evaluate to a function object
              ;; and serialise as nothing. Measured: it silently returns null.
              dead (.evaluate page "[...document.querySelector(\"[data-view-panel='signin']\")
                                        .querySelectorAll('button')]
                                     .filter(b => b.offsetParent !== null && b.disabled)
                                     .map(b => b.textContent.trim())")]

        (check! "the signin panel is showing" (nil? panel-hidden))

        (println "\n── the interrupted ceremony explains itself on load ──")
        (println "     headline:" (pr-str headline))
        (println "     note    :" (pr-str note))
        (check! "the gate copy is addressable, so it can be written at runtime"
                (and (pos? headline-count) (pos? note-count)))
        (check! "the headline names the interrupted Passkey, not a menu"
                (str/includes? headline "完了していません"))
        (check! "the note says the account exists and only the Passkey is missing"
                (str/includes? note "Passkey だけがありません"))

        (println "\n── the copy names only entrances this deployment has ──")
        (check! "Email is named only when it is configured"
                (= (boolean email?) (str/includes? note "Email")))
        (check! "provider SSO is not named as an entrance"
                (not (str/includes? note "SSO")))
        (check! "provider SSO has no card"
                (zero? (.count (.locator page "#sso-signin-card"))))
        (check! "the Email card is shown only when Email is configured"
                (= (boolean email?) email-card))

        (println "\n── nothing is offered that cannot work ──")
        (check! "no visible control in the signin view is disabled without a reason on screen"
                (or (empty? (js->clj dead))
                    (str/includes? note "対応していません")))
        (when (seq (js->clj dead))
          (println "     disabled and visible:" (js->clj dead)))
        ;; Which control the screen offers depends on the state: an account
        ;; that exists is resumed, one that does not is created.
        (check! "the state's own Passkey control is on screen"
                passkey-visible)
        (check! "a disabled Passkey control is explained in the note"
                (or (not passkey-disabled) (str/includes? note "対応していません")))

        (println "\n── console ──")
        (doseq [e @console-errors] (println "  !" e))
        (check! "no console errors" (empty? @console-errors))

        (p/let [_ (.close browser)]
          (if (seq @failures)
            (do (println "\n" (count @failures) "failed:")
                (doseq [f @failures] (println "  -" f))
                (js/process.exit 1))
            (println "\nall checks passed")))))))

(-main)
