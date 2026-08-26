(ns cloud.itonami.app.a2a-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.a2a :as a2a]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.store :as store]))

(def configuration
  {:server {:public-origin "https://itonami.cloud"}
   :a2a {:enabled? true
         :bot-id "bot-a"
         :name "Research Bot"
         :description "Bounded research"
         :version "1.0.0"
         :skills [{:id "research" :name "Research"
                   :description "Research one question"
                   :inputModes ["text/plain"]
                   :outputModes ["text/plain"]}]}
   :agent-messaging
   {:slim {:enabled? true
           :from ["cloud-itonami" "owner-a" "bot-a"]
           :to ["partner" "owner-b" "bot-b"]}}})

(def session
  {:id "agent-session-a" :kind :agent :user-id "user-a"
   :organization-id "org-a"})

(def request
  {:jsonrpc "2.0" :id 7 :method "SendMessage"
   :params {:message {:messageId "message-a"
                      :contextId "context-a"
                      :role "ROLE_USER"
                      :parts [{:text "verify this"}]}}})

(defn- exception-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo error
         (:type (ex-data error)))))

(deftest agent-card-is-an-explicit-public-projection
  (let [card (a2a/agent-card configuration)]
    (is (= "https://itonami.cloud/a2a"
           (get-in card [:supportedInterfaces 0 :url])))
    (is (= ["research"] (mapv :id (:skills card))))
    (is (nil? (:bot-id card)))
    (is (nil? (:tools card)))))

(deftest send-message-is-isolated-durable-and-idempotent
  (let [previous @store/state
        calls (atom [])]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [bots/send!
                    (fn [_ _ bot-id text options]
                      (swap! calls conj [bot-id text options])
                      [{:role "person" :text text}
                       {:role "bot" :text "verified"}])]
        (let [first-response (a2a/respond! configuration session request)
              second-response (a2a/respond! configuration session request)
              task (get-in first-response [:result :task])
              fetched (a2a/respond!
                       configuration session
                       {:jsonrpc "2.0" :id 8 :method "GetTask"
                        :params {:id (:id task)}})]
          (is (= "TASK_STATE_COMPLETED" (get-in task [:status :state])))
          (is (= "verified" (get-in task [:status :message :parts 0 :text])))
          (is (= task (get-in second-response [:result :task])))
          (is (= task (:result fetched)))
          (is (= 1 (count @calls)) "retry must not execute the Bot twice")
          (is (= ["bot-a" "verify this"]
                 (subvec (vec (first @calls)) 0 2)))
          (is (= {:isolated? true :source :a2a :run-id (:id task)}
                 (nth (first @calls) 2)))))
      (finally (reset! store/state previous)))))

(deftest tasks-are-owned-by-the-authenticated-agent
  (let [previous @store/state]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [bots/send! (fn [& _] [{:role "bot" :text "done"}])]
        (let [task (get-in (a2a/respond! configuration session request)
                           [:result :task])
              other (assoc session :id "other-agent")]
          (is (= :a2a/not-found
                 (exception-type
                  #(a2a/get-task
                    other {:jsonrpc "2.0" :id 9 :method "GetTask"
                           :params {:id (:id task)}}))))))
      (finally (reset! store/state previous)))))

(deftest durable-task-history-is-bounded
  (let [previous @store/state
        bounded (assoc-in configuration [:a2a :max-tasks] 1)]
    (try
      (reset! store/state (store/initial-state))
      (with-redefs [bots/send! (fn [& _] [{:role "bot" :text "done"}])]
        (let [first-task (get-in (a2a/respond! bounded session request)
                                 [:result :task])
              next-request (assoc-in request [:params :message :messageId]
                                     "message-b")]
          (a2a/respond! bounded session next-request)
          (is (= 1 (count (get-in @store/state [:a2a :tasks]))))
          (is (= :a2a/not-found
                 (exception-type
                  #(a2a/get-task
                    session {:jsonrpc "2.0" :id 10 :method "GetTask"
                             :params {:id (:id first-task)}}))))))
      (finally (reset! store/state previous)))))

(deftest slim-boundary-refuses-authority
  (is (false? (:ready (a2a/slim-status configuration)))
      "configured names do not pretend that a network publisher exists")
  (is (= "org.kotoba.a2a-over-slim/1"
         (:profile (a2a/slim-envelope configuration "delivery-a" request))))
  (let [published (atom nil)
        publisher (reify a2a/SlimPublisher
                    (publisher-ready? [_] true)
                    (publish-envelope! [_ envelope]
                      (reset! published envelope)
                      {:accepted true}))]
    (is (true? (:ready (a2a/slim-status configuration publisher))))
    (is (= {:accepted true}
           (a2a/publish-slim! configuration publisher "delivery-c" request)))
    (is (= "delivery-c" (:deliveryId @published))))
  (testing "authority never becomes transport payload"
    (is (= :authority-field-refused
           (:error (a2a/slim-envelope
                    configuration "delivery-b"
                    {:method "SendMessage" :metadata {:grant "secret"}}))))))
