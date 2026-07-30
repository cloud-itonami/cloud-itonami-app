(ns cloud.itonami.app.approval-broker
  "Provider-neutral, fail-closed approval rendezvous.

  Provider adapters submit the exact private request here and expose only a
  bounded summary plus a digest to the browser. Decisions are scoped to one
  request and one run; a restart, timeout, or unknown decision declines it."
  (:require [clojure.string :as str]
            [cloud.itonami.app.store :as store])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def schema "cloud.itonami.app.approval.v1")
(def decisions #{:accept :accept-for-session :decline :cancel})
(defonce ^:private waiters (atom {}))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn request-digest [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (hex (.digest digest (.getBytes (pr-str value) StandardCharsets/UTF_8)))))

(defn- normalize-decision [value]
  (let [decision (some-> value name keyword)]
    (when (contains? decisions decision) decision)))

(defn public-approval [approval]
  (select-keys approval
               [:schema :id :run-id :session-id :kind :status :summary
                :reason :cwd :digest :created-at :expires-at :decision
                :resolved-at]))

(defn pending
  ([]
   (pending nil))
  ([session-id]
   (->> (vals (get-in (store/snapshot) [:agent-approvals :requests] {}))
        (filter #(and (= :pending (:status %))
                      (or (nil? session-id) (= session-id (:session-id %)))))
        (sort-by :created-at)
        (mapv public-approval))))

(defn resolve!
  "Resolve one live request. Persisted requests from a previous process cannot
  resume their provider turn and therefore fail closed."
  [approval-id decision]
  (let [decision (normalize-decision decision)
        waiter (get @waiters approval-id)]
    (when-not decision
      (throw (ex-info "Unknown approval decision."
                      {:type :agent-approval/invalid-decision})))
    (when-not waiter
      (throw (ex-info "Approval is no longer active."
                      {:type :agent-approval/not-active :id approval-id})))
    (deliver waiter decision)
    true))

(defn request!
  [{:keys [run-id session-id kind summary reason cwd private-request
           timeout-ms on-event]}]
  (let [approval-id (store/new-id "approval")
        timeout-ms (long (or timeout-ms 120000))
        created-at (store/now)
        expires-at (str (java.time.Instant/ofEpochMilli
                         (+ (System/currentTimeMillis) timeout-ms)))
        waiter (promise)
        approval {:schema schema :id approval-id :run-id run-id
                  :session-id session-id :kind kind :status :pending
                  :summary (or (some-> summary str str/trim not-empty)
                               "Agent action requires approval")
                  :reason (some-> reason str str/trim not-empty)
                  :cwd (some-> cwd str)
                  :digest (request-digest private-request)
                  :private-request private-request
                  :created-at created-at :expires-at expires-at}]
    (store/transact! assoc-in [:agent-approvals :requests approval-id] approval)
    (swap! waiters assoc approval-id waiter)
    (when on-event
      (on-event {:type :approval/requested
                 :approval-id approval-id :kind kind
                 :status :pending :digest (:digest approval)}))
    (let [raw-decision (deref waiter timeout-ms :timeout)
          timed-out? (= :timeout raw-decision)
          decision (if timed-out? :decline raw-decision)
          resolved-at (store/now)
          status (if timed-out? :expired :resolved)]
      (swap! waiters dissoc approval-id)
      (store/transact!
       update-in [:agent-approvals :requests approval-id]
       #(-> %
            (dissoc :private-request)
            (assoc :status status :decision decision :resolved-at resolved-at)))
      (when on-event
        (on-event {:type :approval/resolved :approval-id approval-id
                   :kind kind :status status :decision decision
                   :digest (:digest approval)}))
      decision)))

(defn codex-decision [decision]
  (case decision
    :accept "accept"
    :accept-for-session "acceptForSession"
    :cancel "cancel"
    "decline"))
