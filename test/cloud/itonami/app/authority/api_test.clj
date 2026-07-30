(ns cloud.itonami.app.authority.api-test
  "The request layer: that a disabled authority has no surface, that an unknown
  authority cannot be reached by a typo, that the transport records why a hand-off
  could not happen, and -- the one that matters most -- that a CLIENT CANNOT
  SUPPLY ITS OWN POSTURE."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.authority :as authority]
            [cloud.itonami.app.authority.api :as api]
            [cloud.itonami.app.authority.esim :as esim-adapter]
            [cloud.itonami.app.authority.posture :as posture]
            [cloud.itonami.app.authority.transport :as transport]
            [cloud.itonami.app.store :as store])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def session {:user-id "user-1" :organization-id "org-1"})

(def eid "89049032000000000000000000000001")
(def iccid-a "8981012345678901230")
(def iccid-b "8981012345678909993")
(def card-ref "4111111111111111")

(def all-off
  "What defaults.edn ships: every authority off, no endpoints."
  {:authorities {:esim {:enabled? false :endpoint nil}
                 :card {:enabled? false :endpoint nil}
                 :payment {:enabled? false :endpoint nil}
                 :voice {:enabled? false :endpoint nil}}})

(defn- on
  "Config with one or more authorities enabled, still without endpoints unless
  given."
  [& ks]
  (reduce (fn [c k] (assoc-in c [:authorities k :enabled?] true)) all-off ks))

(defn- reset-proposals! []
  (store/transact! assoc :authority {:proposals {}}))

