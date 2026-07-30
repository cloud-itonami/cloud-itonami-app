(ns cloud.itonami.app.documents-test
  "What a created document has to be true of.

  The object store is `drive.store.memory` rather than the filesystem one,
  and the app state is a local atom rather than the process-wide one, so
  nothing here writes to the data dir."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.capability :as capability]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.filecoin :as filecoin]
            [drive.object]
            [cloud.itonami.app.store :as store]
            [drive.object :as object]
            [drive.store.memory :as memory]
            [drive.workspace :as ws]
            [forms.model :as forms-model]
            [forms.validate :as forms-validate]
            [forms.wire :as forms-wire]
            [sheets.model :as sheets-model]
            [sheets.wire :as sheets-wire]
            [docs.docx :as docs-docx]
            [sheets.xlsx :as sheets-xlsx]))

(def alice "user-alice")
(def bob "user-bob")

;; A fixed instant, because share-link expiry is compared numerically and a
;; test that read the clock would be a test whose meaning changed at midnight.
(def ^:private now-ms 1800000000000)

(defn- save!
  "`documents/update!` with the etag the document currently has.

  The ordinary case: a caller that has just read the document. Tests about
  the *stale* case call `documents/update!` directly with an etag of their
  own, which is the only way to be about it."
  [id payload actor object-store]
  (documents/update! id payload actor
                     (:etag (:item (documents/content id actor object-store)))
                     object-store))

(defn- with-state
  "Run `f` against a private app state and a private object store."
  [f]
  (let [state (atom (store/initial-state))]
    (with-redefs [store/snapshot (fn [] @state)
                  store/transact! (fn [g & args] (apply swap! state g args))]
      (f state (memory/store)))))

(deftest creates-one-document-of-each-kind
  (with-state
    (fn [state object-store]
      (doseq [[kind expected-resource] {:sheets ":sheets/workbook"
                                        :docs ":docs/document"
                                        :forms ":forms/form"}]
        (testing (name kind)
          (let [{:keys [ok? item]} (documents/create! kind "四半期計画" alice object-store)]
            (is ok?)
            (is (= "四半期計画" (:name item)))
            (is (= expected-resource (:resource-kind item)))
            ;; From the envelope rather than restated by the app: whatever
            ;; the wire says it produced is what the Drive records.
            (is (= "application/edn" (:media-type item)))
            (is (= 1 (:versions item)))
            (is (pos? (:size-bytes item)))
            (is (= "workspace" (:origin item))))))
      (is (= 3 (count (documents/documents @state alice)))))))

(deftest a-blank-title-gets-the-kind-default
  (with-state
    (fn [_ object-store]
      (is (= "無題のスプレッドシート"
             (get-in (documents/create! :sheets "   " alice object-store) [:item :name])))
      (is (= "無題のフォーム"
             (get-in (documents/create! :forms nil alice object-store) [:item :name]))))))

(deftest what-is-stored-is-edn
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計メモ" alice object-store)
            workspace (documents/workspace-for @state alice)
            stored (object/read-item workspace object-store (:id item) alice)
            text (String. (byte-array (map unchecked-byte (:bytes stored)))
                          java.nio.charset.StandardCharsets/UTF_8)
            envelope (edn/read-string text)]
        ;; Self-describing on the way out, as it was as JSON: a reader
        ;; holding only these bytes can tell which surface it has.
        (is (= :kotoba.protocol/office (:kotoba.protocol/family envelope)))
        (is (= :docs/document (:kotoba.resource/kind envelope)))
        ;; And keywords are keywords. This is the whole point: nothing has to
        ;; put them back, so no reader can put them back wrongly.
        (is (= "設計メモ" (:docs/title (:kotoba.resource/payload envelope))))
        (is (= :document (:docs/type (:kotoba.resource/payload envelope))))
        (is (= [:heading] (mapv :docs/kind
                                (:docs/blocks (:kotoba.resource/payload envelope)))))))))

(deftest a-workbook-keeps-its-cell-addresses-at-rest
  ;; The value JSON could not carry at all: a cell key is a vector, and the
  ;; projection flattened it to the string "[1 1]" which `sheets.wire` then
  ;; had to parse back.
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                                 (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                                           {"[1 1]" {"sheets/value" "Q1"}})
                                 alice object-store)
            workspace (documents/workspace-for @state alice)
            stored (object/read-item workspace object-store (:id item) alice)
            envelope (edn/read-string
                      (String. (byte-array (map unchecked-byte (:bytes stored)))
                               java.nio.charset.StandardCharsets/UTF_8))]
        (is (= {[1 1] {:sheets/value "Q1"}}
               (get-in (:kotoba.resource/payload envelope)
                       [:sheets/tabs "sheet1" :sheets/cells])))))))

(deftest a-document-written-as-json-still-reads
  ;; Migration is what the Drive does as it is used, not something anyone
  ;; runs: an object written before this change is still JSON, and the save
  ;; that rewrites it is what moves it.
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "旧" alice object-store)
            workspace (documents/workspace-for @state alice)
            ref (:drive/object-ref (ws/item workspace (:id item)))
            legacy (json/write-str
                    {"kotoba.protocol/family" "kotoba.protocol/office"
                     "kotoba.protocol/version" 1
                     "kotoba.resource/kind" "docs/document"
                     "kotoba.resource/payload" {"docs/id" "d" "docs/type" "document"
                                                "docs/title" "旧" "docs/blocks" []}})]
        ;; Put the old shape back under the same reference.
        (object/-put-object object-store ref
                            (mapv #(bit-and (int %) 0xff)
                                  (.getBytes ^String legacy
                                             java.nio.charset.StandardCharsets/UTF_8)))
        (let [current (documents/content (:id item) alice object-store)]
          (is (= "旧" (get (:payload current) "docs/title")))
          (is (= :document (:docs/type (:resource current)))
              "rehydrated on read, exactly as it always was")
          ;; And the save that follows writes EDN and corrects what the item
          ;; claims to be.
          (save! (:id item) (:payload current) alice object-store)
          (is (= "application/edn"
                 (:media-type (first (documents/documents @state alice)))))
          (is (= :edn (:format (documents/decode-stored
                                (:bytes (object/read-item
                                         (documents/workspace-for @state alice)
                                         object-store (:id item) alice)))))))))))

(deftest content-reads-back-through-the-surfaces-own-reader
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            {:keys [ok? payload resource-kind]}
            (documents/content (:id item) alice object-store)]
        (is ok?)
        (is (= ":sheets/workbook" resource-kind))
        (is (= "売上" (get payload "sheets/title")))
        ;; The seeded tab, because a workbook with no tabs has nowhere to
        ;; put a cell.
        (is (= ["sheet1"] (vec (keys (get payload "sheets/tabs")))))))))

(deftest quota-counts-utf-8-bytes-not-characters
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
            workspace (documents/workspace-for @state alice)
            stored (object/read-item workspace object-store (:id item) alice)
            text (String. (byte-array (map unchecked-byte (:bytes stored)))
                          java.nio.charset.StandardCharsets/UTF_8)]
        (is (= (:size-bytes item) (count (:bytes stored))))
        (is (= (:size-bytes item) (:drive.workspace/used-bytes workspace)))
        ;; The title is multi-byte, so the JSON is shorter in characters than
        ;; it is in bytes. A quota counted on the string would drift below
        ;; the bytes the store holds, which is the direction that lets a
        ;; workspace exceed it — `drive.object/write-item` says so and this
        ;; is the app holding up its end.
        (is (< (count text) (count (:bytes stored))))))))

(deftest another-principal-cannot-read-it
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        ;; Bob has his own Drive, so this is `:no-such-item` rather than a
        ;; permission answer — the item is not in the workspace he is asking.
        ;; Either way it is refused, and neither leaks the content.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"見つかりません"
                              (documents/content (:id item) bob object-store)))
        (is (empty? (documents/documents (store/snapshot) bob)))))))

(deftest a-shared-workspace-refuses-a-principal-with-no-role
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        ;; Bob, placed in Alice's workspace without a grant. This is the case
        ;; that made per-user workspaces the choice rather than one shared
        ;; one: drive answers, correctly, that he may not have it.
        (swap! state assoc-in [:drive :workspaces bob]
               (get-in @state [:drive :workspaces alice]))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"権限がありません"
                              (documents/content (:id item) bob object-store)))
        (is (= :drive/not-permitted
               (try (documents/content (:id item) bob object-store)
                    (catch clojure.lang.ExceptionInfo error
                      (:type (ex-data error))))))))))

(deftest an-unknown-kind-is-refused-before-anything-is-written
  (with-state
    (fn [state object-store]
      ;; Something the table does not have. `:slides` was the example until
      ;; it became a surface, which is the shape this assertion is guarding:
      ;; the check is the table, not a list written out here.
      (is (= :drive/unknown-kind
             (try (documents/create! :podcast "第1回" alice object-store)
                  (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))
      (is (empty? (documents/documents @state alice))))))

;; ── editing ─────────────────────────────────────────────────────────────────

(deftest saving-an-edit-records-a-new-version-under-a-new-reference
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            edited (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                             {"[1 1]" {"sheets/value" "Q1"}
                              "[1 2]" {"sheets/value" "Q2"}})
            saved (save! (:id item) edited alice object-store)
            workspace (documents/workspace-for @state alice)
            versions (:drive/versions (ws/item workspace (:id item)))]
        (is (:ok? saved))
        (is (= 2 (:versions (:item saved))))
        ;; A new reference, not the old one. Reusing it would replace the
        ;; first version's bytes while the history saying otherwise stayed.
        (is (= 2 (count (distinct (map :drive.version/object-ref versions)))))
        ;; Both versions are still counted against the quota, which is what
        ;; keeping them means.
        (is (= (reduce + (map :drive.version/size-bytes versions))
               (:drive.workspace/used-bytes workspace)))
        (let [back (:payload (documents/content (:id item) alice object-store))]
          (is (= {"[1 1]" {"sheets/value" "Q1"} "[1 2]" {"sheets/value" "Q2"}}
                 (get-in back ["sheets/tabs" "sheet1" "sheets/cells"]))))))))

(deftest an-edit-that-stops-being-a-workbook-is-refused
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; Row 0 is not an address — `sheets.validate` says cells start
            ;; at 1 — and this is the assertion that the app asks it.
            broken (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                             {"[0 1]" {"sheets/value" "x"}})
            error (try (save! (:id item) broken alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/invalid-document (:type error)))
        (is (= [":cell/invalid"] (mapv :code (:problems error))))
        ;; Nothing was written: still one version, and the bytes are the ones
        ;; from before.
        (is (= 1 (count (:drive/versions
                         (ws/item (documents/workspace-for @state alice) (:id item))))))))))

(deftest a-form-whose-field-type-is-not-one-is-refused
  ;; The case that would pass silently without rehydration: on a projected
  ;; payload `forms.validate` sees no fields at all and reports no problems.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            broken (assoc payload "forms/fields"
                          [{"forms/id" "name" "forms/field-type" "telepathy"
                            "forms/label" "name" "forms/required?" false}])
            error (try (save! (:id item) broken alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/invalid-document (:type error)))
        (is (= [":field/unknown-type"] (mapv :code (:problems error))))))))

(deftest a-docs-warning-does-not-block-a-save
  ;; `docs.validate` reports a missing title as a warning. Refusing over it
  ;; would make the surface unusable for a draft.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "下書き" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            untitled (assoc payload "docs/title" "")]
        (is (:ok? (save! (:id item) untitled alice object-store)))))))

(deftest an-edit-cannot-change-what-kind-of-document-it-is
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; A payload trying to smuggle in another discriminant. The
            ;; envelope is rebuilt from the item's recorded kind, so the
            ;; stray key is carried as data and the kind does not move.
            sneaky (assoc payload "kotoba.resource/kind" "sheets/workbook")
            saved (save! (:id item) sneaky alice object-store)]
        (is (= ":docs/document" (:resource-kind (:item saved))))
        (is (= ":docs/document"
               (:resource-kind (documents/content (:id item) alice object-store))))))))

(deftest the-title-moves-in-both-places-at-once
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "旧題" alice object-store)
            renamed (documents/rename! (:id item) "  新題  " alice object-store)
            back (documents/content (:id item) alice object-store)]
        (is (= "新題" (:name (:item renamed))) "trimmed, and on the Drive item")
        (is (= "新題" (get (:payload back) "docs/title")) "and inside the bytes")
        ;; A rename is a new version, because the title is in the resource.
        (is (= 2 (:versions (:item renamed))))))))

(deftest a-blank-rename-is-refused
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)]
        (is (= :drive/invalid-document
               (try (documents/rename! (:id item) "   " alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= "問い合わせ"
               (:name (first (documents/documents (store/snapshot) alice)))))))))

(deftest editing-someone-elses-document-is-refused
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (swap! state assoc-in [:drive :workspaces bob]
               (get-in @state [:drive :workspaces alice]))
        (is (= :drive/not-permitted
               (try (save! (:id item) payload bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= :drive/not-permitted
               (try (documents/rename! (:id item) "改題" bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-trashed-document-cannot-be-edited
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "旧" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (documents/trash! (:id item) alice)
        (is (= :drive/not-found
               (try (save! (:id item) payload alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest trashing-hides-it-and-refuses-its-bytes
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "旧計画" alice object-store)]
        (is (:ok? (documents/trash! (:id item) alice)))
        (is (empty? (documents/documents @state alice)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ゴミ箱"
                              (documents/content (:id item) alice object-store)))
        ;; Reversible: trash is not forget, and the version history is
        ;; still there.
        (let [restored (ws/restore (documents/workspace-for @state alice) (:id item))]
          (is (= 1 (count (:drive/versions (ws/item restored (:id item)))))))))))

;; ── the trash, and the quota it holds ───────────────────────────────────────

(deftest trashing-does-not-give-the-quota-back-and-purging-does
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "旧計画" alice object-store)
            _ (save! (:id item)
                                 (:payload (documents/content (:id item) alice object-store))
                                 alice object-store)
            held (:held-bytes (first (documents/documents @state alice)))
            used-before (:used-bytes (documents/quota-view @state alice))]
        ;; Two versions, and both are counted: `add-version` adds and nothing
        ;; ever subtracts.
        (is (= 2 (:versions (first (documents/documents @state alice)))))
        (is (= held used-before))
        (documents/trash! (:id item) alice)
        (is (= used-before (:used-bytes (documents/quota-view @state alice)))
            "trashing is a flag, not a reclamation")
        (is (= [(:id item)] (mapv :id (documents/trashed @state alice))))
        (let [purged (documents/purge! (:id item) alice object-store)]
          (is (= held (:freed-bytes purged)))
          (is (zero? (:used-bytes (documents/quota-view @state alice))))
          (is (empty? (documents/trashed @state alice)))
          (is (empty? (documents/documents @state alice))))))))

(deftest a-live-document-cannot-be-purged
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (is (= :drive/not-trashed
               (try (documents/purge! (:id item) alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= 1 (count (documents/documents @state alice))))))))

(deftest restoring-brings-it-back-with-its-versions
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)]
        (documents/trash! (:id item) alice)
        (is (empty? (documents/documents @state alice)))
        (let [restored (documents/restore! (:id item) alice)]
          (is (false? (:trashed? (:item restored))))
          (is (= [(:id item)] (mapv :id (documents/documents @state alice))))
          (is (empty? (documents/trashed @state alice)))
          ;; And readable again — trash is what made it unreadable.
          (is (:ok? (documents/content (:id item) alice object-store))))))))

(deftest emptying-the-trash-purges-only-the-trash
  (with-state
    (fn [state object-store]
      (let [kept (:item (documents/create! :docs "残す" alice object-store))
            binned (:item (documents/create! :sheets "捨てる" alice object-store))
            also (:item (documents/create! :forms "これも" alice object-store))]
        (documents/trash! (:id binned) alice)
        (documents/trash! (:id also) alice)
        (let [emptied (documents/empty-trash! alice object-store)]
          (is (= 2 (:purged emptied)))
          (is (pos? (:freed-bytes emptied)))
          (is (= [(:id kept)] (mapv :id (documents/documents @state alice))))
          (is (= (:held-bytes (first (documents/documents @state alice)))
                 (:used-bytes (documents/quota-view @state alice)))))))))

(deftest purging-is-refused-for-someone-elses-document
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (documents/trash! (:id item) alice)
        (swap! state assoc-in [:drive :workspaces bob]
               (get-in @state [:drive :workspaces alice]))
        (is (= :drive/not-permitted
               (try (documents/purge! (:id item) bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= :drive/not-permitted
               (try (documents/restore! (:id item) bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── version history ─────────────────────────────────────────────────────────

(deftest an-earlier-version-is-still-readable
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            first-payload (:payload (documents/content (:id item) alice object-store))
            edited (assoc-in first-payload ["sheets/tabs" "sheet1" "sheets/cells"]
                             {"[1 1]" {"sheets/value" "Q1"}})
            _ (save! (:id item) edited alice object-store)
            v1 (documents/version-content (:id item) 1 alice object-store)
            v2 (documents/version-content (:id item) 2 alice object-store)]
        (is (= first-payload (:payload v1)) "the bytes the first version wrote")
        (is (= edited (:payload v2)))
        (is (= ":sheets/workbook" (:resource-kind v1)))
        ;; Out of range is a 404, not an empty answer that reads as an empty
        ;; document.
        (is (= :drive/not-found
               (try (documents/version-content (:id item) 3 alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest an-earlier-version-of-a-trashed-document-is-refused
  ;; The check `read-item` would have made, made here too — reaching an older
  ;; version means going to the store directly, and the store does not know
  ;; about trash.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (documents/trash! (:id item) alice)
        (is (= :drive/not-found
               (try (documents/version-content (:id item) 1 alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest an-earlier-version-is-refused-to-a-principal-who-may-not-read-it
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (swap! state assoc-in [:drive :workspaces bob]
               (get-in @state [:drive :workspaces alice]))
        (is (= :drive/not-permitted
               (try (documents/version-content (:id item) 1 bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── warnings ────────────────────────────────────────────────────────────────

(deftest a-warning-is-reported-rather-than-swallowed
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "下書き" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            saved (save! (:id item) (assoc payload "docs/title" "")
                                     alice object-store)]
        (is (:ok? saved) "a missing title is a warning, and a draft still saves")
        (is (= [":document/missing-title"] (mapv :code (:warnings saved))))
        (is (:quota saved))))))

(deftest a-clean-save-reports-no-warnings
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (is (empty? (:warnings (save! (:id item) payload alice object-store))))))))

;; ── listing order ───────────────────────────────────────────────────────────

(deftest the-list-moves-when-something-is-saved
  (with-state
    (fn [state object-store]
      (let [first-doc (:item (documents/create! :docs "先" alice object-store))
            second-doc (:item (documents/create! :docs "後" alice object-store))]
        (is (= [(:id second-doc) (:id first-doc)]
               (mapv :id (documents/documents @state alice))))
        ;; Saving the older one moves it to the front. Ordering by creation
        ;; would leave it where it was, which is not where anyone looks for
        ;; the thing they just saved.
        (save! (:id first-doc)
                           (:payload (documents/content (:id first-doc) alice object-store))
                           alice object-store)
        (is (= [(:id first-doc) (:id second-doc)]
               (mapv :id (documents/documents @state alice))))))))

;; ── the shapes the structured editors produce ───────────────────────────────
;;
;; The editors are JavaScript and cannot be run here. What can be pinned is
;; the contract between them and this namespace: the exact payload shape each
;; one writes has to be one `update!` accepts and the surface's validator
;; recognises. A drift in either direction shows up here rather than in a
;; save that silently produces a document nothing can read.

(deftest a-field-added-the-way-the-forms-editor-adds-one-saves
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; Exactly what `formsEditor`'s 質問を追加 pushes.
            added (assoc payload "forms/fields"
                         [{"forms/id" "q1"
                           "forms/label" "新しい質問"
                           "forms/field-type" "text"
                           "forms/required?" false}])
            saved (save! (:id item) added alice object-store)
            back (:payload (documents/content (:id item) alice object-store))]
        (is (:ok? saved))
        (is (empty? (:warnings saved)))
        (is (= [{"forms/id" "q1" "forms/label" "新しい質問"
                 "forms/field-type" "text" "forms/required?" false}]
               (get back "forms/fields")))))))

(deftest every-field-type-the-editor-offers-is-one-the-validator-accepts
  ;; The select is filled from `:vocabulary`, which is `forms.model/field-types`
  ;; itself — this is the assertion that going through the wire and back does
  ;; not break any of them.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "全種類" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            offered (->> (documents/drive-view {:items []} alice)
                         :kinds
                         (some #(when (= "forms" (:kind %)) (:vocabulary %))))
            fields (map-indexed (fn [index type]
                                  {"forms/id" (str "q" index)
                                   "forms/label" type
                                   "forms/field-type" type
                                   "forms/required?" false})
                                offered)]
        (is (= 7 (count offered)))
        (is (:ok? (save! (:id item) (assoc payload "forms/fields" (vec fields))
                                     alice object-store)))))))

(deftest a-block-added-the-way-the-docs-editor-adds-one-saves
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            added (update payload "docs/blocks" conj
                          {"docs/id" "b2" "docs/kind" "paragraph" "docs/text" ""})
            saved (save! (:id item) added alice object-store)]
        (is (:ok? saved))
        (is (= ["heading" "paragraph"]
               (mapv #(get % "docs/kind")
                     (get (:payload (documents/content (:id item) alice object-store))
                          "docs/blocks"))))))))

(deftest every-block-kind-the-editor-offers-is-one-the-validator-accepts
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "全種類" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            offered (->> (documents/drive-view {:items []} alice)
                         :kinds
                         (some #(when (= "docs" (:kind %)) (:vocabulary %))))
            blocks (map-indexed (fn [index kind]
                                  {"docs/id" (str "b" index) "docs/kind" kind})
                                offered)]
        (is (= 9 (count offered)))
        (is (:ok? (save! (:id item) (assoc payload "docs/blocks" (vec blocks))
                                     alice object-store)))))))

