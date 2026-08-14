;; One core, run on the native ISA, in the DEFAULT suite.
;;
;; Until this file, every Kotoba check in this repository compiled for
;; `:x86_64-kotoba-v1` / `:aarch64-kotoba-v1` and then stopped. Acceptance is
;; not execution: a module can compile and still compute the wrong thing. The
;; production path (`cloud.itonami.app.kotoba-oracle/call`) still answers
;; through the KIR interpreter on the JVM. This canary is the first time a
;; decision in this application actually runs as machine code, and it has to
;; live in `clojure -M:test` or it will not run — murakumo measured that an
;; opt-in `:native` alias is never invoked.
;;
;; It does NOT make a full sweep unnecessary, and it does NOT flip the
;; production seam. `native-exec` explains both. This file must not grow into
;; a second sweep.
;;
;; A missing C toolchain fails rather than skips. A native execution test that
;; does not execute has verified nothing, and a quiet skip is indistinguishable
;; from a passing one.

(ns cloud.itonami.app.native-canary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloud.itonami.app.native-exec :as native]))

(def ^:private canary-core
  "The routing policy. Chosen because `loopback-host?` is `:string → :bool`
  and therefore actually crosses a kexe export, and because it is the
  decision that every request is already answering. `provider-allowed?`
  takes a record and is counted as refused, not skipped."
  "src/cloud/itonami/app/policy.kotoba")

(deftest the-native-backend-still-agrees-on-one-core-every-run
  (is (some #{canary-core} (native/core-sources))
      (str canary-core " must still be a shipped core; if it was renamed or "
           "removed, point this canary at another native-crossable one"))
  (let [{:keys [module calls agreed both-refused crossing exported disagreements]}
        (native/run-core (native/native-host) canary-core)]
    (println (format "native canary: %s -- %d of %d exports crossed, %d calls, %d agreed"
                     module crossing exported calls agreed))
    (is (pos? crossing)
        "at least one export must reach the native ISA, or this measures nothing")
    (is (pos? agreed)
        "some call must produce a VALUE on both engines -- a run where every
         call merely faulted on both sides would satisfy the disagreement
         assertion while executing nothing")
    (is (empty? disagreements)
        (str "native disagrees with the reference interpreter:\n"
             (str/join "\n" (map pr-str disagreements))))
    (is (= calls (+ agreed both-refused))
        "every call is either an agreement or a mutual refusal")))
