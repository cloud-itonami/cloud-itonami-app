(ns cloud.itonami.app.vf-authority-test
  "ADR-2609111230 slice 2: the capability → VF scope mapping and its proofs.

  The ADR's Verification section names the class this slice owes:
  every VF capability maps to exactly one scope; `covers?` on the new
  scopes passes the `kotoba://graph/alice*` / `alice-evil` class of test
  (a prefix must not cross a bot or org boundary); a read-only bot's
  authority does not reach append. All verdicts come from
  `authority.scope` — no comparison is written here."
  (:require [clojure.test :refer [deftest is testing]]
            [authority.scope :as scope]
            [authority.grant :as grant]
            [cloud.itonami.app.vf-authority :as vf]))

(def org :org-a)

(defn- grant-for [& scope-strings]
  (grant/grant {:scopes (vec scope-strings) :holder "did:key:z6MkTest"}))

(deftest scopes-have-the-wire-shape-and-parse
  (testing "the ADR's form, byte for byte"
    (is (= "kotoba://vf/org-a/event/read" (vf/read-scope org)))
    (is (= "kotoba://vf/org-a/event/append" (vf/append-scope org)))
    (is (= "kotoba://vf/org-a/commitment/commit" (vf/commit-scope org))))
  (testing "every wire scope parses back through the one decider's parser"
    (doseq [s [(vf/read-scope org) (vf/append-scope org) (vf/commit-scope org)]]
      (is (some? (scope/parse s))))))

(deftest undefined-plane-action-pairs-are-refused
  (testing "no invented authority: a pair the ADR does not define is nil"
    (is (nil? (vf/vf-scope org :event :delete)))
    (is (nil? (vf/vf-scope org :resource :append)))
    (is (nil? (vf/vf-scope org :agreement :commit)))
    (is (nil? (vf/vf-scope org :economy :read)))))

(deftest capability-mapping-is-mechanical-and-total
  (testing "each VF capability names exactly one scope"
    (is (= (vf/read-scope org) (vf/capability->vf-scope :vf.read org)))
    (is (= (vf/append-scope org) (vf/capability->vf-scope :vf.append org)))
    (is (= (vf/commit-scope org) (vf/capability->vf-scope :vf.commit org))))
  (testing "non-VF capabilities are nil here, not guessed"
    (is (nil? (vf/capability->vf-scope :patch.create org)))
    (is (nil? (vf/capability->vf-scope :metrics.read org))))
  (testing "the org is IN the scope: another org's grant does not reach"
    (is (not= (vf/read-scope :org-a) (vf/read-scope :org-b)))))

(deftest covers-refuses-the-prefix-confusion-class
  (testing "org-a does not cover org-ab — segments compare by =, not prefix"
    (is (false? (scope/covers? (scope/parse (vf/read-scope :org-a))
                               (scope/parse "kotoba://vf/org-ab/event/read")))))
  (testing "event/read does not reach a different plane or action"
    (is (false? (scope/covers? (scope/parse (vf/read-scope org))
                               (scope/parse (vf/append-scope org)))))
    (is (false? (scope/covers? (scope/parse (vf/read-scope org))
                               (scope/parse (vf/commit-scope org)))))))

(deftest the-grant-order-holds-as-scopes
  (testing "step 1 alone: read authorises observation"
    (is (vf/authorized? (grant-for (vf/read-scope org)) :vf.read org)))
  (testing "step 1 alone does NOT authorise append or commit"
    (is (false? (vf/authorized? (grant-for (vf/read-scope org)) :vf.append org)))
    (is (false? (vf/authorized? (grant-for (vf/read-scope org)) :vf.commit org))))
  (testing "step 2: append authorises append, still not commit"
    (is (vf/authorized? (grant-for (vf/append-scope org)) :vf.append org))
    (is (false? (vf/authorized? (grant-for (vf/append-scope org)) :vf.commit org))))
  (testing "step 3: commit is its own leaf; read is a separate grant"
    (is (vf/authorized? (grant-for (vf/commit-scope org)) :vf.commit org))))

(deftest attenuation-can-only-narrow
  (testing "a coordinator holding all three can hand an append slice on"
    (let [slices (vf/attenuate-slices
                  [(vf/read-scope org) (vf/append-scope org) (vf/commit-scope org)]
                  org :event :append)]
      (is (= #{(scope/parse (vf/append-scope org))} slices))))
  (testing "a coordinator holding only read cannot hand append on: no meet"
    (is (empty? (vf/attenuate-slices [(vf/read-scope org)] org :event :append))))
  (testing "another org's scope meets nothing here"
    (is (empty? (vf/attenuate-slices [(vf/read-scope :org-b)] org :event :read)))))

(deftest wildcard-only-where-the-decider-defines-it
  (testing "the decider's own wildcard rule: :* covers strictly longer, never itself"
    (is (scope/covers? (scope/parse "kotoba://vf/org-a/event/*")
                       (scope/parse (vf/append-scope org))))
    (is (false? (scope/covers? (scope/parse "kotoba://vf/org-a/event/*")
                               (scope/parse "kotoba://vf/org-a/event"))))))
