;; agent_heartbeat.cljc — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; Tick-triggered every 5 minutes (mesh/itonami.app.edn). Appends one
;; resident-liveness quad per tick to the hosting node's Datom log, which is
;; what lets "is the itonami cloud-agent alive" be answered by the lattice
;; rather than by whether a particular laptop is awake.
;;
;; Quad shape (kqe-assert! graph subject predicate object): subject "itonami-agent",
;; predicate "heartbeat", object the tick context — so `(kqe-query "heartbeat")`
;; from any component on the node lists the beats. `run` writes one at
;; placement too, which is a fact (the component was placed), not noise.
;; Portable kotoba-clj subset only (no regex, no interop, no reader
;; conditionals).
;;
;; host-imports used:  kqe-assert!  → kotoba:kais/kqe
(ns agent-heartbeat)

(defn run [_ctx]
  (kqe-assert! "g" "itonami-agent" "heartbeat" "placed"))

;; cron trigger: the tick context carries the node's notion of now; the datom
;; log's own transaction time is the timestamp of record.
(defn on-tick [ctx]
  (kqe-assert! "g" "itonami-agent" "heartbeat" (str "alive " ctx)))