(deftest the-cell-key-the-sheets-editor-writes-is-the-one-the-wire-parses
  ;; `cellKey(row, col)` in the page builds "[1 1]" by hand, because it is
  ;; JavaScript and cannot call `sheets.wire/cell-address-string`. This is
  ;; where that duplication is held to account.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; A value, and a formula written with the leading = stripped —
            ;; which is what the cell input does before it stores one.
            edited (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                             {"[1 1]" {"sheets/value" "売上"}
                              "[2 1]" {"sheets/formula" "SUM(B1:B9)"}
                              "[12 340]" {"sheets/value" "遠い"}})
            saved (save! (:id item) edited alice object-store)
            back (:payload (documents/content (:id item) alice object-store))]
        (is (:ok? saved))
        (is (= edited back) "the address survives the round trip unchanged")
        ;; And it is a real address on the other side, not a string that
        ;; happens to look like one.
        (is (= [1 1] (sheets-wire/cell-address "[1 1]")))
        (is (= "[12 340]" (sheets-wire/cell-address-string [12 340])))))))

(deftest a-cell-at-row-zero-is-refused-rather-than-stored
  ;; The grid starts at 1, so this is not reachable by clicking — it is the
  ;; assertion that the validator, not the UI, is what enforces it.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            broken (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                             {"[0 1]" {"sheets/value" "x"}})]
        (is (= :drive/invalid-document
               (try (save! (:id item) broken alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── answering a form ────────────────────────────────────────────────────────

(defn- contact-form
  "A form with a required text field and an email field, as the editor makes
  one."
  [object-store]
  (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
        payload (:payload (documents/content (:id item) alice object-store))]
    (save! (:id item)
                       (assoc payload "forms/fields"
                              [{"forms/id" "name" "forms/label" "お名前"
                                "forms/field-type" "text" "forms/required?" true}
                               {"forms/id" "email" "forms/label" "メール"
                                "forms/field-type" "email" "forms/required?" false}])
                       alice object-store)
    item))

(deftest responses-leave-as-a-table-not-as-a-map-each
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (documents/grant! (:id item) bob "viewer" alice)
        (documents/submit! (:id item) {"name" "田中" "email" "tanaka@example.com"}
                           bob object-store)
        (documents/submit! (:id item) {"name" "鈴木"} bob object-store)
        (let [out (documents/export (:id item) "csv" alice object-store)
              text (String. ^bytes (:bytes out) "UTF-8")
              lines (str/split-lines text)]
          (is (= "問い合わせ.csv" (:filename out)))
          (is (= "text/csv; charset=utf-8" (:media-type out)))
          ;; A column per question, in the form's order — including one the
          ;; second respondent left blank. Derived from the answers' own keys
          ;; that row would be one field wide and its name would land under
          ;; the heading for the address.
          (is (= "送信日時,回答者,お名前,メール" (first lines)))
          (is (= 3 (count lines)))
          (is (str/includes? (nth lines 1) "田中,tanaka@example.com"))
          (is (str/ends-with? (nth lines 2) "鈴木,")))))))

(deftest a-viewer-of-a-form-may-not-have-its-responses
  ;; The one that would be quietest to get wrong. `readable!` answers whether
  ;; this principal may have the *form*, and every other export writes the
  ;; document — so a responses download inheriting the document's permission
  ;; would hand every respondent's answers to anyone the form was shown to.
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (documents/grant! (:id item) bob "viewer" alice)
        (documents/submit! (:id item) {"name" "田中" "email" "t@example.com"}
                           bob object-store)
        ;; bob may read the form, answer it, and export the questions…
        (is (some? (documents/form-for-answering (:id item) bob object-store)))
        (is (some? (documents/export (:id item) "edn" bob object-store)))
        ;; …and not read back what anyone answered.
        (is (= :drive/not-permitted
               (:type (try (documents/export (:id item) "csv" bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        ;; Not even as an editor: editing the questions is not owning the
        ;; answers.
        (documents/grant! (:id item) bob "editor" alice)
        (is (= :drive/not-permitted
               (:type (try (documents/export (:id item) "csv" bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (some? (documents/export (:id item) "csv" alice object-store)))))))

(deftest responses-become-a-workbook-in-the-drive
  (with-state
    (fn [state object-store]
      (let [item (contact-form object-store)]
        (documents/submit! (:id item) {"name" "田中" "email" "t@example.com"}
                           alice object-store)
        (let [made (documents/responses-sheet! (:id item) alice object-store)
              sheet-id (:id (:item made))
              wb (:resource (documents/content sheet-id alice object-store))
              tab (get-in wb [:sheets/tabs "回答"])]
          (is (= ":sheets/workbook" (:resource-kind (:item made))))
          (is (str/starts-with? (:name (:item made)) "問い合わせ の回答 "))
          (is (= {:sheets/value "お名前"} (get-in tab [:sheets/cells [1 3]])))
          (is (= {:sheets/value "田中"} (get-in tab [:sheets/cells [2 3]])))
          ;; A new document, beside the form — two documents now, not one
          ;; replaced.
          (is (= 2 (count (documents/documents @state alice)))))
        ;; And a second snapshot is a second document, because two days'
        ;; answers are two things somebody may want and overwriting would
        ;; destroy one the owner never asked to lose.
        (documents/responses-sheet! (:id item) alice object-store)
        (is (= 3 (count (documents/documents @state alice))))))))

(deftest only-the-owner-may-snapshot-the-responses
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (documents/submit! (:id item) {"name" "田中"} bob object-store)
        (is (= :drive/not-permitted
               (:type (try (documents/responses-sheet! (:id item) bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest the-pane-is-told-which-exports-refuse
  ;; The role is per item and the format table is per kind, so the pane
  ;; filters — this is what it filters on. Without it there is a button that
  ;; 403s for every viewer of a form.
  (with-state
    (fn [_ _]
      (let [kinds (:kinds (documents/drive-view {:items []} alice))
            by-kind (into {} (map (juxt :kind identity)) kinds)]
        (is (= ["csv"] (:owner-only-exports (get by-kind "forms"))))
        (is (= ["csv" "edn"] (:exports (get by-kind "forms"))))
        ;; A workbook's own csv is the document, so nothing is owner-only.
        (is (= [] (:owner-only-exports (get by-kind "sheets"))))
        (is (= [] (:owner-only-exports (get by-kind "docs"))))))))

(deftest a-form-nobody-answered-exports-its-header
  ;; An export that produced nothing would be indistinguishable from a failed
  ;; one.
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)
            text (String. ^bytes (:bytes (documents/export (:id item) "csv" alice
                                                           object-store))
                          "UTF-8")]
        (is (= ["送信日時,回答者,お名前,メール"] (str/split-lines text)))))))

(deftest an-answer-containing-a-comma-stays-one-answer
  ;; Through sheets.csv rather than a second escaping routine written here.
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (documents/submit! (:id item) {"name" "田中, 鈴木" "email" "a@example.com"}
                           alice object-store)
        (let [text (String. ^bytes (:bytes (documents/export (:id item) "csv" alice
                                                             object-store))
                            "UTF-8")]
          (is (str/includes? text "\"田中, 鈴木\"")))))))

(deftest a-form-can-be-answered-by-anyone-who-may-read-it
  (with-state
    (fn [state object-store]
      (let [item (contact-form object-store)]
        (documents/grant! (:id item) bob "viewer" alice)
        ;; A viewer, deliberately: requiring write access to submit would
        ;; make every respondent an editor of the questions.
        (let [{:keys [fields title]} (documents/form-for-answering (:id item) bob object-store)]
          (is (= "問い合わせ" title))
          (is (= [{:id "name" :label "お名前" :field-type "text" :required? true}
                  {:id "email" :label "メール" :field-type "email" :required? false}]
                 fields)))
        (let [sent (documents/submit! (:id item) {"name" "Bob" "email" "bob@example.com"}
                                      bob object-store)]
          (is (:ok? sent))
          (is (= bob (:author (:submission sent)))))
        (let [{:keys [submissions]} (documents/submissions (:id item) alice)]
          (is (= 1 (count submissions)))
          (is (= {"name" "Bob" "email" "bob@example.com"} (:answers (first submissions))))
          (is (= bob (:author (first submissions)))))
        ;; Answering did not change the questions: no new version, nothing
        ;; charged to the quota beyond the form itself.
        (is (= 2 (:versions (first (documents/documents @state alice)))))))))

(deftest a-missing-required-answer-is-refused
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)
            error (try (documents/submit! (:id item) {"email" "a@example.com"}
                                          alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/invalid-submission (:type error)))
        (is (= [{:code ":submission/missing-required" :field "name"
                 :message "required answer is missing"}]
               (:problems error)))
        (is (empty? (:submissions (documents/submissions (:id item) alice))))))))

(deftest an-answer-that-is-not-an-email-is-refused
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)
            error (try (documents/submit! (:id item) {"name" "Bob" "email" "not-an-address"}
                                          alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/invalid-submission (:type error)))
        (is (= [":submission/invalid-email"] (mapv :code (:problems error))))))))

(deftest validating-a-submission-needs-the-rehydrated-form
  ;; The same failure that made rehydration mandatory for saving. Against a
  ;; projected form, `missing-required` reads `:forms/fields`, finds nil, and
  ;; reports that nothing is required — so an empty submission would pass.
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)
            projected (:payload (documents/content (:id item) alice object-store))]
        (is (empty? (forms-validate/submission-problems
                     projected (forms-model/submission (:id item) {}))))
        (is (= [:submission/missing-required]
               (mapv :forms/code
                     (forms-validate/submission-problems
                      (forms-wire/rehydrate-form projected)
                      (forms-model/submission (:id item) {})))))))))

(deftest only-a-form-can-be-answered
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (is (= :drive/unknown-kind
               (try (documents/submit! (:id item) {} alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest responses-belong-to-the-owner
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (documents/submit! (:id item) {"name" "Bob"} bob object-store)
        ;; An editor may change the questions and still not read the answers.
        (is (= :drive/not-permitted
               (try (documents/submissions (:id item) bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= 1 (count (:submissions (documents/submissions (:id item) alice)))))))))

(deftest a-form-can-be-answered-through-a-share-link
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)
            {:keys [token]} (documents/create-link! (:id item) "viewer" nil alice now-ms)
            sent (documents/submit-via-link! token {"name" "Carol"} "user-carol"
                                             now-ms object-store)]
        (is (:ok? sent))
        (is (= "user-carol" (:author (:submission sent))))
        (is (= [{"name" "Carol"}]
               (mapv :answers (:submissions (documents/submissions (:id item) alice)))))
        ;; And the link still cannot be used past its terms.
        (documents/revoke-link! (:id item) token alice)
        (is (= :drive/not-found
               (try (documents/submit-via-link! token {"name" "Carol"} "user-carol"
                                                now-ms object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-stranger-cannot-answer-a-form-they-cannot-read
  (with-state
    (fn [_ object-store]
      (let [item (contact-form object-store)]
        (is (= :drive/not-found
               (try (documents/submit! (:id item) {"name" "Mallory"} bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── slides ──────────────────────────────────────────────────────────────────

(deftest a-deck-is-a-fourth-surface-like-the-others
  (with-state
    (fn [state object-store]
      (let [{:keys [ok? item]} (documents/create! :slides "四半期報告" alice object-store)]
        (is ok?)
        (is (= ":slides/deck" (:resource-kind item)))
        (is (= "application/edn" (:media-type item)))
        (let [{:keys [payload resource-kind]} (documents/content (:id item) alice object-store)]
          (is (= ":slides/deck" resource-kind))
          (is (= "四半期報告" (get payload "slides/title")))
          ;; Seeded with one slide carrying a title text box, because a deck
          ;; with no slides has nowhere to put a shape.
          (is (= ["slide1"] (mapv #(get % "slides/id") (get payload "slides/slides"))))
          (is (= ["text"] (mapv #(get % "slides/shape")
                                (get-in payload ["slides/slides" 0 "slides/shapes"])))))
        ;; And it rides the same everything: trash, sharing, comments,
        ;; versions all take it without knowing what a deck is.
        (documents/grant! (:id item) bob "editor" alice)
        (is (:ok? (documents/comment! (:id item) "表紙を直す" "slide1" bob)))
        (is (= 1 (count (documents/documents @state bob))))))))

(deftest a-slide-added-the-way-the-editor-adds-one-saves
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :slides "四半期報告" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; Exactly what `slidesEditor`'s スライドを追加 and テキストを追加 push.
            added (update payload "slides/slides" conj
                          {"slides/id" "slide2" "slides/title" "スライド 2"
                           "slides/shapes"
                           [{"slides/id" "t1" "slides/shape" "text" "slides/text" "売上"
                             "slides/x" 0.8 "slides/y" 0.8 "slides/w" 8.4 "slides/h" 1.0
                             "slides/font-size" 28}]})
            saved (save! (:id item) added alice object-store)
            back (:payload (documents/content (:id item) alice object-store))]
        (is (:ok? saved))
        (is (empty? (:warnings saved)))
        (is (= ["slide1" "slide2"] (mapv #(get % "slides/id") (get back "slides/slides"))))
        (is (= "売上" (get-in back ["slides/slides" 1 "slides/shapes" 0 "slides/text"])))))))

(deftest a-deck-whose-slides-are-not-a-list-is-refused
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :slides "四半期報告" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            error (try (save! (:id item) (assoc payload "slides/slides" "nope")
                                          alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        ;; A 422 with the surface's own code, not a 500 out of the converter
        ;; — which is what this was before the rehydrators learned to hand
        ;; malformed input on rather than throw at it.
        (is (= :drive/invalid-document (:type error)))
        (is (= [":deck/slides-not-sequential"] (mapv :code (:problems error))))))))

(deftest the-deck-validator-is-not-asked-about-the-slides-website
  ;; `slides.validate/problems` also runs `route-problems`, which reports an
  ;; error for each of four Pages hosts it cannot find. That is a question
  ;; about the slides site and not about this document; asking it here would
  ;; refuse every save.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :slides "四半期報告" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (is (:ok? (save! (:id item) payload alice object-store)))))))

;; ── sharing ─────────────────────────────────────────────────────────────────

(deftest a-grant-makes-the-document-appear-in-the-grantees-list
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)]
        (is (empty? (documents/documents @state bob))
            "before the grant, bob's Drive knows nothing about it")
        (documents/grant! (:id item) bob "editor" alice)
        ;; The permission is written on the item, in alice's workspace. bob
        ;; looking only at his own would still see nothing — this is the
        ;; assertion that `locate` closed that gap.
        (let [[shared] (documents/documents @state bob)]
          (is (= (:id item) (:id shared)))
          (is (= "editor" (:role shared)))
          (is (false? (:own? shared)))
          (is (= alice (:owner shared)))
          (is (:writable? shared))
          (is (= "共有アイテム" (:folder shared))))
        ;; And it is still alice's, in her own Drive.
        (is (= [true] (mapv :own? (documents/documents @state alice))))))))

(deftest an-editor-writes-into-the-owners-drive-not-a-copy
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            payload (:payload (documents/content (:id item) bob object-store))
            saved (save! (:id item) (assoc payload "docs/title" "bob の編集")
                                     bob object-store)]
        (is (:ok? saved))
        ;; One document, two versions, in alice's workspace — not a second
        ;; document in bob's.
        (is (= 1 (count (documents/documents @state alice))))
        (is (= 2 (:versions (first (documents/documents @state alice)))))
        (is (empty? (get-in @state [:drive :workspaces bob :drive.workspace/items])))
        (is (= "bob の編集" (:name (first (documents/documents @state alice)))))
        ;; Charged to the owner's quota, because the bytes are the owner's.
        (is (pos? (:used-bytes (documents/quota-view @state alice))))
        (is (zero? (:used-bytes (documents/quota-view @state bob))))))))

(deftest the-history-says-which-writer-made-each-version
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            payload (:payload (documents/content (:id item) bob object-store))
            saved (save! (:id item) (assoc payload "docs/title" "bob の編集")
                                     bob object-store)]
        ;; The gap sharing created: before this, two principals could write
        ;; one document and the history could not say which of them did.
        (is (= [alice bob] (mapv :author (:history (:item saved)))))
        (is (= bob (:updated-by (:item saved))))
        (is (= [alice bob] (mapv :author (:history (first (documents/documents @state alice))))))
        (is (= alice (:author (documents/version-content (:id item) 1 alice object-store))))
        (is (= bob (:author (documents/version-content (:id item) 2 alice object-store))))))))

