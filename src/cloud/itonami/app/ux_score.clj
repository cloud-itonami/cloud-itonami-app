(ns cloud.itonami.app.ux-score
  "Reproducible information-architecture scoring and its XMILE trajectory.

  The score is an engineering signal, not a usability-test substitute. Inputs
  are observable UI counts/ratios, the weighted result is deterministic, and
  the system-dynamics trajectory is delegated to the OASIS XMILE engine."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.xml :as cxml]
            [xmile.execute :as execute]
            [xmile.xml :as xxml])
  (:import [java.io ByteArrayInputStream]
           [javax.xml.parsers SAXParserFactory]))

(def weights
  {:task-clarity 0.25
   :choice-load 0.15
   :progressive-disclosure 0.25
   :dads-conformance 0.25
   :responsive-fit 0.10})

(defn- clamp [n] (max 0.0 (min 100.0 (double n))))
(defn- round2 [n] (/ (Math/round (* 100.0 (double n))) 100.0))
(defn- log2 [n] (/ (Math/log (double n)) (Math/log 2.0)))

(defn score
  "Score one observed Settings IA audit on a 0–100 scale.

  Counts are intentionally separate from ratios: Hick–Hyman is reported as
  entropy, while DADS conformance is based on inspectable labels, feedback,
  hierarchy and target sizing."
  [{:keys [visible-choices concurrent-primary-actions next-action?
           visible-sections total-sections labeled-control-ratio
           feedback-ratio hierarchy-ratio target-size-ratio responsive-fit]}]
  (let [entropy (log2 (inc visible-choices))
        task (clamp (+ (if next-action? 60 0)
                       (max 0 (- 40 (* 10 (dec concurrent-primary-actions))))))
        choice (clamp (* 100 (- 1 (/ entropy 5.0))))
        disclosure (if (<= total-sections 1)
                     100.0
                     (clamp (* 100 (- 1 (/ (dec visible-sections)
                                             (dec total-sections))))))
        dads (clamp (+ (* 35 labeled-control-ratio)
                       (* 25 feedback-ratio)
                       (* 20 hierarchy-ratio)
                       (* 20 target-size-ratio)))
        dimensions {:task-clarity task
                    :choice-load choice
                    :progressive-disclosure disclosure
                    :dads-conformance dads
                    :responsive-fit (clamp (* 100 responsive-fit))}
        total (reduce-kv (fn [sum k value] (+ sum (* value (weights k))))
                         0.0 dimensions)]
    {:total (round2 total)
     :choice-entropy-bits (round2 entropy)
     :dimensions (update-vals dimensions round2)}))

