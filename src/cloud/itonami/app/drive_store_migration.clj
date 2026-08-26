(ns cloud.itonami.app.drive-store-migration
  "Fail-closed migration of Drive versions to the Kotobase archive.

  Every referenced object is read from the legacy filesystem store, sealed on
  this client when it is still plaintext, written to Kotobase, and read back
  byte-for-byte before the durable workspace is changed.  The old objects are
  deliberately retained: the archive cannot delete and the local copy is the
  rollback floor until an operator has observed the new backend for long
  enough."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.drive-crypto :as crypto]
            [cloud.itonami.app.kotobase-objects :as kotobase]
            [cloud.itonami.app.store :as app-store]
            [drive.object :as object]
            [drive.store.fs :as fs])
  (:import (java.nio.file Files StandardCopyOption)
           (java.nio.file.attribute PosixFilePermissions)
           (java.time Instant)))

(defn- bytes= [left right]
  (= (mapv #(bit-and (int %) 0xff) left)
     (mapv #(bit-and (int %) 0xff) right)))

(defn- source-bytes! [legacy target ref]
  (or (object/-get-object legacy ref)
      (object/-get-object target ref)
      (throw (ex-info "Drive migration source object is missing"
                      {:type :drive/migration-source-missing :object-ref ref}))))

(defn- archive! [target bytes]
  (let [ref (kotobase/content-ref target bytes)]
    (object/-put-object target ref bytes)
    (let [read-back (object/-get-object target ref)]
      (when-not (and read-back (bytes= bytes read-back))
        (throw (ex-info "Kotobase read-back did not match the uploaded object"
                        {:type :drive/migration-readback-mismatch
                         :object-ref ref}))))
    ref))

(defn- principals [owner item]
  (vec (distinct (concat [owner] (keys (:drive/permissions item))))))

(defn- migrate-version
  [legacy target owner item version]
  (let [old-ref (:drive.version/object-ref version)
        source (source-bytes! legacy target old-ref)
        plaintext (crypto/open owner source)
        already-encrypted? (crypto/encrypted? source)
        sealed (if already-encrypted?
                 source
                 (crypto/seal-for (principals owner item) (:drive/id item) source))
        new-ref (archive! target sealed)]
    {:version (assoc version
                     :drive.version/object-ref new-ref
                     :drive.version/size-bytes (count sealed))
     :old-ref old-ref
     :new-ref new-ref
     :plaintext-size (count plaintext)
     :sealed? (not already-encrypted?)}))

(defn- migrate-item
  [legacy target owner item]
  (if (or (not= :file (:drive/kind item))
          (empty? (:drive/versions item)))
    {:item item :objects []}
    (let [migrated (mapv #(migrate-version legacy target owner item %)
                         (:drive/versions item))
          newest (peek migrated)]
      {:item (-> item
                 (assoc :drive/versions (mapv :version migrated))
                 (assoc :drive/object-ref (:new-ref newest))
                 (assoc :drive/logical-size-bytes (:plaintext-size newest))
                 (assoc :drive/encrypted? true))
       :objects (mapv #(select-keys % [:old-ref :new-ref :sealed?]) migrated)})))

(defn migrate-state
  "Upload and verify all referenced Drive versions, returning a new state.

  This function has no durable write.  Callers can therefore upload a complete
  candidate and atomically commit it only after every source and read-back has
  passed."
  [state legacy target]
  (let [workspaces (get-in state [:drive :workspaces])
        migrated
        (into {}
              (for [[owner workspace] workspaces]
                (let [results (into {}
                                    (for [[id item] (:drive.workspace/items workspace)]
                                      [id (migrate-item legacy target owner item)]))]
                  [owner {:workspace (assoc workspace :drive.workspace/items
                                            (into {} (map (fn [[id result]]
                                                            [id (:item result)]))
                                                  results))
                          :objects (mapcat :objects (vals results))}])))
        objects (vec (mapcat :objects (vals migrated)))
        next-state (assoc-in state [:drive :workspaces]
                             (into {} (map (fn [[owner result]]
                                             [owner (:workspace result)]))
                                   migrated))]
    {:state next-state
     :report {:schema "cloud.itonami.app.drive-store-migration.v1"
              :backend :kotobase
              :versions (count objects)
              :sealed-legacy (count (filter :sealed? objects))
              :already-encrypted (count (remove :sealed? objects))
              :unique-cids (count (distinct (map :new-ref objects)))}}))

(defn- backup-state! []
  (let [source (app-store/state-file)
        dir (io/file (config/data-dir) "storage-migrations")
        target (io/file dir "drive-kotobase-v1-pre-state.edn")]
    (.mkdirs dir)
    (when-not (.isFile target)
      (Files/copy (.toPath source) (.toPath target)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/COPY_ATTRIBUTES]))
      (try
        (Files/setPosixFilePermissions (.toPath target)
                                       (PosixFilePermissions/fromString "rw-------"))
        (catch UnsupportedOperationException _)))
    target))

(defn migrate! []
  (when-not (= "kotobase" (some-> (System/getenv "CLOUD_ITONAMI_DRIVE_OBJECT_STORE")
                                   str/trim str/lower-case))
    (throw (ex-info "Set CLOUD_ITONAMI_DRIVE_OBJECT_STORE=kotobase explicitly"
                    {:type :drive/migration-target-not-selected})))
  (when-not (kotobase/configured?)
    (throw (ex-info "KOTOBASE_ARCHIVE_TOKEN is required"
                    {:type :drive/migration-token-missing})))
  (let [before (app-store/snapshot)
        legacy (fs/store (.getPath (io/file (config/data-dir) "drive-objects")))
        target (kotobase/store)
        backup (backup-state!)
        {:keys [state report]} (migrate-state before legacy target)]
    (app-store/transact!
     (fn [current]
       (when-not (= before current)
         (throw (ex-info "Drive state changed during migration"
                         {:type :drive/migration-concurrent-write})))
       state))
    (assoc report :ok true :at (str (Instant/now))
           :backup (.getName backup))))

(defn -main [& args]
  (when-not (= ["--execute"] (vec args))
    (throw (ex-info "Refusing without the exact --execute flag"
                    {:type :drive/migration-execute-required})))
  (println (pr-str (migrate!))))
