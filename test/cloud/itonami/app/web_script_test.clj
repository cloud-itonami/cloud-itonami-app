(ns cloud.itonami.app.web-script-test
  "The page's JavaScript, parsed.

  `web.clj` carries about a quarter of a megabyte of JavaScript inside a
  Clojure string, and nothing has ever checked that it is JavaScript. The
  Clojure reader catches the errors that break the *string* — an unescaped
  quote inside a JS comment ends it early and the file stops compiling, which
  has happened repeatedly and is at least loud. It cannot see a missing
  brace, an unbalanced paren or a stray `\\.` in a regexp: those compile
  fine, ship fine, and turn the whole app blank in the browser, because one
  syntax error takes the entire script with it.

  The interaction layer is a `.js` resource now rather than a string
  literal, which removes the escaping entirely — the reader no longer sees
  it, so a backslash is a backslash. That makes this check cheaper and not
  less necessary: what ships is the rendered page, and a script assembled
  correctly out of a file that does not parse is still a blank app.

  So the page is rendered and every `<script>` in it is handed to a
  JavaScript parser. `node --check` is the parser: it is the same engine the
  browser will use, it is already on any machine that builds this, and
  writing a second one here would be writing a JavaScript parser.

  When node is absent the test says so and passes. A gate that fails on a
  machine without the tool teaches people to ignore it, and a gate that goes
  quiet teaches them it ran — so it neither fails nor stays silent."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.web :as web]))

(def ^:private config
  {:routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true :base-url "http://127.0.0.1:11434" :reviewed? true :enabled? true}]})

(defn- node-version []
  (try (let [{:keys [exit out]} (shell/sh "node" "--version")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

(deftest the-pages-javascript-parses
  (if-let [version (node-version)]
    (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
                 (web/page-html config))
          blocks (map second (re-seq #"(?s)<script(?:\s[^>]*)?>(.*?)</script>" html))]
      (is (seq blocks) "the page has script at all")
      (doseq [[index block] (map-indexed vector blocks)]
        ;; Empty ones are the module tags with a src and no body.
        (when-not (str/blank? block)
          (let [file (io/file (System/getProperty "java.io.tmpdir")
                              (str "itonami-page-script-" index ".js"))]
            (spit file block)
            (let [{:keys [exit err]} (shell/sh "node" "--check" (str file))]
              (.delete file)
              (is (zero? exit)
                  (str "script " index " (" (count block) " characters) does not parse"
                       " under node " version ":\n" err)))))))
    (println (str "web-script-test: node is not on PATH, so the page's "
                  "JavaScript was not parsed. It is not being checked "
                  "anywhere else."))))

(deftest the-interaction-layer-parses-as-the-file-it-is
  ;; The rendered page above is what ships; this is the source it is built
  ;; from, checked directly, so a failure says which file to open.
  (if-let [version (node-version)]
    (let [source (io/file "resources/cloud/itonami/app/interaction.js")]
      (is (.isFile source) "the interaction layer is a resource, not a literal")
      (let [{:keys [exit err]} (shell/sh "node" "--check" (str source))]
        (is (zero? exit) (str source " does not parse under node " version ":\n" err))))
    (println "web-script-test: node is not on PATH, so the interaction layer was not parsed.")))

(deftest bots-bind-an-admitted-provider-and-model
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        load-bots (second (re-find
                           #"(?s)const loadBots = async \(options = \{\}\) => \{(.*?)\n    \};"
                           js))]
    (doseq [id ["bots-provider" "bots-model"]]
      (is (not (str/includes? html (str "id=\"" id "\"")))
          (str id " belongs to the selected Bot settings, not creation")))
    (is (str/includes? js "data['model-providers'] || []"))
    (is (str/includes? js "provider?.models || []"))
    (is (str/includes? js "data['model-provider-readiness'] || []"))
    (is (str/includes? js "const providerSelect = make('select')"))
    (is (not (str/includes? js "'provider-id':$('#bots-provider').value")))
    (is (not (str/includes? js "model:$('#bots-model').value.trim()")))
    (is (str/includes? js "`/api/bots/${bot.id}`"))
    (is (str/includes? js "'provider-id':providerSelect.value"))
    (is (str/includes? js "Model: ${bot['provider-id']} / ${bot.model}"))
    (is load-bots "loadBots remains independently inspectable")
    (is (str/includes? load-bots "await selectBot(botsRecentFirst(botsState.bots)[0].id);")
        "the initial Bot selection opens its per-Bot model setting")))

(deftest startup-workforce-is-visible-and-human-provisioned
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (str/includes? html "id=\"bots-workforce\""))
    (is (str/includes? js "'/api/bots/workforce/provision'"))
    (is (str/includes? js "bot.business?.name, bot.role?.name"))
    (is (str/includes? js "Capability policy は職務上の境界です。"))
    (is (str/includes? js "既存の会話と実行履歴は保持されています。"))))

(deftest bots-quality-score-is-visible-and-fail-closed
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (doseq [id ["bots-quality" "bots-quality-panel" "bots-quality-close"
                "bots-quality-status" "bots-quality-scores"
                "bots-quality-note" "bots-quality-gates"]]
      (is (str/includes? html (str "id=\"" id "\"")) id))
    (is (str/includes? js "botsState.slo = data.slo || null"))
    (is (str/includes? js "const renderBotsSlo = () =>"))
    (is (str/includes? js "const setBotsQualityOpen = (open) =>"))
    (is (str/includes? js "未計測は合格として扱いません。"))
    (is (str/includes? js "固定20タスクの出力品質"))))

