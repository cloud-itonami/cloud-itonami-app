(ns cloud.itonami.app.portfolio
  "Every business as one row across every plane — the matrix ADR-2607309600
  decision 7 describes.

  Read-only, and the last piece of that decision to become possible: the columns
  needed the four panes to exist first. Canvas, Loops, Repos, Metrics and the
  maturity score each answer for one business; this namespace asks all of them
  for all businesses and puts the answers in one grid.

  ## Every cell says which kind of nothing it is

  There are four, and collapsing them is the failure this whole plane was built
  to avoid:

  | state | means | fix |
  |---|---|---|
  | `:unbound` | this business never named that face | bind it in Portfolio |
  | `:unresolvable` | it named one, but no workspace checkout to read | set `:workspace-root` |
  | `:missing` | resolvable, and not there | generate it / correct the binding |
  | `:measured` | a real value, shown | — |

  A fifth, `:stale`, exists only for metrics, because only metrics carries a date
  it can be late against.

  ## Cost is a design constraint here, not a footnote

  The repository planes are 4.8 MB of EDN together and the matrix asks about
  every business at once, so they are read once per request rather than once per
  business (`repos/planes`). The XMILE models are re-run per business — including
  the sensitivity sweep, which is one extra run per constant — so the matrix is
  NOT part of `/api/business`, which loads at startup. It is its own endpoint,
  fetched when somebody opens the pane."
  (:require [cloud.itonami.app.business :as business]
            [cloud.itonami.app.canvas :as canvas]
            [cloud.itonami.app.loops :as loops]
            [cloud.itonami.app.metrics :as metrics]
            [cloud.itonami.app.repos :as repos]))

(def schema "cloud.itonami.app.portfolio.v1")

(def columns
  "The matrix's columns, as data so the pane cannot drift from the server."
  [{:key :canvas :label "Canvas" :detail "block と仮説（fold 済み投影）"}
   {:key :gate :label "riskiest gate" :detail "最も危険な仮説の測定状態"}
   {:key :maturity :label "成熟度" :detail "BMC スコアと未記録の次元数"}
   {:key :loops :label "Loops" :detail "モデルが走るか、どの定数が一番効くか"}
   {:key :repos :label "Repos" :detail "評価済み repo の composite 平均"}
   {:key :metrics :label "実測" :detail "鮮度と直近の uniques"}])

(defn- cell
  "One cell. `:state` is always present; `:value` only when measured."
  ([state detail] {:state state :detail detail})
  ([state detail value] {:state state :detail detail :value value}))

(defn- canvas-cell [c]
  (case (:state c)
    :resolved (cell :measured
                    (str (count (:blocks c)) " block · "
                         (count (:hypotheses c)) " 仮説")
                    (count (:blocks c)))
    :unbound (cell :unbound (:detail c))
    :unresolvable (cell :unresolvable (:detail c))
    (cell :missing (:detail c))))

