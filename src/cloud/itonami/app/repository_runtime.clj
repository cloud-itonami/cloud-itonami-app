(ns cloud.itonami.app.repository-runtime
  "Operator wiring for ADR-0013. Secrets come from Kagi/Keychain and the
  Kotobase token environment; they are never accepted as command arguments."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.repository-measurement :as measurement]
            [cloud.itonami.app.repository-invariants :as invariants]
            [cloud.itonami.app.repository-qualification :as qualification]
            [cloud.itonami.app.repository-storage :as repository])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(def ^:dynamic *environment*
  "Environment lookup seam for fail-closed preflight tests."
  #(System/getenv %))

(defn- env [name] (not-empty (*environment* name)))

(defn- required-env [name]
  (or (env name)
      (throw (ex-info (str name " is required")
                      {:type :repository-storage/config-required
                       :environment name}))))

(defn production-context
  "Resolve non-secret coordinates and unlock the pinned Kagi adapter
  non-interactively."
  []
  (let [load-kagi-context
        (or (requiring-resolve 'kagi.repository-context/load-context)
            (throw (ex-info "Kagi repository context is unavailable"
                            {:type :repository-storage/kagi-adapter-required})))
        owner (required-env "CLOUD_ITONAMI_STORAGE_OWNER")
        dataset (required-env "CLOUD_ITONAMI_DATALAD_DATASET")
        remote (required-env "CLOUD_ITONAMI_DATALAD_REMOTE")
        endpoint (or (env "CLOUD_ITONAMI_KOTOBASE_ENDPOINT")
                     "https://kotobase.net")
        token (required-env "CLOUD_ITONAMI_KOTOBASE_TOKEN")
        workspace-root (or (env "CLOUD_ITONAMI_WORKSPACE_ROOT")
                           (.getPath (io/file (config/data-dir) "workspace")))
        requested-key-epoch (some-> (env "CLOUD_ITONAMI_KEY_EPOCH")
                                    Long/parseLong)
        kagi-context (load-kagi-context
                      {:vault-home (env "KAGI_HOME")
                       :repository-id owner
                       :key-epoch requested-key-epoch})]
    (merge kagi-context
           {:owner owner
            :workspace-root workspace-root
            :datalad-root dataset
            :transport (repository/datalad-block-transport dataset remote)
            :head-store (repository/encrypted-graph-head-registry endpoint token)})))

(defn publish! []
  (repository/commit-workspace! (production-context)))

(defn hydrate! []
  (repository/hydrate-workspace! (production-context)))

(defn rotate-vmk!
  "Advance one repository VMK epoch, re-wrap chunk DEKs, publish the new
  manifest, and leave payload chunk ciphertext unchanged. A retry after a CAS
  failure reuses the already-created Kagi epoch instead of rotating again."
  []
  (let [{:keys [head-store owner provider signing-secret signing-public
                transport current-key-epoch vault-home]
         :as context} (production-context)
        {:keys [revision head]} (repository/head-snapshot head-store owner)]
    (when-not head
      (throw (ex-info "a published head is required before VMK rotation"
                      {:type :repository-storage/rotation-head-required})))
    (let [head-epoch (long (:key/epoch head))
          prepare-kagi (or (requiring-resolve
                            'kagi.repository-context/prepare-repository-vmk-rotation)
                          (throw (ex-info "Kagi VMK rotation is unavailable"
                                          {:type :repository-storage/kagi-rotation-required})))
          adopt-kagi (or (requiring-resolve
                          'kagi.repository-context/adopt-repository-vmk!)
                         (throw (ex-info "Kagi VMK adoption is unavailable"
                                         {:type :repository-storage/kagi-adoption-required})))
          aligned-context
          (if (< current-key-epoch head-epoch)
            (reduce
             (fn [kagi-context historical-head]
               (let [epoch (:key/epoch historical-head)]
                 (if (<= epoch (:current-key-epoch kagi-context))
                   kagi-context
                   (adopt-kagi
                    {:vault-home vault-home :provider provider
                     :repository-id owner
                     :key-epoch epoch
                     :key-envelope (:key/envelope historical-head)
                     :rotation-event (:key/rotation-event historical-head)}))))
             context
             (reverse (repository/retained-heads context head)))
            context)
          aligned-epoch (:current-key-epoch aligned-context)
          next-context
          (cond
            (= aligned-epoch head-epoch)
            (prepare-kagi {:vault-home vault-home :provider provider
                           :repository-id owner
                           :expected-epoch head-epoch})

            (= aligned-epoch (inc head-epoch)) aligned-context

            :else
            (throw (ex-info "Kagi keyring and published head epochs diverge"
                            {:type :repository-storage/key-epoch-divergence
                             :head-epoch head-epoch
                             :current-key-epoch aligned-epoch})))
          old-vmk (get (:vmks next-context) head-epoch)
          new-vmk (get (:vmks next-context) (inc head-epoch))
          prepared (repository/prepare-vmk-rotation
                    {:transport transport :provider provider
                     :vmk old-vmk :new-vmk new-vmk
                     :signing-secret signing-secret
                     :signing-public signing-public
                     :key-envelope (get (:key-envelopes next-context)
                                        (inc head-epoch))
                     :rotation-event (:repository-rotation-event next-context)
                     :owner owner :head head :key-epoch head-epoch})
          published
          (repository/publish-prepared!
           {:transport transport :head-store head-store :owner owner
            :expected-revision revision :signing-public signing-public
            :provider provider}
           prepared)
          event (:key/rotation-event (:head published))]
      (when event
        (adopt-kagi {:vault-home vault-home :provider provider
                     :repository-id owner :key-epoch (inc head-epoch)
                     :key-envelope (:key/envelope (:head published))
                     :rotation-event event}))
      (assoc published :key/epoch (inc head-epoch)))))

(defn migrate!
  [legacy-file]
  (let [owner (required-env "CLOUD_ITONAMI_STORAGE_OWNER")
        dataset (required-env "CLOUD_ITONAMI_DATALAD_DATASET")
        workspace-root (or (env "CLOUD_ITONAMI_WORKSPACE_ROOT")
                           (.getPath (io/file (config/data-dir) "workspace")))]
    (repository/migrate-legacy-state!
     {:workspace-root workspace-root :datalad-root dataset :owner owner}
     legacy-file)))

(defn audit!
  [markers]
  (repository/audit-datalad-blocks
   (required-env "CLOUD_ITONAMI_DATALAD_DATASET") markers))

(defn audit-profiles!
  [roots]
  (if (seq roots)
    (qualification/audit-profile-roots roots)
    (qualification/audit-profile-inventory
     "config/repository-storage-inventory.edn")))

(defn measure!
  [iterations]
  (measurement/measure-workspace
   (production-context)
   (some-> iterations Long/parseLong)))

(defn- safe-read-evidence [file]
  (edn/read-string
   {:readers {}
    :default (fn [tag _]
               (throw (ex-info "tagged qualification evidence denied"
                               {:type :repository-storage/tagged-evidence
                                :tag tag})))}
   (slurp file)))

(defn- nearest-git-root [file]
  (loop [current (.getCanonicalFile ^java.io.File file)]
    (cond
      (.exists (io/file current ".git")) current
      (.getParentFile current) (recur (.getParentFile current))
      :else nil)))

(defn- require-ignored-evidence-output! [file]
  (when-let [git-root (nearest-git-root (.getParentFile ^java.io.File file))]
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["git" "-C" (.getPath git-root) "check-ignore" "-q"
                        "--" (.getPath (.getCanonicalFile file))])
                      (.redirectErrorStream true)
                      .start)]
      (with-open [input (.getInputStream process)] (.readAllBytes input))
      (when-not (zero? (.waitFor process))
        (throw (ex-info "production evidence output must be ignored by Git"
                        {:type :repository-storage/evidence-git-trackable})))))
  file)

