(ns cloud.itonami.app.installation-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.installation :as installation]
            [cloud.itonami.app.recovery :as recovery])
  (:import [java.nio.file Files]
           [java.util Arrays]))

(defn- temp-dir [prefix]
  (.toFile
   (Files/createTempDirectory
    prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest platform-data-directory-is-independent-of-the-checkout
  (with-redefs [installation/home-dir (constantly "/Users/tester")
                installation/os-name (constantly "Mac OS X")
                installation/environment (constantly nil)]
    (is (= "/Users/tester/Library/Application Support/Cloud Itonami"
           (.getPath (installation/default-data-dir)))))
  (with-redefs [installation/home-dir (constantly "/home/tester")
                installation/os-name (constantly "Linux")
                installation/environment
                (fn [name] (when (= name "XDG_DATA_HOME") "/stable/data"))]
    (is (= "/stable/data/cloud-itonami"
           (.getPath (installation/default-data-dir))))))

(deftest legacy-migration-is-validated-atomic-and-non-destructive
  (let [root (temp-dir "cloud-itonami-migration")
        legacy (doto (io/file root "legacy") .mkdirs)
        target (io/file root "stable")
        state {:schema "cloud.itonami.app.state.v1" :events []}]
    (spit (io/file legacy "state.edn") (pr-str state))
    (doto (io/file legacy "nested") .mkdirs)
    (spit (io/file legacy "nested" "object") "payload")
    (let [result (installation/migrate-legacy! target legacy)
          marker (edn/read-string (slurp (io/file target "migration.edn")))]
      (is (= :migrated (:status result)))
      (is (:source-preserved? result))
      (is (.isFile (io/file legacy "state.edn")))
      (is (= state (edn/read-string (slurp (io/file target "state.edn")))))
      (is (= "payload" (slurp (io/file target "nested" "object"))))
      (is (= installation/storage-schema (:schema marker)))
      (is (= :copied (:status marker))))
    (is (= :target-exists
           (:status (installation/migrate-legacy! target legacy))))))

(deftest invalid-legacy-state-is-not-migrated
  (let [root (temp-dir "cloud-itonami-invalid-migration")
        legacy (doto (io/file root "legacy") .mkdirs)
        target (io/file root "stable")]
    (spit (io/file legacy "state.edn") "{:schema \"other\"}")
    (is (= :no-valid-legacy-state
           (:status (installation/migrate-legacy! target legacy))))
    (is (not (.exists target)))))

(deftest recovery-envelope-round-trips-and-detects-tampering
  (let [key (byte-array (map byte (range 32)))
        plaintext (.getBytes "private installation state" "UTF-8")
        envelope (recovery/encrypt-bytes key plaintext)
        tampered (Arrays/copyOf envelope (alength envelope))]
    (is (not (Arrays/equals plaintext envelope)))
    (is (Arrays/equals plaintext
                       (recovery/decrypt-bytes key envelope)))
    (aset-byte tampered (dec (alength tampered))
               (byte (bit-xor 1 (aget tampered (dec (alength tampered))))))
    (is (thrown? Exception (recovery/decrypt-bytes key tampered)))))

(deftest encrypted-backups-are-bounded-and-restorable
  (let [directory (temp-dir "cloud-itonami-backups")
        key (byte-array (repeat 32 (byte 7)))
        plaintext (.getBytes "state" "UTF-8")]
    (binding [recovery/*backup-enabled?* true]
      (with-redefs [recovery/recovery-key (constantly key)]
        (dotimes [_ 7] (recovery/backup! directory plaintext))
        (let [files (->> (file-seq (io/file directory "backups"))
                         (filter #(.isFile %))
                         vec)]
          (is (= recovery/default-retention (count files)))
          (is (every? #(not= "state" (slurp %)) files))
          (is (Arrays/equals
               plaintext
               (recovery/decrypt-bytes
                key (Files/readAllBytes (.toPath (first files)))))))))))
