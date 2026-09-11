(ns cloud.itonami.app.local-query
  "Local-only Datomic-shaped query projection for the Agent.

  The persisted application state remains EDN. `:datoms` is its explicit
  query projection; this namespace materializes a basis-aware local EAV view
  from that projection and never owns durability or transport. Stable schemas
  apply datom deltas; cardinality/schema changes rebuild from authoritative
  state. A DataScript or
  Datomic adapter can replace `langchain.db/api` at the same query boundary."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [langchain.db :as d]))

(def ^:private entity-id :cloud-itonami.entity/id)
(def ^:private max-query-characters 8192)
(def ^:private max-result-rows 200)
(defonce ^:private materialized-view (atom nil))

(defn- cardinality-schema [datoms]
  (let [counts (frequencies (map (juxt first second) datoms))]
    (into {entity-id {:db/unique :db.unique/identity}}
          (keep (fn [[[ _ attribute] count]]
                  (when (> count 1)
                    [attribute {:db/cardinality :db.cardinality/many}])))
          counts)))

(defn- entity-tx [schema datoms]
  (mapv
   (fn [[entity rows]]
     (reduce
      (fn [tx [_ attribute value]]
        (if (= :db.cardinality/many
               (get-in schema [attribute :db/cardinality]))
          (update tx attribute (fnil conj []) value)
          (assoc tx attribute value)))
      {entity-id (pr-str entity)}
      rows))
   (group-by first datoms)))

(defn connection
  "Build an uncached local view from one application-state value."
  [state]
  (let [datoms (vec (:datoms state))
        schema (cardinality-schema datoms)
        conn (d/create-conn schema)]
    (when (seq datoms)
      (d/transact! conn (entity-tx schema datoms)))
    conn))

(defn clear-materialized-view!
  "Drop the process-local query cache. Persistence and source state are not
  affected; the next query rebuilds from its supplied basis."
  []
  (reset! materialized-view nil))

(defn- existing-entity-ref [entity]
  [entity-id (pr-str entity)])

(defn- incremental-tx
  [schema old-datoms new-datoms]
  (let [old-entities (set (map first old-datoms))
        new-entities (set (map first new-datoms))
        removed-entities (set/difference old-entities new-entities)
        added-entities (set/difference new-entities old-entities)
        retained-old (remove #(removed-entities (first %)) old-datoms)
        retained-new (remove #(added-entities (first %)) new-datoms)
        retractions (set/difference (set retained-old) (set retained-new))
        assertions (set/difference (set retained-new) (set retained-old))
        removed (mapv (fn [entity]
                        [:db/retractEntity (existing-entity-ref entity)])
                      removed-entities)
        retracts (mapv (fn [[entity attribute value]]
                         [:db/retract (existing-entity-ref entity)
                          attribute value])
                       retractions)
        adds (mapv (fn [[entity attribute value]]
                     [:db/add (existing-entity-ref entity) attribute value])
                   assertions)
        new-maps (mapv
                  (fn [[entity rows]]
                    (reduce
                     (fn [tx [_ attribute value]]
                       (if (= :db.cardinality/many
                              (get-in schema [attribute :db/cardinality]))
                         (update tx attribute (fnil conj []) value)
                         (assoc tx attribute value)))
                     {entity-id (pr-str entity)} rows))
                  (group-by first (filter #(added-entities (first %))
                                          new-datoms)))]
    (into [] cat [removed retracts new-maps adds])))

(defn materialized-connection
  "Return the process-local view for STATE. Same-basis queries reuse it;
  changed datoms transact as a delta when the inferred schema is stable, and a
  cardinality/schema change rebuilds from the authoritative state."
  [state]
  (let [datoms (vec (:datoms state))
        datom-set (set datoms)
        schema (cardinality-schema datoms)]
    (locking materialized-view
      (let [{old-datoms :datoms old-schema :schema conn :conn} @materialized-view]
        (cond
          (= old-datoms datom-set) conn

          (and conn (= old-schema schema))
          (do
            (when-let [tx-data (seq (incremental-tx schema old-datoms datom-set))]
              (d/transact! conn tx-data))
            (reset! materialized-view
                    {:datoms datom-set :schema schema :conn conn})
            conn)

          :else
          (let [conn (connection state)]
            (reset! materialized-view
                    {:datoms datom-set :schema schema :conn conn})
            conn))))))

(defn parse-query [query-edn]
  (when-not (and (string? query-edn)
                 (<= (count query-edn) max-query-characters))
    (throw (ex-info "local Datalog query is missing or too large"
                    {:type :local-query/invalid-query})))
  (let [query (edn/read-string {:readers {} :default (fn [tag _]
                                                        (throw (ex-info
                                                                "tagged query literal denied"
                                                                {:type :local-query/tagged-literal
                                                                 :tag tag})))}
                               query-edn)]
    (when-not (and (vector? query) (some #{:find} query) (some #{:where} query))
      (throw (ex-info "local Datalog query must contain :find and :where"
                      {:type :local-query/invalid-query})))
    query))

(defn query-state
  "Execute QUERY-EDN against STATE locally. Results are capped before they
  enter an Agent context; ask a narrower query instead of dumping the store."
  [state query-edn]
  (let [conn (materialized-connection state)
        result (d/q (parse-query query-edn) (d/db conn))]
    (cond
      (set? result) (vec (take max-result-rows result))
      (sequential? result) (vec (take max-result-rows result))
      :else result)))
