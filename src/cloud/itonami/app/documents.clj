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
            :severity :sheets/severity :code :sheets/code :message :sheets/msg
            ;; A workbook has no closed vocabulary an editor has to offer —
            ;; a cell holds whatever it holds.
            :vocabulary nil}
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
          :severity :docs/severity :code :docs/code :message :docs/msg
          ;; From `docs.model` rather than restated: the editor offers the
          ;; kinds the validator will accept, and there is one list of them.
          :vocabulary docs/block-kinds}
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
           :severity :forms/severity :code :forms/code :message :forms/msg
           :vocabulary forms/field-types}})

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

(defn- all-workspaces [state]
  (get-in state [:drive :workspaces] {}))

(defn locate
  "Which Drive holds `id`, and whether this principal may read it.

  Everything before sharing looked in `workspace-for` and stopped, which was
  correct while an item could only ever be in the asker's own Drive. A grant
  breaks that: the permission is recorded on the item, and the item lives in
  the *granter's* workspace, so a grantee looking only at their own would be
  told the document does not exist — and a grant nobody can act on is a
  button that does nothing.

  Own Drive first, so the common case does not pay for the scan, and so an
  id that somehow exists in two places resolves to the asker's."
  [state actor id]
  (let [own (workspace-for state actor)]
    (if (ws/item own id)
      {:owner actor :workspace own :own? true}
      (some (fn [[owner workspace]]
              (when (and (not= owner actor)
                         (ws/item workspace id)
                         (ws/can-read? workspace id actor))
                {:owner owner :workspace workspace :own? false}))
            (all-workspaces state)))))

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
            ;; Expired, revoked, and never-existed are one answer on purpose.
            :no-such-link "この共有リンクは無効です。"
            "ドキュメントを操作できませんでした。")
          (assoc refusal
                 :type (case reason
                         :not-permitted :drive/not-permitted
                         :no-such-item :drive/not-found
                         :item-is-trashed :drive/not-found
                         :no-content :drive/no-content
                         :object-missing-from-store :drive/object-missing
                         :quota-exceeded :drive/quota-exceeded
                         :no-such-link :drive/not-found
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
  "One created document, in the shape the Drive list already renders.

  `context` carries who is asking and whose Drive it is in, because after
  sharing those are not the same question. `:role` is the asker's effective
  role, which is what the UI has to know before it offers a save button."
  ([item] (item-view item {}))
  ([item {:keys [owner role own?] :or {own? true}}]
   (let [kind (:drive/resource-kind item)
         newest (latest-version item)]
     {:id (:drive/id item)
      :name (:drive/title item)
      :folder (if own? "マイドライブ" "共有アイテム")
      :media-type (:drive/media-type item)
      :resource-kind (some-> kind str)
      :kind (some-> (get resource-kinds kind) name)
      :label (get-in kinds [(get resource-kinds kind) :label])
      :created-at (:drive/created-at item)
      :updated-at (:drive.version/created-at newest)
      ;; Who last wrote it, which is only a question worth asking because a
      ;; document can now have more than one writer.
      :updated-by (:drive.version/author newest)
      :versions (count (:drive/versions item))
      :history (mapv (fn [version]
                       {:author (:drive.version/author version)
                        :created-at (:drive.version/created-at version)
                        :size-bytes (:drive.version/size-bytes version)})
                     (:drive/versions item))
      :size-bytes (or (:drive.version/size-bytes newest) 0)
      :held-bytes (held-bytes item)
      :trashed? (boolean (:drive/trashed? item))
      :own? own?
      :owner owner
      :role (some-> role name)
      ;; Whether this asker may write, rather than the role alone: the rule
      ;; is `drive.workspace/can-write?`'s and a UI re-deriving it from the
      ;; role string is a second copy of it.
      :writable? (contains? #{:owner :editor} role)
      :available? true
      :origin "workspace"})))

(defn- viewable-files
  "Every file in `workspace` this principal may read, with their role."
  [workspace actor owner]
  (->> (vals (:drive.workspace/items workspace))
       (filter #(= :file (:drive/kind %)))
       (keep (fn [item]
               (when-let [role (ws/effective-role workspace (:drive/id item) actor)]
                 {:item item :role role :owner owner :own? (= owner actor)})))))

(defn- newest-first [entries]
  (->> entries
       (sort-by (juxt #(or (:drive.version/created-at (latest-version (:item %))) "")
                      #(:drive/created-at (:item %))))
       reverse
       (mapv (fn [entry] (item-view (:item entry) entry)))))

(defn- visible-entries
  "Everything this principal can see: their own Drive, and every item shared
  with them from someone else's.

  The scan is over every workspace because a grant is recorded on the item
  rather than anywhere central. That is the cost of per-user workspaces, and
  it is paid here rather than by making the grant invisible."
  [state actor]
  (let [own (viewable-files (workspace-for state actor) actor actor)
        shared (mapcat (fn [[owner workspace]]
                         (when (not= owner actor)
                           (viewable-files workspace actor owner)))
                       (all-workspaces state))]
    (concat own shared)))

(defn documents
  "Every document this principal can see, most recently written first.

  By last write rather than by creation, because a list that does not move
  when something is saved is a list that cannot be used to find what was
  just saved."
  [state actor]
  (newest-first (remove #(:drive/trashed? (:item %)) (visible-entries state actor))))

(defn trashed
  "Everything this principal has trashed.

  Their own only. Someone else's trash is their business, and an item that
  appeared in two people's trash would be purgeable twice.

  A separate call rather than a flag on `documents`, because the two are
  answers to different questions and the trash is not a place anything
  should appear by accident."
  [state actor]
  (let [workspace (workspace-for state actor)]
    (newest-first (filter #(:drive/trashed? (:item %))
                          (viewable-files workspace actor actor)))))

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
                           :resource-kind (str (:resource-kind spec))
                           ;; So the editor offers exactly what the validator
                           ;; accepts, from the one place that defines it.
                           :vocabulary (some->> (:vocabulary spec) (mapv name) sort vec)})
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
                :item (item-view (ws/item (:workspace written) id)
                                 {:owner actor :own? true :role :owner})})
           (refuse! written)))))))

