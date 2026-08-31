(ns cloud.itonami.app.folder-sync
  "Bidirectional synchronization between an ordinary local directory and a
  Cloud Itonami Drive folder.

  Each path records the last local SHA-256 and remote object ETag that were
  known to represent the same bytes. That pair is the common ancestor used to
  distinguish one-sided edits from conflicts. Remote conflicts are preserved
  below `.itonami-conflicts`; remote deletions move local files below
  `.itonami-trash` instead of unlinking them."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.documents :as documents]
            [cloud.itonami.app.store :as store]
            [fileprovider.model :as sync-model])
  (:import [java.io File InputStream]
           [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path StandardCopyOption]
           [java.security MessageDigest]
           [java.time Duration Instant]
           [java.time.format DateTimeFormatter]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory
            TimeUnit]))

(def schema "cloud.itonami.app.folder-sync.v1")
(def state-schema "cloud.itonami.app.folder-sync-state.v1")
(def default-maximum-file-bytes (* 100 1024 1024))

(defprotocol RemoteDrive
  (remote-snapshot [remote]
    "Return relative path -> {:id :etag :media-type :size-bytes} for files.")
  (remote-bytes [remote entry])
  (remote-put! [remote path bytes media-type])
  (remote-trash! [remote entry]))

(defonce ^:private scheduler (atom nil))
(defonce ^:private runtime-config (atom {:enabled? false :roots []}))
(defonce ^:private managed-roots (atom {}))
(defonce ^:private last-status (atom {}))
(defonce ^:private root-locks (atom {}))
(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 15))
      (.build)))

(def ^:dynamic *environment* #(System/getenv %))

(defn- hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- digest-stream [^InputStream input]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (loop []
      (let [n (.read input buffer)]
        (when (pos? n)
          (.update digest buffer 0 n)
          (recur))))
    (str "sha256:" (hex (.digest digest)))))

