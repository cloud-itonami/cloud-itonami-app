(ns cloud.itonami.app.filecoin
  "Filecoin as a `drive` object store, and the live chain surface behind it.

  ## What this is, precisely

  `drive`'s `IObjectStore` is a mutable key→bytes store. Filecoin Onchain
  Cloud is not one, so this is **not** \"Drive on Filecoin\" and the naming
  here avoids saying so. What it is:

  - **Addressing.** A reference is the content's **PieceCID v2** (FRC-0069),
    computed by `filecoin.cloud.piece` — the same identifier a storage
    provider and the PDP contract would use. Nothing else in this app has a
    content-derived reference; Drive currently uses relative paths.
  - **A local staging area.** Bytes live under the app's data directory,
    keyed by their PieceCID. This is where a piece would sit *before* a deal.
  - **Read-through retrieval.** A piece that is not staged is fetched over
    HTTP from a storage provider. That path is real network retrieval, and it
    **verifies**: bytes that do not hash back to the PieceCID they were asked
    for are discarded (see `fetch-piece`).

  ## Retrieval is configured, not guessed

  There are two URL shapes and this app can only use one of them unaided:

      provider   <serviceURL>/piece/<cid>          needs a serviceURL
      FilBeam    https://<client-address>.<domain>/<cid>   needs a client address

  The CDN form is a **per-client subdomain**. Set `FILECOIN_PROVIDER_URL`
  and/or `FILECOIN_CLIENT_ADDRESS` to enable each; with neither set,
  read-through is off and a miss is a miss.

  Provider *discovery* would remove the configuration, and is the one part of
  the provider surface `cloud-filecoin` still lacks — decoding the SP
  registry's `getProviderWithProduct` struct. Until then, guessing a provider
  URL would be worse than asking for one.

  ## What is missing, and why writes stop here

  Putting data *on* Filecoin needs three things, and this app has none of
  them:

  1. **A data set.** `filecoin.cloud.provider` now implements the transfer
     surface (`POST pdp/piece` → `PUT pdp/piece/upload/<id>`), so the upload
     itself is expressible. But an upload alone stores nothing durably: the
     bytes are only *parked* until an on-chain `addPieces` makes them part of
     a data set someone is paid to prove.
  2. **Funds.** `addPieces` and opening a payment rail are transactions.
  3. **A funded key.** Which the agent that wrote this deliberately does not
     hold.

  So `-put-object` stages and addresses; it does not create a deal. That is
  stated in the API response as `:deal/status \"not-implemented\"` rather
  than left for a reader to discover.

  `-delete-object` removes the staged copy only. It cannot unmake a piece a
  provider is proving — nothing can, which is the point of the proof — so it
  never claims to."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.config :as config]
            [drive.object :as object]
            [kotoba.bytes :as b]
            [filecoin.client :as client]
            [filecoin.cloud.chain :as chain]
            [filecoin.cloud.evm :as evm]
            [filecoin.cloud.pdp :as pdp]
            [filecoin.cloud.piece :as piece]
            [filecoin.cloud.provider :as provider]
            [filecoin.transport :as transport]))

(def schema "cloud.itonami.app.filecoin.v1")

(def ^:private network
  "Reads go to mainnet: the on-chain surface below is public and read-only, so
  there is nothing to gain from pointing it at a testnet whose numbers mean
  less."
  :mainnet)

(defn staging-dir []
  (doto (io/file (config/data-dir) "filecoin-pieces") (.mkdirs)))

(defn- staged-file [ref]
  (io/file (staging-dir) (str ref ".bin")))

;; ── addressing ───────────────────────────────────────────────────────────────

