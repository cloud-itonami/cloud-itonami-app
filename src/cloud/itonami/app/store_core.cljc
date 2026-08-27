(ns cloud.itonami.app.store-core
  "The state transitions `store` performs, without the state, the file, or the
  clock.

  ## Why this is separate

  `cloud.itonami.app.store` is the namespace the most other namespaces wait on:
  measured 2026-08-27 by `scripts/jvm-exit-report.cljs`, it is what blocks the
  largest group of otherwise-portable files. It cannot become `.cljc` itself
  yet, and the reason is worth writing down rather than rediscovering — it
  requires `langchain.edn-persist`, which is `.clj` in an EXTERNAL library.
  Porting the app's persistence off the JVM is a decision about how state is
  written when there is no filesystem, and that belongs in an ADR, not in a
  rename.

  What does not have to wait for that decision is the part of `store` that
  never touched a JVM: given a state map, what the next state map is. Those
  transitions were written inline inside `transact!` callbacks, where the only
  way to exercise one was to have an atom and a directory. Here they are
  ordinary functions of ordinary values, so both runtimes can run them and a
  test does not need a disk.

  ## The seam

  Every function here takes the values it would otherwise have reached for —
  the id, the timestamp — as arguments. That is what keeps the namespace free
  of a clock and an id generator, and it is also what makes the tests able to
  assert on exact output instead of on shape.

  ## What is deliberately NOT here

  The datom assertions that accompany an appended message. They go through
  `kotoba.kgraph`, which is `.clj` in an external library, so a `store-core`
  that called it would be a `.cljc` file ClojureScript cannot load — the exact
  defect this move exists to stop. `store` does that half, in the same
  transaction as the transcript update, and it stays there until kgraph is
  portable. This was not foreseen: the first version of this namespace required
  kgraph, and `bin/test-portable-cljs` refused it on the first run. That is the
  argument for having that runner.")

(def schema "cloud.itonami.app.state.v1")

(defn initial-state []
  {:schema schema
   :agents [{:id "local" :name "Local" :system-prompt
             "You are a private, local-first assistant. Be concise and useful."}]
   :sessions {}
   ;; One `drive.workspace` per principal — the tree, the ACL, the quota and
   ;; the version history. The bytes those versions point at are not in here;
   ;; they are in an object store. See `cloud.itonami.app.documents`.
   :drive {:workspaces {}}
   :datoms []
   :events []
   :last-response nil})

(defn new-id
  "`prefix-<uuid>`.

  `random-uuid` rather than `java.util.UUID/randomUUID`: it is in `clojure.core`
  on both runtimes and prints the same 36 characters, so this crossed the
  runtime boundary without changing a single id."
  [prefix]
  (str prefix "-" (random-uuid)))

(defn set-context-refs
  "Attach the conversation's context references.

  Context belongs to the conversation rather than to one message, which is why
  this merges into the session instead of writing a field on a transcript
  entry."
  [state session-id refs at]
  (update-in state [:sessions session-id]
             #(merge {:id session-id :messages []} (or % {})
                     {:updated-at at :context-refs (vec refs)})))

(defn append-message
  "Add one recorded message to a session's transcript.

  `recorded` is already stamped — it carries its own `:id` and `:at` — because
  minting an id and reading a clock are the two things this namespace exists
  not to do. `max-messages` trims from the FRONT: the transcript is a window on
  the recent conversation, and the datom set `store` writes beside it is the
  part that is not forgotten."
  [state session-id recorded max-messages]
  (let [messages (conj (vec (get-in state [:sessions session-id :messages] []))
                       recorded)
        kept (vec (take-last max-messages messages))]
    (-> state
        ;; Preserve all session fields while appending the transcript.
        (update-in [:sessions session-id]
                   #(merge {:id session-id} (or % {})
                           {:updated-at (:at recorded) :messages kept})))))

(def max-events
  "How many chat-completion events are retained.

  A bounded ring, not a log: the durable log is the datom set. This exists so
  the last few completions can be shown without reading one."
  100)

(defn record-response
  "Record the last provider response and append its completion event."
  [state response at]
  (-> state
      (assoc :last-response response)
      (update :events #(vec (take-last max-events
                                       (conj (or % [])
                                             {:type :chat/completed
                                              :at at
                                              :provider (:provider response)
                                              :model (:model response)}))))))

(defn clear-session [state session-id]
  (update state :sessions dissoc session-id))

(defn session-messages [state session-id]
  (get-in state [:sessions session-id :messages] []))

(defn session-context-refs [state session-id]
  (vec (get-in state [:sessions session-id :context-refs] [])))
