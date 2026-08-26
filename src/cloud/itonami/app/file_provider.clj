(ns cloud.itonami.app.file-provider
  "Cloud Itonami adapter for the portable kotoba-lang File Provider model.

  Apple callbacks never decide sync policy or see storage ciphertext. This
  namespace maps Drive items to the localhost wire format and applies the
  same schedule/residency state machine used by the web UI."
  (:require [clojure.string :as str]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.drive-crypto :as crypto]
            [cloud.itonami.app.store :as store]
            [drive.object :as object]
            [drive.workspace :as ws]
            [fileprovider.model :as model])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(def root-id "NSFileProviderRootContainerItemIdentifier")
(def root-aliases #{root-id "root"})

(defn- hex [^bytes value]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) value)))

(defn- version-of [value]
  (str "sha256:"
       (hex (.digest (MessageDigest/getInstance "SHA-256")
                     (.getBytes (pr-str value) StandardCharsets/UTF_8)))))

(defn- policy [item]
  (merge model/defaults
         {:sync/schedule (or (:drive/sync-schedule item) :continuous)
          :sync/residency (or (:drive/residency item) :automatic)}))

(defn- actual-root [actor]
  (:drive.workspace/root-id (documents/workspace-for (store/snapshot) actor)))

(defn- actual-id [actor id]
  (if (root-aliases (str id)) (actual-root actor) (str id)))

(defn- external-parent [actor parent]
  (if (= (actual-root actor) parent) root-id parent))

(defn- root-wire [actor]
  {:id root-id :parentID root-id :name "Cloud Itonami"
   :directory true :size 0
   :contentVersion "root" :metadataVersion (version-of [actor :root])
   :schedule "continuous" :residency "automatic"})

(defn- item-wire [actor item]
  (let [policy (policy item)
        newest (peek (:drive/versions item))
        metadata [(:drive/title item) (:drive/parent-id item)
                  (:drive/sync-schedule item) (:drive/residency item)
                  (:drive/trashed? item)]]
    {:id (:drive/id item)
     :parentID (external-parent actor (:drive/parent-id item))
     :name (:drive/title item)
     :directory (= :folder (:drive/kind item))
     ;; The object version contains the encrypted envelope, whose byte length
     ;; is intentionally larger than the file Finder materializes. File
     ;; Provider's documentSize must describe those plaintext bytes or macOS
     ;; will cache and reconcile against a size it can never fetch.
     :size (long (or (:drive/logical-size-bytes item)
                     (:drive.version/size-bytes newest) 0))
     :contentVersion (or (documents/content-etag item) "folder")
     :metadataVersion (version-of metadata)
     :schedule (name (:sync/schedule policy))
     :residency (name (:sync/residency policy))}))

(defn- target! [actor id]
  (let [id (actual-id actor id)
        {:keys [workspace owner]} (documents/locate (store/snapshot) actor id)
        item (when workspace (ws/item workspace id))]
    (when-not (and item (ws/can-read? workspace id actor)
                   (not (ws/trashed? workspace id)))
      (throw (ex-info "File Provider item not found"
                      {:type :drive/not-found :item-id id})))
    {:workspace workspace :owner owner :item item :id id}))

(defn item [actor id]
  (if (root-aliases (str id))
    (root-wire actor)
    (let [{:keys [item]} (target! actor id)]
      (when (and (= :file (:drive/kind item)) (:drive/resource-kind item))
        (throw (ex-info "Native office documents are exported explicitly"
                        {:type :drive/not-a-file :item-id id})))
      (item-wire actor item))))

