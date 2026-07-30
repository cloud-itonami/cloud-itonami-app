(ns cloud.itonami.app.operator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.fleet :as fleet]
            [cloud.itonami.app.operator :as operator]
            [cloud.itonami.app.store :as store]))

(defn- reset-operator! [_]
  (store/transact! assoc :operator nil :operator-adoptions {}))

(use-fixtures :each (fn [f] (reset-operator! nil) (f) (reset-operator! nil)))

(def ^:private jp-lawyer
  {:name "山田法律事務所" :did "did:key:z6MkTest"
   :isic ["6910"] :isco ["2611"] :iso3166 ["JPN"]
   :technologies [:identity :forms :audit-ledger]
   :licences [{:licence/kind :bengoshi :licence/authority "東京弁護士会"
               :licence/number "第12345号"
               :licence/attested-by "山田 太郎" :licence/attested-on "2026-07-30"}]})

;; ── profile ──────────────────────────────────────────────────────────

(deftest a-licence-is-recorded-as-self-attested-never-verified
  (let [p (operator/save-profile! jp-lawyer)]
    (is (= :self-attested (:licence/verification (first (:operator/licences p)))))
    (testing "the caveat exists and says the app is not a verifier"
      (is (str/includes? operator/attestation-caveat "検証していません")))
    (testing "and no code path can mark a licence verified"
      (let [forged (operator/save-profile!
                    (assoc jp-lawyer :licences
                           [(assoc (first (:licences jp-lawyer))
                                   :licence/verification :verified)]))]
        (is (= :self-attested (:licence/verification (first (:operator/licences forged))))
            "save-profile! overwrites the field rather than trusting its input")))))

(deftest profile-round-trips
  (is (nil? (operator/profile)))
  (operator/save-profile! jp-lawyer)
  (let [p (operator/profile)]
    (is (= "山田法律事務所" (:operator/name p)))
    (is (= ["6910"] (:operator/isic p)))
    (is (some? (:operator/id p))))
  (testing "saving again keeps the id"
    (let [id (:operator/id (operator/profile))]
      (operator/save-profile! (assoc jp-lawyer :name "山田・佐藤法律事務所"))
      (is (= id (:operator/id (operator/profile)))))))

;; ── ② 適合 ────────────────────────────────────────────────────────────