(deftest a-viewer-may-read-and-not-write
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            _ (documents/grant! (:id item) bob "viewer" alice)
            [shared] (documents/documents @state bob)
            payload (:payload (documents/content (:id item) bob object-store))]
        (is (= "viewer" (:role shared)))
        (is (false? (:writable? shared)))
        (is (some? payload) "reading is what a viewer is for")
        (is (= :drive/not-permitted
               (try (save! (:id item) payload bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest only-the-owner-may-trash-purge-or-re-share
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            type-of (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e
                                           (:type (ex-data e)))))]
        ;; An editor may change it — that is what editing means — but not make
        ;; it disappear from the owner's Drive, and not widen the access the
        ;; owner granted narrowly.
        (is (= :drive/not-permitted (type-of #(documents/trash! (:id item) bob))))
        (is (= :drive/not-permitted (type-of #(documents/restore! (:id item) bob))))
        (is (= :drive/not-permitted (type-of #(documents/purge! (:id item) bob object-store))))
        (is (= :drive/not-permitted (type-of #(documents/sharing (:id item) bob))))
        (is (= :drive/not-permitted
               (type-of #(documents/grant! (:id item) "user-carol" "editor" bob))))))))

(deftest ownership-is-not-grantable
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        ;; `drive.workspace/grant` would accept :owner. Two owners either of
        ;; whom can purge it is a transfer dressed as a share.
        (is (= :drive/invalid-share
               (try (documents/grant! (:id item) bob "owner" alice)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= :drive/invalid-share
               (try (documents/grant! (:id item) alice "editor" alice)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= ["editor" "commenter" "viewer"] (:roles (documents/sharing (:id item) alice))))))))

(deftest revoking-takes-the-document-away-again
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (is (= 1 (count (documents/documents @state bob))))
        (let [after (documents/revoke-grant! (:id item) bob alice)]
          (is (empty? (:grants after)))
          (is (empty? (documents/documents @state bob)))
          ;; Refused as not-found rather than not-permitted: without a role,
          ;; bob cannot locate it at all, and "it is not there" is the honest
          ;; answer as well as the one that leaks least.
          (is (= :drive/not-found
                 (try (documents/content (:id item) bob object-store)
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))))

;; ── going back to an earlier version ────────────────────────────────────────

(defn- with-paragraph [item text actor object-store]
  (let [payload (:payload (documents/content (:id item) actor object-store))]
    (documents/update! (:id item)
                       (update payload "docs/blocks" conj
                               {"docs/id" text "docs/kind" "paragraph" "docs/text" text})
                       actor
                       (:etag (:item (documents/content (:id item) actor object-store)))
                       object-store)))

(deftest restoring-is-a-new-version-and-not-a-rewrite
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (with-paragraph item "ひとつめ" alice object-store)
        (with-paragraph item "ふたつめ" alice object-store)
        (let [etag (:etag (:item (documents/content (:id item) alice object-store)))
              out (documents/restore-version! (:id item) 2 alice etag object-store)]
          (is (= 2 (:restored-from out)))
          ;; Four versions, not two: the history is append-only and a restore
          ;; is a new version whose contents happen to equal an old one.
          (is (= 4 (:versions (:item out))))
          (is (= ["title" "ひとつめ"]
                 (mapv #(get % "docs/id")
                       (get (:payload (documents/content (:id item) alice object-store))
                            "docs/blocks")))))
        ;; And the earlier versions are still readable, saying what they said.
        (is (= ["title" "ひとつめ" "ふたつめ"]
               (mapv #(get % "docs/id")
                     (get (:payload (documents/version-content (:id item) 3 alice
                                                               object-store))
                          "docs/blocks"))))))))

(deftest a-restore-is-authored-by-whoever-restored-it
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (with-paragraph item "alice の段落" alice object-store)
        (let [etag (:etag (:item (documents/content (:id item) bob object-store)))]
          (documents/restore-version! (:id item) 1 bob etag object-store))
        ;; They made this version. The earlier one is still there saying who
        ;; made that.
        (is (= [alice alice bob]
               (mapv :author (:history (first (documents/documents @state alice))))))))))

(deftest restoring-the-current-version-is-refused
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            etag (:etag (:item (documents/content (:id item) alice object-store)))]
        (is (= :drive/already-current
               (try (documents/restore-version! (:id item) 1 alice etag object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-restore-carries-an-etag-like-any-other-save
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (with-paragraph item "ひとつめ" alice object-store)
        (let [stale (:etag (:item (documents/content (:id item) bob object-store)))]
          ;; Alice moves it on while bob was looking at the history.
          (with-paragraph item "ふたつめ" alice object-store)
          (is (= :drive/stale-version
                 (try (documents/restore-version! (:id item) 1 bob stale object-store)
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))))

(deftest a-viewer-cannot-restore
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (with-paragraph item "ひとつめ" alice object-store)
        (documents/grant! (:id item) bob "viewer" alice)
        (let [etag (:etag (:item (documents/content (:id item) bob object-store)))]
          (is (= :drive/not-permitted
                 (try (documents/restore-version! (:id item) 1 bob etag object-store)
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))))

(deftest the-history-is-addressable-and-says-what-each-version-cost
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (with-paragraph item "ひとつめ" alice object-store)
        (let [{:keys [current versions]} (documents/history (:id item) alice)]
          (is (= 2 current))
          ;; Newest first, each entry knowing its own index — which is what a
          ;; restore takes.
          (is (= [2 1] (mapv :index versions)))
          (is (= [true false] (mapv :current? versions)))
          (is (= [alice alice] (mapv :author versions)))
          ;; The first version's delta is its whole size; the second's is
          ;; what the paragraph added.
          (is (pos? (:delta-bytes (second versions))))
          (is (pos? (:delta-bytes (first versions))))
          (is (= (:size-bytes (second versions)) (:delta-bytes (second versions)))))))))

(deftest a-stranger-sees-no-history
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (is (= :drive/not-found
               (try (documents/history (:id item) bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── forgetting part of a history ────────────────────────────────────────────

(deftest pruning-keeps-the-newest-and-gives-the-quota-back
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (dotimes [n 4] (with-paragraph item (str "段落" n) alice object-store))
        (let [before (:used-bytes (documents/quota-view @state alice))
              held (:held-bytes (first (documents/documents @state alice)))
              out (documents/prune! (:id item) alice 2 object-store)]
          (is (= 5 (+ (:deleted out) (:kept out))))
          (is (= 2 (:kept out)))
          (is (= 2 (:versions (:item out))))
          (is (pos? (:freed-bytes out)))
          (is (= (- before (:freed-bytes out)) (:used-bytes (:quota out))))
          (is (= held before) "what the document held is what the Drive was counting")
          ;; And it still reads.
          (is (:ok? (documents/content (:id item) alice object-store))))))))

(deftest pruning-cannot-take-the-current-version
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (with-paragraph item "ひとつめ" alice object-store)
        (is (= :drive/invalid-document
               (try (documents/prune! (:id item) alice 0 object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= 2 (:versions (first (documents/documents (store/snapshot) alice)))))))))

(deftest a-prune-renumbers-what-is-left
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (with-paragraph item "ひとつめ" alice object-store)
        (with-paragraph item "ふたつめ" alice object-store)
        (is (= ["title"] (mapv #(get % "docs/id")
                               (get (:payload (documents/version-content
                                               (:id item) 1 alice object-store))
                                    "docs/blocks")))
            "version 1 is the first save")
        (documents/prune! (:id item) alice 1 object-store)
        ;; An index is a position in `:drive/versions`, not an identity, so
        ;; pruning renumbers: what was version 3 is now version 1. The two
        ;; earlier ones are gone and do not come back — irreversible, which
        ;; is why this is owner-only and never automatic.
        (is (= 1 (:current (documents/history (:id item) alice))))
        (is (= ["title" "ひとつめ" "ふたつめ"]
               (mapv #(get % "docs/id")
                     (get (:payload (documents/version-content (:id item) 1 alice
                                                               object-store))
                          "docs/blocks")))
            "and version 1 is now the newest content, not the oldest")
        (is (= :drive/not-found
               (try (documents/version-content (:id item) 2 alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest only-the-owner-may-prune
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (with-paragraph item "ひとつめ" bob object-store)
        ;; An editor may change a document and still not destroy the record
        ;; of how it got that way.
        (is (= :drive/not-permitted
               (try (documents/prune! (:id item) bob 1 object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest pruning-with-nothing-to-prune-is-not-an-error
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            out (documents/prune! (:id item) alice 10 object-store)]
        (is (:ok? out))
        (is (zero? (:deleted out)))
        (is (zero? (:freed-bytes out)))))))

(deftest nothing-prunes-on-its-own
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (dotimes [n 15] (with-paragraph item (str "段落" n) alice object-store))
        ;; Well past the default. A Drive that quietly deleted history at a
        ;; moment nobody chose, to solve a problem nobody had noticed, would
        ;; be a worse thing than one that fills up and says so.
        (is (= 16 (:versions (first (documents/documents @state alice)))))))))

;; ── one page at a time ──────────────────────────────────────────────────────

(defn- names-of [page] (mapv :name (:items page)))

(deftest a-page-stops-and-says-where
  (with-state
    (fn [state object-store]
      (doseq [n (range 5)]
        (documents/create! :docs (str "文書" n) alice object-store))
      (let [first-page (documents/page @state alice {:limit 2})
            second-page (documents/page @state alice {:limit 2
                                                      :cursor (:next-cursor first-page)})
            third-page (documents/page @state alice {:limit 2
                                                     :cursor (:next-cursor second-page)})]
        (is (= 2 (count (:items first-page))))
        (is (some? (:next-cursor first-page)))
        (is (= 2 (count (:items second-page))))
        (is (= 1 (count (:items third-page))))
        ;; Nil at the end rather than a cursor that would return nothing, so
        ;; a caller stops by being told to.
        (is (nil? (:next-cursor third-page)))
        ;; Five documents, each once, newest first.
        (is (= ["文書4" "文書3" "文書2" "文書1" "文書0"]
               (concat (names-of first-page) (names-of second-page) (names-of third-page))))))))

(deftest a-cursor-survives-the-list-moving-under-it
  (with-state
    (fn [state object-store]
      (let [items (mapv #(:item (documents/create! :docs (str "文書" %) alice object-store))
                        (range 5))
            first-page (documents/page @state alice {:limit 2})]
        ;; The list is ordered by last write, so saving the oldest moves it
        ;; to the front. An offset would now show one document twice and
        ;; skip another; a cursor says "after this position".
        (save! (:id (first items))
               (:payload (documents/content (:id (first items)) alice object-store))
               alice object-store)
        (let [second-page (documents/page @state alice {:limit 2
                                                        :cursor (:next-cursor first-page)})
              seen (concat (names-of first-page) (names-of second-page))]
          (is (= 4 (count seen)))
          (is (= (count (distinct seen)) (count seen)) "nothing seen twice")
          ;; 文書0 jumped to the front, which the first page had already
          ;; passed — so it is not in this page, and it was not silently
          ;; dropped from the middle either.
          (is (not (contains? (set seen) "文書0"))))))))

(deftest a-listing-with-no-limit-is-still-everything
  (with-state
    (fn [state object-store]
      (dotimes [n 3] (documents/create! :docs (str "文書" n) alice object-store))
      (is (= 3 (count (documents/documents @state alice))))
      (is (= 2 (count (documents/documents @state alice {:limit 2})))))))

(deftest the-drive-view-pages-the-created-half-only
  (with-state
    (fn [_ object-store]
      (dotimes [n 4] (documents/create! :docs (str "文書" n) alice object-store))
      (let [archive {:source "m365-archive / onedrive" :count 2
                     :items [{:id "a.txt" :name "a.txt"} {:id "b.txt" :name "b.txt"}]}
            view (documents/drive-view archive alice {:limit 2})]
        ;; Two created plus the whole archive, which `drive-snapshot` has
        ;; already capped — a second cursor for a list that does not grow
        ;; would be ceremony.
        (is (= 4 (count (:items view))))
        (is (= 2 (:documents view)))
        (is (some? (:next-cursor view)))
        (is (str/includes? (:source view) "以降あり"))
        (is (nil? (:next-cursor (documents/drive-view archive alice {:limit 50}))))))))

;; ── searching inside documents ──────────────────────────────────────────────

(defn- with-cell [item value object-store]
  (let [payload (:payload (documents/content (:id item) alice object-store))]
    (save! (:id item)
           (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                     {"[1 1]" {"sheets/value" value}})
           alice object-store)))

(deftest a-cell-is-findable
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))]
        (with-cell book "四半期の粗利" object-store)
        (let [{:keys [count results]} (documents/search "粗利" alice object-store)]
          (is (= 1 count))
          (is (= {:id (:id book) :name "売上" :where "content" :snippet "四半期の粗利"}
                 (select-keys (first results) [:id :name :where :snippet]))))))))

(deftest every-surface-is-searchable-by-its-own-text
  (with-state
    (fn [_ object-store]
      ;; What counts as text is the model's business, so each surface has its
      ;; own extractor and each one is exercised.
      (let [book (:item (documents/create! :sheets "帳簿" alice object-store))
            doc (:item (documents/create! :docs "議事録" alice object-store))
            form (:item (documents/create! :forms "問い合わせ" alice object-store))
            deck (:item (documents/create! :slides "報告" alice object-store))]
        (with-cell book "みつばち" object-store)
        (let [p (:payload (documents/content (:id doc) alice object-store))]
          (save! (:id doc) (update p "docs/blocks" conj
                                   {"docs/id" "b" "docs/kind" "paragraph"
                                    "docs/text" "みつばちの話"})
                 alice object-store))
        (let [p (:payload (documents/content (:id form) alice object-store))]
          (save! (:id form) (assoc p "forms/fields"
                                   [{"forms/id" "q" "forms/label" "みつばちは好きですか"
                                     "forms/field-type" "text" "forms/required?" false}])
                 alice object-store))
        (let [p (:payload (documents/content (:id deck) alice object-store))]
          (save! (:id deck)
                 (assoc-in p ["slides/slides" 0 "slides/shapes" 0 "slides/text"] "みつばち")
                 alice object-store))
        (is (= #{"帳簿" "議事録" "問い合わせ" "報告"}
               (set (map :name (:results (documents/search "みつばち" alice object-store))))))))))

(deftest a-title-match-wins-over-a-content-match
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "みつばち" alice object-store))]
        (with-cell book "みつばち" object-store)
        (let [results (:results (documents/search "みつばち" alice object-store))]
          ;; Once, not twice: the same document matching both ways is one
          ;; document, and the stronger signal is the one to show.
          (is (= 1 (clojure.core/count results)))
          (is (= "title" (:where (first results)))))))))

(deftest a-snippet-quotes-the-document-and-not-the-query
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "帳簿" alice object-store))]
        (with-cell book "Quarterly REVENUE for the year" object-store)
        (let [hit (first (:results (documents/search "revenue" alice object-store)))]
          ;; Case-folded for finding, not for showing.
          (is (str/includes? (:snippet hit) "REVENUE")))))))

(deftest a-long-cell-is-cut-around-the-match
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "帳簿" alice object-store))
            filler (apply str (repeat 200 "あ"))]
        (with-cell book (str filler "みつばち" filler) object-store)
        (let [snippet (:snippet (first (:results (documents/search "みつばち" alice
                                                                   object-store))))]
          (is (str/starts-with? snippet "…"))
          (is (str/ends-with? snippet "…"))
          (is (str/includes? snippet "みつばち"))
          (is (< (clojure.core/count snippet) 100)))))))

(deftest search-only-reaches-what-the-asker-may-read
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "私信" alice object-store))]
        (with-cell book "みつばち" object-store)
        (is (zero? (:count (documents/search "みつばち" bob object-store))))
        (documents/grant! (:id book) bob "viewer" alice)
        (is (= 1 (:count (documents/search "みつばち" bob object-store))))))))

(deftest a-trashed-document-is-not-found
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "旧" alice object-store))]
        (with-cell book "みつばち" object-store)
        (documents/trash! (:id book) alice)
        (is (zero? (:count (documents/search "みつばち" alice object-store))))))))

(deftest an-empty-query-finds-nothing-rather-than-everything
  (with-state
    (fn [_ object-store]
      (documents/create! :docs "設計" alice object-store)
      (doseq [q ["" "   " nil]]
        (is (zero? (:count (documents/search q alice object-store)))
            (str "query " (pr-str q)))))))

;; ── import and export ───────────────────────────────────────────────────────

(defn- as-text [bytes] (String. ^bytes bytes java.nio.charset.StandardCharsets/UTF_8))

(deftest a-workbook-exports-as-csv
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                               {"[1 1]" {"sheets/value" "Quarter"}
                                "[1 2]" {"sheets/value" "Revenue"}
                                "[2 1]" {"sheets/value" "Q1"}
                                "[2 2]" {"sheets/value" "1200"}})
                     alice object-store)
            out (documents/export (:id item) "csv" alice object-store)]
        (is (= "text/csv; charset=utf-8" (:media-type out)))
        (is (= "売上.csv" (:filename out)))
        (is (= "Quarter,Revenue\r\nQ1,1200" (as-text (:bytes out))))))))

(deftest a-csv-imports-as-a-workbook
  (with-state
    (fn [state object-store]
      (let [csv "Quarter,Revenue\r\nQ1,1200\r\nQ2,\"1,300\""
            {:keys [item]} (documents/import! "csv" "取り込み売上"
                                              (.getBytes csv "UTF-8") alice object-store)
            back (:resource (documents/content (:id item) alice object-store))]
        (is (= ":sheets/workbook" (:resource-kind item)))
        (is (= "取り込み売上" (:name item)))
        ;; One version, which is the file. It used to be two: create!
        ;; seeded an empty workbook and the import wrote over it, so every
        ;; imported document had a first version that was an empty one
        ;; nobody ever had — offered by the history pane and restorable.
        (is (= 1 (:versions item)))
        (is (= {:sheets/value "1,300"}
               (get-in back [:sheets/tabs "imported" :sheets/cells [3 2]])))
        (is (= 1 (count (documents/documents @state alice))))))))

(deftest a-csv-round-trips-through-the-drive
  (with-state
    (fn [_ object-store]
      (let [csv "a,b\r\n\"c,d\",\"say \"\"hi\"\"\""
            {:keys [item]} (documents/import! "csv" "往復"
                                              (.getBytes csv "UTF-8") alice object-store)
            out (documents/export (:id item) "csv" alice object-store {:tab "imported"})]
        (is (= csv (as-text (:bytes out))))))))

(deftest every-surface-exports-as-edn
  (with-state
    (fn [_ object-store]
      (doseq [kind [:sheets :docs :forms :slides]]
        (let [{:keys [item]} (documents/create! kind "資料" alice object-store)
              out (documents/export (:id item) "edn" alice object-store)
              envelope (edn/read-string (as-text (:bytes out)))]
          (is (= "application/edn" (:media-type out)))
          (is (= "資料.edn" (:filename out)))
          ;; Free, because the stored bytes already are this.
          (is (= :kotoba.protocol/office (:kotoba.protocol/family envelope)))
          (is (= (keyword (subs (:resource-kind item) 1))
                 (:kotoba.resource/kind envelope))))))))

(deftest an-edn-export-imports-back
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            out (documents/export (:id item) "edn" alice object-store)
            copy (documents/import! "edn" "設計の複製" (:bytes out) alice object-store)]
        (is (= ":docs/document" (:resource-kind (:item copy))))
        (is (= "設計の複製" (:name (:item copy))))
        (is (= 2 (count (documents/documents @state alice))) "a copy, not a replacement")))))

(deftest a-surface-is-not-offered-a-format-it-has-no-writer-for
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            error (try (documents/export (:id item) "csv" alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/unsupported-format (:type error)))
        ;; What a document *can* be, named in the refusal — a surface with
        ;; no writer for csv still has two of its own.
        (is (= ["docx" "edn" "md"] (:available error)))))))

