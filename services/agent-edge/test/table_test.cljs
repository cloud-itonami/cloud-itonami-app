(ns table-test
  "The ingress table, asserted without a network.

  What matters here is not that the carried routes are carried — it is that the
  ones deliberately left out are refused, and refused the SAME way an unknown
  path is. A 405 on a known path with the wrong method tells a scanner which
  paths exist behind this door, which is the one fact the door is for.

  Run: npx nbb test/table_test.cljs   (from services/agent-edge)"
  (:require ["../src/index.js" :as edge]
            [clojure.string :as str]))

(defonce failures (atom 0))

(defn check! [label ok? detail]
  (if ok?
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "—" detail))))

(defn carried? [method path]
  (.-carried (edge/decide method path)))

;; ---------------------------------------------------------------------------

(check! "a read this surface offers is carried"
        (carried? "GET" "/api/agent-bots") "")
(check! "a bot's messages are carried with an id in the path"
        (carried? "GET" "/api/agent-bots/bot-8f2c/messages") "")
(check! "posting a message is carried"
        (carried? "POST" "/api/agent-bots/bot-8f2c/messages") "")
(check! "the hermes-shaped session routes are carried"
        (and (carried? "POST" "/p/default/api/sessions")
             (carried? "POST" "/p/default/api/sessions/s-1/messages")) "")

;; ---- the refusals, which are the point ------------------------------------

(check! "an approval is NOT carried"
        (not (carried? "POST" "/api/agent-bots/b1/cards/c1/decide"))
        "approval reached the resident")
(check! "workforce provisioning is NOT carried"
        (not (carried? "POST" "/api/agent-bots/workforce/provision"))
        "provisioning reached the resident")
(check! "minting a session is NOT carried"
        (not (carried? "POST" "/api/agent-session"))
        "a remote caller could mint its own session")
(check! "reading state is NOT carried"
        (not (carried? "GET" "/api/state"))
        "the leak mcp-edge measured on 2026-08-08, reopened")
(check! "the root document is NOT carried"
        (not (carried? "GET" "/")) "the whole UI is behind this door")

;; ---- the pattern is anchored and bounded ----------------------------------
;; A prefix test wearing a regex is the failure this table is written to avoid,
;; so it is asserted rather than trusted to the eye.

(check! "a longer path with a carried prefix is NOT carried"
        (not (carried? "GET" "/api/agent-botsanything"))
        "the pattern is a prefix test")
(check! "an extra segment after a carried route is NOT carried"
        (not (carried? "GET" "/api/agent-bots/b1/messages/extra"))
        "the pattern is not anchored at the end")
(check! "a path separator cannot hide inside an id"
        (not (carried? "GET" "/api/agent-bots/b1/cards/c1/messages"))
        "the id segment matched a slash")
(check! "an empty id is NOT carried"
        (not (carried? "GET" "/api/agent-bots//messages"))
        "an empty segment matched")

;; ---- method is part of the key --------------------------------------------

(check! "a write to a read-only route is NOT carried"
        (not (carried? "POST" "/api/agent-bots"))
        "the method is not part of the key")
(check! "DELETE is not carried anywhere"
        (not (some #(carried? "DELETE" %)
                   ["/api/agent-bots" "/api/agent-bots/b1/messages"
                    "/health" "/api/profiles"]))
        "a method nobody listed was carried")

(if (pos? @failures)
  (do (println "\nFAILED:" @failures) (js/process.exit 1))
  (println "\nthe ingress table refused what it is for"))
