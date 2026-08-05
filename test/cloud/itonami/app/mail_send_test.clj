(ns cloud.itonami.app.mail-send-test
  "Sending, which this app could not do at all.

  Not *could not do well* — there was no code anywhere in it that delivered a
  message to anybody, while the inbox it served had a reply affordance and the
  archive it read was full of conversations.

  The two things worth pinning are the ones that are invisible until a real
  message goes wrong: that a malformed message is refused here rather than at
  a provider, and that a Japanese subject line survives the trip. A raw UTF-8
  header is not legal in RFC 2822 and providers disagree about what to do with
  one — some pass it, some mangle it, some refuse the message — so a subject
  that reads 見積もりの件 locally and arrives as mojibake is the ordinary
  failure here, not an exotic one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-send :as mail-send]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.mail-imap :as mail-imap]
            [smtp.client])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(use-fixtures
  :each
  (fn [run]
    (let [before @store/state]
      (try
        (reset! store/state (store/initial-state))
        ;; In memory: this suite has no business writing state.edn, and a
        ;; fresh worktree has no target/test-data for it to write into.
        (with-redefs [store/transact! (fn [f & args]
                                        (apply swap! store/state f args))]
          (account/configure! {})
          (run))
        (finally (reset! store/state before))))))

(def ^:private imap-account
  {:id "imap:me@example.com@imap.example.com"
   :kind :imap
   :address "me@example.com"
   :user-did "did:key:alice"
   :imap {:host "imap.example.com" :port 993 :username "me@example.com"}
   :smtp {:host "smtp.example.com" :port 465 :username "me@example.com"}})

(defn- with-account [account f]
  (with-redefs [account/accounts (fn ([] [account]) ([_did] [account]))]
    (f)))

(defn- sent-payloads
  "Capture what the SMTP route would put on the wire, without a socket."
  [request]
  (let [sent (atom [])]
    (with-redefs [account/password (constantly "app-password")
                  smtp.client/connect! (fn [_host _opts] {:transport ::fake})
                  smtp.client/ehlo! (fn [session _domain] session)
                  smtp.client/authenticate! (fn [session _opts] session)
                  smtp.client/send-mail! (fn [session message]
                                           (swap! sent conj
                                                  (assoc message
                                                         :from (:from session)))
                                           (assoc session
                                                  :accepted (vec (concat (:to message)
                                                                         (:cc message)))
                                                  :rejected []))
                  smtp.client/quit! (constantly nil)]
      (with-account imap-account
        #(let [result (mail-send/send! (:id imap-account) request
                                       {:user-did "did:key:alice"})]
           {:result result :sent @sent})))))

;; ---------------------------------------------------------------------------

(deftest a-message-is-refused-here-rather-than-at-the-provider
  (testing "a bad recipient comes back naming the field, not as a 400 from
            somebody else's API with a body nobody reads"
    (with-account imap-account
      #(let [error (try (mail-send/send! (:id imap-account)
                                         {:to "not-an-address"
                                          :subject "s" :text "t"}
                                         {:user-did "did:key:alice"})
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :mail/invalid-message (:type error)))
         (is (seq (:errors error))))))
  (testing "and a message with no recipient at all"
    (with-account imap-account
      #(is (thrown? clojure.lang.ExceptionInfo
                    (mail-send/send! (:id imap-account)
                                     {:subject "s" :text "t"}
                                     {:user-did "did:key:alice"}))))))

(deftest sending-to-an-unknown-account-names-it
  (with-account imap-account
    #(let [error (try (mail-send/send! "gmail:nobody"
                                       {:to "a@example.com" :subject "s"
                                        :text "t"}
                                       {:user-did "did:key:alice"})
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
       (is (= :mail/unknown-account (:type error))))))