(deftest bots-expose-bounded-local-coding-and-cancellable-goal-progress
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (doseq [id ["bots-workspace" "bots-cancel"
                "bots-goal" "bots-run"]]
      (is (str/includes? html (str "id=\"" id "\"")) id))
    (is (not (str/includes? html "id=\"bots-coding\"")))
    (is (str/includes? html "ファイル変更・local commitを自律実行"))
    (is (str/includes? js "messages/stream"))
    (is (str/includes? js "messages/${encodeURIComponent(active.runId)}/followups"))
    (is (str/includes? js "messages/${encodeURIComponent(runId)}/cancel"))
    (is (str/includes? js "frame.type === 'followup-applied'"))
    (is (str/includes? js "追加で伝える"))
    (is (str/includes? js "activeRuns:new Map()"))
    (is (str/includes? js "通常より時間がかかっています…"))
    (is (str/includes? js "'goal?':goal"))
    (is (str/includes? js "provider の請求額が未提供のため未算出"))
    (is (str/includes? js "HTTP ${turn['error-status']}"))
    (is (str/includes? js "renderBotsRun(botsState.latestTurn)"))
    (is (str/includes? js "frame.type === 'phase'"))
    (is (str/includes? js "progress.phase = frame.phase"))
    (is (str/includes? js "前回の実行はアプリの再起動で中断されました。"))
    (is (str/includes? js "personEntry.dataset.role = 'person'"))
    (is (str/includes? js "append(personEntry, entry)"))
    (is (str/includes? js "'coding?':true"))
    (is (str/includes? js "workspace:$('#bots-workspace').value.trim()"))))

(deftest bots-render-markdown-safely-and-start-from-a-local-workspace
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (str/includes? js "const renderMarkdown = (node, source) =>"))
    (is (str/includes? js "document.createTextNode")
        "model Markdown is built as DOM text rather than assigned to innerHTML")
    (is (str/includes? js "renderMarkdown(bubble, message.text)"))
    (is (str/includes? js "renderMarkdown(run.provisional, run.provisional.dataset.markdown)"))
    (is (not (str/includes? js "provisional.innerHTML")))
    (is (str/includes? html "Local workspace で働く Bot"))
    (is (str/includes? html "外部サービスを追加（任意）"))
    (is (not (str/includes? html "id=\"bots-coding\"")))
    (is (str/includes? html "自律モードで開始します"))
    (is (str/includes? js "botsState.defaultWorkspace = data['default-workspace'] || ''"))
    (is (str/includes? js "$('#bots-services-next').disabled = false"))))