(deftest an-unknown-import-format-is-refused
  (with-state
    (fn [_ object-store]
      ;; "xlsx" was the example until it became a format. The check is the
      ;; table, not a list written out here.
      (is (= :drive/unsupported-format
             (try (documents/import! "numbers" "売上" (.getBytes "x" "UTF-8")
                                     alice object-store)
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

(deftest bytes-that-are-not-the-file-they-claim-to-be-are-refused
  (with-state
    (fn [state object-store]
      ;; A zip with no worksheet parts is still a zip, so `sheets.xlsx`
      ;; answers an empty workbook rather than nil — and an import that let
      ;; that through would look exactly like a working import of an empty
      ;; file, which is the one failure a reader cannot tell from success.
      (doseq [[format bytes] [["xlsx" (.getBytes "x" "UTF-8")]
                              ["pptx" (.getBytes "x" "UTF-8")]]]
        (is (= :drive/unsupported-format
               (try (documents/import! format "壊れ" bytes alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            format))
      ;; And nothing was left behind at all. This used to allow up to two,
      ;; because `create!` ran before the refusal and left a seeded document
      ;; behind; the contents now arrive with the creation, so a refusal
      ;; happens before there is anything to leave.
      (is (zero? (count (documents/documents @state alice)))))))

(deftest an-imported-file-goes-through-the-validator-like-anything-else
  (with-state
    (fn [_ object-store]
      ;; An EDN envelope carrying a deck whose slides are not a list. The
      ;; import path is create! plus a save, so the save refuses it.
      (let [broken (pr-str {:kotoba.protocol/family :kotoba.protocol/office
                            :kotoba.protocol/version 1
                            :kotoba.resource/kind :slides/deck
                            :kotoba.resource/payload {:slides/id "d"
                                                      :slides/kind :slides/deck
                                                      :slides/title "壊れ"
                                                      :slides/slides "nope"}})]
        (is (= :drive/invalid-document
               (try (documents/import! "edn" "壊れ" (.getBytes broken "UTF-8")
                                       alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-title-does-not-become-a-path
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "../../etc/passwd" alice object-store)
            out (documents/export (:id item) "edn" alice object-store)
            filename (:filename out)]
        (is (= "__.._etc_passwd.edn" filename))
        ;; What actually has to hold, rather than one exact string: no
        ;; separator survives and it does not begin with a dot.
        (is (not (re-find #"[/\\]" filename)))
        (is (not (str/starts-with? filename ".")))))))

(deftest exporting-obeys-the-same-permission-answer
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (is (= :drive/not-found
               (try (documents/export (:id item) "edn" bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (documents/grant! (:id item) bob "viewer" alice)
        (is (:bytes (documents/export (:id item) "edn" bob object-store)))))))

(deftest a-workbook-exports-as-xlsx
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc-in payload ["sheets/tabs" "sheet1" "sheets/cells"]
                               {"[1 1]" {"sheets/value" "四半期"}
                                "[2 2]" {"sheets/formula" "SUM(B1:B1)"}})
                     alice object-store)
            out (documents/export (:id item) "xlsx" alice object-store)]
        (is (= "売上.xlsx" (:filename out)))
        (is (str/includes? (:media-type out) "spreadsheetml.sheet"))
        (is (= [0x50 0x4b] (mapv #(bit-and (int %) 0xff) (take 2 (:bytes out))))
            "PK, so it is a zip")
        (with-open [zip (java.util.zip.ZipInputStream.
                         (java.io.ByteArrayInputStream. (:bytes out)))]
          (let [entries (loop [acc []]
                          (if-let [e (.getNextEntry zip)] (recur (conj acc (.getName e))) acc))]
            (is (contains? (set entries) "[Content_Types].xml"))
            (is (contains? (set entries) "xl/worksheets/sheet1.xml"))))))))

(deftest a-document-leaves-as-markdown
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "議事録" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            saved (save! (:id item)
                         (assoc doc :docs/blocks
                                [{:docs/id "h" :docs/kind :heading :docs/level 1
                                  :docs/text "議事録"}
                                 {:docs/id "p" :docs/kind :paragraph
                                  :docs/text "出席者は3名。"}
                                 {:docs/id "l" :docs/kind :list :docs/ordered? false
                                  :docs/items ["予算の確認" "次回日程"]}])
                         alice object-store)
            out (documents/export (:id item) "md" alice object-store)
            text (String. ^bytes (:bytes out) "UTF-8")]
        (is (some? saved))
        (is (= "議事録.md" (:filename out)))
        (is (= "text/markdown; charset=utf-8" (:media-type out)))
        (is (str/includes? text "# 議事録"))
        (is (str/includes? text "出席者は3名。"))
        (is (str/includes? text "- 予算の確認"))
        ;; The title is not written twice: the document opens with its own
        ;; h1, so `write` does not add another.
        (is (= 1 (count (filter #(= "# 議事録" %) (str/split-lines text)))))))))

(deftest markdown-comes-back-in
  (with-state
    (fn [_ object-store]
      (let [text (str "# 週報\n\n"
                      "今週の進捗です。\n\n"
                      "- 設計レビュー\n- 実装\n\n"
                      "| 項目 | 状態 |\n| --- | --- |\n| 設計 | 完了 |\n")
            {:keys [item]} (documents/import! "md" "週報" (.getBytes text "UTF-8")
                                              alice object-store)
            back (:resource (documents/content (:id item) alice object-store))]
        (is (= ":docs/document" (:resource-kind item)))
        ;; The title the caller asked for wins over the file's own h1, the
        ;; same as every other import — and the h1 stays in the body.
        (is (= "週報" (:docs/title back)))
        (is (= [:heading :paragraph :list :table]
               (mapv :docs/kind (:docs/blocks back))))
        (is (= ["設計レビュー" "実装"] (:docs/items (nth (:docs/blocks back) 2))))
        (is (= [["項目" "状態"] ["設計" "完了"]]
               (:docs/rows (nth (:docs/blocks back) 3))))))))

(deftest markdown-that-is-not-markdown-is-still-a-document
  ;; Every byte sequence is valid Markdown — there is no such thing as a
  ;; malformed one — so unlike pptx and xlsx there is nothing to refuse.
  ;; What matters is that junk becomes a document the validator accepts
  ;; rather than an exception, because a parser that threw would turn a bad
  ;; paste into a 500.
  (with-state
    (fn [_ object-store]
      (doseq [junk ["\u0000\u0001\u0002" "|||" "###" (apply str (repeat 500 "*"))]]
        (let [{:keys [item]} (documents/import! "md" "貼り付け"
                                                (.getBytes ^String junk "UTF-8")
                                                alice object-store)
              back (:resource (documents/content (:id item) alice object-store))]
          (is (= ":docs/document" (:resource-kind item)) (pr-str junk))
          (is (vector? (:docs/blocks back)) (pr-str junk)))))))

(deftest what-markdown-will-drop-is-said-before-it-drops-it
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc doc :docs/blocks
                            [{:docs/id "p" :docs/kind :paragraph :docs/text "赤い字"
                              :docs/text-runs
                              [{:docs/from 0 :docs/to 2 :docs/style {:color "red"}}]}])
                     alice object-store)
            warnings (:export-warnings (documents/content (:id item) alice object-store))]
        ;; Keyed by format, so the pane puts the warning next to the button
        ;; that causes it. Both writers drop a style they cannot spell, so
        ;; both answer — and a set, because the order of a map's keys is not
        ;; something to assert.
        (is (= #{"md" "docx"} (set (keys warnings))))
        (is (= ":markdown/style-dropped" (:code (first (get warnings "md")))))
        (is (= "info" (:severity (first (get warnings "md")))))
        (is (= "p" (:id (first (get warnings "md"))))))
      ;; A document with nothing to lose says nothing rather than saying
      ;; "no warnings" — an empty map would be a thing to render.
      (let [{:keys [item]} (documents/create! :docs "普通" alice object-store)]
        (is (nil? (:export-warnings (documents/content (:id item) alice object-store)))))
      ;; A plain workbook has nothing to lose either — asked, and silent.
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)]
        (is (nil? (:export-warnings (documents/content (:id item) alice object-store))))))))

(deftest a-workbook-says-what-xlsx-will-drop
  ;; The half that was missing: only Markdown could answer, and the note in
  ;; this file said so three times before the function got written.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (-> wb
                         (assoc-in [:sheets/tabs "sheet1" :sheets/cells [1 1]]
                                   ;; Bold is written now, so the style has
                                   ;; to carry something that is not for
                                   ;; this to report anything.
                                   {:sheets/value "四半期"
                                    :sheets/style {:bold true :color "red"}})
                         ;; A named range the validator accepts: it wants a
                         ;; tab and a range, and a save with anything else
                         ;; is refused before `unexpressed` is ever asked.
                         (assoc :sheets/named-ranges
                                {"総計" {:sheets/id "総計" :sheets/tab "sheet1"
                                         :sheets/range "A1:B9"}}))
                     alice object-store)
            warnings (:export-warnings (documents/content (:id item) alice object-store))]
        ;; Both writers answer now, and they answer differently — CSV loses
        ;; the other tabs as well, which xlsx does not.
        (is (= #{"xlsx" "csv"} (set (keys warnings))))
        (is (= #{":xlsx/cell-style-parts-dropped" ":xlsx/named-ranges-dropped"}
               (set (map :code (get warnings "xlsx")))))
        ;; Flattened out of `sheets.validate`'s namespaced shape into the
        ;; app's, the same as the docs ones — the pane renders both with the
        ;; code it already has.
        (is (every? #(= "info" (:severity %)) (get warnings "xlsx")))
        (is (every? :message (get warnings "xlsx")))))))

(deftest a-document-says-what-docx-will-drop-separately-from-markdown
  ;; The two lists differ, which is the reason for keying by format rather
  ;; than reporting one set of losses per document.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc doc :docs/blocks
                            [{:docs/id "r" :docs/kind :deck-ref :docs/target "x"}
                             {:docs/id "b" :docs/kind :paragraph :docs/text "太字"
                              :docs/text-runs
                              [{:docs/from 0 :docs/to 2 :docs/style {:bold true}}]}])
                     alice object-store)
            warnings (:export-warnings (documents/content (:id item) alice object-store))]
        ;; Markdown spells bold, so it says nothing about that run; docx
        ;; does not, so it does.
        (is (contains? (set (map :code (get warnings "docx")))
                       ":docx/text-runs-dropped"))
        (is (not (contains? (set (map :code (get warnings "md")))
                            ":markdown/style-dropped")))
        ;; Both say the reference stops being one.
        (is (contains? (set (map :code (get warnings "md")))
                       ":markdown/reference-is-a-link"))
        (is (contains? (set (map :code (get warnings "docx")))
                       ":docx/reference-becomes-text"))))))

(deftest only-a-workbook-is-offered-xlsx
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            error (try (documents/export (:id item) "xlsx" alice object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/unsupported-format (:type error)))
        (is (= ["docx" "edn" "md"] (:available error))))
      ;; And a workbook is offered exactly the three it has writers for.
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)]
        (is (= ["csv" "edn" "xlsx"]
               (->> (documents/drive-view {:items []} alice)
                    :kinds
                    (some #(when (= "sheets" (:kind %)) (:exports %))))))))))

;; ── two editors, one document ───────────────────────────────────────────────

(deftest a-save-from-a-version-that-has-moved-is-refused
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            ;; Both open the same version.
            a (documents/content (:id item) alice object-store)
            b (documents/content (:id item) bob object-store)
            _ (is (= (:etag (:item a)) (:etag (:item b))))
            add (fn [payload who]
                  (update payload "docs/blocks" conj
                          {"docs/id" who "docs/kind" "paragraph" "docs/text" who}))]
        (documents/update! (:id item) (add (:payload a) "alice") alice
                           (:etag (:item a)) object-store)
        (let [error (try (documents/update! (:id item) (add (:payload b) "bob") bob
                                            (:etag (:item b)) object-store)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :drive/stale-version (:type error)))
          (is (= alice (:updated-by error)) "and it says whose save it lost to")
          (is (= 2 (:versions error))))
        ;; Measured before this check existed: bob's save went through and
        ;; alice's paragraph was simply gone, with the UI saying "saved".
        (let [final (:payload (documents/content (:id item) alice object-store))]
          (is (= ["title" "alice"] (mapv #(get % "docs/id") (get final "docs/blocks")))))
        (is (= 2 (:versions (first (documents/documents @state alice)))))))))

(deftest re-reading-is-what-lets-the-second-editor-win
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            a (documents/content (:id item) alice object-store)]
        (documents/update! (:id item)
                           (update (:payload a) "docs/blocks" conj
                                   {"docs/id" "alice" "docs/kind" "paragraph"
                                    "docs/text" "alice"})
                           alice (:etag (:item a)) object-store)
        ;; bob reads again, and now his save carries the current version.
        (let [b (documents/content (:id item) bob object-store)]
          (is (:ok? (documents/update! (:id item)
                                       (update (:payload b) "docs/blocks" conj
                                               {"docs/id" "bob" "docs/kind" "paragraph"
                                                "docs/text" "bob"})
                                       bob (:etag (:item b)) object-store))))
        (let [final (:payload (documents/content (:id item) alice object-store))]
          (is (= ["title" "alice" "bob"]
                 (mapv #(get % "docs/id") (get final "docs/blocks")))
              "both survive, because the second edit was made from the first"))))))

(deftest a-save-with-no-etag-is-refused-rather-than-treated-as-current
  ;; A nil that meant "whatever is there now" would be the old behaviour
  ;; under a new name.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "計画" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (is (= :drive/stale-version
               (try (documents/update! (:id item) payload alice nil object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest renaming-carries-no-etag-because-it-cannot-be-stale
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "旧題" alice object-store)
            stale (documents/content (:id item) alice object-store)]
        ;; Somebody else moves the document on.
        (documents/grant! (:id item) bob "editor" alice)
        (documents/update! (:id item) (:payload stale) bob (:etag (:item stale))
                           object-store)
        ;; A rename still works: it reads the current resource itself, inside
        ;; the lock, so it never carries a stale copy.
        (is (= "新題" (:name (:item (documents/rename! (:id item) "新題" alice
                                                       object-store)))))))))

(deftest a-reply-joins-the-thread-it-answers
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "commenter" alice)
            root (:comment (documents/comment! (:id item) "ここは要検討" "title" alice))
            reply (:comment (documents/comment! (:id item) "同意します" nil bob (:id root)))
            {:keys [comments unresolved]} (documents/comments (:id item) alice)]
        (is (= 1 (count comments)) "one thread, not two entries")
        (is (= [(:id reply)] (mapv :id (:replies (first comments)))))
        ;; A reply that could point somewhere else would not be a reply.
        (is (= "title" (:anchor reply)))
        (is (= 1 unresolved))))))

(deftest a-reply-to-a-reply-stays-in-the-same-thread
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            root (:comment (documents/comment! (:id item) "一つめ" nil alice))
            reply (:comment (documents/comment! (:id item) "二つめ" nil alice (:id root)))
            deeper (:comment (documents/comment! (:id item) "三つめ" nil alice (:id reply)))
            {:keys [comments]} (documents/comments (:id item) alice)]
        ;; One level: a conversation about one anchor is one conversation,
        ;; and a tree would let somebody resolve half of it.
        (is (= (:id root) (:parent-id deeper)))
        (is (= 1 (count comments)))
        (is (= 2 (count (:replies (first comments)))))))))

(deftest resolving-belongs-to-the-thread
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "commenter" alice)
            root (:comment (documents/comment! (:id item) "ここは要検討" nil alice))
            reply (:comment (documents/comment! (:id item) "直しました" nil bob (:id root)))]
        ;; Resolving the reply resolves the comment it answers: half a
        ;; resolved conversation is not a state anybody can act on.
        (let [{:keys [comments unresolved]}
              (documents/resolve-comment! (:id item) (:id reply) true bob)]
          (is (zero? unresolved))
          (is (string? (:resolved-at (first comments))))
          (is (= bob (:resolved-by (first comments)))))
        (let [{:keys [comments unresolved]}
              (documents/resolve-comment! (:id item) (:id root) false alice)]
          (is (= 1 unresolved))
          (is (nil? (:resolved-at (first comments))))
          (is (nil? (:resolved-by (first comments)))))))))

(deftest anyone-who-may-comment-may-resolve
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            root (:comment (documents/comment! (:id item) "ここは要検討" nil alice))]
        (documents/grant! (:id item) bob "viewer" alice)
        ;; A viewer is shown the conversation and does not get a say in it.
        (is (= :drive/not-permitted
               (try (documents/resolve-comment! (:id item) (:id root) true bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (documents/grant! (:id item) bob "commenter" alice)
        ;; Reversible, which is why this is wider than deleting.
        (is (zero? (:unresolved (documents/resolve-comment! (:id item) (:id root) true bob))))))))

(deftest a-resolved-thread-is-reopened-on-purpose-not-by-replying
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            root (:comment (documents/comment! (:id item) "ここは要検討" nil alice))]
        (documents/resolve-comment! (:id item) (:id root) true alice)
        (is (= :drive/comment-resolved
               (try (documents/comment! (:id item) "やっぱり" nil alice (:id root))
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (documents/resolve-comment! (:id item) (:id root) false alice)
        (is (:ok? (documents/comment! (:id item) "やっぱり" nil alice (:id root))))))))

(deftest deleting-a-root-takes-its-replies
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "commenter" alice)
            root (:comment (documents/comment! (:id item) "一つめ" nil alice))
            _ (documents/comment! (:id item) "返信" nil bob (:id root))
            out (documents/delete-comment! (:id item) (:id root) alice)]
        ;; A reply to nothing is not something a reader can make sense of.
        (is (= 2 (:deleted out)))
        (is (empty? (:comments (documents/comments (:id item) alice))))))))

(deftest deleting-a-reply-leaves-the-thread
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            root (:comment (documents/comment! (:id item) "一つめ" nil alice))
            reply (:comment (documents/comment! (:id item) "返信" nil alice (:id root)))
            out (documents/delete-comment! (:id item) (:id reply) alice)
            {:keys [comments]} (documents/comments (:id item) alice)]
        (is (= 1 (:deleted out)))
        (is (= 1 (count comments)))
        (is (empty? (:replies (first comments))))))))

(deftest replying-to-a-comment-that-is-not-there
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (is (= :drive/not-found
               (try (documents/comment! (:id item) "返信" nil alice "cmt-nonexistent")
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── references between documents ────────────────────────────────────────────

(defn- memo-referencing
  "A docs document with one ref block pointing at `target`."
  [kind target object-store]
  (let [{:keys [item]} (documents/create! :docs "設計メモ" alice object-store)
        payload (:payload (documents/content (:id item) alice object-store))]
    (save! (:id item)
                       (update payload "docs/blocks" conj
                               {"docs/id" "ref1" "docs/kind" kind "docs/target" target})
                       alice object-store)
    item))

(deftest a-reference-resolves-to-the-document-it-names
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))
            memo (memo-referencing "table-ref" (:id book) object-store)
            {:keys [references]} (documents/references (:id memo) alice object-store)]
        (is (= [{:block "ref1" :kind "table-ref" :target (:id book) :resolved? true
                 :name "売上" :label "スプレッドシート"
                 :resource-kind ":sheets/workbook" :expected? true}]
               references))))))

(deftest a-reference-to-nothing-is-a-warning-and-not-a-refusal
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計メモ" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            saved (save! (:id item)
                                     (update payload "docs/blocks" conj
                                             {"docs/id" "ref1" "docs/kind" "deck-ref"
                                              "docs/target" "doc-nonexistent"})
                                     alice object-store)]
        ;; A document being written may name something about to be shared;
        ;; refusing the save would make writing it impossible.
        (is (:ok? saved))
        (is (= [":reference/dangling"] (mapv :code (:warnings saved))))
        (is (false? (:resolved? (first (:references
                                        (documents/references (:id item) alice object-store))))))))))

(deftest pointing-a-table-ref-at-a-deck-is-reported-not-refused
  (with-state
    (fn [_ object-store]
      (let [deck (:item (documents/create! :slides "四半期" alice object-store))
            memo (memo-referencing "table-ref" (:id deck) object-store)
            {:keys [references]} (documents/references (:id memo) alice object-store)
            payload (:payload (documents/content (:id memo) alice object-store))
            again (save! (:id memo) payload alice object-store)]
        ;; `docs.model` names the kinds and does not say a :table-ref must be
        ;; a workbook, so this is advisory.
        (is (true? (:resolved? (first references))))
        (is (false? (:expected? (first references))))
        (is (:ok? again))
        (is (= [":reference/unexpected-kind"] (mapv :code (:warnings again))))))))

(deftest a-file-ref-may-point-at-anything
  (with-state
    (fn [_ object-store]
      (let [form (:item (documents/create! :forms "問い合わせ" alice object-store))
            memo (memo-referencing "file-ref" (:id form) object-store)
            payload (:payload (documents/content (:id memo) alice object-store))]
        (is (true? (:expected? (first (:references
                                       (documents/references (:id memo) alice object-store))))))
        (is (empty? (:warnings (save! (:id memo) payload alice object-store))))))))

