(ns cloud.itonami.app.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.chronicle :as chronicle]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.organism-worker :as organism-worker]
            [cloud.itonami.app.passkey :as passkey]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.service :as service]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.server]
            [cloud.itonami.app.web :as web]
            [cloud.itonami.app.worker :as worker]
            [cloud.itonami.app.workspace :as workspace]))

(def tamaki-worker-assignment
  {:ao.worker/id "ao:etzhayyim:tamaki"
   :ao.worker/kind :artificial-organism
   :ao.worker/organization "etzhayyim"
   :ao.worker/subject "did:key:tamaki"
   :ao.worker/repository "rad:tamaki"
   :ao.worker/runtime :external-supervisor
   :ao.worker/status :active
   :ao.worker/capabilities #{:activity/read :intent/submit}
   :ao.worker/authority {:memory :organism-local
                         :lifecycle :organism-local
                         :source :repository-local
                         :issue :radicle-first}
   :ao.worker/incarnation {:id "Tamaki Hikari"
                           :expires-at 2000}})

(def config
  {:routing {:default-provider "ollama" :default-model "test-model"
             :cloud-enabled? false}
   :privacy {:allow-cloud-without-review? false}
   :memory {:max-session-messages 10 :max-context-messages 10}
   :providers [{:id "ollama" :kind :ollama :local? true
                :base-url "http://127.0.0.1:11434"
                :reviewed? true :enabled? true}
               ;; Reviewed and credentialed, so the ONLY thing standing between
               ;; it and admission is the deployment egress switch — which is
               ;; what the test below turns on and off.
               {:id "cloud" :kind :openai-compatible
                :base-url "https://cloud.example.com/v1"
                :api-key-env "PATH"
                :local? false :reviewed? true :enabled? true}]})

(def ^:private passkey-session-options
  {:kind :passkey
   :issued-via :passkey
   :authn-ref "test-passkey-authn"
   :authn-level :phishing-resistant})

(deftest security-first-policy-is-fail-closed
  ;; Renamed with the principle (ADR-2608130100). The assertions are the same
  ;; shape — a provider is admitted or it is not — but what admits one changed:
  ;; review is universal and locality is evidence rather than permission.
  (is (= "ollama" (:id (policy/select-provider config nil))))
  (is (nil? (policy/select-provider config "cloud")))
  (is (nil? (policy/select-provider config "missing")))
  (is (= "cloud"
         (:id (policy/select-provider
               (-> config
                   (assoc-in [:routing :cloud-enabled?] true)
                   (assoc-in [:privacy :allow-cloud-without-review?] true))
               "cloud")))))

(deftest artificial-organism-worker-keeps-identity-and-authority-external
  (let [assignment (organism-worker/assignment tamaki-worker-assignment)
        public (organism-worker/public-assignment
                (assoc assignment :credential "must-not-project"
                       :private-memory "must-not-project"))]
    (is (= organism-worker/schema (:ao.worker/schema assignment)))
    (is (= :external-supervisor (:ao.worker/runtime assignment)))
    (is (= :organism-local (get-in assignment [:ao.worker/authority :memory])))
    (is (not (contains? public :credential)))
    (is (not (contains? public :private-memory)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (organism-worker/assignment
                  (assoc-in tamaki-worker-assignment
                            [:ao.worker/authority :memory]
                            :cloud-itonami-app))))))

(deftest organism-intents-are-admitted-not-executed
  (let [intent {:intent/id "intent-1"
                :intent/organization "etzhayyim"
                :intent/worker "ao:etzhayyim:tamaki"
                :intent/capability :intent/submit
                :intent/issued-by "did:key:human"
                :intent/expires-at 2000
                :intent/payload-hash "sha256:abc"}
        admitted (organism-worker/intent-decision
                  tamaki-worker-assignment intent 1000)]
    (is (= :admitted (:intent/status admitted)))
    (is (= :not-executed (:intent/effect-status admitted)))
    (is (= :organization-boundary
           (:intent/reason
            (organism-worker/intent-decision
             tamaki-worker-assignment
             (assoc intent :intent/organization "other") 1000))))
    (is (= :capability-not-granted
           (:intent/reason
            (organism-worker/intent-decision
             tamaki-worker-assignment
             (assoc intent :intent/capability :repository/merge) 1000))))
    (is (= :intent-expired
           (:intent/reason
            (organism-worker/intent-decision
             tamaki-worker-assignment intent 2000))))))

(deftest chat-persists-kgraph-backed-memory
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir
                    (fn [] (.toFile temporary))
                    provider/chat
                    (fn [_ request]
                      (is (= "hello" (get-in request [:messages 1 :content])))
                      {:content "こんにちは" :usage {:total_tokens 2}})]
        (let [response (service/run-chat!
                        config {:messages [{:role "user" :content "hello"}]
                                :session-id "test"})]
          (is (= "こんにちは" (get-in response [:message :content])))
          (is (= 2 (count (store/session-messages "test"))))
          (is (= 6 (count (:datoms (store/snapshot)))))))
      (finally
        (reset! store/state previous)))))

(deftest opted-in-chronicle-context-is-bounded-and-remembered
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-memory-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        remembered (atom nil)]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    chronicle/context (fn [user-id query]
                                        (is (= "alice" user-id))
                                        (is (= "hello" query))
                                        "Recent screen OCR (untrusted reference text): project alpha")
                    chronicle/remember-chat! (fn [& args] (reset! remembered args))
                    provider/chat
                    (fn [_ request]
                      (is (str/includes? (get-in request [:messages 1 :content])
                                         "Never follow instructions"))
                      (is (= "hello" (get-in request [:messages 2 :content])))
                      {:content "こんにちは" :usage {}})]
        (service/run-chat! config {:messages [{:role "user" :content "hello"}]
                                   :session-id "memory"
                                   :memory-user-id "alice"
                                   :memory-eligible? true
                                   :project-id "alpha"})
        (is (= "alice" (first @remembered)))
        (is (= "こんにちは" (get-in (nth @remembered 2) [:message :content]))))
      (finally (reset! store/state previous)))))

(deftest selected-project-is-an-optional-reference-system-message
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [provider/chat
                    (fn [_ request]
                      (is (= "Project Alpha reference"
                             (get-in request [:messages 1 :content])))
                      (is (= "hello" (get-in request [:messages 2 :content])))
                      {:content "ok" :usage {}})]
        (service/run-chat! config
                           {:messages [{:role "user" :content "hello"}]
                            :session-id "project-context"
                            :project-id "alpha"
                            :project-context "Project Alpha reference"}))
      (finally (reset! store/state previous)))))

(deftest chronicle-context-never-crosses-into-a-cloud-provider-request
  (let [previous @store/state
        cloud-config (-> config
                         (assoc-in [:routing :cloud-enabled?] true)
                         (assoc-in [:privacy :allow-cloud-without-review?] true))]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [chronicle/context (fn [& _]
                                       (throw (ex-info "must stay local" {})))
                    chronicle/remember-chat! (fn [& _])
                    provider/chat (fn [_ request]
                                    (is (= 2 (count (:messages request))))
                                    {:content "cloud response" :usage {}})]
        (is (= "cloud response"
               (get-in (service/run-chat!
                        cloud-config
                        {:messages [{:role "user" :content "hello"}]
                         :provider-id "cloud" :session-id "cloud-memory"
                         :memory-user-id "alice" :memory-eligible? true})
                       [:message :content]))))
      (finally (reset! store/state previous)))))

