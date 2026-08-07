(ns cloud.itonami.app.project-transfer
  "Moving a project from one of your tenants to another (ADR-0024).

  A personal tenant that work can only enter is a box with no lid: ADR-0023 gave
  every person a namespace of their own, and the ordinary thing to do with
  something started there is to hand it to an organization once it stops being
  personal. This is that operation, and the reverse.

  It is deliberately narrow:

  - **Both sides are yours.** Authority to move a project is owner-or-admin in
    the source tenant AND in the destination. A member cannot push work into an
    organization they do not administer, and cannot take an organization's
    project into their own namespace.
  - **A human, in a browser.** Not the CLI and not an agent. Changing who owns
    something is the same class of act as approving a payment (ADR-0006), and an
    agent session that could move a project into a tenant it holds a connection
    to would be granting itself access by moving the target.
  - **Local-only projects.** A published project's ciphertext is encrypted under
    a key bound to its `storage-owner`, which is a hash of the tenant. Renaming
    the directory would move bytes nobody in the destination can read. Moving
    those is a re-publication under the destination's key, which is a different
    operation and is not this one.

  What it does NOT move is named in the receipt rather than left to be
  discovered: mail filed against the project stays in the tenant it was filed
  in, because a filing is a record of that tenant's correspondence and carrying
  it across would move somebody else's mail."
  (:require [clojure.string :as str]
            [cloud.itonami.app.identity :as identity]
            [cloud.itonami.app.mail-projects :as mail-projects]
            [cloud.itonami.app.project-repository :as project-repository]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files StandardCopyOption]))

(def schema "cloud.itonami.app.project-transfer.v1")

