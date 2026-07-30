(ns cloud.itonami.app.loops-test
  "The loops plane, tested on the four things it exists to get right:

  1. the trajectory comes from the real engine, and a model it could not run
     produces a reason rather than an empty series;
  2. structure is read from the model's own inflow/outflow declarations;
  3. a strength score that `dynamics.core` returns nil for stays absent — it is
     never 0 and never a plausible-looking number;
  4. a ledger of a different shape is named as such, not rendered as empty."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.loops :as loops]
            [cloud.itonami.app.store :as store]
            [dynamics.core :as dynamics])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def no-workspace {})

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "loops-test" (into-array FileAttribute []))))

(defn- reset-all! [] (store/transact! assoc :businesses {}))

(def ^:private backlog-xmile
  "A real XMILE 1.0 document: one stock drained by one flow, plus an aux over
  both. Shaped after the fleet registration backlog the existing ledgers model."
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
       "<xmile version=\"1.0\">"
       "<header><name>backlog</name></header>"
       "<sim_specs method=\"euler\"><start>0</start><stop>4</stop><dt>1</dt></sim_specs>"
       "<model><variables>"
       "<stock name=\"Backlog\"><eqn>28</eqn><outflow>Registration</outflow>"
       "<units>repos</units></stock>"
       "<flow name=\"Registration\"><eqn>2</eqn><units>repos/day</units></flow>"
       "<aux name=\"Days_To_Empty\"><eqn>Backlog / Registration</eqn>"
       "<units>days</units></aux>"
       "</variables></model></xmile>"))

(defn- spit-under! [root rel content]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn- a-business!
  "The fixture business, created once and rebound per test."
  [bindings]
  (let [existing (first (filter #(= "cloud-itonami-5820" (:business/slug %))
                                (business/businesses session)))
        b (or existing (business/create! session {:slug "cloud-itonami-5820"}))]
    (business/bind! session (:business/id b) bindings)))

(defn- with-model
  ([] (with-model backlog-xmile))
  ([xml]
   (let [root (temp-dir)]
     (spit-under! root "loops/backlog.xmile" xml)
     [{:business {:workspace-root (.getPath root)}} root])))

;; ---------------------------------------------------------------------------
;; the model — read, and actually run
;; ---------------------------------------------------------------------------

(deftest an-unbound-model-is-not-a-missing-one
  (reset-all!)
  (is (= :unbound (:state (loops/model no-workspace (a-business! {})))))
  (testing "bound with no checkout is :unresolvable, and names the path"
    (let [m (loops/model no-workspace (a-business! {:model "loops/backlog.xmile"}))]
      (is (= :unresolvable (:state m)))
      (is (= "loops/backlog.xmile" (:source m))))))

(deftest a-missing-or-unparseable-model-says-which
  (reset-all!)
  (let [b (a-business! {:model "loops/backlog.xmile"})]
    (is (= :missing (:state (loops/model {:business {:workspace-root (.getPath (temp-dir))}} b))))
    (let [[config] (with-model "<xmile version=\"1.0\"><header>")
          m (loops/model config b)]
      (testing "XML that will not parse is :unreadable and carries the parser's
                own complaint"
        (is (= :unreadable (:state m)))
        (is (seq (:detail m)))))
    (testing "a well-formed document with no <model> is not an empty model"
      (let [[config] (with-model "<xmile version=\"1.0\"><header><name>x</name></header></xmile>")]
        (is (= :unreadable (:state (loops/model config b))))))))

(deftest the-trajectory-comes-from-the-real-engine
  (reset-all!)
  (let [[config] (with-model)
        m (loops/model config (a-business! {:model "loops/backlog.xmile"}))
        t (:trajectory m)]
    (is (= :resolved (:state m)))
    (is (= :simulated (:state t)))
    (testing "start/stop/dt from the document's sim_specs, inclusive of both ends"
      (is (= [0.0 1.0 2.0 3.0 4.0] (:times t)))
      (is (= 5 (:steps t))))
    (testing "the stock is drained by its outflow — this is xmile.execute's Euler
              integration, not arithmetic in this app"
      (is (= [28.0 26.0 24.0 22.0 20.0] (get (:series t) "Backlog"))))
    (testing "flows and auxes come back too, under their own names"
      (is (= [2.0 2.0 2.0 2.0 2.0] (get (:series t) "Registration")))
      (is (= 14.0 (first (get (:series t) "Days_To_Empty")))))))

