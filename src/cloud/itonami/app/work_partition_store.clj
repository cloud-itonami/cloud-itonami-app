(ns cloud.itonami.app.work-partition-store
  "Generation-pinned, physically partitioned storage for governed work.

  A manifest is the commit point. Every transaction writes a new global file
  and one new file per organization, then atomically replaces the manifest.
  Readers therefore see either the complete previous generation or the
  complete next generation, never a mixture of tenant files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cloud.itonami.app.config :as config])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util UUID]))

(def schema "cloud.itonami.app.work-partitions.v1")
(def ^:private map-keys
  [:organizations :organization-units :positions :organization-roles
   :performers :assignments :approval-policies :work-items :source-bases])
(def ^:private vector-keys
  [:reporting-lines :approval-decisions :execution-receipts
   :verification-receipts :projection-receipts :audit-events :dead-letters])

(defn directory [] (io/file (config/data-dir) "work-governance"))
(defn manifest-file [] (io/file (directory) "manifest.edn"))

(defn- read-edn [file]
  (when (.isFile file) (edn/read-string (slurp file))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bytes)))

(defn- tenant-token [organization]
  (subs (hex (.digest (MessageDigest/getInstance "SHA-256")
                      (.getBytes (str organization) StandardCharsets/UTF_8)))
        0 24))

(defn- committed-file [name]
  (when-not (and (string? name)
                 (re-matches #"[a-z0-9._-]+\.edn" name))
    (throw (ex-info "invalid work partition manifest path"
                    {:type :work-partitions/invalid-path :path name})))
  (io/file (directory) name))

(defn- organization-for-item [ledger item-id]
  (get-in ledger [:work-items item-id :work.item/organization]))

(defn- record-organization [ledger key value]
  (case key
    :reporting-lines (:reporting/organization value)
    :approval-decisions
    (organization-for-item ledger (:approval.decision/work-item value))
    :execution-receipts
    (organization-for-item ledger (:execution.receipt/work-item value))
    :verification-receipts
    (organization-for-item ledger (:verification.receipt/work-item value))
    :projection-receipts
    (organization-for-item ledger (:projection.receipt/work-item value))
    :audit-events (or (:audit/organization value)
                      (organization-for-item ledger (:audit/work-item value)))
    :dead-letters (organization-for-item ledger (:dead-letter/work-item value))))

(defn- organization-ids [ledger]
  (->> (concat
        (keys (:organizations ledger))
        (keep :org.unit/organization (vals (:organization-units ledger)))
        (keep :org.position/organization (vals (:positions ledger)))
        (keep :org.role/organization (vals (:organization-roles ledger)))
        (keep :performer/organization (vals (:performers ledger)))
        (keep :org.assignment/organization (vals (:assignments ledger)))
        (keep :reporting/organization (:reporting-lines ledger))
        (keep :approval.policy/organization (vals (:approval-policies ledger)))
        (keep :work.item/organization (vals (:work-items ledger))))
       (remove nil?) set sort vec))

(defn- tenant-fragment [ledger organization generation]
  (let [item? #(= organization (:work.item/organization %))
        item-ids (->> (:work-items ledger) vals (filter item?)
                      (map :work.item/id) set)]
    {:partition/schema schema
     :partition/generation generation
     :partition/organization organization
     :organizations (select-keys (:organizations ledger) [organization])
     :organization-units
     (into {} (filter (fn [[_ value]]
                        (= organization (:org.unit/organization value))))
           (:organization-units ledger))
     :positions
     (into {} (filter (fn [[_ value]]
                        (= organization (:org.position/organization value))))
           (:positions ledger))
     :organization-roles
     (into {} (filter (fn [[_ value]]
                        (= organization (:org.role/organization value))))
           (:organization-roles ledger))
     :performers (into {} (filter (fn [[_ value]]
                                    (= organization
                                       (:performer/organization value))))
                       (:performers ledger))
     :assignments (into {} (filter (fn [[_ value]]
                                     (= organization
                                        (:org.assignment/organization value))))
                        (:assignments ledger))
     :reporting-lines (filterv #(= organization (:reporting/organization %))
                               (:reporting-lines ledger))
     :approval-policies
     (into {} (filter (fn [[_ value]]
                        (= organization (:approval.policy/organization value))))
           (:approval-policies ledger))
     :work-items (into {} (filter (fn [[id _]] (item-ids id)))
                       (:work-items ledger))
     :source-bases (select-keys (:source-bases ledger) item-ids)
     :approval-decisions
     (filterv #(item-ids (:approval.decision/work-item %))
              (:approval-decisions ledger))
     :execution-receipts
     (filterv #(item-ids (:execution.receipt/work-item %))
              (:execution-receipts ledger))
     :verification-receipts
     (filterv #(item-ids (:verification.receipt/work-item %))
              (:verification-receipts ledger))
     :projection-receipts
     (filterv #(item-ids (:projection.receipt/work-item %))
              (:projection-receipts ledger))
     :audit-events (filterv #(= organization
                                (record-organization ledger :audit-events %))
                            (:audit-events ledger))
     :dead-letters (filterv #(item-ids (:dead-letter/work-item %))
                            (:dead-letters ledger))}))

(defn- global-fragment [ledger organizations generation]
  (let [organizations (set organizations)
        tenant-item? #(contains? organizations (:work.item/organization %))]
    (-> ledger
        (assoc :partition/schema schema
               :partition/generation generation
               :partition/organizations (vec (sort organizations)))
        (assoc :organizations {})
        (update :organization-units
                #(into {} (remove (fn [[_ value]]
                                    (organizations
                                     (:org.unit/organization value)))) %))
        (update :positions
                #(into {} (remove (fn [[_ value]]
                                    (organizations
                                     (:org.position/organization value)))) %))
        (update :organization-roles
                #(into {} (remove (fn [[_ value]]
                                    (organizations
                                     (:org.role/organization value)))) %))
        (update :performers #(into {} (remove (fn [[_ value]]
                                                (organizations
                                                 (:performer/organization value)))) %))
        (update :assignments #(into {} (remove (fn [[_ value]]
                                                 (organizations
                                                  (:org.assignment/organization value)))) %))
        (update :approval-policies #(into {} (remove (fn [[_ value]]
                                                       (organizations
                                                        (:approval.policy/organization value)))) %))
        (update :work-items #(into {} (remove (fn [[_ value]]
                                                (tenant-item? value))) %))
        (update :source-bases #(select-keys %
                                           (keys (into {} (remove (fn [[id _]]
                                                                    (tenant-item?
                                                                     (get-in ledger [:work-items id]))))
                                                       %))))
        (update :reporting-lines #(filterv (fn [value]
                                             (not (organizations
                                                   (:reporting/organization value)))) %))
        (#(reduce (fn [result key]
                    (update result key
                            (fn [values]
                              (filterv (fn [value]
                                         (nil? (record-organization ledger key value)))
                                       values))))
                  % (remove #{:reporting-lines} vector-keys))))))

(defn- merge-fragment [ledger fragment]
  (-> (reduce (fn [result key]
                (update result key merge (get fragment key {})))
              ledger map-keys)
      (#(reduce (fn [result key]
                  (update result key into (get fragment key [])))
                % vector-keys))))

