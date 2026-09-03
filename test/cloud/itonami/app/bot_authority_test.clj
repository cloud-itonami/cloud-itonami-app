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

(def ^:private runnable
  #{"workspace_read" "workspace_list" "workspace_search"
    "git_status" "git_log" "workspace_write_file" "git_commit"
    "disk_space_status" "disk_space_cleanup"
    "disk_space_inventory" "disk_space_reclaim"})

(deftest the-capability-policy-decides-and-not-only-in-the-prompt
  ;; Before this, a Bot's capability policy reached exactly one place: its
  ;; system prompt, which tells it "Blocked capabilities stay blocked" and had
  ;; nothing behind it.
  (let [dir (temp-dir)
        bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
        admit (fn [policy] (bot-authority/admit runnable bot policy {:now now}))]
    (with-keys dir
      (testing "an autonomous capability keeps the tools that exercise it"
        (is (contains? (admit [{:capability :patch.create :decision :autonomous}])
                       "workspace_write_file")))

      (testing "disk inspection and cleanup remain distinct capabilities"
        (let [inspect-only (admit [{:capability :disk.inspect
                                    :decision :autonomous}])]
          (is (contains? inspect-only "disk_space_status"))
          (is (not (contains? inspect-only "disk_space_cleanup"))))
        (let [both (admit [{:capability :disk.inspect :decision :autonomous}
                           {:capability :disk.cleanup :decision :autonomous}])]
          (is (every? both ["disk_space_status" "disk_space_cleanup"]))
          (is (not (contains? both "disk_space_inventory")))
          (is (not (contains? both "disk_space_reclaim"))))
        (let [inventory-only
              (admit [{:capability :disk.candidate.inspect :decision :autonomous}])]
          (is (contains? inventory-only "disk_space_inventory"))
          (is (not (contains? inventory-only "disk_space_reclaim"))))
        (let [candidate-cleanup
              (admit [{:capability :disk.candidate.inspect :decision :autonomous}
                      {:capability :disk.reclaimable.cleanup :decision :autonomous}])]
          (is (every? candidate-cleanup
                      ["disk_space_inventory" "disk_space_reclaim"]))))

      (testing "a capability a human still decides does NOT authorise the tool"
        (is (not (contains? (admit [{:capability :patch.create :decision :approval-required}])
                            "workspace_write_file")))
        (is (not (contains? (admit [{:capability :patch.create :decision :blocked}])
                            "git_commit"))))

      (testing "reading is never taken away by this gate"
        ;; The fleet vocabulary has no capability meaning "may read the
        ;; repository it was given", so mapping reads would be a guess that
        ;; can blind a Bot to the repo it was pointed at.
        (doseq [policy [[{:capability :patch.create :decision :blocked}]
                        [{:capability :patch.create :decision :autonomous}]
                        []]]
          (is (every? (admit policy)
                      ["workspace_read" "workspace_list" "git_status" "git_log"]))))

      (testing "it only ever narrows"
        (doseq [policy [[] [{:capability :patch.create :decision :autonomous}]
                        [{:capability :patch.create :decision :blocked}]]]
          (is (every? runnable (admit policy))
              "no arrangement of policy may ADD a tool"))))

    (testing "an unissuable token is no second floor, not no floor"
      ;; A key problem must not become a fleet outage: the existing ceiling is
      ;; the tool grant and it still applies.
      (with-redefs [bot-authority/root-seed-file (fn [] (io/file "/proc/none/a"))
                    bot-identity/seed-file (fn [] (io/file "/proc/none/i"))]
        (is (= runnable (bot-authority/admit runnable bot
                                             [{:capability :patch.create :decision :blocked}]
                                             {:now now})))))

    (testing "a Bot with no fleet policy is untouched"
      (with-keys dir
        (is (= runnable (bot-authority/admit runnable {:bot/id "b1"}
                                             [{:capability :patch.create :decision :blocked}]
                                             {:now now})))))))

