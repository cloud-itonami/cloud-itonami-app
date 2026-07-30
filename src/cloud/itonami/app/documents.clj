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
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.capability :as capability]
            [cloud.itonami.app.filecoin :as filecoin]
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
            [docs.docx :as docs-docx]
            [docs.markdown :as docs-md]
            [forms.responses :as forms-responses]
            [sheets.csv :as sheets-csv]
            [sheets.xlsx :as sheets-xlsx]
            [sheets.model :as sheets]
            [sheets.validate :as sheets-validate]
            [sheets.wire :as sheets-wire]
            [slides.model :as slides]
            [slides.office :as slides-office]
            [slides.pptx :as slides-pptx]
            [slides.validate :as slides-validate]
            [slides.wire :as slides-wire]
            ;; Only to project EDN onto the wire. What is at rest is EDN; what
            ;; goes over HTTP is the same plain-JSON shape the editor has
            ;; always been given, so the client contract does not move.
            [transit.core :as transit])
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
           :id-key :sheets/id
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
            :text (fn [wb]
                    (concat (keep :sheets/title (vals (:sheets/tabs wb)))
                            (for [tab (vals (:sheets/tabs wb))
                                  cell (vals (:sheets/cells tab))
                                  text [(:sheets/value cell) (:sheets/formula cell)]
                                  :when (some? text)]
                              text)))
            ;; A workbook has no closed vocabulary an editor has to offer —
            ;; a cell holds whatever it holds.
            :vocabulary nil}
   :docs {:resource-kind :docs/document
          :label "ドキュメント"
          :default-title "無題のドキュメント"
          :title-key :docs/title
           :id-key :docs/id
          :seed (fn [id title]
                  (-> (docs/document id {:docs/title title})
                      (docs/add-block (docs/heading "title" 1 title))))
          :envelope docs-wire/document-envelope
          :read docs-wire/read-document-envelope
          :rehydrate docs-wire/rehydrate-document
          :problems docs-validate/problems
          :severity :docs/severity :code :docs/code :message :docs/msg
          :text (fn [doc]
                  (concat (keep :docs/text (:docs/blocks doc))
                          (mapcat :docs/items (:docs/blocks doc))
                          (for [block (:docs/blocks doc)
                                row (:docs/rows block)
                                cell row]
                            cell)
                          (keep :docs/text (:docs/comments doc))))
          ;; From `docs.model` rather than restated: the editor offers the
          ;; kinds the validator will accept, and there is one list of them.
          :vocabulary docs/block-kinds}
   :forms {:resource-kind :forms/form
           :label "フォーム"
           :default-title "無題のフォーム"
           :title-key :forms/title
           :id-key :forms/id
           ;; No seed field: an empty form is valid, and a placeholder
           ;; question is one the author has to notice and delete.
           :seed (fn [id title] (forms/form id {:forms/title title}))
           :envelope forms-wire/form-envelope
           :read forms-wire/read-form-envelope
           :rehydrate forms-wire/rehydrate-form
           :problems forms-validate/form-problems
           :severity :forms/severity :code :forms/code :message :forms/msg
           :text (fn [form] (keep :forms/label (:forms/fields form)))
           :vocabulary forms/field-types}
   :slides {:resource-kind :slides/deck
            :label "スライド"
            :default-title "無題のスライド"
            :title-key :slides/title
           :id-key :slides/id
            :seed (fn [id title]
                    (-> (slides/deck id {:slides/title title})
                        (slides/add-slide
                         (-> (slides/slide "slide1" {:slides/title title})
                             (slides/add-shape (slides/text-box "title" title))))))
            :envelope slides-wire/deck-envelope
            :read slides-wire/read-deck-envelope
            :rehydrate slides-wire/rehydrate-deck
            ;; `slides.validate/problems` takes a workspace, not a deck: it
            ;; looks for items whose kind is `:slides/deck` and it also runs
            ;; `route-problems`, which asks whether four Pages hosts are
            ;; configured — a question about the slides site, not about this
            ;; document. So the deck is wrapped in a workspace of its own and
            ;; only the deck checks are asked for.
            :problems (fn [deck]
                        (slides-validate/deck-problems
                         (slides/add-item (slides/workspace "cloud-itonami") deck)))
            :severity :slides/severity :code :slides/code :message :slides/msg
            :text (fn [deck]
                    (concat (keep :slides/title (:slides/slides deck))
                            (for [slide (:slides/slides deck)
                                  shape (:slides/shapes slide)
                                  :let [text (:slides/text shape)]
                                  :when (some? text)]
                              text)))
            :vocabulary slides-validate/shape-kinds}})

(def export-formats
  "What each surface can be asked for, and what that produces.

  Keyed by surface so a request for a format a surface has no writer for is
  refused by name rather than by producing something empty."
  {:sheets {"csv" {:media-type "text/csv; charset=utf-8" :extension "csv"}
            "xlsx" {:media-type
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    :extension "xlsx"}
            "edn" {:media-type "application/edn" :extension "edn"}}
   :docs {"docx" {:media-type
                  (str "application/vnd.openxmlformats-officedocument."
                       "wordprocessingml.document")
                  :extension "docx"}
          "md" {:media-type "text/markdown; charset=utf-8" :extension "md"}
          "edn" {:media-type "application/edn" :extension "edn"}}
   :forms {"csv" {:media-type "text/csv; charset=utf-8" :extension "csv"
                  ;; Not the questions — the answers. Every other format on
                  ;; this table writes the document; this one writes what was
                  ;; collected with it, which is why `export` asks a
                  ;; different question about who may have it.
                  :owner-only? true}
           "edn" {:media-type "application/edn" :extension "edn"}}
   :slides {"pptx" {:media-type
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    :extension "pptx"}
            "edn" {:media-type "application/edn" :extension "edn"}}})

(def import-formats
  "What a new document can be made from."
  {"csv" :sheets
   "xlsx" :sheets
   "pptx" :slides
   "docx" :docs
   "md" :docs
   "edn" nil})

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

(defn- capability-path [document-id principal]
  [:drive :capabilities document-id principal])

(defn capability-for [state document-id principal]
  (get-in state (capability-path document-id principal)))

(defn- honour-capabilities
  "The workspace with expired grants dropped before `drive` is asked anything.

  This is what makes a capability more than decoration. A CACAO carries an
  expiry; if the ACL kept answering yes after it passed, the signed thing
  would be a description of a permission rather than the permission itself.
  So the expiry is applied where every read already goes, and
  `drive.workspace/effective-role` needs to know nothing about it.

  Only grants that have a capability are subject to this. A workspace's own
  owner has none — nobody granted it to them — and grants made before this
  existed have none either, which is deliberate: retroactively expiring a
  share nobody was warned about would be the change taking something away
  rather than adding something."
  [state workspace now]
  (update workspace :drive.workspace/items
          (fn [items]
            (reduce-kv
             (fn [acc id item]
               (assoc acc id
                      (update item :drive/permissions
                              (fn [permissions]
                                (reduce-kv
                                 (fn [kept principal role]
                                   (let [cap (capability-for state id principal)]
                                     (if (and cap (capability/expired? cap now))
                                       kept
                                       (assoc kept principal role))))
                                 {} (or permissions {}))))))
             {} (or items {})))))

(defn stored-workspace-for
  "The workspace as stored, with expired grants still in it.

  For the one caller that needs to see a lapsed share rather than have it
  disappear: its owner. Everywhere a permission is decided uses
  `workspace-for`, which has already dropped them — an owner being shown
  \"bob, expired\" and bob being told the document does not exist are the
  right pair of answers, and a share that silently vanished would leave the
  owner re-granting without ever learning why."
  [state actor]
  (or (get-in state (workspace-path actor))
      (ws/workspace (str "drive-" actor) actor quota-bytes)))

(defn workspace-for
  "This principal's Drive, created empty if they have never had one.

  Not persisted here: an empty workspace is exactly what the next call would
  build anyway, so writing one on a read would put a row in the state file
  for every principal that has ever loaded the Drive tab."
  [state actor]
  (honour-capabilities
   state
   (or (get-in state (workspace-path actor))
       (ws/workspace (str "drive-" actor) actor quota-bytes))
   (java.time.Instant/now)))

(defn- all-workspaces
  "Every Drive, with expired grants already dropped — see `honour-capabilities`."
  [state]
  (let [now (java.time.Instant/now)]
    (reduce-kv (fn [acc owner workspace]
                 (assoc acc owner (honour-capabilities state workspace now)))
               {} (get-in state [:drive :workspaces] {}))))

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
            :keep-count-too-low "最新の版は残す必要があります。"
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
                         :keep-count-too-low :drive/invalid-document
                         :drive/refused)))))

;; ── bytes ───────────────────────────────────────────────────────────────────

(def stored-media-type
  "What the object store now holds. The wire is still JSON; this is not it."
  "application/edn")

(defn stored-envelope
  "The self-describing thing that goes into the object store, in EDN.

  ## Why not the office envelope's JSON

  `transit.core/office-envelope` builds a plain-JSON projection, and plain
  JSON cannot carry what these models are made of: `:sheets/type` leaves as
  `\"workbook\"` and a cell address `[1 1]` leaves as the string `\"[1 1]\"`.
  Storing that meant every reader had to put it back, which is why there are
  four `rehydrate-*` functions and why each of them had to learn not to throw
  on input it could not convert.

  None of that is needed at rest. EDN is what the models already are, what
  every validator reads, and what `store.clj` already writes for the rest of
  this app's state. So the bytes are `pr-str` of a map with the envelope's
  own shape — the same four protocol keys, so a reader still does not have to
  know in advance which surface it is holding — and reading is `edn/read` and
  nothing else.

  Rehydration does not disappear; it moves to the one place it belongs. A
  payload arriving over HTTP is JSON, because HTTP is, and `update!` converts
  it on the way in. What changes is that nothing has to convert on the way
  *out*."
  [spec resource]
  {:kotoba.protocol/family :kotoba.protocol/office
   :kotoba.protocol/version 1
   :kotoba.resource/kind (:resource-kind spec)
   :kotoba.resource/payload resource})

(defn envelope-bytes
  "`stored-envelope` as the vector of unsigned ints `drive` wants.

  Explicitly rather than by handing `write-item` a string: `count` on a
  string is characters, and the docstring on `write-item` is about exactly
  that drift — a title in Japanese would be charged three bytes against the
  quota and store nine."
  [envelope]
  (mapv #(bit-and (int %) 0xff)
        (.getBytes ^String (pr-str envelope) StandardCharsets/UTF_8)))

(defn- bytes->string [bytes]
  (String. (byte-array (map unchecked-byte bytes)) StandardCharsets/UTF_8))

