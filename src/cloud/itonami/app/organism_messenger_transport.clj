(ns cloud.itonami.app.organism-messenger-transport
  "Capability-bearer authentication for an external OrganismWorker mailbox.

  The clear token exists only in a 0600 file under Tamaki's private workplace.
  Cloud Itonami persists its SHA-256 digest and resolves the bearer to exactly
  one active assignment; a request never supplies the mailbox principal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.organism-gateway :as gateway]
            [cloud.itonami.app.secure-file :as secure-file]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest SecureRandom]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.organism-messenger-transport.v1")

(defn- fail! [type message & [data]]
  (throw (ex-info message (assoc (or data {}) :type type))))

(defn- safe-worker-id [value]
  (let [value (some-> value str str/trim)]
    (when-not (and value (re-matches #"[A-Za-z0-9._:-]{1,160}" value))
      (fail! :ao.messenger/invalid "invalid worker id"))
    value))

(defn- digest-bytes [value]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- random-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- credential-directory []
  (io/file (or (System/getenv "CLOUD_ITONAMI_TAMAKI_STATE_DIR")
               (io/file (gateway/tamaki-root) ".tamaki"))
           "workplace" "credentials"))

(defn credential-file [worker-id]
  (io/file (credential-directory) (str (safe-worker-id worker-id) ".messenger.edn")))

(defn- owner-only! [file]
  (secure-file/harden! file "rw-------")
  file)

(defn- write-credential! [worker-id value]
  (let [file (credential-file worker-id)
        parent (.getParentFile file)
        temporary (io/file parent (str "." (.getName file) "."
                                        (UUID/randomUUID) ".tmp"))]
    (.mkdirs parent)
    (secure-file/create-file! temporary)
    (Files/write (.toPath temporary)
                 (.getBytes (str (pr-str value) "\n") StandardCharsets/UTF_8)
                 (make-array java.nio.file.OpenOption 0))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (owner-only! file)
    file))

(defn issue!
  "Issue or rotate a worker credential after a human owner/admin gate. The
  token is intentionally not returned over HTTP; the external supervisor reads
  its private credential file."
  [worker-id organization]
  (let [worker-id (safe-worker-id worker-id)
        assignment (gateway/assignment worker-id)]
    (when-not assignment
      (fail! :ao.worker/not-found "organism worker was not found" {:id worker-id}))
    (when-not (= (str/lower-case (str organization))
                 (str/lower-case (str (:ao.worker/organization assignment))))
      (fail! :ao.worker/not-found "organism worker is outside the active organization"
             {:id worker-id}))
    (when-not (= :active (:ao.worker/status assignment))
      (fail! :ao.messenger/inactive "organism worker is not active" {:id worker-id}))
    (let [token (random-token)
          id (str "ao-messenger-" (UUID/randomUUID))
          issued-at (store/now)
          public {:schema schema :id id :worker-id worker-id
                  :organization organization :issued-at issued-at
                  :credential-file (.getCanonicalPath (credential-file worker-id))}
          record (assoc public :token-digest (str "sha256:" (hex (digest-bytes token))))]
      ;; A file written before a failed state commit contains a token that the
      ;; server does not recognize. The inverse order could activate a token
      ;; the supervisor never received.
      (write-credential! worker-id
                         {:schema schema :id id :worker-id worker-id
                          :organization organization :token token
                          :issued-at issued-at})
      (store/transact! assoc-in [:organism-messenger-transports worker-id] record)
      public)))

(defn revoke! [worker-id]
  (let [worker-id (safe-worker-id worker-id)
        file (credential-file worker-id)]
    (store/transact! update :organism-messenger-transports dissoc worker-id)
    (when (.isFile file) (Files/delete (.toPath file)))
    {:schema schema :worker-id worker-id :revoked? true}))

(defn authenticate
  "Resolve a bearer token in constant time. Returns no token or digest."
  [token]
  (when-not (str/blank? (str token))
    (let [presented (digest-bytes (str/trim (str token)))]
      (some (fn [[worker-id record]]
              (let [expected (some-> (:token-digest record)
                                     (str/replace #"^sha256:" ""))]
                (when (and expected
                           (MessageDigest/isEqual
                            presented
                            ;; Compare SHA-256 bytes, not their text form.
                            (byte-array
                             (map #(unchecked-byte (Integer/parseInt % 16))
                                  (map #(apply str %) (partition 2 expected))))))
                  (let [assignment (gateway/assignment worker-id)]
                    (when (and assignment
                               (= :active (:ao.worker/status assignment))
                               (= (str/lower-case (str (:organization record)))
                                  (str/lower-case
                                   (str (:ao.worker/organization assignment)))))
                      {:schema schema
                       :worker-id worker-id
                       :organization (:organization record)
                       :principal (str "organism:" worker-id)
                       :assignment assignment})))))
            (:organism-messenger-transports (store/snapshot))))))

(defn read-credential
  "Supervisor-side helper for local integration tests and launch scripts."
  [worker-id]
  (let [file (credential-file worker-id)]
    (when (.isFile file) (edn/read-string (slurp file)))))
