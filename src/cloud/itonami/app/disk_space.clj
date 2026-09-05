(ns cloud.itonami.app.disk-space
  "Bounded host tools for the resident Disk Maintainer.

  No tool accepts a shell command or a path to delete. The existing cleanup
  helper keeps its fixed allowlist. Additional reclamation is a two-step
  protocol: inventory mints evidence-bound candidate ids from fixed roots,
  then reclaim re-discovers and revalidates those ids immediately before a
  mutation. Unknown, stale, open, symlinked, Git-owned and unclassified paths
  fail closed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file FileVisitResult Files LinkOption Path SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]
           [java.util EnumSet]
           [java.util.concurrent TimeUnit]))

(def threshold-bytes (* 20 1024 1024 1024))
(def helper-timeout-seconds 300)
(def inspection-timeout-seconds 10)
(def max-helper-output-chars 24000)
(def max-inventory-candidates 40)
(def max-reclaim-candidates 8)
(def max-reclaim-bytes (* 10 1024 1024 1024))
(def stable-delta-bytes (* 64 1024 1024))

(def tool-definitions
  [{:name "disk_space_status"
    :description "Read free space on this Mac's data volume and the cleanup threshold."
    :parameters {:type "object" :properties {} :additionalProperties false}}
   {:name "disk_space_cleanup"
    :description
    (str "When free space is below the fixed threshold, reclaim only the "
         "regenerable cache classes allowlisted by the disk-space-cleanup "
         "skill. Repositories, worktrees, sessions, documents, databases, "
         "DataLad and browser profiles are preserved. (write)")
    :parameters {:type "object" :properties {} :additionalProperties false}}
   {:name "disk_space_inventory"
    :description
    (str "Inspect only fixed temporary/cache roots and the resident-release "
         "footprint, then return evidence-bound "
         "candidate ids. Git-owned, open, symlinked and unknown paths are not "
         "reclaimable; model files and resident releases are review-required.")
    :parameters {:type "object" :properties {} :additionalProperties false}}
   {:name "disk_space_reclaim"
    :description
    (str "Under disk pressure, re-discover and revalidate up to 8 inventory "
         "candidate ids, then reclaim at most 10 GiB. Raw paths and shell "
         "commands are never accepted. (write)")
    :parameters {:type "object"
                 :properties {:candidate_ids {:type "array" :items {:type "string"}
                                               :minItems 1
                                               :maxItems max-reclaim-candidates}}
                 :required ["candidate_ids"]
                 :additionalProperties false}}])

(def ^:private tool-names (set (map :name tool-definitions)))
(def ^:private write-tool-names #{"disk_space_cleanup" "disk_space_reclaim"})
(defn tool? [tool-name] (contains? tool-names (str tool-name)))
(defn write-tool? [tool-name] (contains? write-tool-names (str tool-name)))

(defn- workspace-root []
  (when-let [path (some-> (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT") str not-empty)]
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
                        {:type :disk-space/helper-unavailable :path (.getPath helper)})))
      helper)))

(defn usable-bytes [] (.getUsableSpace (io/file "/System/Volumes/Data")))
(defn- gib [bytes] (/ (double bytes) 1073741824.0))

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
                    (.redirectErrorStream true) .start)
        output (future (slurp (.getInputStream process)))]
    (when-not (.waitFor process helper-timeout-seconds TimeUnit/SECONDS)
      (.destroyForcibly process)
      (future-cancel output)
      (throw (ex-info "disk cleanup helper timed out" {:type :disk-space/timeout})))
    (let [body @output
          clipped (subs body 0 (min max-helper-output-chars (count body)))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "disk cleanup helper failed"
                        {:type :disk-space/helper-failed :exit (.exitValue process)
                         :output clipped})))
      {:exit 0 :output clipped :truncated? (> (count body) (count clipped))})))

