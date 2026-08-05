(ns cloud.itonami.app.messenger-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.messenger :as messenger]
            [cloud.itonami.app.store :as store])
  (:import [java.security KeyPairGenerator SecureRandom Signature]
           [java.util Base64]))

(def previous-state (atom nil))
(def temporary (atom nil))

(use-fixtures
  :each
  (fn [test-fn]
    (let [before @store/state
          directory (.toFile
                     (java.nio.file.Files/createTempDirectory
                      "cloud-itonami-messenger"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
      (reset! previous-state before)
      (reset! temporary directory)
      (try
        (reset! store/state (store/initial-state))
        (with-redefs [config/data-dir (constantly directory)] (test-fn))
        (finally (reset! store/state before))))))

(def principals
  {"human:alice" {:id "human:alice" :name "Alice" :kind "human"}
   "human:bob" {:id "human:bob" :name "Bob" :kind "human"}
   "agent:ops" {:id "agent:ops" :name "Ops agent" :kind "agent"}})

(defn- random-bytes [length]
  (let [value (byte-array length)] (.nextBytes (SecureRandom.) value) value))

(defn- b64 [value]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) value))

(defn- device-request []
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        public-encoded (.getEncoded (.getPublic pair))
        public-raw (java.util.Arrays/copyOfRange public-encoded
                                                (- (alength public-encoded) 32)
                                                (alength public-encoded))
        signed-prekey (random-bytes 32)
        signer (doto (Signature/getInstance "Ed25519")
                 (.initSign (.getPrivate pair))
                 (.update signed-prekey))]
    {:device-id "browser-one"
     :identity-signing-key (b64 public-raw)
     :identity-key (b64 (random-bytes 32))
     :signed-prekey-id 7
     :signed-prekey (b64 signed-prekey)
     :signed-prekey-signature (b64 (.sign signer))
     :one-time-prekeys [{:id 11 :key (b64 (random-bytes 32))}
                        {:id 12 :key (b64 (random-bytes 32))}]}))

(defn- conversation [members]
  (messenger/create-conversation!
   "acme" "human:alice" principals
   {:kind (if (= 1 (count members)) "direct" "group")
    :title "Operations" :members members}))

(deftest delivery-is-per-mailbox-and-deny-by-default
  (let [c (conversation ["human:bob"])
        id (:conversation/id c)
        sent (messenger/send-message! "acme" "human:alice" id
                                      {:content "private plan"})]
    (is (= {:accepted 1 :quarantined 1} (:deliveries sent)))
    (is (= 1 (count (:items (messenger/messages
                             "acme" "human:alice" id principals)))))
    (is (empty? (:items (messenger/messages
                         "acme" "human:bob" id principals))))
    (let [held (messenger/quarantine "acme" "human:bob" principals)]
      (is (= 1 (:count held)))
      (is (false? (:content-exposed? (first (:items held)))))
      (is (not (contains? (first (:items held)) :content))))
    (messenger/set-trust! "acme" "human:bob" principals "human:alice" true)
    (is (= "private plan"
           (:content (first (:items (messenger/messages
                                     "acme" "human:bob" id principals))))))
    (is (zero? (:count (messenger/quarantine "acme" "human:bob" principals))))))

(deftest a-group-and-agent-are-first-class-members
  (let [c (conversation ["human:bob" "agent:ops"])
        id (:conversation/id c)]
    (messenger/set-trust! "acme" "agent:ops" principals "human:alice" true)
    (messenger/send-message! "acme" "human:alice" id {:content "status?"})
    (is (= "status?" (:content (first (:items
                                        (messenger/messages "acme" "agent:ops"
                                                            id principals))))))
    (messenger/set-trust! "acme" "human:alice" principals "agent:ops" true)
    (messenger/send-message! "acme" "agent:ops" id
                             {:content "all systems nominal"
                              :reply-to (-> (messenger/messages
                                             "acme" "agent:ops" id principals)
                                            :items first :id)})
    (is (= ["status?" "all systems nominal"]
           (mapv :content (:items (messenger/messages
                                   "acme" "human:alice" id principals)))))))

(deftest sealed-messages-are-opaque-and-labelled-truthfully
  (let [c (conversation ["human:bob"])
        id (:conversation/id c)]
    (messenger/set-trust! "acme" "human:bob" principals "human:alice" true)
    (let [sent (messenger/send-message!
                "acme" "human:alice" id
                {:encryption-mode "signal-v1" :sealed "opaque-envelope"})
          viewed (first (:items (messenger/messages
                                 "acme" "human:bob" id principals)))]
      (is (true? (get-in sent [:encryption :e2ee?])))
      (is (true? (:sealed? viewed)))
      (is (= "opaque-envelope" (:sealed viewed)))
      (is (= "signal-v1" (get-in viewed [:encryption :mode])))
      (is (not (contains? viewed :content))))))

(deftest conversation-boundaries-refuse-invented-principals-and-nonmembers
  (testing "directory membership is checked before persistence"
    (is (= :messenger/unknown-principal
           (:type (ex-data
                   (try
                     (messenger/create-conversation!
                      "acme" "human:alice" principals
                      {:kind "direct" :title "bad" :members ["intruder"]})
                     nil
                     (catch clojure.lang.ExceptionInfo error error)))))))
  (let [c (conversation ["human:bob"])]
    (is (= :messenger/forbidden
           (:type (ex-data
                   (try
                     (messenger/messages "acme" "agent:ops"
                                         (:conversation/id c) principals)
                     nil
                     (catch clojure.lang.ExceptionInfo error error))))))))

(deftest device-prekeys-are-signed-consumed-once-and-not-resurrected
  (let [request (device-request)]
    (is (= 2 (:one-time-prekeys
              (messenger/register-device! "acme" "human:bob" principals request))))
    (is (= 11 (get-in (messenger/consume-prekey-bundles!
                       "acme" "human:alice" principals "human:bob")
                      [:bundles 0 :one-time-prekey-id])))
    ;; Re-registering the public device after a page reload cannot reintroduce
    ;; a prekey the server already handed out.
    (messenger/register-device! "acme" "human:bob" principals request)
    (is (= 12 (get-in (messenger/consume-prekey-bundles!
                       "acme" "human:alice" principals "human:bob")
                      [:bundles 0 :one-time-prekey-id])))
    (let [bad (assoc request :signed-prekey-signature (b64 (random-bytes 64)))]
      (is (= :messenger/invalid
             (:type (ex-data
                     (try (messenger/register-device!
                           "acme" "human:alice" principals bad)
                          nil
                          (catch clojure.lang.ExceptionInfo error error)))))))))

(deftest poll-and-ack-remain-bound-to-one-mailbox
  (let [c (conversation ["human:bob"])
        id (:conversation/id c)]
    (messenger/set-trust! "acme" "human:bob" principals "human:alice" true)
    (let [sent (messenger/send-message! "acme" "human:alice" id {:content "poll me"})
          polled (messenger/poll "acme" "human:bob" principals nil 50)
          message-id (:message-id sent)]
      (is (= ["poll me"] (mapv :content (:items polled))))
      (is (seq (:cursor polled)))
      (is (= 1 (:acknowledged
                (messenger/acknowledge! "acme" "human:bob" [message-id]))))
      (is (= :read (:delivery/status
                    (get-in (store/snapshot)
                            [:messenger :organizations "acme" :deliveries
                             [message-id "human:bob"]])))))))