(defn- running-source-commit []
  (let [declared (required-env "CLOUD_ITONAMI_SOURCE_COMMIT")
        _ (when-not (re-matches #"[0-9a-f]{40}" declared)
            (throw (ex-info "CLOUD_ITONAMI_SOURCE_COMMIT must be an exact Git SHA"
                            {:type :repository-storage/invalid-source-commit})))
        git-root (nearest-git-root (io/file "."))
        checked-out
        (when git-root
          (let [process (-> (ProcessBuilder.
                             ^java.util.List
                             ["git" "-C" (.getPath git-root)
                              "rev-parse" "HEAD"])
                            (.redirectErrorStream true)
                            .start)
                output (with-open [input (.getInputStream process)]
                         (String. (.readAllBytes input)
                                  StandardCharsets/UTF_8))]
            (when-not (zero? (.waitFor process))
              (throw (ex-info "cannot resolve checked-out source commit"
                              {:type :repository-storage/source-commit-unavailable})))
            (.trim output)))]
    (when (and checked-out (not= declared checked-out))
      (throw (ex-info "declared source commit differs from checked-out code"
                      {:type :repository-storage/source-commit-mismatch})))
    declared))

(defn- executable-on-path? [name]
  (boolean
   (some (fn [directory]
           (let [file (io/file directory name)]
             (and (.isFile file) (.canExecute file))))
         (some-> (or (env "PATH") "")
                 (str/split
                  (re-pattern (java.util.regex.Pattern/quote
                               java.io.File/pathSeparator)))))))

(defn- require-check! [condition type]
  (when-not condition (throw (ex-info "repository preflight check failed"
                                      {:type type})))
  true)

(defn- dataset? [path]
  (and path
       (.isDirectory (io/file path))
       (.exists (io/file path ".git"))))

(defn- command-succeeds? [directory arguments]
  (try
    (let [process (-> (ProcessBuilder. ^java.util.List (vec arguments))
                      (.directory (io/file directory))
                      (.redirectErrorStream true)
                      .start)]
      (with-open [input (.getInputStream process)] (.readAllBytes input))
      (zero? (.waitFor process)))
    (catch Exception _ false)))

(defn- preflight-check [id f]
  (try
    (let [detail (f)]
      {:id id :ready? true :detail (if (keyword? detail) detail :ready)})
    (catch Exception error
      {:id id :ready? false
       :detail (or (:type (ex-data error)) :preflight/failed)})))

(defn preflight!
  "Read-only production readiness report. It emits no environment values,
  paths, tokens, owner IDs, Kagi material, heads or plaintext."
  []
  (let [owner (env "CLOUD_ITONAMI_STORAGE_OWNER")
        warm (env "CLOUD_ITONAMI_DATALAD_DATASET")
        cold (env "CLOUD_ITONAMI_COLD_DATALAD_DATASET")
        remote (env "CLOUD_ITONAMI_DATALAD_REMOTE")
        workspace-root (or (env "CLOUD_ITONAMI_WORKSPACE_ROOT")
                           (.getPath (io/file (config/data-dir) "workspace")))
        context-holder (atom nil)
        basic-checks
        [(preflight-check :datalad-cli
                          #(require-check! (executable-on-path? "datalad")
                                           :preflight/datalad-missing))
         (preflight-check :git-annex-cli
                          #(require-check! (executable-on-path? "git-annex")
                                           :preflight/git-annex-missing))
         (preflight-check :storage-owner
                          #(require-check! (repository/valid-owner? owner)
                                           :preflight/storage-owner-invalid))
         (preflight-check :kotobase-token
                          #(require-check! (env "CLOUD_ITONAMI_KOTOBASE_TOKEN")
                                           :preflight/kotobase-token-missing))
         (preflight-check :warm-datalad-dataset
                          #(require-check! (dataset? warm)
                                           :preflight/warm-dataset-missing))
         (preflight-check :cold-datalad-dataset
                          #(require-check! (dataset? cold)
                                           :preflight/cold-dataset-missing))
         (preflight-check :datasets-isolated
                          #(do
                             (require-check! (and warm cold)
                                             :preflight/dataset-missing)
                             (require-check!
                              (not= (.getCanonicalPath (io/file warm))
                                    (.getCanonicalPath (io/file cold)))
                              :preflight/datasets-not-isolated)))
         (preflight-check :cold-block-cache
                          #(do
                             (require-check! (dataset? cold)
                                             :preflight/cold-dataset-missing)
                             (repository/assert-empty-datalad-block-cache! cold)
                             :cache-empty))
         (preflight-check :datalad-remote
                          #(do
                             (require-check! (and (dataset? warm) remote)
                                             :preflight/remote-missing)
                             (require-check!
                              (command-succeeds?
                               warm ["git" "annex" "info" remote])
                              :preflight/remote-unconfigured)))
         (preflight-check :editable-workspace
                          #(require-check!
                            (and owner
                                 (.isFile (io/file workspace-root owner
                                                   "state.edn")))
                            :preflight/workspace-missing))
         (preflight-check :source-commit running-source-commit)
         (preflight-check :evidence-output
                          #(do
                             (require-ignored-evidence-output!
                              (io/file
                               "config/repository-production-evidence.edn"))
                             :git-ignored))]
        context-check
        (preflight-check :kagi-and-production-context
                         #(do (reset! context-holder (production-context))
                              :unlocked))
        head-check
        (preflight-check :kotobase-published-head
                         #(do
                            (require-check! @context-holder
                                            :preflight/context-unavailable)
                            (require-check!
                             (:head (repository/head-snapshot
                                     (:head-store @context-holder)
                                     (:owner @context-holder)))
                             :preflight/head-missing)
                            :head-present))
        checks (conj basic-checks context-check head-check)]
    {:qualified? (every? :ready? checks)
     :checks checks
     :missing (mapv :id (remove :ready? checks))}))

