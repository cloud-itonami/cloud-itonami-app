(ns cloud.itonami.capital-client (:require [kotoba.lang.text :as str] [re-frame.core :as rf]))
(defonce transport (atom nil))
(defn link-params [] (js/URLSearchParams. (subs (.-hash js/location) (min 1 (count (.-hash js/location))))))
(defn link-project [] (.get (link-params) "capital"))
(defonce listener (atom false))
(defn install! [adapters]
 (reset! transport adapters)
 (when-not @listener (reset! listener true) (.addEventListener js/window "hashchange" #(when (link-project) (rf/dispatch [:my-bots/public])))))
(defn pending-for [project principal]
 (try (let [p (js->clj (js/JSON.parse (js/localStorage.getItem (str "itonami.capital.pending." project))) :keywordize-keys true)]
  (when (= (:from p) (last (str/split (or principal "") #":"))) p)) (catch :default _ nil)))
(defn request! [body] ((:request! @transport) "/api/capital" body))
(defn load! [project contract safe]
 ((:request! @transport) (str "/api/capital?project=" (js/encodeURIComponent project) (when contract (str "&vault=" (js/encodeURIComponent contract))) (when safe (str "&safe=" (js/encodeURIComponent safe))))))
(rf/reg-event-db :capital/field (fn [db [_ k value]] (-> db (assoc-in [:my-bots :capital-form k] value) (assoc-in [:my-bots :capital-plan] nil))))
(rf/reg-event-fx :capital/load
 (fn [{:keys [db]} [_ contract]]
  (let [project (get-in db [:my-bots :public-selected])]
   {:db (-> db (update :my-bots merge {:capital-status "残高を確認しています…" :capital-plan nil}) (assoc-in [:my-bots :capital-form :safe-address] (or (get-in db [:my-bots :capital-form :safe-address]) (.get (link-params) "safe") "")))
    :itonami.promise {:run #(load! project contract (get-in db [:my-bots :capital-safe :address])) :success [:capital/loaded project] :failure [:capital/failed project]}})))
(rf/reg-event-db :capital/loaded
 (fn [db [_ project data]] (if (= project (get-in db [:my-bots :public-selected])) (update db :my-bots merge {:capital data :capital-status nil :capital-error nil :capital-pending (pending-for project (get-in db [:my-bots :principal]))}) db)))
(rf/reg-event-db :capital/failed
 (fn [db [_ project error]] (if (= project (get-in db [:my-bots :public-selected])) (update db :my-bots merge {:capital-error (str error) :capital-busy? false :capital-status nil}) db)))
(rf/reg-event-fx :capital/prepare
 (fn [{:keys [db]} [_ action]]
  (let [state (:my-bots db) project (:public-selected state) form (:capital-form state)
        input (merge form {:action action :project project :safe (get-in state [:capital-safe :address]) :vault (get-in state [:capital :vault]) :launcher (get-in state [:capital :operatorGrant :launcher]) :termsVersion (get-in state [:funding :version])})
        input (if (and (= action "repay") (= "yield-budget-v1" (get-in state [:capital :settings :policy]))) (assoc input :principal "0") input)
        input (if (contains? #{"deploy" "deploy-launcher"} action) (assoc input :policy (when (:policy form) (or (get-in state [:funding :terms :fundingPolicy]) "fixed-round-net-income-v1")) :fundingDeadline (quot (.getTime (js/Date. (:fundingDeadline form))) 1000) :maturity (quot (.getTime (js/Date. (:maturity form))) 1000) :recipients (str/split (str/trim (or (:recipients form) "")) #"[\s,]+")) input)]
   {:db (update db :my-bots merge {:capital-busy? true :capital-error nil :capital-plan nil})
    :itonami.promise {:run #(request! input) :success [:capital/prepared project] :failure [:capital/failed project]}})))
(defn focus-review! [attempt]
 (js/requestAnimationFrame
  (fn [] (if-let [el (.querySelector js/document ".bw-capital-review")]
   (do (.scrollIntoView el #js {:block "start"}) (when-let [heading (.querySelector el "h3")] (.setAttribute heading "tabindex" "-1") (.focus heading #js {:preventScroll true})))
   (when (< attempt 12) (focus-review! (inc attempt)))))))
(rf/reg-fx :capital/focus-review (fn [_] (focus-review! 0)))
(rf/reg-event-fx :capital/prepared
 (fn [{:keys [db]} [_ project data]]
  (if (= project (get-in db [:my-bots :public-selected]))
   {:db (update db :my-bots merge (if (= project (:project data)) {:capital-plan data :capital-busy? false} {:capital-plan nil :capital-busy? false :capital-error "取引の事業が一致しません"}))
    :capital/focus-review true} {})))
(rf/reg-event-db :capital/cancel (fn [db _] (assoc-in db [:my-bots :capital-plan] nil)))
(defn- confirm! [plan hash attempt]
 (-> (request! {:action "confirm" :id (:id plan) :transactionHash hash})
     (.then (fn [result]
       (if (= "confirmed" (:status result)) (do (try (js/localStorage.removeItem (str "itonami.capital.pending." (:project plan))) (catch :default _ nil)) result)
        (if (< attempt 120)
         (js/Promise. (fn [resolve reject] (js/setTimeout #(-> (confirm! plan hash (inc attempt)) (.then resolve) (.catch reject)) 2000)))
         (throw (js/Error. (str "確定待ちです。取引ID: " hash)))))))))
(rf/reg-event-fx :capital/execute
 (fn [{:keys [db]} _]
  (let [plan (get-in db [:my-bots :capital-plan]) project (:project plan) send! (:send! @transport)]
   {:db (update db :my-bots merge {:capital-busy? true :capital-status (if (:requiresOperatorSigner plan) "Botが募集の実行を確認しています…" "ウォレットで取引内容を確認してください") :capital-error nil})
    :itonami.promise {:run #(-> (if (:requiresOperatorSigner plan)
                               (-> (request! {:action "operator-execute" :id (:id plan)})
                                   (.then (fn [result] (or (:hash result) (throw (js/Error. "Botの取引は未送信です。状態を更新してください。"))))))
                               (-> (if (:approval plan) (send! (:approval plan) true) (js/Promise.resolve nil))
                                   (.then (fn [_] (send! (:transaction plan) false)))))
                              (.then (fn [hash] (try (js/localStorage.setItem (str "itonami.capital.pending." project) (js/JSON.stringify (clj->js {:plan (select-keys plan [:id :project]) :hash hash :from (last (str/split (get-in db [:my-bots :principal]) #":"))}))) (catch :default _ nil)) (confirm! plan hash 0))))
                     :success [:capital/executed project] :failure [:capital/failed project]}})))
(rf/reg-event-fx :capital/executed
 (fn [{:keys [db]} [_ project result]]
  (if (= project (get-in db [:my-bots :public-selected]))
   {:db (update db :my-bots merge {:capital-busy? false :capital-plan nil :capital-status "取引が確定しました"}) :dispatch [:capital/load (when-not (= "deploy-launcher" (get-in result [:receipt :action])) (:contract result))]} {})))
(rf/reg-event-fx :capital/resume
 (fn [{:keys [db]} _]
  (let [p (get-in db [:my-bots :capital-pending]) project (get-in p [:plan :project])]
   {:db (assoc-in db [:my-bots :capital-busy?] true)
    :itonami.promise {:run #(confirm! (:plan p) (:hash p) 0) :success [:capital/executed project] :failure [:capital/failed project]}})))
(rf/reg-event-fx :capital/load-intent
 (fn [{:keys [db]} [_ id]]
  (let [project (get-in db [:my-bots :public-selected])]
   {:itonami.promise {:run #((:request! @transport) (str "/api/capital?intent=" (js/encodeURIComponent id))) :success [:capital/prepared project] :failure [:capital/failed project]}})))
(rf/reg-event-fx :capital/connect-safe
 (fn [{:keys [db]} _]
  (let [project (get-in db [:my-bots :public-selected]) address (get-in db [:my-bots :capital-form :safe-address])]
   {:db (update db :my-bots merge {:capital-busy? true :capital-plan nil})
    :itonami.promise {:run #((:request! @transport) (str "/api/capital?safe-account=" (js/encodeURIComponent address))) :success [:capital/safe-connected project] :failure [:capital/failed project]}})))
(rf/reg-event-fx :capital/safe-connected
 (fn [{:keys [db]} [_ project account]]
  (if (= project (get-in db [:my-bots :public-selected]))
   {:db (update db :my-bots merge {:capital-safe account :capital-busy? false :capital-plan nil}) :dispatch-n [[:capital/load] [:my-bots/public]]} {})))
(rf/reg-event-fx :capital/disconnect-safe
 (fn [{:keys [db]} _] {:db (update db :my-bots dissoc :capital-safe :capital-plan) :dispatch-n [[:capital/load] [:my-bots/public]]}))
(def handlers
 {:capital-connect-safe #(rf/dispatch [:capital/connect-safe]) :capital-disconnect-safe #(rf/dispatch [:capital/disconnect-safe]) :capital-cancel #(rf/dispatch [:capital/cancel]) :capital-resume #(rf/dispatch [:capital/resume]) :capital-field #(rf/dispatch-sync [:capital/field %1 %2]) :capital-round #(rf/dispatch [:capital/load %])
  :capital-prepare #(rf/dispatch [:capital/prepare %]) :capital-execute #(rf/dispatch [:capital/execute]) :capital-refresh #(rf/dispatch [:capital/load])})
