(ns cloud.itonami.app.presentation-request-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.presentation-request :as pr]
            [cloud.itonami.app.store :as store])
  (:import [java.time Instant]))

(use-fixtures :each
  (fn [f]
    (store/transact! (fn [c] (assoc c :presentation-requests {} :events [])))
    (f)))

(def ^:private response-uri "http://localhost:1338/api/presentations/responses")

(defn- create [] (pr/create! {:response-uri response-uri :actor "user-alice"}))

(deftest a-request-carries-what-5-3-demands
  (let [{:keys [request id]} (create)]
    (is (= "vp_token" (get request "response_type")))
    (is (= "direct_post" (get request "response_mode")))
    (is (= (str "redirect_uri:" response-uri) (get request "client_id")))
    (testing "§5.3: state is present and long enough to carry 128 bits"
      (is (>= (count (get request "state")) 22)))
    (is (>= (count (get request "nonce")) 22))
    (is (some? (get request "dcql_query")))
    (testing "and the app remembers it, or state would be a value we forgot"
      (is (= [id] (mapv :id (pr/pending (store/snapshot))))))))

(deftest the-listing-does-not-leak-the-secrets
  (create)
  (let [listed (first (pr/pending (store/snapshot)))]
    (testing "nonce and state are what make the exchange non-replayable; a
              listing surface has no use for them"
      (is (not (contains? listed :nonce)))
      (is (not (contains? listed :state))))
    (is (= "user-alice" (:actor listed)))))

(deftest a-matching-response-validates-once
  (let [{:keys [request id]} (create)
        state (get request "state")
        first-try (pr/validate-response {"vp_token" "eyJ..." "state" state})]
    (is (:valid? first-try))
    (is (= id (:request-id first-try)))
    (testing "and it says it only checked the envelope"
      (is (true? (:envelope-only? first-try))))
    (testing "a request is single-use, so the same response does not replay"
      (let [second-try (pr/validate-response {"vp_token" "eyJ..." "state" state})]
        (is (false? (:valid? second-try)))
        (is (= :presentation-request/unknown-state (:reason second-try)))))
    (testing "and it is gone from the pending list"
      (is (empty? (pr/pending (store/snapshot)))))))

(deftest a-state-we-never-issued-is-refused
  (create)
  (doseq [state ["not-a-state" "" nil]]
    (let [r (pr/validate-response {"vp_token" "eyJ..." "state" state})]
      (is (false? (:valid? r)) (str "state " (pr/pending (store/snapshot)) " " state))
      (is (= :presentation-request/unknown-state (:reason r))))))

(deftest unknown-already-answered-and-expired-are-one-answer
  (testing "telling them apart tells a prober which states existed"
    (let [{:keys [request]} (create)
          state (get request "state")
          later (.plusSeconds (Instant/now) (* 2 pr/default-lifetime-seconds))
          r (pr/validate-response {"vp_token" "eyJ..." "state" state} later)]
      (is (false? (:valid? r)))
      (is (= :presentation-request/unknown-state (:reason r))
          "the same reason an unissued state gets"))))

(deftest an-expired-request-is-still-consumed
  (testing "so a stale request cannot sit around being probed"
    (let [{:keys [request]} (create)
          later (.plusSeconds (Instant/now) (* 2 pr/default-lifetime-seconds))]
      (pr/validate-response {"vp_token" "eyJ..." "state" (get request "state")} later)
      (is (empty? (pr/pending (store/snapshot)))))))

(deftest a-response-without-a-vp-token-is-refused
  (let [{:keys [request]} (create)
        r (pr/validate-response {"state" (get request "state")})]
    (is (false? (:valid? r)))
    (is (= :oid4vp/missing-vp-token (:reason r)))))

(deftest creation-is-recorded
  (create)
  (let [event (first (filter #(= :presentation-request/created (:type %))
                             (:events (store/snapshot))))]
    (is (some? event))
    (is (= "user-alice" (:actor event)))))
