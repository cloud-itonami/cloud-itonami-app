(ns cloud.itonami.app.installation
  "Stable, repository-independent installation storage and legacy migration.

  An explicit CLOUD_ITONAMI_DATA_DIR always wins. Otherwise the application
  uses the host platform's per-user application-data directory. A legacy
  repository-local ./data tree is copied once, never moved or deleted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute PosixFilePermission]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]))

(def storage-schema "cloud.itonami.app.installation-storage.v1")

(defn environment [name] (System/getenv name))
(defn os-name [] (System/getProperty "os.name" ""))
(defn home-dir [] (System/getProperty "user.home"))

(defn default-data-dir []
  (let [home (home-dir)
        os (str/lower-case (os-name))]
    (.getCanonicalFile
     (cond
       (str/includes? os "mac")
       (io/file home "Library" "Application Support" "Cloud Itonami")

       (str/includes? os "win")
       (io/file (or (not-empty (environment "LOCALAPPDATA"))
                    (io/file home "AppData" "Local"))
                "Cloud Itonami")

       :else
       (io/file (or (not-empty (environment "XDG_DATA_HOME"))
                    (io/file home ".local" "share"))
                "cloud-itonami")))))

(defn configured-data-dir []
  (.getCanonicalFile
   (if-let [explicit (or (not-empty (environment "CLOUD_ITONAMI_DATA_DIR"))
                         (not-empty
                          (System/getProperty "cloud.itonami.data-dir")))]
     (io/file explicit)
     (default-data-dir))))

(defn legacy-data-dir []
  (.getCanonicalFile
   (io/file (or (not-empty (environment "CLOUD_ITONAMI_LEGACY_DATA_DIR"))
                (not-empty
                 (System/getProperty "cloud.itonami.legacy-data-dir"))
                (io/file (System/getProperty "user.dir") "data")))))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- valid-legacy-state? [directory]
  (let [file (io/file directory "state.edn")]
    (and (.isFile file)
         (try
           (= "cloud.itonami.app.state.v1"
              (:schema (edn/read-string (slurp file))))
           (catch Exception _ false)))))

(defn- delete-tree! [file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (or (.listFiles file) (make-array java.io.File 0))]
        (delete-tree! child)))
    (Files/deleteIfExists (.toPath file))))

(defn restrict-directory! [directory]
  (try
    (Files/setPosixFilePermissions
     (.toPath (io/file directory))
     #{PosixFilePermission/OWNER_READ
       PosixFilePermission/OWNER_WRITE
       PosixFilePermission/OWNER_EXECUTE})
    (catch UnsupportedOperationException _ nil))
  directory)

(defn restrict-file! [file]
  (try
    (Files/setPosixFilePermissions
     (.toPath (io/file file))
     #{PosixFilePermission/OWNER_READ
       PosixFilePermission/OWNER_WRITE})
    (catch UnsupportedOperationException _ nil))
  file)

(defn- copy-tree! [source target]
  (when (Files/isSymbolicLink (.toPath source))
    (throw (ex-info "legacy data migration refuses symbolic links"
                    {:type :installation/unsafe-legacy-entry
                     :entry (.getName source)})))
  (if (.isDirectory source)
    (do
      (Files/createDirectories (.toPath target)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (doseq [child (or (.listFiles source) (make-array java.io.File 0))]
        (copy-tree! child (io/file target (.getName child)))))
    (Files/copy (.toPath source) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/COPY_ATTRIBUTES]))))

(defn migrate-legacy!
  "Copy a valid legacy data tree into an absent stable target.

  The source is preserved. Existing targets are never merged or overwritten."
  [target legacy]
  (cond
    (= (.getCanonicalPath target) (.getCanonicalPath legacy))
    {:status :same-directory :target target}

    (.exists target)
    {:status :target-exists :target target}

    (not (valid-legacy-state? legacy))
    {:status :no-valid-legacy-state :target target}

    :else
    (let [parent (.getParentFile target)
          staging (io/file parent
                           (str "." (.getName target) ".migration-" (UUID/randomUUID)))]
      (.mkdirs parent)
      (try
        (copy-tree! legacy staging)
        (spit (io/file staging "migration.edn")
              (pr-str {:schema storage-schema
                       :status :copied
                       :source-digest (sha256 (.getCanonicalPath legacy))
                       :migrated-at (str (Instant/now))}))
        (Files/move (.toPath staging) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE]))
        {:status :migrated :target target :source-preserved? true}
        (catch Exception error
          (delete-tree! staging)
          (throw error))))))

(defn ensure-data-dir! []
  (let [target (configured-data-dir)]
    (migrate-legacy! target (legacy-data-dir))
    (.mkdirs target)
    (restrict-directory! target)
    target))