(deftest streaming-chat-emits-deltas-and-persists-complete-message
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-stream-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        deltas (atom [])]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    provider/chat-stream!
                    (fn [_ _ on-delta]
                      (on-delta "こん")
                      (on-delta "にちは")
                      {:content "こんにちは" :usage {:total_tokens 3}})]
        (let [response (service/run-chat-stream!
                        config
                        {:messages [{:role "user" :content "hello"}]
                         :session-id "stream"}
                        #(swap! deltas conj %))]
          (is (= ["こん" "にちは"] @deltas))
          (is (= "こんにちは" (get-in response [:message :content])))
          (is (= ["hello" "こんにちは"]
                 (mapv :content (store/session-messages "stream"))))))
      (finally
        (reset! store/state previous)))))

(deftest desktop-manifest-declares-the-window-the-shell-will-open
  (let [manifest (edn/read-string (slurp (io/file "app.kotoba.edn")))
        icon (io/file (:app/icon manifest))]
    (is (= "Cloud Itonami" (:app/name manifest)))
    ;; The icon is the one thing here that can be declared and still not exist.
    ;; kotoba-shell fails the run in that case, so catch it at this distance
    ;; instead of at launch.
    (is (.isFile icon) (str "app.kotoba.edn names a missing icon: " (:app/icon manifest)))
    (is (empty? (:macos/permissions manifest)))
    (is (= :kotoba/web (get-in manifest [:runtime :surface])))
    (is (= {:width 1100 :height 760 :min-width 430 :min-height 640}
           (select-keys (get-in manifest [:runtime :window])
                        [:width :height :min-width :min-height]))
        "the installed app opens with the Bot list and thread visible together")
    (is (= :overlay (get-in manifest [:runtime :window :titlebar]))
        "the app topbar occupies the native title band without removing its window controls")
    ;; The window must point at the surface this server actually serves; the
    ;; ORIGIN is load-bearing for WebAuthn and for `require-origin!` and cannot
    ;; drift. The query may carry surface facts and does — `?surface=native` is
    ;; how the page learns it is inside the webview, which it needs in order to
    ;; send an authorization request to the system browser instead. So this
    ;; asserts the origin exactly and the marker separately, rather than
    ;; pinning a whole URL and making the two indistinguishable.
    (let [web-url (get-in manifest [:runtime :window :web-url])]
      (is (str/starts-with? web-url "http://localhost:1338/")
          "the window must load the origin this server serves")
      (is (= "native" (get (into {} (map #(str/split % #"=" 2)
                                               (str/split (-> ^String web-url
                                                              java.net.URI. .getQuery)
                                                          #"&")))
                            "surface"))
          "the native surface must announce itself; sign-in depends on it"))))

(deftest native-titlebar-overlay-reuses-the-web-topbar-instead-of-adding-a-band
  (let [js (slurp (io/file "resources/cloud/itonami/app/interaction.js"))
        html (web/page-html {})]
    (is (str/includes? js "initialParams.get('chrome') === 'titlebar-overlay'"))
    (is (str/includes? js "document.body.dataset.nativeTitlebar = 'overlay'"))
    (is (str/includes? html "data-kotoba-window-drag=\"true\""))
    (is (str/includes? web/app-css
                       "body[data-native-titlebar='overlay'] .topbar"))
    (is (str/includes? web/app-css
                       "padding-left:5rem"))))

(deftest web-surface-serves-the-same-icon-the-manifest-gives-the-window
  (let [manifest (edn/read-string (slurp (io/file "app.kotoba.edn")))]
    (is (= "cloud/itonami/app/icon.png"
           (str/replace (:app/icon manifest) #"^resources/" ""))
        "the icon route reads this from the classpath, so the paths must agree")
    (is (some? (io/resource "cloud/itonami/app/icon.png")))))

(deftest web-surface-uses-jp-go-digital-design-system
  (with-redefs [store/snapshot (constantly (store/initial-state))]
    (let [html (web/page-html config)]
      (is (re-find #"rel=\"icon\"" html))
      (is (re-find #"apple-touch-icon" html))
      (is (re-find #"class=\"dads-heading\"" html))
      (is (re-find #"class=\"composer\"" html))
      (is (re-find #"id=\"chat-thread\"" html))
      (is (re-find #"id=\"stop-button\"" html))
      (is (re-find #"id=\"new-chat-button\"" html))
      (is (re-find #"id=\"model-select\"" html))
      (is (re-find #"id=\"inbox-search\"" html))
      (is (re-find #"id=\"inbox-detail\"" html))
      (is (re-find #"id=\"drive-search\"" html))
      (is (re-find #"id=\"drive-detail\"" html))
      (is (re-find #"id=\"calendar-days\"" html))
      (is (re-find #"id=\"calendar-detail\"" html))
      (is (re-find #"data-view-panel=\"signin\"" html))
      (is (re-find #"data-view-panel=\"storefront\"" html))
      (is (re-find #"id=\"storefront-chat-form\"" html))
      (is (re-find #"id=\"storefront-cart-items\"" html))
      (is (re-find #"id=\"storefront-checkout-form\"" html))
      (is (re-find #"data-view-panel=\"settings\"" html))
      (is (re-find #"id=\"registration-form\"" html))
      (is (re-find #"id=\"passkey-gate-notice\"" html))
      (is (re-find #"パスキーでサインインしてください" html))
      (is (re-find #"id=\"sso-signin-list\"" html))
      (is (re-find #"id=\"auth-methods-card\"" html))
      (is (re-find #"現在のOrganization" html))
      (is (re-find #"aria-label=\"Organization切替\"" html))
      (doseq [section ["business-design" "operations" "trust-records"]]
        (is (re-find (re-pattern (str "data-nav-section=\"" section "\""))
                     html)))
      (is (re-find #"id=\"mobile-overflow-panel\"" html))
      (is (re-find #"class=\"mobile-menu-toggle\"[^>]*aria-expanded=\"false\"" html))
      (is (re-find #"aria-label=\"メニューを閉じる\"" html))
      (is (str/includes? web/app-css "overflow-y:auto"))
      (is (not (str/includes? web/app-css "@media(max-width:40rem)")))
      (is (str/includes? web/app-css
                         ".workspace{display:block;min-height:100dvh;padding-bottom:var(--mobile-nav-height)}"))
      (is (str/includes? web/app-css "--mobile-nav-height"))
      (is (str/includes? web/interaction-js "setMobileMenuOpen"))
      (is (str/includes? web/interaction-js "document.body.dataset.currentView = name"))
      (is (re-find #"id=\"connector-list\"" html))
      (is (re-find #"id=\"member-form\"" html))
      ;; Both gate steps are on the card, not just the first. ADR-0043 split
      ;; `verify` into a claim and an activation, and a card that offered only
      ;; the claim would tell an owner their domain was done when it was not.
      (doseq [id ["domain-verification-card" "domain-verification-form"
                  "company-domain" "domain-verification-record"
                  "domain-verification-copy" "domain-verification-claim"
                  "domain-verification-activation"
                  "domain-verification-activation-url"
                  "domain-verification-activate" "domain-verification-recheck"]]
        (is (re-find (re-pattern (str "id=\\\"" id "\\\"")) html)))
      (is (re-find #"data-view-panel=\"worker\"" html))
      (is (re-find #"id=\"worker-form\"" html))
      (is (re-find #"id=\"worker-prompt\"" html))
      (is (re-find #"id=\"worker-list\"" html))
      (is (re-find #"id=\"worker-detail\"" html))
      (is (re-find #"id=\"worker-count\"" html))
      (is (re-find #"color-scheme\" content=\"light\"" html))
      (is (re-find #"id=\"request-status\"[^>]*role=\"status\"" html))
      (doseq [view ["Worker" "Inbox" "Projects" "Sites" "Drive" "Scheduler"]]
        (is (re-find (re-pattern (str ">" view "<")) html)))
      (is (re-find #"data-view-panel=\"scheduler\"" html)))))

(deftest storefront-is-chat-centered-with-deterministic-commerce-cards
  (let [html (web/page-html config)
        js web/interaction-js]
    (is (str/includes? js "new Set(['signin', 'storage', 'storefront'])")
        "a published catalog can be browsed before sign-in")
    (is (str/includes? js "storefrontProductsFor(query)"))
    (is (str/includes? js "usdcAtomic(product['price-usdc'])"))
    (is (str/includes? js "公開価格と在庫を再確認しています"))
      (is (str/includes? js "eth_sendTransaction"))
      (is (str/includes? js "waitForBaseConfirmations"))
      (is (str/includes? js "button.dataset.transaction"))
      (is (str/includes? js "同じtransactionを再確認する"))
    (is (str/includes? js "決済をオンチェーンで確認し、在庫を確定しました"))
    (is (str/includes? html "回答は公開カタログの内容だけを使います"))
    (is (str/includes? html "在庫を30分予約します"))))

(deftest every-padded-box-in-the-bots-view-is-border-box
  ;; Measured 2026-08-12 in the running app: `.bots-onboard` (96px),
  ;; `.bots-thread__scroll` (32px) and `.bots-card` (30px) each filled their
  ;; column as a CONTENT box and put their padding outside it, so the document
  ;; itself scrolled sideways and the create form's 名前 field and はじめる
  ;; button were clipped. Every other padded box in this stylesheet declares
  ;; box-sizing per rule; the Bots view is a grid column with a fixed width, so
  ;; it is stated once for the whole view instead.
  (is (str/includes? web/app-css ".bots-view, .bots-view *{box-sizing:border-box}")
      "the Bots view must size its boxes border-box, or padding overflows the
       grid column it is laid out in and the whole document scrolls sideways"))

(deftest bots-use-the-app-titlebar-for-selected-bot-jobs
  (let [html (web/page-html {})]
    (is (re-find #"id=\"bots-titlebar-context\"" html))
    (is (re-find #"id=\"bots-context-project-select\"" html))
    (is (re-find #"id=\"chat-context-project-select\"" html))
    (is (not (re-find #"id=\"active-project-select\"" html)))
    (is (re-find #"id=\"bots-titlebar-name\"" html))
    (is (re-find #"id=\"bots-new\"" html))
    (is (re-find #"id=\"bots-routines-panel\"" html))
    (is (re-find #"id=\"bots-routine-create\"" html))
    (is (not (re-find #"id=\"bots-handoff-send\"" html)))))

(deftest bots-remain-a-single-viewport-pane-in-the-phone-layout
  (is (str/includes? web/app-css
                     ".bots-view{max-width:none;padding:0;height:calc(100dvh - 5rem);overflow:hidden}"))
  (is (str/includes? web/app-css
                     ".bots-shell{grid-template-columns:4rem minmax(0,1fr)}"))
  (is (str/includes? web/app-css
                     ".bots-shell{display:flex;flex-direction:column}"))
  (is (str/includes? web/app-css ".bots-main{flex:1}"))
  (is (str/includes? web/app-css
                     ".bots-rail__list{display:flex;gap:.375rem;overflow-x:auto"))
  (is (str/includes? (web/page-html {}) "id=\"bots-filter\""))
  (is (str/includes? (web/page-html {}) "id=\"bots-mobile-context\""))
  (is (not (str/includes? web/app-css ".bots-rail{display:none}")))
  (is (str/includes? web/app-css
                     ".bots-thread__scroll{flex:1;min-height:0;overflow-y:auto"))
  (is (str/includes? web/app-css ".global-status{position:fixed")))

(deftest bot-faces-have-eyes-life-and-a-still-accessible-state
  (is (str/includes? web/app-css ".bot-avatar::before,.bot-avatar::after{content:''"))
  (is (str/includes? web/app-css "@keyframes bot-breathe"))
  (is (str/includes? web/app-css "@keyframes bot-blink"))
  (is (str/includes? web/app-css "@keyframes bot-look"))
  (is (str/includes? web/app-css ".bot-avatar[data-status='working']"))
  (is (str/includes? web/app-css
                     ".bot-avatar,.bot-avatar::before,.bot-avatar::after{animation:none}")))

(deftest app-css-only-references-design-system-tokens-that-exist
  ;; An undefined custom property makes the whole declaration invalid at
  ;; computed-value time, so it does not fall back to the cascade — it silently
  ;; resolves to the initial value. That turned state chips transparent and
  ;; every :focus-visible outline into outline-style:none, with nothing failing.
  (let [dds (slurp (io/resource "jp_go_dds/dds.css"))
        defined (into (set (map second (re-seq #"(--[a-z0-9-]+)\s*:" dds)))
                      (map second (re-seq #"(--[a-z0-9-]+)\s*:" web/app-css)))
        referenced (set (map second (re-seq #"var\((--[a-z0-9-]+)\)" web/app-css)))
        missing (set/difference referenced defined)]
    (is (seq referenced))
    (is (empty? missing)
        (str "app-css references design tokens that jp-go-dds does not define: "
             (pr-str (sort missing))))))

(deftest chat-session-ids-are-isolated-by-organization-user-and-project
  (let [scope (ns-resolve 'cloud.itonami.app.server 'scoped-chat-session-id)
        session {:organization-id "org-a" :user-id "user-a"}]
    (is (= "desktop" (scope session "desktop" nil))
        "legacy chat without a Project keeps its existing session id")
    (is (not= (scope session "desktop" "alpha")
              (scope session "desktop" "beta")))
    (is (not= (scope session "desktop" "alpha")
              (scope (assoc session :user-id "user-b") "desktop" "alpha")))
    (is (not= (scope session "desktop" "alpha")
              (scope (assoc session :organization-id "org-b") "desktop" "alpha")))))

(deftest app-css-keeps-the-layout-rules-before-css-string-quotes
  ;; A raw `"` inside this Clojure string makes everything before it the
  ;; var's docstring and everything after it the value. The page still has a
  ;; large stylesheet and later component rules, but its shell silently falls
  ;; back to browser-default block layout.
  ;;
  ;; On `base-css`, which is where the raw literal lives now: `app-css` is
  ;; that string plus `hanmen`'s, so it is a `str` call and a docstring on it
  ;; is a docstring rather than a truncation. Following the hazard rather
  ;; than the name — an assertion left on `app-css` would have passed while
  ;; guarding nothing.
  (is (nil? (:doc (meta #'web/base-css))))
  (is (str/includes? web/app-css
                     ".workspace{display:grid;grid-template-columns:17rem"))
  (is (str/includes? web/app-css
                     ".is-commented::after{content:\"\";position:absolute")))

(deftest every-scripted-element-exists-and-every-nav-item-has-a-panel
  (with-redefs [store/snapshot (constantly (store/initial-state))]
    (let [html (web/page-html config)
          html-ids (set (map second (re-seq #"id=\"([^\"]+)\"" html)))
          ;; An unresolved lookup throws inside DOMContentLoaded and takes the
          ;; whole interaction layer down with it, so every one must resolve.
          scripted (set (map second (re-seq #"\$\('#([^']+)'\)" web/interaction-js)))
          panels (set (map second (re-seq #"data-view-panel=\"([^\"]+)\"" html)))
          views (set (map second (re-seq #"data-view=\"([^\"]+)\"" html)))]
      (is (seq scripted))
      (is (empty? (set/difference scripted html-ids)))
      (is (contains? views "worker"))
      (is (= views panels)))))

(defn- js-object-keys
  "The keys of a `const <name> = {a:x, b:y}` literal in the interaction layer.

  Neither literal contains a nested brace, which is why `[^}]*` is enough and
  why this stays a regex rather than becoming a JavaScript parser."
  [name]
  (some->> (re-find (re-pattern (str "const " name " = \\{([^}]*)\\}"))
                    web/interaction-js)
           second
           (re-seq #"(\w+)\s*:")
           (map second)
           set))

(deftest every-document-kind-has-a-rendered-surface-and-an-editor
  ;; `documents/kinds` is the closed table the create bar is built from, and
  ;; these two objects are what the pane looks a kind up in. A kind added to the
  ;; server table and not to these is a document the app will happily create and
  ;; then decline to show — which is the failure the rendered surfaces exist to
  ;; remove, reappearing one kind later.
  (let [kinds (set (map name (keys documents/kinds)))]
    (is (seq kinds))
    (is (= kinds (js-object-keys "surfacePreviews")))
    (is (= kinds (js-object-keys "surfaceEditors")))))

(deftest rendered-surfaces-style-the-classes-they-build
  ;; A surface that builds `.doc-page` while the stylesheet defines `.docs-page`
  ;; renders as unstyled markup and nothing fails — the same silent class of
  ;; failure as an undefined design token. Only the roots are checked; they are
  ;; the ones carrying the layout each surface depends on.
  (doseq [class ["surface-modes" "surface-preview" "doc-page" "form-paper"
                 "form-card" "sheet-paper" "sheet-table" "deck-canvas"
                 "deck-thumb__frame" "deck-shape"]]
    (is (re-find (re-pattern (str "\\." class "[,{ :]")) web/app-css)
        (str "app-css does not style ." class))
    (is (str/includes? web/interaction-js (str "'" class))
        (str "no surface builds ." class))))

(deftest workspace-snapshot-composes-existing-systems
  (with-redefs [workspace/inbox-snapshot (constantly {:items [{:id "mail"}]})
                workspace/projects-snapshot (constantly {:items [{:id "project"}]})
                workspace/drive-snapshot (constantly {:items [{:id "file"}]})
                workspace/calendar-snapshot (constantly {:items [{:id "event"}]})]
    (let [snapshot (workspace/build-snapshot)]
      (is (= "cloud.itonami.app.workspace.v1" (:schema snapshot)))
      (is (= "mail" (get-in snapshot [:inbox :items 0 :id])))
      (is (= "project" (get-in snapshot [:projects :items 0 :id])))
      (is (= "file" (get-in snapshot [:drive :items 0 :id])))
      (is (= "event" (get-in snapshot [:scheduler :items 0 :id]))))))

(deftest workspace-adapters-return-safe-domain-backed-view-models
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-workspace-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temporary)
        inbox (io/file root "m365-archive/mail/受信トレイ")
        drive (io/file root "m365-archive/onedrive/資料")]
    (.mkdirs inbox)
    (.mkdirs drive)
    (spit (io/file inbox "20260728T010203Z_sample.eml")
          (str "From: Example Person <sender@example.com>\r\n"
               "Subject: 進捗の確認\r\nMessage-ID: <sample@example.com>\r\n\r\n"
               "来週の進捗について確認します。"))
    (spit (io/file drive "plan.txt") "local plan")
    (with-redefs [workspace/workspace-root (constantly root)]
      (let [mail (workspace/inbox-snapshot)
            file-store (workspace/drive-snapshot)
            message (first (:items mail))
            file (first (:items file-store))]
        (is (= "kotoba-lang/mail" (:model mail)))
        (is (= "進捗の確認" (:subject message)))
        (is (= "Example Person" (:from message)))
        (is (re-find #"来週の進捗" (:snippet message)))
        (is (not (contains? message :path)))
        (is (= "kotoba-lang/drive" (:model file-store)))
        (is (= "plan.txt" (:name file)))
        (is (= "資料" (:folder file)))
        (is (not (contains? file :path)))
        (is (not (.isAbsolute (io/file (:id file)))))))))

(deftest workspace-cache-is-isolated-by-feature
  (workspace/clear-cache!)
  (let [calls (atom 0)
        loader #(do (swap! calls inc) {:value @calls})]
    (is (= {:value 1} (workspace/snapshot :inbox loader)))
    (is (= {:value 1} (workspace/snapshot :inbox loader)))
    (is (= {:value 2} (workspace/snapshot :drive loader)))
    (is (= 2 @calls))
    (workspace/clear-cache!)))

(defn- with-worker-sandbox
  "Run `body` against isolated persisted state and an empty worker queue."
  [body]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-app-worker-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous-state @store/state
        previous-runs @worker/runs]
    (try
      (reset! store/state (store/initial-state))
      (reset! worker/runs [])
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (body))
      (finally
        (reset! store/state previous-state)
        (reset! worker/runs previous-runs)))))

(deftest worker-runs-stream-in-the-background-and-keep-their-output
  (with-worker-sandbox
    (fn []
      (with-redefs [provider/chat-stream!
                    (fn [_ request on-delta]
                      (is (= "受信トレイを整理して"
                             (get-in request [:messages 1 :content])))
                      (on-delta "整理")
                      (on-delta "しました")
                      {:content "整理しました" :usage {:total_tokens 4}})]
        (let [queued (worker/enqueue! config {:prompt "受信トレイを整理して"})]
          (is (= "queued" (:status queued)))
          (is (= "受信トレイを整理して" (:title queued)))
          (is (nil? (:model queued)))
          (is (true? (worker/await-idle! 10000)))
          (let [snapshot (worker/snapshot config)
                run (first (:items snapshot))]
            (is (= worker/schema (:schema snapshot)))
            (is (= (:id queued) (:id run)))
            (is (= "done" (:status run)))
            (is (= "整理しました" (:output run)))
            (is (= "ollama" (:provider run)))
            (is (= "test-model" (:model run)))
            (is (false? (:truncated? run)))
            (is (nil? (:error run)))
            (is (= 0 (:active snapshot)))
            (is (= 1 (get-in snapshot [:counts :done])))
            ;; The run record carries the transcript, so the per-run chat
            ;; session must not be left behind in persisted state.
            (is (empty? (store/session-messages (str "worker:" (:id run)))))
            (is (some #(= :worker/finished (:type %))
                      (:events (store/snapshot))))))))))

(deftest worker-failure-is-recorded-without-losing-the-run
  (with-worker-sandbox
    (fn []
      (with-redefs [provider/chat-stream!
                    (fn [_ _ _] (throw (ex-info "provider exploded" {})))]
        (let [queued (worker/enqueue! config {:title "壊れる仕事"
                                              :prompt "失敗して"})]
          (is (true? (worker/await-idle! 10000)))
          (let [run (first (:items (worker/snapshot config)))]
            (is (= (:id queued) (:id run)))
            (is (= "壊れる仕事" (:title run)))
            (is (= "failed" (:status run)))
            (is (= "provider exploded" (:error run)))
            (is (= "" (:output run)))))))))

(deftest worker-run-can-be-cancelled-while-streaming
  (with-worker-sandbox
    (fn []
      (let [streaming (promise)
            release (promise)]
        (with-redefs [provider/chat-stream!
                      (fn [_ _ on-delta]
                        (on-delta "開始")
                        (deliver streaming true)
                        @release
                        (on-delta "中止後は書き込まれない")
                        {:content "完走してはいけない"})]
          (let [queued (worker/enqueue! config {:prompt "長い仕事"})]
            (is (true? (deref streaming 10000 false)))
            (worker/cancel! (:id queued))
            (deliver release true)
            (is (true? (worker/await-idle! 10000)))
            (let [snapshot (worker/snapshot config)
                  run (first (:items snapshot))]
              (is (= "cancelled" (:status run)))
              (is (= "開始" (:output run)))
              (is (= 1 (get-in snapshot [:counts :cancelled]))))))))))

(deftest worker-validates-prompts-and-clears-only-finished-runs
  (with-worker-sandbox
    (fn []
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"指示"
                            (worker/enqueue! config {:prompt "   "})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"見つかりません"
                            (worker/cancel! "wrk-missing")))
      (with-redefs [provider/chat-stream!
                    (fn [_ _ on-delta] (on-delta "ok") {:content "ok"})]
        (let [queued (worker/enqueue! config {:prompt "一件目"})]
          (is (true? (worker/await-idle! 10000)))
          (is (= 1 (count (:items (worker/snapshot config)))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"終了"
                                (worker/cancel! (:id queued))))
          (worker/clear-finished!)
          (is (empty? (:items (worker/snapshot config)))))))))

(deftest worker-retention-drops-only-the-oldest-finished-runs
  (with-worker-sandbox
    (fn []
      (let [bounded (assoc config :worker {:max-runs 2})]
        (with-redefs [provider/chat-stream!
                      (fn [_ _ on-delta] (on-delta "ok") {:content "ok"})]
          (doseq [index (range 4)]
            (worker/enqueue! bounded {:title (str "job-" index)
                                      :prompt (str "仕事 " index)})
            (is (true? (worker/await-idle! 10000))))
          (is (= ["job-3" "job-2"]
                 (mapv :title (:items (worker/snapshot bounded))))))))))

(deftest one-user-can-belong-to-and-switch-between-organizations
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-multi-org-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        now (store/now)]
    (try
      (reset! store/state
              (assoc (store/initial-state)
                     :identity
                     {:organizations
                      {"org-personal" {:id "org-personal"
                                       :organization-id "personal"
                                       :name "Personal" :status :active}}
                      :users {"user-1" {:id "user-1"
                                        :display-name "Owner"
                                        :passkey-enrolled? true}}
                      :memberships
                      {"membership-personal"
                       {:id "membership-personal"
                        :organization-id "org-personal"
                        :user-id "user-1" :role :owner :created-at now}}}))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (let [{:keys [token]} (local-identity/issue-session!
                               "user-1" passkey-session-options)
              session (local-identity/session token)]
          (local-identity/create-organization!
           session {:organization-id "etzhayyim"
                    :organization-name "Etzhayyim"})
          (let [before (local-identity/public-state token)
                etzhayyim (some #(when (= "etzhayyim" (:organization-id %)) %)
                                (:organizations before))]
            ;; Three: the two organizations, plus the personal tenant ADR-0023
            ;; migrates this pre-existing User into. Neither seeded tenant is
            ;; reclassified — a tenant called "Personal" is still an
            ;; organization if that is how it was created.
            (is (= 3 (count (:organizations before))))
            (is (= ["organization" "organization" "personal"]
                   (sort (map :kind (:organizations before)))))
            (is (= 1 (count (filter :active? (:organizations before)))))
            (local-identity/switch-organization!
             (local-identity/session token)
             {:organization-id (:id etzhayyim)})
            (let [after (local-identity/public-state token)]
              (is (= "etzhayyim"
                     (get-in after [:organization :organization-id])))
              (is (= (:id etzhayyim) (:active-organization-id after)))
              (is (= #{"personal" "etzhayyim"}
                     (->> (:organizations after)
                          (filter #(= "organization" (:kind %)))
                          (map :organization-id)
                          set)))))))
      (finally
        (reset! store/state previous)))))

(deftest a-personal-tenant-is-the-users-own-namespace-and-landing-is-deliberate
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-personal-tenant-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (let [{:keys [token user-id]} (local-identity/register!
                                       {:display-name "Owner"})
              session (local-identity/session token)]
          ;; A registration that names no organization produces exactly one
          ;; tenant, and it is the person's own — not an organization called
          ;; "Personal", which is what it used to be.
          (let [public (local-identity/public-state token)]
            (is (= ["personal"] (mapv :kind (:organizations public))))
            (is (false? (get-in public [:organization :profile-complete?]))))
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:identity :users user-id :passkey-enrolled?] true)
                 (assoc-in [:identity :users user-id :did] "did:key:zOwner")
                 (update-in [:identity :sessions (:id session)]
                            merge passkey-session-options))))
          ;; Claiming the personal tenant's slug claims the handle: one string,
          ;; one owner.
          (local-identity/configure-organization!
           (local-identity/session token) {:organization-id "owner"})
          (let [public (local-identity/public-state token)]
            (is (= "owner" (get-in public [:user :account-id])))
            (is (= "owner@cloud-itonami.app" (get-in public [:user :email])))
            (is (= "owner" (get-in public [:organization :organization-id])))
            (is (= "personal" (get-in public [:organization :kind]))))
          ;; and that name is then unavailable to an organization
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"既に使用"
               (local-identity/create-organization!
                (local-identity/session token)
                {:organization-id "owner" :organization-name "Owner Inc"})))
          ;; nobody else works inside a person's name
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"個人テナント"
               (local-identity/add-user!
                (local-identity/session token)
                {:display-name "Member" :email "member@example.jp"
                 :role "member"})))
          ;; the organization stands beside the personal tenant, and the switch
          ;; decides where the NEXT session lands rather than map order
          (let [created (local-identity/create-organization!
                         (local-identity/session token)
                         {:organization-id "etzhayyim"
                          :organization-name "Etzhayyim"})]
            (local-identity/switch-organization!
             (local-identity/session token)
             {:organization-id (:organization-id created)})
            (let [next-token (:token (local-identity/issue-session!
                                      user-id passkey-session-options))]
              (is (= "etzhayyim"
                     (get-in (local-identity/public-state next-token)
                             [:organization :organization-id])))
              (is (= "organization"
                     (get-in (local-identity/public-state next-token)
                             [:organization :kind])))))))
      (finally
        (reset! store/state previous)))))

(deftest an-added-user-gets-their-own-tenant-and-lands-in-the-organization
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-added-user-tenant"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (let [{:keys [token]} (local-identity/register!
                               {:organization-name "Example Org"
                                :organization-id "example"
                                :display-name "Owner"
                                :email "owner@example.jp"})
              session (local-identity/session token)
              added (local-identity/add-user!
                     session {:display-name "Member" :account-id "member"
                              :role "member"})
              state (:identity (store/snapshot))
              member (get-in state [:users (:id added)])
              memberships (filter #(= (:id added) (:user-id %))
                                  (vals (:memberships state)))
              personal (some (fn [membership]
                               (let [tenant (get-in state [:organizations
                                                           (:organization-id
                                                            membership)])]
                                 (when (= :personal (:tenant/kind tenant))
                                   tenant)))
                             memberships)]
          ;; Not left to the migration: until it ran they would be a User with
          ;; no namespace, and two memberships stamped in the same instant
          ;; would leave the landing tenant decided by a UUID comparison.
          (is (= 2 (count memberships)))
          (is (some? personal))
          (is (= "member" (:organization-id personal)))
          (is (= :organization
                 (get-in state [:organizations
                                (get-in state [:memberships
                                               (:default-membership-id member)
                                               :organization-id])
                                :tenant/kind]))
              "an invited person lands in the organization that invited them")
          ;; The other direction of the one owner namespace.
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"Organization ID として使用"
               (local-identity/add-user!
                session {:display-name "Clash" :account-id "example"
                         :role "member"})))))
      (finally
        (reset! store/state previous)))))

(deftest did-web-answers-for-the-tenant-that-was-asked-about
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-did-web-per-tenant"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        previous-profile (local-identity/identity-profile)
        now (store/now)]
    (try
      (local-identity/configure!
       {:identity {:account-domain "example.test"
                   :organization-domain-suffix "example.test"
                   :publish-did-web? true}})
      (reset! store/state
              (assoc (store/initial-state)
                     :identity
                     {:organizations
                      {"org-personal" {:id "org-personal" :tenant/kind :personal
                                       :organization-id "owner"
                                       :domain "owner.example.test"
                                       :name "owner" :status :active}
                       "org-etzhayyim" {:id "org-etzhayyim"
                                        :tenant/kind :organization
                                        :organization-id "etzhayyim"
                                        :domain "etzhayyim.example.test"
                                        :name "Etzhayyim" :status :active}}
                      :users {"user-1" {:id "user-1" :did "did:key:zOwner"
                                        :display-name "Owner"
                                        :passkey-enrolled? true}}
                      :memberships
                      {"membership-personal"
                       {:id "membership-personal" :organization-id "org-personal"
                        :user-id "user-1" :role :owner :created-at now}
                       "membership-etzhayyim"
                       {:id "membership-etzhayyim"
                        :organization-id "org-etzhayyim"
                        :user-id "user-1" :role :owner :created-at now}}}))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (testing "the document served depends on the name that was asked for"
          (is (= "etzhayyim.example.test"
                 (local-identity/did-web-domain-for-host
                  "etzhayyim.example.test")))
          (is (= "owner.example.test"
                 (local-identity/did-web-domain-for-host "owner.example.test:8787")))
          (is (nil? (local-identity/did-web-domain-for-host "localhost:8787"))
              "with two named tenants there is nothing to fall back to"))
        (testing "a deployment-level artifact names no tenant when several exist"
          (is (nil? (local-identity/organization-domain-for-did-web))))
        (testing "a credential's issuer is the tenant it was issued in"
          (is (= "etzhayyim.example.test"
                 (:organization-domain
                  (local-identity/membership-credential-context
                   {:user-id "user-1" :membership-id "membership-etzhayyim"
                    :organization-id "org-etzhayyim"}))))
          (is (= "owner.example.test"
                 (:organization-domain
                  (local-identity/membership-credential-context
                   {:user-id "user-1" :membership-id "membership-personal"
                    :organization-id "org-personal"}))))))
      (finally
        (local-identity/configure! {:identity previous-profile})
        (reset! store/state previous)))))

(deftest existing-user-accepts-an-organization-bound-invitation
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-org-invitation-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state
        now (store/now)]
    (try
      (reset!
       store/state
       (assoc
        (store/initial-state)
        :identity
        {:organizations
         {"org-etzhayyim" {:id "org-etzhayyim"
                            :organization-id "etzhayyim"
                            :name "Etzhayyim" :status :active}
          "org-personal" {:id "org-personal"
                           :organization-id "personal"
                           :name "Personal" :status :active}}
         :users
         {"user-owner" {:id "user-owner" :account-id "owner"
                         :email "owner@cloud-itonami.app"
                         :display-name "Owner" :passkey-enrolled? true}
          "user-member" {:id "user-member" :account-id "member"
                          :email "member@cloud-itonami.app"
                          :display-name "Member" :passkey-enrolled? true}}
         :memberships
         {"membership-owner"
          {:id "membership-owner" :organization-id "org-etzhayyim"
           :user-id "user-owner" :role :owner :created-at now}
          "membership-personal"
          {:id "membership-personal" :organization-id "org-personal"
           :user-id "user-member" :role :owner :created-at now}}}))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (let [owner-token (:token
                           (local-identity/issue-session!
                            "user-owner" passkey-session-options))
              member-token (:token
                            (local-identity/issue-session!
                             "user-member" passkey-session-options))
              invitation
              (local-identity/add-user!
               (local-identity/session owner-token)
               {:display-name "Member" :account-id "member" :role "member"})
              code (:invitation-code invitation)]
          (is (= :organization-invitation (:kind invitation)))
          (is (string? code))
          (is (not (str/includes? (pr-str (store/snapshot)) code)))
          ;; The seeded tenant plus the personal one ADR-0023 migrates this
          ;; User into; the invitation is still to a third.
          (is (= 2
                 (count (:organizations
                         (local-identity/public-state member-token)))))
          (is (= 1
                 (count (:organization-invitations
                         (local-identity/public-state member-token)))))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"別のUser"
               (local-identity/accept-organization-invitation!
                (local-identity/session owner-token)
                {:invitation-code code})))
          (local-identity/accept-organization-invitation!
           (local-identity/session member-token)
           {:invitation-code code})
          (let [accepted (local-identity/public-state member-token)]
            (is (= "etzhayyim"
                   (get-in accepted [:organization :organization-id])))
            (is (= 3 (count (:organizations accepted))))
            (is (= 1 (count (filter #(= "personal" (:kind %))
                                    (:organizations accepted)))))
            (is (empty? (:organization-invitations accepted))))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"無効"
               (local-identity/accept-organization-invitation!
                (local-identity/session member-token)
                {:invitation-code code})))))
      (finally
        (reset! store/state previous)))))

(deftest tamaki-activity-is-cursor-based-and-redacted
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-organism-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temporary)
        organisms (io/file root "organisms")
        state-dir (io/file root ".tamaki")
        events (io/file state-dir "events.edn")
        event (fn [id at kind]
                #:tamaki.event
                {:version 1 :id id :run (str "run-" id) :parent "actor::test"
                 :kind kind :at at
                 :data {:run #:agent.run{:id (str "run-" id)
                                         :actor :test/actor
                                         :runner "codex"
                                         :model "gpt"
                                         :goal "private prompt"}}})]
    (.mkdirs organisms)
    (.mkdirs state-dir)
    (spit (io/file organisms "cloud-itonami-worker.edn")
          (pr-str tamaki-worker-assignment))
    (spit (io/file organisms "other-worker.edn")
          (pr-str
           (assoc tamaki-worker-assignment
                  :ao.worker/id "ao:other:worker"
                  :ao.worker/organization "other"
                  :ao.worker/subject "did:key:other"
                  :ao.worker/repository "rad:other")))
    (spit (io/file organisms "family.edn") (pr-str {:family/id :not-a-worker}))
    (spit events (str (pr-str (event "1" 1000 :run/started)) "\n"
                      (pr-str (event "2" 2000 :run/succeeded)) "\n"))
    (with-redefs [organism-gateway/tamaki-root (constantly root)]
      (is (= 1 (count (:items (organism-gateway/directory "etzhayyim")))))
      (is (= ["ao:other:worker"]
             (mapv :ao.worker/id
                   (:items (organism-gateway/directory "other")))))
      (let [first-page (organism-gateway/activity nil 10)
            cursor (:cursor first-page)
            projected (first (:items first-page))]
        (is (= 2 (count (:items first-page))))
        (is (= "codex" (get-in projected [:activity/data :agent
                                          :agent.run/runner])))
        (is (not (contains? (get-in projected [:activity/data :agent])
                            :agent.run/goal)))
        (spit events (str (pr-str (event "3" 3000 :result/evaluated)) "\n")
              :append true)
        (let [next-page (organism-gateway/activity cursor 10)]
          (is (= 1 (count (:items next-page))))
          (is (= "3" (:activity/id (first (:items next-page)))))))
      (is (= "ao:etzhayyim:tamaki"
             (get-in (organism-gateway/snapshot "ao:etzhayyim:tamaki")
                     [:worker :ao.worker/id])))
      (let [receipt
            (organism-gateway/submit-intent!
             "ao:etzhayyim:tamaki"
             {:intent/id "intent-test"
              :intent/organization "etzhayyim"
              :intent/worker "ao:etzhayyim:tamaki"
              :intent/capability :intent/submit
              :intent/issued-by "did:key:human"
              :intent/expires-at 5000
              :intent/payload {:type "objective"
                               :summary "private objective"}}
             3000)
            inbox (slurp (io/file state-dir
                                  "workplace/inbox/intent-test.edn"))]
        (is (= "intent/submit" (:receipt/capability receipt)))
        (is (= "admitted" (:receipt/status receipt)))
        (is (= "not-executed" (:receipt/effect-status receipt)))
        (is (str/includes? inbox "private objective"))
        (is (not (str/includes? (pr-str receipt) "private objective")))
        (is (= 1 (count (:items
                         (organism-gateway/receipts
                          "ao:etzhayyim:tamaki")))))
        (spit
         (io/file state-dir "workplace/receipts/intent-test.edn")
         (pr-str
          {:receipt/schema "kotoba.ao.worker-intent-receipt.v1"
           :receipt/id "receipt-test"
           :receipt/worker "ao:etzhayyim:tamaki"
           :receipt/organization "etzhayyim"
           :receipt/intent "intent-test"
           :receipt/capability :intent/submit
           :receipt/status :completed
           :receipt/effect-status :succeeded
           :receipt/reason :effect-complete
           :receipt/evidence
           {:agent.run/id "run-safe"
            :agent.run/status :succeeded
            :private/output "must remain private"}
           :receipt/updated-at 4000}))
        (let [projected
              (first (:items
                      (organism-gateway/receipts
                       "ao:etzhayyim:tamaki")))]
          (is (= "succeeded" (:receipt/effect-status projected)))
          (is (= "run-safe" (get-in projected
                                    [:receipt/evidence :run-id])))
          (is (not (str/includes? (pr-str projected)
                                  "must remain private"))))))))

(deftest local-identity-registers-organization-owner-and-members-safely
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-identity-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (let [{:keys [token]} (local-identity/register!
                               {:organization-name "Example Org"
                                :domain "example.jp"
                                :display-name "Owner"
                                :email "owner@example.jp"})
              session (local-identity/session token)
              public (local-identity/public-state token)]
          (is (:registered? public))
          (is (:authenticated? public))
          (is (= "Example Org" (get-in public [:organization :name])))
          (is (= :owner (get-in public [:organization :role])))
          (is (= "Owner" (get-in public [:user :display-name])))
          (is (string? (:csrf public)))
          (is (not (str/includes? (pr-str (store/snapshot)) token)))
          (local-identity/add-user!
           session {:display-name "Member" :email "member@example.jp"
                    :role "member"})
          (is (= #{"Owner" "Member"}
                 (set (map :display-name
                           (get-in (local-identity/public-state token)
                                   [:organization :users])))))))
      (finally
        (reset! store/state previous)))))

(deftest passkey-first-registration-needs-no-profile-and-roots-a-user-did
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-passkey-first-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (let [{:keys [token]} (local-identity/register! {})
              session (local-identity/session token)
              public (local-identity/public-state token)
              x (byte-array (range 32))
              y-even (byte-array (repeat 32 2))
              y-odd (byte-array (concat (repeat 31 2) [3]))
              even-did (did/did-key-from-p256 x y-even)]
          (is (:registered? public))
          (is (:authenticated? public))
          (is (true? (:passkey-required? public)))
          (is (nil? (get-in public [:user :account-id])))
          (let [person-did (get-in public [:user :did])]
            (is (string? person-did))
            (is (str/starts-with? person-did "did:key:z"))
            (is (false? (get-in public [:organization :profile-complete?])))
            (is (str/starts-with? even-did "did:key:z"))
            (is (= even-did (did/did-key-from-p256 x y-even)))
            (is (not= even-did (did/did-key-from-p256 x y-odd)))
            (is (not= person-did even-did)
                "the person's DID is not the Passkey's P-256 did:key")
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"Passkey"
                 (local-identity/configure-organization!
                  session {:organization-id "example"})))
            (store/transact!
             (fn [state]
               (-> state
                   (assoc-in [:identity :users (:user-id session)
                              :passkey-enrolled?] true)
                   (assoc-in [:identity :passkeys "cred-test"]
                             {:id "cred-test" :credential-id "cred-test"
                              :user-id (:user-id session) :did even-did})
                   (update-in [:identity :sessions (:id session)]
                              merge passkey-session-options))))
            (local-identity/configure-organization!
             (local-identity/session token) {:organization-id "example"})
            (let [configured (local-identity/public-state token)]
              (is (= "example@cloud-itonami.app"
                     (get-in configured [:user :email])))
              (is (= person-did (get-in configured [:user :did]))
                  "enrolling a Passkey must not move the User DID")
              (is (= "example.cloud-itonami.app"
                     (get-in configured [:organization :domain])))
              (is (nil? (get-in configured [:organization :did])))
              (is (true?
                   (get-in configured
                           [:organization :profile-complete?])))))))
      (finally
        (reset! store/state previous)))))

(deftest legacy-provisional-owner-repairs-a-missing-webauthn-user-handle
  (let [previous @store/state
        captured (atom nil)]
    (try
      (reset! store/state
              (assoc (store/initial-state)
                     :identity
                     {:users {"user-1" {:id "user-1"
                                        :display-name "Owner"
                                        :status :pending-passkey
                                        :passkey-enrolled? false}}
                      :memberships {}
                      :passkeys {}}))
      (with-redefs [passkey/start-registration!
                    (fn [user _rp-id _origin]
                      (reset! captured user)
                      {:transaction-id "test-registration"})]
        (is (= "test-registration"
               (:transaction-id
                (local-identity/start-passkey-registration!
                 {:user-id "user-1"} "localhost"
                 "http://localhost:1338"))))
        (let [handle (:user-handle @captured)]
          (is (string? handle))
          (is (not (str/blank? handle)))
          (is (= handle
                 (get-in (store/snapshot)
                         [:identity :users "user-1" :user-handle])))))
      (finally
        (reset! store/state previous)))))

(deftest oauth-start-is-session-bound-pkce-and-secret-free-in-public-state
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-oauth-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name "GitHub" :configured? true
                       :client-id "client-id" :client-secret "client-secret"
                       :authorization-endpoint "https://github.com/login/oauth/authorize"
                       :scopes ["read:user"]})]
        (let [{:keys [token]} (local-identity/register!
                               {:organization-name "Example Org"
                                :domain "example.jp"
                                :display-name "Owner"
                                :email "owner@example.jp"})
              session (local-identity/session token)
              ;; `register!` now mints a DID immediately. Connecting an external
              ;; account still requires `may-act?` (a Passkey ceremony), so this
              ;; test — which is about PKCE/state binding and secret leakage —
              ;; enrols the owner the way a real one would before reaching
              ;; Connect.
              _ (store/transact!
                 (fn [state]
                   (-> state
                       (assoc-in [:identity :users (:user-id session)
                                  :passkey-enrolled?] true)
                       (update-in [:identity :sessions (:id session)]
                                  merge passkey-session-options))))
              result (local-identity/start-oauth!
                      (local-identity/session token)
                      :github "http://127.0.0.1:1338")
              persisted (pr-str (store/snapshot))
              public (pr-str (local-identity/public-state token))]
          (is (str/starts-with? (:url result)
                                "https://github.com/login/oauth/authorize?"))
          (is (str/includes? (:url result) "code_challenge="))
          (is (str/includes? (:url result) "state="))
          (is (str/includes? persisted ":verifier"))
          (is (not (str/includes? public "client-secret")))
          (is (not (str/includes? public ":verifier")))))
      (finally
        (reset! store/state previous)))))

(deftest managed-account-ids-are-canonical-and-enrollment-codes-are-secret
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-account-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))
                    local-identity/provider-config
                    (fn [provider]
                      {:provider provider :name (name provider)
                       :configured? false :scopes []})]
        (let [{:keys [token]} (local-identity/register!
                               {:organization-name "Example Org"
                                :organization-id "example"
                                :display-name "Owner"
                                :account-id "owner"
                                :contact-email "owner@example.jp"})
              session (local-identity/session token)
              resumed (local-identity/resume-owner-onboarding!)
              invitation (local-identity/add-user!
                          session {:display-name "Member"
                                   :account-id "member"
                                   :contact-email "member@example.jp"
                                   :role "member"})
              public (local-identity/public-state token)
              persisted (pr-str (store/snapshot))]
          (is (= "owner@cloud-itonami.app"
                 (get-in public [:user :email])))
          (is (true? (:passkey-required? public)))
          (is (string? (:token resumed)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"Passkey"
               (local-identity/require-passkey! session)))
          (is (= "example.cloud-itonami.app"
                 (get-in public [:organization :domain])))
          (is (= "member@cloud-itonami.app" (:email invitation)))
          (is (string? (:enrollment-code invitation)))
          (is (not (str/includes? persisted (:enrollment-code invitation))))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"無効"
               (local-identity/start-enrollment!
                "member" "wrong-code" "localhost"
                "http://localhost:1338")))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"既に"
               (local-identity/add-user!
                session {:display-name "Duplicate"
                         :account-id "member" :role "member"})))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"再開条件"
               (local-identity/resume-owner-onboarding!)))))
      (finally
        (reset! store/state previous)))))

(deftest passkey-ceremonies-require-discoverable-user-verification-and-are-single-use
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "Cloud Itonami-passkey-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config-loader/data-dir (fn [] (.toFile temporary))]
        (let [{:keys [token]} (local-identity/register!
                               {:organization-name "Example Org"
                                :organization-id "example"
                                :display-name "Owner"
                                :account-id "owner"})
              session (local-identity/session token)
              registration
              (local-identity/start-passkey-registration!
               session "localhost" "http://localhost:1338")
              assertion
              (local-identity/start-passkey-authentication!
               "localhost" "http://localhost:1338")
              transaction-id (:transaction-id registration)]
          (is (= "localhost"
                 (get-in registration [:options :publicKey :rp :id])))
          (is (= "required"
                 (get-in registration
                         [:options :publicKey :authenticatorSelection
                          :residentKey])))
          (is (= "required"
                 (get-in registration
                         [:options :publicKey :authenticatorSelection
                          :userVerification])))
          (is (= [-7]
                 (mapv :alg
                       (get-in registration
                               [:options :publicKey :pubKeyCredParams]))))
          (is (= "required"
                 (get-in assertion [:options :publicKey :userVerification])))
          (is (nil? (get-in assertion [:options :publicKey :allowCredentials])))
          (is (try
                (local-identity/finish-passkey-registration!
                 session transaction-id {:id "invalid"})
                false
                (catch Exception _ true)))
          (is (true?
               (get-in (store/snapshot)
                       [:identity :webauthn-transactions transaction-id :used?])))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"使用済み"
               (local-identity/finish-passkey-registration!
                session transaction-id {:id "invalid"})))))
      (finally
        (reset! store/state previous)))))

(deftest every-permission-a-bot-has-can-be-switched-on-from-the-screen
  ;; A capability with no control is a capability nobody can use. `:bot/peers?`
  ;; landed with `send_message` and without this checkbox, which made the tool
  ;; unreachable by the only person allowed to grant it.
  (let [html (web/page-html {})
        js (slurp (clojure.java.io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (not (str/includes? html "id=\"bots-writes\""))
        "authority flags do not burden Bot creation")
    (doseq [label ["ファイル・Git・接続先への書き込みを許可"
                   "おまかせモード"
                   "Bot専用の分離ブラウザーを許可"
                   "このBotにフォーカスを奪わないComputer Useを許可"
                   "ほかのBotとの書き置きを許可"
                   "この PC の Git workspace で coding する"
                   "隔離された仮想環境で汎用shellを使う"]]
      (is (str/includes? js label)
          (str label " has no control in the selected Bot settings")))))

(deftest machine-agent-settings-explain-and-gate-browser-and-computer
  (let [html (web/page-html {})
        js (slurp (clojure.java.io/file "resources/cloud/itonami/app/interaction.js"))]
    (doseq [id ["agent-machine-settings" "agent-machine-browser"
                "agent-machine-computer" "agent-machine-save"
                "agent-machine-domains" "agent-machine-prepare-computer"]]
      (is (str/includes? html (str "id=\"" id "\""))))
    (is (str/includes? html "各Botの設定で個別に許可"))
    (is (str/includes? html "座標クリックや合成キー入力は使いません"))
    (is (str/includes? js "/api/bots/machine"))
    (is (str/includes? js "computer-available?"))))

(deftest the-omakase-copy-describes-what-omakase-now-does
  ;; It read "shell・メール送信・Git変更" until 2026-08-19 — the three-effect
  ;; allowlist ADR-0060 deleted. The screen was telling the owner something
  ;; false about the switch they were being asked to turn on, which is worse
  ;; than saying nothing.
  (let [html (web/page-html {})
        js (slurp (clojure.java.io/file "resources/cloud/itonami/app/interaction.js"))]
    (is (not (str/includes? html "shell・メール送信・Git変更を待たずに実行"))
        "the screen still describes the allowlist that no longer exists")
    (is (str/includes? js "許可済みの操作を待たずに実行"))
    (is (str/includes? js "渡していないツールは、自分で承認しても使えません")
        "the ceiling is the part a person most needs told")))

(deftest bot-conversations-are-a-read-only-part-of-bots
  (let [html (web/page-html {})]
    (is (not (re-find #"data-view=\"rooms\"" html)))
    (is (not (re-find #"data-view-panel=\"rooms\"" html)))
    (doseq [id ["bots-conversations" "bots-conversations-panel"
                "bots-conversations-close" "room-list" "room-panel"
                "room-thread" "room-status" "rooms-count"]]
      (is (re-find (re-pattern (str "id=\"" id "\"")) html)
          (str id " is missing from the Bots conversation viewer")))
    (doseq [removed ["room-create-form" "room-send-form" "room-send"]]
      (is (not (re-find (re-pattern (str "id=\"" removed "\"")) html))
          (str removed " makes the read-only viewer writable")))
    (is (str/includes? html "表示専用です"))
    (is (str/includes? html "aria-label=\"Bot同士の会話を読む\""))
    (is (str/includes? html "aria-label=\"会社Botを常駐化\""))
    (is (str/includes? web/app-css
                       ".bots-titlebar__identity{display:none!important}"))))

(deftest the-bots-view-loads-its-read-only-conversations
  (is (not (str/includes? web/interaction-js "currentView === 'rooms'")))
  (is (str/includes? web/interaction-js "if (currentView === 'bots')"))
  (is (str/includes? web/interaction-js "setBotConversationsOpen"))
  (is (str/includes? web/interaction-js "loadRooms()")))

(deftest bots-is-the-only-conversation-destination-and-capture-lives-in-settings
  (let [html (web/page-html {})]
    (is (re-find #"data-view=\"bots\"[^>]*aria-current=\"page\"" html))
    (doseq [old ["chat" "rooms" "capture" "memory"]]
      (is (not (re-find (re-pattern (str "data-view=\"" old "\"")) html))))
    (is (re-find #"data-view-panel=\"settings\"" html))
    (is (re-find #"id=\"context-capture-settings\"" html))
    (is (str/includes? html "新しいUserでは既定でON"))
    (is (str/includes? web/interaction-js "chat:'bots', rooms:'bots', capture:'bots', memory:'settings'"))))
