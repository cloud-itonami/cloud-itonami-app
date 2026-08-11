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
  fallback is how a decision stops being the one that shipped."
  (:require [clojure.edn :as edn]
            [kotoba.kir :as ir]
            #?(:clj [clojure.java.io :as io])))

(def cores
  "Oracle id -> the .kotoba it was compiled from, under src/."
  {:policy "cloud/itonami/app/policy.kotoba"
   :fleet-core "cloud/itonami/app/fleet_core.kotoba"
   :organism-worker "cloud/itonami/app/organism_worker.kotoba"
   :work-transitions "cloud/itonami/app/work_transitions_core.kotoba"
   :approval "cloud/itonami/app/approval_core.kotoba"})

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

(defn- read-artifact [id]
  #?(:clj
     (let [path (resource-path id)]
       (if-let [url (io/resource path)]
         (edn/read-string (slurp url))
         (throw (ex-info "shipped decision core is missing — run `clojure -M:test:gen`"
                         {:oracle id :path path}))))
     :cljs
     (throw (ex-info "no classpath on this runtime — register-kir! first"
                     {:oracle id}))))

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
