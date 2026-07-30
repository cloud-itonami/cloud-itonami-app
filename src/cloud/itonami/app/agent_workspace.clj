(ns cloud.itonami.app.agent-workspace
  "Session-scoped Git worktrees with turn-scoped write leases.

  A chat keeps one worktree and branch across all of its runs. Different chat
  sessions may write in parallel because Git isolates their checkout, index,
  HEAD, and branch; only concurrent turns inside the same session conflict."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.agent-workspace.v2")
(def lease-seconds 3600)

(defn- command!
  [argv]
  (let [process (.start (ProcessBuilder. ^java.util.List (vec argv)))
        _ (.close (.getOutputStream process))
        stdout (future (slurp (.getInputStream process)))
        stderr (future (slurp (.getErrorStream process)))
        complete? (.waitFor process 30 TimeUnit/SECONDS)]
    (when-not complete?
      (.destroyForcibly process)
      (throw (ex-info "Git workspace command timed out."
                      {:type :agent-workspace/timeout})))
    (let [result {:exit (.exitValue process)
                  :stdout (deref stdout 2000 "")
                  :stderr (deref stderr 2000 "")}]
      (when-not (zero? (:exit result))
        (throw (ex-info "Git workspace command failed."
                        {:type :agent-workspace/git-failed
                         :stderr (:stderr result)})))
      result)))

(defn- try-command [argv]
  (try (command! argv) (catch Exception _ nil)))

(defn- git-root [cwd]
  (some-> (try-command ["git" "-C" cwd "rev-parse" "--show-toplevel"])
          :stdout str/trim not-empty))

