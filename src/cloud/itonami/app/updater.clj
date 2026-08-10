(ns cloud.itonami.app.updater
  "Signed desktop update discovery and staging.

  GitHub is only the transport. A release is trusted after its EDN manifest
  verifies under the public Ed25519 key embedded in this application and the
  selected package matches the signed size and SHA-256. Applying the staged
  package happens before the next server start, in the platform launcher."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.time Instant]
           [java.util Base64]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def manifest-schema "cloud.itonami.desktop-update.v1")
(def manifest-name "update-manifest.edn")
(def max-manifest-bytes (* 1024 1024))
(def max-package-bytes (* 512 1024 1024))

(defonce ^:private updater-state
  (atom {:schema "cloud.itonami.update-status.v1"
         :status :idle
         :checked-at nil
         :available? false
         :restart-required? false}))
(defonce ^:private scheduler (atom nil))

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[k v]] [k (canonical-value v)])) value)
    (vector? value) (mapv canonical-value value)
    (set? value) (->> value (map canonical-value) (sort-by pr-str) vec)
    (seq? value) (mapv canonical-value value)
    :else value))

(defn signable-bytes
  "Canonical bytes covered by the manifest signature. Public for the release
  signer and focused parity tests; callers never choose which fields are
  omitted."
  [manifest]
  (.getBytes (pr-str (canonical-value (dissoc manifest :signature)))
             StandardCharsets/UTF_8))

(defn- decode-public-key [encoded]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec.
                    (.decode (Base64/getDecoder) (str/trim encoded)))))

(defn embedded-public-key []
  (-> "cloud-itonami-update-public-key.b64" io/resource slurp decode-public-key))

(defn verify-manifest
  ([manifest] (verify-manifest manifest (embedded-public-key)))
  ([manifest public-key]
   (when-not (= manifest-schema (:schema manifest))
     (throw (ex-info "unsupported update manifest schema"
                     {:type :update/schema :schema (:schema manifest)})))
   (when-not (re-matches #"\d+\.\d+\.\d+" (str (:version manifest)))
     (throw (ex-info "invalid update version" {:type :update/version})))
   (when-not (and (map? (:assets manifest)) (seq (:assets manifest)))
     (throw (ex-info "update manifest has no assets" {:type :update/assets})))
   (doseq [[platform asset] (:assets manifest)]
     (when-not (and (keyword? platform)
                    (string? (:url asset))
                    (re-matches #"[0-9a-f]{64}" (str (:sha256 asset)))
                    (pos-int? (:size asset)))
       (throw (ex-info "invalid update asset" {:type :update/asset
                                                :platform platform}))))
   (let [signature-text (:signature manifest)
         verifier (Signature/getInstance "Ed25519")]
     (when-not (string? signature-text)
       (throw (ex-info "update manifest is unsigned" {:type :update/signature})))
     (.initVerify verifier public-key)
     (.update verifier (signable-bytes manifest))
     (when-not (.verify verifier (.decode (Base64/getDecoder) signature-text))
       (throw (ex-info "update manifest signature is invalid"
                       {:type :update/signature}))))
   manifest))

(defn current-version []
  (-> "cloud-itonami-version.edn" io/resource slurp edn/read-string :version))

(defn- version-parts [version]
  (mapv parse-long (str/split (str version) #"\.")))

(defn newer-version? [candidate installed]
  (pos? (compare (version-parts candidate) (version-parts installed))))

(defn platform-key []
  (let [os (str/lower-case (System/getProperty "os.name" ""))
        arch (str/lower-case (System/getProperty "os.arch" ""))]
    (cond
      (and (str/includes? os "mac") (contains? #{"aarch64" "arm64"} arch)) :macos-arm64
      (and (str/includes? os "mac") (contains? #{"x86_64" "amd64"} arch)) :macos-x64
      (str/includes? os "windows") :windows-x64
      :else :unsupported)))

(defn- http-get-bytes [url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Accept" "application/vnd.github+json, application/edn")
                    (.header "User-Agent" "cloud-itonami-app-updater")
                    (.GET)
                    (.build))
        client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/ALWAYS)
                   (.build))
        response (.send client request
                        (HttpResponse$BodyHandlers/ofByteArray))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "update transport returned an error"
                      {:type :update/http :status (.statusCode response)})))
    (.body response)))

(def ^:dynamic *http-get-bytes* http-get-bytes)

(defn- parse-releases [bytes]
  (json/read-str (String. ^bytes bytes StandardCharsets/UTF_8) :key-fn keyword))

(defn- release-manifest-url [releases channel]
  (some (fn [release]
          (when (and (not (:draft release))
                     (or (= channel :preview) (not (:prerelease release))))
            (some (fn [asset]
                    (when (= manifest-name (:name asset))
                      (:browser_download_url asset)))
                  (:assets release))))
        releases))

(defn- safe-package-url? [url]
  (str/starts-with? url
                    "https://github.com/cloud-itonami/cloud-itonami-app/releases/download/"))

(defn check!
  ([] (check! (config/load-config)))
  ([configuration]
   (let [updates (:updates configuration)
         now (str (Instant/now))]
     (try
       (let [release-bytes (*http-get-bytes* (:releases-url updates))
             manifest-url (release-manifest-url (parse-releases release-bytes)
                                                (:channel updates))]
         (when-not manifest-url
           (throw (ex-info "no signed update manifest is published"
                           {:type :update/not-published})))
         (let [manifest-bytes (*http-get-bytes* manifest-url)]
           (when (> (alength ^bytes manifest-bytes) max-manifest-bytes)
             (throw (ex-info "update manifest is too large" {:type :update/size})))
           (let [manifest (-> (String. ^bytes manifest-bytes StandardCharsets/UTF_8)
                              edn/read-string
                              verify-manifest)
                 installed (current-version)
                 available? (newer-version? (:version manifest) installed)
                 platform (platform-key)
                 asset (get-in manifest [:assets platform])
                 result {:schema "cloud.itonami.update-status.v1"
                         :status (if available? :available :current)
                         :checked-at now
                         :installed-version installed
                         :available-version (when available? (:version manifest))
                         :available? (and available? (some? asset))
                         :platform platform
                         :platform-supported? (some? asset)
                         :release-url (:release-url manifest)
                         :restart-required? false
                         :manifest manifest}]
             (reset! updater-state result)
             (dissoc result :manifest))))
       (catch Exception error
         (let [result {:schema "cloud.itonami.update-status.v1"
                       :status :error
                       :checked-at now
                       :installed-version (current-version)
                       :available? false
                       :restart-required? false
                       :error (.getMessage error)}]
           (reset! updater-state result)
           result))))))

(defn- sha256-file [^Path path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (Files/newInputStream path (make-array java.nio.file.OpenOption 0))]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- download-to! [url ^Path destination]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Accept" "application/octet-stream")
                    (.header "User-Agent" "cloud-itonami-app-updater")
                    (.GET)
                    (.build))
        client (-> (HttpClient/newBuilder)
                   (.followRedirects HttpClient$Redirect/ALWAYS)
                   (.build))
        response (.send client
                        request
                        (HttpResponse$BodyHandlers/ofFile destination))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info "update download returned an error"
                      {:type :update/http :status (.statusCode response)})))
    destination))

