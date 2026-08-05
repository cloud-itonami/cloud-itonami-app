(ns cloud.itonami.app.github-projects-sandbox
  "Destructive-but-restoring live probe for the GitHub Projects adapter.

  The probe changes one sandbox item's Status, verifies the resulting basis,
  restores the original option, and verifies restoration. It is intentionally
  unavailable without an explicit environment confirmation."
  (:require [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.github-projects-writeback :as github]
            [cloud.itonami.app.work-runtime :as runtime]))

(def schema "cloud.itonami.app.github-projects-sandbox-receipt.v1")

(defn- required-env [name]
  (let [value (some-> (System/getenv name) str/trim not-empty)]
    (when-not value
      (throw (ex-info (str name " is required")
                      {:type :github-projects/sandbox-configuration-required
                       :environment name})))
    value))

(defn environment-source []
  {:kind :github-projects-v2
   :project-id (required-env "CLOUD_ITONAMI_GITHUB_SANDBOX_PROJECT_ID")
   :item-id (required-env "CLOUD_ITONAMI_GITHUB_SANDBOX_ITEM_ID")
   :field-id (required-env "CLOUD_ITONAMI_GITHUB_SANDBOX_FIELD_ID")
   :field-name (or (some-> (System/getenv
                            "CLOUD_ITONAMI_GITHUB_SANDBOX_FIELD_NAME")
                           str/trim not-empty)
                   "Status")
   :sandbox-option-id
   (required-env "CLOUD_ITONAMI_GITHUB_SANDBOX_OPTION_ID")
   :write-capability
   (keyword (required-env "CLOUD_ITONAMI_GITHUB_SANDBOX_WRITE_CAPABILITY"))})

(defn ensure-enabled! [configuration source]
  (when-not (= "1" (System/getenv "CLOUD_ITONAMI_GITHUB_SANDBOX_CONFIRM"))
    (throw (ex-info "set CLOUD_ITONAMI_GITHUB_SANDBOX_CONFIRM=1 for this restoring mutation"
                    {:type :github-projects/sandbox-confirmation-required})))
  (let [cfg (:work-governance configuration)]
    (when-not (and (:enabled? cfg) (:github-writeback-enabled? cfg))
      (throw (ex-info "governance and GitHub write-back must both be enabled"
                      {:type :github-projects/writeback-disabled})))
    (when-not (contains? (set (:github-write-capabilities cfg))
                         (:write-capability source))
      (throw (ex-info "sandbox write capability is not allowlisted"
                      {:type :work-runtime/github-capability-required
                       :capability (:write-capability source)})))
    (when-not (contains? (set (:github-sandbox-project-ids cfg))
                         (:project-id source))
      (throw (ex-info "project is not in the live sandbox allowlist"
                      {:type :github-projects/sandbox-project-required
                       :project-id (:project-id source)})))))

(defn probe!
  "Mutate to the configured sandbox option and restore the original option.
  Returns a content-addressed receipt containing no OAuth credential."
  ([configuration source] (probe! configuration source github/github-transport))
  ([configuration source transport]
   (ensure-enabled! configuration source)
   (let [read (github/request transport github/item-basis-query
                              {:item (:item-id source)
                               :fieldName (:field-name source)})
         original (github/current-basis read)
         target (:sandbox-option-id source)]
     (when-not (= (select-keys source [:project-id :item-id :field-id])
                  (select-keys original [:project-id :item-id :field-id]))
       (throw (ex-info "sandbox IDs do not match the connected GitHub item"
                       {:type :github-projects/sandbox-target-mismatch
                        :configured (select-keys source
                                                 [:project-id :item-id :field-id])
                        :observed (select-keys original
                                               [:project-id :item-id :field-id])})))
     (when (= target (:option-id original))
       (throw (ex-info "sandbox option must differ from the current option"
                       {:type :github-projects/sandbox-noop-option})))
     (let [target-source (assoc source :basis original
                                :target-option-ids {:sandbox target})
           target-basis (github/write-status! configuration target-source
                                              :sandbox transport)]
       (try
         (let [restore-source (assoc source :basis target-basis
                                     :target-option-ids
                                     {:restore (:option-id original)})
               restored (github/write-status! configuration restore-source
                                              :restore transport)
               receipt-base
               {:schema schema
                :status :verified-and-restored
                :project-id (:project-id source)
                :item-id (:item-id source)
                :field-id (:field-id source)
                :original-option-id (:option-id original)
                :sandbox-option-id target
                :target-basis target-basis
                :restored-basis restored
                :verified-at (System/currentTimeMillis)}]
           (assoc receipt-base :receipt-hash (runtime/payload-hash receipt-base)))
         (catch Exception error
           (throw (ex-info
                   "sandbox mutation succeeded but verified restoration failed; inspect the item"
                   {:type :github-projects/sandbox-restoration-required
                    :project-id (:project-id source)
                    :item-id (:item-id source)
                    :original-basis original
                    :target-basis target-basis
                    :cause-type (:type (ex-data error))}
                   error))))))))

(defn -main [& _]
  (let [receipt (probe! (config/load-config) (environment-source))]
    (println (pr-str receipt))))
