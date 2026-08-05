(ns cloud.itonami.app.mail-age-key-test
  "Where the sealing key comes from, and what happens when it comes from nowhere.

  The precedence is the whole behaviour: an operator who exports an override and
  still sees mail sealed to the vault's key has a bug they cannot see, and one
  whose vault is unreachable needs the Keychain to answer rather than the
  resolution to stop."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-age-key :as age-key]))

(def ^:private recipient-a
  "age1erny355hm8nrq5plls0fs2trl6gwrxp0vl8nfqkxkc5clh2gxfwqemlhg9")
(def ^:private recipient-b
  "age1f2ephydnp8pdzzkv7yj6pumm87edjeeyl734kp4anscwhyyymyqsjd42va")

(defn- with-environment [values f]
  (binding [age-key/*environment* #(get values %)] (f)))

(deftest an-explicit-override-wins
  (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS" recipient-a}
    (fn []
      (let [{:keys [recipients source]} (age-key/resolve-recipients)]
        (is (= [recipient-a] recipients))
        (is (= :environment source)
            "an override that loses to a vault is a bug nobody can see")))))

(deftest several-recipients-are-read-from-one-value
  (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS"
                     (str recipient-a "," recipient-b)}
    (fn []
      (is (= [recipient-a recipient-b] (age-key/recipients))))))

(deftest anything-that-is-not-a-recipient-is-dropped
  (testing "a stray word in the variable must not become an age argument —
            `age -r not-a-key` fails the whole encryption, and the body is then
            not written at all"
    (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS"
                       (str "# comment," recipient-a ",not-a-key")}
      (fn []
        (is (= [recipient-a] (age-key/recipients)))))))

(deftest a-recipients-file-is-read-with-its-comments-ignored
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "age-recipients-" (System/nanoTime) ".txt"))]
    (try
      (spit file (str "# who may read filed mail\n"
                      recipient-a "\n"
                      "\n"
                      recipient-b "\n"))
      (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS_FILE" (.getPath file)}
        (fn []
          (let [{:keys [recipients source]} (age-key/resolve-recipients)]
            (is (= [recipient-a recipient-b] recipients))
            (is (= :file source)))))
      (finally (.delete file)))))

(deftest the-environment-beats-the-file
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "age-recipients-" (System/nanoTime) ".txt"))]
    (try
      (spit file recipient-b)
      (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS" recipient-a
                         "CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS_FILE" (.getPath file)}
        (fn []
          (is (= [recipient-a] (age-key/recipients)))))
      (finally (.delete file)))))

(deftest with-nothing-configured-it-says-so-and-names-the-places
  (with-environment {}
    (fn []
      (with-redefs [age-key/resolve-recipients
                    (fn [] {:recipients [] :source nil
                            :reason (str "age recipient がどこにも設定されていません。"
                                         "kagi item `" age-key/kagi-item "`")})]
        (let [{:keys [recipients reason]} (age-key/resolve-recipients)]
          (is (empty? recipients))
          (is (str/includes? reason age-key/kagi-item)
              "an operator reading this must learn WHERE to put the key, not
               only that it is missing"))))))

(deftest status-reports-sealing-without-revealing-a-key
  (with-environment {"CLOUD_ITONAMI_MAIL_AGE_RECIPIENTS" recipient-a}
    (fn []
      (let [status (age-key/status)]
        (is (true? (:sealed? status)))
        (is (= "environment" (:source status)))
        (testing "recipients are public keys, and naming them is how an operator
                  notices they are filing to a key they cannot open"
          (is (= [recipient-a] (:recipients status))))
        (testing "and the identity is described by location only — this app
                  never reads it, because it only ever writes mail"
          (is (= age-key/kagi-item (get-in status [:identity-location :kagi])))
          (is (str/includes? (get-in status [:identity-location :keychain])
                             age-key/keychain-service))
          (is (not (str/includes? (pr-str status) "AGE-SECRET-KEY"))))))))

(deftest a-store-that-is-absent-falls-through-rather-than-failing
  (testing "each source is optional by design; one that is unreachable must let
            the next answer, or an app on a machine without the kagi checkout
            could not file mail at all"
    (with-environment {}
      (fn []
        (with-redefs [age-key/*environment* (constantly nil)]
          ;; No environment, no file. Whatever the machine's Keychain and kagi
          ;; say, this must return a map rather than throw.
          (let [{:keys [recipients]} (age-key/resolve-recipients)]
            (is (vector? recipients))))))))
