(ns cloud.itonami.app.disk-space
  "A bounded host tool for the disk-maintenance workforce Bot.

  The Bot never receives a shell or a path to delete.  The only mutation it can
  request is the reviewed disk-space-cleanup skill helper in the workspace,
  with its fixed `apply-extended` mode.  That helper owns the allowlist and
  refuses targets outside the current user's home."
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(def threshold-bytes (* 20 1024 1024 1024))
(def helper-timeout-seconds 300)
(def max-helper-output-chars 24000)

(def tool-definitions
  [{:name "disk_space_status"
    :description
    "Read free space on this Mac's data volume and the cleanup threshold."
    :parameters {:type "object" :properties {}}}
   {:name "disk_space_cleanup"
    :description
    (str "When free space is below the fixed threshold, reclaim only the "
         "regenerable cache classes allowlisted by the disk-space-cleanup "
         "skill. Repositories, worktrees, sessions, documents, databases, "
         "DataLad and browser profiles are preserved. (write)")
    :parameters {:type "object" :properties {}}}])

(defn tool? [tool-name]
  (contains? #{"disk_space_status" "disk_space_cleanup"} (str tool-name)))

(defn write-tool? [tool-name]
  (= "disk_space_cleanup" (str tool-name)))

(defn- workspace-root []
  (when-let [path (some-> (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT")
                          str not-empty)]
    (.getCanonicalFile (io/file path))))

(defn helper-file []
  (let [root (workspace-root)]
    (when-not root
      (throw (ex-info "CLOUD_ITONAMI_WORKSPACE_ROOT が設定されていません。"
                      {:type :disk-space/workspace-required})))
    (let [helper (.getCanonicalFile
                  (io/file root ".agents/skills/disk-space-cleanup/scripts/mac_disk_cleanup.zsh"))
          root-prefix (str (.getPath root) java.io.File/separator)]
      (when-not (.startsWith (.getPath helper) root-prefix)
        (throw (ex-info "disk cleanup helper が workspace 外を指しています。"
                        {:type :disk-space/helper-outside-workspace})))
      (when-not (and (.isFile helper) (.canExecute helper))
        (throw (ex-info "disk cleanup helper が実行できません。"
                        {:type :disk-space/helper-unavailable
                         :path (.getPath helper)})))
      helper)))

(defn usable-bytes []
  (.getUsableSpace (io/file "/System/Volumes/Data")))

(defn- gib [bytes]
  (/ (double bytes) 1073741824.0))

(defn status []
  (let [usable (usable-bytes)]
    {:schema "cloud.itonami.app.disk-space.v1"
     :usable-bytes usable
     :usable-gib (double (/ (Math/round (* 10.0 (gib usable))) 10.0))
     :threshold-bytes threshold-bytes
     :threshold-gib (long (gib threshold-bytes))
     :pressure? (< usable threshold-bytes)}))

(defn run-helper!
  "Run one fixed helper mode without a shell. Public for deterministic tests."
  [mode]
  (when-not (contains? #{"audit" "apply-extended"} mode)
    (throw (ex-info "unsupported disk cleanup mode"
                    {:type :disk-space/invalid-mode :mode mode})))
  (let [process (-> (ProcessBuilder. [(str (helper-file)) mode])
                    (.redirectErrorStream true)
                    (.start))
        output (future (slurp (.getInputStream process)))]
    (when-not (.waitFor process helper-timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (future-cancel output)
      (throw (ex-info "disk cleanup helper timed out"
                      {:type :disk-space/timeout})))
    (let [body @output
          clipped (subs body 0 (min max-helper-output-chars (count body)))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "disk cleanup helper failed"
                        {:type :disk-space/helper-failed
                         :exit (.exitValue process)
                         :output clipped})))
      {:exit 0 :output clipped :truncated? (> (count body) (count clipped))})))

(defn maintain! []
  (let [before (status)]
    (if-not (:pressure? before)
      {:schema "cloud.itonami.app.disk-space-maintenance.v1"
       :action "none"
       :reason "above-threshold"
       :before before
       :after before}
      (let [receipt (run-helper! "apply-extended")
            after (status)]
        {:schema "cloud.itonami.app.disk-space-maintenance.v1"
         :action "cleanup"
         :before before
         :after after
         :reclaimed-bytes (max 0 (- (:usable-bytes after)
                                     (:usable-bytes before)))
         :helper receipt}))))

(defn describe [tool-name]
  (case (str tool-name)
    "disk_space_status" "この Mac の空き容量を読みます。"
    "disk_space_cleanup"
    (str "空きが 20 GiB 未満なら、disk-space-cleanup skill の固定 allowlist にある"
         "再生成可能 cache だけを削除します。repo、worktree、session、文書、database、"
         "DataLad、browser profile は削除しません。")
    "disk tool"))

(defn call! [tool-name]
  (case (str tool-name)
    "disk_space_status" (status)
    "disk_space_cleanup" (maintain!)
    (throw (ex-info "Unknown disk-space tool."
                    {:type :disk-space/unknown-tool :tool tool-name}))))
