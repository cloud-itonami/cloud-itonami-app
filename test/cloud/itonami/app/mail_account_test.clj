(ns cloud.itonami.app.mail-account-test
  "Several mailboxes, of several kinds, belonging to several people.

  The property under test throughout is that the unit is an *account*. Every
  bug this replaces came from the unit being a provider: one slot per
  provider per person meant a second Gmail overwrote the first, one archive
  directory per provider meant two mailboxes' messages collided by id, and
  one error per provider meant 'Google is broken' when one of two Google
  mailboxes was fine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-sync :as mail-sync]
            [cloud.itonami.app.store :as store]))

(use-fixtures
  :each
  (fn [run]
    (let [before @store/state]
      (try
        ;; A clean store, not merely a restored one. `store/state` is loaded
        ;; from `target/test-data/state.edn` at namespace load, so whatever a
        ;; previous run persisted — a Microsoft connection, in the run that
        ;; caught this — is present before the first assertion. These tests
        ;; count mailboxes, so an inherited connection is an inherited
        ;; mailbox and the counts are off by however many somebody else left.
        (reset! store/state (store/initial-state))
        ;; ...and writes stay in memory, so this suite does not persist the
        ;; state that would then leak into the next one.
        (with-redefs [store/transact! (fn [f & args]
                                        (apply swap! store/state f args))]
          ;; No delegated credentials unless a test names one.
          (account/configure! {})
          (run))
        (finally (reset! store/state before))))))

(defn- put-connection!
  "An OAuth connection as `identity/complete-oauth!` writes one."
  [{:keys [did provider subject email]}]
  (let [id (str "org-1:" did ":" (name provider) ":" subject)]
    (store/transact!
     assoc-in [:identity :connections id]
     {:id id :provider provider :status :connected
      :organization-id "org-1" :user-id "user-1" :user-did did
      :provider-subject subject :email email :display-name email})
    id))

;; ---------------------------------------------------------------------------

(deftest one-person-can-connect-two-gmail-accounts
  (testing "the case that was structurally impossible: connection ids used to
            be {org}:{did}:{provider}, so the second Google account landed on
            the first one's key and replaced it"
    (put-connection! {:did "did:key:alice" :provider :google
                      :subject "1001" :email "alice@work.example"})
    (put-connection! {:did "did:key:alice" :provider :google
                      :subject "1002" :email "alice@personal.example"})
    (let [accounts (account/accounts "did:key:alice")]
      (is (= 2 (count accounts)))
      (is (= #{"alice@work.example" "alice@personal.example"}
             (set (map :address accounts))))
      (testing "and they are distinct accounts, not one shadowing the other"
        (is (= 2 (count (set (map :id accounts)))))))))

(deftest an-account-is-named-by-the-providers-subject-not-by-its-address
  (testing "an address can be reassigned and an alias can deliver to a mailbox
            whose primary address is something else; either would re-point a
            cursor at a different mailbox without the id changing"
    (put-connection! {:did "did:key:bob" :provider :google
                      :subject "2001" :email "bob@example.com"})
    (is (= "gmail:2001" (:id (first (account/accounts "did:key:bob")))))))

(deftest accounts-are-narrowed-to-the-person-asking
  (put-connection! {:did "did:key:alice" :provider :google
                    :subject "3001" :email "alice@example.com"})
  (put-connection! {:did "did:key:bob" :provider :google
                    :subject "3002" :email "bob@example.com"})
  (is (= ["alice@example.com"] (map :address (account/accounts "did:key:alice"))))
  (is (= ["bob@example.com"] (map :address (account/accounts "did:key:bob"))))
  (testing "and asking without naming anybody sees the deployment"
    (is (= 2 (count (account/accounts))))))

(deftest a-github-connection-is-not-a-mailbox
  (testing "walking providers rather than mail kinds is how a source-control
            grant ends up listed as an inbox"
    (put-connection! {:did "did:key:alice" :provider :github
                      :subject "4001" :email "alice@example.com"})
    (is (empty? (account/accounts "did:key:alice")))))

(deftest disconnecting-an-oauth-grant-takes-its-mailbox-away
  (testing "an OAuth account is derived from the connection rather than copied
            out of it, so there is no stale record to leave behind"
    (let [id (put-connection! {:did "did:key:alice" :provider :google
                               :subject "5001" :email "alice@example.com"})]
      (is (= 1 (count (account/accounts "did:key:alice"))))
      (store/transact! update-in [:identity :connections] dissoc id)
      (is (empty? (account/accounts "did:key:alice"))))))