(defn decode-stored
  "Stored bytes back into `{:kind k :payload resource}`, as EDN.

  Two formats, because documents written before this change are JSON and
  rewriting somebody's object store on a deploy is not a migration anyone
  asked for. They are told apart by their first character — EDN opens
  `{:kotoba.protocol/family`, JSON opens `{\"kotoba.protocol/family\"` — and a
  JSON one is rehydrated on read exactly as it always was.

  So an old document reads as it did, and the first save rewrites it in EDN.
  Migration is something the Drive does as it is used rather than something
  anyone runs."
  [bytes]
  (let [text (bytes->string bytes)]
    (if (str/starts-with? (str/triml text) "{:")
      (let [envelope (edn/read-string text)]
        {:kind (:kotoba.resource/kind envelope)
         :payload (:kotoba.resource/payload envelope)
         :format :edn})
      (let [body (json/read-str text)
            kind (some-> (get body "kotoba.resource/kind") keyword)
            spec (get kinds (get resource-kinds kind))]
        {:kind kind
         :payload (if-let [rehydrate (:rehydrate spec)]
                    (rehydrate ((:read spec) body))
                    (get body "kotoba.resource/payload"))
         :format :json}))))

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
  ([item {:keys [owner role own? trashed?] :or {own? true}}]
   (let [kind (:drive/resource-kind item)
         newest (latest-version item)]
     {:id (:drive/id item)
      :name (:drive/title item)
      :folder (if own? "マイドライブ" "共有アイテム")
      ;; Which folder it is in, so a listing can be scoped to one without
      ;; asking the server per item. Nil for a document in somebody else's
      ;; Drive: the folder it sits in there is not one this principal can
      ;; navigate to, and naming it would put an id in a breadcrumb that
      ;; goes nowhere.
      :parent-id (when own? (:drive/parent-id item))
      :media-type (:drive/media-type item)
      :resource-kind (some-> kind str)
      ;; An uploaded file has no resource kind: it is bytes with a media
      ;; type, not one of the four surfaces. It is still an item, so it
      ;; still needs a kind and a label the list can render — saying nothing
      ;; would put a blank row in the Drive.
      :kind (if kind (some-> (get resource-kinds kind) name) "file")
      :label (if kind
               (get-in kinds [(get resource-kinds kind) :label])
               "ファイル")
      ;; Whether this is something the editors can open at all. The pane
      ;; asks before it offers an editor, rather than opening one on bytes
      ;; that are not a document and failing at the first read.
      :file? (nil? kind)
      :created-at (:drive/created-at item)
      ;; What a save has to echo back. The object reference of the current
      ;; version, which `write-item` guarantees is unique per version, so it
      ;; is an ETag in every sense but the header it is not sent in.
      :etag (:drive/object-ref item)
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
      ;; Given by the caller, which has the workspace, rather than read off
      ;; the item: a file inside a trashed folder is in the trash, and an
      ;; item view that said otherwise would put it back in the listing.
      ;; The item's own flag is the fallback for a caller with no tree to
      ;; ask, which is the answer that was always given before folders.
      :trashed? (boolean (if (some? trashed?) trashed? (:drive/trashed? item)))
      :own? own?
      :owner owner
      :role (some-> role name)
      ;; Whether this asker may write, rather than the role alone: the rule
      ;; is `drive.workspace/can-write?`'s and a UI re-deriving it from the
      ;; role string is a second copy of it.
      :writable? (contains? #{:owner :editor} role)
      :available? true
      :origin "workspace"})))

(defn folder-view
  "One folder, in the shape the list renders.

  Deliberately not `item-view`: a folder has no versions, no resource kind
  and no size, and half of that view would be zeros standing for questions a
  folder cannot be asked."
  [workspace item actor]
  {:id (:drive/id item)
   :name (:drive/title item)
   :kind "folder"
   :label "フォルダ"
   :parent-id (:drive/parent-id item)
   :trashed? (ws/trashed? workspace (:drive/id item))
   :role (some-> (ws/effective-role workspace (:drive/id item) actor) name)
   :count (count (ws/children workspace (:drive/id item) actor))})


