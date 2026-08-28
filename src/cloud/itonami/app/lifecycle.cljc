(ns cloud.itonami.app.lifecycle
  "What one line in the process log SAYS, and nothing else.

  Zero dependencies, on the argument `host-bounds` already makes: the part that
  has been wrong in production is the judgement, not the plumbing, and a
  judgement that needs a resolved classpath to execute cannot be checked by the
  runtime that does not have one. Everything here is a pure function of its
  arguments -- the clock and the process live in the caller.

  ## Why this namespace exists at all

  The resident install's start banner was `cloud-itonami-app listening on
  http://host:port` and nothing more, so `StandardOutPath` accumulated
  identical lines with no timestamp. Measured 2026-08-28: 86 of them over 103.8
  hours -- a restart every 72 minutes on average -- and the file could not say
  when any single one happened, how long that process had lived, or which
  release it ran.

  That gap has a cost that was paid the same day. One of those restarts was a
  SIGTERM that created no release directory and rewrote no plist, so it was not
  a deploy. Establishing even that much took reading every agent transcript on
  the machine, and the sender was still not identified. A start line and a
  matching stop line would have made it two lines in a log.

  A stop line is the half that did not exist at all: the shutdown hook ran
  `stop!` and printed nothing, so a terminated process and a crashed one left
  the same evidence, which is none."
  (:require [clojure.string :as str]))

(def prefix "cloud-itonami-app")

(defn iso
  "EPOCH-MS as an ISO-8601 instant.

  The only host call in this namespace, and it is here rather than in the
  caller so a caller does not need a time class to write a log line. The two
  runtimes agree on the format to the millisecond; they disagree on whether a
  trailing zero in the fraction is printed, which no reader of this log cares
  about and no function here parses back."
  [epoch-ms]
  #?(:clj (str (java.time.Instant/ofEpochMilli (long epoch-ms)))
     :cljs (.toISOString (js/Date. epoch-ms))))

(defn line
  "One log line: an event, when it happened, and PAIRS of context.

  Pure, and separated from every caller, because what it SAYS is the part that
  matters. `nil` and blank values are dropped rather than printed as `nil=`,
  since a field that could not be determined is not a field -- and `release`
  cannot be determined on a layout that does not deploy from a directory."
  [event epoch-ms pairs]
  (->> (partition 2 pairs)
       (keep (fn [[k v]]
               (let [v (str/trim (str v))]
                 (when-not (str/blank? v) (str (name k) "=" v)))))
       (into [prefix (name event) (str "at=" (iso epoch-ms))])
       (str/join " ")))

(defn uptime-seconds
  "Whole seconds between two epoch millisecond readings, never negative.

  Clamped because these are two separate wall-clock readings and the clock can
  step backwards between them. A negative uptime in a log is a puzzle nobody
  needs to solve; zero is the honest floor."
  [started-ms stopped-ms]
  (max 0 (quot (- (long stopped-ms) (long started-ms)) 1000)))

(defn started-line
  "The line a process prints once it is serving."
  [started-ms release url]
  (line :listening started-ms [:release release :url url]))

(defn stopping-line
  "The line a process prints when something has asked it to stop.

  Carries the uptime because that is the number that makes a restart cadence
  measurable without correlating two lines by hand, and the release because a
  stop that names a different one from the start would mean a deploy replaced
  the process rather than something terminating it."
  [stopped-ms started-ms release]
  (line :stopping stopped-ms
        [:release release
         :uptime-seconds (uptime-seconds started-ms stopped-ms)]))
