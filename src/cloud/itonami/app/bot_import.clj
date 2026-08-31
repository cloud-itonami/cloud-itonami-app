(ns cloud.itonami.app.bot-import
  "Bring a bot that already runs somewhere else into this workforce — without
  letting the import be the thing that creates it.

  Two sources are read today, and they are read because they are the two that
  actually hold running bots on this account:

    hermes   Nous Research's Hermes Agent keeps its scheduled bots as cron
             jobs in `~/.hermes/cron/jobs.json`: a name, a prompt or a script,
             an interval, and the health of the last run.
    grok     the AWAI Grok Bots clean-room surface
             (`network-awai/local-murakumo`, ADR-2608300200) exposes its
             durable agent loop at `/api/v1/grok-bots/bots`, with the public
             `/runtime` projection beside it.

  ## Why an import is a PROPOSAL and not a creation

  A Cloud Itonami Bot exists because `loop-yakuwari` declares a role and
  `itonami bots provision` reconciles to that declaration; provisioning
  \"cannot create a Bot or widen a grant\". If import created Bots directly it
  would be a second way for a governed identity to come into being, and the
  registry would stop being the answer to \"who works here\". So this
  namespace ends at a reviewable `:yakuwari/…` role map. Landing it is a git
  change in loop-yakuwari, and the Bot appears at the next provision — the
  same path a role written by hand takes.

  ## Why a bot that is not importable is said out loud

  Measured 2026-08-30: Hermes held exactly one job, `mailbox-triage`, and it
  is `no_agent: true` with an empty prompt — its work is a Python script, and
  a script is not something a Cloud Itonami Bot's tool set can run. It was
  also paused, with a failure streak of 111 behind an expired M365 login.

  Both facts have to survive the import. A converter that quietly produced a
  role with an empty objective would be refused downstream by `yakuwari.spec`
  (\"a role nobody can state the purpose of is not reviewable\") with a
  message about this application rather than about the source bot; a
  converter that enabled a bot with a 111-run failure streak on a
  fifteen-minute cadence would move a broken job onto a fleet that is already
  oversubscribed. So `:importable` and `:not-importable` are separate lists,
  every exclusion carries its reason, and inherited health decides the
  proposed `:scale`, never the operator's optimism.

  ## The refusal that matters most

  A source this cannot read must not answer like a source with no bots
  (ADR-2608136000). No Hermes home, unparsable jobs file, no
  `MURAKUMO_SERVICE_TOKEN`, an HTTP error: each throws. `:available 0` is only
  ever printed after a read that succeeded."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private default-grok-base "https://itonami.cloud")
(def ^:private http-timeout-seconds 20)
(def sources #{"hermes" "grok"})

;; ── the shape both sources are read into ─────────────────────────────────

(defn- slug [value]
  (let [s (-> (str value) str/lower-case (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (if (str/blank? s) nil s)))

(defn- clamp-cadence
  "Cadence carried across, floored at the resident tick and ceilinged at a day.

  `workforce.edn` is explicit that this field is a ceiling and not a promise —
  the scheduler decides the real interval — so a source bot asking for one
  minute is recorded as fifteen rather than as a number nothing will honour."
  [minutes]
  (let [m (long (or minutes 1440))]
    (max 15 (min 1440 m))))

;; ── hermes ───────────────────────────────────────────────────────────────

(defn hermes-home []
  (io/file (or (some-> (System/getenv "HERMES_HOME") str not-empty)
               (str (System/getProperty "user.home") "/.hermes"))))

(defn- hermes-jobs
  "Every cron job Hermes holds. Throws rather than returning none."
  [home]
  (let [file (io/file home "cron" "jobs.json")]
    (when-not (.isFile file)
      (throw (ex-info (str "Hermes の cron jobs が読めません: " (.getPath file))
                      {:type :bot-import/source-unreadable
                       :source "hermes" :path (.getPath file)})))
    (let [parsed (try (json/read-str (slurp file) :key-fn keyword)
                      (catch Exception e
                        (throw (ex-info (str "Hermes の jobs.json が壊れています: "
                                             (ex-message e))
                                        {:type :bot-import/source-unreadable
                                         :source "hermes"
                                         :path (.getPath file)}))))]
      (vec (:jobs parsed)))))

(defn- hermes-cadence [{:keys [schedule]}]
  (case (str (:kind schedule))
    "interval" (clamp-cadence (:minutes schedule))
    (clamp-cadence nil)))

(defn- declared-objective
  "The purpose a prompt states about itself, or nil when it states none.

  A hermes prompt is OPERATING INSTRUCTIONS -- how to work this pass -- and
  runs to several kilobytes. A yakuwari objective is a STATEMENT OF PURPOSE,
  capped at `bot/max-responsibility` so a role stays reviewable. Measured
  2026-08-31: every one of the twelve prompt-bearing hermes jobs exceeded that
  cap, by 1.2x to 7.0x, so the two planes could not exchange a single bot.

  Splitting the prompt is not the bridge. Twelve responsibilities of a thousand
  characters are available, and seven fragments of a manual still state no
  purpose -- which is the thing `yakuwari.spec` refuses. So the SOURCE declares
  it instead: a prompt may open a `## Objective` section, and that section, not
  the manual around it, is what crosses.

  Absent the section the whole prompt is still offered and still judged by the
  same cap, so nothing that imported before stops importing. A declared
  objective is not exempt either -- an over-long one is refused by `exclusion`
  like any other, because the cap is what makes a role reviewable and this is a
  way to satisfy it, not a way around it."
  [prompt]
  (let [lines (str/split-lines (str prompt))
        heading? #(re-matches #"##\s+.*" (str/trim (str %)))
        start (first (keep-indexed
                      (fn [i line]
                        (when (re-matches #"(?i)##\s+objective\s*" (str/trim (str line)))
                          i))
                      lines))]
    (when start
      (not-empty
       (str/trim (str/join "\n" (take-while (complement heading?)
                                             (drop (inc start) lines))))))))

(defn- hermes->bot [job]
  (let [prompt (str/trim (str (:prompt job)))
        script (some-> (:script job) str not-empty)]
    {:source "hermes"
     :source-id (:id job)
     :name (:name job)
     :key (slug (:name job))
     :objective (or (declared-objective prompt)
                    (when (seq prompt) prompt))
     :cadence-minutes (hermes-cadence job)
     :enabled? (boolean (:enabled job))
     :state (:state job)
     :model (:model job)
     :script script
     :health {:last-status (:last_status job)
              :failure-streak (long (or (:failure_streak job) 0))
              :last-error (some-> (:last_error job) str str/split-lines first)}}))

;; ── grok ─────────────────────────────────────────────────────────────────

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(defn- get-json [url token]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds http-timeout-seconds))
                    (.header "Accept" "application/json"))
        _ (when token (.header builder "Authorization" (str "Bearer " token)))
        response (try (.send @client (.build (.GET builder))
                             (HttpResponse$BodyHandlers/ofString))
                      (catch Exception e
                        (throw (ex-info (str "Grok Bots に接続できません: "
                                             (ex-message e))
                                        {:type :bot-import/source-unreachable
                                         :source "grok" :url url}))))
        status (.statusCode response)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Grok Bots が HTTP " status " を返しました: " url)
                      {:type :bot-import/source-refused
                       :source "grok" :status status :url url
                       :body (subs (.body response)
                                   0 (min 400 (count (.body response))))})))
    (json/read-str (.body response) :key-fn keyword)))

