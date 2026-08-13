(ns cloud.itonami.app.session-handoff
  "Moving an authentication that already succeeded into the agent that asked
  for it.

  Unrelated to `cloud.itonami.app.handoff`, which is one Bot giving work to
  another. The name is shared because the shape is: something crosses a
  boundary, and the question is what it must not carry.

  ## The problem, stated exactly

  The native window is a webview with its own cookie jar. RFC 8252 says the
  authorization request must not open inside it — and this application has
  independent evidence for that rule, because the embedded webview cannot do
  WebAuthn, which is how people actually sign in here. So the sign-in happens
  in the system browser, the loopback callback lands there, and the session
  cookie is set in a jar the native window cannot read. The window that
  started the flow ends with nothing.

  ## The claim token, and why it is not the OAuth `state`

  `state` is published: it travels in the authorization URL, sits in an
  address bar, reaches the authorization server's logs, and returns in a
  redirect. A claim endpoint keyed on it would hand a session to anybody who
  read one. So the claim token is a second secret, generated beside it, that
  goes only into the JSON response the starter already receives — never into
  a URL, never to the authorization server, never into a redirect.

  ## Nothing sensitive is stored to make this work

  `identity/issue-session!` keeps `:token-digest`, never a raw session token,
  and this path does not become the exception. The claim record holds the
  digest of the claim token and the FACTS the callback established — which
  User, how they proved it. A claim mints its own session from those facts.

  That the native window gets a session of its own rather than a copy of the
  browser's is the better outcome anyway: two agents are two sessions, both
  listed in `/api/auth/sessions`, each revocable without killing the other.

  ## Where the decision is

  `session_handoff_core.kotoba`. This namespace hands it four booleans and
  reads back its answer, keeping no second copy of a rule it could quietly
  disagree with."
  (:require [cloud.itonami.app.kotoba-oracle :as oracle]))

(def schema "cloud.itonami.app.session-handoff.v1")

(def claim-window-seconds
  "How long a completed claim stays claimable.

  Ninety seconds. The agent that started the flow is already polling when the
  callback lands, so the real gap is one poll interval; the rest is slack for
  a person who took a moment on the consent screen and for a machine that went
  to sleep mid-flow. Longer would leave a mintable session sitting in the
  store for no one's benefit."
  90)

(def start-window-seconds
  "How long an unfinished claim survives.

  Bounded by the same clock as the OAuth transaction it belongs to — a claim
  whose `state` can no longer be exchanged cannot become ready, so keeping it
  past that point stores a secret that can never do anything."
  600)

;; ── the seam to the decision core ────────────────────────────────────

(def ^:private claim-record
  "The record `session_handoff_core.kotoba` declares, in DECLARED field order.

  No token, no user, no provider, no authentication strength. The first would
  put a live secret into a decision; the rest were decided by the callback and
  must not be re-decided by the thing that only moves the result."
  [:record :session-handoff/claim
   [[:origin-trusted :bool] [:ready :bool] [:claimed :bool] [:expired :bool]]])

(defn ->claim
  "The four facts the core decides from.

  `record` is what the store holds for this claim, or nil when the presented
  token matched nothing. A missing claim is not ready — which is the same
  answer a caller gets for a claim that exists and is not ready yet, and that
  sameness is deliberate: see `claimable?`."
  [record {:keys [origin-trusted? expired?]}]
  (oracle/record claim-record
                 [(boolean origin-trusted?)
                  (boolean (:session-handoff/ready? record))
                  (boolean (:session-handoff/claimed? record))
                  (boolean expired?)]))

(defn claimable?
  "May this presented claim token be exchanged for a session right now?

  Callers must not report WHICH refusal this was. Every negative answer goes
  back on the wire as the same \"not ready\", so a guessed token cannot be
  confirmed by the shape of its rejection — an unknown token, a claim still
  waiting, a spent one and an expired one are one response. The core keeps the
  distinctions because refusing precisely and answering vaguely are different
  jobs, and only the second belongs at the boundary."
  [record context]
  (oracle/call :session-handoff 'claimable? [(->claim record context)]))
