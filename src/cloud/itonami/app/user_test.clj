(ns cloud.itonami.app.user-test
  "Business-scoped host adapter for kotoba-lang/user-test.

  The shared library owns portable study/run/evaluation meaning. This adapter
  owns tenancy, local persistence, business joins and issue-shaped events. Raw
  participant evidence is deliberately not persisted here: callers place it in
  an encrypted/private evidence store and pass opaque refs plus hashes."
  (:require [clojure.string :as str]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.store :as store]
            [kotoba.kgraph :as kgraph]
            [kotoba.user-test :as user-test])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def schema "cloud.itonami.app.user-test.v1")

(defn enabled? [configuration]
  (true? (get-in configuration [:user-test :enabled?])))

(defn execution-enabled? [configuration]
  (and (enabled? configuration)
       (true? (get-in configuration [:user-test :execution-enabled?]))
       (true? (get-in configuration [:agent-control :browser :enabled?]))))

(defn- refuse [type detail]
  (throw (ex-info detail {:type type})))

(defn- require-business! [session business-id]
  (or (business/business session business-id)
      (refuse :user-test/business-not-found
              "この organization に該当する business がありません")))

(defn- studies-path [organization-id] [:user-tests organization-id :studies])
(defn- runs-path [organization-id] [:user-tests organization-id :runs])

