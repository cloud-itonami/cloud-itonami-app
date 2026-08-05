(ns cloud.itonami.app.work-partition-store-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.work-partition-store :as partitions]))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [entry (reverse (file-seq file))] (.delete entry))))

(deftest ledger-round-trips-through-physical-tenant-files
  (let [directory (io/file (System/getProperty "java.io.tmpdir")
                           (str "itonami-work-partitions-"
                                (java.util.UUID/randomUUID)))
        ledger {:schema "work.v2"
                :organizations {"org-a" {:org/id "org-a"}
                                "org-b" {:org/id "org-b"}}
                :organization-units
                {"a-root" {:org.unit/id "a-root"
                            :org.unit/organization "org-a"}}
                :positions
                {"a-position" {:org.position/id "a-position"
                               :org.position/organization "org-a"}}
                :organization-roles
                {:a-role {:org.role/id :a-role
                          :org.role/organization "org-a"}}
                :performers {"alice" {:performer/id "alice"
                                      :performer/organization "org-a"}
                             "bob" {:performer/id "bob"
                                    :performer/organization "org-b"}}
                :assignments {} :reporting-lines [] :approval-policies {}
                :work-items {"a-1" {:work.item/id "a-1"
                                    :work.item/organization "org-a"}
                             "b-1" {:work.item/id "b-1"
                                    :work.item/organization "org-b"}}
                :approval-decisions [] :execution-receipts []
                :verification-receipts [] :projection-receipts []
                :audit-events [{:audit/id "global" :audit/type :tick}
                               {:audit/id "a" :audit/work-item "a-1"}]
                :dead-letters [] :source-bases {"a-1" {:v 1} "b-1" {:v 2}}
                :source-cursors {} :roles {} :runtime {:ticks 1}}]
    (try
      (with-redefs [config/data-dir (constantly directory)]
        (partitions/persist-ledger! ledger)
        (let [manifest (edn/read-string (slurp (partitions/manifest-file)))
              tenant-files (vals (:partition/tenants manifest))
              org-a-file (get-in manifest [:partition/tenants "org-a"])
              org-a-fragment (edn/read-string
                              (slurp (io/file (partitions/directory)
                                              org-a-file)))
              loaded (partitions/load-ledger nil)]
          (is (= 2 (count tenant-files)))
          (is (every? #(.isFile (io/file (partitions/directory) %)) tenant-files))
          (is (= #{"a-root"} (set (keys (:organization-units org-a-fragment)))))
          (is (= #{:a-role} (set (keys (:organization-roles org-a-fragment)))))
          (is (= ledger loaded))
          (is (= :physical-per-organization (:mode (partitions/status))))))
      (finally (delete-tree! directory)))))

(deftest legacy-ledger-is-used-before-first-partition-commit
  (let [directory (io/file (System/getProperty "java.io.tmpdir")
                           (str "itonami-work-legacy-"
                                (java.util.UUID/randomUUID)))
        legacy {:schema "legacy" :work-items {}}]
    (try
      (with-redefs [config/data-dir (constantly directory)]
        (is (= legacy (partitions/load-ledger legacy)))
        (is (= :legacy-single-file (:mode (partitions/status)))))
      (finally (delete-tree! directory)))))
