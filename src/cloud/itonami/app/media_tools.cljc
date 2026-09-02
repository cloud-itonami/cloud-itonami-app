(ns cloud.itonami.app.media-tools
  "Video generation for Bots through `awai-network/hokusai` on murakumo.cloud.

  Two tools, one destination. `video_generate` submits an asynchronous job and
  returns its id; `video_status` reads it. Neither downloads the artifact into
  the resident: the content URL is handed to the Bot, which is the Drive's
  problem to keep, not a tool result's.

  The destination is the SAME provider record as chat (`murakumo`), read from
  its `:media` block, and it passes the SAME admission
  (`policy/provider-allowed?`): review, egress switch, TLS, and a credential
  actually present in the environment. A video call cannot leave the machine
  on a provider chat was not allowed to leave on. There is no fallback to any
  other video service: an unready Hokusai is reported as unready.

  Portable except for the one HTTP round trip, which is the JVM resident's
  (`#?(:clj)`); the tool definitions, admission and result shaping run under
  ClojureScript too, and the portable suite executes them."
  (:require [clojure.string :as str]
            [cloud.itonami.app.hokusai :as hokusai]
            [cloud.itonami.app.policy :as policy]
            #?(:clj [cloud.itonami.app.provider :as provider])))

(def provider-id "murakumo")

(def tool-definitions
  [{:name "video_generate"
    :description
    (str "Generate a short video with awai-network/hokusai (text-to-video, "
         "optional reference image, native audio) via murakumo.cloud. "
         "Asynchronous: returns a job id to poll with video_status. "
         "`seconds` is 1-15 (default 4). (write)")
    :parameters {:type "object"
                 :required ["prompt"]
                 :properties {:prompt {:type "string"}
                              :seconds {:type "number"}
                              :image {:type "string" :description "reference image URL"}
                              :resolution {:type "string"}
                              :aspect_ratio {:type "string"}
                              :generate_audio {:type "boolean"}}
                 :additionalProperties false}}
   {:name "video_status"
    :description "Read a video job submitted with video_generate: status, and the content URL once it has completed."
    :parameters {:type "object"
                 :required ["id"]
                 :properties {:id {:type "string"}}
                 :additionalProperties false}}])

(def tool-names (set (map :name tool-definitions)))
(def write-tool-names #{"video_generate"})

(defn tool? [tool-name] (contains? tool-names (str tool-name)))
(defn write-tool? [tool-name] (contains? write-tool-names (str tool-name)))

(def request-timeout-seconds 60)

(defn provider
  "The murakumo provider record, or nil when the distribution has none."
  [configuration]
  (some #(when (= provider-id (:id %)) %) (:providers configuration)))

(defn admission
  "Why a video call may or may not leave the machine. Secret-free."
  [configuration]
  (let [p (provider configuration)
        videos-url (get-in p [:media :videos-url])]
    (cond
      (nil? p)
      {:allowed? false :blocking [:provider-missing]}

      (not (policy/provider-allowed? configuration p))
      {:allowed? false :blocking (:blocking (policy/provider-readiness configuration p))}

      (not (and (string? videos-url) (str/starts-with? videos-url "https://")))
      {:allowed? false :blocking [:media-endpoint-missing]}

      :else
      {:allowed? true :blocking [] :videos-url videos-url})))

(defn require-admitted!
  "The admitted video endpoint, or a typed refusal naming the blockers.
  Public so the ClojureScript branch (which has no HTTP) still owns it."
  [configuration]
  (let [{:keys [allowed? blocking videos-url]} (admission configuration)]
    (when-not allowed?
      (throw (ex-info "video generation is not admitted on this resident"
                      {:type :media/provider-not-admitted :blocking blocking})))
    videos-url))

(defn generate-result
  "Shape the submit answer for the Bot, or refuse in the gateway's own words."
  [response]
  (let [submitted (hokusai/parse-submit response)]
    (when-not submitted
      (throw (ex-info "hokusai accepted the job but returned no id"
                      {:type :media/no-job-id})))
    (assoc submitted :model hokusai/model-id :poll-with "video_status")))

(defn status-result [videos-url id response]
  (let [st (hokusai/parse-status response)]
    (cond-> (assoc st :model hokusai/model-id)
      (and (:succeeded? st) (nil? (:content-url st)))
      (assoc :content-url (hokusai/content-url videos-url id)))))

#?(:clj
   (do
     (defn- api-key [p]
       (some-> (:api-key-env p) System/getenv str/trim not-empty))

     (defn- request-json
       "One round trip through `provider/request-json`, so a timeout, an
       unreachable gateway and a transport reset arrive under the names the
       ledger already knows (`:provider/timeout` etc.) instead of as an
       internal error. A non-2xx is re-typed `:media/upstream-refused` and
       keeps the house shape (`:status` / `:response`) that `bots/error-message`
       renders, plus the gateway's own `:code` — `self_model_monthly_budget_exhausted`
       is what the Bot needs to read to stop asking."
       [method url body key]
       (try
         (provider/request-json method url body key nil request-timeout-seconds 0)
         (catch clojure.lang.ExceptionInfo e
           (let [{:keys [type status response]} (ex-data e)]
             (if (= :provider/http-error type)
               (throw (ex-info (str "hokusai refused: HTTP " status
                                    (when-let [c (get-in response [:error :code])]
                                      (str " " c)))
                               {:type :media/upstream-refused
                                :status status
                                :response response
                                :code (get-in response [:error :code])
                                :message (get-in response [:error :message])}
                               e))
               (throw e))))))

     (defn call-tool!
       "Run one media tool. Admission is checked on every call, not once at
       startup: the egress switch and the credential can change under a
       running resident, and the last check must be the one that decides."
       [configuration tool-name args]
       (let [videos-url (require-admitted! configuration)
             key (api-key (provider configuration))]
         (case (str tool-name)
           "video_generate"
           (let [{:keys [ok? body code message]} (hokusai/submit-body args)]
             (when-not ok?
               (throw (ex-info message {:type :media/invalid-request :code code})))
             (generate-result (request-json :post videos-url body key)))

           "video_status"
           (let [id (some-> (:id args) str str/trim not-empty)]
             (when-not id
               (throw (ex-info "video_status needs the job id"
                               {:type :media/invalid-request :code "id_required"})))
             ;; The id becomes a request path segment under the resident's
             ;; bearer; only an opaque token shape is allowed through.
             (when-not (hokusai/valid-job-id? id)
               (throw (ex-info "video job id must be an opaque token (letters, digits, - or _)"
                               {:type :media/invalid-request :code "id_invalid"})))
             (status-result videos-url id
                            (request-json :get (hokusai/status-url videos-url id) nil key)))

           (throw (ex-info "Unknown media tool."
                           {:type :media/unknown-tool :tool tool-name})))))))
