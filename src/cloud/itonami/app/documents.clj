(ns cloud.itonami.app.documents
  "Creating a Sheets workbook, a Docs document or a Forms form in the Drive.

  Four libraries meet here and none of them knows about the others.
  `sheets`, `docs` and `forms` are portable models that produce an office
  envelope; `drive` owns the tree, the ACL, the quota and the versions, and
  `drive.object` owns the byte boundary. The join is this namespace, which is
  the arrangement `drive.object/store-of` prescribes for object stores and
  the same one applies to content: whoever depends on both is the
  application.

  ## What is stored is the envelope, not the model

  A new workbook is serialized through `sheets.wire/workbook-envelope` and
  the JSON of that envelope is what goes into the object store. So the bytes
  in the Drive are self-describing — they carry `:kotoba.resource/kind` and
  the protocol version — and a reader does not have to already know which of
  the three surfaces it is holding.

  It also means the read side gives back plain JSON rather than the EDN that
  went in. `transit.core/read-office-envelope-body` says so: the projection
  is lossy and callers convert if they need EDN. `content` therefore returns
  the payload as it comes back, and does not pretend otherwise by round-
  tripping it through the model constructors.

  ## One workspace per user, and why not one shared one

  `drive.workspace` is a tenant Drive: a root folder, an owner, a quota. Its
  root grants `:owner` to whoever created it and nobody else, so a single
  shared workspace would answer `:not-permitted` to the second user who ever
  signed in — and the fix for that is a sharing policy, which is a decision
  this app has not made and should not make silently by granting every
  authenticated principal `:editor` on everything.

  So each user gets their own, keyed by `:user-id`, exactly as
  `drive.workspace` is shaped for. Sharing between them is a later feature
  with a real decision behind it; `drive.workspace/grant` and
  `create-share-link` are already there for when it is made.

  ## The archive is not this

  `cloud.itonami.app.workspace/drive-snapshot` reads the read-only OneDrive
  archive and is unaffected by any of this. `drive-view` merges the two for
  the UI and marks each item with `:origin`, because \"can I edit this\" has
  different answers on either side and a merged list that did not say so
  would be inviting the wrong one."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.storj :as storj]
            [docs.model :as docs]
            [docs.wire :as docs-wire]
            [drive.object :as object]
            [drive.store.fs :as fs]
            [drive.workspace :as ws]
            [forms.model :as forms]
            [forms.wire :as forms-wire]
            [sheets.model :as sheets]
            [sheets.wire :as sheets-wire])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def schema "cloud.itonami.app.documents.v1")

(def quota-bytes
  "Per-user Drive quota.

  `drive.workspace` requires one — `add-version` throws when a write would
  exceed it — so this is a number that has to exist rather than a limit
  anyone has asked for. A gibibyte of JSON envelopes is far more documents
  than this app has any way to produce."
  (* 1024 1024 1024))

(def kinds
  "The three surfaces, and how each one is seeded, wrapped and read back.

  A closed table rather than a naming convention: `:kind` arrives from an
  HTTP request, and a convention would turn a typo into a namespace lookup."
  {:sheets {:resource-kind :sheets/workbook
            :label "スプレッドシート"
            :default-title "無題のスプレッドシート"
            :seed (fn [id title]
                    (-> (sheets/workbook id {:sheets/title title})
                        ;; A workbook with no tabs has nowhere to put a cell,
                        ;; which is a state the editor would have to handle
                        ;; and no user ever asks for.
                        (sheets/add-tab (sheets/tab "sheet1" {:sheets/title "Sheet1"}))))
            :envelope sheets-wire/workbook-envelope
            :read sheets-wire/read-workbook-envelope}
   :docs {:resource-kind :docs/document
          :label "ドキュメント"
          :default-title "無題のドキュメント"
          :seed (fn [id title]
                  (-> (docs/document id {:docs/title title})
                      (docs/add-block (docs/heading "title" 1 title))))
          :envelope docs-wire/document-envelope
          :read docs-wire/read-document-envelope}
   :forms {:resource-kind :forms/form
           :label "フォーム"
           :default-title "無題のフォーム"
           ;; No seed field: an empty form is valid, and a placeholder
           ;; question is one the author has to notice and delete.
           :seed (fn [id title] (forms/form id {:forms/title title}))
           :envelope forms-wire/form-envelope
           :read forms-wire/read-form-envelope}})

(def ^:private resource-kinds
  (into {} (map (juxt (comp :resource-kind val) key)) kinds))

;; ── the object store ────────────────────────────────────────────────────────

(defn- objects-dir []
  (.getPath (io/file (config/data-dir) "drive-objects")))