(defn piece-of
  "Bytes → the piece: its PieceCID and the sizes that follow from it.

  Pure and offline. This is the part of Filecoin that is available without a
  provider, funds or a network."
  [bytes]
  (let [p (piece/calculate (vec (map #(bit-and (int %) 0xff) (seq bytes))))]
    (select-keys p [:cid :height :padding :size :padded-size])))

(defn piece-ref
  "The reference to store bytes under — their PieceCID.

  `drive` lets the caller choose a reference (\"a content hash, a uuid, a
  path\"), so choosing the PieceCID makes the store content-addressed without
  `drive` needing to know what a PieceCID is."
  [bytes]
  (:cid (piece-of bytes)))

;; ── the store ────────────────────────────────────────────────────────────────

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

(defn- config-of
  "Explicit options win over the environment, so this is testable without
  mutating the process."
  [opts]
  {:provider-url (or (:provider-url opts) (env "FILECOIN_PROVIDER_URL"))
   :client-address (or (:client-address opts) (env "FILECOIN_CLIENT_ADDRESS"))})

(defn retrieval-urls
  "Every URL this app is configured to read `ref` from, provider first.

  Empty when neither is configured — a real state, not a misconfiguration to
  paper over with a default."
  ([ref] (retrieval-urls ref nil))
  ([ref opts]
   (let [{:keys [provider-url client-address]} (config-of opts)
         domain (:retrieval-domain (chain/chain network))]
     (cond-> []
       provider-url (conj {:kind "provider"
                           :url (provider/piece-url provider-url ref)})
       client-address (conj {:kind "cdn"
                             :url (provider/cdn-url client-address domain ref)})))))

(defn- get-bytes
  "`GET url` → raw bytes, bypassing `filecoin.protocols/IHttp`.

  IHttp specifies `body` as a **String** and `filecoin.transport` builds it
  with `BodyHandlers/ofString`, which decodes as UTF-8 and rewrites every byte
  above 0x7f. A piece cannot survive that, so this uses `ofByteArray`
  directly. Widening IHttp is the fix and belongs in `io-filecoin`; until then
  this bypass is deliberate and is why `http` is unused on this path.

  (The previous version read `(.getBytes body \"ISO-8859-1\")`, which looks
  like a latin-1 round trip but is not one — the damage happened during
  decoding, before this code saw the string.)"
  [^String url]
  (let [client (java.net.http.HttpClient/newHttpClient)
        req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                (.timeout (java.time.Duration/ofSeconds 30))
                (.GET)
                (.build))
        resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :bytes (.body resp)}))

(defn- fetch-piece
  "Read-through retrieval for a piece that is not staged. Returns bytes or
  nil — a miss is an ordinary outcome here, not an error.

  **Every response is verified against the PieceCID before it is returned.**
  Not defensive habit: measured against mainnet on 2026-07-30, of 13 providers
  reporting they held one live piece, 1 served a 27-byte nginx placeholder as
  `application/octet-stream` and 10 served an identical wrong 81,918 bytes —
  all with status 200. Eleven of thirteen were indistinguishable from success
  at the HTTP layer. Unverified read-through would hand those bytes to `drive`
  under a reference they do not belong to, which is the one failure a
  content-addressed store must not have."
  [fetch ref opts]
  (some (fn [{:keys [url]}]
          (try
            (let [{:keys [status bytes]} (fetch url)]
              (when (<= 200 status 299)
                (let [v (provider/verify-bytes ref (b/->bytes bytes))]
                  (when (:ok? v) bytes))))
            (catch Exception _ nil)))
        (retrieval-urls ref opts)))

(defn store
  "An `IObjectStore` over PieceCID-addressed staging with verified
  read-through.

  `opts` may carry `:provider-url` / `:client-address` to override the
  environment, and `:fetch` to replace the HTTP GET (which is how the
  verification path is tested offline).

  `put` **verifies** the reference against the content rather than trusting
  it. A store that let a caller file bytes under someone else's PieceCID
  would be content-addressed in name only."
  ([] (store {}))
  ([{:keys [fetch] :as opts}]
   (object/store-of
    {;; `drive` hands the write side a vector of unsigned ints — that is the
     ;; protocol's shape, and `write-item` normalises to it before it measures
     ;; quota. `io/copy` and `piece-ref` both want a real array, so this is
     ;; where that is said. Without it a write through `drive.object/write-item`
     ;; reaches `io/copy` as a vector and fails; direct `-put-object` callers
     ;; passing an array still work, because the coercion is total.
     :bytes-out (fn [v] (byte-array (map unchecked-byte (b/->bytes v))))

     :put-object
     (fn [ref bytes]
       (let [computed (piece-ref bytes)]
         (when-not (= ref computed)
           (throw (ex-info "filecoin: reference is not this content's PieceCID"
                           {:given ref :computed computed})))
         (io/copy bytes (staged-file ref))
         {:ok? true :ref ref :deal/status "not-implemented"}))

     :get-object
     (fn [ref]
       (let [f (staged-file ref)]
         (if (.isFile f)
           (with-open [in (io/input-stream f)]
             (let [out (java.io.ByteArrayOutputStream.)]
               (io/copy in out)
               (.toByteArray out)))
           (fetch-piece (or fetch get-bytes) ref opts))))

     :delete-object
     (fn [ref]
       ;; the staged copy only — a piece a provider is proving cannot be
       ;; unmade, and this does not pretend otherwise
       (let [f (staged-file ref)]
         {:ok? true :staged-removed? (boolean (and (.isFile f) (.delete f)))
          :note "staged copy only; an on-chain piece is not affected"}))

     :exists?
     (fn [ref] (.isFile (staged-file ref)))})))

;; ── the live chain surface ───────────────────────────────────────────────────

(defn- staged-pieces []
  (->> (.listFiles (staging-dir))
       (filter #(.isFile ^java.io.File %))
       (map (fn [^java.io.File f]
              {:ref (str/replace (.getName f) #"\.bin$" "")
               :bytes (.length f)}))
       (sort-by :ref)
       vec))

(defn- read-contract [http contract-key calldata types]
  (client/read-contract
   http (:rpc (chain/chain network))
   (evm/call-message network contract-key calldata
                     {:from (chain/eth->f4 (chain/contract network :pdp-verifier))
                      :nonce 0 :gas-limit 100000000
                      :gas-fee-cap "0" :gas-premium "0"})
   types))

(defn status
  "What this app can actually say about Filecoin right now.

  Every `:chain/*` value below is read live from mainnet through
  `filecoin.client`; the two `:pdp/*` values are real calls against the
  deployed PDPVerifier, executed with `StateCall` so they cost nothing and put
  nothing on chain."
  []
  (let [http (transport/http {:timeout-ms 15000})
        c (chain/chain network)
        rpc (:rpc c)
        safe (fn [f] (try (f) (catch Exception e {:error (.getMessage e)})))]
    ;; FLAT keys on purpose. `clojure.data.json/write-str` drops keyword
    ;; namespaces silently, so `:chain/height` serialises as `"height"` and
    ;; `:write/status` as `"status"` — the reader sees neither an error nor the
    ;; name it asked for. Namespaced keys read better in Clojure and lie at
    ;; this boundary; the first version of this map had them and the UI showed
    ;; a column of dashes.
    {:schema schema
     :network (name network)
     :chain-id (:chain-id c)
     :chain-height (safe #(client/height http rpc))
     :chain-network-name (safe #(client/network-name http rpc))
     :pdp-verifier (chain/contract network :pdp-verifier)
     :pdp-verifier-f4 (chain/eth->f4 (chain/contract network :pdp-verifier))
     :pdp-next-data-set-id
     (safe #(first (read-contract http :pdp-verifier
                                  (pdp/call :get-next-data-set-id []) ["uint256"])))
     :pdp-challenge-finality
     (safe #(first (read-contract http :pdp-verifier
                                  (pdp/call :get-challenge-finality []) ["uint256"])))
     :warm-storage (chain/contract network :warm-storage)
     :filecoin-pay (chain/contract network :filecoin-pay)
     :retrieval-domain (:retrieval-domain c)
     :retrieval-urls (mapv :kind (retrieval-urls "<pieceCID>"))
     :retrieval-note
     (if (seq (retrieval-urls "<pieceCID>"))
       (str "Read-through is on. Every response is checked by recomputing the "
            "PieceCID; bytes that do not match are discarded.")
       (str "Read-through is off — set FILECOIN_PROVIDER_URL (a provider's "
            "serviceURL) or FILECOIN_CLIENT_ADDRESS (FilBeam serves each "
            "client from its own subdomain). Neither is guessed."))
     :staged-pieces (staged-pieces)
     :write-status "not-implemented"
     :write-reason
     (str "The provider transfer surface exists now (filecoin.cloud.provider), "
          "but an upload only parks bytes: they become durable when an "
          "on-chain addPieces adds them to a data set, which needs funds and "
          "a funded key. This store addresses and stages; it does not create "
          "a deal.")}))

(defn sample
  "A PieceCID computed here and now, so the addressing is visible rather than
  asserted. Offline — no network, no funds, no provider."
  ([] (sample "cloud-itonami-app · filecoin piece addressing"))
  ([text]
   (let [bytes (.getBytes ^String text "UTF-8")]
     (assoc (piece-of bytes) :text text :bytes (alength bytes)))))
