(ns cloud.itonami.app.host-bounds
  "What `host` decides before it touches a disk, and nothing else.

  Zero dependencies — not on this application, not on kotoba-lang, not on a
  host. That is the point: `bin/test-portable-cljs` grants no classpath beyond
  `src` and `test`, on the argument that a portable judgement needing a
  resolved dependency tree would not be very portable. `host` itself requires
  the filesystem and process libraries, so it cannot run there; these decisions
  can, and they are the part that has been wrong in production.

  Every function here came out of an outage, and none of them had a direct test
  before this file existed — they were expressions inside `write-atomic!`,
  reachable only by filling a disk or growing a file past its bound."
  (:require [clojure.string :as str]))

(def default-max-bytes
  "Bound for a confined write of a DOCUMENT -- content whose size is not ours
  to predict."
  (* 16 1024 1024))

(def store-max-bytes
  "Bound for the durable store writing its OWN state.

  These are different bounds and conflating them took the resident down. The
  document bound is a defence against content we did not author; the store file
  is content we author, and its size is a function of how much history the
  fleet has accumulated -- 28 MB and climbing on 2026-08-20, past a 16 MiB
  document bound, so every write was refused and the process could not start.
  Nothing untrusted has ever gone through this path: `write-atomic!` has
  exactly one caller, and it is the store.

  Still bounded, because unbounded is not the alternative to wrong.

  This paragraph used to end 'Retention is the actual gap', and that sentence
  outlived the gap. `gc` (ADR-0072) now compacts terminal goal-jobs after two
  days, drops them after fourteen, and keeps fifty per Bot. Measured 2026-08-27
  on the resident store: 1,663 of 1,985 goal-jobs compacted, the uncompacted
  remainder all inside the two-day window, sweep receipts every few hours.
  Nothing was overdue.

  It is left here as a correction rather than deleted, because the stale
  sentence did real work: on 2026-08-27 it was read as current and a store
  behaving exactly as designed was reported as unbounded growth. A standing
  claim about another namespace's behaviour is a claim that has to be re-measured
  or removed, and this one is now dated."
  (* 256 1024 1024))

(defn require-cap!
  "Fail closed when GRANTED is a set and CAP is absent from it.

  Takes the grant set as an ARGUMENT rather than reading a dynamic var, and
  that is the whole reason this function moved. The first attempt left the var
  in `host` and put this check here reading a var of its own -- so callers
  bound one and the check read the other, and the gate stopped denying while
  every call still succeeded. `host_grant_test` caught it; nothing about the
  code looked wrong. A pure function of its inputs cannot fail that way.

  An EMPTY set is a grant set, not an absent one: it denies everything. Only
  `nil` means untendered."
  [granted cap]
  (when granted
    (when-not (contains? granted cap)
      (throw (ex-info (str "Host capability not granted: " cap)
                      {:type :host/capability-denied
                       :capability cap
                       :granted granted}))))
  nil)

(def approaching-bound-fraction
  "Warn once a write is this far into its bound.

  0.75 is not a tuned number; it is a number that leaves room to act. What
  matters is that the warning exists at all."
  0.75)

(defn- mib [bytes] (/ (double bytes) 1048576))

(defn- round1 [n]
  (/ #?(:clj (Math/round (* 10.0 (double n)))
        :cljs (js/Math.round (* 10.0 (double n))))
     10.0))

(defn bound-warning
  "The sentence to print, or nil when SIZE is not yet near MAX-BYTES.

  Pure, and separated from the printing, because what it SAYS is the part that
  matters -- and `format` does not exist on both runtimes anyway.

  On 2026-08-20 the resident died on startup because the store had grown past
  its bound. There was no prior signal: writes succeeded at 99% of the bound and
  the process failed to start at 101%. The first thing anyone learned was that
  the fleet was down, and the log said `content exceeds :max-bytes` -- true, and
  useless, because by then the only way out was a code change."
  [name size max-bytes]
  (when (> size (* approaching-bound-fraction max-bytes))
    (let [pct (int (* 100 (/ (double size) max-bytes)))]
      (str "WARNING " name " is " pct "% of its "
           (int (mib max-bytes)) " MiB write bound ("
           (round1 (mib size)) " MiB). At the bound the write is REFUSED and "
           "a process that must write this file will not start. "
           "This is the only notice before that."))))

