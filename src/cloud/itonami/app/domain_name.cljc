(ns cloud.itonami.app.domain-name
  "What a domain name IS, apart from any DNS this deployment can reach.

  ADR-0043 and ADR-0048 put the promotion rules in `.kotoba` and left the facts
  to be established by a host. Several of those facts are not host work at all —
  whether a name is well formed, whether this deployment already speaks for it,
  whether a measurement is still recent enough to count — and they were written
  into `domain_verification.clj` because that is where the DNS was.

  This repository's runtime order puts ClojureScript above the JVM for new
  code, with `:clj` isolated as a compat layer rather than assumed. These
  functions have no reason to be JVM-only, so they are not: nothing here opens a
  socket, reads a store or names a Java class outside one reader conditional for
  the clock.

  What stays behind: `IDN/toASCII`. Unicode-to-ASCII is a platform primitive and
  `url.domainToASCII` is not the same function under STD3 rules — swapping one
  for the other would change which names a tenant may claim, quietly, in a
  security boundary. The caller converts and hands the ASCII name here."
  (:require [clojure.string :as str]))

(def ^:private label-pattern #"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")

(defn valid-ascii-name?
  "Whether `domain` is a well-formed, already-ASCII DNS name.

  Refuses URL syntax, ports, paths, wildcards and public-suffix-only names — a
  single label is a TLD, and a tenant does not get to claim one."
  [domain]
  (boolean
   (when (string? domain)
     (let [labels (str/split domain #"\.")]
       (and (<= 3 (count domain) 253)
            (<= 2 (count labels))
            (every? #(and (<= 1 (count %) 63)
                          (re-matches label-pattern %))
                    labels))))))

(defn service-owned?
  "Whether `domain` is one of `own-names` or sits underneath one.

  Subdomains count: a tenant proving `team.<suffix>` would be proving control of
  the operator's zone rather than of its own. `own-names` is passed in rather
  than read from a profile, so this answers the same way for a caller that has
  the deployment's names and for a test that has three strings."
  [own-names domain]
  (let [domain (some-> domain str str/lower-case not-empty)
        own (into #{} (keep #(some-> % str str/lower-case not-empty)) own-names)]
    (boolean
     (and domain
          (some (fn [host]
                  (or (= domain host) (str/ends-with? domain (str "." host))))
                own)))))

(defn epoch-ms
  "An ISO-8601 instant as epoch milliseconds, or nil when it will not parse.

  The one reader conditional in this namespace. nil rather than a throw because
  every caller here treats an unreadable timestamp as \"no measurement\", which
  is the safe direction — see `fresh?`."
  [instant]
  (when (string? instant)
    (try
      #?(:clj (.toEpochMilli (java.time.Instant/parse instant))
         :cljs (let [ms (.getTime (js/Date. instant))]
                 (when-not (js/isNaN ms) ms)))
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn fresh?
  "Whether a measurement taken at `at` is still inside `window-ms` of `now-ms`.

  **An unreadable or missing `at` is NOT fresh.** A measurement this process
  cannot date is not one it can vouch for, and answering `true` here would let
  a binding stay live on evidence nobody can place in time."
  [at now-ms window-ms]
  (boolean
   (when-let [taken (epoch-ms at)]
     (> taken (- now-ms window-ms)))))

(defn exclusive?
  "Whether no record OTHER than `id` holds `domain` in one of `held-states`.

  The shape both authorities share: naming reserves a name at `:claimed`, mail
  reserves it at `:authorized`, and neither may be held by two tenants. Which
  states count is the caller's, because that is the part that differs."
  [records id domain held-states]
  (not (boolean
        (some (fn [other]
                (and (not= id (:id other))
                     (= domain (:domain other))
                     (contains? held-states (:status other))))
              records))))
