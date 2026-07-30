(ns cloud.itonami.app.workspace-manifest
  "Content-bound policy manifests for one Coding Agent working folder."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]
           [java.util Base64]))

(def schema "cloud.itonami.workspace-manifest.v1")
(def excluded-names
  [".git" ".env" ".env.local" ".ssh" ".gnupg"
   "id_rsa" "id_ed25519" "credentials.json"])

(defn- digest [value]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes (pr-str value) StandardCharsets/UTF_8))
      (->> (.encodeToString
            (.withoutPadding (Base64/getUrlEncoder))))))

(defn- root-identity [root]
  (let [attributes
        (Files/readAttributes
         root BasicFileAttributes (make-array LinkOption 0))]
    {:real-path (str root)
     :file-key (some-> (.fileKey attributes) str)
     :created-millis (.toMillis (.creationTime attributes))}))

(defn build [workspace access]
  (let [root (.toRealPath
              (Paths/get (str workspace) (make-array String 0))
              (make-array LinkOption 0))
        stable {:schema schema
                :root (root-identity root)
                :access access
                :allowed-roots ["."]
                :excluded-names excluded-names
                :symlink-policy :resolve-root-deny-escape}]
    (assoc stable :created-at (store/now)
           :digest (digest stable))))

(defn valid? [manifest]
  (and (= schema (:schema manifest))
       (= (:digest manifest)
          (digest (dissoc manifest :created-at :digest)))))

(defn verify! [manifest]
  (when-not (valid? manifest)
    (throw (ex-info "Coding Agent workspace manifest was modified."
                    {:type :cli-agent/manifest-invalid})))
  (let [current (build (get-in manifest [:root :real-path])
                       (:access manifest))]
    (when-not (= (:root manifest) (:root current))
      (throw
       (ex-info "Coding Agent workspace identity changed after approval."
                {:type :cli-agent/workspace-changed})))
    manifest))

(defn excluded-path? [manifest relative-path]
  (let [segments
        (remove str/blank?
                (str/split
                 (str/replace (str relative-path) "\\" "/") #"/"))]
    (boolean (some (set (:excluded-names manifest)) segments))))
