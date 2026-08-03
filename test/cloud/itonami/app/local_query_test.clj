(ns cloud.itonami.app.local-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.local-query :as local-query]
            [datascript.core :as datascript]))

(def state
  {:datoms [["m1" :message/session "s1"]
            ["m1" :message/role "user"]
            ["m1" :message/content "hello"]
            ["m2" :message/session "s1"]
            ["m2" :message/role "assistant"]
            ["m2" :message/content "hi"]]})

(deftest datomic-shaped-join-runs-locally
  (is (= #{["hello"]}
         (set (local-query/query-state
               state
               "[:find ?content :where [?e :message/role \"user\"] [?e :message/content ?content]]")))))

(deftest query-view-is-rebuilt-from-the-given-basis
  (let [query "[:find ?content :where [_ :message/content ?content]]"]
    (is (= 2 (count (local-query/query-state state query))))
    (is (= 3 (count (local-query/query-state
                     (update state :datoms conj ["m3" :message/content "fresh"])
                     query))))))

(deftest materialized-view-reuses-a-basis-and-applies-safe-deltas
  (local-query/clear-materialized-view!)
  (let [first-conn (local-query/materialized-connection state)
        same-conn (local-query/materialized-connection state)
        changed (update state :datoms conj
                        ["m3" :message/content "incremental"])
        changed-conn (local-query/materialized-connection changed)]
    (is (identical? first-conn same-conn))
    (is (identical? first-conn changed-conn))
    (is (= #{["incremental"]}
           (set (local-query/query-state
                 changed
                 "[:find ?content :where [_ :message/content ?content] [(= ?content \"incremental\")]]"))))))

(deftest malformed-and-tagged-input-is-refused
  (doseq [query ["{:find [?e]}" "#foo/bar [:find ?e :where [?e :x _]]"]]
    (testing query
      (is (thrown? clojure.lang.ExceptionInfo
                   (local-query/query-state state query))))))

(deftest persisted-common-subset-query-has-datascript-parity
  (let [query '[:find ?content
                :where [?e :message/role "user"]
                       [?e :message/content ?content]]
        entity-ids (zipmap (distinct (map first (:datoms state))) (range 1 1000))
        tx-data (mapv (fn [[entity attribute value]]
                        [:db/add (entity-ids entity) attribute value])
                      (:datoms state))
        datascript-db (datascript/db-with (datascript/empty-db) tx-data)
        local-result (set (local-query/query-state state (pr-str query)))
        datascript-result (datascript/q query datascript-db)]
    (is (= datascript-result local-result))))
