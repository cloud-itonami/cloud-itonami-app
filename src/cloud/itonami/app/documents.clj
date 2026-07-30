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
            [docs.validate :as docs-validate]
            [docs.wire :as docs-wire]
            [drive.object :as object]
            [drive.store.fs :as fs]
            [drive.workspace :as ws]
            [forms.model :as forms]
            [forms.validate :as forms-validate]
            [forms.wire :as forms-wire]
            [sheets.model :as sheets]
            [sheets.validate :as sheets-validate]
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
  "The three surfaces, and how each one is seeded, wrapped, read and checked.

  A closed table rather than a naming convention: `:kind` arrives from an
  HTTP request, and a convention would turn a typo into a namespace lookup.

  `:problems` and the three keys under it exist because the surfaces do not
  agree on what a problem looks like — `sheets.validate` reports
  `:sheets/severity` and `:sheets/msg`, `docs.validate` reports
  `:docs/severity` and `:docs/msg`, and `forms.validate` names its entry
  point `form-problems` rather than `problems`. Normalising here is not an
  endorsement of that; it is the app declining to leak three vocabularies
  into one HTTP response."
  {:sheets {:resource-kind :sheets/workbook
            :label "スプレッドシート"
            :default-title "無題のスプレッドシート"
            :title-key :sheets/title
            :seed (fn [id title]
                    (-> (sheets/workbook id {:sheets/title title})
                        ;; A workbook with no tabs has nowhere to put a cell,
                        ;; which is a state the editor would have to handle
                        ;; and no user ever asks for.
                        (sheets/add-tab (sheets/tab "sheet1" {:sheets/title "Sheet1"}))))
            :envelope sheets-wire/workbook-envelope
            :read sheets-wire/read-workbook-envelope
            :rehydrate sheets-wire/rehydrate-workbook
            :problems sheets-validate/problems
            :severity :sheets/severity :code :sheets/code :message :sheets/msg}
   :docs {:resource-kind :docs/document
          :label "ドキュメント"
          :default-title "無題のドキュメント"
          :title-key :docs/title
          :seed (fn [id title]
                  (-> (docs/document id {:docs/title title})
                      (docs/add-block (docs/heading "title" 1 title))))
          :envelope docs-wire/document-envelope
          :read docs-wire/read-document-envelope
          :rehydrate docs-wire/rehydrate-document
          :problems docs-validate/problems
          :severity :docs/severity :code :docs/code :message :docs/msg}
   :forms {:resource-kind :forms/form
           :label "フォーム"
           :default-title "無題のフォーム"
           :title-key :forms/title
           ;; No seed field: an empty form is valid, and a placeholder
           ;; question is one the author has to notice and delete.
           :seed (fn [id title] (forms/form id {:forms/title title}))
           :envelope forms-wire/form-envelope
           :read forms-wire/read-form-envelope
           :rehydrate forms-wire/rehydrate-form
           :problems forms-validate/form-problems
           :severity :forms/severity :code :forms/code :message :forms/msg}})

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

(defn- latest-version [item]
  (peek (:drive/versions item)))

(defn- held-bytes
  "What this item costs the quota: every version, not only the newest.

  `add-version` adds each version's size to `:drive.workspace/used-bytes` and
  never subtracts, so an item with six versions is holding six versions'
  worth. Showing only the newest would make a Drive that is filling up look
  like one that is not."
  [item]
  (reduce + 0 (keep :drive.version/size-bytes (:drive/versions item))))

(defn item-view
  "One created document, in the shape the Drive list already renders."
  [item]
  (let [kind (:drive/resource-kind item)
        newest (latest-version item)]
    {:id (:drive/id item)
     :name (:drive/title item)
     :folder "マイドライブ"
     :media-type (:drive/media-type item)
     :resource-kind (some-> kind str)
     :kind (some-> (get resource-kinds kind) name)
     :label (get-in kinds [(get resource-kinds kind) :label])
     :created-at (:drive/created-at item)
     :updated-at (:drive.version/created-at newest)
     :versions (count (:drive/versions item))
     :size-bytes (or (:drive.version/size-bytes newest) 0)
     :held-bytes (held-bytes item)
     :trashed? (boolean (:drive/trashed? item))
     :available? true
     :origin "workspace"}))