(defn maintain!
  ([] (maintain! (status)))
  ([before]
   (if-not (:pressure? before)
     {:schema "cloud.itonami.app.disk-space-maintenance.v1"
      :action "none" :reason "above-threshold" :before before :after before}
     (let [receipt (run-helper! "apply-extended")
           after (status)]
       {:schema "cloud.itonami.app.disk-space-maintenance.v1"
        :action "cleanup" :before before :after after
        :reclaimed-bytes (max 0 (- (:usable-bytes after) (:usable-bytes before)))
        :helper receipt}))))

;; Tests redefine this seam. Production roots cannot be selected by tool input.
(defn candidate-roots []
  [{:kind :temporary :path (io/file "/private/tmp")}
   {:kind :uv-cache :path (io/file (System/getProperty "user.home") ".cache" "uv")}
   ;; This root is observation-only.  A running/rollback release is not cache,
   ;; and the reclaim tool never admits its :review-required receipt.
   {:kind :itonami-releases
    :path (io/file (System/getProperty "user.home") ".cloud-itonami" "releases")}])

(defn- nofollow-options [] (make-array LinkOption 0))
(defn- path-exists? [^Path p] (Files/exists p (nofollow-options)))
(defn- symlink? [^Path p] (Files/isSymbolicLink p))
(defn- real-path [^Path p] (.toRealPath p (nofollow-options)))
(defn- under? [^Path root ^Path candidate]
  (let [r (.normalize (.toAbsolutePath root)) c (.normalize (.toAbsolutePath candidate))]
    (and (not= r c) (.startsWith c r))))

(defn- walk-paths [^Path root depth]
  (when (and (path-exists? root) (not (symlink? root)))
    (let [paths (atom [])]
      (Files/walkFileTree
       root
       (EnumSet/noneOf java.nio.file.FileVisitOption)
       (int depth)
       (proxy [SimpleFileVisitor] []
         (preVisitDirectory [dir _]
           (swap! paths conj dir)
           FileVisitResult/CONTINUE)
         (visitFile [file _]
           (swap! paths conj file)
           FileVisitResult/CONTINUE)
         (visitFileFailed [_ _] FileVisitResult/SKIP_SUBTREE)))
      @paths)))

(defn- directory-bytes [^Path root]
  (let [total (atom 0) failure (atom nil)]
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (visitFile [file attrs]
         (when (and (.isRegularFile ^BasicFileAttributes attrs)
                    (not (Files/isSymbolicLink ^Path file)))
           (swap! total + (.size ^BasicFileAttributes attrs)))
         FileVisitResult/CONTINUE)
       (visitFileFailed [file error]
         (reset! failure {:file file :error error})
         FileVisitResult/TERMINATE)))
    (when @failure
      (throw (ex-info "candidate size could not be verified"
                      {:type :disk-space/candidate-unverified
                       :path (str (:file @failure))}
                      (:error @failure))))
    @total))

(defn- git-marker? [^Path p]
  (or (path-exists? (.resolve p ".git")) (path-exists? (.resolve p ".gitmodules"))))

(defn- nearest-git-root [^Path root ^Path candidate]
  (loop [p (.getParent candidate)]
    (cond (nil? p) nil
          (= p root) (when (git-marker? p) p)
          (git-marker? p) p
          :else (recur (.getParent p)))))

(defn- run-inspection-process [argv]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List (mapv str argv))
                      (.redirectErrorStream true) .start)
          output (future (slurp (.getInputStream process)))]
      (if-not (.waitFor process inspection-timeout-seconds TimeUnit/SECONDS)
        (do (.destroyForcibly process) (future-cancel output)
            {:state :unverified})
        {:state :complete :exit (.exitValue process)
         :output (subs @output 0 (min 4096 (count @output)))}))
    (catch Exception _ {:state :unverified})))

