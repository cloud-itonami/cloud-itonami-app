(ns cloud.itonami.app.repo-profile-test
  "What a repository is allowed to say about its own bot.

  Every refusal pins its `:reason` literal and not the message. A negative test
  that asserts only `(not accepted?)` counts a refusal for ANY cause as the one
  it meant to exercise — ADR-2608136000 §6, and the reason this file is mostly
  reasons."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.repo-profile :as rp]))

(def ^:private minimal
  (pr-str {:profile/schema rp/schema
           :profile/id "kotoba-lang/amu"}))

(defn- with [m]
  (pr-str (merge {:profile/schema rp/schema :profile/id "kotoba-lang/amu"} m)))

(deftest a-minimal-profile-is-admitted
  (let [r (rp/admit minimal)]
    (is (:accepted? r))
    (is (= "kotoba-lang/amu" (:profile/id r)))))

(deftest description-crosses
  (let [r (rp/admit (with {:bot/name "amu"
                           :bot/brief "あなたは amu の現在地を測る。"
                           :bot/avatar {:avatar/color :teal :avatar/glyph :wedge}
                           :bot/context-refs [{:kind "repo" :target "kotoba-lang/amu"}]}))]
    (is (:accepted? r))
    (is (= "amu" (get-in r [:describes :bot/name])))
    (is (= 1 (count (get-in r [:describes :bot/context-refs]))))))

(deftest preference-crosses-separately-from-description
  (let [r (rp/admit (with {:bot/model-preference {:provider-id "murakumo"
                                                  :model "awai-network/basho"}}))]
    (is (:accepted? r))
    (is (contains? (:prefers r) :bot/model-preference))
    (is (not (contains? (:describes r) :bot/model-preference))
        "a preference must not be readable as a description")))

;; ---------------------------------------------------------------------------
;; the refusals, which are what this file is for
;; ---------------------------------------------------------------------------

(deftest a-grant-is-refused-by-name
  ;; The whole point. A repository that writes `:bot/omakase? true` is a
  ;; repository whose author gave themselves a standing owner delegation.
  (doseq [k [:bot/tools :bot/accounts :bot/workspace :bot/writes? :bot/browser?
             :bot/computer? :bot/omakase? :bot/peers? :bot/enabled?]]
    (let [r (rp/admit (with {k true}))]
      (is (= :repo-profile/key-refused (:reason r))
          (str k " was not refused"))
      (is (= [k] (:refused-keys r))
          (str k " was refused but not named"))
      (is (str/includes? (:message r) (pr-str k))
          (str k " is not in the message the writer reads")))))

(deftest an-unknown-key-is-refused-and-not-ignored
  ;; A closed vocabulary. The day `:bot/spend-limit` is added upstream, a
  ;; repository that already carried it must not start granting it.
  (let [r (rp/admit (with {:bot/spend-limit 100000}))]
    (is (= :repo-profile/key-refused (:reason r)))
    (is (= [:bot/spend-limit] (:refused-keys r)))))

(deftest tools-may-be-REQUESTED-but-not-granted
  ;; The pair that must not collapse: asking for a tool crosses, holding one
  ;; does not.
  (is (:accepted? (rp/admit (with {:bot/tools-requested #{"repo_read"}}))))
  (is (= :repo-profile/key-refused (:reason (rp/admit (with {:bot/tools #{"repo_read"}}))))))

(deftest a-tagged-literal-is-denied
  (testing "an unknown tag, caught by :default"
    (is (= :repo-profile/tagged-literal
           (:reason (rp/admit (str "{:profile/schema \"" rp/schema "\" "
                                   ":profile/id #my/thing \"x\"}"))))))
  ;; The one the first version of this namespace admitted. `clojure.edn/read`
  ;; still applies `default-data-readers`, so `#inst` and `#uuid` never reach
  ;; `:default` -- they parse. Caught on the value instead.
  (testing "a BUILT-IN tag, which :default does not see"
    (is (= :repo-profile/tagged-literal
           (:reason (rp/admit (str "{:profile/schema \"" rp/schema "\" "
                                   ":profile/id \"x\" :bot/name #inst \"2026-09-06\"}")))))
    (is (= :repo-profile/tagged-literal
           (:reason (rp/admit (str "{:profile/schema \"" rp/schema "\" "
                                   ":profile/id \"x\" "
                                   ":bot/name #uuid \"00000000-0000-0000-0000-000000000000\"}")))))))

(deftest absent-is-its-own-reason-and-not-a-parse-failure
  ;; `:absent` and `:unreadable` are different facts: no file, versus a file
  ;; that could not be read. A caller that folded them would tell an operator
  ;; their profile is broken when they have not written one.
  (is (= :repo-profile/absent (:reason (rp/admit nil))))
  (is (= :repo-profile/unreadable (:reason (rp/admit "{:profile/id")))))

(deftest a-non-map-is-not-a-profile
  (is (= :repo-profile/not-a-map (:reason (rp/admit "[1 2 3]")))))

(deftest the-schema-must-be-named
  (is (= :repo-profile/schema-mismatch
         (:reason (rp/admit (pr-str {:profile/id "x"})))))
  (is (= :repo-profile/schema-mismatch
         (:reason (rp/admit (pr-str {:profile/schema "something.else.v1"
                                     :profile/id "x"}))))))

(deftest an-id-is-required
  (is (= :repo-profile/id-missing
         (:reason (rp/admit (pr-str {:profile/schema rp/schema})))))
  (is (= :repo-profile/id-missing
         (:reason (rp/admit (pr-str {:profile/schema rp/schema :profile/id "  "}))))))

(deftest oversized-values-are-refused-with-their-own-reasons
  (is (= :repo-profile/too-large
         (:reason (rp/admit (apply str (repeat 70000 "x"))))))
  (is (= :repo-profile/name-too-long
         (:reason (rp/admit (with {:bot/name (apply str (repeat 61 "あ"))})))))
  (is (= :repo-profile/brief-too-long
         (:reason (rp/admit (with {:bot/brief (apply str (repeat 2001 "あ"))})))))
  (is (= :repo-profile/id-too-long
         (:reason (rp/admit (pr-str {:profile/schema rp/schema
                                     :profile/id (apply str (repeat 121 "x"))}))))))

(deftest the-two-key-sets-do-not-overlap
  ;; A key in both would be applied twice with two different meanings, and
  ;; `select-keys` would make that invisible.
  (is (empty? (clojure.set/intersection rp/describes rp/prefers))))

(deftest no-grant-key-hides-inside-the-allowed-vocabulary
  ;; A floor on the vocabulary itself: this suite would still pass if someone
  ;; added `:bot/omakase?` to `describes`, so state that they have not.
  (doseq [k [:bot/tools :bot/accounts :bot/workspace :bot/writes? :bot/browser?
             :bot/computer? :bot/omakase? :bot/peers? :bot/enabled?
             :bot/coding? :bot/virtual-shell? :bot/goal? :bot/priority?]]
    (is (not (contains? rp/describes k)) (str k " is described as harmless"))
    (is (not (contains? rp/prefers k)) (str k " is described as a preference"))))

;; ---------------------------------------------------------------------------
;; :cli/config, and the key that is no longer in the vocabulary
;; ---------------------------------------------------------------------------

(deftest cli-config-is-admitted-only-for-keys-this-surface-reads
  (is (:accepted? (rp/admit (with {:cli/config {:skin "grok"}}))))
  (let [r (rp/admit (with {:cli/config {:skin "grok" :server {:port 1}}}))]
    (is (= :repo-profile/cli-config-key-refused (:reason r))
        "a repository must not be able to name the ingress the CLI talks to")
    (is (= [:server] (:refused-keys r)))))

(deftest cli-config-must-be-a-map
  (is (= :repo-profile/cli-config-not-a-map
         (:reason (rp/admit (with {:cli/config "grok"}))))))

(deftest cli-layers-is-not-in-the-vocabulary
  ;; It was, for one commit, and it drove nothing: the shipped profile mounts
  ;; its three layers unconditionally and there is no selection for a name to
  ;; select. A key that is admitted and inert reads as a capability.
  (is (not (contains? rp/prefers :cli/layers)))
  (is (not (contains? rp/describes :cli/layers)))
  (is (= :repo-profile/key-refused
         (:reason (rp/admit (with {:cli/layers ["itonami.theme"]}))))))

(deftest the-cli-config-allowlist-is-not-everything
  ;; A floor on the allowlist itself, the same shape as the grant floor above:
  ;; this suite would keep passing if `:server` were added to it.
  (is (contains? rp/cli-config-keys :skin))
  (doseq [k [:server :mcp :auth :providers :routing :residency]]
    (is (not (contains? rp/cli-config-keys k))
        (str k " would let a repository configure the client's own plumbing"))))

;; ---------------------------------------------------------------------------
;; several people, one repository
;; ---------------------------------------------------------------------------

(defn- profiles [& ms]
  (:accepted (rp/admit-all (map-indexed (fn [i m] [(str "p" i ".edn") (pr-str m)]) ms))))

(def ^:private jun {:profile/schema rp/schema :profile/id "amu/jun" :profile/for "jun"})
(def ^:private rio {:profile/schema rp/schema :profile/id "amu/rio" :profile/for "rio"})
(def ^:private shared {:profile/schema rp/schema :profile/id "amu/shared" :profile/default? true})

(deftest one-bad-file-does-not-discard-the-good-ones
  ;; Two people keeping their own profiles: one person's typo must not silence
  ;; the other's profile.
  (let [{:keys [accepted refused]}
        (rp/admit-all [["jun.edn" (pr-str jun)]
                       ["broken.edn" "{:profile/id"]
                       ["rio.edn" (pr-str rio)]])]
    (is (= 2 (count accepted)))
    (is (= 1 (count refused)))
    (is (= :repo-profile/unreadable (:reason (first refused))))
    (is (= "broken.edn" (:source (first refused)))
        "a refusal that does not say which file is not actionable")))

(deftest the-operator-picks-by-name
  (let [ps (profiles jun rio shared)]
    (is (= "amu/rio" (:profile/id (rp/select ps {:requested "amu/rio"}))))))

(deftest a-name-that-is-not-there-lists-what-is
  (let [r (rp/select (profiles jun rio) {:requested "amu/nobody"})]
    (is (= :repo-profile/requested-not-found (:reason r)))
    (is (= #{"amu/jun" "amu/rio"} (set (:available r))))))

(deftest the-local-identity-selects-and-the-repository-does-not-assert-it
  ;; `:profile/for` is a label the DESTINATION matches against its own
  ;; configured identity. The repository never says who is running the CLI.
  (let [ps (profiles jun rio shared)]
    (is (= "amu/jun" (:profile/id (rp/select ps {:as "jun"}))))
    (is (= "amu/rio" (:profile/id (rp/select ps {:as "rio"}))))
    (testing "an operator nobody claims falls through to the default"
      (is (= "amu/shared" (:profile/id (rp/select ps {:as "someone-else"})))))))

(deftest an-explicit-name-beats-the-identity
  (let [ps (profiles jun rio)]
    (is (= "amu/rio" (:profile/id (rp/select ps {:as "jun" :requested "amu/rio"}))))))

(deftest ambiguity-refuses-and-never-guesses
  ;; Answering by sort order would hand the operator a bot chosen by filename.
  (testing "several profiles, no default, no identity"
    (let [r (rp/select (profiles jun rio) {})]
      (is (= :repo-profile/ambiguous (:reason r)))
      (is (= #{"amu/jun" "amu/rio"} (set (:available r))))))
  (testing "two defaults"
    (let [r (rp/select (profiles shared (assoc shared :profile/id "amu/other")) {})]
      (is (= :repo-profile/duplicate-default (:reason r)))
      (is (= 2 (count (:sources r))))))
  (testing "two profiles claiming the same person"
    (let [r (rp/select (profiles jun (assoc jun :profile/id "amu/jun2")) {:as "jun"})]
      (is (= :repo-profile/duplicate-for (:reason r)))))
  (testing "the same id in two files"
    (let [r (rp/select (profiles jun jun) {:requested "amu/jun"})]
      (is (= :repo-profile/duplicate-id (:reason r))))))

(deftest one-profile-needs-no-ceremony
  ;; A repository with a single profile should not have to mark it default.
  (is (= "amu/jun" (:profile/id (rp/select (profiles jun) {})))))

(deftest no-readable-profile-is-its-own-reason
  (is (= :repo-profile/none-accepted (:reason (rp/select [] {})))))

(deftest profile-for-and-default-are-descriptions-not-grants
  (is (contains? rp/describes :profile/for))
  (is (contains? rp/describes :profile/default?))
  (is (:accepted? (rp/admit (with {:profile/for "jun" :profile/default? true})))))