(defn content
  "The stored envelope of one document, read back through the ACL.

  `drive.object/read-item` is what answers whether this principal may have
  the bytes; nothing here consults the store directly, which is the whole
  reason that boundary is in `drive` rather than in each application."
  ([id actor] (content id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [workspace owner own?] :as found} (locate (store/snapshot) actor id)
         _ (when-not found (refuse! {:reason :no-such-item :item-id id}))
         result (object/read-item workspace object-store id actor)]
     (if (:ok? result)
       (let [item (ws/item workspace id)
             kind (get resource-kinds (:drive/resource-kind item))
             body (json/read-str (bytes->string (:bytes result)))]
         {:schema schema
          :ok? true
          :item (item-view item {:owner owner :own? own?
                                 :role (ws/effective-role workspace id actor)})
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
  "The workspace, item and spec for a write, or the refusal that stops it.

  The workspace is whichever one holds the item, not the actor's — an editor
  writing to a document shared with them is writing into the owner's Drive,
  and the version they add is charged against the owner's quota. That is the
  right place for it: the bytes are the owner's, and a grant that moved the
  cost to the grantee would let anyone fill someone else's Drive by
  accepting a share."
  [actor id]
  (let [{:keys [workspace owner] :as found} (locate (store/snapshot) actor id)
        item (when found (ws/item workspace id))]
    (cond
      (nil? item) (refuse! {:reason :no-such-item :item-id id})
      (:drive/trashed? item) (refuse! {:reason :item-is-trashed :item-id id})
      (not (ws/can-write? workspace id actor))
      (refuse! {:reason :not-permitted :item-id id :principal actor})
      :else
      (if-let [spec (spec-of-item item)]
        (assoc found :item item :spec spec)
        (throw (ex-info "このドキュメントの種類を判別できません。"
                        {:type :drive/unknown-kind
                         :owner owner
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
  [{:keys [workspace item spec owner own?]} id actor object-store resource]
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
        ;; Under the owner's path, not the actor's. Writing it back under the
        ;; actor would fork the document into a second copy the owner never
        ;; sees, which is the failure mode a shared editor has to not have.
        (do (store/transact! assoc-in (workspace-path owner) (:workspace written))
            {:schema schema
             :ok? true
             :item (item-view (ws/item (:workspace written) id)
                              {:owner owner :own? own?
                               :role (ws/effective-role (:workspace written) id actor)})
             ;; Reported, not swallowed. The save went through; the surface
             ;; still had something to say about what was saved.
             :warnings warnings
             ;; The owner's quota, because that is the one the version was
             ;; charged to.
             :quota (quota-view (store/snapshot) owner)})
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
   (let [{:keys [workspace owner own?]} (locate (store/snapshot) actor id)
         item (when workspace (ws/item workspace id))
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
            :item (item-view item {:owner owner :own? own?
                                   :role (ws/effective-role workspace id actor)})
            :index index
            :created-at (:drive.version/created-at version)
            :author (:drive.version/author version)
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
  irreversible one lives, and it refuses anything that is not here first.

  Owner only. An editor a document was shared with may change it, which is
  what editing means; making it disappear from the owner's Drive is not, and
  `can-write?` does not distinguish the two."
  [id actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
          item (when workspace (ws/item workspace id))]
      (cond
        (nil? item) (refuse! {:reason :no-such-item :item-id id})
        (not= :owner (ws/effective-role workspace id actor))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [trashed (ws/trash workspace id)]
          (store/transact! assoc-in (workspace-path owner) trashed)
          {:schema schema :ok? true :id id
           :item (item-view (ws/item trashed id)
                            {:owner owner :own? (= owner actor) :role :owner})})))))

(defn restore!
  "Take a document back out of the trash.

  The half of trashing that makes it reversible, and therefore the half that
  has to exist before trashing is honest. Without it the trash is a sink and
  `trash!` is a delete button that says otherwise. Owner only, for the same
  reason `trash!` is."
  [id actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
          item (when workspace (ws/item workspace id))]
      (cond
        (nil? item) (refuse! {:reason :no-such-item :item-id id})
        (not= :owner (ws/effective-role workspace id actor))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [restored (ws/restore workspace id)]
          (store/transact! assoc-in (workspace-path owner) restored)
          {:schema schema :ok? true :id id
           :item (item-view (ws/item restored id)
                            {:owner owner :own? (= owner actor) :role :owner})})))))

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
     (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
           item (when workspace (ws/item workspace id))]
       (cond
         (nil? item) (refuse! {:reason :no-such-item :item-id id})
         ;; Owner only, and checked before the trashed test so that a
         ;; grantee is told they may not rather than told to trash it first.
         (not= :owner (ws/effective-role workspace id actor))
         (refuse! {:reason :not-permitted :item-id id :principal actor})
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
               (store/transact! assoc-in (workspace-path owner) without)
               {:schema schema :ok? true :id id
                :freed-bytes (:freed-bytes forgotten)
                :quota (quota-view (store/snapshot) owner)})
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

;; ── sharing ─────────────────────────────────────────────────────────────────

(def grantable-roles
  "What one principal may give another.

  `:owner` is not in it. `drive.workspace/grant` would accept it, and the
  result would be a document with two owners either of whom can purge it out
  from under the other — a transfer dressed as a share. Transferring
  ownership is a different operation and this is not it."
  {"editor" :editor "commenter" :commenter "viewer" :viewer})

(def ^:private link-roles
  ;; `create-share-link` refuses anything else, and says why: a link may read
  ;; and never write. Restated as data so the UI can offer exactly these.
  {"viewer" :viewer "commenter" :commenter})

(defn- owned!
  "The workspace holding `id`, if `actor` owns it.

  Sharing, unsharing and links are all owner-only: an editor who could
  re-share would be able to widen access the owner granted them narrowly,
  which makes the owner's grant not mean what it said."
  [actor id]
  (let [{:keys [workspace] :as found} (locate (store/snapshot) actor id)
        item (when found (ws/item workspace id))]
    (cond
      (nil? item) (refuse! {:reason :no-such-item :item-id id})
      (not= :owner (ws/effective-role workspace id actor))
      (refuse! {:reason :not-permitted :item-id id :principal actor})
      :else (assoc found :item item))))

(defn sharing
  "Who this document is shared with, and by what links.

  Owner-only, because it is the only view that lists other principals."
  [id actor]
  (let [{:keys [workspace item]} (owned! actor id)
        links (->> (vals (:drive.workspace/share-links workspace))
                   (filter #(= id (:drive.share/item-id %)))
                   (mapv (fn [link]
                           {:token (:drive.share/token link)
                            :role (name (:drive.share/role link))
                            :expires-at (:drive.share/expires-at link)})))]
    {:schema schema
     :ok? true
     :id id
     :grants (->> (:drive/permissions item)
                  (remove (fn [[principal _]] (= principal actor)))
                  (mapv (fn [[principal role]]
                          {:principal principal :role (name role)})))
     :links links
     :roles (vec (keys grantable-roles))
     :link-roles (vec (keys link-roles))}))

(defn grant!
  "Give `principal` a role on this document.

  Recorded on the item, in the owner's workspace, which is why `locate`
  exists: the grantee's own Drive has no idea this happened, and looking
  only there is what would make the grant invisible."
  [id principal role-name actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (owned! actor id)
          principal (not-empty (str/trim (str principal)))
          role (get grantable-roles (some-> role-name str/trim))]
      (cond
        (nil? principal)
        (throw (ex-info "共有相手を指定してください。"
                        {:type :drive/invalid-share :field :principal}))
        (= principal actor)
        (throw (ex-info "自分自身には共有できません。"
                        {:type :drive/invalid-share :field :principal}))
        (nil? role)
        (throw (ex-info "権限は editor / commenter / viewer のいずれかです。"
                        {:type :drive/invalid-share :field :role
                         :given role-name :known (vec (keys grantable-roles))}))
        :else
        (let [granted (ws/grant workspace id principal role)]
          (store/transact! assoc-in (workspace-path owner) granted)
          (sharing id actor))))))

(defn revoke-grant!
  "Take a role away again.

  `drive.workspace` has no `revoke`, so this removes the entry directly —
  which is what `grant` writes, and the only thing it writes."
  [id principal actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (owned! actor id)]
      (when (= principal actor)
        (throw (ex-info "所有者の権限は取り消せません。"
                        {:type :drive/invalid-share :field :principal})))
      (let [revoked (update-in workspace
                               [:drive.workspace/items id :drive/permissions]
                               dissoc principal)]
        (store/transact! assoc-in (workspace-path owner) revoked)
        (sharing id actor)))))

(defn create-link!
  "A token that reads this document without a role on it.

  `expires-in-hours` is optional; nil is a link with no expiry, which
  `resolve-share-link` treats as permanent. The token is a uuid rather than
  anything derived from the document, because a token that could be guessed
  from what it points at is not a token.

  Redeeming one still requires an app session — see `link-content`."
  [id role-name expires-in-hours actor now-ms]
  (locking write-lock
    (let [{:keys [workspace owner]} (owned! actor id)
          role (get link-roles (some-> role-name str/trim))
          hours (when expires-in-hours (long expires-in-hours))]
      (cond
        (nil? role)
        (throw (ex-info "リンクの権限は viewer / commenter のいずれかです。"
                        {:type :drive/invalid-share :field :role
                         :given role-name :known (vec (keys link-roles))}))
        (and hours (not (pos? hours)))
        (throw (ex-info "有効期限は1時間以上で指定してください。"
                        {:type :drive/invalid-share :field :expires-in-hours}))
        :else
        (let [token (str (UUID/randomUUID))
              ;; Epoch millis, because `resolve-share-link` compares with `<`.
              ;; `store/now` is an ISO string and would compare as nonsense.
              expires-at (when hours (+ now-ms (* hours 60 60 1000)))
              linked (ws/create-share-link workspace token id role expires-at)]
          (store/transact! assoc-in (workspace-path owner) linked)
          (assoc (sharing id actor) :token token))))))

(defn revoke-link! [id token actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (owned! actor id)]
      (store/transact! assoc-in (workspace-path owner)
                       (ws/revoke-share-link workspace token))
      (sharing id actor))))