(defn- atomic-write-evidence! [file evidence]
  (let [file (.getCanonicalFile (io/file file))
        parent (.getParentFile file)
        temporary (io/file parent (str ".repository-evidence-"
                                        (UUID/randomUUID) ".tmp"))
        bytes (.getBytes (str (pr-str (into (sorted-map) evidence)) "\n")
                         StandardCharsets/UTF_8)]
    (.mkdirs parent)
    (require-ignored-evidence-output! file)
    (Files/write (.toPath temporary) bytes
                 (make-array java.nio.file.OpenOption 0))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    {:evidence-file (.getPath file)}))

(defn drill!
  "Collect source-bound local capacity and a real cache-empty hydrate into the
  ignored production evidence file. Peak write rate, sustained upload capacity,
  RTO and invariant approvals remain explicit operator/CI inputs."
  [iterations output-file]
  (let [context (production-context)
        cold-dataset (required-env "CLOUD_ITONAMI_COLD_DATALAD_DATASET")
        source-commit (running-source-commit)
        warm-path (.getCanonicalPath (io/file (:datalad-root context)))
        cold-path (.getCanonicalPath (io/file cold-dataset))
        _ (when (= warm-path cold-path)
            (throw (ex-info "cold recovery requires a separate DataLad dataset"
                            {:type :repository-storage/cold-dataset-not-isolated})))
        cold-context (assoc context
                            :datalad-root cold-path
                            :transport (repository/datalad-block-transport
                                        cold-path
                                        (required-env
                                         "CLOUD_ITONAMI_DATALAD_REMOTE")))
        workspace (repository/workspace-snapshot
                   (:workspace-root context) (:owner context))
        _ (when-not workspace
            (throw (ex-info "editable workspace is required for production drill"
                            {:type :repository-storage/workspace-missing})))
        local (measurement/measure-local-capacity
               context (:state workspace) (some-> iterations Long/parseLong))
        cold (measurement/measure-cold-hydrate cold-context)
        file (io/file (or output-file
                          "config/repository-production-evidence.edn"))
        prior (if (.isFile file)
                (safe-read-evidence file)
                (safe-read-evidence
                 (io/file "config/repository-production-evidence.example.edn")))
        evidence (merge prior
                        {:reconcile-bps (:reconcile-bps local)
                         :local-view-apply-bps (:local-view-apply-bps local)
                         :encrypted-output-bps (:sealed-output-bps local)
                         :hydrate-ms (:hydrate-ms cold)
                         :evidence/scope :production
                         :evidence/measured-at (str (Instant/now))
                         :evidence/source-commit source-commit
                         :evidence/cold-hydrate? (:cold-hydrate? cold)
                         :evidence/cold-downloaded-bytes
                         (:downloaded-bytes cold)})]
    (merge (atomic-write-evidence! file evidence)
           {:measurement {:local local :cold cold}})))

