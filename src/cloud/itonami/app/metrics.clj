(ns cloud.itonami.app.metrics
  "What a business's product is actually measured at, and how old that is.

  Phase 4 of ADR-2607309600. Read-only. The file is
  `90-docs/business/metrics/<product>.edn`, keyed by the `:canvas/product` the
  business already binds, and written by the emitter that reads Cloudflare,
  Stripe and each product's own status API.

  ## Freshness is the first thing, not a footnote

  Every metrics file carries `:as-of` (or `:asOf`). Those dates are not all
  recent: measured across the twelve files, eleven read 2026-07-30 and
  `ai-gftd-yukkuri` reads 2026-07-02 — 28 days old. A pane that prints
  「登録者 3」 from a 28-day-old file without saying so is presenting a stale
  figure as a current one, which is exactly the failure `funding` refuses when it
  will not spend against a balance older than its window.

  So the age is computed and every payload carries a verdict:
  `:fresh` / `:stale` / `:undated`. `:undated` is its own state — a file with no
  `:as-of` is not a fresh file.

  ## The shapes disagree, and this namespace does not unify them

  `:funnel` means something different in every product that has one, measured:

      cloud-itonami  {:trials :freeClaims :agentRuns7d :externalTenants :paid}
      club-shinshi   {:visitors :chatters :scenes :paying}
      net-kotobase   {:visitors :signups :checkouts}

  Mapping those onto one 「visitors → signups → paying」 funnel would require
  deciding that a `freeClaim` is a `signup` and a `chatter` is neither, which is
  a product judgement this app has no basis for. The common core that every file
  really does share is extracted with names; everything else is passed through as
  the product's own keys, labelled `:product-specific`, and rendered without
  interpretation.

  ## Traffic and its quality travel together

  `:zone :requests-7d` alone is misleading and the files know it — they carry
  `:traffic-quality {:probe-4xx-pct :error-5xx-pct}` beside it, and the numbers
  are large: `ai-gftd-apex` reports 508,284 requests with 80% 5xx, and
  `net-kotobase` 57% probe 4xx. Requests are therefore never returned without
  the quality figures in the same map, so a renderer cannot show one without
  having been handed the other."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cloud.itonami.app.business :as business])
  (:import [java.time Duration Instant LocalDate ZoneOffset]))

(def schema "cloud.itonami.app.metrics.v1")

(def default-max-age-days
  "How old a metrics file may be and still read as current.

  3 days: the emitter runs daily, so one missed run is not staleness and three
  are. Configurable at `:business :metrics-max-age-days`."
  3)

(def ^:private core-keys
  "The keys this namespace claims to understand. Everything else is passed
  through untouched — see the namespace docstring."
  #{:as-of :asOf :zone :zone-name :traffic-quality :health-status :sources
    :signal :top-paths :paths :note :version :source :window :ok :live
    :health-live :links :agentNote :productSurfaceHint})

(defn- metrics-path [product]
  (str "90-docs/business/metrics/" (name product) ".edn"))

(def ^:private unreadable ::unreadable)

(defn- read-metrics [ws product]
  (when-let [root (:file ws)]
    (let [f (io/file root (metrics-path product))]
      (when (.isFile f)
        (try (edn/read-string (slurp f))
             (catch Exception _ unreadable))))))

(defn- parse-instant
  "`:as-of` is either a full timestamp or a bare date, in real files. A bare date
  is read as its start of day in UTC: reading it as 「now」 would make a
  month-old file look fresh, so the conservative end of the day is the wrong
  choice too — the start is what the emitter can be held to."
  [value]
  (let [s (some-> value str str/trim not-empty)]
    (when s
      (try (Instant/parse s)
           (catch Exception _
             (try (-> (LocalDate/parse s) (.atStartOfDay ZoneOffset/UTC) .toInstant)
                  (catch Exception _ nil)))))))

