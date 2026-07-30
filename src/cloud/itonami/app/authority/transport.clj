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

  What an actor is expected to answer with:

    {\"ok\": true,  \"record\": {...}}      the governor cleared it
    {\"ok\": false, \"refusal\": {...}}     the governor refused it

  A governor refusal arriving over this transport is NOT an error. It is the
  second gate doing its job: the human consented and the licensed operator's
  governor still said no. `commit!` records it and both statuses are terminal.

  NOTE on reachability, stated plainly: no actor in this fleet exposes an HTTP
  surface yet -- they are libraries with demo mains. So with today's fleet every
  configured endpoint will refuse with :transport-failed, and every unconfigured
  one with :endpoint-not-configured. This namespace is the seam that makes the
  app side complete and testable; it does not make the fleet reachable. See
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

        ;; The actor's governor refused. Not an error -- the second gate working.
        (false? (:ok payload))
        {:authority/ok? false
         :authority/refusal (or (:refusal payload) {:rule :governor-refused})}

        (true? (:ok payload))
        {:authority/ok? true :authority/record (:record payload)}

        :else
        (refusal :transport-failed {:detail "actor の応答に ok が含まれません"})))
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
