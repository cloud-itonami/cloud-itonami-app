(ns cloud.itonami.app.sign-update-manifest
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.updater :as updater])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.time Instant]
           [java.util Base64]))

(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- asset [file url]
  {:url url
   :sha256 (sha256 file)
   :size (Files/size (.toPath (io/file file)))})

(defn- private-key [path]
  (let [encoded (.decode (Base64/getDecoder) (str/trim (slurp path)))]
    (.generatePrivate (KeyFactory/getInstance "Ed25519")
                      (PKCS8EncodedKeySpec. encoded))))

(defn- sign [manifest key]
  (let [signer (Signature/getInstance "Ed25519")]
    (.initSign signer key)
    (.update signer (updater/signable-bytes manifest))
    (.encodeToString (Base64/getEncoder) (.sign signer))))

(defn -main [& _]
  (let [root (.getCanonicalFile (io/file "."))
        release-dir (io/file root "target" "release")
        version (-> "cloud-itonami-version.edn" io/resource slurp read-string :version)
        tag (str "v" version)
        base-url (str "https://github.com/cloud-itonami/cloud-itonami-app/releases/download/" tag "/")
        mac-name (str "Cloud-Itonami-" version "-macOS-arm64.zip")
        windows-name (str "Cloud-Itonami-" version "-Windows-x64.zip")
        signing-key (or (System/getenv "CLOUD_ITONAMI_UPDATE_SIGNING_KEY")
                        (str (System/getProperty "user.home")
                             "/.gftd/cloud-itonami-app-updater-ed25519.pk8.b64"))
        source-commit (or (System/getenv "CLOUD_ITONAMI_SOURCE_COMMIT")
                          (throw (ex-info "CLOUD_ITONAMI_SOURCE_COMMIT is required" {})))
        unsigned {:schema updater/manifest-schema
                  :version version
                  :published-at (str (Instant/now))
                  :source-commit source-commit
                  :release-url (str "https://github.com/cloud-itonami/cloud-itonami-app/releases/tag/" tag)
                  :assets {:macos-arm64
                           (asset (io/file release-dir mac-name) (str base-url mac-name))
                           :windows-x64
                           (asset (io/file release-dir windows-name) (str base-url windows-name))}}
        manifest (assoc unsigned :signature (sign unsigned (private-key signing-key)))
        output (io/file release-dir updater/manifest-name)]
    (spit output (str (pr-str manifest) "\n"))
    (updater/verify-manifest manifest)
    (println (.getCanonicalPath output))))