(def ^:private transfer-roles #{:owner :admin})

(defn- scope [organization-id user-id project-id]
  {:organization-id organization-id :user-id user-id :project-id project-id})

(defn- move-directory!
  "Rename `from` to `to`, or nil when there is nothing there.

  Refuses rather than merges when the destination exists: two projects with the
  same slug in one tenant is the collision the caller was asked about, and
  discovering it halfway through a move is too late."
  [from to]
  (when (.isDirectory from)
    (when (.exists to)
      (throw (ex-info "移動先に同名のディレクトリが既に存在します。"
                      {:type :project-transfer/destination-exists
                       :path (.getCanonicalPath to)})))
    (.mkdirs (.getParentFile to))
    (Files/move (.toPath from) (.toPath to)
                (into-array StandardCopyOption []))
    {:from (.getCanonicalPath from) :to (.getCanonicalPath to)}))

(defn- mail-references
  "How much of this tenant's mail names the project that is leaving."
  [organization-id project-id]
  {:filed-messages (count (filter (fn [[_ by-project]]
                                    (contains? by-project project-id))
                                  (mail-projects/filings organization-id)))
   :filing-rules (count (filter #(= project-id (:rule/project %))
                                (mail-projects/rules organization-id)))})

(defn transfer-project!
  "Move `project-id` from this session's active tenant to `to-tenant`.

  Returns a receipt naming both tenants, what moved on disk, and what stayed
  behind. Throws before touching anything if the move is not allowed."
  [session {:keys [project-id to-tenant]}]
  (identity/require-passkey! session)
  ;; `require-passkey!` admits an agent session (`may-act?` allows `:agent`) and
  ;; an email magic-link one (ADR-0012 — a session proof, not an identity root).
  ;; Neither may change who owns something, so the kind is asked for here, where
  ;; the reason is visible, as well as at the route.
  (when-not (= :passkey (:kind session))
    (throw (ex-info "Project の移動はブラウザの Passkey session でのみ実行できます。"
                    {:type :project-transfer/human-only})))
  (let [project-id (not-empty (str/trim (str project-id)))
        user-id (:user-id session)
        source (identity/tenant-membership user-id (:organization-id session))
        destination (identity/tenant-membership user-id to-tenant)]
    (when-not project-id
      (throw (ex-info "Project ID を入力してください。"
                      {:type :project/invalid-id})))
    (when-not destination
      (throw (ex-info "移動先の tenant への membership がありません。"
                      {:type :identity/forbidden})))
    (when (= (get-in source [:tenant :id]) (get-in destination [:tenant :id]))
      (throw (ex-info "移動元と移動先が同じ tenant です。"
                      {:type :project-transfer/same-tenant})))
    (doseq [[side side-name] [[source "移動元"] [destination "移動先"]]]
      (when-not (transfer-roles (get-in side [:membership :role]))
        (throw (ex-info (str "Project の移動には" side-name
                             " tenant の owner または admin 権限が必要です。")
                        {:type :identity/forbidden}))))
    (let [source-id (get-in source [:tenant :id])
          destination-id (get-in destination [:tenant :id])
          state (store/snapshot)
          project (get-in state [:chat-projects [source-id project-id]])
          from (scope source-id user-id project-id)
          to (scope destination-id user-id project-id)]
      (when-not project
        (throw (ex-info "この tenant にその Project はありません。"
                        {:type :project/not-found})))
      (when (get-in state [:chat-projects [destination-id project-id]])
        (throw (ex-info "移動先に同じ Project ID が既に存在します。"
                        {:type :project-transfer/destination-exists})))
      (when-let [barrier (project-repository/publication-barrier project)]
        (throw (ex-info
                (if (= :published barrier)
                  (str "公開済みの Project は移動できません。暗号文は移動元の "
                       "storage owner の鍵で封じられており、移動先で読める形に"
                       "するには再公開が必要です。")
                  (str "この Project が公開済みかどうかを判定できません"
                       "（公開記録が導入される前に作成されています）。"
                       "読めない暗号文を移すより安全側に倒して拒否します。"))
                {:type (keyword "project-transfer" (name barrier))
                 :publication-state (:publication-state project)})))
      (let [from-paths (project-repository/project-paths from)
            to-paths (project-repository/project-paths to)
            stayed (mail-references source-id project-id)
            moved-project (move-directory! (:project-directory from-paths)
                                           (:project-directory to-paths))
            moved-workspace
            (try
              (move-directory! (:workspace-directory from-paths)
                               (:workspace-directory to-paths))
              (catch Exception error
                ;; The Git project already moved. Put it back before rethrowing,
                ;; or the store and the disk disagree about where it lives and
                ;; nothing afterwards can find it.
                (when moved-project
                  (move-directory! (:project-directory to-paths)
                                   (:project-directory from-paths)))
                (throw error)))]
        (try
          (project-repository/rewrite-project-metadata!
           (:metadata-file to-paths) (:organization-storage-id to-paths))
          (store/transact!
           (fn [current]
             (let [board (get-in current [:project-workspaces
                                          [source-id project-id]])
                   artifacts (:drive-artifacts current)
                   moved-artifacts
                   (reduce-kv (fn [result [organization user project & rest] value]
                                (if (and (= source-id organization)
                                         (= project-id project))
                                  (-> result
                                      (dissoc (into [organization user project] rest))
                                      (assoc (into [destination-id user project] rest)
                                             value))
                                  result))
                              artifacts
                              artifacts)]
               (cond-> current
                 true (update :chat-projects dissoc [source-id project-id])
                 true (assoc-in [:chat-projects [destination-id project-id]]
                                (assoc project :updated-at (store/now)))
                 true (assoc :drive-artifacts moved-artifacts)
                 board (update :project-workspaces dissoc [source-id project-id])
                 board (assoc-in [:project-workspaces [destination-id project-id]]
                                 (assoc board :organization-id destination-id))
                 true (update :events conj
                              {:type :project/transferred
                               :at (store/now)
                               :project-id project-id
                               :user-id user-id
                               :from-organization-id source-id
                               :to-organization-id destination-id})))))
          (catch Exception error
            (when moved-workspace
              (move-directory! (:workspace-directory to-paths)
                               (:workspace-directory from-paths)))
            (when moved-project
              (move-directory! (:project-directory to-paths)
                               (:project-directory from-paths))
              (project-repository/rewrite-project-metadata!
               (:metadata-file from-paths)
               (:organization-storage-id from-paths)))
            (throw error)))
        {:schema schema
         :project-id project-id
         :from {:id source-id
                :organization-id (get-in source [:tenant :organization-id])
                :name (get-in source [:tenant :name])
                :kind (name (or (get-in source [:tenant :tenant/kind])
                                :organization))}
         :to {:id destination-id
              :organization-id (get-in destination [:tenant :organization-id])
              :name (get-in destination [:tenant :name])
              :kind (name (or (get-in destination [:tenant :tenant/kind])
                              :organization))}
         :moved (cond-> []
                  moved-project (conj "project")
                  moved-workspace (conj "workspace"))
         ;; Named, not silently left: a filing is a record of the source
         ;; tenant's correspondence and does not follow the project out.
         :stayed-behind stayed}))))
