(ns cloud.itonami.app.hermes-compat-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.bots :as bots]
            [cloud.itonami.app.hermes-compat :as hermes]))

(def ^:private owner {:kind :agent :user-id "u-1" :organization-id "o-1"})
(def ^:private intruder {:kind :agent :user-id "u-2" :organization-id "o-1"})

(def ^:private bot-row
  {:id "bot-1" :name "Researcher" :model "glm-5.3-flash"
   :provider-id "openrouter" :enabled? true :status "idle"
   :pinned? true :hidden? false :updated-at "2026-09-01T00:00:00Z"
   :last-turn {:tool-count 2}})

(def ^:private transcript
  [{:id "m-1" :role "owner" :text "調べて"
    :at "2026-09-01T00:00:01Z" :cards []}
   {:id "m-2" :role "bot" :text "確認しました"
    :at "2026-09-01T00:00:02Z" :cards []}])

(defn- with-bot [f]
  (with-redefs [bots/overview (fn [_ _] {:bots [bot-row]})
                bots/messages (fn [_ _] transcript)]
    (f)))

(deftest profile-multiplex-and-canonical-session-match-hermes-bot-mode
  (with-bot
    (fn []
      (is (= ["bot-1" "/api/sessions"]
             (hermes/split-profile-path "/p/bot-1/api/sessions")))
      (is (= [nil "/api/sessions"]
             (hermes/split-profile-path "/api/sessions")))
      (is (= "bot-1" (:id (hermes/resolve-bot nil owner "default"))))
      (is (= "bot-2"
             (:id
              (with-redefs [bots/overview
                            (fn [_ _]
                              {:bots [bot-row (assoc bot-row :id "bot-2")]})]
                (hermes/resolve-bot
                 {:bots {:hermes {:default-bot-id "bot-2"}}}
                 owner "default")))))
      (let [profiles (hermes/profile-list nil owner)
            sessions (hermes/session-list nil owner nil {})
            row (first (:data sessions))]
        (is (= "list" (:object profiles)))
        (is (= "bot-1" (get-in profiles [:data 0 :session_id])))
        (is (= "Bot Chat" (:title row)))
        (is (= "cloud_itonami" (:source row)))
        (is (= 2 (:message_count row)))
        (is (= "確認しました" (:preview row)))
        (is (true? (:pinned row)))))))

(deftest session-message-wire-roles-and-pagination-match-hermes
  (with-bot
    (fn []
      (let [oldest (hermes/session-messages nil owner "bot-1"
                                             {:limit 1 :offset 0
                                              :order "oldest"})
            latest (hermes/session-messages nil owner "bot-1" {})]
        (is (= {:role "user" :content "調べて"}
               (select-keys (first (:data oldest)) [:role :content])))
        (is (= {:role "assistant" :content "確認しました"}
               (select-keys (first (:data latest)) [:role :content])))
        (is (= {:limit 1 :offset 0 :order "oldest" :returned 1}
               (:pagination oldest)))))))

(deftest a-hermes-run-uses-the-native-loop-and-emits-pollable-sse-events
  (hermes/reset-runs!)
  (let [called (promise)]
    (with-redefs [bots/overview (fn [_ _] {:bots [bot-row]})
                  bots/send-stream!
                  (fn [_ _ bot-id text run-id goal? on-event]
                    (deliver called {:bot-id bot-id :text text :run-id run-id
                                     :goal? goal?})
                    (on-event {:type "phase" :phase "model"})
                    (on-event {:type "delta" :content "完了"})
                    transcript)
                  bots/messages
                  (fn [session _]
                    (if (= owner session)
                      transcript
                      (throw (ex-info "forbidden" {:type :bot/forbidden}))))
                  bots/latest-turn
                  (fn [_ _] {:state "completed" :provider "openrouter"
                             :model "glm-5.3-flash"
                             :usage {:total_tokens 7}})]
      (let [started (hermes/start-run! nil owner "bot-1"
                                       {:input "調べて" :goal true})
            run-id (:run_id started)
            call (deref called 2000 ::timeout)]
        (is (= "started" (:status started)))
        (is (= {:bot-id "bot-1" :text "調べて" :run-id run-id :goal? true}
               call))
        (loop [remaining 100]
          (when (and (pos? remaining)
                     (not= "completed" (:status (hermes/run-status nil owner run-id))))
            (Thread/sleep 10)
            (recur (dec remaining))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (hermes/run-status nil intruder run-id)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (hermes/take-event! intruder run-id 0)))
        (let [status (hermes/run-status nil owner run-id)
              events (loop [out []]
                       (let [event (hermes/take-event! owner run-id 1)]
                         (if (hermes/closed-event? event)
                           out
                           (recur (conj out event)))))]
          (is (= "completed" (:status status)))
          (is (= "確認しました" (:output status)))
          (is (= {:total_tokens 7} (:usage status)))
          (is (= ["run.started" "run.phase" "assistant.delta"
                  "run.completed"]
                 (mapv :event events))))))))

(deftest steer-stop-and-approval-preserve-itonami-authority-gates
  (hermes/reset-runs!)
  (let [entered (promise)
        release (promise)
        steered (atom nil)
        stopped (atom nil)
        decided (atom nil)
        open-card {:id "card-1" :kind "approval" :standing "open"}]
    (with-redefs [bots/overview (fn [_ _] {:bots [bot-row]})
                  bots/send-stream!
                  (fn [_ _ _ _ run-id _ _]
                    (deliver entered run-id)
                    @release
                    transcript)
                  bots/latest-turn (fn [_ _] {:state "cancelled"})
                  bots/queue-followup!
                  (fn [_ bot-id run-id text]
                    (reset! steered [bot-id run-id text])
                    {:id "followup-1"})
                  bots/cancel!
                  (fn [_ bot-id run-id]
                    (reset! stopped [bot-id run-id])
                    {:cancelled true})
                  bots/messages (fn [_ _] [{:cards [open-card]}])
                  bots/decide!
                  (fn [_ _ bot-id card-id decision]
                    (reset! decided [bot-id card-id decision])
                    [])]
      (let [run-id (:run_id (hermes/start-run! nil owner "bot-1"
                                                {:input "start"}))]
        (is (= run-id (deref entered 2000 ::timeout)))
        (is (true? (:accepted (hermes/steer! nil owner run-id
                                              {:message "change"}))))
        (is (= ["bot-1" run-id "change"] @steered))
        (is (= 1 (:resolved (hermes/approval! nil owner run-id
                                               {:choice "approve"}))))
        (is (= ["bot-1" "card-1" :approved] @decided))
        (is (= "stopping" (:status (hermes/stop! nil owner run-id))))
        (is (= ["bot-1" run-id] @stopped))
        (deliver release true)))))

(deftest polling-survives-a-process-local-stream-registry-loss
  (hermes/reset-runs!)
  (with-redefs [bots/overview (fn [_ _] {:bots [bot-row]})
                bots/turn (fn [_ bot-id run-id]
                            (when (= ["bot-1" "run-durable"]
                                     [bot-id run-id])
                              {:id run-id :state "completed"
                               :result "durable answer"
                               :usage {:total_tokens 9}
                               :started-at "2026-09-01T00:00:00Z"
                               :finished-at "2026-09-01T00:00:01Z"}))]
    (let [status (hermes/run-status nil owner "run-durable")]
      (is (= "completed" (:status status)))
      (is (= "durable answer" (:output status)))
      (is (= {:total_tokens 9} (:usage status))))))
