(ns cloud.itonami.app.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.app :as app]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.organism-gateway :as organism-gateway]
            [cloud.itonami.app.organism-worker :as organism-worker]
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
   :providers [{:id "ollama" :kind :ollama :local? true :enabled? true}
               {:id "cloud" :kind :openai-compatible
                :local? false :enabled? true}]})

(deftest local-first-policy-is-fail-closed
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

(deftest shell-surface-is-a-local-kotoba-dom-program
  (with-redefs [config-loader/load-config (constantly config)
                store/snapshot (constantly (store/initial-state))]
    (let [ops (:kotoba.app/surface-ops (app/start))
          attrs (filter #(= :dom/set-attr (first %)) ops)]
      (is (seq ops))
      (is (= [:dom/set-root 1] (last ops)))
      (is (some #(and (= :data-action (nth % 2 nil))
                      (= "local/chat" (nth % 3 nil)))
                attrs))
      (is (not-any? #(= :data-endpoint (nth % 2 nil)) attrs)))))

(deftest web-surface-uses-jp-go-digital-design-system
  (with-redefs [store/snapshot (constantly (store/initial-state))]
    (let [html (web/page-html config)]
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
      (is (re-find #"data-view-panel=\"settings\"" html))
      (is (re-find #"id=\"registration-form\"" html))
      (is (re-find #"id=\"passkey-gate-notice\"" html))
      (is (re-find #"id=\"passkey-gate-status\"" html))
      (is (re-find #"Passkey 登録が必須" html))
      (is (re-find #"Passkey 確認待ち" html))
      (is (re-find #"Touch IDまたはブラウザのPasskey画面" html))
      (is (re-find #"NotAllowedError" html))
      (is (re-find #"id=\"connector-list\"" html))
      (is (re-find #"id=\"member-form\"" html))
      (is (re-find #"data-view-panel=\"worker\"" html))
      (is (re-find #"id=\"worker-form\"" html))
      (is (re-find #"id=\"worker-prompt\"" html))
      (is (re-find #"id=\"worker-list\"" html))
      (is (re-find #"id=\"worker-detail\"" html))
      (is (re-find #"id=\"worker-count\"" html))
      (is (re-find #"color-scheme\" content=\"light\"" html))
      (is (re-find #"id=\"request-status\"[^>]*role=\"status\"" html))
      (doseq [view ["Worker" "Inbox" "Projects" "Drive" "Scheduler"]]
        (is (re-find (re-pattern (str ">" view "<")) html)))
      (is (re-find #"data-view-panel=\"scheduler\"" html)))))

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
        (let [{:keys [token]} (local-identity/issue-session! "user-1")
              session (local-identity/session token)]
          (local-identity/create-organization!
           session {:organization-id "etzhayyim"
                    :organization-name "Etzhayyim"})
          (let [before (local-identity/public-state token)
                etzhayyim (some #(when (= "etzhayyim" (:organization-id %)) %)
                                (:organizations before))]
            (is (= 2 (count (:organizations before))))
            (is (= 1 (count (filter :active? (:organizations before)))))
            (local-identity/switch-organization!
             (local-identity/session token)
             {:organization-id (:id etzhayyim)})
            (let [after (local-identity/public-state token)]
              (is (= "etzhayyim"
                     (get-in after [:organization :organization-id])))
              (is (= (:id etzhayyim) (:active-organization-id after)))
              (is (= #{"personal" "etzhayyim"}
                     (set (map :organization-id (:organizations after)))))))))
      (finally
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
                           (local-identity/issue-session! "user-owner"))
              member-token (:token
                            (local-identity/issue-session! "user-member"))
              invitation
              (local-identity/add-user!
               (local-identity/session owner-token)
               {:display-name "Member" :account-id "member" :role "member"})
              code (:invitation-code invitation)]
          (is (= :organization-invitation (:kind invitation)))
          (is (string? code))
          (is (not (str/includes? (pr-str (store/snapshot)) code)))
          (is (= 1
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
            (is (= 2 (count (:organizations accepted))))
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
              even-did (did/did-key-from-p256 x y-even)
              cose (doto (java.util.HashMap.)
                     (.put (int 1) (int 2))
                     (.put (int 3) (int -7))
                     (.put (int -1) (int 1))
                     (.put (int -2) x)
                     (.put (int -3) y-even))
              mapper
              (com.fasterxml.jackson.databind.ObjectMapper.
               (com.fasterxml.jackson.dataformat.cbor.CBORFactory.))
              encoded-cose
              (.encodeToString
               (.withoutPadding (java.util.Base64/getUrlEncoder))
               (.writeValueAsBytes mapper cose))]
          (is (:registered? public))
          (is (:authenticated? public))
          (is (true? (:passkey-required? public)))
          (is (nil? (get-in public [:user :account-id])))
          (is (nil? (get-in public [:user :did])))
          (is (false? (get-in public [:organization :profile-complete?])))
          (is (str/starts-with? even-did "did:key:z"))
          (is (= even-did (did/did-key-from-p256 x y-even)))
          (is (= even-did (did/did-key-from-cose encoded-cose)))
          (is (not= even-did (did/did-key-from-p256 x y-odd)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"Passkey"
               (local-identity/configure-organization!
                session {:organization-id "example"})))
          (store/transact!
           (fn [state]
             (-> state
                 (assoc-in [:identity :users (:user-id session)
                            :passkey-enrolled?] true)
                 (assoc-in [:identity :users (:user-id session) :did]
                           even-did))))
          (local-identity/configure-organization!
           session {:organization-id "example"})
          (let [configured (local-identity/public-state token)]
            (is (= "example@cloud-itonami.app"
                   (get-in configured [:user :email])))
            (is (= even-did (get-in configured [:user :did])))
            (is (= "example.cloud-itonami.app"
                   (get-in configured [:organization :domain])))
            (is (nil? (get-in configured [:organization :did])))
            (is (true?
                 (get-in configured
                         [:organization :profile-complete?]))))))
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
              result (local-identity/start-oauth!
                      session :github "http://127.0.0.1:1338")
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
          (is (= :failed
                 (get-in (store/snapshot)
                         [:identity :webauthn-transactions transaction-id
                          :status])))
          (is (= :passkey/registration-failed
                 (:type (last (:events (store/snapshot))))))
          (is (nil?
               (get (last (:events (store/snapshot))) :credential)))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"使用済み"
               (local-identity/finish-passkey-registration!
                session transaction-id {:id "invalid"})))))
      (finally
        (reset! store/state previous)))))
