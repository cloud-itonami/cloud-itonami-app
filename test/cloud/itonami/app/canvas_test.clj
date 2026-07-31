(ns cloud.itonami.app.canvas-test
  "The canvas plane, tested on the four things it exists to get right:

  1. a canvas it could not read is never rendered as an empty canvas;
  2. the app reads the FOLDED projection and folds nothing itself;
  3. a proposal's landed-ness is measured against the projection, never stored;
  4. nothing here writes to the canvas ledger."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.canvas :as canvas]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-2"})
(def no-workspace {})

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "canvas-test" (into-array FileAttribute []))))

(defn- reset-all! []
  (store/transact! #(assoc % :businesses {} :canvas-proposals {})))

(def ^:private projection
  "A projection shaped exactly like `gftd canvas datoms` emits: a header, blocks
  carrying `:canvas/order`, and a hypothesis with both a ledger status and a gate
  verdict — the pair whose namespaces collide on the wire."
  [{:db/id -1 :projection/id "canvas-cloud-itonami"
    :projection/product :cloud-itonami :projection/as-of "2026-07-30"
    :projection/layer :business-operator
    :projection/blocks 2 :projection/hypotheses 1
    :projection/source "gftd canvas datoms" :source/dataset "canvas-projection"}
   {:db/id -2 :canvas/id :cloud-itonami.problem :canvas/product :cloud-itonami
    :canvas/block :lean/problem :canvas/label "Problem" :canvas/order 0
    :canvas/items ["p1" "p2"] :source/dataset "canvas-projection"}
   {:db/id -3 :canvas/id :cloud-itonami.uvp :canvas/product :cloud-itonami
    :canvas/block :lean/uvp :canvas/label "UVP" :canvas/order 1
    :canvas/items [] :canvas/note "n1" :source/dataset "canvas-projection"}
   {:db/id -4 :hyp/id :hyp/t1 :hyp/product :cloud-itonami :hyp/claim "claim-1"
    :hyp/gate "gate-1" :hyp/risk :riskiest :hyp/status :untested
    :gate/status :measuring :gate/distance "あと 3"
    :source/dataset "canvas-projection"}])

(defn- workspace-with
  ([root] (workspace-with root projection))
  ([root tx]
   (let [f (io/file root "90-docs/business/cloud-itonami-canvas.datoms.edn")]
     (.mkdirs (.getParentFile f))
     (spit f (if (string? tx) tx (pr-str tx)))
     {:business {:workspace-root (.getPath root)}})))

(defn- a-business!
  "The fixture business, created once per test. Idempotent because several tests
  read the same business under two different workspaces, and a slug is unique
  within an organization."
  ([] (a-business! {:canvas "cloud-itonami"}))
  ([bindings]
   (let [existing (first (filter #(= "cloud-itonami-5820" (:business/slug %))
                                 (business/businesses session)))
         b (or existing (business/create! session {:slug "cloud-itonami-5820"}))]
     (if (seq bindings) (business/bind! session (:business/id b) bindings) b))))

;; ---------------------------------------------------------------------------
;; reading — an unreadable canvas is never an empty canvas
;; ---------------------------------------------------------------------------

(deftest a-business-with-no-canvas-says-so
  (reset-all!)
  (let [b (a-business! {})
        c (canvas/canvas no-workspace b)]
    (is (= :unbound (:state c)))
    (is (nil? (:blocks c)))))

(deftest a-bound-canvas-with-no-workspace-is-unresolvable
  (reset-all!)
  (let [c (canvas/canvas no-workspace (a-business!))]
    (testing "not :missing — nobody said where to look, so nothing was measured"
      (is (= :unresolvable (:state c))))
    (testing "and the path it would have read is named, so the fix is visible"
      (is (= "90-docs/business/cloud-itonami-canvas.datoms.edn" (:source c))))))

(deftest a-workspace-without-the-projection-names-the-generator
  (reset-all!)
  (let [c (canvas/canvas {:business {:workspace-root (.getPath (temp-dir))}}
                         (a-business!))]
    (is (= :missing (:state c)))
    (testing "the detail says how to produce it rather than only that it is absent"
      (is (str/includes? (:detail c) "gftd canvas datoms")))))

(deftest an-unparseable-projection-is-reported-not-treated-as-empty
  (reset-all!)
  (let [config (workspace-with (temp-dir) "[{:canvas/id :x")
        c (canvas/canvas config (a-business!))]
    (is (= :unreadable (:state c)))
    (is (nil? (:blocks c)))))

(deftest the-canvas-is-read-in-projection-order-with-the-folded-values
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        c (canvas/canvas config (a-business!))]
    (is (= :resolved (:state c)))
    (testing "blocks come back in :canvas/order, which travels as data rather
              than being re-derived here"
      (is (= ["cloud-itonami.problem" "cloud-itonami.uvp"] (mapv :id (:blocks c))))
      (is (= [0 1] (mapv :order (:blocks c)))))
    (testing "items and notes are whatever the projection says — this app folds
              nothing"
      (is (= ["p1" "p2"] (:items (first (:blocks c)))))
      (is (= "n1" (:note (second (:blocks c))))))
    (testing "an empty block is carried as empty, not dropped: a lean canvas with
              a blank UVP is a real state"
      (is (= [] (:items (second (:blocks c))))))
    (is (= "2026-07-30" (:as-of c)))))

