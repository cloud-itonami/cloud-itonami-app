(ns cloud.itonami.app.store-core-test
  "The state transitions, on both runtimes, with no atom and no disk.

  Before the extraction these lived inside `transact!` callbacks, so exercising
  one meant having a state atom and a writable directory — which is why the
  transcript window and the event ring had no direct test at all, only the
  incidental coverage of whatever integration test happened to append a
  message. Every assertion below was unavailable while the logic sat inside
  `store`."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.store-core :as core]))

(def ^:private blank (core/initial-state))

(deftest new-id-crossed-the-runtime-boundary-unchanged
  (let [id (core/new-id "msg")]
    (is (= "msg-" (subs id 0 4)))
    ;; 4 for the prefix + 36 for the UUID. `random-uuid` prints the same
    ;; canonical form on both runtimes; a shorter or longer id here would mean
    ;; the move off `java.util.UUID` changed what gets written to the store.
    (is (= 40 (count id)))
    (is (re-matches #"msg-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id)))
  (testing "two ids differ"
    (is (not= (core/new-id "msg") (core/new-id "msg")))))

(deftest a-message-lands-in-the-transcript
  (let [recorded {:id "msg-1" :role "person" :content "こんにちは" :at "2026-08-27T00:00:00.000000Z"}
        next-state (core/append-message blank "s1" recorded 10)]
    (is (= [recorded] (core/session-messages next-state "s1")))
    (is (= "2026-08-27T00:00:00.000000Z"
           (get-in next-state [:sessions "s1" :updated-at])))
    (testing "and the datoms are NOT this namespace's business"
      (is (= [] (:datoms next-state))
          "kgraph is JVM-only, so `store` writes them beside this call"))))

(deftest the-transcript-is-a-window-and-drops-the-oldest
  (let [state (reduce (fn [s n]
                        (core/append-message s "s1"
                                             {:id (str "msg-" n) :role "person"
                                              :content (str n) :at "t"}
                                             3))
                      blank (range 5))
        kept (core/session-messages state "s1")]
    (is (= 3 (count kept)) "max-messages trims")
    (is (= ["2" "3" "4"] (map :content kept))
        "it drops from the FRONT — the newest are the ones kept")
    (testing "the window is the only thing trimmed here"
      (is (= [] (:datoms state))))))

(deftest context-belongs-to-the-conversation-not-to-a-message
  (let [with-refs (core/set-context-refs blank "s1" [{:kind "project" :target "p1"}] "t1")]
    (is (= [{:kind "project" :target "p1"}] (core/session-context-refs with-refs "s1")))
    (testing "appending a message afterwards does not drop them"
      (let [after (core/append-message with-refs "s1"
                                       {:id "msg-1" :role "person" :content "x" :at "t2"} 10)]
        (is (= [{:kind "project" :target "p1"}] (core/session-context-refs after "s1"))
            "this is the merge that `store`'s comment says it is preserving")))
    (testing "and setting refs on a session that has messages keeps them"
      (let [after (core/append-message with-refs "s1"
                                       {:id "msg-1" :role "person" :content "x" :at "t2"} 10)
            re-set (core/set-context-refs after "s1" [] "t3")]
        (is (= 1 (count (core/session-messages re-set "s1"))))))))

(deftest the-event-ring-is-bounded
  (let [state (reduce (fn [s n] (core/record-response s {:provider "p" :model (str n)} "t"))
                      blank (range (+ core/max-events 5)))]
    (is (= core/max-events (count (:events state))))
    (is (= (str (+ core/max-events 4)) (:model (last (:events state))))
        "the newest survives")
    (is (= "5" (:model (first (:events state))))
        "and the oldest five are the ones gone")
    (is (= {:provider "p" :model (str (+ core/max-events 4))} (:last-response state)))))

(deftest clearing-a-session-leaves-the-others
  (let [state (-> blank
                  (core/append-message "s1" {:id "a" :role "person" :content "x" :at "t"} 10)
                  (core/append-message "s2" {:id "b" :role "person" :content "y" :at "t"} 10)
                  (core/clear-session "s1"))]
    (is (empty? (core/session-messages state "s1")))
    (is (= 1 (count (core/session-messages state "s2"))))
    (testing "clearing touches only :sessions"
      (is (= (:datoms blank) (:datoms state))))))

(deftest an-unknown-session-reads-as-empty-rather-than-nil
  (is (= [] (core/session-messages blank "nope")))
  (is (= [] (core/session-context-refs blank "nope"))))
