(ns cloud.itonami.app.workforce
  "Read the governed startup-role registry that Cloud Itonami projects as Bots.

  `network-awai/loop-yakuwari` remains the role/capability source of truth. The
  resident app invokes its deterministic EDN projection at provisioning time;
  it never guesses roles from repo names and never treats a missing checkout
  as an empty workforce."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def schema "network.awai.workforce-bots.v1")
(def command-timeout-seconds 30)
(def skill-id-pattern #"^[a-z0-9]+(?:-[a-z0-9]+)*$")
(def skill-sha256-pattern #"^[0-9a-f]{64}$")
(def max-skill-instructions 12000)
(def founding-businesses
  "The eight businesses this app was built around. Required to be PRESENT, not
  required to be all there is.

  Pinning the exact set froze the org chart in a repo that does not own it:
  loop-yakuwari is the source of truth, and adding a business there made this
  gate reject the WHOLE catalog — including the eight that had not changed.
  That is the opposite of what the gate is for. Kept as a floor because these
  eight silently vanishing is a real failure a count alone would not catch."
  #{:cloud-itonami :nexus-x402 :club-shinshi :app-aozora
    :network-isekai :net-babiniku :cloud-murakumo :net-kotobase})

(defn workspace-root []
  (.getCanonicalFile
   (io/file (or (System/getenv "CLOUD_ITONAMI_WORKSPACE_ROOT")
                (System/getProperty "user.dir")))))

(defn registry-root []
  (.getCanonicalFile
   (io/file (workspace-root) "orgs/network-awai/loop-yakuwari")))

(defn- read-output! [process]
  ;; Drain concurrently: the complete 70-role catalog can exceed a platform
  ;; pipe buffer, while waiting before reading can deadlock the child.
  (let [output (future (slurp (.getInputStream process)))]
    (when-not (.waitFor process command-timeout-seconds
                        java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly process)
      (future-cancel output)
      (throw (ex-info "workforce projection timed out"
                      {:type :workforce/timeout})))
    (let [body @output]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "workforce projection failed"
                        {:type :workforce/command-failed
                         :exit (.exitValue process)
                         :detail (subs body 0 (min 2000 (count body)))})))
      body)))

(defn- nbb-command []
  (let [homebrew (io/file "/opt/homebrew/bin/nbb")]
    (if (.canExecute homebrew) (.getPath homebrew) "nbb")))

(defn load-catalog
  "Load and minimally validate one complete projection. No partial result is
  returned: provisioning half a company is worse than refusing visibly."
  []
  (let [root (registry-root)
        command (io/file root "bin/awai.cljs")]
    (when-not (.isFile command)
      (throw (ex-info "loop-yakuwari workforce registry is not checked out"
                      {:type :workforce/unavailable :path (.getPath command)})))
    (let [workspace (workspace-root)
          classpath (str (.getPath (io/file root "src"))
                         java.io.File/pathSeparator
                         (.getPath (io/file workspace "orgs/kotoba-lang/yakuwari/src"))
                         java.io.File/pathSeparator
                         (.getPath (io/file workspace "orgs/kotoba-lang/yakuwari-view/src")))
          process (-> (ProcessBuilder. [(nbb-command) "--config" "/dev/null"
                                        "--classpath" classpath
                                        "bin/awai.cljs" "workforce"])
                      (.directory root)
                      (.redirectErrorStream true)
                      (.start))
          value (try (edn/read-string (read-output! process))
                     (catch Exception error
                       (if (:type (ex-data error))
                         (throw error)
                         (throw (ex-info "workforce projection is not EDN"
                                         {:type :workforce/unreadable}
                                         error)))))]
      (let [roles (:roles value)
            keys* (map :key roles)
            valid-skill?
            (fn [package]
              (and (map? package)
                   (re-matches skill-id-pattern (str (:id package)))
                   (re-matches skill-sha256-pattern (str (:sha256 package)))
                   (string? (:instructions package))
                   (<= 1 (count (:instructions package)) max-skill-instructions)))
            businesses (into #{} (map #(get-in % [:business :id])) roles)]
        ;; Completeness is checked as INTERNAL AGREEMENT rather than against a
        ;; fixed number: the projection's own declared business count must
        ;; equal the number of businesses its roles actually cover. That is
        ;; what catches the failure this gate exists for — a truncated or
        ;; half-generated catalog — and it keeps catching it as the registry
        ;; grows. A literal 8 only ever caught "the registry changed".
        (when-not (and (= schema (:schema value))
                       (= (count businesses) (:businesses value))
                       (vector? roles)
                       (= (count keys*) (count (set keys*)))
                       (every? businesses founding-businesses)
                       (every? #(and (string? (:key %))
                                     (map? (:business %))
                                     (map? (:role %))
                                     (seq (str (:objective %)))
                                     (<= (count (:skills %)) 4)
                                     (= (count (:skills %))
                                        (count (set (map :id (:skills %)))))
                                     (every? valid-skill? (:skills %))
                                     (pos-int? (:cadence-minutes %)))
                               roles))
        (throw (ex-info "workforce projection failed its complete-catalog contract"
                        {:type :workforce/invalid-catalog
                         :schema (:schema value)
                         :businesses (:businesses value)
                         :business-ids businesses
                         :missing-founding (into (sorted-set)
                                                 (remove businesses founding-businesses))
                         :roles (count roles)}))))
      (assoc value :source {:path (.getPath root)}))))