(deftest the-fold-fails-in-both-directions-and-both-are-pinned
  ;; These two bugs are opposites and each hides the other. An empty BASE
  ;; makes every token reach nothing; an empty TOKEN folding onto a wide base
  ;; makes every restriction a promotion. Asserting only one would have looked
  ;; fine while the other shipped -- and the second one did, until the probe
  ;; for this namespace caught a Bot with every capability :blocked being
  ;; granted everything.
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}]
        (testing "a token declaring no scope reaches nothing"
          (let [t (bot-authority/issue bot [{:capability :patch.create :decision :blocked}])]
            (is (:ok? (bot-authority/verify t)) "it is still a valid token")
            (is (empty? (:grant/scopes (bot-authority/->grant t)))
                "a Bot whose every capability was withheld must not be granted the base")))

        (testing "a token declaring scopes reaches exactly those"
          (let [t (bot-authority/issue bot [{:capability :patch.create :decision :autonomous}])]
            (is (= 1 (count (:grant/scopes (bot-authority/->grant t)))))))))))

;; ── a Bot narrowing its own authority ───────────────────────────────────────
;;
;; Until 2026-09-01 this namespace said a Bot could not do this, because
;; `token/append` needs the key the previous block names and no Bot held one.
;; It held one all along -- `bot-did` is the public half of
;; derive-seed(fleet, bot-id) -- and what was missing was a caller.
;;
;; The load-bearing assertion is NOT that a narrowed token still works. It is
;; that the narrowing REMOVED something, and that a block cannot add. A reader
;; that folded only the first block would pass every refusal below and fail
;; only these.

(deftest a-bot-narrows-its-own-token-and-the-narrowing-holds
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
            t (bot-authority/issue bot policy)
            holder (bot-identity/bot-did "b1")
            sub-did (bot-identity/bot-did "b2")
            ok? (fn [tok k] (bot-authority/authorized?
                             tok "mangaka/work-yamainu" k
                             {:now now :holder holder}))
            narrowed (bot-authority/attenuate
                      bot t
                      [(bot-authority/capability->scope "mangaka/work-yamainu" :metrics.read)]
                      sub-did)]

        (testing "the Bot could sign at all -- the key its own did names"
          (is (some? (bot-identity/bot-signing-seed "b1")))
          (is (some? narrowed) "attenuate must not answer nil for a Bot with a seed"))

        (testing "the narrowed token still verifies against the fleet root"
          ;; The issuer was never consulted, and the chain is still whole.
          (is (:ok? (bot-authority/verify narrowed))))

        (testing "what the Bot kept is still authority"
          (is (ok? narrowed :metrics.read)))

        (testing "what the Bot gave up is GONE"
          ;; The assertion this test exists for. patch.create was autonomous on
          ;; the issued token; after narrowing to metrics.read it must not be.
          (is (ok? t :patch.create) "precondition: the wide token had it")
          (is (not (ok? narrowed :patch.create))
              "a reader answering true here would make attenuation decorative"))))))

(deftest appending-cannot-widen-a-bot-token
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
            t (bot-authority/issue bot policy)
            holder (bot-identity/bot-did "b1")
            widened (bot-authority/attenuate
                     bot t
                     [(bot-authority/capability->scope "mangaka/work-yamainu" :deploy.production)]
                     (bot-identity/bot-did "b1"))]
        (testing "claiming a capability the policy BLOCKED grants nothing"
          (is (:ok? (bot-authority/verify widened)))
          (is (not (bot-authority/authorized? widened "mangaka/work-yamainu"
                                              :deploy.production
                                              {:now now :holder holder}))))))))

(deftest attenuate-refuses-rather-than-passing-the-wide-token-on
  (let [dir (temp-dir)]
    (with-keys dir
      (let [bot {:bot/id "b1" :bot/workforce-key "mangaka/work-yamainu"}
            t (bot-authority/issue bot policy)]
        (testing "no scopes, no recipient, no token -- each answers nil, never t"
          (is (nil? (bot-authority/attenuate bot t [] (bot-identity/bot-did "b2"))))
          (is (nil? (bot-authority/attenuate bot t ["kotoba://cap/x"] nil)))
          (is (nil? (bot-authority/attenuate bot nil ["kotoba://cap/x"]
                                             (bot-identity/bot-did "b2")))))))))
