(ns cloud.itonami.app.screen-guest-test
  "Behavioral checks for the screen gate bridge: the compiled kotoba-lang/screen
  artifact must answer through kotoba.kir exactly as the pure .kotoba modules
  specify. Values are the same golden literals host/verify.clj asserts."
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.screen-guest :as screen-guest]))

(deftest gate-loads-from-artifact
  (is (true? (screen-guest/available?)))
  (is (= 1 (screen-guest/gate-version))))

(deftest chronicle-dedup-judgment
  ;; 1 = keep, 0 = drop; frame-keep? turns 1 into true.
  (is (false? (screen-guest/frame-keep? 100 100)) "same combined digest drops")
  (is (true? (screen-guest/frame-keep? 100 101)) "changed digest keeps")
  (is (false? (screen-guest/frame-keep? 0 0)) "two empty screens are the same empty -> drop"))

(deftest frame-unchanged-judgment
  (is (true? (screen-guest/frame-unchanged? 100 1 100 1)))
  (is (false? (screen-guest/frame-unchanged? 100 1 101 1)))
  (is (false? (screen-guest/frame-unchanged? 100 1 100 2)) "app change is a change"))

(deftest act-shape-gate
  (is (true? (screen-guest/press-ok? 12 777 100)))
  (is (false? (screen-guest/press-ok? 0 777 100)) "ref 0 is NOT-FOUND, never a target")
  (is (false? (screen-guest/press-ok? 12 0 100)) "pressing an unread screen refuses")
  (is (false? (screen-guest/press-ok? 12 777 0)) "empty tree is a caller defect")
  (is (false? (screen-guest/press-ok? 12 777 99999)) "over-quota node count refuses"))
