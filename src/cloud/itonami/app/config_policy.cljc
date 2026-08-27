(ns cloud.itonami.app.config-policy
  "How configuration layers combine, and nothing about where they came from.

  Zero dependencies, deliberately. `cloud.itonami.app.config` reads an
  environment, resolves a data directory and slurps EDN — all host questions
  with host answers. What it does with the maps afterwards is not, and that
  half is here so `bin/test-portable-cljs` can run it: that runner grants no
  classpath beyond `src` and `test`, on the argument that a portable judgement
  needing a resolved dependency tree would not be very portable.

  Note what is NOT here: the loopback bind check. It goes through
  `cloud.itonami.app.policy`, which delegates to `policy.kotoba` through the
  Kotoba oracle and so needs the KIR interpreter — an external library. That
  check stays in `config`, next to the IO, rather than dragging an interpreter
  into a namespace whose whole claim is that it needs nothing.

  ## Why the layering deserved its own home

  `overlay-providers` had a silent failure until 2026-08-27: the profile layer
  was collected, computed into an intermediate value, and thrown away one form
  later. A profile that set `{:id \"murakumo\" :enabled? true :reviewed? true}`
  left the provider off, while a non-provider key in the same file applied
  normally — so a deployment profile that enabled a provider read as correct
  and did nothing. It was reachable only by loading a real config from a real
  directory with a real profile environment variable set."
  (:require [clojure.string :as str]))

(defn deep-merge
  "Merge maps recursively; a later value wins unless both sides are maps.

  Public rather than private because it is the whole of how a layer applies,
  and a test that cannot name it has to assert on `load-config` instead — which
  needs a filesystem."
  ([a b]
   (merge-with (fn [x y]
                 (if (and (map? x) (map? y))
                   (deep-merge x y)
                   y))
               a b))
  ([a b & more]
   (reduce deep-merge (deep-merge a b) more)))

(defn overlay-providers
  "Apply provider overrides onto the shipped catalog, matched by `:id`.

  `layers` are applied in order, so the last one wins: defaults <- profile <-
  the store's config.edn.

  An override naming an `:id` that is not in the catalog throws. Silently
  ignoring it is the same failure one level along: a typo in a provider name
  would leave the operator reading a config that says the thing is on."
  [catalog layers]
  (reduce
   (fn [providers overrides]
     (let [known (into #{} (map :id) providers)
           by-id (into {} (map (juxt :id identity)) overrides)]
       (when-let [unknown (seq (remove known (keys by-id)))]
         (throw (ex-info "provider override names no configured provider"
                         {:type :config/unknown-provider
                          :unknown (vec (sort unknown))
                          :known (vec (sort known))})))
       (mapv #(deep-merge % (get by-id (:id %) {})) providers)))
   catalog
   layers))

(defn compose
  "The configuration these layers describe.

  `:providers` is overlaid by `:id` and everything else is deep-merged, and the
  two are kept apart on purpose: a provider list merged positionally would let a
  layer that names one provider silently reorder or truncate the catalog.

  Returns the map. It does NOT validate — the one validation this application
  performs needs the Kotoba oracle, so `config` does it after calling this."
  [defaults profile overrides]
  (let [profile (or profile {})
        overrides (or overrides {})]
    (assoc (deep-merge defaults
                       (dissoc profile :providers)
                       (dissoc overrides :providers))
           :providers (overlay-providers (:providers defaults)
                                         [(:providers profile)
                                          (:providers overrides)]))))

(defn secret-env-name
  "The environment variable a provider's key comes from, or nil.

  The NAME is policy; reading it is not. Splitting them is what lets a host
  with no environment supply the value another way."
  [provider]
  (let [name (:api-key-env provider)]
    (when-not (str/blank? (str name)) name)))
