(ns cloud.itonami.app.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.app :as app]
            [cloud.itonami.app.config :as config-loader]
            [cloud.itonami.app.did :as did]
            [cloud.itonami.app.identity :as local-identity]
            [cloud.itonami.app.policy :as policy]
            [cloud.itonami.app.service :as service]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.provider :as provider]
            [cloud.itonami.app.web :as web]
            [cloud.itonami.app.workspace :as workspace]))

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
      (is (re-find #"Passkey 登録が必須" html))
      (is (re-find #"id=\"connector-list\"" html))
      (is (re-find #"id=\"member-form\"" html))
      (is (re-find #"color-scheme\" content=\"light\"" html))
      (is (re-find #"id=\"request-status\"[^>]*role=\"status\"" html))
      (doseq [view ["Inbox" "Projects" "Drive" "Scheduler"]]
        (is (re-find (re-pattern (str ">" view "<")) html)))
      (is (re-find #"data-view-panel=\"scheduler\"" html)))))

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
          (is (nil? (get-in public [:user :did])))
          (is (false? (get-in public [:organization :profile-complete?])))
          (is (str/starts-with? even-did "did:key:z"))
          (is (= even-did (did/did-key-from-p256 x y-even)))
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
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"使用済み"
               (local-identity/finish-passkey-registration!
                session transaction-id {:id "invalid"})))))
      (finally
        (reset! store/state previous)))))
