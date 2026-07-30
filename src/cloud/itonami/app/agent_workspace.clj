(ns cloud.itonami.app.agent-workspace
  "Run-scoped Git worktrees and repository write leases."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store])
  (:import [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.agent-workspace.v1")
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

(defn- git-root [cwd]
  (try
    (some-> (command! ["git" "-C" cwd "rev-parse" "--show-toplevel"])
            :stdout str/trim not-empty)
    (catch Exception _ nil)))

(defn- active-lease? [lease now]
  (and (= :active (:status lease))
       (> (long (or (:expires-at-ms lease) 0)) now)))

(defn- acquire-lease! [resource run-id]
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
                       {:schema schema :resource resource :run-id run-id
                        :status :active :acquired-at (store/now)
                        :expires-at-ms (+ now (* lease-seconds 1000))}))))))
    (when-not @acquired?
      (throw (ex-info "Repository already has an active writer."
                      {:type :agent-workspace/write-lease-conflict
                       :resource resource})))
    true))

(defn prepare!
  "Create an isolated detached worktree when cwd is a Git repository.
  Non-Git workspaces still receive an exclusive write lease."
  [cwd run-id]
  (let [cwd (.getCanonicalPath (io/file cwd))
        root (or (git-root cwd) cwd)
        _ (acquire-lease! root run-id)
        git? (boolean (git-root cwd))
        worktree (when git?
                   (io/file (config/data-dir) "agent-worktrees" run-id))
        path (if worktree (.getCanonicalPath worktree) cwd)]
    (try
      (when worktree
        (.mkdirs (.getParentFile worktree))
        (command! ["git" "-C" root "worktree" "add" "--detach" path "HEAD"]))
      (let [workspace {:schema schema :run-id run-id :repo-root root
                       :path path :isolation (if git? :git-worktree
                                                :exclusive-checkout)
                       :status :active :created-at (store/now)}]
        (store/transact! assoc-in [:agent-workspaces :runs run-id] workspace)
        workspace)
      (catch Exception error
        (store/transact! update-in [:agent-workspaces :leases root]
                         assoc :status :failed :released-at (store/now))
        (throw error)))))

(defn release!
  [run-id status]
  (when-let [{:keys [repo-root] :as workspace}
             (get-in (store/snapshot) [:agent-workspaces :runs run-id])]
    (store/transact!
     (fn [state]
       (-> state
           (update-in [:agent-workspaces :runs run-id]
                      assoc :status status :released-at (store/now))
           (update-in [:agent-workspaces :leases repo-root]
                      assoc :status :released :released-at (store/now)))))
    workspace))