(defn- readable-files
  [workspace actor]
  (->> (vals (:drive.workspace/items workspace))
       (filter #(and (= :file (:drive/kind %))
                     (ws/can-read? workspace (:drive/id %) actor)))))

(defn- newest-first [items]
  (->> items
       (sort-by (juxt #(or (:drive.version/created-at (latest-version %)) "")
                      :drive/created-at))
       reverse
       (mapv item-view)))

(defn documents
  "Every document this principal can see, most recently written first.

  By last write rather than by creation, because a list that does not move
  when something is saved is a list that cannot be used to find what was
  just saved."
  [state actor]
  (let [workspace (workspace-for state actor)]
    (newest-first (remove :drive/trashed? (readable-files workspace actor)))))

(defn trashed
  "Everything this principal has trashed.

  A separate call rather than a flag on `documents`, because the two are
  answers to different questions and the trash is not a place anything
  should appear by accident."
  [state actor]
  (let [workspace (workspace-for state actor)]
    (newest-first (filter :drive/trashed? (readable-files workspace actor)))))

(defn quota-view [state actor]
  (let [workspace (workspace-for state actor)]
    {:used-bytes (:drive.workspace/used-bytes workspace)
     :quota-bytes (:drive.workspace/quota-bytes workspace)}))

(defn drive-view
  "The archive snapshot with this principal's created documents in front.

  Created documents lead because they are the ones that just changed; the
  archive is eighty files that have not moved since they were exported.

  The trash rides along rather than being fetched separately: it is the only
  place quota goes to be reclaimed, and a Drive that never shows it is one
  where nobody finds out why it is full."
  [archive actor]
  (let [state (store/snapshot)
        created (documents state actor)
        binned (trashed state actor)
        archived (mapv #(assoc % :origin "archive") (:items archive))]
    (assoc archive
           :schema schema
           :items (into created archived)
           :trash binned
           :count (+ (count created) (count archived))
           :documents (count created)
           :quota (quota-view state actor)
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

;; ── editing ─────────────────────────────────────────────────────────────────

(defn- spec-of-item [item]
  (get kinds (get resource-kinds (:drive/resource-kind item))))

(defn problems-in
  "What the surface says about `resource`, in one shape rather than three.

  Split by severity rather than filtered down to errors. Only errors block a
  save — `docs.validate` reports a missing title as a warning, and refusing
  over it would make the surface unusable for a draft — but a warning that is
  computed and then dropped is a warning nobody ever sees, which is the same
  as not having run the validator at all."
  [spec resource]
  (let [shape (fn [problem]
                {:code (some-> (get problem (:code spec)) str)
                 :message (get problem (:message spec))})
        by-severity (group-by #(get % (:severity spec)) ((:problems spec) resource))]
    {:errors (mapv shape (get by-severity :error))
     :warnings (mapv shape (get by-severity :warning))}))

(defn errors-in [spec resource]
  (:errors (problems-in spec resource)))

(defn- writable!
  "The workspace, item and spec for a write, or the refusal that stops it."
  [actor id]
  (let [workspace (workspace-for (store/snapshot) actor)
        item (ws/item workspace id)]
    (cond
      (nil? item) (refuse! {:reason :no-such-item :item-id id})
      (:drive/trashed? item) (refuse! {:reason :item-is-trashed :item-id id})
      (not (ws/can-write? workspace id actor))
      (refuse! {:reason :not-permitted :item-id id :principal actor})
      :else
      (if-let [spec (spec-of-item item)]
        {:workspace workspace :item item :spec spec}
        (throw (ex-info "このドキュメントの種類を判別できません。"
                        {:type :drive/unknown-kind
                         :resource-kind (:drive/resource-kind item)}))))))

(defn- write-resource!
  "Validate a rehydrated `resource` and store it as a new version of `id`.

  Validated before anything is written, and re-projected from the value the
  model accepted rather than from the bytes a client happened to send. The
  envelope is rebuilt from the kind already on the item, so a save cannot
  turn a document into a workbook by rewriting its discriminant.

  A new object reference every time: `drive.object/write-item` refuses a
  reused one, and the reason is the one that matters — reusing a reference
  replaces an earlier version's bytes while the history saying otherwise is
  still sitting in `:drive/versions`."
  [{:keys [workspace item spec]} id actor object-store resource]
  (let [{:keys [errors warnings]} (problems-in spec resource)]
    (when (seq errors)
      (throw (ex-info (str "保存できません: " (:message (first errors)))
                      {:type :drive/invalid-document :problems errors})))
    (let [envelope ((:envelope spec) resource)
          title (or (not-empty (str/trim (str (get resource (:title-key spec)))))
                    (:drive/title item))
          ;; The resource's title and the Drive item's title are two places
          ;; for one fact, so a save keeps them together rather than letting
          ;; the list disagree with what is open.
          retitled (assoc-in workspace [:drive.workspace/items id :drive/title] title)
          written (object/write-item retitled object-store id actor
                                     (envelope-bytes envelope)
                                     {:object-ref (object-ref)
                                      :created-at (store/now)})]
      (if (:ok? written)
        (do (store/transact! assoc-in (workspace-path actor) (:workspace written))
            {:schema schema
             :ok? true
             :item (item-view (ws/item (:workspace written) id))
             ;; Reported, not swallowed. The save went through; the surface
             ;; still had something to say about what was saved.
             :warnings warnings
             :quota (quota-view (store/snapshot) actor)})
        (refuse! written)))))

(defn update!
  "Store an edited `payload` as a new version of `id`.

  `payload` is the plain-JSON projection, as `content` returns it, and only
  the payload — never a whole envelope.

  Rehydrated before it is validated, because validation reads namespaced
  keys and a projected payload has none: `sheets.validate/problems` on a
  string-keyed map finds no tabs, reports no problems, and waves anything
  through. That failure is silent in the direction that matters, which is
  why the rehydrate step is not an optimisation."
  ([id payload actor] (update! id payload actor (store-instance)))
  ([id payload actor object-store]
   (locking write-lock
     (let [{:keys [spec] :as target} (writable! actor id)]
       (write-resource! target id actor object-store ((:rehydrate spec) payload))))))

(defn rename!
  "Change a document's title.

  This does record a new version, because the title is not only Drive
  metadata — it is inside the stored resource as `:sheets/title` and its
  siblings. Renaming only the Drive item would leave the two disagreeing,
  and the one that travels with the bytes is the one another reader sees."
  ([id title actor] (rename! id title actor (store-instance)))
  ([id title actor object-store]
   (locking write-lock
     (let [{:keys [spec] :as target} (writable! actor id)
           title (not-empty (str/trim (str title)))]
       (when-not title
         (throw (ex-info "名前を空にはできません。"
                         {:type :drive/invalid-document
                          :problems [{:code ":title/blank"
                                      :message "名前を空にはできません。"}]})))
       (let [current (content id actor object-store)
             resource (assoc ((:rehydrate spec) (:payload current))
                             (:title-key spec) title)]
         (write-resource! target id actor object-store resource))))))

(defn version-content
  "The stored envelope of one *earlier* version of `id`.

  `drive.object/read-item` only ever reads `:drive/object-ref`, which is the
  newest — an older version's bytes are reachable only by going to the store
  with that version's own reference. So this asks `drive.object/readable?`
  first, which is the library's own answer to whether this principal may
  have these bytes at all, trash included. Asking the store directly without
  it would be the second permission answer that `drive.object` exists to
  prevent.

  `index` is 1-based and matches what `item-view` reports as `:versions`."
  ([id index actor] (version-content id index actor (store-instance)))
  ([id index actor object-store]
   (let [workspace (workspace-for (store/snapshot) actor)
         item (ws/item workspace id)
         versions (:drive/versions item)
         version (get versions (dec index))]
     (cond
       (nil? item) (refuse! {:reason :no-such-item :item-id id})
       (not (object/readable? workspace id actor))
       (refuse! (if (ws/can-read? workspace id actor)
                  {:reason :item-is-trashed :item-id id}
                  {:reason :not-permitted :item-id id :principal actor}))
       (nil? version)
       (throw (ex-info (str "版 " index " はありません。")
                       {:type :drive/not-found :item-id id :index index
                        :versions (count versions)}))
       :else
       (if-let [bytes (object/-get-object object-store
                                          (:drive.version/object-ref version))]
         (let [kind (get resource-kinds (:drive/resource-kind item))
               body (json/read-str (bytes->string bytes))]
           {:schema schema
            :ok? true
            :item (item-view item)
            :index index
            :created-at (:drive.version/created-at version)
            :resource-kind (some-> (:drive/resource-kind item) str)
            :payload (if-let [read-envelope (get-in kinds [kind :read])]
                       (read-envelope body)
                       body)})
         (refuse! {:reason :object-missing-from-store :item-id id
                   :object-ref (:drive.version/object-ref version)}))))))

(defn trash!
  "Move a document to the trash.

  Trash, not `forget-item`: trashing is reversible and forgetting is not,
  and `drive.object/forget-item` says in as many words that wiring the two
  together makes deletion silent and permanent. `purge!` is where the
  irreversible one lives, and it refuses anything that is not here first."
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
          {:schema schema :ok? true :id id
           :item (item-view (ws/item trashed id))})))))

(defn restore!
  "Take a document back out of the trash.

  The half of trashing that makes it reversible, and therefore the half that
  has to exist before trashing is honest. Without it the trash is a sink and
  `trash!` is a delete button that says otherwise."
  [id actor]
  (locking write-lock
    (let [workspace (workspace-for (store/snapshot) actor)
          item (ws/item workspace id)]
      (cond
        (nil? item) (refuse! {:reason :no-such-item :item-id id})
        (not (ws/can-write? workspace id actor))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [restored (ws/restore workspace id)]
          (store/transact! assoc-in (workspace-path actor) restored)
          {:schema schema :ok? true :id id
           :item (item-view (ws/item restored id))})))))

(defn purge!
  "Delete a trashed document's bytes for good, and give the quota back.

  Two things `trash!` deliberately does not do. `drive.workspace/trash` only
  sets a flag: the versions stay, the objects stay, and every one of them is
  still counted in `:drive.workspace/used-bytes` — `add-version` adds and
  nothing ever subtracts. So a Drive whose trash is never emptied fills up
  and cannot say why.

  Refuses anything not already trashed. `drive.object/forget-item` names the
  hazard exactly — a caller that wires it to the trash button has made
  deletion silent and permanent — so the trash is the gate rather than a
  suggestion, and emptying it is a second, separate act."
  ([id actor] (purge! id actor (store-instance)))
  ([id actor object-store]
   (locking write-lock
     (let [workspace (workspace-for (store/snapshot) actor)
           item (ws/item workspace id)]
       (cond
         (nil? item) (refuse! {:reason :no-such-item :item-id id})
         (not (:drive/trashed? item))
         (throw (ex-info "先にゴミ箱へ移動してください。"
                         {:type :drive/not-trashed :item-id id}))
         :else
         (let [forgotten (object/forget-item workspace object-store id actor)]
           (if (:ok? forgotten)
             ;; forget-item empties the item and returns the quota; the item
             ;; itself is what is dropped here, because an entry with no
             ;; versions and no bytes is a row that can only confuse a list.
             (let [without (-> (:workspace forgotten)
                               (update :drive.workspace/items dissoc id)
                               (update-in [:drive.workspace/items
                                           (:drive.workspace/root-id workspace)
                                           :drive/children]
                                          (fn [children]
                                            (vec (remove #{id} children)))))]
               (store/transact! assoc-in (workspace-path actor) without)
               {:schema schema :ok? true :id id
                :freed-bytes (:freed-bytes forgotten)
                :quota (quota-view (store/snapshot) actor)})
             (refuse! forgotten))))))))

(defn empty-trash!
  "Purge everything in the trash, and report what came back.

  One call because emptying a trash one document at a time is how a trash
  stays full."
  ([actor] (empty-trash! actor (store-instance)))
  ([actor object-store]
   (locking write-lock
     (let [ids (mapv :id (trashed (store/snapshot) actor))
           results (mapv #(purge! % actor object-store) ids)]
       {:schema schema :ok? true
        :purged (count results)
        :freed-bytes (reduce + 0 (map :freed-bytes results))
        :quota (quota-view (store/snapshot) actor)}))))
