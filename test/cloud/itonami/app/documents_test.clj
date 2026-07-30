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
            [drive.workspace :as ws]))

(def alice "user-alice")
(def bob "user-bob")

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
