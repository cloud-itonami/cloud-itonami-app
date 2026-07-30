(ns cloud.itonami.app.repos-test
  "The repos plane, tested on the one thing it exists to get right: an axis
  nobody scored is not a zero, and no average pretends otherwise."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.repos :as repos]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def no-workspace {})

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "repos-test" (into-array FileAttribute []))))

(defn- reset-all! []
  (store/transact! #(assoc % :businesses {} :operator-adoptions {})))

(defn- spit-under! [root rel content]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    (spit f (if (string? content) content (pr-str content)))
    f))

(def ^:private taxonomy
  [{:repo/path "orgs/cloud-itonami/cloud-itonami-app" :repo/name "cloud-itonami-app"
    :repo/org "cloud-itonami" :repo/kind "app"
    :repo/kind-evidence "src + index.html" :repo/traits "#{:source :tests :ui}"}
   {:repo/path "orgs/cloud-itonami/scored-nothing" :repo/name "scored-nothing"
    :repo/org "cloud-itonami" :repo/kind "unclassified"
    :repo/kind-evidence "no classifying marker"}])

(def ^:private maturity
  [{:repo/path "orgs/cloud-itonami/cloud-itonami-app"
    :repo/org "cloud-itonami" :repo/name "cloud-itonami-app"
    :repo/pinned-revision "aaa8960"
    :maturity/composite 0.72
    :maturity/stage-score 0.8 :maturity/structural-score 1.0
    :maturity/activity-score 0.9
    :maturity/impl-score 0.5 :maturity/impl-score-method :size-and-scaffold-marker-heuristic
    :maturity/impl-detail "size 20MB"
    ;; The real generator leaves these nil for most repos.
    :maturity/coverage-score nil
    :maturity/computed-at "2026-07-30T00:00:00Z"}
   {:repo/path "orgs/cloud-itonami/scored-nothing"
    :repo/org "cloud-itonami" :repo/name "scored-nothing"
    ;; composite absent entirely — the case an average must not silently
    ;; swallow as 0.
    :maturity/structural-score 0.25}])

(defn- workspace-with
  ([root] (workspace-with root taxonomy maturity))
  ([root tax mat]
   (spit-under! root repos/taxonomy-path tax)
   (spit-under! root repos/maturity-path mat)
   {:business {:workspace-root (.getPath root)}}))

(defn- a-business! [bindings]
  (let [existing (first (filter #(= "b-one" (:business/slug %))
                                (business/businesses session)))
        b (or existing (business/create! session {:slug "b-one"}))]
    (business/bind! session (:business/id b) bindings)))

(defn- axis-of [row a]
  (first (filter #(= a (:axis %)) (:axes row))))

;; ---------------------------------------------------------------------------
;; the plane
;; ---------------------------------------------------------------------------

(deftest without-a-workspace-there-are-no-scores-and-no-zeros
  (reset-all!)
  (let [b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"]})
        s (repos/snapshot no-workspace session (:business/id b))]
    (is (= :unresolvable (:state (:plane s))))
    (testing "the repo is still listed — the binding is a real fact — but with no
              composite and no axes rather than a row of zeros"
      (is (= 1 (count (:repos s))))
      (is (nil? (:composite (first (:repos s)))))
      (is (nil? (:axes (first (:repos s))))))
    (testing "and the average is nil, because there is no mean of no numbers"
      (is (nil? (:mean-composite (:roll-up s))))
      (is (= 1 (:unscored (:roll-up s)))))))

(deftest a-missing-plane-names-the-generator
  (reset-all!)
  (let [b (a-business! {:repos ["orgs/x/y"]})
        s (repos/snapshot {:business {:workspace-root (.getPath (temp-dir))}}
                          session (:business/id b))]
    (is (= :missing (:state (:plane s))))
    (is (str/includes? (:detail (:plane s)) "repo-maturity.cljs"))))

(deftest one-unreadable-plane-fails-the-pair
  (reset-all!)
  (let [root (temp-dir)
        config (workspace-with root taxonomy "[{:repo/path")
        b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"]})]
    (testing "taxonomy without maturity would render a kind with no scores and
              read as 「評価が 0」, so the pair gets one verdict"
      (is (= :unreadable (:state (:plane (repos/snapshot config session
                                                        (:business/id b)))))))))

;; ---------------------------------------------------------------------------
;; the axes — the load-bearing distinction
;; ---------------------------------------------------------------------------

(deftest an-unscored-axis-is-marked-unscored-not-zero
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"]})
        row (first (:repos (repos/snapshot config session (:business/id b))))]
    (is (= 5 (count (:axes row))))
    (testing "a scored axis carries its number"
      (is (true? (:scored? (axis-of row :stage))))
      (is (= 0.8 (:score (axis-of row :stage)))))
    (testing "a nil axis carries no score at all — not 0.0"
      (let [cov (axis-of row :coverage)]
        (is (false? (:scored? cov)))
        (is (not (contains? cov :score)))
        (is (str/includes? (:detail cov) "0 ではなく"))))
    (testing "the heuristic's method rides with its score, so a proxy and a
              parsed marker are not two identical-looking decimals"
      (is (= :size-and-scaffold-marker-heuristic (:method (axis-of row :impl))))
      (is (nil? (:method (axis-of row :stage)))))))

(deftest the-average-covers-only-the-repos-that-have-a-score
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"
                                "orgs/cloud-itonami/scored-nothing"]})
        roll (:roll-up (repos/snapshot config session (:business/id b)))]
    (is (= 2 (:repos roll)))
    (is (= 1 (:scored roll)))
    (is (= 1 (:unscored roll)))
    (testing "0.72, not 0.36 — folding the unscored repo in as a zero would
              halve a number nobody measured"
      (is (= 0.72 (:mean-composite roll))))))

