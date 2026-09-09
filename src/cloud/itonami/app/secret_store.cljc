(ns cloud.itonami.app.secret-store
  "Where a credential a person typed is kept, and how the call site gets it
  back.

  ## The shape this follows

  `mail-age-key` already answered this question once, for the age recipient:
  environment, then the macOS Keychain, then kagi. This is the same ladder for
  the same reason — *an environment variable is not an answer for a desktop
  app; it is an answer for whoever remembers to export it* — with one rung
  removed.

  ## Why kagi is not on the read path

  It is the workspace's vault and it is where an item of record belongs, and
  reading it here would still be wrong: `kagi get` can prompt, and this
  resolution runs at the moment of a tool call. A source that may block for a
  human is the wrong thing to put in front of every Cloudflare request —
  `mail-age-key` reaches for it once, when a message is filed, and even there
  it is deliberately last. This mechanism WRITES to the Keychain, so it reads
  from the Keychain, and the two halves cannot disagree about where a value is.
  A deployment that keeps this credential in kagi exports it, which is the rung
  above.

  ## What this namespace will not do

  - **It will not enumerate.** Every read names a service and an account in
    full, both taken from the catalogue entry. `identity/keychain-find` already
    refuses to list, and this does not work around it. A caller that does not
    know which item it wants has no business here.
  - **It will not return a value to a caller that did not name one.** There is
    no `all`, no `dump`, no `some-secret`.
  - **It will not log one.** `security` prints an item's password as part of
    its human-readable attribute dump under `-g`, and a subprocess inherits the
    caller's stderr unless told otherwise — that pair is how a B2 key once
    reached a chat transcript (ADR-2607152322). Every subprocess here reads
    with `-w`, which prints the password alone, and redirects its error stream
    into the same captured pipe rather than to the console.

  ## Present is not the same as correct

  `present?` says an item exists. It does not say the value in it still works:
  a Cloudflare token can be revoked without anything here noticing. The card
  reports what this knows — *stored* — and the tool that uses it reports what
  it finds. Conflating the two would put 保存済み on screen next to a call that
  is failing on 403."
  (:require [kotoba.lang.text :as str]
            [cloud.itonami.app.secret-request :as request])
  #?(:clj (:import [java.util.concurrent TimeUnit])))

(def ^:dynamic *environment*
  #?(:clj #(System/getenv %)
     ;; nbb has `js/process.env`, and this could be written for it. It is not,
     ;; because the two functions below cannot be: `security` is a macOS binary
     ;; and there is no consumer of this namespace off the JVM. An environment
     ;; reader that worked beside a keychain reader that refused would answer
     ;; "no credential" for a credential that is present.
     :cljs (fn [_] nil)))

(defn- unsupported-host!
  "Refuse, rather than answer nil.

  A `nil` here is indistinguishable from 'this machine has no such item', and
  that is the shape this whole workspace keeps finding: a check that could not
  run returning the same value as a check that ran and found nothing
  (ADR-2608136000). A caller on a host with no `security` should fail loudly
  and be given the host it needs."
  [argv]
  (throw (ex-info "the credential store needs a host that can run `security`"
                  {:type :secret/unsupported-host
                   :command (first argv)})))

(defn- env [name] (some-> (*environment* name) str/trim not-empty))

(defn- run*
  "Run a command, capturing stdout, and return it trimmed or nil.

  `redirectErrorStream` is not tidiness: without it the child's stderr goes to
  this process's stderr, which is where a running desktop app's log is. Every
  failure is nil rather than an exception, because each source below is
  optional by design and an absent one must fall through to the next."
  [argv & {:keys [timeout-seconds] :or {timeout-seconds 10}}]
  #?(:clj
     (try
       (let [process (.start (doto (ProcessBuilder. ^java.util.List (vec argv))
                               (.redirectErrorStream true)))
             output (future (slurp (.getInputStream process)))
             finished? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
         (when-not finished? (.destroyForcibly process))
         (when (and finished? (zero? (.exitValue process)))
           (not-empty (str/trim (deref output 2000 "")))))
       (catch Exception _ nil))
     :cljs (unsupported-host! argv)))

