(ns cloud.itonami.app.drive-delivery
  "Personal, action-bound deliveries for Drive content.

  A delivery never exposes the source object's reference.  It stores a new,
  recipient-encrypted package in the selected object store and uses that
  package's content reference as the only public delivery id.  A Biscuit
  binds the package nonce to one document, audience, action and expiry; the
  durable registry supplies revocation and bounded-use state.

  The watermark is visible where changing bytes preserves the format
  (plain text, Markdown, HTML, PNG and JPEG).  Other formats retain a signed
  forensic watermark in the encrypted delivery package and response headers
  rather than corrupting the file by appending arbitrary bytes."
  (:require [biscuit.authorizer :as authorizer]
            [biscuit.token :as biscuit]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [cloud.itonami.app.drive-crypto :as crypto]
            [cloud.itonami.app.store :as store]
            [drive.object :as object]
            [ed25519.core :as ed])
  (:import (java.awt AlphaComposite Color Font RenderingHints)
           (java.awt.image BufferedImage)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute PosixFilePermissions)
           (java.security SecureRandom)
           (java.util Base64 UUID)
           (javax.imageio ImageIO)))

(def schema "cloud.itonami.app.drive-delivery.v1")
(def ^:private seed-bytes 32)
(def ^:private actions #{:view :download :copy})

(defn root-seed-file []
  (io/file (config/data-dir) "drive-delivery-authority.seed"))

(defn- read-seed [file]
  (when (.isFile file)
    (let [value (Files/readAllBytes (.toPath file))]
      (when (= seed-bytes (alength value)) value))))

(defn root-seed []
  (let [file (root-seed-file)]
    (or (read-seed file)
        (let [value (byte-array seed-bytes)]
          (.nextBytes (SecureRandom.) value)
          (io/make-parents file)
          (with-open [out (io/output-stream file)] (.write out value))
          (try
            (Files/setPosixFilePermissions
             (.toPath file) (PosixFilePermissions/fromString "rw-------"))
            (catch UnsupportedOperationException _))
          value))))

(defn root-did [] (ed/did-key-from-seed (root-seed)))

(defn- random-seed []
  (let [value (byte-array seed-bytes)]
    (.nextBytes (SecureRandom.) value)
    value))

(defn- as-bytes ^bytes [value]
  (if (bytes? value) value (byte-array (map unchecked-byte value))))

(defn- sign [seed payload]
  (ed/sign seed (.getBytes ^String payload StandardCharsets/UTF_8)))

(defn- verify [did payload signature]
  (try
    (ed/verify-did did (.getBytes ^String payload StandardCharsets/UTF_8)
                   (as-bytes signature))
    (catch Exception _ false)))

(defn- b64 [^bytes value]
  (.encodeToString (Base64/getEncoder) value))

(defn- unb64 [value]
  (.decode (Base64/getDecoder) ^String value))

(defn- watermark-label [{:keys [watermark audience action issued-at]}]
  (str "Cloud Itonami " watermark " · " audience " · "
       (name action) " · " issued-at))

(defn- watermark-html [^bytes bytes label]
  (let [text (String. bytes StandardCharsets/UTF_8)
        mark (str "<div data-cloud-itonami-watermark=\"true\" style=\""
                  "position:fixed;right:12px;bottom:10px;z-index:2147483647;"
                  "font:12px system-ui;color:#555;background:#fff;padding:4px 7px;"
                  "border:1px solid #aaa;opacity:.82\">"
                  (-> label
                      (str/replace "&" "&amp;")
                      (str/replace "<" "&lt;")
                      (str/replace ">" "&gt;"))
                  "</div>")
        out (if (str/includes? (str/lower-case text) "</body>")
              (str/replace-first text #"(?i)</body>" (str mark "</body>"))
              (str text mark))]
    (.getBytes out StandardCharsets/UTF_8)))

(defn- watermark-image [^bytes bytes media-type label]
  (try
    (when-let [source (ImageIO/read (ByteArrayInputStream. bytes))]
      (let [rgb? (= media-type "image/jpeg")
            image (BufferedImage. (.getWidth source) (.getHeight source)
                                  (if rgb? BufferedImage/TYPE_INT_RGB
                                      BufferedImage/TYPE_INT_ARGB))
            g (.createGraphics image)
            font-size (max 11 (min 28 (quot (.getWidth source) 34)))
            margin (max 8 (quot font-size 2))]
        (try
          (.drawImage g source 0 0 nil)
          (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING
                             RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
          (.setFont g (Font. Font/SANS_SERIF Font/BOLD font-size))
          (let [metrics (.getFontMetrics g)
                width (.stringWidth metrics label)
                x (max margin (- (.getWidth image) width margin))
                y (- (.getHeight image) margin)]
            (.setComposite g (AlphaComposite/getInstance AlphaComposite/SRC_OVER 0.72))
            (.setColor g Color/WHITE)
            (.drawString g label (inc x) (inc y))
            (.setColor g (Color. 30 30 30))
            (.drawString g label x y))
          (finally (.dispose g)))
        (let [out (ByteArrayOutputStream.)
              format (if rgb? "jpg" "png")]
          (when (ImageIO/write image format out)
            (.toByteArray out)))))
    (catch Exception _ nil)))

(defn watermark-bytes
  "Add a visible watermark when the format has a safe built-in writer.

  Returns both bytes and the strength so callers never imply that a signed
  package marker is a visible mark inside an opaque binary format."
  [bytes media-type delivery]
  (let [bytes (as-bytes bytes)
        label (watermark-label delivery)]
    (cond
      (#{"text/plain" "text/markdown"} media-type)
      {:bytes (.getBytes (str (String. bytes StandardCharsets/UTF_8)
                              "\n\n[" label "]\n")
                         StandardCharsets/UTF_8)
       :mode :visible-text}

      (= "text/html" media-type)
      {:bytes (watermark-html bytes label) :mode :visible-html}

      (#{"image/png" "image/jpeg"} media-type)
      (if-let [marked (watermark-image bytes media-type label)]
        {:bytes marked :mode :visible-image}
        {:bytes bytes :mode :signed-package})

      :else {:bytes bytes :mode :signed-package})))

(defn- token-for [{:keys [nonce source-id audience action expires-at watermark]}]
  (let [next-seed (random-seed)]
    ;; Signature implementations return byte arrays, whose printed form is a
    ;; JVM `#object` and therefore cannot be read back as EDN. Biscuit treats
    ;; signatures as bytes, so the wire form is an unsigned-byte vector.
    (update
     (biscuit/authority
      {:facts [['delivery nonce]
               ['document source-id]
               ['holder audience]
               ['operation (name action)]
               ['expires expires-at]
               ['watermark watermark]]
       :rules [] :checks []
       :next-public-key (ed/did-key-from-seed next-seed)
       :root-private-key (root-seed)
       :sign-fn sign})
     :biscuit/blocks
     #(mapv (fn [block] (update block :block/signature
                                 (fn [signature] (vec (as-bytes signature))))) %))))

(defn- authorize-token [token delivery actor requested-action now-ms]
  (let [{:keys [nonce source-id action expires-at]} delivery]
    (cond
      (not= actor (:audience delivery)) {:allowed? false :reason :audience-mismatch}
      (not= requested-action action) {:allowed? false :reason :action-mismatch}
      (<= (long expires-at) (long now-ms)) {:allowed? false :reason :expired}
      :else
      (authorizer/authorize
       token
       {:root-public-key (root-did)
        :verify-fn verify
        :facts []
        :policies [{:kind :allow
                    :body [['delivery nonce]
                           ['document source-id]
                           ['holder actor]
                           ['operation (name requested-action)]
                           ['expires expires-at]
                           ['watermark (:watermark delivery)]]}]}))))

(defn- registry-path [delivery-id]
  [:drive :deliveries delivery-id])

(defn issue!
  "Create one recipient/action-bound encrypted delivery object.

  `content-ref` must return the reference the selected store will resolve. In
  production that is the Kotobase CID."
  [{:keys [source-id source-version owner audience action expires-at max-uses
           filename media-type bytes content-ref object-store]
    :as request}]
  (let [action (keyword action)]
    (when-not (actions action)
      (throw (ex-info "Delivery action must be view, download or copy"
                      {:type :drive/delivery-action-invalid :action action})))
    (when-not (and (string? audience) (not (str/blank? audience)))
      (throw (ex-info "Delivery audience is required"
                      {:type :drive/delivery-audience-required})))
    (let [nonce (str (UUID/randomUUID))
          issued-at (store/now)
          watermark (str "CI-" (subs (str/replace nonce "-" "") 0 16))
          base {:schema schema :nonce nonce :source-id source-id
                :source-version source-version :owner owner :audience audience
                :action action :issued-at issued-at :expires-at (long expires-at)
                :max-uses (long max-uses) :watermark watermark}
          marked (watermark-bytes bytes media-type base)
          token (token-for base)
          package (assoc base :filename filename :media-type media-type
                         :watermark-mode (:mode marked)
                         :payload-b64 (b64 ^bytes (:bytes marked))
                         :biscuit token)
          sealed (crypto/seal-for (distinct [owner audience]) nonce
                                  (.getBytes (pr-str package) StandardCharsets/UTF_8))
          delivery-id (content-ref sealed)
          record (assoc (dissoc package :payload-b64 :biscuit)
                        :id delivery-id :uses 0 :revoked? false)]
      (object/-put-object object-store delivery-id sealed)
      (store/transact! assoc-in (registry-path delivery-id) record)
      {:schema schema :ok? true :delivery-id delivery-id :action (name action)
       :audience audience :expires-at expires-at :max-uses max-uses
       :watermark watermark :watermark-mode (name (:mode marked))
       :url (str "/api/workspace/drive/deliveries/" delivery-id "/"
                 (name action))})))

(defn- delivery-record! [delivery-id]
  (or (get-in (store/snapshot) (registry-path delivery-id))
      (throw (ex-info "Delivery does not exist"
                      {:type :drive/delivery-not-found :delivery-id delivery-id}))))

(defn- preflight!
  "Reject an unusable delivery before touching its encrypted object.

  In particular, an unrelated principal must not learn whether they happen
  to possess a key envelope for an existing delivery.  `claim!` repeats the
  same checks atomically immediately before consuming a use."
  [delivery actor requested-action now-ms]
  (cond
    (:revoked? delivery)
    (throw (ex-info "Delivery was revoked" {:type :drive/delivery-revoked}))
    (not= actor (:audience delivery))
    (throw (ex-info "Delivery belongs to another audience"
                    {:type :drive/delivery-audience-mismatch}))
    (not= requested-action (:action delivery))
    (throw (ex-info "Delivery cannot perform this action"
                    {:type :drive/delivery-action-mismatch}))
    (<= (long (:expires-at delivery)) (long now-ms))
    (throw (ex-info "Delivery expired" {:type :drive/delivery-expired}))
    (>= (long (:uses delivery)) (long (:max-uses delivery)))
    (throw (ex-info "Delivery use limit reached"
                    {:type :drive/delivery-use-limit})))
  delivery)

(defn- claim! [delivery-id actor requested-action now-ms]
  (let [claimed (atom nil)]
    (store/transact!
     (fn [state]
       (let [delivery (get-in state (registry-path delivery-id))]
         (cond
           (nil? delivery)
           (throw (ex-info "Delivery does not exist" {:type :drive/delivery-not-found}))
           (:revoked? delivery)
           (throw (ex-info "Delivery was revoked" {:type :drive/delivery-revoked}))
           (not= actor (:audience delivery))
           (throw (ex-info "Delivery belongs to another audience"
                           {:type :drive/delivery-audience-mismatch}))
           (not= requested-action (:action delivery))
           (throw (ex-info "Delivery cannot perform this action"
                           {:type :drive/delivery-action-mismatch}))
           (<= (long (:expires-at delivery)) (long now-ms))
           (throw (ex-info "Delivery expired" {:type :drive/delivery-expired}))
           (>= (long (:uses delivery)) (long (:max-uses delivery)))
           (throw (ex-info "Delivery use limit reached"
                           {:type :drive/delivery-use-limit}))
           :else
           (let [next (-> delivery
                          (update :uses inc)
                          (assoc :last-used-at (store/now)))]
             (reset! claimed next)
             (assoc-in state (registry-path delivery-id) next))))))
    @claimed))

(defn redeem!
  "Verify, authorize and consume one use of a delivery."
  [delivery-id actor requested-action now-ms object-store]
  (let [requested-action (keyword requested-action)
        delivery (preflight! (delivery-record! delivery-id) actor
                             requested-action now-ms)
        sealed (or (object/-get-object object-store delivery-id)
                   (throw (ex-info "Delivery object is unavailable"
                                   {:type :drive/delivery-object-missing})))
        package (-> (crypto/open actor sealed)
                    as-bytes
                    (String. StandardCharsets/UTF_8)
                    edn/read-string)
        decision (authorize-token (:biscuit package) delivery actor
                                  requested-action now-ms)]
    (when-not (and (= schema (:schema package))
                   (= (:nonce delivery) (:nonce package))
                   (= delivery-id (:id delivery)))
      (throw (ex-info "Delivery package does not match its registry record"
                      {:type :drive/delivery-package-mismatch})))
    (when-not (:allowed? decision)
      (throw (ex-info "Delivery authorization refused"
                      {:type :drive/delivery-not-authorized
                       :reason (:reason decision)})))
    (let [claimed (claim! delivery-id actor requested-action now-ms)]
      {:schema schema :ok? true :delivery-id delivery-id
       :source-id (:source-id claimed) :action requested-action
       :filename (:filename package) :media-type (:media-type package)
       :watermark (:watermark package)
       :watermark-mode (:watermark-mode package)
       :uses (:uses claimed) :max-uses (:max-uses claimed)
       :bytes (unb64 (:payload-b64 package))})))

(defn revoke! [delivery-id actor]
  (let [delivery (delivery-record! delivery-id)]
    (when-not (= actor (:owner delivery))
      (throw (ex-info "Only the source owner may revoke a delivery"
                      {:type :drive/delivery-not-owner})))
    (store/transact! assoc-in (conj (registry-path delivery-id) :revoked?) true)
    {:schema schema :ok? true :delivery-id delivery-id :revoked? true}))