(def disk-headroom-bytes
  "Slack demanded beyond the write itself before it is allowed to start.

  On 2026-08-23 the disk filled and thirteen resident turns died as an opaque
  `fs/io write failed`; the one that kept its message said `No space left on
  device` after the bytes were already half-written. Refusing early loses
  nothing -- the write was going to fail -- and keeps the provenance: a typed
  error carrying what was measured, which the SLO surface can aggregate and
  `gc/sweep!` can act on."
  (* 32 1024 1024))

(defn disk-pressure?
  "Should a write of SIZE be refused, given USABLE bytes free?

  `usable` of 0 means the probe could not answer, and that is deliberately NOT
  pressure: the write is then the better witness."
  [usable size]
  (and (pos? usable) (< usable (+ size disk-headroom-bytes))))

(def journal-max-bytes
  "Bound for the write-ahead journal beside the store snapshot.

  Much smaller than `store-max-bytes` on purpose. The journal exists so an
  ordinary transaction does not rewrite a 30 MB snapshot; letting it grow to
  the snapshot's own bound would give back the amplification it was added to
  remove, and would make the startup replay itself the slow part."
  (* 4 1024 1024))

(def journal-max-entries
  "Records appended before a checkpoint folds them into the snapshot.

  A count as well as a size because the two failures differ: many tiny records
  make replay slow without ever approaching the byte bound."
  256)

(defn checkpoint-due?
  "Should the journal be folded into the snapshot now?"
  [entries journal-bytes]
  (or (>= entries journal-max-entries)
      (>= journal-bytes journal-max-bytes)))

(defn append-exceeds-bound?
  "Would appending ADD-BYTES to a file already CURRENT-BYTES long pass MAX?"
  [current-bytes add-bytes max-bytes]
  (> (+ current-bytes add-bytes) max-bytes))

(defn record-needs-its-own-snapshot?
  "Is this record too big for the journal to ever hold?

  Then checkpointing cannot make room and the write must go to the snapshot
  directly. Measured: one mail-sync transaction produced 2,041 operations and
  393 KB, so this is not hypothetical at a 4 MiB budget -- and a delta larger
  than the delta budget saves nothing anyway."
  [record-bytes max-bytes]
  (>= record-bytes max-bytes))

(defn journal-belongs-to-snapshot?
  "Is this journal an increment of THIS snapshot?

  A journal records the byte length of the snapshot it was opened against, and
  that length cannot move while the journal is live: only a checkpoint rewrites
  the snapshot, and a checkpoint truncates the journal in the same step. So a
  disagreement means something ELSE wrote the snapshot -- and the ops in the
  journal are then increments of a state that no longer exists.

  Measured 2026-08-27, which is why this function exists. A resident server
  running a build with journalling was replaced by an auto-update built from a
  branch without it. The new server read the snapshot, could not see the
  journal, and rewrote the snapshot from its own memory for an hour. The
  journal survived on disk holding 2,057 operations, and every one of them
  disagreed with the newer snapshot -- turn counts, token totals and whole
  message vectors, all of them older. Replaying that journal, which is exactly
  what the next journalling process would have done, was a silent hour-long
  rollback presented as a normal start.

  NIL base-bytes is a journal written before this check existed. It cannot be
  vouched for, so it does not belong."
  [base-bytes snapshot-bytes]
  (and (some? base-bytes)
       (some? snapshot-bytes)
       (= base-bytes snapshot-bytes)))

(defn merge-output
  "stdout and stderr as one stream, matching the legacy ProcessBuilder callers
  that set `redirectErrorStream`. A blank half contributes nothing, including
  its separator."
  [stdout stderr]
  (str stdout
       (when-not (str/blank? stderr)
         (str (when-not (str/blank? stdout) "\n") stderr))))
