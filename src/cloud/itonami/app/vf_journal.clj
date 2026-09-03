(ns cloud.itonami.app.vf-journal
  "The Valueflows event journal (ADR-2609111230 slice 1).

  One organization's economic record as an append-only EDN journal of conforming
  EconomicEvents, folded through `valueflows.event/apply-event` into an
  inventory projection. What a bot's `kotoba://vf/<org>/event/append` capability
  authorizes is an append HERE — and admission is not execution: the event must
  conform, the fold must accept it, and the applied state root is recorded in
  the append's own line before the append is considered done.

  ## What this namespace does not do

  It does not decide who may append. Capability checking lives with the caller
  (`bot-authority` scopes + intent admission); this namespace is the journal
  itself and its arithmetic. It does not sync with other bots — readers open
  the same file. It does not calibrate the XMILE models; that is the
  journal-advance hook's job (later slice), and the journal only announces
  advances.

  ## File shape

  One EDN map per line (the same append-durable discipline the state journal
  uses), of one kind:

    {:vf.journal/kind :event     :vf.journal/entry-id … :vf.journal/org …
     :vf.journal/event   {… EconomicEvent …}
     :vf.journal/root    <inventory sha256 after the fold>
     :vf.journal/at      <ms>}

  Replay folds every event and CHECKS the recorded root; a journal whose
  replayed root disagrees with a recorded root is corrupt and refuses to load."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.host :as host]
            [valueflows.conform :as conform]
            [valueflows.event :as event]))

(def schema "cloud.itonami.app.vf-journal.v1")

(def ^:dynamic *journal-dir*
  "Where per-org journals live. Tests bind a temp dir; production resolves
   beside the app's data dir."
  nil)

(defn- journal-dir []
  (or *journal-dir*
      (io/file (config/data-dir) "vf-journals")))

(defn- org-journal-file [org]
  (io/file (journal-dir) (str (str/replace (name org) #"[^a-zA-Z0-9_-]" "_")
                              ".vf-journal.edn")))

(def ^:private journal-max-bytes 268435456)

(defn- journal-lines [org]
  (let [file (org-journal-file org)]
    (when (.isFile file)
      (->> (slurp file)
           str/split-lines
           (remove str/blank?)
           (mapv edn/read-string)))))

(defn- inventory-root
  "SHA-256 hex of the EDN serialization of an inventory — the journal's own
  tamper-evidence line. Collisions aside, two journals with the same root
  hold the same economic record."
  [inventory]
  (let [s (pr-str (sort-by first (seq inventory)))]
    (apply str (map #(format "%02x" %)
                    (.digest (java.security.MessageDigest/getInstance "SHA-256")
                             (.getBytes s java.nio.charset.StandardCharsets/UTF_8))))))

(defn root [inventory] (inventory-root inventory))

(defn load-inventory
  "Fold every :event line into the empty inventory. Root lines are CHECKED:
   after each event, the running root must match the root that line recorded,
   or the journal is corrupt and loading refuses. Returns the inventory."
  [org]
  (let [file (org-journal-file org)]
    (if-not (.isFile file)
      {}
      (loop [lines (journal-lines org), inventory {}]
        (if-not (seq lines)
          inventory
          (let [{:vf.journal/keys [kind event root]} (first lines)]
            (if (not= :event kind)
              (recur (rest lines) inventory)
              (let [fold (event/apply-event inventory event)]
                (when-not (:ok? fold)
                  (throw (ex-info "vf journal replay fold failed"
                                  {:type :vf-journal/replay-failed
                                   :file (.getPath file) :errors (:errors fold)})))
                (let [next-inv (:inventory fold)]
                  (when (and root (not= root (inventory-root next-inv)))
                    (throw (ex-info "vf journal replay root mismatch"
                                    {:type :vf-journal/root-mismatch
                                     :file (.getPath file)
                                     :recorded root
                                     :computed (inventory-root next-inv)})))
                  (recur (rest lines) next-inv))))))))))

(defn append-event!
  "Validate, fold, and append one EconomicEvent for ORG.

  The gate order is fixed and each failure is its own reason:
    1. `valueflows.conform/conform-event` — shape (missing quantities, a
       two-sided effect with no receiving side, a process misuse).
    2. `valueflows.event/apply-event` over the CURRENT inventory — arithmetic
       (an action whose effect the fold refuses on this inventory).
  Conformance failing and the fold failing are different stages, because an
  operator fixes them differently: the first is a bad payload, the second is a
  state this event does not fit.

  Returns
    {:appended? true  :entry-id … :root … :inventory …}
    {:appended? false :stage :conform | :fold :errors [...]}

  The journal line carries BOTH the event and the root AFTER the fold.
  Replay recomputes the fold and compares roots; a mismatch is corruption,
  not divergence to paper over."
  [org ev]
  (let [conform-result (conform/conform-event ev)]
    (if (not (:ok? conform-result))
      {:appended? false :stage :conform :errors (:errors conform-result)}
      (let [entry-id (str "vfe-" (java.util.UUID/randomUUID))
            file (org-journal-file org)
            inventory (load-inventory org)
            fold (event/apply-event inventory ev)]
        (if (not (:ok? fold))
          {:appended? false :stage :fold :errors (:errors fold)}
          (let [root (inventory-root (:inventory fold))
                line {:vf.journal/kind :event
                      :vf.journal/entry-id entry-id
                      :vf.journal/org (name org)
                      :vf.journal/event ev
                      :vf.journal/root root
                      :vf.journal/at (System/currentTimeMillis)}]
            (.mkdirs (.getParentFile file))
            (host/append-durable! (.getPath file) (str line "\n") journal-max-bytes)
            {:appended? true :entry-id entry-id :root root
             :inventory (:inventory fold)}))))))

(defn events
  "The journal's event lines, oldest first, as plain maps."
  [org]
  (->> (journal-lines org)
       (filter #(= :event (:vf.journal/kind %)))
       (mapv #(select-keys % [:vf.journal/entry-id :vf.journal/event
                              :vf.journal/root :vf.journal/at]))))

(defn status
  "A reader's one-call view: event count, current root, and the last entry id.
  What an observer bot reads when asked where the org's economy stands."
  [org]
  (let [evs (events org)
        inventory (load-inventory org)]
    {:schema schema
     :org (name org)
     :events (count evs)
     :root (inventory-root inventory)
     :last-entry-id (:vf.journal/entry-id (last evs))
     :last-at (:vf.journal/at (last evs))}))
