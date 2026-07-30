(ns cloud.itonami.app.authority.transport
  "The hand-off from a consented proposal to the governed actor that decides it.

  This is the `:authority/commit!` the spine calls, built from configuration
  rather than hardcoded, so a deployment chooses whether an authority is reachable
  at all.

  Three refusals, in order, before any request leaves the process:

    :authority-disabled        the authority is off (the default)
    :endpoint-not-configured   it is on but there is nowhere to send to
    :transport-failed          the actor could not be reached or refused at the
                               transport level

  Each is returned as a REFUSAL, not thrown, because the spine records a refusal
  as `:authority-refused` and that is a real outcome a human should see -- an
  authority that cannot be reached is not the same as one that said no, and the
  ledger should be able to tell them apart.

  What an actor answers with -- FOUR states, not two:

    {\"status\": \"committed\", \"record\": {...}}    the governor cleared it
    {\"status\": \"held\",      \"refusal\": {...}}   the governor refused it
    {\"status\": \"pending\",   \"reference\": \"…\"}  accepted, awaiting the
                                                   actor's OWN operator
    {\"status\": \"approved-not-actuated\", …}      the operator APPROVED and the
                                                   outward call did not happen

  The third state is why the earlier boolean contract was wrong. A governor
  refusal is NOT an error -- it is the second gate doing its job, the human
  consented and the licensed operator said no. But \"the human consented and the
  operator has not decided yet\" is neither success nor refusal, and a boolean
  forced it to be filed as one of them.

  THE FOURTH STATE arrived with `cloud-itonami-card-issuing`, the first actor in
  this fleet that can perform a real outward act (issue a card through Stripe
  Issuing). There, approving and issuing are separate events that can fail
  separately: the approval is written to the actor's ledger first, and the provider
  is called second. When the provider refuses, the approval still happened.

  Reading that as `:transport-failed` -- which is what this function did before the
  branch below existed -- would have told a human \"the actor could not be reached\"
  when the truth was \"your operator approved this and Stripe declined it\". Those
  need different responses from a person, so they get different records. It is not
  `:authority/ok?` (no card exists) and not pending (nobody is deciding), so it is
  recorded as a refusal carrying `:approval-recorded true`.

  NOTE on reachability, current as of 2026-07-30: all three of
  `cloud-itonami-esim`, `cloud-itonami-card-issuing` and `denwaban` now serve
  `POST /commit` (`clojure -M:serve`, loopback), so each is reachable once an
  endpoint is configured. denwaban's surface answers `held` at its G7 gate for every
  outward op and never `pending` -- being told \"no\" is different from
  :endpoint-not-configured, which is what it answered while it had no surface at
  all. See ADR-2607300300's remaining gaps."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defonce ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.followRedirects HttpClient$Redirect/NEVER)
      (.build)))

(defn settings
  "The configured settings for one authority."
  [configuration authority-key]
  (get-in configuration [:authorities authority-key]))

(defn enabled?
  "True only when this authority is explicitly enabled. Absent config is off."
  [configuration authority-key]
  (true? (:enabled? (settings configuration authority-key))))

(defn- refusal [rule detail]
  {:authority/ok? false
   :authority/refusal (cond-> {:rule rule}
                        detail (assoc :detail detail))})

(defn- url
  "Join the configured base to a path.

  `:endpoint` is a BASE url (e.g. http://127.0.0.1:1339), not a full commit url.
  It changed meaning here: step 8 treated it as the commit url outright, which
  left nowhere to put the status read. Nothing depended on the old reading --
  defaults.edn ships nil -- so this is a rename of a still-unused knob rather than
  a migration."
  [endpoint path]
  (str (str/replace (str endpoint) #"/+$" "") path))

(defn consent-header
  "The header an actor's consent surface expects. Derived from the authority key so the
  three actors agree without a table: :card -> X-CARD-CONSENT-TOKEN."
  [authority-key]
  (str "X-" (str/upper-case (name authority-key)) "-CONSENT-TOKEN"))

(defn consent-token
  "The consent token for one authority, read from the environment at call time.

  Config names the VARIABLE, never the value -- the same rule the provider keys follow.
  A token written into defaults.edn or into the store would be a secret in git or in a
  backup, and this one is what lets a caller claim a subject consented.

  nil when unconfigured, and that is NOT treated as an error here: the actor refuses on
  its own side (503, fail closed), and its refusal is the honest thing to record. An app
  that pre-emptively refused would be guessing at the actor's configuration."
  [configuration authority-key]
  (some-> (:consent-token-env (settings configuration authority-key))
          str
          not-empty
          System/getenv
          not-empty))

(defn- decode
  "Read a JSON body, or nil."
  [^String body]
  (try (json/read-str body :key-fn keyword) (catch Exception _ nil)))

(defn- interpret
  "Map an actor's four-state answer onto a spine outcome. One function, so the
  commit path and the refresh path cannot read the same wire word differently."
  [payload]
  (condp = (:status payload)
    "pending"   {:authority/ok? false
                 :authority/pending? true
                 :authority/reference (:reference payload)
                 :authority/refusal nil}
    "committed" {:authority/ok? true :authority/record (:record payload)}
    "held"      {:authority/ok? false
                 :authority/refusal (or (:refusal payload) {:rule :governor-refused})}
    ;; The operator approved and the outward call did not happen. Not ok (nothing
    ;; was issued) and not pending (nobody is deciding), so it is a refusal -- but
    ;; one that must carry the fact that an approval really exists in the actor's
    ;; ledger. A human who sees only "refused" would reasonably conclude their
    ;; operator declined, and go ask them why.
    "approved-not-actuated"
    {:authority/ok? false
     :authority/approval-recorded? true
     :authority/refusal (merge {:rule :actuation-failed}
                               (:refusal payload)
                               {:approval-recorded true
                                :decided-by (:decided-by payload)})}
    ;; "unknown" is the actor saying it has no record of this reference -- after a
    ;; restart, every older reference is unknown. That is not a refusal by a
    ;; governor and must not be recorded as one.
    "unknown"   {:authority/ok? false
                 :authority/unknown? true
                 :authority/refusal {:rule :reference-unknown
                                     :detail (:detail payload)}}
    (refusal :transport-failed
             {:detail (str "actor の応答 status が committed|held|pending|unknown|"
                           "approved-not-actuated のいずれでもありません: "
                           (pr-str (:status payload)))})))

(defn- post-proposal!
  "POST the proposal to the actor's commit route. Returns the actor's own answer,
  or a :transport-failed refusal. Never throws -- a transport problem is an
  outcome to record, not an exception to leak into a route."
  [endpoint proposal header token]
  (try
    (let [body {:proposal (select-keys proposal
                                       [:id :authority :op :value :digest
                                        :status :approved-at
                                        :passkey-credential-id])}
          request (-> (HttpRequest/newBuilder (URI/create (url endpoint "/commit")))
                      (.timeout (Duration/ofSeconds 20))
                      (.header "Accept" "application/json")
                      (.header "Content-Type" "application/json")
                      (cond-> token (.header header token))
                      (.POST (HttpRequest$BodyPublishers/ofString
                              (json/write-str body)))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          payload (decode (.body response))]
      (cond
        (not (<= 200 status 299)) (refusal :transport-failed {:status status})
        (nil? payload) (refusal :transport-failed
                                {:detail "actor の応答が JSON として読めません"})
        :else (interpret payload)))
    (catch Exception e
      (refusal :transport-failed {:detail (.getMessage e)}))))

(defn- get-status!
  "Ask the actor what became of a reference. Read-only: this hits the consent
  surface, which cannot decide -- learning the outcome and causing it are
  different authorities and different listeners."
  [endpoint reference header token]
  (try
    (let [request (-> (HttpRequest/newBuilder
                       (URI/create (url endpoint (str "/proposals/" reference))))
                      (.timeout (Duration/ofSeconds 20))
                      (.header "Accept" "application/json")
                      (cond-> token (.header header token))
                      (.GET)
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          payload (decode (.body response))]
      (cond
        (not (<= 200 status 299)) (refusal :transport-failed {:status status})
        (nil? payload) (refusal :transport-failed
                                {:detail "actor の応答が JSON として読めません"})
        :else (interpret payload)))
    (catch Exception e
      (refusal :transport-failed {:detail (.getMessage e)}))))

(defn commit-fn
  "The `:authority/commit!` for one authority, closed over its key.

  Refuses -- rather than throwing -- when the authority is disabled or has no
  endpoint, so a proposal that reaches consent and then cannot be handed off is
  recorded as refused instead of erroring out of a route."
  [authority-key]
  (fn [configuration _session proposal]
    (let [{:keys [endpoint]} (settings configuration authority-key)]
      (cond
        (not (enabled? configuration authority-key))
        (refusal :authority-disabled
                 {:detail (str (name authority-key)
                               " authority は無効です（defaults.edn :authorities）")})

        (or (nil? endpoint) (and (string? endpoint) (empty? endpoint)))
        (refusal :endpoint-not-configured
                 {:detail (str (name authority-key) " authority に endpoint がありません")})

        :else
        (post-proposal! endpoint proposal
                        (consent-header authority-key)
                        (consent-token configuration authority-key))))))

(defn status-fn
  "The read used to refresh a pending proposal, closed over its authority key.
  Same disabled / unconfigured refusals as `commit-fn`, for the same reason."
  [authority-key]
  (fn [configuration reference]
    (let [{:keys [endpoint]} (settings configuration authority-key)]
      (cond
        (not (enabled? configuration authority-key))
        (refusal :authority-disabled
                 {:detail (str (name authority-key) " authority は無効です")})

        (or (nil? endpoint) (and (string? endpoint) (empty? endpoint)))
        (refusal :endpoint-not-configured
                 {:detail (str (name authority-key) " authority に endpoint がありません")})

        (or (nil? reference) (and (string? reference) (empty? reference)))
        (refusal :reference-missing
                 {:detail "pending proposal に reference がありません"})

        :else
        (get-status! endpoint reference
                     (consent-header authority-key)
                     (consent-token configuration authority-key))))))
