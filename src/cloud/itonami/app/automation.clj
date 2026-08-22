(ns cloud.itonami.app.automation
  "User-defined Workers and Agents plus branch-isolated Project dispatch."
  (:require [clojure.string :as str]
            [cloud.itonami.app.project-repository :as projects]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.worker :as worker]))

(def schema "cloud.itonami.app.automation-actors.v1")

(defn- slug [value]
  (-> (str value) str/trim str/lower-case
      (str/replace #"[^a-z0-9._-]+" "-")
      (str/replace #"(^[-.]+|[-.]+$)" "")
      not-empty))

(defn provider-actors [config]
  (->> (:providers config)
       (filter :enabled?)
       (mapv (fn [provider]
               {:id (:id provider) :name (or (:name provider) (:id provider))
                :kind "agent" :provider-id (:id provider)
                :model (:model provider) :auto-run? true
                :access (if (= :cli (:kind provider))
                          :workspace-write :read-only)
                :built-in? true}))))

(defn actors [config]
  (let [state (store/snapshot)
        configured (concat
                    (map #(merge {:kind "agent" :auto-run? true
                                  :provider-id (get-in config [:routing :default-provider])}
                                 %) (:agents state))
                    (map #(merge {:kind "worker" :auto-run? true} %)
                         (:worker-profiles state)))]
    {:schema schema
     :providers (->> (:providers config)
                     (filter :enabled?)
                     (mapv #(select-keys % [:id :name :kind :model :local?])))
     :items (->> (concat configured (provider-actors config))
                 (reduce (fn [result actor]
                           (if (some #(= (:id %) (:id actor)) result)
                             result (conj result actor))) [])
                 vec)}))

(defn resolve-actor [config actor-id]
  (some #(when (= actor-id (:id %)) %) (:items (actors config))))

(defn create-actor! [config request]
  (let [kind (if (= "worker" (str (:kind request))) "worker" "agent")
        name (not-empty (str/trim (str (:name request))))
        id (or (slug (:id request)) (some-> name slug))
        provider-id (not-empty (str/trim (str (:provider-id request))))
        provider (some #(when (= provider-id (:id %)) %) (:providers config))
        requested-write? (= "workspace-write"
                            (some-> (:access request) clojure.core/name))]
    (when-not name
      (throw (ex-info "Worker / Agent の名前を入力してください。"
                      {:type :automation/invalid-actor})))
    (when-not provider
      (throw (ex-info "利用可能な provider を選択してください。"
                      {:type :automation/unknown-provider})))
    (when (and requested-write? (not= :cli (:kind provider)))
      (throw (ex-info "Repository write には Codex CLI / Claude CLI を選択してください。"
                      {:type :automation/write-agent-required})))
    (let [actor {:id id :name name :kind kind :provider-id provider-id
                 :model (or (not-empty (str/trim (str (:model request))))
                            (:model provider))
                 :system-prompt (or (not-empty (str/trim (str (:system-prompt request))))
                                    (if (= kind "worker")
                                      "Complete the assigned background task and report evidence."
                                      "Work on the assigned issue safely and report a concrete result."))
                 :auto-run? (not= false (:auto-run? request))
                 :access (if requested-write? :workspace-write :read-only)
                 :created-at (store/now)}
          target (if (= kind "worker") :worker-profiles :agents)]
      (store/transact!
       update target
       (fn [items]
         (conj (vec (remove #(= id (:id %)) (or items []))) actor)))
      (store/transact! update :events
                       #(vec (take-last 200
                                        (conj (or % [])
                                              {:type :automation/actor-added
                                               :at (store/now) :actor-id id
                                               :kind kind :provider-id provider-id}))))
      actor)))

(defn- issue-prompt [board issue write?]
  (let [repository-by-id (into {} (map (juxt :id identity) (:repositories board)))
        repositories (keep repository-by-id (:repository-ids issue))]
    (str "Project Issue #" (:number issue) ": " (:title issue) "\n\n"
         (:description issue)
         "\n\nProject: " (get-in board [:project :title])
         "\nRepositories (this issue may span all of them):\n"
         (str/join "\n" (map #(str "- " (:name %) ": " (:location %)) repositories))
         "\n\nProduce a concrete result, evidence, and remaining blockers. "
         (if write?
           (str "Implement and test the requested change in the current isolated worktree. "
                "The application already created the session branch. Do not switch branches, "
                "run git add, or run git commit; Cloud Itonami stages and commits after success.")
           "Do not modify repositories; propose changes for review."))))

(defn dispatch-ready!
  "Enqueue every newly unblocked assigned Issue exactly once."
  [config scope]
  (let [board (projects/project-board scope)
        issues (projects/dispatchable-issues scope)]
    (vec (keep
     (fn [issue]
       (when-let [actor (resolve-actor config (:agent-id issue))]
         (when (:auto-run? actor)
           (let [write? (= :workspace-write (:access actor))
                 repository-by-id (into {} (map (juxt :id identity)
                                                (:repositories board)))
                 repositories (mapv repository-by-id (:repository-ids issue))
                 run (worker/enqueue!
                      config
                      {:title (str (get-in board [:project :title]) " #" (:number issue))
                       :prompt (str (:system-prompt actor) "\n\n"
                                    (issue-prompt board issue write?))
                       :model (:model actor)
                       :provider (:provider-id actor)
                       :agent (:id actor)
                       :access (:access actor)
                       :repositories repositories
                       :commit-message (str "[Cloud Itonami #" (:number issue) "] "
                                            (:title issue))
                       :session-scope (assoc scope :conversation-id
                                             (str "issue-" (:id issue)))
                       :context {:kind :project/issue
                                 :organization-id (:organization-id scope)
                                 :project-id (:project-id scope)
                                 :issue-id (:id issue)}})]
             (projects/record-issue-run! scope (:id issue) run)
             {:issue-id (:id issue) :run run}))))
     issues))))
