(ns cloud.itonami.app.mail-pop3-test
  "Reading a maildrop that offers neither OAuth nor IMAP.

  What is pinned here is mostly the three ways POP3 differs from the other
  two protocols, because those are the ways a client built on IMAP habits
  gets it wrong: message numbers that do not survive the session, reading
  that must not delete, and a QUIT that is not a courtesy."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mail-pop3 :as mail-pop3]
            [pop3.transport :as transport]))

(defn- binary
  "A Clojure string as the binary string a real transport delivers.

  `pop3.transport` decodes reads ISO-8859-1, one byte per character, which
  is `org-ietf-mime`'s stated input contract — so a fake handing over a
  UTF-8 Clojure string is not modelling the real one, and a test built on
  that passes or fails for the wrong reason."
  [s]
  (String. (.getBytes ^String s "UTF-8") "ISO-8859-1"))

(defn- fake-transport [script]
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
       (close! [_] (swap! written conj :closed)))}))

(def ^:private account
  {:id "pop3:me@example.com@pop.example.com"
   :kind :pop3
   :address "me@example.com"
   :pop3 {:host "pop.example.com" :port 995 :username "me@example.com"}})

(defn- sync-script
  "greeting, CAPA, USER/PASS, LIST, UIDL, one RETR per wanted message, QUIT."
  [messages]
  (concat
   ["+OK POP3 ready"
    "-ERR unknown command"                       ; no CAPA: a plain server
    "+OK user" "+OK pass"
    "+OK scan listing follows"]
   (map-indexed (fn [i m] (str (inc i) " " (count (binary m)))) messages)
   ["."
    "+OK unique-id listing follows"]
   (map-indexed (fn [i _] (str (inc i) " uid-" (inc i))) messages)
   ["."]
   (mapcat (fn [m] (concat ["+OK octets"]
                           (str/split (binary m) #"\r\n")
                           ["."]))
           messages)
   ["+OK bye"]))

(deftest a-maildrop-that-offers-nothing-but-pop3-can-be-read
  (let [source (str "From: Example Person <sender@example.com>\r\n"
                    "Subject: =?UTF-8?B?6YCy5o2X44Gu56K66KqN?=\r\n"
                    "Content-Type: text/plain; charset=UTF-8\r\n"
                    "Content-Transfer-Encoding: base64\r\n"
                    "\r\n"
                    "5p2l6YCx44Gu6YCy5o2X44Gr44Gk44GE44Gm44CC\r\n")
        {:keys [transport]} (fake-transport (sync-script [source]))
        {:keys [messages]} (mail-pop3/sync! account {:transport transport
                                                     :password "p"})
        message (first messages)]
    (is (= 1 (count messages)))
    (is (= "進捗の確認" (:subject message)) "RFC 2047, decoded")
    (is (= "来週の進捗について。" (str/trim (:body message))) "base64, decoded")
    (is (= "Example Person" (:from message)))
    (is (= "sender@example.com" (:from-email message)))))

(deftest the-message-id-is-the-uidl-not-the-session-number
  (testing "numbers renumber the moment anything is deleted, so a client that
            keys on them attributes every later message to the wrong one"
    (let [source "From: a@example.com\r\nSubject: s\r\n\r\nbody\r\n"
          {:keys [transport]} (fake-transport (sync-script [source]))
          message (first (:messages (mail-pop3/sync! account
                                                     {:transport transport
                                                      :password "p"})))]
      (is (= "uid-1" (:provider-message-id message))))))

(deftest a-message-this-app-already-has-is-not-downloaded-again
  (testing "POP3 has no 'what is new', so the only way to avoid refetching
            the whole maildrop every minute is to say what is already held"
    (let [source "From: a@example.com\r\nSubject: s\r\n\r\nbody\r\n"
          ;; Two listed, one already known: only the unknown one is RETRieved,
          ;; so the script carries exactly one message body.
          script (concat
                  ["+OK ready" "-ERR no capa" "+OK user" "+OK pass"
                   "+OK scan listing follows" "1 40" "2 40" "."
                   "+OK uidl follows" "1 uid-1" "2 uid-2" "."]
                  ["+OK octets"] (str/split (binary source) #"\r\n") ["."]
                  ["+OK bye"])
          {:keys [transport written]} (fake-transport script)
          {:keys [messages]} (mail-pop3/sync! account
                                              {:transport transport
                                               :password "p"
                                               :known-uids ["uid-1"]})]
      (is (= 1 (count messages)))
      (is (= ["RETR 2\r\n"] (filter #(str/starts-with? (str %) "RETR") @written))
          "only the one this app did not have"))))

(deftest reading-never-deletes
  (testing "POP3's historical default removed the server's only copy as a
            side effect of RETR, which destroys the mailbox for every other
            client the account is opened in"
    (let [source "From: a@example.com\r\nSubject: s\r\n\r\nbody\r\n"
          {:keys [transport written]} (fake-transport (sync-script [source]))]
      (mail-pop3/sync! account {:transport transport :password "p"})
      (is (not-any? #(str/starts-with? (str %) "DELE") @written)))))

(deftest quit-is-sent-because-it-releases-the-maildrop-lock
  (testing "dropping the socket leaves the mailbox locked until the server
            times the session out, refusing every other client meanwhile"
    (let [{:keys [transport written]}
          (fake-transport ["+OK ready" "-ERR no capa" "+OK user" "+OK pass"
                           "+OK listing" "." "+OK uidl" "." "+OK bye"])]
      (mail-pop3/sync! account {:transport transport :password "p"})
      (is (some #(= "QUIT\r\n" %) @written))
      (is (= :closed (last @written))))))

(deftest a-missing-password-is-refused-before-a-socket-is-opened
  (let [error (try (mail-pop3/sync! account {:password ""})
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :mail/missing-credential (:type error)))
    (is (= (:id account) (:id error)))))
