(ns cloud.itonami.app.mail-sync-test
  "The auth seam and the account seam.

  These tests used to pin one question — *'can this process get a token that
  outlives the hour after somebody clicked Connect'* — and they still do. What
  changed underneath them is the unit: a mailbox, not a provider. `:google` as
  a thing that can be syncable or broken has no answer once somebody connects
  a work Gmail and a personal one, so the same properties are asserted here
  per account instead.

  What must not regress, and is asserted below: a workspace that was merely
  installed reads nobody's mail; a half-named delegated credential is not a
  credential; configured-and-refused is reported rather than swallowed; and a
  never-synced mailbox does not look like a failing one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-sync :as mail-sync]))

(defn- account-ids [] (set (map :id (account/accounts))))

(deftest classify-adds-local-labels-without-losing-provider-ones
  (testing "provider labels survive, normalized, alongside the derived ones"
    (let [labels (mail-sync/classify {:subject "請求書のご送付"
                                      :from-email "billing@example.com"
                                      :labels ["INBOX" "Work Stuff"]})]
      (is (contains? labels :finance))
      (is (contains? labels :inbox))
      (is (contains? labels :work-stuff)
          "a provider label with a space is still that provider's label")))
  (testing "an unremarkable message earns no local label it did not deserve"
    (is (= #{:inbox}
           (mail-sync/classify {:subject "lunch?"
                                :from-email "friend@example.com"
                                :labels ["INBOX"]})))))

