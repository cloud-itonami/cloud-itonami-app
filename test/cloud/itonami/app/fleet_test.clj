(ns cloud.itonami.app.fleet-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            [cloud.itonami.app.fleet :as fleet]))

(deftest catalog-loads
  (testing "the shipped catalog parses and reconciles against the fleet"
    (let [{:keys [actors company-records]} (fleet/counts)]
      (is (= actors (count (fleet/actors))))
      (is (pos? actors))
      ;; The catalog now has two sources, so the invariant is stated over the
      ;; blueprint-derived half only: entries read from a blueprint.edn, plus
      ;; the :company/* records skipped as non-actors, must account for every
      ;; repository in orgs/cloud-itonami that carries that file — 1,338.
      ;; Asserting the sum rather than either number keeps this from failing
      ;; whenever an actor is added, while still catching a generator that
      ;; silently drops a population, which it did twice: first the :company/*
      ;; records, then the vector-wrapped isic ones.
      (is (= 1338 (+ (count (remove :reference-only (fleet/actors)))
                     company-records)))
      ;; The rest arrive by reference from west and are additive, never a
      ;; substitute for a blueprint that failed to parse.
      (is (= actors (+ (count (remove :reference-only (fleet/actors)))
                       (count (fleet/reference-only))))))))

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
  (testing "isic sector agents are on-demand, and are most of that class"
    ;; They answer an API or MCP request and stop; making that many processes
    ;; resident would pay continuously for idle.
    (let [od (fleet/by-execution :on-demand)
          sector (filter #(= :sector-agent (:role %)) od)]
      (is (< 400 (count sector)))
      (is (every? #(clojure.string/starts-with? (:repo %) "cloud-itonami-isic-") sector))))

  (testing "on-demand is broader than the sector agents"
    ;; It also covers the GitHub action adapter and the skill package, which
    ;; the authority classifies as on-demand for the same reason: they run when
    ;; something asks, and hold no loop. An earlier version of this test
    ;; asserted every on-demand actor was a :sector-agent, which was only true
    ;; while the catalog could not see beyond orgs/cloud-itonami.
    (is (= #{:sector-agent :github-action-adapter :agent-instruction-package}
           (set (map :role (fleet/by-execution :on-demand))))))

  (testing "resident actors are present, by reference"
    ;; They were absent while the catalog only read blueprint.edn from
    ;; orgs/cloud-itonami. They are here now because west pins them and the
    ;; authority classifies them — six loop- orchestrators and two person-
    ;; organisms — carrying a repo, a remote and a revision and nothing read
    ;; out of the repository.
    (let [r (fleet/by-execution :resident)]
      (is (= 8 (count r)))
      (is (every? :reference-only r))
      (is (every? #(re-matches #"[0-9a-f]{40}" (fleet/revision %)) r))
      (is (= #{:continuous-orchestrator :artificial-organism-actor}
             (set (map :role r))))))

  (testing "a reference carries a pin and no content"
    ;; The point of by-reference inclusion: the catalog must not become a
    ;; mirror. If a field from inside one of those repositories ever appears
    ;; here, this fails.
    (let [allowed #{:repo :repo-name :remote :revision :path :role :execution
                    :reference-only :id :authority-library}]
      (doseq [e (fleet/reference-only)]
        (is (empty? (remove allowed (keys e)))
            (str (:repo e) " leaked a field beyond its pin")))))

  (testing "person actors pin the organism model rather than vendoring it"
    ;; ao is "model only, no CLI, no runner, no storage, no clock", so an actor
    ;; supplies both and holds a hash to the model.
    (doseq [p (filter #(= :artificial-organism-actor (:role %)) (fleet/actors))]
      (let [lib (:authority-library p)]
        (is (= "ao" (:repo-name lib)))
        (is (re-matches #"[0-9a-f]{40}" (:revision lib))))))

  (testing "resident actors are not probed, and not because they are down"
    ;; A loop- orchestrator runs on a schedule and has no HTTP surface. Its
    ;; liveness is whether it recorded evidence recently, which this catalog
    ;; does not carry — so it is not probeable, not unhealthy.
    (is (every? (complement fleet/probeable?) (fleet/by-execution :resident))))

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
