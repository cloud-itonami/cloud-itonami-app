#!/usr/bin/env nbb
;; itonami_harness — the Cordis configuration contract on nbb ClojureScript
;; (ADR-2609042200).
;;
;;   Everything is a plugin. A plugin is a map:
;;
;;     {:name         :itonami.chat          ; unique, in mount order
;;      :inject       [:ctx.config]          ; service keys it depends on
;;      :provides     :ctx.chat              ; the service key it mounts
;;      :description  "slash registry, REPL state"
;;      :apply        (fn [deps] value)}     ; deps = {service-key value}
;;
;;   The context is a service repository: lookup happens through ctx.<key>,
;;   never through an import. Mount order is dependency order: :inject must be
;;   satisfied by already-mounted plugins. Registration is a reversible
;;   effect — unmount! unwinds in reverse, calling each plugin's recorded
;;   disposers.
;;
;;   Events dispatch in five modes, with Cordis's semantics:
;;     :emit       every listener, fire-and-forget
;;     :waterfall  listeners chain; each receives (next) and the previous
;;                 listener's value; returning without calling (next)
;;                 short-circuits
;;     :parallel   all listeners, results collected, order not guaranteed
;;     :serial     all listeners, in order, results collected in order
;;     :bail       listeners in order until one returns non-nil; that value
;;                 is the dispatch result
;;
;;   Parity: `--dump-config` prints the mounted plugin tree as EDN, the way
;;   dsh does, so an operator can answer "what is actually running" without
;;   reading this file.

(ns itonami-harness
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [nbb.core :refer [*file*]]))

;; ---------------------------------------------------------------------------
;; the five dispatch modes
;; ---------------------------------------------------------------------------

(defn- dispatch-emit
  [_ctx listeners event]
  (doseq [l listeners] (l event))
  nil)

(defn- dispatch-waterfall
  [_ctx listeners event]
  (if (empty? listeners)
    event
    ;; The first listener receives the event and (next); each later listener
    ;; receives the previous listener's return value and (next). A listener
    ;; that never calls (next) has short-circuited: the chain's answer is
    ;; whatever it returned.
    (letfn [(step [i value]
              (if (>= i (count listeners))
                value
                (let [called? (atom false)
                      next!   (fn [v]
                                (reset! called? true)
                                (step (inc i) v))
                      result  ((nth listeners i) value next!)]
                  (if @called?
                    result
                    (do (reset! called? true) result)))))]
      (step 0 event))))

(defn- dispatch-parallel
  [_ctx listeners event]
  (mapv #(% event) listeners))

(defn- dispatch-serial
  [_ctx listeners event]
  (mapv #(% event) listeners))

(defn- dispatch-bail
  [_ctx listeners event]
  (loop [ls listeners]
    (when (seq ls)
      (or ((first ls) event)
          (recur (rest ls))))))

(def ^:private dispatchers
  {:emit dispatch-emit
   :waterfall dispatch-waterfall
   :parallel dispatch-parallel
   :serial dispatch-serial
   :bail dispatch-bail})

;; ---------------------------------------------------------------------------
;; the context: a service repository with an ordered plugin registry
;; ---------------------------------------------------------------------------

(defn make-context
  "An empty harness context. Services live under :services keyed by their
  stable :ctx/* name; plugins under :plugins in mount order; :events holds
  one listener list per [:mode :event] pair; :effects holds, per plugin,
  the disposers unmount! will run in reverse."
  []
  (atom {:services {} :plugins [] :events {} :effects {}}))

(defn ctx-get [ctx k]
  (get (:services @ctx) k))

(defn- deps-satisfied?
  "Every :inject key must already be mounted. A dependency that mounts later
  is a bug the mount call refuses, not a silent nil the plugin discovers at
  its first lookup."
  [ctx inject]
  (every? #(contains? (:services @ctx) %) inject))

(defn mount!
  "Resolve :inject, call (:apply plugin deps), record the value under
  :provides. Returns the context. Refuses:
    - a plugin whose name is already mounted (double mount, not a patch —
      patching is a later plugin that :injects the same service and re-provides)
    - a plugin whose :inject is not yet satisfied"
  [ctx plugin]
  (let [{:keys [name inject provides apply]} plugin]
    (when (some #(= name (:name %)) (:plugins @ctx))
      (throw (ex-info (str "plugin already mounted: " name)
                      {:type :harness/double-mount :plugin name})))
    (when-not (deps-satisfied? ctx inject)
      (let [missing (remove #(contains? (:services @ctx) %) inject)]
        (throw (ex-info (str "unsatisfied :inject for " name ": " (pr-str missing))
                        {:type :harness/unsatisfied-inject
                         :plugin name :missing (vec missing)}))))
    (let [deps (select-keys (:services @ctx) inject)
          ;; the disposer slot pre-exists :apply, so a plugin can register
          ;; effects while it mounts
          disposers (volatile! [])]
      (swap! ctx assoc-in [:effects name] disposers)
      (let [value (apply deps)]
        (swap! ctx update :plugins conj
               (select-keys plugin [:name :provides :description :inject]))
        (swap! ctx assoc-in [:services provides] value)
        ctx))))

(defn on
  "Register a listener for [mode event]. Returns a disposer. This is the
  reversible effect plugins use inside :apply; unmount! runs it."
  [ctx mode event listener]
  (swap! ctx update-in [:events [mode event]] (fnil conj []) listener)
  (fn [] (swap! ctx update-in [:events [mode event]]
                (fn [ls] (remove #(= % listener) ls)))))

(defn effect
  "Register an arbitrary reversible effect under a plugin's name."
  [ctx plugin-name disposer]
  (let [slot (get (:effects @ctx) plugin-name)]
    (when slot (vswap! slot conj disposer))))

(defn unmount!
  "Remove the named plugin: run its disposers in reverse, drop its service,
  drop it from the registry. Its listeners go with its disposers only if it
  registered them through `on` — a listener added by hand belongs to whoever
  added it."
  [ctx name]
  (when-let [d (get (:effects @ctx) name)]
    (doseq [disposer (reverse @d)] (try (disposer) (catch :default _ nil))))
  (let [provided (some (fn [p] (when (= (:name p) name) (:provides p)))
                       (:plugins @ctx))]
    (swap! ctx (fn [c]
                 (-> c
                     (update :plugins #(remove (fn [p] (= (:name p) name)) %))
                     (update :effects dissoc name)
                     (update :services dissoc provided))))
    ctx))

(defn dispatch
  "Fire [mode event] with a payload through its listeners. The [mode event]
  pair is the listener key; the payload is what listeners receive. An event
  with no listeners is not an error; it dispatches to the mode's empty
  behavior (bail: nil, waterfall: the payload, others: empty)."
  ([ctx mode event]
   (dispatch ctx mode event event))
  ([ctx mode event payload]
   (let [listeners (get (:events @ctx) [mode event] [])]
     ((dispatchers mode) ctx listeners payload))))

(defn dump-config
  "The dsh parity surface: the mounted tree, as EDN."
  [ctx]
  (pr-str {:plugins (mapv #(select-keys % [:name :provides :description :inject])
                          (:plugins @ctx))
           :services (vec (sort (keys (:services @ctx))))}))