(defn link-content
  "Read a document by share-link token.

  ## Still behind the app session, deliberately

  A share link exists so someone without a role can read. It does not exist
  here so someone without an *account* can, and the difference matters
  because this server binds loopback-only under
  `:privacy/bind-loopback-only?`. An unauthenticated route would be the only
  one in the app, and the person it would serve — someone off this machine —
  cannot reach the port at all. So it buys nothing and opens the one hole.

  `drive.object/read-via-share-link` is what answers; it consults the link
  rather than the ACL, and an expired or revoked token is indistinguishable
  from one that never existed, which is the point of a token."
  ([token actor now-ms] (link-content token actor now-ms (store-instance)))
  ([token actor now-ms object-store]
   (when (str/blank? (str actor))
     (throw (ex-info "認証が必要です。" {:type :identity/unauthenticated})))
   (let [state (store/snapshot)
         hit (some (fn [[owner workspace]]
                     (when (ws/resolve-share-link workspace token now-ms)
                       {:owner owner :workspace workspace}))
                   (all-workspaces state))]
     (if-not hit
       (refuse! {:reason :no-such-link})
       (let [{:keys [workspace owner]} hit
             result (object/read-via-share-link workspace object-store token now-ms)]
         (if (:ok? result)
           (let [link (ws/resolve-share-link workspace token now-ms)
                 item (ws/item workspace (:drive.share/item-id link))
                 kind (get resource-kinds (:drive/resource-kind item))
                 body (json/read-str (bytes->string (:bytes result)))]
             {:schema schema
              :ok? true
              :item (item-view item {:owner owner :own? (= owner actor)
                                     :role (:drive.share/role link)})
              :role (name (:role result))
              :resource-kind (some-> (:drive/resource-kind item) str)
              :payload (if-let [read-envelope (get-in kinds [kind :read])]
                         (read-envelope body)
                         body)})
           (refuse! result)))))))

