(ns cloud.itonami.app.drive-delivery-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.drive-delivery :as delivery]
            [cloud.itonami.app.store :as store]
            [drive.store.memory :as memory])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.security MessageDigest)))

(def ^:private alice "user-alice")
(def ^:private bob "user-bob")
(def ^:private mallory "user-mallory")
(def ^:private now-ms 1800000000000)

(defn- hex [^bytes value]
  (apply str (map #(format "%02x" (bit-and % 0xff)) value)))

(defn- cid-for [bytes]
  ;; The in-memory store deliberately accepts opaque refs. Give it a stable
  ;; content address here so the test exercises the production invariant:
  ;; the public id changes whenever the personalized ciphertext changes.
  (str "bafk-test-"
       (hex (.digest (MessageDigest/getInstance "SHA-256")
                     (byte-array (map unchecked-byte bytes))))))

(defn- error-type [f]
  (:type (ex-data (try (f) nil (catch Exception error error)))))

(defn- with-delivery-state [f]
  (let [state (atom (store/initial-state))
        dir (.toFile
             (Files/createTempDirectory
              "drive-delivery-test"
              (make-array java.nio.file.attribute.FileAttribute 0)))
        root (byte-array (map byte (range 32)))]
    (with-redefs [store/snapshot (fn [] @state)
                  store/transact! (fn [g & args] (apply swap! state g args))
                  config/data-dir (constantly dir)
                  delivery/root-seed (constantly root)]
      (f state (memory/store)))))

(defn- issue [object-store action audience max-uses expires-at]
  (delivery/issue!
   {:source-id "doc-source-1" :source-version "content-sha256:abc"
    :owner alice :audience audience :action action
    :expires-at expires-at :max-uses max-uses
    :filename "plan.txt" :media-type "text/plain"
    :bytes (.getBytes "confidential plan" StandardCharsets/UTF_8)
    :object-store object-store :content-ref cid-for}))

(deftest each-recipient-gets-a-distinct-content-addressed-delivery
  (with-delivery-state
    (fn [state object-store]
      (let [bob-delivery (issue object-store :download bob 1 (+ now-ms 10000))
            mallory-delivery (issue object-store :download mallory 1 (+ now-ms 10000))]
        (is (str/starts-with? (:delivery-id bob-delivery) "bafk-test-"))
        (is (not= (:delivery-id bob-delivery) (:delivery-id mallory-delivery)))
        (is (not= "doc-source-1" (:delivery-id bob-delivery)))
        (is (= bob (get-in @state [:drive :deliveries
                                   (:delivery-id bob-delivery) :audience])))
        (let [opened (delivery/redeem! (:delivery-id bob-delivery) bob :download
                                       now-ms object-store)
              text (String. ^bytes (:bytes opened) StandardCharsets/UTF_8)]
          (is (str/includes? text "confidential plan"))
          (is (str/includes? text (:watermark bob-delivery)))
          (is (= :visible-text (:watermark-mode opened))))))))

(deftest audience-action-expiry-use-limit-and-revocation-fail-closed
  (with-delivery-state
    (fn [_ object-store]
      (let [one-use (issue object-store :download bob 1 (+ now-ms 10000))
            id (:delivery-id one-use)]
        (testing "audience and action are checked before decryption"
          (is (= :drive/delivery-audience-mismatch
                 (error-type #(delivery/redeem! id mallory :download now-ms
                                                object-store))))
          (is (= :drive/delivery-action-mismatch
                 (error-type #(delivery/redeem! id bob :view now-ms object-store)))))
        (delivery/redeem! id bob :download now-ms object-store)
        (is (= :drive/delivery-use-limit
               (error-type #(delivery/redeem! id bob :download now-ms object-store))))

        (let [expired (issue object-store :view bob 2 (dec now-ms))]
          (is (= :drive/delivery-expired
                 (error-type #(delivery/redeem! (:delivery-id expired) bob :view
                                                now-ms object-store)))))

        (let [revoked (issue object-store :copy bob 1 (+ now-ms 10000))]
          (is (= :drive/delivery-not-owner
                 (error-type #(delivery/revoke! (:delivery-id revoked) bob))))
          (delivery/revoke! (:delivery-id revoked) alice)
          (is (= :drive/delivery-revoked
                 (error-type #(delivery/redeem! (:delivery-id revoked) bob :copy
                                                now-ms object-store)))))))))

(deftest owner-issues-a-view-delivery-and-recipient-never-sees-source-ref
  (with-delivery-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "配送計画" alice object-store)
            id (:id item)
            source-ref (get-in @state [:drive :workspaces alice
                                       :drive.workspace/items id :drive/object-ref])
            issued (documents/issue-delivery! id alice bob :view object-store
                                              {:expires-in-hours 24 :max-uses 2})
            opened (documents/redeem-delivery! (:delivery-id issued) bob :view
                                                object-store)
            copy-delivery (documents/issue-delivery! id alice bob :copy object-store
                                                      {:max-uses 1})
            copied (documents/copy-delivery! (:delivery-id copy-delivery) bob
                                              object-store {:title "受領した配送計画"})
            copy-id (get-in copied [:item :id])
            provenance (get-in @state [:drive :workspaces bob
                                       :drive.workspace/items copy-id
                                       :drive/delivery-provenance])]
        (is (not= source-ref (:delivery-id issued)))
        (is (nil? (:source-ref issued)))
        (is (nil? (:object-ref issued)))
        (is (= id (:source-id opened)))
        (is (str/includes? (String. ^bytes (:bytes opened) StandardCharsets/UTF_8)
                           (:watermark issued)))
        (is (= (:delivery-id copy-delivery) (:delivery/id provenance)))
        (is (= id (:delivery/source-id provenance)))
        (is (= (:watermark copy-delivery) (:delivery/watermark provenance)))))))

(deftest legacy-content-etag-does-not-publish-the-storage-reference
  (let [ref "bafy-source-cid-that-must-stay-private"
        etag (documents/content-etag {:drive/id "legacy-1"
                                      :drive/object-ref ref})]
    (is (str/starts-with? etag "content-sha256:"))
    (is (not= ref etag))
    (is (not (str/includes? etag ref)))))
