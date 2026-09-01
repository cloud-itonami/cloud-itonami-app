(ns cloud.itonami.app.hermes-migration
  "A credential-free, full-fidelity migration bundle for Hermes profiles.

  Hermes already owns the portable representation of a profile and of its
  session history.  This namespace invokes those two source-native exporters
  rather than guessing at Hermes's SQLite schema or copying a live database.
  A bundle therefore contains, for every default and named profile:

  * Hermes's credential-free profile archive (persona, config, memories,
    skills, cron, plugins, preferences, local state and profile files), and
  * a forced-redacted JSONL export of every session and message.

  `.env`, `auth.json`, provider tokens, account bindings and Cloud Itonami
  grants never cross.  Their *presence* is recorded as `rebind-required`, so
  full information does not become silently copied authority.

  Preview and stage use the same v2 manifest.  Stage rebuilds the source
  inventory before and after export and refuses if it changed, which keeps the
  reviewed preview tied to the bytes that were actually staged."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file FileVisitOption Files LinkOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.hermes-bot-migration.v2")
(def ^:private export-timeout-seconds 900)
(def ^:private credential-file-names #{".env" "auth.json"})
(def ^:private default-portable-roots
  #{"config.yaml" "SOUL.md" "MEMORY.md" "USER.md" "todo.json"
    "system_prompt.md" "AGENTS.md" "CLAUDE.md" ".cursorrules"
    "desktop.json" "skills" "cron" "scripts" "sessions" "plugins"
    "memories" "knowledge" "preferences"})

(declare delete-tree!)

(defn hermes-home []
  (io/file (or (some-> (System/getenv "HERMES_HOME") str/trim not-empty)
               (str (System/getProperty "user.home") "/.hermes"))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256-string [value]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str value) "UTF-8"))))

(defn- sha256-file [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (hex (.digest digest))))

