(ns cloud.itonami.app.domain-tools-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.agent-session :as agent-session]
            [cloud.itonami.app.authority.domain :as authority-domain]
            [cloud.itonami.app.cloudflare :as cloudflare]
            [cloud.itonami.app.domain-tools :as tools]
            [cloud.itonami.app.identity :as identity]
            [yadori.cloudflare :as yadori]))

(def configuration
  {:domain-service {:account-id-env "CF_ACCOUNT" :api-token-env "CF_TOKEN"}
   :authorities {:domain {:enabled? true}}})

(defn environment [name]
  ({"CF_ACCOUNT" "acct" "CF_TOKEN" "secret-token"} name))

(deftest cloudflare-host-builds-authenticated-json-without-returning-token
  (let [seen (atom nil)]
    (binding [cloudflare/*environment* environment
              cloudflare/*send!* (fn [request]
                                   (reset! seen request)
                                   {:status 200 :body (json/write-str {:success true :result {:ok true}})})]
      (is (= {:success true :result {:ok true}}
             (cloudflare/request! configuration
                                  (yadori/check-request "acct" ["example.com"]))))
      (is (= "POST" (.method @seen)))
      (is (= "https://api.cloudflare.com/client/v4/accounts/acct/registrar/domain-check"
             (str (.uri @seen))))
      (is (= ["Bearer secret-token"]
             (vec (.allValues (.headers @seen) "Authorization")))))))

(deftest domain-surface-is-dark-without-every-gate
  (with-redefs [cloudflare/available? (constantly true)
                agent-session/session-token (constantly "agent-token")
                identity/session (constantly {:kind :agent :user-id "u1"})
                identity/may-act? (constantly true)]
    (is (false? (tools/available? (assoc-in configuration [:authorities :domain :enabled?] false))))
    (is (true? (tools/available? configuration))))
  (with-redefs [cloudflare/available? (constantly false)
                agent-session/session-token (constantly "agent-token")
                identity/session (constantly {:kind :agent :user-id "u1"})
                identity/may-act? (constantly true)]
    (is (false? (tools/available? configuration)))))

(deftest domain-surface-refuses-a-browser-session-in-the-agent-token-slot
  (with-redefs [cloudflare/available? (constantly true)
                agent-session/session-token (constantly "wrong-kind")
                identity/session (constantly {:kind :passkey :user-id "u1"})
                identity/may-act? (constantly true)]
    (is (false? (tools/available? configuration)))))

(deftest server-overwrites-a-client-supplied-registration-quote
  (let [official {:domains [{:name "agent-home.dev" :registrable true :tier "standard"
                             :pricing {:currency "USD" :registration_cost "10.11"
                                       :renewal_cost "10.11"}}]}]
    (with-redefs [cloudflare/account-id (constantly "acct")
                  cloudflare/request! (fn [_ request]
                                        (is (str/ends-with? (:path request) "/domain-check"))
                                        {:success true :result official})]
      (let [facts (authority-domain/with-server-facts
                   configuration {:op :domain/register :domain "agent-home.dev"
                                  :quote {:registration-cost "0.01"}})]
        (is (= "10.11" (get-in facts [:quote :registration-cost])))
        (is (= "10.11" (get-in facts [:quote :renewal-cost])))))))

(deftest dns-delete-material-binds-the-exact-record
  (let [value (authority-domain/pre-check
               configuration {} {:op :dns/delete :zone-id "z1" :record-id "r1"})]
    (is (= {:op :dns/delete :zone-id "z1" :record-id "r1"} value))
    (is (= "yadori-dns/v1|op=delete|zone=z1|record=r1"
           (authority-domain/material value)))))

(deftest descriptors-separate-read-from-passkey-bound-writes
  (let [by-name (into {} (map (juxt :name identity)) tools/tools)]
    (is (= ["domains"] (get-in by-name ["domain_check" :parameters :required])))
    (is (= ["zone_id"] (get-in by-name ["domain_dns_records" :parameters :required])))
    (is (str/includes? (get-in by-name ["domain_registration_review" :description])
                       "Passkey"))
    (is (str/includes? (get-in by-name ["domain_commit" :description])
                       "approved"))))
