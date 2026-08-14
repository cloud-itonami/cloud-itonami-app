(ns cloud.itonami.app.did-web
  "Whether this request is the public did:web document.

  The judgement is in `did_web_core.kotoba` and RUNS from there. This
  namespace is the host half: stringify, call the shipped artifact, decide
  nothing. The document `credential/did-web-document` returns on a yes, and
  the Host→tenant resolution, are not decisions and do not live here."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(defn did-web-route? [method path]
  (oracle/call :did-web 'did-web-route? [(str method) (str path)]))