(deftest backlinks-say-which-documents-depend-on-this-one
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))
            memo (memo-referencing "table-ref" (:id book) object-store)
            {:keys [referenced-by]} (documents/referenced-by (:id book) alice object-store)]
        (is (= [{:id (:id memo) :name "設計メモ" :label "ドキュメント"
                 :block "ref1" :kind "table-ref"}]
               referenced-by))
        ;; And the memo itself has none pointing at it.
        (is (empty? (:referenced-by (documents/referenced-by (:id memo) alice object-store))))))))

(deftest a-reference-obeys-the-same-permission-answer-as-everything-else
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))
            memo (memo-referencing "table-ref" (:id book) object-store)]
        ;; bob is shown the memo but not the workbook it names, so the
        ;; reference reads as unresolved — the same answer as a target that
        ;; does not exist, which is what keeps it from leaking that one does.
        (documents/grant! (:id memo) bob "viewer" alice)
        (let [{:keys [references]} (documents/references (:id memo) bob object-store)]
          (is (= 1 (count references)))
          (is (false? (:resolved? (first references))))
          (is (nil? (:name (first references)))))
        ;; Once he may read it too, the same reference resolves.
        (documents/grant! (:id book) bob "viewer" alice)
        (is (true? (:resolved? (first (:references
                                       (documents/references (:id memo) bob object-store))))))))))

(deftest backlinks-only-count-documents-the-asker-can-see
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))
            _ (memo-referencing "table-ref" (:id book) object-store)]
        (documents/grant! (:id book) bob "viewer" alice)
        ;; bob may read the workbook and not the memo, so he is not told the
        ;; memo exists by way of its backlink.
        (is (empty? (:referenced-by (documents/referenced-by (:id book) bob object-store))))
        (is (= 1 (count (:referenced-by (documents/referenced-by (:id book) alice
                                                                 object-store)))))))))

(deftest only-a-document-carries-references
  (with-state
    (fn [_ object-store]
      (let [book (:item (documents/create! :sheets "売上" alice object-store))]
        ;; A workbook has no block that names another document, and a deck's
        ;; links live on a slides workspace rather than in the deck.
        (is (empty? (:references (documents/references (:id book) alice object-store))))))))

;; ── comments ────────────────────────────────────────────────────────────────

(deftest a-commenter-may-comment-and-still-not-write
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "commenter" alice)
            payload (:payload (documents/content (:id item) bob object-store))]
        ;; The role now means something. Before this it was indistinguishable
        ;; from :viewer — grantable, and backed by nothing.
        (is (:ok? (documents/comment! (:id item) "ここは要検討" "title" bob)))
        (is (= :drive/not-permitted
               (try (save! (:id item) payload bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (let [{:keys [comments]} (documents/comments (:id item) alice)]
          (is (= 1 (count comments)))
          (is (= {:author bob :text "ここは要検討" :anchor "title"}
                 (select-keys (first comments) [:author :text :anchor]))))))))

(deftest a-viewer-may-read-comments-and-not-leave-one
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/comment! (:id item) "所有者のメモ" nil alice)
        (documents/grant! (:id item) bob "viewer" alice)
        ;; Shown the document and what has been said about it; not given a
        ;; voice, because `drive.workspace` already draws that line.
        (is (= 1 (count (:comments (documents/comments (:id item) bob)))))
        (is (= :drive/not-permitted
               (try (documents/comment! (:id item) "口を出したい" nil bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest commenting-does-not-touch-the-document
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            before (:payload (documents/content (:id item) alice object-store))]
        (documents/comment! (:id item) "一言" nil alice)
        ;; No new version, nothing charged, and the stored bytes unchanged —
        ;; which is the whole reason comments are not written into them.
        (is (= 1 (:versions (first (documents/documents @state alice)))))
        (is (= before (:payload (documents/content (:id item) alice object-store))))
        (is (empty? (get before "docs/comments")))))))

(deftest an-empty-comment-is-refused
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (is (= :drive/invalid-comment
               (try (documents/comment! (:id item) "   " nil alice)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (empty? (:comments (documents/comments (:id item) alice))))))))

(deftest a-comment-is-deleted-by-its-author-or-the-owner
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            _ (documents/grant! (:id item) bob "editor" alice)
            _ (documents/grant! (:id item) "user-carol" "commenter" alice)
            from-carol (:comment (documents/comment! (:id item) "carol の指摘" nil "user-carol"))]
        ;; An editor may rewrite the document and still not delete what
        ;; somebody said about it.
        (is (= :drive/not-permitted
               (try (documents/delete-comment! (:id item) (:id from-carol) bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (:ok? (documents/delete-comment! (:id item) (:id from-carol) "user-carol")))
        (is (empty? (:comments (documents/comments (:id item) alice))))
        ;; And the owner may, on someone else's.
        (let [again (:comment (documents/comment! (:id item) "もう一度" nil "user-carol"))]
          (is (:ok? (documents/delete-comment! (:id item) (:id again) alice))))))))

(deftest comments-on-a-trashed-document-are-out-of-reach
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/comment! (:id item) "一言" nil alice)
        (documents/trash! (:id item) alice)
        (is (= :drive/not-found
               (try (documents/comments (:id item) alice)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-stranger-sees-no-comments
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私信" alice object-store)]
        (documents/comment! (:id item) "内緒" nil alice)
        (is (= :drive/not-found
               (try (documents/comments (:id item) bob)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── a grant is a signed capability ──────────────────────────────────────────

(deftest a-grant-mints-a-capability-anyone-can-check
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)
            out (documents/grant! (:id item) bob "editor" alice)
            grant (first (:grants out))]
        (is (str/starts-with? (:issuer out) "did:key:"))
        (is (:verified? grant) "the library's answer, not a guess from the dates")
        (is (string? (:capability grant)))
        (is (string? (:expires-at grant)))
        ;; Verifiable without this namespace being asked: the token, the
        ;; document and the role are all it takes.
        (let [cap (documents/capability-for @state (:id item) bob)]
          (is (:valid? (capability/verify-grant cap (:id item) :editor
                                                (java.time.Instant/now))))
          ;; And it is about this document and this role only.
          (is (= :resource-mismatch
                 (:reason (capability/verify-grant cap (:id item) :viewer
                                                   (java.time.Instant/now)))))
          (is (= :resource-mismatch
                 (:reason (capability/verify-grant cap "doc-other" :editor
                                                   (java.time.Instant/now))))))))))

(deftest an-expired-capability-stops-being-honoured
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "共同設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (is (= 1 (count (documents/documents @state bob))))
        ;; A genuinely lapsed capability — minted a year ago with a day to
        ;; live, so the token and the record agree. This is what makes it
        ;; the permission rather than a description of one: the ACL entry is
        ;; still there and stops answering yes.
        (swap! state assoc-in
               [:drive :capabilities (:id item) bob]
               (capability/mint-grant
                {:document-id (:id item) :role :editor :audience bob
                 :expires-in-days 1
                 :now (.minusSeconds (java.time.Instant/now) (* 365 86400))}))
        (is (empty? (documents/documents @state bob)))
        (is (= :drive/not-found
               (try (documents/content (:id item) bob object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        ;; The entry itself was not deleted — the owner can still see who it
        ;; was issued to, and that it has lapsed.
        (is (= [bob] (mapv :principal (:grants (documents/sharing (:id item) alice)))))
        (is (false? (:verified? (first (:grants (documents/sharing (:id item) alice))))))))))

(deftest revoking-takes-the-capability-with-the-entry
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (documents/revoke-grant! (:id item) bob alice)
        ;; Leaving it behind would hand a revoked grantee something that
        ;; still verifies.
        (is (nil? (documents/capability-for @state (:id item) bob)))))))

(deftest a-grant-made-before-capabilities-existed-still-works
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        ;; An ACL entry with no capability beside it, as every grant looked
        ;; before this. Retroactively expiring a share nobody was warned
        ;; about would be the change taking something away.
        (swap! state update-in [:drive :workspaces alice :drive.workspace/items
                                (:id item) :drive/permissions]
               assoc bob :editor)
        (is (= 1 (count (documents/documents @state bob))))
        (is (:ok? (documents/content (:id item) bob object-store)))))))

;; ── share links ─────────────────────────────────────────────────────────────

(deftest a-link-reads-without-a-role
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "配布資料" alice object-store)
            {:keys [token links]} (documents/create-link! (:id item) "viewer" nil
                                                          alice now-ms)]
        (is (string? token))
        (is (= [{:token token :role "viewer" :expires-at nil}] links))
        (let [read (documents/link-content token bob now-ms object-store)]
          (is (:ok? read))
          (is (= "viewer" (:role read)))
          (is (= "配布資料" (get (:payload read) "docs/title")))
          (is (false? (:writable? (:item read)))))))))

(deftest a-link-cannot-be-made-writable
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "配布資料" alice object-store)]
        ;; `create-share-link` refuses anything but viewer/commenter and says
        ;; why: a link may read and never write.
        (is (= :drive/invalid-share
               (try (documents/create-link! (:id item) "editor" nil alice now-ms)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= ["viewer" "commenter"]
               (:link-roles (documents/sharing (:id item) alice))))))))