(deftest a-repo-the-plane-does-not-carry-says-so
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:repos ["orgs/nope/nope"]})
        row (first (:repos (repos/snapshot config session (:business/id b))))]
    (is (false? (:present row)))
    (is (str/includes? (:detail row) "west 登録"))
    (is (nil? (:composite row)))))

;; ---------------------------------------------------------------------------
;; where a repo came from
;; ---------------------------------------------------------------------------

;; A blueprint that really is in the shipped catalog. `cloud-itonami-app` is not
;; — the app itself carries no blueprint.edn, so it is not an actor — and using it
;; here is what made the first version of this test fail.
(def ^:private catalogued-repo "action-loop-system-dynamics")
(def ^:private catalogued-path "orgs/kotoba-lang/action-loop-system-dynamics")

(deftest a-declared-repo-and-an-adopted-blueprint-are-distinguished
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"]
                        :adoptions [catalogued-repo]})
        rows (:repos (repos/snapshot config session (:business/id b)))
        by-source (group-by :source rows)]
    (is (= 1 (count (:repos by-source))))
    (is (= 1 (count (:adoptions by-source))))
    (testing "an adoption with no declaration says that, AND keeps whatever else
              is true of it — the reasons accumulate rather than overwrite"
      (let [d (:detail (first (:adoptions by-source)))]
        (is (str/includes? d "参与が表明されていません"))
        (is (str/includes? d "生成 plane にありません"))))
    (testing "the adopted blueprint's workspace path comes from the fleet
              catalog, never from string-concatenating org and repo"
      (is (= catalogued-path (:path (first (:adoptions by-source))))))))

(deftest an-adoption-outside-the-catalog-cannot-be-scored-and-says-why
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:adoptions ["not-a-real-blueprint"]})
        row (first (:repos (repos/snapshot config session (:business/id b))))]
    (is (nil? (:path row)))
    (testing "both reasons survive: not in the catalog AND not declared. A
              cond-> chain let the second overwrite the first, which is what this
              assertion caught."
      (is (str/includes? (:detail row) "fleet catalog"))
      (is (str/includes? (:detail row) "参与が表明されていません")))))

(deftest the-snapshot-is-organization-scoped-and-writes-nothing
  (reset-all!)
  (let [config (workspace-with (temp-dir))
        b (a-business! {:repos ["orgs/cloud-itonami/cloud-itonami-app"]})
        before (store/snapshot)]
    (is (nil? (repos/snapshot config {:user-id "u" :organization-id "org-2"}
                              (:business/id b))))
    (repos/snapshot config session (:business/id b))
    (testing "this plane has no write path"
      (is (= before (store/snapshot))))))
