(ns cloud.itonami.app.health
  "Whether this request is the process-liveness probe.

  The judgement is in `health_core.kotoba` and RUNS from there. This
  namespace is the host half: stringify, call the shipped artifact, decide
  nothing. The JSON body `server.clj` returns on a yes is not a decision
  and does not live here."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(defn health-route? [method path]
  (oracle/call :health 'health-route? [(str method) (str path)]))