(defn default-store
  "The object store new documents are written to.

  Storj when it is configured, and a directory under the data dir when it is
  not. Not Filecoin: `cloud.itonami.app.filecoin` addresses by content, so it
  names its own references, and `drive.object/write-item` requires the caller
  to name one. The two are not interchangeable at this seam and the
  difference is silent if it is not said — see `cloud.itonami.app.storj`."
  []
  (if (storj/configured?)
    (storj/store {:prefix "drive/"})
    (fs/store (objects-dir))))

(defonce ^:private cached-store (atom nil))

(defn store-instance []
  (or @cached-store (reset! cached-store (default-store))))

;; ── workspaces ──────────────────────────────────────────────────────────────

(defn- workspace-path [actor] [:drive :workspaces actor])

(defn workspace-for
  "This principal's Drive, created empty if they have never had one.

  Not persisted here: an empty workspace is exactly what the next call would
  build anyway, so writing one on a read would put a row in the state file
  for every principal that has ever loaded the Drive tab."
  [state actor]
  (or (get-in state (workspace-path actor))
      (ws/workspace (str "drive-" actor) actor quota-bytes)))

(defn- refuse!
  "Turn a `drive.object` refusal into the app's HTTP error shape.

  The reasons are drive's vocabulary and the types are this app's, mapped
  rather than passed through: `:not-permitted` is a 403 and `:no-such-item`
  is a 404, and a caller that saw the raw reason would have to know that."
  [{:keys [reason] :as refusal}]
  (throw (ex-info
          (case reason
            :not-permitted "このドキュメントを操作する権限がありません。"
            :no-such-item "ドキュメントが見つかりません。"
            :item-is-trashed "このドキュメントはゴミ箱にあります。"
            :no-content "このドキュメントにはまだ内容がありません。"
            :object-missing-from-store "保管されているはずの内容が見つかりません。"
            :quota-exceeded "Drive の容量が上限に達しています。"
            "ドキュメントを操作できませんでした。")
          (assoc refusal
                 :type (case reason
                         :not-permitted :drive/not-permitted
                         :no-such-item :drive/not-found
                         :item-is-trashed :drive/not-found
                         :no-content :drive/no-content
                         :object-missing-from-store :drive/object-missing
                         :quota-exceeded :drive/quota-exceeded
                         :drive/refused)))))

;; ── bytes ───────────────────────────────────────────────────────────────────

(defn envelope-bytes
  "The JSON of an office envelope, as the vector of unsigned ints `drive`
  wants.

  Explicitly rather than by handing `write-item` a string: `count` on a
  string is characters, and the docstring on `write-item` is about exactly
  that drift — a title in Japanese would be charged three bytes against the
  quota and store nine.

  `:escape-unicode false` because the bytes are UTF-8 and saying so once is
  cheaper than saying `\\u554f` three times. `data.json` escapes by default,
  which is the safe choice for a wire whose encoding is unknown and the
  wrong one for a store where it is decided here. `:escape-slash false` for
  the same reason and not the same risk: the default exists so JSON can sit
  inside a `<script>` element, and these bytes go to an object store. What
  the HTTP layer sends is re-serialized by `send!` with the defaults intact."
  [envelope]
  (mapv #(bit-and (int %) 0xff)
        (.getBytes (json/write-str (:body envelope)
                                   :escape-unicode false :escape-slash false)
                   StandardCharsets/UTF_8)))

(defn- bytes->string [bytes]
  (String. (byte-array (map unchecked-byte bytes)) StandardCharsets/UTF_8))

;; ── views ───────────────────────────────────────────────────────────────────

(defn- latest-size [item]
  (or (some-> (peek (:drive/versions item)) :drive.version/size-bytes)
      0))

(defn item-view
  "One created document, in the shape the Drive list already renders."
  [item]
  (let [kind (:drive/resource-kind item)]
    {:id (:drive/id item)
     :name (:drive/title item)
     :folder "マイドライブ"
     :media-type (:drive/media-type item)
     :resource-kind (some-> kind str)
     :kind (some-> (get resource-kinds kind) name)
     :label (get-in kinds [(get resource-kinds kind) :label])
     :created-at (:drive/created-at item)
     :versions (count (:drive/versions item))
     :size-bytes (latest-size item)
     :available? true
     :origin "workspace"}))

