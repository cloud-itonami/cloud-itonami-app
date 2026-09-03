(ns cloud.itonami.app.bulky-waste
  "A human-computing vertical slice for bulky, non-hazardous waste.

  This is the join that the generic HC design and the ISCO/ISIC blueprints did
  not provide: a resident may publish one pickup, an eligible human User may
  book it, and collection evidence is carried through a permitted facility to
  a recovery receipt.  It is deliberately a control plane.  Evidence refs are
  recorded and only become eligible after an organization verifier binds a
  decision to the exact claim version; trucks, scales, permits and physical
  sorting remain external authorities.

  Exact pickup addresses are visible only to the requester and the booked
  worker.  An open-job candidate sees the service area, window and items, which
  is enough to decide whether to book without exposing a resident's address."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cloud.itonami.app.human-work :as human-work]
            #?(:clj [cloud.itonami.app.store :as store])))

(def schema "cloud.itonami.app.bulky-waste.v1")

(def allowed-categories
  #{"bedding" "wood-furniture" "metal-furniture" "plastic-furniture"
    "mixed-non-hazardous"})

(def terminal-statuses #{"cancelled" "recovered"})
(def ^:private active-assignment-statuses
  #{"booked" "checked-in" "collected" "delivered"})
#?(:clj (defonce ^:private write-lock (Object.)))

;; The decision/state-machine source stays portable. A runtime supplies these
;; four effects; the JVM app has a default adapter to its durable state.edn.
(def ^:dynamic *snapshot* #?(:clj store/snapshot :cljs nil))
(def ^:dynamic *transact!* #?(:clj store/transact! :cljs nil))
(def ^:dynamic *new-id* #?(:clj store/new-id :cljs nil))
(def ^:dynamic *now* #?(:clj store/now :cljs nil))

(defn- host-call [f label & args]
  (when-not f
    (throw (ex-info (str "No bulky-waste host adapter for " label)
                    {:type :bulky-waste/host-unavailable :effect label})))
  (apply f args))

(defn- snapshot [] (host-call *snapshot* :snapshot))
(defn- transact! [f & args] (apply host-call *transact!* :transact f args))
(defn- new-id [prefix] (host-call *new-id* :new-id prefix))
(defn- now [] (host-call *now* :now))

(defn- fail! [type message & [data]]
  (throw (ex-info message (merge {:type type} data))))

(defn- present? [value]
  (and (string? value) (not (str/blank? value))))

(defn- positive-int? [value]
  (and (integer? value) (pos? value)))

(defn- epoch-millis [value]
  (try
    (when (present? value)
      #?(:clj (.toEpochMilli (java.time.Instant/parse value))
         :cljs (.parse js/Date value)))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- valid-window? [{:keys [start end]}]
  (let [start' (epoch-millis start) end' (epoch-millis end)]
    (and start' end' (< start' end'))))

(defn- contains-window? [outer inner]
  (let [outer-start (epoch-millis (:start outer)) outer-end (epoch-millis (:end outer))
        inner-start (epoch-millis (:start inner)) inner-end (epoch-millis (:end inner))]
    (and outer-start outer-end inner-start inner-end
         (<= outer-start inner-start)
         (>= outer-end inner-end))))

(defn- overlaps? [a b]
  (let [a-start (epoch-millis (:start a)) a-end (epoch-millis (:end a))
        b-start (epoch-millis (:start b)) b-end (epoch-millis (:end b))]
    (and (< a-start b-end) (< b-start a-end))))

(defn- validate-items! [items]
  (when-not (and (vector? items) (seq items))
    (fail! :bulky-waste/invalid "items must be a non-empty vector"))
  (doseq [{:keys [category quantity unit-weight-grams description]} items]
    (when-not (contains? allowed-categories category)
      (fail! :bulky-waste/unsupported-category
             "Only the explicit non-hazardous bulky-waste categories are accepted"
             {:category category}))
    (when-not (and (positive-int? quantity) (positive-int? unit-weight-grams)
                   (present? description))
      (fail! :bulky-waste/invalid
             "Every item needs description, positive quantity and unit-weight-grams")))
  items)

(defn- estimated-weight [items]
  (reduce + (map #(* (:quantity %) (:unit-weight-grams %)) items)))

(defn- validate-evidence! [evidence]
  (doseq [key [:vehicle :insurance :waste-carrier :service-location]]
    (when-not (present? (get evidence key))
      (fail! :bulky-waste/evidence-required
             "vehicle, insurance, waste-carrier and service-location evidence refs are required"
             {:missing key}))))

(defn register-worker!
  "Register or replace `actor`'s own availability profile.

  Evidence is explicitly self-attested. Registration does not make the worker
  matchable until an organization verifier checks the service location,
  licence, insurance, and vehicle claims through the HumanWork registry."
  [{:keys [service-areas categories capacity-grams availability evidence
           country region]} actor]
  (when-not (present? actor) (fail! :identity/unauthenticated "Sign in required"))
  (when-not (and (vector? service-areas) (seq service-areas)
                 (every? present? service-areas))
    (fail! :bulky-waste/invalid "service-areas must be non-empty strings"))
  (when-not (and (vector? categories) (seq categories)
                 (set/subset? (set categories) allowed-categories))
    (fail! :bulky-waste/unsupported-category "Worker categories are unsupported"))
  (when-not (positive-int? capacity-grams)
    (fail! :bulky-waste/invalid "capacity-grams must be a positive integer"))
  (when-not (and (vector? availability) (seq availability)
                 (every? valid-window? availability))
    (fail! :bulky-waste/invalid "availability must contain valid ISO-8601 windows"))
  (validate-evidence! evidence)
  (let [country (or country "JP")
        human-profile
        (human-work/register-worker!
         {:locations [{:location-id "bulky-waste-service-area"
                       :country country :region region
                       :service-areas service-areas
                       :work-modes ["onsite"]
                       :evidence-ref (:service-location evidence)}]
          :availability availability
          :credentials
          [{:credential-id "bulky-waste-carrier-license"
            :type "license" :name "Waste carrier licence"
            :issuer "declared issuing authority"
            :jurisdiction {:country country :region region}
            :scopes ["bulky-waste-collection"]
            :evidence-ref (:waste-carrier evidence)}
           {:credential-id "bulky-waste-vehicle-insurance"
            :type "insurance" :name "Commercial vehicle insurance"
            :issuer "declared insurer"
            :jurisdiction {:country country :region region}
            :scopes ["commercial-vehicle"]
            :evidence-ref (:insurance evidence)}
           {:credential-id "bulky-waste-collection-vehicle"
            :type "asset" :name "Collection vehicle"
            :issuer "declared vehicle registry"
            :jurisdiction {:country country :region region}
            :scopes ["collection-vehicle"]
            :evidence-ref (:vehicle evidence)}]}
         actor)
        profile {:schema schema
                 :worker-id actor
                 :service-areas (vec (distinct service-areas))
                 :categories (vec (distinct categories))
                 :capacity-grams capacity-grams
                 :availability availability
                 :evidence evidence
                 :human-work-worker-id (:worker-id human-profile)
                 :eligibility-verification "organization-verified-required"
                 :active? true
                 :updated-at (now)}]
    (transact! assoc-in [:bulky-waste :workers actor] profile)
    profile))

(defn- event [action actor evidence]
  (cond-> {:action action :actor actor :at (now)}
    (seq evidence) (assoc :evidence evidence)))

(defn create-job!
  [{:keys [organization-id service-area country region pickup-address access-notes
           pickup-window items facility-id facility-operator-id
           facility-permit-evidence-ref]}
   actor]
  (when-not (present? actor) (fail! :identity/unauthenticated "Sign in required"))
  (when-not (and (present? organization-id) (present? service-area)
                 (present? pickup-address)
                 (present? facility-id) (present? facility-operator-id)
                 (present? facility-permit-evidence-ref)
                 (valid-window? pickup-window))
    (fail! :bulky-waste/invalid
           "pickup, facility, facility operator, permit evidence and a valid window are required"))
  (validate-items! items)
  (let [id (new-id "bulky")
        job {:schema schema :id id :requester-id actor :status "draft"
             :organization-id organization-id
             :service-area service-area :country (or country "JP") :region region
             :pickup-address pickup-address
             :access-notes access-notes :pickup-window pickup-window
             :items items :estimated-weight-grams (estimated-weight items)
             :facility-id facility-id :facility-operator-id facility-operator-id
             :facility-permit-evidence-ref facility-permit-evidence-ref
             :worker-id nil
             :created-at (now)
             :audit [(event "created" actor nil)]}]
    (transact! assoc-in [:bulky-waste :jobs id] job)
    job))

(defn- job! [state id]
  (or (get-in state [:bulky-waste :jobs id])
      (fail! :bulky-waste/not-found "No such bulky-waste job")))

(defn- owner! [job actor]
  (when-not (= actor (:requester-id job))
    (fail! :bulky-waste/forbidden "Only the requester may do that")))

(defn- worker! [job actor]
  (when-not (= actor (:worker-id job))
    (fail! :bulky-waste/forbidden "Only the booked worker may do that")))

(defn- facility! [job actor]
  (when-not (= actor (:facility-operator-id job))
    (fail! :bulky-waste/forbidden "Only the selected facility may do that")))

(defn- require-status! [job expected]
  (when-not (= expected (:status job))
    (fail! :bulky-waste/invalid-transition
           (str "Expected status " expected ", got " (:status job)))))

(defn- conflicting-booking? [state worker-id job]
  (some (fn [other]
          (and (= worker-id (:worker-id other))
               (contains? active-assignment-statuses (:status other))
               (not= (:id job) (:id other))
               (overlaps? (:pickup-window other) (:pickup-window job))))
        (vals (get-in state [:bulky-waste :jobs] {}))))

(defn- require-unused-evidence!
  [state job path value kind]
  (when (some (fn [other]
                (and (not= (:id job) (:id other))
                     (= value (get-in other path))))
              (vals (get-in state [:bulky-waste :jobs] {})))
    (fail! :bulky-waste/invalid-transition
           "A chain-of-custody evidence reference cannot be reused"
           {:reason :duplicate-evidence
            :evidence-kind kind :evidence-ref value})))

(defn- human-work-request-view [job]
  {:id (:id job)
   :organization-id (:organization-id job)
   :work-mode "onsite"
   :location {:country (:country job)
              :region (:region job)
              :service-area (:service-area job)
              :minimum-verification "verified"}
   :work-window (:pickup-window job)
   :requirements
   {:credentials
    [{:type "license" :scopes ["bulky-waste-collection"]
      :jurisdiction {:country (:country job) :region (:region job)}
      :minimum-verification "verified"}
     {:type "insurance" :scopes ["commercial-vehicle"]
      :jurisdiction {:country (:country job) :region (:region job)}
      :minimum-verification "verified"}
     {:type "asset" :scopes ["collection-vehicle"]
      :jurisdiction {:country (:country job) :region (:region job)}
      :minimum-verification "verified"}]}})

(defn- qualification-report [state profile job]
  (when-let [human-profile
             (get-in state [:human-work :workers (:worker-id profile)])]
    (human-work/eligibility-report state human-profile
                                   (human-work-request-view job))))

(defn- eligible? [state profile job]
  (let [qualification (qualification-report state profile job)]
    (and (:eligible? qualification)
       (:active? profile)
       (some #{(:service-area job)} (:service-areas profile))
       (set/subset? (set (map :category (:items job)))
                    (set (:categories profile)))
       (>= (:capacity-grams profile) (:estimated-weight-grams job))
       (some #(contains-window? % (:pickup-window job)) (:availability profile))
       (not (conflicting-booking? state (:worker-id profile) job)))))

(defn- candidate-view [state profile job]
  {:worker-id (:worker-id profile)
   :capacity-grams (:capacity-grams profile)
   :spare-capacity-grams (- (:capacity-grams profile)
                            (:estimated-weight-grams job))
   :eligibility (qualification-report state profile job)})

(defn matches
  "Eligible workers, least spare vehicle capacity first. Requester-only."
  [id actor]
  (let [state (snapshot) job (job! state id)]
    (owner! job actor)
    (when-not (= "open" (:status job))
      (fail! :bulky-waste/invalid-transition "Matching requires an open job"))
    {:schema schema :job-id id
     :items (->> (vals (get-in state [:bulky-waste :workers] {}))
                 (filter #(eligible? state % job))
                 (map #(candidate-view state % job))
                 (sort-by (juxt :spare-capacity-grams :worker-id))
                 vec)}))

(defn- mutate-job!
  [id f]
  (let [run! (fn []
               (transact!
                (fn [state]
                  (let [job (job! state id)
                        next-job (f state job)]
                    (assoc-in state [:bulky-waste :jobs id] next-job))))
               (get-in (snapshot) [:bulky-waste :jobs id]))]
    #?(:clj (locking write-lock (run!))
       :cljs (run!))))

(defn publish! [id actor]
  (mutate-job! id
               (fn [_ job]
                 (owner! job actor) (require-status! job "draft")
                 (-> job (assoc :status "open" :published-at (now))
                     (update :audit conj (event "published" actor nil))))))

(defn book! [id actor]
  (mutate-job! id
               (fn [state job]
                 (require-status! job "open")
                 (let [profile (get-in state [:bulky-waste :workers actor])]
                   (when-not (and profile (eligible? state profile job))
                     (fail! :bulky-waste/not-eligible
                            "The worker does not currently match this job"))
                   (let [qualification (qualification-report state profile job)]
                     (-> job (assoc :status "booked" :worker-id actor
                                  :booked-at (now))
                       (update :audit conj (event "booked" actor
                                                  {:eligibility qualification}))))))))

(defn check-in! [id {:keys [presence-proof-ref]} actor]
  (when-not (present? presence-proof-ref)
    (fail! :bulky-waste/evidence-required "presence-proof-ref is required"))
  (mutate-job! id
               (fn [_ job]
                 (worker! job actor) (require-status! job "booked")
                 (-> job (assoc :status "checked-in")
                     (update :audit conj
                             (event "checked-in" actor
                                    {:presence-proof-ref presence-proof-ref}))))))

(defn collect!
  [id {:keys [manifest-id actual-weight-grams collection-proof-ref]} actor]
  (when-not (and (present? manifest-id) (present? collection-proof-ref)
                 (positive-int? actual-weight-grams))
    (fail! :bulky-waste/evidence-required
           "manifest-id, collection-proof-ref and actual-weight-grams are required"))
  (mutate-job! id
               (fn [state job]
                 (worker! job actor) (require-status! job "checked-in")
                 (require-unused-evidence! state job [:collection :manifest-id]
                                           manifest-id :manifest)
                 (let [capacity (get-in state [:bulky-waste :workers actor
                                               :capacity-grams])]
                   (when (> actual-weight-grams capacity)
                     (fail! :bulky-waste/capacity-exceeded
                            "Actual weight exceeds the booked vehicle capacity"))
                   (-> job
                       (assoc :status "collected"
                              :collection {:manifest-id manifest-id
                                           :actual-weight-grams actual-weight-grams
                                           :proof-ref collection-proof-ref
                                           :at (now)})
                       (update :audit conj
                               (event "collected" actor
                                      {:manifest-id manifest-id
                                       :collection-proof-ref collection-proof-ref})))))))

(defn deliver!
  [id {:keys [facility-receipt-ref batch-id accepted-weight-grams]} actor]
  (when-not (and (present? facility-receipt-ref) (present? batch-id)
                 (positive-int? accepted-weight-grams))
    (fail! :bulky-waste/evidence-required
           "facility-receipt-ref, batch-id and accepted-weight-grams are required"))
  (mutate-job! id
               (fn [state job]
                 (facility! job actor) (require-status! job "collected")
                 (require-unused-evidence! state job
                                           [:delivery :facility-receipt-ref]
                                           facility-receipt-ref :facility-receipt)
                 (require-unused-evidence! state job [:delivery :batch-id]
                                           batch-id :recovery-batch)
                 (when (> accepted-weight-grams
                          (get-in job [:collection :actual-weight-grams]))
                   (fail! :bulky-waste/invalid-weight
                          "A facility cannot accept more than was collected"))
                 (-> job
                     (assoc :status "delivered"
                            :delivery {:facility-receipt-ref facility-receipt-ref
                                       :batch-id batch-id
                                       :accepted-weight-grams accepted-weight-grams
                                       :at (now)})
                     (update :audit conj
                             (event "delivered" actor
                                    {:facility-receipt-ref facility-receipt-ref
                                     :batch-id batch-id}))))))

(defn recover!
  [id {:keys [recovery-receipt-ref recovered-weight-grams
              disposed-weight-grams outputs]} actor]
  (when-not (and (present? recovery-receipt-ref)
                 (integer? recovered-weight-grams) (not (neg? recovered-weight-grams))
                 (integer? disposed-weight-grams) (not (neg? disposed-weight-grams))
                 (vector? outputs)
                 (every? (fn [{:keys [material weight-grams]}]
                           (and (present? material) (integer? weight-grams)
                                (not (neg? weight-grams))))
                         outputs))
    (fail! :bulky-waste/evidence-required
           "A recovery receipt, non-negative weights and outputs are required"))
  (mutate-job! id
               (fn [state job]
                 (facility! job actor) (require-status! job "delivered")
                 (require-unused-evidence! state job [:recovery :receipt-ref]
                                           recovery-receipt-ref :recovery-receipt)
                 (when-not (= (get-in job [:delivery :accepted-weight-grams])
                              (+ recovered-weight-grams disposed-weight-grams))
                   (fail! :bulky-waste/invalid-weight
                          "Recovered plus disposed weight must equal accepted weight"))
                 (when-not (= recovered-weight-grams
                              (reduce + 0 (map :weight-grams outputs)))
                   (fail! :bulky-waste/invalid-weight
                          "Recovery output weights must equal recovered weight"))
                 (-> job
                     (assoc :status "recovered"
                            :recovery {:receipt-ref recovery-receipt-ref
                                       :recovered-weight-grams recovered-weight-grams
                                       :disposed-weight-grams disposed-weight-grams
                                       :outputs outputs :at (now)})
                     (update :audit conj
                             (event "recovered" actor
                                    {:recovery-receipt-ref recovery-receipt-ref}))))))

(defn cancel! [id actor]
  (mutate-job! id
               (fn [_ job]
                 (owner! job actor)
                 (when-not (contains? #{"draft" "open"} (:status job))
                   (fail! :bulky-waste/invalid-transition
                          "A booked or collected job cannot be cancelled here"))
                 (-> job (assoc :status "cancelled" :cancelled-at (now))
                     (update :audit conj (event "cancelled" actor nil))))))

(defn- redact-address [job]
  (dissoc job :pickup-address :access-notes))

(defn jobs
  "Jobs visible to `actor`, with addresses redacted before a worker books.

  Requesters see their own jobs, facilities see jobs routed to them, booked
  workers see their assignment, and eligible workers see redacted open jobs."
  [actor]
  (let [state (snapshot)
        profile (get-in state [:bulky-waste :workers actor])]
    {:schema schema :you actor
     :items
     (->> (vals (get-in state [:bulky-waste :jobs] {}))
          (keep (fn [job]
                  (cond
                    (= actor (:requester-id job)) job
                    (= actor (:worker-id job)) job
                    (= actor (:facility-operator-id job)) (redact-address job)
                    (and profile (= "open" (:status job))
                         (eligible? state profile job)) (redact-address job)
                    :else nil)))
          (sort-by (juxt :created-at :id)) vec)}))
