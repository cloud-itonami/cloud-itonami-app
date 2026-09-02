;; agent_profile.cljc — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; HTTP-triggered. The manifest binds this to route "/itonami/profile"; a POST
;; to `/mesh/http/itonami/profile` on a hosting node invokes `on-http` with the
;; request bytes, and its output becomes the response.
;;
;; It SERVES the cloud-agent's identity record — what itonami.cloud is, where
;; its resident lives, and which models it speaks through murakumo — from facts
;; the off-mesh resident asserted to the Datom log (kqe). It decides nothing
;; and calls no model: `llm/infer` is not yet an admissible guest capability
;; (see mesh/itonami.app.edn), and a profile that answered differently
;; depending on a model would not be an identity record.
;;
;; Written in the kotoba-clj subset the mesh compiler accepts: no regex
;; literals, no interop, no reader conditionals, plain str/subs/count
;; (kenchi's valuation_query is the precedent that found those limits). The
;; `.cljc` extension is the workspace's rule for new source; the file contains
;; only the portable subset.
;;
;; host-imports used:  kqe-assert! / kqe-query  → kotoba:kais/kqe
(ns agent-profile)

(def agent-id "itonami.cloud")

;; generic invoke / placement probe — a placement auction may call `run` to
;; check the component answers; it must be cheap and pure-read.
(defn run [_ctx]
  (kqe-query "profile(?k,?v) :- itonami-agent(?k,?v)."))

;; HTTP trigger: (request-bytes) → the agent's profile facts.
;;   itonami-agent(key, value) is the relation the off-mesh resident asserts:
;;     residency        "cloud" | "local"
;;     substrate        "murakumo.cloud"
;;     ingress          "agent.itonami.cloud"
;;     chat-model       "awai-network/basho"
;;     video-model      "awai-network/hokusai"
;;     inference        "https://api.murakumo.cloud/v1"
(defn on-http [req]
  (kqe-assert! "g" "itonami-agent-query" "served" (str req))   ; audit the read
  (kqe-query "profile(?k,?v) :- itonami-agent(?k,?v)."))