(deftest detached-goal-polling-refreshes-the-durable-turn-not-only-messages
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        refresh (second (re-find
                         #"(?s)const refreshBotsThread = async \(\) => \{(.*?)\n    \};"
                         js))]
    (is refresh "the detached-goal poll target remains independently inspectable")
    (is (str/includes? refresh "botsState.messages = data.messages || []"))
    (is (str/includes? refresh "botsState.latestTurn = data.turn")
        "a Goal that finished in the resident must replace the browser's provisional running turn")))

(deftest bots-reconcile-cli-and-mcp-messages-while-the-view-is-open
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        sync (second (re-find
                      #"(?s)const syncBotsFromResident = async \(\) => \{(.*?)\n    \};"
                      js))]
    (is sync "the resident synchronizer remains independently inspectable")
    (is (str/includes? js "threadVersion:null, syncTimer:null, syncing:false"))
    (is (str/includes? sync "fetch(`/api/bots/${botId}/messages`, {cache:'no-store'})"))
    (is (str/includes? sync "version !== botsState.threadVersion")
        "unchanged conversations do not rebuild the thread")
    (is (str/includes? sync "document.hidden || botsState.activeRuns.has(botsState.selected) ||")
        "background tabs and only the selected Bot's active stream are not raced")
    (is (str/includes? sync "stickToBottom")
        "an external message does not pull a person away from older history")
    (is (str/includes? sync "botsState.latestTurn = data.turn || null")
        "CLI/MCP lifecycle state replaces the browser's stale state")
    (is (str/includes? js "document.addEventListener('visibilitychange'")
        "returning to the app requests an immediate reconciliation")
    (is (str/includes? js "scheduleBotsRealtime(0);")
        "opening Bots does not wait one interval for the first reconciliation")))

(deftest bots-pass-server-status-to-decorative-living-faces
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (str/includes? js "const botAvatar = (node, avatar, status = null) =>"))
    (is (str/includes? js "node.dataset.status = status"))
    (is (str/includes? js "node.setAttribute('aria-hidden', 'true')"))
    (is (str/includes? js
                       "botAvatar(make('span', 'bot-avatar'), bot.avatar, bot.status)"))
    (is (str/includes? js
                       "botAvatar($('#bots-titlebar-avatar'), bot.avatar, bot.status)"))))

(deftest bots-picker-is-a-recent-searchable-conversation-list
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (str/includes? js "$('#bots-filter').addEventListener('input', renderBotsRail)"))
    (is (str/includes? js "botsActivityTime(b) - botsActivityTime(a)"))
    (is (str/includes? js "bot['last-message']?.text"))
    (is (str/includes? js "bots-rail__time"))
    (is (str/includes? js "$('#bots-input').placeholder = `${bot.name} に頼む`"))
    (is (str/includes? js "botAvatar($('#bots-mobile-avatar'), bot.avatar, bot.status)"))))

(deftest bots-expose-one-approved-virtual-shell-per-bot
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (not (str/includes? html "id=\"bots-virtual-shell\"")))
    (is (str/includes? js "virtualShellBox.setAttribute('aria-label', '隔離された仮想環境で汎用shellを使う')"))
    (is (str/includes? js "'virtual-shell?':virtualShellBox.checked"))
    (is (str/includes? js "bot['virtual-shell?']"))
    (is (str/includes? js "`/api/bots/${botId}/shell/cancel`"))))

