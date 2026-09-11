(ns cloud.itonami.app.secret-request-test
  "The closed catalogue, admission, and the transcript guard.

  Every test here is about a boundary that is invisible when it works: nothing
  observable happens when a credential does NOT reach a transcript. So each one
  is written to fail for the reason it names — a shape check is exercised at the
  boundary length as well as inside and outside it, and the guard is asserted in
  both directions rather than only on the case it is meant to catch."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.bot :as bot]
            [cloud.itonami.app.secret-request :as secret]))

(def ^:private cloudflare (secret/requirement "cloudflare-api-token"))
(def ^:private account-id (secret/requirement "cloudflare-account-id"))

;; A value of the right SHAPE and no other significance. Written out rather than
;; generated so the length is visible in the source: the boundary tests below
;; depend on it being exactly what the catalogue asks for.
(def ^:private token "abcdefghij0123456789_-ABCDEFGHIJKLMNOPQR")

(deftest the-fixture-token-is-the-length-the-catalogue-asks-for
  ;; If this drifts, every admission test below passes for the wrong reason.
  (is (= 40 (count token))))

(deftest the-catalogue-is-closed
  (testing "a request can only be made for an entry that exists"
    (is (some? (secret/requirement "cloudflare-api-token")))
    (is (nil? (secret/requirement "aws-root-password")))
    (is (nil? (secret/requirement nil)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (secret/requirement! "aws-root-password"))))
  (testing "every entry is complete enough to render a field and resolve a value"
    (doseq [[id r] secret/catalogue]
      (is (= id (:secret/id r)) "the key and the id are the same name")
      (doseq [field [:secret/title :secret/purpose :secret/holder
                     :secret/environment :secret/service :secret/account
                     :secret/shape]]
        (is (seq (str (get r field)))
            (str id " is missing " field)))
      (is (some? (re-pattern (:secret/shape r)))
          (str id " has a shape that does not compile")))))

(deftest a-request-cannot-carry-a-value
  (testing "nothing reachable from `public` could hold one"
    (let [p (secret/public cloudflare)]
      (is (= #{:id :title :concealed? :purpose :holder :issue-url
               :environment :tools}
             (set (keys p)))
          "a new key here is a new place a value could be put")))
  (testing "and the card constructor refuses one rather than dropping it"
    ;; Dropping it would leave the caller believing the credential had been
    ;; recorded. This is the same refusal `cloud-itonami/kagi` makes with
    ;; `400 plaintext-value-received`.
    (is (thrown? clojure.lang.ExceptionInfo
                 (bot/secret-card {:id "card-1" :secret "cloudflare-api-token"
                                   :value token})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bot/secret-card {:id "card-1" :secret "cloudflare-api-token"
                                   :password token})))
    (is (= :bot/plaintext-value-received
           (try (bot/secret-card {:id "card-1" :secret "cloudflare-api-token"
                                  :token token})
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (testing "the refusal is for the value, not for any unexpected key"
      ;; A constructor that threw on everything it did not recognise would pass
      ;; the assertions above while discriminating nothing.
      (is (map? (bot/secret-card {:id "card-1" :secret "cloudflare-api-token"
                                  :state :requested :stored-ref "keychain://a/b"}))))))

(deftest admission-checks-the-shape-and-says-nothing-about-the-value
  (testing "accepted"
    (is (= {:admitted? true} (secret/admit cloudflare token))))
  (testing "refused, with a reason and never the value"
    (doseq [[value reason]
            [[nil :not-a-string]
             [42 :not-a-string]
             ["" :empty]
             ["   " :empty]
             [(str " " token) :surrounding-whitespace]
             [(str token "\n") :surrounding-whitespace]
             [(apply str (repeat 5000 "a")) :too-long]
             ["short" :wrong-shape]
             [(str token "x") :wrong-shape]
             [(subs token 1) :wrong-shape]
             [(str "token is " token) :wrong-shape]]]
      (let [result (secret/admit cloudflare value)]
        (is (= {:admitted? false :reason reason} result)
            (str "for " (pr-str (when (string? value) (count value))) " chars"))
        (is (seq (secret/refusal reason))
            "every reason has something to tell the person"))))
  (testing "the boundary is where the catalogue put it"
    ;; 39 and 41 both fail; 40 passes. Without these two, flipping the shape's
    ;; quantifier would leave this suite green.
    (is (false? (:admitted? (secret/admit cloudflare (subs token 0 39)))))
    (is (true? (:admitted? (secret/admit cloudflare token))))
    (is (false? (:admitted? (secret/admit cloudflare (str token "z"))))))
  (testing "a value surrounded by prose is not a value"
    (is (false? (:admitted? (secret/admit cloudflare (str "use " token)))))))

(deftest the-transcript-guard-fires-and-does-not-over-fire
  (let [open [cloudflare]]
    (testing "a bare credential is refused"
      (is (true? (secret/carries-value? open token)))
      (is (true? (secret/carries-value? open (str "  " token "  ")))
          "trimmed first: a pasted line usually carries whitespace"))
    (testing "ordinary messages are not"
      (doseq [text ["deploy して" "kotoba-lang.org を deploy して"
                    "トークンは Cloudflare の画面にあります"
                    "https://dash.cloudflare.com/profile/api-tokens"
                    "" nil]]
        (is (false? (secret/carries-value? open text))
            (str "refused an ordinary message: " (pr-str text)))))
    (testing "a credential inside a sentence is NOT refused, and that is known"
      ;; Stated rather than left to be discovered: the guard is anchored, so a
      ;; person who writes prose around the value gets past it. The field is the
      ;; prevention; this is containment for the common case (a bare paste).
      (is (false? (secret/carries-value? open (str "token: " token)))))
    (testing "nothing is refused when nothing is open"
      (is (false? (secret/carries-value? [] token))))
    (testing "a coordinate is not guarded"
      ;; An account id is not a credential, and refusing a message that contains
      ;; one would be refusing a person for saying a public fact about their own
      ;; account.
      (is (false? (secret/concealed? account-id)))
      (is (true? (secret/concealed? cloudflare)))
      (is (false? (secret/carries-value? [account-id]
                                         "0123456789abcdef0123456789abcdef")))
      (is (true? (:admitted? (secret/admit account-id
                                           "0123456789abcdef0123456789abcdef")))
          "it is still admitted through the field it was asked for"))))
