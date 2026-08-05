(ns cloud.itonami.app.project-repository
  "Organization-scoped Git projects and user-scoped conversation projections.

  Project source and conversation history deliberately have different roots.
  The former is an ordinary Git repository. The latter is plaintext only in
  the local editable workspace and enters DataLad solely through the existing
  Kagi sealed-block pipeline."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.repository-runtime :as runtime]
            [cloud.itonami.app.repository-storage :as repository]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.project-conversations.v1")

(def ^:dynamic *environment* #(System/getenv %))

(defn- env [name] (not-empty (*environment* name)))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- digest [value]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str value) StandardCharsets/UTF_8))))

(defn- digest-bytes [^bytes value]
  (hex (.digest (MessageDigest/getInstance "SHA-256") value)))

(defn- project-slug [project-id]
  (let [slug (-> (or (not-empty (str project-id)) "default")
                 str/lower-case
                 (str/replace #"[^a-z0-9._-]+" "-")
                 (str/replace #"(^[-.]+|[-.]+$)" "")
                 not-empty)]
    (or (some-> slug (subs 0 (min 63 (count slug))))
        (str "project-" (subs (digest project-id) 0 12)))))

(defn storage-owner
  "One editable/encrypted repository per organization, user and project."
  [{:keys [organization-id user-id project-id]}]
  (str "usr-" (digest (str organization-id "\u0000" user-id "\u0000" project-id))))

(defn- organization-storage-id [{:keys [organization-id]}]
  (str "org-" (subs (digest organization-id) 0 32)))

(defn- roots []
  {:projects-root (.getCanonicalFile (io/file (config/data-dir) "projects"))
   :workspace-root (.getCanonicalPath
                    (io/file (or (env "CLOUD_ITONAMI_EDITABLE_WORKSPACE_ROOT")
                                 (io/file (config/data-dir) "workspace"))))
   :datalad-root (.getCanonicalFile
                  (io/file (or (env "CLOUD_ITONAMI_DATALAD_DATASET")
                               (io/file (config/data-dir) "datalad"))))})

(defn- run-command! [directory argv]
  (let [builder (doto (ProcessBuilder. ^java.util.List (vec argv))
                  (.directory (io/file directory))
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "project repository command failed"
                      {:type :project-repository/command-failed
                       :argv (vec argv) :exit exit
                       :output (subs output 0 (min 4000 (count output)))})))
    output))

(defn- atomic-edn! [file value]
  (let [file (io/file file)
        temporary (io/file (.getParentFile file)
                           (str "." (.getName file) ".tmp-" (UUID/randomUUID)))]
    (.mkdirs (.getParentFile file))
    (spit temporary (pr-str value))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    file))

(defn- ensure-git-project! [scope]
  (let [{:keys [projects-root]} (roots)
        org-id (organization-storage-id scope)
        slug (project-slug (:project-id scope))
        directory (.getCanonicalFile (io/file projects-root org-id slug))
        metadata (io/file directory ".itonami" "project.edn")
        local-exclude (io/file directory ".git" "info" "exclude")]
    (.mkdirs directory)
    (when-not (.isDirectory (io/file directory ".git"))
      (run-command! directory ["/usr/bin/git" "init" "-q" "--initial-branch=main"]))
    (let [gitignore (io/file directory ".gitignore")
          current (if (.isFile gitignore) (slurp gitignore) "")]
      ;; Appended line by line rather than written once. A project created
      ;; before mail filing existed already HAS a .gitignore, so a
      ;; `when-not .isFile` branch would never reach it — and the first filed
      ;; message would put a mail body into an ordinary Git repository.
      (doseq [line [".itonami/runtime/" ".conversations/" ".mail/"]]
        (when-not (str/includes? current line)
          (spit gitignore (str line "\n") :append true))))
    (when-not (and (.isFile local-exclude)
                   (str/includes? (slurp local-exclude) ".itonami/runtime/"))
      (.mkdirs (.getParentFile local-exclude))
      (spit local-exclude "\n.itonami/runtime/\n" :append true))
    (atomic-edn!
     metadata
     {:schema "cloud.itonami.app.project.v1"
      :project/id (:project-id scope)
      :organization/storage-id org-id
      :west {:catalog-root (env "CLOUD_ITONAMI_WORKSPACE_ROOT")
             :managed? false
             :note "Add a remote and west manifest entry before distributed source sync."}})
    {:directory (.getCanonicalPath directory)
     :organization-storage-id org-id
     :project-id (:project-id scope)
     :project-slug slug}))

(defn- datalad-binary []
  (some #(when (and (not (str/blank? %)) (.canExecute (io/file %))) %)
        [(or (env "CLOUD_ITONAMI_DATALAD_BIN") "")
         "/opt/homebrew/bin/datalad" "/usr/local/bin/datalad"]))

(defn- ensure-datalad! []
  (let [{:keys [datalad-root]} (roots)]
    (locking ensure-datalad!
      (.mkdirs datalad-root)
      (when-not (.isDirectory (io/file datalad-root ".datalad"))
        (let [binary (or (datalad-binary)
                         (throw (ex-info "DataLad is not installed"
                                         {:type :project-repository/datalad-unavailable})))]
          (run-command! (.getParentFile datalad-root)
                        [binary "create" (.getCanonicalPath datalad-root)]))))
    {:dataset (.getCanonicalPath datalad-root)
     :initialized? (.isDirectory (io/file datalad-root ".datalad"))
     :remote (env "CLOUD_ITONAMI_DATALAD_REMOTE")}))

(defn- record-project! [scope project datalad]
  (let [key [(:organization-id scope) (:project-id scope)]
        existing (get-in (store/snapshot) [:chat-projects key])
        timestamp (store/now)
        public (merge existing
                      (select-keys project [:project-id :project-slug])
                      {:title (or (:title existing) (:project-id scope))
                       :git-initialized? true
                       :west-managed? false
                       :datalad-initialized? (:initialized? datalad)
                       :sync-state (if (:remote datalad) :configured :local-only)
                       :created-at (or (:created-at existing) timestamp)
                       :updated-at (or (:updated-at existing) timestamp)})]
    (store/transact! assoc-in [:chat-projects key] public)
    public))

(defn ensure-project!
  "Initialize the organization Git project and local ciphertext dataset."
  [scope]
  (let [project (ensure-git-project! scope)
        datalad (ensure-datalad!)]
    (merge project datalad (record-project! scope project datalad))))

(def ^:private maximum-attachment-bytes (* 5 1024 1024))
(def ^:private maximum-attachment-total-bytes (* 10 1024 1024))
(def ^:private maximum-attachments 4)

(defn- safe-filename [value]
  (let [name (-> (or value "attachment")
                 str
                 (str/replace #"[\\/\u0000-\u001f]+" "-")
                 str/trim
                 not-empty)]
    (subs (or name "attachment") 0 (min 120 (count (or name "attachment"))))))

(defn prepare-attachments!
  "Validate browser-provided attachment bytes and materialize a Git-ignored,
  read-only context copy for local CLI providers. The base64 remains in the
  conversation projection so encrypted DataLad publication includes the file."
  [scope project attachments]
  (let [attachments (vec (or attachments []))]
    (when (> (count attachments) maximum-attachments)
      (throw (ex-info "添付できるファイルは4件までです。"
                      {:type :chat/too-many-attachments})))
    (loop [remaining attachments total 0 result []]
      (if-let [attachment (first remaining)]
        (let [data (or (:data-base64 attachment) (:data_base64 attachment))
              bytes (try (.decode (Base64/getDecoder) (str data))
                         (catch Exception _
                           (throw (ex-info "添付ファイルを読み取れません。"
                                           {:type :chat/invalid-attachment}))))
              size (alength bytes)
              next-total (+ total size)
              _ (when (> size maximum-attachment-bytes)
                  (throw (ex-info "1ファイルは5MBまでです。"
                                  {:type :chat/attachment-too-large})))
              _ (when (> next-total maximum-attachment-total-bytes)
                  (throw (ex-info "添付ファイルの合計は10MBまでです。"
                                  {:type :chat/attachments-too-large})))
              name (safe-filename (:name attachment))
              media-type (or (not-empty (str (:media-type attachment)))
                             (not-empty (str (:media_type attachment)))
                             "application/octet-stream")
              cid (str "sha256:" (digest-bytes bytes))
              directory (io/file (:directory project) ".itonami" "runtime"
                                 "attachments" (project-slug (:conversation-id scope)))
              file (io/file directory (str (subs cid 7 23) "-" name))]
          (.mkdirs directory)
          (when-not (.isFile file)
            (Files/write (.toPath file) bytes
                         (make-array java.nio.file.OpenOption 0)))
          (recur (rest remaining) next-total
                 (conj result {:id cid :name name :media-type media-type
                               :size-bytes size :data-base64 data
                               :path (.getCanonicalPath file)})))
        result))))

(defn chat-context
  "Trusted identity/project metadata for the model's system context."
  [scope project]
  (let [state (store/snapshot)
        user (get-in state [:identity :users (:user-id scope)])
        organization (get-in state [:identity :organizations
                                    (:organization-id scope)])
        tenant-kind (or (:tenant/kind organization)
                        (when (some #(and (= :identity/registered (:type %))
                                          (= (:organization-id scope)
                                             (:organization-id %)))
                                    (:events state))
                          :personal)
                        :organization)
        membership (some #(when (and (= (:user-id scope) (:user-id %))
                                     (= (:organization-id scope)
                                        (:organization-id %))) %)
                         (vals (get-in state [:identity :memberships])))]
    {:user {:id (:user-id scope)
            :display-name (:display-name user)
            :did (:did user)}
     :organization {:id (:organization-id scope)
                    :name (:name organization)
                    :tenant-kind tenant-kind
                    :role (:role membership)}
     :project {:id (:project-id scope)}
     :repository {:kind :git
                  :path (:directory project)
                  :west-managed? (:west-managed? project)}
     :conversation {:id (:conversation-id scope)}}))

