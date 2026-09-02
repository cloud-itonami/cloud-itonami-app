;; The front end's dispatch, and whether the paths it names exist.
;;
;;     nbb test/itonami_client_nbb.cljs           # resolution only, no server
;;     nbb test/itonami_client_nbb.cljs --probe   # also ask a live server
;;
;;   0  checks passed
;;   1  a check failed
;;   2  could not measure  (--probe with nothing listening)
;;
;; ## Why a probe, and why only some of them
;;
;; The alias table was transcribed from `cli.clj`. A transcription error does
;; not look like one: `/api/agent-bots/workforce` misspelt is a perfectly
;; well-formed path that the resolver fills happily and the server answers 404.
;; So `--probe` asks a live server for the parameter-free GETs and fails on 404,
;; which turns a typo into "no such route" — what it is.
;;
;; ONLY parameter-free GETs. A POST probe would provision a workforce, cancel a
;; run or mint a session; a test that changes the operator's state to check a
;; string is not a test worth having. The POST paths are covered by the JVM
;; `cli-aliases-test`, which compares them against the generated registry and
;; against golden requests rather than by calling them.
;;
;; ## It has to be authenticated, and the first version was not
;;
;; The first version asked unauthenticated and passed anything that was not
;; 404. Measured 2026-09-02 by breaking a path on purpose: `bots workforce`
;; pointed at `/api/agent-bots/workforcce` PASSED. The server gates `/api/...`
;; on a prefix, so a typo inside a gated prefix answers 401 exactly as the real
;; route does; only a path outside every prefix (`/api/definitely-not-a-route`)
;; reaches 404. The check agreed with itself and measured nothing.
;;
;; With a session the signal is real: the same typo answers 405 and the route
;; answers 200. So a token is REQUIRED, and its absence is exit 2 rather than a
;; quieter run of the vacuous version.

(ns itonami-client-nbb
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [nbb.classpath :as classpath]
            [nbb.core :refer [*file*]]))

(def app-directory
  (fs/realpathSync (path/resolve (path/dirname *file*) "..")))

