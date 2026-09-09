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

(def dependency-floor
  "Sibling source roots the projection needs whatever the registry declares.

  A floor, not the answer: `registry-dependencies` adds whatever
  loop-yakuwari's `nbb.edn` declares on top, because a hand-kept list here is a
  list that drifts. It drifted on 2026-09-08 -- the workspace-wide
  clojure.string retirement (loop-yakuwari e3f6024) put `kotoba.lang.text` into
  `src/awai/*.cljc` and `bin/awai.cljs`, this side did not learn it, and every
  provision after that died on \"Could not find namespace: kotoba.lang.text\".
  Registry edits stopped reaching running Bots and nothing said so until
  somebody tried to add a business."
  ["orgs/kotoba-lang/yakuwari/src"
   "orgs/kotoba-lang/yakuwari-view/src"
   "orgs/kotoba-lang/text/src"])

(def ^:private sibling-root-pattern
  ;; `nbb.edn` addresses siblings from inside the checkout: "../../<org>/<repo>"
  ;; is `orgs/<org>/<repo>` seen from the workspace root. Anything else -- a git
  ;; coordinate, an absolute path, a deeper relative one -- is left to the floor
  ;; rather than guessed at.
  #"^\.\./\.\./([^/]+)/([^/]+)$")

(defn registry-dependencies
  "The sibling source roots to put on the projection's classpath.

  `dependency-floor` UNION whatever the registry's own `nbb.edn` declares, so
  adding a dependency there is the only edit a registry change needs. The
  projection still runs with `--config /dev/null` and an explicit
  `--classpath`: this reads what the REPOSITORY declares, which is not the same
  as letting nbb resolve an operator's user-level config.

  Union rather than \"declared if any, else floor\", and the difference is the
  bug this fixed. On 2026-09-09 the registry required `kotoba.lang.text` in its
  sources while its `nbb.edn` still declared only two siblings: a declaration
  that is present but incomplete would have silently replaced the floor and
  left provisioning exactly as broken.

  An unreadable or absent `nbb.edn` contributes nothing rather than throwing --
  that shape is not this repository's to diagnose, and it should not turn
  provisioning into a parse error."
  [root]
  (let [file (io/file root "nbb.edn")
        declared (when (.isFile file)
                   (try
                     (->> (:deps (edn/read-string (slurp file)))
                          vals
                          (keep :local/root)
                          (keep #(when-let [[_ org repo]
                                            (re-matches sibling-root-pattern (str %))]
                                   (str "orgs/" org "/" repo "/src")))
                          distinct
                          vec)
                     (catch Exception _ nil)))]
    (vec (distinct (concat dependency-floor declared)))))

(defn missing-dependencies
  "Those of `dependencies` that are not directories under `workspace`."
  [workspace dependencies]
  (vec (remove #(.isDirectory (io/file workspace %)) dependencies)))

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
          dependencies (registry-dependencies root)
          missing (missing-dependencies workspace dependencies)
          ;; Name the checkout that is not there. Letting nbb say it instead
          ;; buries the sentence in a subprocess dump behind
          ;; `:workforce/command-failed`, and a dependency that is absent is
          ;; the same class of fact as the registry itself being absent -- so
          ;; it gets the same type.
          _ (when (seq missing)
              (throw (ex-info "workforce registry dependency is not checked out"
                              {:type :workforce/unavailable
                               :missing (mapv #(.getPath (io/file workspace %))
                                              missing)})))
          classpath (apply str
                           (interpose java.io.File/pathSeparator
                                      (cons (.getPath (io/file root "src"))
                                            (map #(.getPath (io/file workspace %))
                                                 dependencies))))
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
