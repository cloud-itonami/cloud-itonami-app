(ns cloud.itonami.app.business-test
  "The business plane, tested on the four things it exists to get right:

  1. a face that was never bound, a face nobody can resolve, and a face that
     resolved to nothing are three different states — never one 'empty';
  2. a business belongs to an organization, so another organization cannot see
     or bind it;
  3. binding is recorded even when the workspace cannot confirm it, because a
     released install has no workspace at all;
  4. nothing here writes to an analysis plane."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-2"})
(def anonymous {:user-id "user-3"})

(def no-workspace {})

(defn- reset-businesses! []
  (store/transact! assoc :businesses {}))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- face [faces k]
  (first (filter #(= k (:face %)) faces)))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "business-test" (into-array FileAttribute []))))

(defn- spit-under! [root relative content]
  (let [f (io/file root relative)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn- workspace-with
  "A checkout carrying just enough of the two plane files to resolve against."
  [root]
  (spit-under! root "90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn"
               (pr-str [{:canvas/product :cloud-itonami :canvas/block :lean/problem}
                        {:canvas/product :cloud-murakumo :canvas/block :lean/problem}]))
  (spit-under! root "manifest/repo-taxonomy.edn"
               (pr-str [{:repo/path "orgs/cloud-itonami/cloud-itonami-app"
                         :repo/kind "app"}]))
  {:business {:workspace-root (.getPath root)}})

;; ---------------------------------------------------------------------------
;; the entity — tenant scoped, explicitly named, nothing derived
;; ---------------------------------------------------------------------------

(deftest a-business-needs-an-organization
  (reset-businesses!)
  (testing "a session with no organization cannot create one: a business is an
            organization's record, not a person's"
    (is (= :identity/organization-required
           (refuses #(business/create! anonymous {:slug "x-y-z"})))))
  (testing "nor read the portfolio"
    (is (= :identity/organization-required
           (refuses #(business/portfolio no-workspace anonymous)))))
  (testing "the refusal is 'set an Organization ID', not 'log in again' — there
            is a session, so :identity/unauthenticated would send the client to
            fix something that is not broken"
    (is (not= :identity/unauthenticated
              (refuses #(business/create! anonymous {:slug "x-y-z"}))))))

(deftest the-slug-is-required-validated-and-unique
  (reset-businesses!)
  (is (= :business/slug-missing (refuses #(business/create! session {:slug "  "}))))
  (testing "spaces, a leading dash and anything under 3 characters are rejected"
    (doseq [bad ["cloud itonami" "-cloud" "ab"]]
      (is (= :business/slug-invalid (refuses #(business/create! session {:slug bad})))
          bad)))
  (testing "case is canonicalised rather than refused, so `Cloud-Itonami` and
            `cloud-itonami` cannot become two businesses that look like one"
    (is (= "cloud-itonami" (:business/slug (business/create!
                                           session {:slug "  Cloud-Itonami "}))))
    (is (= :business/slug-taken
           (refuses #(business/create! session {:slug "CLOUD-ITONAMI"})))))
  (business/create! session {:slug "cloud-itonami-5820"})
  (is (= :business/slug-taken
         (refuses #(business/create! session {:slug "cloud-itonami-5820"}))))
  (testing "another organization may use the same slug — uniqueness is per organization"
    (is (some? (business/create! other-session {:slug "cloud-itonami-5820"})))))

(deftest nothing-is-derived-from-the-name
  (reset-businesses!)
  (let [b (business/create! session {:slug "cloud-itonami-5820"})]
    (testing "a fresh business has no canvas, no model, no lei and no repos —
              guessing any of them from the slug would invent the binding this
              entity exists to record"
      (is (nil? (:business/canvas b)))
      (is (nil? (:business/model b)))
      (is (nil? (:business/lei b)))
      (is (= [] (:business/repos b)))
      (is (= [] (:business/adoptions b))))
    (testing "the display name falls back to the slug rather than to nil"
      (is (= "cloud-itonami-5820" (:business/name b))))))

(deftest a-business-belongs-to-one-organization
  (reset-businesses!)
  (let [b (business/create! session {:slug "cloud-itonami-5820"})
        id (:business/id b)]
    (is (some? (business/business session id)))
    (testing "another organization cannot read it"
      (is (nil? (business/business other-session id))))
    (testing "and cannot bind it — not-found rather than forbidden, because
              telling them it exists is already a leak"
      (is (= :business/not-found
             (refuses #(business/bind! other-session id {:lei "X"})))))
    (testing "nor does it appear in their portfolio"
      (is (= [] (:businesses (business/portfolio no-workspace other-session)))))))

;; ---------------------------------------------------------------------------
;; the five faces — the whole point is that these states stay distinct
;; ---------------------------------------------------------------------------

(deftest an-unbound-face-is-not-a-missing-one
  (reset-businesses!)
  (business/create! session {:slug "cloud-itonami-5820"})
  (let [row (first (:businesses (business/portfolio no-workspace session)))
        faces (:faces row)]
    (is (= 5 (count faces)))
    (testing "nothing bound: every face is :unbound, and none of them is :missing"
      (is (= #{:unbound} (set (map :state faces))))
      (is (= 0 (:bound (:coverage row)))))))

(deftest a-bound-face-with-no-workspace-is-unresolvable-not-missing
  (reset-businesses!)
  (let [b (business/create! session {:slug "cloud-itonami-5820"})]
    (business/bind! session (:business/id b)
                    {:canvas "cloud-itonami"
                     :repos ["orgs/cloud-itonami/cloud-itonami-app"]
                     :lei "ZSN2LWNPYW6ISMRUC664"
                     :model "some/model.xmile"})
    (let [row (first (:businesses (business/portfolio no-workspace session)))
          faces (:faces row)]
      (testing "with nobody having said where to look, a bound face is
                :unresolvable — reporting :missing would be a measurement of
                nothing"
        (doseq [k [:canvas :repos :lei :model]]
          (is (= :unresolvable (:state (face faces k))) (str k))))
      (testing "and the state names the setting to fix"
        (is (str/includes? (:detail (face faces :canvas)) "workspace")))
      (testing "coverage separates 'declared' from 'confirmed'"
        (is (= 4 (:bound (:coverage row))))
        (is (= 0 (:resolved (:coverage row))))
        (is (= 4 (:unresolvable (:coverage row))))))))

(deftest with-a-workspace-a-face-resolves-or-says-what-is-missing
  (reset-businesses!)
  (let [root (temp-dir)
        config (workspace-with root)
        b (business/create! session {:slug "cloud-itonami-5820"})]
    (spit-under! root "loops/itonami.xmile" "<xmile/>")
    (business/bind! session (:business/id b)
                    {:canvas "cloud-itonami"
                     :model "loops/itonami.xmile"
                     :repos ["orgs/cloud-itonami/cloud-itonami-app"]})
    (let [faces (:faces (first (:businesses (business/portfolio config session))))]
      (is (= :resolved (:state (face faces :canvas))))
      (is (= :resolved (:state (face faces :model))))
      (is (= :resolved (:state (face faces :repos)))))
    (testing "a canvas the plane does not carry is :missing, and says which key"
      (business/bind! session (:business/id b) {:canvas "no-such-product"})
      (let [f (face (:faces (first (:businesses (business/portfolio config session))))
                    :canvas)]
        (is (= :missing (:state f)))
        (is (str/includes? (:detail f) "no-such-product"))))
    (testing "a repo the taxonomy does not carry makes the face :missing, and a
              mix of found and not-found is :partial rather than resolved"
      (business/bind! session (:business/id b)
                      {:repos ["orgs/cloud-itonami/cloud-itonami-app"
                               "orgs/nope/nope"]})
      (is (= :partial
             (:state (face (:faces (first (:businesses (business/portfolio config session))))
                           :repos)))))))

(deftest an-unparseable-plane-is-reported-not-treated-as-empty
  (reset-businesses!)
  (let [root (temp-dir)
        config (workspace-with root)
        b (business/create! session {:slug "cloud-itonami-5820"})]
    (spit-under! root "90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn"
                 "[{:canvas/product :cloud-itonami")
    (business/bind! session (:business/id b) {:canvas "cloud-itonami"})
    (testing "'the plane is corrupt' and 'the product is not in it' need
              different fixes, so they are different states"
      (is (= :unreadable
             (:state (face (:faces (first (:businesses (business/portfolio config session))))
                           :canvas)))))))

(deftest a-blueprint-nobody-adopted-does-not-resolve
  (reset-businesses!)
  (store/transact! assoc :operator-adoptions {})
  (let [b (business/create! session {:slug "cloud-itonami-5820"})]
    (business/bind! session (:business/id b) {:adoptions ["cloud-itonami-isic-5820"]})
    (let [f (face (:faces (first (:businesses (business/portfolio no-workspace session))))
                  :adoptions)]
      (testing "the adoptions face needs no workspace — it reads this app's own
                store — so it is :missing rather than :unresolvable"
        (is (= :missing (:state f)))))
    (testing "a declared adoption resolves, and a withdrawn one does not"
      (store/transact! assoc :operator-adoptions
                       {"cloud-itonami-isic-5820"
                        {:adoption/repo "cloud-itonami-isic-5820"
                         :adoption/stage :deployed}})
      (is (= :resolved
             (:state (face (:faces (first (:businesses (business/portfolio no-workspace session))))
                           :adoptions))))
      (store/transact! assoc-in [:operator-adoptions "cloud-itonami-isic-5820"
                                 :adoption/stage] :withdrawn)
      (is (= :missing
             (:state (face (:faces (first (:businesses (business/portfolio no-workspace session))))
                           :adoptions)))))))

;; ---------------------------------------------------------------------------
;; the 未割当 bucket — 1,213 blueprints are not businesses
;; ---------------------------------------------------------------------------

(deftest unassigned-counts-declared-adoptions-only
  (reset-businesses!)
  (store/transact! assoc :operator-adoptions
                   {"a" {:adoption/repo "a" :adoption/stage :ready}
                    "b" {:adoption/repo "b" :adoption/stage :deployed}
                    "c" {:adoption/repo "c" :adoption/stage :withdrawn}})
  (let [b (business/create! session {:slug "cloud-itonami-5820"})]
    (business/bind! session (:business/id b) {:adoptions ["a"]})
    (let [{:keys [unassigned counts]} (business/portfolio no-workspace session)]
      (testing "'a' is bound and 'c' was withdrawn, so only 'b' is unassigned —
                the directory's other 1,210 entries are not businesses and are
                never counted here"
        (is (= 1 (:count unassigned)))
        (is (= ["b"] (mapv :adoption/repo (:adoptions unassigned))))
        (is (= 1 (:unassigned-adoptions counts))))
      (testing "the bucket names its own scope instead of implying it is
                organization-scoped, because operator adoptions are not"
        (is (= :installation (:scope unassigned)))
        (is (seq (:caveat unassigned)))))))

;; ---------------------------------------------------------------------------
;; the line this namespace must not cross
;; ---------------------------------------------------------------------------

(deftest binding-writes-nothing-but-its-own-partition
  (reset-businesses!)
  (testing "the declared write surface is exactly one path"
    (is (= [[:businesses]] (business/binds-only-locally?))))
  (let [before (store/snapshot)
        b (business/create! session {:slug "cloud-itonami-5820"})
        _ (business/bind! session (:business/id b)
                          {:canvas "cloud-itonami" :repos ["orgs/x/y"]})
        after (store/snapshot)
        changed (set (keep (fn [k] (when (not= (get before k) (get after k)) k))
                           (into (set (keys before)) (keys after))))]
    (testing "create! and bind! change :businesses and nothing else — the BMC
              base datoms are 書き換え禁止, canvas-ledger is governed append-only
              and repo-taxonomy is generated, so this namespace only ever reads
              them"
      (is (= #{:businesses} changed)))))

(deftest an-unconfirmable-binding-is-still-recorded
  (reset-businesses!)
  (let [b (business/create! session {:slug "cloud-itonami-5820"})
        bound (business/bind! session (:business/id b)
                              {:canvas "cloud-itonami" :lei "zsn2lwnpyw6ismruc664"})]
    (testing "no workspace is configured, and the binding is kept anyway:
              refusing it would make the entity unusable in exactly the released
              install that has no checkout"
      (is (= :cloud-itonami (:business/canvas bound))))
    (testing "an LEI is normalised upward, a canvas keyword loses its colon"
      (is (= "ZSN2LWNPYW6ISMRUC664" (:business/lei bound)))
      (is (= :cloud-itonami
             (:business/canvas (business/bind! session (:business/id b)
                                               {:canvas ":cloud-itonami"})))))
    (testing "an empty value clears a face rather than storing a blank one"
      (is (nil? (:business/canvas (business/bind! session (:business/id b)
                                                  {:canvas ""})))))
    (testing "a key that is absent from the request is left alone"
      (is (= "ZSN2LWNPYW6ISMRUC664"
             (:business/lei (business/bind! session (:business/id b)
                                            {:model "m.xmile"})))))))

(deftest the-workspace-state-is-named-not-guessed
  (testing "unset and configured-but-absent are different, and neither is
            silently treated as an empty checkout"
    (is (= :unset (:state (business/workspace no-workspace))))
    (is (= :missing (:state (business/workspace
                             {:business {:workspace-root "/nope/nowhere"}}))))
    (let [root (temp-dir)]
      (is (= :present (:state (business/workspace
                               {:business {:workspace-root (.getPath root)}})))))))