(deftest the-riskiest-hypothesis-comes-from-the-data
  (reset-all!)
  (let [config (workspace-with (temp-dir))]
    (is (= "hyp/t1" (:riskiest-hyp (canvas/canvas config (a-business!)))))
    (testing "a canvas that marked none gets none, rather than the first one
              being lit up arbitrarily"
      (let [tx (mapv #(dissoc % :hyp/risk) projection)
            c (canvas/canvas (workspace-with (temp-dir) tx) (a-business!))]
        (is (nil? (:riskiest-hyp c)))))
    (testing "the block a hypothesis is about is absent, not inferred — no
              attribute links them"
      (is (nil? (:riskiest-block (canvas/canvas config (a-business!))))))))

(deftest a-truncated-projection-does-not-look-complete
  (reset-all!)
  (let [full (canvas/canvas (workspace-with (temp-dir)) (a-business!))
        short (canvas/canvas (workspace-with (temp-dir) (vec (butlast projection)))
                             (a-business!))]
    (testing "the header declares its own counts, so a file missing an entity
              disagrees with itself and says so"
      (is (true? (:complete? (:counts full))))
      (is (false? (:complete? (:counts short))))
      (is (= 1 (:declared-hypotheses (:counts short))))
      (is (= 0 (:hypotheses (:counts short)))))))

;; ---------------------------------------------------------------------------
;; the wire contract that the collisions made necessary
;; ---------------------------------------------------------------------------

(deftest no-two-keys-collide-once-namespaces-are-dropped
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        c (canvas/canvas config (a-business!))
        h (first (:hypotheses c))
        collides? (fn [m] (let [ks (keys m)]
                            (not= (count ks) (count (set (map name ks))))))]
    (testing "clojure.data.json drops a keyword key's namespace, so :hyp/status
              and :gate/status would both arrive as `status` and one would win by
              map iteration order. The re-keyed shape has no such pair."
      (is (not (collides? h)))
      (is (every? (complement collides?) (:blocks c))))
    (testing "and both statuses survive separately, which is the pair this view
              exists to show: the ledger's verdict and the metrics' verdict"
      (is (= :untested (:status h)))
      (is (= :measuring (:gate-status h)))
      (is (= "あと 3" (:gate-distance h))))
    (testing "ids keep their namespace, because a reader copies them into a
              command"
      (is (= "hyp/t1" (:id h)))
      (is (= "lean/problem" (:block (first (:blocks c))))))
    (testing "the transaction's :db/id is not on the wire at all — it collides
              with :canvas/id and means nothing to a client"
      (is (not (contains? (first (:blocks c)) :db/id))))))


;; ---------------------------------------------------------------------------
;; maturity — a judgement nobody recorded is not a low score
;; ---------------------------------------------------------------------------

