(ns cloud.itonami.app.browser-handoff-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.store :as store]))

(defn- reset-state! [run]
  (store/transact!
   (constantly
    (-> (store/initial-state)
        (assoc-in [:identity :users "user-1"] {:id "user-1"})
        (assoc-in [:identity :organizations "org-1"] {:id "org-1"})
        (assoc-in [:identity :memberships "membership-1"]
                  {:id "membership-1" :user-id "user-1"
                   :organization-id "org-1" :role :owner}))))
  (reset! identity/browser-handoffs {})
  (run)
  (reset! identity/browser-handoffs {}))

(use-fixtures :each reset-state!)

(deftest a-browser-handoff-is-short-lived-and-single-use
  (let [{:keys [id expires-at]} (identity/start-browser-handoff!)
        token (:token (identity/issue-session! "user-1"))]
    (is (= :pending (:status (identity/consume-browser-handoff! id))))
    (is (string? expires-at))
    (is (= :complete
           (:status (identity/complete-browser-handoff! id token))))
    (is (= {:status :complete :token token}
           (identity/consume-browser-handoff! id)))
    (is (= {:status :invalid}
           (identity/consume-browser-handoff! id)))))

(deftest a-handoff-refuses-an-unauthenticated-token
  (let [{:keys [id]} (identity/start-browser-handoff!)]
    (is (= :identity/unauthenticated
           (try
             (identity/complete-browser-handoff! id "not-a-session")
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))
    (is (= :pending (:status (identity/consume-browser-handoff! id))))))
