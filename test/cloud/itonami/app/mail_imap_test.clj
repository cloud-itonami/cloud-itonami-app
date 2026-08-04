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

(defn- binary
  "A Clojure string as the binary string a real transport delivers.

  `imap.transport` decodes reads ISO-8859-1, one byte per character, which
  is `org-ietf-mime`'s stated input contract — so a fake transport handing
  over a UTF-8 Clojure string is not modelling the real one, and a test
  built on it passes or fails for the wrong reason. It also makes `count`
  the byte count, which is what an IMAP literal announces."
  [s]
  (String. (.getBytes ^String s "UTF-8") "ISO-8859-1"))

(defn- message-source
  [{:keys [from subject date message-id references body]}]
  (str "From: " from "\r\n"
       "Subject: " subject "\r\n"
       (when date (str "Date: " date "\r\n"))
       (when message-id (str "Message-ID: " message-id "\r\n"))
       (when references (str "References: " references "\r\n"))
       "\r\n" body "\r\n"))

(defn- sync-script
  "The wire script for one `sync!`: greeting, CAPABILITY, LOGIN, EXAMINE,
  UID SEARCH ALL, one UID FETCH per message, LOGOUT.

  Sources are converted to binary strings first, because that is what the
  real transport delivers."
  [sources]
  (let [sources (mapv binary sources)]
   (concat
   ["* OK ready"
    "* CAPABILITY IMAP4rev1"
    "A1 OK CAPABILITY completed"
    "A2 OK LOGIN completed"
    "* OK [UIDVALIDITY 3857529045] UIDs valid"
    "* OK [UIDNEXT 99] next"
    "A3 OK [READ-ONLY] EXAMINE completed"
    (str "* SEARCH " (str/join " " (map inc (range (count sources)))))
    "A4 OK UID SEARCH completed"]
   (mapcat (fn [source i]
             [(str "* 1 FETCH (UID " (inc i) " FLAGS (\\Seen) RFC822.SIZE "
                   (count source) " BODY[] {" (count source) "}")
              {:literal source}
              ")"
              (str "A" (+ 5 i) " OK UID FETCH completed")])
           sources (range))
   ["* BYE" (str "A" (+ 5 (count sources)) " OK LOGOUT completed")])))

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
      (is (= "来週の進捗について確認します。" (str/trim (:body message)))))
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

(deftest a-multipart-message-shows-its-text-not-its-mime-boundaries
  (testing "the whole reason this parses through org-ietf-mime. The previous
            version read a body the IMAP library had 'decoded' itself, which
            returned a multipart/* body raw -- boundaries, per-part headers,
            base64 of every attachment -- and that is what an inbox would
            have shown for any mail with an attachment or an HTML half"
    (let [source (str "From: Sender <sender@example.com>\r\n"
                      "Subject: =?UTF-8?B?6KuL5rGC5pu444Gu5Lu2?=\r\n"
                      "Content-Type: multipart/mixed; boundary=outer\r\n"
                      "\r\n"
                      "--outer\r\n"
                      "Content-Type: multipart/alternative; boundary=inner\r\n"
                      "\r\n"
                      "--inner\r\n"
                      "Content-Type: text/plain; charset=UTF-8\r\n"
                      "Content-Transfer-Encoding: base64\r\n"
                      "\r\n"
                      "44GU56K66KqN44GP44Gg44GV44GE44CC\r\n"
                      "--inner--\r\n"
                      "--outer\r\n"
                      "Content-Type: application/pdf\r\n"
                      "Content-Disposition: attachment;"
                      " filename*=UTF-8''%E8%AB%8B%E6%B1%82%E6%9B%B8.pdf\r\n"
                      "\r\n"
                      "PDFBYTES\r\n"
                      "--outer--\r\n")
          {:keys [transport]} (fake-transport (sync-script [source]))
          message (first (:messages (mail-imap/sync! account
                                                     {:transport transport
                                                      :password "p"})))]
      (testing "the text part, decoded from base64, not the raw multipart"
        (is (= "ご確認ください。" (str/trim (:body message))))
        (is (not (str/includes? (:body message) "--outer"))))
      (testing "the RFC 2047 subject, decoded"
        (is (= "請求書の件" (:subject message))))
      (testing "and the attachment, with its RFC 2231 filename"
        (is (= ["請求書.pdf"] (mapv :filename (:attachments message))))))))

(deftest the-read-state-comes-from-the-servers-own-flags
  (testing "assuming was how every synced message arrived unread and
            overwrote what somebody had actually done in another client"
    (let [source (message-source {:from "a@example.com" :subject "s" :body "b"})
          {:keys [transport]} (fake-transport (sync-script [source]))
          message (first (:messages (mail-imap/sync! account
                                                     {:transport transport
                                                      :password "p"})))]
      (is (true? (:read? message)) "the script says FLAGS (\\Seen)"))))

(deftest the-cursor-carries-uidvalidity
  (testing "RFC 3501 §2.3.1.1: a stored UID means nothing without it, and a
            server that reissues it makes every cached UID name a different
            message -- silently"
    (let [source (message-source {:from "a@example.com" :subject "s" :body "b"})
          {:keys [transport]} (fake-transport (sync-script [source]))
          {:keys [cursor]} (mail-imap/sync! account {:transport transport
                                                     :password "p"})]
      (is (= 3857529045 (:uidvalidity cursor)))
      (is (= 99 (:uidnext cursor))))))

(deftest the-session-is-closed-even-when-the-mailbox-is-empty
  (testing "an IMAP session is a socket, and one leaked per sync is how a
            process that syncs every minute runs out of descriptors overnight"
    (let [{:keys [transport written]}
          (fake-transport ["* OK ready"
                           "* CAPABILITY IMAP4rev1"
                           "A1 OK CAPABILITY completed"
                           "A2 OK LOGIN completed"
                           "A3 OK [READ-ONLY] EXAMINE completed"
                           "* SEARCH"
                           "A4 OK UID SEARCH completed"
                           "* BYE"
                           "A5 OK LOGOUT completed"])
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
