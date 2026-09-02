(ns cloud.itonami.app.hokusai
  "`awai-network/hokusai` — awai.network's video model, reached through
  murakumo.cloud's OpenRouter-compatible async video contract
  (root ADR-2609011842; deployment contract
  `90-docs/deployment/awai-murakumo-basho-hokusai-routing.edn`):

    POST {videos-url}              → {id, status, status_url}
    GET  {videos-url}/{id}         → {id, status, content_url?, error?}
    GET  {videos-url}/{id}/content → the artifact

  This namespace only translates data: request shapes in, typed results out.
  Authentication, egress admission and the HTTP round trip stay in
  `cloud.itonami.app.media-tools`, behind the same provider policy every other
  destination that leaves the machine has to pass. Portable so the
  ClojureScript half of the test suite executes it.

  The backend is fail-closed on murakumo's side until the fine-tuned Hokusai
  revision is attested (`:contract/advertise-models? false` as of 2026-09-01):
  a submit today is answered 503 `self_model_backend_unavailable`, and that
  answer is carried to the Bot verbatim rather than being turned into a retry
  against some other video service."
  (:require [clojure.string :as str]))

(def model-id "awai-network/hokusai")

(def max-seconds 15)
(def default-seconds 4)

(def terminal-statuses
  #{"completed" "succeeded" "failed" "cancelled" "canceled" "error" "expired"})

(def success-statuses #{"completed" "succeeded"})

(defn- present [v]
  (some-> v str str/trim not-empty))

(defn- getv [m k]
  (let [missing ::missing
        v (get m k missing)]
    (if (= missing v) (get m (name k)) v)))

(defn- parse-number [s]
  (let [t (present s)]
    (when t
      #?(:clj (try (Double/parseDouble t) (catch Exception _ nil))
         :cljs (let [n (js/Number t)] (when-not (js/isNaN n) n))))))

(defn submit-body
  "A Bot's arguments → the request murakumo accepts, or a typed refusal.

  Refuses rather than repairs: a blank prompt or a duration outside 1..15 s
  is answered as `{:ok? false ...}` with the reason named, so the Bot can fix
  its call instead of a silently clamped value producing a video it did not
  ask for."
  [args]
  (let [prompt (present (getv args :prompt))
        seconds (let [s (or (getv args :seconds) (getv args :duration))]
                  (cond (nil? s) default-seconds
                        (number? s) s
                        :else (or (parse-number s) :invalid)))
        image (present (or (getv args :image) (getv args :reference_image)))
        audio (getv args :generate_audio)]
    (cond
      (nil? prompt)
      {:ok? false :code "prompt_required" :message "video prompt is required"}

      (or (= :invalid seconds) (not (number? seconds))
          (< seconds 1) (> seconds max-seconds))
      {:ok? false :code "seconds_out_of_range"
       :message (str "seconds must be a number between 1 and " max-seconds)}

      :else
      {:ok? true
       :body (cond-> {:model model-id
                      :prompt prompt
                      :seconds seconds}
               image (assoc :image image)
               (present (getv args :resolution)) (assoc :resolution (present (getv args :resolution)))
               (present (getv args :aspect_ratio)) (assoc :aspect_ratio (present (getv args :aspect_ratio)))
               (boolean? audio) (assoc :generate_audio audio)
               (number? (getv args :seed)) (assoc :seed (getv args :seed)))})))

(defn parse-submit
  "murakumo's submit answer → `{:id :status :status-url}`. Accepts both the
  OpenRouter field names and murakumo's own `jobId`, since the adapter has
  changed once already."
  [response]
  (let [id (present (or (getv response :id) (getv response :jobId) (getv response :job_id)))]
    (when id
      {:id id
       :status (or (present (getv response :status)) "queued")
       :status-url (present (or (getv response :status_url) (getv response :statusUrl)))})))

(defn parse-status
  "A poll answer → `{:id :status :terminal? :succeeded? :content-url :error}`."
  [response]
  (let [status (str/lower-case (or (present (getv response :status)) "unknown"))
        error (or (getv response :error) (getv response :failure))]
    {:id (present (or (getv response :id) (getv response :jobId)))
     :status status
     :terminal? (contains? terminal-statuses status)
     :succeeded? (contains? success-statuses status)
     :content-url (present (or (getv response :content_url) (getv response :contentUrl)
                               (get-in response [:output :url]) (get-in response [:video :url])))
     :error (when error (if (string? error) error (str (or (getv error :message) error))))}))

(defn status-url [videos-url id]
  (str (str/replace (str videos-url) #"/+$" "") "/" id))

(defn content-url [videos-url id]
  (str (status-url videos-url id) "/content"))
