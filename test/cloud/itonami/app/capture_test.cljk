(ns cloud.itonami.app.capture-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.capture :as capture]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.store :as store]))

(defn- with-store [f]
  (let [temporary (java.nio.file.Files/createTempDirectory
                   "cloud-itonami-capture-test"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [config/data-dir (fn [] (.toFile temporary))]
        (f))
      (finally (reset! store/state previous)))))

(deftest capture-first-clarify-later
  (with-store
    (fn []
      (let [raw "  話しながら考える。\nまだ分類しない。  "
            admitted (capture/create! "alice" "org-1"
                                      {:text raw :mode "think-aloud"})]
        (testing "admission keeps the raw utterance and asks for no metadata"
          (is (= raw (:capture/text admitted)))
          (is (= :think-aloud (:capture/mode admitted)))
          (is (= :unclarified (:capture/state admitted)))
          (is (nil? (:capture/title admitted)))
          (is (= 1 (get-in (capture/snapshot "alice" "org-1")
                           [:counts :inbox]))))
        (testing "clarification organizes without rewriting the raw record"
          (let [organized (capture/clarify!
                           (:capture/id admitted) "alice" "org-1"
                           {:outcome "next-action" :title "問いを一つ書く"
                            :project "writing" :context "desk" :due "2026-08-10"})]
            (is (= raw (:capture/text organized)))
            (is (= :next-action (:capture/outcome organized)))
            (is (= "問いを一つ書く" (:capture/title organized)))
            (is (= "desk" (:capture/context organized)))
            (is (= 1 (get-in (capture/snapshot "alice" "org-1")
                             [:counts :next-action])))))
        (testing "review and reopen are explicit, reversible acts"
          (let [reviewed (capture/review! (:capture/id admitted) "alice" "org-1")]
            (is (= 1 (:capture/review-count reviewed)))
            (is (string? (:capture/last-reviewed-at reviewed))))
          (let [completed (capture/complete! (:capture/id admitted) "alice" "org-1")]
            (is (= :completed (:capture/state completed)))
            (is (= 1 (get-in (capture/snapshot "alice" "org-1") [:counts :done]))))
          (let [reopened (capture/reopen! (:capture/id admitted) "alice" "org-1")]
            (is (= :unclarified (:capture/state reopened)))
            (is (nil? (:capture/outcome reopened)))
            (is (= raw (:capture/text reopened)))))))))

(deftest records-never-cross-a-person-or-organization-boundary
  (with-store
    (fn []
      (let [item (capture/create! "alice" "org-1" {:text "private"})]
        (is (= 1 (count (:items (capture/snapshot "alice" "org-1")))))
        (is (empty? (:items (capture/snapshot "bob" "org-1"))))
        (is (empty? (:items (capture/snapshot "alice" "org-2"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"見つかりません"
                              (capture/clarify! (:capture/id item) "bob" "org-1"
                                                {:outcome :reference})))))))

(deftest chronicle-source-is-bounded-attributed-and-immutable
  (with-store
    (fn []
      (let [source {:type :chronicle-frame :frame-id "frame-1"
                    :captured-at "2026-08-08T12:00:00Z" :application "Editor"
                    :text-preview (apply str (repeat 5000 "x"))
                    :image-path "/private/not-admitted.jpg"}
            item (capture/create! "alice" "org-1" {:text "自分の考え"} source)
            admitted (:capture/source item)
            event (last (get-in (store/snapshot) [:capture :events]))]
        (is (= :chronicle-frame (:type admitted)))
        (is (= :untrusted-reference (:trust admitted)))
        (is (= 4000 (count (:text-preview admitted))))
        (is (not (contains? admitted :image-path)))
        (is (= :chronicle-frame (:capture.event/source event)))
        (is (= "frame-1" (:capture.event/source-id event)))
        (is (= admitted (:capture/source
                         (capture/clarify! (:capture/id item) "alice" "org-1"
                                           {:outcome :reference}))))))))

(deftest invalid-input-fails-before-writing
  (with-store
    (fn []
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"空の記録"
                            (capture/create! "alice" "org-1" {:text "  \n"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未知の記録モード"
                            (capture/create! "alice" "org-1"
                                             {:text "hello" :mode :automatic-analysis})))
      (is (empty? (:items (capture/snapshot "alice" "org-1")))))))
