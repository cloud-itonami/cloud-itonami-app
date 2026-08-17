(ns cloud.itonami.app.connectors-test
  "The load-bearing test here is `wiring-the-registry-cannot-widen-a-grant`.

  Everything else about this change is an improvement that can be argued
  about; that one is a safety property. If deriving the catalogue from the
  registry asked for one scope more than the application asked for before,
  then every person who reconnects would be shown an approval screen for
  access nobody decided to want — and they would approve it, because it
  arrives looking like the normal reconnect."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.connectors :as connectors]
            [connector.model :as cm]
            [connector.provider :as cp]
            [connector.registry :as creg]))

(deftest wiring-the-registry-cannot-widen-a-grant
  (doseq [[provider granted] connectors/historical-grant]
    (testing (str (name provider) " asks for nothing new")
      (let [asked (connectors/granted-scopes provider)
            extra (remove (fn [s]
                            (or (contains? granted s)
                                (contains? granted (get connectors/scope-implications s))))
                          asked)]
        (is (empty? extra)
            (str (name provider) " would newly request " (pr-str (vec (sort extra))))))))
  (is (empty? (connectors/widened-scopes))
      "the same property, stated the way an operator sees it"))

(deftest the-default-is-computed-not-listed
  (testing "a tool is on only if every scope it needs was already granted"
    (let [on (connectors/default-enabled-tools)]
      (doseq [pr (creg/providers connectors/all)
              :let [d (cp/descriptor pr)]
              t (cm/tools d)
              :when (contains? on (:connector/name t))]
        (let [granted (get connectors/historical-grant
                           (get-in connectors/client-overlay
                                   [(get-in d [:connector/auth :connector.auth/client-id-env])
                                    :provider]))]
          (is (some? granted)
              (str (:connector/name t) " is on for a provider with no historical grant"))
          (doseq [s (:connector/scopes t)]
            (is (or (contains? granted s)
                    (contains? granted (get connectors/scope-implications s)))
                (str (:connector/name t) " is on but needs " s))))))))

(deftest the-wideners-are-off-and-named
  (let [on (connectors/default-enabled-tools)]
    (testing "Drive file CONTENTS — the application only ever held metadata"
      (is (not (contains? on "google_drive_export_document")))
      (is (contains? on "google_drive_search_files")))
    (testing "calendar WRITES — the application only ever held readonly"
      (is (not (contains? on "google_calendar_create_event")))
      (is (contains? on "google_calendar_freebusy")))
    (testing "GitHub's repo scope — the application held read:user/read:org only"
      (is (not (contains? on "github_list_repositories")))
      (is (not (contains? on "github_create_issue")))
      (is (contains? on "github_get_authenticated_user")))
    (testing "Microsoft Calendars.Read is wider than the Calendars.ReadBasic held"
      (is (not (contains? on "microsoft_graph_list_events")))
      (is (contains? on "microsoft_graph_get_profile"))
      (is (contains? on "microsoft_graph_list_messages")
          "Mail.Read is contained in the Mail.ReadWrite the application held"))
    (testing "providers this application never reached are entirely off"
      (doseq [t (concat (cm/tool-names (cp/descriptor
                                        (creg/provider connectors/all "com.slack")))
                        (cm/tool-names (cp/descriptor
                                        (creg/provider connectors/all "com.notion")))
                        (cm/tool-names (cp/descriptor
                                        (creg/provider connectors/all "com.google.chat"))))]
        (is (not (contains? on t)) (str t " is on but its provider is new"))))))

