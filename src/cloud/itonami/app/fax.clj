(ns cloud.itonami.app.fax
  "Dropbox Fax as the practice's `lawfirm.workspace/DispatchPort`.

  ## Why an API and not the UI

  The prior art in this workspace (`etzhayyim-project-fax`) treated Dropbox
  Fax as a **manual UI handoff**: an operator opens the web app, uploads the
  document, and types the destination. That is precisely the shape
  `lawfirm.transmission` exists to eliminate — a number typed at send time, by
  the person in the biggest hurry, from something on a screen.

  Dropbox Fax has an API. Using it is not a convenience here, it is the
  control: the destination is read from the practice record by
  `lawfirm.workspace/dispatch-plan` and handed to the transport, and no human
  is in a position to mistype it.

  ## What this refuses to send

  A document whose bytes it cannot tie to the one the 弁護士 approved.

  The practice approves a `doc-id`, not a file. If a work product records
  `:object-ref \"sha256:…\"`, the bytes offered here must hash to it. If it
  records no ref, this refuses rather than sending: 'the approval was for
  document A and these bytes are unidentified' is not a state to transmit a
  client's confidence in. An operator who finds this inconvenient should
  record the digest; the alternative is a system that will one day fax the
  wrong file to a court and have a green audit trail saying it went fine.

  ## Credentials

  Basic auth with the account's **own password** — that is what the API takes.
  It is therefore never in config, never in the state file and never in an
  environment variable committed anywhere: it is read from the OS credential
  store by service and account, one item, by name.

  ## Not exercised against the live service

  Every test here drives an injected `send-fn`. This code has never been run
  against `api.hellofax.com`: no account is provisioned in this workspace and
  sending a fax costs money and reaches a real phone line. The request shape
  follows Dropbox's published API documentation; the first live call is a
  deployment step, not something a test here has proved.

  API reference: <https://help.dropbox.com/integrations/send-fax-using-dropbox-fax-api>"
  (:require [cloud.itonami.app.config :as config]
            [cloud.itonami.app.lawfirm :as app-lawfirm]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [lawfirm.actor :as lf-actor]
            [lawfirm.store :as lf-store]
            [lawfirm.workspace :as lf-workspace])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration]
           [java.util Base64 UUID]
           [java.util.concurrent TimeUnit]))

(def schema "cloud.itonami.app.fax.v1")

(def api-base
  "The v1 Transmissions endpoint. Dropbox's own documentation notes that v1 is
  being phased out in favour of v3; when that lands this constant and
  `parse-response` are what change, and the port contract above them does
  not."
  "https://api.hellofax.com/v1")

;; ---------------------------------------------------------------------------
;; Configuration and credentials
;; ---------------------------------------------------------------------------

(defn settings
  ([] (settings (config/load-config)))
  ([configuration] (get configuration :fax {})))

(defn enabled?
  "Fail-closed, like every authority here."
  ([] (enabled? (config/load-config)))
  ([configuration] (true? (:enabled? (settings configuration)))))

(defn- keychain-secret
  "One item, fetched by service and account.

  Deliberately a targeted read and never an enumeration: dumping a keychain to
  find a credential exposes the metadata of every unrelated secret on the
  machine."
  [service account]
  (try
    (let [process (-> (ProcessBuilder.
                       ^java.util.List
                       ["security" "find-generic-password"
                        "-s" service "-a" account "-w"])
                      (.redirectErrorStream true)
                      .start)
          output (future (slurp (.getInputStream process)))
          completed? (.waitFor process 3 TimeUnit/SECONDS)]
      (when (and completed? (zero? (.exitValue process)))
        (not-empty (str/trim (deref output 500 "")))))
    (catch Exception _ nil)))

(defn credentials
  "`{:username :password :account-guid}` or nil when any part is missing.

  The password is never in `configuration` — only the service and account
  names that locate it are."
  [configuration]
  (let [{:keys [username account-guid keychain-service]} (settings configuration)
        service (or keychain-service "dropbox-fax")
        password (when (seq (str username)) (keychain-secret service username))]
    (when (and (seq (str username)) (seq (str account-guid)) (seq (str password)))
      {:username username :password password :account-guid account-guid})))

;; ---------------------------------------------------------------------------
;; Refusals
;;
;; Returned, not thrown — a dispatch that cannot proceed is a recorded outcome
;; and not an error out of a route. Same shape as `authority.transport`.
;; ---------------------------------------------------------------------------

