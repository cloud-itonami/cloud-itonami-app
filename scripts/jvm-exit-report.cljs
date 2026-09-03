#!/usr/bin/env nbb
;; How far this application still is from running without a JVM, measured.
;;
;;     nbb scripts/jvm-exit-report.cljs            (from the repository root)
;;     nbb scripts/jvm-exit-report.cljs --movable  (just the names to rename)
;;
;; ## Why this exists
;;
;; The runtime rule (ADR-0065, ADR-2608095000, ADR-0049, and the workspace
;; CLAUDE.md) says new code goes on ClojureScript or Kotoba and `:clj` is a
;; frozen compat layer. A PreToolUse hook enforces the "new" half. Nothing
;; measured the stock, so "how much is left" was answerable only by counting
;; files by extension — and that count is misleading in both directions:
;;
;;   - A `.clj` with no JVM interop in it is a `.clj` by extension only. That is
;;     the same defect `portable_nbb.cljs` names for tests: a file one runtime
;;     ever executes. Measured 2026-08-27, 24 of 152 were in this state.
;;   - But renaming one is not enough on its own. A namespace with no interop
;;     that requires a JVM-bound namespace is still JVM-bound, and renaming it
;;     produces a `.cljc` that ClojureScript cannot load — a file that claims
;;     portability and does not have it, which is worse than an honest `.clj`.
;;
;; So this walks the require graph and reports the fixpoint: which namespaces
;; are portable TODAY, and for the ones that are clean but blocked, which
;; dependency is holding them. The second list is the useful one. It turns
;; "port 152 files" into "unblock the handful of namespaces that everything
;; else waits on".
;;
;; ## What it does not claim
;;
;; The interop pattern below is a heuristic over source text, not a compiler.
;; It cannot see interop reached through a macro, and it does not read the
;; `.cljc` files' reader conditionals to check that the `:cljs` branch is real
;; rather than a `throw`. A namespace this reports as portable has passed a
;; text search, not an execution — which is exactly why `bin/test-portable-cljs`
;; exists and why a rename is only finished when a test runs there.

(ns jvm-exit-report
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.set :as set]
            [clojure.string :as str]))

(def interop-pattern
  ;; Host access that has no ClojureScript meaning. `Math/` and `String/` are
  ;; included even though cljs has its own spellings, because a `.clj` using
  ;; them does not compile as `.cljc` without a reader conditional — which is
  ;; the work this is trying to size, not hide.
  (js/RegExp. (str "\\(:import|\\bjava\\.|\\bjavax\\.|\\bcom\\.sun\\.|\\(proxy\\b"
                   "|\\bClass/|\\bThread/|\\bSystem/|\\bMath/|\\bInteger/|\\bLong/"
                   "|\\bDouble/|\\bString/|\\bArrays/|\\.getBytes|\\bclojure\\.lang\\.")
              "g"))