(deftest authenticated-writes-recover-one-stale-csrf-without-relaxing-the-gate
  ;; A resident app is a long-lived single-page document. Hosted sign-in or a
  ;; renewed session can replace its cookie without replacing the JavaScript
  ;; closure that still holds the previous session's CSRF token. The client may
  ;; fetch the token for the cookie it now has and retry exactly that failure;
  ;; the server must still perform its ordinary Origin and CSRF checks.
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        refresh (second (re-find
                         #"(?s)const refreshIdentityForWrite = async \(\) => \{(.*?)\n    \};"
                         js))
        write (second (re-find
                       #"(?s)const writeJSON = async \(path, method, body=\{\}, authenticated=false\) => \{(.*?)\n    \};"
                       js))]
    (is (some? refresh))
    (is (some? write))
    (is (str/includes? refresh "fetch('/api/identity', {cache:'no-store'})"))
    (is (str/includes? refresh "identityState = data;"))
    (is (str/includes? write "data?.error?.type === 'invalid-csrf'"))
    (is (= 2 (count (re-seq #"refreshIdentityForWrite\(\)" write)))
        "a missing token is filled before send and a stale token is refreshed in the retry branch")
    (is (= 2 (count (re-seq #"await send\(\)" write)))
        "there is one initial request and at most one retry")
    (is (not (str/includes? write "invalid-origin"))
        "an Origin refusal is never converted into a retry")))

(deftest organization-studio-is-a-dedicated-single-editor-surface
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))]
    (is (= 1 (count (re-seq #"data-view-panel=\"organization\"" html))))
    (is (= 1 (count (re-seq #"id=\"governance-organization-form\"" html))))
    (doseq [id ["organization-studio-tree" "organization-studio-actors"
                "organization-studio-assignments" "organization-studio-policies"
                "governance-units" "governance-positions" "governance-roles"]]
      (is (str/includes? html (str "id=\"" id "\"")) id))))

(deftest authentication-is-a-dedicated-view-and-keeps-the-link-proof
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        signin-start (.indexOf html "data-view-panel=\"signin\"")
        settings-start (.indexOf html "data-view-panel=\"settings\"")
        signin-html (subs html signin-start settings-start)
        settings-html (subs html settings-start)]
    (is (pos? signin-start))
    (is (> settings-start signin-start))
    (doseq [id ["identity-onboarding" "itonami-cloud-signin" "itonami-enrolment-link"
                "registration-form" "registered-auth" "local-recovery"
                "passkey-signin" "email-login-form" "sso-signin-list"
                "enrollment-form"]]
      (is (str/includes? signin-html (str "id=\"" id "\"")) id)
      (is (not (str/includes? settings-html (str "id=\"" id "\""))) id))
    (let [hosted (.indexOf signin-html "id=\"itonami-cloud-signin-card\"")
          local (.indexOf signin-html "id=\"registration-form\"")]
      (is (pos? hosted))
      (is (< hosted local)
          "hosted auth.itonami.cloud is the first entrance, not local Passkey"))
    (is (not (str/includes? signin-html "id=\"itonami-cloud-signin-card\" hidden"))
        "the hosted entrance must not start hidden behind script")
    (is (str/includes? signin-html "href=\"/api/auth/itonami/start\""))
    (is (re-find #"<a [^>]*id=\"itonami-cloud-signin\"" signin-html)
        "hosted sign-in is a document link, not a fetch button")
    (is (str/includes? js "if (nativeSurface())")
        "only the native webview intercepts the hosted sign-in link")
    (is (not (str/includes? signin-html "Passkey の P-256 公開鍵から User DID"))
        "first-time copy must not mint a local did:key as the story")
    (is (str/includes? settings-html "id=\"identity-workspace\""))
    (is (str/includes? js "const viewFromHash = (raw) => {"))
    (is (str/includes? js "const emailLoginToken = new URLSearchParams("))
    (is (str/includes? js "if (location.hash !== target) history.replaceState(null, '', target);"))
    (is (str/includes? js "window.addEventListener('hashchange'"))
    (is (str/includes? html "href=\"#/signin\""))
    (is (str/includes? html "href=\"#/bots\""))
    (is (not (str/includes? html "href=\"#/chat\"")))
    (is (str/includes? html "href=\"#/settings\""))
    (is (str/includes? js "const token = emailLoginToken;"))
    (is (not (str/includes? js
                            "const token = new URLSearchParams(location.hash.slice(1))"))
        "showView must not erase the proof before the one finishing POST")
    (is (str/includes? signin-html "パスキーでサインイン"))
    (is (str/includes? signin-html "パスキーを作る"))
    (is (not (str/includes? signin-html "auth.itonami.cloud でサインイン"))
        "the hostname is not the verb; the hosted page's copy is")))

(deftest the-signin-gate-describes-this-deployment-and-not-a-general-one
  ;; The screen a person meets when the owner ceremony was interrupted:
  ;; `/api/identity/register` created the account and the Passkey never
  ;; arrived, so `registered?` is true and `passkey-required?` is true. Measured
  ;; on a real store 2026-08-12 — the copy named Passkey, Email and SSO while
  ;; the Email card was hidden and every SSO button was disabled.
  ;;
  ;; What the browser can prove lives in test/browser/signin_gate.cljs. What
  ;; this asks is narrower and cheaper: that the copy is addressable at all, and
  ;; that the client derives it from `auth-methods` rather than asserting it.
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        signin-start (.indexOf html "data-view-panel=\"signin\"")
        signin-html (subs html signin-start (.indexOf html "data-view-panel=\"settings\""))]
    (doseq [id ["signin-gate-headline" "signin-gate-note" "sso-signin-card"]]
      (is (str/includes? signin-html (str "id=\"" id "\"")) id))
    ;; The SSO card ships hidden: a card of disabled buttons is not an entrance,
    ;; and the client reveals it only once a provider is configured.
    (is (str/includes? signin-html "id=\"sso-signin-card\" hidden"))
    (is (str/includes? js "const otherSigninMethods = (data) => ["))
    (is (str/includes? js "$('#sso-signin-card').hidden = !providers.length;"))
    (is (str/includes? js
                       "(methods.sso || []).filter((p) => p['configured?'])")
        "the sign-in list must hold only providers that can actually start")
    (is (str/includes? js "renderSigninGate(data);")
        "the gate must be rewritten on every identity render, not once")
    (is (str/includes? js "入口は auth.itonami.cloud")
        "the gate leads with hosted auth when it is configured")
    (is (str/includes? signin-html "dads-accordion")
        "device-local recovery is an accordion, not a parallel ceremony")
    (is (str/includes? js "recovery.open = Boolean(data['passkey-required?'])")
        "an interrupted owner ceremony opens the recovery accordion")
    ;; The copy that made the promise this deployment could not keep.
    (is (not (str/includes? html "通常の入口は Passkey、Email、SSO です")))
    (is (not (str/includes? js "'Passkey、Email、またはSSOで続行できます。'")))))

(deftest domain-ownership-ui-uses-the-human-session-api
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (doseq [id ["domain-verification-card" "domain-verification-form"
                "company-domain" "domain-verification-record-name"
                "domain-verification-record-value" "domain-verification-claim"
                "domain-verification-activate" "domain-verification-recheck"
                "domain-verification-probe"]]
      (is (str/includes? html (str "id=\"" id "\"")) id))
    (is (str/includes? js "fetch('/api/identity/domain-verifications')"))
    (is (str/includes? js
                       "postJSON(\n          '/api/identity/domain-verifications'"))
    ;; The two gates are separate calls, and the card drives both (ADR-0043).
    ;; `/verify` is gone: the name it had said the one proof finished the job.
    (is (str/includes? js "'/api/identity/domain-verifications/claim'"))
    (is (str/includes? js "'/api/identity/domain-verifications/activate'"))
    (is (str/includes? js "'/api/identity/domain-verifications/recheck'"))
    (is (not (str/includes? js "'/api/identity/domain-verifications/verify'")))
    ;; The claim state has to be legible AS a claim. A card that said 確認済み
    ;; here would be describing a tenant that is not yet named by the domain.
    (is (str/includes? js "まだこのOrganizationの名前ではありません"))
    (is (str/includes? js "initialParams.get('setup-domain')"))))

(deftest capture-is-a-record-only-surface-before-clarification
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (doseq [id ["capture-form" "capture-text" "capture-dictate" "capture-filters"
                "capture-list" "capture-detail" "capture-chronicle-toggle"
                "capture-chronicle-panel" "capture-chronicle-now"
                "capture-chronicle-clear" "capture-chronicle-frame-id"]]
      (is (str/includes? html (str "id=\"" id "\"")) id))
    (is (str/includes? js "await postJSON('/api/captures', body, true)"))
    (is (str/includes? js "fetch('/api/captures/chronicle'"))
    (is (str/includes? js "postJSON('/api/captures/chronicle/capture', {}, true)"))
    (is (not (str/includes? js "postJSON('/api/chat', body"))
        "capture does not reuse the model request path")))

(deftest a-cell-anchor-is-spelled-in-one-place
  ;; The grid writes `Sheet1!B3` onto every cell as `data-anchor`, and the
  ;; comment box reads it back to put a dot where a comment points. Two
  ;; spellings would mean a dot that never appears and nothing to say why —
  ;; the same drift `docs.model/text-spans` was pulled out to end, one file
  ;; over and in JavaScript.
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    ;; The anchor format specifically — `!${columnName(` — and not every
    ;; use of `columnName`, which the style bar also makes when it says
    ;; which cell it is acting on. The first version of this assertion
    ;; counted those too and failed on code that was right.
    (is (= 1 (count (re-seq #"!\$\{columnName\(" js)))
        "the `tab!B3` form is written in exactly one place, which is cellAnchor")
    (is (str/includes? js "const cellAnchor = (tab, row, col)"))
    (is (= 2 (count (re-seq #"cellAnchor\(" (str/replace js "const cellAnchor" ""))))
        "and both the grid and the comment box call it")))

(deftest the-signing-picker-does-not-offer-a-file
  ;; `item['file?']` is the server's own answer to "is this one of the four
  ;; surfaces", and the eSign picker has to ask it: an uploaded PDF has no
  ;; resource to outline, so offering one is offering a signature nobody can
  ;; be shown what they are making. `documents/source-bytes` refuses it —
  ;; this is why it is never chosen, not the check that it is refused.
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        picker (second (re-find #"(?s)const refreshEsignDocuments = \(\) => \{(.*?)\n    \};" js))]
    (is (some? picker) "refreshEsignDocuments is still spelled that way")
    (is (str/includes? picker "!item['file?']"))))

(deftest bot-omakase-is-an-explicit-human-setting-with-a-visible-receipt
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (not (str/includes? html "id=\"bots-omakase\"")))
    (is (str/includes? js "make('strong', 'bots-settings__title', 'Bot設定')"))
    (is (str/includes? js "'omakase?':true"))
    (is (str/includes? js "'omakase?':omakaseBox.checked"))
    (is (str/includes? js "'おまかせ承認済み'"))))

(deftest wallet-is-bot-native-rather-than-an-assignment-grid
  (let [html (with-redefs [store/snapshot (constantly (store/initial-state))]
               (web/page-html config))
        js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (str/includes? html "Botを作ると専用Walletも自動で生まれます"))
    (is (str/includes? html "id=\"wallet-bot-select\""))
    (is (str/includes? html "id=\"wallet-assets-tab\""))
    (is (str/includes? html "id=\"wallet-activity-tab\""))
    (is (not (str/includes? html "Walletを選択")))
    (is (str/includes? js "このBot Walletへ署名権限を接続しました"))))
