(ns cloud.itonami.app.west-kotoba-refactor
  "Bounded CLJ -> Kotoba migration inventory for west-managed repositories.

  This namespace never edits a checkout.  It resolves one project from the
  generated west projection, inventories its language surface, and produces a
  deterministic task brief for a coding Bot whose admitted workspace is that
  exact Git root."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]))

(def ^:private no-links (make-array LinkOption 0))
(def ^:private source-extensions #{"clj" "cljc" "cljs"})
(def ^:private ignored-segments
  #{".git" ".clj-kondo" ".cpcache" "node_modules" "target" "vendor"})

(defn- canonical [file]
  (.getCanonicalFile (io/file file)))

(defn- manifest-file [root]
  (io/file (canonical root) "manifest" "west.yml"))

(defn projects
  "Read the minimal name/path/revision projection from generated west.yml.
  Unknown YAML fields are ignored; no YAML is evaluated."
  [root]
  (let [manifest (manifest-file root)]
    (when-not (.isFile manifest)
      (throw (ex-info "manifest/west.yml がありません。--root に west superproject を指定してください。"
                      {:type :west-refactor/manifest-missing
                       :manifest (.getPath manifest)})))
    (->> (str/split-lines (slurp manifest))
         (reduce (fn [{:keys [current items] :as state} line]
                   (if-let [[_ name] (re-matches #"\s+- name:\s+(.+?)\s*" line)]
                     {:current {:name name} :items (cond-> items current (conj current))}
                     (if current
                       (if-let [[_ k v] (re-matches #"\s+(path|revision):\s+(.+?)\s*" line)]
                         (assoc state :current (assoc current (keyword k) v))
                         state)
                       state)))
                 {:current nil :items []})
         ((fn [{:keys [current items]}] (cond-> items current (conj current))))
         (filter :path)
         (mapv (fn [project]
                 (assoc project :checkout (.getCanonicalPath
                                           (io/file (canonical root) (:path project)))))))))

(defn- extension [^Path path]
  (let [name (str (.getFileName path))
        dot (.lastIndexOf name ".")]
    (when (pos? dot) (subs name (inc dot)))))

(defn- ignored? [root ^Path path]
  (some ignored-segments
        (map str (iterator-seq (.iterator (.relativize root path))))))

(defn- files [checkout]
  (let [root (.toPath (canonical checkout))]
    (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (remove #(or (Files/isSymbolicLink ^Path %)
                        (ignored? root %)))
           (filter #(Files/isRegularFile ^Path % no-links))
           (map (fn [^Path path]
                  {:path (str (.relativize root path))
                   :extension (extension path)
                   :bytes (Files/size path)}))
           vec))))

(defn- verification-commands [checkout-files]
  (cond-> []
    (some #(= "deps.edn" (:path %)) checkout-files)
    (conj "clojure -M:test")

    (some #(= "package.json" (:path %)) checkout-files)
    (conj "npm test")

    (some #(= "bin/kotoba" (:path %)) checkout-files)
    (conj "bin/kotoba -M check <changed.kotoba>")))

(defn- inspect-resolved [project {:keys [limit] :or {limit 8}}]
  (let [checkout (canonical (:checkout project))]
      (when-not (and (.isDirectory checkout) (.exists (io/file checkout ".git")))
        (throw (ex-info (str "project checkout がありません: " (:path project))
                        {:type :west-refactor/checkout-missing
                         :project (:name project) :checkout (.getPath checkout)})))
      (let [all (files checkout)
            clj (filter #(source-extensions (:extension %)) all)
            kotoba (filter #(= "kotoba" (:extension %)) all)
            candidates (->> clj
                            (sort-by (juxt #(not (str/starts-with? (:path %) "src/"))
                                           :bytes :path))
                            (take (max 1 (min 50 (long limit))))
                            (mapv #(select-keys % [:path :bytes])))]
        {:schema "cloud.itonami.app.west-kotoba-refactor.v1"
         :project (select-keys project [:name :path :revision :checkout])
         :counts {:clj (count (filter #(= "clj" (:extension %)) clj))
                  :cljc (count (filter #(= "cljc" (:extension %)) clj))
                  :cljs (count (filter #(= "cljs" (:extension %)) clj))
                  :kotoba (count kotoba)}
         :candidate-count (count clj)
         :candidates candidates
         :verification (verification-commands all)})))

(defn inspect-project
  "Inventory one checked-out west project. Missing/unreadable checkouts fail
  closed instead of looking like a zero-source repository."
  [root project-name options]
  (let [project (some #(when (= project-name (:name %)) %) (projects root))]
    (when-not project
      (throw (ex-info (str "west project が見つかりません: " project-name)
                      {:type :west-refactor/project-missing :project project-name})))
    (inspect-resolved project options)))

(defn scan
  "Rank checked-out west projects with CLJ-family source. The result is bounded
  and contains counts, not file contents."
  [root {:keys [limit] :or {limit 25}}]
  (let [root-file (canonical root)
        manifest-projects (projects root)
        by-path (into {} (map (juxt :path identity)) manifest-projects)
        {:keys [exit out err]}
        (apply shell/sh
               (concat ["rg" "--files" "--no-ignore"
                        "-g" "*.clj" "-g" "*.cljc" "-g" "*.cljs" "-g" "*.kotoba"
                        "-g" "!**/.git/**" "-g" "!**/.clj-kondo/**"
                        "-g" "!**/.cpcache/**" "-g" "!**/target/**"
                        "-g" "!**/node_modules/**" "-g" "!**/vendor/**" "."]
                       [:dir (.getPath root-file)]))
        _ (when-not (#{0 1} exit)
            (throw (ex-info "高速scanには rg (ripgrep) が必要です。対象名が分かる場合は inspect を使用できます。"
                            {:type :west-refactor/scanner-unavailable
                             :exit exit :detail (str/trim err)})))
        owner (fn [path]
                (loop [candidate path]
                  (when-let [slash (str/last-index-of candidate "/")]
                    (let [parent (subs candidate 0 slash)]
                      (or (get by-path parent) (recur parent))))))
        grouped (reduce (fn [acc raw-path]
                          (let [path (str/replace-first raw-path #"^\./" "")]
                           (if-let [project (owner path)]
                            (let [ext (some-> path io/file .toPath extension)]
                              (-> acc
                                  (update-in [(:name project) :clj-files]
                                             (fnil + 0) (if (source-extensions ext) 1 0))
                                  (update-in [(:name project) :kotoba-files]
                                             (fnil + 0) (if (= "kotoba" ext) 1 0))
                                  (update-in [(:name project) :first-candidate]
                                             #(or % (when (source-extensions ext)
                                                      (subs path (inc (count (:path project)))))))
                                  (assoc-in [(:name project) :project] project)))
                            acc)))
                        {}
                        (remove str/blank? (str/split-lines out)))
        summaries (->> (vals grouped)
                       (filter #(pos? (:clj-files % 0)))
                       (keep (fn [{:keys [project] :as summary}]
                               (let [checkout (io/file (:checkout project))]
                                 (when (and (.isDirectory checkout)
                                            (.exists (io/file checkout ".git")))
                                   (merge (select-keys project [:name :path :checkout])
                                          (dissoc summary :project))))))
                       (sort-by (juxt (comp - :clj-files) :name))
                       (take (max 1 (min 200 (long limit))))
                       vec)]
    {:schema "cloud.itonami.app.west-kotoba-refactor-scan.v1"
     :root (.getCanonicalPath (canonical root))
     :projects summaries
     :shown (count summaries)}))

(defn task-text
  "A fixed migration contract. It asks for one compatibility-preserving slice,
  not a repository-wide rewrite or a prose-only migration plan."
  [inspection]
  (let [{:keys [project candidates verification]} inspection]
    (str "CLJからKotobaへの移行を、互換性を壊さない最小の1スライスだけ実行してください。\n"
         "対象west project: " (:name project) "\n"
         "対象Git root: " (:checkout project) "\n"
         "候補（上から1件を選び、依存が大きければさらに小さくする）:\n"
         (str/join "\n" (map #(str "- " (:path %) " (" (:bytes %) " bytes)") candidates))
         "\n\n完了条件:\n"
         "1. 変更前のテスト結果を確認する。既に赤ければ編集せず停止する。\n"
         "2. 既存CLJ APIを一度に削除せず、Kotobaの決定核と薄いCLJ adapterに分ける。\n"
         "3. 同じ入力に対する旧実装/Kotoba実装のparity testを追加する。\n"
         "4. 正常系だけでなく、拒否または境界値を1件以上固定する。\n"
         "5. 変更後に検証し、git diffを確認する。remoteへのpush、west pin変更、rebaseはしない。\n"
         (if (seq verification)
           (str "検証候補（実際のdeps/READMEを読んで正しいものだけ使う）:\n"
                (str/join "\n" (map #(str "- " %) verification)) "\n")
           "検証コマンドをリポジトリ内のREADME/depsから特定できなければ、編集せず阻害要因を報告する。\n")
         "成果には変更ファイル、実行した検証、pass/fail、残るCLJ境界を明記してください。")))