(defn projects [scope]
  (let [state (store/snapshot)]
   (->> (:chat-projects state)
       (keep (fn [[[organization-id _] project]]
               (when (= organization-id (:organization-id scope))
                 (let [workspace (get-in state [:project-workspaces
                                                [organization-id (:project-id project)]])]
                   (assoc project
                          :repository-count (count (:repositories workspace))
                          :issue-count (count (:issues workspace)))))))
       (sort-by :project-id)
       vec)))

(defn local-projects-snapshot
  "The local project catalogue. GitHub is an optional adapter, never the
  authority for this response."
  [scope]
  {:schema "cloud.itonami.app.projects.v1"
   :source "この端末"
   :scope (:organization-id scope)
   :status "local"
   :views ["Table" "Board" "Roadmap"]
   :integration {:github {:available? true :mode "optional"}}
   :items (projects scope)})

(defn- artifact-recorded? [key]
  (boolean (get-in (store/snapshot) [:drive-artifacts key])))

(defn- remember-artifact! [key item]
  (store/transact! assoc-in [:drive-artifacts key]
                   {:item-id (:id item) :at (store/now)}))

(defn- put-drive-artifact!
  [scope kind artifact-id path filename media-type bytes]
  (let [key [(:organization-id scope) (:user-id scope)
             (:project-id scope) kind artifact-id]]
    (when-not (artifact-recorded? key)
      (let [folder (documents/ensure-folder-path! (:user-id scope) path)
            result (documents/upload! filename media-type bytes (:user-id scope)
                                      (documents/store-instance)
                                      {:folder (:id folder)})]
        (remember-artifact! key (:item result))
        (:item result)))))

