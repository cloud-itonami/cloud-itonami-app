(ns cloud.itonami.app.drive-fs
  "The Drive as a filesystem, so it can be mounted.

  `nfs.v3/IFilesystem` is thirteen questions about a tree of bytes. The Drive
  can answer all thirteen, and this namespace is where the two vocabularies
  meet — the same arrangement `documents` is for `drive` and the office
  surfaces: whoever depends on both is the application.

  ## A handle is an item id

  `drive` already gives every item a stable identifier that survives
  restarts, which is exactly what NFS requires of a handle and the thing an
  implementation handing out array indices gets wrong. So a handle is the id
  in UTF-8, and the root is the workspace's own root id.

  ## What does not map cleanly, stated rather than hidden

  - **The Drive stores whole objects; NFS writes at an offset.** Every
    partial write is therefore read-modify-write and costs the whole file.
    Fine for documents, wrong for a database file, and the reason
    `:max-file-bytes` exists.
  - **A new file has no bytes until the first write.** `drive.object` has
    no concept of an empty version and `documents/upload!` refuses zero
    bytes, so `-create` makes the item and nothing else. `-attrs` reports
    size 0 for an item with no version, which is what the client expects.
  - **Delete is trash, not purge.** `ws/trash` is reversible and `rm` over
    NFS should not be the one irreversible path into this Drive. Emptying
    the trash stays a decision someone makes in the app.
  - **Permissions are the Drive's, not the mount's.** Every read and write
    goes through `drive.object`, so a share that expired is refused here for
    the same reason it is refused in the browser. NFS mode bits are derived
    from that answer rather than being a second opinion.

  ## Times

  `drive` records `:drive/created-at` as an ISO instant and nothing else. NFS
  wants three times and clients show `mtime`. Reporting the creation instant
  for all three is honest — it is the only one that exists — and better than
  reporting `now`, which makes every file look modified on every listing."
  (:require [clojure.string :as str]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.store :as store]
            [drive.object :as object]
            [drive.workspace :as ws]
            [nfs.v3 :as nfs]
            [xdr.core :as xdr]))

(def ^:const default-max-file-bytes
  "Above this, `-write` refuses rather than rewriting the whole object for a
  4 KiB change. A ceiling that is stated is better than a filesystem that
  becomes mysteriously slow."
  (* 64 1024 1024))

;; ── handles ───────────────────────────────────────────────────────────────

(defn- handle-of [id] (xdr/->bytes (str id)))
(defn- id-of [handle] (xdr/utf8 (xdr/->bytes handle)))

(defn- fileid-of
  "A stable 63-bit integer per item id. Clients use it to tell two files
  apart; deriving it from the id keeps it stable across restarts, which
  `hash` alone would not guarantee across JVM versions."
  [id]
  (let [bytes (.getBytes ^String (str id) "UTF-8")]
    (loop [i 0 h 1125899906842597]
      (if (= i (alength bytes))
        (bit-and h 0x7fffffffffffffff)
        (recur (inc i) (unchecked-add (unchecked-multiply h 31)
                                      (long (aget bytes i))))))))

(defn- millis [instant]
  (try (.toEpochMilli (java.time.Instant/parse (str instant)))
       (catch Exception _ 0)))

;; ── attributes ────────────────────────────────────────────────────────────

(defn- size-of
  "The latest version's recorded size, or zero for an item with no version.

  From the model rather than the store: asking the object store would turn
  every directory listing into one network round trip per file, and the
  model already records it at write time."
  [item]
  (or (some-> (peek (:drive/versions item)) :drive.version/size-bytes) 0))