;; ── form submissions ────────────────────────────────────────────────────────

(defn- submissions-path [id] [:drive :submissions id])

(defn- readable-form!
  "The rehydrated form behind `id`, if this principal may read it.

  Rehydrated, because `forms.model/missing-required` reads `:forms/fields`
  and `forms.validate/submission-problems` matches on `:email` — a projected
  payload has neither, so a submission checked against one would be told
  every answer is fine and no field is required. The same failure that made
  rehydration mandatory for saving makes it mandatory here."
  [id actor object-store]
  (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
        item (when workspace (ws/item workspace id))]
    (cond
      (nil? item) (refuse! {:reason :no-such-item :item-id id})
      (not= :forms/form (:drive/resource-kind item))
      (throw (ex-info "回答できるのはフォームだけです。"
                      {:type :drive/unknown-kind
                       :resource-kind (:drive/resource-kind item)}))
      :else
      (let [current (content id actor object-store)]
        {:owner owner
         :item item
         :form (forms-wire/rehydrate-form (:payload current))}))))

(defn form-for-answering
  "A form as something to fill in, rather than as a document to edit.

  Whoever may read the form may answer it: a form shared read-only is a form
  meant to be answered, and requiring write access to submit would make
  every respondent an editor of the questions."
  ([id actor] (form-for-answering id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [form item]} (readable-form! id actor object-store)]
     {:schema schema
      :ok? true
      :id id
      :title (:forms/title form)
      :name (:drive/title item)
      :fields (mapv (fn [field]
                      {:id (:forms/id field)
                       :label (:forms/label field)
                       :field-type (some-> (:forms/field-type field) name)
                       :required? (boolean (:forms/required? field))})
                    (:forms/fields form))})))

