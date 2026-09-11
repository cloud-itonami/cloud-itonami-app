(ns cloud.itonami.app.tenant-repository
  "Connection-gated direct EDN access to one user's tenant repository.

  Plaintext exists only in the local editable workspace. `publish!` uses the
  existing Kagi/DataLad/Kotobase pipeline; this namespace never accepts keys or
  remote credentials from an agent request."
  (:require [clojure.java.io :as io]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.repository-runtime :as runtime]
            [cloud.itonami.app.repository-storage :as repository]
            [cloud.itonami.app.tenant-connection :as connection])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:dynamic *environment* #(System/getenv %))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn owner-id
  "Opaque stable coordinate. User and tenant IDs never become filesystem names
  or Kotobase CIDs themselves."
  [session tenant-id]
  (let [source (.getBytes (str (:user-id session) "\u0000" tenant-id)
                          StandardCharsets/UTF_8)]
    (str "usr-" (hex (.digest (MessageDigest/getInstance "SHA-256") source)))))

(defn- roots []
  {:workspace-root
   (or (not-empty (*environment* "CLOUD_ITONAMI_WORKSPACE_ROOT"))
       (.getPath (io/file (config/data-dir) "workspace")))
   :datalad-root
   (or (not-empty (*environment* "CLOUD_ITONAMI_DATALAD_DATASET"))
       (.getPath (io/file (config/data-dir) "datalad")))})

(defn- parse-state [state-edn]
  (when-not (string? state-edn)
    (throw (ex-info "state_edn is required"
                    {:type :tenant-repository/state-required})))
  (repository/validate-state! (repository/decode-wire-string state-edn)))

(defn read! [session connection-id]
  (let [context (connection/context! session connection-id "repository.query")
        owner (owner-id session (:tenant-id context))
        {:keys [workspace-root]} (roots)
        workspace (repository/workspace-snapshot workspace-root owner)]
    (when-not workspace
      (throw (ex-info "tenant repository workspace is not initialized"
                      {:type :tenant-repository/not-found})))
    {:schema "cloud.itonami.app.tenant-repository.v1"
     :connection-id connection-id
     :tenant-id (:tenant-id context)
     :repository-stream (:repository-stream context)
     :owner owner
     :semantic-cid (repository/semantic-cid (:state workspace))
     :state-edn (repository/wire-string (:state workspace))}))

(defn write!
  [session connection-id {:keys [state-edn state_edn expected-cid expected_cid]}]
  (let [candidate (parse-state (or state-edn state_edn))
        expected (or expected-cid expected_cid)
        storage-bytes (alength (repository/canonical-bytes candidate))
        visible (connection/connection session connection-id)
        owner (owner-id session (:tenant-id visible))
        {:keys [workspace-root datalad-root]} (roots)
        tenant-context (volatile! nil)
        result (repository/replace-workspace!
                {:workspace-root workspace-root :datalad-root datalad-root
                 :owner owner :expected-cid expected :candidate candidate
                 :before-write
                 (fn [_]
                   (vreset! tenant-context
                            (connection/context!
                             session connection-id "repository.write"
                             {:storage-bytes storage-bytes})))})]
    {:schema "cloud.itonami.app.tenant-repository.v1"
     :connection-id connection-id
     :tenant-id (:tenant-id @tenant-context)
     :repository-stream (:repository-stream @tenant-context)
     :owner owner
     :semantic-cid (:semantic/cid result)
     :storage-used-bytes storage-bytes}))

(defn publish! [session connection-id]
  (let [visible (connection/connection session connection-id)
        owner (owner-id session (:tenant-id visible))
        tenant-context (volatile! nil)
        result
        (repository/commit-workspace!
         (assoc (runtime/production-context owner)
                :before-publish
                (fn [prepared]
                  (vreset! tenant-context
                           (connection/context!
                            session connection-id "repository.write"
                            {:publication-id (:tx/id prepared)
                             :published-byte-delta
                             (get-in prepared [:head :sealed/bytes])})))))]
    {:schema "cloud.itonami.app.tenant-repository-publication.v1"
     :connection-id connection-id
     :tenant-id (:tenant-id @tenant-context)
     :repository-stream (:repository-stream @tenant-context)
     :storage-used-bytes (:storage-used-bytes @tenant-context)
     :semantic-cid (:semantic/cid result)
     :head-cid (get-in result [:head :head/cid])
     :revision (get-in result [:receipt :revision])}))