(deftest an-expired-link-is-indistinguishable-from-one-that-never-existed
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "期限つき" alice object-store)
            {:keys [token]} (documents/create-link! (:id item) "viewer" 24 alice now-ms)
            type-of (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e
                                           (:type (ex-data e)))))]
        (is (:ok? (documents/link-content token bob (+ now-ms 1000) object-store)))
        (is (= :drive/not-found
               (type-of #(documents/link-content token bob
                                                 (+ now-ms (* 25 60 60 1000))
                                                 object-store))))
        (is (= :drive/not-found
               (type-of #(documents/link-content "never-issued" bob now-ms object-store))))
        ;; Revoked reads the same way.
        (documents/revoke-link! (:id item) token alice)
        (is (= :drive/not-found
               (type-of #(documents/link-content token bob now-ms object-store))))))))

(deftest a-link-needs-a-session-all-the-same
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "配布資料" alice object-store)
            {:keys [token]} (documents/create-link! (:id item) "viewer" nil alice now-ms)]
        ;; The server binds loopback-only; an unauthenticated route would be
        ;; the only one in the app and would serve nobody who could not
        ;; already reach the port.
        (is (= :identity/unauthenticated
               (try (documents/link-content token "" now-ms object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest a-link-to-a-trashed-document-does-not-read-it
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "取り下げ" alice object-store)
            {:keys [token]} (documents/create-link! (:id item) "viewer" nil alice now-ms)]
        (documents/trash! (:id item) alice)
        ;; `read-via-share-link` checks trash itself. A link that outlived the
        ;; document it points at hands out deleted content otherwise.
        (is (= :drive/not-found
               (try (documents/link-content token bob now-ms object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest the-drive-view-keeps-the-archive-and-labels-both-sides
  (with-state
    (fn [_ object-store]
      (documents/create! :sheets "計画" alice object-store)
      (let [archive {:source "m365-archive / onedrive" :model "kotoba-lang/drive"
                     :mode "archive" :count 2
                     :items [{:id "a.txt" :name "a.txt" :available? true}
                             {:id "b.txt" :name "b.txt" :available? true}]}
            view (documents/drive-view archive alice)]
        (is (= 3 (:count view)))
        (is (= 1 (:documents view)))
        ;; Created documents lead; the archive keeps its own order behind
        ;; them.
        (is (= ["計画" "a.txt" "b.txt"] (mapv :name (:items view))))
        (is (= ["workspace" "archive" "archive"] (mapv :origin (:items view))))
        (is (str/includes? (:source view) "作成済み 1 件"))
        (is (= #{"sheets" "docs" "forms" "slides"} (set (map :kind (:kinds view)))))))))

(deftest the-create-bar-is-driven-by-the-servers-own-table
  ;; The UI renders one button per entry of `:kinds`, so a surface added to
  ;; `documents/kinds` appears without a second list being edited.
  (is (= (set (map name (keys documents/kinds)))
         (set (map :kind (:kinds (with-state (fn [_ _] (documents/drive-view {:items []} alice)))))))))

(deftest an-xlsx-imports-as-a-workbook
  (with-state
    (fn [state object-store]
      (let [source (-> (sheets-model/workbook "src")
                       (sheets-model/add-tab
                        (-> (sheets-model/tab "予算" {:sheets/title "予算"})
                            (sheets-model/put-cell 1 1 "四半期")
                            (sheets-model/put-cell 2 1 "Q1")
                            (sheets-model/put-formula 2 2 "A2")))
                       (sheets-model/add-tab
                        (-> (sheets-model/tab "実績" {:sheets/title "実績"})
                            (sheets-model/put-cell 1 1 "実績"))))
            {:keys [item]} (documents/import! "xlsx" "取り込み予算"
                                              (sheets-xlsx/xlsx-bytes source)
                                              alice object-store)
            back (:resource (documents/content (:id item) alice object-store))]
        (is (= ":sheets/workbook" (:resource-kind item)))
        (is (= "取り込み予算" (:name item)))
        ;; Both tabs, and no leftover "sheet1" from the seed in front of
        ;; them — a workbook arrives whole.
        (is (= ["予算" "実績"] (sort (keys (:sheets/tabs back)))))
        (is (= {:sheets/value "四半期"}
               (get-in back [:sheets/tabs "予算" :sheets/cells [1 1]])))
        (is (= {:sheets/formula "A2"}
               (get-in back [:sheets/tabs "予算" :sheets/cells [2 2]])))
        (is (= 1 (count (documents/documents @state alice))))))))

(defn- excel-shaped-xlsx
  "A .xlsx the way Excel writes one, which is not the way `sheets.xlsx`
  writes one: shared strings, a style table, and numbers with no `t`.

  Zipped by hand because `xlsx-files` cannot produce this — it has no
  styles to write — and the whole point is to import a file this Drive did
  not create."
  []
  (let [files
        {"[Content_Types].xml" "<Types/>"
         "_rels/.rels" "<Relationships/>"
         "xl/workbook.xml"
         (str "<workbook><workbookPr date1904=\"0\"/>"
              "<sheets><sheet name=\"請求\" r:id=\"rId1\"/></sheets></workbook>")
         "xl/_rels/workbook.xml.rels"
         (str "<Relationships><Relationship Id=\"rId1\" "
              "Target=\"worksheets/sheet1.xml\"/></Relationships>")
         "xl/styles.xml"
         (str "<styleSheet><cellXfs>"
              "<xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>"
              "</cellXfs></styleSheet>")
         "xl/sharedStrings.xml" "<sst><si><t>支払期日</t></si></sst>"
         "xl/worksheets/sheet1.xml"
         (str "<worksheet><sheetData>"
              "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
              "<c r=\"B1\" s=\"1\"><v>45000</v></c></row>"
              "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>金額</t></is></c>"
              "<c r=\"B2\" s=\"0\"><v>120000</v></c></row>"
              "</sheetData></worksheet>")}
        out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (java.util.zip.ZipOutputStream. out)]
      (doseq [[path text] (sort files)]
        (.putNextEntry zip (java.util.zip.ZipEntry. ^String path))
        (.write zip (.getBytes ^String text "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

(deftest a-dated-cell-arrives-as-a-date
  ;; Excel has no date type: a date is a serial number whose *format* makes
  ;; it one. Without reading `xl/styles.xml` this cell arrives as 45000, and
  ;; a person looking at their own invoice sees a five-digit number where
  ;; they wrote a day.
  (with-state
    (fn [_state object-store]
      (let [{:keys [item]} (documents/import! "xlsx" "請求書"
                                              (excel-shaped-xlsx)
                                              alice object-store)
            back (:resource (documents/content (:id item) alice object-store))
            cells (get-in back [:sheets/tabs "請求" :sheets/cells])]
        (is (= {:sheets/value "2023-03-15"} (get cells [1 2])))
        ;; And the amount beside it is not a date, because nothing said it
        ;; was. This is the half that a converter guessing from shape alone
        ;; would get wrong — 120000 is a plausible serial too.
        (is (= {:sheets/value "120000"} (get cells [2 2])))
        (is (= {:sheets/value "支払期日"} (get cells [1 1])))
        (is (= {:sheets/value "金額"} (get cells [2 1])))))))

(deftest an-xlsx-round-trips-through-the-drive
  (with-state
    (fn [_ object-store]
      (let [csv "四半期,売上\r\nQ1,1200"
            imported (:item (documents/import! "csv" "売上"
                                               (.getBytes csv "UTF-8") alice object-store))
            xlsx (documents/export (:id imported) "xlsx" alice object-store)
            again (:item (documents/import! "xlsx" "往復"
                                            (:bytes xlsx) alice object-store))
            back (:resource (documents/content (:id again) alice object-store))
            tab (first (vals (:sheets/tabs back)))]
        (is (= {:sheets/value "四半期"} (get-in tab [:sheets/cells [1 1]])))
        (is (= {:sheets/value "1200"} (get-in tab [:sheets/cells [2 2]])))))))

;; ── folders ─────────────────────────────────────────────────────────────────

(deftest a-document-can-be-made-inside-a-folder
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            {:keys [item]} (documents/create! :docs "議事録" alice object-store
                                              {:folder (:id work)})]
        (is (= "folder" (:kind work)))
        (is (= 0 (:count (:item (documents/create-folder! "仕事2" alice)))) "a fresh folder is empty")
        (is (= ["My Drive" "仕事" "議事録"]
               (:path (documents/move! (:id item) (:id work) alice))))
        ;; It is a document like any other — the listing is flat and still
        ;; shows it, because a Drive that hid everything filed away would be
        ;; a Drive nobody could search.
        (is (= 1 (count (documents/documents @state alice))))))))

(deftest trashing-a-folder-takes-its-contents-out-of-the-listing
  ;; The bug the derived rule exists for. Before it, a document whose folder
  ;; was in the trash stayed in the list: an orphan nobody could explain.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            {:keys [item]} (documents/create! :docs "議事録" alice object-store
                                              {:folder (:id work)})
            loose (:item (documents/create! :docs "単独" alice object-store))]
        (is (= 2 (count (documents/documents @state alice))))
        (documents/trash! (:id work) alice)
        (is (= [(:id loose)] (mapv :id (documents/documents @state alice))))
        ;; The document itself now reports as trashed, because it is.
        (is (:trashed? (:item (documents/content (:id item) alice object-store))))
        ;; And the trash lists the folder, not each file under it — what was
        ;; put there is one thing, and restoring it is one act.
        (let [binned (documents/trashed @state alice)]
          (is (= [(:id work)] (mapv :id binned)))
          (is (= ["folder"] (mapv :kind binned))))))))

(deftest restoring-a-folder-does-not-restore-what-was-already-in-the-trash
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            a (:item (documents/create! :docs "A" alice object-store {:folder (:id work)}))
            b (:item (documents/create! :docs "B" alice object-store {:folder (:id work)}))]
        (documents/trash! (:id a) alice)
        (documents/trash! (:id work) alice)
        (documents/restore! (:id work) alice)
        ;; B is back; A stays where its owner put it.
        (is (= [(:id b)] (mapv :id (documents/documents @state alice))))))))

(deftest a-folder-cannot-be-put-inside-itself
  (with-state
    (fn [_ _]
      (let [work (:item (documents/create-folder! "仕事" alice))
            q1 (:item (documents/create-folder! "Q1" alice (:id work)))]
        ;; Asserted without rewriting the type in the catch — a test that
        ;; supplies the answer it is checking passes whenever anything
        ;; throws, which is every bug as well as this rule.
        (is (= :drive/invalid-move
               (:type (try (documents/move! (:id work) (:id q1) alice)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (= :drive/invalid-move
               (:type (try (documents/move! (:id work) (:id work) alice)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        ;; And a legitimate move still works, so the guard is not refusing
        ;; everything.
        (is (:ok? (documents/move! (:id q1) nil alice)))))))

(deftest an-editor-of-a-shared-folder-may-create-in-it
  ;; This was the gap named in the last change: creating looked only in the
  ;; creator's own Drive, so a folder shared with you was not somewhere you
  ;; could put anything.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))]
        (documents/grant! (:id work) bob "editor" alice)
        (let [{:keys [item]} (documents/create! :docs "議事録" bob object-store
                                                {:folder (:id work)})]
          ;; It is in alice's Drive, because that is the Drive the folder is
          ;; in — and one document, not two.
          (is (= alice (:owner item)))
          (is (false? (:own? item)))
          (is (= [(:id item)] (mapv :id (documents/documents @state alice))))
          (is (= [(:id item)] (mapv :id (documents/documents @state bob))))
          ;; alice owns what is in her Drive: trash, purge and re-sharing
          ;; are all owner-only, and a document she could not remove from
          ;; her own folder would be one she is stuck with.
          (is (= "owner" (:role (first (documents/documents @state alice)))))
          ;; bob keeps what he needs to go on working on it.
          (is (= "editor" (:role (first (documents/documents @state bob)))))
          (is (:writable? (first (documents/documents @state bob))))
          (is (some? (save! (:id item)
                            (assoc (:resource (documents/content (:id item) bob
                                                                 object-store))
                                   :docs/title "改訂")
                            bob object-store))))))))

(deftest what-an-editor-creates-is-charged-to-the-drive-it-is-in
  ;; The cost, stated rather than discovered: someone you gave write access
  ;; to can consume your quota. That was already true of *saving* a shared
  ;; document — every version is charged to the owner — so this widens who
  ;; can start one rather than introducing the hazard.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))]
        (documents/grant! (:id work) bob "editor" alice)
        (let [before (:used-bytes (documents/quota-view @state alice))]
          (documents/create! :docs "議事録" bob object-store {:folder (:id work)})
          (is (> (:used-bytes (documents/quota-view (store/snapshot) alice)) before))
          (is (zero? (:used-bytes (documents/quota-view (store/snapshot) bob)))))))))

(deftest a-viewer-of-a-shared-folder-may-not-create-in-it
  (with-state
    (fn [_ object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))]
        (documents/grant! (:id work) bob "viewer" alice)
        (is (= :drive/not-permitted
               (:type (try (documents/create! :docs "侵入" bob object-store
                                              {:folder (:id work)})
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        ;; And a folder nobody shared with them is not even visible as a
        ;; place — told it is not there rather than that they may not.
        (let [private (:item (documents/create-folder! "私用" alice))]
          (is (= :drive/not-found
                 (:type (try (documents/create! :docs "侵入" bob object-store
                                                {:folder (:id private)})
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))))

(deftest a-move-may-not-leave-the-drive-it-is-in
  ;; `ws/move` rewrites one tree. A destination in another Drive would leave
  ;; a parent id pointing at an item that tree does not contain — a
  ;; breadcrumb walking up out of the workspace and a listing that never
  ;; shows it again. Creating may cross; moving may not.
  (with-state
    (fn [_ object-store]
      (let [theirs (:item (documents/create-folder! "相手" bob))
            _ (documents/grant! (:id theirs) alice "editor" bob)
            mine (:item (documents/create! :docs "自分の" alice object-store))]
        (is (= :drive/not-found
               (:type (try (documents/move! (:id mine) (:id theirs) alice)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-folder-you-may-only-read-is-not-one-you-may-create-in
  ;; The permission rule itself, asked where it can be: inside one Drive.
  (with-state
    (fn [_ object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            ;; alice's own Drive, so the folder is found; the question left
            ;; is whether this principal may write into it.
            _ (documents/grant! (:id work) bob "viewer" alice)]
        (is (some? (documents/create! :docs "議事録" alice object-store
                                      {:folder (:id work)})))))))

(deftest sharing-a-folder-shares-what-is-in-it
  ;; The reason folders are worth having, and it is inheritance rather than
  ;; anything written here.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            {:keys [item]} (documents/create! :docs "議事録" alice object-store
                                              {:folder (:id work)})]
        (is (empty? (documents/documents @state bob)))
        (documents/grant! (:id work) bob "editor" alice)
        (is (= [(:id item)] (mapv :id (documents/documents @state bob))))
        (is (= "editor" (:role (first (documents/documents @state bob)))))))))

(deftest a-purged-document-leaves-its-folder-s-listing
  ;; Everything lived at the root until folders existed, so `purge!` removed
  ;; the id from the root's children and was right by accident. In a folder
  ;; that left a listing pointing at an item that is gone.
  (with-state
    (fn [_ object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            {:keys [item]} (documents/create! :docs "議事録" alice object-store
                                              {:folder (:id work)})]
        (documents/trash! (:id item) alice)
        (documents/purge! (:id item) alice object-store)
        (is (= 0 (:count (first (:folders (documents/folders (store/snapshot) alice nil))))))))))

(deftest the-breadcrumb-says-where-you-are
  (with-state
    (fn [_ _]
      (let [work (:item (documents/create-folder! "仕事" alice))
            q1 (:item (documents/create-folder! "Q1" alice (:id work)))
            here (documents/folders (store/snapshot) alice (:id q1))]
        (is (= ["My Drive" "仕事" "Q1"] (mapv :name (:path here))))
        (is (= [] (:folders here)) "nothing inside it yet")
        (let [top (documents/folders (store/snapshot) alice nil)]
          (is (= ["仕事"] (mapv :name (:folders top))) "only the direct children"))))))

(deftest emptying-the-trash-reclaims-what-was-inside-a-folder
  ;; The bytes of a file inside a trashed folder are still charged to the
  ;; quota, and nothing lists that file on its own — it is in the trash
  ;; because its folder is. Before folders, `trashed` listed only files with
  ;; their own flag set, so a trashed folder could never be purged at all and
  ;; everything under it stayed charged for ever.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            a (:item (documents/create! :docs "A" alice object-store {:folder (:id work)}))
            _ (:item (documents/create! :docs "B" alice object-store {:folder (:id work)}))
            before (:used-bytes (documents/quota-view @state alice))]
        (is (pos? before))
        (documents/trash! (:id work) alice)
        (let [{:keys [freed-bytes purged]} (documents/empty-trash! alice object-store)]
          ;; The folder and both files.
          (is (= 3 purged))
          (is (= before freed-bytes)))
        (is (zero? (:used-bytes (documents/quota-view (store/snapshot) alice))))
        (is (empty? (documents/documents (store/snapshot) alice)))
        (is (empty? (documents/trashed (store/snapshot) alice)))
        ;; And the file is gone rather than merely hidden.
        (is (= :drive/not-found
               (:type (try (documents/content (:id a) alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest purging-a-folder-does-not-resurrect-what-was-inside-it
  ;; `trashed?` walks upwards. A folder dropped before its contents would
  ;; leave them pointing at a parent that is not there, the walk would end at
  ;; a missing item, and the answer would be "not in the trash" — the files
  ;; would come back into the listing, resurrected by the deletion of their
  ;; folder and impossible to get rid of.
  (with-state
    (fn [_ object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            q1 (:item (documents/create-folder! "Q1" alice (:id work)))
            deep (:item (documents/create! :docs "議事録" alice object-store
                                           {:folder (:id q1)}))]
        (documents/trash! (:id work) alice)
        (let [{:keys [purged]} (documents/purge! (:id work) alice object-store)]
          (is (= 3 purged) "two folders and the document under them"))
        (is (empty? (documents/documents (store/snapshot) alice)))
        (is (= :drive/not-found
               (:type (try (documents/content (:id deep) alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-folder-inside-a-trashed-folder-is-not-a-second-thing-to-restore
  (with-state
    (fn [state _]
      (let [work (:item (documents/create-folder! "仕事" alice))
            _ (documents/create-folder! "Q1" alice (:id work))]
        (documents/trash! (:id work) alice)
        (is (= [(:id work)] (mapv :id (documents/trashed @state alice))))))))

(deftest a-document-says-which-folder-it-is-in
  ;; So a listing can be scoped to one without asking the server per item.
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            inside (:item (documents/create! :docs "中" alice object-store
                                             {:folder (:id work)}))
            outside (:item (documents/create! :docs "外" alice object-store))
            by-id (into {} (map (juxt :id identity)) (documents/documents @state alice))]
        (is (= (:id work) (:parent-id (get by-id (:id inside)))))
        (is (= "root" (:parent-id (get by-id (:id outside)))))
        ;; A document shared from someone else's Drive has no folder in
        ;; this one — naming theirs would put an id in a breadcrumb that
        ;; goes nowhere.
        (documents/grant! (:id inside) bob "viewer" alice)
        (is (nil? (:parent-id (first (documents/documents @state bob)))))))))

(deftest the-move-picker-offers-every-folder-by-path
  ;; A move is a choice among all folders, not among the ones you are
  ;; standing in — and two folders called Q1 are ordinary, so a picker
  ;; showing both as "Q1" would ask an unanswerable question.
  (with-state
    (fn [_ _]
      (let [a (:item (documents/create-folder! "営業" alice))
            b (:item (documents/create-folder! "開発" alice))]
        (documents/create-folder! "Q1" alice (:id a))
        (documents/create-folder! "Q1" alice (:id b))
        (let [all (:all (documents/folders (store/snapshot) alice nil))]
          (is (= ["My Drive" "My Drive / 営業" "My Drive / 営業 / Q1"
                  "My Drive / 開発" "My Drive / 開発 / Q1"]
                 (mapv :name all)))
          (is (= 5 (count (distinct (map :id all))))))
        ;; A trashed folder is not a destination.
        (documents/trash! (:id a) alice)
        (is (= ["My Drive" "My Drive / 開発" "My Drive / 開発 / Q1"]
               (mapv :name (:all (documents/folders (store/snapshot) alice nil)))))))))

(deftest a-shared-folder-is-somewhere-you-can-go
  ;; The capability to create in a shared folder is only worth having if
  ;; something can reach it. Listed at the top level, because a folder from
  ;; another Drive is not inside anything in this one.
  (with-state
    (fn [_ object-store]
      (let [work (:item (documents/create-folder! "仕事" alice))
            q1 (:item (documents/create-folder! "Q1" alice (:id work)))]
        (documents/grant! (:id work) bob "editor" alice)
        (let [top (documents/folders (store/snapshot) bob nil)]
          (is (= ["仕事"] (mapv :name (:shared top))))
          (is (= [] (:folders top)) "bob has no folders of his own")
          ;; A subfolder of a shared folder is not a second entry at the top
          ;; — it is reached by opening the one above it.
          (is (= ["Q1"] (mapv :name (:folders (documents/folders (store/snapshot)
                                                                 bob (:id work))))))
          ;; And the breadcrumb reads through the other Drive rather than
          ;; stopping at its edge.
          (is (= ["My Drive" "仕事" "Q1"]
                 (mapv :name (:path (documents/folders (store/snapshot) bob (:id q1)))))))
        ;; The move picker offers it too, own folders first.
        (documents/create-folder! "自分の" bob)
        (is (= ["My Drive" "My Drive / 自分の" "My Drive / 仕事" "My Drive / 仕事 / Q1"]
               (mapv :name (:all (documents/folders (store/snapshot) bob nil)))))
        (is (= [true true false false]
               (mapv :own? (:all (documents/folders (store/snapshot) bob nil)))))))))

(deftest a-folder-nobody-shared-is-not-listed
  (with-state
    (fn [_ _]
      (documents/create-folder! "私用" alice)
      (let [top (documents/folders (store/snapshot) bob nil)]
        (is (empty? (:shared top)))
        (is (= ["My Drive"] (mapv :name (:all top))) "only bob's own root")))))

(deftest a-listing-says-whose-drive-you-are-standing-in
  ;; Creating here puts the document in somebody else's Drive and against
  ;; their quota. That is the one consequence of this feature a person
  ;; should not discover afterwards, so the response carries it.
  (with-state
    (fn [_ _]
      (let [work (:item (documents/create-folder! "仕事" alice))]
        (documents/grant! (:id work) bob "editor" alice)
        (let [own (documents/folders (store/snapshot) bob nil)
              theirs (documents/folders (store/snapshot) bob (:id work))]
          (is (= bob (:owner own)) "your own root is yours")
          (is (= bob (:you own)))
          (is (= alice (:owner theirs)))
          (is (= bob (:you theirs))))))))

(deftest a-document-leaves-as-docx
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "議事録" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc doc :docs/blocks
                            [{:docs/id "h" :docs/kind :heading :docs/level 1
                              :docs/text "議事録"}
                             {:docs/id "p" :docs/kind :paragraph :docs/text "出席者は3名。"}
                             {:docs/id "l" :docs/kind :list :docs/ordered? true
                              :docs/items ["予算の確認" "次回日程"]}
                             {:docs/id "t" :docs/kind :table
                              :docs/rows [["項目" "状態"] ["設計" "完了"]]}])
                     alice object-store)
            out (documents/export (:id item) "docx" alice object-store)
            entries (docs-docx/docx-entries (:bytes out))]
        (is (= "議事録.docx" (:filename out)))
        (is (str/starts-with? (:media-type out)
                              "application/vnd.openxmlformats-officedocument."))
        ;; A real package, not a file with the right name.
        (is (contains? entries "word/document.xml"))
        (is (contains? entries "word/styles.xml"))
        (is (contains? entries "word/numbering.xml"))
        ;; Structure, not appearance — the whole reason for the format.
        (is (str/includes? (get entries "word/document.xml") "w:pStyle w:val=\"Heading1\""))
        (is (str/includes? (get entries "word/document.xml") "<w:numPr>"))
        (is (str/includes? (get entries "word/document.xml") "<w:tbl>"))))))

(deftest a-docx-comes-back-in
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "元" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc doc :docs/blocks
                            [{:docs/id "h" :docs/kind :heading :docs/level 1
                              :docs/text "週報"}
                             {:docs/id "p" :docs/kind :paragraph :docs/text "今週の進捗。"}
                             {:docs/id "q" :docs/kind :quote :docs/text "来週締切。"}])
                     alice object-store)
            bytes (:bytes (documents/export (:id item) "docx" alice object-store))
            imported (:item (documents/import! "docx" "取り込み" bytes alice object-store))
            back (:resource (documents/content (:id imported) alice object-store))]
        (is (= ":docs/document" (:resource-kind imported)))
        (is (= [:heading :paragraph :quote] (mapv :docs/kind (:docs/blocks back))))
        (is (= "週報" (:docs/text (first (:docs/blocks back)))))
        (is (= "来週締切。" (:docs/text (last (:docs/blocks back)))))))))

(deftest bytes-that-are-not-a-docx-are-refused
  ;; `docs.docx/read` answers an empty document for anything it cannot
  ;; parse, which is right for a reader and wrong for an import: an empty
  ;; document is indistinguishable from a working import of an empty file.
  ;; The package is what can be asked.
  (with-state
    (fn [state object-store]
      (doseq [junk [(.getBytes "x" "UTF-8")
                    ;; A real zip, with nothing Word would recognise in it.
                    (let [out (java.io.ByteArrayOutputStream.)]
                      (with-open [zip (java.util.zip.ZipOutputStream. out)]
                        (.putNextEntry zip (java.util.zip.ZipEntry. "hello.txt"))
                        (.write zip (.getBytes "hi" "UTF-8"))
                        (.closeEntry zip))
                      (.toByteArray out))]]
        (is (= :drive/unsupported-format
               (:type (try (documents/import! "docx" "壊れ" junk alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
      (is (empty? (documents/documents @state alice)) "and nothing was created"))))

;; ── make a copy ─────────────────────────────────────────────────────────────

(deftest a-copy-is-a-new-document-with-the-same-contents
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc doc :docs/blocks
                            [{:docs/id "p" :docs/kind :paragraph :docs/text "本文"}])
                     alice object-store)
            copy (:item (documents/copy! (:id item) alice object-store))
            back (:resource (documents/content (:id copy) alice object-store))]
        (is (= "設計 のコピー" (:name copy)))
        (is (= ":docs/document" (:resource-kind copy)))
        (is (not= (:id item) (:id copy)))
        (is (= [{:docs/id "p" :docs/kind :paragraph :docs/text "本文"}]
               (:docs/blocks back)))
        ;; The resource knows its own new id and title, rather than still
        ;; saying it is the document it came from.
        (is (= (:id copy) (:docs/id back)))
        (is (= "設計 のコピー" (:docs/title back)))
        (is (= 2 (count (documents/documents @state alice))))))))

(deftest a-reader-of-a-shared-document-can-take-their-own-copy
  ;; The reason this operation exists. Until now the only way to get an
  ;; editable version of something shared read-only was export then import —
  ;; two steps, through bytes, losing the kind if the surface has no office
  ;; format.
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)]
        (documents/grant! (:id item) bob "viewer" alice)
        (let [copy (:item (documents/copy! (:id item) bob object-store))]
          (is (= bob (:owner copy)) "in bob's Drive")
          (is (:own? copy))
          (is (= "owner" (:role copy)))
          (is (:writable? copy) "and editable, which the original was not")
          ;; alice's Drive is unchanged; the copy is not in it.
          (is (= [(:id item)] (mapv :id (documents/documents @state alice))))
          (is (= #{(:id item) (:id copy)}
                 (set (mapv :id (documents/documents @state bob))))))))))

(deftest a-copy-is-not-shared
  ;; Copying a document shared with five people must not share the copy with
  ;; them. It falls out of `create!` giving the creator :owner and nobody
  ;; anything — asserted anyway, because getting it wrong is a silent access
  ;; leak rather than a visible fault.
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/grant! (:id item) bob "editor" alice)
        (let [copy (:item (documents/copy! (:id item) alice object-store))]
          (is (= [] (:grants (documents/sharing (:id copy) alice))))
          ;; bob sees the original and not the copy.
          (is (= [(:id item)] (mapv :id (documents/documents @state bob))))
          (is (= :drive/not-found
                 (:type (try (documents/content (:id copy) bob object-store)
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))))

(deftest a-copy-carries-no-comments-and-no-responses
  ;; They are about the document somebody said them about. A copy with its
  ;; original's threads would put words into a conversation that did not
  ;; happen.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (documents/comment! (:id item) "ここを直して" {:block "p"} alice)
        (let [copy (:item (documents/copy! (:id item) alice object-store))]
          (is (= 1 (count (:comments (documents/comments (:id item) alice)))))
          (is (= [] (:comments (documents/comments (:id copy) alice))))))
      (let [form (:item (documents/create! :forms "問い合わせ" alice object-store))]
        (documents/submit! (:id form) {} alice object-store)
        (let [copy (:item (documents/copy! (:id form) alice object-store))]
          (is (= 1 (count (:submissions (documents/submissions (:id form) alice)))))
          (is (= [] (:submissions (documents/submissions (:id copy) alice)))))))))

(deftest a-copy-starts-its-own-history
  ;; A copy is not a fork of the past; the original still has all of it.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))]
        (save! (:id item) (assoc doc :docs/title "設計 v2") alice object-store)
        (save! (:id item) (assoc doc :docs/title "設計 v3") alice object-store)
        (let [copy (:item (documents/copy! (:id item) alice object-store))]
          (is (= 3 (count (:versions (documents/history (:id item) alice)))))
          (is (= 1 (count (:versions (documents/history (:id copy) alice))))))))))

(deftest a-copy-is-charged-to-whoever-made-it
  ;; Unlike editing a shared document, which is charged to the owner because
  ;; the bytes stay in their Drive. Here the bytes are new and in the
  ;; copier's.
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            alice-before (:used-bytes (documents/quota-view @state alice))]
        (documents/grant! (:id item) bob "viewer" alice)
        (documents/copy! (:id item) bob object-store)
        (is (= alice-before (:used-bytes (documents/quota-view (store/snapshot) alice))))
        (is (pos? (:used-bytes (documents/quota-view (store/snapshot) bob))))))))

(deftest a-copy-can-be-named-and-filed
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            work (:item (documents/create-folder! "仕事" alice))
            copy (:item (documents/copy! (:id item) alice object-store
                                         {:title "設計 2026年版" :folder (:id work)}))]
        (is (= "設計 2026年版" (:name copy)))
        (is (= (:id work) (:parent-id copy)))))))

