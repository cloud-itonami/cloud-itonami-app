(ns cloud.itonami.app.git-hygiene-test
  "Real repositories, real worktrees, real `git worktree prune`.

  A fixture that only writes `.git/worktrees/<name>/gitdir` by hand would test
  this namespace's reader against this namespace's own idea of the format, and
  would say nothing about whether the one mutation removes what it claims to
  remove or leaves alone what it claims to leave alone. So the repositories
  here are made by git and the working trees are really deleted."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.git-hygiene :as subject]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- git! [dir & args]
  (let [result (apply shell/sh (concat ["git" "-C" (str dir)] args))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "git " (str/join " " args) " failed")
                      {:dir (str dir) :err (:err result)})))
    result))

(defn- write! [root path text]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file text)
    file))

(defn- delete-tree! [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)] (delete-tree! child)))
  (.delete file))

(defn- init-repo! [root relative]
  (let [repo (io/file root relative)]
    (.mkdirs repo)
    (git! repo "init" "-q" "-b" "main")
    (git! repo "config" "user.email" "test@example.invalid")
    (git! repo "config" "user.name" "Test")
    (write! repo "README.md" "seed\n")
    (git! repo "add" "README.md")
    (git! repo "commit" "-q" "-m" "seed")
    repo))

(defn- manifest! [root projects]
  (write! root "manifest/west.yml"
          (str "manifest:\n  projects:\n"
               (str/join
                (for [[name path] projects]
                  (str "    - name: " name "\n      remote: test\n"
                       "      revision: abc123\n      path: " path "\n"))))))

(defn- add-worktree! [repo where branch]
  (git! repo "worktree" "add" "-q" "-b" branch (str where) "main")
  (io/file where))

(defn- fixture
  "One registry with three repositories:

    stale    a worktree whose directory was deleted            -> prunable
    live     a worktree that still exists                      -> must survive
    locked   a deleted worktree that git was told to protect   -> must survive"
  []
  (let [root (temp-dir "itonami-git-hygiene-")
        outside (temp-dir "itonami-git-hygiene-trees-")
        stale (init-repo! root "orgs/test/stale")
        live (init-repo! root "orgs/test/live")
        locked (init-repo! root "orgs/test/locked")]
    (manifest! root [["stale" "orgs/test/stale"]
                     ["live" "orgs/test/live"]
                     ["locked" "orgs/test/locked"]])
    (delete-tree! (add-worktree! stale (io/file outside "stale-tree") "wt-stale"))
    (add-worktree! live (io/file outside "live-tree") "wt-live")
    (let [tree (add-worktree! locked (io/file outside "locked-tree") "wt-locked")]
      (git! locked "worktree" "lock" (str tree))
      (delete-tree! tree))
    {:root root :stale stale :live live :locked locked}))

(defn- registrations [repo]
  (set (map #(.getName %)
            (or (some-> (io/file repo ".git" "worktrees") .listFiles seq) []))))

(deftest status-separates-what-is-gone-from-what-is-merely-registered
  (let [{:keys [root]} (fixture)
        report (subject/status root)]
    (is (= 3 (:listed report)))
    (is (= 3 (:scanned report)) "every registered checkout was readable")
    (is (= 0 (:unreadable report)))
    (is (= 1 (:stale-worktree-repos report)))
    (is (= 1 (:stale-worktrees report)))
    (is (true? (:prunable? report)))
    (testing "a live worktree is not a finding"
      (is (= 1 (:worktrees-live report))))
    (testing "a locked worktree is counted apart from a prunable one"
      (is (= 1 (:worktrees-locked report)))
      (is (= ["stale"] (mapv :project (:findings report)))))))

