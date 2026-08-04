(ns cloud.itonami.app.mail-send
  "Sending mail, from whichever account it is being sent as.

  Until now this app could not send mail. Not *could not send well* — there
  was no code anywhere in it that delivered a message to anybody, while the
  inbox it showed had a reply affordance and the archive it read was full of
  conversations. A mailbox you can only read is a strange object to have
  built, and the reason it happened is that reading was two HTTP APIs and
  sending is a third thing, SMTP, that nothing here spoke.

  Two routes out, chosen by what the account already proved:

  - **OAuth accounts** send through their own provider API. The grant that
    reads the mailbox can send from it, and asking somebody for an app
    password as well — to send from an account this app is already
    authenticated to — would be collecting a second credential for a job the
    first one covers.
  - **IMAP accounts** send over SMTP with the password they were registered
    with, through `kotoba-lang/org-ietf-smtp`.

  The message itself is built once, by `mail.message`, and validated before
  either route sees it. A malformed recipient should be refused here, where
  the error names the field, rather than at a provider, where it comes back
  as a 400 with a body nobody reads."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [cloud.itonami.app.mail-account :as account]
            [cloud.itonami.app.mail-gmail :as gmail]
            [cloud.itonami.app.store :as store]
            [mail.message :as message]
            [mail.receipt :as receipt]
            [smtp.client :as smtp])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def schema "cloud.itonami.app.mail-send.v1")

(defn- addresses
  "One or many recipients as a vector, however they were given."
  [value]
  (cond
    (nil? value) []
    (string? value) (->> (str/split value #"[,;]")
                         (map str/trim)
                         (remove str/blank?)
                         vec)
    (sequential? value) (vec (remove str/blank? (map (comp str/trim str) value)))
    :else []))

(defn- draft!
  "The message, checked.

  Through `mail.message` rather than by assembling headers here: it already
  knows what a valid address is and what a message is missing, and this app
  having a second opinion about that is how the two come to disagree."
  [account {:keys [to cc subject text in-reply-to]}]
  (let [envelope (message/message {:from (:address account)
                                   :to (addresses to)
                                   :cc (addresses cc)
                                   :subject (str subject)
                                   :text (str text)
                                   :headers (cond-> {}
                                              (not (str/blank? (str in-reply-to)))
                                              (assoc "In-Reply-To"
                                                     (str in-reply-to)))})
        errors (message/validation-errors envelope)]
    (when (seq errors)
      (throw (ex-info "メールの内容が不正です。"
                      {:type :mail/invalid-message :errors (vec errors)})))
    envelope))

;; ---------------------------------------------------------------------------
;; RFC 2822 assembly, for the routes that want a whole message

(defn- encode-header
  "A header value that may contain non-ASCII, as RFC 2047 encoded-words.

  A raw UTF-8 subject line is not legal in a header and providers treat it
  inconsistently — some pass it, some mangle it, some refuse the message. A
  Japanese subject is the ordinary case here, not an edge one."
  [value]
  (let [value (str value)]
    (if (every? #(< (int %) 128) value)
      value
      (str "=?UTF-8?B?"
           (.encodeToString (Base64/getEncoder)
                            (.getBytes value StandardCharsets/UTF_8))
           "?="))))

(defn- email
  "The address out of `mail.message`'s normalized address map.

  `mail.message` stores `{:mail.address/email \"a@b\"}` rather than a string,
  so a header built by interpolating the map directly reads
  `To: {:mail.address/email \"a@b\"}` — which is a well-formed header
  containing a Clojure map, and every provider rejects it."
  [address]
  (:mail.address/email address))

(defn- emails [addresses] (mapv email addresses))

(defn- rfc2822
  "The whole message as text, for a route that takes one."
  [envelope]
  (let [header-lines
        (cond-> [(str "From: " (email (:mail/from envelope)))
                 (str "To: " (str/join ", " (emails (:mail/to envelope))))]
          (seq (:mail/cc envelope))
          (conj (str "Cc: " (str/join ", " (emails (:mail/cc envelope)))))
          true
          (conj (str "Subject: " (encode-header (:mail/subject envelope))))
          (get-in envelope [:mail/headers "In-Reply-To"])
          (conj (str "In-Reply-To: "
                     (get-in envelope [:mail/headers "In-Reply-To"]))
                (str "References: "
                     (get-in envelope [:mail/headers "In-Reply-To"])))
          true
          (conj "MIME-Version: 1.0"
                "Content-Type: text/plain; charset=\"UTF-8\""
                "Content-Transfer-Encoding: base64"))
        body (:mail.part/body (first (:mail/parts envelope)))]
    (str (str/join "\r\n" header-lines)
         "\r\n\r\n"
         ;; base64, so a body with non-ASCII in it survives a transport that
         ;; is only guaranteed to carry 7 bits.
         (->> (.encodeToString (Base64/getEncoder)
                               (.getBytes (str body) StandardCharsets/UTF_8))
              (partition-all 76)
              (map #(apply str %))
              (str/join "\r\n")))))

(defn- base64url [text]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str text) StandardCharsets/UTF_8)))

;; ---------------------------------------------------------------------------
;; Routes

