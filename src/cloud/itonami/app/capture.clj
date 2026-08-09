(ns cloud.itonami.app.capture
  "A deliberately weak front door for thoughts, and a separate GTD clarification step.

  Capture never calls a model and never asks for a project, due date, or action.
  Those belong to clarification. The raw text is immutable after admission so a
  later tidy title cannot rewrite what the person actually put down."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store]))

(def schema "cloud.itonami.app.capture.v1")
(def modes #{:quick-capture :freewriting :think-aloud})
(def outcomes #{:next-action :project :waiting-for :someday-maybe :reference :trash})
(def max-text-length 100000)
(def ^:private max-source-preview-length 4000)

(defn- refuse! [message type data]
  (throw (ex-info message (assoc data :type type :status 400))))

(defn- normalized-keyword [value fallback]
  (if (or (nil? value) (str/blank? (str value)))
    fallback
    (keyword (name value))))

(defn- require-text [value]
  (when-not (string? value)
    (refuse! "本文は文字列で指定してください。" :capture/invalid-text {}))
  (when (str/blank? value)
    (refuse! "空の記録は保存できません。" :capture/blank-text {}))
  (when (> (count value) max-text-length)
    (refuse! "一つの記録は100,000文字までです。" :capture/text-too-long
             {:maximum max-text-length :actual (count value)}))
  value)

(defn- default-title [text]
  (let [line (or (some->> (str/split-lines text)
                          (map str/trim)
                          (remove str/blank?)
                          first)
                 "無題の記録")]
    (subs line 0 (min 120 (count line)))))

(defn- bounded-string [value maximum]
  (let [value (str (or value ""))]
    (subs value 0 (min maximum (count value)))))

(defn- normalized-source [source]
  (when source
    (when-not (= :chronicle-frame (:type source))
      (refuse! "未知のCapture出典です。" :capture/invalid-source
               {:source-type (:type source)}))
    (when (str/blank? (str (:frame-id source)))
      (refuse! "Chronicle frame IDが必要です。" :capture/invalid-source {}))
    {:type :chronicle-frame
     :frame-id (bounded-string (:frame-id source) 200)
     :captured-at (bounded-string (:captured-at source) 80)
     :application (bounded-string (:application source) 200)
     :text-preview (bounded-string (:text-preview source) max-source-preview-length)
     :trust :untrusted-reference}))

(defn- mine? [item actor organization]
  (and (= actor (:capture/owner item))
       (= organization (:capture/organization item))))

(defn- item! [state id actor organization]
  (let [item (get-in state [:capture :items id])]
    (when-not (and item (mine? item actor organization))
      (throw (ex-info "記録が見つかりません。"
                      {:type :capture/not-found :status 404 :capture id})))
    item))

(defn items
  "Newest-first records belonging to one human in the active organization."
  ([actor organization] (items (store/snapshot) actor organization {}))
  ([state actor organization {:keys [outcome state-filter]}]
   (let [outcome (some-> outcome (normalized-keyword nil))
         state-filter (some-> state-filter (normalized-keyword nil))]
     (->> (vals (get-in state [:capture :items] {}))
          (filter #(mine? % actor organization))
          (filter #(if outcome (= outcome (:capture/outcome %)) true))
          (filter #(if state-filter (= state-filter (:capture/state %)) true))
          (sort-by (juxt :capture/created-at :capture/id) #(compare %2 %1))
          vec))))

(defn public-item
  "The JSON-facing projection. Keep namespaced keys on disk and use ordinary
  field names on the wire; JSON object keys cannot preserve Clojure namespaces
  through every client decoder."
  [item]
  (into {}
        (for [[key value] item]
          [(keyword (name key)) value])))

(defn snapshot [actor organization]
  (let [records (items actor organization)
        counts (frequencies
                (map #(case (:capture/state %)
                        :unclarified :inbox
                        :completed :done
                        (:capture/outcome %))
                     records))]
    {:schema schema
     :items (mapv public-item records)
     :counts (merge {:inbox 0 :next-action 0 :project 0 :waiting-for 0
                     :someday-maybe 0 :reference 0 :trash 0 :done 0}
                    counts)}))

(defn create!
  "Admit raw text without interpreting it. Whitespace and line breaks are kept."
  ([actor organization request]
   (create! actor organization request nil))
  ([actor organization {:keys [text mode]} source]
   (when (str/blank? (str actor))
     (throw (ex-info "認証が必要です。" {:type :identity/unauthenticated :status 401})))
   (when (str/blank? (str organization))
     (throw (ex-info "Organizationを選択してください。"
                     {:type :identity/organization-required :status 400})))
   (let [text (require-text text)
         mode (normalized-keyword mode :quick-capture)
         source (normalized-source source)]
     (when-not (modes mode)
       (refuse! "未知の記録モードです。" :capture/invalid-mode {:mode mode}))
     (let [id (store/new-id "capture")
           now (store/now)
           item (cond-> {:capture/schema schema
                         :capture/id id
                         :capture/owner actor
                         :capture/organization organization
                         :capture/text text
                         :capture/mode mode
                         :capture/state :unclarified
                         :capture/created-at now}
                  source (assoc :capture/source source))]
       (store/transact!
        (fn [state]
          (-> state
              (assoc-in [:capture :items id] item)
              (update-in [:capture :events] (fnil conj [])
                         (cond-> {:capture.event/type :captured
                                  :capture.event/capture id
                                  :capture.event/actor actor
                                  :capture.event/at now}
                           source
                           (assoc :capture.event/source :chronicle-frame
                                  :capture.event/source-id (:frame-id source)))))))
       item))))

(defn clarify!
  "Classify a capture after the raw thought exists. This never executes work."
  [id actor organization values]
  (let [outcome (normalized-keyword (:outcome values) nil)]
    (when-not (outcomes outcome)
      (refuse! "整理先を選択してください。" :capture/invalid-outcome
               {:outcome outcome :allowed (vec (sort outcomes))}))
    (let [now (store/now)
          updated (atom nil)]
      (store/transact!
       (fn [state]
         (let [item (item! state id actor organization)
               title (or (not-empty (str/trim (str (:title values))))
                         (default-title (:capture/text item)))
               clarified (cond->
                           (assoc item
                                  :capture/state :clarified
                                  :capture/outcome outcome
                                  :capture/title title
                                  :capture/clarified-at now
                                  :capture/updated-at now)
                            (not (str/blank? (str (:project values))))
                            (assoc :capture/project (str/trim (str (:project values))))
                            (not (str/blank? (str (:context values))))
                            (assoc :capture/context (str/trim (str (:context values))))
                            (not (str/blank? (str (:due values))))
                            (assoc :capture/due (str/trim (str (:due values))))
                            (not (str/blank? (str (:waiting-for values))))
                            (assoc :capture/waiting-for
                                   (str/trim (str (:waiting-for values)))))]
           (reset! updated clarified)
           (-> state
               (assoc-in [:capture :items id] clarified)
               (update-in [:capture :events] (fnil conj [])
                          {:capture.event/type :clarified
                           :capture.event/capture id
                           :capture.event/outcome outcome
                           :capture.event/actor actor
                           :capture.event/at now})))))
      @updated)))

(defn review!
  "Mark a clarified item as consciously seen in a review, without changing it."
  [id actor organization]
  (let [now (store/now)
        updated (atom nil)]
    (store/transact!
     (fn [state]
       (let [item (item! state id actor organization)]
         (when (= :unclarified (:capture/state item))
           (refuse! "未整理の記録は、先に整理先を決めてください。"
                    :capture/unclarified {}))
         (let [reviewed (-> item
                            (assoc :capture/last-reviewed-at now
                                   :capture/updated-at now)
                            (update :capture/review-count (fnil inc 0)))]
           (reset! updated reviewed)
           (-> state
               (assoc-in [:capture :items id] reviewed)
               (update-in [:capture :events] (fnil conj [])
                          {:capture.event/type :reviewed
                           :capture.event/capture id
                           :capture.event/actor actor
                           :capture.event/at now}))))))
    @updated))

(defn complete!
  "Close an organized result without deleting or disguising its history."
  [id actor organization]
  (let [now (store/now)
        updated (atom nil)]
    (store/transact!
     (fn [state]
       (let [item (item! state id actor organization)]
         (when (= :unclarified (:capture/state item))
           (refuse! "未整理の記録は完了にできません。先に整理してください。"
                    :capture/unclarified {}))
         (let [completed (assoc item :capture/state :completed
                                :capture/completed-at now :capture/updated-at now)]
           (reset! updated completed)
           (-> state
               (assoc-in [:capture :items id] completed)
               (update-in [:capture :events] (fnil conj [])
                          {:capture.event/type :completed
                           :capture.event/capture id
                           :capture.event/actor actor
                           :capture.event/at now}))))))
    @updated))

(defn reopen!
  "Return an item to the inbox. Raw text and its audit history remain intact."
  [id actor organization]
  (let [now (store/now)
        updated (atom nil)]
    (store/transact!
     (fn [state]
       (let [item (item! state id actor organization)
             reopened (-> item
                          (assoc :capture/state :unclarified
                                 :capture/updated-at now)
                          (dissoc :capture/outcome :capture/title :capture/project
                                  :capture/context :capture/due :capture/waiting-for
                                  :capture/clarified-at :capture/completed-at))]
         (reset! updated reopened)
         (-> state
             (assoc-in [:capture :items id] reopened)
             (update-in [:capture :events] (fnil conj [])
                        {:capture.event/type :reopened
                         :capture.event/capture id
                         :capture.event/actor actor
                         :capture.event/at now})))))
    @updated))
