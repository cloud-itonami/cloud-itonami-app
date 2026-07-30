(ns cloud.itonami.app.repos
  "The repositories a business is implemented in, and how mature they are.

  Phase 4 of ADR-2607309600. Read-only.

  Two generated files in the superproject describe every west-registered
  repository, and this namespace joins them on `:repo/path`:
  `manifest/repo-taxonomy.edn` (what kind of thing a repo is, from evidence
  inside it) and `manifest/repo-maturity.edn` (five 0.0-1.0 axes plus a
  composite). Both are generated; nothing here writes to either.

  ## An unscored axis is not a zero, and the average knows it

  `repo-maturity.edn` says so in its own header — 「0.0-1.0, nil when not
  computable -- no fabricated defaults」 — and it means it: `:maturity/stage-score`
  is nil for 2,732 of 3,899 repos, because most repos carry no stage marker for
  the generator to parse. Rendering that as 0.0 would put 70% of the fleet at the
  bottom of an axis nobody measured them on.

  So a nil axis stays nil here, and `roll-up` averages only over the repos that
  actually have the value, reporting how many it left out. An average that
  silently counts a missing score as zero is the most common way a maturity
  dashboard becomes a lie about work that was never assessed.

  ## A heuristic is labelled as one

  `:maturity/impl-score` is a proxy — `:maturity/impl-score-method` says
  `:size-and-scaffold-marker-heuristic` — and that method travels with the score.
  A number whose provenance is a heuristic and a number derived from a declared
  stage marker are not the same kind of claim, and a column of five identical-
  looking decimals hides which is which."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.operator :as operator]))

(def schema "cloud.itonami.app.repos.v1")

(def taxonomy-path "manifest/repo-taxonomy.edn")
(def maturity-path "manifest/repo-maturity.edn")

(def axes
  "The five axes, in the order `repo-maturity.edn` documents them. Presentation
  order is data so the pane and the generator cannot drift apart."
  [:stage :structural :activity :impl :coverage])

(def ^:private axis-labels
  {:stage "宣言された段階 (README/CLAUDE.md の stage marker)"
   :structural "構造 (README / LICENSE / tests / CLAUDE.md)"
   :activity "活動 (push 時刻の新しさ + commit 数)"
   :impl "実装量 (heuristic proxy)"
   :coverage "テスト網羅"})

(def ^:private unreadable ::unreadable)

(defn- read-plane [ws path]
  (when-let [root (:file ws)]
    (let [f (io/file root path)]
      (when (.isFile f)
        (try (filterv map? (edn/read-string (slurp f)))
             (catch Exception _ unreadable))))))

(defn- index-by-path [entities]
  (if (or (nil? entities) (identical? unreadable entities))
    entities
    (into {} (keep (fn [m] (when-let [p (:repo/path m)] [p m]))) entities)))

(defn- axis
  "One axis of one repo: its value, or the fact that it has none."
  [maturity a]
  (let [score (get maturity (keyword "maturity" (str (name a) "-score")))
        method (get maturity (keyword "maturity" (str (name a) "-score-method")))
        detail (get maturity (keyword "maturity" (str (name a) "-detail")))]
    (cond-> {:axis a :label (axis-labels a)
             :scored? (number? score)}
      (number? score) (assoc :score score)
      ;; The method rides with the score, so `impl`'s heuristic provenance cannot
      ;; be dropped on the way to a table of five decimals.
      method (assoc :method method)
      detail (assoc :detail detail)
      (not (number? score))
      (assoc :detail (or detail "この repo には計算できる根拠がありません（0 ではなく未評価）")))))

(defn- repo->wire [path {:keys [taxonomy maturity]}]
  (cond-> {:path path
           :present (some? (or taxonomy maturity))}
    taxonomy (assoc :name (:repo/name taxonomy)
                    :org (:repo/org taxonomy)
                    :kind (:repo/kind taxonomy)
                    :kind-evidence (:repo/kind-evidence taxonomy)
                    :traits (:repo/traits taxonomy))
    maturity (assoc :composite (:maturity/composite maturity)
                    :computed-at (:maturity/computed-at maturity)
                    :pinned-revision (:repo/pinned-revision maturity)
                    :axes (mapv #(axis maturity %) axes))))

(defn roll-up
  "The mean composite over the repos that HAVE one, and the count that did not.

  Two numbers rather than one, for the same reason `business/coverage` reports
  bound and resolved separately: a mean over 3 of 12 repos is a different claim
  from a mean over 12, and folding the other 9 in as zeros would produce a third
  number that is neither."
  [rows]
  (let [scored (filterv #(number? (:composite %)) rows)]
    {:repos (count rows)
     :scored (count scored)
     :unscored (- (count rows) (count scored))
     ;; nil, not 0, when nothing was scored — there is no mean of no numbers.
     :mean-composite (when (seq scored)
                       (/ (reduce + (map :composite scored)) (count scored)))}))

(defn- add-note
  "Append a reason to `:detail` instead of replacing it.

  Two reasons can be true at once — a blueprint absent from the fleet catalog AND
  undeclared, or a path absent from the generated plane on top of either — and
  `(cond-> m c (assoc :detail …))` twice keeps only the last. That overwrite
  shipped twice in this namespace before tests caught it, so the append is a
  function rather than a habit."
  [m note]
  (if (str/blank? (str note))
    m
    (update m :detail (fn [existing]
                        (if (str/blank? (str existing))
                          note
                          (str existing " / " note))))))

(defn- entries
  "The repositories this business claims, each labelled by which binding claimed
  it.

  `:business/repos` is a workspace path the owner named. `:business/adoptions` is
  a blueprint an operator declared they run — a stronger fact, carrying a stage
  and sometimes an endpoint. Its workspace path comes from the fleet catalog,
  because an adoption names a repository DIRECTORY and turning that into
  `orgs/<org>/<repo>` by hand would be guessing a path — the same guess `fleet`
  refuses to make about an address."
  [b]
  (into
   (mapv (fn [p] {:path p :source :repos}) (:business/repos b))
   (mapv (fn [repo]
           (let [a (operator/adoption repo)
                 actor (fleet/actor repo)]
             ;; Both facts can be true at once, and a `cond->` chain would let
             ;; the later `assoc :detail` overwrite the earlier one — which it
             ;; did, hiding 「catalog に無い」 behind 「参与が無い」. Collected
             ;; instead, so neither is lost.
             (cond-> {:repo repo :source :adoptions :path (:path actor)}
               a (assoc :stage (:adoption/stage a)
                        :endpoint (:adoption/endpoint a))
               (nil? actor)
               (add-note (str repo " は fleet catalog にありません。"
                              "workspace path が分からないので成熟度を引けません"))
               (nil? a) (add-note "参与が表明されていません"))))
         (:business/adoptions b))))

(defn- plane-state
  "Whether the two generated planes can be read at all, as one verdict.

  Both files are needed to say anything about a repo, so they get one state
  rather than two half-answers: taxonomy without maturity would render a kind
  with no scores and read as 「評価が 0」."
  [ws tax mat]
  (cond
    (not= :present (:state ws))
    {:state :unresolvable :detail (:detail ws)}

    (or (identical? unreadable tax) (identical? unreadable mat))
    {:state :unreadable
     :detail (str "generated plane を読めませんでした（" taxonomy-path " / "
                  maturity-path "）")}

    (or (nil? tax) (nil? mat))
    {:state :missing
     :detail (str "workspace に " taxonomy-path " または " maturity-path
                  " がありません。superproject で "
                  "`nbb scripts/repo-taxonomy.cljs` / `nbb scripts/repo-maturity.cljs` "
                  "を実行すると生成されます")}

    :else {:state :resolved}))

(defn snapshot
  "Every repository this business is implemented in, joined against the two
  generated planes on `:repo/path`."
  [configuration session business-id]
  (when-let [b (business/business session business-id)]
    (let [ws (business/workspace configuration)
          tax (index-by-path (read-plane ws taxonomy-path))
          mat (index-by-path (read-plane ws maturity-path))
          plane (plane-state ws tax mat)
          es (entries b)
          rows (if (= :resolved (:state plane))
                 (mapv (fn [e]
                         (let [p (:path e)
                               joined (repo->wire p {:taxonomy (get tax p)
                                                     :maturity (get mat p)})]
                           (cond-> (merge e joined)
                             (and p (nil? (get tax p)) (nil? (get mat p)))
                             (add-note (str p " は west 登録の生成 plane にありません")))))
                       es)
                 ;; No plane, so no scores — and no zeros standing in for them.
                 (mapv #(-> (assoc % :present false) (add-note (:detail plane))) es))]
      {:schema schema
       :business (select-keys b [:business/id :business/slug :business/name])
       :plane plane
       :sources {:taxonomy taxonomy-path :maturity maturity-path}
       :axes (mapv (fn [a] {:axis a :label (axis-labels a)}) axes)
       :repos rows
       :roll-up (roll-up rows)})))