(defn usage!
  []
  (let [{:keys [head-store owner] :as context} (production-context)
        head (:head (repository/head-snapshot head-store owner))]
    (when-not head
      (throw (ex-info "a published head is required for usage accounting"
                      {:type :repository-storage/accounting-head-required})))
    (let [heads (repository/retained-heads context head)]
      (assoc (repository/storage-usage context heads)
             :heads (count heads)))))

(defn qualify!
  "Combine operator-supplied production rates/RTO with live profile, payload
  and retained-byte evidence. Private marker values are used in memory only."
  [evidence-file]
  (let [evidence
        (edn/read-string
         {:readers {}
          :default (fn [tag _]
                     (throw (ex-info "tagged qualification evidence denied"
                                     {:type :repository-storage/tagged-evidence
                                      :tag tag})))}
         (slurp evidence-file))
        _ (qualification/validate-production-attestation!
           evidence (running-source-commit))
        context (production-context)
        workspace (repository/workspace-snapshot
                   (:workspace-root context) (:owner context))
        _ (when-not workspace
            (throw (ex-info "workspace is required for qualification"
                            {:type :repository-storage/workspace-missing})))
        profiles (qualification/audit-profile-inventory
                  "config/repository-storage-inventory.edn")
        {:keys [head-store owner]} context
        head (:head (repository/head-snapshot head-store owner))
        _ (when-not head
            (throw (ex-info "published head is required for qualification"
                            {:type :repository-storage/qualification-head-required})))
        usage (repository/storage-usage
               context (repository/retained-heads context head))
        ;; Accounting fetches every referenced annex object first; the scan
        ;; therefore cannot pass merely because local content was dropped.
        audit (repository/audit-datalad-blocks
               (:datalad-root context)
               (repository/plaintext-markers (:state workspace)))
        live-invariants (invariants/verify)]
    (qualification/require-qualified!
     (assoc evidence
            :profiles-report profiles
            :datalad-audit audit
            :usage-reconciliation usage
            :semantic-convergence? (:semantic-convergence? live-invariants)
            :conflict-surfaced? (:conflict-surfaced? live-invariants)
            :vmk-rotation-payload-stable?
            (:vmk-rotation-payload-stable? live-invariants)
            :transport-failure-head-stable?
            (:transport-failure-head-stable? live-invariants)
            :query-backend-parity? (:query-backend-parity? live-invariants)))))

