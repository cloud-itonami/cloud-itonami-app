(ns cloud.itonami.app.workspace-tools-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.workspace-tools :as workspace])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "itonami-workspace-tools-"
                                      (make-array FileAttribute 0))))

(defn- git! [root & args]
  (let [process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               (into ["/usr/bin/git" "-C" (.getPath root)] args))
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (is (zero? exit) output)
    output))

(deftest a-workspace-is-an-exact-git-root
  (let [root (temp-dir)
        child (io/file root "src")]
    (.mkdirs child)
    (git! root "init" "-q" "--initial-branch=main")
    (is (= (.getCanonicalPath root) (workspace/admit-root (.getPath root))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Git repository root"
                          (workspace/admit-root (.getPath child))))))

(deftest filesystem-tools-cannot-escape-or-open-git-internals
  (let [root (temp-dir)
        outside (io/file (.getParentFile root) "outside.txt")]
    (git! root "init" "-q" "--initial-branch=main")
    (spit (io/file root "README.md") "hello\n")
    (spit outside "outside\n")
    (is (= "hello\n" (workspace/call! (.getPath root) "workspace_read"
                                        {:path "README.md"})))
    (doseq [path ["../outside.txt" (.getPath outside) ".git/config" ".GIT/config"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (workspace/call! (.getPath root) "workspace_read" {:path path}))))
    (let [link (io/file root "outside-link")]
      (Files/createSymbolicLink (.toPath link) (.toPath outside)
                                (make-array FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic link"
                            (workspace/call! (.getPath root) "workspace_read"
                                             {:path "outside-link"}))))))

(deftest reads-and-writes-are-distinct-capabilities
  (let [root (temp-dir)]
    (git! root "init" "-q" "--initial-branch=main")
    (spit (io/file root "README.md") "before\n")
    (is (false? (workspace/write-tool? "git_status")))
    (is (false? (workspace/write-tool? "workspace_read")))
    (is (true? (workspace/write-tool? "workspace_write_file")))
    (is (true? (workspace/write-tool? "git_commit")))
    (workspace/call! (.getPath root) "workspace_write_file"
                     {:path "README.md" :content "after\n"})
    (is (= "after\n" (slurp (io/file root "README.md"))))
    (is (str/includes? (workspace/call! (.getPath root) "git_status" {})
                       "README.md"))))

(deftest commit-includes-only-the-approved-paths
  (let [root (temp-dir)]
    (git! root "init" "-q" "--initial-branch=main")
    (spit (io/file root "approved.txt") "approved\n")
    (spit (io/file root "unrelated.txt") "unrelated\n")
    (git! root "add" "unrelated.txt")
    (workspace/call! (.getPath root) "git_commit"
                     {:paths ["approved.txt"] :message "approved only"})
    (is (= "approved.txt\n" (git! root "show" "--pretty=format:" "--name-only" "HEAD")))
    (is (str/includes? (git! root "diff" "--cached" "--name-only")
                       "unrelated.txt")
        "an unrelated staged file stays staged but is not committed")))

(deftest orientation-hands-over-the-top-level-instead-of-charging-for-it
  ;; MEASURED 2026-08-19 over 84 resident ticks: the tick may use two
  ;; repository reads, `workspace_list` took 103 of the 187 tool calls made,
  ;; and only 37 of 84 runs ever opened a FILE. Ten listed twice and stopped.
  ;; The budget was going on orientation.
  (let [dir (temp-dir)
        root (.getCanonicalPath dir)]
    ;; An admitted workspace is EXACTLY a git worktree root -- orientation
    ;; reads through the same admission `workspace_list` does, so a directory
    ;; that is not a repository correctly yields nothing.
    (git! dir "init" "-q" "--initial-branch=main")
    (try
      (spit (io/file root "README.md") "hi")
      (.mkdirs (io/file root "src"))
      (spit (io/file root "deps.edn") "{}")

      (testing "it names what is there"
        (let [o (workspace/orientation root)]
          (is (string? o))
          (is (re-find #"README\.md" o))
          (is (re-find #"deps\.edn" o))
          (is (re-find #"src/" o) "directories are marked as directories")))

      (testing "a workspace that is not there degrades to nil, never a throw"
        ;; A missing checkout must cost the turn nothing. Throwing here would
        ;; take down every tick for a Bot whose repo moved.
        (is (nil? (workspace/orientation nil)))
        (is (nil? (workspace/orientation "")))
        (is (nil? (workspace/orientation "/no/such/place/at/all")))
        (is (nil? (workspace/orientation (System/getProperty "java.io.tmpdir")))
            "a directory that is not a git root is not an admitted workspace"))

      (testing "it is bounded, and says so when it truncates"
        (doseq [i (range 80)] (spit (io/file root (format "f%03d.txt" i)) "x"))
        (let [o (workspace/orientation root)
              lines (str/split-lines o)]
          (is (<= (count lines) 61) "60 entries plus at most one summary line")
          (is (re-find #"more" (last lines))
              "a truncated listing says it was truncated rather than looking complete")))
      (finally
        (doseq [f (reverse (file-seq (io/file root)))] (io/delete-file f true))))))
