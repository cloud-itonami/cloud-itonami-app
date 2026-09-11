(ns cloud.itonami.app.oauth-resource-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.oauth-resource :as oauth]
            [cloud.itonami.app.store :as store]))

(def configuration
  {:server {:public-origin "https://itonami.cloud"}
   :mcp {:oauth {:authorization-servers ["https://auth.itonami.cloud"]}}})

(defn- fixture []
  (assoc (store/initial-state) :identity
         {:memberships {"membership-a"
                        {:id "membership-a" :user-id "user-a"
                         :organization-id "org-a"}}}))

(deftest protected-resource-metadata-is-path-bound
  (is (= "https://itonami.cloud/mcp"
         (:resource (oauth/metadata configuration))))
  (is (= ["https://auth.itonami.cloud"]
         (:authorization_servers (oauth/metadata configuration))))
  (is (= ["header"] (:bearer_methods_supported
                      (oauth/metadata configuration))))
  (is (= "https://itonami.cloud/a2a"
         (:resource (oauth/a2a-metadata configuration))))
  (is (= ["a2a:tasks"]
         (:scopes_supported (oauth/a2a-metadata configuration)))))

(deftest hosted-resource-origin-is-independent-from-passkey-origin
  (let [split-origin (-> configuration
                         (assoc-in [:server :public-origin]
                                   "http://localhost:1338")
                         (assoc-in [:mcp :resource-origin]
                                   "https://mcp.itonami.cloud"))]
    (is (= "https://mcp.itonami.cloud/mcp"
           (oauth/resource-url split-origin)))
    (is (= (str "Bearer resource_metadata=\"https://mcp.itonami.cloud/"
                ".well-known/oauth-protected-resource/mcp\", scope=\"mcp:tools\"")
           (oauth/challenge split-origin "mcp:tools")))))

(deftest introspected-token-is-audience-and-scope-bound
  (let [previous @store/state
        claims {:active true :sub "user-a" :aud ["https://itonami.cloud/mcp"]
                :scope "mcp:tools tenant:connect" :client_id "codex"}]
    (try
      (reset! store/state (fixture))
      (binding [oauth/*introspect* (fn [_ _] claims)]
        (let [session (oauth/session configuration "secret-token" "mcp:tools"
                                     "https://itonami.cloud/mcp")]
          (is (= :agent (:kind session)))
          (is (= "user-a" (:user-id session)))
          (is (= :oauth (:issued-via session)))
          (is (= (:id session)
                 (:id (oauth/session configuration "rotated-access-token"
                                     "mcp:tools"
                                     "https://itonami.cloud/mcp")))
              "access-token rotation keeps the requesting agent identity"))
        (testing "a token for another resource is rejected"
          (is (= :oauth-resource/invalid-audience
                 (:type
                  (ex-data
                   (try (oauth/session configuration "secret-token" "mcp:tools"
                                       "https://other.example/mcp")
                        (catch clojure.lang.ExceptionInfo e e)))))))
        (testing "a missing route scope is rejected"
          (is (= :oauth-resource/insufficient-scope
                 (:type
                  (ex-data
                   (try (oauth/session configuration "secret-token"
                                       "repository:write"
                                       "https://itonami.cloud/mcp")
                        (catch clojure.lang.ExceptionInfo e e))))))))
      (finally (reset! store/state previous)))))