(defn- record-submission!
  "Validate `answers` against `form` and keep them.

  Beside the document rather than inside it. A submission is not a version of
  the questions: writing one into the stored envelope would make every
  response a new version of the form, charged to the owner's quota and
  changing the document every respondent is reading from."
  [id owner form answers author]
  (let [answers (into {} (map (fn [[k v]] [(name k) v])) (or answers {}))
        submission (forms/submission id answers)
        errors (->> (forms-validate/submission-problems form submission)
                    (filter #(= :error (:forms/severity %)))
                    (mapv (fn [problem]
                            {:code (some-> (:forms/code problem) str)
                             :field (:forms/id problem)
                             :message (:forms/msg problem)})))]
    (when (seq errors)
      (throw (ex-info (str "送信できません: " (:message (first errors)))
                      {:type :drive/invalid-submission :problems errors})))
    (let [record {:id (store/new-id "sub")
                  :form-id id
                  :owner owner
                  :author author
                  :answers answers
                  :submitted-at (store/now)}]
      (store/transact! update-in (submissions-path id) (fnil conj []) record)
      {:schema schema :ok? true :submission (dissoc record :owner)})))

(defn submit!
  "Answer a form."
  ([id answers actor] (submit! id answers actor (store-instance)))
  ([id answers actor object-store]
   (locking write-lock
     (let [{:keys [form owner]} (readable-form! id actor object-store)]
       (record-submission! id owner form answers actor)))))

(defn submit-via-link!
  "Answer a form reached by share link.

  The link is what distributing a form looks like, so this exists for the
  same reason `link-content` does. `read-via-share-link` is still what
  decides — expiry and trash included — and a `:viewer` link is enough:
  answering is not writing the questions."
  ([token answers actor now-ms] (submit-via-link! token answers actor now-ms
                                                  (store-instance)))
  ([token answers actor now-ms object-store]
   (locking write-lock
     (let [read (link-content token actor now-ms object-store)
           id (:id (:item read))]
       (when-not (= ":forms/form" (:resource-kind read))
         (throw (ex-info "回答できるのはフォームだけです。"
                         {:type :drive/unknown-kind
                          :resource-kind (:resource-kind read)})))
       ;; The owner comes off the item the link resolved to, so the answers
       ;; are filed against the Drive that actually holds the form rather
       ;; than against whoever happened to follow the link.
       (record-submission! id (:owner (:item read))
                           (forms-wire/rehydrate-form (:payload read))
                           answers actor)))))

(defn submissions
  "Every answer to this form. Owner only — the responses are theirs."
  [id actor]
  (let [_ (owned! actor id)]
    {:schema schema
     :ok? true
     :id id
     :submissions (mapv #(dissoc % :owner)
                        (get-in (store/snapshot) (submissions-path id) []))}))

;; ── comments ────────────────────────────────────────────────────────────────
;;
;; ## Beside the document, and why not inside it
;;
;; `docs.model` puts comments in `:docs/comments`, inside the resource. That
;; is the natural place for them and it is not reachable from here, because a
;; comment written there is a write to the document — and `drive.workspace`
;; says a `:commenter` may not write one. `can-write?` is `#{:owner :editor}`
;; and it is right to be: a commenter who could rewrite the content would be
;; an editor under a quieter name.
;;
;; The alternative is to perform that write as somebody who may — the owner,
;; or the app itself. That was merely distasteful before versions had
;; authors. Now it would file a comment under the wrong name in the one
;; record that says who changed what. A history that can be made to lie is
;; worse than a comment that lives somewhere else.
;;
;; So comments are kept beside the document, keyed by its id, like form
;; submissions and for the same reason: they are about the document rather
;; than part of it. The costs are real and named — a comment does not travel
;; with the exported envelope, and `docs.validate`'s comment checks never see
;; it. If comments must travel with the bytes, the fix is a constrained-write
;; operation in `drive` that a commenter may reach, not a louder version of
;; this.

(def comment-roles
  "Who may leave one. Read is not enough — a viewer is someone shown the
  document, and `drive.workspace` already draws that line."
  #{:owner :editor :commenter})

(defn- comments-path [id] [:drive :comments id])

(defn- readable!
  "The item and this principal's role on it, if they may read it at all."
  [actor id]
  (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
        item (when workspace (ws/item workspace id))]
    (cond
      (nil? item) (refuse! {:reason :no-such-item :item-id id})
      (:drive/trashed? item) (refuse! {:reason :item-is-trashed :item-id id})
      :else {:owner owner :item item
             :role (ws/effective-role workspace id actor)})))

(defn comments
  "Every comment on this document, oldest first.

  Visible to anyone who may read the document, including a viewer: being
  shown a document and not what has been said about it is a strange half of
  a thing to be shown."
  [id actor]
  (readable! actor id)
  {:schema schema
   :ok? true
   :id id
   :comments (mapv #(dissoc % :owner) (get-in (store/snapshot) (comments-path id) []))})

(defn comment!
  "Leave a comment.

  `anchor` is free text and optional — a block id for a document, a cell
  address for a workbook, nothing at all for a remark about the whole thing.
  Deliberately not interpreted here: the moment this parsed one it would owe
  every surface a different parser, and the surfaces are where that knowledge
  lives."
  [id text anchor actor]
  (locking write-lock
    (let [{:keys [owner role]} (readable! actor id)
          text (not-empty (str/trim (str text)))]
      (cond
        (not (contains? comment-roles role))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        (nil? text)
        (throw (ex-info "コメントを入力してください。"
                        {:type :drive/invalid-comment :field :text}))
        :else
        (let [record {:id (store/new-id "cmt")
                      :document-id id
                      :owner owner
                      :author actor
                      :text text
                      :anchor (not-empty (str/trim (str anchor)))
                      :created-at (store/now)}]
          (store/transact! update-in (comments-path id) (fnil conj []) record)
          {:schema schema :ok? true :comment (dissoc record :owner)})))))

(defn delete-comment!
  "Remove one.

  Its author or the document's owner. An editor may rewrite the document and
  still not delete what somebody said about it — those are different things
  and only one of them is the content."
  [id comment-id actor]
  (locking write-lock
    (let [{:keys [role]} (readable! actor id)
          existing (get-in (store/snapshot) (comments-path id) [])
          target (some #(when (= comment-id (:id %)) %) existing)]
      (cond
        (nil? target)
        (throw (ex-info "コメントが見つかりません。"
                        {:type :drive/not-found :comment-id comment-id}))
        (not (or (= actor (:author target)) (= :owner role)))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (do (store/transact! assoc-in (comments-path id)
                             (vec (remove #(= comment-id (:id %)) existing)))
            {:schema schema :ok? true :id comment-id})))))
