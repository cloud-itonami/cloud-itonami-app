(ns cloud.itonami.app.git-hygiene
  "A bounded host tool for the git-maintenance workforce Bot.

  ## What \"git conflict cleanup\" is, once it is measured

  The workspace runbook for this (`manifest/cleanup-workflow.edn` in the west
  superproject) covers five different things under one word: interrupted merge
  state, conflict markers left in files, stashes nobody owns any more, branches
  and worktrees that outlived the work, and checkouts that drifted from their
  remote.

  Measured 2026-08-30 across 4,696 checkouts under `orgs/`:

      interrupted operations (MERGE_HEAD / rebase / cherry-pick / revert)   0
      stashes                                                              6 repos
      live linked worktrees                                              791
      worktree registrations whose directory is gone                      80 in 65 repos

  So the thing actually accumulating is the last row, and it is the one a
  deterministic tool can finish: `git worktree prune` removes the bookkeeping
  for a working tree that is ALREADY gone. Nothing that still exists is
  touched, and git refuses to prune an entry marked `locked`, which is how a
  worktree on removable media protects itself.

  Everything else in that list is REPORTED and not acted on, because the safe
  action is not mechanical:

    interrupted operation   resolving a conflict is a judgement about content
    stash                   the runbook requires archiving to
                            `.git/stash-archive-<date>/` before any drop, and
                            'I am sure it landed' is explicitly not a reason
                            to skip that (CLAUDE.md, measured incident)
    branch / dirty tree     retiring these needs push and server-side merge;
                            this Bot has no network and no credential

  Reporting them is not a consolation prize. Until this tool existed, the
  count was not on any surface an operator reads: `ls` does not show a
  registration whose directory is gone, and `git status` in one checkout says
  nothing about the other four thousand.

  ## Why the mutation is one fixed verb and not a shell

  Same reason as `disk-space`: the Bot never receives a path to delete or a
  command to run. It asks for `git_hygiene_prune`, and this namespace decides
  which checkouts qualify -- from evidence it re-reads at call time, not from
  anything the model said -- and launches a fixed argv per checkout. There is
  no shell anywhere in the path.

  ## The evidence floor

  A scan that could not read the manifest must not return the same shape as a
  scan that read it and found nothing (ADR-2608136000). `projects` throws when
  `manifest/west.yml` is absent, and every report carries `:listed` against
  `:scanned` so a caller can see how much of the registry was actually
  visible."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.west-kotoba-refactor :as west])
  (:import [java.util.concurrent TimeUnit]))

;; `west/projects` is reused rather than copied: it is a pure reader of the
;; generated `manifest/west.yml` projection, and a second parser of the same
;; generated file is a second thing to drift.

(def ^:private prune-timeout-seconds 60)
(def default-prune-limit 25)
(def max-prune-limit 200)
(def ^:private max-listed-findings 50)

(def tool-definitions
  [{:name "git_hygiene_status"
    :description
    (str "Read git hygiene across every west-registered checkout: interrupted "
         "merge/rebase state, stashes, live linked worktrees, and worktree "
         "registrations whose directory no longer exists.")
    :parameters {:type "object" :properties {}}}
   {:name "git_hygiene_prune"
    :description
    (str "Remove worktree bookkeeping for working trees that are already gone, "
         "in checkouts that have some. Live worktrees, locked worktrees, "
         "stashes, branches, uncommitted changes and remotes are never "
         "touched, and nothing is pushed. (write)")
    :parameters {:type "object" :properties {}}}])

