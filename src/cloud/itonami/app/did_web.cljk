(ns cloud.itonami.app.did-web
  "Whether this request is one of the three public DID discovery documents.

  The judgement is in `did_web_core.kotoba` and RUNS from there. This
  namespace is the host half: stringify, call the shipped artifact, decide
  nothing. The document `credential/did-web-document` returns on a yes, and
  the Host→tenant resolution, are not decisions and do not live here."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(defn did-web-route? [method path]
  (oracle/call :did-web 'did-web-route? [(str method) (str path)]))

(defn did-log-route?
  "`GET /.well-known/did.jsonl` -- the did:webvh log (ADR-0068)."
  [method path]
  (oracle/call :did-web 'did-log-route? [(str method) (str path)]))

(defn did-witness-route?
  "`GET /.well-known/did-witness.json` -- the witness proofs whose count the
  resolver compares against the threshold. Served beside the log because a log
  without them does not resolve, which is the point."
  [method path]
  (oracle/call :did-web 'did-witness-route? [(str method) (str path)]))