(deftest a-japanese-subject-survives-as-an-encoded-word
  (let [{:keys [sent]} (sent-payloads {:to "friend@example.com"
                                       :subject "見積もりの件"
                                       :text "よろしくお願いします。"})
        subject (second (re-find #"Subject: (.*)\r\n" (:raw (first sent))))]
    (is (str/starts-with? subject "=?UTF-8?B?")
        "RFC 2047, because a raw UTF-8 header is not legal and providers
         disagree about what to do with one")
    (testing "and it decodes back to what was typed"
      (let [encoded (second (re-find #"=\?UTF-8\?B\?([^?]+)\?=" subject))]
        (is (= "見積もりの件"
               (String. (.decode (Base64/getDecoder) ^String encoded)
                        StandardCharsets/UTF_8)))))))

(deftest an-ascii-subject-is-left-alone
  (testing "encoding one that does not need it makes every message unreadable
            in clients that show raw headers, for nothing"
    (let [{:keys [sent]} (sent-payloads {:to "friend@example.com"
                                         :subject "lunch?" :text "hi"})]
      (is (re-find #"Subject: lunch\?\r\n" (:raw (first sent)))))))

(deftest every-recipient-goes-in-one-transaction
  (testing "one send per recipient is not one message delivered three times:
            each copy carries only its own address in the header, so nobody
            can see who else received it and a reply-all reaches one person.
            RFC 5321 §3.3 has one MAIL FROM and one or more RCPT TO"
    (let [{:keys [sent]} (sent-payloads {:to "a@example.com, b@example.com"
                                         :cc "c@example.com"
                                         :subject "s" :text "t"})]
      (is (= 1 (count sent)) "one transaction")
      (is (= ["a@example.com" "b@example.com"] (:to (first sent))))
      (is (= ["c@example.com"] (:cc (first sent))))
      (testing "and every recipient is named in the message, so a reply-all
                reaches all of them"
        (is (re-find #"To: a@example.com, b@example.com\r\n" (:raw (first sent))))
        (is (re-find #"Cc: c@example.com\r\n" (:raw (first sent))))))))

(deftest the-from-address-is-the-accounts-own
  (testing "and it is the address string, not `mail.message`'s address map —
            interpolating the map yields a well-formed header containing a
            Clojure map, which every provider rejects"
    (let [{:keys [sent]} (sent-payloads {:to "a@example.com" :subject "s"
                                         :text "t"})]
      (is (= "me@example.com" (:from (first sent))))
      (is (re-find #"From: me@example.com\r\n" (:raw (first sent))))
      (is (not (str/includes? (str (:raw (first sent))) ":mail.address"))))))

(deftest a-sent-message-is-recorded-with-an-id-that-says-where-it-came-from
  (testing "`mail.receipt` insists on a provider message-id and is right to —
            a receipt whose id is nil cannot be matched to anything later. SMTP
            does not hand one back, so the locally-minted id says so rather
            than looking like an id the provider would recognise"
    (let [{:keys [result]} (sent-payloads {:to "a@example.com" :subject "s"
                                           :text "t"})]
      (is (true? (:ok? result)))
      (is (str/starts-with? (:id result) "local:"))
      (testing "and it is in the store, attributed to the account that sent it"
        (let [record (last (get-in (store/snapshot) [:mail :sent]))]
          (is (= (:id imap-account) (:account-id record)))
          (is (= ["a@example.com"] (:to record))))))))

(deftest a-missing-smtp-password-is-refused-before-a-socket-is-opened
  (with-redefs [account/password (constantly nil)]
    (with-account imap-account
      #(let [error (try (mail-send/send! (:id imap-account)
                                         {:to "a@example.com" :subject "s"
                                          :text "t"}
                                         {:user-did "did:key:alice"})
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
         (is (= :mail/missing-credential (:type error)))))))

;; --- the copy in the sender's own Sent folder --------------------------------

(def ^:private pop3-account
  {:id "pop3:me@example.com@pop.example.com"
   :kind :pop3
   :address "me@example.com"
   :user-did "did:key:alice"
   :pop3 {:host "pop.example.com" :port 995 :username "me@example.com"}
   :smtp {:host "smtp.example.com" :port 465 :username "me@example.com"}})

(deftest an-imap-account-files-a-copy-into-its-sent-folder
  (testing "without it the mail exists at the recipient and nowhere in the
            sender's own mailbox, so every other client the account is opened
            in shows an empty Sent folder for everything sent from here"
    (let [appended (atom nil)]
      (with-redefs [account/password (constantly "app-password")
                    smtp.client/connect! (fn [_h _o] {:transport ::fake})
                    smtp.client/ehlo! (fn [s _d] s)
                    smtp.client/authenticate! (fn [s _o] s)
                    smtp.client/send-mail! (fn [s m] (assoc s :accepted (:to m) :rejected []))
                    smtp.client/quit! (constantly nil)
                    mail-imap/append-sent! (fn [_account raw]
                                             (reset! appended raw)
                                             {:appended? true :mailbox "INBOX/Sent"})]
        (with-account imap-account
          #(let [result (mail-send/send! (:id imap-account)
                                         {:to "a@example.com" :subject "件名"
                                          :text "本文"}
                                         {:user-did "did:key:alice"})]
             (is (= "INBOX/Sent" (get-in result [:sent-copy :mailbox])))
             (testing "and what is filed is the same message that was sent"
               (is (re-find #"To: a@example.com" @appended))
               (is (re-find #"Subject: =\?UTF-8\?B\?" @appended)))))))))

(deftest a-failed-sent-copy-does-not-fail-the-send
  (testing "the message has already left; refusing to return would tell the
            caller their mail did not go when it did"
    (with-redefs [account/password (constantly "app-password")
                  smtp.client/connect! (fn [_h _o] {:transport ::fake})
                  smtp.client/ehlo! (fn [s _d] s)
                  smtp.client/authenticate! (fn [s _o] s)
                  smtp.client/send-mail! (fn [s m] (assoc s :accepted (:to m) :rejected []))
                  smtp.client/quit! (constantly nil)
                  mail-imap/append-sent! (fn [_ _] (throw (ex-info "IMAP down" {})))]
      (with-account imap-account
        #(let [result (mail-send/send! (:id imap-account)
                                       {:to "a@example.com" :subject "s" :text "t"}
                                       {:user-did "did:key:alice"})]
           (is (true? (:ok? result)) "the send still succeeded")
           (is (false? (get-in result [:sent-copy :appended?])))
           (is (= :append-failed (get-in result [:sent-copy :reason]))))))))

(deftest a-pop3-account-can-send
  (testing "POP3 reads over POP3 and sends over SMTP — `add-imap-account!`
            gives both kinds the same :smtp block. Without a :pop3 clause
            `case` threw `No matching clause`, so a POP3 account could be
            registered and never sent from"
    (let [sent (atom [])]
      (with-redefs [account/password (constantly "app-password")
                    smtp.client/connect! (fn [_h _o] {:transport ::fake})
                    smtp.client/ehlo! (fn [s _d] s)
                    smtp.client/authenticate! (fn [s _o] s)
                    smtp.client/send-mail! (fn [s m]
                                             (swap! sent conj m)
                                             (assoc s :accepted (:to m) :rejected []))
                    smtp.client/quit! (constantly nil)]
        (with-account pop3-account
          #(let [result (mail-send/send! (:id pop3-account)
                                         {:to "a@example.com" :subject "s" :text "t"}
                                         {:user-did "did:key:alice"})]
             (is (true? (:ok? result)))
             (is (= ["a@example.com"] (:to (first @sent))))
             (testing "and no Sent copy is attempted — POP3 has no folders"
               (is (nil? (:sent-copy result))))))))))
