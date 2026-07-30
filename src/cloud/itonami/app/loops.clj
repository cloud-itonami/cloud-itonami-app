(ns cloud.itonami.app.loops
  "The stock-flow structure of a business, simulated, and where intervening pays.

  Phase 3 of ADR-2607309600. Read-only: there is no write path here at all.

  ## The simulator is not in this file

  `xmile.execute/run` is the simulator — OASIS XMILE 1.0, Euler or RK4, and
  ADR-2607072350 makes it the authoritative system-dynamics engine for this
  workspace. This namespace parses the XML into the shape `xmile.xml/parse-doc`
  expects, hands the model over, and renders what comes back. It integrates
  nothing itself. `dynamics.core` likewise owns the Meadows band vocabulary; the
  band weights and labels are read from it rather than restated.

  ## What it refuses to produce

  **A trajectory it could not compute.** `run` throws when a model is
  array-dimensioned, declares an unsupported method, or has no sim-specs to run
  under. Each becomes `:unsimulatable` carrying the engine's own message. An
  empty series would read as 「シミュレーションした結果、全部ゼロ」, which is a
  different and false claim.

  **A structural strength score from guessed inputs.**
  `dynamics.core/loop-structural-strength` returns nil rather than a number when
  cycle time was never observed, and that nil is carried through as
  `:uncomputable-until-measured`. The four inputs it needs (cycle time,
  self-funding, instrumentation, friction) are not in any ledger this app reads,
  so for today's ledgers the answer is that it cannot be computed — not zero, and
  not a plausible-looking figure.

  ## What the ledgers actually model, said out loud

  The leverage ledgers under `loop-system-dynamics/ledger/` are real, dated
  output. They are also, today, models of the FLEET'S OWN REGISTRATION BACKLOG —
  how fast repositories get registered — not of a business's economics: no
  revenue, no customers, no cash. ADR-2607309600 requires that limit to be
  visible in the UI rather than left for a reader to infer from intervention
  names, so `:models-what` says it on every response."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.business :as business]
            [dynamics.core :as dynamics]
            [xmile.execute :as execute]
            [xmile.model :as xmodel]
            [xmile.xml :as xxml]
            [clojure.xml :as cxml])
  (:import [java.io ByteArrayInputStream]
           [javax.xml.parsers SAXParserFactory]))

(def schema "cloud.itonami.app.loops.v1")

(def ledger-caveat
  (str "この ledger は fleet の repo 登録 backlog を模型化したものです。"
       "事業の経済（売上・顧客・現金）はまだ模型化されていません。"))

;; ---------------------------------------------------------------------------
;; XMILE
;; ---------------------------------------------------------------------------

(defn- parse-xml
  "Parse XMILE text into the `{:tag :attrs :content}` shape `xmile.xml` reads.

  DOCTYPE declarations are refused. The file comes from the owner's own checkout
  rather than from the network, so this is a floor and not a threat model — but a
  parser that resolves external entities is one hostile file away from reading
  the filesystem, and nothing here needs that capability."
  [^String text]
  (let [factory (doto (SAXParserFactory/newInstance)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))]
    (cxml/parse (ByteArrayInputStream. (.getBytes text "UTF-8"))
                (fn [source handler]
                  (.parse (.newSAXParser factory) source handler)))))

(defn- runnable-model
  "One `<model>` with sim-specs resolved.

  XMILE 1.0 lets a model carry its own `<sim_specs>` and otherwise inherit the
  document's. That resolution is the host's job and is two lines of the spec, not
  a piece of the simulator."
  [doc model]
  (cond-> model
    (nil? (:xmile/sim-specs model))
    (assoc :xmile/sim-specs (:xmile/sim-specs doc))))

(defn- read-model [ws path]
  (when-let [root (:file ws)]
    (let [f (io/file root path)]
      (when (.isFile f)
        (try (xxml/parse-doc (parse-xml (slurp f)))
             (catch Exception e {::error (.getMessage e)}))))))

(defn- variable->wire
  "One variable, re-keyed for the wire.

  Namespaces are dropped on the way out, and `:xmile/name`/`:xmile/kind`/
  `:xmile/units` do not collide once stripped — but the eqn does not travel
  verbatim: it can be a long expression and the pane shows structure, not source."
  [m v]
  (let [nm (:xmile/name v)]
    (cond-> {:name nm
             :kind (:xmile/kind v)
             :units (:xmile/units v)}
      (xmodel/stock? v)
      (assoc :inflows (vec (sort (xmodel/inflows-of m nm)))
             :outflows (vec (sort (xmodel/outflows-of m nm)))))))