(defn documents
  "Every document this principal can see, newest first."
  [state actor]
  (let [workspace (workspace-for state actor)]
    (->> (ws/visible-items workspace actor)
         (filter #(= :file (:drive/kind %)))
         (sort-by :drive/created-at)
         reverse
         (mapv item-view))))

(defn drive-view
  "The archive snapshot with this principal's created documents in front.

  Created documents lead because they are the ones that just changed; the
  archive is eighty files that have not moved since they were exported."
  [archive actor]
  (let [created (documents (store/snapshot) actor)
        archived (mapv #(assoc % :origin "archive") (:items archive))]
    (assoc archive
           :schema schema
           :items (into created archived)
           :count (+ (count created) (count archived))
           :documents (count created)
           :kinds (mapv (fn [[k spec]]
                          {:kind (name k) :label (:label spec)
                           :resource-kind (str (:resource-kind spec))})
                        kinds)
           :source (str (:source archive) " · 作成済み " (count created) " 件"))))

;; ── creating ────────────────────────────────────────────────────────────────

(defonce ^:private write-lock (Object.))

(defn- object-ref []
  ;; `drive.store.fs` refuses a reference that could become a path, so this
  ;; stays inside its alphabet on purpose.
  (str "obj-" (UUID/randomUUID)))

(defn create!
  "Create a document of `kind` in `actor`'s Drive and return its item view.

  The order is `drive.object`'s: the item exists in the model, then the
  bytes are written, then the version is recorded — so a store failure
  leaves nothing behind claiming bytes that are not there. State is only
  persisted once `write-item` has said yes.

  The read-then-write is inside a lock rather than inside `store/transact!`
  because the write is IO: `transact!` is a `swap!`, and a `swap!` that
  retries would put the object twice under two references, the second of
  which nothing would ever reference again."
  ([kind title actor] (create! kind title actor (store-instance)))
  ([kind title actor object-store]
   (let [spec (get kinds kind)]
     (when-not spec
       (throw (ex-info "作成できるのはスプレッドシート・ドキュメント・フォームだけです。"
                       {:type :drive/unknown-kind :kind kind
                        :known (vec (keys kinds))})))
     (when (str/blank? (str actor))
       (throw (ex-info "作成者を特定できません。" {:type :identity/unauthenticated})))
     (locking write-lock
       (let [workspace (workspace-for (store/snapshot) actor)
             id (store/new-id "doc")
             title (or (not-empty (str/trim (str title))) (:default-title spec))
             created-at (store/now)
             envelope ((:envelope spec) ((:seed spec) id title))
             staged (ws/create-file workspace id (:drive.workspace/root-id workspace)
                                    {:drive/title title
                                     :drive/media-type (:content-type envelope)
                                     :drive/resource-kind (:resource-kind spec)
                                     :drive/created-at created-at}
                                    actor)
             written (object/write-item staged object-store id actor
                                        (envelope-bytes envelope)
                                        {:object-ref (object-ref)
                                         :created-at created-at})]
         (if (:ok? written)
           (do (store/transact! assoc-in (workspace-path actor) (:workspace written))
               {:schema schema
                :ok? true
                :item (item-view (ws/item (:workspace written) id))})
           (refuse! written)))))))

(defn content
  "The stored envelope of one document, read back through the ACL.

  `drive.object/read-item` is what answers whether this principal may have
  the bytes; nothing here consults the store directly, which is the whole
  reason that boundary is in `drive` rather than in each application."
  ([id actor] (content id actor (store-instance)))
  ([id actor object-store]
   (let [workspace (workspace-for (store/snapshot) actor)
         result (object/read-item workspace object-store id actor)]
     (if (:ok? result)
       (let [item (ws/item workspace id)
             kind (get resource-kinds (:drive/resource-kind item))
             body (json/read-str (bytes->string (:bytes result)))]
         {:schema schema
          :ok? true
          :item (item-view item)
          :resource-kind (some-> (:drive/resource-kind item) str)
          ;; Read through the surface's own reader, so a body whose
          ;; discriminant disagrees with the item's recorded kind is refused
          ;; here rather than surfacing as a confusing render later.
          :payload (if-let [read-envelope (get-in kinds [kind :read])]
                     (read-envelope body)
                     body)})
       (refuse! result)))))

(defn trash!
  "Move a document to the trash.

  Trash, not `forget-item`: trashing is reversible and forgetting is not,
  and `drive.object/forget-item` says in as many words that wiring the two
  together makes deletion silent and permanent."
  [id actor]
  (locking write-lock
    (let [workspace (workspace-for (store/snapshot) actor)]
      (cond
        (nil? (ws/item workspace id)) (refuse! {:reason :no-such-item :item-id id})
        (not (ws/can-write? workspace id actor))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [trashed (ws/trash workspace id)]
          (store/transact! assoc-in (workspace-path actor) trashed)
          {:schema schema :ok? true :id id})))))