(defn tool? [tool-name]
  (contains? #{"git_hygiene_status" "git_hygiene_prune"} (str tool-name)))

(defn write-tool? [tool-name]
  (= "git_hygiene_prune" (str tool-name)))

;; ── where the workspace is ───────────────────────────────────────────────

(defn workspace-root
  "The west superproject this resident maintains.

  Shared with `disk-space` on purpose: both are host-maintenance identities
  pointed at the same machine, and two environment variables meaning the same
  directory is a way for them to disagree."
  []
  (let [path (some-> (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT") str not-empty)]
    (when-not path
      (throw (ex-info "CLOUD_ITONAMI_WORKSPACE_ROOT が設定されていません。"
                      {:type :git-hygiene/workspace-required})))
    (.getCanonicalFile (io/file path))))

(defn- inside? [root file]
  (str/starts-with? (.getPath (.getCanonicalFile (io/file file)))
                    (str (.getPath (io/file root)) java.io.File/separator)))

;; ── reading one checkout, without launching git ──────────────────────────

(defn- git-dir
  "The metadata directory for `checkout`, or nil when it is not a checkout.

  A `.git` FILE means a linked worktree. Those are followed so a report about
  a linked worktree is about the right metadata, but `prune` only ever runs in
  a standalone clone (see `prunable`)."
  [checkout]
  (let [dot (io/file checkout ".git")]
    (cond
      (.isDirectory dot) dot
      (.isFile dot)
      (let [line (str/trim (slurp dot))
            target (some-> (re-find #"(?m)^gitdir:\s*(.+)$" line) second str/trim)]
        (when target
          (let [f (io/file target)]
            (when (.isDirectory f) (.getCanonicalFile f)))))
      :else nil)))

(def ^:private interrupted-markers
  {"MERGE_HEAD" :merge
   "CHERRY_PICK_HEAD" :cherry-pick
   "REVERT_HEAD" :revert
   "rebase-merge" :rebase
   "rebase-apply" :rebase})

(defn- interrupted [git-dir]
  (->> interrupted-markers
       (keep (fn [[name kind]] (when (.exists (io/file git-dir name)) kind)))
       distinct
       sort
       vec))

(defn- stash-count [git-dir]
  (let [log (io/file git-dir "logs" "refs" "stash")]
    (if (.isFile log)
      (with-open [r (io/reader log)]
        (count (line-seq r)))
      0)))

(defn- worktrees
  "Every registration under this checkout, and whether its directory is there.

  `locked` is read but never overridden: git's own refusal to prune a locked
  worktree is the mechanism protecting a working tree on an unmounted volume,
  and re-implementing the decision here would be a second opinion about it."
  [git-dir]
  (let [dir (io/file git-dir "worktrees")]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           (keep (fn [entry]
                   (let [pointer (io/file entry "gitdir")]
                     (when (.isFile pointer)
                       (let [target (io/file (str/trim (slurp pointer)))
                             working (.getParentFile target)]
                         {:name (.getName entry)
                          :working-tree (some-> working .getPath)
                          :present? (boolean (some-> working .isDirectory))
                          :locked? (.exists (io/file entry "locked"))})))))
           vec))))

(defn inspect-checkout
  "One checkout's hygiene, from metadata alone. No subprocess, no working-tree
  walk: this runs once per registered project and has to stay cheap enough
  that the whole registry is affordable on every tick."
  [checkout]
  (when-let [gd (git-dir checkout)]
    (let [trees (worktrees gd)
          stale (filterv #(and (not (:present? %)) (not (:locked? %))) trees)]
      {:checkout (.getPath (io/file checkout))
       :standalone? (.isDirectory (io/file checkout ".git"))
       :interrupted (interrupted gd)
       :stashes (stash-count gd)
       :worktrees-live (count (filterv :present? trees))
       :worktrees-locked (count (filterv :locked? trees))
       :worktrees-stale (count stale)
       :stale-names (mapv :name stale)})))

;; ── the fleet report ─────────────────────────────────────────────────────

(defn- finding-line [{:keys [name path] :as row}]
  (cond-> {:project name :path path}
    (seq (:interrupted row)) (assoc :interrupted (mapv clojure.core/name (:interrupted row)))
    (pos? (:stashes row 0)) (assoc :stashes (:stashes row))
    (pos? (:worktrees-stale row 0)) (assoc :worktrees-stale (:worktrees-stale row))))

(defn status
  "Hygiene across the registry, bounded and countable.

  `:listed` is what the manifest declares and `:scanned` is what was actually
  readable on disk; a caller that sees them diverge is looking at checkouts
  that were never fetched, which is a different fact from a clean fleet."
  ([] (status (workspace-root)))
  ([root]
   (let [root (.getCanonicalFile (io/file root))
         listed (west/projects root)
         rows (->> listed
                   (keep (fn [{:keys [name path checkout]}]
                           (when-let [seen (inspect-checkout checkout)]
                             (merge seen {:name name :path path}))))
                   vec)
         with (fn [pred] (filterv pred rows))
         interrupted-rows (with #(seq (:interrupted %)))
         stash-rows (with #(pos? (:stashes % 0)))
         stale-rows (with #(pos? (:worktrees-stale % 0)))
         findings (->> (concat interrupted-rows stash-rows stale-rows)
                       distinct
                       (sort-by (juxt (comp - :worktrees-stale) :name)))]
     {:schema "cloud.itonami.app.git-hygiene.v1"
      :root (.getPath root)
      :listed (count listed)
      :scanned (count rows)
      :unreadable (- (count listed) (count rows))
      :interrupted-repos (count interrupted-rows)
      :stash-repos (count stash-rows)
      :stashes (reduce + 0 (map :stashes stash-rows))
      :worktrees-live (reduce + 0 (map :worktrees-live rows))
      :worktrees-locked (reduce + 0 (map :worktrees-locked rows))
      :stale-worktree-repos (count stale-rows)
      :stale-worktrees (reduce + 0 (map :worktrees-stale stale-rows))
      :prunable? (boolean (seq stale-rows))
      :findings (mapv finding-line (take max-listed-findings findings))
      :findings-truncated? (> (count findings) max-listed-findings)})))

;; ── the one mutation ─────────────────────────────────────────────────────

(defn prunable
  "Checkouts this tool may prune, re-derived from disk at call time.

  Standalone clones only. A linked worktree's `.git` points at metadata shared
  with the repository that owns it, so pruning 'in' one would be operating on
  a root nobody granted."
  [root]
  (->> (west/projects root)
       (keep (fn [{:keys [name path checkout]}]
               (when-let [seen (inspect-checkout checkout)]
                 (when (and (:standalone? seen)
                            (pos? (:worktrees-stale seen))
                            (inside? root checkout))
                   (merge seen {:name name :path path})))))
       (sort-by (juxt (comp - :worktrees-stale) :name))
       vec))

(defn prune-checkout!
  "One `git worktree prune`, as a fixed argv. Public for deterministic tests."
  [checkout]
  (let [process (-> (ProcessBuilder. ["git" "-C" (str checkout) "worktree" "prune"])
                    (.redirectErrorStream true)
                    (.start))
        output (future (slurp (.getInputStream process)))]
    (when-not (.waitFor process prune-timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (future-cancel output)
      (throw (ex-info "git worktree prune timed out"
                      {:type :git-hygiene/timeout :checkout (str checkout)})))
    {:exit (.exitValue process)
     :output (str/trim (str @output))}))

(defn maintain!
  "Prune the stale registrations, bounded, and report what actually changed.

  `:pruned` counts registrations that are gone AFTER the run, re-read rather
  than assumed: a prune that exits 0 having removed nothing and a prune that
  removed eighty entries are the same exit code."
  ([] (maintain! (workspace-root) {}))
  ([root {:keys [limit] :or {limit default-prune-limit}}]
   (let [root (.getCanonicalFile (io/file root))
         before (status root)
         limit (max 1 (min max-prune-limit (long limit)))
         targets (take limit (prunable root))]
     (if (empty? targets)
       {:schema "cloud.itonami.app.git-hygiene-maintenance.v1"
        :action "none"
        :reason "no-stale-worktree-registrations"
        :before before
        :after before
        :pruned 0
        :repos []}
       (let [receipts
             (mapv (fn [{:keys [name checkout worktrees-stale]}]
                     (let [{:keys [exit output]} (prune-checkout! checkout)
                           left (:worktrees-stale (inspect-checkout checkout) 0)]
                       {:project name
                        :exit exit
                        :stale-before worktrees-stale
                        :stale-after left
                        :pruned (max 0 (- worktrees-stale left))
                        :output (when (seq output) output)}))
                   targets)
             after (status root)]
         {:schema "cloud.itonami.app.git-hygiene-maintenance.v1"
          :action "prune"
          :before before
          :after after
          :attempted (count receipts)
          :limit limit
          :remaining-repos (max 0 (- (:stale-worktree-repos before) (count receipts)))
          :pruned (reduce + 0 (map :pruned receipts))
          :failed (filterv #(not (zero? (:exit %))) receipts)
          :repos receipts})))))

(defn describe [tool-name]
  (case (str tool-name)
    "git_hygiene_status"
    "west に登録された全 checkout の git 衛生状態を読みます。書き換えません。"
    "git_hygiene_prune"
    (str "既に消えている working tree の登録だけを `git worktree prune` で片付けます。"
         "生きている worktree、locked worktree、stash、branch、未コミットの変更、"
         "remote には触れません。push もしません。")
    "git hygiene tool"))

(defn call! [tool-name]
  (case (str tool-name)
    "git_hygiene_status" (status)
    "git_hygiene_prune" (maintain!)
    (throw (ex-info "Unknown git-hygiene tool."
                    {:type :git-hygiene/unknown-tool :tool tool-name}))))