(def ^:private maturity-tx
  [{:db/id -1 :projection/id "maturity-scores" :projection/as-of "2026-07-30"
    :projection/products 1 :source/dataset "maturity-scores"}
   {:db/id -2 :score/product :cloud-itonami :score/bmc 78.0 :score/yc 61.0
    :score/unrecorded-dims 1 :score/unrecorded ["defensibility"]
    :source/dataset "maturity-scores"}
   {:db/id -3 :dim/product :cloud-itonami :dim/name :completeness :dim/value 5.0
    :dim/source :auto :dim/recorded? true :source/dataset "maturity-scores"}
   {:db/id -4 :dim/product :cloud-itonami :dim/name :pricing :dim/value 3.0
    :dim/source :facts :dim/recorded? true :source/dataset "maturity-scores"}
   {:db/id -5 :dim/product :cloud-itonami :dim/name :defensibility :dim/value 0.0
    :dim/source :facts :dim/recorded? false :source/dataset "maturity-scores"}
   {:db/id -6 :dim/product :other-product :dim/name :pricing :dim/value 1.0
    :dim/source :facts :dim/recorded? true :source/dataset "maturity-scores"}])

(defn- with-maturity [root tx]
  (let [f (io/file root "90-docs/business/maturity-scores.datoms.edn")]
    (.mkdirs (.getParentFile f))
    (spit f (if (string? tx) tx (pr-str tx)))
    {:business {:workspace-root (.getPath root)}}))

(deftest maturity-separates-computed-dimensions-from-recorded-judgements
  (reset-all!)
  (let [root (temp-dir)
        _ (workspace-with root)
        config (with-maturity root maturity-tx)
        m (:maturity (canvas/snapshot config session (:business/id (a-business!))))
        by-name (into {} (map (juxt :name identity)) (:dims m))]
    (is (= :resolved (:state m)))
    (is (= 78.0 (:bmc m)))
    (is (= "2026-07-30" (:as-of m)))
    (testing "only this product's dimensions"
      (is (= 3 (count (:dims m)))))
    (testing "three of the fourteen are computed from the canvas; the rest are
              judgements somebody entered, and the reader should not have to guess"
      (is (= :auto (:source (by-name "completeness"))))
      (is (= :facts (:source (by-name "pricing")))))
    (testing "an unrecorded judgement is flagged, and its 0 is not a verdict —
              the generator defaults absent facts to the worst value"
      (is (false? (:recorded? (by-name "defensibility"))))
      (is (= 0.0 (:value (by-name "defensibility"))))
      (is (= 1 (:unrecorded-dims m)))
      (is (= ["defensibility"] (:unrecorded m))))))

(deftest a-product-outside-the-portfolio-is-missing-not-zero
  (reset-all!)
  (let [root (temp-dir)
        _ (workspace-with root)
        config (with-maturity root maturity-tx)
        b (a-business! {:canvas "not-a-portfolio-product"})
        m (:maturity (canvas/snapshot config session (:business/id b)))]
    (testing "the gftd portfolio has twelve products; a business bound to
              anything else has no score rather than a zero one"
      (is (= :missing (:state m)))
      (is (str/includes? (:detail m) "投影にありません")))))

(deftest maturity-states-mirror-the-canvas-states
  (reset-all!)
  (let [b (a-business!)]
    (is (= :unresolvable (:state (:maturity (canvas/snapshot no-workspace session
                                                             (:business/id b))))))
    (let [root (temp-dir)
          config (workspace-with root)]
      (testing "a workspace without the projection names the generator"
        (let [m (:maturity (canvas/snapshot config session (:business/id b)))]
          (is (= :missing (:state m)))
          (is (str/includes? (:detail m) "score datoms"))))
      (testing "and an unparseable projection is reported, not treated as empty"
        (let [config (with-maturity root "[{:score/product")]
          (is (= :unreadable (:state (:maturity (canvas/snapshot
                                                 config session (:business/id b))))))))))
  (reset-all!)
  (testing "a business with no canvas has no product to score"
    (is (= :unbound (:state (:maturity (canvas/snapshot no-workspace session
                                                        (:business/id (a-business! {})))))))))

;; ---------------------------------------------------------------------------
;; proposals — recorded, never applied; landed-ness measured, never stored
;; ---------------------------------------------------------------------------