(defn require-token
  "The bearer, or a refusal naming what is missing.

  Split from the environment read so a test can reach the refusal without
  depending on whether this machine happens to export the variable -- a test
  that passes only on a machine with no token is a test that stops running
  the day someone exports one."
  [value]
  (or (some-> value str str/trim not-empty)
      (throw (ex-info (str "MURAKUMO_SERVICE_TOKEN が要ります。"
                           "/api/v1/grok-bots/bots は token 無しでは 401 を返し、"
                           "それは bot が 0 体であることとは違います。")
                      {:type :bot-import/credential-required
                       :source "grok"}))))

(defn- grok-token [] (require-token (System/getenv "MURAKUMO_SERVICE_TOKEN")))

(defn- grok-bots
  "Every bot the management surface lists.

  `/runtime` is read too and merged in: it is the unauthenticated projection
  an operator can already see, and it carries the health (`status`,
  `last_error`) that decides whether an imported role starts desired."
  [base]
  (let [listed (get-json (str base "/api/v1/grok-bots/bots") (grok-token))
        runtime (try (get-json (str base "/api/v1/grok-bots/runtime") nil)
                     (catch Exception _ nil))
        rows (cond
               (sequential? listed) listed
               (sequential? (:bots listed)) (:bots listed)
               (sequential? (:data listed)) (:data listed)
               :else (throw (ex-info "Grok Bots の応答に bot 一覧がありません。"
                                     {:type :bot-import/source-unreadable
                                      :source "grok"
                                      :keys (vec (keys listed))})))]
    {:rows (vec rows) :runtime runtime}))

