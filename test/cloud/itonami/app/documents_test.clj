(ns cloud.itonami.app.documents-test
  "What a created document has to be true of.

  The object store is `drive.store.memory` rather than the filesystem one,
  and the app state is a local atom rather than the process-wide one, so
  nothing here writes to the data dir."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.store :as store]
            [drive.object :as object]
            [drive.store.memory :as memory]
            [drive.workspace :as ws]
            [forms.model :as forms-model]
            [forms.validate :as forms-validate]
            [forms.wire :as forms-wire]
            [sheets.wire :as sheets-wire]))

(def alice "user-alice")
(def bob "user-bob")

;; A fixed instant, because share-link expiry is compared numerically and a
;; test that read the clock would be a test whose meaning changed at midnight.
(def ^:private now-ms 1800000000000)

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
            (is (= "application/json" (:media-type item)))
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

(deftest what-is-stored-is-the-office-envelope
  (with-state
    (fn [state object-store]
      (let [{:keys [item]} (documents/create! :docs "設計メモ" alice object-store)
            workspace (documents/workspace-for @state alice)
            stored (object/read-item workspace object-store (:id item) alice)
            envelope (json/read-str
                      (String. (byte-array (map unchecked-byte (:bytes stored)))
                               java.nio.charset.StandardCharsets/UTF_8))]
        ;; Self-describing on the way out: a reader holding only these bytes
        ;; can tell which of the three surfaces it has.
        (is (= "kotoba.protocol/office" (get envelope "kotoba.protocol/family")))
        (is (= "docs/document" (get envelope "kotoba.resource/kind")))
        (is (= "設計メモ" (get-in envelope ["kotoba.resource/payload" "docs/title"])))))))

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
      (is (= :drive/unknown-kind
             (try (documents/create! :slides "デッキ" alice object-store)
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
            saved (documents/update! (:id item) edited alice object-store)
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
            error (try (documents/update! (:id item) broken alice object-store)
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
            error (try (documents/update! (:id item) broken alice object-store)
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
        (is (:ok? (documents/update! (:id item) untitled alice object-store)))))))

(deftest an-edit-cannot-change-what-kind-of-document-it-is
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            ;; A payload trying to smuggle in another discriminant. The
            ;; envelope is rebuilt from the item's recorded kind, so the
            ;; stray key is carried as data and the kind does not move.
            sneaky (assoc payload "kotoba.resource/kind" "sheets/workbook")
            saved (documents/update! (:id item) sneaky alice object-store)]
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
               (try (documents/update! (:id item) payload bob object-store)
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
               (try (documents/update! (:id item) payload alice object-store)
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
            _ (documents/update! (:id item)
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
            _ (documents/update! (:id item) edited alice object-store)
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
            saved (documents/update! (:id item) (assoc payload "docs/title" "")
                                     alice object-store)]
        (is (:ok? saved) "a missing title is a warning, and a draft still saves")
        (is (= [":document/missing-title"] (mapv :code (:warnings saved))))
        (is (:quota saved))))))

(deftest a-clean-save-reports-no-warnings
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))]
        (is (empty? (:warnings (documents/update! (:id item) payload alice object-store))))))))

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
        (documents/update! (:id first-doc)
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
            saved (documents/update! (:id item) added alice object-store)
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
        (is (:ok? (documents/update! (:id item) (assoc payload "forms/fields" (vec fields))
                                     alice object-store)))))))

(deftest a-block-added-the-way-the-docs-editor-adds-one-saves
  (with-state
    (fn [_ object-store]
      (let [{:keys [item]} (documents/create! :docs "設計" alice object-store)
            payload (:payload (documents/content (:id item) alice object-store))
            added (update payload "docs/blocks" conj
                          {"docs/id" "b2" "docs/kind" "paragraph" "docs/text" ""})
            saved (documents/update! (:id item) added alice object-store)]
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
        (is (:ok? (documents/update! (:id item) (assoc payload "docs/blocks" (vec blocks))
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
            saved (documents/update! (:id item) edited alice object-store)
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
               (try (documents/update! (:id item) broken alice object-store)
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

;; ── answering a form ────────────────────────────────────────────────────────

(defn- contact-form
  "A form with a required text field and an email field, as the editor makes
  one."
  [object-store]
  (let [{:keys [item]} (documents/create! :forms "問い合わせ" alice object-store)
        payload (:payload (documents/content (:id item) alice object-store))]
    (documents/update! (:id item)
                       (assoc payload "forms/fields"
                              [{"forms/id" "name" "forms/label" "お名前"
                                "forms/field-type" "text" "forms/required?" true}
                               {"forms/id" "email" "forms/label" "メール"
                                "forms/field-type" "email" "forms/required?" false}])
                       alice object-store)
    item))

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
            saved (documents/update! (:id item) (assoc payload "docs/title" "bob の編集")
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
               (try (documents/update! (:id item) payload bob object-store)
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
        (is (= #{"sheets" "docs" "forms"} (set (map :kind (:kinds view)))))))))

(deftest the-create-bar-is-driven-by-the-servers-own-table
  ;; The UI renders one button per entry of `:kinds`, so a surface added to
  ;; `documents/kinds` appears without a second list being edited.
  (is (= (set (map name (keys documents/kinds)))
         (set (map :kind (:kinds (with-state (fn [_ _] (documents/drive-view {:items []} alice)))))))))
