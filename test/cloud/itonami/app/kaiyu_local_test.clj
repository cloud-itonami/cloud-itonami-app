(ns cloud.itonami.app.kaiyu-local-test
  "The two properties that make local-first analytics defensible, as tests
  rather than as sentences in a docstring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cloud.itonami.app.kaiyu-local :as k]))

(deftest a-path-can-never-carry-what-a-person-typed
  (testing "the vocabulary is a whitelist, so a document title, a search term
            or a file name collapses to `other` before it is ever counted"
    (is (= "home" (k/route-of "/")))
    (is (= "mail" (k/route-of "/mail")))
    (is (= "mail" (k/route-of "/mail/thread/an-actual-subject-line")))
    (is (= "other" (k/route-of "/search?q=my+bank+password")))
    (testing "a named section keeps its name and drops everything after it —
              the file name is what must not survive, not the section"
      (is (= "drive" (k/route-of "/drive/Contract%20with%20Acme.pdf")))
      (is (not (str/includes? (k/route-of "/drive/Contract%20with%20Acme.pdf") "Acme"))))
    (is (= "other" (k/route-of (str "/" (apply str (repeat 400 "x"))))))))

(deftest the-order-of-a-session-is-not-representable
  (testing "counters per (day, route) and nothing else — reconstructing what
            was looked at in what order is not merely unimplemented here"
    (let [s (-> nil
                (k/record-view "2026-08-08" "mail")
                (k/record-view "2026-08-08" "chat")
                (k/record-view "2026-08-08" "mail"))]
      (is (= {"mail" 2 "chat" 1} (get-in s [:views "2026-08-08"])))
      (is (= "2026-08-08" (:since s)))
      (is (every? (fn [[_ routes]] (every? number? (vals routes))) (:views s))
          "values are counts; nothing carries a time or an index"))))

(deftest since-is-kept-at-the-earliest-day
  (let [s (-> nil
              (k/record-view "2026-08-08" "mail")
              (k/record-view "2026-08-01" "chat"))]
    (is (= "2026-08-01" (:since s)) "an earlier day moves the boundary back, never forward")))

(deftest a-report-says-which-kind-of-empty-it-is
  (let [state {:kaiyu {:since "2026-08-01"
                       :views {"2026-08-08" {"mail" 3 "chat" 1}
                               "2026-01-01" {"mail" 99}}}}
        r (k/report state {:days 7})]
    (is (= [{:route "mail" :count 3} {:route "chat" :count 1}] (get-in r [:views :rows]))
        "days outside the window are excluded")
    (is (= :measured (get-in r [:views :state])))
    (testing "dwell and transitions are named absences, not missing keys — this
              surface measures neither, and a reader should not have to infer it"
      (is (= :not-measured (get-in r [:dwell :state])))
      (is (= :not-measured (get-in r [:transitions :state]))))
    (is (true? (:local-only r)))))

(deftest nothing-in-this-namespace-can-transmit
  (testing "the property that makes local-first analytics defensible is not a
            promise in a docstring, it is the absence of a writer — so this
            asserts what the namespace REQUIRES, not what its prose says
            (the first version of this test failed on its own docstring, which
            is the difference between checking code and checking wording)"
    (let [requires (->> (:requires (meta (find-ns 'cloud.itonami.app.kaiyu-local)))
                        (or (ns-aliases 'cloud.itonami.app.kaiyu-local))
                        vals
                        (map str)
                        set)]
      (is (= #{"clojure.string" "kaiyu.core" "cloud.itonami.app.store"} requires)
          "a new require here is the only way this could gain a transport")
      (doseq [n requires]
        (is (not (re-find #"(?i)http|client|socket|net\.|url" n))
            (str "requires a transport-shaped namespace: " n))))))
