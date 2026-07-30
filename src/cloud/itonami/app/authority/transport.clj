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

  What an actor answers with -- THREE states, not two:

    {\"status\": \"committed\", \"record\": {...}}    the governor cleared it
    {\"status\": \"held\",      \"refusal\": {...}}   the governor refused it
    {\"status\": \"pending\",   \"reference\": \"…\"}  accepted, awaiting the
                                                   actor's OWN operator

  The third state is why the earlier boolean contract was wrong. A governor
  refusal is NOT an error -- it is the second gate doing its job, the human
  consented and the licensed operator said no. But \"the human consented and the
  operator has not decided yet\" is neither success nor refusal, and a boolean
  forced it to be filed as one of them. With today's fleet it is also the ONLY
  answer a well-formed proposal gets: every op a consent surface can send is
  absent from every phase's :auto set, permanently.

  NOTE on reachability: `cloud-itonami/cloud-itonami-esim` now serves
  `POST /commit` (`clojure -M:serve`, loopback), so the eSIM authority is
  reachable once an endpoint is configured. `cloud-itonami-card-issuing` and
  `denwaban` still have no HTTP surface, so those two remain
  :endpoint-not-configured / :transport-failed until they get one. See
  ADR-2607300300's remaining gaps."
  (:require [clojure.data.json :as json])
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

(defn- post-proposal!
  "POST the proposal to the actor. Returns the actor's own answer, or a
  :transport-failed refusal. Never throws -- a transport problem is an outcome to
  record, not an exception to leak into a route."
  [endpoint proposal]
  (try
    (let [body {:proposal (select-keys proposal
                                       [:id :authority :op :value :digest
                                        :status :approved-at
                                        :passkey-credential-id])}
          request (-> (HttpRequest/newBuilder (URI/create endpoint))
                      (.timeout (Duration/ofSeconds 20))
                      (.header "Accept" "application/json")
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString
                              (json/write-str body)))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          payload (try (json/read-str (.body response) :key-fn keyword)
                       (catch Exception _ nil))]
      (cond
        (not (<= 200 status 299))
        (refusal :transport-failed {:status status})

        (nil? payload)
        (refusal :transport-failed {:detail "actor の応答が JSON として読めません"})

        ;; Accepted, and the actor's own operator has still to decide. Checked
        ;; before the others because it is neither of them.
        (= "pending" (:status payload))
        {:authority/ok? false
         :authority/pending? true
         :authority/reference (:reference payload)
         :authority/refusal nil}

        (= "committed" (:status payload))
        {:authority/ok? true :authority/record (:record payload)}

        ;; The actor's governor refused. Not an error -- the second gate working.
        (= "held" (:status payload))
        {:authority/ok? false
         :authority/refusal (or (:refusal payload) {:rule :governor-refused})}

        :else
        (refusal :transport-failed
                 {:detail (str "actor の応答 status が committed|held|pending の"
                               "いずれでもありません: " (pr-str (:status payload)))})))
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
        (post-proposal! endpoint proposal)))))
