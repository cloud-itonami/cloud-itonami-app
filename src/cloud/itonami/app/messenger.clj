(ns cloud.itonami.app.messenger
  "Organization-scoped mailboxes for humans and durable non-human principals.

  A conversation is the social projection; delivery belongs to each mailbox.
  Trust is exact and deny-by-default.  A message from a sender the recipient
  has not allowed is retained in a quarantine lane, but its content is never
  returned by the quarantine API and never becomes model context.

  `:local-plaintext` is deliberately named.  It is suitable for this app's
  loopback-only local surface but is not E2EE.  `:signal-v1` stores an opaque
  envelope produced by a client; this namespace never claims to encrypt bytes
  it did not encrypt."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.util Base64 UUID]))

(def schema "cloud.itonami.app.messenger.v1")
(def max-content-characters 8000)
(def max-sealed-characters (* 1024 1024))
(def conversation-kinds #{:direct :group :channel})
(def max-device-count 16)
(def max-one-time-prekeys 100)
(def max-poll-limit 100)

(defn- fail! [type message & [data]]
  (throw (ex-info message (assoc (or data {}) :type type))))

(defn- clean-id [value field]
  (let [value (some-> value str str/trim)]
    (when-not (and (not (str/blank? value))
                   (<= (count value) 200)
                   (re-matches #"[A-Za-z0-9._:@/-]+" value))
      (fail! :messenger/invalid (str field " is invalid") {:field field}))
    value))

(defn- clean-title [value]
  (let [value (some-> value str str/trim)]
    (when (or (str/blank? value) (> (count value) 120))
      (fail! :messenger/invalid "conversation title is required and must be at most 120 characters"))
    value))

(defn- messenger-state [state]
  (merge {:conversations {} :messages {} :deliveries {} :trust {} :devices {}}
         (:messenger state)))

(defn- organization-state [state organization]
  (get-in (messenger-state state) [:organizations organization]
          {:conversations {} :messages {} :deliveries {} :trust {} :devices {}}))

(defn- org-path [organization & more]
  (into [:messenger :organizations organization] more))

(defn- digest [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str value) StandardCharsets/UTF_8))]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

(defn- b64url-bytes [value expected field]
  (let [value (some-> value str str/trim)]
    (try
      (let [bytes (.decode (Base64/getUrlDecoder) value)]
        (when-not (= expected (alength bytes))
          (fail! :messenger/invalid (str field " has an invalid length") {:field field}))
        bytes)
      (catch IllegalArgumentException _
        (fail! :messenger/invalid (str field " is not base64url") {:field field})))))

(def ^:private ed25519-x509-prefix
  ;; SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 public key.
  (byte-array (map unchecked-byte
                   [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00])))

(defn- verify-signed-prekey! [signing-key signed-prekey signature]
  (let [public-raw (b64url-bytes signing-key 32 :identity-signing-key)
        signed-raw (b64url-bytes signed-prekey 32 :signed-prekey)
        signature-raw (b64url-bytes signature 64 :signed-prekey-signature)
        encoded (byte-array (+ (alength ed25519-x509-prefix) (alength public-raw)))]
    (System/arraycopy ed25519-x509-prefix 0 encoded 0 (alength ed25519-x509-prefix))
    (System/arraycopy public-raw 0 encoded (alength ed25519-x509-prefix)
                      (alength public-raw))
    (let [key (.generatePublic (KeyFactory/getInstance "Ed25519")
                               (X509EncodedKeySpec. encoded))
          verifier (doto (Signature/getInstance "Ed25519") (.initVerify key))]
      (.update verifier signed-raw)
      (when-not (try (.verify verifier signature-raw)
                     (catch java.security.SignatureException _ false))
        (fail! :messenger/invalid "signed prekey signature is invalid"
               {:field :signed-prekey-signature}))))
  true)

(defn- integer-id [value field]
  (let [n (try (long value) (catch Exception _ -1))]
    (when-not (<= 0 n Integer/MAX_VALUE)
      (fail! :messenger/invalid (str field " is invalid") {:field field}))
    n))

(defn register-device!
  "Register public Signal material for the authenticated mailbox principal.
  Private keys never cross this boundary. Replacing an identity key is retained
  as a new version so clients can stop and require explicit verification."
  [organization principal known-principals request]
  (let [organization (clean-id organization :organization)
        principal (clean-id principal :principal)
        _ (when-not (contains? known-principals principal)
            (fail! :messenger/unknown-principal "principal is not in this organization"))
        device-id (clean-id (:device-id request) :device-id)
        signing-key (str (:identity-signing-key request))
        identity-key (str (:identity-key request))
        signed-prekey (str (:signed-prekey request))
        signature (str (:signed-prekey-signature request))
        signed-prekey-id (integer-id (:signed-prekey-id request) :signed-prekey-id)
        _ (b64url-bytes identity-key 32 :identity-key)
        _ (verify-signed-prekey! signing-key signed-prekey signature)
        one-time-prekeys (vec (or (:one-time-prekeys request) []))
        _ (when (> (count one-time-prekeys) max-one-time-prekeys)
            (fail! :messenger/invalid "too many one-time prekeys"))
        submitted-prekeys (into (sorted-map)
                      (map (fn [prekey]
                             (let [id (integer-id (:id prekey) :one-time-prekey-id)
                                   key (str (:key prekey))]
                               (b64url-bytes key 32 :one-time-prekey)
                               [id key])))
                      one-time-prekeys)
        now (store/now)
        previous (get-in (store/snapshot)
                         (org-path organization :devices principal device-id))
        consumed (set (:device/consumed-prekey-ids previous))
        prekeys (apply dissoc submitted-prekeys consumed)
        changed? (and previous
                      (not= signing-key (:device/identity-signing-key previous)))
        version (if changed? (inc (long (or (:device/identity-version previous) 1)))
                    (long (or (:device/identity-version previous) 1)))
        device {:device/id device-id
                :device/principal principal
                :device/identity-signing-key signing-key
                :device/identity-key identity-key
                :device/identity-version version
                :device/signed-prekey-id signed-prekey-id
                :device/signed-prekey signed-prekey
                :device/signed-prekey-signature signature
                :device/one-time-prekeys prekeys
                :device/consumed-prekey-ids consumed
                :device/identity-changed? (boolean changed?)
                :device/registered-at (or (:device/registered-at previous) now)
                :device/updated-at now}]
    (let [devices (get-in (store/snapshot) (org-path organization :devices principal) {})]
      (when (and (not (contains? devices device-id))
                 (>= (count devices) max-device-count))
        (fail! :messenger/invalid "device limit reached")))
    (store/transact! assoc-in (org-path organization :devices principal device-id) device)
    {:schema schema :device-id device-id :principal principal
     :identity-version version :identity-changed? (boolean changed?)
     :one-time-prekeys (count prekeys) :updated-at now}))

(defn remove-device! [organization principal device-id]
  (let [organization (clean-id organization :organization)
        principal (clean-id principal :principal)
        device-id (clean-id device-id :device-id)]
    (when-not (get-in (store/snapshot)
                      (org-path organization :devices principal device-id))
      (fail! :messenger/not-found "device was not found" {:id device-id}))
    (store/transact! update-in (org-path organization :devices principal) dissoc device-id)
    {:schema schema :device-id device-id :removed? true}))

(defn device-directory [organization target]
  (let [devices (vals (get-in (organization-state (store/snapshot) organization)
                              [:devices target] {}))]
    {:schema schema :principal target
     :devices
     (mapv (fn [device]
             {:id (:device/id device)
              :identity-signing-key (:device/identity-signing-key device)
              :identity-key (:device/identity-key device)
              :identity-version (:device/identity-version device)
              :identity-changed? (:device/identity-changed? device)
              :signed-prekey-id (:device/signed-prekey-id device)
              :signed-prekey (:device/signed-prekey device)
              :signed-prekey-signature (:device/signed-prekey-signature device)
              :one-time-prekey-count (count (:device/one-time-prekeys device))
              :updated-at (:device/updated-at device)})
           devices)}))

(defn consume-prekey-bundles!
  "Return one bundle per target device and atomically consume at most one OPK
  from each. A recipient can still establish a session without an OPK, as X3DH
  specifies, but the response makes that downgrade visible."
  [organization requester known-principals target]
  (let [organization (clean-id organization :organization)
        requester (clean-id requester :requester)
        target (clean-id target :target)]
    (when-not (contains? known-principals target)
      (fail! :messenger/unknown-principal "target is not in this organization"
             {:principal target}))
    (let [result (atom [])]
      (store/transact!
       (fn [state]
         (let [devices (get-in state (org-path organization :devices target) {})
               bundles
               (mapv (fn [[device-id device]]
                       (let [[prekey-id prekey] (first (:device/one-time-prekeys device))]
                         (cond-> {:principal target :device-id device-id
                                  :identity-signing-key (:device/identity-signing-key device)
                                  :identity-key (:device/identity-key device)
                                  :identity-version (:device/identity-version device)
                                  :identity-changed? (:device/identity-changed? device)
                                  :signed-prekey-id (:device/signed-prekey-id device)
                                  :signed-prekey (:device/signed-prekey device)
                                  :signed-prekey-signature (:device/signed-prekey-signature device)}
                           prekey-id (assoc :one-time-prekey-id prekey-id
                                            :one-time-prekey prekey))))
                     devices)
               next-state
               (reduce (fn [current [device-id device]]
                         (if-let [prekey-id (ffirst (:device/one-time-prekeys device))]
                           (-> current
                               (update-in
                                (org-path organization :devices target device-id
                                          :device/one-time-prekeys)
                                dissoc prekey-id)
                               (update-in
                                (org-path organization :devices target device-id
                                          :device/consumed-prekey-ids)
                                (fnil conj #{}) prekey-id))
                           current))
                       state devices)]
           (reset! result bundles)
           next-state)))
      {:schema schema :requester requester :principal target :bundles @result})))

(defn- member? [conversation principal]
  (contains? (set (:conversation/members conversation)) principal))

(defn- conversation! [state organization conversation-id principal]
  (let [conversation (get-in state (org-path organization :conversations conversation-id))]
    (when-not conversation
      (fail! :messenger/not-found "conversation was not found" {:id conversation-id}))
    (when-not (member? conversation principal)
      (fail! :messenger/forbidden "principal is not a conversation member"
             {:id conversation-id :principal principal}))
    conversation))

(defn- trusted? [org-state recipient sender]
  (or (= recipient sender)
      (true? (get-in org-state [:trust recipient sender :allowed?]))))

(defn create-conversation!
  "Create a conversation from principals already resolved by the server.
  Creating a conversation does not silently trust its members."
  [organization actor known-principals {:keys [kind title members]}]
  (let [organization (clean-id organization :organization)
        actor (clean-id actor :actor)
        kind (keyword (or kind :direct))
        members (->> (conj (vec (or members [])) actor)
                     (map #(clean-id % :member)) distinct vec)]
    (when-not (conversation-kinds kind)
      (fail! :messenger/invalid "conversation kind must be direct, group, or channel"))
    (when (or (< (count members) 2) (> (count members) 100))
      (fail! :messenger/invalid "a conversation needs 2 to 100 members"))
    (doseq [member members]
      (when-not (contains? known-principals member)
        (fail! :messenger/unknown-principal "conversation member is not in this organization"
               {:principal member})))
    (when (and (= kind :direct) (not= 2 (count members)))
      (fail! :messenger/invalid "a direct conversation has exactly two members"))
    (let [id (str "conversation-" (UUID/randomUUID))
          conversation {:conversation/id id
                        :conversation/organization organization
                        :conversation/kind kind
                        :conversation/title (clean-title title)
                        :conversation/members members
                        :conversation/created-by actor
                        :conversation/created-at (store/now)}]
      (store/transact! assoc-in (org-path organization :conversations id) conversation)
      conversation)))

(defn set-trust!
  "Allow or revoke one exact sender for the caller's mailbox.

  Allowing promotes that sender's existing quarantined deliveries. Revoking is
  prospective: already accepted/read mail remains part of the record."
  [organization recipient known-principals sender allowed?]
  (let [organization (clean-id organization :organization)
        recipient (clean-id recipient :recipient)
        sender (clean-id sender :sender)]
    (when (= recipient sender)
      (fail! :messenger/invalid "a mailbox always trusts itself"))
    (when-not (contains? known-principals sender)
      (fail! :messenger/unknown-principal "sender is not in this organization"
             {:principal sender}))
    (let [at (store/now)]
      (store/transact!
       (fn [state]
         (let [state (assoc-in state
                               (org-path organization :trust recipient sender)
                               {:allowed? (boolean allowed?) :updated-at at})]
           (if-not allowed?
             state
             (update-in state (org-path organization :deliveries)
                        (fn [deliveries]
                          (into {}
                                (map (fn [[key delivery]]
                                       [key (if (and (= recipient (:delivery/recipient delivery))
                                                     (= sender (:delivery/sender delivery))
                                                     (= :quarantined (:delivery/status delivery)))
                                              (assoc delivery :delivery/status :accepted
                                                     :delivery/admitted-at at)
                                              delivery)]))
                                (or deliveries {}))))))))
      {:schema schema :recipient recipient :sender sender
       :allowed? (boolean allowed?) :updated-at at})))

(defn- message-payload [{:keys [content sealed encryption-mode]}]
  (let [content (some-> content str)
        mode (keyword (or encryption-mode (when sealed :signal-v1) :local-plaintext))]
    (case mode
      :local-plaintext
      (do
        (when (or (str/blank? content) (> (count content) max-content-characters))
          (fail! :messenger/invalid "message content is required and must be at most 8000 characters"))
        {:message/content content
         :message/encryption {:mode :local-plaintext :e2ee? false}})

      :signal-v1
      (let [sealed (some-> sealed str)]
        (when (or (str/blank? sealed) (> (count sealed) max-sealed-characters))
          (fail! :messenger/invalid "a signal-v1 sealed envelope is required and envelopes over 1 MiB are refused"))
        {:message/sealed sealed
         :message/encryption {:mode :signal-v1 :e2ee? true}})

      (fail! :messenger/invalid "unsupported message encryption mode" {:mode mode}))))

(defn send-message!
  [organization sender conversation-id request]
  (let [organization (clean-id organization :organization)
        sender (clean-id sender :sender)
        conversation-id (clean-id conversation-id :conversation)
        state (store/snapshot)
        conversation (conversation! state organization conversation-id sender)
        reply-to (some-> (:reply-to request) (clean-id :reply-to))
        _ (when reply-to
            (let [parent (get-in state (org-path organization :messages reply-to))]
              (when-not (= conversation-id (:message/conversation parent))
                (fail! :messenger/invalid "reply-to must name a message in this conversation"))))
        payload (message-payload request)
        id (str "message-" (UUID/randomUUID))
        at (store/now)
        message (merge {:message/id id
                        :message/organization organization
                        :message/conversation conversation-id
                        :message/sender sender
                        :message/type :message
                        :message/reply-to reply-to
                        :message/created-at at}
                       payload)
        content-digest (digest (or (:message/content message) (:message/sealed message)))
        org-state (organization-state state organization)
        deliveries
        (into {}
              (map (fn [recipient]
                     (let [status (if (trusted? org-state recipient sender)
                                    :accepted :quarantined)]
                       [[id recipient]
                        {:delivery/message id
                         :delivery/conversation conversation-id
                         :delivery/sender sender
                         :delivery/recipient recipient
                         :delivery/status status
                         :delivery/content-digest content-digest
                         :delivery/created-at at}]))
                   (:conversation/members conversation)))]
    (store/transact!
     (fn [current]
       (-> current
           (assoc-in (org-path organization :messages id) message)
           (update-in (org-path organization :deliveries) #(merge (or % {}) deliveries)))))
    {:schema schema :message-id id :conversation-id conversation-id
     :deliveries (frequencies (map :delivery/status (vals deliveries)))
     :encryption (:message/encryption message)}))

(defn- message-view [message delivery principals]
  (let [sender (get principals (:message/sender message))]
    (cond-> {:id (:message/id message)
             :conversation-id (:message/conversation message)
             :sender-id (:message/sender message)
             :sender (or (:name sender) (:message/sender message))
             :sender-kind (:kind sender)
             :reply-to (:message/reply-to message)
             :created-at (:message/created-at message)
             :delivery-status (name (:delivery/status delivery))
             :encryption {:mode (name (get-in message [:message/encryption :mode]))
                          :e2ee? (boolean (get-in message [:message/encryption :e2ee?]))}}
      (:message/content message) (assoc :content (:message/content message))
      (:message/sealed message) (assoc :sealed? true :sealed (:message/sealed message)))))

(defn messages
  "Accepted/read messages visible to one mailbox. Quarantined content is never
  projected by this function."
  [organization principal conversation-id principals]
  (let [state (store/snapshot)
        conversation (conversation! state organization conversation-id principal)
        org-state (organization-state state organization)
        visible (->> (:deliveries org-state)
                     vals
                     (filter #(and (= principal (:delivery/recipient %))
                                   (= conversation-id (:delivery/conversation %))
                                   (#{:accepted :read} (:delivery/status %))))
                     (sort-by :delivery/created-at)
                     (mapv (fn [delivery]
                             (message-view (get-in org-state [:messages (:delivery/message delivery)])
                                           delivery principals))))]
    {:schema schema
     :conversation (assoc conversation
                          :conversation/kind (name (:conversation/kind conversation)))
     :items visible}))

(defn mark-read! [organization principal conversation-id]
  (conversation! (store/snapshot) organization conversation-id principal)
  (let [at (store/now)]
    (store/transact!
     update-in (org-path organization :deliveries)
     (fn [deliveries]
       (into {}
             (map (fn [[key delivery]]
                    [key (if (and (= principal (:delivery/recipient delivery))
                                  (= conversation-id (:delivery/conversation delivery))
                                  (= :accepted (:delivery/status delivery)))
                           (assoc delivery :delivery/status :read :delivery/read-at at)
                           delivery)]))
             (or deliveries {}))))
    {:schema schema :conversation-id conversation-id :read-at at}))

(defn poll
  "Poll accepted/read deliveries for one authenticated mailbox. The cursor is
  a stable `created-at|message-id` pair; no caller can choose another mailbox."
  [organization principal principals cursor limit]
  (let [org-state (organization-state (store/snapshot) organization)
        limit (min max-poll-limit (max 1 (try (long limit) (catch Exception _ 50))))
        after (or (some-> cursor str) "")
        deliveries (->> (:deliveries org-state) vals
                        (filter #(and (= principal (:delivery/recipient %))
                                      (#{:accepted :read} (:delivery/status %))))
                        (sort-by (juxt :delivery/created-at :delivery/message))
                        (filter (fn [delivery]
                                  (pos? (compare (str (:delivery/created-at delivery) "|"
                                                      (:delivery/message delivery))
                                                 after))))
                        (take limit)
                        vec)
        items (mapv (fn [delivery]
                      (message-view
                       (get-in org-state [:messages (:delivery/message delivery)])
                       delivery principals))
                    deliveries)
        next-cursor (if-let [delivery (last deliveries)]
                      (str (:delivery/created-at delivery) "|" (:delivery/message delivery))
                      after)]
    {:schema schema :principal principal :cursor next-cursor :items items}))

(defn acknowledge!
  "Mark only the caller's named deliveries read. Unknown IDs are ignored so a
  retry remains idempotent; IDs for another mailbox are never touched."
  [organization principal message-ids]
  (let [ids (->> (or message-ids [])
                 (map #(clean-id % :message-id)) distinct (take 100) set)
        at (store/now)
        changed (atom 0)]
    (store/transact!
     update-in (org-path organization :deliveries)
     (fn [deliveries]
       (into {}
             (map (fn [[key delivery]]
                    [key (if (and (= principal (:delivery/recipient delivery))
                                  (contains? ids (:delivery/message delivery))
                                  (= :accepted (:delivery/status delivery)))
                           (do (swap! changed inc)
                               (assoc delivery :delivery/status :read
                                      :delivery/read-at at))
                           delivery)]))
             (or deliveries {}))))
    {:schema schema :acknowledged @changed :read-at at}))

(defn quarantine [organization principal principals]
  (let [org-state (organization-state (store/snapshot) organization)
        items (->> (:deliveries org-state) vals
                   (filter #(and (= principal (:delivery/recipient %))
                                 (= :quarantined (:delivery/status %))))
                   (sort-by :delivery/created-at #(compare %2 %1))
                   (mapv (fn [delivery]
                           (let [sender (get principals (:delivery/sender delivery))]
                             {:message-id (:delivery/message delivery)
                              :conversation-id (:delivery/conversation delivery)
                              :sender-id (:delivery/sender delivery)
                              :sender (or (:name sender) (:delivery/sender delivery))
                              :sender-kind (:kind sender)
                              :created-at (:delivery/created-at delivery)
                              :content-digest (:delivery/content-digest delivery)
                              :content-exposed? false}))))]
    {:schema schema :items items :count (count items)}))

(defn overview [organization principal principals]
  (let [org-state (organization-state (store/snapshot) organization)
        deliveries (vals (:deliveries org-state))
        conversations
        (->> (:conversations org-state) vals
             (filter #(member? % principal))
             (mapv (fn [conversation]
                     (let [id (:conversation/id conversation)
                           mine (filter #(and (= principal (:delivery/recipient %))
                                              (= id (:delivery/conversation %))) deliveries)]
                       {:id id
                        :kind (name (:conversation/kind conversation))
                        :title (:conversation/title conversation)
                        :members (:conversation/members conversation)
                        :unread (count (filter #(= :accepted (:delivery/status %)) mine))
                        :updated-at (or (some->> mine (map :delivery/created-at) sort last)
                                        (:conversation/created-at conversation))})))
             (sort-by :updated-at #(compare %2 %1)) vec)
        trusted (->> (get-in org-state [:trust principal])
                     (keep (fn [[sender rule]] (when (:allowed? rule) sender))) set)]
    {:schema schema
     :principal principal
     :encryption {:local-plaintext {:e2ee? false :available? true}
                  :signal-v1 {:e2ee? true :available? true
                              :key-management "client-owned"}}
     :principals (->> principals vals
                      (mapv #(assoc %
                                    :trusted? (or (= principal (:id %))
                                                  (contains? trusted (:id %)))
                                    :devices (count (get-in org-state
                                                            [:devices (:id %)])))))
     :conversations conversations
     :quarantine (count (filter #(and (= principal (:delivery/recipient %))
                                      (= :quarantined (:delivery/status %))) deliveries))}))
