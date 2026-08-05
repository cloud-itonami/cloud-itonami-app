(ns cloud.itonami.app.project-remote-test
  "Where a project's annexed mail bodies go, and what is said when they cannot.

  The push itself needs a bucket and a network, so it is exercised by hand
  against real B2 rather than here. What these pin is everything around it: the
  bucket layout, credential resolution, the refusal when there are none, and the
  one parsing rule that has now bitten this codebase twice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.project-remote :as remote]))

(defn- with-environment [values f]
  (binding [remote/*environment* #(get values %)] (f)))

(deftest every-project-gets-its-own-place-in-the-shared-bucket
  (testing "one bucket holds every dataset in this workspace, so a prefix that
            two projects could share would mix their objects with nothing on the
            B2 side able to tell them apart"
    (let [a (remote/file-prefix {:organization-storage-id "org-aaa"
                                 :project-slug "finance"})
          b (remote/file-prefix {:organization-storage-id "org-bbb"
                                 :project-slug "finance"})]
      (is (not= a b)
          "the same project name in two organizations must not collide")
      (is (str/ends-with? a "/")
          "git-annex treats the prefix as a literal string, so a missing slash
           silently makes it part of the first object's name")
      (is (str/starts-with? a "cloud-itonami-mail/")
          "and it is namespaced, because this bucket is not ours alone"))))

(deftest the-environment-can-override-every-coordinate
  (with-environment {"B2_BUCKET" "other-bucket"
                     "B2_ENDPOINT" "s3.example.invalid"
                     "B2_KEY_ID" "key-id" "B2_APP_KEY" "app-key"}
    (fn []
      (is (= "other-bucket" (remote/bucket)))
      (is (= "s3.example.invalid" (remote/endpoint)))
      (let [{:keys [key-id app-key source]} (remote/credentials)]
        (is (= "key-id" key-id))
        (is (= "app-key" app-key))
        (is (= :environment source))))))

(deftest a-half-configured-environment-is-not-credentials
  (testing "a key id with no application key must fall through to the Keychain
            rather than be handed to git-annex as an empty secret"
    (with-environment {"B2_KEY_ID" "key-id"}
      (fn []
        (is (not= :environment (:source (remote/credentials))))))))

(deftest with-no-credentials-it-refuses-and-names-the-places
  (with-environment {}
    (fn []
      (with-redefs [remote/credentials (constantly nil)]
        (let [result (remote/ensure-remote! "/tmp" {:organization-storage-id "o"
                                                    :project-slug "p"})]
          (is (false? (:ok? result)))
          (is (str/includes? (:error result) "B2_KEY_ID"))
          (is (str/includes? (:error result) remote/keychain-service)
              "an operator reading this must learn WHERE to put the key"))))))

(deftest a-push-without-credentials-does-not-report-success
  (with-redefs [remote/credentials (constantly nil)]
    (let [result (remote/push! "/tmp" {:organization-storage-id "o"
                                       :project-slug "p"})]
      (is (false? (:ok? result)))
      (is (nil? (:pushed? result))
          "`pushed?` absent rather than false, because nothing was attempted —
           a false here would read as 'tried and failed'"))))

(deftest defaults-point-at-the-workspace-bucket
  (with-environment {}
    (fn []
      (is (= "gftdcojp-m365-annex" (remote/bucket)))
      (is (= "s3.us-west-004.backblazeb2.com" (remote/endpoint)))
      (is (= "b2" remote/remote-name)))))
