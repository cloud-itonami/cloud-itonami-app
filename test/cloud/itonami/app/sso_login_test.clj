(ns cloud.itonami.app.sso-login-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(def ^:private configuration
  {:auth {:allow-signup? true
          :sso-providers [:google :microsoft :github]}
   :email-login {:enabled? false}})

(defn- fake-provider [provider]
  {:provider provider :name (str/capitalize (name provider))
   :client-id (str (name provider) "-client") :client-secret "secret"
   :configured? true
   :authorization-endpoint (str "https://auth.example/" (name provider))
   :token-endpoint "https://auth.example/token"
   :profile-endpoint "https://auth.example/profile"})

(defn- transaction-state []
  (->> (get-in (store/snapshot) [:identity :sso-transactions])
       (keep (fn [[state transaction]] (when-not (:used? transaction) state)))
       first))

(defn- finish! [provider profile]
  (let [state (transaction-state)]
    (with-redefs-fn
      {#'cloud.itonami.app.identity/exchange-code!
       (fn [_ _ _] {:access_token "access"})
       #'cloud.itonami.app.identity/profile!
       (fn [_ _] profile)}
      #(identity/complete-sso-authentication!
        provider {:state state :code "code"}))))

(deftest sso-signup-is-minimal-stable-and-does-not-auto-merge-by-email
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [identity/provider-config fake-provider]
        (identity/configure! configuration)
        (testing "authentication scopes do not inherit connector access"
          (let [started (identity/start-sso-authentication!
                         :github "http://localhost:1338"
                         {:mode :authenticate})]
            (is (str/includes? (:url started) "read%3Auser"))
            (is (not (str/includes? (:url started) "repo")))
            (is (not (str/includes? (:url started) "mail.google.com")))
            (let [finished (finish! :github
                                    {:id 42 :login "new-person"
                                     :email "new@example.jp"})
                  public (identity/public-state (:token finished))]
              (is (true? (:may-act? public)))
              (is (= :github (get-in public [:session :authn-provider])))
              (is (= :sso (get-in public [:session :issued-via])))
              (is (= "new@example.jp" (get-in public [:user :contact-email])))
              (is (= "personal" (get-in public [:organization :kind]))))))
        (testing "the same provider subject resolves to the same User"
          (let [before (count (get-in (store/snapshot) [:identity :users]))]
            (identity/start-sso-authentication!
             :github "http://localhost:1338" {:mode :authenticate})
            (finish! :github {:id 42 :login "new-person"
                              :email "new@example.jp"})
            (is (= before (count (get-in (store/snapshot) [:identity :users]))))))
        (testing "matching email alone requires an explicit authenticated link"
          (identity/start-sso-authentication!
           :google "http://localhost:1338" {:mode :authenticate})
          (try
            (finish! :google {:sub "different-provider-subject"
                              :email "new@example.jp"})
            (is false "email equality must not merge accounts")
            (catch clojure.lang.ExceptionInfo error
              (is (= :sso/link-required (:type (ex-data error))))))))
      (finally
        (reset! store/state previous)))))
