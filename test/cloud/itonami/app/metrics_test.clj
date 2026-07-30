(ns cloud.itonami.app.metrics-test
  "The metrics plane, tested on the three things it exists to get right:

  1. a stale measurement is marked stale, and undated is neither fresh nor stale;
  2. requests never travel without the quality figures that qualify them;
  3. product-specific shapes are passed through, never unified into a funnel this
     app has no basis to define."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.metrics :as metrics]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def no-workspace {})
(def ^:private now (Instant/parse "2026-07-30T12:00:00Z"))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "metrics-test" (into-array FileAttribute []))))

(defn- reset-all! [] (store/transact! assoc :businesses {}))

(defn- write-metrics! [root product m]
  (let [f (io/file root (str "90-docs/business/metrics/" product ".edn"))]
    (.mkdirs (.getParentFile f))
    (spit f (if (string? m) m (pr-str m)))
    {:business {:workspace-root (.getPath root)}}))

(defn- a-business! [bindings]
  (let [existing (first (filter #(= "b-one" (:business/slug %))
                                (business/businesses session)))
        b (or existing (business/create! session {:slug "b-one"}))]
    (business/bind! session (:business/id b) bindings)))

;; Shaped after the real cloud-itonami file, including its product-specific
;; :funnel and :tenants and its high probe-4xx share.
(def ^:private real-ish
  {:asOf "2026-07-30T11:11:56.942Z"
   :as-of "2026-07-30"
   :zone-name "itonami.cloud"
   :zone {:requests-7d 21491 :pageviews-7d 25 :uniques-7d-sum 147 :zone "itonami.cloud"}
   :traffic-quality {:window "24h" :probe-4xx-pct 57 :error-5xx-pct 0}
   :health-status 200
   :sources [:cloudflare :health :emitter]
   :signal "itonami.cloud 実測 21491 req/7d・147 uniques"
   :funnel {:trials 5 :freeClaims 5 :externalTenants 5 :paid 0}
   :tenants {:active 5 :external-paid 0}
   :agentRuns7d 2254})

;; ---------------------------------------------------------------------------
;; freshness — the first thing, not a footnote
;; ---------------------------------------------------------------------------

(deftest freshness-distinguishes-fresh-stale-and-undated
  (testing "a same-day timestamp is fresh"
    (is (= :fresh (:state (metrics/freshness "2026-07-30T11:00:00Z" now 3)))))
  (testing "a bare date parses too, because real files use both forms"
    (is (= :fresh (:state (metrics/freshness "2026-07-29" now 3)))))
  (testing "28 days is stale — this is ai-gftd-yukkuri's real :as-of"
    (let [f (metrics/freshness "2026-07-02" now 3)]
      (is (= :stale (:state f)))
      (is (< 28.0 (:age-days f) 28.6))))
  (testing "the boundary is inclusive, so exactly max-age is still fresh"
    (is (= :fresh (:state (metrics/freshness "2026-07-27T12:00:00Z" now 3))))
    (is (= :stale (:state (metrics/freshness "2026-07-27T11:59:00Z" now 3)))))
  (testing "no :as-of is :undated, which is neither fresh nor stale — it did not
            measure late, it declined to say when"
    (doseq [v [nil "" "   " "not-a-date"]]
      (is (= :undated (:state (metrics/freshness v now 3))) (pr-str v)))))

(deftest a-stale-file-is-still-read-but-marked
  (reset-all!)
  (let [root (temp-dir)
        config (write-metrics! root "yukkuri" (assoc real-ish :asOf "2026-07-02"
                                                    :as-of "2026-07-02"))
        b (a-business! {:canvas "yukkuri"})
        s (metrics/snapshot config session (:business/id b) now)]
    (testing "the numbers are returned — refusing to show them would hide the
              product — but the verdict says they are not current"
      (is (= :resolved (:state s)))
      (is (= :stale (:state (:freshness s))))
      (is (= 21491 (:requests-7d (:traffic s)))))))

(deftest the-max-age-is-configurable
  (reset-all!)
  (let [root (temp-dir)
        base (write-metrics! root "p" (assoc real-ish :asOf "2026-07-25T12:00:00Z"))
        b (a-business! {:canvas "p"})]
    (is (= :stale (:state (:freshness (metrics/snapshot base session
                                                       (:business/id b) now)))))
    (is (= :fresh (:state (:freshness (metrics/snapshot
                                       (assoc-in base [:business :metrics-max-age-days] 10)
                                       session (:business/id b) now)))))))