(defn- gate-cell
  "The riskiest hypothesis's measured state.

  Not the canvas's own `:hyp/status` — that is what the ledger says — but
  `:gate-status`, what the metrics say. A canvas that marked no hypothesis
  riskiest gets `:unbound` rather than an arbitrary one promoted."
  [c]
  (if (not= :resolved (:state c))
    (cell (if (= :unbound (:state c)) :unbound :missing) (:detail c))
    (let [risk (first (filter #(= (:riskiest-hyp c) (:id %)) (:hypotheses c)))]
      (cond
        (nil? risk) (cell :unbound "riskiest と印された仮説がありません")
        (nil? (:gate-status risk))
        (cell :missing (str (:id risk) " — gate spec か metrics がありません（未測定）"))
        :else (cell :measured
                    (str (:id risk) " — " (name (:gate-status risk))
                         (when (:gate-distance risk) (str " / " (:gate-distance risk))))
                    (name (:gate-status risk)))))))

(defn- maturity-cell [m]
  (if (= :resolved (:state m))
    (cell :measured
          (str (when (number? (:bmc m)) (format "BMC %.1f" (:bmc m)))
               (when (pos? (or (:unrecorded-dims m) 0))
                 (str " · 未記録 " (:unrecorded-dims m) " 次元")))
          (:bmc m))
    (cell (or (#{:unbound :unresolvable} (:state m)) :missing) (:detail m))))

(defn- top-lever
  "The constant with the largest absolute elasticity on any stock.

  Disconnected constants are excluded rather than ranked at 0: 「繋がっていない」
  is not a weak lever, it is not a lever."
  [sens]
  (when (= :computed (:state sens))
    (->> (:parameters sens)
         (filter :connected?)
         (keep (fn [p]
                 (when-some [best (->> (:effects p)
                                       (filter #(= :computed (:state %)))
                                       (sort-by (comp - abs :value))
                                       first)]
                   {:name (:name p) :outcome (:outcome best) :value (:value best)})))
         (sort-by (comp - abs :value))
         first)))

(defn- loops-cell [m]
  (cond
    (not= :resolved (:state m))
    (cell (or (#{:unbound :unresolvable} (:state m)) :missing) (:detail m))

    (not= :simulated (:state (:trajectory m)))
    ;; The model is bound and readable; it is the RUN that failed, which is a
    ;; different fix from a missing file.
    (cell :missing (str "シミュレーションできません: " (:reason (:trajectory m))))

    :else
    (if-some [lever (top-lever (:sensitivity m))]
      (cell :measured
            (format "%s → %s %+.2f" (:name lever) (:outcome lever) (:value lever))
            (:name lever))
      (cell :measured (str (:steps (:trajectory m)) " step（効く定数なし）")
            (:steps (:trajectory m))))))

(defn- repos-cell [r]
  (let [roll (:roll-up r)
        plane (:plane r)]
    (cond
      ;; Ordered before the plane check on purpose. A business that named no
      ;; repositories is `:unbound` whether or not a workspace is configured —
      ;; the plane only matters once there is something to look up, and reporting
      ;; 「解析不能」 for a lookup nobody asked for points at the wrong fix.
      ;; A test caught this the other way round.
      (zero? (:repos roll)) (cell :unbound "repo / 参与が紐付いていません")

      (not= :resolved (:state plane))
      (cell (or (#{:unresolvable} (:state plane)) :missing) (:detail plane))

      ;; nil, not 0: a mean over no scored repos is not a low mean.
      (nil? (:mean-composite roll))
      (cell :missing (str (:repos roll) " repo すべて未評価"))

      :else
      (cell :measured
            (format "%.2f（評価済み %d / %d）" (:mean-composite roll)
                    (:scored roll) (:repos roll))
            (:mean-composite roll)))))

(defn- metrics-cell [m]
  (let [state (:state m)
        f (:freshness m)
        t (:traffic m)]
    (cond
      (not= :resolved state)
      (cell (or (#{:unbound :unresolvable} state) :missing) (:detail m))

      ;; The only column with a fifth state, because it is the only one carrying
      ;; a date it can be late against.
      (= :stale (:state f))
      (cell :stale (format "%s（%.1f 日前 / 上限 %d 日）" (:as-of f)
                           (:age-days f) (:max-age-days f))
            (:uniques-7d t))

      (= :undated (:state f)) (cell :missing (:detail f))

      :else
      (cell :measured
            (str (:as-of f)
                 (when (:uniques-7d t) (str " · uniques/7d " (:uniques-7d t)))
                 (when (:caveat t) " ⚠"))
            (:uniques-7d t)))))

(defn matrix
  "Every business in this session's organization, as one row per business.

  `now` is injectable so a test can pin the metrics staleness boundary."
  ([configuration session] (matrix configuration session (java.time.Instant/now)))
  ([configuration session now]
   (let [businesses (business/businesses session)
         ;; Read once, not once per business — 4.8 MB between the two files.
         planes (repos/planes configuration)
         rows
         (mapv (fn [b]
                 (let [id (:business/id b)
                       c (canvas/canvas configuration b)
                       mt (canvas/maturity configuration (:business/canvas b))
                       lm (loops/model configuration b)
                       rp (repos/snapshot-with planes session id)
                       mx (metrics/snapshot configuration session id now)]
                   {:business (select-keys b [:business/id :business/slug
                                              :business/name :business/canvas])
                    :cells {:canvas (canvas-cell c)
                            :gate (gate-cell c)
                            :maturity (maturity-cell mt)
                            :loops (loops-cell lm)
                            :repos (repos-cell rp)
                            :metrics (metrics-cell mx)}}))
               businesses)
         states (frequencies (mapcat (fn [r] (map :state (vals (:cells r)))) rows))]
     {:schema schema
      :workspace (business/workspace configuration)
      :columns columns
      :businesses rows
      ;; The count of each kind of nothing, so 「まだ何も測れていない」 is visible
      ;; as a number rather than as a screen of grey cells.
      :counts (merge {:businesses (count rows)
                      :cells (* (count rows) (count columns))}
                     states)})))
