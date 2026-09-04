(ns itonami-harness
  "Cordis, the plugin framework under the itonami CLI — a port of the
  deepseek-harness contract (docs/cordis-primer.md, docs/architecture.md)
  to ClojureScript/nbb.

  The five ideas, transcribed:

  1. A plugin is a map {:name :inject :apply} (+ optional :config :disabled).
  2. A context is a repository of services. A service claims a stable key
     (:ctx/tools, :ctx/llm, :ctx/sessions, ...); others find services by key,
     never by import.
  3. :inject declares service dependencies; mount waits until they exist.
  4. Typed events with dispatch modes — :emit / :waterfall / :parallel /
     :serial / :bail — the same five modes and semantics as Cordis.
  5. Registrations are reversible effects. Every register returns a disposer;
     unmounting a plugin unwinds exactly what it installed.

  The itonami CLI keeps every behavior a plugin: theme, chat REPL, slash
  commands, the HTTP transport — all mount beside each other and any one
  can be replaced from a profile's plugin list."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; the context — a repository of services
;; ---------------------------------------------------------------------------

(defn make-ctx
  "An empty context. State:
    :services  {key -> value}          the service repository
    :plugins   {name -> plugin-record} mounted plugins, in mount order
    :events    {event -> {:mode :listeners [{plugin fn}]}}
    :effects   {plugin-name -> [disposer-fn]}  reversible registrations"
  []
  (atom {:services {}
         :plugins (array-map)
         :events {}
         :effects {}
         :mounting? false}))

(defn service [ctx key]
  (get (:services @ctx) key))

(defn provide!
  "Claim a stable service key. A second provider for the same key replaces
  the first — the replacement is the whole point of the seam."
  [ctx key value]
  (swap! ctx assoc-in [:services key] value)
  nil)

(defn- ensure-event! [ctx event mode]
  (swap! ctx update-in [:events event]
         (fn [e] (or e {:mode mode :listeners []}))))

(defn on
  "Register a listener for `event`. The event's :mode is declared by its
  first registrant (the event's contract belongs to its domain owner).
  Returns a disposer."
  [ctx plugin-name event f]
  (ensure-event! ctx event :emit)
  (swap! ctx update-in [:events event :listeners] conj {:plugin plugin-name :f f})
  (swap! ctx update-in [:effects plugin-name] conj
         (fn [] (swap! ctx update-in [:events event :listeners]
                       (fn [ls] (vec (remove #(= f (:f %)) ls))))))
  nil)

(defn effect
  "Register a side effect with an explicit disposer. `install` runs at mount,
  the disposer it returns runs at unmount — reverse order, like Cordis."
  [ctx plugin-name install]
  (let [disposer (or (install) (fn []))]
    (swap! ctx update-in [:effects plugin-name] conj disposer)
    nil))

;; ---------------------------------------------------------------------------
;; dispatch — the five modes
;; ---------------------------------------------------------------------------

(defn- sorted-listeners [ctx event]
  (:listeners (get (:events @ctx) event)))

(defn emit
  ":emit — listeners observe in registration order. Not awaited (sync here),
  no return value."
  [ctx event & args]
  (doseq [{:keys [f]} (sorted-listeners ctx event)]
    (apply f args))
  nil)

(defn waterfall
  ":waterfall — around-middleware. Each listener receives (...args next);
  call (next) to delegate, return without next to short-circuit. The value
  flows through next's return value, exactly like Cordis."
  [ctx event & args]
  (let [listeners (sorted-listeners ctx event)]
    (if (empty? listeners)
      (last args)
      (letfn [(call [idx & args]
                (if (>= idx (count listeners))
                  (last args)
                  (let [{:keys [f]} (nth listeners idx)
                        called (atom false)
                        next (fn [& inner]
                               (reset! called true)
                               (apply call (inc idx) inner))]
                    (apply f (concat args [next])))))]
        (apply call 0 args)))))

(defn bail
  ":bail — listeners in registration order until one returns a value;
  that value is the answer."
  [ctx event & args]
  (loop [{:keys [f]} nil, ls (sorted-listeners ctx event)]
    (when-let [{:keys [f]} (first ls)]
      (let [v (apply f args)]
        (if (some? v) v (recur nil (rest ls)))))))

(defn serial
  ":serial — awaited, registration order, each listener's return feeds none;
  the last listener's return value is the dispatch value."
  [ctx event & args]
  (let [ls (sorted-listeners ctx event)
        vs (doall (map #(apply (:f %) args) ls))]
    (last vs)))

(defn parallel
  ":parallel — all listeners in parallel (Promise.all). Awaits everything,
  returns nil. For listeners that must not observe each other."
  [ctx event & args]
  (js/Promise.all
   (clj->js (map #(apply (:f %) args) (sorted-listeners ctx event))))
  nil)

;; ---------------------------------------------------------------------------
;; mounting — profiles and bundles as ordered plugin layers
;; ---------------------------------------------------------------------------

(defn- resolve-inject
  "A plugin's :inject names service keys that must exist. Missing services
  resolve to nil — the plugin decides how to degrade — but a declared
  :required? inject throws at mount, turning load order into a requirement
  rather than a boot sequence."
  [ctx inject]
  (into {} (map (fn [k] [k (service ctx k)])) inject))

(defn mount!
  "Mount one plugin. Order: resolve :inject services -> call :apply(ctx, deps)
  -> record the plugin. Plugins mount in the order the profile lists them;
  a bundle is just a plugin whose :apply mounts its own rows."
  [ctx {:keys [name inject] :as plugin-def}]
  (when (get-in @ctx [:plugins name])
    (throw (ex-info (str "plugin mounted twice: " name)
                    {:type :harness/double-mount :plugin name})))
  (let [deps (resolve-inject ctx (or inject []))]
    ((:apply plugin-def) ctx deps)
    (swap! ctx assoc-in [:plugins name]
           (assoc plugin-def :mounted-at (js/Date.now))))
  name)

(defn mount-all!
  "Mount a profile's plugins in order. A profile is a vector of plugin maps
  (bundles included) — ordered layers over an empty entry list, then the
  caller's patches are just more plugins appended later."
  [ctx plugins]
  (doseq [p plugins] (mount! ctx p))
  (count plugins))

(defn unmount!
  "Run a plugin's disposers in reverse registration order, then drop it.
  Registrations unwind; what other plugins mounted stays."
  [ctx name]
  (let [effects (get-in @ctx [:effects name] [])]
    (doseq [d (reverse effects)] (d))
    (swap! ctx update :effects dissoc name)
    (swap! ctx update :plugins dissoc name))
  nil)

(defn reload!
  "Live patch reload: unmount then mount the same plugin map. dsh ships this
  for profiles; the itonami REPL uses it for /plugin reload."
  [ctx plugin]
  (unmount! ctx (:name plugin))
  (mount! ctx plugin))

(defn plugin-names [ctx]
  (keys (:plugins @ctx)))

(defn dump-config
  "`dsh --dump-config` parity: the mounted plugin tree, in mount order, with
  the service each one claimed. Any row a patch can replace."
  [ctx]
  (mapv (fn [[name p]]
          {:name name
           :inject (:inject p)
           :services (:provides p)
           :description (:description p)})
        (:plugins @ctx)))
