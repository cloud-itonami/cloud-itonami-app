(ns cloud.itonami.app.session-handoff-kotoba-parity-test
  "Claiming a session established in another agent, in .kotoba and through the
  host.

  ## What is actually at risk

  This is a session-MINTING gate reached without a session — by construction,
  since the caller's whole problem is that it has no cookie. A claim token is
  therefore the entire authority, and four properties are the defence:

  - a claim that is not ready mints nothing. Without it, starting a sign-in
    would be indistinguishable from finishing one, and the claim token issued
    at `start` would already be a session.
  - a claim is spent once. Two windows presenting the same token must not both
    receive a session; the second presentation is somebody holding a copy.
  - an expired claim mints nothing, so a token that outlived the window it was
    for is inert rather than merely unlikely to be used.
  - an untrusted origin mints nothing, asserted EXHAUSTIVELY over the other
    three facts, because a refusal that any combination can trade against is
    not a refusal. This is the same construction — and the same test — as
    `handoff_core/may-approve?`'s agent refusal.

  Each is asserted separately from agreement. Agreement holds when both sides
  are wrong together, which is exactly the failure a decision core moved out
  of the host is supposed to make impossible to hide.

  ## What is NOT tested here, because it cannot be

  That a claim cannot choose which User it signs in as, or upgrade how
  strongly they authenticated. There is no test because there is no field:
  `:session-handoff/claim` carries no user, no provider and no authentication
  level. Those were the callback's judgement, already made. The test that
  would fail if somebody added one is the record-shape mismatch the compiler
  raises.

  That the refusals are indistinguishable ON THE WIRE is also not here — it is
  a property of the host boundary, asserted in `identity_lifecycle_test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.session-handoff :as session-handoff]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def ^:private core-source
  (slurp "src/cloud/itonami/app/session_handoff_core.kotoba"))

(def ^:private export-prefix "claimable? main")

(def ^:private claim-ty
  (str "[:record :session-handoff/claim [[:origin-trusted :bool] "
       "[:ready :bool] [:claimed :bool] [:expired :bool]]]"))

(defn- run-probes [probes result-type]
  (let [defs (for [[name body] probes]
               (str "(defn " name " [] " result-type " " body ")"))
        src (-> core-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " "
                      (str/join " " (map first probes)) "])"))
                (str "\n" (str/join "\n" defs)))
        {:keys [kir]} (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (into {} (map (fn [[n _]] [n (ir/execute kir (symbol n) [])]) probes))))

;; ── the corpus ───────────────────────────────────────────────────────

(def ^:private rows
  "Every combination of the four facts. Sixteen rows is the whole input space,
  so there is no sampling question to argue about."
  (for [origin-trusted [true false]
        ready [true false]
        claimed [true false]
        expired [true false]]
    {:origin-trusted origin-trusted :ready ready
     :claimed claimed :expired expired}))

(defn- claim-literal [{:keys [origin-trusted ready claimed expired]}]
  (str "(record-new " claim-ty " " origin-trusted " " ready " "
       claimed " " expired ")"))

;; ── the host side, driven through its public door ────────────────────

(defn- host-record [{:keys [ready claimed]}]
  {:session-handoff/ready? ready :session-handoff/claimed? claimed})

(defn- host-claimable? [{:keys [origin-trusted expired] :as row}]
  (session-handoff/claimable? (host-record row)
                              {:origin-trusted? origin-trusted
                               :expired? expired}))

;; ── agreement ────────────────────────────────────────────────────────

(deftest kotoba-and-host-agree-on-claimability
  (testing "claimable?"
    (let [probes (map-indexed
                  (fn [i row] [(str "cl_" i)
                               (str "(claimable? " (claim-literal row) ")")])
                  rows)
          guest (run-probes probes ":bool")]
      (doseq [[i row] (map-indexed vector rows)]
        (is (= (get guest (str "cl_" i)) (host-claimable? row))
            (str "claimable? disagreed on " (pr-str row)))))))

;; ── the properties, which agreement alone would not catch ────────────

(deftest an-untrusted-origin-can-never-claim
  ;; Exhaustive over the other three facts, for the reason the core tests
  ;; origin first and alone: a page on the open internet POSTing guesses at a
  ;; loopback port must not reach a mintable state by any route.
  (let [untrusted (filter #(not (:origin-trusted %)) rows)
        probes (map-indexed
                (fn [i row] [(str "uo_" i)
                             (str "(claimable? " (claim-literal row) ")")])
                untrusted)
        guest (run-probes probes ":bool")]
    (is (= 8 (count untrusted)) "the exhaustive case set changed shape")
    (doseq [[i row] (map-indexed vector untrusted)]
      (is (false? (get guest (str "uo_" i)))
          (str "the core admitted an untrusted origin: " (pr-str row)))
      (is (false? (host-claimable? row))
          (str "the host admitted an untrusted origin: " (pr-str row))))))

(deftest a-claim-that-is-not-ready-mints-nothing
  ;; Without this, the token handed out at `start` would already be a session,
  ;; and beginning a sign-in would be the same as completing one.
  (doseq [row rows :when (not (:ready row))]
    (is (false? (host-claimable? row))
        (str "admitted a claim nobody has authenticated: " (pr-str row)))))

(deftest a-claim-is-spent-once
  (doseq [row rows :when (:claimed row)]
    (is (false? (host-claimable? row))
        (str "admitted a replayed claim: " (pr-str row)))))

(deftest an-expired-claim-mints-nothing
  (doseq [row rows :when (:expired row)]
    (is (false? (host-claimable? row))
        (str "admitted an expired claim: " (pr-str row)))))

(deftest a-missing-claim-is-not-claimable
  ;; nil is what the store returns for a token that matched nothing, and it
  ;; must reach the same answer as a claim that exists and is not ready — the
  ;; host relies on that sameness to keep its refusals indistinguishable.
  (is (false? (session-handoff/claimable?
               nil {:origin-trusted? true :expired? false}))))

(deftest an-ordinary-claim-is-admitted
  ;; The fixture is asserted rather than trusted: every property above holds
  ;; vacuously against a corpus that refuses everything.
  (is (true? (host-claimable? {:origin-trusted true :ready true
                               :claimed false :expired false}))))