(defn- viewable-files
  "Every file in `workspace` this principal may read, with their role."
  [workspace actor owner]
  (->> (vals (:drive.workspace/items workspace))
       (filter #(= :file (:drive/kind %)))
       (keep (fn [item]
               (when-let [role (ws/effective-role workspace (:drive/id item) actor)]
                 {:item item :role role :owner owner :own? (= owner actor)
                  ;; Answered here because this is where the tree is. Every
                  ;; listing flows through `newest-first`, which passes the
                  ;; entry straight to `item-view`.
                  :trashed? (ws/trashed? workspace (:drive/id item))})))))

(defn- newest-first
  "Entries as item views, most recently written first.

  The id is part of the sort key, and it is not decoration. `cursor-of`
  builds a cursor from `updated-at` *and* the id, so paging compares on a
  total order; a sort that stopped at the timestamps would leave documents
  written in the same millisecond in whatever order they came out of a hash
  map. The two orders would then disagree, and `after-cursor` — which drops
  everything up to the cursor — would skip one document and repeat another.

  Found as a flaky test rather than as a bug report: five documents created
  in a loop shared a timestamp, and one run in some number came out
  interleaved. Anything that can page in a different order between two
  requests can lose a row between two pages."
  [entries]
  (->> entries
       (sort-by (juxt #(or (:drive.version/created-at (latest-version (:item %))) "")
                      #(:drive/created-at (:item %))
                      #(:drive/id (:item %))))
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

(def default-page-size
  "How many documents a listing returns when the caller does not say.

  Fifty, which is a number rather than a principle: enough that a household
  Drive is one page and small enough that a large one does not arrive as a
  single response."
  50)

(defn- cursor-of
  "Where a page stops, as the sort key of its last entry.

  A keyset cursor rather than an offset, and the reason is specific to this
  list: it is ordered by last write, so saving anything moves that document
  to the front and shifts every offset after it. Offset paging would then
  show one document twice and skip another, silently. A cursor says \"after
  this position\", which stays meaningful however the list moves — an item
  that jumps to the front is seen again at the top rather than lost from the
  middle."
  [entry]
  (str (:updated-at entry) "\u0000" (:id entry)))

(defn- after-cursor [views cursor]
  (if (str/blank? (str cursor))
    views
    (vec (drop-while #(not (neg? (compare (cursor-of %) (str cursor)))) views))))

(defn documents
  "Documents this principal can see, most recently written first.

  By last write rather than by creation, because a list that does not move
  when something is saved is a list that cannot be used to find what was
  just saved.

  `:limit` bounds the *response*, not the work. Every workspace is still
  scanned and sorted, because a grant is recorded on the item rather than
  anywhere central and there is no index to consult. A page is what a caller
  receives; when the scan itself needs bounding the fix is the same index
  that would fix search."
  ([state actor] (documents state actor {}))
  ([state actor {:keys [limit cursor]}]
   (let [views (-> (remove :trashed? (visible-entries state actor))
                   newest-first
                   (after-cursor cursor))
         limit (or limit (count views))]
     (vec (take limit views)))))

(defn page
  "One page of `documents`, with where the next one starts.

  `:next-cursor` is nil at the end rather than a cursor that would return
  nothing, so a caller stops by being told to rather than by asking again."
  ([state actor] (page state actor {}))
  ([state actor {:keys [limit cursor] :or {limit default-page-size}}]
   (let [taken (documents state actor {:limit (inc limit) :cursor cursor})
         more? (> (count taken) limit)
         shown (vec (take limit taken))]
     {:items shown
      :next-cursor (when more? (cursor-of (peek shown)))})))

(defn trashed
  "Everything this principal has trashed.

  Their own only. Someone else's trash is their business, and an item that
  appeared in two people's trash would be purgeable twice.

  A separate call rather than a flag on `documents`, because the two are
  answers to different questions and the trash is not a place anything
  should appear by accident."
  [state actor]
  (let [workspace (workspace-for state actor)
        own-flag? #(:drive/trashed? (ws/item workspace %))]
    (into
     ;; Folders first, and only those trashed in their own right — a folder
     ;; inside a trashed folder is not a second thing to restore. Without
     ;; them the trash could never be emptied of a folder at all, and the
     ;; bytes of everything inside it would stay charged to the quota with
     ;; nothing listing them.
     (->> (vals (:drive.workspace/items workspace))
          (filter #(and (= :folder (:drive/kind %))
                        (:drive/trashed? %)
                        (not (some own-flag? (ws/ancestors workspace (:drive/id %))))))
          (mapv #(folder-view workspace % actor)))
     (newest-first (filter #(and (:drive/trashed? (:item %))
                                 (not (some own-flag?
                                            (ws/ancestors workspace
                                                          (:drive/id (:item %))))))
                           (viewable-files workspace actor actor))))))

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
  ([archive actor] (drive-view archive actor {}))
  ([archive actor {:keys [limit cursor] :or {limit default-page-size}}]
  (let [state (store/snapshot)
        {created :items next-cursor :next-cursor} (page state actor {:limit limit
                                                                     :cursor cursor})
        binned (trashed state actor)
        archived (mapv #(assoc % :origin "archive") (:items archive))]
    (assoc archive
           :schema schema
           :items (into created archived)
           ;; Only the created half is paged. The archive is eighty files
           ;; that `workspace/drive-snapshot` has already capped, so paging
           ;; it too would be a second cursor for a list that does not grow.
           :next-cursor next-cursor
           :trash binned
           :count (+ (count created) (count archived))
           :documents (count created)
           :quota (quota-view state actor)
           :kinds (mapv (fn [[k spec]]
                          {:kind (name k) :label (:label spec)
                           :resource-kind (str (:resource-kind spec))
                           ;; So the editor offers exactly what the validator
                           ;; accepts, from the one place that defines it.
                           :vocabulary (some->> (:vocabulary spec) (mapv name) sort vec)
                           ;; So the pane offers exactly the formats this
                           ;; surface has a writer for, from the one table
                           ;; that decides.
                           :exports (vec (sort (keys (get export-formats k))))
                           ;; Which of those write something other than the
                           ;; document, so the pane does not offer a button
                           ;; that refuses. The role is per item and this
                           ;; table is per kind, so the filtering is the
                           ;; caller's — this says what to filter on.
                           :owner-only-exports
                           (->> (get export-formats k)
                                (keep (fn [[format shape]]
                                        (when (:owner-only? shape) format)))
                                sort vec)})
                        kinds)
           :source (str (:source archive) " · 作成済み " (count created) " 件"
                        (when next-cursor " 以降あり"))))))

;; ── creating ────────────────────────────────────────────────────────────────

(defonce ^:private write-lock (Object.))

(defn- object-ref []
  ;; `drive.store.fs` refuses a reference that could become a path, so this
  ;; stays inside its alphabet on purpose.
  (str "obj-" (UUID/randomUUID)))

(defn- locate-folder!
  "Which Drive a new item belongs in, and where in it.

  `folder-parent!` looks in one workspace, which is right for `move!` —
  `ws/move` rewrites one tree and a destination in another Drive would leave
  a parent id pointing outside it. Creating is different: a folder shared
  with you is somewhere you may put things, and that folder lives in the
  granter's Drive.

  **What is created there belongs to that Drive.** The workspace is this
  application's ownership boundary: the bytes are in it, the quota is its
  owner's, and trash, purge and re-sharing are all owner-only. A document
  created in alice's folder that bob owned would be a document alice cannot
  remove from her own Drive. So the Drive's owner owns it and the creator is
  recorded as an editor, which is what they need to go on working on it.

  The cost is real and is the same one an editor already has: someone you
  gave write access to can consume your quota. That was already true of
  saving a shared document — every version is charged to the owner — so this
  widens who can start one rather than introducing the hazard."
  [state actor folder]
  (if (str/blank? (str folder))
    (let [own (workspace-for state actor)]
      {:workspace own :owner actor :parent (:drive.workspace/root-id own)})
    (let [{:keys [workspace owner]} (locate state actor folder)
          item (when workspace (ws/item workspace folder))]
      (cond
        (nil? item)
        (throw (ex-info "そのフォルダはありません。"
                        {:type :drive/not-found :folder folder}))
        (not= :folder (:drive/kind item))
        (throw (ex-info "フォルダではありません。"
                        {:type :drive/not-a-folder :folder folder}))
        (ws/trashed? workspace folder)
        (throw (ex-info "ゴミ箱の中のフォルダには作成できません。"
                        {:type :drive/item-is-trashed :folder folder}))
        (not (ws/can-write? workspace folder actor))
        (refuse! {:reason :not-permitted :item-id folder :principal actor})
        :else {:workspace workspace :owner owner :parent folder}))))

(defn- folder-parent!
  "A destination folder inside `workspace`, checked.

  Nil means that workspace's root. Used by `move!`, which must stay inside
  one Drive: `ws/move` rewrites one tree, so a destination in another would
  leave a parent id pointing at an item that tree does not contain — a
  breadcrumb that walks up out of the workspace and a listing that never
  shows it again. Creating uses `locate-folder!` instead, which may cross.

  A folder that is not a folder, is not there, or is not this principal's to
  write into is refused here rather than by `ws/create-file`, whose message
  is about a parent and not about a Drive."
  [workspace folder actor]
  (let [root (:drive.workspace/root-id workspace)]
    (if (str/blank? (str folder))
      root
      (let [item (ws/item workspace folder)]
        (cond
          ;; Looked up in *this* workspace, which for `create!` is the
          ;; creator's own. A folder shared from someone else's Drive is not
          ;; here, and that is a limitation rather than a permission answer:
          ;; creating into it would mean writing into the owner's workspace
          ;; and against the owner's quota, which is what saving a shared
          ;; document already does but not what creating one does yet.
          (nil? item)
          (throw (ex-info "そのフォルダはあなたのドライブにありません。"
                          {:type :drive/not-found :folder folder}))
          (not= :folder (:drive/kind item))
          (throw (ex-info "フォルダではありません。"
                          {:type :drive/not-a-folder :folder folder}))
          (ws/trashed? workspace folder)
          (throw (ex-info "ゴミ箱の中のフォルダには作成できません。"
                          {:type :drive/item-is-trashed :folder folder}))
          (not (ws/can-write? workspace folder actor))
          (refuse! {:reason :not-permitted :item-id folder :principal actor})
          :else folder)))))

(defn create-folder!
  "A folder in `actor`'s Drive.

  No object and no version: a folder holds nothing, so there is nothing to
  write to the store and nothing to charge against the quota. That is why
  this does not go through `object/write-item` like every other creation
  here."
  ([title actor] (create-folder! title actor nil))
  ([title actor folder]
   (when (str/blank? (str actor))
     (throw (ex-info "作成者を特定できません。" {:type :identity/unauthenticated})))
   (locking write-lock
     (let [workspace (workspace-for (store/snapshot) actor)
           parent (folder-parent! workspace folder actor)
           id (store/new-id "fld")
           title (or (not-empty (str/trim (str title))) "無題のフォルダ")
           made (ws/create-folder workspace id parent title actor)]
       (store/transact! assoc-in (workspace-path actor) made)
       {:schema schema :ok? true :item (folder-view made (ws/item made id) actor)}))))

(defn move!
  "Put a document or folder inside another folder.

  Owner only. Moving into a shared folder shares what was moved — that falls
  out of `effective-role` walking up the parents — so a mover who was merely
  an editor could widen the access the owner granted, which is the same
  reason re-sharing is owner-only."
  [id folder actor]
  (locking write-lock
    (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
          item (when workspace (ws/item workspace id))]
      (cond
        (nil? item) (refuse! {:reason :no-such-item :item-id id})
        (not= :owner (ws/effective-role workspace id actor))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [parent (folder-parent! workspace folder actor)
              ;; `ws/move` refuses a cycle, and refuses in the library's
              ;; vocabulary — an ex-info with no `:type`, which the server's
              ;; status table cannot see and would answer 502 for. A folder
              ;; dragged onto its own child is an ordinary mistake and
              ;; deserves to be told so, not to look like a broken server.
              moved (try (ws/move workspace id parent)
                         (catch clojure.lang.ExceptionInfo e
                           (throw (ex-info "そのフォルダの中には移動できません。"
                                           (assoc (ex-data e)
                                                  :type :drive/invalid-move)))))]
          (store/transact! assoc-in (workspace-path owner) moved)
          {:schema schema :ok? true :id id :folder parent
           :path (mapv :drive/title (ws/path moved id))})))))

(defn folders
  "The folders inside `folder` — the root when it is not given — and the
  breadcrumb that leads there.

  Folders are listed separately from documents because they are a different
  kind of thing with a different view, and because `documents` is ordered by
  last write, which a folder does not have."
  [state actor folder]
  (let [own (workspace-for state actor)
        ;; Resolved rather than assumed to be here. A folder shared from
        ;; another Drive is somewhere this principal can now create, so it
        ;; has to be somewhere they can navigate into — otherwise the
        ;; capability exists and nothing can reach it.
        located (when (not-empty (str folder)) (locate state actor folder))
        workspace (or (:workspace located) own)
        here (or (not-empty (str folder)) (:drive.workspace/root-id own))]
    (when (ws/item workspace here)
      {:folder here
       ;; Whose Drive this is, and who is asking — so the pane can say that
       ;; what you create here lands in somebody else's Drive, which is the
       ;; one consequence of this feature a person should not discover
       ;; afterwards.
       :owner (or (:owner located) actor)
       :you actor
       :path (mapv (fn [item] {:id (:drive/id item) :name (:drive/title item)})
                   (ws/path workspace here))
       :folders (->> (ws/children workspace here actor)
                     (filter #(= :folder (:drive/kind %)))
                     (mapv #(folder-view workspace % actor)))
       ;; Every folder, with where it is, for a *move* — which is a choice
       ;; among all of them and not among the ones you happen to be standing
       ;; in. Named by path rather than by title, because two folders called
       ;; Q1 are an ordinary thing to have and a picker showing both as "Q1"
       ;; would be asking an unanswerable question.
       ;; The asker's own Drive is merged in rather than taken from the
       ;; store: `workspace-for` creates one on demand, so somebody who has
       ;; never created anything has no entry there — and the picker would
       ;; offer them nowhere at all, including their own root, while `:path`
       ;; above happily said "My Drive".
       :all (->> (assoc (all-workspaces state) actor own)
                 (mapcat
                  (fn [[owner ws]]
                    (->> (vals (:drive.workspace/items ws))
                         (filter #(and (= :folder (:drive/kind %))
                                       (not (ws/trashed? ws (:drive/id %)))
                                       (ws/can-write? ws (:drive/id %) actor)))
                         (map (fn [item]
                                {:id (:drive/id item)
                                 :owner owner
                                 :own? (= owner actor)
                                 :name (str/join " / "
                                                 (map :drive/title
                                                      (ws/path ws (:drive/id item))))})))))
                 (sort-by (juxt (complement :own?) :name))
                 vec)
       ;; Folders from other Drives, at the top level only: they are not
       ;; inside anything here, so there is no folder they could appear
       ;; under. Shown beside your own the way shared documents are shown
       ;; beside yours in the list.
       :shared (when (str/blank? (str folder))
                 (->> (all-workspaces state)
                      (remove #(= (first %) actor))
                      (mapcat
                       (fn [[_ ws]]
                         (->> (vals (:drive.workspace/items ws))
                              (filter #(and (= :folder (:drive/kind %))
                                            (not (ws/trashed? ws (:drive/id %)))
                                            ;; Shared *to this principal*
                                            ;; rather than inherited from a
                                            ;; folder already listed — a
                                            ;; subfolder of a shared folder
                                            ;; is reached by opening it.
                                            (get (:drive/permissions %) actor)))
                              (map #(folder-view ws % actor)))))
                      vec))})))

(defn- stored-kind-mismatch!
  "Refuse bytes whose discriminant disagrees with the item that points at them.

  What `(:read spec)` used to do for free by checking the envelope kind. It
  still has to be done — an object reference pointing at the wrong document
  is a broken node, not a rendering quirk — so it is done here rather than
  lost with the JSON reader."
  [id expected found]
  (when-not (= expected found)
    (throw (ex-info "保管されている内容がこのドキュメントの種類と一致しません。"
                    {:type :drive/object-missing :item-id id
                     :expected (str expected) :found (str found)}))))

(defn- stored-payload
  "The EDN resource behind `id`'s current bytes, discriminant checked."
  [id item bytes]
  (let [{:keys [kind payload]} (decode-stored bytes)]
    (stored-kind-mismatch! id (:drive/resource-kind item) kind)
    payload))

(def ^:private format-losses
  "Which formats can say what they will drop, and how to ask.

  A table rather than a `cond`, so adding a writer that gains the function
  is one line here — and so the formats that *cannot* answer are visible as
  absences rather than as an unstated assumption. EDN is not here because it
  is the stored bytes and loses nothing; CSV, PPTX and the office readers
  are not here because nobody has written the function for them, which is a
  gap and not a claim that they are lossless."
  {[:docs "md"] docs-md/unexpressed
   [:docs "docx"] docs-docx/unexpressed
   [:sheets "xlsx"] sheets-xlsx/unexpressed})

(defn export-warnings
  "What each export format will drop from this resource, before it drops it.

  Keyed by format, so the pane can put the warning next to the button that
  causes it rather than in a place nobody reads.

  Every surface writes its own answer in its own namespaced shape —
  `:docs/severity` here, `:sheets/severity` there — because each belongs
  beside the writer that does the dropping. Flattened to one shape here,
  which is the app's, so the pane renders all of them with the code it
  already has."
  [kind resource]
  (let [flatten-entry (fn [e]
                        (let [get* (fn [n] (some (fn [[k v]] (when (= n (name k)) v)) e))]
                          {:severity (some-> (get* "severity") name)
                           :code (str (get* "code"))
                           :id (get* "id")
                           :message (get* "msg")}))
        answered (keep (fn [[[k format] ask]]
                         (when (= k kind)
                           (let [entries (ask resource)]
                             (when (seq entries) [format (mapv flatten-entry entries)]))))
                       format-losses)]
    (when (seq answered) (into {} answered))))

(defn content
  "The stored resource of one document, read back through the ACL.

  `drive.object/read-item` is what answers whether this principal may have
  the bytes; nothing here consults the store directly, which is the whole
  reason that boundary is in `drive` rather than in each application.

  `:payload` is the plain-JSON projection, not the EDN that is stored. That
  is deliberate and is the one place the two formats meet: HTTP is JSON, the
  editor has always been given this shape, and moving storage to EDN was not
  a reason to move the client contract with it. `:resource` is the EDN, for
  callers inside this process that would otherwise convert it straight back."
  ([id actor] (content id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [workspace owner own?] :as found} (locate (store/snapshot) actor id)
         _ (when-not found (refuse! {:reason :no-such-item :item-id id}))
         ;; An uploaded file has no envelope. Without this the bytes reach
         ;; `decode-stored`, which hands a PDF to the EDN or JSON reader and
         ;; the caller gets "unexpected character: %" as a 500 — a parse
         ;; error standing in for "that is not a document".
         _ (when-not (:drive/resource-kind (ws/item workspace id))
             (throw (ex-info "これはドキュメントではありません。ダウンロードしてください。"
                             {:type :drive/not-a-document :item-id id})))
         result (object/read-item workspace object-store id actor)]
     (if (:ok? result)
       (let [item (ws/item workspace id)
             resource (stored-payload id item (:bytes result))]
         {:schema schema
          :ok? true
          :item (item-view item {:owner owner :own? own?
                                 :trashed? (ws/trashed? workspace id)
                                 :role (ws/effective-role workspace id actor)})
          :resource-kind (some-> (:drive/resource-kind item) str)
          :resource resource
          :export-warnings (export-warnings (get resource-kinds (:drive/resource-kind item))
                                            resource)
          :payload (transit/write-json resource)})
       (refuse! result)))))

(defn source-bytes
  "The stored bytes of `id`'s current version, through the ACL, plus the
  identity of the version they came from.

  The only caller is `cloud.itonami.app.esign`, and it needs the bytes rather
  than the decoded resource for a reason that is easy to miss: a signature over
  a document has to be over bytes that can be reproduced exactly, and
  re-serializing a resource does not reproduce anything. `pr-str` of a Clojure
  map is not order-stable — above eight entries the map is a hash map and the
  print order follows hashing — so a digest taken over `envelope-bytes` of a
  decoded resource would differ between two processes holding the same
  document.

  The bytes of a version, by contrast, never change: `write-item` gives every
  version its own `:object-ref` and nothing rewrites one. So `:object-ref` is
  returned alongside, and it is what an evidence record names as the thing the
  digest was taken over.

  Everything about permission is `drive.object/read-item`'s answer, exactly as
  in `content` — this is a second reader of the same bytes, not a second path
  to them."
  ([id actor] (source-bytes id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [workspace owner own?] :as found} (locate (store/snapshot) actor id)
         _ (when-not found (refuse! {:reason :no-such-item :item-id id}))
         result (object/read-item workspace object-store id actor)]
     (if (:ok? result)
       (let [item (ws/item workspace id)]
         {:ok? true
          :bytes (:bytes result)
          :object-ref (:drive/object-ref item)
          :media-type (:drive/media-type item)
          :resource-kind (some-> (:drive/resource-kind item) str)
          :resource (stored-payload id item (:bytes result))
          :item (item-view item {:owner owner :own? own?
                                 :role (ws/effective-role workspace id actor)})})
       (refuse! result)))))

(def reference-kinds
  "Which block kinds are a reference, and what each is meant to point at.

  The expectation is advisory: `docs.model` names the kinds and does not say
  a `:table-ref` must be a workbook, so pointing one at a deck is reported
  and not refused. What is refused is a save whose reference goes nowhere at
  all — that is a warning too, because a draft may name something that is
  about to be shared."
  {:table-ref :sheets/workbook
   :file-ref nil
   :deck-ref :slides/deck})

(defn- reference-blocks
  "The reference blocks of a *rehydrated* document.

  Rehydrated, because `:docs/kind` is a keyword there and a bare string on
  the projection — the same reason validation cannot run on a payload."
  [document]
  (->> (:docs/blocks document)
       (filter map?)
       (filter #(contains? reference-kinds (:docs/kind %)))))

(defn- resource-of
  "The resource behind an item, read through the ACL.

  No conversion: `content` returns the EDN it read. This function used to
  project and immediately rehydrate, which is the round trip that storing
  EDN removes."
  [id actor object-store]
  (:resource (content id actor object-store)))

(defn- reference-warnings
  "Dangling and mistyped references, as save-time warnings.

  Warnings rather than errors, for the same reason `docs.validate` treats a
  missing title as one: a document being written may name something that is
  about to exist, and refusing the save would make writing it impossible."
  [document actor]
  (let [visible (into {} (map (juxt :id identity)) (documents (store/snapshot) actor))]
    (vec
     (for [block (reference-blocks document)
           :let [target (:docs/target block)
                 kind (:docs/kind block)
                 hit (get visible target)
                 expect (get reference-kinds kind)]
           :when (or (nil? hit)
                     (and expect (not= (str expect) (:resource-kind hit))))]
       (if hit
         {:code ":reference/unexpected-kind"
          :message (str "「" (:docs/id block) "」の参照先は "
                        (:label hit) " です（" (name kind) " が想定するものと異なります）。")}
         {:code ":reference/dangling"
          :message (str "「" (:docs/id block) "」の参照先 " (pr-str target)
                        " は見つかりません。")})))))

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
  ([kind title actor] (create! kind title actor (store-instance) {}))
  ([kind title actor object-store] (create! kind title actor object-store {}))
  ([kind title actor object-store {:keys [folder resource-fn]}]
   (let [spec (get kinds kind)]
     (when-not spec
       (throw (ex-info "作成できるのはスプレッドシート・ドキュメント・フォームだけです。"
                       {:type :drive/unknown-kind :kind kind
                        :known (vec (keys kinds))})))
     (when (str/blank? (str actor))
       (throw (ex-info "作成者を特定できません。" {:type :identity/unauthenticated})))
     (locking write-lock
       (let [{:keys [workspace owner parent]}
             (locate-folder! (store/snapshot) actor folder)
             id (store/new-id "doc")
             title (or (not-empty (str/trim (str title))) (:default-title spec))
             created-at (store/now)
             ;; `resource-fn` rather than a resource, because the id is
             ;; minted here and the contents have to carry it. Copying and
             ;; importing both used to create a seeded document and then
             ;; write over it, which left every one of them with a first
             ;; version that was an empty document nobody ever made —
             ;; offered by the history pane and restorable.
             resource ((or resource-fn (:seed spec)) id title)
             ;; The same check `write-resource!` makes, because this is now
             ;; the same act. Before `resource-fn` existed, creating always
             ;; produced a seed and validating one would have been checking
             ;; this file against itself; now a copy or an import arrives
             ;; here whole, and a document that cannot be saved must not be
             ;; creatable either. Leaving it out let a broken .edn import
             ;; succeed — silent in the direction that looks like success.
             errors (:errors (problems-in spec resource))
             _ (when (seq errors)
                 (throw (ex-info (str "作成できません: " (:message (first errors)))
                                 {:type :drive/invalid-document :problems errors})))
             envelope (stored-envelope spec resource)
             staged (ws/create-file workspace id parent
                                    {:drive/title title
                                     :drive/media-type stored-media-type
                                     :drive/resource-kind (:resource-kind spec)
                                     :drive/created-at created-at}
                                    actor)
             ;; A document belongs to the Drive it is in. `ws/create-file`
             ;; makes the creator the owner, which is right in your own
             ;; Drive and wrong in somebody else's: alice would be unable to
             ;; trash, purge or re-share something sitting in her own
             ;; folder, all of which are owner-only. So the Drive's owner
             ;; owns it and the creator is recorded as an editor — enough to
             ;; go on working on what they just made.
             staged (cond-> staged
                      (not= owner actor)
                      (assoc-in [:drive.workspace/items id :drive/permissions]
                                {owner :owner actor :editor}))
             written (object/write-item staged object-store id actor
                                        (envelope-bytes envelope)
                                        {:object-ref (object-ref)
                                         :created-at created-at})]
         (if (:ok? written)
           ;; Persisted under the *folder owner*, because that is whose
           ;; workspace was rewritten. Writing it back under the creator
           ;; would put a copy in their Drive and leave the folder owner's
           ;; unchanged — the document would appear to exist twice and be
           ;; the same document neither time.
           (do (store/transact! assoc-in (workspace-path owner) (:workspace written))
               {:schema schema
                :ok? true
                :item (item-view (ws/item (:workspace written) id)
                                 {:owner owner :own? (= owner actor)
                                  :role (ws/effective-role (:workspace written) id actor)})})
           (refuse! written)))))))


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
      (ws/trashed? workspace id) (refuse! {:reason :item-is-trashed :item-id id})
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
  [{:keys [workspace item spec owner own?]} id actor object-store resource expected-etag]
  (when-not (= expected-etag (:drive/object-ref item))
    ;; The lost update this exists to stop. Two editors open version 1, both
    ;; save, and the second write silently discards the first — measured
    ;; before this check existed: alice's paragraph was simply gone and the
    ;; UI said "saved". The bytes were still in the history, which is not the
    ;; same as anybody knowing to look.
    (throw (ex-info "他の人がこのドキュメントを更新しました。読み込み直してください。"
                    {:type :drive/stale-version
                     :item-id id
                     :etag (:drive/object-ref item)
                     :versions (count (:drive/versions item))
                     :updated-by (:drive.version/author (peek (:drive/versions item)))})))
  (let [{:keys [errors warnings]} (problems-in spec resource)
        ;; Reference checks are the app's, not a surface's: `docs.validate`
        ;; sees a `:docs/target` string and has no way to know whether it
        ;; names anything, because what it could name lives in a Drive it
        ;; does not know about.
        warnings (into (vec warnings) (reference-warnings resource actor))]
    (when (seq errors)
      (throw (ex-info (str "保存できません: " (:message (first errors)))
                      {:type :drive/invalid-document :problems errors})))
    (let [envelope (stored-envelope spec resource)
          title (or (not-empty (str/trim (str (get resource (:title-key spec)))))
                    (:drive/title item))
          ;; The resource's title and the Drive item's title are two places
          ;; for one fact, so a save keeps them together rather than letting
          ;; the list disagree with what is open.
          retitled (-> workspace
                       (assoc-in [:drive.workspace/items id :drive/title] title)
                       ;; A document written before EDN at rest still says
                       ;; application/json; the save that rewrites its bytes
                       ;; is the save that corrects what it claims to be.
                       (assoc-in [:drive.workspace/items id :drive/media-type]
                                 stored-media-type))
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
  why the rehydrate step is not an optimisation.

  `expected-etag` is the `:etag` the caller was given when it read the
  document — the object reference of the version it edited. A save whose
  etag is not the current one is refused rather than applied, because a
  document can now have two editors and the alternative is that the second
  save silently deletes the first one's work.

  Not optional, and not defaulted to the current value. A nil that meant
  \"whatever is there now\" would be the old behaviour under a new name."
  ([id payload actor expected-etag]
   (update! id payload actor expected-etag (store-instance)))
  ([id payload actor expected-etag object-store]
   (locking write-lock
     (let [{:keys [spec] :as target} (writable! actor id)]
       (write-resource! target id actor object-store ((:rehydrate spec) payload)
                        expected-etag)))))

(defn rename!
  "Change a document's title.

  This does record a new version, because the title is not only Drive
  metadata — it is inside the stored resource as `:sheets/title` and its
  siblings. Renaming only the Drive item would leave the two disagreeing,
  and the one that travels with the bytes is the one another reader sees.

  No etag from the caller: this reads the current resource itself, inside the
  lock, so what it writes is by construction based on what is there. A rename
  cannot be a lost update because it never carries a stale copy."
  ([id title actor] (rename! id title actor (store-instance)))
  ([id title actor object-store]
   (locking write-lock
     (let [{:keys [item spec] :as target} (writable! actor id)
           title (not-empty (str/trim (str title)))]
       (when-not title
         (throw (ex-info "名前を空にはできません。"
                         {:type :drive/invalid-document
                          :problems [{:code ":title/blank"
                                      :message "名前を空にはできません。"}]})))
       (let [resource (assoc (:resource (content id actor object-store))
                             (:title-key spec) title)]
         (write-resource! target id actor object-store resource
                          (:drive/object-ref item)))))))

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
         ;; An old version may still be JSON while the newest is EDN, which
         ;; is exactly what `decode-stored` is for — a Drive migrating as it
         ;; is used has both in the same item's history.
         (let [resource (stored-payload id item bytes)]
           {:schema schema
            :ok? true
            :item (item-view item {:owner owner :own? own?
                                   :trashed? (ws/trashed? workspace id)
                                   :role (ws/effective-role workspace id actor)})
            :index index
            :created-at (:drive.version/created-at version)
            :author (:drive.version/author version)
            :resource-kind (some-> (:drive/resource-kind item) str)
            :resource resource
            :payload (transit/write-json resource)})
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

(defn- shared-ref?
  "A predicate over object references that something *other* than `item-id`
  still points at.

  Only meaningful because references can be content-derived: an uploaded
  file's reference is its PieceCID, so two people holding the same PDF hold
  the same reference and deleting it for one destroys it for the other.
  Documents are unaffected — their references are uuids and no two items
  ever share one — so this answers false for every one of them and costs a
  scan nobody notices.

  Every workspace, not just this one. The other holder may be somebody else
  entirely; a check scoped to one Drive would be correct exactly until two
  people uploaded the same file, which is the case content addressing exists
  for.

  `current` is the workspace as it stands mid-purge, so that a folder's
  descendants already removed by earlier steps do not count as holders of
  what they used to hold."
  [current item-id]
  (let [refs-of (fn [item]
                  (into #{} (comp (map :drive.version/object-ref) (filter some?))
                        (cons {:drive.version/object-ref (:drive/object-ref item)}
                              (:drive/versions item))))
        others (fn [ws]
                 (->> (vals (:drive.workspace/items ws))
                      (remove #(= item-id (:drive/id %)))
                      (mapcat refs-of)))
        ;; The Drive being purged is `current` — the in-flight value, not
        ;; the stored copy. Earlier steps of this same purge have already
        ;; removed items from it, and counting those as holders would keep
        ;; the bytes of a folder's own contents alive for ever.
        here (:drive.workspace/id current)
        elsewhere (->> (all-workspaces (store/snapshot))
                       (remove (fn [[_ ws]] (= here (:drive.workspace/id ws))))
                       (mapcat (fn [[_ ws]] (others ws))))]
    (into #{} (concat (others current) elsewhere))))

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
  suggestion, and emptying it is a second, separate act.

  Purging a folder purges what is inside it, deepest first, and `:purged`
  says how many items that was. There is no other way for those bytes to be
  reclaimed: they are in the trash because their folder is, so nothing ever
  lists them on their own."
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
         (not (ws/trashed? workspace id))
         (throw (ex-info "先にゴミ箱へ移動してください。"
                         {:type :drive/not-trashed :item-id id}))
         :else
         (let [;; Deepest first, then the item itself. A folder purged before
               ;; what is inside it would leave those files pointing at a
               ;; parent that no longer exists — and `trashed?` walks
               ;; upwards, so the walk would end at a missing item and answer
               ;; "not in the trash". They would come back into the listing,
               ;; unreachable and undeletable, having been resurrected by the
               ;; deletion of their folder.
               order (conj (vec (reverse (ws/descendants workspace id))) id)
               result
               (reduce
                (fn [{:keys [ws freed] :as acc} target]
                  (let [child (ws/item ws target)
                        ;; A folder holds no bytes, so there is nothing for
                        ;; `forget-item` to forget and no quota to return.
                        forgotten (if (= :folder (:drive/kind child))
                                    {:ok? true :workspace ws :freed-bytes 0}
                                    (object/forget-item
                                     ws object-store target actor
                                     {:keep-ref? (shared-ref? ws target)}))]
                    (if (:ok? forgotten)
                      (let [parent (or (:drive/parent-id child)
                                       (:drive.workspace/root-id ws))]
                        {:ws (-> (:workspace forgotten)
                                 (update :drive.workspace/items dissoc target)
                                 ;; Its own parent, not the root. Everything
                                 ;; lived at the root until folders existed,
                                 ;; so this read correctly and meant the
                                 ;; wrong thing — a purged file would have
                                 ;; stayed listed in its folder for ever,
                                 ;; pointing at an item that is gone.
                                 (update-in [:drive.workspace/items parent
                                             :drive/children]
                                            (fn [children]
                                              (vec (remove #{target} children)))))
                         :freed (+ freed (or (:freed-bytes forgotten) 0))})
                      (reduced (assoc acc :failed forgotten)))))
                {:ws workspace :freed 0}
                order)]
           (if-let [failed (:failed result)]
             (refuse! failed)
             (do (store/transact! assoc-in (workspace-path owner) (:ws result))
                 {:schema schema :ok? true :id id
                  :purged (count order)
                  :freed-bytes (:freed result)
                  :quota (quota-view (store/snapshot) owner)}))))))))

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
        ;; What was removed, not how many things were listed. Purging a
        ;; folder purges what is inside it, so the two stopped being the
        ;; same number the moment folders existed — and the one worth
        ;; reporting is the one that says how much is gone.
        :purged (reduce + 0 (map #(or (:purged %) 1) results))
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
  (let [{:keys [workspace]} (owned! actor id)
        item (ws/item (stored-workspace-for (store/snapshot) actor) id)
        links (->> (vals (:drive.workspace/share-links workspace))
                   (filter #(= id (:drive.share/item-id %)))
                   (mapv (fn [link]
                           {:token (:drive.share/token link)
                            :role (name (:drive.share/role link))
                            :expires-at (:drive.share/expires-at link)})))]
    {:schema schema
     :ok? true
     :id id
     :issuer (capability/issuer-did)
     :grants (->> (:drive/permissions item)
                  (remove (fn [[principal _]] (= principal actor)))
                  (mapv (fn [[principal role]]
                          (let [cap (capability-for (store/snapshot) id principal)]
                            (cond-> {:principal principal :role (name role)}
                              cap (assoc :expires-at (:exp cap)
                                         :capability (:cacao-b64 cap)
                                         ;; Checked here so the answer shown
                                         ;; is the library's, not a guess
                                         ;; from the dates.
                                         :verified?
                                         (:valid? (capability/verify-grant
                                                   cap id role
                                                   (java.time.Instant/now)))))))))
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
        ;; The ACL entry and the capability are written together. The entry
        ;; is what `drive` answers from; the capability is what anybody else
        ;; can check, and what stops the entry being true forever.
        (let [granted (ws/grant workspace id principal role)
              cap (capability/mint-grant {:document-id id :role role
                                          :audience principal})]
          (store/transact! assoc-in (workspace-path owner) granted)
          (store/transact! assoc-in (capability-path id principal) cap)
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
        ;; The capability goes with the entry. Leaving it behind would mean
        ;; handing a revoked grantee something that still verifies, which is
        ;; the failure this layer exists to not have — and is why revocation
        ;; is still this server's word rather than the capability's.
        (store/transact! update-in [:drive :capabilities id] dissoc principal)
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
                 resource (stored-payload (:drive.share/item-id link) item (:bytes result))]
             {:schema schema
              :ok? true
              :item (item-view item {:owner owner :own? (= owner actor)
                                     :role (:drive.share/role link)})
              :role (name (:role result))
              :resource-kind (some-> (:drive/resource-kind item) str)
              :resource resource
              :payload (transit/write-json resource)})
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
      ;; The EDN as stored. This used to project and rehydrate straight back,
      ;; which is the round trip EDN at rest removes.
      {:owner owner
       :item item
       :form (:resource (content id actor object-store))})))

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
       (record-submission! id (:owner (:item read)) (:resource read) answers actor)))))

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
      (ws/trashed? workspace id) (refuse! {:reason :item-is-trashed :item-id id})
      :else {:owner owner :item item
             :role (ws/effective-role workspace id actor)})))

(defn- thread-root
  "The comment a reply belongs to, or the comment itself.

  One level. A reply to a reply is still a reply to the thread, because a
  conversation about one anchor is one conversation — and a tree would let
  somebody resolve half of it."
  [existing comment-id]
  (let [by-id (into {} (map (juxt :id identity)) existing)
        c (get by-id comment-id)]
    (get by-id (or (:parent-id c) comment-id))))

(defn comments
  "Every comment on this document as threads, oldest first.

  Visible to anyone who may read the document, including a viewer: being
  shown a document and not what has been said about it is a strange half of
  a thing to be shown.

  Replies are nested under the comment they answer rather than returned
  flat, because a flat list with parent ids is a tree the caller has to
  build, and every caller would build it slightly differently."
  [id actor]
  (readable! actor id)
  (let [existing (get-in (store/snapshot) (comments-path id) [])
        public (mapv #(dissoc % :owner) existing)
        replies (group-by :parent-id (filter :parent-id public))]
    {:schema schema
     :ok? true
     :id id
     :comments (mapv (fn [root]
                       (assoc root :replies (vec (get replies (:id root) []))))
                     (remove :parent-id public))
     ;; The count that matters when deciding whether to look: an unresolved
     ;; thread is one somebody is still waiting on.
     :unresolved (clojure.core/count
                  (remove #(or (:parent-id %) (:resolved-at %)) public))}))

(defn comment!
  "Leave a comment, or a reply to one.

  `anchor` is free text and optional — a block id for a document, a cell
  address for a workbook, nothing at all for a remark about the whole thing.
  Deliberately not interpreted here: the moment this parsed one it would owe
  every surface a different parser, and the surfaces are where that
  knowledge lives.

  A reply takes its anchor from the comment it answers, because a reply that
  could point somewhere else would not be a reply. Replying to a reply
  attaches to the same thread rather than nesting further — see
  `thread-root`."
  ([id text anchor actor] (comment! id text anchor actor nil))
  ([id text anchor actor parent-id]
   (locking write-lock
     (let [{:keys [owner role]} (readable! actor id)
           text (not-empty (str/trim (str text)))
           existing (get-in (store/snapshot) (comments-path id) [])
           parent (when parent-id (thread-root existing parent-id))]
       (cond
         (not (contains? comment-roles role))
         (refuse! {:reason :not-permitted :item-id id :principal actor})
         (nil? text)
         (throw (ex-info "コメントを入力してください。"
                         {:type :drive/invalid-comment :field :text}))
         (and parent-id (nil? parent))
         (throw (ex-info "返信先のコメントが見つかりません。"
                         {:type :drive/not-found :comment-id parent-id}))
         (and parent (:resolved-at parent))
         ;; Reopening is an act somebody takes on purpose, not something a
         ;; reply does on their behalf.
         (throw (ex-info "解決済みのコメントには返信できません。先に未解決へ戻してください。"
                         {:type :drive/comment-resolved :comment-id (:id parent)}))
         :else
         (let [record (cond-> {:id (store/new-id "cmt")
                               :document-id id
                               :owner owner
                               :author actor
                               :text text
                               :anchor (if parent
                                         (:anchor parent)
                                         (not-empty (str/trim (str anchor))))
                               :created-at (store/now)}
                        parent (assoc :parent-id (:id parent)))]
           (store/transact! update-in (comments-path id) (fnil conj []) record)
           {:schema schema :ok? true :comment (dissoc record :owner)}))))))

(defn resolve-comment!
  "Mark a thread resolved, or put it back.

  Anyone who may comment may resolve. Unlike deleting, this takes nothing
  away and `resolved?` false undoes it — the reason deleting is narrower is
  that it is not reversible, and that reason does not apply here.

  Resolution belongs to the thread. Resolving a reply resolves the comment
  it answers, because half a resolved conversation is not a state anybody
  can act on."
  [id comment-id resolved? actor]
  (locking write-lock
    (let [{:keys [role]} (readable! actor id)
          existing (get-in (store/snapshot) (comments-path id) [])
          root (thread-root existing comment-id)]
      (cond
        (nil? root)
        (throw (ex-info "コメントが見つかりません。"
                        {:type :drive/not-found :comment-id comment-id}))
        (not (contains? comment-roles role))
        (refuse! {:reason :not-permitted :item-id id :principal actor})
        :else
        (let [updated (mapv (fn [c]
                              (if (= (:id c) (:id root))
                                (if resolved?
                                  (assoc c :resolved-at (store/now) :resolved-by actor)
                                  (dissoc c :resolved-at :resolved-by))
                                c))
                            existing)]
          (store/transact! assoc-in (comments-path id) updated)
          (comments id actor))))))

(defn delete-comment!
  "Remove a comment, and its replies if it is the start of a thread.

  Its author or the document's owner. An editor may rewrite the document and
  still not delete what somebody said about it — those are different things
  and only one of them is the content.

  Deleting the root takes the replies with it. A reply to nothing is not
  something a reader can make sense of, and leaving one behind so that the
  deletion looks smaller is not honesty."
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
        (let [gone (into #{comment-id}
                         (when-not (:parent-id target)
                           (map :id (filter #(= comment-id (:parent-id %)) existing))))]
          (store/transact! assoc-in (comments-path id)
                           (vec (remove #(contains? gone (:id %)) existing)))
          {:schema schema :ok? true :id comment-id :deleted (clojure.core/count gone)})))))

;; ── references between documents ────────────────────────────────────────────
;;
;; `docs.model` has had `:table-ref`, `:file-ref` and `:deck-ref` blocks since
;; before any of this, each carrying a `:docs/target` string, and nothing has
;; ever resolved one. A document that can name a workbook but never reach it
;; is four surfaces sharing a pane rather than four surfaces that know about
;; each other.
;;
;; A target is a Drive item id. Not a URL and not a `slides:intro-deck`-style
;; scheme — the seed document in `docs.model` uses one of those, and it is a
;; placeholder rather than a format anything parses. An id is what `locate`
;; already resolves, which means a reference obeys the same permission answer
;; as everything else: you can follow a link to a document you may read, and
;; a link to one you may not is indistinguishable from a link to nothing.

(defn references
  "What this document points at, and whether each target is reachable.

  Only `docs` documents carry references today. A workbook has no block that
  names another document, and a deck's links live on a `slides` workspace
  rather than in the deck itself — the envelope carries one deck, so there is
  nowhere in it for a link to sit."
  ([id actor] (references id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [item]} (readable! actor id)
         document (when (= :docs/document (:drive/resource-kind item))
                    (resource-of id actor object-store))
         visible (into {} (map (juxt :id identity)) (documents (store/snapshot) actor))]
     {:schema schema
      :ok? true
      :id id
      :references
      (mapv (fn [block]
              (let [target (:docs/target block)
                    kind (:docs/kind block)
                    hit (get visible target)]
                (cond-> {:block (:docs/id block)
                         :kind (name kind)
                         :target target
                         :resolved? (some? hit)}
                  hit (assoc :name (:name hit)
                             :label (:label hit)
                             :resource-kind (:resource-kind hit)
                             :expected? (let [expect (get reference-kinds kind)]
                                          (or (nil? expect)
                                              (= (str expect) (:resource-kind hit))))))))
            (reference-blocks document))})))

(defn referenced-by
  "Which of the documents this principal can see point at `id`.

  Backlinks, and the reason references are worth resolving at all: a workbook
  that cannot say which memo depends on it is a workbook nobody dares
  change."
  ([id actor] (referenced-by id actor (store-instance)))
  ([id actor object-store]
   (readable! actor id)
   (let [state (store/snapshot)]
     {:schema schema
      :ok? true
      :id id
      :referenced-by
      (vec
       (for [candidate (documents state actor)
             :when (= ":docs/document" (:resource-kind candidate))
             :let [document (try (resource-of (:id candidate) actor object-store)
                                 ;; A document whose bytes are gone should not
                                 ;; make the backlinks of a different one fail.
                                 (catch clojure.lang.ExceptionInfo _ nil))]
             block (reference-blocks document)
             :when (= id (:docs/target block))]
         {:id (:id candidate)
          :name (:name candidate)
          :label (:label candidate)
          :block (:docs/id block)
          :kind (name (:docs/kind block))}))})))


;; ── import and export ───────────────────────────────────────────────────────
;;
;; Two formats that already existed somewhere and were not reachable from
;; here: CSV, which `sheets.csv` gained for this, and PPTX, which `slides`
;; has had all along in `slides.pptx` and `slides.office` without the Drive
;; ever offering it.
;;
;; EDN is the third, and it is free: the stored bytes are already the EDN
;; envelope, so exporting one is handing over what is on disk. That makes
;; every surface exportable, including the two with no office format at all.

(defn- safe-filename
  "A title as a filename. Refuses to become a path, for the same reason
  `drive.store.fs` refuses an object reference that could be one."
  [title extension]
  (let [base (-> (str title)
                 (str/replace #"[^\p{L}\p{N}_.-]" "_")
                 (str/replace #"^[.]+" "_"))]
    (str (if (str/blank? base) "document" base) "." extension)))

(defn responses-workbook
  "This form's answers as a one-tab workbook.

  The bridge between `forms.responses`, which returns a grid and stops, and
  `sheets`, which is what a grid is for. Both are already here, which is why
  the join lives in this application rather than making `forms` depend on
  `sheets`.

  A snapshot, not a link. Google Forms keeps its sheet updated as answers
  arrive; this is the responses at the moment it was asked for, and calling
  it a linked spreadsheet would be describing a feature that is not here."
  [form id]
  (let [rows (forms-responses/rows-with-header
              form (get-in (store/snapshot) (submissions-path id) []))]
    (sheets/add-tab
     (sheets/workbook id {:sheets/title (:forms/title form)})
     (reduce-kv (fn [tab r cells]
                  (reduce-kv (fn [tab c value]
                               (sheets/put-cell tab (inc r) (inc c) value))
                             tab
                             (vec cells)))
                (sheets/tab "回答" {:sheets/title "回答"})
                (vec rows)))))

(def ^:private upload-media-type
  "What an uploaded file is served as, whatever it claims to be.

  Not the client's Content-Type. Bytes uploaded by one person and served
  from this origin to another are stored XSS if the browser is allowed to
  decide they are HTML — `text/html` with a script in it, opened from the
  Drive, runs with this app's session. The declared type is kept on the item
  so the list can say what it is; the response says
  `application/octet-stream` and `Content-Disposition: attachment`, and the
  browser downloads it instead of rendering it.

  The cost is that an image cannot be previewed inline. Previewing safely
  means serving user bytes from a different origin, which is a decision
  about what this app is allowed to talk to and not one to make while adding
  uploads."
  "application/octet-stream")

(defn upload!
  "Put arbitrary bytes in `actor`'s Drive — a PDF, an image, a zip.

  **The reference is the content's PieceCID**, so the store is content
  addressed: `cloud.itonami.app.filecoin/piece-ref` computes it, and `drive`
  never has to know what a PieceCID is because it lets the caller name the
  reference. Two people uploading the same file store one object.

  That has two consequences the rest of this file has to honour, and both
  are handled rather than hoped for. `drive.object/write-item` allows a
  reference already in use only when the bytes are identical, which is
  exactly this case and is checked against the store rather than asserted.
  And `purge!` passes `:keep-ref?`, so deleting one holder's copy does not
  delete the bytes another holder still points at.

  No resource kind, no envelope, no validator: this is not one of the four
  surfaces and pretending it is would mean a document whose `:docs/blocks`
  is a PDF. `content` refuses it and says so."
  ([filename media-type bytes actor] (upload! filename media-type bytes actor
                                              (store-instance) {}))
  ([filename media-type bytes actor object-store]
   (upload! filename media-type bytes actor object-store {}))
  ([filename media-type bytes actor object-store {:keys [folder]}]
   (when (str/blank? (str actor))
     (throw (ex-info "作成者を特定できません。" {:type :identity/unauthenticated})))
   (when (or (nil? bytes) (zero? (alength ^bytes bytes)))
     (throw (ex-info "空のファイルはアップロードできません。"
                     {:type :drive/invalid-document
                      :problems [{:code ":file/empty"
                                  :message "空のファイルはアップロードできません。"}]})))
   (locking write-lock
     (let [{:keys [workspace owner parent]}
           (locate-folder! (store/snapshot) actor folder)
           id (store/new-id "file")
           title (or (not-empty (str/trim (str filename))) "無題のファイル")
           created-at (store/now)
           staged (ws/create-file workspace id parent
                                  {:drive/title title
                                   ;; What it says it is, kept for the list.
                                   ;; What it is *served* as is decided at
                                   ;; the response — see `upload-media-type`.
                                   :drive/media-type (or (not-empty (str media-type))
                                                         upload-media-type)
                                   :drive/created-at created-at}
                                  actor)
           staged (cond-> staged
                    (not= owner actor)
                    (assoc-in [:drive.workspace/items id :drive/permissions]
                              {owner :owner actor :editor}))
           written (object/write-item staged object-store id actor bytes
                                      {:object-ref (filecoin/piece-ref bytes)
                                       :created-at created-at})]
       (if (:ok? written)
         (do (store/transact! assoc-in (workspace-path owner) (:workspace written))
             {:schema schema :ok? true
              :item (item-view (ws/item (:workspace written) id)
                               {:owner owner :own? (= owner actor)
                                :role (ws/effective-role (:workspace written) id actor)})})
         (refuse! written))))))

(defn file-bytes
  "An uploaded file's bytes, read back through the ACL.

  Through `drive.object/read-item`, which is what answers whether this
  principal may have them — the same seam every document read goes through,
  for the same reason."
  ([id actor] (file-bytes id actor (store-instance)))
  ([id actor object-store]
   (let [{:keys [workspace]} (locate (store/snapshot) actor id)
         item (when workspace (ws/item workspace id))]
     (when-not item (refuse! {:reason :no-such-item :item-id id}))
     (when (:drive/resource-kind item)
       (throw (ex-info "これはドキュメントです。書き出しを使ってください。"
                       {:type :drive/not-a-file :item-id id})))
     (let [result (object/read-item workspace object-store id actor)]
       (if (:ok? result)
         {:schema schema :ok? true :id id
          :filename (:drive/title item)
          :declared-media-type (:drive/media-type item)
          :media-type upload-media-type
          :object-ref (:drive/object-ref item)
          :bytes (:bytes result)}
         (refuse! result))))))

(defn copy!
  "A new document with this one's contents, in `actor`'s Drive.

  What every Drive calls *make a copy*, and the one operation a reader of a
  shared document actually needs: until now the only way to get an editable
  version of something shared read-only was to export it and import it back,
  which is two steps, goes through bytes, and loses the kind on the way if
  the surface has no office format.

  So `readable!` and not `writable!` — a viewer may copy, and that is the
  point rather than an oversight.

  **Four things are deliberately left behind, and each would be a bug if it
  came along.**

  *The grants.* Copying a document shared with five people must not share
  the copy with them. It is a new document created by `create!`, which gives
  the creator `:owner` and nobody anything, so this falls out — and is
  asserted anyway, because getting it wrong is a silent access leak rather
  than a visible fault.

  *The comments and the responses.* They are about the document somebody
  said them about. A copy carrying its original's comment threads would put
  words in a conversation that did not happen.

  *The history.* The copy has one version, which is this one. A copy is not
  a fork of the past; the original still has all of it.

  *The quota.* Charged to whoever made the copy, unlike editing a shared
  document — which is charged to the owner, because the bytes stay in their
  Drive. Here the bytes are new and they are in the copier's Drive."
  ([id actor] (copy! id actor (store-instance) {}))
  ([id actor object-store] (copy! id actor object-store {}))
  ([id actor object-store {:keys [title folder]}]
   (let [{:keys [item]} (readable! actor id)
         kind (get resource-kinds (:drive/resource-kind item))
         source (:resource (content id actor object-store))
         ;; Google's convention, and a name that says what it is: two
         ;; documents called 議事録 in one listing is a choice nobody made.
         name (or (not-empty (str/trim (str title)))
                  (str (:drive/title item) " のコピー"))
         spec (get kinds kind)]
     (create! kind name actor object-store
              {:folder folder
               ;; One version, which is this one. The document arrives with
               ;; its contents rather than being seeded empty and written
               ;; over — see `create!`.
               :resource-fn (fn [copy-id copy-title]
                              (assoc source
                                     (:title-key spec) copy-title
                                     (:id-key spec) copy-id))}))))

(defn export
  "One document in `format`, as bytes plus what to call them.

  Returns `{:media-type :filename :bytes}` where `:bytes` is a JVM byte
  array, because that is what an HTTP response wants and what
  `slides.pptx/pptx-bytes` produces."
  ([id format actor] (export id format actor (store-instance) {}))
  ([id format actor object-store] (export id format actor object-store {}))
  ([id format actor object-store {:keys [tab]}]
   (let [{:keys [item]} (readable! actor id)
         kind (get resource-kinds (:drive/resource-kind item))
         available (get export-formats kind)
         shape (get available format)]
     (when-not shape
       (throw (ex-info (str "この種類は " (pr-str format) " で書き出せません。")
                       {:type :drive/unsupported-format
                        :format format
                        :available (vec (sort (keys available)))})))
     ;; A format that writes something other than the document is asked
     ;; about separately. `readable!` answers whether this principal may have
     ;; the *form*; a viewer of a form may not have its responses, and a
     ;; download route inheriting the document's permission would be the
     ;; quietest possible way to hand them over.
     (when (:owner-only? shape) (owned! actor id))
     (let [resource (:resource (content id actor object-store))
           text (case format
                  "edn" (pr-str (stored-envelope (get kinds kind) resource))
                  "md" (docs-md/write resource)
                  "csv" (if (= :forms kind)
                          ;; Responses, not questions. Through a workbook
                          ;; rather than joining strings here, so the quoting
                          ;; is `sheets.csv`'s one implementation of it — an
                          ;; answer containing a comma or a newline is
                          ;; ordinary, and a second escaping routine is a
                          ;; second place to get it wrong.
                          (sheets-csv/workbook->csv (responses-workbook resource id) "回答")
                          (let [tab-id (or tab (first (sort (keys (:sheets/tabs resource)))))]
                            (or (sheets-csv/workbook->csv resource tab-id)
                                (throw (ex-info (str "タブ " (pr-str tab-id) " はありません。")
                                                {:type :drive/not-found :tab tab-id
                                                 :tabs (vec (sort (keys (:sheets/tabs resource))))})))))
                  nil)]
       {:media-type (:media-type shape)
        :filename (safe-filename (:drive/title item) (:extension shape))
        :bytes (case format
                 ;; The two that are already bytes; everything else was
                 ;; built as text above.
                 "pptx" (slides-pptx/pptx-bytes resource)
                 "xlsx" (sheets-xlsx/xlsx-bytes resource)
                 "docx" (docs-docx/docx-bytes resource)
                 (.getBytes ^String text StandardCharsets/UTF_8))}))))

(defn responses-sheet!
  "Put this form's answers into a new workbook in the Drive.

  What Google Forms means by sending responses to a spreadsheet, with one
  difference stated in the name and in the document it produces: **this is a
  snapshot.** Theirs stays up to date as answers arrive; this is what had
  been collected when it was asked for. Keeping it current would mean every
  submission writing a second document — a new version, charged to the
  owner's quota, on a document somebody may be editing.

  Owner-only for the same reason the CSV is: it is the answers, not the
  questions.

  A new document each time rather than overwriting the last one. Two
  snapshots of different days are two things somebody may want, and
  silently replacing the earlier one would destroy a document the owner
  never asked to lose."
  ([id actor] (responses-sheet! id actor (store-instance)))
  ([id actor object-store]
   (let [_ (owned! actor id)
         {:keys [form]} (readable-form! id actor object-store)
         item (ws/item (:workspace (locate (store/snapshot) actor id)) id)
         title (str (:drive/title item) " の回答 " (store/now))
         created (create! :sheets title actor object-store)
         sheet-id (:id (:item created))
         workbook (assoc (responses-workbook form id)
                         :sheets/id sheet-id
                         :sheets/title (:name (:item created)))
         target (writable! actor sheet-id)]
     (write-resource! target sheet-id actor object-store workbook
                      (:drive/object-ref (:item target))))))

(defn- office-parts
  "The entry names of `bytes` if they are a zip, else nil.

  A format check rather than a guess. Both office readers answer something
  for bytes that are not the file they claim to be — `slides.office` builds
  a deck with one empty slide from an empty graph, and `sheets.xlsx` reads a
  zip with no worksheets as a workbook with no tabs — so neither can be
  asked whether the input was really a package. The package can."
  [^bytes bytes]
  (try
    (with-open [zip (java.util.zip.ZipInputStream.
                     (java.io.ByteArrayInputStream. bytes))]
      (loop [names []]
        (if-let [entry (.getNextEntry zip)]
          (recur (conj names (.getName entry)))
          (when (seq names) names))))
    (catch Exception _ nil)))

(defn- require-office-package!
  "Refuse bytes that are not a package of the kind `format` names.

  An import that silently produced an empty document would look exactly like
  a working import of an empty file, which is the one failure a reader
  cannot tell from success. Measured: three bytes of `x` imported as pptx
  produced a one-slide deck, and as xlsx a workbook with no tabs, both
  reported as successes."
  [format ^bytes bytes]
  (let [prefix (case format "pptx" "ppt/" "xlsx" "xl/" "docx" "word/" nil)]
    (when prefix
      (let [names (office-parts bytes)]
        (when-not (some #(str/starts-with? % prefix) (or names []))
          (throw (ex-info (str (str/upper-case format) " として読めませんでした。")
                          {:type :drive/unsupported-format :format format})))))))

(defn import!
  "A new document from `bytes` in `format`.

  Creates rather than replaces. Importing into an existing document would be
  a save, and a save has an etag; an import has a file and no idea what it is
  landing on top of.

  It lands through `create!` and then `write-resource!` — the same path a
  save takes — so quota, ACL, versioning and the surface's own validator all
  apply to it exactly as they do to anything else. An imported deck that is
  not a deck is refused with the same code as a typed one."
  ([format title bytes actor] (import! format title bytes actor (store-instance)))
  ([format title bytes actor object-store]
   (when-not (contains? import-formats format)
     (throw (ex-info (str "読み込めない形式です: " (pr-str format))
                     {:type :drive/unsupported-format
                      :available (vec (sort (keys import-formats)))})))
   (require-office-package! format bytes)
   (let [text (delay (String. ^bytes bytes StandardCharsets/UTF_8))
         [kind imported]
         (case format
           "csv" [:sheets nil]
           "md" [:docs (docs-md/read @text)]
           "xlsx" [:sheets (sheets-xlsx/workbook-from-bytes bytes)]
           "pptx" [:slides (slides-office/deck-from-office-bytes bytes)]
           "docx" [:docs (docs-docx/document-from-bytes bytes)]
           "edn" (let [envelope (edn/read-string @text)
                       k (get resource-kinds (:kotoba.resource/kind envelope))]
                   (when-not k
                     (throw (ex-info "この EDN はこの Drive の資源ではありません。"
                                     {:type :drive/unsupported-format
                                      :kind (str (:kotoba.resource/kind envelope))})))
                   [k (:kotoba.resource/payload envelope)]))
         ;; Bytes that are not the file they claim to be. `slides.office`
         ;; answers nil; `sheets.xlsx` answers an empty workbook, because a
         ;; zip with no worksheet parts is still a zip — so the emptiness is
         ;; what has to be checked. An import that silently produced an empty
         ;; document would look exactly like a working import of an empty
         ;; file, which is the one failure a reader cannot tell from success.
         ;; pptx only: csv builds from the seeded workbook and legitimately
         ;; has no `imported`, and edn has already thrown if its kind was
         ;; not one of ours.
         _ (when (and (= "pptx" format) (nil? imported))
             (throw (ex-info (str (str/upper-case format) " として読めませんでした。")
                             {:type :drive/unsupported-format :format format})))
         spec (get kinds kind)]
     ;; One version, which is the file. This used to create a seeded
     ;; document and write over it, so every imported document had a first
     ;; version that was an empty one nobody ever had — offered by the
     ;; history pane and restorable.
     (create! kind (or (not-empty (str/trim (str title)))
                       (str "取り込み " (store/now)))
              actor object-store
              {:resource-fn
               (fn [doc-id doc-title]
                 (cond
                   ;; CSV is the one format that builds *onto* a seed
                   ;; rather than replacing it: it is one tab's cells and
                   ;; not a workbook.
                   (= "csv" format)
                   (sheets-csv/import-csv ((:seed spec) doc-id doc-title)
                                          "imported" @text)

                   ;; A workbook arrives whole, tabs and all, so it
                   ;; replaces the seeded one rather than being added
                   ;; beside it — importing a five-tab file should not
                   ;; leave an empty "sheet1" in front of them.
                   (= "xlsx" format)
                   (assoc imported :sheets/id doc-id :sheets/title doc-title)

                   :else
                   ;; Keep the ids the Drive just minted and the title the
                   ;; caller asked for; take everything else from the file.
                   (assoc imported
                          (:title-key spec) doc-title
                          ;; From the kinds table rather than from a guess.
                          ;; This read `(if (= kind :slides) :slides/id
                          ;; :docs/id)`, so an EDN-imported form gained a
                          ;; stray `:docs/id` and kept the original's
                          ;; `:forms/id` — a document that internally still
                          ;; said it was the one it came from.
                          (:id-key spec) doc-id)))}))))

;; ── searching inside documents ──────────────────────────────────────────────
;;
;; The Drive could filter a list of names. Everything else about a document —
;; what a cell says, what a paragraph says, what is written on a slide — was
;; unreachable except by opening it.
;;
;; What counts as text is the model's business and lives with each surface as
;; `:text`, next to `:vocabulary` and `:problems`. Searching is the app's,
;; because only the app knows which documents this principal may read.
;;
;; ## It reads everything, and that is the honest version of this
;;
;; Every readable document's bytes, on every search. No index, so nothing can
;; be stale and nothing has to be rebuilt — and it is linear in the size of
;; the Drive. That is the right trade at a scale where a Drive is one
;; household's documents, and the wrong one at an organisation's. When it
;; stops being right, the fix is an index keyed on the version, invalidated
;; by the object reference that already changes on every save.

(def ^:private snippet-radius
  "Characters either side of a match. Enough to see which occurrence it is."
  40)

(defn- snippet
  "The matching text, cut to a window around the match.

  Case-folded for finding and *not* for showing: a result that echoed back
  the query's casing rather than the document's would be quoting something
  the document does not say."
  [text needle]
  (let [text (str text)
        at (str/index-of (str/lower-case text) needle)]
    (if (nil? at)
      text
      (let [from (max 0 (- at snippet-radius))
            to (min (count text) (+ at (count needle) snippet-radius))]
        (str (when (pos? from) "…")
             (subs text from to)
             (when (< to (count text)) "…"))))))

(defn- text-of
  "Every piece of text in `resource`, per its surface. Empty for a surface
  with no extractor rather than an error — a new surface should be findable
  by name before it is findable by content."
  [kind resource]
  (if-let [extract (get-in kinds [kind :text])]
    (->> (extract resource) (keep identity) (map str) (remove str/blank?))
    []))

(defn search
  "Documents whose title or contents contain `query`, for this principal.

  Returns each match once, with where it was found and a snippet. A title
  match wins over a content match: it is the stronger signal and showing
  both for one document is noise."
  ([query actor] (search query actor (store-instance)))
  ([query actor object-store]
   (let [needle (str/lower-case (str/trim (str query)))]
     (if (str/blank? needle)
       {:schema schema :ok? true :query "" :count 0 :results []}
       (let [results
             (vec
              (for [candidate (documents (store/snapshot) actor)
                    :let [in-title? (str/includes? (str/lower-case (str (:name candidate)))
                                                   needle)
                          ;; Read once per document, and only when the title
                          ;; did not already answer.
                          hit (when-not in-title?
                                (let [resource (try
                                                 (:resource (content (:id candidate) actor
                                                                     object-store))
                                                 ;; A document whose bytes are
                                                 ;; gone must not fail the
                                                 ;; whole search.
                                                 (catch clojure.lang.ExceptionInfo _ nil))]
                                  (some #(when (str/includes? (str/lower-case %) needle) %)
                                        (text-of (keyword (:kind candidate)) resource))))]
                    :when (or in-title? hit)]
                {:id (:id candidate)
                 :name (:name candidate)
                 :label (:label candidate)
                 :kind (:kind candidate)
                 :own? (:own? candidate)
                 :owner (:owner candidate)
                 :where (if in-title? "title" "content")
                 :snippet (if in-title? (:name candidate) (snippet hit needle))}))]
         {:schema schema
          :ok? true
          :query (str/trim (str query))
          :count (count results)
          :results results})))))

;; ── going back to an earlier version ────────────────────────────────────────

(defn history
  "The versions of `id`, newest first, with what each one cost.

  `item-view` already carries the same list oldest-first as `:history`; this
  is the addressable form — each entry knows its own index, which is what a
  restore takes, and the size delta, which is what makes a list of identical
  timestamps readable."
  [id actor]
  (let [{:keys [item]} (readable! actor id)
        versions (vec (:drive/versions item))]
    {:schema schema
     :ok? true
     :id id
     :current (count versions)
     :versions
     (vec (reverse
           (map-indexed
            (fn [index version]
              (let [previous (when (pos? index) (nth versions (dec index)))]
                {:index (inc index)
                 :author (:drive.version/author version)
                 :created-at (:drive.version/created-at version)
                 :size-bytes (:drive.version/size-bytes version)
                 :delta-bytes (- (or (:drive.version/size-bytes version) 0)
                                 (or (:drive.version/size-bytes previous) 0))
                 :current? (= (inc index) (count versions))}))
            versions)))}))

(defn restore-version!
  "Put the contents of version `index` back, as a new version.

  Not a rewrite. The history is append-only and restoring is a new version
  whose contents happen to equal an old one — which is why the author
  recorded is whoever restored it and not whoever wrote it the first time.
  They made this version; the earlier one is still there saying who made
  that.

  It goes through `write-resource!` like any other save, so the validator
  sees it. That is not ceremony: a surface's rules can have tightened since,
  and silently reinstating something the model would now refuse is how a
  document becomes unopenable by the thing that owns it.

  Takes an etag for the same reason a save does. Restoring on top of
  somebody else's change, without seeing it, is the lost update wearing a
  different hat."
  ([id index actor expected-etag]
   (restore-version! id index actor expected-etag (store-instance)))
  ([id index actor expected-etag object-store]
   (locking write-lock
     (let [target (writable! actor id)
           older (version-content id index actor object-store)]
       (when (= index (count (:drive/versions (:item target))))
         (throw (ex-info "その版がすでに最新です。"
                         {:type :drive/already-current :item-id id :index index})))
       (assoc (write-resource! target id actor object-store (:resource older)
                               expected-etag)
              :restored-from index)))))

(def default-keep-versions
  "How many versions a prune keeps when the caller does not say.

  Ten, which is a number rather than a principle. It is enough that the
  history is still a history and small enough that a document edited all
  afternoon stops costing an afternoon's worth of storage. Nothing prunes on
  its own — see `prune!`."
  10)

(defn prune!
  "Forget all but the newest `keep-count` versions of `id`, and take the
  quota back.

  ## Nothing does this automatically, on purpose

  `add-version` adds and nothing subtracts, so an edited document's cost only
  goes up, and until `drive.object/prune-versions` the only way down was to
  delete the document. It would be easy to prune on every save and never
  mention it. That would also mean the Drive quietly deleting history
  somebody may be relying on, at a moment they did not choose, to solve a
  problem they had not noticed. So it is a thing the owner does.

  ## Owner only

  Irreversible, like `purge!`, and for the same reason: an editor may change
  a document and still not destroy the record of how it got that way."
  ([id actor] (prune! id actor default-keep-versions (store-instance)))
  ([id actor keep-count] (prune! id actor keep-count (store-instance)))
  ([id actor keep-count object-store]
   (locking write-lock
     (let [{:keys [workspace owner]} (locate (store/snapshot) actor id)
           item (when workspace (ws/item workspace id))]
       (cond
         (nil? item) (refuse! {:reason :no-such-item :item-id id})
         (not= :owner (ws/effective-role workspace id actor))
         (refuse! {:reason :not-permitted :item-id id :principal actor})
         :else
         (let [pruned (object/prune-versions workspace object-store id actor
                                             (long keep-count))]
           (if (:ok? pruned)
             (do (store/transact! assoc-in (workspace-path owner) (:workspace pruned))
                 {:schema schema
                  :ok? true
                  :id id
                  :deleted (:deleted pruned)
                  :kept (:kept pruned)
                  :freed-bytes (:freed-bytes pruned)
                  :item (item-view (ws/item (:workspace pruned) id)
                                   {:owner owner :own? (= owner actor) :role :owner})
                  :quota (quota-view (store/snapshot) owner)})
             (refuse! pruned))))))))