(deftest every-surface-can-be-copied
  (with-state
    (fn [_ object-store]
      (doseq [kind [:sheets :docs :forms :slides]]
        (let [{:keys [item]} (documents/create! kind "元" alice object-store)
              copy (:item (documents/copy! (:id item) alice object-store))
              back (:resource (documents/content (:id copy) alice object-store))
              spec (get documents/kinds kind)]
          (is (= (:resource-kind item) (:resource-kind copy)) (str kind))
          ;; Each surface's own id and title keys, from the kinds table
          ;; rather than from a guess about which one this is.
          (is (= (:id copy) (get back (:id-key spec))) (str kind))
          (is (= "元 のコピー" (get back (:title-key spec))) (str kind)))))))

(deftest a-document-you-cannot-read-cannot-be-copied
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "私用" alice object-store)]
        (is (= :drive/not-found
               (:type (try (documents/copy! (:id item) bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-created-document-is-validated-like-a-saved-one
  ;; `create!` now takes the contents, which means it takes contents that
  ;; can be wrong. Before `resource-fn` existed it only ever produced a seed
  ;; and validating one would have been checking the file against itself; a
  ;; copy or an import arrives whole. Leaving the check out let a broken
  ;; .edn import succeed — silent in the direction that looks like success,
  ;; which is how it was found.
  (with-state
    (fn [state object-store]
      ;; The same fixture the import test uses — a deck whose slides are not
      ;; a list — rather than one invented here, because a fabricated
      ;; "invalid" document that the validator happens to accept makes this
      ;; test pass for the wrong reason. It did, the first time.
      (let [broken (pr-str {:kotoba.protocol/family :kotoba.protocol/office
                            :kotoba.protocol/version 1
                            :kotoba.resource/kind :slides/deck
                            :kotoba.resource/payload {:slides/id "d"
                                                      :slides/kind :slides/deck
                                                      :slides/title "壊れ"
                                                      :slides/slides "nope"}})]
        (is (= :drive/invalid-document
               (:type (try (documents/import! "edn" "壊れ" (.getBytes broken "UTF-8")
                                              alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (empty? (documents/documents @state alice))
            "and nothing was created before the check"))))) 

(deftest a-copy-has-one-version-not-two
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            copy (:item (documents/copy! (:id item) alice object-store))]
        (is (= 1 (:versions copy)))
        ;; And that version is the contents, not an empty document.
        (is (= 1 (count (:versions (documents/history (:id copy) alice)))))))))

;; ── files that are not documents ────────────────────────────────────────────

(defn- pdf-bytes [text]
  (.getBytes (str "%PDF-1.4\n" text "\n%%EOF") "UTF-8"))

(deftest a-pdf-can-live-in-the-drive
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/upload! "見積.pdf" "application/pdf"
                                              (pdf-bytes "quote") alice object-store)]
        (is (= "見積.pdf" (:name item)))
        (is (:file? item) "not one of the four surfaces")
        (is (= "file" (:kind item)))
        (is (= "ファイル" (:label item)))
        (is (nil? (:resource-kind item)))
        (is (= "application/pdf" (:media-type item)))
        (is (= 1 (count (documents/documents @state alice))))
        (let [back (documents/file-bytes (:id item) alice object-store)]
          (is (= "見積.pdf" (:filename back)))
          (is (= (seq (pdf-bytes "quote")) (seq (:bytes back)))))))))

(deftest the-reference-is-the-content
  ;; A PieceCID, so the same bytes are stored once and named by what they
  ;; are. Two uploads of one file are two items over one object.
  (with-state
    (fn [_ object-store]
      (let [bytes (pdf-bytes "same")
            a (:item (documents/upload! "a.pdf" "application/pdf" bytes alice
                                        object-store))
            b (:item (documents/upload! "b.pdf" "application/pdf" bytes alice
                                        object-store))
            c (:item (documents/upload! "c.pdf" "application/pdf"
                                        (pdf-bytes "different") alice object-store))]
        (is (= (:etag a) (:etag b)) "same bytes, same reference")
        (is (not= (:etag a) (:etag c)))
        ;; And the reference really is derived from the content, not minted.
        (is (= (filecoin/piece-ref bytes) (:etag a)))))))

(deftest purging-one-holder-does-not-delete-the-other-s-bytes
  ;; The failure content addressing makes possible: two items over one
  ;; object, and deleting either destroys both. It would surface much later,
  ;; as a download that used to work.
  (with-state
    (fn [_ object-store]
      (let [bytes (pdf-bytes "shared")
            a (:item (documents/upload! "a.pdf" "application/pdf" bytes alice
                                        object-store))
            b (:item (documents/upload! "b.pdf" "application/pdf" bytes alice
                                        object-store))]
        (documents/trash! (:id a) alice)
        (let [{:keys [freed-bytes]} (documents/purge! (:id a) alice object-store)]
          (is (pos? freed-bytes) "the quota still comes back"))
        ;; b still reads.
        (is (= (seq bytes) (seq (:bytes (documents/file-bytes (:id b) alice
                                                              object-store)))))
        ;; And once the last holder goes, so do the bytes.
        (documents/trash! (:id b) alice)
        (documents/purge! (:id b) alice object-store)
        (is (= :drive/not-found
               (:type (try (documents/file-bytes (:id b) alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest another-drive-holding-the-same-bytes-counts-too
  ;; The other holder may be somebody else entirely. A check scoped to one
  ;; Drive would be correct exactly until two people uploaded the same file,
  ;; which is the case content addressing exists for.
  (with-state
    (fn [_ object-store]
      (let [bytes (pdf-bytes "everyone has this")
            mine (:item (documents/upload! "a.pdf" "application/pdf" bytes alice
                                           object-store))
            theirs (:item (documents/upload! "a.pdf" "application/pdf" bytes bob
                                             object-store))]
        (is (= (:etag mine) (:etag theirs)))
        (documents/trash! (:id mine) alice)
        (documents/purge! (:id mine) alice object-store)
        (is (= (seq bytes) (seq (:bytes (documents/file-bytes (:id theirs) bob
                                                              object-store)))))))))

(deftest a-file-is-not-a-document-and-says-so
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/upload! "見積.pdf" "application/pdf"
                                              (pdf-bytes "q") alice object-store)]
        ;; No envelope, so `content` has nothing to give. Without the guard
        ;; the bytes reach the EDN/JSON reader and the caller gets
        ;; "unexpected character: %" as a 500 — a parse error standing in
        ;; for "that is not a document".
        (is (= :drive/not-a-document
               (:type (try (documents/content (:id item) alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        ;; And a document is not a file.
        (let [doc (:item (documents/create! :docs "設計" alice object-store))]
          (is (= :drive/not-a-file
                 (:type (try (documents/file-bytes (:id doc) alice object-store)
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))))

(deftest an-empty-upload-is-refused
  ;; Its PieceCID would be the CID of nothing, shared by every empty upload
  ;; anyone ever makes — an item pointing at a shared object holding no
  ;; content, which is a row that can only confuse.
  (with-state
    (fn [_ object-store]
      (is (= :drive/invalid-document
             (:type (try (documents/upload! "空.pdf" "application/pdf"
                                            (byte-array 0) alice object-store)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

(deftest a-file-can-be-filed-shared-and-trashed-like-anything-else
  (with-state
    (fn [state object-store]
      (let [work (:item (documents/create-folder! "資料" alice))
            {:keys [item]} (documents/upload! "見積.pdf" "application/pdf"
                                              (pdf-bytes "q") alice object-store
                                              {:folder (:id work)})]
        (is (= (:id work) (:parent-id item)))
        (documents/grant! (:id item) bob "viewer" alice)
        (is (= [(:id item)] (mapv :id (documents/documents @state bob))))
        (is (= (seq (pdf-bytes "q"))
               (seq (:bytes (documents/file-bytes (:id item) bob object-store)))))
        (documents/trash! (:id item) alice)
        (is (empty? (documents/documents @state alice)))))))

(deftest an-image-may-be-shown-and-a-document-may-not
  ;; Raster image formats cannot carry script — there is no execution
  ;; context in a PNG — so serving one inline from this origin is safe in a
  ;; way that serving anything else is not.
  (with-state
    (fn [_ object-store]
      (let [png (:item (documents/upload! "写真.png" "image/png"
                                          (.getBytes "not really a png" "UTF-8")
                                          alice object-store))
            pdf (:item (documents/upload! "見積.pdf" "application/pdf"
                                          (pdf-bytes "q") alice object-store))]
        (is (:previewable? png))
        (is (not (:previewable? pdf)))
        ;; The response is labelled with the declared type only for the ones
        ;; on the list; everything else is octet-stream.
        (is (= "image/png" (:media-type (documents/file-bytes (:id png) alice
                                                              object-store))))
        (is (:inline? (documents/file-bytes (:id png) alice object-store)))
        (is (= "application/octet-stream"
               (:media-type (documents/file-bytes (:id pdf) alice object-store))))
        (is (not (:inline? (documents/file-bytes (:id pdf) alice object-store))))))))

(deftest svg-is-not-an-image-for-this-purpose
  ;; It is an image everywhere except in the way that matters: it is XML, it
  ;; may contain <script>, and a browser runs that when the SVG is a
  ;; document rather than an <img> source. Allowing it because it is "an
  ;; image" is the mistake the allowlist exists to not make.
  (with-state
    (fn [_ object-store]
      (doseq [claimed ["image/svg+xml" "text/html" "application/xhtml+xml"
                       "text/xml" "image/svg" "IMAGE/PNG " ""]]
        (let [{:keys [item]} (documents/upload! "x" claimed
                                                (.getBytes "<svg/>" "UTF-8")
                                                alice object-store)]
          (is (not (:previewable? item)) (pr-str claimed))
          (is (= "application/octet-stream"
                 (:media-type (documents/file-bytes (:id item) alice object-store)))
              (pr-str claimed)))))))

(deftest a-document-is-never-previewable
  ;; It has a resource kind, so it is not a file at all — and the pane must
  ;; not put an <img> on a spreadsheet.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)]
        (is (not (:previewable? item)))
        (is (not (:file? item)))))))

(deftest a-file-a-viewer-may-read-is-one-they-may-preview
  ;; The preview goes through `file-bytes`, so it goes through the same ACL
  ;; as the download rather than being a second way in.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/upload! "写真.png" "image/png"
                                              (.getBytes "png" "UTF-8")
                                              alice object-store)]
        (is (= :drive/not-found
               (:type (try (documents/file-bytes (:id item) bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (documents/grant! (:id item) bob "viewer" alice)
        (is (:inline? (documents/file-bytes (:id item) bob object-store)))))))

;; ── searching twice ─────────────────────────────────────────────────────────

(defn- counting-store
  "An object store that records how many reads it served.

  Measured rather than asserted: a cache whose benefit is described in a
  comment is a cache nobody can tell is working."
  [inner]
  (let [reads (atom 0)]
    {:reads reads
     :store (reify drive.object/IObjectStore
              (-get-object [_ ref] (swap! reads inc)
                (drive.object/-get-object inner ref))
              (-put-object [_ ref bytes] (drive.object/-put-object inner ref bytes))
              (-delete-object [_ ref] (drive.object/-delete-object inner ref))
              (-object-exists? [_ ref] (drive.object/-object-exists? inner ref)))}))

(deftest a-second-search-does-not-read-the-bytes-again
  (with-state
    (fn [_ object-store]
      (let [{:keys [reads store]} (counting-store object-store)]
        (doseq [n (range 5)]
          (let [{:keys [item]} (documents/create! :docs (str "文書" n) alice store)
                doc (:resource (documents/content (:id item) alice store))]
            (save! (:id item)
                   (assoc doc :docs/blocks
                          [{:docs/id "p" :docs/kind :paragraph
                            :docs/text (str "共通の語 " n)}])
                   alice store)))
        (reset! reads 0)
        (documents/search "共通の語" alice store)
        (let [first-pass @reads]
          (is (pos? first-pass) "the first search reads them")
          (reset! reads 0)
          (documents/search "共通の語" alice store)
          (is (zero? @reads)
              (str "the second reads none; the first read " first-pass)))))))

(deftest an-edited-document-is-read-again
  ;; The invalidation that needs no invalidating: a save is a new object
  ;; reference, so it is a new cache entry and the old one is simply never
  ;; asked for again.
  (with-state
    (fn [_ object-store]
      (let [{:keys [reads store]} (counting-store object-store)
            {:keys [item]} (documents/create! :docs "設計" alice store)
            doc (:resource (documents/content (:id item) alice store))]
        (save! (:id item)
               (assoc doc :docs/blocks
                      [{:docs/id "p" :docs/kind :paragraph :docs/text "最初の本文"}])
               alice store)
        (is (= 1 (count (:results (documents/search "最初の本文" alice store)))))
        (save! (:id item)
               (assoc doc :docs/blocks
                      [{:docs/id "p" :docs/kind :paragraph :docs/text "書き直した本文"}])
               alice store)
        (reset! reads 0)
        ;; The new text is found…
        (is (= 1 (count (:results (documents/search "書き直した本文" alice store)))))
        (is (pos? @reads) "which required reading the new version")
        ;; …and the old text is not, which is what a stale cache would get
        ;; wrong.
        (is (= 0 (count (:results (documents/search "最初の本文" alice store)))))))))

(deftest a-restored-version-is-found-by-its-own-text
  ;; Restoring writes the old content as a *new* version with a new
  ;; reference, so it caches as a new entry rather than colliding with the
  ;; one it came from.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            doc (:resource (documents/content (:id item) alice object-store))]
        (save! (:id item)
               (assoc doc :docs/blocks
                      [{:docs/id "p" :docs/kind :paragraph :docs/text "版1の語"}])
               alice object-store)
        (save! (:id item)
               (assoc doc :docs/blocks
                      [{:docs/id "p" :docs/kind :paragraph :docs/text "版2の語"}])
               alice object-store)
        (is (= 1 (count (:results (documents/search "版2の語" alice object-store)))))
        (documents/restore-version!
         (:id item) 2 alice
         (:etag (:item (documents/content (:id item) alice object-store)))
         object-store)
        (is (= 1 (count (:results (documents/search "版1の語" alice object-store)))))
        (is (= 0 (count (:results (documents/search "版2の語" alice object-store)))))))))

(defn- documents-with-content
  "`n` documents whose text does not appear in their titles.

  A search whose needle matches the title never reads the bytes at all —
  `in-title?` answers first — so a fixture named after what you search for
  measures nothing, cache or no cache. That is how the first version of the
  test below passed while proving nothing."
  [n actor store]
  (doseq [i (range n)]
    (let [{:keys [item]} (documents/create! :docs (str "資料" i) actor store)
          doc (:resource (documents/content (:id item) actor store))]
      (save! (:id item)
             (assoc doc :docs/blocks
                    [{:docs/id "p" :docs/kind :paragraph
                      :docs/text (str "本文にだけある語 " i)}])
             actor store))))

(deftest the-cache-is-bounded
  ;; Otherwise it is a memory leak that grows with every version anyone ever
  ;; saves. Pinned behaviourally — with a limit smaller than the Drive, a
  ;; second search is *not* free, which is what a bound means and what an
  ;; unbounded cache would get wrong.
  (with-state
    (fn [_ object-store]
      (with-redefs [documents/text-cache-limit 4]
        (let [{:keys [reads store]} (counting-store object-store)]
          (documents-with-content 12 alice store)
          (documents/search "本文にだけある語" alice store)
          (reset! reads 0)
          (documents/search "本文にだけある語" alice store)
          (is (pos? @reads)
              "twelve documents do not fit in four, so some were read again")))))
  ;; And at a size inside the limit, nothing is read twice — the bound is a
  ;; ceiling, not the behaviour.
  (with-state
    (fn [_ object-store]
      (let [{:keys [reads store]} (counting-store object-store)]
        (documents-with-content 12 alice store)
        (documents/search "本文にだけある語" alice store)
        (reset! reads 0)
        (documents/search "本文にだけある語" alice store)
        (is (zero? @reads))))))

;; ── suggestions ─────────────────────────────────────────────────────────────

(defn- memo-with-paragraph [object-store text]
  (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
        doc (:resource (documents/content (:id item) alice object-store))]
    (save! (:id item)
           (assoc doc :docs/blocks
                  [{:docs/id "p1" :docs/kind :paragraph :docs/text text}])
           alice object-store)
    item))

(deftest a-commenter-can-propose-a-change-they-cannot-make
  ;; The whole of suggestion mode. `drive.workspace` says a commenter may
  ;; not write, and being able to say what should change without being able
  ;; to change it is the point rather than a limitation.
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "commenter" alice)
        ;; bob cannot save…
        (is (thrown? clojure.lang.ExceptionInfo
                     (save! (:id item) {:docs/id "x"} bob object-store)))
        ;; …and can propose.
        (let [{:keys [suggestion]} (documents/suggest! (:id item) "p1" "直した本文"
                                                       bob object-store)]
          (is (= bob (:author suggestion)))
          (is (= "open" (:status suggestion)))
          (is (= "元の本文" (:before suggestion)) "what it said when proposed"))
        ;; The document is untouched until somebody accepts.
        (is (= "元の本文"
               (:docs/text (first (:docs/blocks
                                   (:resource (documents/content (:id item) alice
                                                                 object-store)))))))))))

(deftest accepting-a-suggestion-is-a-new-version-authored-by-whoever-accepted
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "commenter" alice)
        (let [sug (:suggestion (documents/suggest! (:id item) "p1" "直した本文" bob
                                                   object-store))]
          (documents/accept-suggestion! (:id item) (:id sug) alice object-store)
          (let [back (documents/content (:id item) alice object-store)]
            (is (= "直した本文" (:docs/text (first (:docs/blocks (:resource back))))))
            ;; They made this version; the suggestion still says who
            ;; proposed it — the same rule restoring a version follows.
            (is (= alice (:updated-by (:item back))))
            (is (= bob (:author (first (:suggestions
                                        (documents/suggestions (:id item) alice
                                                               object-store)))))))
          (is (= "accepted"
                 (:status (first (:suggestions (documents/suggestions
                                                (:id item) alice object-store)))))))))))

(deftest a-suggestion-about-a-sentence-that-has-changed-is-refused
  ;; The lost update, arriving through a different door. bob proposes a
  ;; change to a paragraph; alice rewrites that paragraph herself; applying
  ;; bob's proposal now would discard her rewrite without anybody seeing it.
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "commenter" alice)
        (let [sug (:suggestion (documents/suggest! (:id item) "p1" "bob の案" bob
                                                   object-store))
              doc (:resource (documents/content (:id item) alice object-store))]
          (save! (:id item)
                 (assoc doc :docs/blocks
                        [{:docs/id "p1" :docs/kind :paragraph :docs/text "alice の書き直し"}])
                 alice object-store)
          ;; It is reported as stale before anybody tries.
          (let [listed (first (:suggestions (documents/suggestions (:id item) alice
                                                                   object-store)))]
            (is (:stale? listed))
            (is (= "alice の書き直し" (:current listed))))
          (let [error (try (documents/accept-suggestion! (:id item) (:id sug) alice
                                                         object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= :drive/suggestion-stale (:type error)))
            (is (= "元の本文" (:was error)))
            (is (= "alice の書き直し" (:now error))))
          ;; And alice's text is still there.
          (is (= "alice の書き直し"
                 (:docs/text (first (:docs/blocks
                                     (:resource (documents/content (:id item) alice
                                                                   object-store))))))))))))