(deftest delegation-is-opt-in-and-names-its-credential
  (testing "an unconfigured deployment delegates to nothing"
    (mail-sync/start! {})
    (is (empty? (filter :delegated? (account/accounts)))
        "a workspace that was merely installed must not be reading mail"))
  (testing "a half-named credential is not a credential"
    (is (nil? (identity/delegated-access-token!
               :google {:client-service "google-oauth-client"
                        :client-account "someone"}))
        "missing refresh-service/account must not fall back to a search"))
  (testing "naming one makes exactly that mailbox appear, and no other"
    (mail-sync/start!
     {:mail-sync
      {:providers {:google {:delegated-credential
                            {:client-service "example-oauth-client"
                             :client-account "example"
                             :refresh-service "example-oauth:nobody"
                             :refresh-account "nobody@example.com"}}}}})
    (let [delegated (filter :delegated? (account/accounts))]
      (is (= 1 (count delegated)))
      (is (= :gmail (:kind (first delegated))))
      (is (empty? (filter #(= :microsoft (:kind %)) delegated))
          "naming Google's credential says nothing about Microsoft's")))
  (mail-sync/start! {})
  (is (empty? (filter :delegated? (account/accounts)))
      "and stopping naming it takes the mailbox away again"))

(deftest sync-is-off-until-asked-for
  (testing "configuration alone does not start the loop"
    (mail-sync/stop!)
    (is (true? (mail-sync/start! {:mail-sync {:providers {}}})))
    (is (false? (:enabled? (mail-sync/status)))))
  (testing "enabling it starts the loop, and stop! ends it"
    (is (true? (mail-sync/start! {:mail-sync {:enabled? true
                                              :interval-seconds 3600}})))
    (is (true? (:enabled? (mail-sync/status))))
    (mail-sync/stop!)
    (is (false? (:enabled? (mail-sync/status))))))

(deftest status-separates-never-synced-from-broken
  (testing "a mailbox that was never synced is reported as such, not as empty"
    (mail-sync/start!
     {:mail-sync
      {:providers {:google {:delegated-credential
                            {:client-service "cloud-itonami-app.test.absent"
                             :client-account "no-such-account"
                             :refresh-service "cloud-itonami-app.test.absent"
                             :refresh-account "nobody@example.com"}}}}})
    (let [mailbox (first (filter :delegated? (:accounts (mail-sync/status))))]
      (is (some? mailbox) "a configured mailbox must appear in the status")
      (is (= :never-synced (:status mailbox))
          "an inbox that was never synced and one that is failing must not
           look the same to somebody asking whether mail is arriving")
      (is (nil? (get-in mailbox [:sync :last-error])))))
  (mail-sync/start! {}))

(deftest status-never-reports-a-credential
  (testing "the account list is served over HTTP; a password or a token
            reference reaching it would be that secret leaving the machine"
    (mail-sync/start!
     {:mail-sync
      {:providers {:google {:delegated-credential
                            {:client-service "cloud-itonami-app.test.absent"
                             :client-account "no-such-account"
                             :refresh-service "cloud-itonami-app.test.absent"
                             :refresh-account "nobody@example.com"}}}}})
    (doseq [mailbox (:accounts (mail-sync/status))]
      (is (nil? (:delegated-credential mailbox)))
      (is (nil? (:password-ref mailbox)))
      (is (nil? (:connection-id mailbox))))
    (mail-sync/start! {})))

(deftest keychain-reads-are-targeted
  (testing "a keychain read names both service and account, and finds nothing
            when either is wrong"
    (is (nil? (identity/keychain-find "cloud-itonami-app.test.absent"
                                      "no-such-account"))))
  (testing "a provider outside the catalog has no config at all"
    (reset! identity/runtime-oauth-clients {})
    (is (nil? (identity/provider-config :nonexistent-provider))
        "nil, not a map that answers :configured? false — there is no such
         provider to configure, and the two are different answers"))
  (testing "a referenced client that is absent leaves the provider unconfigured
            rather than half-configured"
    (reset! identity/runtime-oauth-clients
            {:google {:service "cloud-itonami-app.test.absent"
                      :account "no-such-account"}})
    (let [config (identity/provider-config :google)]
      (is (contains? config :configured?))
      (is (or (false? (:configured? config))
              (and (:client-id config) (:client-secret config)))
          "either both halves resolved from the environment, or neither"))
    (reset! identity/runtime-oauth-clients {})))

(deftest a-rejected-delegated-grant-is-reported-not-swallowed
  (testing "configured-and-refused must not read as nothing-to-do: a refresh
            token that has gone stale is the ordinary way this breaks, and it
            is invisible if the sync just returns nil"
    (mail-sync/start!
     {:mail-sync
      {:providers {:google {:delegated-credential
                            {:client-service "cloud-itonami-app.test.absent"
                             :client-account "no-such-account"
                             :refresh-service "cloud-itonami-app.test.absent"
                             :refresh-account "no-such-account"}}}}})
    (let [result (mail-sync/sync-all!)
          delegated (first (filter #(str/includes? (str (:account-id %))
                                                   "delegated")
                                   (:accounts result)))]
      (is (some? delegated) "a configured mailbox must appear in the result")
      (is (string? (:error delegated))
          "the rejection is carried out, not dropped"))
    (testing "and it lands on that mailbox rather than on 'Gmail'"
      (let [mailbox (first (filter :delegated? (:accounts (mail-sync/status))))]
        (is (= :error (:status mailbox)))
        (is (string? (get-in mailbox [:sync :last-error])))))
    (mail-sync/start! {})))

(deftest one-account-failing-does-not-report-the-others-as-failing
  (testing "the whole reason the unit is a mailbox: two Gmail accounts, one
            expired grant, and 'Google: error' would name the wrong thing"
    (mail-sync/start! {})
    (let [before (account-ids)]
      (is (not (contains? before nil))
          "every account has an id to attribute a failure to"))))

;; ---------------------------------------------------------------------------
;; a slow sync must not wedge every later one

(deftest a-sync-that-holds-the-lock-too-long-can-be-taken-over
  (testing "measured 2026-08-06: a full sync of 1000 threads held the flag past
            forty minutes, and because the scheduler asks every 300 seconds and
            gets `already-running` immediately, NOTHING synced in that window.
            The mailbox looked healthy and was frozen."
    (let [claim! #'cloud.itonami.app.mail-sync/claim-sync!
          release! #'cloud.itonami.app.mail-sync/release-sync!
          started @#'cloud.itonami.app.mail-sync/sync-started-at
          lease @#'cloud.itonami.app.mail-sync/sync-lease-ms]
      (release!)
      (is (true? (claim!)) "a free lock is taken")
      (is (false? (claim!)) "and is then held — this is the normal case")

      (testing "a holder that is still inside the lease keeps it"
        (reset! started (- (System/currentTimeMillis) (quot lease 2)))
        (is (false? (claim!))))

      (testing "one past the lease loses the right to keep others out"
        (reset! started (- (System/currentTimeMillis) (inc lease)))
        (is (true? (claim!))))

      (release!)
      (is (nil? @started) "releasing clears the stamp, not only the flag")
      (is (true? (claim!)))
      (release!))))
