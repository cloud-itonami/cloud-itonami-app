(ns cloud.itonami.app.fleet-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
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
    ;; Corrected 2026-07-30. These three were recorded as "not deployed" from a
    ;; `wrangler deployments list` check — a Workers-only command run against
    ;; Cloudflare PAGES projects. They are live on *.pages.dev, and two of them
    ;; now carry an address. cloud-itonami-partners still does not, for a
    ;; different reason: it has no blueprint.edn at all, so it is not in the
    ;; catalog to address.
    (doseq [repo ["cloud-itonami-isic-6492" "cloud-itonami-commitment-ledger"]]
      (let [a (fleet/actor repo)]
        (is (some? a) (str repo " should be in the catalog"))
        (is (fleet/callable? a) (str repo " is deployed on Pages and has an address"))
        (is (= :pages-dev (:endpoint-kind a)))))
    (is (nil? (fleet/actor "cloud-itonami-partners"))
        "deployed but has no blueprint.edn, so it cannot be catalogued")))

(deftest ids-collide-and-the-catalog-says-so
  (testing "id is not unique, and lookup does not pretend otherwise"
    ;; Three actors were duplicated into a second repository — a -component, a
    ;; -codex, and an ISIC code written both zero-padded and not — and each copy
    ;; kept the original's id. The first catalog shipped 1,183 entries under
    ;; 1,180 ids and a lookup by id silently returned whichever sorted first.
    (let [dups (fleet/duplicate-ids)]
      (is (= 3 (count dups)))
      (is (= (- (count (fleet/actors)) (count dups))
             (count (distinct (map :id (fleet/actors))))))
      (doseq [id dups]
        (is (< 1 (count (fleet/find-by-id id)))
            (str id " should return every claimant, not one")))))

  (testing "repo is unique, which is why lookup keys on it"
    (let [repos (map :repo (fleet/actors))]
      (is (every? some? repos))
      (is (= (count repos) (count (distinct repos)))))))

(deftest probeable-is-narrower-than-callable
  (testing "an actor with no health path is not probed"
    ;; The Pages actors answer /health with 200 and their SPA index. Probing an
    ;; assumed path would read that HTML as a healthy API — the exact false
    ;; positive :health-path exists to prevent.
    (let [pages (filter #(= :pages-dev (:endpoint-kind %)) (fleet/callable))]
      (is (seq pages))
      (is (every? (complement fleet/probeable?) pages))
      (is (every? #(= :not-probeable (:health %))
                  (vals (select-keys (fleet/probe-health! 250)
                                     (map :repo pages)))))))

  (testing "the Workers declare a path and are probed"
    (let [w (filter #(= :workers-dev (:endpoint-kind %)) (fleet/callable))]
      (is (seq w))
      (is (every? fleet/probeable? w))
      (is (every? #(= "/health" (:health-path %)) w)))))

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
    (with-redefs [fleet/callable (fn [] [{:repo "unreachable"
                                          :id "unreachable"
                                          :endpoint "https://actor.invalid"
                                          :health-path "/health"}])]
      (is (= :unknown (get-in (fleet/probe-health! 250) ["unreachable" :health]))))))

(deftest execution-model-separates-on-demand-from-resident
  (testing "isic actors are on-demand, and that is most of the fleet"
    ;; 452 of them. They answer an API or MCP request and stop; making that
    ;; many processes resident would pay continuously for idle.
    (let [od (fleet/by-execution :on-demand)]
      (is (< 400 (count od)))
      (is (every? #(= :sector-agent (:role %)) od))
      (is (every? #(clojure.string/starts-with? (:repo %) "cloud-itonami-isic-") od))))

  (testing "no resident actors are in this catalog, and the reason is scope"
    ;; person-* and loop-* are resident, but they live outside cloud-itonami
    ;; and carry no blueprint.edn, so the generator cannot see them. Asserting
    ;; zero here records that boundary — if resident actors ever appear, the
    ;; missing-endpoint semantics in this namespace start mattering.
    (is (empty? (fleet/by-execution :resident))))

  (testing "unclassified is reported, not defaulted"
    ;; marketplace, assoc, municipality and others match no prefix rule yet.
    ;; Defaulting them to :on-demand because they happen to be reachable would
    ;; turn an unfinished vocabulary into a confident answer.
    (let [u (fleet/by-execution :unclassified)]
      (is (seq u))
      (is (every? #(nil? (:execution %)) u))
      (is (= (count (fleet/actors))
             (+ (count u)
                (count (fleet/by-execution :on-demand))
                (count (fleet/by-execution :resident)))))))

  (testing "counts expose the split"
    (let [{:keys [by-execution]} (fleet/counts)]
      (is (pos? (:on-demand by-execution)))
      (is (pos? (:unclassified by-execution))))))
