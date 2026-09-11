(ns cloud.itonami.app.host-bounds-test
  "The decisions `host` makes before it touches a disk, on both runtimes.

  All three came out of real incidents, and none of them had a direct test —
  they were expressions inside `write-atomic!`, reachable only by filling a
  disk or growing a file past its bound. The dates in the docstrings are the
  outages; these are the assertions that were missing while those were being
  diagnosed.

  Against `host-bounds` rather than `host`, because `host` requires the
  filesystem and process libraries and `bin/test-portable-cljs` grants no
  classpath beyond `src` and `test`. The decisions have no dependencies, so
  they live where that is true."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.host-bounds :as host]))

(deftest the-two-bounds-are-different-on-purpose
  ;; Conflating them took the resident down on 2026-08-20: the store writes its
  ;; OWN state, whose size is a function of accumulated history, and it was
  ;; being held to a bound meant for documents nobody here authored.
  (is (> host/store-max-bytes (* 16 1024 1024))
      "the store bound is above the document bound that refused every write")
  (is (>= host/store-max-bytes (* 28 1024 1024))
      "and above the 28 MB the store had actually reached when it failed"))

(deftest a-write-is-warned-about-before-it-becomes-impossible
  (testing "nothing to say well below the bound"
    (is (nil? (host/bound-warning "state.edn" 10 (* 100 1024 1024)))))
  (testing "nothing to say just under the warning fraction"
    (let [max-bytes 1000]
      (is (nil? (host/bound-warning "state.edn"
                                    (dec (long (* host/approaching-bound-fraction
                                                  max-bytes)))
                                    max-bytes)))))
  (testing "and a sentence naming the file and the percentage above it"
    (let [message (host/bound-warning "state.edn" (* 90 1024 1024)
                                      (* 100 1024 1024))]
      (is (some? message))
      (is (re-find #"state\.edn" message))
      (is (re-find #"90%" message))
      (is (re-find #"REFUSED" message)
          "the consequence is in the sentence, because the log line was the
           only notice anyone got")))
  (testing "the percentage is of the bound, not of anything else"
    ;; 50 of 100 is BELOW the 0.75 fraction, so there is nothing to say -- an
    ;; earlier version of this test asserted "50%" here and the ClojureScript
    ;; run caught it as a nil match.
    (is (nil? (host/bound-warning "s" 50 100)))
    (is (re-find #"80%" (host/bound-warning "s" 80 100)))
    (is (re-find #"100%" (host/bound-warning "s" 100 100))
        "at the bound itself the write is about to be refused")))

(deftest a-probe-that-could-not-measure-does-not-refuse-the-write
  ;; `usable-space` answers 0 for "unable to determine". Treating that as
  ;; pressure would refuse every write on a host with no statfs, which is a
  ;; worse failure than the one this guard exists to prevent.
  (is (false? (host/disk-pressure? 0 (* 1024 1024 1024)))
      "0 is 'unknown', and the write is then the better witness"))

(deftest the-disk-preflight-demands-headroom-beyond-the-write-itself
  (let [size (* 4 1024 1024)]
    (testing "refused when what is left would not cover the write plus headroom"
      (is (true? (host/disk-pressure? (+ size 1) size))))
    (testing "admitted once the headroom fits"
      (is (false? (host/disk-pressure? (+ size host/disk-headroom-bytes 1) size))))
    (testing "the boundary itself is refused rather than squeaked through"
      (is (true? (host/disk-pressure? (+ size host/disk-headroom-bytes -1) size)))
      (is (false? (host/disk-pressure? (+ size host/disk-headroom-bytes) size))))))

(deftest a-grant-set-fails-closed-and-an-absent-one-does-not
  ;; The grant set is an ARGUMENT. When it was a dynamic var read from here
  ;; while callers bound one in `host`, the gate stopped denying and every call
  ;; still succeeded -- fail-open, invisible in the code, caught only by
  ;; `host_grant_test` binding the var a real caller binds.
  (testing "untendered: nil means the legacy host, not a denial"
    (is (nil? (host/require-cap! nil :fs/write))))
  (testing "tendered and held"
    (is (nil? (host/require-cap! #{:fs/write :process/spawn} :fs/write))))
  (testing "tendered and absent"
    (is (= :host/capability-denied
           (try (host/require-cap! #{:process/spawn} :fs/write) nil
                (catch #?(:clj Exception :cljs :default) e
                  (:type (ex-data e)))))))
  (testing "an empty grant set denies everything rather than meaning untendered"
    (is (= :host/capability-denied
           (try (host/require-cap! #{} :fs/write) nil
                (catch #?(:clj Exception :cljs :default) e
                  (:type (ex-data e)))))))
  (testing "the refusal names the capability, because the log line is the notice"
    (is (re-find #"fs/write"
                 (try (host/require-cap! #{} :fs/write) ""
                      (catch #?(:clj Exception :cljs :default) e
                        #?(:clj (.getMessage e) :cljs (.-message e))))))))