(defn- refusal [reason detail]
  {:schema schema :ok? false :reason reason :detail detail})

;; ---------------------------------------------------------------------------
;; The document must be the one that was approved
;; ---------------------------------------------------------------------------

(defn sha256-hex [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" %) digest))))

(defn document-matches?
  "True when `bytes` are the document `object-ref` names.

  Only `sha256:<hex>` is understood. Anything else — including nil — is not a
  match, because this function's job is to answer *are these the approved
  bytes* and 'I cannot tell' is not a yes."
  [object-ref ^bytes bytes]
  (let [ref (str object-ref)]
    (and (str/starts-with? ref "sha256:")
         (= (str/lower-case (subs ref (count "sha256:")))
            (sha256-hex bytes)))))

;; ---------------------------------------------------------------------------
;; The transport
;; ---------------------------------------------------------------------------

(defonce ^:private http-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) .build)))

(defn- multipart
  "A multipart/form-data body. Hand-rolled because this is the only multipart
  request in the app and `java.net.http` has no builder for one."
  [boundary fields ^bytes file-bytes filename]
  (let [out (java.io.ByteArrayOutputStream.)
        write (fn [^String s] (.write out (.getBytes s StandardCharsets/UTF_8)))]
    (doseq [[k v] fields :when (some? v)]
      (write (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"" k "\"\r\n\r\n"
                  v "\r\n")))
    (write (str "--" boundary "\r\n"
                "Content-Disposition: form-data; name=\"file\"; filename=\"" filename "\"\r\n"
                "Content-Type: application/pdf\r\n\r\n"))
    (.write out file-bytes)
    (write (str "\r\n--" boundary "--\r\n"))
    (.toByteArray out)))

(defn- basic-auth [{:keys [username password]}]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str username ":" password)
                                            StandardCharsets/UTF_8))))