;; ---------------------------------------------------------------------------
;; traffic and its quality
;; ---------------------------------------------------------------------------

(deftest requests-never-arrive-without-the-figures-that-qualify-them
  (reset-all!)
  (let [config (write-metrics! (temp-dir) "p" real-ish)
        b (a-business! {:canvas "p"})
        t (:traffic (metrics/snapshot config session (:business/id b) now))]
    (is (= 21491 (:requests-7d t)))
    (testing "the quality percentages are in the SAME map, so a renderer handed
              the request count was also handed the reason it is not an audience"
      (is (= 57 (:probe-4xx-pct t)))
      (is (= 0 (:error-5xx-pct t)))
      (is (= "24h" (:window t))))
    (testing "and at 57% probe traffic the caveat is spelled out rather than left
              to the reader's arithmetic"
      (is (str/includes? (:caveat t) "訪問者数として読めません")))))

(deftest clean-traffic-gets-no-caveat
  (reset-all!)
  (let [config (write-metrics! (temp-dir) "p"
                               (assoc real-ish :traffic-quality
                                      {:window "24h" :probe-4xx-pct 0 :error-5xx-pct 0}))
        b (a-business! {:canvas "p"})
        t (:traffic (metrics/snapshot config session (:business/id b) now))]
    (testing "a caveat on every product would stop meaning anything"
      (is (nil? (:caveat t))))))

;; ---------------------------------------------------------------------------
;; the shapes this namespace refuses to unify
;; ---------------------------------------------------------------------------

(deftest product-specific-keys-are-passed-through-not-interpreted
  (reset-all!)
  (let [config (write-metrics! (temp-dir) "p" real-ish)
        b (a-business! {:canvas "p"})
        s (metrics/snapshot config session (:business/id b) now)
        specific (into {} (map (juxt :key identity)) (:product-specific s))]
    (testing ":funnel is carried under its own name with its own shape — mapping
              cloud-itonami's :freeClaims onto club-shinshi's :chatters would be
              a product judgement this app has no basis for"
      (is (contains? specific "funnel"))
      (is (= :map (:shape (get specific "funnel"))))
      (is (str/includes? (:value (get specific "funnel")) ":freeClaims 5")))
    (testing "scalars stay scalars, so a number is not stringified for no reason"
      (is (= 2254 (:value (get specific "agentRuns7d"))))
      (is (= :number (:shape (get specific "agentRuns7d")))))
    (testing "keys this namespace DOES understand are not duplicated into the
              passthrough"
      (doseq [k ["zone" "traffic-quality" "signal" "sources" "as-of" "asOf"]]
        (is (not (contains? specific k)) k)))))

;; ---------------------------------------------------------------------------
;; the absent cases
;; ---------------------------------------------------------------------------

(deftest an-unbound-canvas-has-no-metrics-to-find
  (reset-all!)
  (let [b (a-business! {})
        s (metrics/snapshot no-workspace session (:business/id b) now)]
    (is (= :unbound (:state s)))
    (testing "and it says why: metrics are keyed by the canvas product"
      (is (str/includes? (:detail s) ":canvas/product")))))

(deftest missing-unreadable-and-unresolvable-are-three-states
  (reset-all!)
  (let [b (a-business! {:canvas "p"})]
    (is (= :unresolvable (:state (metrics/snapshot no-workspace session
                                                  (:business/id b) now))))
    (is (= :missing (:state (metrics/snapshot
                             {:business {:workspace-root (.getPath (temp-dir))}}
                             session (:business/id b) now))))
    (let [config (write-metrics! (temp-dir) "p" "{:as-of")]
      (is (= :unreadable (:state (metrics/snapshot config session
                                                   (:business/id b) now)))))
    (testing "a file that parses but is not a map is unreadable, not empty"
      (let [config (write-metrics! (temp-dir) "p" "[1 2 3]")]
        (is (= :unreadable (:state (metrics/snapshot config session
                                                     (:business/id b) now))))))))

(deftest the-snapshot-is-organization-scoped-and-writes-nothing
  (reset-all!)
  (let [config (write-metrics! (temp-dir) "p" real-ish)
        b (a-business! {:canvas "p"})
        before (store/snapshot)]
    (is (nil? (metrics/snapshot config {:user-id "u" :organization-id "org-2"}
                                (:business/id b) now)))
    (metrics/snapshot config session (:business/id b) now)
    (is (= before (store/snapshot)))))
