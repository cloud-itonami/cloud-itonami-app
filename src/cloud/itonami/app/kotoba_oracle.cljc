(ns cloud.itonami.app.kotoba-oracle
  "Runs the shipped decision cores.

  `src/cloud/itonami/app/*.kotoba` holds the decisions;
  `resources/cloud/itonami/app/oracle/*.kir.edn` is what was compiled from
  them and what ships. This namespace is the seam, and it is deliberately
  thin: it resolves a resource, executes an export, and decides nothing.

  ## Why this exists

  The three cores landed with a parity test that ran both implementations over
  the same inputs and required the same answers. That was the right first step
  and it caught the thing it was built for — before it, `policy.cljc` claimed
  in a docstring to mirror `policy.kotoba` while the two files shared no
  function at all.

  But two implementations bound by a test are still two implementations, and
  the measure of this migration is not how many host lines went away; it is
  whether the AUTHORITY moved (ADR-2608110100, quoting
  `ADR-safe-capability-language` 0.1). Until now it had not: the `.kotoba` was
  a checked replica and the `.cljc` was what ran. Now the `.kotoba` is what
  runs, and the `.cljc` keeps the halves that are not decisions — reading a
  map, walking a collection, throwing.

  ## What made it possible

  `policy-kotoba-parity-test` said \"a record cannot be handed across the entry
  boundary as a literal — it is constructed inside the guest\", and built
  zero-arg probe wrappers around each case to work around it. Measured at this
  repository's pins on 2026-08-11, that is not true: `ir/execute` accepts a
  record as `[schema field …]` in declaration order, accepts `[:option :string]`
  as `[[:option :string] true \"x\"]` / `[[:option :string] false]`, and returns
  both, plus `:keyword`, unchanged. murakumo's `oracle/record` had recorded the
  same thing on 2026-07-29. The workaround was load-bearing for the test and
  would have been fatal here — a production call path cannot recompile.

  ## No fallback, in either direction

  A missing or unreadable artifact throws. It does not quietly run a host
  reimplementation, because there is no longer one to run, and because a silent
  fallback is how a decision stops being the one that shipped.

  ## `:i64` does not cross by itself, and the JVM cannot tell you that

  An `:i64` is a JVM `long` under `:clj` and a `js/BigInt` under `:cljs`, and
  the second is not what a host map holds. `kir/execute` coerces a TOP-LEVEL
  `:i64` argument and will accept a host integer there, so a seam without a
  conversion looks finished on both runtimes right up until the integer is
  inside a record: that goes through `value/bounded-typed-value!`, which under
  ClojureScript requires a `js/BigInt` and rejects a `js/Number` outright
  (`value is not a signed i64`). The compiler's T5.2 rewrite pushed every
  multi-argument pure export into a record, so that is most of them.

  The return direction is worse, because it does not throw. An `:i64` result
  read on ClojureScript is a `js/BigInt`, and `(get {0 :connect} (js/BigInt 0))`
  is a miss — a host that maps a guest status code through a literal map gets
  its `not-found` for every input and answers confidently with the wrong one.

  `i64` and `i64-value` are that conversion, kept here so a caller never has to
  know which runtime it is on. Every `:i64` that crosses this seam, in either
  direction, goes through one of them.

  ## Reading the artifact where there is no classpath

  ClojureScript has no classpath, but it usually has SOMETHING: nbb has a
  filesystem, a Worker has an asset binding. `set-resource-loader!` takes that
  something as a function and `register-kir!` is its pre-parsed form. Neither
  is a default: the seam does not reach for `node:fs` on its own, because a
  namespace that does cannot be bundled into a Worker running without
  `nodejs_compat` — which this one's is, deliberately."
  (:require [clojure.edn :as edn]
            [kotoba.kir :as ir]
            #?(:clj [clojure.java.io :as io])))

(def cores
  "Oracle id -> the .kotoba it was compiled from, under src/."
  {:policy "cloud/itonami/app/policy.kotoba"
   :fleet-core "cloud/itonami/app/fleet_core.kotoba"
   :organism-worker "cloud/itonami/app/organism_worker.kotoba"
   :work-transitions "cloud/itonami/app/work_transitions_core.kotoba"
   :approval "cloud/itonami/app/approval_core.kotoba"
   :bot "cloud/itonami/app/bot_core.kotoba"
   :routine "cloud/itonami/app/routine_core.kotoba"
   :handoff "cloud/itonami/app/handoff_core.kotoba"
   :peer "cloud/itonami/app/peer_core.kotoba"
   :session-handoff "cloud/itonami/app/session_handoff_core.kotoba"
   :health "cloud/itonami/app/health_core.kotoba"
   :oauth-resource "cloud/itonami/app/oauth_resource_core.kotoba"
   :did-web "cloud/itonami/app/did_web_core.kotoba"
   :domain-binding "cloud/itonami/app/domain_binding_core.kotoba"
   :identity "cloud/itonami/app/identity_core.kotoba"
   :store-core "cloud/itonami/app/store_core.kotoba"
   :workforce-cadence "cloud/itonami/app/workforce_cadence_core.kotoba"
   :model-routing "cloud/itonami/app/model_routing_core.kotoba"})

(defn resource-path [id]
  (str "cloud/itonami/app/oracle/" (name id) ".kir.edn"))

(def ^:private registered
  "Pre-parsed KIR, for runtimes with no classpath (a Worker bundle injects here)."
  (atom {}))

(defn register-kir!
  "Install a parsed KIR for `id`, bypassing the resource read."
  [id kir]
  (swap! registered assoc id kir)
  kir)

(defn deregister-kir!
  "Drop a registration, so `id` reads the shipped artifact again."
  [id]
  (swap! registered dissoc id)
  nil)

(def ^:private resource-loader
  "path -> artifact text, for a ClojureScript host that has neither a classpath
  nor a filesystem. A Worker installs its asset fetch here."
  (atom nil))

(defn set-resource-loader!
  "Install `f` : resource-path -> artifact text (or nil). Pass nil to clear."
  [f]
  (reset! resource-loader f)
  f)

(defn- read-artifact [id]
  #?(:clj
     (let [path (resource-path id)]
       (if-let [url (io/resource path)]
         (edn/read-string (slurp url))
         (throw (ex-info "shipped decision core is missing — run `clojure -M:test:gen`"
                         {:oracle id :path path}))))
     :cljs
     (let [path (resource-path id)
           text (when-let [f @resource-loader] (f path))]
       (if text
         (edn/read-string text)
         (throw (ex-info "no classpath on this runtime — register-kir! or set-resource-loader! first"
                         {:oracle id :path path}))))))

(def ^:private cache (atom {}))

(defn kir
  "The shipped KIR for `id`, read once."
  [id]
  ;; A registration wins over the cache: it is an explicit instruction, and a
  ;; caller that registers after something already read the artifact means the
  ;; registration, not the read.
  (or (get @registered id)
      (get @cache id)
      (let [loaded (read-artifact id)]
        (swap! cache assoc id loaded)
        loaded)))

(defn call
  "Execute an export of a shipped core. Args and result are guest ABI values;
  see `option` and `record` for the two that are not plain scalars."
  [id export args]
  (ir/execute (kir id) (if (symbol? export) export (symbol (name export))) (vec args)))

;; ── the two guest values that are not plain scalars ──────────────────
;;
;; Shaped exactly as `ir/execute` returns them, so a value read out of one
;; export can be passed straight into another.

(def string-option [:option :string])

(defn option
  "Host nil -> none; anything else -> some, stringified."
  [s]
  (if (nil? s) [string-option false] [string-option true (str s)]))

(defn option-value
  "Payload of a some option, or nil for none."
  [opt]
  (when (and (vector? opt) (true? (second opt))) (nth opt 2)))

(defn record
  "Build a guest record argument: the descriptor, then fields in DECLARED
  order. Declared order, not map order — a record whose fields are permuted is
  accepted by nothing and silently wrong in nothing, it simply fails to match
  the declared type."
  [schema field-values]
  (into [schema] field-values))

;; ── :i64, which is a different host type on each runtime ─────────────

(defn fits-i64?
  "Whether a host value can cross as `:i64` without changing what it means.

  On `:cljs` the host speaks `js/Number`, so the round trip is only exact
  inside the safe-integer range; outside it the answer is no, and the caller
  keeps whatever it was going to do otherwise."
  [n]
  #?(:clj  (and (integer? n) (<= Long/MIN_VALUE n Long/MAX_VALUE))
     :cljs (and (number? n) (js/Number.isSafeInteger n))))

(defn i64
  "Host integer -> guest `:i64`.

  Required for an `:i64` inside a record. Also correct, and cheaper to apply
  uniformly, for a top-level `:i64` argument that `kir/execute` would have
  coerced anyway — the point of putting it at every crossing is that no reader
  has to work out which kind a given argument is."
  [n]
  #?(:clj (long n) :cljs (js/BigInt n)))

(defn i64-value
  "Guest `:i64` -> host integer.

  The direction that does not throw when it is skipped: an unconverted
  `js/BigInt` simply misses every host lookup keyed by a number."
  [n]
  #?(:clj n :cljs (js/Number n)))