(defn grok->bot
  "One management row, merged with the public runtime projection.

  Public because the authenticated `/bots` call needs a credential this
  workspace does not currently hold (measured 2026-08-30: 401 without a token,
  and the token the secrets map names is not in the vault it names). The
  public `/runtime` IS readable, so the merge — which is where an imported
  bot's health comes from — can be pinned against the shape the surface
  really returned rather than against one invented here."
  [runtime row]
  (let [id (str (or (:bot_id row) (:id row) "default"))
        same? (= id (str (:bot_id runtime)))
        status (if same? (:status runtime) (:status row))]
    {:source "grok"
     :source-id id
     :name (or (:name row) (str "grok-" id))
     :key (slug (or (:name row) id))
     :objective (some-> (or (:objective row) (:prompt row) (:instructions row))
                        str str/trim not-empty)
     :cadence-minutes (clamp-cadence
                       (when-let [ms (or (:interval_ms row)
                                         (when same? (:interval_ms runtime)))]
                         (quot (long ms) 60000)))
     :enabled? (not= "held" (str status))
     :state (str status)
     :model (or (:model row) (when same? (:model runtime)))
     :script nil
     :health {:last-status (str status)
              :failure-streak 0
              :last-error (or (:last_error row)
                              (when same? (:last_error runtime)))}}))

;; ── converting to a reviewable role ──────────────────────────────────────

(defn exclusion
  "Why this source bot cannot become a role, or nil when it can.

  Order matters: the most specific reason first, so an operator reading the
  report is told the thing they would have to change."
  [{:keys [objective script name key]}]
  (cond
    (nil? key)
    "名前から role key を作れません（英数字が含まれていません）"

    (and script (nil? objective))
    (str "work が script (" script ") で、Cloud Itonami Bot の tool set では"
         " 実行できません。objective を書き起こすか、script を capability に"
         " 起こしてください")

    (nil? objective)
    (str "objective が空です。yakuwari.spec は目的を述べられない role を"
         " 受け付けません")

    (< (count objective) 40)
    (str "objective が短すぎます（" (count objective) " 字）。role は"
         " 何を境界に何を出すかを述べる必要があります")

    (> (count objective) 1000)
    (str "objective が 1000 字を超えています（" (count objective) " 字）。"
         "cloud-itonami-app の bot/max-responsibility に当たり、"
         "provision が workforce 全体を拒否します")

    :else
    (when (str/blank? (str name)) "name がありません")))

