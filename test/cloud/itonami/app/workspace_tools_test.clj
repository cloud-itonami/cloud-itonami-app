(ns cloud.itonami.app.workspace-tools-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
