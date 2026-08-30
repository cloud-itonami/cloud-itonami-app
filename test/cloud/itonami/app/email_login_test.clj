(ns cloud.itonami.app.email-login-test
  "Email delivery may remain as migration code, but it is not an application
  sign-in root. HTTP closure is exercised with the other legacy routes in
  central-auth-http-test; these tests keep shipped configuration fail-closed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(defn- profile [name]
  (edn/read-string (slurp (io/file "profiles" (str name ".edn")))))

(deftest shipped-profiles-do-not-enable-email-signin
  (let [defaults (edn/read-string
                  (slurp (io/resource "cloud-itonami-app.defaults.edn")))
        itonami (profile "itonami")]
    (is (false? (get-in defaults [:email-login :enabled?])))
    (is (nil? (:email-login itonami)))
    (is (nil? (get-in itonami [:auth :sso-enabled?])))
    (is (nil? (get-in itonami [:auth :sso-providers])))))

(deftest old-email-configuration-cannot-be-advertised-as-signin
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (identity/configure!
       {:email-login {:enabled? true
                      :delivery-endpoint "https://mailer.example.test/login"
                      :access-token-env "PATH"}})
      (testing "the application contract remains Passkey-only"
        (is (false? (get-in (identity/public-auth-methods)
                            [:email :configured?])))
        (is (false? (:email-login-configured?
                     (identity/public-state nil)))))
      (finally
        (reset! store/state previous)
        (identity/configure! {})))))