(defn- digest-bytes [^bytes bytes]
  (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- valid-relative-path? [path]
  (and (string? path)
       (not (str/blank? path))
       (not (str/includes? path "\u0000"))
       (not (str/starts-with? path "/"))
       (not (re-find #"^[A-Za-z]:" path))
       (every? #(and (not (str/blank? %))
                     (not (contains? #{"." ".."} %)))
               (str/split path #"/"))))

(defn- require-relative! [path]
  (when-not (valid-relative-path? path)
    (throw (ex-info "folder sync path is not a safe relative path"
                    {:type :folder-sync/unsafe-path :path path})))
  path)

(defn- require-segment! [value]
  (let [segment (str value)]
    (when (or (str/blank? segment)
              (contains? #{"." ".."} segment)
              (str/includes? segment "/")
              (str/includes? segment "\\")
              (str/includes? segment "\u0000"))
      (throw (ex-info "Drive name cannot be represented as a local path"
                      {:type :folder-sync/unsafe-remote-name})))
    segment))

(defn- internal-path? [path]
  (contains? #{".git" ".itonami" ".itonami-conflicts" ".itonami-trash"}
             (first (str/split path #"/"))))

(defn- normalized-relative [^Path root ^Path file]
  (-> (.relativize root file) str (str/replace File/separator "/")))

(defn- local-file-entry [^File file maximum-file-bytes]
  (let [size (.length file)]
    (when (> size maximum-file-bytes)
      (throw (ex-info "local file exceeds the configured sync limit"
                      {:type :folder-sync/file-too-large
                       :size-bytes size :maximum-file-bytes maximum-file-bytes})))
    (with-open [input (io/input-stream file)]
      {:hash (digest-stream input)
       :size-bytes size
       :media-type (or (try (Files/probeContentType (.toPath file))
                            (catch Exception _ nil))
                       "application/octet-stream")
       :file file})))

(defn local-snapshot
  "Read an ordinary directory without following symbolic links. Internal
  conflict/trash trees are excluded from synchronization."
  [root maximum-file-bytes]
  (let [^File directory (.getCanonicalFile (io/file root))]
    (when-not (.isDirectory directory)
      (throw (ex-info "folder sync root is not a directory"
                      {:type :folder-sync/root-required})))
    (let [root-path (.toPath directory)]
      (with-open [paths (Files/walk root-path (make-array java.nio.file.FileVisitOption 0))]
        (reduce
         (fn [result ^Path path]
           (let [relative (normalized-relative root-path path)]
             (if (or (str/blank? relative)
                     (internal-path? relative)
                     (Files/isSymbolicLink path)
                     (not (Files/isRegularFile path (make-array LinkOption 0))))
               result
               (assoc result (require-relative! relative)
                      (local-file-entry (.toFile path) maximum-file-bytes)))))
         {} (iterator-seq (.iterator paths)))))))

(defn- safe-id [value]
  (let [id (str value)]
    (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,79}" id)
      (throw (ex-info "folder sync id is not filesystem safe"
                      {:type :folder-sync/invalid-id})))
    id))

(defn- state-file [root-config]
  (or (some-> (:state-file root-config) io/file .getCanonicalFile)
      (.getCanonicalFile
       (io/file (config/data-dir) "folder-sync"
                (str (safe-id (:id root-config)) ".edn")))))

(defn- read-state [root-config]
  (let [file (state-file root-config)]
    (if-not (.isFile file)
      {:schema state-schema :entries {}}
      (let [value (edn/read-string
                   {:readers {}
                    :default (fn [tag _]
                               (throw (ex-info "tagged folder sync state denied"
                                               {:type :folder-sync/tagged-state
                                                :tag tag})))}
                   (slurp file))]
        (when-not (and (= state-schema (:schema value))
                       (map? (:entries value)))
          (throw (ex-info "folder sync state has an unsupported shape"
                          {:type :folder-sync/invalid-state})))
        value))))

(defn- write-state! [root-config state]
  (let [^File file (state-file root-config)
        parent (.getParentFile file)
        temporary (io/file parent (str "." (.getName file) ".tmp"))]
    (.mkdirs parent)
    (spit temporary (str (pr-str (assoc state :schema state-schema)) "\n"))
    (try
      (Files/move (.toPath temporary) (.toPath file)
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (Files/move (.toPath temporary) (.toPath file)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))
    state))

(defn- local-target [root path]
  (require-relative! path)
  (let [^Path base (.toPath (.getCanonicalFile (io/file root)))
        segments (str/split path #"/")
        target (.normalize (.resolve base (str/replace path "/" File/separator)))]
    (when-not (.startsWith target base)
      (throw (ex-info "folder sync path escaped its root"
                      {:type :folder-sync/unsafe-path :path path})))
    ;; A lexical startsWith check is insufficient when an existing component
    ;; is a symlink. Refuse the complete path, including the leaf, before any
    ;; read, write or move can follow it outside the configured root.
    (doseq [^Path candidate (rest (reductions #(.resolve ^Path %1 ^String %2)
                                              base segments))]
      (when (Files/isSymbolicLink candidate)
        (throw (ex-info "folder sync path crosses a symbolic link"
                        {:type :folder-sync/symbolic-link :path path}))))
    (.toFile target)))

(defn- write-local! [root path ^bytes bytes]
  (let [^File target (local-target root path)
        parent (.getParentFile target)
        temporary (io/file parent (str "." (.getName target) ".itonami-tmp"))]
    (.mkdirs parent)
    (Files/write (.toPath temporary) bytes (make-array java.nio.file.OpenOption 0))
    (try
      (Files/move (.toPath temporary) (.toPath target)
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (Files/move (.toPath temporary) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))
    target))

(defn- timestamp []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))

(defn- path-timestamp []
  (str/replace (timestamp) #"[:.]" "-"))

(defn- preserve-conflict! [root path ^bytes remote-content]
  (write-local! root
                (str ".itonami-conflicts/" (path-timestamp) "/" path)
                remote-content))

(defn- preserve-deletion! [root path]
  (let [^File source (local-target root path)]
    (when (.isFile source)
      (let [^File target (local-target
                          root (str ".itonami-trash/" (path-timestamp) "/" path))]
        (.mkdirs (.getParentFile target))
        (Files/move (.toPath source) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        target))))

(defn- read-local-bytes [entry]
  (Files/readAllBytes (.toPath ^File (:file entry))))

(defn- current-local-entry [root path maximum-file-bytes]
  (let [^File file (local-target root path)]
    (cond
      (not (.exists file)) nil
      (.isFile file) (local-file-entry file maximum-file-bytes)
      :else (throw (ex-info "folder sync target is not a regular file"
                            {:type :folder-sync/not-a-file :path path})))))

(defn- read-remote-bytes! [remote entry maximum-file-bytes]
  (let [declared (:size-bytes entry)]
    (when (and (number? declared)
               (> (long declared) maximum-file-bytes))
      (throw (ex-info "remote file exceeds the configured sync limit"
                      {:type :folder-sync/file-too-large
                       :size-bytes declared
                       :maximum-file-bytes maximum-file-bytes})))
    (let [value (remote-bytes remote entry)
          bytes (cond
                  (bytes? value) value
                  (and (sequential? value)
                       (every? #(and (integer? %) (<= 0 % 255)) value))
                  (byte-array (map unchecked-byte value))
                  :else nil)]
      (when-not (bytes? bytes)
        (throw (ex-info "remote Drive returned a non-binary file body"
                        {:type :folder-sync/invalid-remote-body})))
      (when (> (alength ^bytes bytes) maximum-file-bytes)
        (throw (ex-info "remote file exceeds the configured sync limit"
                        {:type :folder-sync/file-too-large
                         :size-bytes (alength ^bytes bytes)
                         :maximum-file-bytes maximum-file-bytes})))
      bytes)))

(defn- base-entry [local remote]
  {:local-hash (:hash local)
   :remote-etag (:etag remote)
   :remote-id (:id remote)
   :synced-at (timestamp)})

(defn- same-content? [local remote-content]
  (= (:hash local) (digest-bytes remote-content)))

(defn- report! [report action path]
  (swap! report update action (fnil conj []) path))

(defn sync-root!
  "Run one finite two-way reconciliation for ROOT-CONFIG and REMOTE.

  ROOT-CONFIG requires `:id` and `:path`. State is written after every
  successful path-level mutation, keeping a crash from forgetting more than
  the operation currently in flight."
  [root-config remote]
  (let [maximum (long (or (:maximum-file-bytes root-config)
                          default-maximum-file-bytes))
        local (local-snapshot (:path root-config) maximum)
        remote-files (into {} (map (fn [[path entry]]
                                     [(require-relative! path) entry]))
                           (remote-snapshot remote))
        state (atom (read-state root-config))
        report (atom {:pushed [] :pulled [] :remote-trashed []
                      :local-trashed [] :conflicts [] :unchanged []})
        persist! (fn [] (write-state! root-config @state))
        bind! (fn [path local-entry remote-entry]
                (swap! state assoc-in [:entries path]
                       (base-entry local-entry remote-entry))
                (persist!))
        forget! (fn [path]
                  (swap! state update :entries dissoc path)
                  (persist!))
        current! (fn [path]
                   (current-local-entry (:path root-config) path maximum))
        local-still? (fn [path expected]
                       (= (:hash expected) (:hash (current! path))))
        conflict! (fn [path local-entry remote-entry remote-content kind]
                    (when remote-content
                      (preserve-conflict! (:path root-config) path remote-content))
                    (let [ancestor (or (get-in @state [:entries path])
                                       (base-entry local-entry remote-entry))]
                      (swap! state assoc-in [:entries path]
                             (assoc ancestor :conflict
                                    (cond-> {:local-hash (:hash local-entry)
                                             :remote-etag (:etag remote-entry)
                                             :detected-at (timestamp)}
                                      kind (assoc :kind kind)))))
                    (persist!)
                    (report! report :conflicts path))]
    (doseq [path (sort (set (concat (keys local) (keys remote-files)
                                   (keys (:entries @state)))))]
      (let [local-entry (get local path)
            remote-entry (get remote-files path)
            base (get-in @state [:entries path])
            conflict (:conflict base)
            local-changed? (not= (:hash local-entry) (:local-hash base))
            remote-changed? (or (not= (:etag remote-entry) (:remote-etag base))
                                (not= (:id remote-entry) (:remote-id base)))]
        (cond
          ;; An edit after a conflict is the explicit resolution: the local
          ;; copy wins only because a person changed it after seeing the fork.
          (and conflict
               (not= (:hash local-entry) (:local-hash conflict)))
          (if local-entry
            (let [created (remote-put! remote path (read-local-bytes local-entry)
                                       (:media-type local-entry))]
              (when remote-entry (remote-trash! remote remote-entry))
              (bind! path local-entry created)
              (report! report :pushed path))
            (do (when remote-entry (remote-trash! remote remote-entry))
                (forget! path)
                (report! report :remote-trashed path)))

          conflict
          (report! report :conflicts path)

          ;; First sight of a path.
          (nil? base)
          (cond
            (and local-entry remote-entry)
            (let [bytes (read-remote-bytes! remote remote-entry maximum)
                  current (current! path)]
              (if (and (= (:hash local-entry) (:hash current))
                       (same-content? current bytes))
                (do (bind! path local-entry remote-entry)
                    (report! report :unchanged path))
                (conflict! path current remote-entry bytes nil)))

            local-entry
            (let [created (remote-put! remote path (read-local-bytes local-entry)
                                       (:media-type local-entry))]
              (bind! path local-entry created)
              (report! report :pushed path))

            remote-entry
            (let [bytes (read-remote-bytes! remote remote-entry maximum)]
              (if (local-still? path nil)
                (let [file (write-local! (:path root-config) path bytes)
                      pulled (assoc remote-entry :hash (digest-bytes bytes) :file file)]
                  (bind! path pulled remote-entry)
                  (report! report :pulled path))
                (conflict! path (current! path) remote-entry bytes nil))))

          ;; Both copies still exist.
          (and local-entry remote-entry)
          (cond
            (and local-changed? remote-changed?)
            (let [bytes (read-remote-bytes! remote remote-entry maximum)
                  current (current! path)]
              (if (same-content? current bytes)
                (do (bind! path current remote-entry)
                    (report! report :unchanged path))
                (conflict! path current remote-entry bytes nil)))

            local-changed?
            (let [created (remote-put! remote path (read-local-bytes local-entry)
                                       (:media-type local-entry))]
              (remote-trash! remote remote-entry)
              (bind! path local-entry created)
              (report! report :pushed path))

            remote-changed?
            (let [bytes (read-remote-bytes! remote remote-entry maximum)]
              (if (local-still? path local-entry)
                (let [file (write-local! (:path root-config) path bytes)
                      pulled (assoc remote-entry :hash (digest-bytes bytes) :file file)]
                  (bind! path pulled remote-entry)
                  (report! report :pulled path))
                (conflict! path (current! path) remote-entry bytes nil)))

            :else (report! report :unchanged path))

          ;; A remote deletion versus a local copy.
          local-entry
          (if local-changed?
            (conflict! path (current! path) remote-entry nil
                       :remote-deletion)
            (if (local-still? path local-entry)
              (do (preserve-deletion! (:path root-config) path)
                  (forget! path)
                  (report! report :local-trashed path))
              (conflict! path (current! path) remote-entry nil
                         :remote-deletion)))

          ;; A local deletion versus a remote copy.
          remote-entry
          (if remote-changed?
            (let [bytes (read-remote-bytes! remote remote-entry maximum)]
              (conflict! path (current! path) remote-entry bytes
                         :local-deletion))
            (do (remote-trash! remote remote-entry)
                (forget! path)
                (report! report :remote-trashed path)))

          :else
          (do (forget! path) (report! report :unchanged path)))))
    (assoc @report :schema schema :root-id (str (:id root-config))
           :finished-at (timestamp))))

;; Cloud Itonami Drive adapter ----------------------------------------------

(defn- item-path [items root-id item-id]
  (loop [id item-id segments [] seen #{}]
    (when (contains? seen id)
      (throw (ex-info "Drive folder cycle encountered during sync"
                      {:type :folder-sync/remote-cycle})))
    (let [item (get items id)
          parent (:drive/parent-id item)]
      (cond
        (nil? item) nil
        (= parent root-id) (str/join "/" (reverse (conj segments
                                                          (require-segment!
                                                           (:drive/title item)))))
        (nil? parent) nil
        :else (recur parent (conj segments (require-segment! (:drive/title item)))
                     (conj seen id))))))

(defn- remotely-trashed? [items item-id]
  (loop [id item-id seen #{}]
    (cond
      (nil? id) false
      (contains? seen id) true
      :else (let [item (get items id)]
              (or (:drive/trashed? item)
                  (recur (:drive/parent-id item) (conj seen id)))))))

(defrecord DocumentsRemote [actor drive-path object-store]
  RemoteDrive
  (remote-snapshot [_]
    (let [{root-id :id} (documents/ensure-folder-path! actor drive-path)
          workspace (documents/stored-workspace-for (store/snapshot) actor)
          items (:drive.workspace/items workspace)]
      (->> items
           vals
           (keep (fn [item]
                   (when (and (= :file (:drive/kind item))
                              (nil? (:drive/resource-kind item))
                              (not (remotely-trashed? items (:drive/id item))))
                     (when-let [path (item-path items root-id (:drive/id item))]
                       [path {:id (:drive/id item)
                              :etag (:drive/object-ref item)
                              :media-type (:drive/media-type item)
                              :size-bytes (or (:drive.version/size-bytes
                                               (peek (:drive/versions item))) 0)
                              :updated-at (:drive.version/created-at
                                           (peek (:drive/versions item)))}]))))
           ;; If a crash left two same-path uploads, prefer the newest complete
           ;; object. The next local write retires the selected predecessor.
           (sort-by (fn [[_ entry]] [(:updated-at entry) (:id entry)]))
           (reduce (fn [result [path entry]] (assoc result path entry)) {}))))
  (remote-bytes [_ entry]
    (:bytes (documents/file-bytes (:id entry) actor object-store)))
  (remote-put! [_ path bytes media-type]
    (let [segments (str/split (require-relative! path) #"/")
          filename (last segments)
          folder-path (into (vec drive-path) (butlast segments))
          {folder-id :id} (documents/ensure-folder-path! actor folder-path)
          result (documents/upload! filename media-type bytes actor object-store
                                    {:folder folder-id})
          item (:item result)]
      {:id (:id item) :etag (:etag item) :media-type (:media-type item)
       :size-bytes (:size-bytes item) :updated-at (:updated-at item)}))
  (remote-trash! [_ entry]
    (documents/trash! (:id entry) actor)))

(defn documents-remote [root-config]
  (->DocumentsRemote (:actor root-config)
                     (vec (or (:drive-path root-config)
                              ["Synced" (str (:id root-config))]))
                     (documents/store-instance)))

;; Hosted Drive HTTP adapter ------------------------------------------------

(defn- encoded [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- http-base [remote-config]
  (let [value (str/replace (str (:base-url remote-config)) #"/+$" "")
        uri (URI/create value)]
    (when-not (and (contains? #{"http" "https"} (.getScheme uri))
                   (.getHost uri)
                   (nil? (.getUserInfo uri)))
      (throw (ex-info "folder sync remote requires an HTTP(S) base URL"
                      {:type :folder-sync/invalid-remote-url})))
    value))

(defn- remote-token [remote-config]
  (let [environment-name (:bearer-token-env remote-config)
        token (when-not (str/blank? (str environment-name))
                (not-empty (*environment* (str environment-name))))]
    (or token
        (throw (ex-info "folder sync remote bearer token is unavailable"
                        {:type :folder-sync/remote-token-required
                         :environment (str environment-name)})))))

(defn- http-request!
  ([remote-config method path] (http-request! remote-config method path nil nil))
  ([remote-config method path body content-type]
   (let [uri (URI/create (str (http-base remote-config) path))
         publisher (if body
                     (HttpRequest$BodyPublishers/ofByteArray body)
                     (HttpRequest$BodyPublishers/noBody))
         builder (doto (HttpRequest/newBuilder uri)
                   (.timeout (Duration/ofSeconds 120))
                   (.header "Authorization" (str "Bearer " (remote-token remote-config)))
                   (.header "Accept" "application/json")
                   (.method method publisher))
         _ (when content-type (.header builder "Content-Type" content-type))
         response (.send ^HttpClient http-client (.build builder)
                         (HttpResponse$BodyHandlers/ofByteArray))
         status (.statusCode response)
         response-body (.body response)]
     (when-not (<= 200 status 299)
       (throw (ex-info "hosted Drive request failed"
                       {:type :folder-sync/remote-http-error
                        :status status :path path})))
     {:status status :headers (.headers response) :bytes response-body})))

(defn- http-json
  ([remote-config method path]
   (http-json remote-config method path nil))
  ([remote-config method path value]
   (let [body (when value (.getBytes (json/write-str value) StandardCharsets/UTF_8))
         response (http-request! remote-config method path body
                                 (when body "application/json"))]
     (json/read-str (String. ^bytes (:bytes response) StandardCharsets/UTF_8)
                    :key-fn keyword))))

(defn- folder-query [folder]
  (str "/api/workspace/drive/folders"
       (when folder (str "?folder=" (encoded folder)))))

(defn- ensure-http-folder! [remote-config segments]
  (loop [parent nil remaining (seq segments)]
    (if-let [title (first remaining)]
      (let [title (require-segment! title)
            listing (http-json remote-config "GET" (folder-query parent))
            existing (some #(when (= title (:name %)) %) (:folders listing))
            child (or existing
                      (:item (http-json remote-config "POST"
                                        "/api/workspace/drive/folders"
                                        {:title title :folder parent})))]
        (recur (:id child) (next remaining)))
      (or parent (:folder (http-json remote-config "GET" (folder-query nil)))))))

(defn- http-folder-paths [remote-config root-id]
  (loop [queue [[root-id ""]] result {root-id ""} seen #{}]
    (when (> (count result) 10000)
      (throw (ex-info "hosted Drive folder tree exceeds the sync limit"
                      {:type :folder-sync/remote-tree-too-large})))
    (if-let [[folder prefix] (first queue)]
      (if (contains? seen folder)
        (recur (subvec (vec queue) 1) result seen)
        (let [listing (http-json remote-config "GET" (folder-query folder))
              children (:folders listing)
              child-rows (mapv (fn [child]
                                 [(:id child)
                                  (str prefix (when-not (str/blank? prefix) "/")
                                       (require-segment! (:name child)))])
                               children)]
          (recur (into (subvec (vec queue) 1) child-rows)
                 (into result child-rows)
                 (conj seen folder))))
      result)))

(defn- http-drive-items [remote-config]
  (loop [cursor nil result []]
    (let [path (str "/api/workspace/drive"
                    (when cursor (str "?cursor=" (encoded cursor))))
          page (http-json remote-config "GET" path)
          workspace-items (filter #(= "workspace" (:origin %)) (:items page))
          accumulated (into result workspace-items)]
      (if-let [next-cursor (:next-cursor page)]
        (recur next-cursor accumulated)
        accumulated))))

(defrecord HttpRemote [remote-config drive-path]
  RemoteDrive
  (remote-snapshot [_]
    (let [root-id (ensure-http-folder! remote-config drive-path)
          folders (http-folder-paths remote-config root-id)]
      (->> (http-drive-items remote-config)
           (keep (fn [item]
                   (when (and (:file? item)
                              (not (:trashed? item))
                              (contains? folders (:parent-id item)))
                     (let [prefix (get folders (:parent-id item))
                           path (str prefix (when-not (str/blank? prefix) "/")
                                     (require-segment! (:name item)))]
                       [(require-relative! path)
                        {:id (:id item) :etag (:etag item)
                         :media-type (:media-type item)
                         :size-bytes (:size-bytes item)
                         :updated-at (:updated-at item)}]))))
           (sort-by (fn [[_ entry]] [(:updated-at entry) (:id entry)]))
           (reduce (fn [result [path entry]] (assoc result path entry)) {}))))
  (remote-bytes [_ entry]
    (:bytes (http-request! remote-config "GET"
                           (str "/api/workspace/drive/documents/"
                                (encoded (:id entry)) "/download"))))
  (remote-put! [_ path bytes media-type]
    (let [segments (str/split (require-relative! path) #"/")
          filename (last segments)
          folder (ensure-http-folder! remote-config
                                      (into (vec drive-path) (butlast segments)))
          route (str "/api/workspace/drive/upload?filename=" (encoded filename)
                     "&media-type=" (encoded media-type)
                     "&folder=" (encoded folder))
          item (:item (json/read-str
                       (String. ^bytes (:bytes (http-request! remote-config "POST"
                                                             route bytes media-type))
                                StandardCharsets/UTF_8)
                       :key-fn keyword))]
      {:id (:id item) :etag (:etag item) :media-type (:media-type item)
       :size-bytes (:size-bytes item) :updated-at (:updated-at item)}))
  (remote-trash! [_ entry]
    (http-json remote-config "POST"
               (str "/api/workspace/drive/documents/"
                    (encoded (:id entry)) "/trash") {})))

(defn http-remote [root-config]
  (let [remote-config (:remote root-config)]
    (->HttpRemote remote-config
                  (vec (or (:drive-path root-config)
                           ["Synced" (str (:id root-config))])))))

(defn configured-remote [root-config]
  (case (keyword (or (get-in root-config [:remote :kind]) :local))
    :local (documents-remote root-config)
    :http (http-remote root-config)
    (throw (ex-info "folder sync remote kind is unsupported"
                    {:type :folder-sync/unsupported-remote
                     :kind (get-in root-config [:remote :kind])}))))

(defn- root-lock [id]
  (or (get @root-locks id)
      (get (swap! root-locks #(if (contains? % id) % (assoc % id (Object.)))) id)))

(defn- root-configs
  ([] (mapv #(cond-> %
               (nil? (:maximum-file-bytes %))
               (assoc :maximum-file-bytes
                      (:maximum-file-bytes @runtime-config)))
            (concat (:roots @runtime-config) (vals @managed-roots))))
  ([actor]
   (filter #(= (str actor) (str (:actor %))) (root-configs))))

(defn sync-configured!
  ([] (sync-configured! nil #{:continuous :manual}))
  ([actor] (sync-configured! actor #{:continuous :manual}))
  ([actor schedules]
   (let [roots (->> (if actor (root-configs actor) (root-configs))
                    (filter #(contains? schedules (:schedule % :continuous))))]
     (mapv
      (fn [root]
        (let [id (str (:id root))]
          (locking (root-lock id)
           (try
            (let [result (sync-root! root (configured-remote root))]
              (swap! last-status assoc id {:status :ok :at (timestamp)
                                           :counts (into {}
                                                         (map (fn [[k v]]
                                                                [k (if (vector? v)
                                                                     (count v) v)]))
                                                         (dissoc result :schema
                                                                 :root-id
                                                                 :finished-at))})
              result)
            (catch Exception error
              (let [failure {:schema schema :root-id id :status :error
                             :error {:type (or (:type (ex-data error))
                                               :folder-sync/error)
                                     :message (.getMessage error)}}]
                (swap! last-status assoc id (assoc failure :at (timestamp)))
                failure))))))
      roots))))

(defn status
  ([] (status nil))
  ([actor]
   (let [roots (if actor (root-configs actor) (root-configs))]
     {:schema schema
      :enabled? (boolean (or (:enabled? @runtime-config) (seq @managed-roots)))
      :running? (boolean @scheduler)
      :roots (mapv (fn [root]
                     (merge {:id (str (:id root))
                             :schedule (:schedule root :continuous)
                             :residency (:residency root :pinned)}
                            (select-keys (get @last-status (str (:id root)))
                                         [:status :at :counts :error])))
                   roots)})))

(defn- ensure-scheduler! []
  (when (and (some #(= :continuous (:schedule % :continuous)) (root-configs))
             (nil? @scheduler))
    (let [executor (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "cloud-itonami-folder-sync")
                          (.setDaemon true)))))
          interval (max 2 (long (:interval-seconds @runtime-config 30)))]
      (.scheduleWithFixedDelay
       ^ScheduledExecutorService executor
       ^Runnable #(sync-configured! nil #{:continuous}) 2 interval TimeUnit/SECONDS)
      (reset! scheduler executor))))

(defn start!
  ([] (start! {}))
  ([configuration]
   (let [settings (merge {:enabled? false :interval-seconds 30 :roots []}
                         (:folder-sync configuration))
         settings (update settings :roots
                          (fn [roots]
                            (mapv #(merge {:schedule :continuous
                                           :residency :pinned} %)
                                  roots)))]
     (doseq [root (:roots settings)]
       (safe-id (:id root))
       (when (str/blank? (str (:actor root)))
         (throw (ex-info "folder sync root requires an actor"
                         {:type :folder-sync/actor-required})))
       (when (str/blank? (str (:path root)))
         (throw (ex-info "folder sync root requires a local path"
                         {:type :folder-sync/path-required})))
       (when-not (contains? sync-model/schedules (:schedule root))
         (throw (ex-info "folder sync schedule is invalid"
                         {:type :folder-sync/invalid-schedule
                          :schedule (:schedule root)})))
       ;; An ordinary directory contains real bytes by definition. Placeholder
       ;; and automatic eviction belong to File Provider, not folder sync.
       (when-not (= :pinned (:residency root))
         (throw (ex-info "folder sync roots are always pinned; use Finder File Provider for online-only"
                         {:type :folder-sync/residency-requires-file-provider
                          :residency (:residency root)}))))
     (when-not (= (count (:roots settings))
                  (count (set (map (comp str :id) (:roots settings)))))
       (throw (ex-info "folder sync ids must be unique"
                       {:type :folder-sync/duplicate-id})))
     (reset! runtime-config settings)
     (when (:enabled? settings) (ensure-scheduler!))
     true)))

(defn register-managed-root!
  "Register one application-owned, continuously synchronized directory.

  Unlike operator roots in config.edn, these roots are reconstructed from a
  durable domain record (currently a Bot) and may therefore be registered at
  runtime. The root remains pinned: online-only placeholders belong to File
  Provider, while a Bot needs real bytes when it works offline."
  [root]
  (let [root (merge {:schedule :continuous :residency :pinned} root)
        id (safe-id (:id root))]
    (when (str/blank? (str (:actor root)))
      (throw (ex-info "managed folder sync root requires an actor"
                      {:type :folder-sync/actor-required})))
    (when (str/blank? (str (:path root)))
      (throw (ex-info "managed folder sync root requires a local path"
                      {:type :folder-sync/path-required})))
    (when-not (= :pinned (:residency root))
      (throw (ex-info "managed folder sync roots are always pinned"
                      {:type :folder-sync/residency-requires-file-provider})))
    (swap! managed-roots assoc id (assoc root :id id))
    (ensure-scheduler!)
    (get @managed-roots id)))

(defn unregister-managed-root! [id]
  (swap! managed-roots dissoc (str id))
  true)

(defn sync-managed-root! [actor id]
  (let [id (str id)
        root (get @managed-roots id)]
    (when-not (and root (= (str actor) (str (:actor root))))
      (throw (ex-info "managed folder sync root not found"
                      {:type :folder-sync/not-found :id id})))
    (first (sync-configured! actor #{(:schedule root :continuous)}))))

(defn set-root-mode!
  "Change one configured root's schedule. Folder roots are always materialized
  (`:pinned`); online-only/automatic residency is provided by File Provider."
  [actor id schedule residency]
  (when-not (contains? sync-model/schedules schedule)
    (throw (ex-info "folder sync schedule is invalid"
                    {:type :folder-sync/invalid-schedule :schedule schedule})))
  (when-not (= :pinned residency)
    (throw (ex-info "folder sync roots are always pinned"
                    {:type :folder-sync/residency-requires-file-provider
                     :residency residency})))
  (let [matched (atom false)]
    (swap! runtime-config update :roots
           (fn [roots]
             (mapv (fn [root]
                     (if (and (= (str actor) (str (:actor root)))
                              (= (str id) (str (:id root))))
                       (do (reset! matched true)
                           (assoc root :schedule schedule :residency residency))
                       root))
                   roots)))
    (when-not @matched
      (throw (ex-info "folder sync root not found"
                      {:type :folder-sync/not-found :id id})))
    (status actor)))

(defn stop! []
  (when-let [^ScheduledExecutorService executor @scheduler]
    (.shutdownNow executor)
    (reset! scheduler nil))
  true)
