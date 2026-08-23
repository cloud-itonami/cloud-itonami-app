(ns cloud.itonami.app.updater-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.updater :as updater])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security KeyPairGenerator MessageDigest Signature]
           [java.util Base64]))

(defn- key-pair []
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))

(defn- sign [manifest private-key]
  (let [signature (Signature/getInstance "Ed25519")]
    (.initSign signature private-key)
    (.update signature (updater/signable-bytes manifest))
    (.encodeToString (Base64/getEncoder) (.sign signature))))

(defn- sha256 [^bytes content]
  (format "%064x"
          (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") content))))

(defn- signed-manifest [pair package]
  (let [unsigned {:schema updater/manifest-schema
                  :version "9.0.0"
                  :source-commit (apply str (repeat 40 "a"))
                  :published-at "2026-08-10T00:00:00Z"
                  :release-url "https://github.com/cloud-itonami/cloud-itonami-app/releases/tag/v9.0.0"
                  :assets {(updater/platform-key)
                           {:url "https://github.com/cloud-itonami/cloud-itonami-app/releases/download/v9.0.0/package.zip"
                            :sha256 (sha256 package)
                            :size (alength ^bytes package)}}}]
    (assoc unsigned :signature (sign unsigned (.getPrivate pair)))))

(deftest signed-manifests-fail-closed
  (let [pair (key-pair)
        manifest (signed-manifest pair (.getBytes "package" StandardCharsets/UTF_8))]
    (is (= manifest (updater/verify-manifest manifest (.getPublic pair))))
    (testing "a transport cannot change even one signed field"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signature is invalid"
                            (updater/verify-manifest
                             (assoc manifest :version "9.0.1")
                             (.getPublic pair)))))
    (testing "another key cannot bless the same bytes"
      (is (thrown? clojure.lang.ExceptionInfo
                   (updater/verify-manifest manifest (.getPublic (key-pair))))))))

(deftest versions-advance-only-forward
  (is (updater/newer-version? "1.2.0" "1.1.9"))
  (is (not (updater/newer-version? "1.2.0" "1.2.0")))
  (is (not (updater/newer-version? "1.1.9" "1.2.0"))))

(deftest discovery-and-download-stage-only-verified-bytes
  (let [pair (key-pair)
        package (.getBytes "verified package bytes" StandardCharsets/UTF_8)
        manifest (signed-manifest pair package)
        manifest-url "https://github.com/cloud-itonami/cloud-itonami-app/releases/download/v9.0.0/update-manifest.edn"
        releases-url "https://api.github.test/releases"
        releases (json/write-str [{:draft false :prerelease true
                                   :assets [{:name updater/manifest-name
                                             :browser_download_url manifest-url}]}])
        directory (.toFile (Files/createTempDirectory
                            "cloud-itonami-updater-test"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        configuration {:updates {:channel :preview :releases-url releases-url}}]
    (with-redefs [updater/embedded-public-key (constantly (.getPublic pair))
                  config/data-dir (constantly directory)]
      (binding [updater/*http-get-bytes*
                (fn [url]
                  (cond
                    (= url releases-url) (.getBytes releases StandardCharsets/UTF_8)
                    (= url manifest-url) (.getBytes (pr-str manifest) StandardCharsets/UTF_8)
                    :else (throw (ex-info "unexpected URL" {:url url}))))
                updater/*download-to!*
                (fn [_ destination]
                  (Files/write destination package
                               (make-array java.nio.file.OpenOption 0)))]
        (let [checked (updater/check! configuration)
              downloaded (updater/download! configuration)
              marker (io/file directory "updates" "pending" "pending.edn")
              checksum (io/file directory "updates" "pending" "package.sha256")
              version-file (io/file directory "updates" "pending" "version.txt")
              staged (io/file directory "updates" "pending" "package.zip")]
          (is (:available? checked))
          (is (= "9.0.0" (:available-version checked)))
          (is (:restart-required? downloaded))
          (is (= (seq package) (seq (Files/readAllBytes (.toPath staged)))))
          (is (= (str (sha256 package) "  package.zip\n") (slurp checksum)))
          (is (= "9.0.0\n" (slurp version-file)))
          (is (= "9.0.0" (:version (edn/read-string (slurp marker))))))))))

(deftest automatic-staging-fails-closed-on-changed-package
  (let [pair (key-pair)
        signed-package (.getBytes "signed package" StandardCharsets/UTF_8)
        changed-package (.getBytes "changed package" StandardCharsets/UTF_8)
        manifest (signed-manifest pair signed-package)
        manifest-url "https://github.com/cloud-itonami/cloud-itonami-app/releases/download/v9.0.0/update-manifest.edn"
        releases-url "https://api.github.test/releases"
        releases (json/write-str [{:draft false :prerelease true
                                   :assets [{:name updater/manifest-name
                                             :browser_download_url manifest-url}]}])
        directory (.toFile (Files/createTempDirectory
                            "cloud-itonami-updater-tamper-test"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        configuration {:updates {:channel :preview
                                 :releases-url releases-url
                                 :auto-download? true}}]
    (with-redefs [updater/embedded-public-key (constantly (.getPublic pair))
                  config/data-dir (constantly directory)]
      (binding [updater/*http-get-bytes*
                (fn [url]
                  (if (= url releases-url)
                    (.getBytes releases StandardCharsets/UTF_8)
                    (.getBytes (pr-str manifest) StandardCharsets/UTF_8)))
                updater/*download-to!*
                (fn [_ destination]
                  (Files/write destination changed-package
                               (make-array java.nio.file.OpenOption 0)))]
        (let [result (updater/check-and-stage! configuration)]
          (is (= :error (:status result)))
          (is (re-find #"does not match" (:error result)))
          (is (not (.exists (io/file directory "updates" "pending" "pending.edn")))))))))