(defn- structure [m]
  (let [vs (xmodel/variables m)]
    {:stocks (mapv #(variable->wire m %) (sort-by :xmile/name (filter xmodel/stock? vs)))
     :flows (mapv #(variable->wire m %) (sort-by :xmile/name (filter xmodel/flow? vs)))
     :auxes (mapv #(variable->wire m %) (sort-by :xmile/name (filter xmodel/aux? vs)))}))

(defn- trajectory
  "The simulated run, or why there is not one.

  `run`'s own exception message is carried through rather than replaced: it names
  the variable that is array-dimensioned or the method that is unsupported, and
  that is more use than 「実行できません」."
  [m]
  (try
    (let [{:keys [xmile/times xmile/series]} (execute/run m)]
      {:state :simulated
       :times (vec times)
       :series (into {} (map (fn [[k v]] [k (vec v)])) series)
       :steps (count times)})
    (catch Exception e
      {:state :unsimulatable :reason (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; sensitivity — where intervening actually moves the outcome
;; ---------------------------------------------------------------------------

(def default-perturbation
  "How far each parameter is nudged. 10%: large enough that a linear effect
  clears floating-point noise, small enough to stay near the operating point the
  measurements describe."
  0.10)

(defn- constant-eqn
  "The number, when a variable's equation is a bare literal.

  Only leaf constants are perturbed. Nudging a variable whose equation references
  other variables would overwrite a computed value with a fixed one — that is not
  a sensitivity, it is a different model."
  [v]
  (let [s (some-> (:xmile/eqn v) str str/trim)]
    (when (seq s)
      (try (Double/parseDouble s) (catch Exception _ nil)))))

(defn- parameters
  "Every leaf constant in the model, with the kind of thing it is.

  A stock's `<eqn>` is its initial value, so stocks are parameters too — and
  often the interesting ones, since 「もし顧客が 10% 多かったら」 is a question
  about an initial condition."
  [m]
  (->> (xmodel/variables m)
       (keep (fn [v]
               (when-some [c (constant-eqn v)]
                 {:name (:xmile/name v)
                  :kind (:xmile/kind v)
                  :units (:xmile/units v)
                  :baseline c})))
       (sort-by :name)
       vec))

(defn- referenced-by
  "Every variable whose equation mentions `nm`.

  An elasticity of exactly 0 is ambiguous: it can mean 「動かしても効かない」 or
  「そもそも繋がっていない」, and those call for different responses. The second
  is decidable from the model's own text, so it is decided rather than left to
  the reader — `Weekly_Human_Uniques` in the cloud-itonami funnel is carried as
  observed context and is deliberately not wired into signup, which its own
  `<doc>` explains and a bare 0.0000 would hide.

  Word-boundary matched, so `Paying_Tenants` is not read as a reference by
  `Non_Paying_Tenants`."
  [m nm]
  (let [pattern (re-pattern (str "(?<![A-Za-z0-9_])" (java.util.regex.Pattern/quote nm)
                                 "(?![A-Za-z0-9_])"))]
    (->> (xmodel/variables m)
         (remove #(= nm (:xmile/name %)))
         (filter (fn [v]
                   (or (re-find pattern (str (:xmile/eqn v)))
                       ;; A stock names its flows structurally, not in its eqn.
                       (contains? (:xmile/inflows v #{}) nm)
                       (contains? (:xmile/outflows v #{}) nm))))
         (mapv :xmile/name)
         sort
         vec)))

(defn- final-values [m]
  (let [{:keys [xmile/series]} (execute/run m)]
    (into {} (map (fn [[k vs]] [k (last vs)])) series)))

(defn- elasticity
  "Percent change in the outcome per percent change in the parameter.

  Dimensionless on purpose: it is the only way to compare a parameter in
  `tenants/day` against one in `days` — the same reason the trajectory panes
  refuse a shared y-axis for variables with different units.

  `:undefined` when the baseline outcome is 0. A percent change of zero is not a
  number, and reporting 0 there would say 「この介入は効かない」 about an outcome
  that simply has no scale yet — which is exactly the case a funnel with no
  paying customers is in."
  [base-out new-out base-param delta-fraction]
  (cond
    (or (nil? base-out) (nil? new-out)) {:state :undefined :reason :no-outcome}
    (zero? base-param) {:state :undefined :reason :zero-parameter}
    (zero? base-out) {:state :undefined :reason :zero-baseline-outcome
                      :absolute-change (- new-out base-out)}
    :else {:state :computed
           :value (/ (/ (- new-out base-out) base-out) delta-fraction)
           :absolute-change (- new-out base-out)}))

(defn sensitivity
  "How much each leaf constant moves each stock's final value.

  This is the honest answer to 「どこに介入すれば効くか」 for a model whose
  intervention tractability nobody has scored: it is measured out of the model
  itself by re-running it, not judged. `dynamics.core/leverage-score` needs a
  tractability in [0,1] per intervention, and inventing those would put a guessed
  number at the centre of the ranking.

  Local by construction, and says so: an elasticity is the response at THIS
  operating point to THIS perturbation, and a model with saturation or a
  non-negative floor will answer differently elsewhere."
  ([m] (sensitivity m default-perturbation))
  ([m delta]
   (try
     (let [base (final-values m)
           outcomes (mapv :xmile/name (filter xmodel/stock? (xmodel/variables m)))
           params (parameters m)]
       {:state :computed
        :perturbation delta
        :note (str "各定数を +" (int (* 100 delta)) "% した再実行との比較。"
                   "弾力性はこの運転点での局所的な応答であって、全域の性質ではありません")
        :outcomes outcomes
        :parameters
        (mapv (fn [{:keys [name baseline] :as p}]
                (let [bumped (assoc-in m [:xmile/variables name :xmile/eqn]
                                       (str (* baseline (+ 1.0 delta))))
                      after (final-values bumped)
                      refs (referenced-by m name)]
                  (assoc p
                         :referenced-by refs
                         :connected? (boolean (seq refs))
                         :detail (when (empty? refs)
                                   (str name " はモデル内のどの式からも参照されていません。"
                                        "効果 0 は「効かない」ではなく「繋がっていない」です"))
                         :effects
                         (mapv (fn [o]
                                 (assoc (elasticity (get base o) (get after o)
                                                    baseline delta)
                                        :outcome o
                                        :baseline-outcome (get base o)))
                               outcomes))))
              params)})
     (catch Exception e
       {:state :unsimulatable :reason (.getMessage e)}))))

(defn model
  "The XMILE model bound to this business, simulated, or the reason there is none.

  `:state` mirrors the face states in `business`: `:unbound`, `:unresolvable`
  (a path, but no workspace checkout), `:missing`, `:unreadable`, `:resolved`."
  [configuration b]
  (let [ws (business/workspace configuration)
        path (:business/model b)]
    (cond
      (nil? path)
      {:schema schema :state :unbound
       :detail "この事業には XMILE モデルが紐付いていません"}

      (not= :present (:state ws))
      {:schema schema :state :unresolvable :source path :detail (:detail ws)}

      :else
      (let [doc (read-model ws path)]
        (cond
          (nil? doc)
          {:schema schema :state :missing :source path
           :detail (str path " が workspace にありません")}

          (::error doc)
          {:schema schema :state :unreadable :source path
           :detail (str "XMILE として読めませんでした: " (::error doc))}

          (empty? (:xmile/models doc))
          {:schema schema :state :unreadable :source path
           :detail "この XMILE には <model> がありません"}

          :else
          (let [models (:xmile/models doc)
                m (runnable-model doc (first models))]
            {:schema schema :state :resolved :source path
             :name (or (:xmile/name m) (get-in doc [:xmile/header :xmile/name]))
             ;; Which model was run, and how many were not. Picking the first is
             ;; a choice, so it is reported instead of hidden.
             :models (mapv #(or (:xmile/name %) "(無名)") models)
             :simulated-model (or (:xmile/name m) "(無名)")
             :sim-specs (:xmile/sim-specs m)
             :structure (structure m)
             :trajectory (trajectory m)
             :sensitivity (sensitivity m)}))))))

;; ---------------------------------------------------------------------------
;; leverage
;; ---------------------------------------------------------------------------

(defn bands
  "Meadows' bands as `dynamics.core` defines them, for the UI's legend. Read from
  the library rather than restated, so a change to the weights cannot leave this
  app describing the old ones."
  []
  (mapv (fn [[band {:keys [label weight tiers]}]]
          {:band band :label label :weight weight :tiers (vec tiers)})
        (sort-by (comp - :weight val) dynamics/meadows-bands)))

(defn- ledger-events
  "Every EDN map in an append-only ledger, in file order. One form per line, so a
  single corrupt line is skipped and counted rather than losing the file."
  [f]
  (reduce (fn [acc line]
            (let [t (str/trim line)]
              (if (or (empty? t) (str/starts-with? t ";"))
                acc
                (try (update acc :events conj (edn/read-string t))
                     (catch Exception _ (update acc :skipped inc))))))
          {:events [] :skipped 0}
          (str/split-lines (slurp f))))

(defn- strength
  "`dynamics.core/loop-structural-strength`, or the reason it is not a number.

  nil is not converted to 0. A loop whose cycle time was never observed has no
  strength score, and one computed from a guessed cycle time would be fiction —
  the library returns nil for exactly this reason and the nil is preserved."
  [inputs]
  ;; `number?`, not `if-some`: the library returns nil for an unmeasured cycle
  ;; time, and `if-some` treats the `false` that `(and (map? nil) …)` yields as a
  ;; present value — which shipped `:state :computed :value false` until a test
  ;; caught it.
  (let [s (when (map? inputs) (dynamics/loop-structural-strength inputs))]
    (if (number? s)
      {:state :computed :value s :inputs inputs}
      {:state :uncomputable-until-measured
       :detail (str "cycle-time-days が観測されていないため強度は計算できません。"
                    "推測値から計算した数値は測定値ではありません")
       :inputs inputs})))

(defn leverage
  "The latest leverage ranking from the ledger this business binds."
  [configuration b]
  (let [ws (business/workspace configuration)
        path (:business/leverage b)]
    (cond
      (nil? path)
      {:schema schema :state :unbound
       :detail "この事業には leverage ledger が紐付いていません"}

      (not= :present (:state ws))
      {:schema schema :state :unresolvable :source path :detail (:detail ws)}

      :else
      (let [f (io/file (:file ws) path)]
        (if-not (.isFile f)
          {:schema schema :state :missing :source path
           :detail (str path " が workspace にありません")}
          (let [{:keys [events skipped]}
                (try (ledger-events f) (catch Exception _ {::error true}))
                latest (last events)]
            (cond
              (nil? events)
              {:schema schema :state :unreadable :source path
               :detail (str path " を読めませんでした")}

              (nil? latest)
              {:schema schema :state :missing :source path
               :detail (str path " に event がありません")}

              ;; A ledger of a different shape is named as such rather than
              ;; rendered as an empty ranking: the xmile and sysml ledgers in the
              ;; same directory carry backlog windows, not interventions.
              (not (:event/ranked latest))
              {:schema schema :state :not-a-ranking :source path
               :events (count events) :skipped-lines skipped
               :detail (str path " の最新 event は leverage ranking ではありません"
                            "（:event/ranked が無い — この directory には backlog "
                            "window を記録する別形式の ledger もあります）")}

              :else
              (let [labels (into {} (map (fn [[b {:keys [label]}]] [b label]))
                                 dynamics/meadows-bands)]
                {:schema schema :state :resolved :source path
                 :events (count events) :skipped-lines skipped
                 :models-what ledger-caveat
                 :ranked (mapv (fn [[id score band]]
                                 {:id (str (symbol id))
                                  :score score
                                  :band band
                                  :band-label (get labels band)
                                  :band-weight (dynamics/band-weight band)})
                               (:event/ranked latest))
                 :top-3 (mapv #(str (symbol %)) (:event/top-3 latest))
                 ;; No ledger this app reads carries the four inputs, so this is
                 ;; :uncomputable-until-measured today. It is computed and not
                 ;; hardcoded so that a ledger which starts carrying them works
                 ;; without a change here.
                 :structural-strength (strength (:event/strength-inputs latest))}))))))))

(defn snapshot
  "The loops read model for one business."
  [configuration session business-id]
  (when-let [b (business/business session business-id)]
    {:schema schema
     :business (select-keys b [:business/id :business/slug :business/name
                               :business/model :business/leverage])
     :model (model configuration b)
     :leverage (leverage configuration b)
     :bands (bands)}))
