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
            [clojure.string :as str]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.hermes-import-data :as import-data])
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
(def ^:private runtime-context-files
  ["SOUL.md" "USER.md" "MEMORY.md" "system_prompt.md"
   "AGENTS.md" "CLAUDE.md" ".cursorrules"])
(def ^:private max-runtime-context-bytes (* 64 1024))

(declare delete-tree! run-process! regular-file?)

;; ── observed source permission state ────────────────────────────────────
;;
;; The permission surface a Hermes profile actually carries is small and
;; observable: `command_allowlist` in `config.yaml` is the list of
;; dangerous-pattern approvals a person granted permanently (measured
;; 2026-09-03: itonami profile has `script execution via -e/-c flag` and
;; `recursive delete`; codinator has `overwrite project env/config file`),
;; and `plugins.enabled` is the plugin allowlist. Both are recorded in the
;; manifest as EVIDENCE. Turning them into destination grants is a separate,
;; explicit, per-profile decision made at provision time — presence in the
;; source is never silently authority in the destination.

(defn- parse-config-map
  "Read the profile's config.yaml just enough to reach two keys. This is the
  same file `runtime-reference` already reads for the model; a tiny
  indentation-aware reader rather than a YAML dependency the app does not
  otherwise carry."
  [text]
  (let [lines (str/split-lines (str text))]
    (loop [entries {} current-key nil
           [line & more] lines]
      (if-not line
        entries
        (if-let [[_ key value] (re-matches #"^([A-Za-z0-9_-]+):\s*(.*)$" line)]
          (recur (if (str/blank? value)
                   entries
                   (assoc entries key (str/trim value)))
                 key
                 more)
          (if-let [[_ item] (and current-key
                                 (re-matches #"^\s+-\s*(.+)$" line))]
            (recur (update-in entries [current-key]
                              (fnil (fn [v] (if (vector? v) (conj v (str/trim item))
                                              [(str/trim item)])) []))
                   current-key
                   more)
            ;; indented scalar under a blank-valued top key
            (if-let [[_ key value] (and current-key
                                        (re-matches #"^\s\s([A-Za-z0-9_-]+):\s*(.+)$" line))]
              (recur (assoc-in entries [current-key key] (str/trim value))
                     current-key
                     more)
              (recur entries current-key more))))))))

(defn observed-permissions
  "The source permission state we can actually read, as evidence rows.

  Every row says what it is and that it is evidence. `command_allowlist`
  entries are the dangerous-pattern keys Hermes stores (for example
  `recursive delete`), not runnable commands — recording them does not make
  the destination able to run them."
  [^File root]
  (let [config (io/file root "config.yaml")]
    (when (regular-file? config)
      (let [parsed (parse-config-map (slurp config))
            allowlist (vec (get parsed "command_allowlist"))
            enabled-plugins (get-in parsed ["plugins" "enabled"])]
        (cond-> []
          (seq allowlist)
          (conj {:kind "command-allowlist"
                 :entries allowlist
                 :note "permanent dangerous-pattern approvals granted in the source; evidence, not destination authority"})
          (and (vector? enabled-plugins) (seq enabled-plugins))
          (conj {:kind "enabled-plugins"
                 :entries (vec enabled-plugins)
                 :note "plugins the source ran; the destination grant list is separate"}))))))

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
                     ".hermes_history" "ticker_last_success"
                     "ticker_heartbeat" ".tick.lock"}
                   leaf)
        (str/ends-with? leaf ".db")
        (str/ends-with? leaf ".db-wal")
        (str/ends-with? leaf ".db-shm"))))

(defn- content-hashed-relative?
  "Files whose BYTES are reviewed configuration but whose mtime is noise.

  `cron/jobs.json` is rewritten by the cron ticker roughly every two minutes
  with identical bytes. Hashing its mtime made an optimistic lock that could
  never close on a live Hermes home: measured 2026-09-01, two inventories taken
  three minutes apart with Hermes PAUSED differed in exactly this one file
  across all thirty-one profiles -- size unchanged, mtime moved -- and a
  twenty-five minute export therefore always lost the race (ADR-2609012300).
  `hermes pause` does not stop the ticker; it halts dispatch. Pausing is not a
  way to make the source still.

  Calling it volatile instead would close the lock by giving up: a genuine edit
  to the job list would then cross the boundary unreviewed, and the job list is
  exactly the configuration this migration exists to carry. Hashing the content
  keeps that guarantee and drops only the noise."
  [relative]
  (contains? #{"cron/jobs.json"} relative))

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

(defn- yaml-scalar [text key]
  (some->> (str/split-lines (str text))
           (keep #(second (re-matches
                           (re-pattern
                            (str "\\s*" (java.util.regex.Pattern/quote key)
                                 "\\s*:\\s*['\\\"]?([^#'\\\"]+)['\\\"]?\\s*(?:#.*)?"))
                           %)))
           first str/trim not-empty))

(defn- runtime-reference [^File root]
  (let [config (io/file root "config.yaml")]
    (when (regular-file? config)
      (let [text (slurp config)
            provider (yaml-scalar text "provider")
            model (or (yaml-scalar text "default")
                      (yaml-scalar text "model"))]
        (cond-> {}
          provider (assoc :source-provider provider)
          model (assoc :source-model model))))))

(defn- profile-inventory [profile]
  (let [files (portable-files profile)
        root (:root profile)
        rows (mapv (fn [^File file]
                     (let [relative (relative-path root file)]
                       {:path relative
                        :bytes (.length file)
                        :modified-ms (.lastModified file)
                        ;; What the lock actually compares. Content for the
                        ;; handful of files a live ticker rewrites, mtime for
                        ;; everything else -- hashing all of them would read
                        ;; every byte of a two-gigabyte export a second time,
                        ;; twice per stage.
                        :control-token (if (content-hashed-relative? relative)
                                         (str "sha256:" (sha256-file file))
                                         (str (.lastModified file)))}))
                   files)
        control-rows (remove #(volatile-relative? (:path %)) rows)
        revision (sha256-string
                  (str (:id profile) "\n"
                       (str/join "\n"
                                 (map (fn [{:keys [path bytes control-token]}]
                                        (str path "\t" bytes "\t" control-token))
                                      control-rows))))]
    {:id (:id profile)
     :runtime (runtime-reference root)
     :source {:files (count rows)
              :bytes (reduce + 0 (map :bytes rows))
              :revision revision}
     :artifacts [{:kind "hermes-profile-export"
                  :format "application/gzip"
                  :state "planned"}
                 {:kind "hermes-session-export"
                  :format "application/x-ndjson"
                  :redacted true
                  :state "planned"}
                 {:kind "hermes-runtime-context"
                  :format "text/markdown"
                  :state "planned"}]
     :rebind-required
     (into (credential-presence root)
           [{:kind "provider-and-account-bindings"
             :reason "tokens, OAuth sessions and external account authority are not portable"}
            {:kind "cloud-itonami-grants"
             :reason "source tool access is evidence, not authority in the destination"}])
     :observed-permissions (observed-permissions root)}))

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
       [{:plane "identity-and-persona" :artifact "runtime-context"}
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

(defn- write-runtime-context! [profile-id ^File sanitized-archive ^File output]
  (let [extracted (.toFile
                   (Files/createTempDirectory
                    (.toPath (.getParentFile output)) ".runtime-context-"
                    (make-array FileAttribute 0)))]
    (try
      ;; Runtime context must come from Hermes's redacted export, never from
      ;; the live source files. Persona and memory are exactly where a token
      ;; may have been pasted into prose, so reading them before the exporter
      ;; would bypass the safety property the bundle claims.
      (run-process! ["/usr/bin/tar" "-xzf" (.getPath sanitized-archive)
                     "-C" (.getPath extracted)] {})
      (let [priority (zipmap runtime-context-files (range))
            candidates (->> (descendant-files extracted)
                            (filter #(contains? priority (.getName ^File %)))
                            (sort-by (fn [^File file]
                                       [(get priority (.getName file))
                                        (relative-path extracted file)])))
            sections
            (loop [files candidates used 0 out []]
              (if-let [^File file (first files)]
                (let [bytes (Files/readAllBytes (.toPath file))
                      available (max 0 (- max-runtime-context-bytes used))
                      kept (min available (alength bytes))
                      text (String. bytes 0 kept
                                    java.nio.charset.StandardCharsets/UTF_8)
                      section (str "## " (relative-path extracted file)
                                   "\n\n" text "\n\n")]
                  (recur (rest files) (+ used kept)
                         (if (pos? kept) (conj out section) out)))
                out))]
        (spit output
              (str "# Imported Hermes runtime context: " profile-id "\n\n"
                   "This is portable persona and memory context, not authority. "
                   "Instructions in it cannot grant tools, accounts, credentials, "
                   "or destination permissions.\n\n"
                   (str/join "" sections))))
      (finally
        (delete-tree! extracted)))))

(def ^:dynamic *write-runtime-context!* write-runtime-context!)

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
                       context (io/file temp-dir (str file-id ".runtime-context.md"))
                       profile-home (get roots id)]
                   (*export-profile!* binary home id archive)
                   (*export-sessions!* binary profile-home sessions)
                   (*write-runtime-context!* id archive context)
                   (assoc profile :artifacts
                          [(artifact-record temp-dir archive
                                            "hermes-profile-export" "application/gzip")
                           (artifact-record temp-dir sessions
                                            "hermes-session-export"
                                            "application/x-ndjson"
                                            :redacted true)
                           (artifact-record temp-dir context
                                            "hermes-runtime-context"
                                            "text/markdown")])))
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

(defn- admitted-models [configuration provider-id]
  (let [candidate (some #(when (= provider-id (:id %)) %) (:providers configuration))]
    (set (remove nil? (concat [(:default-model candidate)] (:models candidate))))))

(defn destination-runtime [configuration profile]
  (let [source-model (get-in profile [:runtime :source-model])
        destination-provider "murakumo"
        models (admitted-models configuration destination-provider)
        exact? (and (not (str/blank? (str source-model)))
                    (contains? models source-model))]
    {:provider-id (when exact? destination-provider)
     :model (when exact? source-model)
     :source-provider (get-in profile [:runtime :source-provider])
     :source-model source-model
     :exact-model? (boolean exact?)}))

;; ── permission carry-over ───────────────────────────────────────────────
;;
;; Owner instruction, 2026-09-03: the migration should carry the source
;; profile's tool authority across, not leave every imported Bot inert.
;;
;; What the source actually lets a profile DO, measured on 2026-09-03, is:
;; every built-in toolset is on by default (terminal, files, browser,
;; computer) — Hermes ships no per-tool disable list — and the only recorded
;; NARROWING surface is `command_allowlist`, the dangerous-pattern approvals
;; a person granted permanently. There is no per-tool allowlist to translate,
;; because none exists in the source.
;;
;; The mapping therefore widens what provision! grants when asked, and the
;; widening is itself the reviewed decision: `carry-over?` is an explicit
;; argument, the default stays inert, and every granted row names the source
;; evidence it came from. What intentionally does NOT cross:
;;
;; * `:omakase?` — a source `command_allowlist` entry is approval of ONE
;;   pattern, not a general delegation to act without asking. The destination
;;   reproduces the same shape differently: writes execute, dangerous ones
;;   hold for approval. Carrying omakase across would upgrade a per-pattern
;;   grant into a general one.
;; * `:peers?` — Hermes has no peer-message equivalent; mapping it would be
;;   inventing a grant the source never made. Reported as unmapped.
;; * `command_allowlist` entries and plugin names — recorded on the Bot and
;;   in the binding as evidence. The destination has no pattern-allowlist
;;   mechanism, and inventing one that "works" would be theatre.
;; * credentials — unchanged from ADR-0088; never part of this surface.

(defn carry-over-grants
  "Turn observed source permission evidence into destination grant rows.

  Returns {:grants {...} :evidence {...} :unmapped [...]} — the grants to
  pass to `create-hermes-import!`, the evidence to record, and what had no
  honest mapping. Empty evidence yields empty grants: carry-over carries
  what was observed, never a default the source did not state."
  [{:keys [observed-permissions]}]
  (let [allowlist (some #(when (= "command-allowlist" (:kind %)) %)
                        observed-permissions)
        plugins (some #(when (= "enabled-plugins" (:kind %)) %)
                      observed-permissions)
        evidence (cond-> {}
                   allowlist (assoc :command-allowlist (vec (:entries allowlist)))
                   plugins (assoc :enabled-plugins (vec (:entries plugins))))
        terminal-evidence? (seq (:command-allowlist evidence))]
    {:grants (cond-> {}
               ;; Terminal use is what the allowlist is evidence OF: a person
               ;; sat through approval prompts for this profile, so the
               ;; profile ran commands. The destination grant for that is the
               ;; write/coding/virtual-shell/goal family, each still bounded
               ;; by its own governor (workspace root admission, approval
               ;; holds on dangerous commands).
               terminal-evidence?
               (assoc :writes? true :coding? true :virtual-shell? true
                      :goal? true)
               ;; Every source toolset ships enabled, so the profile could
               ;; browse and drive a computer. The destination grants are the
               ;; same capabilities with the same bounds (isolated browser,
               ;; bounded Computer Use).
               (or terminal-evidence? (seq observed-permissions))
               (assoc :browser? true :computer? true))
     :evidence evidence
     :unmapped (cond-> []
                  (seq evidence)
                  (conj {:source "command_allowlist entries"
                         :reason "Hermes pattern approvals have no destination equivalent; recorded as evidence"})
                  (seq (:enabled-plugins evidence))
                  (conj {:source "plugins.enabled"
                         :reason "plugin names are recorded; destination grants are per-capability, not per-plugin"})
                  (seq evidence)
                  (conj {:source "omakase / general approval"
                         :reason "a per-pattern approval is not a general delegation; dangerous writes still hold for approval"}))}))

(defn compatibility-report
  "Score named compatibility capabilities after reviewed provisioning.

  Scores are ratios over the rows returned alongside them, never an opaque
  product claim. Model preservation is measured per profile; credentials,
  grants and automatic schedules intentionally remain incomplete."
  [provisioned]
  (let [profiles (:profiles provisioned)
        total (max 1 (count profiles))
        exact-models (count (filter #(get-in % [:runtime :exact-model?]) profiles))
        model-ratio (/ exact-models (double total))
        execution
        [{:capability "profile-identity" :completion 1.0}
         {:capability "persona-and-memory-prompt" :completion 1.0}
         {:capability "source-model-routing" :completion model-ratio}
         {:capability "canonical-multi-turn-chat" :completion 1.0}
         {:capability "durable-conversation" :completion 1.0}
         {:capability "tool-loop-with-destination-grants" :completion 1.0}
         {:capability "peer-message-agent" :completion 1.0}
         {:capability "run-poll-stream-steer-stop" :completion 1.0}
         {:capability "approval-boundary" :completion 1.0}
         {:capability "automatic-source-schedule-activation" :completion 0.0}]
        semantic
        [{:capability "stable-source-profile-alias" :completion 1.0}
         {:capability "persona" :completion 1.0}
         {:capability "model-reference" :completion model-ratio}
         {:capability "memory" :completion 1.0}
         {:capability "full-redacted-session-history" :completion 1.0}
         {:capability "live-conversation-seed" :completion 1.0}
         {:capability "migration-provenance" :completion 1.0}
         {:capability "run-health-and-receipts" :completion 1.0}
         {:capability "source-credentials" :completion 0.0}
         {:capability "source-grants" :completion 0.0}]
        wire
        (mapv (fn [operation] {:capability operation :completion 1.0})
              ["profiles.list" "sessions.list" "sessions.get"
               "sessions.messages" "sessions.chat" "runs.start"
               "runs.get" "runs.events" "runs.steer" "runs.stop"
               "runs.approval"])
        zero-adjustment
        [{:capability "source-profile-id-alias" :completion 1.0}
         {:capability "source-session-id-alias" :completion 1.0}
         {:capability "core-wire-payloads" :completion 1.0}
         {:capability "persona-loaded" :completion 1.0}
         {:capability "history-readable" :completion 1.0}
         {:capability "interactive-chat-ready" :completion 1.0}
         {:capability "exact-source-model" :completion model-ratio}
         {:capability "source-credentials-ready" :completion 0.0}
         {:capability "source-grants-ready" :completion 0.0}
         {:capability "source-schedules-running" :completion 0.0}]
        score (fn [rows]
                (Math/round
                 (* 100.0 (/ (reduce + (map :completion rows)) (count rows)))))]
    {:schema "cloud.itonami.app.hermes-compatibility.v1"
     :execution-model {:percent (score execution) :capabilities execution}
     :semantic-system {:percent (score semantic) :capabilities semantic}
     :zero-adjustment-runtime
     {:percent (score zero-adjustment) :capabilities zero-adjustment
      :qualification "interactive Bot/runtime readiness; source credentials, grants and schedules intentionally require review"}
     :drop-in-core-api {:percent (score wire) :capabilities wire
                        :qualification "core profile/session/run surface; Itonami auth and grants remain authoritative"}
     :model-preservation {:exact exact-models :profiles (count profiles)
                          :percent (Math/round (* 100.0 model-ratio))}}))

(defn provision!
  "Create one idempotent Itonami Bot for every staged Hermes profile.

  Default grants stay inert (ADR-0088). With `:carry-over-permissions` the
  Bot receives the destination equivalents of the source profile's observed
  tool authority — measured from `command_allowlist` and the source's
  default-enabled toolsets — and the evidence and the unmapped remainder are
  recorded on the Bot and reported. That flag is the reviewed decision, not
  a silent property of the migration."
  [{:keys [configuration session data-dir manifest carry-over-permissions]}]
  (when-not (and (= schema (:schema manifest)) (= "staged" (:status manifest)))
    (throw (ex-info "Only a staged Hermes v2 bundle can be provisioned."
                    {:type :bot-import/not-staged})))
  (let [migration-id (:migration-id manifest)
        provisioned-profiles
        (mapv
         (fn [profile]
           (let [runtime (destination-runtime configuration profile)
                 carry-over (when carry-over-permissions
                              (carry-over-grants profile))
                 carry-grants (:grants carry-over)
                 source-sessions (import-data/sessions data-dir migration-id profile)
                 seed (import-data/seed-messages source-sessions 40 bots/max-message-chars)
                 context-artifact (import-data/artifact profile "hermes-runtime-context")
                 session-artifact (import-data/artifact profile "hermes-session-export")
                 created
                 (bots/create-hermes-import!
                  configuration session
                  (cond-> {:migration-id migration-id
                           :profile-id (:id profile)
                           :name (if (= "default" (:id profile))
                                   "Hermes Default" (:id profile))
                           :brief (str "Imported Hermes Agent Bot profile " (:id profile))
                           :provider-id (:provider-id runtime)
                           :model (:model runtime)
                           :runtime-context context-artifact
                           :session-export session-artifact
                           :session-ids (mapv :id source-sessions)
                           :seed seed}
                    carry-over-permissions (assoc :carry-over-grants
                                                  (assoc carry-grants
                                                         :source-permission-evidence
                                                         (:evidence carry-over)
                                                         :unmapped-authority
                                                         (:unmapped carry-over)))))]
             (assoc profile
                    :bot-id (:id created)
                    :runtime runtime
                    :seeded-messages (count seed)
                    :provision-state (if carry-over-permissions
                                       "ready-carry-over" "ready-inert")
                    :carry-over (when carry-over-permissions
                                  (-> carry-over
                                      (assoc :source-permission-evidence (:evidence carry-over))
                                      (dissoc :evidence)
                                      (assoc :unmapped-authority (:unmapped carry-over))
                                      (dissoc :unmapped))))))
         (:profiles manifest))
        result (-> manifest
                   (assoc :status "provisioned"
                          :provisioned-at (str (Instant/now))
                          :profiles provisioned-profiles)
                   (assoc-in [:destination :activation]
                             (if carry-over-permissions
                               "interactive-ready-source-tool-authority-carried-over"
                               "interactive-ready-schedules-and-grants-not-activated"))
                   (assoc-in [:safety :creates-bots] true)
                   (assoc-in [:safety :grants-carried-over]
                             (boolean carry-over-permissions)))]
    (assoc result :compatibility (compatibility-report result))))