(defn- mode-of [workspace id actor kind]
  (let [role (ws/effective-role workspace id actor)
        writable? (contains? #{:owner :editor} role)]
    (if (= :folder kind)
      (if writable? 0755 0555)
      (if writable? 0644 0444))))

;; ── the filesystem ────────────────────────────────────────────────────────

(defn- located
  "The workspace holding `id` and the item, through the same lookup the
  browser uses — so a share that expired is invisible here too."
  [actor id]
  (let [{:keys [workspace] :as found} (documents/locate (store/snapshot) actor id)]
    (when found
      (when-let [item (ws/item workspace id)]
        {:workspace workspace :item item :owner (:owner found)}))))

(defrecord DriveFilesystem [actor object-store max-file-bytes]
  nfs/IFilesystem
  (-root [_]
    (handle-of (:drive.workspace/root-id
                (documents/workspace-for (store/snapshot) actor))))

  (-attrs [_ handle]
    (let [id (id-of handle)
          {:keys [workspace item]} (located actor id)]
      (if (nil? item)
        {:error nfs/NFS3ERR_NOENT}
        (let [folder? (= :folder (:drive/kind item))]
          {:type (if folder? nfs/NF3DIR nfs/NF3REG)
           :size (if folder? 4096 (size-of item))
           :mode (mode-of workspace id actor (:drive/kind item))
           :fileid (fileid-of id)
           :mtime (millis (:drive/created-at item))
           :nlink (if folder?
                    (+ 2 (count (ws/children workspace id actor)))
                    1)}))))

  (-lookup [_ dir-handle name]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace item]} (located actor dir-id)]
      (cond
        (nil? item) {:error nfs/NFS3ERR_NOENT}
        (not= :folder (:drive/kind item)) {:error nfs/NFS3ERR_NOTDIR}
        :else
        (if-let [hit (first (filter #(= name (:drive/title %))
                                    (ws/children workspace dir-id actor)))]
          (handle-of (:drive/id hit))
          {:error nfs/NFS3ERR_NOENT}))))

  (-readdir [_ dir-handle cookie max-entries]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace item]} (located actor dir-id)]
      (if (or (nil? item) (not= :folder (:drive/kind item)))
        {:error nfs/NFS3ERR_NOTDIR}
        ;; Sorted by title so a cookie keeps meaning the same place between
        ;; calls. `ws/children` returns insertion order, which is right for
        ;; the model and wrong for a cursor: a file created between two
        ;; READDIRs would shift every later entry and the client would skip
        ;; or repeat one.
        (let [all (vec (sort-by :drive/title (ws/children workspace dir-id actor)))
              from (min (int cookie) (count all))
              to (min (count all) (+ from max-entries))]
          {:entries (map-indexed
                     (fn [i child]
                       {:name (:drive/title child)
                        :fileid (fileid-of (:drive/id child))
                        :cookie (+ from i 1)
                        :handle (handle-of (:drive/id child))})
                     (subvec all from to))
           :eof? (>= to (count all))}))))

  (-read [_ handle offset count]
    (let [id (id-of handle)
          {:keys [workspace item]} (located actor id)]
      (cond
        (nil? item) {:error nfs/NFS3ERR_NOENT}
        (= :folder (:drive/kind item)) {:error nfs/NFS3ERR_ISDIR}
        (nil? (:drive/object-ref item)) {:bytes (xdr/->bytes []) :eof? true}
        :else
        (let [result (object/read-item workspace object-store id actor)]
          (if-not (:ok? result)
            {:error (if (= :not-permitted (:reason result))
                      nfs/NFS3ERR_ACCES
                      nfs/NFS3ERR_IO)}
            (let [all (xdr/->bytes (:bytes result))
                  len (alength ^bytes all)
                  from (min offset len)
                  to (min len (+ from count))]
              {:bytes (java.util.Arrays/copyOfRange ^bytes all (int from) (int to))
               :eof? (>= to len)}))))))

  (-write [_ handle offset data]
    (let [id (id-of handle)
          {:keys [workspace item owner]} (located actor id)]
      (cond
        (nil? item) {:error nfs/NFS3ERR_NOENT}
        (= :folder (:drive/kind item)) {:error nfs/NFS3ERR_ISDIR}
        :else
        ;; Read-modify-write, because `drive` stores whole objects. Stated in
        ;; the namespace docstring and bounded here rather than discovered as
        ;; a slow mount.
        (let [existing (if (:drive/object-ref item)
                         (let [r (object/read-item workspace object-store id actor)]
                           (if (:ok? r) (xdr/->bytes (:bytes r)) (byte-array 0)))
                         (byte-array 0))
              dlen (alength ^bytes data)
              end (max (alength ^bytes existing) (+ offset dlen))]
          (if (> end max-file-bytes)
            {:error nfs/NFS3ERR_FBIG}
            (let [out (byte-array end)]
              (System/arraycopy existing 0 out 0 (alength ^bytes existing))
              (System/arraycopy data 0 out (int offset) dlen)
              (let [written (object/write-item
                             workspace object-store id actor out
                             {:object-ref (documents/object-ref-for object-store out)
                              :created-at (store/now)})]
                (if (:ok? written)
                  (do (documents/save-workspace! owner (:workspace written))
                      {:count dlen})
                  {:error (case (:reason written)
                            :not-permitted nfs/NFS3ERR_ACCES
                            :quota-exceeded nfs/NFS3ERR_NOSPC
                            nfs/NFS3ERR_IO)}))))))))

  (-create [_ dir-handle name _attrs]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace item owner]} (located actor dir-id)]
      (if (or (nil? item) (not= :folder (:drive/kind item)))
        {:error nfs/NFS3ERR_NOTDIR}
        (let [id (store/new-id "file")
              staged (ws/create-file workspace id dir-id
                                     {:drive/title name
                                      :drive/media-type "application/octet-stream"
                                      :drive/created-at (store/now)}
                                     actor)]
          (documents/save-workspace! owner staged)
          (handle-of id)))))

  (-mkdir [_ dir-handle name _attrs]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace item owner]} (located actor dir-id)]
      (if (or (nil? item) (not= :folder (:drive/kind item)))
        {:error nfs/NFS3ERR_NOTDIR}
        (let [id (store/new-id "folder")
              staged (ws/create-folder workspace id dir-id name actor)]
          (documents/save-workspace! owner staged)
          (handle-of id)))))

  (-remove [this dir-handle name]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace owner]} (located actor dir-id)
          hit (first (filter #(= name (:drive/title %))
                             (ws/children workspace dir-id actor)))]
      (if (nil? hit)
        {:error nfs/NFS3ERR_NOENT}
        ;; Trash, not purge. `rm` over a mount should not be the one
        ;; irreversible path into this Drive.
        (do (documents/save-workspace! owner (ws/trash workspace (:drive/id hit)))
            true))))

  (-rmdir [this dir-handle name]
    (let [dir-id (id-of dir-handle)
          {:keys [workspace]} (located actor dir-id)
          hit (first (filter #(= name (:drive/title %))
                             (ws/children workspace dir-id actor)))]
      (cond
        (nil? hit) {:error nfs/NFS3ERR_NOENT}
        (seq (ws/children workspace (:drive/id hit) actor))
        {:error nfs/NFS3ERR_NOTEMPTY}
        :else (nfs/-remove this dir-handle name))))

  (-rename [_ from-dir from-name to-dir to-name]
    (let [from-id (id-of from-dir)
          to-id (id-of to-dir)
          {:keys [workspace owner]} (located actor from-id)
          hit (first (filter #(= from-name (:drive/title %))
                             (ws/children workspace from-id actor)))]
      (if (nil? hit)
        {:error nfs/NFS3ERR_NOENT}
        (let [id (:drive/id hit)
              renamed (assoc-in workspace
                                [:drive.workspace/items id :drive/title] to-name)
              moved (if (= from-id to-id)
                      renamed
                      (let [r (ws/move renamed id to-id)]
                        (if (map? r) r renamed)))]
          (documents/save-workspace! owner moved)
          true))))

  (-setattr [this handle attrs]
    (let [id (id-of handle)]
      (when-let [size (:size attrs)]
        (let [current (nfs/-read this handle 0 (int (min size max-file-bytes)))]
          (when-not (:error current)
            (let [out (byte-array (int size))
                  have (xdr/->bytes (:bytes current))]
              (System/arraycopy have 0 out 0
                                (int (min size (alength ^bytes have))))
              (nfs/-write this handle 0 out)))))
      (nfs/-attrs this handle)))

  (-fsstat [_ _]
    (let [workspace (documents/workspace-for (store/snapshot) actor)
          quota (or (:drive.workspace/quota-bytes workspace) 0)
          used (or (:drive.workspace/used-bytes workspace) 0)
          free (max 0 (- quota used))]
      {:tbytes quota :fbytes free :abytes free
       :tfiles 1000000 :ffiles 1000000 :afiles 1000000})))

(defn filesystem
  "The Drive of `actor`, as a filesystem."
  ([actor] (filesystem actor {}))
  ([actor {:keys [object-store max-file-bytes]
           :or {max-file-bytes default-max-file-bytes}}]
   (->DriveFilesystem actor
                      (or object-store (documents/store-instance))
                      max-file-bytes)))