(defn desired-scale
  "How many of this role should run.

  A source bot that is paused, held, or has been failing does not become a
  running Bot here. `:min 0 :desired 0` keeps the role reviewable in the
  registry — the import is recorded, the failure is not inherited — and a
  person raises it after fixing what the source could not do."
  [{:keys [enabled? health]}]
  (if (and enabled? (zero? (long (or (:failure-streak health) 0)))
           (not (contains? #{"error" "held" "paused"} (str (:last-status health)))))
    {:min 0 :desired 1 :max 1}
    {:min 0 :desired 0 :max 1}))

(defn role-proposal
  "The `:yakuwari/…` map to land in loop-yakuwari.

  Capabilities are the read-only floor plus proposal-making, and never the
  source bot's own reach: what a bot was allowed to do in Hermes says nothing
  about what it may do here, and copying it across would be the import
  widening a grant."
  [business {:keys [source source-id name key objective cadence-minutes] :as bot}]
  {:yakuwari/id (keyword (clojure.core/name business) key)
   :yakuwari/business (keyword (clojure.core/name business))
   :yakuwari/project "cloud-itonami/cloud-itonami-app"
   :yakuwari/objective objective
   :yakuwari/scale (desired-scale bot)
   :yakuwari/runners [{:runner :claude :weight 1}]
   :yakuwari/imported-from {:source source :id source-id}
   :bot/role :operations
   :bot/name name
   :bot/cadence-minutes cadence-minutes
   :yakuwari/capabilities
   [{:capability :metrics.read :decision :autonomous}
    {:capability :issue.create :decision :autonomous}
    {:capability :patch.create :decision :approval-required
     :note "Imported roles start without an autonomous write. The source bot's own reach is not evidence for this one."}
    {:capability :spend.commit :decision :blocked}]})

(defn read-source
  "Normalized bots from one source. Throws when the source cannot be read."
  [source {:keys [home base]}]
  (case (str source)
    "hermes" (mapv hermes->bot (hermes-jobs (or home (hermes-home))))
    "grok" (let [{:keys [rows runtime]} (grok-bots (or base default-grok-base))]
             (mapv (partial grok->bot (or runtime {})) rows))
    (throw (ex-info (str "unknown bot import source: " source
                         " (" (str/join " / " (sort sources)) ")")
                    {:type :bot-import/unknown-source :source source}))))

(defn import-report
  "What this source holds, what could become a role, and what could not.

  `existing` is the names already in this workforce (from `itonami bots
  list`); a source bot whose name is already here is reported as present
  rather than proposed twice."
  [source {:keys [business existing] :or {business "cloud-itonami"} :as options}]
  (let [bots (read-source source options)
        present (into #{} (map str/lower-case) (or existing []))
        classify (fn [bot]
                   (cond
                     (contains? present (str/lower-case (str (:name bot))))
                     [:already-present nil]
                     (exclusion bot) [:not-importable (exclusion bot)]
                     :else [:importable nil]))
        rows (mapv (fn [bot]
                     (let [[verdict reason] (classify bot)]
                       (assoc bot :verdict verdict :reason reason)))
                   bots)
        importable (filterv #(= :importable (:verdict %)) rows)]
    {:schema "cloud.itonami.app.bot-import.v1"
     :source (str source)
     :business business
     :available (count rows)
     :importable (mapv #(select-keys % [:name :key :source-id :cadence-minutes
                                        :state :health])
                       importable)
     :not-importable (mapv #(select-keys % [:name :source-id :reason :state :health])
                           (filterv #(= :not-importable (:verdict %)) rows))
     :already-present (mapv :name (filterv #(= :already-present (:verdict %)) rows))
     :proposals (mapv (partial role-proposal business) importable)
     :next (if (seq importable)
             (str "提案を loop-yakuwari の yakuwari/" business
                  ".edn に載せ、businesses.edn の :business/roles に足してから"
                  " `itonami bots provision` を実行してください。"
                  " import は Bot を作りません。")
             "role になる bot はありません。")}))
