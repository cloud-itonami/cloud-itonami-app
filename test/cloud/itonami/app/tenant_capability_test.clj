(ns cloud.itonami.app.tenant-capability-test
  "The capability vocabulary, and the rule that it stays sayable.

  ADR-2608093000 D2 adds outbound capabilities — one app acting with something
  of another tenant's — to a set that until now only described an agent acting
  inside its own workspace. The risk changes with the direction, so the
  approval screen has to be able to say which it is."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.tenant-connection :as tc]))

(deftest every-capability-has-a-sentence
  ;; The structural version of the rule. `allowed-capabilities` is derived
  ;; from the catalog, so a capability with no label cannot exist — there is
  ;; no set to add to independently.
  (is (= tc/allowed-capabilities (set (keys tc/capability-catalog))))
  (doseq [[c {:keys [label direction]}] tc/capability-catalog]
    (testing c
      (is (and (string? label) (seq label)))
      (is (not= label c) "a label that repeats the identifier says nothing")
      (is (#{:inbound :outbound} direction)))))

(deftest outbound-capabilities-exist-and-are-marked
  (let [out (->> tc/capability-catalog
                 (filter (fn [[_ v]] (= :outbound (:direction v))))
                 (map key)
                 set)]
    (is (contains? out "calendar.freebusy.read"))
    (is (contains? out "calendar.event.write"))
    (testing "and the inbound ones did not silently change direction"
      (doseq [c ["tenant.read" "workspace.write" "actor.invoke"]]
        (is (= :inbound (:direction (get tc/capability-catalog c))))))))

(deftest freebusy-wording-says-what-is-not-shared
  ;; free/busy and "the calendar" are different disclosures, and somebody
  ;; approving has to be able to tell them apart.
  (let [label (:label (get tc/capability-catalog "calendar.freebusy.read"))]
    (is (str/includes? label "空き"))
    (is (or (str/includes? label "件名") (str/includes? label "渡しません"))
        "it must say what is withheld, not only what is read")))

(deftest describe-keeps-an-unknown-capability-visible
  ;; Dropping something the grant was asked to approve is worse than showing
  ;; an ugly identifier.
  (let [d (tc/describe-capabilities ["tenant.read" "made.up.capability"])]
    (is (= 2 (count d)))
    (is (= "made.up.capability" (:label (second d))))
    (is (= :unknown (:direction (second d))))))

(deftest the-wording-is-published-once
  ;; ADR-2608093000 D4: the Fleet view shows what an app asks for before
  ;; anything is granted, and it must not keep its own copy of the sentences.
  ;; If this stops being sent, the UI silently falls back to identifiers and
  ;; a consent screen goes back to reading like a config file.
  (let [cat tc/capability-catalog]
    (is (contains? cat "calendar.freebusy.read"))
    (is (every? (fn [[_ v]] (and (:label v) (:direction v))) cat))))

(deftest an-unknown-capability-is-still-refused-at-request
  ;; describe- is lenient for display; validation is not.
  (is (thrown? clojure.lang.ExceptionInfo
               (#'tc/validate-capabilities ["calendar.freebusy.read" "made.up"])))
  (is (= ["calendar.freebusy.read"]
         (#'tc/validate-capabilities ["calendar.freebusy.read"]))))