(defn- project-metadata-artifact! [scope metadata]
  (let [artifact {:schema "cloud.itonami.app.project-artifact.v1"
                  :organization-id (:organization-id scope)
                  ;; Listing-only counts are derived from the board and must
                  ;; not manufacture a second metadata artifact during a
                  ;; Drive backfill.
                  :project (select-keys metadata
                                        [:project-id :project-slug :title :description
                                         :git-initialized? :west-managed?
                                         :datalad-initialized? :sync-state
                                         :created-at :updated-at])}
        artifact-id (digest (pr-str artifact))]
    (put-drive-artifact!
     scope :project artifact-id ["Projects" (:project-id scope)]
     (str "project-" (subs artifact-id 0 16) ".edn") "application/edn"
     (.getBytes (pr-str artifact) StandardCharsets/UTF_8))))

(defn create-project!
  "Create a local project and its ordinary Git repository. GitHub can be
  connected later; no remote account or network request is required here."
  [scope {:keys [title description]}]
  (let [project-id (not-empty (str/trim (str (:project-id scope))))]
    (when-not project-id
      (throw (ex-info "Project ID を入力してください。"
                      {:type :project/invalid-id})))
    (when (> (count project-id) 80)
      (throw (ex-info "Project ID は80文字以内にしてください。"
                      {:type :project/invalid-id})))
    (let [project (ensure-project! scope)
          key [(:organization-id scope) project-id]
          existing (get-in (store/snapshot) [:chat-projects key])
          requested-title (not-empty (str/trim (str title)))
          requested-description (not-empty (str/trim (str description)))
          metadata (merge existing
                          {:title (or requested-title (:title existing) project-id)
                           :description (or requested-description
                                            (:description existing) "")
                           :updated-at (if (or requested-title requested-description
                                               (nil? existing))
                                         (store/now)
                                         (:updated-at existing))})]
      (store/transact! assoc-in [:chat-projects key] metadata)
      (project-metadata-artifact! scope metadata)
      (merge project metadata))))