(deftest a-proposal-needs-a-canvas-a-block-a-value-and-a-proposer
  (reset-all!)
  (let [b (a-business! {})
        id (:business/id b)]
    (testing "a business with no canvas has nothing to propose against"
      (is (= :canvas/product-unbound
             (refuses #(canvas/propose! session id {:action "add-item"
                                                   :canvas-id "x" :value "v" :by "y"})))))
    (business/bind! session id {:canvas "cloud-itonami"})
    (is (= :canvas/action-unsupported
           (refuses #(canvas/propose! session id {:action "rewrite-everything"
                                                  :canvas-id "x" :value "v" :by "y"}))))
    (is (= :canvas/canvas-id-missing
           (refuses #(canvas/propose! session id {:action "add-item"
                                                  :canvas-id " " :value "v" :by "y"}))))
    (is (= :canvas/value-missing
           (refuses #(canvas/propose! session id {:action "add-item"
                                                  :canvas-id "x" :value "" :by "y"}))))
    (testing "an anonymous proposal is not a proposal — nobody's judgement is
              recorded"
      (is (= :canvas/anonymous-proposal
             (refuses #(canvas/propose! session id {:action "add-item"
                                                    :canvas-id "x" :value "v"})))))
    (testing "another organization cannot propose against this business"
      (is (= :business/not-found
             (refuses #(canvas/propose! other-session id {:action "add-item"
                                                          :canvas-id "x" :value "v"
                                                          :by "y"})))))))

(deftest a-proposal-is-recorded-even-when-nothing-can-confirm-it
  (reset-all!)
  (let [b (a-business!)
        p (canvas/propose! session (:business/id b)
                           {:action "add-item" :canvas-id "cloud-itonami.problem"
                            :value "p3" :by "山田" :reason "検証結果"})]
    (is (= :canvas/add-item (:proposal/action p)))
    (testing "the action is normalised into the ledger's own vocabulary, so a
              bare `add-item` and `:canvas/add-item` mean the same thing"
      (is (= :canvas/add-item
             (:proposal/action (canvas/propose! session (:business/id b)
                                                {:action ":canvas/add-item"
                                                 :canvas-id "cloud-itonami.uvp"
                                                 :value "u9" :by "山田"})))))
    (testing "with no workspace the state is neither awaiting nor landed"
      (let [snap (canvas/snapshot no-workspace session (:business/id b))]
        (is (= #{:unverifiable} (set (map :state (:proposals snap)))))))))

(deftest landed-is-read-out-of-the-projection-not-stored
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business!)
        id (:business/id b)
        already (canvas/propose! session id {:action "add-item"
                                             :canvas-id "cloud-itonami.problem"
                                             :value "p1" :by "山田"})
        pending (canvas/propose! session id {:action "add-item"
                                             :canvas-id "cloud-itonami.problem"
                                             :value "p9" :by "山田"})
        by-id (into {} (map (juxt :id identity))
                    (:proposals (canvas/snapshot config session id)))]
    (testing "the projection already carries p1, so that proposal has landed —
              and nothing set a flag to say so"
      (is (= :landed (:state (get by-id (:proposal/id already))))))
    (is (= :awaiting-governor (:state (get by-id (:proposal/id pending)))))
    (testing "a landed proposal is offered no command: an append-only event
              applied twice is not idempotent"
      (is (nil? (:command (get by-id (:proposal/id already)))))
      (is (str/includes? (:command (get by-id (:proposal/id pending)))
                         "gftd.cljs canvas add")))))

(deftest a-retraction-lands-only-when-the-block-was-actually-read
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business!)
        id (:business/id b)
        real (canvas/propose! session id {:action "retract-item"
                                          :canvas-id "cloud-itonami.problem"
                                          :value "p1" :by "山田"})
        nowhere (canvas/propose! session id {:action "retract-item"
                                             :canvas-id "cloud-itonami.nosuchblock"
                                             :value "anything" :by "山田"})
        by-id (into {} (map (juxt :id identity))
                    (:proposals (canvas/snapshot config session id)))]
    (testing "p1 is still there, so the retraction has not landed"
      (is (= :awaiting-governor (:state (get by-id (:proposal/id real))))))
    (testing "a block the projection does not have must not make every retraction
              read as landed — that is the difference between 「消えた」 and
              「見ていない」"
      (is (= :awaiting-governor (:state (get by-id (:proposal/id nowhere))))))))

