(ns cloud.itonami.app.fleet-test
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.fleet :as fleet]))

(deftest catalog-loads
  (testing "the shipped catalog parses and reconciles against the fleet"
    (let [{:keys [actors company-records]} (fleet/counts)]
      (is (= actors (count (fleet/actors))))
      (is (pos? actors))
      ;; 1,183 actors + 155 company records = the 1,338 repositories carrying a
      ;; blueprint.edn. Asserting the sum rather than either number keeps this
      ;; from failing every time an actor is added, while still catching a
      ;; generator that silently drops a population — which it did twice:
      ;; first the :company/* records, then the vector-wrapped isic ones.
      (is (= 1338 (+ actors company-records))))))

(deftest callable-is-the-line-between-directory-and-service
  (testing "callable? tracks the presence of an address, nothing else"
    (let [c (fleet/callable)]
      (is (seq c))
      (is (every? :endpoint c))
      (is (every? #(re-find #"^https://" (:endpoint %)) c))
      (is (= (count c) (:callable (fleet/counts))))))

  (testing "the overwhelming majority are directory records, and that is correct"
    ;; Not a wish: 1,326 of the 1,338 repositories have no deployable Worker at
    ;; all. If this ever inverts it means addresses were invented rather than
    ;; measured.
    (is (< (count (fleet/callable)) (/ (count (fleet/actors)) 10))))

  (testing "only deployed actors carry an address"
    ;; commitment-ledger, isic-6492 and partners ship a wrangler.jsonc but are
    ;; not deployed. Giving them an endpoint is the specific mistake this
    ;; guards: a URL that looks usable and answers nothing.
    (doseq [id ["cloud-itonami-commitment-ledger"
                "cloud-itonami-isic-6492"
                "cloud-itonami-partners"]]
      (when-some [a (fleet/actor id)]
        (is (not (fleet/callable? a))
            (str id " is not deployed and must not declare an endpoint"))))))

(deftest search-filters
  (testing "criteria are ANDed and omitted criteria do not constrain"
    (is (= (count (fleet/actors)) (count (fleet/search {}))))
    (let [m (fleet/search {:text "marketplace" :callable? true})]
      (is (seq m))
      (is (every? fleet/callable? m))))

  (testing "isic matches whichever revision the actor codes in"
    ;; The fleet uses :isic, :isic-rev4 and :isic-rev5 inconsistently; a caller
    ;; should not have to know which.
    (let [rev4 (->> (fleet/actors) (keep :isic-rev4) first)]
      (when rev4
        (is (seq (fleet/search {:isic rev4}))))))

  (testing "an unmatched criterion returns empty, not everything"
    (is (empty? (fleet/search {:domain :no-such/domain})))))

(deftest facets-drive-the-filter-ui
  (let [f (fleet/facets :maturity)]
    (is (seq f))
    (is (every? (fn [[_ n]] (pos? n)) f))
    (is (apply >= (map second f)) "most common first")))

(deftest probe-distinguishes-down-from-unmeasured
  (testing "an unreachable endpoint is :unknown, never :down"
    ;; .invalid is reserved by RFC 2606 and never resolves, so this exercises
    ;; the failure path without touching the network. Reporting it as :down
    ;; would present a DNS failure as a broken actor.
    (with-redefs [fleet/callable (fn [] [{:id "unreachable"
                                          :endpoint "https://actor.invalid"}])]
      (is (= :unknown (get-in (fleet/probe-health! 250) ["unreachable" :health]))))))