(deftest fit-explains-itself
  (let [op (operator/save-profile! jp-lawyer)]
    (testing "sector and occupation weigh more than jurisdiction"
      (is (= 3 (:score (operator/fit op {:isic "6910"}))))
      (is (= 3 (:score (operator/fit op {:isco-08 "2611"}))))
      (is (= 1 (:score (operator/fit op {:iso3166 "JPN"}))))
      (is (= 7 (:score (operator/fit op {:isic "6910" :isco-08 "2611" :iso3166 "JPN"})))))
    (testing "no overlap is zero, not a small number"
      (is (= 0 (:score (operator/fit op {:isic "0111" :iso3166 "USA"})))))
    (testing "every point carries the reason it was awarded"
      (let [f (operator/fit op {:isic "6910" :iso3166 "JPN"})]
        (is (= #{:isic :iso3166} (set (map :on (:reasons f)))))
        (is (= "6910" (:value (first (filter #(= :isic (:on %)) (:reasons f))))))))
    (testing "an actor coding in another ISIC revision still matches"
      (is (= 3 (:score (operator/fit op {:isic-rev5 "6910"}))))
      (is (= 3 (:score (operator/fit op {:isic-rev4 "6910"})))))))

(deftest matches-ranks-and-excludes
  (let [op (operator/save-profile! jp-lawyer)
        actors [{:repo "a" :isic "6910" :isco-08 "2611" :iso3166 "JPN"}
                {:repo "b" :isic "6910"}
                {:repo "c" :iso3166 "JPN"}
                {:repo "d" :isic "0111"}]
        m (operator/matches op actors)]
    (is (= ["a" "b" "c"] (mapv :repo m)) "best fit first; no overlap is dropped entirely")
    (is (= 7 (get-in (first m) [:fit :score])))))

;; ── ③ 要件 ────────────────────────────────────────────────────────────

(def ^:private ready-actor
  {:repo "x" :maturity :implemented :governor :some-governor
   :required-technologies [:identity :forms]
   :deploy-config [:cloudflare]})

(defn- state-of [rs k]
  (:state (first (filter #(= k (:requirement %)) rs))))

(deftest readiness-reports-each-requirement
  (let [op (operator/save-profile! jp-lawyer)
        {:keys [requirements ready?]} (operator/readiness op ready-actor)]
    (is (true? ready?))
    (is (= :met (state-of requirements :maturity)))
    (is (= :met (state-of requirements :governor)))
    (is (= :met (state-of requirements :technologies)))
    (is (= :met (state-of requirements :deploy-path)))
    (testing "a licence is :attested — never :met"
      (is (= :attested (state-of requirements :licence)))
      (is (not= :met (state-of requirements :licence)))
      (is (some? (:caveat (first (filter #(= :licence (:requirement %)) requirements))))))))

(deftest what-the-app-cannot-decide-does-not-block
  (let [op (operator/save-profile! jp-lawyer)]
    (testing "an absent deploy path is reported, not treated as failure"
      (let [r (operator/readiness op (dissoc ready-actor :deploy-config))]
        (is (= :absent (state-of (:requirements r) :deploy-path)))
        (is (true? (:ready? r)) "運用者が自分で deployment を作るのは正常な経路")
        (is (str/includes?
             (:detail (first (filter #(= :deploy-path (:requirement %)) (:requirements r))))
             "自分で構築"))))
    (testing "an undeclared maturity or governor is :unknown, not :unmet"
      (let [r (operator/readiness op (dissoc ready-actor :maturity :governor))]
        (is (= :unknown (state-of (:requirements r) :maturity)))
        (is (= :unknown (state-of (:requirements r) :governor)))
        (is (true? (:ready? r)))))
    (testing "a blueprint that declares no technologies is :none"
      (is (= :none (state-of (:requirements (operator/readiness
                                             op (dissoc ready-actor :required-technologies)))
                             :technologies))))))

(deftest what-the-app-can-decide-does-block
  (let [op (operator/save-profile! jp-lawyer)]
    (testing "maturity :blueprint — a design is not an implementation"
      (let [r (operator/readiness op (assoc ready-actor :maturity :blueprint))]
        (is (false? (:ready? r)))
        (is (= [:maturity] (mapv :requirement (:blocking r))))))
    (testing "missing required technology"
      (let [r (operator/readiness op (assoc ready-actor :required-technologies
                                            [:identity :forms :bpmn :dmn]))]
        (is (false? (:ready? r)))
        (is (= [:technologies] (mapv :requirement (:blocking r))))
        (is (= [:bpmn :dmn] (:items (first (:blocking r)))))))
    (testing "no licence attested at all"
      (operator/save-profile! (assoc jp-lawyer :licences []))
      (let [r (operator/readiness (operator/profile) ready-actor)]
        (is (false? (:ready? r)))
        (is (= [:licence] (mapv :requirement (:blocking r))))))))

;; ── ④ 表明 ────────────────────────────────────────────────────────────

(def ^:private real-repo
  "A blueprint that actually exists in the shipped catalog."
  (:repo (first (fleet/actors))))

(deftest declaration-must-be-signed
  (operator/save-profile! jp-lawyer)
  (is (thrown? clojure.lang.ExceptionInfo (operator/declare! real-repo {:by nil})))
  (is (thrown? clojure.lang.ExceptionInfo (operator/declare! real-repo {:by ""})))
  (testing "and must name a blueprint the catalog knows"
    (is (thrown? clojure.lang.ExceptionInfo
                 (operator/declare! "no-such-blueprint" {:by "山田 太郎"})))))

(deftest declaring-records-the-requirements-as-they-stood
  (operator/save-profile! jp-lawyer)
  (let [a (operator/declare! real-repo {:by "山田 太郎" :note "評価中"})]
    (is (= real-repo (:adoption/repo a)))
    (is (= "山田 太郎" (:adoption/declared-by a)))
    (is (some? (:adoption/declared-on a)))
    (is (contains? (set operator/stages) (:adoption/stage a)))
    (testing "the readiness snapshot travels with the declaration"
      (is (seq (:adoption/readiness-at-declaration a)))
      (is (every? :requirement (:adoption/readiness-at-declaration a))))
    (is (= 1 (count (operator/adoptions))))))

(deftest withdrawing-keeps-the-record
  (operator/save-profile! jp-lawyer)
  (operator/declare! real-repo {:by "山田 太郎"})
  (let [w (operator/withdraw! real-repo {:by "山田 太郎"})]
    (is (= :withdrawn (:adoption/stage w)))
    (is (some? (:adoption/withdrawn-on w)))
    (is (= 1 (count (operator/adoptions)))
        "取り下げた事実も記録である — 一度も始めなかったのとは違う")))

;; ── ⑤ 稼働 ────────────────────────────────────────────────────────────

(defn- up [_ _] :up)
(defn- unknown [_ _] :unknown)
(defn- down [_ _] :down)

(deftest endpoint-registration-is-gated
  (operator/save-profile! jp-lawyer)
  (testing "参与の表明が先"
    (is (= :not-declared (:reason (operator/register-endpoint!
                                   real-repo {:endpoint "https://x.example" :by "山田"
                                              :probe-fn up})))))
  (operator/declare! real-repo {:by "山田 太郎"})
  (testing "登録者は必須"
    (is (= :anonymous (:reason (operator/register-endpoint!
                                real-repo {:endpoint "https://x.example" :by ""
                                           :probe-fn up})))))
  (testing "https のみ"
    (doseq [bad ["http://x.example" "x.example" "" nil "ftp://x.example"]]
      (is (= :bad-endpoint (:reason (operator/register-endpoint!
                                     real-repo {:endpoint bad :by "山田" :probe-fn up})))
          (pr-str bad))))
  (testing "未登録の blueprint"
    (is (= :unknown-blueprint (:reason (operator/register-endpoint!
                                        "nope" {:endpoint "https://x.example" :by "山田"
                                                :probe-fn up}))))))

(deftest an-endpoint-that-does-not-answer-is-not-registered
  (operator/save-profile! jp-lawyer)
  (operator/declare! real-repo {:by "山田 太郎"})
  (testing ":unknown — 応答が無いことは稼働の証明にならない"
    (let [r (operator/register-endpoint! real-repo {:endpoint "https://x.example"
                                                    :by "山田" :probe-fn unknown})]
      (is (false? (:ok? r)))
      (is (= :endpoint-not-answering (:reason r)))
      (is (= :unknown (:health r)))
      (is (not= :deployed (:adoption/stage (operator/adoption real-repo))))))
  (testing ":down も同様"
    (is (false? (:ok? (operator/register-endpoint! real-repo {:endpoint "https://x.example"
                                                              :by "山田" :probe-fn down})))))
  (testing "実際に応答したときだけ稼働になる"
    (let [r (operator/register-endpoint! real-repo {:endpoint "https://x.example"
                                                    :by "山田" :probe-fn up})]
      (is (true? (:ok? r)))
      (is (= :deployed (:adoption/stage (operator/adoption real-repo))))
      (is (= "https://x.example" (:adoption/endpoint (operator/adoption real-repo))))
      (is (= "山田" (:adoption/registered-by (operator/adoption real-repo))))
      (is (= 1 (count (operator/deployed)))))))

(deftest unmet-requirements-stop-registration-even-with-a-live-endpoint
  (operator/save-profile! (assoc jp-lawyer :licences []))
  (let [repo real-repo]
    (operator/declare! repo {:by "山田 太郎"})
    (let [r (operator/register-endpoint! repo {:endpoint "https://x.example"
                                               :by "山田" :probe-fn up})]
      (is (false? (:ok? r)))
      (is (= :requirements-unmet (:reason r)))
      (is (seq (:blocking r))))))

(deftest summary-reports-the-operator-and-the-fleet
  (let [s0 (operator/summary)]
    (is (false? (:profile? s0)))
    (is (zero? (:adoptions s0)))
    (is (pos? (:actors (:fleet s0))) "fleet の規模は profile が無くても分かる"))
  (operator/save-profile! jp-lawyer)
  (operator/declare! real-repo {:by "山田 太郎"})
  (let [s (operator/summary)]
    (is (true? (:profile? s)))
    (is (= 1 (:adoptions s)))
    (is (zero? (:deployed s)))))

;; ── fleet/probe-endpoint ──────────────────────────────────────────────

(deftest probe-endpoint-refuses-nonsense-without-a-network-call
  (is (= :unknown (fleet/probe-endpoint nil "/health")))
  (is (= :unknown (fleet/probe-endpoint "https://x.example" nil)))
  (is (= :unknown (fleet/probe-endpoint 42 "/health"))))
