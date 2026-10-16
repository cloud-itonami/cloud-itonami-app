(ns cloud.itonami.app.user-test-tools
  "Passkey-session-scoped MCP tools for private business user-test records."
  (:require [cloud.itonami.app.payment-tools :as payment-tools]
            [cloud.itonami.app.user-test :as app-user-test]
            [kotoba.user-test :as user-test]))

(def tools
  [{:name "user_test_business_summary"
    :description "Summarize human, synthetic and recipe evidence for one business."
    :parameters {:type "object" :properties {:business-id {:type "string"}}
                 :required ["business-id"]}}
   {:name "user_test_list_studies"
    :description "List user-test studies scoped to one business and organization."
    :parameters {:type "object" :properties {:business-id {:type "string"}}
                 :required ["business-id"]}}
   {:name "user_test_create_study"
    :description "Create a study. Persona must be an opaque private reference."
    :parameters {:type "object"
                 :properties {:business-id {:type "string"}
                              :study {:type "object"}}
                 :required ["business-id" "study"]}}
   {:name "user_test_record_run"
    :description "Record outcomes; raw evidence content is removed before persistence."
    :parameters {:type "object"
                 :properties {:study-id {:type "string"} :run {:type "object"}}
                 :required ["study-id" "run"]}}
   {:name "user_test_next_plan"
    :description "Return the oldest business study without a passing run."
    :parameters {:type "object" :properties {:business-id {:type "string"}}
                 :required ["business-id"]}}])

(defn available? [configuration]
  (and (app-user-test/enabled? configuration)
       (some? (payment-tools/session configuration))))

(defn call-tool [configuration tool-name args]
  (let [session (or (payment-tools/session configuration)
                    (throw (ex-info "Passkey session is required"
                                    {:type :user-test/session-required})))
        args (user-test/from-wire args)]
    (case tool-name
      "user_test_business_summary"
      (app-user-test/business-summary session (:business-id args))
      "user_test_list_studies"
      {:studies (app-user-test/studies session (:business-id args))}
      "user_test_create_study"
      (app-user-test/create-study! configuration session (:business-id args)
                                    (:study args))
      "user_test_record_run"
      (app-user-test/record-run! configuration session (:study-id args) (:run args))
      "user_test_next_plan"
      {:plan (app-user-test/next-plan session (:business-id args))}
      nil)))