(def project-columns
  [{:id "backlog" :name "Backlog"}
   {:id "ready" :name "Ready"}
   {:id "in-progress" :name "進行中"}
   {:id "review" :name "Review"}
   {:id "done" :name "完了"}])

(def ^:private project-column-ids (set (map :id project-columns)))

(defn- project-key [scope]
  [(:organization-id scope) (:project-id scope)])

(defn- empty-project-workspace [scope]
  {:schema "cloud.itonami.app.project-board.v1"
   :organization-id (:organization-id scope)
   :project-id (:project-id scope)
   :columns project-columns
   :repositories []
   :issues {}
   :next-issue-number 1
   :updated-at (store/now)})

(defn- ensure-project-workspace! [scope]
  (let [key (project-key scope)]
    (when-not (get-in (store/snapshot) [:project-workspaces key])
      (store/transact! assoc-in [:project-workspaces key]
                       (empty-project-workspace scope)))
    (get-in (store/snapshot) [:project-workspaces key])))

(defn- board-artifact! [scope board]
  (let [artifact-id (digest (pr-str board))]
    (put-drive-artifact!
     scope :project-board artifact-id ["Projects" (:project-id scope)]
     (str "board-" (subs artifact-id 0 16) ".edn") "application/edn"
     (.getBytes (pr-str board) StandardCharsets/UTF_8))))