(deftest prune-removes-the-gone-registration-and-nothing-else
  (let [{:keys [root stale live locked]} (fixture)
        receipt (subject/maintain! root {})]
    (is (= "prune" (:action receipt)))
    (is (= 1 (:pruned receipt)))
    (is (empty? (:failed receipt)))
    (is (empty? (registrations stale)) "the deleted worktree's bookkeeping is gone")
    (is (= 1 (count (registrations live))) "a live worktree survives a prune pass")
    (is (= 1 (count (registrations locked)))
        "git refuses to prune a locked worktree, and this tool does not override it")
    (testing "the receipt is measured after the fact, not predicted"
      (is (= 1 (get-in receipt [:before :stale-worktrees])))
      (is (= 0 (get-in receipt [:after :stale-worktrees]))))))

(deftest a-clean-registry-reports-none-and-launches-no-git
  (let [root (temp-dir "itonami-git-hygiene-clean-")]
    (init-repo! root "orgs/test/only")
    (manifest! root [["only" "orgs/test/only"]])
    (let [report (subject/status root)
          receipt (subject/maintain! root {})]
      (is (false? (:prunable? report)))
      (is (= 0 (:stale-worktrees report)))
      (is (= "none" (:action receipt)))
      (is (= "no-stale-worktree-registrations" (:reason receipt)))
      (is (= 0 (:pruned receipt))))))

(deftest interrupted-merge-state-is-reported-and-never-acted-on
  (let [{:keys [root live]} (fixture)]
    (spit (io/file live ".git" "MERGE_HEAD") "0000000000000000000000000000000000000000\n")
    (let [report (subject/status root)
          before (registrations live)
          receipt (subject/maintain! root {})]
      (is (= 1 (:interrupted-repos report)))
      (is (= ["merge"] (:interrupted (first (filter #(= "live" (:project %))
                                                    (:findings report))))))
      (is (.exists (io/file live ".git" "MERGE_HEAD"))
          "resolving a conflict is a judgement about content; this tool does not make it")
      (is (= before (registrations live)))
      (is (= 1 (:pruned receipt)) "the unrelated stale registration is still pruned"))))

(deftest stashes-are-counted-and-left-where-they-are
  (let [{:keys [root live]} (fixture)]
    (write! live "README.md" "dirty\n")
    (git! live "stash" "push" "-q" "-m" "wip")
    (let [report (subject/status root)]
      (is (= 1 (:stash-repos report)))
      (is (= 1 (:stashes report)))
      (subject/maintain! root {})
      (is (str/includes? (:out (git! live "stash" "list")) "wip")
          "the runbook requires archiving before any drop; this tool never drops"))))

(deftest a-registry-it-cannot-read-refuses-instead-of-reporting-clean
  (let [root (temp-dir "itonami-git-hygiene-nomanifest-")]
    (init-repo! root "orgs/test/only")
    (is (thrown? clojure.lang.ExceptionInfo (subject/status root))
        "a scan that could not read the manifest must not look like a clean fleet")))

(deftest an-unfetched-checkout-is-counted-as-unreadable-not-as-clean
  (let [root (temp-dir "itonami-git-hygiene-partial-")]
    (init-repo! root "orgs/test/present")
    (manifest! root [["present" "orgs/test/present"]
                     ["absent" "orgs/test/absent"]])
    (let [report (subject/status root)]
      (is (= 2 (:listed report)))
      (is (= 1 (:scanned report)))
      (is (= 1 (:unreadable report))))))

(deftest the-prune-limit-bounds-one-pass-and-says-what-is-left
  (let [root (temp-dir "itonami-git-hygiene-limit-")
        outside (temp-dir "itonami-git-hygiene-limit-trees-")]
    (doseq [n ["a" "b"]]
      (let [repo (init-repo! root (str "orgs/test/" n))]
        (delete-tree! (add-worktree! repo (io/file outside (str n "-tree")) (str "wt-" n)))))
    (manifest! root [["a" "orgs/test/a"] ["b" "orgs/test/b"]])
    (let [receipt (subject/maintain! root {:limit 1})]
      (is (= 1 (:attempted receipt)))
      (is (= 1 (:pruned receipt)))
      (is (= 1 (:remaining-repos receipt)))
      (is (= 1 (get-in receipt [:after :stale-worktree-repos]))))))
