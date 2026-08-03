(ns cloud.itonami.app.mail-sync-test
  "The auth seam, which is the whole of what was missing.

  Everything downstream of a token — Gmail's history cursor, the label
  mapping, the local classifier — was already written and already right. What
  did not exist was a way to get a token that outlives the hour after somebody
  clicked Connect, and a way to say which credential this application is
  allowed to use when it holds none of its own. Those are what these tests
  pin, because those are what a person's mailbox now hangs on."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-sync :as mail-sync]))

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
    (is (not (contains? (mail-sync/syncable-providers) :google))
        "a workspace that was merely installed must not be reading mail"))
  (testing "a half-named credential is not a credential"
    (is (nil? (identity/delegated-access-token!
               :google {:client-service "google-oauth-client"
                        :client-account "someone"}))
        "missing refresh-service/account must not fall back to a search"))
  (testing "naming one makes exactly that provider syncable"
    (mail-sync/start!
     {:mail-sync
      {:providers {:google {:delegated-credential
                            {:client-service "example-oauth-client"
                             :client-account "example"
                             :refresh-service "example-oauth:nobody"
                             :refresh-account "nobody@example.com"}}}}})
    (let [syncable (mail-sync/syncable-providers)]
      (is (contains? syncable :google))
      (is (not (contains? syncable :microsoft))
          "naming Google's credential says nothing about Microsoft's")))
  (mail-sync/start! {}))

(deftest sync-is-off-until-asked-for
  (testing "configuration alone does not start the loop"
    (mail-sync/stop!)
    (is (false? (mail-sync/start! {:mail-sync {:providers {}}})))
    (is (false? (:enabled? (mail-sync/status)))))
  (testing "enabling it starts the loop, and stop! ends it"
    (is (true? (mail-sync/start! {:mail-sync {:enabled? true
                                              :interval-seconds 3600}})))
    (is (true? (:enabled? (mail-sync/status))))
    (mail-sync/stop!)
    (is (false? (:enabled? (mail-sync/status))))))

(deftest status-separates-never-synced-from-broken
  (testing "a provider nobody configured is reported as such, not as empty"
    (mail-sync/start! {})
    (let [google (get-in (mail-sync/status) [:providers :google])]
      (is (false? (:configured? google)))
      (is (false? (:delegated? google)))
      (is (= :never-synced (:status google))
          "an inbox that was never synced and one that is failing must not
           look the same to somebody asking whether mail is arriving")
      (is (nil? (:last-error google))))))

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
          google (get-in result [:providers :google])]
      (is (some? google) "a configured provider must appear in the result")
      (is (string? (:error google))
          "the rejection is carried out, not dropped"))
    (mail-sync/start! {})))