(defn freshness
  "How old this measurement is, and whether that is acceptable.

  `:undated` is distinct from `:stale`: a file with no `:as-of` was not measured
  late, it declined to say when it was measured, and the fix is different."
  [as-of now max-age-days]
  (if-some [t (parse-instant as-of)]
    (let [age-seconds (.getSeconds (Duration/between t now))
          age-days (/ age-seconds 86400.0)]
      {:state (if (<= age-days max-age-days) :fresh :stale)
       :as-of (str as-of)
       :age-days age-days
       :max-age-days max-age-days})
    {:state :undated
     :as-of (some-> as-of str)
     :max-age-days max-age-days
     :detail "この metrics ファイルは :as-of を持っていません。いつの測定か不明です"}))

(defn- traffic
  "Requests, uniques and the quality figures, as one value.

  Never split: 508,284 requests at 80% 5xx and 508,284 requests at 0% 5xx are
  different facts, and a renderer handed only the first would state the wrong
  one."
  [m]
  (let [z (:zone m) q (:traffic-quality m)]
    (when (or z q)
      (cond-> {}
        (:zone m) (assoc :zone (or (:zone z) (:zone-name m))
                         :requests-7d (:requests-7d z)
                         :pageviews-7d (:pageviews-7d z)
                         :uniques-7d (:uniques-7d-sum z))
        q (assoc :window (:window q)
                 :probe-4xx-pct (:probe-4xx-pct q)
                 :error-5xx-pct (:error-5xx-pct q))
        ;; Said out loud rather than left to arithmetic: these two percentages
        ;; are why the request count is not an audience count.
        (and q (or (> (or (:probe-4xx-pct q) 0) 20)
                   (> (or (:error-5xx-pct q) 0) 20)))
        (assoc :caveat (str "requests の大半が probe / error です"
                            "（4xx probe " (:probe-4xx-pct q) "% ・ 5xx "
                            (:error-5xx-pct q) "%）。訪問者数として読めません"))))))

(defn- product-specific
  "Everything this namespace does not claim to understand, as the product's own
  keys.

  Values are `pr-str`'d rather than sent as structures: their shapes differ per
  product and a renderer that walked them would be inventing a schema. A string
  a reader can see is honest; a half-understood tree is not."
  [m]
  (->> (seq m)
       (remove (fn [[k _]] (contains? core-keys k)))
       (sort-by (comp str key))
       (mapv (fn [[k v]]
               {:key (str (symbol k))
                :value (if (or (string? v) (number? v) (boolean? v))
                         v
                         (pr-str v))
                :shape (cond (map? v) :map (sequential? v) :sequence
                             (number? v) :number (string? v) :string
                             (boolean? v) :boolean :else :other)}))))

(defn snapshot
  "The measured state of this business's product, with its age.

  `now` is injectable so a test can assert the staleness boundary rather than
  waiting a day for it."
  ([configuration session business-id]
   (snapshot configuration session business-id (Instant/now)))
  ([configuration session business-id now]
   (when-let [b (business/business session business-id)]
     (let [ws (business/workspace configuration)
           product (:business/canvas b)
           max-age (or (get-in configuration [:business :metrics-max-age-days])
                       default-max-age-days)
           base {:schema schema
                 :business (select-keys b [:business/id :business/slug
                                           :business/name :business/canvas])}]
       (merge
        base
        (cond
          (nil? product)
          {:state :unbound
           :detail (str "この事業には canvas (:canvas/product) が紐付いていません。"
                        "metrics はその product 名で引きます")}

          (not= :present (:state ws))
          {:state :unresolvable :source (metrics-path product)
           :detail (:detail ws)}

          :else
          (let [m (read-metrics ws product)
                path (metrics-path product)]
            (cond
              (nil? m)
              {:state :missing :source path
               :detail (str path " が workspace にありません")}

              (identical? unreadable m)
              {:state :unreadable :source path
               :detail (str path " を読めませんでした")}

              (not (map? m))
              {:state :unreadable :source path
               :detail (str path " は map ではありません")}

              :else
              {:state :resolved :source path
               :freshness (freshness (or (:asOf m) (:as-of m)) now max-age)
               :traffic (traffic m)
               :health-status (:health-status m)
               ;; The emitter's own one-line summary, verbatim. It knows what it
               ;; measured; re-deriving a sentence from the numbers here would be
               ;; a second, worse summary.
               :signal (:signal m)
               :top-paths (:top-paths m)
               :sources (mapv #(str (symbol %)) (:sources m))
               :note (:note m)
               :product-specific (product-specific m)}))))))))
