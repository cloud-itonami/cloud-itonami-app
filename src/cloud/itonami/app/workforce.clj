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
(def expected-businesses
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
            businesses (into #{} (map #(get-in % [:business :id])) roles)]
        (when-not (and (= schema (:schema value))
                       (= 8 (:businesses value))
                       (vector? roles)
                       (= (count keys*) (count (set keys*)))
                       (= expected-businesses businesses)
                       (every? #(and (string? (:key %))
                                     (map? (:business %))
                                     (map? (:role %))
                                     (seq (str (:objective %)))
                                     (pos-int? (:cadence-minutes %)))
                               roles))
        (throw (ex-info "workforce projection failed its complete-catalog contract"
                        {:type :workforce/invalid-catalog
                         :schema (:schema value)
                         :businesses (:businesses value)
                         :business-ids businesses
                         :roles (count roles)}))))
      (assoc value :source {:path (.getPath root)}))))