(defn- regular-file? [^File file]
  (Files/isRegularFile (.toPath file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- directory? [^File file]
  (Files/isDirectory (.toPath file) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- walked-paths [^File root]
  (with-open [paths (Files/walk (.toPath root)
                                (make-array FileVisitOption 0))]
    (vec (iterator-seq (.iterator paths)))))

(defn- descendant-files [^File root]
  (if-not (directory? root)
    []
    (->> (walked-paths root)
         rest
         (map #(.toFile ^Path %))
         (filter regular-file?))))

(defn- relative-path [^File root ^File file]
  (-> (.toPath root) (.relativize (.toPath file)) str
      (str/replace File/separator "/")))

(defn- ignored-relative? [relative]
  (let [parts (str/split relative #"/")]
    (or (credential-file-names (last parts))
        (some #{"__pycache__"} parts)
        (some #(or (str/ends-with? % ".pyc")
                   (str/ends-with? % ".pyo")
                   (str/ends-with? % ".sock")
                   (str/ends-with? % ".tmp"))
              parts))))

(defn- volatile-relative?
  "Data that is expected to move while a resident gateway is alive.

  It is still exported and counted.  It is not part of the optimistic lock:
  session export provides its own SQLite-consistent snapshot, and every final
  artifact is content-hashed.  Treating a log mtime as reviewed configuration
  made an active Hermes installation structurally impossible to migrate."
  [relative]
  (let [parts (str/split relative #"/")
        leaf (last parts)]
    (or (contains? #{"sessions" "logs" "backups" "state-snapshots"
                     "checkpoints" "image_cache" "audio_cache"
                     "document_cache" "browser_screenshots" "sandboxes"}
                   (first parts))
        (contains? #{"gateway.pid" "gateway_state.json" "processes.json"
                     "active_profile" ".update_check" "errors.log"
                     ".hermes_history"}
                   leaf)
        (str/ends-with? leaf ".db")
        (str/ends-with? leaf ".db-wal")
        (str/ends-with? leaf ".db-shm"))))

(defn- portable-files [{:keys [id root]}]
  (let [candidates (if (= "default" id)
                     (mapcat (fn [name]
                               (let [entry (io/file root name)]
                                 (cond
                                   (regular-file? entry) [entry]
                                   (directory? entry) (descendant-files entry)
                                   :else [])))
                             default-portable-roots)
                     (descendant-files root))]
    (->> candidates
         (remove #(ignored-relative? (relative-path root %)))
         (sort-by #(relative-path root %))
         vec)))

(defn- profile-roots [^File home]
  (when-not (directory? home)
    (throw (ex-info (str "Hermes home が読めません: " (.getPath home))
                    {:type :bot-import/source-unreadable :source "hermes"})))
  (let [profiles-dir (io/file home "profiles")
        named (if (directory? profiles-dir)
                (->> (.listFiles profiles-dir)
                     (filter directory?)
                     (remove #(.isHidden ^File %))
                     (map (fn [^File file] {:id (.getName file) :root file}))
                     (sort-by :id))
                [])]
    (into [{:id "default" :root home}] named)))

(defn- credential-presence [^File root]
  (->> credential-file-names
       (keep (fn [name]
               (when (regular-file? (io/file root name))
                 {:kind "credential-file"
                  :source-path name
                  :reason "credential values do not migrate; bind the provider/account again"})))
       vec))

(defn- profile-inventory [profile]
  (let [files (portable-files profile)
        root (:root profile)
        rows (mapv (fn [^File file]
                     {:path (relative-path root file)
                      :bytes (.length file)
                      :modified-ms (.lastModified file)})
                   files)
        control-rows (remove #(volatile-relative? (:path %)) rows)
        revision (sha256-string
                  (str (:id profile) "\n"
                       (str/join "\n"
                                 (map (fn [{:keys [path bytes modified-ms]}]
                                        (str path "\t" bytes "\t" modified-ms))
                                      control-rows))))]
    {:id (:id profile)
     :source {:files (count rows)
              :bytes (reduce + 0 (map :bytes rows))
              :revision revision}
     :artifacts [{:kind "hermes-profile-export"
                  :format "application/gzip"
                  :state "planned"}
                 {:kind "hermes-session-export"
                  :format "application/x-ndjson"
                  :redacted true
                  :state "planned"}]
     :rebind-required
     (into (credential-presence root)
           [{:kind "provider-and-account-bindings"
             :reason "tokens, OAuth sessions and external account authority are not portable"}
            {:kind "cloud-itonami-grants"
             :reason "source tool access is evidence, not authority in the destination"}])}))

(defn- source-revision [profiles]
  (sha256-string
   (json/write-str (mapv (fn [profile]
                           [(:id profile) (get-in profile [:source :revision])])
                         profiles))))

(defn preview
  "Return the common API/CLI migration manifest without writing a bundle."
  [{:keys [home business migration-id captured-at]
    :or {business "cloud-itonami"}}]
  (let [migration-id (or migration-id (str "hermes-" (UUID/randomUUID)))]
    (when-not (re-matches #"hermes-[A-Za-z0-9_-]{1,100}" migration-id)
      (throw (ex-info "migration-id が安全な Hermes id ではありません。"
                      {:type :bot-import/invalid-migration-id})))
    (let [home (or home (hermes-home))
          home (.getCanonicalFile (io/file home))
          profiles (mapv profile-inventory (profile-roots home))
          bytes (reduce + 0 (map #(get-in % [:source :bytes]) profiles))]
      {:schema schema
       :migration-id migration-id
       :status "preview"
       :captured-at (or captured-at (str (Instant/now)))
       :source {:kind "hermes-agent"
                :home-fingerprint (subs (sha256-string (.getPath home)) 0 16)
                :revision (source-revision profiles)}
       :destination {:kind "cloud-itonami-bots"
                     :business business
                     :activation "review-and-provision-required"}
       :coverage
       [{:plane "identity-and-persona" :artifact "profile-export"}
        {:plane "configuration-and-model-references" :artifact "profile-export"}
        {:plane "memories-knowledge-preferences" :artifact "profile-export"}
        {:plane "skills-scripts-plugins-mcp" :artifact "profile-export"}
        {:plane "cron-jobs-schedules-and-health" :artifact "profile-export"}
        {:plane "sessions-messages-runs-and-tool-results" :artifact "session-export"}]
       :profiles profiles
       :summary {:profiles (count profiles)
                 :portable-files (reduce + 0 (map #(get-in % [:source :files]) profiles))
                 :source-bytes bytes
                 :rebind-required (reduce + 0 (map #(count (:rebind-required %)) profiles))}
       :safety {:source-mutated false
                :creates-bots false
                :copies-grants false
                :copies-credentials false
                :secret-redaction "forced-by-hermes-exporter"
                :binary-databases-excluded true
                :consistency "control-files-optimistic-lock-plus-source-native-session-snapshot"}})))

(defn- hermes-binary [^File home]
  (let [explicit (some-> (System/getenv "HERMES_BIN") str/trim not-empty)
        candidates (remove nil?
                           [(some-> explicit io/file)
                            (io/file home "hermes-agent" "venv" "bin" "hermes")
                            (io/file home "hermes-agent" "hermes")])]
    (or (some #(when (and (.isFile ^File %) (.canExecute ^File %)) %) candidates)
        (throw (ex-info "Hermes exporter が見つかりません。HERMES_BIN を絶対パスで指定してください。"
                        {:type :bot-import/exporter-unavailable
                         :source "hermes"})))))

(defn- run-process! [argv environment]
  (let [argv (mapv str argv)
        builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.redirectErrorStream true))
        _ (doseq [[key value] environment]
            (.put (.environment builder) (str key) (str value)))
        process (.start builder)
        output (future (slurp (.getInputStream process)))
        finished? (.waitFor process export-timeout-seconds TimeUnit/SECONDS)]
    (when-not finished?
      (.destroyForcibly process)
      (throw (ex-info "Hermes export が制限時間を超えました。"
                      {:type :bot-import/export-timeout})))
    (let [text (deref output 10000 "")]
      (when-not (zero? (.exitValue process))
        (throw (ex-info (str "Hermes export が失敗しました: "
                             (subs text 0 (min 1000 (count text))))
                        {:type :bot-import/export-failed
                         :exit (.exitValue process)}))))))

(defn- run-hermes! [^File binary ^File profile-home args]
  (run-process! (into [(.getPath binary)] args)
                {"HERMES_HOME" (.getPath profile-home)}))

(defn- database-artifact? [^Path path]
  (let [name (str/lower-case (str (.getFileName path)))]
    (or (credential-file-names name)
        (str/ends-with? name ".db")
        (str/ends-with? name ".db-wal")
        (str/ends-with? name ".db-shm")
        (str/ends-with? name ".sqlite")
        (str/ends-with? name ".sqlite3"))))

(defn- sanitize-profile-archive!
  "Remove opaque databases after Hermes has redacted its portable text.

  Hermes documents that binary DBs are not secret-scrubbed.  Session history
  crosses through the forced-redacted JSONL exporter instead, so retaining the
  raw DB as well would add no portable information and could reintroduce a key
  written inside a conversation or plugin database."
  [^File raw ^File output]
  (let [tar (io/file "/usr/bin/tar")]
    (when-not (.canExecute tar)
      (throw (ex-info "安全な profile archive を作る /usr/bin/tar がありません。"
                      {:type :bot-import/exporter-unavailable})))
    (let [parent (.getParentFile output)
          extracted (.toFile
                     (Files/createTempDirectory
                      (.toPath parent) ".sanitize-"
                      (make-array FileAttribute 0)))]
      (try
        (run-process! [(.getPath tar) "-xzf" (.getPath raw)
                       "-C" (.getPath extracted)] {})
        (with-open [paths (Files/walk (.toPath extracted)
                                     (make-array FileVisitOption 0))]
          (doseq [^Path path (doall (iterator-seq (.iterator paths)))]
            (when (and (Files/isRegularFile path
                                            (into-array LinkOption
                                                        [LinkOption/NOFOLLOW_LINKS]))
                       (database-artifact? path))
              (Files/delete path))))
        (let [children (->> (.listFiles extracted) (mapv #(.getName ^File %)) sort vec)]
          (when (empty? children)
            (throw (ex-info "Hermes profile archive が空です。"
                            {:type :bot-import/export-failed})))
          (run-process! (into [(.getPath tar) "-czf" (.getPath output)
                               "-C" (.getPath extracted)]
                              children)
                        {}))
        (finally
          (delete-tree! extracted))))))

(def ^:dynamic *export-profile!*
  (fn [binary root-profile-home profile-id output]
    (let [raw (io/file (.getParentFile ^File output)
                       (str "." (.getName ^File output) ".raw.tar.gz"))]
      (try
        (run-hermes! binary root-profile-home
                     ["profile" "export" profile-id "--output" (.getPath raw)])
        (sanitize-profile-archive! raw output)
        (finally
          (io/delete-file raw true))))))

(def ^:dynamic *export-sessions!*
  (fn [binary profile-home output]
    (run-hermes! binary profile-home
                 ["sessions" "export" (.getPath ^File output)
                  "--format" "jsonl" "--redact"])))

(defn- safe-file-id [id]
  (let [slug (-> id str/lower-case (str/replace #"[^a-z0-9_-]+" "-"))]
    (if (str/blank? slug) (subs (sha256-string id) 0 12) slug)))

(defn- artifact-record [root ^File file kind format & {:keys [redacted]}]
  (cond-> {:kind kind :format format :state "staged"
           :path (relative-path root file)
           :bytes (.length file)
           :sha256 (sha256-file file)}
    (some? redacted) (assoc :redacted redacted)))

(defn- delete-tree! [^File root]
  (when (.exists root)
    ;; Files.walk does not follow links unless FOLLOW_LINKS is passed.  A
    ;; profile archive may legitimately contain a symlink; cleanup must remove
    ;; that link, never walk through it and delete the source it points at.
    (doseq [^Path path (reverse (walked-paths root))]
      (Files/deleteIfExists path))))

(defn stage!
  "Stage every profile and session export, returning the same v2 manifest.

  The supplied preview is an optimistic lock.  A caller cannot remove a
  profile or change a source revision in the POST body and have that altered
  subset staged: the live source is re-inventoried and compared first."
  [{:keys [home data-dir manifest staged-by]}]
  (when-not (= schema (:schema manifest))
    (throw (ex-info "Hermes migration schema が一致しません。"
                    {:type :bot-import/invalid-schema})))
  (let [home (.getCanonicalFile (io/file (or home (hermes-home))))
        rebuilt (preview {:home home
                          :business (get-in manifest [:destination :business])
                          :migration-id (:migration-id manifest)
                          :captured-at (:captured-at manifest)})]
    (when-not (= (get-in rebuilt [:source :revision])
                 (get-in manifest [:source :revision]))
      (throw (ex-info "preview 後に Hermes profile が変わりました。もう一度 preview してください。"
                      {:type :bot-import/source-changed})))
    (let [migration-id (:migration-id rebuilt)
          parent (io/file data-dir "bot-imports")
          final-dir (io/file parent migration-id)
          temp-dir (io/file parent (str "." migration-id ".tmp-" (UUID/randomUUID)))
          binary (hermes-binary home)]
      (when (.exists final-dir)
        (throw (ex-info (str "migration は既に stage 済みです: " migration-id)
                        {:type :bot-import/already-staged})))
      (.mkdirs parent)
      (let [required (+ (long (get-in rebuilt [:summary :source-bytes]))
                        (* 512 1024 1024))]
        (when (< (.getUsableSpace parent) required)
          (throw (ex-info "Hermes bundle を安全に stage する空き容量が足りません。"
                          {:type :bot-import/insufficient-space
                           :required-bytes required
                           :usable-bytes (.getUsableSpace parent)}))))
      (when-not (.mkdirs temp-dir)
        (throw (ex-info "Hermes bundle の一時ディレクトリを作れません。"
                        {:type :bot-import/stage-directory})))
      (try
        (let [roots (into {} (map (juxt :id :root)) (profile-roots home))
              staged-profiles
              (mapv
               (fn [profile]
                 (let [id (:id profile)
                       file-id (safe-file-id id)
                       archive (io/file temp-dir (str file-id ".profile.tar.gz"))
                       sessions (io/file temp-dir (str file-id ".sessions.jsonl"))
                       profile-home (get roots id)]
                   (*export-profile!* binary home id archive)
                   (*export-sessions!* binary profile-home sessions)
                   (assoc profile :artifacts
                          [(artifact-record temp-dir archive
                                            "hermes-profile-export" "application/gzip")
                           (artifact-record temp-dir sessions
                                            "hermes-session-export"
                                            "application/x-ndjson"
                                            :redacted true)])))
               (:profiles rebuilt))
              after (preview {:home home
                              :business (get-in rebuilt [:destination :business])
                              :migration-id migration-id
                              :captured-at (:captured-at rebuilt)})]
          (when-not (= (get-in rebuilt [:source :revision])
                       (get-in after [:source :revision]))
            (throw (ex-info "export 中に Hermes profile が変わりました。stage を破棄しました。"
                            {:type :bot-import/source-changed})))
          (let [staged (-> rebuilt
                           (assoc :status "staged"
                                  :staged-at (str (Instant/now))
                                  :staged-by staged-by
                                  :profiles staged-profiles)
                           (assoc-in [:destination :bundle-path]
                                     (str "bot-imports/" migration-id)))]
            (spit (io/file temp-dir "manifest.json") (json/write-str staged))
            (Files/move (.toPath temp-dir) (.toPath final-dir)
                        (into-array StandardCopyOption
                                    [StandardCopyOption/ATOMIC_MOVE]))
            staged))
        (catch Exception error
          (delete-tree! temp-dir)
          (throw error))))))