(defn- source-files [dir]
  (->> (fs/readdirSync dir #js {:withFileTypes true})
       (mapcat (fn [entry]
                 (let [full (path/join dir (.-name entry))]
                   (cond
                     (.isDirectory entry) (source-files full)
                     (re-find #"\.cljc?$" (.-name entry)) [full]
                     :else []))))))

(defn- describe [file]
  (let [text (fs/readFileSync file "utf8")
        ns-name (second (re-find #"\(ns\s+([\w.\-]+)" text))]
    (when ns-name
      {:ns ns-name
       :path file
       :extension (last (str/split file #"\."))
       :lines (count (str/split-lines text))
       ;; `.match` rather than `re-seq`: the pattern carries the global flag,
       ;; and a stateful RegExp shared across files is a source of answers that
       ;; depend on the order they were read in.
       :interop (count (or (.match text interop-pattern) #js []))
       :deps (set (map second (re-seq #"\[(cloud\.itonami\.app[\w.\-]*)" text)))
       ;; Required namespaces from OUTSIDE this repository. Tracked separately
       ;; because whether they load under ClojureScript is a fact about those
       ;; libraries, which this script cannot see and must not assume.
       :foreign (into #{}
                      (comp (map second)
                            (remove #(str/starts-with? % "cloud.itonami"))
                            (remove #(str/starts-with? % "clojure."))
                            (remove #(str/starts-with? % "cljs.")))
                      (re-seq #"\[([a-z][\w.\-]*\.[\w.\-]+)\s+:as" text))})))

(defn portable-closure
  "The namespaces that could load without a JVM, as a fixpoint.

  Seeded with what is already `.cljc`, then repeatedly admitting any `.clj`
  that has no interop and whose in-repository dependencies are all admitted.
  A namespace with any dependency outside this repository is NOT admitted.
  That is the correction to this script's first version, which ignored them and
  so reported four files as portable when only two would load: `local-query`
  needs `langchain.db` and `connectors` needs `connector.consent`, and whether
  those load under ClojureScript is a fact about those libraries that no text
  search here can see. They are reported separately as unmeasured rather than
  counted as either portable or blocked."
  [by-ns]
  (loop [portable (set (keep (fn [[n v]] (when (= "cljc" (:extension v)) n)) by-ns))]
    (let [next-set
          (into portable
                (keep (fn [[n v]]
                        (when (and (not (portable n))
                                   (zero? (:interop v))
                                   (empty? (:foreign v))
                                   (every? #(or (portable %) (not (by-ns %)))
                                           (:deps v)))
                          n)))
                by-ns)]
      (if (= next-set portable) portable (recur next-set)))))

(defn report [by-ns]
  (let [portable (portable-closure by-ns)
        clj (filter (comp #{"clj"} :extension val) by-ns)
        movable (->> clj (filter (comp portable key)) (map val) (sort-by :lines))
        foreign (->> clj
                     (filter (fn [[n v]] (and (zero? (:interop v))
                                              (seq (:foreign v))
                                              (not (portable n)))))
                     (map val)
                     (sort-by :lines))
        blocked (->> clj
                     (filter (fn [[n v]] (and (zero? (:interop v))
                                              (empty? (:foreign v))
                                              (not (portable n)))))
                     (map (fn [[n v]]
                            (assoc v :blockers
                                   (sort (filter #(and (by-ns %) (not (portable %)))
                                                 (:deps v))))))
                     (sort-by :lines))
        ;; What everything is waiting on, counted. This is the list that turns
        ;; the migration from a file count into a short queue of real work.
        hubs (->> blocked
                  (mapcat :blockers)
                  frequencies
                  (sort-by (comp - val)))]
    {:namespaces (count by-ns)
     :clj (count clj)
     :movable movable
     :blocked blocked
     :foreign foreign
     :hubs hubs}))

(defn -main [& args]
  (let [by-ns (into {} (keep (fn [f] (when-let [d (describe f)] [(:ns d) d]))
                             (source-files "src")))
        {:keys [namespaces clj movable blocked foreign hubs]} (report by-ns)]
    (if (some #{"--movable"} args)
      (doseq [m movable] (println (:path m)))
      (do
        (println "src namespaces:" namespaces " still .clj:" clj)
        (println)
        (println "portable today —" (count movable) "files,"
                 (reduce + (map :lines movable)) "lines:")
        (doseq [m movable] (println (str "  " (:lines m) "\t" (:path m))))
        (println)
        (println "clean but blocked by an in-repo namespace —" (count blocked)
                 "files," (reduce + (map :lines blocked)) "lines")
        (println)
        (println "clean but UNMEASURED (depend on a library outside this repo) —"
                 (count foreign) "files,"
                 (reduce + (map :lines foreign)) "lines:")
        (doseq [f (take 8 foreign)]
          (println (str "  " (:lines f) "\t" (:ns f)
                        "\n        needs: " (str/join ", " (sort (:foreign f))))))
        (println)
        (println "what they are waiting on:")
        (doseq [[hub n] hubs] (println (str "  " n " file(s)\t" hub)))
        (println)
        (println "A rename is finished when the namespace runs under"
                 "bin/test-portable-cljs, not when the extension changes.")))
    ;; Reporting only. This is a map of the work, and a gate that failed on a
    ;; number would just be re-baselined the first time somebody added a `.clj`
    ;; the rule already refuses at the hook.
    (js/process.exit 0)))

(-main)