(defn scorecards []
  (let [audits (read-string (slurp (io/resource "cloud/itonami/app/settings_ux_audit.edn")))]
    (update-vals audits #(assoc % :score (score (:metrics %))))))

(defn model-xml
  "Build the checked-in XMILE model from the measured before/after cards."
  []
  (let [{before :before after :after} (scorecards)
        baseline (get-in before [:score :total])
        target (get-in after [:score :total])]
    (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
         "<xmile version=\"1.0\"><header><name>Settings IA remediation</name></header>"
         "<sim_specs method=\"euler\"><start>0</start><stop>4</stop><dt>1</dt></sim_specs>"
         "<model><variables>"
         "<stock name=\"Decision_Friction\"><eqn>" (- 100 baseline) "</eqn>"
         "<outflow>Friction_Removed</outflow><units>score_points</units></stock>"
         "<flow name=\"Friction_Removed\"><eqn>(Target_Quality - Baseline_Quality) / 4</eqn>"
         "<units>score_points/iteration</units></flow>"
         "<aux name=\"Baseline_Quality\"><eqn>" baseline "</eqn><units>score_points</units></aux>"
         "<aux name=\"Target_Quality\"><eqn>" target "</eqn><units>score_points</units></aux>"
         "<aux name=\"Information_Architecture_Quality\"><eqn>100 - Decision_Friction</eqn>"
         "<units>score_points</units></aux>"
         "</variables></model></xmile>")))

(defn- parse-xml [text]
  (let [factory (doto (SAXParserFactory/newInstance)
                  (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))]
    (cxml/parse (ByteArrayInputStream. (.getBytes text "UTF-8"))
                (fn [source handler]
                  (.parse (.newSAXParser factory) source handler)))))

(defn simulate []
  (let [doc (xxml/parse-doc (parse-xml (model-xml)))
        model (assoc (first (:xmile/models doc))
                     :xmile/sim-specs (:xmile/sim-specs doc))
        run (execute/run model)]
    {:times (vec (:xmile/times run))
     :series (update-vals (:xmile/series run) vec)}))

(def minimum-score 85.0)

(defn- source-text [path]
  (slurp (io/file path)))

(defn audit-report
  "Evaluate the score and the code invariants that make its inputs true.

  The EDN scorecard alone is only a claim. These checks bind that claim to the
  rendered information architecture, responsive CSS, visible feedback, the
  KotobaShell Passkey hand-off, and the checked-in XMILE target."
  []
  (let [{before :before after :after} (scorecards)
        score (get-in after [:score :total])
        baseline (get-in before [:score :total])
        metrics (:metrics after)
        web-source (source-text "src/cloud/itonami/app/web.clj")
        server-source (source-text "src/cloud/itonami/app/server.clj")
        interaction (source-text "resources/cloud/itonami/app/interaction.js")
        xmile (source-text "docs/ux/settings-information-architecture.xmile")
        targets (set (map second
                          (re-seq #":data-settings-target \"([^\"]+)\"" web-source)))
        panels (set (map second
                         (re-seq #":data-settings-panel \"([^\"]+)\"" web-source)))
        expected #{"overview" "account" "organization" "connections" "agents"}
        quality (get-in (simulate) [:series "Information_Architecture_Quality"])
        checks
        [{:id :score-threshold :pass? (>= score minimum-score)
          :actual score :expected (str ">= " minimum-score)}
         {:id :measured-improvement :pass? (>= (- score baseline) 30.0)
          :actual (round2 (- score baseline)) :expected ">= 30"}
         {:id :settings-categories :pass? (= expected targets panels)
          :actual {:targets targets :panels panels} :expected expected}
         {:id :scorecard-choice-count
          :pass? (= (:visible-choices metrics) (inc (count targets)))
          :actual (:visible-choices metrics) :expected (inc (count targets))}
         {:id :scorecard-section-count
          :pass? (= (:total-sections metrics) (count panels))
          :actual (:total-sections metrics) :expected (count panels)}
         {:id :single-next-action
          :pass? (= 1 (count (re-seq #":id \"settings-next-action\"" web-source)))
          :actual (count (re-seq #":id \"settings-next-action\"" web-source)) :expected 1}
         {:id :visible-passkey-feedback
          :pass? (and (str/includes? web-source ":id \"passkey-action-status\"")
                      (str/includes? interaction "setPasskeyStatus"))
          :actual :source :expected :visible-live-region}
         {:id :shell-passkey-handoff
          :pass? (and (str/includes? interaction "KotobaShell")
                      (str/includes? interaction "window.open(url, '_blank')")
                      (str/includes? interaction "pollExternalPasskey"))
          :actual :source :expected :browser-handoff-with-resume}
         {:id :dedicated-passkey-flow
          :pass? (and (str/includes? server-source "#{\"/\" \"/passkey\"}")
                      (str/includes? web-source ":id \"passkey-flow-header\"")
                      (str/includes? web-source ":id \"passkey-browser-complete\"")
                      (str/includes? interaction "document.title = 'Passkey | Cloud Itonami'"))
          :actual :source :expected :dedicated-passkey-route}
         {:id :dads-target-size
          :pass? (and (str/includes? web-source
                                     ".settings-topics button{min-height:2.75rem")
                      (str/includes? web-source
                                     "[data-view-panel='settings'] button,[data-view-panel='settings'] summary{min-height:2.75rem"))
          :actual :css :expected :minimum-44px}
         {:id :responsive-breakpoints
          :pass? (and (str/includes? web-source "@media(max-width:56rem)")
                      (str/includes? web-source "@media(max-width:35rem)"))
          :actual :css :expected #{:iphone :ipad}}
         {:id :xmile-target
          :pass? (str/includes? xmile (str "<eqn>" score "</eqn>"))
          :actual score :expected :checked-in-xmile}
         {:id :xmile-converges
          :pass? (and (= 5 (count quality))
                      (apply < quality)
                      (< (Math/abs (- score (last quality))) 0.001))
          :actual quality :expected score}]
        failed (filterv (comp not :pass?) checks)]
    {:pass? (empty? failed)
     :score score
     :minimum-score minimum-score
     :checks checks
     :failed failed}))

(defn -main [& _]
  (let [{:keys [pass? score minimum-score failed]} (audit-report)]
    (println (format "Settings UI/UX score %.2f / 100 (minimum %.2f)"
                     score minimum-score))
    (if pass?
      (println "UI/UX code invariants and XMILE trajectory: PASS")
      (do
        (binding [*out* *err*]
          (println "UI/UX audit: FAIL")
          (doseq [{:keys [id actual expected]} failed]
            (println (str "- " (name id) ": " (pr-str actual)
                          "; expected " (pr-str expected)))))
        (System/exit 1)))))
