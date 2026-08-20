(ns cloud.itonami.app.virtual-shell-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.virtual-shell :as shell]
            [cloud.itonami.app.workspace-tools :as workspace-tools]))

(deftest one-bot-gets-one-safe-container-identity
  (let [a (shell/container-name "bot/a person's id")
        b (shell/container-name "bot/another")]
    (is (re-matches #"cloud-itonami-bot-[0-9a-f]{24}" a))
    (is (not= a b))
    (is (= a (shell/container-name "bot/a person's id")))))

(deftest creation-is-an-isolated-bounded-virtual-environment
  (let [argv (shell/create-argv
              {:bot-id "bot-1" :workspace "/tmp/example repo"
               :image "cloud-itonami-shell:test"})
        joined (pr-str argv)]
    (doseq [required ["--network" "none" "--cap-drop" "ALL"
                      "no-new-privileges:true" "--read-only"
                      "--cpus" "1" "--memory" "1g" "--pids-limit" "256"]]
      (is (some #{required} argv) required))
    (is (some #{"type=bind,src=/tmp/example repo,dst=/workspace"} argv))
    (is (not (.contains joined "docker.sock")))
    (is (= "sleep" (nth argv (- (count argv) 2))))
    (is (= "docker" (first argv)))))

(deftest a-command-is-one-argument-to-the-guest-not-a-host-shell
  (let [argv (shell/exec-argv "cloud-itonami-bot-0123456789abcdef01234567"
                              "printf ok; touch /tmp/inside" 17)]
    (is (= ["docker" "exec" "-i"
            "cloud-itonami-bot-0123456789abcdef01234567"
            "/usr/bin/timeout" "-s" "TERM" "17s"
            "/bin/bash" "-lc" "printf ok; touch /tmp/inside"]
           argv))))

(deftest shell-output-is-bounded
  (is (< (count (shell/bounded-output (apply str (repeat 40000 "x")))) 33000))
  (is (.endsWith (shell/bounded-output (apply str (repeat 40000 "x"))) "…")))

(deftest linked-worktrees-are-refused-instead-of-mounting-shared-git-metadata
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "cloud-itonami-vshell-worktree-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (io/file root ".git") "gitdir: /outside/grant\n")
    (with-redefs [workspace-tools/admit-root (constantly (.getPath root))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"standalone Git clone"
                            (shell/admit-workspace (.getPath root)))))))
