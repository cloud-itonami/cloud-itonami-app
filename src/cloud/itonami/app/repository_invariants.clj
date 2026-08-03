(ns cloud.itonami.app.repository-invariants
  "Executable, source-local invariants for ADR-0013 qualification.

  These checks do not claim production throughput or remote availability. They
  prove properties of the exact running implementation, so an operator cannot
  replace them with `true` in an evidence file."
  (:require [cloud.itonami.app.local-query :as local-query]
            [cloud.itonami.app.repository-storage :as repository]
            [datascript.core :as datascript]
            [kagi.crypto :as crypto]
            [kotobase.local :as local]))

(def ^:private owner "qualification-invariant-owner")

(def ^:private base-state
  {:schema "cloud.itonami.app.state.v1"
   :datoms [["m1" :message/role "user"]
            ["m1" :message/content "hello"]]
   :settings {:order [1 2]}})

(defn- fixture []
  (let [provider (crypto/jvm-provider)
        signing (crypto/sign-keypair provider)]
    {:provider provider
     :vmk (crypto/rand-bytes provider 32)
     :signing-secret (:secret signing)
     :signing-public (:public signing)
     :transport (repository/memory-block-transport)
     :head-store (local/local-store)
     :owner owner}))

(defn- prepare [context candidate previous-head]
  (repository/prepare-publication
   (merge context
          {:key-epoch 1 :max-chunk-bytes 1024
           :base base-state :candidate candidate :current base-state
           :basis-cid (repository/semantic-cid base-state)
           :previous-head previous-head})))

(defn- publish [context prepared revision]
  (repository/publish-prepared!
   (select-keys (assoc context :expected-revision revision)
                [:transport :head-store :provider :signing-public :owner
                 :expected-revision])
   prepared))

(defn- mutation-converges? []
  (let [added ["m2" :message/content "same"]
        candidate (update base-state :datoms conj added)
        direct (repository/reconcile
                {:base base-state :candidate candidate :current base-state
                 :basis-cid (repository/semantic-cid base-state)})
        transaction (repository/apply-datom-transaction
                     {:base base-state :current base-state
                      :basis-cid (repository/semantic-cid base-state)
                      :tx-data [(into [:db/add] added)]})]
    (and (= (:state direct) (:state transaction))
         (= (:semantic/cid direct) (:semantic/cid transaction)))))

(defn- conflict-surfaces? []
  (try
    (repository/reconcile
     {:base base-state
      :candidate (assoc-in base-state [:settings :order] [2])
      :current (assoc-in base-state [:settings :order] [3])
      :basis-cid (repository/semantic-cid base-state)})
    false
    (catch clojure.lang.ExceptionInfo error
      (= :repository-storage/conflict (:type (ex-data error))))))

(defn- transport-before-head? []
  (let [{:keys [transport head-store] :as context} (fixture)
        prepared (prepare context base-state nil)]
    (repository/fail-memory-publish! transport true)
    (try
      (publish context prepared 0)
      false
      (catch clojure.lang.ExceptionInfo _
        (nil? (:head (repository/head-snapshot head-store owner)))))))

(defn- vmk-rewrap-preserves-payload? []
  (let [{:keys [provider vmk signing-secret signing-public transport]
         :as context} (fixture)
        first-result (publish context (prepare context base-state nil) 0)
        old-head (:head first-result)
        old-hydrated (repository/hydrate-head context old-head)
        old-cids (mapv :sealed/cid (get-in old-hydrated [:manifest :chunks]))
        new-vmk (crypto/rand-bytes provider 32)
        rotation (repository/prepare-vmk-rotation
                  {:transport transport :provider provider
                   :vmk vmk :new-vmk new-vmk
                   :signing-secret signing-secret
                   :signing-public signing-public
                   :owner owner :head old-head :key-epoch 1})
        rotated (publish context rotation 1)
        new-hydrated (repository/hydrate-head
                      (assoc context :vmk new-vmk :key-epoch 2)
                      (:head rotated))
        new-cids (mapv :sealed/cid (get-in new-hydrated [:manifest :chunks]))]
    (and (= old-cids new-cids)
         (= (:basis/cid old-hydrated) (:basis/cid new-hydrated)))))

(defn- query-backends-agree? []
  (let [query '[:find ?content
                :where [?e :message/role "user"]
                       [?e :message/content ?content]]
        entity-ids (zipmap (distinct (map first (:datoms base-state)))
                           (range 1 1000))
        tx-data (mapv (fn [[entity attribute value]]
                        [:db/add (entity-ids entity) attribute value])
                      (:datoms base-state))
        datascript-db (datascript/db-with (datascript/empty-db) tx-data)]
    (= (datascript/q query datascript-db)
       (set (local-query/query-state base-state (pr-str query))))))

(defn verify
  "Run all code invariants and return only boolean qualification facts. Any
  unexpected exception fails that invariant closed rather than aborting before
  the twelve-gate report can identify it."
  []
  (let [check (fn [f] (try (true? (f)) (catch Throwable _ false)))
        result {:semantic-convergence? (check mutation-converges?)
                :conflict-surfaced? (check conflict-surfaces?)
                :vmk-rotation-payload-stable?
                (check vmk-rewrap-preserves-payload?)
                :transport-failure-head-stable? (check transport-before-head?)
                :query-backend-parity? (check query-backends-agree?)}]
    (assoc result :qualified? (every? true? (vals result)))))
