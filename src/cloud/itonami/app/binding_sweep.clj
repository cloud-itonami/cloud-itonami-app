(ns cloud.itonami.app.binding-sweep
  "Re-measuring what a tenant has proven, on an interval.

  Two authorities, one timer. `domain-verification` proves a NAME (a TXT record
  plus this process answering at it) and `mail-domain-authority` proves a mail
  POSTURE (SPF, DKIM, DMARC). Both are statements about somebody else's DNS,
  both can stop being true without anybody here doing anything, and both are
  taken back rather than carried when they do.

  They are swept together because the timer is the only thing they share; the
  measurements, the states and the consequences all stay in their own
  namespaces. This file exists so that neither of them has to know the other
  runs, and so a deployment gets one executor rather than two.

  ADR-0043's first draft said this application had no scheduler to hang this on.
  It was wrong — `updater`, `mail-sync`, `folder-sync` and `work-reconciler` all
  run one from `server/start!`, and this follows `updater` exactly. The claim
  was written from memory instead of from the source."
  (:require [cloud.itonami.app.domain-verification :as naming]
            [cloud.itonami.app.mail-domain-authority :as mail-authority])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defonce ^:private scheduler (atom nil))

(defn sweep!
  "Re-measure both authorities once. Returns what each of them saw.

  The two counts are reported separately and neither is summed away: a sweep
  that measured four names and no mail domains is a different fact from one that
  measured two of each, and an operator reading `8` cannot tell them apart."
  [configuration]
  {:naming (naming/recheck-all! configuration)
   :mail (mail-authority/recheck-all!)})

(defn start!
  "Run `sweep!` on an interval.

  Costs nothing on a deployment with nothing proven: both sweeps visit only
  records that have already passed their gates, so a store with none makes no
  DNS query and no outbound request at all."
  [configuration]
  (when (and (get-in configuration [:domain-binding :recheck?] true)
             (nil? @scheduler))
    (let [service (Executors/newSingleThreadScheduledExecutor)
          interval-minutes (* 60 (long (get-in configuration
                                               [:domain-binding :interval-hours]
                                               12)))
          initial-delay (long (get-in configuration
                                      [:domain-binding :initial-delay-minutes]
                                      15))]
      (.scheduleWithFixedDelay
       ^ScheduledExecutorService service
       ^Runnable (fn []
                   (try
                     (sweep! configuration)
                     ;; A scheduled task that throws is cancelled by the
                     ;; executor and never runs again — the failure where the
                     ;; sweep looks configured and has been dead for weeks.
                     ;; Swallowed HERE and only here; both sweeps already keep
                     ;; one record's failure from stopping the others.
                     (catch Throwable _ nil)))
       initial-delay interval-minutes TimeUnit/MINUTES)
      (reset! scheduler service)))
  {:running? (some? @scheduler)})

(defn stop! []
  (when-let [^ScheduledExecutorService service @scheduler]
    (.shutdownNow service)
    (reset! scheduler nil)))
