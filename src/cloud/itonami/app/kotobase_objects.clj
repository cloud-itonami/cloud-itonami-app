(ns cloud.itonami.app.kotobase-objects
  "Drive bytes as UnixFS blocks in the kotobase.net archive.

  The third `IObjectStore` in this app, and the first whose references are
  IPFS CIDs. What each of the three actually is, since the shared interface
  invites the assumption that they are interchangeable and they are not:

  | store | reference | addressed by |
  |---|---|---|
  | `drive.store.fs` / `storj` | `obj-<uuid>` chosen by the caller | key |
  | `filecoin` | PieceCID v2 (FRC-0069) | content, for a deal |
  | this | UnixFS CIDv1 | content, and readable by any IPFS client |

  A reference here is exactly what `ipfs add -Q --cid-version=1
  --raw-leaves` prints for the same bytes. That is the property worth
  having: the identity is not this application's, so nothing has to be
  exported to be readable elsewhere, and two people uploading the same file
  store one object without anyone implementing deduplication.

  ## The ACL does not reach the bytes

  `GET https://kotobase.net/ipfs/{cid}` is unauthenticated. `drive` decides
  who may reach a reference *through this application* and cannot decide
  who may fetch the bytes once a CID is known — which is what a public
  content-addressed archive is, not a gap in it.

  So the ACL here guards the reference, not the content, and **anything
  whose confidentiality depends on that ACL does not belong in this
  store**. It is why the store is opt-in rather than the default, and why
  repository state stays where ADR-0013 put it: sealed before it reaches
  any transport. Said here because the seam looks identical to the other
  two stores, where it is not true.

  ## Identity and location are different strings

  `kotobase.archive-put` accepts raw CIDv1 only. A file of one chunk is a
  raw block, so its identity is already its location. A larger file has a
  dag-pb root, and that block is archived under the raw spelling of the
  same digest — `cloud.itonami.app.archive/location-cid`, a codec byte, not
  a lookup. The Drive stores the identity; the archive is told the
  location. Writing the location into `:drive/object-ref` would put a CID
  in the Drive that no IPFS client resolves to this file (ADR-2608148200).

  ## What a write costs

  One request per block: a 100 MiB upload is 400 leaves plus 3 nodes. That
  is the number to watch and the reason `ADR-2608160100` prefers packed
  blocks — but a pack needs range reads, and the archive plane serves whole
  objects. Chunking is not the part that could be avoided: it is what makes
  the CID the real one.

  ## Deletion is not a thing an archive does

  `-delete-object` does nothing and says so. The archive has no delete
  route, and a content-addressed store could not honour one anyway while
  another holder still points at the same bytes — which is the case
  `drive.workspace/forget-item`'s `:keep-ref?` exists for. Forgetting a
  reference removes the Drive's path to the bytes; it does not remove the
  bytes. Anything that must be *destroyed* rather than unlinked does not
  belong in this store, and this is the sentence that says so before
  someone finds out."
  (:require [cloud.itonami.app.archive :as archive]
            [drive.object :as object]
            [unixfs.file :as unixfs]))

(def ^:const chunk-size
  "The `ipfs add` default. Not a tuning knob — a different value is a
  different CID for the same bytes, and the point of this store is that the
  CID is the one everyone else computes."
  262144)

(defprotocol IContentAddressed
  "A store that derives its own references from the bytes.

  `drive.object/write-item` requires the *caller* to name the reference,
  for a reason `drive` states plainly: a reference it could generate is a
  reference it would need randomness or hashing for, and it has neither.
  That leaves the app choosing, and choosing correctly means asking the
  store — a UUID handed to this store would be a name for bytes that
  already have one.

  So this is the question `documents` asks before it invents an `obj-…`.
  It lives here rather than in `drive` because it is about which store is
  in use, which is an application fact; `filecoin` could implement it too
  and would then stop being a special case at its one call site."
  (content-ref [store bytes]
    "The reference `bytes` must be stored under."))

(extend-protocol IContentAddressed
  nil
  (content-ref [_ _] nil))

(defn- put-block!
  [transport {:keys [cid bytes]}]
  (let [location (archive/location-cid cid)
        {:keys [status body]} ((:put! transport) {:cid location :bytes bytes})]
    (when-not (<= 200 status 299)
      (throw (ex-info "kotobase archive refused a block"
                      ;; The body, not just the status. A 400 says which of
                      ;; the archive's rules was broken and a status alone
                      ;; has sent people looking at the wrong layer.
                      {:cid cid :location location :status status
                       :body (some-> body str (subs 0 (min 400 (count (str body)))))})))))

(defn- get-block
  [transport cid]
  (let [{:keys [status bytes]} ((:get-bytes transport) (archive/location-cid cid))]
    (when (<= 200 status 299) bytes)))

(defrecord KotobaseObjectStore [transport]
  IContentAddressed
  (content-ref [_ bytes]
    (unixfs/cid bytes {:chunk-size chunk-size}))

  object/IObjectStore
  (-put-object [_ ref bytes]
    (let [{:keys [cid blocks]} (unixfs/build bytes {:chunk-size chunk-size})]
      (when-not (= ref cid)
        ;; The caller named a reference these bytes do not have. Storing
        ;; them anyway would put a CID in the Drive that resolves to
        ;; something else — the one failure a content-addressed store must
        ;; not have.
        (throw (ex-info "kotobase-objects: reference is not the content's CID"
                        {:asked ref :computed cid})))
      ;; `:blocks` is leaves first, root last, and this loop keeps that
      ;; order: a root reachable before its children are stored is a CID
      ;; that resolves to a hole for as long as the write takes.
      (doseq [block blocks] (put-block! transport block))
      {:ok? true :ref cid :blocks (count blocks)}))

  (-get-object [_ ref]
    ;; `read-file` verifies every block against the CID it was asked for
    ;; and refuses a short read, so a partial archive fails loudly instead
    ;; of returning a truncated file.
    ;;
    ;; Absence and corruption are different answers and only one of them is
    ;; nil. `drive.object/read-item` turns nil into `:missing-object` — a
    ;; broken node, which is what a block the archive does not have is. A
    ;; block whose bytes do not hash to their CID is a store that must not
    ;; be trusted, and swallowing that into the same nil would file it as
    ;; ordinary absence.
    (try
      (unixfs/read-file #(get-block transport %) ref)
      (catch clojure.lang.ExceptionInfo e
        (if (= :unixfs/missing-block (:type (ex-data e)))
          nil
          (throw e)))))

  (-object-exists? [_ ref]
    (some? (get-block transport ref)))

  (-delete-object [_ _ref]
    ;; Deliberately nothing. See the namespace docstring.
    false))

(def default-transport
  "The live archive. Named so a test can hand the store a map instead of a
  network — the store's job is the DAG and the identity/location split, and
  neither needs kotobase.net to be reachable to be wrong."
  {:put! archive/put! :get-bytes archive/get-bytes})

(defn store
  "The kotobase archive as a `drive` object store."
  ([] (store default-transport))
  ([transport] (->KotobaseObjectStore transport)))

(defn configured?
  "Whether this store can be used — it writes with a bearer token, and a
  store that can only read is not a store the Drive can be given."
  []
  (archive/configured?))
