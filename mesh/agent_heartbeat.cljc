;; agent_heartbeat.cljc — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; Tick-triggered every 5 minutes (mesh/itonami.app.edn). Appends one
;; resident-liveness datom per tick to the hosting node's Datom log, which is
;; what lets "is the itonami cloud-agent alive" be answered by the lattice
;; rather than by whether a particular laptop is awake.
;;
;; It asserts exactly one fact and reads nothing: a heartbeat that depended on
;; a query would go quiet for the wrong reason. Portable kotoba-clj subset only
;; (no regex, no interop, no reader conditionals).
;;
;; host-imports used:  kqe-assert!  → kotoba:kais/kqe
(ns agent-heartbeat)

(defn run [_ctx]
  (kqe-assert! "g" "itonami-agent" "heartbeat" "alive"))

;; cron trigger: the tick context carries the node's notion of now; the datom
;; log's own transaction time is the timestamp of record.
(defn on-tick [ctx]
  (kqe-assert! "g" "itonami-agent" "heartbeat" (str "alive " ctx)))
