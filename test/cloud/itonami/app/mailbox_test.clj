(ns cloud.itonami.app.mailbox-test
  "Reading, starring, filing and getting mail out of the way.

  All of it goes through `mail.mailbox` — the labels, the trash rule, the
  search that reads a body. The app had been calling two of that library's
  functions and none of these, so what is under test is mostly whether the
  app asks the model the right questions and stores only the difference."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.mailbox :as app-mailbox]
            [cloud.itonami.app.store :as store]
            [cloud.itonami.app.workspace :as workspace]))

(def ^:private alice "person-alice")
(def ^:private bob "person-bob")

(def ^:private archive (atom nil))

(defn- write-message! [directory id from subject body]
  (spit (io/file directory id)
        (str "From: " from "\r\nSubject: " subject
             "\r\nMessage-ID: <" id "@example.com>\r\n\r\n" body)))

(use-fixtures
  :each
  (fn [run]
    (let [temporary (java.nio.file.Files/createTempDirectory
                     "cloud-itonami-app-mailbox"
                     (make-array java.nio.file.attribute.FileAttribute 0))
          root (.toFile temporary)
          inbox (io/file root "m365-archive/mail/受信トレイ")]
      (.mkdirs inbox)
      ;; Long enough that the last word is past the 220-character snippet:
      ;; the interface filtered that string and the model was handed it as
      ;; the body, so a word out here was findable by nothing.
      (write-message! inbox "20260728T010203Z_a.eml" "Example Person <sender@example.com>"
                      "進捗の確認"
                      (str "来週の進捗について確認します。"
                           (apply str (repeat 30 "細かい経緯をここに書きます。"))
                           "最後に予算の話。"))
      (write-message! inbox "20260727T010203Z_b.eml" "経理 <keiri@example.com>"
                      "請求書の件" "添付の請求書をご確認ください。")
      (reset! archive root)
      (with-redefs [workspace/workspace-root (constantly root)
                    store/transact! (fn [f & args] (apply swap! store/state f args))]
        (reset! store/state (store/initial-state))
        (workspace/clear-cache!)
        (run)))))

(defn- ids [result] (mapv :id (:items result)))
(def ^:private a "20260728T010203Z_a.eml")
(def ^:private b "20260727T010203Z_b.eml")

(deftest the-archive-arrives-read-because-the-archive-has-no-read-state
  ;; Files on disk carry no read state and these were read years ago in
  ;; another program. Marking one unread is a flag a person raises, not a
  ;; fact this app recovered — so the count starts at zero and is not a
  ;; claim that anything was checked.
  (let [seen (app-mailbox/view alice)]
    (is (= [a b] (ids seen)) "newest first")
    (is (every? :read? (:items seen)))
    (is (= 0 (:unread seen)))))

(deftest a-mark-is-one-persons-and-does-not-touch-the-archive
  (app-mailbox/set-read! a false alice)
  (is (= 1 (:unread (app-mailbox/view alice))))
  (testing "the next reader's inbox is untouched"
    (is (= 0 (:unread (app-mailbox/view bob)))))
  (testing "and the file is exactly where it was"
    (is (.isFile (io/file @archive "m365-archive/mail/受信トレイ" a)))
    (is (= 2 (count (:items (app-mailbox/view bob)))))))

(deftest starring-is-a-toggle-and-shows-up-as-a-place-to-look
  (app-mailbox/set-label! a "starred" true alice)
  (is (= ["inbox" "starred"] (:labels (first (:items (app-mailbox/view alice))))))
  (is (= [a] (ids (app-mailbox/view alice {:label "starred"}))))
  (testing "and off again"
    (app-mailbox/set-label! a "starred" false alice)
    (is (= [] (ids (app-mailbox/view alice {:label "starred"}))))))

(deftest a-label-of-your-own-is-a-label
  ;; `mail.mailbox` keeps the set of labels in play, which is how an
  ;; interface can offer one somebody invented without being told about it.
  (app-mailbox/set-label! b "経費" true alice)
  (is (= [b] (ids (app-mailbox/view alice {:label "経費"}))))
  (is (contains? (set (:labels (app-mailbox/view alice))) "経費")))

