(ns cloud.itonami.app.bot-authority-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot-authority :as bot-authority]
            [cloud.itonami.app.bot-identity :as bot-identity])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "bot-authority-" (make-array FileAttribute 0))))

(def ^:private now "2026-08-20T00:00:00Z")
(def ^:private policy
  [{:capability :patch.create      :decision :autonomous}
   {:capability :metrics.read      :decision :autonomous}
   {:capability :patch.integrate   :decision :approval-required}
   {:capability :deploy.production :decision :blocked}])

(defmacro with-keys [dir & body]
  `(with-redefs [bot-identity/seed-file (fn [] (io/file ~dir "bot-identity.seed"))
                 bot-authority/root-seed-file (fn [] (io/file ~dir "workforce-authority.seed"))]
     ~@body))

(deftest a-token-carries-only-what-the-fleet-made-autonomous
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
            t (bot-authority/issue bot policy)
            holder (bot-identity/bot-did "b1")
            ok? (fn [k] (bot-authority/authorized? t "mangaka/work-yamainu" k
                                                   {:now now :holder holder}))]

        (testing "the chain verifies against the root did alone"
          (is (:ok? (bot-authority/verify t))))

        (testing "autonomous capabilities are carried"
          (is (ok? :patch.create))
          (is (ok? :metrics.read)))

        (testing "a decision a human still makes is NOT authority"
          ;; :approval-required and :blocked are not narrower grants. Carrying
          ;; them as scope would be the token claiming what the policy withheld.
          (is (not (ok? :patch.integrate)))
          (is (not (ok? :deploy.production))))

        (testing "a token does not reach another business"
          (is (not (bot-authority/authorized? t "club-shinshi/engineer" :patch.create
                                              {:now now :holder holder}))))

        (testing "the holder and the clock are both required conjuncts"
          ;; authority/authorized? has no arity that omits either, and the
          ;; first version of this namespace dropped both by calling covers?
          ;; with a scope string -- which answered true for everything.
          (is (not (bot-authority/authorized? t "mangaka/work-yamainu" :patch.create
                                              {:now now :holder "did:key:zSomeoneElse"})))
          (is (not (bot-authority/authorized? t "mangaka/work-yamainu" :patch.create
                                              {:now nil :holder holder}))))))))

(deftest a-forged-block-is-refused-before-it-becomes-a-grant
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
            t (bot-authority/issue bot policy)
            holder (bot-identity/bot-did "b1")
            forged (assoc-in t [:biscuit/blocks 0 :block/facts]
                             [['holder holder]
                              ['scope (bot-authority/capability->scope
                                       "mangaka/work-yamainu" :deploy.production)]])]
        (is (= :signature-mismatch (:reason (bot-authority/verify forged))))
        (is (nil? (bot-authority/->grant forged))
            "an unverified token must not become a grant -- folding first and
             checking later is how a forgery becomes a decision")
        (is (not (bot-authority/authorized? forged "mangaka/work-yamainu" :deploy.production
                                            {:now now :holder holder})))))))

(deftest the-base-is-the-top-of-the-range-not-the-bottom
  ;; biscuit.authority/->grant MEETS blocks onto the base, and meet only
  ;; narrows. An empty base produced a token reaching nothing, which reads as
  ;; a safe failure and is not one: it is indistinguishable from a Bot with no
  ;; capabilities.
  (let [dir (temp-dir)]
    (with-keys dir
      (let [t (bot-authority/issue {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
                                   [{:capability :patch.create :decision :autonomous}])
            g (bot-authority/->grant t)]
        (is (seq (:grant/scopes g))
            "a token whose grant reaches nothing has folded wrongly")
        (is (= (bot-identity/bot-did "b1") (:grant/holder g)))))))

(deftest without-a-key-there-is-no-token-rather-than-an-unsigned-one
  (with-redefs [bot-authority/root-seed-file (fn [] (io/file "/proc/nonexistent/seed"))
                bot-identity/seed-file (fn [] (io/file "/proc/nonexistent/seed"))]
    (is (nil? (bot-authority/issue {:bot/id "b1" :bot/workforce-key "k"} policy)))
    (is (= :no-root-key (:reason (bot-authority/verify {:biscuit/version "biscuit/edn-v1"
                                                        :biscuit/blocks []}))))))
