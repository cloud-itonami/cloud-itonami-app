;; Run only after fencing the source process and restart manager. Never loads app code.
(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.walk :as walk])
(import '[java.security MessageDigest] '[java.nio.file Files StandardCopyOption])
(defn sha [bytes] (.formatHex (java.util.HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn apply-op [s {:keys [op path value from]}]
  (case op
    :assoc (if (seq path) (assoc-in s path value) value)
    :dissoc (if (= 1 (count path)) (dissoc s (first path)) (update-in s (pop path) dissoc (peek path)))
    :append (let [v (if (seq path) (get-in s path) s)]
              (assert (and (vector? v) (vector? value) (integer? from) (<= 0 from)) "Invalid append")
              (cond (= (count v) from) (if (seq path) (update-in s path into value) (into s value))
                    (and (>= (count v) (+ from (count value))) (= value (subvec v from (+ from (count value))))) s
                    :else (throw (ex-info "Journal append mismatch" {:path path}))))
    (throw (ex-info "Unknown journal operation" {:op op}))))
(let [[source destination mappings-file] *command-line-args*
      _ (assert (and source destination mappings-file) "source destination mappings.edn required")
      target (io/file destination)
      _ (assert (not (.exists target)) "Destination must be new")
      raw (Files/readAllBytes (.toPath (io/file source "state.edn")))
      journal (slurp (io/file source "state.journal.edn"))
      _ (assert (or (empty? journal) (.endsWith journal "\n")) "Incomplete journal")
      records (mapv edn/read-string (remove empty? (.split journal "\n")))
      digest (sha raw)
      _ (doseq [r records] (assert (= "cloud.itonami.app.state-journal.v1" (:schema r)) "Journal schema mismatch")
                    (assert (vector? (:ops r)) "Invalid operations")
                    (doseq [op (:ops r)] (assert (vector? (:path op)) "Invalid operation path"))
                    (assert (= (alength raw) (:base-bytes r)) "Journal size mismatch")
                    (assert (= digest (:base-sha256 r)) "Journal digest mismatch"))
      state (reduce (fn [s r] (reduce apply-op s (:ops r))) (edn/read-string (String. raw "UTF-8")) records)
      mappings (sort-by (comp - count first) (edn/read-string (slurp mappings-file)))
      rewrite (fn [v] (or (some (fn [[a b]] (when (or (= v a) (.startsWith v (str a "/"))) (str b (subs v (count a))))) mappings) v))
      changed (atom 0)
      migrated (update-in state [:bots :bots]
                    (fn [bots] (into {} (for [[id bot] bots]
                      [id (if-let [v (:bot/workspace bot)]
                            (let [n (rewrite v)] (when (not= v n) (swap! changed inc))
                              (assoc bot :bot/workspace n)) bot)]))))
      held (atom [])
      migrated (update-in migrated [:bots :goal-jobs]
                    (fn [jobs] (into {} (for [[id j] jobs]
                        [id (if (contains? #{:queued :leased :running :checkpointed} (get-in j [:job/run :agent.run/status]))
                              (do (swap! held conj id) (-> j
                                  (assoc :job/controller-migration-review? true)
                                  (assoc-in [:job/run :agent.run/status] :held)
                                  (assoc-in [:job/run :agent.run/checkpoint-reason] :controller-migration-review))) j)]))))
      held-bots (set (keep #(get-in migrated [:bots :goal-jobs % :job/bot]) @held))
      migrated (update-in migrated [:bots :workforce-jobs]
                    (fn [jobs] (into {} (for [[id j] jobs]
                      [id (if (held-bots (:workforce.job/bot j))
                            (assoc j :workforce.job/enabled? false :workforce.job/disabled-reason :controller-migration-review) j)]))))
      config (edn/read-string (slurp (io/file source "config.edn")))
      config (-> config (assoc-in [:server :host] "127.0.0.1") (assoc-in [:server :port] 1438)
                 (assoc-in [:updates :enabled?] false)
                 (assoc-in [:work-governance :enabled?] false)
                 (assoc-in [:domain-binding :recheck?] false)
                 (assoc-in [:mail-sync :enabled?] false) (assoc-in [:folder-sync :enabled?] false))]
  (.mkdirs target)
  (spit (io/file target "state.edn") (pr-str migrated))
  (spit (io/file target "state.journal.edn") "")
  (spit (io/file target "config.edn") (pr-str config))
  (spit (io/file target "controller-migration.edn")
        (pr-str {:source-sha256 digest :source-journal-sha256 (sha (.getBytes journal "UTF-8"))
                 :bot-count (count (get-in migrated [:bots :bots])) :path-fields-updated @changed
                 :held-inflight-jobs @held :at (str (java.time.Instant/now))}))
  (prn {:bots (count (get-in migrated [:bots :bots])) :path-fields-updated @changed :held-inflight-count (count @held)}))