(deftest a-settled-suggestion-cannot-be-settled-again
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")
            sug (:suggestion (documents/suggest! (:id item) "p1" "案" alice object-store))]
        (documents/accept-suggestion! (:id item) (:id sug) alice object-store)
        (is (= :drive/suggestion-settled
               (:type (try (documents/accept-suggestion! (:id item) (:id sug) alice
                                                         object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (= :drive/suggestion-settled
               (:type (try (documents/reject-suggestion! (:id item) (:id sug) alice)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest declining-and-withdrawing-are-the-same-operation
  ;; A writer may decline anyone's; the person who made one may take it
  ;; back. Refusing the second would leave a mistake on the page with no way
  ;; to remove it.
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "commenter" alice)
        (let [mine (:suggestion (documents/suggest! (:id item) "p1" "案1" bob
                                                    object-store))]
          (is (:ok? (documents/reject-suggestion! (:id item) (:id mine) bob))))
        (let [theirs (:suggestion (documents/suggest! (:id item) "p1" "案2" bob
                                                      object-store))]
          (is (:ok? (documents/reject-suggestion! (:id item) (:id theirs) alice))))
        ;; Nothing was written to the document by either.
        (is (= "元の本文"
               (:docs/text (first (:docs/blocks
                                   (:resource (documents/content (:id item) alice
                                                                 object-store)))))))))))

(deftest only-a-writer-may-accept
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "commenter" alice)
        (let [sug (:suggestion (documents/suggest! (:id item) "p1" "案" bob
                                                   object-store))]
          ;; A commenter accepting their own proposal would be writing.
          (is (= :drive/not-permitted
                 (:type (try (documents/accept-suggestion! (:id item) (:id sug) bob
                                                           object-store)
                             (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))))

(deftest a-stranger-may-not-propose
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (is (= :drive/not-found
               (:type (try (documents/suggest! (:id item) "p1" "案" bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-proposal-must-be-about-a-block-that-exists
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (is (= :drive/not-found
               (:type (try (documents/suggest! (:id item) "nope" "案" alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (= :drive/invalid-suggestion
               (:type (try (documents/suggest! (:id item) "p1" "   " alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest only-a-document-takes-suggestions
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)]
        (is (= :drive/not-a-document
               (:type (try (documents/suggest! (:id item) "A1" "案" alice object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-viewer-may-not-propose-either
  ;; The line `comment-roles` already draws: a viewer is someone the
  ;; document was shown to, and annotating it is more than being shown it.
  ;; Not a new rule for suggestions — the same one.
  (with-state
    (fn [_ object-store]
      (let [item (memo-with-paragraph object-store "元の本文")]
        (documents/grant! (:id item) bob "viewer" alice)
        (is (= :drive/not-permitted
               (:type (try (documents/suggest! (:id item) "p1" "案" bob object-store)
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        ;; And they can still read it and what has been proposed for it.
        (is (some? (documents/suggestions (:id item) bob object-store)))))))

(deftest a-workbook-says-what-its-formulas-come-to
  ;; Alongside the resource rather than inside it: the payload is what a
  ;; save sends back, and a computed value in there would return as
  ;; something somebody typed.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            tab-id (first (keys (:sheets/tabs wb)))
            _ (save! (:id item)
                     (assoc-in wb [:sheets/tabs tab-id :sheets/cells]
                               {[1 1] {:sheets/value "1200"}
                                [2 1] {:sheets/value "1300"}
                                [3 1] {:sheets/formula "SUM(A1:A2)"}
                                [4 1] {:sheets/formula "A1/0"}})
                     alice object-store)
            back (documents/content (:id item) alice object-store)]
        (is (= "2500" (get-in back [:computed tab-id "[3 1]"])))
        (is (= "#DIV/0!" (get-in back [:computed tab-id "[4 1]"])))
        (is (= "1200" (get-in back [:computed tab-id "[1 1]"])))
        ;; And the stored resource still holds the formula, not the answer.
        (is (= "SUM(A1:A2)"
               (get-in (:resource back) [:sheets/tabs tab-id :sheets/cells [3 1]
                                         :sheets/formula])))
        (is (nil? (get-in (:resource back) [:sheets/tabs tab-id :sheets/cells [3 1]
                                            :sheets/value])))))))

(deftest only-a-workbook-is-asked-what-it-comes-to
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)]
        (is (nil? (:computed (documents/content (:id item) alice object-store))))))))

(deftest a-guarded-division-does-not-become-the-error-it-guards
  ;; IF is the standard way to avoid #DIV/0!, and evaluating both branches
  ;; made the guard produce the error it exists to avoid. Pinned here as
  ;; well as in the library, because this is the path a person's spreadsheet
  ;; actually takes.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            tab-id (first (keys (:sheets/tabs wb)))
            _ (save! (:id item)
                     (assoc-in wb [:sheets/tabs tab-id :sheets/cells]
                               {[1 1] {:sheets/value "0"}
                                [1 2] {:sheets/value "1200"}
                                [2 1] {:sheets/formula "IF(A1=0,\"未入力\",100/A1)"}
                                [2 2] {:sheets/formula "SUMIF(A1:A1,\">-1\",B1:B1)"}})
                     alice object-store)
            computed (:computed (documents/content (:id item) alice object-store))]
        (is (= "未入力" (get-in computed [tab-id "[2 1]"])))
        (is (= "1200" (get-in computed [tab-id "[2 2]"])))))))

(deftest a-named-range-works-through-the-drive
  ;; Evaluating tab by tab would make =SUM(売上) a #NAME? on every sheet that
  ;; uses one, because a name belongs to the workbook and a tab does not
  ;; know which workbook it is in.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            tab-id (first (keys (:sheets/tabs wb)))
            title (get-in wb [:sheets/tabs tab-id :sheets/title])
            _ (save! (:id item)
                     (-> wb
                         (assoc-in [:sheets/tabs tab-id :sheets/cells]
                                   {[1 1] {:sheets/value "1200"}
                                    [2 1] {:sheets/value "1300"}
                                    [3 1] {:sheets/formula "SUM(売上)"}})
                         (assoc :sheets/named-ranges
                                {"売上" {:sheets/id "売上" :sheets/tab title
                                         :sheets/range "A1:A2"}}))
                     alice object-store)
            back (documents/content (:id item) alice object-store)]
        (is (= "2500" (get-in back [:computed tab-id "[3 1]"])))
        ;; And the export writes the name rather than dropping it, so it is
        ;; no longer reported as a loss.
        (is (nil? (get (:export-warnings back) "xlsx")))
        (let [xlsx (String. ^bytes (:bytes (documents/export (:id item) "xlsx" alice
                                                             object-store))
                            "UTF-8")]
          ;; The bytes are a zip, so this only checks it did not throw; the
          ;; XML itself is asserted in sheets.
          (is (pos? (count xlsx))))))))

;; ── more than one tab ───────────────────────────────────────────────────────

(defn- two-tab-workbook [object-store]
  (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
        wb (:resource (documents/content (:id item) alice object-store))
        first-id (first (keys (:sheets/tabs wb)))]
    (save! (:id item)
           (-> wb
               (assoc-in [:sheets/tabs first-id :sheets/title] "上期")
               (assoc-in [:sheets/tabs first-id :sheets/cells]
                         {[1 1] {:sheets/value "1200"}
                          [2 1] {:sheets/formula "A1*2"}})
               (assoc-in [:sheets/tabs "sheet2"]
                         {:sheets/id "sheet2" :sheets/title "下期"
                          :sheets/cells {[1 1] {:sheets/value "1500"}
                                         [2 1] {:sheets/formula "SUM(A1:A1)"}}}))
           alice object-store)
    {:item item :first-id first-id}))

(deftest a-workbook-can-have-more-than-one-tab
  ;; Every other surface could add to itself — a question, a paragraph, a
  ;; slide — and a workbook was stuck with the tab it was created with
  ;; unless somebody hand-edited JSON. This is the server half: a two-tab
  ;; workbook has to survive the validator, the evaluator and both writers.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item first-id]} (two-tab-workbook object-store)
            back (documents/content (:id item) alice object-store)]
        (is (= #{first-id "sheet2"} (set (keys (:sheets/tabs (:resource back))))))
        (is (= "上期" (get-in back [:resource :sheets/tabs first-id :sheets/title])))
        ;; Each tab computes against its own cells, not against the first.
        (is (= "2400" (get-in back [:computed first-id "[2 1]"])))
        (is (= "1500" (get-in back [:computed "sheet2" "[2 1]"])))))))

(deftest both-writers-take-every-tab
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (two-tab-workbook object-store)
            xlsx (:bytes (documents/export (:id item) "xlsx" alice object-store))
            entries (sheets-xlsx/xlsx-entries xlsx)]
        ;; One worksheet part per tab, and the workbook part naming both.
        (is (= 2 (count (filter #(str/starts-with? % "xl/worksheets/") (keys entries)))))
        (is (str/includes? (get entries "xl/workbook.xml") "上期"))
        (is (str/includes? (get entries "xl/workbook.xml") "下期"))
        ;; And back in, whole.
        (let [again (:item (documents/import! "xlsx" "往復" xlsx alice object-store))
              wb (:resource (documents/content (:id again) alice object-store))]
          (is (= #{"上期" "下期"} (set (keys (:sheets/tabs wb))))))
        ;; CSV is one tab by name, because a CSV is one table — asking for a
        ;; tab that is not there says so rather than quietly giving the
        ;; first.
        (is (= :drive/not-found
               (:type (try (documents/export (:id item) "csv" alice object-store
                                             {:tab "無い"})
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))))))))

(deftest a-formula-reaches-into-another-tab-only-when-asked
  ;; `A1` means this tab's A1, and `原価表!A1` means that tab's. The first
  ;; half is what an unqualified reference must keep meaning now that the
  ;; second half exists.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item first-id]} (two-tab-workbook object-store)
            back (documents/content (:id item) alice object-store)]
        (is (= "2400" (get-in back [:computed first-id "[2 1]"])) "1200 * 2")
        (is (= "1500" (get-in back [:computed "sheet2" "[2 1]"])) "not 1200")))))

(deftest a-named-range-survives-the-way-the-editor-saves
  ;; The editor writes into the projected payload and saves that, so what
  ;; matters is the round trip through JSON and back — the place a nested
  ;; map is most likely to be quietly lost. Checked here rather than assumed
  ;; from the panel existing.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            before (documents/content (:id item) alice object-store)
            tab-id (first (keys (:sheets/tabs (:resource before))))
            title (get-in before [:resource :sheets/tabs tab-id :sheets/title])
            ;; Exactly what the panel builds, through `update!` — the
            ;; projected payload, not the EDN resource.
            payload (assoc (:payload before)
                           "sheets/named-ranges"
                           {"売上" {"sheets/id" "売上" "sheets/tab" title
                                    "sheets/range" "A1:A2"}})
            payload (assoc-in payload ["sheets/tabs" tab-id "sheets/cells"]
                              {"[1 1]" {"sheets/value" "1200"}
                               "[2 1]" {"sheets/value" "1300"}
                               "[3 1]" {"sheets/formula" "SUM(売上)"}})
            _ (documents/update! (:id item) payload alice
                                 (:etag (:item before)) object-store)
            back (documents/content (:id item) alice object-store)]
        ;; Stored as a rehydrated map, not as string keys.
        (is (= {:sheets/id "売上" :sheets/tab title :sheets/range "A1:A2"}
               (get-in back [:resource :sheets/named-ranges "売上"])))
        ;; And it resolves, which is the only reason to have defined it.
        (is (= "2500" (get-in back [:computed tab-id "[3 1]"])))))))

(deftest a-malformed-named-range-is-refused-with-a-reason
  ;; The panel does not validate; the surface does. A range with no tab is
  ;; what a hand-edited payload looks like, and refusing it with a message
  ;; beats storing a name that resolves nowhere.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            before (documents/content (:id item) alice object-store)
            payload (assoc (:payload before)
                           "sheets/named-ranges"
                           {"売上" {"sheets/id" "売上" "sheets/range" "A1:A2"}})
            error (try (documents/update! (:id item) payload alice
                                          (:etag (:item before)) object-store)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :drive/invalid-document (:type error)))
        (is (= ":named-range/invalid" (:code (first (:problems error)))))))))

;; ── tables and lists without the JSON editor ────────────────────────────────

(deftest a-table-and-a-list-survive-the-way-the-editor-saves
  ;; The editor writes into the projected payload. A table is a vector of
  ;; vectors and a list is a vector of strings, which is where a projection
  ;; loses shape if it is going to — so this goes through `update!` with the
  ;; string-keyed payload rather than the EDN resource.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "議事録" alice object-store)
            before (documents/content (:id item) alice object-store)
            payload (assoc (:payload before) "docs/blocks"
                           [{"docs/id" "t" "docs/kind" "table"
                             "docs/rows" [["項目" "状態"] ["設計" "完了"]]}
                            {"docs/id" "l" "docs/kind" "list"
                             "docs/ordered?" true
                             "docs/items" ["予算の確認" "次回日程"]}])
            _ (documents/update! (:id item) payload alice
                                 (:etag (:item before)) object-store)
            back (:resource (documents/content (:id item) alice object-store))]
        (is (= [:table :list] (mapv :docs/kind (:docs/blocks back))))
        (is (= [["項目" "状態"] ["設計" "完了"]]
               (:docs/rows (first (:docs/blocks back)))))
        (is (= ["予算の確認" "次回日程"] (:docs/items (second (:docs/blocks back)))))
        (is (true? (:docs/ordered? (second (:docs/blocks back)))))))))

(deftest a-ragged-table-is-stored-ragged-and-written-square
  ;; The model allows rows of different lengths and the editor does not
  ;; force them even; what is stored stays what was entered. The writers pad
  ;; on the way out, because a ragged row draws with a torn edge in Word and
  ;; ends a Markdown table early.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "表" alice object-store)
            before (documents/content (:id item) alice object-store)
            payload (assoc (:payload before) "docs/blocks"
                           [{"docs/id" "t" "docs/kind" "table"
                             "docs/rows" [["あ" "い" "う"] ["え"]]}])
            _ (documents/update! (:id item) payload alice
                                 (:etag (:item before)) object-store)
            back (:resource (documents/content (:id item) alice object-store))]
        (is (= [["あ" "い" "う"] ["え"]] (:docs/rows (first (:docs/blocks back))))
            "stored as entered")
        (let [md (String. ^bytes (:bytes (documents/export (:id item) "md" alice
                                                           object-store))
                          "UTF-8")]
          (is (str/includes? md "| え |  |  |") "padded on the way out"))
        (let [entries (docs-docx/docx-entries
                       (:bytes (documents/export (:id item) "docx" alice object-store)))]
          ;; Three cells in each row, including the short one.
          (is (= 6 (count (re-seq #"<w:tc>" (get entries "word/document.xml"))))))))))

(deftest an-empty-list-and-an-empty-table-are-not-an-error
  ;; What the editor produces the moment somebody adds one and has not typed
  ;; anything into it yet.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "空" alice object-store)
            before (documents/content (:id item) alice object-store)
            payload (assoc (:payload before) "docs/blocks"
                           [{"docs/id" "l" "docs/kind" "list" "docs/items" []}
                            {"docs/id" "t" "docs/kind" "table" "docs/rows" []}])
            saved (documents/update! (:id item) payload alice
                                     (:etag (:item before)) object-store)]
        (is (:ok? saved))
        ;; And they come out of both writers without producing a broken file.
        (is (pos? (count (:bytes (documents/export (:id item) "md" alice object-store)))))
        (is (pos? (count (:bytes (documents/export (:id item) "docx" alice
                                                   object-store)))))))))

(deftest a-cross-tab-formula-works-through-the-drive
  (with-state
    (fn [_ object-store]
      (let [{:keys [item first-id]} (two-tab-workbook object-store)
            before (documents/content (:id item) alice object-store)
            wb (:resource before)
            _ (save! (:id item)
                     (assoc-in wb [:sheets/tabs first-id :sheets/cells]
                               {[1 1] {:sheets/value "1200"}
                                [2 1] {:sheets/formula "A1*2"}
                                [3 1] {:sheets/formula "下期!A1"}
                                [4 1] {:sheets/formula "A1+下期!A1"}
                                [5 1] {:sheets/formula "無い表!A1"}})
                     alice object-store)
            computed (:computed (documents/content (:id item) alice object-store))]
        (is (= "1500" (get-in computed [first-id "[3 1]"])) "the other tab's A1")
        (is (= "2700" (get-in computed [first-id "[4 1]"])) "1200 + 1500")
        (is (= "2400" (get-in computed [first-id "[2 1]"])) "unqualified is still local")
        (is (= "#REF!" (get-in computed [first-id "[5 1]"])))))))

(deftest every-writer-says-what-it-drops
  ;; The table used to hold three of the five, and the note said the other
  ;; two were a gap rather than a claim of losslessness. All five answer
  ;; now; EDN is the one absence and is not a gap, because it is the stored
  ;; bytes.
  (with-state
    (fn [_ object-store]
      ;; A workbook loses different things to each of its two writers.
      (let [{:keys [item first-id]} (two-tab-workbook object-store)
            warnings (:export-warnings (documents/content (:id item) alice
                                                          object-store))]
        (is (= #{"csv"} (set (keys warnings)))
            "two tabs and a formula: csv loses both, xlsx loses neither")
        (is (contains? (set (map :code (get warnings "csv")))
                       ":csv/other-tabs-dropped"))
        (is (contains? (set (map :code (get warnings "csv")))
                       ":csv/formulas-as-text"))
        (is (some? first-id)))
      ;; A deck loses its slide names and nothing else.
      (let [{:keys [item]} (documents/create! :slides "提案" alice object-store)
            deck (:resource (documents/content (:id item) alice object-store))
            _ (save! (:id item)
                     (assoc deck :slides/slides
                            [{:slides/id "s1" :slides/title "見出し" :slides/shapes []}])
                     alice object-store)
            warnings (:export-warnings (documents/content (:id item) alice
                                                          object-store))]
        (is (= ["pptx"] (keys warnings)))
        (is (= [":pptx/slide-title-dropped"] (mapv :code (get warnings "pptx"))))))))

(deftest a-bold-header-survives-an-xlsx-round-trip-through-the-drive
  ;; The largest of the losses this Drive used to report. A spreadsheet
  ;; imported with a bold header row came back plain, and exporting it again
  ;; lost the formatting for good.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            tab-id (first (keys (:sheets/tabs wb)))
            _ (save! (:id item)
                     (assoc-in wb [:sheets/tabs tab-id :sheets/cells]
                               {[1 1] {:sheets/value "四半期"
                                       :sheets/style {:bold true}}
                                [1 2] {:sheets/value "金額"
                                       :sheets/style {:bold true :align :center}}
                                [2 2] {:sheets/value "1200"
                                       :sheets/style {:number-format "#,##0\"円\""}}})
                     alice object-store)
            xlsx (:bytes (documents/export (:id item) "xlsx" alice object-store))
            back (:item (documents/import! "xlsx" "往復" xlsx alice object-store))
            reimported (:resource (documents/content (:id back) alice object-store))
            ;; Whatever the tab came back keyed as — `workbook-from-files`
            ;; keys by sheet name, which is the tab's title and not the
            ;; document's.
            cells (:sheets/cells (first (vals (:sheets/tabs reimported))))]
        (is (= {:bold true} (:sheets/style (get cells [1 1]))))
        (is (= {:bold true :align :center} (:sheets/style (get cells [1 2]))))
        (is (= {:number-format "#,##0\"円\""} (:sheets/style (get cells [2 2]))))
        (is (= "四半期" (:sheets/value (get cells [1 1]))))))))

(deftest what-a-style-still-loses-is-named-by-key
  ;; Not "the style is dropped" any more — what is written is written, and
  ;; the report is about the parts that are not.
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :sheets "売上" alice object-store)
            wb (:resource (documents/content (:id item) alice object-store))
            tab-id (first (keys (:sheets/tabs wb)))
            _ (save! (:id item)
                     (assoc-in wb [:sheets/tabs tab-id :sheets/cells]
                               {[1 1] {:sheets/value "x"
                                       :sheets/style {:bold true :color "red"}}})
                     alice object-store)
            warnings (:export-warnings (documents/content (:id item) alice
                                                          object-store))]
        (is (= [":xlsx/cell-style-parts-dropped"]
               (mapv :code (get warnings "xlsx"))))
        (is (str/includes? (:message (first (get warnings "xlsx"))) "color"))
        (is (not (str/includes? (:message (first (get warnings "xlsx"))) "bold")))))))
