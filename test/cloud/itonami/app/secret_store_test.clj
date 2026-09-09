(ns cloud.itonami.app.secret-store-test
  "Where a credential goes, where it comes back from, and what is never done
  to it on the way.

  `security` is never actually run: `*run*` and `*write*` are redefined, so
  these assertions are about the argument lists this namespace builds rather
  than about the developer's own login keychain. A test that wrote there would
  either be skipped on CI or leave an item behind, and a skipped test reports
  the same green as a passing one."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.secret-store :as store]))

(def ^:private token "abcdefghij0123456789_-ABCDEFGHIJKLMNOPQR")

(defn- capturing
  "Run `f` with the subprocess seams recorded. Returns
  `{:reads [...] :writes [...] :result ...}`. `found` is what a read answers."
  [found f]
  (let [reads (atom []) writes (atom [])]
    (binding [store/*run* (fn [argv & _] (swap! reads conj argv) found)
              store/*write* (fn [argv & _] (swap! writes conj argv) true)
              store/*environment* (constantly nil)]
      (let [result (f)]
        {:reads @reads :writes @writes :result result}))))

(deftest a-read-never-asks-for-the-attribute-dump
  ;; `security find-generic-password -g` prints the password as part of a
  ;; human-readable dump on stderr, and a subprocess inherits the caller's
  ;; stderr unless told otherwise. That pair is how a B2 application key reached
  ;; a chat transcript once already (ADR-2607152322). `-w` prints the password
  ;; alone, into a pipe this namespace captures.
  (let [{:keys [reads]} (capturing token #(store/value "cloudflare-api-token"))]
    (is (seq reads) "the keychain was actually consulted")
    (doseq [argv reads]
      (when (= "find-generic-password" (second argv))
        (is (some #{"-w"} argv) "reads with -w")
        (is (not-any? #{"-g"} argv) "never with -g")))))

(deftest a-read-names-the-item-in-full-and-never-lists
  (let [{:keys [reads]} (capturing token #(store/value "cloudflare-api-token"))
        find-argv (first (filter #(= "find-generic-password" (second %)) reads))]
    (is (some? find-argv))
    (is (= "cloud-itonami-app.secret"
           (nth find-argv (inc (.indexOf ^java.util.List find-argv "-s")))))
    (is (= "cloudflare-api-token"
           (nth find-argv (inc (.indexOf ^java.util.List find-argv "-a")))))
    (is (not-any? #{"dump-keychain" "-D" "find-generic-password-all"}
                  (rest find-argv))
        "there is no listing form of this call anywhere in the argv")))

(deftest the-environment-wins-and-says-so
  (binding [store/*environment* {"CLOUDFLARE_API_TOKEN" token}]
    (binding [store/*run* (fn [& _] (throw (ex-info "must not be reached" {})))]
      (is (= token (store/value "cloudflare-api-token")))
      (is (= :environment (store/source "cloudflare-api-token")))
      (is (true? (store/present? "cloudflare-api-token")))))
  (testing "and the stored item answers when it does not"
    (let [{:keys [result]} (capturing token
                                      #(store/source "cloudflare-api-token"))]
      (is (= :stored result))))
  (testing "absent is absent, not an error"
    (let [{:keys [result]} (capturing nil #(store/value "cloudflare-api-token"))]
      (is (nil? result)))
    (let [{:keys [result]} (capturing nil #(store/present? "cloudflare-api-token"))]
      (is (false? result)))))

(deftest a-refused-value-never-reaches-a-subprocess
  ;; The order matters more than the refusal. `admit` runs BEFORE the argument
  ;; list is built, so a mistyped credential is not put on an argv at all.
  (doseq [bad ["" "  " "short" (str token " ")]]
    (let [reads (atom []) writes (atom [])]
      (binding [store/*run* (fn [argv & _] (swap! reads conj argv) nil)
                store/*write* (fn [argv & _] (swap! writes conj argv) true)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (store/put! "cloudflare-api-token" bad))
            (str "accepted " (pr-str bad))))
      (is (empty? @writes)
          (str "a subprocess was started for " (pr-str bad))))))

(deftest a-stored-value-is-recorded-as-a-locator
  (let [{:keys [writes result]}
        (capturing nil #(store/put! "cloudflare-api-token" token))]
    (is (= "keychain://cloud-itonami-app.secret/cloudflare-api-token" result)
        "what comes back is where it went, not what went there")
    (is (not (.contains ^String (str result) token)))
    (let [argv (first writes)]
      (is (= "add-generic-password" (second argv)))
      (is (some #{"-U"} argv)
          "updates in place: a credential with two copies has one rotation"))))

(deftest an-unknown-credential-is-refused-rather-than-invented
  (is (thrown? clojure.lang.ExceptionInfo
               (store/put! "aws-root-password" token)))
  (is (nil? (store/value "aws-root-password")))
  (is (false? (store/present? "aws-root-password"))))

(deftest a-write-failure-is-reported-rather-than-assumed
  ;; `security` failing has to be louder than it succeeding: a card that said
  ;; 保存済み over a keychain that holds nothing is the exact state this whole
  ;; mechanism exists to avoid.
  (binding [store/*write* (fn [& _] false)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/put! "cloudflare-api-token" token)))))