(defn git-evidence
  "Git containment evidence. An ignored, wholly untracked node_modules tree is
  regenerable even when its parent is a worktree; this never admits the
  worktree itself or any tracked content. Public for deterministic tests."
  [^Path root ^Path candidate kind]
  (let [git-root (nearest-git-root root candidate)
        nested? (boolean (some #(and (not= candidate %) (git-marker? %))
                               (walk-paths candidate 4)))]
    (if-not git-root
      {:git-owned? false :nested-git? nested?
       :safe? (or (not nested?)
                  (contains? #{:temporary-node-modules :pnpm-temporary-store
                               :uv-cache} kind))}
      (let [relative (str (.relativize git-root candidate))
            ignored (run-inspection-process
                     ["/usr/bin/git" "-C" git-root "check-ignore" "-q" "--" relative])
            tracked (run-inspection-process
                     ["/usr/bin/git" "-C" git-root "ls-files" "--" relative])
            verified? (and (= :complete (:state ignored))
                           (= :complete (:state tracked)))
            ignored? (and verified? (zero? (:exit ignored)))
            tracked? (or (not verified?) (not (str/blank? (:output tracked))))]
        {:git-owned? true :nested-git? nested?
         :git-ignored? ignored? :git-tracked? tracked?
         :safe? (and (= :temporary-node-modules kind)
                     ignored? (not tracked?))}))))

(defn open-file-state
  "Return :clear, :open or :unverified using one fixed executable and path."
  [^Path path]
  (let [lsof (io/file "/usr/sbin/lsof")]
    (if-not (.canExecute lsof)
      :unverified
      (try
        (let [process (-> (ProcessBuilder. [(.getPath lsof) "-t" "+D" (str path)])
                          (.redirectErrorStream true) .start)]
          (if-not (.waitFor process inspection-timeout-seconds TimeUnit/SECONDS)
            (do (.destroyForcibly process) :unverified)
            (case (.exitValue process) 0 :open 1 :clear :unverified)))
        (catch Exception _ :unverified)))))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- candidate-id [^Path path kind bytes]
  (let [attrs (Files/readAttributes path BasicFileAttributes (nofollow-options))]
    (sha256 (str (real-path path) "\u0000" (name kind) "\u0000" bytes "\u0000"
                 (.lastModifiedTime attrs) "\u0000" (.fileKey attrs)))))

(defn- lockfile? [^Path parent]
  (some #(path-exists? (.resolve parent %))
        ["pnpm-lock.yaml" "package-lock.json" "yarn.lock" "bun.lock" "bun.lockb"]))

