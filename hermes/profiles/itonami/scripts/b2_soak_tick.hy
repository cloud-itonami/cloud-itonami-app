#!/usr/bin/env hy
"B2 bytes plane soak/storage watch - kotobase-b2 bot tick."
(import json os sys time urllib.request hashlib subprocess)
(setv LEDGER
  ((. os.path join) ((. os.path dirname) ((. os.path abspath) __file__))
    "b2_storage_ledger.jsonl"))
(setv CERT_SHA_PREFIX "1a0ada098b0d3f05")
(setv SPOT_CID "bafkreia2blnatcynh4ct2qdbkcxyph4g4hr3of6w3tiboxc4vb76mkiii4")
(setv CF_ACCOUNT "4da88288dc30d9ee257f319d3c33ecf0")
(setv UA "kotobase-b2-soak/1.0")

(defn now_iso []
  (return (time.strftime "%Y-%m-%dT%H:%M:%S+09:00")))

(defn http_json [url [timeout 30]]
  (try
   (setv req ((. urllib.request Request) url :headers {"User-Agent" UA}))
   (with [r ((. urllib.request urlopen) req :timeout timeout)]
     (return (json.loads (r.read))))
   (except [e Exception] (return {"_error" (str e)}))))

(defn measure_meta []
  (setv d (http_json "https://ipfs.kotobase.net/_app/meta"))
  (when (in "_error" d)
    (return {"b2_primary_origin" "unmeasured"  "b2_configured" "unmeasured"  "err" (get d "_error")}))
  (return
    {"b2_primary_origin" (d.get "b2_primary_origin")
     "b2_configured"     (d.get "b2_configured")
     "version"           (d.get "version")}))

(defn measure_spot []
  (try
   (setv req
     ((. urllib.request Request) f"https://{SPOT_CID}.ipfs.kotobase.net/" :headers {"User-Agent" UA}))
   (with [r ((. urllib.request urlopen) req :timeout 90)]
     (setv sha (cut ((. (hashlib.sha256 (r.read)) hexdigest)) 16)))
   (return {"sha" sha  "ok" (= sha CERT_SHA_PREFIX)})
   (except [e Exception] (return {"sha" "unmeasured"  "ok" False  "err" (str e)}))))

(defn measure_r2_ops []
  (setv token_path ((. os.path expanduser) "~/.wrangler/config/default.toml"))
  (try
    (setv token None)
    (with [f (open token_path)]
      (for [line f]
       (when (line.startswith "oauth_token")
        (setv token
          ((. ((. (get (line.split "=" 1) 1) strip)) strip) "\"")))))
    (when (not token) (return {"class_a_24h" "unmeasured"  "class_b_24h" "unmeasured"  "err" "no oauth token"}))
    (setv q
      (%
        "
        { viewer { accounts(filter: {accountTag: \"%s\"}) {
            r2OperationsAdaptiveGroups(limit: 2,
              filter: {datetime_geq: \"%s\", datetime_leq: \"%s\"}) {
              sum { requests }
              dimensions { actionType }
            } } } }
        "
        #(CF_ACCOUNT
          (time.strftime "%Y-%m-%dT00:00:00Z" (time.gmtime (- (time.time) 86400)))
          (time.strftime "%Y-%m-%dT%H:%M:%SZ" (time.gmtime)))))
    (setv req
      ((. urllib.request Request) "https://api.cloudflare.com/client/v4/graphql"
        :data ((. (json.dumps {"query" q}) encode))
        :headers {"Authorization" f"Bearer {token}"  "Content-Type" "application/json"}))
    (with [r ((. urllib.request urlopen) req :timeout 30)]
      (setv data (json.loads (r.read))))
    (when (data.get "errors")
      (return
        {"class_a_24h" "unmeasured"
         "class_b_24h" "unmeasured"
         "err"         ((. (get (get data "errors") 0) get) "message" "?")}))
    (setv rows
      (get (get (get (get (get data "data") "viewer") "accounts") 0) "r2OperationsAdaptiveGroups"))
    (setv a
      (sum
        (gfor r rows
          :if (in (get (get r "dimensions") "actionType") #("PutObject" "CopyObject" "CreateMultipartUpload" "UploadPart" "CompleteMultipartUpload"))
          (or (get (get r "sum") "requests") 0))))
    (setv b
      (sum
        (gfor r rows
          :if (in (get (get r "dimensions") "actionType") #("GetObject" "HeadObject" "ListObjects" "ListBuckets"))
          (or (get (get r "sum") "requests") 0))))
    (return {"class_a_24h" a  "class_b_24h" b})
    (except [e Exception] (return {"class_a_24h" "unmeasured"  "class_b_24h" "unmeasured"  "err" (str e)}))))

(defn measure_rclone []
  (try
   (setv r
     (subprocess.run ["bash" "-c" "ps aux | grep '[r]clone copy' | wc -l"] :capture_output True :text True :timeout 15))
   (return (int ((. r.stdout strip))))
   (except [Exception] (return -1))))

(defn main []
  (setv tick {"t" (now_iso)  "meta" (measure_meta)  "spot" (measure_spot)  "r2_ops" (measure_r2_ops)  "rclone_procs" (measure_rclone)})
  (with [f (open LEDGER "a")]
    (f.write (+ (json.dumps tick :ensure_ascii False) "\n")))
  (setv meta (get tick "meta"))
  (setv spot (get tick "spot"))
  (setv ops (get tick "r2_ops"))
  (setv anom [])
  (when (is (meta.get "b2_primary_origin") False) (anom.append "b2_primary_origin=false"))
  (when (is (meta.get "b2_configured") False) (anom.append "b2_configured=false"))
  (when (and
          (is (spot.get "ok") False)
          (!= (spot.get "sha") "unmeasured"))
    (anom.append "spot_sha_mismatch"))
  (print
    (%
      "b2 tick: primary=%s configured=%s spot_sha=%s r2_ops_a=%s r2_ops_b=%s rclone=%d anomaly=%s"
      #((meta.get "b2_primary_origin" "unmeasured")
        (meta.get "b2_configured" "unmeasured")
        (spot.get "sha" "unmeasured")
        (ops.get "class_a_24h" "unmeasured")
        (ops.get "class_b_24h" "unmeasured")
        (get tick "rclone_procs")
        (if (not anom) "none" ((. "; " join) anom))))))

(when (= __name__ "__main__") (main))