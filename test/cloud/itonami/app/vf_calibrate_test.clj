(ns cloud.itonami.app.vf-calibrate-test
  "ADR-2609111230 slice 3: the XMILE calibration bridge's proofs.

  The ADR's Verification section names what this slice owes:
  appending a measured event changes exactly the calibrated constants named
  by its resource, and the next model run's inputs match the journal —
  nothing else moves. The boundary from ADR-2608153000 holds throughout:
  no flow rate is written, no event is converted into a rate, and a
  resource with no correspondence calibrates nothing."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cloud.itonami.app.vf-journal :as vf-journal]
            [cloud.itonami.app.vf-calibrate :as vfc]
            [xmile.model :as xmodel]
            [xmile.xml :as xxml]
            [xmile.execute :as execute]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private org :org-a)

(def ^:private backlog-xmile
  "One stock drained by one flow. `Backlog`'s initial value (28) is the
  constant calibration rebases: the journal's measured inventory of the
  resource bound to it becomes the model's operating point."
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

(defn- parse-model
  "The fixture model through the SAME read path the app uses
  (`xmile.xml/parse-doc` → first model → doc-level sim-specs merged in,
  which is `loops/runnable-model`'s one-line essence): the XML carries
  `<sim_specs>` above `<model>`, so the model map only becomes runnable
  once the doc's sim-specs are merged onto it."
  []
  (let [doc (xxml/parse-doc (xxml/parse-xml-string backlog-xmile))
        m (-> doc :xmile/models first)]
    (cond-> m
      (nil? (:xmile/sim-specs m))
      (assoc :xmile/sim-specs (:xmile/sim-specs doc)))))

;; bind both dynamics in one place
(defmacro ^:private with-setup [correspondences & body]
  `(let [dir# (str (Files/createTempDirectory
                    "vf-calibrate-test"
                    (make-array java.nio.file.attribute.FileAttribute 0)))]
     (binding [vf-journal/*journal-dir* (io/file dir#)
               vfc/*correspondences* ~correspondences]
       ~@body)))

(defn- produce! [resource n]
  (vf-journal/append-event! org
                            {:action :produce
                             :resource-inventoried-as resource
                             :resource-quantity {:has-numerical-value n
                                                 :has-unit :one}
                             :receiver org}))

(deftest calibration-rebases-exactly-the-named-constant
  (with-setup {org {"backlog" "Backlog"}}
    (is (:appended? (produce! "backlog" 17)))
    (let [m (parse-model)
          inventory (vf-journal/load-inventory org)
          m2 (vfc/calibrated-model m org inventory)]
      (testing "the named constant moved to the measured value"
        (is (= 17.0 (Double/parseDouble
                     (str/trim (get-in m2 [:xmile/variables "Backlog" :xmile/eqn]))))))
      (testing "NOTHING else moves: the flow and the aux are untouched"
        (is (= "2" (get-in m2 [:xmile/variables "Registration" :xmile/eqn])))
        (is (= "Backlog / Registration"
               (get-in m2 [:xmile/variables "Days_To_Empty" :xmile/eqn]))))
      (testing "the original model value is unchanged (immutable update)"
        (is (= "28" (get-in m [:xmile/variables "Backlog" :xmile/eqn])))))))

(deftest the-next-run-reflects-the-calibrated-operating-point
  (with-setup {org {"backlog" "Backlog"}}
    (is (:appended? (produce! "backlog" 10)))
    (let [m (parse-model)
          m2 (vfc/calibrated-model m org (vf-journal/load-inventory org))
          before (execute/run m)
          after (execute/run m2)
          before-backlog (last (get-in before [:xmile/series "Backlog"]))
          after-backlog (last (get-in after [:xmile/series "Backlog"]))]
      ;; 28 - 4*2 = 20 uncalibrated; 10 - 4*2 = 2 calibrated (clamped at 0
      ;; is not reached here since 10 > 8). The run INPUTS differ exactly
      ;; where calibration moved them.
      (is (= 20.0 before-backlog))
      (is (= 2.0 after-backlog)))))

(deftest a-resource-without-a-correspondence-calibrates-nothing
  (with-setup {org {"backlog" "Backlog"}}
    (is (:appended? (produce! "mystery" 99)))
    (let [m (parse-model)
          inventory (vf-journal/load-inventory org)
          m2 (vfc/calibrated-model m org inventory)]
      (testing "the model is IDENTICAL: an unnamed resource is not a slot"
        (is (= m m2)))
      (testing "the report names the BINDING with no measurement, not the
                resource nobody bound — 「mystery を観測した」 is a fact
                about the journal, 「Backlog はまだ測られていない」 is the
                actionable half"
        (is (= [{:resource "backlog" :constant "Backlog"}]
               (:unmatched (vfc/calibration m org inventory))))))))

(deftest the-report-names-rebased-unmatched-and-ignored
  (with-setup {org {"backlog" "Backlog" "ghost" "Days_To_Empty"}}
    (is (:appended? (produce! "backlog" 17)))
    (is (:appended? (produce! "ghost" 3)))
    (let [m (parse-model)
          inventory (vf-journal/load-inventory org)
          report (vfc/calibration m org inventory)]
      (testing "measured + leaf constant → rebased, from and to named"
        (is (= [{:resource "backlog" :constant "Backlog"
                 :from 28.0 :to 17.0}]
               (:rebased report))))
      (testing "a binding onto a COMPUTED variable is ignored, not written"
        ;; Days_To_Empty's eqn is `Backlog / Registration` — calibrating it
        ;; would overwrite a derived value with a fixed one. The resource
        ;; has a measurement; the MODEL has no place it may go.
        (is (= [{:resource "ghost" :constant "Days_To_Empty"}]
               (:ignored report))))
      (testing "calibrated-model also refuses the computed variable"
        (is (= (get-in m [:xmile/variables "Days_To_Empty" :xmile/eqn])
               (get-in (vfc/calibrated-model m org inventory)
                       [:xmile/variables "Days_To_Empty" :xmile/eqn])))))))

(deftest journal-and-calibration-agree-through-the-real-append-path
  (with-setup {org {"backlog" "Backlog"}}
    ;; Two measured events, exactly as a bot's `vf.event.append` intent
    ;; would land them (slice 1's path, no new writer here).
    (is (:appended? (produce! "backlog" 5)))
    (is (:appended? (produce! "backlog" 5)))
    (let [inventory (vf-journal/load-inventory org)
          m2 (vfc/calibrated-model (parse-model) org inventory)]
      (testing "the register is the FOLD's arithmetic, not the last event"
        (is (= 10 (get-in inventory ["backlog" :onhand-quantity
                                     :has-numerical-value]))))
      (testing "and the model's operating point is that same number"
        (is (= 10.0 (Double/parseDouble
                     (str/trim (get-in m2 [:xmile/variables "Backlog"
                                           :xmile/eqn])))))))))