(defn load-ledger
  "Load the generation selected by the manifest, or return the legacy value.
  The fallback makes migration from state.edn automatic and non-destructive."
  [legacy]
  (if-let [manifest (read-edn (manifest-file))]
    (let [generation (:partition/generation manifest)
          global (read-edn (committed-file (:partition/global manifest)))]
      (when-not (and (= schema (:partition/schema manifest))
                     (= generation (:partition/generation global)))
        (throw (ex-info "work partition generation is incomplete"
                        {:type :work-partitions/incomplete-generation})))
      (reduce
       (fn [ledger [organization filename]]
         (let [fragment (read-edn (committed-file filename))]
           (when-not (and (= generation (:partition/generation fragment))
                          (= organization (:partition/organization fragment)))
             (throw (ex-info "tenant work partition is incomplete"
                             {:type :work-partitions/incomplete-tenant
                              :organization organization})))
           (merge-fragment ledger fragment)))
       (apply dissoc global [:partition/schema :partition/generation
                             :partition/organizations])
       (sort-by key (:partition/tenants manifest))))
    legacy))

(defn- restrict-owner! [file]
  (try
    (Files/setPosixFilePermissions
     (.toPath file) (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil))
  file)

(defn- atomic-write! [file value]
  (.mkdirs (.getParentFile file))
  (let [temporary (io/file (.getParentFile file)
                           (str "." (.getName file) "." (UUID/randomUUID) ".tmp"))]
    (spit temporary (pr-str value))
    (restrict-owner! temporary)
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    (restrict-owner! file))
  value)

(defn persist-ledger!
  "Persist a complete ledger as one manifest-pinned physical generation."
  [ledger]
  (let [previous (read-edn (manifest-file))
        generation (str (UUID/randomUUID))
        organizations (organization-ids ledger)
        global-name (str "global." generation ".edn")
        tenants (into {}
                      (map (fn [organization]
                             [organization
                              (str "tenant-" (tenant-token organization) "."
                                   generation ".edn")]))
                      organizations)]
    (atomic-write! (committed-file global-name)
                   (global-fragment ledger organizations generation))
    (doseq [[organization filename] tenants]
      (atomic-write! (committed-file filename)
                     (tenant-fragment ledger organization generation)))
    (let [manifest {:partition/schema schema
                    :partition/generation generation
                    :partition/global global-name
                    :partition/tenants tenants
                    :partition/committed-at (System/currentTimeMillis)}]
      (atomic-write! (manifest-file) manifest)
      ;; Keep the selected generation and one previous complete generation.
      ;; A process crash before the manifest swap can leave orphan files; the
      ;; next successful commit collects them without ever matching arbitrary
      ;; files in the data directory.
      (let [retained (set (concat [(:partition/global manifest)
                                   (:partition/global previous)]
                                  (vals (:partition/tenants manifest))
                                  (vals (:partition/tenants previous))))]
        (doseq [file (or (seq (.listFiles (directory))) [])
                :let [name (.getName file)]
                :when (and (.isFile file)
                           (re-matches
                            #"(?:global|tenant-[a-f0-9]{24})\.[a-f0-9-]{36}\.edn"
                            name)
                           (not (retained name)))]
          (Files/deleteIfExists (.toPath file)))))
    ledger))

(defn status []
  (if-let [manifest (read-edn (manifest-file))]
    {:schema schema :mode :physical-per-organization
     :generation (:partition/generation manifest)
     :tenants (count (:partition/tenants manifest))}
    {:schema schema :mode :legacy-single-file :tenants 0}))