(deftest the-two-places-are-not-labels
  ;; `trash` means "off :inbox, onto :trash". If a client could also set
  ;; those directly there would be two ways to do one thing, and they could
  ;; disagree — a message in the inbox and in the trash at once.
  (doseq [reserved ["inbox" "trash"]]
    (is (= :mail/reserved-label
           (try (app-mailbox/set-label! a reserved true alice) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        reserved)))

(deftest trashing-takes-it-out-of-the-inbox-and-deletes-nothing
  (app-mailbox/set-trashed! a true alice)
  (is (= [b] (ids (app-mailbox/view alice))) "gone from the inbox")
  (is (= [a] (ids (app-mailbox/view alice {:label "trash"}))) "and findable in the trash")
  (is (.isFile (io/file @archive "m365-archive/mail/受信トレイ" a))
      "the archive is files this app reads and does not own")
  (testing "and it comes back"
    (app-mailbox/set-trashed! a false alice)
    (is (= [a b] (ids (app-mailbox/view alice))))))

(deftest search-reads-the-body-and-not-only-the-preview
  ;; The interface had been filtering the snippet — the first 220 characters
  ;; — so a word further in could not be found. `mailbox/search` reads the
  ;; envelope, the subject and every part.
  (is (= [a] (ids (app-mailbox/view alice {:query "予算"})))
      "which is past the snippet, and is why the message keeps the whole body")
  (is (not (re-find #"予算" (:snippet (first (:items (app-mailbox/view alice))))))
      "the row still shows only what a row has room for")
  (is (= [b] (ids (app-mailbox/view alice {:query "keiri@example.com"}))))
  (is (= [a] (ids (app-mailbox/view alice {:query "進捗"}))))
  (testing "with a filter as well as a needle"
    (app-mailbox/set-read! a false alice)
    (is (= [a] (ids (app-mailbox/view alice {:unread? true}))))
    (is (= [] (ids (app-mailbox/view alice {:unread? true :query "請求書"}))))))

(deftest a-conversation-can-be-asked-for
  (let [thread (:thread (first (:items (app-mailbox/view alice))))]
    (is (string? thread))
    (is (= [a] (ids (app-mailbox/thread thread alice))))))

(deftest a-mark-on-a-message-that-is-not-there-is-refused
  ;; Otherwise any string would be stored as a mark forever, and nothing
  ;; would ever clear it.
  (is (= :mail/not-found
         (try (app-mailbox/set-read! "no-such.eml" false alice) nil
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (is (= {} (get-in @store/state [:mail :marks alice] {}))))

(deftest a-mark-outlives-the-message-it-was-on-without-resurrecting-it
  ;; An archive can lose a file — annexed, dropped, moved. The mark stays in
  ;; the store; laying it over a box that has no such message must not
  ;; conjure an entry with a label and no mail in it, which is what
  ;; `add-label` on an unknown id would produce.
  (app-mailbox/set-label! a "starred" true alice)
  (.delete (io/file @archive "m365-archive/mail/受信トレイ" a))
  (workspace/clear-cache!)
  (let [seen (app-mailbox/view alice)]
    (is (= [b] (ids seen)))
    (is (every? :subject (:items seen)) "no entry with a label and no message")))

;; --- the archive and the accounts, in one box -------------------------------
;;
;; The bug this closes: `mail-sync` pulled Gmail into the store every minute
;; and the inbox was built only out of the on-disk archive, so mail arrived,
;; was parsed, was labelled, was written — and was visible to nobody.
;; Connecting Google appeared to do nothing because from outside it did
;; nothing.

(defn- put-synced!
  [{:keys [id account-id subject from-email body read? received-at thread-id]}]
  (swap! store/state assoc-in [:mail :messages id]
         {:id id
          :account-id (or account-id "gmail:1001")
          :kind :gmail
          :account-address "alice@work.example"
          :provider-message-id (str/replace id #"^[^|]*\|" "")
          :thread-id (or thread-id id)
          :subject subject
          :from "Someone"
          :from-email from-email
          :body body
          :snippet (subs body 0 (min 220 (count body)))
          :labels #{:inbox}
          :read? (boolean read?)
          :received-at (or received-at "2026-08-01T00:00:00Z")
          :size-bytes (count body)}))

(deftest a-synced-message-appears-in-the-inbox
  (put-synced! {:id "gmail:1001|m1" :subject "同期されたメール"
                :from-email "sync@example.com"
                :body "これは Gmail から同期されたメールです。"})
  (let [seen (app-mailbox/view alice)]
    (is (contains? (set (ids seen)) "gmail:1001|m1")
        "a message that syncs is a message that shows up — the whole point")
    (is (= 3 (count (:items seen)))
        "beside the two archived ones, not instead of them")))

(deftest a-synced-message-keeps-the-read-state-its-provider-reported
  (testing "the archive hardcodes read? true because files carry no read
            state; a synced message genuinely has one and must not inherit
            the archive's assumption"
    (put-synced! {:id "gmail:1001|unread" :subject "未読"
                  :from-email "a@example.com" :body "本文" :read? false})
    (put-synced! {:id "gmail:1001|read" :subject "既読"
                  :from-email "b@example.com" :body "本文" :read? true})
    (is (= 1 (:unread (app-mailbox/view alice))))))

(deftest a-synced-message-is-searched-by-its-body-not-its-snippet
  (put-synced! {:id "gmail:1001|long" :subject "件名"
                :from-email "a@example.com"
                :body (str (apply str (repeat 30 "前置きの文章です。"))
                           "決算の締め切り")})
  (is (= ["gmail:1001|long"]
         (ids (app-mailbox/view alice {:query "決算の締め切り"})))
      "past the 220-character snippet, which is what the interface used to
       filter on"))

(deftest two-accounts-messages-do-not-collide-by-provider-id
  (testing "Gmail message ids are unique within a mailbox, not across them —
            keyed by provider alone, one account's message silently
            overwrote the other's"
    (put-synced! {:id "gmail:1001|same" :account-id "gmail:1001"
                  :subject "仕事のメール" :from-email "work@example.com"
                  :body "work"})
    (put-synced! {:id "gmail:1002|same" :account-id "gmail:1002"
                  :subject "個人のメール" :from-email "home@example.com"
                  :body "home"})
    (let [seen (app-mailbox/view alice)]
      (is (= #{"gmail:1001|same" "gmail:1002|same"}
             (set (filter #(str/ends-with? % "|same") (ids seen))))
          "both mailboxes' messages, in one inbox"))))

(deftest a-synced-message-can-be-marked
  (testing "the mark check used to consult the archive alone, so marking a
            synced message was refused as そのメールはありません"
    (put-synced! {:id "gmail:1001|markable" :subject "印"
                  :from-email "a@example.com" :body "本文" :read? true})
    (app-mailbox/set-read! "gmail:1001|markable" false alice)
    (is (= 1 (:unread (app-mailbox/view alice))))
    (app-mailbox/set-label! "gmail:1001|markable" "starred" true alice)
    (let [message (first (filter #(= "gmail:1001|markable" (:id %))
                                 (:items (app-mailbox/view alice))))]
      (is (contains? (set (:labels message)) "starred")))))

(deftest a-synced-message-and-an-archived-one-thread-independently
  (put-synced! {:id "gmail:1001|t1" :thread-id "thread-x" :subject "一通目"
                :from-email "a@example.com" :body "one"})
  (put-synced! {:id "gmail:1001|t2" :thread-id "thread-x" :subject "二通目"
                :from-email "a@example.com" :body "two"})
  (is (= #{"gmail:1001|t1" "gmail:1001|t2"}
         (set (ids (app-mailbox/thread "thread-x" alice))))))

(deftest a-synced-message-carries-what-a-reply-needs
  (testing "the reply button prefills the account to send from and the
            Message-ID to thread against; without either, a reply is a new
            conversation sent from whichever mailbox the interface guessed"
    (swap! store/state assoc-in [:mail :messages "gmail:1001|reply-me"]
           {:id "gmail:1001|reply-me"
            :account-id "gmail:1001"
            :kind :gmail
            :account-address "alice@work.example"
            :provider-message-id "reply-me"
            :thread-id "thread-r"
            :message-id "<original@example.com>"
            :subject "見積もりの件"
            :from "Sender" :from-email "sender@example.com"
            :body "本文" :snippet "本文"
            :labels #{:inbox} :read? true
            :received-at "2026-08-01T00:00:00Z" :size-bytes 4})
    (let [item (first (filter #(= "gmail:1001|reply-me" (:id %))
                              (:items (app-mailbox/view alice))))]
      (is (= "gmail:1001" (:account-id item)))
      (is (= "<original@example.com>" (:message-id item)))
      (is (= "thread-r" (:thread item)))))
  (testing "an archived .eml has neither — it belongs to no live account and
            there is nothing to send a reply from"
    (let [archived (first (filter #(= a (:id %)) (:items (app-mailbox/view alice))))]
      (is (nil? (:account-id archived))))))