(defn- pnpm-store? [^Path path]
  (boolean
   (some (fn [^Path version]
           (and (Files/isDirectory version (nofollow-options))
                (re-matches #"v[0-9]+" (str (.getFileName version)))
                (Files/isDirectory (.resolve version "files") (nofollow-options))
                (Files/isDirectory (.resolve version "index") (nofollow-options))))
         (walk-paths path 1))))

(defn- cmake-source [^Path path]
  (let [cache (.resolve path "CMakeCache.txt")]
    (when (path-exists? cache)
      (some->> (str/split-lines (slurp (str cache)))
               (some #(second (re-matches #"CMAKE_HOME_DIRECTORY:INTERNAL=(.+)" %)))
               io/file .toPath))))

(defn- gguf-tree? [^Path path]
  (boolean (some #(and (Files/isRegularFile ^Path % (nofollow-options))
                       (str/ends-with? (str/lower-case (str (.getFileName ^Path %))) ".gguf"))
                 (walk-paths path 2))))

(defn- classify [root-kind ^Path path]
  (let [name (str (.getFileName path)) parent (.getParent path)
        source (when (= root-kind :temporary) (cmake-source path))]
    (cond
      (= root-kind :uv-cache) {:kind :uv-cache :recovery :native-cache-manager}
      (= root-kind :itonami-releases)
      {:kind :resident-releases :recovery :review-required}
      (and (= root-kind :temporary) (re-matches #"pnpm-.+-store" name)
           (pnpm-store? path))
      {:kind :pnpm-temporary-store :recovery :delete-tree}
      (and (= root-kind :temporary) (= "node_modules" name) parent
           (path-exists? (.resolve parent "package.json")) (lockfile? parent))
      {:kind :temporary-node-modules :recovery :delete-tree}
      (and source (path-exists? source)
           (not= (.normalize (.toAbsolutePath path))
                 (.normalize (.toAbsolutePath ^Path source)))
           (not (under? path source)))
      {:kind :cmake-build :recovery :delete-tree}
      (and (= root-kind :temporary) (gguf-tree? path))
      {:kind :model-artifact :recovery :review-required}
      :else nil)))

(defn- inspect-candidate [^Path root root-kind ^Path path]
  (try
    (when-let [{:keys [kind recovery]} (classify root-kind path)]
      (let [bytes (directory-bytes path)
            ;; The aggregate release root deliberately contains Git checkouts.
            ;; Walking each checkout to infer disposable Git state would be
            ;; both expensive and the wrong authority question: the whole
            ;; class remains review-only.
            git (if (= :resident-releases kind)
                  {:git-owned? true :nested-git? true :safe? false}
                  (git-evidence root path kind))
            open-state (open-file-state path)
            reclaimable? (and (not= :review-required recovery) (:safe? git)
                              (= :clear open-state) (not (symlink? path)))]
        {:candidate-id (candidate-id path kind bytes)
         :kind kind :bytes bytes
         :locator {:root (name root-kind) :relative (str (.relativize root path))}
         :decision (if reclaimable? :reclaimable :review-required)
         :recovery recovery
         :evidence (merge {:fixed-root (name root-kind)
                           :open-file-state open-state :symlink? (symlink? path)}
                          (dissoc git :safe?))}))
    (catch Exception _ nil)))

(defn- discovery-paths [{:keys [kind path]}]
  (let [root (.toPath ^java.io.File path)]
    (when (and (path-exists? root) (Files/isDirectory root (nofollow-options))
               (not (symlink? root)))
      (if (contains? #{:uv-cache :itonami-releases} kind)
        [root]
        (let [all (walk-paths root 4)
              whole (filter #(and (= root (.getParent ^Path %))
                                  (or (re-matches #"pnpm-.+-store"
                                                  (str (.getFileName ^Path %)))
                                      (cmake-source %)
                                      (gguf-tree? %))) all)
              modules (filter #(= "node_modules" (str (.getFileName ^Path %))) all)
              selected (set whole)]
          (->> (concat whole modules)
               (remove #(some (fn [^Path p]
                                (and (not= p %) (.startsWith ^Path % p))) selected))
               distinct))))))

(defn- discover-located []
  (let [roots (candidate-roots)
        inspected (->> roots
                       (mapcat (fn [{:keys [kind path] :as root-spec}]
                                 (let [root (.toPath ^java.io.File path)]
                                   (keep (fn [candidate-path]
                                           (when-let [candidate
                                                      (inspect-candidate root kind candidate-path)]
                                             {:root root :path candidate-path
                                              :candidate candidate}))
                                         (discovery-paths root-spec)))))
                       (sort-by (comp :bytes :candidate) >) vec)]
    {:roots roots
     :truncated? (> (count inspected) max-inventory-candidates)
     :located (vec (take max-inventory-candidates inspected))}))

(defn inventory []
  (let [{:keys [roots located truncated?]} (discover-located)
        candidates (mapv :candidate located)]
    {:schema "cloud.itonami.app.disk-space-inventory.v1"
     :roots (mapv (comp name :kind) roots)
     :candidate-count (count candidates)
     :truncated? truncated?
     :reclaimable-bytes (reduce + 0 (map :bytes
                                         (filter #(= :reclaimable (:decision %)) candidates)))
     :review-required-bytes
     (reduce + 0 (map :bytes
                      (filter #(= :review-required (:decision %)) candidates)))
     :candidates candidates}))

(defn- delete-tree! [^Path root ^Path target]
  (when-not (and (under? root target) (path-exists? target) (not (symlink? target)))
    (throw (ex-info "candidate is outside its fixed root or is a symlink"
                    {:type :disk-space/candidate-boundary})))
  (Files/walkFileTree
   target
   (proxy [SimpleFileVisitor] []
     (visitFile [file _] (Files/delete file) FileVisitResult/CONTINUE)
     (postVisitDirectory [dir error]
       (when error (throw error))
       (Files/delete dir)
       FileVisitResult/CONTINUE))))

(defn run-native-cache-clean! [kind]
  (when-not (= :uv-cache kind)
    (throw (ex-info "unsupported native cache manager"
                    {:type :disk-space/invalid-native-manager :kind kind})))
  (if-let [uv (some #(when (.canExecute ^java.io.File %) %)
                    [(io/file "/opt/homebrew/bin/uv") (io/file "/usr/local/bin/uv")])]
    (let [process (-> (ProcessBuilder. [(str uv) "cache" "clean"])
                      (.redirectErrorStream true) .start)]
      (when-not (.waitFor process helper-timeout-seconds TimeUnit/SECONDS)
        (.destroyForcibly process)
        (throw (ex-info "uv cache clean timed out" {:type :disk-space/timeout})))
      (when-not (zero? (.exitValue process))
        (throw (ex-info "uv cache clean failed"
                        {:type :disk-space/native-manager-failed :exit (.exitValue process)})))
      {:exit 0})
    (throw (ex-info "uv cache manager unavailable"
                    {:type :disk-space/native-manager-unavailable}))))

(defn- validate-reclaim-input [input]
  (let [input (or input {}) ids (or (:candidate_ids input) (:candidate-ids input))
        allowed #{:candidate_ids :candidate-ids}]
    (when (seq (remove allowed (keys input)))
      (throw (ex-info "disk reclaim accepts candidate ids only"
                      {:type :disk-space/invalid-input})))
    (when-not (and (sequential? ids) (seq ids) (<= (count ids) max-reclaim-candidates)
                   (every? #(and (string? %) (re-matches #"[0-9a-f]{64}" %)) ids)
                   (= (count ids) (count (distinct ids))))
      (throw (ex-info "candidate_ids must contain 1-8 unique inventory ids"
                      {:type :disk-space/invalid-candidate-ids})))
    (vec ids)))

(defn reclaim! [input]
  (let [ids (validate-reclaim-input input) before (status)]
    (if-not (:pressure? before)
      {:schema "cloud.itonami.app.disk-space-reclaim.v1"
       :action "none" :reason "above-threshold" :before before :after before
       :requested-candidate-ids ids :reclaimed-candidate-ids [] :reclaimed-bytes 0}
      (let [by-id (into {} (map (juxt (comp :candidate-id :candidate) identity)
                                    (:located (discover-located))))
            located (mapv by-id ids)
            candidates (mapv :candidate located)]
        (when (some nil? located)
          (throw (ex-info "candidate id is unknown or stale"
                          {:type :disk-space/stale-candidate})))
        (when-not (every? #(= :reclaimable (:decision %)) candidates)
          (throw (ex-info "candidate is not reclaimable"
                          {:type :disk-space/candidate-not-reclaimable})))
        (when (> (reduce + 0 (map :bytes candidates)) max-reclaim-bytes)
          (throw (ex-info "candidate byte budget exceeded"
                          {:type :disk-space/reclaim-budget-exceeded})))
        (doseq [{:keys [root path candidate]} located]
          (let [current-candidate (inspect-candidate root
                                                     (keyword (get-in candidate
                                                                      [:evidence :fixed-root]))
                                                     path)
                current {:root root :path path :candidate current-candidate}]
            (when-not (and (= (:candidate-id candidate) (:candidate-id current-candidate))
                           (= :reclaimable (:decision current-candidate)))
              (throw (ex-info "candidate changed before reclamation"
                              {:type :disk-space/stale-candidate})))
            (if (= :native-cache-manager (get-in current [:candidate :recovery]))
              (run-native-cache-clean! (get-in current [:candidate :kind]))
              (delete-tree! (:root current) (:path current)))))
        (let [after (status)]
          {:schema "cloud.itonami.app.disk-space-reclaim.v1"
           :action "reclaim" :before before :after after
           :requested-candidate-ids ids :reclaimed-candidate-ids ids
           :reclaimed-bytes (max 0 (- (:usable-bytes after) (:usable-bytes before)))})))))

(defn settle! [] (Thread/sleep 1000))
(defn- stable-status []
  (settle!)
  (let [first (status)]
    (settle!)
    (let [second (status)]
      {:first first :second second
       :stable? (<= (Math/abs (long (- (:usable-bytes second) (:usable-bytes first))))
                    stable-delta-bytes)})))

(defn- select-bounded [candidates]
  (loop [remaining (filter #(= :reclaimable (:decision %)) candidates)
         selected [] bytes 0]
    (if-let [candidate (first remaining)]
      (let [next-bytes (+ bytes (:bytes candidate))]
        (if (and (< (count selected) max-reclaim-candidates)
                 (<= next-bytes max-reclaim-bytes))
          (recur (rest remaining) (conj selected candidate) next-bytes)
          (recur (rest remaining) selected bytes)))
      selected)))

(defn reconcile!
  "Complete one provider-independent pressure cycle with bounded receipts."
  ([] (reconcile! (status)))
  ([before]
   (let [fixed (maintain! before) after-fixed (:after fixed)]
     (if-not (:pressure? after-fixed)
       (assoc fixed :schema "cloud.itonami.app.disk-space-maintenance.v2")
       (let [found (inventory)
             selected (select-bounded (:candidates found))
             reclaimed (when (seq selected)
                         (reclaim! {:candidate_ids (mapv :candidate-id selected)}))
             stable (stable-status) after (:second stable)]
         {:schema "cloud.itonami.app.disk-space-maintenance.v2"
          :action (if reclaimed "cleanup-and-reclaim" "cleanup")
          :before before :after after :fixed-cleanup fixed
         :inventory (select-keys found [:candidate-count :truncated?
                                        :reclaimable-bytes :review-required-bytes])
          :selected-candidate-ids (mapv :candidate-id selected)
          :review-required (->> (:candidates found)
                                (filter #(= :review-required (:decision %)))
                                (mapv #(select-keys % [:candidate-id :kind :bytes
                                                       :locator :decision])))
          :candidate-reclaim reclaimed :stable-observation stable
          :reclaimed-bytes (max 0 (- (:usable-bytes after) (:usable-bytes before)))})))))

(defn describe
  ([tool-name] (describe tool-name {}))
  ([tool-name args]
   (case (str tool-name)
     "disk_space_status" "この Mac の空き容量を読みます。"
     "disk_space_cleanup" "圧迫時に固定 allowlist の再生成可能 cache だけを掃除します。"
     "disk_space_inventory" "固定 temporary/cache 領域から削除せず候補 receipt を発行します。"
     "disk_space_reclaim" (str (count (or (:candidate_ids args) (:candidate-ids args)))
                                " 件の候補 ID を再検証し、圧迫時だけ回収します。")
     "disk tool")))

(defn call!
  ([tool-name] (call! tool-name {}))
  ([tool-name input]
   (case (str tool-name)
     "disk_space_status" (status)
     "disk_space_cleanup" (maintain!)
     "disk_space_inventory" (inventory)
     "disk_space_reclaim" (reclaim! input)
     (throw (ex-info "Unknown disk-space tool."
                     {:type :disk-space/unknown-tool :tool tool-name})))))