(classpath/add-classpath (path/resolve app-directory "src"))
(require '[cloud.itonami.app.commands :as commands])

(commands/install-sources!
 (into {} (map (fn [n]
                 [n (fs/readFileSync (path/resolve app-directory "resources" n) "utf8")]))
       [commands/resource-name commands/alias-resource-name]))

(def argv (vec (drop 3 (js->clj js/process.argv))))
(def probe? (some? (some #{"--probe"} argv)))

(def results (atom []))

(defn- check [label pass? detail]
  (swap! results conj pass?)
  (println (str (if pass? "PASS" "FAIL") "\t" label
                (when (and detail (not pass?)) (str "\t" detail)))))

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(defn- kind [& words]
  (:kind (commands/resolve-invocation (vec words))))

(defn resolution-checks []
  (check "a named command resolves as an alias"
         (= :alias (kind "bots" "list")) (kind "bots" "list"))
  (check "a generated command resolves from the registry"
         (= :registry (kind "contracts")) (kind "contracts"))
  (check "a host-side command is its own outcome, not unknown"
         (= :host-side (kind "bots" "hygiene")) (kind "bots" "hygiene"))
  (check "an unknown command is unknown"
         (= :unknown (kind "frobnicate" "widgets")) (kind "frobnicate" "widgets"))
  (check "a group name alone is not a command"
         (= :unknown (kind "bots")) (kind "bots"))
  ;; The flag walker, not a filter: a bare token after a flag is that flag's
  ;; value and must not be read as a command word.
  (check "a flag value is not read as a command word"
         (= ["bots" "task"]
            (:words (commands/resolve-invocation
                     ["bots" "task" "--text" "list" "--id" "b-1"])))
         (:words (commands/resolve-invocation
                  ["bots" "task" "--text" "list" "--id" "b-1"])))
  (check "an alias shadows a generated command of the same name"
         (= :alias (kind "business" "list")) (kind "business" "list"))
  (let [refusal (try (commands/alias-request (commands/resolve-alias ["bots" "task"])
                                             {:id "b-1"} {})
                     nil
                     (catch :default e (ex-data e)))]
    (check "a missing required flag refuses by name"
           (= :text (:flag refusal)) (pr-str refusal)))
  (let [req (commands/alias-request (commands/resolve-alias ["bots" "decide"])
                                    {:id "b/1" :card "c 2" :decision "yes"} {})]
    ;; Two parameters, both encoded. A path parameter carrying a slash would
    ;; otherwise invent a route segment.
    (check "path parameters are percent-encoded"
           (= "/api/agent-bots/b%2F1/cards/c+2/decide" (:path req))
           (:path req))))

;; ---------------------------------------------------------------------------
;; probe
;; ---------------------------------------------------------------------------

(defn- base-url []
  (or (some-> (aget js/process.env "CLOUD_ITONAMI_API_URL") str/trim not-empty)
      (str "http://" (or (aget js/process.env "CLOUD_ITONAMI_HOST") "127.0.0.1")
           ":" (or (aget js/process.env "CLOUD_ITONAMI_PORT") "1338"))))

(defn- probeable
  "Parameter-free GETs. Everything else is covered without calling it."
  []
  (filter (fn [{:keys [method params body]}]
            (and (= :get method)
                 (nil? body)
                 (every? #(or (not (:required? %)) (:default %)) params)))
          (commands/alias-commands)))

(defn- session-token []
  (or (some-> (aget js/process.env "CLOUD_ITONAMI_MCP_SESSION") str/trim not-empty)
      (let [{:keys [status stdout]}
            (js->clj (cp/spawnSync "security"
                                   #js ["find-generic-password"
                                        "-s" "cloud-itonami-app.mcp"
                                        "-a" "session-token" "-w"]
                                   #js {:encoding "utf8" :stdio "pipe"})
                     :keywordize-keys true)]
        (when (zero? (or status 1)) (not-empty (str/trim (str stdout)))))))

(defn- get-status
  "The status of one GET, or nil when nothing answered."
  [url token]
  (-> (js/fetch url (clj->js (cond-> {:method "GET"}
                               token (assoc :headers {"authorization"
                                                      (str "Bearer " token)}))))
      (.then (fn [r] (.-status r)))
      (.catch (fn [_] nil))))

(defn- probe-one [token entry]
  (let [p (:path (commands/alias-request entry {} {}))]
    (-> (get-status (str (base-url) p) token)
        (.then (fn [status] #js [(str/join " " (:command entry)) p status])))))

(defn- probe-all [token]
  (let [entries (vec (probeable))]
    (println (str "PROBING\t" (count entries) "\t" (base-url)))
    (-> (js/Promise.all (clj->js (map #(probe-one token %) entries)))
        (.then (fn [rows]
                 (doseq [row (js->clj rows)]
                   (let [[name p status] row]
                     (cond
                       (nil? status)
                       (do (println (str "REFUSED\tno-answer\t" p))
                           (js/process.exit 2))

                       (= 401 status)
                       (do (println (str "REFUSED\tsession-rejected\t" p
                                         " — the token did not authenticate"))
                           (js/process.exit 2))

                       :else
                       ;; 2xx, not merely "not 404": a typo inside a gated
                       ;; prefix answers 405, which the weaker test passed.
                       (check (str "route answers: " name " " p)
                              (<= 200 status 299)
                              (str "status " status))))))))))

(defn- probe! []
  (let [token (session-token)]
    (if-not token
      (do (println "REFUSED\tno-session\tset CLOUD_ITONAMI_MCP_SESSION or run `itonami auth login`")
          (println "Refusing to report a pass: an unauthenticated probe cannot tell a typo from a route.")
          (js/process.exit 2))
      (-> (get-status (str (base-url) "/health") nil)
          (.then (fn [health]
                   (if health
                     (probe-all token)
                     (do (println (str "REFUSED\tno-server\t" (base-url)
                                       " — nothing answered /health"))
                         (println "Refusing to report a pass: no route was asked about.")
                         (js/process.exit 2)))))))))

;; ---------------------------------------------------------------------------

(defn- finish []
  (println)
  (println (str "CHECKS " (count @results)
                " FAILED " (count (remove true? @results))))
  (js/process.exit (if (every? true? @results) 0 1)))

(resolution-checks)
(if probe?
  (-> (probe!) (.then finish))
  (finish))
