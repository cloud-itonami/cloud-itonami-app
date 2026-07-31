(ns cloud.itonami.app.portfolio-test
  "The matrix, tested on the one thing it exists to get right: every cell says
  which KIND of nothing it is, and the four kinds never collapse into one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.portfolio :as portfolio]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-2"})
(def no-workspace {})
(def ^:private now (Instant/parse "2026-07-31T00:00:00Z"))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "portfolio-test" (into-array FileAttribute []))))

(defn- reset-all! []
  (store/transact! #(assoc % :businesses {} :canvas-proposals {}
                           :operator-adoptions {})))

(defn- spit-under! [root rel content]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    (spit f (if (string? content) content (pr-str content)))
    f))

(defn- a-business! [slug bindings]
  (let [existing (first (filter #(= slug (:business/slug %))
                                (business/businesses session)))
        b (or existing (business/create! session {:slug slug}))]
    (if (seq bindings) (business/bind! session (:business/id b) bindings) b)))

(defn- cell [row k] (get-in row [:cells k]))
(defn- row-for [m slug]
  (first (filter #(= slug (:business/slug (:business %))) (:businesses m))))

;; A workspace with every plane present and consistent.
(defn- full-workspace []
  (let [root (temp-dir)]
    (spit-under! root "90-docs/business/cloud-itonami-canvas.datoms.edn"
                 [{:projection/id "canvas-cloud-itonami" :projection/as-of "2026-07-31"
                   :projection/blocks 1 :projection/hypotheses 1}
                  {:canvas/id :cloud-itonami.problem :canvas/product :cloud-itonami
                   :canvas/block :lean/problem :canvas/label "Problem"
                   :canvas/order 0 :canvas/items ["p1"]}
                  {:hyp/id :hyp/t1 :hyp/product :cloud-itonami :hyp/claim "c"
                   :hyp/gate "g" :hyp/risk :riskiest :hyp/status :untested
                   :gate/status :measuring :gate/distance "あと 3"}])
    (spit-under! root "90-docs/business/maturity-scores.datoms.edn"
                 [{:projection/id "maturity-scores" :projection/as-of "2026-07-31"}
                  {:score/product :cloud-itonami :score/bmc 78.0 :score/yc 61.0
                   :score/unrecorded-dims 0}
                  {:dim/product :cloud-itonami :dim/name :pricing :dim/value 3.0
                   :dim/source :facts :dim/recorded? true}])
    (spit-under! root "90-docs/business/metrics/cloud-itonami.edn"
                 {:as-of "2026-07-30" :zone {:uniques-7d-sum 147 :requests-7d 21491}
                  :traffic-quality {:window "24h" :probe-4xx-pct 1 :error-5xx-pct 0}})
    (spit-under! root "manifest/repo-taxonomy.edn"
                 [{:repo/path "orgs/x/y" :repo/name "y" :repo/org "x" :repo/kind "app"}])
    (spit-under! root "manifest/repo-maturity.edn"
                 [{:repo/path "orgs/x/y" :maturity/composite 0.6
                   :maturity/structural-score 1.0}])
    (spit-under! root "loops/m.xmile"
                 (str "<?xml version=\"1.0\" encoding=\"utf-8\"?><xmile version=\"1.0\">"
                      "<sim_specs method=\"euler\"><start>0</start><stop>4</stop>"
                      "<dt>1</dt></sim_specs><model><variables>"
                      "<stock name=\"S\"><eqn>5</eqn><inflow>F</inflow>"
                      "<units>t</units></stock>"
                      "<flow name=\"F\"><eqn>Rate</eqn><units>t/day</units></flow>"
                      "<aux name=\"Rate\"><eqn>0.5</eqn></aux>"
                      "</variables></model></xmile>"))
    [{:business {:workspace-root (.getPath root)}} root]))

;; ---------------------------------------------------------------------------
;; the four kinds of nothing
;; ---------------------------------------------------------------------------

(deftest a-business-that-bound-nothing-is-unbound-everywhere-not-missing
  (reset-all!)
  (a-business! "empty" {})
  (let [row (row-for (portfolio/matrix no-workspace session now) "empty")]
    (testing "「紐付けていない」 is not 「見つからない」 — the first is fixed in
              Portfolio, the second by generating a file"
      (is (= #{:unbound} (set (map :state (vals (:cells row)))))))
    (testing "and every cell explains itself rather than showing a bare state"
      (is (every? (comp seq :detail) (vals (:cells row)))))))

(deftest bound-faces-with-no-workspace-are-unresolvable-not-missing
  (reset-all!)
  (a-business! "bound" {:canvas "cloud-itonami" :model "loops/m.xmile"
                        :repos ["orgs/x/y"]})
  (let [row (row-for (portfolio/matrix no-workspace session now) "bound")]
    (doseq [k [:canvas :maturity :loops :repos :metrics]]
      (is (= :unresolvable (:state (cell row k))) (str k)))
    (testing "the reason names the setting, so the fix is visible from the grid"
      (is (str/includes? (:detail (cell row :canvas)) "workspace")))))

(deftest a-full-workspace-measures-every-column
  (reset-all!)
  (let [[config] (full-workspace)]
    (a-business! "cloud-itonami-5820"
                 {:canvas "cloud-itonami" :model "loops/m.xmile" :repos ["orgs/x/y"]})
    (let [row (row-for (portfolio/matrix config session now) "cloud-itonami-5820")]
      (is (= :measured (:state (cell row :canvas))))
      (is (= :measured (:state (cell row :maturity))))
      (is (= :measured (:state (cell row :loops))))
      (is (= :measured (:state (cell row :repos))))
      (is (= :measured (:state (cell row :metrics))))
      (testing "the gate column reports what the METRICS say, not what the ledger
                says — :hyp/status is untested and :gate/status is measuring, and
                the column that matters here is the second"
        (is (= :measured (:state (cell row :gate))))
        (is (= "measuring" (:value (cell row :gate))))
        (is (str/includes? (:detail (cell row :gate)) "あと 3")))
      (testing "the repos column carries the scored/total split, never a mean
                that swallowed unscored repos"
        (is (str/includes? (:detail (cell row :repos)) "評価済み 1 / 1")))
      (testing "the loops column names the constant that moves the model most"
        (is (= "Rate" (:value (cell row :loops))))))))

(deftest stale-is-a-fifth-state-and-only-metrics-has-it
  (reset-all!)
  (let [[config root] (full-workspace)]
    (spit-under! root "90-docs/business/metrics/cloud-itonami.edn"
                 {:as-of "2026-07-02" :zone {:uniques-7d-sum 147}
                  :traffic-quality {:window "24h" :probe-4xx-pct 1 :error-5xx-pct 0}})
    (a-business! "cloud-itonami-5820"
                 {:canvas "cloud-itonami" :model "loops/m.xmile" :repos ["orgs/x/y"]})
    (let [row (row-for (portfolio/matrix config session now) "cloud-itonami-5820")]
      (testing "only metrics carries a date it can be late against"
        (is (= :stale (:state (cell row :metrics))))
        (is (str/includes? (:detail (cell row :metrics)) "日前")))
      (testing "and the other columns are unaffected"
        (is (= :measured (:state (cell row :canvas))))))))

(deftest a-model-that-will-not-run-is-missing-not-unbound
  (reset-all!)
  (let [[config root] (full-workspace)]
    (spit-under! root "loops/m.xmile"
                 (str "<?xml version=\"1.0\" encoding=\"utf-8\"?><xmile version=\"1.0\">"
                      "<sim_specs method=\"heun\"><start>0</start><stop>4</stop>"
                      "<dt>1</dt></sim_specs><model><variables>"
                      "<stock name=\"S\"><eqn>5</eqn></stock>"
                      "</variables></model></xmile>"))
    (a-business! "cloud-itonami-5820" {:model "loops/m.xmile"})
    (let [c (cell (row-for (portfolio/matrix config session now) "cloud-itonami-5820")
                  :loops)]
      (testing "the file is there and readable; it is the RUN that failed, and
                the engine's own complaint is carried"
        (is (= :missing (:state c)))
        (is (str/includes? (:detail c) "heun"))))))

(deftest a-canvas-with-no-riskiest-hypothesis-gets-no-gate
  (reset-all!)
  (let [[config root] (full-workspace)]
    (spit-under! root "90-docs/business/cloud-itonami-canvas.datoms.edn"
                 [{:projection/id "canvas-cloud-itonami" :projection/blocks 1
                   :projection/hypotheses 1}
                  {:canvas/id :cloud-itonami.problem :canvas/product :cloud-itonami
                   :canvas/block :lean/problem :canvas/order 0 :canvas/items []}
                  {:hyp/id :hyp/t1 :hyp/product :cloud-itonami :hyp/claim "c"
                   :hyp/status :untested}])
    (a-business! "cloud-itonami-5820" {:canvas "cloud-itonami"})
    (let [row (row-for (portfolio/matrix config session now) "cloud-itonami-5820")]
      (testing "no hypothesis was marked riskiest, so none is promoted to fill
                the column"
        (is (= :unbound (:state (cell row :gate)))))
      (testing "and a hypothesis with no gate verdict is :missing rather than a
                gate that failed"
        (spit-under! root "90-docs/business/cloud-itonami-canvas.datoms.edn"
                     [{:projection/id "c" :projection/blocks 0 :projection/hypotheses 1}
                      {:hyp/id :hyp/t1 :hyp/product :cloud-itonami :hyp/claim "c"
                       :hyp/risk :riskiest :hyp/status :untested}])
        (is (= :missing (:state (cell (row-for (portfolio/matrix config session now)
                                               "cloud-itonami-5820")
                                      :gate))))))))

;; ---------------------------------------------------------------------------
;; the summary, and the boundaries
;; ---------------------------------------------------------------------------

(deftest the-counts-make-the-emptiness-a-number
  (reset-all!)
  (a-business! "biz-one" {})
  (a-business! "biz-two" {})
  (let [m (portfolio/matrix no-workspace session now)]
    (is (= 2 (:businesses (:counts m))))
    (is (= 12 (:cells (:counts m))))
    (testing "「まだ何も測れていない」 is visible as a number rather than as a
              screen of grey cells"
      (is (= 12 (:unbound (:counts m))))
      (is (nil? (:measured (:counts m)))))))

(deftest the-matrix-is-organization-scoped-and-writes-nothing
  (reset-all!)
  (let [[config] (full-workspace)]
    (a-business! "cloud-itonami-5820" {:canvas "cloud-itonami"})
    (is (= [] (:businesses (portfolio/matrix config other-session now))))
    (let [before (store/snapshot)]
      (portfolio/matrix config session now)
      (testing "every plane it touches is read-only"
        (is (= before (store/snapshot)))))))

(deftest the-columns-are-data-so-the-pane-cannot-drift
  (is (= [:canvas :gate :maturity :loops :repos :metrics]
         (mapv :key portfolio/columns)))
  (is (every? (comp seq :label) portfolio/columns))
  (is (every? (comp seq :detail) portfolio/columns)))