(defn http-send-fn
  "The real transport. Separated so every test drives an injected one and no
  test can reach the network by forgetting to stub something."
  [{:keys [credentials to file-bytes filename cover]}]
  (let [boundary (str "----kotoba" (UUID/randomUUID))
        url (str api-base "/Accounts/" (:account-guid credentials) "/Transmissions"
                 "?To=" (URLEncoder/encode (str to) StandardCharsets/UTF_8))
        body (multipart boundary
                        {"CoverPageTo" (:to cover)
                         "CoverPageFrom" (:from cover)
                         "CoverPageMessage" (:message cover)}
                        file-bytes filename)
        request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "Authorization" (basic-auth credentials))
                    (.header "Content-Type" (str "multipart/form-data; boundary=" boundary))
                    (.timeout (Duration/ofSeconds 60))
                    (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                    .build)
        response (.send @http-client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(def status-codes
  "Dropbox Fax `StatusCode` → the practice's `:result`.

  `O` (on hold — unconfirmed account or insufficient pages) maps to
  `:pending` and not `:failed`: nothing has been refused, the transmission has
  not started. Reporting it as failed would send someone to re-send a document
  that is queued."
  {"S" :ok
   "E" :failed
   "T" :pending
   "P" :pending
   "O" :pending})

(def error-codes
  "`ErrorCode` when `StatusCode` is `E`, for the operator reading the record."
  {"R" "話中" "N" "応答なし" "A" "未割当番号" "D" "回線切断"
   "L" "拒否リスト" "U" "不明なエラー"})

(defn parse-response
  "The provider's answer, reduced to what the practice records.

  `:dialled` is whatever destination the provider **echoed back**, and stays
  nil when it echoed none. That nil is load-bearing:
  `lawfirm.transmission/direction-check` turns it into `:undeterminable`
  rather than into a claim that the destination was correct."
  [{:keys [status body] :as _response} decode]
  (let [payload (try (decode body) (catch Exception _ nil))
        tx (or (get payload "Transmission") (get payload :Transmission) payload)
        code (str (or (get tx "StatusCode") (get tx :StatusCode)))
        err (str (or (get tx "ErrorCode") (get tx :ErrorCode)))]
    (cond
      (not (<= 200 status 299))
      {:ok? false :reason :provider-rejected :status status
       :detail (if (= 429 status)
                 "Dropbox Fax の1日あたり保留上限(200件)に達しています"
                 (str "HTTP " status))}

      (nil? payload)
      {:ok? false :reason :provider-unreadable
       :detail "Dropbox Fax の応答を JSON として読めません"}

      :else
      {:ok? true
       :provider-id (str (or (get tx "Guid") (get tx :Guid)))
       :provider-status code
       :result (get status-codes code :pending)
       :error (get error-codes err)
       :dialled (some-> (or (get tx "To") (get tx :To)) str not-empty)
       :page-count (some-> (or (get tx "PageCount") (get tx :PageCount)) int)})))

;; ---------------------------------------------------------------------------
;; DispatchPort
;; ---------------------------------------------------------------------------

(defn dispatch-port
  "`lawfirm.workspace/DispatchPort` over Dropbox Fax.

  `-dispatch` receives a plan the practice built — the number in it came from
  the registered recipient, which is the whole point — plus the bytes and the
  configuration the host adds."
  [{:keys [send-fn decode]}]
  (reify lf-workspace/DispatchPort
    (-dispatch [_ {:keys [plan bytes filename credentials cover]}]
      (let [to (get-in plan [:recipient :coordinate :number])]
        (parse-response (send-fn {:credentials credentials
                                  :to to
                                  :file-bytes bytes
                                  :filename (or filename "document.pdf")
                                  :cover cover})
                        decode)))))

;; ---------------------------------------------------------------------------
;; The operation
;; ---------------------------------------------------------------------------

(defn record-status!
  "Put a provider outcome into the practice record, through its gate.

  Never escalated and never refused for a mismatch: the pages have already
  gone, and the practice's rule is that a misdirection is *recorded*. See
  `lawfirm.transmission/confirmation-violations`.

  `thread-id` names the run. `dispatch!` writes twice for one 送達 — the
  attempt before the transport and the outcome after it — and those are two
  runs of the gate, not one run replayed, so they are not given the same
  checkpoint scope."
  [{:keys [transmission-id matter-id client-id today bengoshi-id status thread-id]}]
  (let [g (app-lawfirm/graph)
        request {:op :confirm-transmission
                 :matter-id matter-id
                 ;; From `dispatch-plan`, not derived here. Every op needs a
                 ;; registered client and an acting 弁護士; a host that made
                 ;; those up would be inventing provenance for a record.
                 :client-id client-id
                 :bengoshi-id bengoshi-id
                 :transmission-confirmation
                 {:transmission-id transmission-id
                  :matter-id matter-id
                  :result (:result status)
                  :provider :dropbox-fax
                  :provider-id (:provider-id status)
                  :provider-status (:provider-status status)
                  :dialled (:dialled status)
                  :page-count (:page-count status)
                  :confirmed-on today}}
        run (lf-actor/run-request! g request {:today today}
                                   (or thread-id
                                       (str "fax-confirm-" transmission-id)))]
    {:recorded? (lf-actor/committed? run)
     :violations (mapv (comp name :rule)
                       (get-in run [:state :verdict :violations]))}))

(defn dispatch!
  "Send a committed 送達 through Dropbox Fax, and record what happened.

  Every refusal below is checked **before** the transport is touched, so a
  request that cannot lawfully proceed never reaches a phone line:

    1. the surface is enabled and has credentials
    2. the practice blesses the plan (`dispatch-plan`: the 送達 exists, is
       outbound, names a registered recipient with a coordinate, and its
       document is 発出済)
    3. the channel is `:fax`
    4. the bytes are the approved document

  Then the 送達 is written down as `:pending` **before** the transport is
  called, and the outcome reconciles that row afterwards. Both writes go
  through the practice's own gate as `:confirm-transmission` — this namespace
  decides nothing about the record, it only refuses to act before the record
  can describe the act.

  ## Why the record is written first

  `cloud.itonami.app.credential/issue-membership!` already states this rule
  for a credential's revocation index:

  > The index is allocated from durable state before signing, so a credential
  > that exists is always revocable. Allocating afterwards would leave a
  > window in which an issued credential had no index to flip.

  A fax is the same shape and the window is worse, because the act is not
  undoable. `parse-response` returns `:provider-unreadable` only *after* a
  2xx: the provider took the transmission and then answered in something that
  is not JSON. Recording the outcome afterwards meant that answer produced no
  write at all, so the 送達 kept no `:result` — and `lawfirm.transmission/
  unconfirmed`, which the console shows as 「結果未確認の送達」, listed it
  identically to one that never reached a phone line. The obvious repair for
  a 送達 that looks un-dispatched is to dispatch it, and the document is a
  legal filing.

  There is no id to recover afterwards either. The unreadable body is the
  body the `Guid` was in, so `transmission-by-provider-id` — the only join
  from the provider's callback back to this practice — has nothing to match,
  and the callback for that fax is refused as
  `:unknown-provider-transmission`. A row written beforehand cannot supply
  that id, but it can stop the second copy.

  So the failure this prefers is a 送達 recorded as `:pending` that turns out
  never to have left. That is a row saying *this may have been transmitted*,
  and a person can resolve it. The other failure is a legal document on a
  stranger's fax machine and no record that it went."
  [{:keys [transmission-id bytes filename today send-fn decode configuration]}]
  (let [decode (or decode #(json/read-str %))
        configuration (or configuration (config/load-config))
        creds (credentials configuration)]
    (cond
      (not (enabled? configuration))
      (refusal :fax-disabled "FAX 送信面は無効です（config の :fax :enabled? を true に）")

      (nil? creds)
      (refusal :credentials-missing
               (str "Dropbox Fax の認証情報が揃っていません。"
                    ":fax :username と :account-guid を config に、"
                    "パスワードは keychain（既定 service \"dropbox-fax\"）に置いてください"))

      (not (app-lawfirm/enabled? configuration))
      (refusal :lawfirm-disabled "法律事務所の記録面が無効です")

      :else
      (let [store (app-lawfirm/practice)
            plan (lf-workspace/dispatch-plan store transmission-id)]
        (cond
          (not (:ok? plan))
          (refusal (:reason plan) "practice がこの送達の実行を認めていません")

          (not= :fax (:channel plan))
          (refusal :not-a-fax
                   (str "送達 " transmission-id " の経路は "
                        (name (or (:channel plan) :unknown)) " です"))

          (not (document-matches? (get-in plan [:document :object-ref]) bytes))
          (refusal :document-mismatch
                   (str "送信しようとしているバイト列が、弁護士が承認した書面 "
                        (get-in plan [:document :doc-id])
                        " の :object-ref と一致しません。"
                        "承認は書面に対して行われたものであって、"
                        "手元のファイルに対してではありません"))

          :else
          ;; The attempt is recorded first. `:pending` is the practice's own
          ;; word for a transport that has been handed a document and has not
          ;; yet said what became of it (`lawfirm.transmission/results`), and
          ;; the confirmation upserts by `:transmission-id`, so the outcome
          ;; below corrects this row rather than adding a second one.
          (let [attempt (record-status!
                         {:transmission-id transmission-id
                          :matter-id (:matter-id plan)
                          :client-id (:client-id plan)
                          :bengoshi-id (:bengoshi-id plan)
                          :today today
                          :thread-id (str "fax-attempt-" transmission-id)
                          :status {:result :pending}})]
            (if-not (:recorded? attempt)
              ;; Nothing is sent. A practice that cannot write down that it is
              ;; about to fax a filing cannot be handed one to fax: the record
              ;; is what a re-send decision is made from, and this is the last
              ;; moment at which refusing is still free.
              (refusal :attempt-not-recordable
                       (str "送達 " transmission-id
                            " の送信を :pending として記録できなかったため、送信しません。"
                            (when-let [v (seq (:violations attempt))]
                              (str "（" (str/join "、" v) "）"))))
              (let [result (lf-workspace/-dispatch
                            (dispatch-port {:send-fn (or send-fn http-send-fn)
                                            :decode decode})
                            {:plan plan :bytes bytes :filename filename
                             :credentials creds
                             :cover {:to (get-in plan [:recipient :name])
                                     :from (:cover-from (settings configuration))
                                     :message (:cover-message (settings configuration))}})]
                (if-not (:ok? result)
                  ;; The row stays `:pending`. Neither refusal carries a
                  ;; verdict this may turn into one: `:provider-rejected` did
                  ;; not say the pages stayed put, and `:provider-unreadable`
                  ;; followed a 2xx, so it very nearly says the opposite.
                  ;; `:recorded-as` tells the caller the 送達 is on the record
                  ;; as possibly transmitted, which is what a re-send has to
                  ;; be decided against.
                  (assoc result :schema schema
                         :transmission-id transmission-id
                         :recorded-as :pending)
                  (merge {:schema schema :ok? true :transmission-id transmission-id}
                         (select-keys result [:provider-id :provider-status :result
                                              :error :dialled :page-count])
                         (record-status! {:transmission-id transmission-id
                                          :matter-id (:matter-id plan)
                                          :client-id (:client-id plan)
                                          :bengoshi-id (:bengoshi-id plan)
                                          :today today
                                          :status result})))))))))))

(defn transmission-by-provider-id
  "The 送達 a provider GUID belongs to, or nil.

  A Dropbox Fax callback names its own transmission and knows nothing about
  this practice's ids, so the join has to happen somewhere. It happens here
  rather than in the practice because it is a lookup and not a rule — but it
  is the reason `dispatch!` records `:provider-id` before anything can call
  back about it. A callback for a GUID this practice never sent matches
  nothing and is refused, which is also the answer for a forged one.

  It is also the whole join, which is why `:provider-unreadable` is a hole
  this cannot cover: the GUID was in the body that would not parse, so a
  transmission the provider accepted there has no id on the record and its
  callback is refused as `:unknown-provider-transmission`. `dispatch!`
  answers that by recording the attempt as `:pending` beforehand — the fax
  is still unjoinable, but it is no longer invisible."
  [store provider-id]
  (when (seq (str provider-id))
    (->> (lf-store/matters store)
         (mapcat #(lf-store/transmissions-of store (:matter-id %)))
         (filter #(= (str provider-id) (str (:provider-id %))))
         first)))

(defn callback-token-ok?
  "Constant-time-ish comparison of the configured callback token.

  The token is a bearer credential in a URL, which is weak, and it is what
  Dropbox Fax's callback mechanism affords. What bounds the damage is the
  callback's *reach*: it can record a status against a 送達 that already
  exists and nothing else — it cannot create one, cannot change a
  destination, and cannot cause anything to be sent."
  [configuration token]
  (let [expected (str (:callback-token (settings configuration)))
        given (str token)]
    (boolean
     (and (seq expected)
          (seq given)
          (= (count expected) (count given))
          (zero? (reduce bit-or 0 (map bit-xor (map int expected) (map int given))))))))

(defn on-callback!
  "A terminal status POSTed back by Dropbox Fax.

  The provider names its own GUID, so the 送達 is resolved from the record by
  that GUID — a callback for something this practice never sent matches
  nothing and is refused. Idempotent by construction: the record upserts by
  `:transmission-id`, so the retry schedule (15 minutes out to 16.5 hours)
  cannot produce duplicates."
  [{:keys [configuration token today payload decode]}]
  (let [decode (or decode #(json/read-str %))
        configuration (or configuration (config/load-config))]
    (cond
      (not (enabled? configuration))
      (refusal :fax-disabled "FAX 送信面は無効です")

      (not (callback-token-ok? configuration token))
      (refusal :callback-token-invalid "callback token が一致しません")

      (not (app-lawfirm/enabled? configuration))
      (refusal :lawfirm-disabled "法律事務所の記録面が無効です")

      :else
      (let [status (parse-response {:status 200 :body payload} decode)]
        (if-not (:ok? status)
          (assoc status :schema schema)
          (let [store (app-lawfirm/practice)
                known (transmission-by-provider-id store (:provider-id status))
                m (when known (lf-store/matter store (:matter-id known)))]
            (if-not known
              (refusal :unknown-provider-transmission
                       (str "provider GUID " (pr-str (:provider-id status))
                            " に対応する送達がこの記録にありません"))
              (merge {:schema schema :ok? true
                      :transmission-id (:transmission-id known)}
                     (select-keys status [:result :provider-status :dialled])
                     (record-status! {:transmission-id (:transmission-id known)
                                      :matter-id (:matter-id known)
                                      :client-id (:client-id m)
                                      :bengoshi-id (:bengoshi-id m)
                                      :today today
                                      :status status})))))))))

(defn status
  "What this surface is, for a client that has to render something when it is
  off. Never throws, and never reports on a credential's value — only on
  whether one could be located."
  ([] (status (config/load-config)))
  ([configuration]
   {:schema schema
    :enabled? (enabled? configuration)
    :provider "dropbox-fax"
    :api "v1 (Dropbox は v3 への移行を告知済み)"
    :credentials-present? (some? (credentials configuration))
    :callback-configured? (seq (str (:callback-token (settings configuration))))}))
