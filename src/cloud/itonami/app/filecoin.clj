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
    HTTP from FilBeam, which serves content already on Filecoin. That path is
    real network retrieval.

  ## What is missing, and why writes stop here

  Putting data *on* Filecoin needs three things this app cannot do today:

  1. **A provider upload API.** `cloud-filecoin` covers the on-chain half —
     PieceCID, PDP calldata, Filecoin Pay, Warm Storage — and nothing else in
     this workspace implements a storage provider's HTTP transfer surface.
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
            [filecoin.client :as client]
            [filecoin.cloud.chain :as chain]
            [filecoin.cloud.evm :as evm]
            [filecoin.cloud.pdp :as pdp]
            [filecoin.cloud.piece :as piece]
            [filecoin.protocols :as p]
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

(defn- retrieval-url [ref]
  (str "https://" (:retrieval-domain (chain/chain network)) "/piece/" ref))

(defn- fetch-piece
  "Read-through retrieval for a piece that is not staged. Returns bytes or
  nil — a miss is an ordinary outcome here, not an error."
  [http ref]
  (try
    (let [{:keys [status body]} (p/request http {:method :get
                                                    :url (retrieval-url ref)
                                                    :headers {}})]
      (when (<= 200 status 299)
        (.getBytes ^String body "ISO-8859-1")))
    (catch Exception _ nil)))

(defn store
  "An `IObjectStore` over PieceCID-addressed staging with FilBeam
  read-through.

  `put` **verifies** the reference against the content rather than trusting
  it. A store that let a caller file bytes under someone else's PieceCID
  would be content-addressed in name only."
  ([] (store (transport/http)))
  ([http]
   (object/store-of
    {:put-object
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
           (fetch-piece http ref))))

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
     :staged-pieces (staged-pieces)
     :write-status "not-implemented"
     :write-reason
     (str "Putting data on Filecoin needs a storage provider's upload API "
          "(not implemented in this workspace), plus funds for addPieces and "
          "a payment rail. This store addresses and stages; it does not "
          "create a deal.")}))

(defn sample
  "A PieceCID computed here and now, so the addressing is visible rather than
  asserted. Offline — no network, no funds, no provider."
  ([] (sample "cloud-itonami-app · filecoin piece addressing"))
  ([text]
   (let [bytes (.getBytes ^String text "UTF-8")]
     (assoc (piece-of bytes) :text text :bytes (alength bytes)))))