(def ^:dynamic *download-to!* download-to!)

(defn download!
  ([] (download! (config/load-config)))
  ([configuration]
   (let [checked (if (:manifest @updater-state)
                   @updater-state
                   (do (check! configuration) @updater-state))
         platform (:platform checked)
         manifest (:manifest checked)
         asset (get-in manifest [:assets platform])]
     (when-not (and (:available? checked) asset)
       (throw (ex-info "no update is available for this platform"
                       {:type :update/not-available :platform platform})))
     (when-not (safe-package-url? (:url asset))
       (throw (ex-info "update package URL is outside the release origin"
                       {:type :update/origin})))
     (when (> (:size asset) max-package-bytes)
       (throw (ex-info "update package exceeds the size limit" {:type :update/size})))
     (let [pending (.toPath (io/file (config/data-dir) "updates" "pending"))
           temporary (.resolve pending "package.download")
           package (.resolve pending "package.zip")
           marker (.resolve pending "pending.edn")]
       (Files/createDirectories pending (make-array java.nio.file.attribute.FileAttribute 0))
       (*download-to!* (:url asset) temporary)
       (let [actual-size (Files/size temporary)
             actual-sha (sha256-file temporary)]
         (when-not (= (:size asset) actual-size)
           (Files/deleteIfExists temporary)
           (throw (ex-info "update package size does not match the signed manifest"
                           {:type :update/size :expected (:size asset)
                            :actual actual-size})))
         (when-not (= (:sha256 asset) actual-sha)
           (Files/deleteIfExists temporary)
           (throw (ex-info "update package digest does not match the signed manifest"
                           {:type :update/digest})))
         (Files/move temporary package
                     (into-array StandardCopyOption
                                 [StandardCopyOption/REPLACE_EXISTING
                                  StandardCopyOption/ATOMIC_MOVE]))
         (spit (.toFile marker)
               (pr-str {:schema "cloud.itonami.pending-update.v1"
                        :version (:version manifest)
                        :platform platform
                        :package (.getCanonicalPath (.toFile package))
                        :sha256 actual-sha
                        :size actual-size
                        :release-url (:release-url manifest)}))
         (let [result (assoc checked
                             :status :downloaded
                             :restart-required? true
                             :staged-version (:version manifest))]
           (reset! updater-state result)
           (dissoc result :manifest)))))))

(defn status []
  (let [pending (io/file (config/data-dir) "updates" "pending" "pending.edn")]
    (-> @updater-state
        (dissoc :manifest)
        (assoc :restart-required? (or (:restart-required? @updater-state)
                                      (.isFile pending))))))

(defn check-and-stage!
  "Check for a newer signed release and, when configured, stage its verified
  package. Applying it remains a launcher-time operation so an update never
  kills work in an active desktop session."
  [configuration]
  (let [checked (check! configuration)]
    (if (and (:available? checked)
             (get-in configuration [:updates :auto-download?])
             (not (:restart-required? (status))))
      (try
        (download! configuration)
        (catch Exception error
          (let [result {:schema "cloud.itonami.update-status.v1"
                        :status :error
                        :checked-at (str (Instant/now))
                        :installed-version (current-version)
                        :available-version (:available-version checked)
                        :available? true
                        :restart-required? false
                        :error (.getMessage error)}]
            (reset! updater-state result)
            result)))
      checked)))

(defn start! [configuration]
  (when (and (get-in configuration [:updates :enabled?]) (nil? @scheduler))
    (let [service (Executors/newSingleThreadScheduledExecutor)
          interval-minutes (* 60 (long (get-in configuration
                                                [:updates :interval-hours] 24)))
          initial-delay (long (get-in configuration
                                      [:updates :initial-delay-minutes] 60))]
      (.scheduleWithFixedDelay ^ScheduledExecutorService service
                               ^Runnable #(check-and-stage! configuration)
                               initial-delay interval-minutes TimeUnit/MINUTES)
      (reset! scheduler service)))
  (status))

(defn stop! []
  (when-let [^ScheduledExecutorService service @scheduler]
    (.shutdownNow service)
    (reset! scheduler nil)))