(defn children [actor id]
  (let [id (actual-id actor id)
        {:keys [workspace item]} (target! actor id)]
    (when-not (= :folder (:drive/kind item))
      (throw (ex-info "File Provider container is not a folder"
                      {:type :drive/not-a-folder :item-id id})))
    {:items (->> (ws/children workspace id actor)
                 (remove #(ws/trashed? workspace (:drive/id %)))
                 ;; Native Sheets/Docs/Forms remain in their editors. Finder
                 ;; gets ordinary files and folders with exact byte semantics.
                 (filter #(or (= :folder (:drive/kind %))
                              (and (= :file (:drive/kind %))
                                   (nil? (:drive/resource-kind %)))))
                 (mapv #(item-wire actor %)))
     :nextPage nil}))

(defn materialize [actor id object-store]
  (let [{:keys [item]} (target! actor id)
        commands (model/command (policy item) :open)]
    (when (= [:fail-paused] commands)
      (throw (ex-info "File Provider item is paused"
                      {:type :folder-sync/paused :item-id id})))
    (when (= :folder (:drive/kind item))
      (throw (ex-info "A folder has no file contents"
                      {:type :drive/not-a-file :item-id id})))
    {:item (item-wire actor item)
     :bytes (:bytes (documents/file-bytes (:drive/id item) actor object-store))}))

(defn create! [actor parent-id name directory? object-store]
  (let [parent (actual-id actor parent-id)]
    (if directory?
      (let [created (:item (documents/create-folder! name actor parent))]
        (item actor (:id created)))
      (let [workspace (documents/workspace-for (store/snapshot) actor)
            id (store/new-id "file")
            created-at (store/now)
            staged (ws/create-file workspace id parent
                                   {:drive/title (or (not-empty (str/trim (str name)))
                                                     "名称未設定")
                                    :drive/media-type "application/octet-stream"
                                    :drive/content-etag (str "content-" (UUID/randomUUID))
                                    :drive/logical-size-bytes 0
                                    :drive/created-at created-at
                                    :drive/encrypted? true
                                    :drive/sync-schedule :continuous
                                    :drive/residency :automatic}
                                   actor)
            encrypted (crypto/seal actor id [])
            written (object/write-item staged object-store id actor encrypted
                                       {:object-ref (documents/object-ref-for
                                                     object-store encrypted)
                                        :created-at created-at})]
        (when-not (:ok? written) (throw (ex-info "File Provider create failed" written)))
        (documents/save-workspace! actor (:workspace written))
        (item-wire actor (ws/item (:workspace written) id))))))

(defn modify! [actor id parent-id name]
  (let [{:keys [workspace owner item id]} (target! actor id)]
    (when-not (ws/can-write? workspace id actor)
      (throw (ex-info "File Provider item is read-only"
                      {:type :drive/not-permitted :item-id id})))
    (when (:drive/resource-kind item)
      (throw (ex-info "Native office documents are modified in their editor"
                      {:type :drive/not-a-file :item-id id})))
    (let [parent (actual-id actor parent-id)
          moved (if (= parent (:drive/parent-id item)) workspace
                  (ws/move workspace id parent))
          renamed (assoc-in moved [:drive.workspace/items id :drive/title]
                            (or (not-empty (str/trim (str name))) (:drive/title item)))]
      (documents/save-workspace! owner renamed)
      (item-wire actor (ws/item renamed id)))))

(defn upload! [actor id bytes object-store]
  (let [{:keys [workspace owner item id]} (target! actor id)]
    (when-not (and (= :file (:drive/kind item))
                   (nil? (:drive/resource-kind item))
                   (ws/can-write? workspace id actor))
      (throw (ex-info "File Provider item is not writable bytes"
                      {:type :drive/not-permitted :item-id id})))
    (let [principals (distinct (concat [owner actor]
                                       (keys (:drive/permissions item))))
          encrypted (crypto/seal-for principals id bytes)
          staged (-> workspace
                     (assoc-in [:drive.workspace/items id :drive/content-etag]
                               (str "content-" (UUID/randomUUID)))
                     (assoc-in [:drive.workspace/items id :drive/logical-size-bytes]
                               (count bytes))
                     (assoc-in [:drive.workspace/items id :drive/encrypted?] true)
                     (assoc-in [:drive.workspace/items id :drive/sync-local-state]
                               :materialized))
          written (object/write-item staged object-store id actor encrypted
                                     {:object-ref (documents/object-ref-for
                                                   object-store encrypted)
                                      :created-at (store/now)})]
      (when-not (:ok? written) (throw (ex-info "File Provider upload failed" written)))
      (documents/save-workspace! owner (:workspace written))
      (item-wire actor (ws/item (:workspace written) id)))))

(defn delete! [actor id]
  (documents/trash! (actual-id actor id) actor)
  true)

(defn set-mode! [actor id schedule residency]
  (when-not (and (contains? model/schedules schedule)
                 (contains? model/residencies residency))
    (throw (ex-info "File Provider sync mode is invalid"
                    {:type :folder-sync/invalid-mode
                     :schedule schedule :residency residency})))
  (let [{:keys [workspace owner id]} (target! actor id)
        updated (-> workspace
                    (assoc-in [:drive.workspace/items id :drive/sync-schedule] schedule)
                    (assoc-in [:drive.workspace/items id :drive/residency] residency))]
    (documents/save-workspace! owner updated)
    (item-wire actor (ws/item updated id))))