(defn -main [& [command & arguments]]
  (let [result
        (case command
          "migrate" (migrate! (or (first arguments)
                                  (.getPath (io/file (config/data-dir)
                                                     "state.edn"))))
          "publish" (publish!)
          "hydrate" (hydrate!)
          "rotate-vmk" (rotate-vmk!)
          "measure" (measure! (first arguments))
          "preflight" (preflight!)
          "drill" (drill! (first arguments) (second arguments))
          "usage" (usage!)
          "qualify" (qualify! (or (first arguments)
                                   "config/repository-production-evidence.edn"))
          "audit" (audit! arguments)
          "profiles" (audit-profiles! arguments)
          (throw (ex-info
                  "usage: repository preflight | migrate [legacy.edn] | publish | hydrate | rotate-vmk | measure [iterations] | drill [iterations] [evidence.edn] | usage | qualify [evidence.edn] | audit [markers...] | profiles [repo...]"
                  {:type :repository-storage/invalid-command})))]
    ;; Result summaries are explicitly stripped of plaintext, VMKs, blocks and
    ;; tokens before they reach an operator terminal.
    (prn (select-keys result
                      [:published? :head/revision :basis/cid :qualified?
                       :key/epoch :measurement :violations :failed :sealed/bytes
                       :physical/bytes :reconciled? :heads :blocks :state-file
                       :basis-file :base-file :evidence-file :checks :missing]))))