(deftest a-note-proposal-lands-on-an-exact-match
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business!)
        id (:business/id b)
        same (canvas/propose! session id {:action "note" :canvas-id "cloud-itonami.uvp"
                                          :value "n1" :by "山田"})
        other (canvas/propose! session id {:action "note" :canvas-id "cloud-itonami.uvp"
                                           :value "n2" :by "山田"})
        by-id (into {} (map (juxt :id identity))
                    (:proposals (canvas/snapshot config session id)))]
    (is (= :landed (:state (get by-id (:proposal/id same)))))
    (is (= :awaiting-governor (:state (get by-id (:proposal/id other)))))))

(deftest withdrawing-keeps-the-record
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business!)
        id (:business/id b)
        p (canvas/propose! session id {:action "add-item"
                                       :canvas-id "cloud-itonami.problem"
                                       :value "p9" :by "山田"})]
    (canvas/withdraw! session (:proposal/id p) {:by "山田"})
    (let [w (first (:proposals (canvas/snapshot config session id)))]
      (testing "withdrawn is the one state a human sets, because 「もう要らない」
                is not something a projection can show"
        (is (= :withdrawn (:state w)))
        (is (= "山田" (:withdrawn-by w))))
      (testing "the proposal is still there — raised-and-abandoned is a different
                fact from never-made"
        (is (= 1 (count (:proposals (canvas/snapshot config session id)))))))
    (testing "another organization cannot withdraw it"
      (is (= :canvas/proposal-not-found
             (refuses #(canvas/withdraw! other-session (:proposal/id p) {:by "x"})))))))

(deftest the-badge-counts-only-what-is-still-waiting
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business!)
        id (:business/id b)]
    (canvas/propose! session id {:action "add-item" :canvas-id "cloud-itonami.problem"
                                 :value "p1" :by "山田"})
    (canvas/propose! session id {:action "add-item" :canvas-id "cloud-itonami.problem"
                                 :value "p9" :by "山田"})
    (let [c (canvas/counts config session)]
      (testing "a landed proposal is finished work; padding the badge with it
                would make the number stop meaning anything"
        (is (= 2 (:proposals c)))
        (is (= 1 (:awaiting c)))
        (is (= 1 (:landed c)))))))

;; ---------------------------------------------------------------------------
;; the line this namespace must not cross
;; ---------------------------------------------------------------------------

(deftest proposing-writes-nothing-but-its-own-partition
  (reset-all!)
  (testing "the declared write surface is exactly one path, and the canvas ledger
            is not in it"
    (is (= [[:canvas-proposals]] (canvas/writes-only-locally?))))
  (let [b (a-business!)
        before (store/snapshot)
        p (canvas/propose! session (:business/id b)
                           {:action "add-item" :canvas-id "cloud-itonami.problem"
                            :value "p9" :by "山田"})
        _ (canvas/withdraw! session (:proposal/id p) {:by "山田"})
        after (store/snapshot)
        changed (set (keep (fn [k] (when (not= (get before k) (get after k)) k))
                           (into (set (keys before)) (keys after))))]
    (testing "propose! and withdraw! change :canvas-proposals and nothing else —
              canvas-ledger.edn is append-only and governed, and this app has no
              governor"
      (is (= #{:canvas-proposals} changed)))))

(deftest the-projection-file-is-never-written
  (reset-all!)
  (let [root (temp-dir)
        config (workspace-with root)
        f (io/file root "90-docs/business/cloud-itonami-canvas.datoms.edn")
        before (slurp f)
        b (a-business!)]
    (canvas/propose! session (:business/id b) {:action "add-item"
                                               :canvas-id "cloud-itonami.problem"
                                               :value "p9" :by "山田"})
    (canvas/snapshot config session (:business/id b))
    (testing "reading and proposing leave the upstream projection byte-identical:
              it is a generated artifact of another tool's fold"
      (is (= before (slurp f))))))