(defn- write*
  "Run a command that is not expected to print anything, and say whether it
  worked. Output is captured and discarded rather than inherited: the value is
  on this argument list."
  [argv & {:keys [timeout-seconds] :or {timeout-seconds 10}}]
  #?(:clj
     (try
       (let [process (.start (doto (ProcessBuilder. ^java.util.List (vec argv))
                               (.redirectErrorStream true)))
             _ (future (slurp (.getInputStream process)))
             finished? (.waitFor process timeout-seconds TimeUnit/SECONDS)]
         (when-not finished? (.destroyForcibly process))
         (boolean (and finished? (zero? (.exitValue process)))))
       (catch Exception _ false))
     :cljs (unsupported-host! argv)))

;; Seams, in the shape this application already uses for `*environment*` and
;; `cloudflare/*send!*`. A test that had to run `security` would either write
;; to the developer's own login keychain or be skipped, and a skipped test
;; reports the same green as a passing one.

(def ^:dynamic *run* run*)
(def ^:dynamic *write* write*)

(defn- run [argv & opts] (apply *run* argv opts))
(defn- write [argv & opts] (apply *write* argv opts))

;; ── the two sources ──────────────────────────────────────────────────────

(defn- from-environment [requirement]
  (some-> (:secret/environment requirement) env))

(defn- from-keychain [requirement]
  (run ["security" "find-generic-password"
        "-s" (:secret/service requirement)
        "-a" (:secret/account requirement)
        "-w"]
       :timeout-seconds 3))

;; ── the API ──────────────────────────────────────────────────────────────

(defn value
  "The credential for `id`, or nil.

  Named in full by the caller, which in practice means named by the catalogue.
  Callers are tools, at the moment of the call: nothing holds the result, and
  nothing puts it anywhere a conversation can reach."
  [id]
  (when-let [r (request/requirement id)]
    (or (from-environment r) (from-keychain r))))

(defn source
  "WHERE the credential for `id` is coming from, without saying what it is.

  This is what the card shows. `:environment` and `:stored` are both working
  states; they are distinguished because an operator who exported the variable
  needs to know that the stored item is not what is being used -- re-entering
  the value would change nothing while the variable is set -- and a person who
  typed it needs to know that it is."
  [id]
  (when-let [r (request/requirement id)]
    (cond
      (from-environment r) :environment
      (from-keychain r) :stored)))

(defn present?
  "Is there a credential for `id` at all? Says nothing about whether it works."
  [id]
  (some? (source id)))

(defn put!
  "Store `value` for `id`, and return where it went.

  Returns a `keychain://service/account` reference — the same form
  `identity/keychain-put!` and `mail-account` return, and the only thing about
  this write that is safe to record. `-U` updates in place, so re-answering a
  card rotates the item rather than creating a second one; a credential with
  two copies has two expiry dates and one rotation.

  Refuses rather than truncating or trimming: `secret-request/admit` has
  already decided, and this asserts the decision was made rather than trusting
  the caller to have made it.

  **The value is on this argument list.** `security add-generic-password` has
  no way to take a password on stdin -- `-w` with no argument prompts a terminal
  this application does not have -- so for the duration of one short-lived
  subprocess the value is visible in `ps` to other processes of the SAME user
  on this machine. That is the exposure `identity/keychain-put!` and
  `mail-account/keychain-put!` already carry, and it is written down here
  rather than left to be rediscovered. It is not a reason to leave the
  credential in the transcript instead: a transcript is durable, is read by a
  model, and is read again by every later turn."
  [id value]
  (let [r (request/requirement! id)
        {:keys [admitted? reason]} (request/admit r value)]
    (when-not admitted?
      (throw (ex-info (request/refusal reason)
                      {:type :secret/refused :secret id :reason reason})))
    (when-not (write ["security" "add-generic-password" "-U"
                      "-s" (:secret/service r)
                      "-a" (:secret/account r)
                      "-D" "cloud-itonami credential"
                      "-j" (:secret/title r)
                      "-w" value]
                     :timeout-seconds 5)
      (throw (ex-info "macOS キーチェーンに保存できませんでした。"
                      {:type :secret/keychain-error :secret id})))
    (str "keychain://" (:secret/service r) "/" (:secret/account r))))

(defn forget!
  "Remove the stored copy for `id`. Silent about whether there was one — a
  caller that wants to know asks `present?`, and 'there was nothing to delete'
  is not a failure of deleting."
  [id]
  (when-let [r (request/requirement id)]
    (write ["security" "delete-generic-password"
            "-s" (:secret/service r) "-a" (:secret/account r)]
           :timeout-seconds 5)
    nil))
