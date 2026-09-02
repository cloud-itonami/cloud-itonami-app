(ns cloud.itonami.app.hokusai-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.hokusai :as hokusai]))

(deftest submit-refuses-rather-than-repairs
  (testing "a blank prompt is named, not defaulted"
    (let [r (hokusai/submit-body {:prompt "  "})]
      (is (false? (:ok? r)))
      (is (= "prompt_required" (:code r)))))
  (testing "duration outside 1..15 is named, not clamped"
    (doseq [s [0 16 -1 "abc" 100]]
      (let [r (hokusai/submit-body {:prompt "a cat" :seconds s})]
        (is (false? (:ok? r)) (pr-str s))
        (is (= "seconds_out_of_range" (:code r)) (pr-str s))))))

(deftest submit-carries-only-what-was-asked
  (let [{:keys [ok? body]} (hokusai/submit-body {:prompt "桜の下で踊る猫" :seconds "6"
                                                  :generate_audio true
                                                  :aspect_ratio "16:9"})]
    (is ok?)
    (is (= hokusai/model-id (:model body)))
    (is (= 6.0 (double (:seconds body))))
    (is (true? (:generate_audio body)))
    (is (= "16:9" (:aspect_ratio body)))
    (is (not (contains? body :image)))
    (is (not (contains? body :resolution)))
    (is (not (contains? body :seed)) "a seed the Bot did not choose is not sent"))
  (is (= hokusai/default-seconds
         (:seconds (:body (hokusai/submit-body {:prompt "x"}))))))

(deftest submit-answer-accepts-both-field-spellings
  (is (= {:id "job-1" :status "queued" :status-url "https://murakumo.cloud/api/v1/videos/job-1"}
         (hokusai/parse-submit {:id "job-1" :status "queued"
                                :status_url "https://murakumo.cloud/api/v1/videos/job-1"})))
  (is (= "j2" (:id (hokusai/parse-submit {"jobId" "j2"}))))
  (is (nil? (hokusai/parse-submit {:status "queued"})) "no id is no submission"))

(deftest status-knows-when-it-is-over
  (let [done (hokusai/parse-status {:id "j" :status "completed" :content_url "https://x/c"})
        failed (hokusai/parse-status {:id "j" :status "failed" :error {:message "no backend"}})
        running (hokusai/parse-status {:id "j" :status "running"})]
    (is (:terminal? done))
    (is (:succeeded? done))
    (is (= "https://x/c" (:content-url done)))
    (is (:terminal? failed))
    (is (not (:succeeded? failed)))
    (is (= "no backend" (:error failed)))
    (is (not (:terminal? running)))
    (is (nil? (:error running)))))

(deftest urls-are-derived-from-the-configured-endpoint
  (is (= "https://murakumo.cloud/api/v1/videos/abc"
         (hokusai/status-url "https://murakumo.cloud/api/v1/videos/" "abc")))
  (is (= "https://murakumo.cloud/api/v1/videos/abc/content"
         (hokusai/content-url "https://murakumo.cloud/api/v1/videos" "abc"))))
