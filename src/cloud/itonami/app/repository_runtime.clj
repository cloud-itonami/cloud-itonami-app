(ns cloud.itonami.app.repository-runtime
  "Operator wiring for ADR-0013. Secrets come from Kagi/Keychain and the
  Kotobase token environment; they are never accepted as command arguments."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.repository-measurement :as measurement]
            [cloud.itonami.app.repository-qualification :as qualification]
            [cloud.itonami.app.repository-storage :as repository]))

(defn- required-env [name]
  (or (not-empty (System/getenv name))
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
        endpoint (or (not-empty (System/getenv "CLOUD_ITONAMI_KOTOBASE_ENDPOINT"))
                     "https://kotobase.net")
        token (required-env "CLOUD_ITONAMI_KOTOBASE_TOKEN")
        workspace-root (or (not-empty
                            (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT"))
                           (.getPath (io/file (config/data-dir) "workspace")))
        requested-key-epoch (some-> (not-empty
                                     (System/getenv
                                      "CLOUD_ITONAMI_KEY_EPOCH"))
                                    Long/parseLong)
        kagi-context (load-kagi-context
                      {:vault-home (not-empty (System/getenv "KAGI_HOME"))
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
        workspace-root (or (not-empty
                            (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT"))
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
        _ (qualification/validate-production-attestation! evidence)
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
               (repository/plaintext-markers (:state workspace)))]
    (qualification/require-qualified!
     (assoc evidence
            :profiles-report profiles
            :datalad-audit audit
            :usage-reconciliation usage))))

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
          "usage" (usage!)
          "qualify" (qualify! (or (first arguments)
                                   "config/repository-production-evidence.edn"))
          "audit" (audit! arguments)
          "profiles" (audit-profiles! arguments)
          (throw (ex-info
                  "usage: repository migrate [legacy.edn] | publish | hydrate | rotate-vmk | measure [iterations] | usage | qualify [evidence.edn] | audit [markers...] | profiles [repo...]"
                  {:type :repository-storage/invalid-command})))]
    ;; Result summaries are explicitly stripped of plaintext, VMKs, blocks and
    ;; tokens before they reach an operator terminal.
    (prn (select-keys result
                      [:published? :head/revision :basis/cid :qualified?
                       :key/epoch :measurement :violations :failed :sealed/bytes
                       :physical/bytes :reconciled? :heads :blocks :state-file
                       :basis-file :base-file]))))