(deftest sync-state-for-a-disconnected-mailbox-is-not-listed-as-a-mailbox
  (testing "an OAuth account's cursor is stored under [:mail :accounts] like
            everything else's; if that entry were read back as an account,
            disconnecting Google would leave its mailbox in the list forever"
    (store/transact! assoc-in [:mail :accounts "gmail:6001"]
                     {:id "gmail:6001" :kind :gmail
                      :sync {:status :ready :cursor {:history-id "9"}}})
    (is (empty? (account/accounts)))))

;; ---------------------------------------------------------------------------
;; IMAP accounts

(def ^:private imap-request
  {:address "me@example.com"
   :host "imap.example.com"
   :password "app-password"
   :display-name "個人メール"})

(deftest an-imap-account-defaults-its-ports-and-its-smtp-identity
  (with-redefs [account/password (constantly "app-password")]
    (let [added (account/add-imap-account! imap-request {:user-did "did:key:alice"})]
      (is (= :imap (:kind added)))
      (is (= 993 (get-in added [:imap :port])) "IMAPS")
      (is (= 465 (get-in added [:smtp :port])) "SMTP submission over implicit TLS")
      (is (= "imap.example.com" (get-in added [:smtp :host]))
          "the same host unless a deployment says otherwise")
      (is (= "me@example.com" (get-in added [:imap :username]))
          "the address, when no separate username was given"))))

(deftest re-registering-an-imap-account-keeps-its-cursor
  (testing "changing a password or a host must not throw away the position the
            mailbox is synced to and re-download it"
    (with-redefs [account/password (constantly "app-password")]
      (account/add-imap-account! imap-request {:user-did "did:key:alice"})
      (let [id (:id (first (account/accounts "did:key:alice")))]
        (account/record-sync! id {:cursor {:highest-uid 42}})
        (account/add-imap-account! (assoc imap-request :password "new-password")
                                   {:user-did "did:key:alice"})
        (is (= 42 (:highest-uid (account/cursor id))))))))

(deftest an-oauth-account-cannot-be-removed-as-if-it-were-a-declared-one
  (testing "it exists because a grant exists; removing it here would leave the
            connection live and the mailbox merely hidden"
    (put-connection! {:did "did:key:alice" :provider :google
                      :subject "7001" :email "alice@example.com"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (account/remove-account! "gmail:7001")))))

;; ---------------------------------------------------------------------------

(deftest what-is-handed-out-never-contains-a-credential
  (testing "this list is served over HTTP: a password reference or a token
            reference reaching it is that secret leaving the machine"
    (with-redefs [account/password (constantly "app-password")]
      (account/add-imap-account! imap-request {:user-did "did:key:alice"}))
    (put-connection! {:did "did:key:alice" :provider :google
                      :subject "8001" :email "alice@example.com"})
    (doseq [public (map account/public-account (account/accounts "did:key:alice"))]
      (is (nil? (:password-ref public)))
      (is (nil? (:connection-id public)))
      (is (nil? (:delegated-credential public)))
      (is (not (str/includes? (str/lower-case (pr-str public)) "app-password"))
          "not anywhere in it, under any key"))))

(deftest a-mailbox-that-never-synced-does-not-look-like-one-that-is-failing
  (put-connection! {:did "did:key:alice" :provider :google
                    :subject "9001" :email "alice@example.com"})
  (let [public (account/public-account (first (account/accounts "did:key:alice")))]
    (is (= :never-synced (:status public)))
    (is (nil? (get-in public [:sync :last-error])))))

(deftest an-error-is-recorded-against-one-mailbox-and-keeps-its-cursor
  (testing "a failed sync must not lose the position a later one resumes from"
    (store/transact! assoc-in [:mail :accounts "gmail:1" :sync]
                     {:status :ready :cursor {:history-id "77"}})
    (account/record-error! "gmail:1" (Exception. "grant expired"))
    (is (= "77" (:history-id (account/cursor "gmail:1")))
        "still there, so the mailbox resumes rather than re-reading")
    (is (= :error (get-in (store/snapshot)
                          [:mail :accounts "gmail:1" :sync :status])))))

(deftest a-synced-message-id-is-qualified-by-account
  (testing "Gmail ids are unique within a mailbox and not across mailboxes, so
            `google:<id>` collided silently between two Google accounts —
            which message survived depended on the order they synced in"
    (is (not= (mail-sync/message-id "gmail:1001" "msg-abc")
              (mail-sync/message-id "gmail:1002" "msg-abc")))))
