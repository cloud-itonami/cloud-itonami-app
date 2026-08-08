(ns cloud.itonami.app.freebusy-test
  "Free/busy for an external consumer.

  The property under test is what the route does *not* do: it must answer
  without disclosing the access token, and it must treat 'nobody has connected
  Google' as an ordinary answer rather than a failure. A poller that reads a
  4xx as breakage will page somebody on the day before anyone has connected
  anything."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.identity :as identity]))

(deftest with-no-connection-it-is-a-reason-not-an-exception
  (let [r (identity/google-freebusy "2026-08-08T00:00:00Z" "2026-10-07T00:00:00Z" nil)]
    (is (false? (:ok? r)))
    (is (= "google-not-connected" (:reason r)))
    (testing "and it offers no busy times rather than nil"
      (is (= nil (:busy r))))))

(deftest the-answer-never-carries-a-token
  ;; The whole point of the seam. `access-token` refuses to return a token
  ;; through an HTTP view; this must not reintroduce one by another door.
  (let [r (identity/google-freebusy "2026-08-08T00:00:00Z" "2026-10-07T00:00:00Z" nil)
        s (pr-str r)]
    (doseq [leak ["access" "token" "Bearer" "refresh"]]
      (testing leak
        (is (not (re-find (re-pattern (str "(?i)" leak)) s)))))
    (is (= #{:ok? :reason} (set (keys r))))))