(deftest a-model-that-cannot-run-produces-a-reason-not-an-empty-series
  (reset-all!)
  (let [[config] (with-model
                   (str/replace backlog-xmile "method=\"euler\"" "method=\"heun\""))
        m (loops/model config (a-business! {:model "loops/backlog.xmile"}))
        t (:trajectory m)]
    (testing "the model parsed, so the state is :resolved — it is the RUN that
              failed, and conflating the two would hide the structure"
      (is (= :resolved (:state m))))
    (is (= :unsimulatable (:state t)))
    (testing "the engine's own message survives, naming the unsupported method
              rather than saying 「実行できません」"
      (is (str/includes? (:reason t) "heun")))
    (testing "and there is no series at all — an empty one would read as
              『シミュレーションした結果、全部ゼロ』"
      (is (nil? (:series t))))))

(deftest the-structure-is-the-models-own-declarations
  (reset-all!)
  (let [[config] (with-model)
        s (:structure (loops/model config (a-business! {:model "loops/backlog.xmile"})))
        backlog (first (:stocks s))]
    (is (= ["Backlog"] (mapv :name (:stocks s))))
    (is (= ["Registration"] (mapv :name (:flows s))))
    (is (= ["Days_To_Empty"] (mapv :name (:auxes s))))
    (testing "the arrows are the XMILE <outflow> declaration, not inferred from
              the equation text"
      (is (= [] (:inflows backlog)))
      (is (= ["Registration"] (:outflows backlog))))
    (testing "units travel, because they are the reason each variable needs its
              own axis"
      (is (= "repos" (:units backlog)))
      (is (= "repos/day" (:units (first (:flows s))))))))

(deftest which-model-was-run-is-reported-when-there-are-several
  (reset-all!)
  (let [two (str/replace backlog-xmile "</model></xmile>"
                         (str "</model><model name=\"other\"><variables>"
                              "<aux name=\"Z\"><eqn>1</eqn></aux>"
                              "</variables></model></xmile>"))
        [config] (with-model two)
        m (loops/model config (a-business! {:model "loops/backlog.xmile"}))]
    (testing "picking the first is a choice, so both the choice and the count are
              in the payload rather than hidden"
      (is (= 2 (count (:models m))))
      (is (= "(無名)" (:simulated-model m)))
      (is (= ["(無名)" "other"] (:models m))))))


;; ---------------------------------------------------------------------------
;; sensitivity — measured out of the model, not judged
;; ---------------------------------------------------------------------------

(def ^:private funnel-xmile
  "A two-stock funnel with a conversion that moves tenants between them, plus a
  constant nothing references. Shaped after the real
  90-docs/business/cloud-itonami-saas-funnel.xmile."
  (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
       "<xmile version=\"1.0\">"
       "<sim_specs method=\"euler\"><start>0</start><stop>10</stop><dt>1</dt></sim_specs>"
       "<model><variables>"
       "<stock name=\"Non_Paying\"><eqn>5</eqn><inflow>Signup</inflow>"
       "<outflow>Conversion</outflow><units>tenants</units></stock>"
       "<stock name=\"Paying\"><eqn>1</eqn><inflow>Conversion</inflow>"
       "<units>tenants</units></stock>"
       "<flow name=\"Signup\"><eqn>0.1</eqn><units>tenants/day</units></flow>"
       "<flow name=\"Conversion\"><eqn>Non_Paying * Rate</eqn>"
       "<units>tenants/day</units></flow>"
       "<aux name=\"Rate\"><eqn>0.02</eqn></aux>"
       "<aux name=\"Weekly_Uniques\"><eqn>147</eqn><units>uniques/week</units></aux>"
       "</variables></model></xmile>"))

