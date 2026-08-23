(ns cloud.itonami.app.git-session
  "Isolated Git worktrees for write-capable Agent sessions.

  A run never checks out or commits on the operator's current worktree. Each
  conversation receives an `itonami/<session>` branch and a durable worktree
  below the application data directory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config])
  (:import [java.util.concurrent TimeUnit]))

(defn- slug [value]
  (let [normalized (-> (str value) str/lower-case
                       (str/replace #"[^a-z0-9._-]+" "-")
                       (str/replace #"(^[-.]+|[-.]+$)" ""))
        result (or (not-empty normalized) "session")]
    (subs result 0 (min 80 (count result)))))

(defn- execute [argv]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str argv))
        _ (.redirectErrorStream builder true)
        process (.start builder)
        completed? (.waitFor process 120 TimeUnit/SECONDS)
        output (slurp (.getInputStream process) :encoding "UTF-8")]
    (when-not completed?
      (.destroyForcibly process)
      (throw (ex-info "Git operation timed out."
                      {:type :git-session/timeout :argv (vec argv)})))
    {:exit (.exitValue process) :output output :argv (vec argv)}))

(defn- require-success! [result message]
  (when-not (zero? (:exit result))
    (throw (ex-info message {:type :git-session/failed
                             :exit (:exit result)
                             :output (:output result)
                             :argv (:argv result)})))
  result)

(defn- local-root [directory]
  (let [result (execute ["git" "-C" (.getCanonicalPath ^java.io.File directory)
                         "rev-parse" "--show-toplevel"])]
    (require-success! result "Repository は Git checkout ではありません。")
    (str/trim (:output result))))

(defn- repository-root [{:keys [id name location]}]
  (let [directory (io/file (str location))]
    (if (and (.isDirectory directory)
             (not (str/starts-with? (str location) "file://")))
      (local-root directory)
      (let [cache (io/file (config/data-dir) "agent-repositories"
                           (str (slug (or id name)) ".git"))
            cache-path (.getCanonicalPath cache)]
        (.mkdirs (.getParentFile cache))
        (if (.exists (io/file cache ".git"))
          (require-success! (execute ["git" "-C" cache-path "fetch" "--prune" "origin"])
                            "Remote repository を更新できません。")
          (require-success! (execute ["git" "clone" "--no-checkout"
                                      (str location) cache-path])
                            "Remote repository を clone できません。"))
        (local-root cache)))))

(defn branch-name [session-scope]
  (str "itonami/"
       (slug (str (:organization-id session-scope) "-"
                  (:project-id session-scope) "-"
                  (:conversation-id session-scope)))))

(defn prepare!
  "Create or reopen the isolated worktree for one repository and session."
  [session-scope {:keys [id name location] :as repository}]
  (let [root (repository-root repository)
        branch (branch-name session-scope)
        worktree (io/file (config/data-dir) "agent-worktrees"
                          (slug branch) (slug (or id name)))
        worktree-path (.getCanonicalPath worktree)]
    (.mkdirs (.getParentFile worktree))
    (if (.exists (io/file worktree ".git"))
      (let [actual (-> (execute ["git" "-C" worktree-path
                                 "branch" "--show-current"])
                       (require-success! "Agent worktree を確認できません。")
                       :output str/trim)]
        (when-not (= branch actual)
          (throw (ex-info "Agent worktree の branch が一致しません。"
                          {:type :git-session/branch-mismatch
                           :expected branch :actual actual}))))
      (let [exists? (zero? (:exit (execute ["git" "-C" root "show-ref"
                                             "--verify" "--quiet"
                                             (str "refs/heads/" branch)])))
            argv (if exists?
                   ["git" "-C" root "worktree" "add" worktree-path branch]
                   ["git" "-C" root "worktree" "add" "-b" branch
                    worktree-path "HEAD"])]
        (require-success! (execute argv) "Agent session branch を作成できません。")))
    {:repository-id id :repository-name name :source root
     :worktree worktree-path :branch branch :repository repository}))

(defn commit!
  "Stage every change in the isolated worktree and commit it when non-empty."
  [{:keys [worktree branch] :as prepared} message]
  (require-success! (execute ["git" "-C" worktree "add" "--all"])
                    "Agent の変更を stage できません。")
  (let [diff (execute ["git" "-C" worktree "diff" "--cached" "--quiet"])]
    (if (zero? (:exit diff))
      (assoc prepared :committed? false :commit nil)
      (do
        (require-success!
         (execute ["git" "-C" worktree
                   "-c" "user.name=Cloud Itonami Agent"
                   "-c" "user.email=agent@itonami.local"
                   "commit" "-m" (str message)])
         "Agent の変更を commit できません。")
        (let [commit (-> (execute ["git" "-C" worktree "rev-parse" "HEAD"])
                         (require-success! "Commit ID を取得できません。")
                         :output str/trim)]
          (assoc prepared :committed? true :commit commit :branch branch))))))
