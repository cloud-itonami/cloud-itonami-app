(ns cloud.itonami.app.workspace-tools
  "Bounded filesystem and local-Git tools for one explicitly selected repo.

  There is deliberately no shell tool. Reads stay inside the exact Git root;
  writes and commits are separate tool names so the Bot host can hold them for
  human approval before this namespace is called."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private max-file-bytes (* 256 1024))
(def ^:private max-output-chars 24000)
(def ^:private max-search-files 300)
(def ^:private no-links (make-array LinkOption 0))

(def tool-definitions
  [{:name "workspace_list"
    :description "List one directory inside the selected local Git repository."
    :parameters {:type "object" :properties {:path {:type "string"}}}}
   {:name "workspace_read"
    :description "Read one UTF-8 text file inside the selected local Git repository."
    :parameters {:type "object" :properties {:path {:type "string"}}
                 :required ["path"]}}
   {:name "workspace_search"
    :description "Search text files in the selected local Git repository for a literal string."
    :parameters {:type "object"
                 :properties {:query {:type "string"}}
                 :required ["query"]}}
   {:name "workspace_write_file"
    :description "Replace one UTF-8 text file inside the selected repository. Requires approval. (write)"
    :parameters {:type "object"
                 :properties {:path {:type "string"} :content {:type "string"}}
                 :required ["path" "content"]}}
   {:name "git_status"
    :description "Read local Git status for the selected repository."
    :parameters {:type "object" :properties {}}}
   {:name "git_diff"
    :description "Read the unstaged Git diff, optionally for one path."
    :parameters {:type "object" :properties {:path {:type "string"}}}}
   {:name "git_log"
    :description "Read recent local Git commits."
    :parameters {:type "object" :properties {:limit {:type "integer"}}}}
   {:name "git_commit"
    :description "Stage only named paths and create one local commit. Requires approval; never pushes. (write)"
    :parameters {:type "object"
                 :properties {:paths {:type "array" :items {:type "string"}}
                              :message {:type "string"}}
                 :required ["paths" "message"]}}])

(def ^:private names (into #{} (map :name) tool-definitions))
(def ^:private writes #{"workspace_write_file" "git_commit"})

(defn tool? [name] (contains? names (str name)))
(defn write-tool? [name] (contains? writes (str name)))

(defn- bounded [value]
  (let [value (str value)]
    (if (> (count value) max-output-chars)
      (str (subs value 0 max-output-chars) "…")
      value)))

(defn- run-git
  [root args]
  (let [command (into ["/usr/bin/git" "-C" root] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectErrorStream true)))
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (if (zero? exit)
      (bounded output)
      (throw (ex-info "Git operation failed."
                      {:type :workspace/git-failed :exit exit
                       :operation (first args) :output (bounded output)})))))

(defn admit-root
  "Return the canonical path only when `value` is exactly a Git worktree root."
  [value]
  (let [directory (.getCanonicalFile (io/file (str value)))]
    (when-not (.isDirectory directory)
      (throw (ex-info "Workspace directory does not exist."
                      {:type :workspace/root-required})))
    (let [root (-> (run-git (.getPath directory)
                            ["rev-parse" "--show-toplevel"])
                   str/trim
                   io/file
                   .getCanonicalPath)]
      (when-not (= root (.getPath directory))
        (throw (ex-info "Select the exact Git repository root."
                        {:type :workspace/exact-git-root-required})))
      root)))

(defn- relative-parts [value]
  (let [value (if (str/blank? (str value)) "." (str value))
        path (.toPath (io/file value))]
    (when (.isAbsolute path)
      (throw (ex-info "Workspace path must be relative."
                      {:type :workspace/unsafe-path})))
    (let [parts (mapv str (iterator-seq (.iterator path)))]
      (when (some #(= ".git" (str/lower-case %)) parts)
        (throw (ex-info "Direct access to .git is denied; use the Git tools."
                        {:type :workspace/git-internals-denied})))
      parts)))

(defn- safe-path
  [root value]
  (let [^Path root-path (.toPath (io/file (admit-root root)))
        parts (relative-parts value)
        ^Path candidate (.normalize (.resolve root-path (str/join "/" parts)))]
    (when-not (.startsWith candidate root-path)
      (throw (ex-info "Workspace path escaped its root."
                      {:type :workspace/unsafe-path})))
    (loop [current root-path, remaining parts]
      (when-let [part (first remaining)]
        (let [next-path (.resolve current part)]
          (when (and (Files/exists next-path no-links)
                     (Files/isSymbolicLink next-path))
            (throw (ex-info "Workspace path crosses a symbolic link."
                            {:type :workspace/symbolic-link})))
          (recur next-path (next remaining)))))
    candidate))

(defn- regular-text! [^Path path]
  (when-not (Files/isRegularFile path no-links)
    (throw (ex-info "Workspace target is not a regular file."
                    {:type :workspace/not-a-file})))
  (let [size (Files/size path)]
    (when (> size max-file-bytes)
      (throw (ex-info "Workspace file exceeds the read limit."
                      {:type :workspace/file-too-large :size size}))))
  path)

(defn- relative-name [^Path root ^Path path]
  (str/replace (str (.relativize root path)) "\\" "/"))

(defn- list-directory [root input]
  (let [^Path root-path (.toPath (io/file (admit-root root)))
        ^Path directory (safe-path root (or (:path input) "."))]
    (when-not (Files/isDirectory directory no-links)
      (throw (ex-info "Workspace list target is not a directory."
                      {:type :workspace/not-a-directory})))
    (with-open [stream (Files/list directory)]
      (->> (iterator-seq (.iterator stream))
           (remove #(Files/isSymbolicLink ^Path %))
           (remove #(= ".git" (str (.getFileName ^Path %))))
           (sort-by str)
           (take 200)
           (map #(str (relative-name root-path %)
                      (when (Files/isDirectory ^Path % no-links) "/")))
           (str/join "\n")))))

(def ^:private max-orientation-entries 60)

(defn orientation
  "The repository's top level, as one short block for the system prompt, or
  nil when there is no readable workspace.

  MEASURED 2026-08-19 over 84 resident runs: the tick is told to use at most
  two repository reads, and `workspace_list` took 103 of the 187 tool calls
  made. Only 37 of 84 runs ever opened a FILE; the rest spent the budget
  finding out what was there. Ten runs listed twice and stopped.

  A top level costs 27-105 tokens on this fleet's repositories. One model call
  costs a 3,275-token prompt, about 43 seconds at the measured rate, and half
  the tick's budget. Handing over the names is the cheaper half of that trade
  by two orders of magnitude.

  Bounded and never throwing: a workspace that has gone missing must degrade
  to 'no listing', not to a failed turn."
  [root]
  (try
    (when (seq (str root))
      (let [text (list-directory root {:path "."})
            lines (str/split-lines (str text))
            shown (take max-orientation-entries lines)]
        (when (seq shown)
          (str (str/join "\n" shown)
               (when (> (count lines) max-orientation-entries)
                 (str "\n… and " (- (count lines) max-orientation-entries) " more"))))))
    (catch Exception _ nil)))


(defn- search-workspace [root input]
  (let [query (str (:query input))
        _ (when (or (str/blank? query) (> (count query) 200))
            (throw (ex-info "Search query is empty or too long."
                            {:type :workspace/invalid-query})))
        ^Path root-path (.toPath (io/file (admit-root root)))]
    (with-open [stream (Files/walk root-path (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (remove #(or (Files/isSymbolicLink ^Path %)
                        (str/includes? (relative-name root-path %) ".git/")))
           (filter #(Files/isRegularFile ^Path % no-links))
           (filter #(<= (Files/size ^Path %) max-file-bytes))
           (take max-search-files)
           (mapcat (fn [^Path file]
                     (try
                       (keep-indexed
                        (fn [index line]
                          (when (str/includes? line query)
                            (str (relative-name root-path file) ":" (inc index)
                                 ":" line)))
                        (Files/readAllLines file StandardCharsets/UTF_8))
                       (catch Exception _ []))))
           (take 200)
           (str/join "\n")
           bounded))))

(defn- write-file! [root {:keys [path content]}]
  (let [^Path target (safe-path root path)
        ^Path parent (.getParent target)
        bytes (.getBytes (str content) StandardCharsets/UTF_8)]
    (when (> (alength bytes) max-file-bytes)
      (throw (ex-info "Workspace file exceeds the write limit."
                      {:type :workspace/file-too-large})))
    (when-not (Files/isDirectory parent no-links)
      (throw (ex-info "Workspace parent directory does not exist."
                      {:type :workspace/parent-required})))
    (let [temporary (Files/createTempFile parent ".itonami-write-" ".tmp"
                                          (make-array FileAttribute 0))]
      (try
        (Files/write temporary bytes (make-array java.nio.file.OpenOption 0))
        (Files/move temporary target
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (str "wrote " path " (" (alength bytes) " bytes)")
        (finally (Files/deleteIfExists temporary))))))

(defn- safe-relative [root value]
  (let [^Path root-path (.toPath (io/file (admit-root root)))
        ^Path path (safe-path root value)]
    (relative-name root-path path)))

(defn- commit! [root {:keys [paths message]}]
  (let [paths (mapv #(safe-relative root %) (or paths []))
        message (some-> message str str/trim)]
    (when (or (empty? paths) (> (count paths) 32))
      (throw (ex-info "Commit requires 1 to 32 explicit paths."
                      {:type :workspace/invalid-commit-paths})))
    (when (or (str/blank? message) (> (count message) 200))
      (throw (ex-info "Commit message is empty or too long."
                      {:type :workspace/invalid-commit-message})))
    (run-git (admit-root root)
             (into ["-c" "core.hooksPath=/dev/null" "add" "--"] paths))
    (let [status (run-git (admit-root root)
                          (into ["diff" "--cached" "--name-only" "--"] paths))]
      (if (str/blank? status)
        "nothing to commit"
        (do
          (run-git (admit-root root)
                   (into ["-c" "user.name=Cloud Itonami"
                          "-c" "user.email=itonami@localhost"
                          "-c" "commit.gpgsign=false"
                          "-c" "core.hooksPath=/dev/null"
                          "commit" "--no-verify" "-m" message "--"]
                         paths))
          (str "committed "
               (str/trim (run-git (admit-root root) ["rev-parse" "HEAD"]))))))))

(defn describe [name input]
  (case (str name)
    "workspace_write_file" (str "ローカル Git workspace の " (:path input)
                                " を置き換えます。")
    "git_commit" (str "指定した " (count (:paths input))
                      " path を local commit します（push はしません）。")
    (str name " を実行します。")))

(defn call!
  [root name input]
  (let [name (str name) input (or input {})]
    (case name
      "workspace_list" (list-directory root input)
      "workspace_read" (slurp (.toFile (regular-text! (safe-path root (:path input)))))
      "workspace_search" (search-workspace root input)
      "workspace_write_file" (write-file! root input)
      "git_status" (run-git (admit-root root) ["status" "--short"])
      "git_diff" (let [path (:path input)]
                   (run-git (admit-root root)
                            (cond-> ["diff" "--no-ext-diff" "--"]
                              (not (str/blank? (str path)))
                              (conj (safe-relative root path)))))
      "git_log" (run-git (admit-root root)
                          ["log" "-n" (str (min 50 (max 1 (long (or (:limit input) 10)))))
                           "--pretty=format:%h %ad %s" "--date=short"])
      "git_commit" (commit! root input)
      (throw (ex-info "Unknown workspace tool."
                      {:type :workspace/unknown-tool :tool name})))))
