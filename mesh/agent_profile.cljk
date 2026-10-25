;; agent_profile.cljc — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; HTTP-triggered. The manifest binds this to route "/itonami/profile"; a POST
;; to `/mesh/http/itonami/profile` on a hosting node invokes `on-http` with the
;; request bytes, and its output becomes the response body.
;;
;; It SERVES the cloud-agent's identity record — what itonami.cloud is, where
;; its resident lives, and which models it speaks through murakumo. The
;; component IS the record: identity facts are deploy-time constants, so the
;; artifact that the lattice placed answers for them directly, and `run`
;; (invoked by the host at placement) also asserts them into the node's Datom
;; log so the lattice's own store carries the same facts.
;;
;; Why the record is returned as a value and not read back with `kqe-query`
;; (measured 2026-09-02 against kotoba-server 0.1.0, levi / dan / joseph):
;;   - kotoba-runtime host.rs: `kqe-query` is a PREDICATE filter over the
;;     snapshot the host hands the invocation, not a Datalog program.
;;   - kotoba-server net_actor.rs `invoke_trigger`: that snapshot is
;;     `Vec::new()` for every trigger, so a mesh component's query returns
;;     nothing on this build — while its `kqe-assert!` output IS persisted
;;     (`quad_store.assert_datom`, graph CID = from_bytes("g")). The first two
;;     versions of this file queried, executed fine (`trigger: executed` in
;;     mesh.log), and answered HTTP 200 with content-length 0 — the same shape
;;     as the kenchi / minidrama precedents' "HTTP 200". A read that cannot
;;     see writes is not a read; the record is served from the artifact.
;;   The write side is kept because it is real: each placement appends the
;;   record, each read appends an audit quad, and both survive in the node's
;;   quad store for the day the server hands components a snapshot.
;;
;; Output is a JSON text; the host CBOR-encodes the returned string, so the
;; HTTP body is a CBOR text item wrapping this JSON (kotoba-server 0.1.0 has
;; no content negotiation for mesh triggers).
;;
;; Written in the kotoba-clj subset the mesh compiler accepts: no regex
;; literals, no interop, no reader conditionals, no vectors-of-vectors —
;; explicit asserts and `str` only. `.cljc` is the workspace's rule for new
;; source; the file contains only the portable subset.
;;
;; host-imports used:  kqe-assert!  → kotoba:kais/kqe
(ns agent-profile)

(def agent-id "itonami.cloud")
(def residency "cloud")
(def substrate "murakumo.cloud")
(def ingress "agent.itonami.cloud")
(def chat-model "awai-network/basho")
(def video-model "awai-network/hokusai")
(def inference "https://api.murakumo.cloud/v1")
(def record-predicate "itonami-agent")

(defn- record []
  (str "{\"agent-id\":\"" agent-id "\","
       "\"residency\":\"" residency "\","
       "\"substrate\":\"" substrate "\","
       "\"ingress\":\"" ingress "\","
       "\"chat-model\":\"" chat-model "\","
       "\"video-model\":\"" video-model "\","
       "\"inference\":\"" inference "\","
       "\"served-by\":\"mesh:itonami-agent/agent-profile\"}"))

(defn- seed! []
  (kqe-assert! "g" "agent-id"    record-predicate agent-id)
  (kqe-assert! "g" "residency"   record-predicate residency)
  (kqe-assert! "g" "substrate"   record-predicate substrate)
  (kqe-assert! "g" "ingress"     record-predicate ingress)
  (kqe-assert! "g" "chat-model"  record-predicate chat-model)
  (kqe-assert! "g" "video-model" record-predicate video-model)
  (kqe-assert! "g" "inference"   record-predicate inference))

;; Placement / generic invoke: write the record to this node's log, answer it.
(defn run [_ctx]
  (seed!)
  (record))

;; HTTP trigger: (request-bytes) → the identity record.
(defn on-http [req]
  (kqe-assert! "g" "itonami-agent-query" "served" (str req))   ; audit the read
  (record))