(defn project-board
  "A local Kanban board spanning any number of repositories."
  ([scope]
   (project-board scope (mapv #(select-keys % [:id :name])
                              (:agents (store/snapshot)))))
  ([scope agents]
   (create-project! scope {})
   (let [board (ensure-project-workspace! scope)
         state (store/snapshot)
         project (get-in state [:chat-projects (project-key scope)])]
     {:schema "cloud.itonami.app.project-board.v1"
      :project project
      :columns (:columns board)
      :repositories (:repositories board)
      :issues (->> (vals (:issues board))
                   (mapv (fn [issue]
                           (let [blockers (keep #((:issues board) %)
                                                (:blocker-ids issue))]
                             (assoc issue
                                    :blocked? (boolean (some #(not= "done" (:column %)) blockers))
                                    :blockers (mapv #(select-keys % [:id :number :title :column]) blockers)))))
                   (sort-by (juxt :column :number))
                   vec)
      :agents (vec agents)
      :updated-at (:updated-at board)})))

(defn add-project-repository!
  [scope {:keys [name location kind]}]
  (let [name (not-empty (str/trim (str name)))
        location (not-empty (str/trim (str location)))]
    (when-not name
      (throw (ex-info "Repository 名を入力してください。"
                      {:type :project/invalid-repository})))
    (when-not location
      (throw (ex-info "Repository のローカル path または URL を入力してください。"
                      {:type :project/invalid-repository})))
    (ensure-project-workspace! scope)
    (let [repository {:id (str "repo-" (subs (digest (str name "\u0000" location)) 0 16))
                      :name name :location location
                      :kind (if (= "github" (clojure.core/name (keyword (or kind "local"))))
                              "github" "local")
                      :created-at (store/now)}
          key (project-key scope)]
      (store/transact!
       update-in [:project-workspaces key]
       (fn [board]
         (if (some #(= (:id repository) (:id %)) (:repositories board))
           board
           (-> board
               (update :repositories conj repository)
               (assoc :updated-at (store/now))))))
      (let [board (get-in (store/snapshot) [:project-workspaces key])]
        (board-artifact! scope board)
        repository))))

(defn create-issue!
  ([scope request]
   (create-issue! scope request (set (map :id (:agents (store/snapshot))))))
  ([scope {:keys [title description column repository-ids blocker-ids agent-id]} known-agents]
  (let [title (not-empty (str/trim (str title)))
        column (or (not-empty (str column)) "backlog")
        repository-ids (vec (distinct (remove str/blank? (map str repository-ids))))
        blocker-ids (vec (distinct (remove str/blank? (map str blocker-ids))))]
    (when-not title
      (throw (ex-info "Issue のタイトルを入力してください。"
                      {:type :project/invalid-issue})))
    (when-not (project-column-ids column)
      (throw (ex-info "Kanban column が不正です。"
                      {:type :project/invalid-column})))
    (let [board (ensure-project-workspace! scope)
          known-repositories (set (map :id (:repositories board)))]
      (when-not (every? known-repositories repository-ids)
        (throw (ex-info "Project に登録されていない repository が含まれています。"
                        {:type :project/unknown-repository})))
      (when (and (not (str/blank? (str agent-id)))
                 (not (known-agents (str agent-id))))
        (throw (ex-info "登録されていない agent は割り当てできません。"
                        {:type :project/unknown-agent})))
      (when-not (every? (set (keys (:issues board))) blocker-ids)
        (throw (ex-info "Project に存在しない blocker が含まれています。"
                        {:type :project/unknown-blocker})))
      (let [key (project-key scope)
            number (:next-issue-number board)
            issue {:id (str "issue-" (UUID/randomUUID))
                   :number number :title title
                   :description (or (not-empty (str/trim (str description))) "")
                   :column column :repository-ids repository-ids
                   :blocker-ids blocker-ids
                   :agent-id (not-empty (str agent-id))
                   :created-at (store/now) :updated-at (store/now)}]
        (store/transact!
         update-in [:project-workspaces key]
         (fn [current]
           (-> current
               (assoc-in [:issues (:id issue)] issue)
               (update :next-issue-number inc)
               (assoc :updated-at (store/now)))))
        (let [updated (get-in (store/snapshot) [:project-workspaces key])]
          (board-artifact! scope updated)
          issue))))))

(defn- dependency-reaches? [issues start target seen]
  (cond
    (= start target) true
    (seen start) false
    :else (some #(dependency-reaches? issues % target (conj seen start))
                (:blocker-ids (get issues start)))))

(defn update-issue!
  ([scope issue-id request]
   (update-issue! scope issue-id request
                  (set (map :id (:agents (store/snapshot))))))
  ([scope issue-id {:keys [column agent-id repository-ids blocker-ids]} known-agents]
  (let [key (project-key scope)
        board (ensure-project-workspace! scope)
        issue (get-in board [:issues issue-id])
        known-repositories (set (map :id (:repositories board)))
        repository-ids (when (some? repository-ids)
                         (vec (distinct (map str repository-ids))))
        blocker-ids (when (some? blocker-ids)
                      (vec (distinct (remove str/blank? (map str blocker-ids)))))]
    (when-not issue
      (throw (ex-info "Issue が見つかりません。" {:type :project/issue-not-found})))
    (when (and column (not (project-column-ids (str column))))
      (throw (ex-info "Kanban column が不正です。" {:type :project/invalid-column})))
    (when (and repository-ids (not (every? known-repositories repository-ids)))
      (throw (ex-info "Project に登録されていない repository が含まれています。"
                      {:type :project/unknown-repository})))
    (when (and blocker-ids
               (or (some #{issue-id} blocker-ids)
                   (not (every? (set (keys (:issues board))) blocker-ids))
                   (some #(dependency-reaches? (:issues board) % issue-id #{})
                         blocker-ids)))
      (throw (ex-info "blocker が不正か、循環依存を作ります。"
                      {:type :project/invalid-blocker})))
    (when (and (not (str/blank? (str agent-id)))
               (not (known-agents (str agent-id))))
      (throw (ex-info "登録されていない agent は割り当てできません。"
                      {:type :project/unknown-agent})))
    (store/transact!
     update-in [:project-workspaces key :issues issue-id]
     (fn [current]
       (cond-> (assoc current :updated-at (store/now))
         column (assoc :column (str column))
         (some? agent-id) (assoc :agent-id (not-empty (str agent-id)))
         repository-ids (assoc :repository-ids repository-ids)
         blocker-ids (assoc :blocker-ids blocker-ids))))
    (store/transact! assoc-in [:project-workspaces key :updated-at] (store/now))
    (let [updated (get-in (store/snapshot) [:project-workspaces key])]
      (board-artifact! scope updated)
      (get-in updated [:issues issue-id])))))

(defn dispatchable-issues
  "Assigned, unfinished and unblocked issues without a run yet."
  [scope]
  (let [board (ensure-project-workspace! scope)
        issues (:issues board)]
    (->> (vals issues)
         (filter :agent-id)
         (remove #(= "done" (:column %)))
         (remove #(get-in % [:automation :run-id]))
         (filter (fn [issue]
                   (every? #(= "done" (:column (get issues %)))
                           (:blocker-ids issue))))
         (sort-by :number)
         vec)))

(defn record-issue-run! [scope issue-id run]
  (let [key (project-key scope)
        automation {:run-id (:id run)
                    :status (:status run)
                    :started-at (store/now)}]
    (store/transact! assoc-in
                     [:project-workspaces key :issues issue-id :automation]
                     automation)
    (store/transact! update :events
                     #(vec (take-last 200
                                      (conj (or % [])
                                            {:type :project/issue-dispatched
                                             :at (store/now)
                                             :organization-id (:organization-id scope)
                                             :project-id (:project-id scope)
                                             :issue-id issue-id
                                             :run-id (:id run)}))))
    automation))

(defn- initial-projection [scope owner]
  {:schema schema
   :project {:id (:project-id scope)
             :storage-owner owner}
   :conversations {}})

(defn- save-projection! [scope messages]
  (let [{:keys [workspace-root datalad-root]} (roots)
        owner (storage-owner scope)
        conversation-id (:conversation-id scope)
        conversation {:id conversation-id
                      :updated-at (store/now)
                      :messages
                      (mapv (fn [message]
                              (cond-> (select-keys message
                                                   [:id :role :content :at])
                                (:attachments message)
                                (assoc :attachments
                                       (mapv #(dissoc % :path)
                                             (:attachments message)))))
                            messages)}
        edit #(assoc-in % [:conversations conversation-id] conversation)]
    (if (repository/workspace-snapshot workspace-root owner)
      (repository/retry-workspace-edit!
       {:workspace-root workspace-root :owner owner :edit-fn edit})
      (try
        (repository/replace-workspace!
         {:workspace-root workspace-root
          :datalad-root (.getCanonicalPath datalad-root)
          :owner owner
          :candidate (edit (initial-projection scope owner))})
        (catch clojure.lang.ExceptionInfo error
          (if (= :repository-storage/initialization-conflict (:type (ex-data error)))
            (repository/retry-workspace-edit!
             {:workspace-root workspace-root :owner owner :edit-fn edit})
            (throw error)))))))

(defn- publish-configured? []
  (every? env ["CLOUD_ITONAMI_DATALAD_REMOTE"
               "CLOUD_ITONAMI_KOTOBASE_TOKEN"]))

(defn- project-drive-artifacts!
  [scope messages semantic-cid]
  (let [conversation-id (:conversation-id scope)
        cid-label (-> (str semantic-cid) (str/replace #"[^a-zA-Z0-9._-]" "-")
                      (subs 0 (min 40 (count (str semantic-cid)))))
        projection (mapv (fn [message]
                           (cond-> (select-keys message [:id :role :content :at])
                             (:attachments message)
                             (assoc :attachments
                                    (mapv #(dissoc % :data-base64 :path)
                                          (:attachments message)))))
                         messages)]
    (put-drive-artifact!
     scope :chat semantic-cid ["Chat" (:project-id scope) conversation-id]
     (str "conversation-" cid-label ".edn") "application/edn"
     (.getBytes (pr-str {:schema schema
                         :organization-id (:organization-id scope)
                         :project-id (:project-id scope)
                         :conversation-id conversation-id
                         :semantic-cid semantic-cid
                         :messages projection})
                StandardCharsets/UTF_8))
    (doseq [attachment (mapcat #(or (:attachments %) []) messages)
            :let [content-id (:id attachment)
                  encoded (:data-base64 attachment)]
            :when (and content-id encoded)]
      (put-drive-artifact!
       scope :attachment content-id ["Chat" (:project-id scope) conversation-id "Attachments"]
       (:name attachment) (:media-type attachment)
       (.decode (Base64/getDecoder) (str encoded))))
    {:state :available
     :folders ["Chat" (:project-id scope) conversation-id]}))

(defn sync-existing-drive-artifacts!
  "Idempotently expose the local catalogue and retained chat sessions in
  Drive. This is the migration path for data created before artifact folders
  existed; the artifact index prevents a Drive refresh from duplicating it."
  [scope]
  (let [state (store/snapshot)
        organization-id (:organization-id scope)
        user-id (:user-id scope)
        local-projects (projects scope)
        sessions (->> (vals (:sessions state))
                      (filter #(and (= organization-id
                                       (get-in % [:scope :organization-id]))
                                    (= user-id (get-in % [:scope :user-id])))))]
    (doseq [project local-projects]
      (project-metadata-artifact!
       (assoc scope :project-id (:project-id project)) project))
    (doseq [session sessions
            :let [session-scope (:scope session)
                  messages (:messages session)
                  retained-id (str "retained-state:"
                                   (digest (pr-str messages)))]
            :when (and (:project-id session-scope)
                       (:conversation-id session-scope)
                       (seq messages))]
      (project-drive-artifacts! session-scope messages retained-id))
    {:projects (count local-projects) :conversations (count sessions)}))

(defn- git-commit!
  "Commit whatever is staged, if anything is.

  Identity is passed per invocation rather than configured into the repository:
  these repositories are created by the app on somebody's machine, and writing a
  global-looking `user.email` into them would put this app's name on commits a
  person may later make by hand.

  A repository with nothing staged is not an error — filing the same message
  twice is expected, and it should be quiet rather than fail."
  [directory message]
  ;; `run-command!` merges stderr into stdout so that a failure reports what git
  ;; said. That means anything git writes to stderr for reasons of its own is in
  ;; this output too — measured on this machine, a repeated
  ;; `error: could not read IPC response` from the filesystem monitor. So the
  ;; porcelain is read by shape and the SHA by pattern, rather than by trusting
  ;; the whole output: taken literally, that noise reads as a dirty tree and as
  ;; a commit id that is not one.
  (let [status (run-command! directory ["/usr/bin/git" "status" "--porcelain"])
        changed? (some #(re-matches #"[ MADRCU?!]{2} .+" %)
                       (str/split-lines status))]
    (when changed?
      (run-command! directory ["/usr/bin/git" "add" "-A"])
      (run-command! directory
                    ["/usr/bin/git"
                     "-c" "user.name=Cloud Itonami"
                     "-c" "user.email=itonami@localhost"
                     "commit" "-q" "-m" message])
      (some->> (run-command! directory ["/usr/bin/git" "rev-parse" "HEAD"])
               str/split-lines
               (some #(re-matches #"[0-9a-f]{40}" (str/trim %)))))))

(defn- mail-envelope
  "What goes into Git: who wrote, when, about what — and a digest of the body.

  The body itself does not. That is the same line `.conversations/` draws, for
  the same reason: this is an ordinary Git repository, it may gain a remote, and
  a message body is both the sensitive part and the large part. The digest is
  what lets the tracked record be checked against the plaintext beside it."
  [message assignment]
  {:schema "cloud.itonami.app.project-mail.v1"
   :mail/id (:id message)
   :mail/message-id (:message-id message)
   :mail/from (:from message)
   :mail/from-email (:from-email message)
   :mail/subject (:subject message)
   :mail/received-at (:received-at message)
   :mail/labels (vec (sort (map name (or (:labels message) []))))
   :mail/body-sha256 (digest (str (:body message)))
   :mail/body-bytes (count (.getBytes (str (:body message))
                                      StandardCharsets/UTF_8))
   :filed/project (:project-id assignment)
   :filed/by (some-> (:by assignment) name)
   :filed/rule (:rule-id assignment)
   :filed/at (:at assignment)})

(defn- mail-paths [directory message]
  (let [received (str (or (:received-at message) "unknown"))
        year (if (>= (count received) 4) (subs received 0 4) "unknown")
        month (if (>= (count received) 7) (subs received 5 7) "unknown")
        safe (str/replace (str (:id message)) #"[^A-Za-z0-9._-]+" "_")
        safe (subs safe (max 0 (- (count safe) 80)))]
    {:envelope (io/file directory "mail" year month (str safe ".edn"))
     :body (io/file directory ".mail" year month (str safe ".txt"))}))

(defn file-mail!
  "Write filed messages into the project's repository and commit them.

  Two destinations, which is the boundary this namespace already draws between
  project source and conversation history:

  - `mail/<yyyy>/<mm>/<id>.edn` is **tracked**: the envelope and a digest of the
    body. It is the project's own record of what it holds, diffable and
    reviewable like any other source.
  - `.mail/<yyyy>/<mm>/<id>.txt` is **git-ignored**: the body in plaintext, for
    tools working inside the project — the same status, and the same reason, as
    the read-only attachment copies `prepare-attachments!` materializes.

  One commit per call, not per message: filing a hundred messages should read as
  one act in the history, because it was one."
  [scope messages]
  (let [project (ensure-git-project! scope)
        directory (:directory project)
        written (doall
                 (for [{:keys [message assignment]} messages
                       :when (and message (:id message))]
                   (let [{:keys [envelope body]} (mail-paths directory message)]
                     (atomic-edn! envelope (mail-envelope message assignment))
                     (.mkdirs (.getParentFile body))
                     (spit body (str (:body message)))
                     (.getPath envelope))))
        commit (when (seq written)
                 (git-commit! directory
                              (str "mail: file " (count written)
                                   " message" (if (= 1 (count written)) "" "s")
                                   " into " (:project-id scope))))]
    {:schema "cloud.itonami.app.project-mail.v1"
     :project-id (:project-id scope)
     :project-slug (:project-slug project)
     :directory directory
     :written (count written)
     :commit commit}))

(defn persist-conversation!
  "Persist a completed conversation locally and publish sealed blocks when the
  operator configured the remote/Kotobase boundary. A remote failure never
  discards the already-written local projection."
  [scope messages]
  (let [project (ensure-project! scope)
        owner (storage-owner scope)
        saved (save-projection! scope messages)
        drive (try
                (project-drive-artifacts! scope messages (:semantic/cid saved))
                (catch Exception error
                  {:state :projection-failed :error (.getMessage error)}))
        local {:state :local-only
               :semantic-cid (:semantic/cid saved)
               :storage-owner owner
               :drive drive
               :git-initialized? true
               :datalad-initialized? (:initialized? project)}]
    (if-not (publish-configured?)
      local
      (try
        (let [{:keys [workspace-root]} (roots)
              published (repository/commit-workspace!
                         (assoc (runtime/production-context owner)
                                :workspace-root workspace-root))]
          (assoc local :state :published
                 :head-cid (get-in published [:head :head/cid])
                 :revision (get-in published [:receipt :revision])))
        (catch Exception error
          (assoc local :state :publish-failed
                 :error (.getMessage error)))))))
