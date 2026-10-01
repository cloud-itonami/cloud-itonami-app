(ns cloud.itonami.app.user-test-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.agent-control :as agent-control]
            [cloud.itonami.app.business :as business]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.user-test :as app-user-test]
            [cloud.itonami.app.user-test-loop :as user-test-loop]))

(def config {:user-test {:enabled? true :max-runs-per-study 10}})
(def session {:user-id "user-1" :organization-id "org-1"})
(def other-session {:user-id "user-2" :organization-id "org-2"})

(defn- reset-state! []
  (store/transact! assoc :businesses {} :user-tests {} :datoms [] :events []))

(defn- proposed-study [revision]
  {:user-test/id "onboarding"
   :user-test/project "untrusted/project"
   :user-test/persona "private://persona/first-visit"
   :user-test/revision revision
   :user-test/tasks [{:task/id "sign-in" :task/goal "Reach workspace"
                      :task/success #{:workspace-visible}}]})

(deftest studies-are-forced-into-the-session-business-and-tenant
  (reset-state!)
  (let [b (business/create! session {:slug "test-business"})
        record (app-user-test/create-study! config session (:business/id b)
                                            (assoc (proposed-study "abc")
                                                   :persona/email "must-not-store@example.test"))]
    (is (= (:business/id b) (:user-test/project record)))
    (is (= (:business/id b) (:user-test/business record)))
    (is (nil? (:persona/email record)))
    (is (= :user-test/business-not-found
           (try (app-user-test/studies other-session (:business/id b)) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

(deftest a-run-is-scored-and-queryable-without-raw-evidence
  (reset-state!)
  (let [b (business/create! session {:slug "test-business"})
        _ (app-user-test/create-study! config session (:business/id b)
                                       (proposed-study "abc"))
        run {:run/id "run-1" :run/revision "abc"
             :run/participant-kind :synthetic
             :run/participant "private://participant/1"
             :run/transcript "secret words"
             :run/outcomes [{:task/id "sign-in" :outcome/succeeded? false
                             :outcome/elapsed-ms 50000 :outcome/actions 8
                             :outcome/dead-ends 1 :outcome/recoveries 0
                             :outcome/a11y-violations 0}]
             :run/evidence {:screen {:evidence/path "/secret/screen.png"
                                     :evidence/sha256 "deadbeef"}}}
        recorded (app-user-test/record-run! config session "onboarding" run)
        summary (app-user-test/business-summary session (:business/id b))]
    (is (nil? (:run/transcript recorded)))
    (is (nil? (get-in recorded [:run/evidence :screen :evidence/path])))
    (is (= "deadbeef" (get-in recorded [:run/evidence :screen :evidence/sha256])))
    (is (= 1 (:user-test/runs summary)))
    (is (= 2 (count (:user-test/open-findings summary))))
    (is (= [["run-1"]]
           (app-user-test/query-runs
            {:find ['?run]
             :where [['?run :user-test-run/study "onboarding"]]})))))

(deftest next-plan-stops-after-a-passing-run
  (reset-state!)
  (let [b (business/create! session {:slug "test-business"})]
    (app-user-test/create-study! config session (:business/id b)
                                 (proposed-study "abc"))
    (is (= "onboarding" (:plan/study
                         (app-user-test/next-plan session (:business/id b)))))
    (app-user-test/record-run!
     config session "onboarding"
     {:run/id "pass" :run/revision "abc" :run/participant-kind :recipe
      :run/participant "recipe://onboarding-v1"
      :run/outcomes [{:task/id "sign-in" :outcome/succeeded? true
                      :outcome/elapsed-ms 1000 :outcome/actions 2
                      :outcome/dead-ends 0 :outcome/recoveries 0
                      :outcome/a11y-violations 0}]
      :run/evidence {}})
    (is (nil? (app-user-test/next-plan session (:business/id b))))))

(deftest browser-execution-needs-both-explicit-gates
  (is (not (app-user-test/execution-enabled?
            {:user-test {:enabled? true :execution-enabled? true}
             :agent-control {:browser {:enabled? false}}})))
  (is (app-user-test/execution-enabled?
       {:user-test {:enabled? true :execution-enabled? true}
        :agent-control {:browser {:enabled? true}}})))

(deftest resident-supervisor-starts-and-stops
  (user-test-loop/stop!)
  (let [supervisor-config
        {:user-test {:enabled? true :execution-enabled? true
                     :dispatch-interval-seconds 60}
         :agent-control {:browser {:enabled? true}}}]
    (try
      (is (true? (user-test-loop/start! supervisor-config)))
      (is (true? (user-test-loop/running?)))
      (finally
        (user-test-loop/stop!)))
    (is (false? (user-test-loop/running?)))))

(deftest synthetic-dispatch-uses-agent-control-and-observed-markers
  (reset-state!)
  (let [b (business/create! session {:slug "test-business"})
        dispatch-config
        {:user-test {:enabled? true :execution-enabled? true
                     :max-runs-per-study 10}
         :agent-control {:browser {:enabled? true}}}
        request (atom nil)]
    (app-user-test/create-study!
     dispatch-config session (:business/id b)
     (update-in (proposed-study "abc") [:user-test/tasks 0]
                assoc :task/observations
                {:workspace-visible {:kind :text-present :value "Workspace"}}))
    (with-redefs [agent-control/create-run!
                  (fn [_ req actor]
                    (reset! request {:request req :actor actor})
                    {:agent.run/id "agent-run-1"
                     :agent.run/status :succeeded
                     :agent.run/created-at 1000
                     :agent.run/updated-at 2000
                     :agent/tool-count 4})
                  agent-control/browser-snapshot!
                  (fn [_] "heading Workspace button Continue")]
      (let [dispatch (app-user-test/dispatch-synthetic!
                      dispatch-config session (:business/id b))
            [recorded] (app-user-test/runs session "onboarding")]
        (is (= :user-test (get-in @request [:request :tool-profile])))
        (is (true? (get-in @request [:request :auto-browser?])))
        (is (= :user-test/participant
               (get-in @request [:actor :actor/role])))
        (is (= :succeeded (:dispatch/status dispatch)))
        (is (true? (get-in recorded
                           [:run/evaluation :evaluation/pass?])))
        (is (= "agent-control://agent-run-1" (:run/participant recorded)))))))
