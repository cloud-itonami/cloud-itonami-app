(ns cloud.itonami.app.lifecycle-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            [clojure.string :as str]
            [cloud.itonami.app.lifecycle :as lifecycle]))

;; The resident install printed 86 identical untimestamped start lines over
;; 103.8 hours -- a restart every 72 minutes -- and no stop line at all, so a
;; terminated process and a crashed one left the same evidence. These are the
;; judgements that make a restart attributable; they run on both runtimes
;; because they need neither a clock class nor a process.

(deftest a-start-line-says-when-what-and-where
  (let [line (lifecycle/started-line 1787900000000
                                     "/Users/j/.cloud-itonami/releases/ffd0de9"
                                     "http://127.0.0.1:1338")]
    (is (str/starts-with? line "cloud-itonami-app listening "))
    (is (str/includes? line "at=2026-08-28T"))
    (is (str/includes? line "release=/Users/j/.cloud-itonami/releases/ffd0de9")
        "the release directory is how this install carries its commit")
    (is (str/includes? line "url=http://127.0.0.1:1338"))
    (is (= 1 (count (str/split-lines line)))
        "one line, because the log this joins is read by eye and by grep")))

(deftest a-stop-line-exists-and-carries-the-uptime
  ;; The half that did not exist. Without it a SIGTERM and a crash are the same
  ;; observation, which is what made 2026-08-28's non-deploy restart unattributable.
  (let [line (lifecycle/stopping-line 1787900072000 1787900000000 "/rel/ffd0de9")]
    (is (str/starts-with? line "cloud-itonami-app stopping "))
    (is (str/includes? line "uptime-seconds=72"))
    (is (str/includes? line "release=/rel/ffd0de9"))))

(deftest uptime-never-reads-as-negative
  (is (= 72 (lifecycle/uptime-seconds 1787900000000 1787900072000)))
  (is (= 0 (lifecycle/uptime-seconds 1787900000000 1787900000999))
      "a sub-second life is zero whole seconds, not rounded up")
  (testing "a clock that stepped backwards between the two readings"
    ;; Two separate wall-clock readings; a negative uptime in a log is a puzzle
    ;; nobody needs to solve.
    (is (= 0 (lifecycle/uptime-seconds 1787900072000 1787900000000)))))

(deftest a-field-that-could-not-be-determined-is-not-printed
  ;; `release` is absent on any layout that does not deploy from a directory,
  ;; and `release=nil` would read as a release literally named nil.
  (let [line (lifecycle/started-line 1787900000000 nil "http://127.0.0.1:1338")]
    (is (not (str/includes? line "release=")))
    (is (str/includes? line "url=http://127.0.0.1:1338"))
    (is (str/includes? line "at=")))
  (is (not (str/includes? (lifecycle/started-line 1787900000000 "   " "u")
                          "release="))
      "blank is as absent as nil"))

(deftest an-instant-renders-the-same-on-both-runtimes
  ;; To the millisecond. The two disagree on whether a trailing zero in the
  ;; fraction is printed, which is why this compares the prefix and not the
  ;; whole string -- and why nothing in this namespace parses one back.
  (is (str/starts-with? (lifecycle/iso 1787900000000) "2026-08-28T"))
  (is (str/ends-with? (lifecycle/iso 1787900000000) "Z"))
  (is (str/includes? (lifecycle/iso 1787900000123) ":20.123")
      "the millisecond survives, which is what pairs a stop line with its start"))
