(ns cloud.itonami.app.awai-content-policy
  "Portable validation for Awai-signed content-origin records.

  This policy checks hostname syntax and rejects known Cloudflare and
  local-only names. It does not prove DNS ownership, operator independence, or
  the address to which a hostname resolves. Each origin must carry an expected
  SHA-256 pin for the TLS subject public key."
  (:require [clojure.string :as str]))

(def minimum-origin-count 3)

(def ^:private blocked-hosts
  #{"cloudflare.com" "cloudflare.net" "cloudflare-dns.com"
    "workers.dev" "pages.dev" "r2.dev" "cfargotunnel.com"
    "localhost" "localhost.localdomain"})

(def ^:private blocked-host-suffixes
  [".workers.dev" ".pages.dev" ".r2.dev" ".cfargotunnel.com"
   ".cloudflare.com" ".cloudflare.net" ".cloudflare-dns.com"
   ".localhost" ".localdomain" ".local" ".internal" ".test"])

(defn- blocked-origin-host? [host]
  (or (contains? blocked-hosts host)
      (some #(str/ends-with? host %) blocked-host-suffixes)))

(defn- valid-host? [host]
  (and (string? host)
       (<= 1 (count host) 253)
       (= host (str/lower-case host))
       (not (str/ends-with? host "."))
       (not (str/includes? host ":"))
       (not (re-matches #"(?:[0-9]{1,3}\.){3}[0-9]{1,3}" host))
       (<= 2 (count (str/split host #"\.")))
       (every? #(and (<= 1 (count %) 63)
                     (re-matches #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?" %))
               (str/split host #"\."))
       (not (blocked-origin-host? host))))

(defn- normalize-origin [record now]
  (let [{:keys [app-id publisher-did operator-did host spki-sha256 expires-at]} record]
    (when (and (map? record)
               (valid-host? host)
               (string? app-id) (not-empty app-id)
               (string? publisher-did) (not-empty publisher-did)
               (string? operator-did) (not-empty operator-did)
               (string? spki-sha256)
               (re-matches #"[0-9a-f]{64}" spki-sha256)
               (nat-int? expires-at))
      (cond
        (<= expires-at now) {:error :expired-content-origin}
        :else {:host host
               :origin (str "https://" host)
               :spki-sha256 spki-sha256
               :app-id app-id
               :publisher-did publisher-did
               :operator-did operator-did
               :expires-at expires-at
               :cid (:cid record)}))))

(defn content-origins
  "Validate the content/origin records explicitly referenced by an accepted
  Awai release. Trust in the entry envelope and operator key comes from Awai's
  accepted-state projection; this function enforces scope, freshness, distinct
  hosts, keys, and operator identities before any transport is attempted."
  [records app-id publisher-did now]
  (let [configured (when (sequential? records) (vec records))
        normalized (when (and configured (nat-int? now))
                    (mapv #(normalize-origin % now) configured))
        valid (when normalized (vec (keep #(when (and (map? %) (not (:error %))) %) normalized)))
        errors (when normalized (vec (keep :error normalized)))
        hosts (mapv :host valid)
        pins (mapv :spki-sha256 valid)
        operators (mapv :operator-did valid)]
    (cond
      (not (nat-int? now))
      {:decision :unavailable :reason :invalid-clock}

      (nil? configured)
      {:decision :unavailable :reason :missing-content-origins}

      (or (< (count configured) minimum-origin-count)
          (> (count configured) 8))
      {:decision :unavailable :reason :insufficient-distinct-origins
       :required minimum-origin-count :configured (count configured)}

      (some nil? normalized)
      {:decision :unavailable :reason :invalid-content-origin}

      (seq errors)
      {:decision :unavailable :reason :expired-content-origin}

      (not (every? #(and (= app-id (:app-id %))
                         (= publisher-did (:publisher-did %))) valid))
      {:decision :unavailable :reason :content-origin-scope-mismatch}

      (not= (count hosts) (count (set hosts)))
      {:decision :unavailable :reason :duplicate-origin-host}

      (not= (count pins) (count (set pins)))
      {:decision :unavailable :reason :duplicate-origin-key}

      (not= (count operators) (count (set operators)))
      {:decision :unavailable :reason :duplicate-origin-operator}

      :else
      {:decision :origins-ready :origins valid})))