(defn studies [session business-id]
  (require-business! session business-id)
  (->> (vals (get-in (store/snapshot)
                     (studies-path (:organization-id session)) {}))
       (filter #(= business-id (:user-test/business %)))
       (sort-by :user-test/created-at)
       vec))

(defn study [session study-id]
  (let [record (get-in (store/snapshot)
                       (conj (studies-path (:organization-id session)) study-id))]
    (when (and record
               (business/business session (:user-test/business record)))
      record)))

(def ^:private study-fields
  [:user-test/id :user-test/persona :user-test/revision :user-test/tasks
   :user-test/evidence :user-test/privacy])

(defn create-study!
  "Persist only allowlisted fields. Project is forced to the business id rather
  than trusted from input, preventing a study from escaping its tenant join."
  [configuration session business-id proposed]
  (when-not (enabled? configuration)
    (refuse :user-test/disabled "user-test capability is disabled"))
  (require-business! session business-id)
  (let [organization-id (:organization-id session)
        id (or (:user-test/id proposed) (store/new-id "study"))
        record (-> (select-keys proposed study-fields)
                   (assoc :schema schema
                          :user-test/id id
                          :user-test/project business-id
                          :user-test/business business-id
                          :organization-id organization-id
                          :user-test/created-by (:user-id session)
                          :user-test/created-at (store/now)))]
    (when (study session id)
      (refuse :user-test/study-exists "study id は既に使われています"))
    (when-let [errors (seq (user-test/study-errors record))]
      (throw (ex-info "user-test study が不正です"
                      {:type :user-test/invalid-study :errors errors})))
    (store/transact!
     (fn [state]
       (-> state
           (assoc-in (conj (studies-path organization-id) id) record)
           (update :datoms kgraph/assert-entity
                   {:user-test-study/id id
                    :user-test-study/business business-id
                    :user-test-study/organization organization-id
                    :user-test-study/revision (:user-test/revision record)}))))
    record))

(defn runs [session study-id]
  (when-let [study-record (study session study-id)]
    (->> (vals (get-in (store/snapshot)
                       (runs-path (:organization-id session)) {}))
         (filter #(= (:user-test/id study-record) (:run/study %)))
         (sort-by :run/recorded-at)
         vec)))

(defn record-run!
  "Score a run, then persist only its public projection and deterministic
  evaluation. Raw paths, URLs, transcripts and event content are removed before
  the store transaction."
  [configuration session study-id proposed-run]
  (when-not (enabled? configuration)
    (refuse :user-test/disabled "user-test capability is disabled"))
  (let [study-record (or (study session study-id)
                         (refuse :user-test/study-not-found
                                 "この organization に該当する study がありません"))
        run-id (or (:run/id proposed-run) (store/new-id "user-test-run"))
        run (assoc proposed-run :run/id run-id :run/study study-id)
        evaluation (user-test/evaluate study-record run)
        findings (user-test/findings evaluation)
        recorded (-> (user-test/public-projection run)
                     (assoc :schema schema
                            :organization-id (:organization-id session)
                            :run/business (:user-test/business study-record)
                            :run/recorded-by (:user-id session)
                            :run/recorded-at (store/now)
                            :run/evaluation evaluation
                            :run/findings findings))
        organization-id (:organization-id session)
        max-runs (or (get-in configuration [:user-test :max-runs-per-study]) 100)]
    (store/transact!
     (fn [state]
       (let [path (runs-path organization-id)
             current (assoc (get-in state path {}) run-id recorded)
             this-study (->> (vals current)
                             (filter #(= study-id (:run/study %)))
                             (sort-by :run/recorded-at))
             evict (set (map :run/id (drop-last max-runs this-study)))
             kept (apply dissoc current evict)]
         (-> state
             (assoc-in path kept)
             (update :datoms kgraph/assert-entity
                     {:user-test-run/id run-id
                      :user-test-run/study study-id
                      :user-test-run/business (:run/business recorded)
                      :user-test-run/participant-kind (:run/participant-kind recorded)
                      :user-test-run/pass? (:evaluation/pass? evaluation)})
             (update :events #(vec (take-last 200
                                               (conj (or % [])
                                                     {:type :user-test/evaluated
                                                      :at (store/now)
                                                      :business (:run/business recorded)
                                                      :study study-id
                                                      :run run-id
                                                      :pass? (:evaluation/pass? evaluation)
                                                      :findings (count findings)}))))))))
    recorded))

(defn business-summary [session business-id]
  (require-business! session business-id)
  (let [study-records (studies session business-id)
        evaluations (mapcat (fn [s]
                              (map #(get-in % [:run/evaluation])
                                   (runs session (:user-test/id s))))
                            study-records)]
    (assoc (user-test/project-summary business-id evaluations)
           :user-test/studies (count study-records))))

(defn next-plan
  "Oldest study with no passing run. The caller chooses a participant kind."
  [session business-id]
  (some (fn [study-record]
          (let [rs (runs session (:user-test/id study-record))]
            (when-not (some #(get-in % [:run/evaluation :evaluation/pass?]) rs)
              (assoc (user-test/execution-plan study-record)
                     :plan/execution-state :awaiting-participant))))
        (studies session business-id)))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- observed-success?
  "Evaluate declared success markers against an accessibility-tree snapshot.
  No marker means unobservable, never success by agent assertion."
  [snapshot task]
  (let [observations (:task/observations task)
        text (str snapshot)]
    (and (map? observations)
         (every?
          (fn [marker]
            (let [{:keys [kind value]} (get observations marker)]
              (case kind
                :text-present (str/includes? text (str value))
                :text-absent (not (str/includes? text (str value)))
                false)))
          (:task/success task)))))

(defn- synthetic-goal [plan]
  (str "Act as a first-time synthetic participant identified only by "
       (:plan/persona plan) ". Do not inspect source code or use developer "
       "knowledge. Use the isolated browser only. Complete these tasks in order: "
       (pr-str (:plan/tasks plan))
       ". Start from each declared start URL, inspect before acting, never enter "
       "a password, token, MFA value or real personal data, and finish after "
       "checking the visible result."))

(defn dispatch-synthetic!
  "Dispatch the next unresolved study into Agent Control and reconcile a
  terminal run from a fresh accessibility snapshot. The agent's own success
  prose is ignored; only declared observation predicates decide task outcome."
  [configuration session business-id]
  (when-not (execution-enabled? configuration)
    (refuse :user-test/execution-disabled
            "user-test と isolated browser の実行gateを有効にしてください"))
  (when-let [plan (next-plan session business-id)]
    (let [dispatch-id (store/new-id "user-test-dispatch")
          study-record (study session (:plan/study plan))
          created-at (store/now)
          agent-run
          (agent-control/create-run!
           configuration
           {:goal (synthetic-goal plan)
            :tool-profile :user-test
            :auto-browser? true}
           {:actor/id "synthetic-participant"
            :actor/role :user-test/participant
            :actor/business business-id
            :actor/study (:plan/study plan)})
          terminal? (contains? #{:succeeded :failed :rejected :cancelled}
                               (:agent.run/status agent-run))
          snapshot (when terminal?
                     (try (agent-control/browser-snapshot! configuration)
                          (catch Exception error
                            (str "snapshot unavailable: " (.getMessage error)))))
          elapsed (max 0 (- (long (:agent.run/updated-at agent-run))
                            (long (:agent.run/created-at agent-run))))
          outcomes
          (when terminal?
            (mapv (fn [task]
                    {:task/id (:task/id task)
                     :outcome/succeeded?
                     (and (= :succeeded (:agent.run/status agent-run))
                          (observed-success? snapshot task))
                     :outcome/elapsed-ms elapsed
                     :outcome/actions (or (:agent/tool-count agent-run) 0)
                     :outcome/dead-ends 0
                     :outcome/recoveries 0
                     :outcome/a11y-violations 0})
                  (:user-test/tasks study-record)))
          recorded
          (when terminal?
            (record-run!
             configuration session (:plan/study plan)
             {:run/id (str "user-test-" (:agent.run/id agent-run))
              :run/revision (:user-test/revision study-record)
              :run/participant-kind :synthetic
              :run/participant (str "agent-control://" (:agent.run/id agent-run))
              :run/outcomes outcomes
              :run/evidence {:accessibility-tree
                             {:evidence/sha256 (sha256 snapshot)}}}))
          dispatch {:dispatch/id dispatch-id
                    :dispatch/business business-id
                    :dispatch/study (:plan/study plan)
                    :dispatch/agent-run (:agent.run/id agent-run)
                    :dispatch/status (:agent.run/status agent-run)
                    :dispatch/created-at created-at
                    :dispatch/reconciled-run (:run/id recorded)}]
      (store/transact! assoc-in
                       [:user-tests (:organization-id session) :dispatches dispatch-id]
                       dispatch)
      dispatch)))

(defn dispatchable-sessions
  "Internal loop inputs derived only from already-authorized local studies."
  []
  (for [[organization-id partition] (:user-tests (store/snapshot))
        study-record (vals (:studies partition))]
    [{:user-id (:user-test/created-by study-record)
      :organization-id organization-id}
     (:user-test/business study-record)]))

(defn query-runs [query]
  (kgraph/query (:datoms (store/snapshot)) query))

(defn writes-only-local-projections? []
  [[:user-tests] [:datoms] [:events]])