(defn- digest-token [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (->> (.digest digest (.getBytes (str value) StandardCharsets/UTF_8))
         (take 10)
         (map #(format "%02x" (bit-and (int %) 0xff)))
         (apply str))))

(defn- workspace-key [root session-id]
  (str "session-" (digest-token (str root "\u0000" session-id))))

(defn- active-lease? [lease now]
  (and (= :active (:status lease))
       (> (long (or (:expires-at-ms lease) 0)) now)))

(defn- acquire-lease! [resource session-id run-id]
  (let [now (System/currentTimeMillis)
        acquired? (atom false)]
    (store/transact!
     (fn [state]
       (let [lease (get-in state [:agent-workspaces :leases resource])]
         (if (and (active-lease? lease now)
                  (not= run-id (:run-id lease)))
           state
           (do
             (reset! acquired? true)
             (assoc-in state [:agent-workspaces :leases resource]
                       {:schema schema :resource resource
                        :session-id session-id :run-id run-id
                        :status :active :acquired-at (store/now)
                        :expires-at-ms (+ now (* lease-seconds 1000))}))))))
    (when-not @acquired?
      (throw (ex-info "This session already has an active writer."
                      {:type :agent-workspace/write-lease-conflict
                       :resource resource :session-id session-id})))
    true))

(defn- branch-exists? [root branch]
  (boolean
   (try-command ["git" "-C" root "show-ref" "--verify" "--quiet"
                 (str "refs/heads/" branch)])))

(defn- create-worktree! [root path branch]
  (.mkdirs (.getParentFile (io/file path)))
  (if (branch-exists? root branch)
    (command! ["git" "-C" root "worktree" "add" path branch])
    (command! ["git" "-C" root "worktree" "add" "-b" branch path "HEAD"])))

(defn- git-summary [{:keys [path base-commit]}]
  (let [status (some-> (try-command ["git" "-C" path "status"
                                     "--porcelain=v1"])
                       :stdout str/split-lines)
        commits (some-> (when base-commit
                          (try-command ["git" "-C" path "rev-list" "--count"
                                        (str base-commit "..HEAD")]))
                        :stdout str/trim)
        dirty (vec (remove str/blank? status))]
    {:changed-files (count dirty)
     :untracked-files (count (filter #(str/starts-with? % "??") dirty))
     :commits (try (Long/parseLong (or commits "0"))
                   (catch Exception _ 0))}))

(defn session-workspace-record
  [session-id]
  (->> (vals (get-in (store/snapshot)
                      [:agent-workspaces :sessions] {}))
       (filter #(= session-id (:session-id %)))
       (sort-by :updated-at)
       last))

(defn session-workspace
  [session-id]
  (when-let [workspace (session-workspace-record session-id)]
    (merge workspace (git-summary workspace))))

(defn- prune-clean-idle!
  [current-key max-worktrees]
  (let [max-worktrees (max 1 (long (or max-worktrees 15)))
        sessions (vals (get-in (store/snapshot)
                               [:agent-workspaces :sessions] {}))
        present (filter #(.exists (io/file (:path %))) sessions)
        excess (max 0 (inc (- (count present) max-worktrees)))
        candidates
        (->> present
             (remove #(= current-key (:id %)))
             (filter #(= :idle (:status %)))
             (filter #(zero? (:changed-files (git-summary %))))
             (sort-by :updated-at)
             (take excess))]
    (doseq [{:keys [id repo-root path]} candidates]
      (when (try-command ["git" "-C" repo-root "worktree" "remove" path])
        (store/transact!
         update-in [:agent-workspaces :sessions id]
         assoc :status :pruned :pruned-at (store/now))))))

(defn prepare!
  "Return the session's existing worktree or create it once. The lease covers
  only this turn, while the worktree and branch survive for later turns."
  ([cwd session-id run-id]
   (prepare! cwd session-id run-id {}))
  ([cwd session-id run-id {:keys [max-worktrees]}]
   (let [cwd (.getCanonicalPath (io/file cwd))
         root (or (git-root cwd) cwd)
         git? (boolean (git-root cwd))
         key (workspace-key root session-id)
         lease-resource (str "turn:" key)
         _ (acquire-lease! lease-resource session-id run-id)
         existing (get-in (store/snapshot)
                          [:agent-workspaces :sessions key])
         branch (or (:branch existing)
                    (str "agent/session-" (digest-token session-id)))
         worktree (when git?
                    (io/file (config/data-dir) "agent-worktrees" key))
         path (or (:path existing)
                  (if worktree (.getCanonicalPath worktree) cwd))
         reusable? (and existing
                        (= root (:repo-root existing))
                        (.exists (io/file path))
                        (or (not git?) (.exists (io/file path ".git"))))]
     (try
       (when (and git? (not reusable?))
         (prune-clean-idle! key max-worktrees)
         (create-worktree! root path branch))
       (let [base-commit
             (or (:base-commit existing)
                 (some-> (try-command ["git" "-C" path "rev-parse" "HEAD"])
                         :stdout str/trim))
             workspace
             (merge existing
                    {:schema schema :id key :session-id session-id
                     :repo-root root :path path :branch (when git? branch)
                     :base-commit base-commit
                     :isolation (if git? :git-worktree
                                    :exclusive-checkout)
                     :status :active :last-run-id run-id
                     :created-at (or (:created-at existing) (store/now))
                     :updated-at (store/now)})
             run {:schema schema :run-id run-id :session-id session-id
                  :workspace-id key :lease-resource lease-resource
                  :path path :repo-root root :status :active}]
         (store/transact!
          (fn [state]
            (-> state
                (assoc-in [:agent-workspaces :sessions key] workspace)
                (assoc-in [:agent-workspaces :runs run-id] run))))
         (merge workspace (git-summary workspace)
                {:reused? (boolean reusable?)}))
       (catch Exception error
         (store/transact! update-in
                          [:agent-workspaces :leases lease-resource]
                          assoc :status :failed :released-at (store/now))
         (throw error))))))

(defn release!
  "Release only the active turn lease. Keep the session worktree and branch."
  [run-id status]
  (when-let [{:keys [workspace-id lease-resource] :as run}
             (get-in (store/snapshot) [:agent-workspaces :runs run-id])]
    (store/transact!
     (fn [state]
       (-> state
           (update-in [:agent-workspaces :runs run-id]
                      assoc :status status :released-at (store/now))
           (update-in [:agent-workspaces :sessions workspace-id]
                      assoc :status :idle :updated-at (store/now)
                      :last-run-status status)
           (update-in [:agent-workspaces :leases lease-resource]
                      assoc :status :released :released-at (store/now)))))
    run))