(deftest gmail-can-now-be-read-without-being-writable
  (testing "the split the single gmail.modify entry could not express"
    (let [reading (connectors/enabled {:connectors {:enabled {:tools #{"gmail_search_messages"}}}})
          scopes (connectors/granted-scopes
                  {:connectors {:enabled {:tools #{"gmail_search_messages"}}}}
                  :google)]
      (is (= ["gmail_search_messages"] (creg/tool-names reading)))
      (is (contains? scopes "https://www.googleapis.com/auth/gmail.readonly"))
      (is (not (contains? scopes "https://www.googleapis.com/auth/gmail.modify")))
      (is (not (contains? scopes "https://www.googleapis.com/auth/gmail.send"))
          "a mailbox search that could also send mail is the thing being fixed"))))

(deftest the-catalogue-keeps-the-shape-identity-expects
  (let [catalog (connectors/provider-catalog)]
    (testing "the three providers that existed before are all still here"
      (is (= #{:google :github :microsoft}
             (set/intersection #{:google :github :microsoft} (set (keys catalog)))))
      (is (empty? (connectors/unknown-provider-scopes))))
    (doseq [[provider entry] catalog]
      (testing (name provider)
        (is (string? (:name entry)))
        (is (string? (:credential-service entry)))
        (is (str/ends-with? (:client-id-env entry) "_CLIENT_ID"))
        (is (str/ends-with? (:client-secret-env entry) "_CLIENT_SECRET"))
        (is (str/starts-with? (:authorization-endpoint entry) "https://"))
        (is (str/starts-with? (:token-endpoint entry) "https://"))
        (is (seq (:scopes entry)))
        (is (= (:scopes entry) (vec (sort (:scopes entry))))
            "sorted, so the same enabled set always yields the same consent URL")))))

(deftest the-endpoints-are-the-ones-the-application-used
  (let [catalog (connectors/provider-catalog)]
    (is (= "https://accounts.google.com/o/oauth2/v2/auth"
           (get-in catalog [:google :authorization-endpoint])))
    (is (= "https://oauth2.googleapis.com/token"
           (get-in catalog [:google :token-endpoint])))
    (is (= "https://github.com/login/oauth/authorize"
           (get-in catalog [:github :authorization-endpoint])))
    (is (= "https://login.microsoftonline.com/organizations/oauth2/v2.0/token"
           (get-in catalog [:microsoft :token-endpoint])))
    (testing "and the keychain service names, which are this app's to choose"
      (is (= "gftd.google" (get-in catalog [:google :credential-service])))
      (is (= "gftd.github" (get-in catalog [:github :credential-service])))
      (is (= "gftd.m365" (get-in catalog [:microsoft :credential-service]))))))

(deftest three-google-connectors-are-one-catalogue-entry
  (testing "because they share one OAuth client, and a grant belongs to a client"
    (let [catalog (connectors/provider-catalog)]
      (is (= 1 (count (filter #(= "GOOGLE_CLIENT_ID" (:client-id-env %)) (vals catalog)))))
      (is (some #(str/includes? % "calendar") (get-in catalog [:google :scopes])))
      (is (some #(str/includes? % "drive") (get-in catalog [:google :scopes])))
      (is (some #(str/includes? % "gmail") (get-in catalog [:google :scopes]))))))

(deftest profile-comes-from-the-application-not-a-connector
  (testing "no tool needs it; the userinfo call that fills in a display name does"
    (is (contains? (connectors/granted-scopes :google) "profile"))
    (doseq [pr (creg/providers connectors/all)
            t (cm/tools (cp/descriptor pr))]
      (is (not (contains? (set (:connector/scopes t)) "profile"))))))

(deftest an-explicitly-empty-configuration-yields-nothing
  (testing "a deployment that wants no external services says so and gets none —
            distinct from an absent key, which means 'use the default'"
    (let [none (connectors/enabled {:connectors {:enabled {:tools []}}})]
      (is (empty? (creg/tool-names none)))
      (is (empty? (connectors/provider-catalog {:connectors {:enabled {:tools []}}}))))))

(deftest turning-a-widener-on-is-reported-not-silent
  (let [config {:connectors {:enabled {:tools (conj (connectors/default-enabled-tools)
                                                    "google_drive_export_document")}}}]
    (is (= {:google ["https://www.googleapis.com/auth/drive.readonly"]}
           (connectors/widened-scopes config))
        "an operator who turns on file contents is told which approval that surfaces as")
    (is (str/includes? (connectors/describe config) "WIDER"))))

(deftest also-adds-to-the-computed-default-rather-than-replacing-it
  (testing "the Microsoft calendar, turned on the way an operator should"
    (let [config {:connectors
                  {:enabled {:also ["microsoft_graph_list_events"
                                    "microsoft_graph_get_schedule"]}}}
          on (set (creg/tool-names (connectors/enabled config)))]
      (is (contains? on "microsoft_graph_list_events"))
      (is (contains? on "microsoft_graph_get_schedule"))
      (is (= (into (connectors/default-enabled-tools)
                   ["microsoft_graph_list_events" "microsoft_graph_get_schedule"])
             on)
          "the default is still computed — naming one widener does not pin the rest")
      (is (= {:microsoft ["Calendars.Read"]} (connectors/widened-scopes config))
          "and the one scope this surfaces is named, so the reconnect is expected")
      (is (str/includes? (connectors/describe config) "WIDER")))))

(deftest also-cannot-be-mistaken-for-the-default-moving
  (testing "nothing is on for a deployment that did not ask"
    (let [on (connectors/default-enabled-tools)]
      (is (not (contains? on "microsoft_graph_list_events")))
      (is (not (contains? on "microsoft_graph_get_schedule")))
      (is (empty? (connectors/widened-scopes)))))
  (testing "and an empty :tools with an :also is that one tool, not the default"
    (let [only (connectors/enabled
                {:connectors {:enabled {:tools [] :also ["microsoft_graph_get_schedule"]}}})]
      (is (= ["microsoft_graph_get_schedule"] (creg/tool-names only))))))

(deftest the-catalog-rows-show-what-is-off-as-well-as-on
  (let [rows (connectors/catalog-rows)
        by-id (into {} (map (juxt :id identity)) rows)]
    (is (= 8 (count rows)) "a registry nobody can see the rest of is a literal with extra steps")
    (is (false? (:configurable? (get by-id "com.google.chat")))
        "no keychain service name has been chosen for it, so Settings cannot connect it")
    (is (true? (:configurable? (get by-id "com.slack"))))
    (let [drive (get by-id "com.google.drive")
          export (first (filter #(= "google_drive_export_document" (:name %)) (:tools drive)))]
      (is (false? (:enabled? export)))
      (is (seq (:description export)) "with the sentence explaining what it would do"))))
