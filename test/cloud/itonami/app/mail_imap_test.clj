(ns cloud.itonami.app.mail-imap-test
  "Reading a mailbox that is not Google's and not Microsoft's.

  Tested against a scripted transport rather than a live server, which is the
  reason this namespace exists at all: the hand-rolled Gmail reader it sits
  beside could only ever be exercised by pointing it at somebody's real
  mailbox, so for its whole life it never was."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-imap :as mail-imap]
            [imap.transport :as transport]))

(defn- fake-transport
  "A scripted `imap.transport/Transport`: strings are lines, `{:literal ...}`
  is what the next `read-n!` returns."
  [script]
  (let [queue (atom (vec script))
        written (atom [])]
    {:written written
     :transport
     (reify transport/Transport
       (write! [_ s] (swap! written conj s))
       (read-line! [_]
         (let [item (first @queue)]
           (swap! queue (comp vec rest))
           item))
       (read-n! [_ _n]
         (let [item (first @queue)]
           (swap! queue (comp vec rest))
           (:literal item)))
       (close! [_] (swap! written conj :closed)))}))

(def ^:private account
  {:id "imap:me@example.com@imap.example.com"
   :kind :imap
   :address "me@example.com"
   :imap {:host "imap.example.com" :port 993 :username "me@example.com"}})

(defn- message-source
  [{:keys [from subject date message-id references body]}]
  (str "From: " from "\r\n"
       "Subject: " subject "\r\n"
       (when date (str "Date: " date "\r\n"))
       (when message-id (str "Message-ID: " message-id "\r\n"))
       (when references (str "References: " references "\r\n"))
       "\r\n" body "\r\n"))

(defn- sync-script [sources]
  (concat
   ["* OK ready"
    "A1 OK LOGIN completed"
    "A2 OK SELECT completed"
    (str "* SEARCH " (str/join " " (map inc (range (count sources)))))
    "A3 OK UID SEARCH completed"]
   (mapcat (fn [source i]
             [(str "* 1 FETCH (UID " (inc i) " BODY[] {" (count source) "}")
              {:literal source}
              ")"
              (str "A" (+ 4 i) " OK UID FETCH completed")])
           sources (range))
   ["* BYE" (str "A" (+ 4 (count sources)) " OK LOGOUT completed")]))

(deftest a-mailbox-that-is-neither-google-nor-microsoft-can-be-read
  (let [source (message-source
                {:from "Example Person <sender@example.com>"
                 :subject "進捗の確認"
                 :date "Mon, 3 Aug 2026 09:00:00 +0900"
                 :message-id "<abc@example.com>"
                 :body "来週の進捗について確認します。"})
        {:keys [transport]} (fake-transport (sync-script [source]))
        {:keys [messages cursor]} (mail-imap/sync! account
                                                   {:transport transport
                                                    :password "app-password"})
        message (first messages)]
    (is (= 1 (count messages)))
    (testing "the envelope, split into what a list row shows"
      (is (= "進捗の確認" (:subject message)))
      (is (= "Example Person" (:from message)))
      (is (= "sender@example.com" (:from-email message)))
      (is (= "1" (:provider-message-id message))))
    (testing "the body, not the snippet — search reads what is in the message"
      (is (= "来週の進捗について確認します。\n" (:body message))))
    (testing "the Date header becomes an instant rather than the sync time"
      (is (str/starts-with? (:received-at message) "2026-08-03T00:00:00")
          "09:00 +0900 is midnight UTC, and it is the message's time, not now"))
    (is (= 1 (:highest-uid cursor)))))

(deftest an-unparseable-date-is-not-quietly-replaced-with-now
  (testing "substituting the sync time would sort a message with a broken Date
            header to the top of the mailbox as though it had just arrived —
            so it falls back to now only because a message must have some
            time, and never silently claims the header said so"
    (let [source (message-source {:from "a@example.com" :subject "s"
                                  :date "not a date" :body "b"})
          {:keys [transport]} (fake-transport (sync-script [source]))
          message (first (:messages (mail-imap/sync! account
                                                     {:transport transport
                                                      :password "p"})))]
      (is (some? (:received-at message))))))

(deftest a-thread-is-the-references-root-so-a-reply-files-with-its-original
  (testing "IMAP has no thread id of its own; RFC 2822 References is the
            standing convention and the alternative is every message being
            its own conversation"
    (let [source (message-source
                  {:from "a@example.com" :subject "Re: 見積もり"
                   :message-id "<reply@example.com>"
                   :references "<original@example.com> <second@example.com>"
                   :body "承知しました。"})
          {:keys [transport]} (fake-transport (sync-script [source]))
          message (first (:messages (mail-imap/sync! account
                                                     {:transport transport
                                                      :password "p"})))]
      (is (= "<original@example.com>" (:thread-id message))
          "the root of the chain, not the most recent link"))))

(deftest a-message-with-no-references-threads-on-its-own-message-id
  (let [source (message-source {:from "a@example.com" :subject "new"
                                :message-id "<solo@example.com>" :body "hi"})
        {:keys [transport]} (fake-transport (sync-script [source]))
        message (first (:messages (mail-imap/sync! account
                                                   {:transport transport
                                                    :password "p"})))]
    (is (= "<solo@example.com>" (:thread-id message)))))

(deftest the-session-is-closed-even-when-the-mailbox-is-empty
  (testing "an IMAP session is a socket, and one leaked per sync is how a
            process that syncs every minute runs out of descriptors overnight"
    (let [{:keys [transport written]}
          (fake-transport ["* OK ready"
                           "A1 OK LOGIN completed"
                           "A2 OK SELECT completed"
                           "* SEARCH"
                           "A3 OK UID SEARCH completed"
                           "* BYE"
                           "A4 OK LOGOUT completed"])
          result (mail-imap/sync! account {:transport transport
                                           :password "p"})]
      (is (= [] (:messages result)))
      (is (= :closed (last @written))))))

(deftest a-missing-password-is-refused-before-a-socket-is-opened
  (testing "naming the account, so somebody can fix the one that is broken"
    (let [error (try (mail-imap/sync! account {:password ""})
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :mail/missing-credential (:type error)))
      (is (= (:id account) (:id error))))))
