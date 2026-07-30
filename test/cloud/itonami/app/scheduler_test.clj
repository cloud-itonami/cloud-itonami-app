(ns cloud.itonami.app.scheduler-test
  "Appointments: making one, asking people to it, and answering.

  Every one of these goes through `kotoba-lang/calendar` — the attendees,
  the RSVP states, the conflict rule. The app had been calling three of that
  library's functions and none of these, so what is under test here is
  mostly whether the app hands the model the right questions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.scheduler :as scheduler]
            [cloud.itonami.app.store :as store]))

(def ^:private alice "person-alice")
(def ^:private bob "person-bob")
(def ^:private carol "person-carol")

(use-fixtures :each (fn [run]
                      (with-redefs [store/transact!
                                    (fn [f & args] (apply swap! store/state f args))]
                        (reset! store/state (store/initial-state))
                        (run))))

(defn- make [attrs actor] (:event (scheduler/create! attrs actor)))

(def ^:private morning
  {:title "四半期の打ち合わせ"
   :start "2026-08-03T09:00:00Z" :end "2026-08-03T10:00:00Z"})

(deftest an-appointment-is-made-and-seen-by-the-people-on-it
  (let [event (make (assoc morning :attendees [bob]) alice)]
    (is (= "organizer" (:role event)))
    (is (= [bob] (:attendees event)))
    ;; Dense: an invitee who has not answered is `needs-action`, not absent.
    ;; `:calendar/rsvp` is sparse, and a list built from it omits exactly
    ;; the people the organizer is waiting on.
    (is (= {bob "needs-action"} (:rsvp event)))
    (is (nil? (:your-rsvp event)) "the organizer was not asked")
    (testing "the invitee sees it in their own list, as an attendee"
      (let [seen (first (:items (scheduler/events bob)))]
        (is (= (:id event) (:id seen)))
        (is (= "attendee" (:role seen)))
        (is (= "needs-action" (:your-rsvp seen)))))
    (testing "and somebody who is on neither side sees nothing"
      (is (= [] (:items (scheduler/events carol)))))))

(deftest an-event-you-are-not-on-does-not-exist-to-you
  ;; The same answer for "no such event" and "not yours". Telling them apart
  ;; tells a stranger that a meeting they were not invited to happened.
  (let [event (make morning alice)]
    (doseq [[id label] [[(:id event) "somebody else's event"]
                        ["evt-nothing" "an id that was never minted"]]]
      (is (= :scheduler/not-found
             (try (scheduler/respond! id "accepted" carol) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          label))))

(deftest answering-is-for-the-invited-and-only-the-three-answers
  (let [event (make (assoc morning :attendees [bob]) alice)]
    (is (= "accepted" (:your-rsvp (:event (scheduler/respond! (:id event) "accepted" bob)))))
    (is (= {bob "accepted"} (:rsvp (first (:items (scheduler/events alice))))))
    (testing "a change of mind is the answer, not a second one"
      (scheduler/respond! (:id event) "declined" bob)
      (is (= {bob "declined"} (:rsvp (first (:items (scheduler/events alice)))))))
    (testing "the organizer cannot answer their own invitation"
      ;; They can see it, so this is 403-shaped and not 404-shaped: not
      ;; invited, rather than no such event.
      (is (= :scheduler/not-invited
             (try (scheduler/respond! (:id event) "accepted" alice) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (testing "and an answer the model does not know is refused by name"
      ;; `calendar/respond` returns the calendar unchanged for an unknown
      ;; status, which as an API would be 200 and no change.
      (is (= :scheduler/unknown-rsvp
             (try (scheduler/respond! (:id event) "maybe-ish" bob) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

(deftest inviting-is-the-organizers-and-twice-is-once
  (let [event (make morning alice)]
    (is (false? (:already? (scheduler/invite! (:id event) bob alice))))
    (let [again (scheduler/invite! (:id event) bob alice)]
      (is (true? (:already? again)))
      ;; The list would gain a duplicate and the RSVP map would not, so the
      ;; same person would appear twice with one answer.
      (is (= [bob] (:attendees (:event again)))))
    (testing "an attendee cannot invite"
      (is (= :scheduler/not-organizer
             (try (scheduler/invite! (:id event) carol bob) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (testing "and the organizer is not invitable"
      (is (= :scheduler/organizer-is-not-an-attendee
             (try (scheduler/invite! (:id event) alice alice) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))

(deftest an-appointment-with-no-time-is-refused-by-the-model
  ;; `calendar.validate` already knows what a broken event is. This checks
  ;; the app asks it rather than holding a second opinion.
  (doseq [[attrs label] [[{:title "いつか"} "no times at all"]
                         [{:title "逆さま" :start "2026-08-03T10:00:00Z"
                           :end "2026-08-03T09:00:00Z"} "ends before it starts"]]]
    (is (= :scheduler/invalid-event
           (try (scheduler/create! attrs alice) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        label))
  (is (= [] (:items (scheduler/events alice))) "and nothing was stored"))

(deftest a-clash-is-what-you-said-yes-to
  (let [first-event (make (assoc morning :attendees [bob]) alice)
        overlapping (make {:title "重なる打ち合わせ" :attendees [bob]
                           :start "2026-08-03T09:30:00Z" :end "2026-08-03T10:30:00Z"}
                          carol)]
    (testing "an unanswered invitation still counts as a clash"
      ;; It is on your calendar until you say otherwise, which is why
      ;; declining is worth doing.
      (is (= [(:id first-event)] (mapv :id (scheduler/conflicts (:id overlapping) bob)))))
    (testing "declining clears it"
      (scheduler/respond! (:id first-event) "declined" bob)
      (is (= [] (scheduler/conflicts (:id overlapping) bob))))
    (testing "and an event never clashes with itself"
      (is (= [] (scheduler/conflicts (:id first-event) alice))))))

(deftest cancelling-is-the-organizers-and-takes-it-off-everyones-list
  (let [event (make (assoc morning :attendees [bob]) alice)]
    (is (= :scheduler/not-organizer
           (try (scheduler/cancel! (:id event) bob) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (scheduler/cancel! (:id event) alice)
    (is (= [] (:items (scheduler/events alice))))
    (is (= [] (:items (scheduler/events bob)))
        "the invitation was a mention in this event, so it goes with it")))

(deftest an-appointment-outlives-the-request-that-made-it
  ;; Through the store, so it is in the same state.edn everything else is in
  ;; and comes back when the process does.
  (let [event (make morning alice)]
    (is (= (:id event)
           (get-in @store/state [:scheduler :calendars alice
                                 :calendar/events (:id event) :calendar/id])))))