(defn- send-over-smtp!
  [account envelope]
  (let [{:keys [host port username]} (:smtp account)
        password (account/password (:id account))]
    (when (str/blank? password)
      (throw (ex-info "このアカウントのパスワードが Keychain にありません。再登録してください。"
                      {:type :mail/missing-credential :id (:id account)})))
    (let [recipients (emails (concat (:mail/to envelope) (:mail/cc envelope)))
          session (-> (smtp/connect! host {:port port})
                      (smtp/ehlo! "cloud-itonami-app")
                      (smtp/auth-login! username password)
                      (assoc :from (email (:mail/from envelope))))]
      (try
        ;; One RCPT TO per recipient. `smtp.client/send-mail!` takes a single
        ;; `:to`, so a message addressed to three people is three sends of the
        ;; same body rather than one — which is what its README says it does
        ;; and not something to work around by silently dropping recipients.
        (doseq [recipient recipients]
          (smtp/send-mail! session
                           {:to recipient
                            :subject (encode-header (:mail/subject envelope))
                            :body (:mail.part/body
                                   (first (:mail/parts envelope)))
                            :in-reply-to (get-in envelope
                                                 [:mail/headers "In-Reply-To"])}))
        {:accepted recipients}
        (finally
          (try (smtp/quit! session) (catch Exception _ nil)))))))

(defn- send-over-gmail!
  [account envelope thread-id]
  (let [result (gmail/send! account {:raw (base64url (rfc2822 envelope))
                                     :thread-id thread-id})]
    {:provider-message-id (:id result)
     :thread-id (:threadId result)}))

(defn- send-over-graph!
  "Microsoft Graph `sendMail`.

  Left as a direct call rather than routed through a client library because
  there is no `com-microsoft-graph` in this workspace to route it through;
  writing one for a single endpoint would be inventing a library rather than
  using one. If Graph grows a second caller here, that is when it earns one."
  [account envelope]
  (let [token (account/access-token! account)
        body {:message
              {:subject (:mail/subject envelope)
               :body {:contentType "Text"
                      :content (:mail.part/body (first (:mail/parts envelope)))}
               :toRecipients (mapv #(hash-map :emailAddress {:address %})
                                   (emails (:mail/to envelope)))
               :ccRecipients (mapv #(hash-map :emailAddress {:address %})
                                   (emails (:mail/cc envelope)))}
              :saveToSentItems true}
        request (-> (java.net.http.HttpRequest/newBuilder
                     (java.net.URI/create
                      "https://graph.microsoft.com/v1.0/me/sendMail"))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Content-Type" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                            (json/write-str body)))
                    .build)
        response (.send (java.net.http.HttpClient/newHttpClient) request
                        (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode response) 299)
      (throw (ex-info "メールを送信できませんでした。"
                      {:type :mail/send-failed
                       :status (.statusCode response)})))
    ;; Graph's sendMail answers 202 with an empty body — it accepts the
    ;; message and does not name it. There is genuinely no provider id to
    ;; report here, and inventing one that looks like Graph's would be worse
    ;; than the receipt saying so.
    {:accepted (emails (:mail/to envelope))}))

;; ---------------------------------------------------------------------------

(defn send!
  "Send `request` as `account-id`, and record that it was sent.

  The receipt is `mail.receipt`'s, not a shape invented here, and it is
  written whether or not the provider handed back an id — a message that left
  is a fact about this workspace even when the far end declined to name it."
  [account-id request {:keys [user-did]}]
  (let [account (account/account! account-id user-did)
        envelope (draft! account request)
        result (case (:kind account)
                 :imap (send-over-smtp! account envelope)
                 :gmail (send-over-gmail! account envelope
                                          (:thread-id request))
                 :microsoft (send-over-graph! account envelope))
        now (store/now)
        ;; `mail.receipt` insists on a provider message-id and is right to:
        ;; a receipt whose id is nil cannot be matched to anything later. The
        ;; providers that do not return one (Graph answers 202 and an empty
        ;; body) get a locally-minted id that says where it came from, so
        ;; nothing downstream mistakes it for an id the provider would know.
        sent (receipt/receipt {} (:kind account)
                              {:message-id (or (:provider-message-id result)
                                               (str "local:" (store/new-id "mail")))
                               :status :sent}
                              {:mail.receipt/sent-at now})]
    (store/transact!
     (fn [state]
       (-> state
           (update-in [:mail :sent] (fnil conj [])
                      {:id (:mail.receipt/provider-message-id sent)
                       :account-id account-id
                       :to (emails (:mail/to envelope))
                       :cc (emails (:mail/cc envelope))
                       :subject (:mail/subject envelope)
                       :thread-id (or (:thread-id result) (:thread-id request))
                       :sent-at now})
           (update :events conj {:type :mail/sent :at now
                                 :account-id account-id}))))
    {:schema schema :ok? true
     :account-id account-id
     :id (:mail.receipt/provider-message-id sent)
     :to (emails (:mail/to envelope))
     :subject (:mail/subject envelope)
     :thread-id (or (:thread-id result) (:thread-id request))
     :sent-at now}))
