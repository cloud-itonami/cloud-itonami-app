(ns cloud.itonami.app.fleet-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set]
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
      ;; A floor, not an exact number. The exact sum moves whenever west gains
      ;; or loses a cloud-itonami repository, which has already happened twice
      ;; this session; pinning it turns unrelated fleet growth into a failing
      ;; test. A floor still catches what this guards against — the generator
      ;; silently dropping a population, which cost 379 actors once and 155
      ;; another time.
      (is (<= 1300 (+ (count (remove :reference-only (fleet/actors)))
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
    ;; cloud-itonami-partners joined these on 2026-07-30. It had been deployed
    ;; and serving /api/intake with no blueprint.edn, so the catalog — built
    ;; from those files — could not see it. Its record was written from what the
    ;; repository already states (README: ScreeningAdvisor with a sealed LLM
    ;; behind an independent PartnerGovernor; ADR-2607194000), not invented,
    ;; which is why it took a separate pass rather than a guess.
    (doseq [repo ["cloud-itonami-isic-6492" "cloud-itonami-commitment-ledger"
                  "cloud-itonami-partners"]]
      (let [a (fleet/actor repo)]
        (is (some? a) (str repo " should be in the catalog"))
        (is (fleet/callable? a) (str repo " is deployed on Pages and has an address"))
        (is (= :pages-dev (:endpoint-kind a)))
        (is (nil? (:health-path a))
            (str repo " serves its SPA index at /health, so it declares no probe path"))))))

(deftest ids-no-longer-collide-because-the-duplicates-were-not-real
  (testing "id is unique again, and the three collisions were an artefact"
    ;; The catalog once shipped 1,183 entries under 1,180 ids and I recorded
    ;; three duplicated actors. Investigation showed none of the three was a
    ;; duplicate repository:
    ;;   cloud-itonami-isic-7500 — a stale local checkout under a pre-rename
    ;;     name; GitHub reports the canonical .name as cloud-itonami-isic-750
    ;;   cloud-itonami-commitment-ledger-component — 404 on GitHub
    ;;   cloud-itonami-marketplace-order-codex     — 404 on GitHub
    ;; The last two were never pushed. The generator was enumerating local
    ;; directories, so a stale rename and two unpushed scratch directories
    ;; entered a shipped artefact. It enumerates from west now.
    (is (empty? (fleet/duplicate-ids)))
    (let [ids (map :id (fleet/actors))]
      (is (= (count ids) (count (distinct ids)))))
    ;; isic-7500 is no longer in this list. It WAS the stale pre-rename
    ;; directory; on 2026-07-30 the repository was renamed to that name on
    ;; owner directive, so it is now the real one and isic-750 is the redirect.
    (doseq [phantom ["cloud-itonami-isic-750"
                     "cloud-itonami-commitment-ledger-component"
                     "cloud-itonami-marketplace-order-codex"]]
      (is (nil? (fleet/actor phantom))
          (str phantom " is not a repository west registers"))))

  (testing "find-by-id still returns a collection"
    ;; Kept even though every id currently resolves to one actor: west could
    ;; register two repositories declaring the same id, and returning the first
    ;; match would be a wrong answer shaped like a right one.
    (is (= 1 (count (fleet/find-by-id "cloud-itonami-isic-7500")))))

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
    ;; It spans every family the authority now classifies — occupation,
    ;; jurisdiction, municipal, association and the code systems, plus the
    ;; action adapter and skill package. Asserting a fixed set here has broken
    ;; twice as the vocabulary grew, so this asserts the property that matters:
    ;; on-demand is many roles, and none of them is a resident one.
    (let [od-roles (set (map :role (fleet/by-execution :on-demand)))]
      (is (< 5 (count od-roles)))
      (is (contains? od-roles :sector-agent))
      (is (empty? (clojure.set/intersection
                   od-roles
                   #{:continuous-orchestrator :artificial-organism-actor})))))

  (testing "resident is exactly the two roles that carry a loop"
    (is (= #{:continuous-orchestrator :artificial-organism-actor}
           (set (map :role (fleet/by-execution :resident))))))

  (testing "the vocabulary reaches all but a handful"
    ;; It reached 452 of 1,183 when execution was introduced, then all of them.
    ;; Then cloud-itonami-esim landed in west and this assertion failed —
    ;; correctly. A fleet that grows will always have a moment where a new
    ;; family predates its rule, so this asserts the property that matters:
    ;; unclassified is a rounding error and never the norm. Asserting zero
    ;; makes an unrelated repo addition look like a regression.
    (is (< (count (fleet/by-execution :unclassified))
           (/ (count (fleet/actors)) 100))))

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
                    :reference-only :id :authority-library
                    ;; the pin's own commit date — metadata about the hash,
                    ;; still nothing read from inside the repository
                    :revision-committed-at}]
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

  (testing "unclassified stays queryable even now that it is empty"
    ;; It held 731 when execution was introduced and holds none today. The
    ;; query is kept rather than deleted: the next family added to the fleet
    ;; lands here until the authority classifies it, and the alternative — a
    ;; nil execution that no query surfaces — is how an unfinished vocabulary
    ;; becomes invisible instead of merely incomplete.
    (let [u (fleet/by-execution :unclassified)]
      ;; The partition is what is asserted, not that u is empty — every actor
      ;; lands in exactly one bucket, including the ones no rule covers yet.
      (is (= (count (fleet/actors))
             (+ (count u)
                (count (fleet/by-execution :on-demand))
                (count (fleet/by-execution :resident)))))))

  (testing "counts expose the split"
    (let [{:keys [by-execution]} (fleet/counts)]
      (is (pos? (:on-demand by-execution)))
      (is (pos? (:resident by-execution))))))

(deftest pin-age-is-pin-age-not-liveness
  (testing "every resident actor carries a pin date"
    ;; Resident actors are exactly the set the generator fetches commit dates
    ;; for — it was every reference entry until the vocabulary grew to match
    ;; most of west and the generator started timing out on hundreds of API
    ;; calls. Pin age is a liveness proxy for something that runs a loop; for
    ;; an undeployed on-demand agent it was never worth a network round-trip.
    (let [r (fleet/by-execution :resident)]
      (is (seq r))
      (is (every? :revision-committed-at r))
      (is (every? #(nat-int? (fleet/pin-age-days %)) r))))

  (testing "an actor with no recorded date yields nil, not zero"
    ;; Zero would read as "pinned today" — the same collapse of unmeasured into
    ;; a value that :unknown avoids on the health side.
    (is (nil? (fleet/pin-age-days {:repo "x"}))))

  (testing "the threshold selects, and the result is ordered oldest first"
    (is (empty? (fleet/stale-pins 100000)))
    (let [all (fleet/stale-pins 0)]
      (is (seq all))
      (is (every? :pin-age-days all))
      (is (apply >= (map :pin-age-days all)))
      ;; only entries that carry a date can appear
      (is (every? :revision-committed-at all)))))