(defn- param [s nm] (first (filter #(= nm (:name %)) (:parameters s))))
(defn- effect [p outcome] (first (filter #(= outcome (:outcome %)) (:effects p))))

(deftest sensitivity-is-measured-by-re-running-the-model
  (reset-all!)
  (let [[config] (with-model funnel-xmile)
        s (:sensitivity (loops/model config (a-business! {:model "loops/backlog.xmile"})))]
    (is (= :computed (:state s)))
    (is (= 0.10 (:perturbation s)))
    (testing "outcomes are the stocks — the state of the system, not its rates"
      (is (= ["Non_Paying" "Paying"] (:outcomes s))))
    (testing "only leaf constants are parameters. Conversion's equation
              references other variables, and overwriting it with a fixed number
              would be a different model, not a sensitivity"
      (is (= #{"Non_Paying" "Paying" "Signup" "Rate" "Weekly_Uniques"}
             (set (map :name (:parameters s)))))
      (is (nil? (param s "Conversion"))))
    (testing "a stock's eqn is its initial value, so stocks are parameters too"
      (is (= 5.0 (:baseline (param s "Non_Paying")))))))

(deftest raising-the-conversion-rate-raises-paying-and-lowers-non-paying
  (reset-all!)
  (let [[config] (with-model funnel-xmile)
        s (:sensitivity (loops/model config (a-business! {:model "loops/backlog.xmile"})))
        rate (param s "Rate")]
    (testing "signs come out of the structure, not out of an assumption: the same
              flow drains one stock and fills the other"
      (is (pos? (:value (effect rate "Paying"))))
      (is (neg? (:value (effect rate "Non_Paying")))))
    (testing "the elasticity is dimensionless, so a rate and a day count are
              comparable — which is the only reason this ranking means anything"
      (is (= :computed (:state (effect rate "Paying"))))
      (is (number? (:value (effect (param s "Signup") "Paying")))))))

(deftest a-constant-nothing-references-is-disconnected-not-ineffective
  (reset-all!)
  (let [[config] (with-model funnel-xmile)
        s (:sensitivity (loops/model config (a-business! {:model "loops/backlog.xmile"})))
        u (param s "Weekly_Uniques")]
    (testing "0.0000 is ambiguous between 「効かない」 and 「繋がっていない」, and
              the second is decidable from the model's own text"
      (is (false? (:connected? u)))
      (is (= [] (:referenced-by u)))
      (is (str/includes? (:detail u) "繋がっていない"))
      (is (zero? (:value (effect u "Paying")))))
    (testing "and a connected one says what references it — including a flow
              named structurally by a stock rather than in an equation"
      (let [signup (param s "Signup")]
        (is (true? (:connected? signup)))
        (is (= ["Non_Paying"] (:referenced-by signup)))
        (is (nil? (:detail signup)))))))

(deftest an-elasticity-with-no-scale-is-undefined-not-zero
  (reset-all!)
  (let [[config] (with-model (str/replace funnel-xmile
                                          "<stock name=\"Paying\"><eqn>1</eqn>"
                                          "<stock name=\"Paying\"><eqn>0</eqn>"))
        s (:sensitivity (loops/model config (a-business! {:model "loops/backlog.xmile"})))]
    (testing "a parameter whose baseline is 0 cannot be nudged by a percentage"
      (let [e (effect (param s "Paying") "Paying")]
        (is (= :undefined (:state e)))
        (is (= :zero-parameter (:reason e)))
        (is (nil? (:value e)))))))

(deftest a-model-that-cannot-run-has-no-sensitivity-either
  (reset-all!)
  (let [[config] (with-model (str/replace backlog-xmile "method=\"euler\"" "method=\"heun\""))
        s (:sensitivity (loops/model config (a-business! {:model "loops/backlog.xmile"})))]
    (is (= :unsimulatable (:state s)))
    (is (str/includes? (:reason s) "heun"))
    (testing "and no parameter list standing in for one"
      (is (nil? (:parameters s))))))

;; ---------------------------------------------------------------------------
;; leverage — read the ledger, borrow the vocabulary, invent no numbers
;; ---------------------------------------------------------------------------

(def ^:private leverage-ledger
  (str "#:event{:top-3 [:wire-live-observe] "
       ":ranked [[:wire-live-observe 4.9 :band/B] [:clear-backlog 0.9 :band/E]]}\n"))

(deftest the-ranking-is-the-ledgers-with-bands-from-the-library
  (reset-all!)
  (let [root (temp-dir)]
    (spit-under! root "ledger/lev.edn" leverage-ledger)
    (let [config {:business {:workspace-root (.getPath root)}}
          lv (loops/leverage config (a-business! {:leverage "ledger/lev.edn"}))
          top (first (:ranked lv))]
      (is (= :resolved (:state lv)))
      (is (= "wire-live-observe" (:id top)))
      (is (= 4.9 (:score top)))
      (testing "the band label and weight are read from dynamics.core, not
                restated here, so a change upstream cannot leave this app
                describing the old bands"
        (is (= (get-in dynamics/meadows-bands [:band/B :label]) (:band-label top)))
        (is (= (dynamics/band-weight :band/B) (:band-weight top))))
      (testing "what the ledger actually models is stated on every response, not
                left for a reader to infer from intervention names"
        (is (str/includes? (:models-what lv) "backlog"))))))

(deftest a-ledger-of-another-shape-is-named-not-rendered-empty
  (reset-all!)
  (let [root (temp-dir)]
    ;; The xmile/sysml ledgers in the same directory carry backlog windows.
    (spit-under! root "ledger/window.edn"
                 "#:event{:as-of \"2026-07-21\" :per-category {:isic {:initial-backlog 28}}}\n")
    (let [config {:business {:workspace-root (.getPath root)}}
          lv (loops/leverage config (a-business! {:leverage "ledger/window.edn"}))]
      (is (= :not-a-ranking (:state lv)))
      (is (= 1 (:events lv)))
      (is (str/includes? (:detail lv) "ranked")))))

(deftest a-corrupt-line-is-skipped-and-counted-not-silently-dropped
  (reset-all!)
  (let [root (temp-dir)]
    (spit-under! root "ledger/lev.edn"
                 (str ";; a comment\n" "{:event/ranked [[:a 1.0 :band/E]\n" leverage-ledger))
    (let [config {:business {:workspace-root (.getPath root)}}
          lv (loops/leverage config (a-business! {:leverage "ledger/lev.edn"}))]
      (testing "one unparseable line does not lose the file, and the count of what
                was skipped is reported rather than swallowed"
        (is (= :resolved (:state lv)))
        (is (= 1 (:skipped-lines lv)))
        (is (= 1 (:events lv)))))))

(deftest structural-strength-stays-absent-when-it-cannot-be-measured
  (reset-all!)
  (let [root (temp-dir)]
    (spit-under! root "ledger/lev.edn" leverage-ledger)
    (let [config {:business {:workspace-root (.getPath root)}}
          lv (loops/leverage config (a-business! {:leverage "ledger/lev.edn"}))
          s (:structural-strength lv)]
      (testing "no ledger this app reads carries cycle time, so the answer is
                that it cannot be computed — not 0, and not a plausible number"
        (is (= :uncomputable-until-measured (:state s)))
        (is (nil? (:value s)))
        (is (str/includes? (:detail s) "cycle-time-days"))))
    (testing "a ledger that DOES carry the four inputs is computed, so the
              absence above is about the data and not about this code"
      (spit-under! root "ledger/full.edn"
                   (str "#:event{:ranked [[:a 1.0 :band/E]] :top-3 [:a] "
                        ":strength-inputs {:cycle-time-days 7 "
                        ":self-funding-coefficient 0.5 "
                        ":instrumentation-completeness 0.5 :friction 0.2}}\n"))
      (let [config {:business {:workspace-root (.getPath root)}}
            s (:structural-strength
               (loops/leverage config (a-business! {:leverage "ledger/full.edn"})))]
        (is (= :computed (:state s)))
        (is (= (dynamics/loop-structural-strength
                {:cycle-time-days 7 :self-funding-coefficient 0.5
                 :instrumentation-completeness 0.5 :friction 0.2})
               (:value s)))))))

(deftest the-bands-legend-comes-from-the-library
  (testing "five bands, heaviest first, exactly as dynamics.core defines them"
    (let [bs (loops/bands)]
      (is (= 5 (count bs)))
      (is (= :band/A (:band (first bs))))
      (is (= 10 (:weight (first bs))))
      (is (= (set (keys dynamics/meadows-bands)) (set (map :band bs)))))))

;; ---------------------------------------------------------------------------
;; scoping and the absence of a write path
;; ---------------------------------------------------------------------------

(deftest the-snapshot-is-organization-scoped
  (reset-all!)
  (let [b (a-business! {:model "loops/backlog.xmile"})]
    (is (some? (loops/snapshot no-workspace session (:business/id b))))
    (is (nil? (loops/snapshot no-workspace {:user-id "u" :organization-id "org-2"}
                              (:business/id b))))))

(deftest reading-loops-writes-nothing
  (reset-all!)
  (let [[config root] (with-model)
        b (a-business! {:model "loops/backlog.xmile"})
        f (io/file root "loops/backlog.xmile")
        model-before (slurp f)
        before (store/snapshot)]
    (loops/snapshot config session (:business/id b))
    (testing "this plane has no write path at all: neither the store nor the
              model file changes when it is read"
      (is (= model-before (slurp f)))
      (is (= before (store/snapshot))))))