(defn- refuses [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(def ^:private lifecycle-request
  {:op :profile/lifecycle :eid eid :iccid iccid-a :operation :disable
   :profiles [{:esim/iccid iccid-a :esim/state :enabled}]})

;; ---------------------------------------------------------------------------
;; a disabled authority has no surface at all
;; ---------------------------------------------------------------------------

(deftest every-stage-refuses-while-the-authority-is-disabled
  (reset-proposals!)
  (is (= :authority/disabled (refuses #(api/review! all-off session :esim lifecycle-request))))
  (is (= :authority/disabled (refuses #(api/start-approval! all-off session :esim "p" "localhost" "http://localhost:1338"))))
  (is (= :authority/disabled (refuses #(api/finish-approval! all-off session :esim "p" "t" {}))))
  (is (= :authority/disabled (refuses #(api/reject! all-off session :esim "p"))))
  (is (= :authority/disabled (refuses #(api/commit! all-off session :esim "p"))))
  (testing "including the read -- answering as though a disabled authority might
            have proposals is worse than refusing"
    (is (= :authority/disabled (refuses #(api/proposals all-off session :esim)))))
  (is (empty? (authority/proposals session))))

(deftest the-shipped-default-is-off-for-every-authority
  (doseq [k (keys api/adapters)]
    (is (not (transport/enabled? all-off k)) (str k " must ship disabled"))
    (is (not (transport/enabled? {} k))
        (str k ": absent config must read as off, not as on"))))

(deftest an-unknown-authority-is-refused-rather-than-defaulted
  (is (= :authority/disabled (refuses #(api/review! all-off session :esmi lifecycle-request)))
      "a typo hits the enabled check first, which is also a refusal")
  (testing "and even when enabled, an unknown key does not resolve to an adapter"
    (let [cfg (assoc-in all-off [:authorities :esmi :enabled?] true)]
      (is (= :authority/unknown-authority
             (refuses #(api/review! cfg session :esmi lifecycle-request)))))))

;; ---------------------------------------------------------------------------
;; the posture is the server's, not the client's
;; ---------------------------------------------------------------------------

(deftest a-client-cannot-supply-its-own-posture
  (reset-proposals!)
  (let [cfg (on :esim :card)]
    ;; The subject has an eSIM ownership transfer on record, so the real posture
    ;; is :restricted.
    (esim-adapter/review! (fn [_ _ p] {:authority/ok? true :authority/record {:id (:id p)}})
                          cfg session
                          {:op :ownership/transfer :eid eid :iccid iccid-a
                           :from-subject "did:key:zVictim"
                           :to-subject "did:key:zAttacker"})
    (is (= :restricted (:authority/posture (posture/subject-posture session cfg))))

    (testing "a request claiming :normal is OVERWRITTEN, not merged -- otherwise
              the whole invariant is advisory and an attacker just sends :normal"
      (is (= :card/sim-swap-hold
             (refuses #(api/review! cfg session :card
                                    {:op :authorization/decide
                                     :card-reference card-ref
                                     :amount 100 :daily-limit 10000 :spent-today 0
                                     :posture {:authority/posture :normal}})))))

    (testing "and so is a claimed posture on :card/issue"
      (is (= :card/sim-swap-hold
             (refuses #(api/review! cfg session :card
                                    {:op :card/issue :cardholder-id "ch1"
                                     :posture {:authority/posture :normal}})))))

    (testing "an op that is not posture-restricted is untouched by this"
      (is (nil? (refuses #(api/review! cfg session :card
                                       {:op :card/lifecycle
                                        :card-reference card-ref
                                        :event :block :state :active})))))))

(deftest with-no-transfer-the-server-supplies-a-normal-posture-so-the-caller-need-not
  (reset-proposals!)
  (let [cfg (on :card)
        p (api/review! cfg session :card
                       {:op :authorization/decide :card-reference card-ref
                        :amount 100 :daily-limit 10000 :spent-today 0})]
    (is (= :awaiting-passkey (:status p)))
    (is (= :normal (get-in p [:value :posture]))
        "the caller sent no posture at all and the server filled it in")))

;; ---------------------------------------------------------------------------
;; the transport records WHY a hand-off could not happen
;; ---------------------------------------------------------------------------

(deftest the-transport-refuses-a-disabled-authority
  (let [f (transport/commit-fn :esim)
        out (f all-off session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :authority-disabled (get-in out [:authority/refusal :rule])))))

(deftest an-enabled-authority-with-no-endpoint-still-cannot-commit
  (let [f (transport/commit-fn :esim)
        out (f (on :esim) session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :endpoint-not-configured (get-in out [:authority/refusal :rule]))
        "enabled is not the same as reachable")))

(deftest an-empty-endpoint-string-counts-as-unconfigured
  (let [cfg (-> (on :esim) (assoc-in [:authorities :esim :endpoint] ""))
        out ((transport/commit-fn :esim) cfg session {:id "p1"})]
    (is (= :endpoint-not-configured (get-in out [:authority/refusal :rule])))))

(deftest an-unreachable-endpoint-refuses-rather-than-throwing
  (let [cfg (-> (on :esim)
                ;; Port 1 on loopback: refused immediately, no network wait.
                (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1/commit"))
        out ((transport/commit-fn :esim) cfg session {:id "p1"})]
    (is (false? (:authority/ok? out)))
    (is (= :transport-failed (get-in out [:authority/refusal :rule]))
        "a transport problem is an outcome to record, not an exception to leak
         into a route")))

(deftest a-failed-handoff-lands-as-authority-refused-not-as-an-error
  (reset-proposals!)
  (let [cfg (on :esim)
        p (api/review! cfg session :esim lifecycle-request)]
    ;; Stand in for the Passkey stage; the consent path is covered elsewhere.
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
    (let [out (api/commit! cfg session :esim (:id p))]
      (is (= :authority-refused (:status out)))
      (is (= :endpoint-not-configured (get-in out [:authority-refusal :rule])))
      (is (nil? (:authority-record out)))
      (is (authority/terminal? out)
          "an unreachable authority is terminal for this proposal -- the consent
           was for this content and does not carry over to a retry"))))

;; ---------------------------------------------------------------------------
;; reads
;; ---------------------------------------------------------------------------

(deftest the-overview-shows-a-disabled-authority-instead-of-refusing
  (reset-proposals!)
  (let [o (api/overview all-off session)]
    (is (= #{:esim :card :payment :voice} (set (keys (:authorities o)))))
    (doseq [[k v] (:authorities o)]
      (is (false? (:enabled? v)) (str k))
      (is (false? (:endpoint-configured? v)) (str k))
      (is (not (contains? v :proposals))
          (str k ": a disabled authority has no proposals to list")))
    (testing "and the posture travels with the read so a UI cannot derive it
              differently"
      (is (= :normal (:authority/posture (:posture o)))))))

(deftest the-overview-distinguishes-enabled-from-reachable
  (let [cfg (-> (on :esim)
                (assoc-in [:authorities :esim :endpoint] "https://esim.example/commit"))
        o (api/overview cfg session)]
    (is (true? (get-in o [:authorities :esim :enabled?])))
    (is (true? (get-in o [:authorities :esim :endpoint-configured?])))
    (testing "an enabled authority with no endpoint is reported as such, because a
              settings screen showing only 'enabled' would mislead"
      (let [o2 (api/overview (on :card) session)]
        (is (true? (get-in o2 [:authorities :card :enabled?])))
        (is (false? (get-in o2 [:authorities :card :endpoint-configured?])))))))

(deftest the-per-authority-read-is-scoped-and-carries-the-posture
  (reset-proposals!)
  (let [cfg (on :esim)]
    (api/review! cfg session :esim lifecycle-request)
    (let [r (api/proposals cfg session :esim)]
      (is (= :esim (:authority r)))
      (is (true? (:enabled? r)))
      (is (= 1 (count (:proposals r))))
      (is (contains? (:posture r) :authority/posture)))
    (testing "and it does not leak another authority's proposals"
      (let [cfg2 (on :esim :voice)]
        (is (zero? (count (:proposals (api/proposals cfg2 session :voice)))))))))

(deftest a-second-eSIM-op-is-still-refused-on-its-own-merits
  (testing "enabling an authority does not weaken its adapter's pre-check"
    (reset-proposals!)
    (let [cfg (on :esim)]
      (is (= :esim/transition-unreachable
             (refuses #(api/review! cfg session :esim
                                    (assoc lifecycle-request
                                           :iccid iccid-b
                                           :operation :enable
                                           :profiles [{:esim/iccid iccid-a :esim/state :enabled}
                                                      {:esim/iccid iccid-b :esim/state :disabled}])))))
      (is (empty? (authority/proposals session))))))

;; ---------------------------------------------------------------------------
;; the third outcome: accepted, awaiting the authority's own operator
;; ---------------------------------------------------------------------------

(defn- pending-domain
  "A domain whose commit! answers the way an actor's POST /commit does when it
  accepted the proposal and is holding it for its operator."
  []
  {:authority/key :fixture
   :authority/status (fn [_config _ref] {:authority/pending? true})
   :authority/context-type (fn [_] :fixture/op)
   :authority/pre-check (fn [_ _ _] {:x 1})
   :authority/material (fn [v] (str "fixture/" (:x v)))
   :authority/commit! (fn [_ _ _]
                        {:authority/ok? false
                         :authority/pending? true
                         :authority/reference "commit-p-1-abc"})})

(deftest a-pending-outcome-is-neither-committed-nor-refused
  (reset-proposals!)
  (let [d (pending-domain)
        p (authority/review! d {} session {:op :x})]
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
    (let [out (authority/commit! d {} session (:id p))]
      (is (= :authority-pending (:status out))
          "the subject consented and the operator has not decided -- two states
           could not say that")
      (is (= "commit-p-1-abc" (:authority-reference out))
          "the reference is what an operator resumes")
      (is (nil? (:authority-record out)) "nothing was committed")
      (is (nil? (:authority-refusal out)) "and nothing was refused")
      (testing "it is NOT terminal, because the operator has still to decide"
        (is (not (authority/terminal? out)))
        (is (authority/pending-with-authority? out))))))

(deftest a-pending-proposal-cannot-be-re-committed
  (reset-proposals!)
  (let [d (pending-domain)
        p (authority/review! d {} session {:op :x})]
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
    (authority/commit! d {} session (:id p))
    (is (= :authority/proposal-not-found
           (refuses #(authority/commit! d {} session (:id p))))
        "commit requires :approved, so a pending proposal cannot silently
         accumulate attempts")))

(deftest pending-is-checked-before-ok-so-it-is-not-filed-as-refused
  (testing "a pending outcome carries ok? false and no refusal; testing ok? first
            would file it as :authority-refused"
    (reset-proposals!)
    (let [d (pending-domain)
          p (authority/review! d {} session {:op :x})]
      (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
      (is (= :authority-pending
             (:status (authority/commit! d {} session (:id p))))))))


;; ---------------------------------------------------------------------------
;; the transport reads the three-state wire contract, over a real socket
;; ---------------------------------------------------------------------------

(defn- with-stub-actor
  "Run f with a tiny HTTP server that answers `payload` for POST /commit, so the
  transport's real code path -- socket, status code, JSON decode, status mapping --
  is exercised rather than a stubbed function."
  [payload f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ ex]
         (let [bytes (.getBytes (json/write-str payload) StandardCharsets/UTF_8)]
           (.set (.getResponseHeaders ex) "Content-Type" "application/json")
           (.sendResponseHeaders ex 200 (alength bytes))
           (with-open [out (.getResponseBody ex)] (.write out bytes))
           (.close ex)))))
    (.setExecutor server nil)
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/commit"))
         (finally (.stop server 0)))))

(defn- transport-outcome [payload]
  (with-stub-actor
    payload
    (fn [endpoint]
      (let [cfg (-> (on :esim) (assoc-in [:authorities :esim :endpoint] endpoint))]
        ((transport/commit-fn :esim) cfg session {:id "p1"})))))

(deftest the-transport-maps-committed
  (let [out (transport-outcome {:status "committed" :record {:x 1}})]
    (is (true? (:authority/ok? out)))
    (is (= {:x 1} (:authority/record out)))
    (is (not (:authority/pending? out)))))

(deftest the-transport-maps-held-to-a-refusal
  (let [out (transport-outcome {:status "held" :refusal {:rule "iccid-invalid"}})]
    (is (false? (:authority/ok? out)))
    (is (= {:rule "iccid-invalid"} (:authority/refusal out)))
    (is (not (:authority/pending? out)))))

(deftest the-transport-maps-pending-and-does-not-call-it-refused
  (let [out (transport-outcome {:status "pending" :reference "commit-p1-abc"})]
    (is (false? (:authority/ok? out)))
    (is (true? (:authority/pending? out)))
    (is (= "commit-p1-abc" (:authority/reference out)))
    (is (nil? (:authority/refusal out))
        "pending must carry no refusal, or the spine would file it as refused")))

(deftest a-held-answer-with-no-refusal-body-still-names-a-rule
  (let [out (transport-outcome {:status "held"})]
    (is (= :governor-refused (get-in out [:authority/refusal :rule])))))

(deftest an-unrecognised-status-is-a-transport-failure-not-a-guess
  (doseq [payload [{:status "maybe"} {:status nil} {:ok true} {}]]
    (let [out (transport-outcome payload)]
      (is (false? (:authority/ok? out)) (str (pr-str payload)))
      (is (= :transport-failed (get-in out [:authority/refusal :rule]))
          (str "payload " (pr-str payload) " must not be interpreted"))))
  (testing "the old boolean contract is no longer accepted -- it could not express
            pending, and silently honouring it would hide an un-migrated actor"
    (let [out (transport-outcome {:ok false :refusal {:rule "x"}})]
      (is (= :transport-failed (get-in out [:authority/refusal :rule]))))))

(deftest a-pending-wire-answer-becomes-a-pending-proposal
  (reset-proposals!)
  (with-stub-actor
    {:status "pending" :reference "commit-p1-abc"}
    (fn [endpoint]
      (let [cfg (-> (on :esim) (assoc-in [:authorities :esim :endpoint] endpoint))
            p (api/review! cfg session :esim lifecycle-request)]
        (store/transact! assoc-in [:authority :proposals (:id p) :status] :approved)
        (let [out (api/commit! cfg session :esim (:id p))]
          (is (= :authority-pending (:status out)))
          (is (= "commit-p1-abc" (:authority-reference out)))
          (is (not (authority/terminal? out))))))))

;; ---------------------------------------------------------------------------
;; refreshing a pending proposal
;; ---------------------------------------------------------------------------

(defn- pending-proposal!
  "A proposal parked in :authority-pending with a reference, the state a refresh
  acts on."
  [reference]
  (let [d (pending-domain)
        p (authority/review! d {} session {:op :x})]
    (store/transact! update-in [:authority :proposals (:id p)]
                     merge {:status :authority-pending
                            :authority-reference reference})
    (:id p)))

(defn- domain-answering [outcome]
  (assoc (pending-domain) :authority/status (fn [_config _ref] outcome)))

(deftest a-still-pending-authority-leaves-the-proposal-alone
  (reset-proposals!)
  (let [id (pending-proposal! "commit-p1-abc")
        d (domain-answering {:authority/pending? true})
        out (authority/refresh! d {} session id)]
    (is (= :authority-pending (:status out)))
    (is (nil? (:resolved-at out)) "nothing was resolved, so nothing is dated")))

(deftest an-operator-approval-resolves-the-proposal
  (reset-proposals!)
  (let [id (pending-proposal! "commit-p1-abc")
        d (domain-answering {:authority/ok? true :authority/record {:did :it}})
        out (authority/refresh! d {} session id)]
    (is (= :committed (:status out)))
    (is (= {:did :it} (:authority-record out)))
    (is (string? (:resolved-at out)))
    (is (authority/terminal? out))))

(deftest an-operator-rejection-resolves-it-as-refused
  (reset-proposals!)
  (let [id (pending-proposal! "commit-p1-abc")
        d (domain-answering {:authority/ok? false
                             :authority/refusal {:rule :approver-rejected}})
        out (authority/refresh! d {} session id)]
    (is (= :authority-refused (:status out)))
    (is (= {:rule :approver-rejected} (:authority-refusal out)))
    (is (authority/terminal? out))))

(deftest an-unknown-reference-stays-pending-and-is-marked
  (testing "the actor forgot the reference -- nobody refused it, so recording a
            governor refusal would put a decision on the ledger no one made"
    (reset-proposals!)
    (let [id (pending-proposal! "commit-p1-abc")
          d (domain-answering {:authority/ok? false
                               :authority/unknown? true
                               :authority/refusal {:rule :reference-unknown}})
          out (authority/refresh! d {} session id)]
      (is (= :authority-pending (:status out)) "it must NOT become refused")
      (is (string? (:authority-unknown-since out))
          "but that we asked and it did not know is itself recorded")
      (is (not (authority/terminal? out))))))

(deftest only-a-pending-proposal-can-be-refreshed
  (reset-proposals!)
  (let [d (pending-domain)
        p (authority/review! d {} session {:op :x})]
    (is (= :authority/proposal-not-found
           (refuses #(authority/refresh! d {} session (:id p))))
        "an awaiting-passkey proposal has no reference to ask about")
    (store/transact! assoc-in [:authority :proposals (:id p) :status] :committed)
    (is (= :authority/proposal-not-found
           (refuses #(authority/refresh! d {} session (:id p))))
        "and a resolved one is done")))

(deftest refresh-refuses-a-disabled-authority
  (reset-proposals!)
  (let [id (pending-proposal! "commit-p1-abc")]
    (is (= :authority/disabled (refuses #(api/refresh! all-off session :esim id))))))

(deftest the-status-read-goes-to-the-consent-surface-not-the-operator-one
  (testing "the transport derives /proposals/<ref> from the configured base, and
            the operator listener's address is deliberately not configurable here"
    (let [answered (atom nil)
          server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
      (.createContext
       server "/"
       (reify HttpHandler
         (handle [_ ex]
           (reset! answered {:method (.getRequestMethod ex)
                             :path (.getPath (.getRequestURI ex))})
           (let [b (.getBytes (json/write-str {:status "pending" :reference "r1"})
                              StandardCharsets/UTF_8)]
             (.set (.getResponseHeaders ex) "Content-Type" "application/json")
             (.sendResponseHeaders ex 200 (alength b))
             (with-open [o (.getResponseBody ex)] (.write o b))
             (.close ex)))))
      (.setExecutor server nil)
      (.start server)
      (try
        (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))
              cfg (-> (on :esim) (assoc-in [:authorities :esim :endpoint] base))
              out ((transport/status-fn :esim) cfg "r1")]
          (is (true? (:authority/pending? out)))
          (is (= "GET" (:method @answered)) "a status read must not mutate")
          (is (= "/proposals/r1" (:path @answered))))
        (finally (.stop server 0))))))

(deftest an-unreachable-authority-cannot-resolve-a-pending-proposal
  (let [cfg (-> (on :esim)
                (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1"))
        out ((transport/status-fn :esim) cfg "r1")]
    (is (= :transport-failed (get-in out [:authority/refusal :rule])))
    (is (not (:authority/unknown? out))
        "unreachable is not the same as the authority not knowing"))
  (testing "and a pending proposal with no reference is refused rather than asked about"
    (let [cfg (-> (on :esim)
                  (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1"))]
      (is (= :reference-missing
             (get-in ((transport/status-fn :esim) cfg nil) [:authority/refusal :rule]))))))

;; ---------------------------------------------------------------------------
;; resolving what is pending
;; ---------------------------------------------------------------------------

(deftest resolve-pending-asks-nothing-when-nothing-is-pending
  (reset-proposals!)
  (let [out (api/resolve-pending! (on :esim) session)]
    (is (= 0 (:asked out)))
    (is (= 0 (:skipped-disabled out)))
    (is (= [] (:results out)))))

(deftest resolve-pending-skips-authorities-the-deployment-switched-off
  (testing "counted separately so a caller can tell 'nothing was pending' from
            'everything pending belongs to an authority that is off' -- refusals for
            disabled authorities would bury the answers that matter"
    (reset-proposals!)
    (let [cfg (on :esim)
          p (api/review! cfg session :esim lifecycle-request)]
      (store/transact! assoc-in [:authority :proposals (:id p) :status]
                       :authority-pending)
      (let [out (api/resolve-pending! all-off session)]
        (is (= 0 (:asked out)))
        (is (= 1 (:skipped-disabled out)))))))

(deftest resolve-pending-reports-what-moved
  (testing "an unreachable authority leaves the proposal pending and says so, rather
            than reporting a resolution that did not happen"
    (reset-proposals!)
    (let [cfg (-> (on :esim)
                  ;; port 1 on loopback: refused immediately, no network wait
                  (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1"))
          p (api/review! cfg session :esim lifecycle-request)]
      (store/transact! assoc-in [:authority :proposals (:id p) :status]
                       :authority-pending)
      (store/transact! assoc-in [:authority :proposals (:id p) :authority-reference]
                       "ref-1")
      (let [out (api/resolve-pending! cfg session)
            r (first (:results out))]
        (is (= 1 (:asked out)))
        (is (= (:id p) (:id r)))
        (is (= :authority-pending (:was r)))
        (is (or (false? (:moved? r)) (some? (:error r)))
            "an unreachable actor does not move the proposal")))))

(deftest one-unreachable-authority-does-not-stop-the-others
  (testing "the sweep must not abort on the first failure, or a single misconfigured
            actor would keep every other proposal pending forever"
    (reset-proposals!)
    (let [cfg (-> (on :esim)
                  (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1"))]
      (doseq [_ (range 3)]
        (let [p (api/review! cfg session :esim lifecycle-request)]
          (store/transact! assoc-in [:authority :proposals (:id p) :status]
                           :authority-pending)))
      (let [out (api/resolve-pending! cfg session)]
        (is (= 3 (:asked out)))
        (is (= 3 (count (:results out))) "every one was attempted")))))

(deftest an-unreachable-authority-is-not-recorded-as-a-refusal
  (testing "no governor issued that refusal. transport.clj says it in as many words --
            'an authority that cannot be reached is not the same as one that said no,
            and the ledger should be able to tell them apart' -- and refresh! was
            recording :authority-refused for a connection error, which is a refusal on
            the ledger that nobody made."
    (reset-proposals!)
    (let [cfg (-> (on :esim)
                  (assoc-in [:authorities :esim :endpoint] "http://127.0.0.1:1"))
          p (api/review! cfg session :esim lifecycle-request)]
      (store/transact! assoc-in [:authority :proposals (:id p) :status]
                       :authority-pending)
      (store/transact! assoc-in [:authority :proposals (:id p) :authority-reference]
                       "ref-1")
      (let [out (api/refresh! cfg session :esim (:id p))]
        (is (= :authority-pending (:status out))
            "still pending -- nobody decided anything")
        (is (nil? (:authority-refusal out)))
        (is (nil? (:resolved-at out)))
        (is (some? (:authority-unreachable-since out))
            "and it records that we tried and could not ask")))))

(deftest a-governor-refusal-is-still-a-refusal
  (testing "the fix must not swallow real refusals: a rule that came FROM the actor
            resolves the proposal, as before"
    (reset-proposals!)
    (let [cfg (on :esim)
          p (api/review! cfg session :esim lifecycle-request)]
      (store/transact! assoc-in [:authority :proposals (:id p) :status]
                       :authority-pending)
      (store/transact! assoc-in [:authority :proposals (:id p) :authority-reference]
                       "ref-1")
      (with-redefs [cloud.itonami.app.authority.transport/status-fn
                    (fn [_] (fn [_ _] {:authority/ok? false
                                       :authority/refusal {:rule :kyc-incomplete}}))]
        (let [out (api/refresh! cfg session :esim (:id p))]
          (is (= :authority-refused (:status out)))
          (is (= :kyc-incomplete (get-in out [:authority-refusal :rule])))
          (is (some? (:resolved-at out))))))))
