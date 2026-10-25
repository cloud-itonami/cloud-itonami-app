(ns cloud.itonami.app.product-policy
  "One typed host seam for the first external-app policy cores.

  Product decisions stay in their domain .kotoba modules. This namespace reads
  the packaged index, converts host numbers to the declared Kotoba ABI, runs
  the shipped KIR through kotoba-oracle, and returns the stable result code.
  It contains no policy fallback."
  (:require [kotoba.lang.edn :as edn]
            [cloud.itonami.app.kotoba-oracle :as oracle]))

(def index-path "cloud/itonami/app/product-policy-index.edn")

(def ^:private index
  (delay (edn/read-string (oracle/resource-text index-path))))

(defn policy-info
  "The indexed policy descriptor for a canonical domain, or nil if it is not
  part of this shared host's first product cohort."
  [domain]
  (get-in @index [:domains domain]))

(defn shared-policy-info
  "The indexed common-host policy descriptor for an internal policy id."
  [policy-id]
  (get-in @index [:shared-policies policy-id]))

(defn- finite-number? [value]
  #?(:clj (and (number? value) (Double/isFinite (double value)))
     :cljs (and (number? value) (js/Number.isFinite value))))

(defn- guest-argument [domain position abi value]
  (case abi
    :bool
    (if (boolean? value)
      value
      (throw (ex-info "product policy input is outside its bool ABI"
                      {:domain domain :position position :abi abi})))

    :string
    (if (string? value)
      value
      (throw (ex-info "product policy input is outside its string ABI"
                      {:domain domain :position position :abi abi})))

    :i64
    (if (oracle/fits-i64? value)
      (oracle/i64 value)
      (throw (ex-info "product policy input is outside its i64 ABI"
                      {:domain domain :position position :abi abi})))

    :f64
    (if (finite-number? value)
      #?(:clj (double value) :cljs value)
      (throw (ex-info "product policy input is outside its f64 ABI"
                      {:domain domain :position position :abi abi})))

    (throw (ex-info "product policy index declares an unsupported ABI"
                    {:domain domain :position position :abi abi}))))

(defn- argument-count! [domain values expected]
  (when-not (and (sequential? values) (= expected (count values)))
    (throw (ex-info "product policy argument count does not match its ABI"
                    {:domain domain
                     :expected expected
                     :actual (when (sequential? values) (count values))}))))

(defn- record-fields [descriptor]
  (mapv first (nth descriptor 2)))

(defn- record-values [domain {:keys [descriptor field-abi]} values]
  (let [values (if (map? values)
                 (mapv #(get values %) (record-fields descriptor))
                 values)]
    (argument-count! domain values (count field-abi))
    (oracle/record
     descriptor
     (mapv (fn [position abi value]
             (guest-argument domain position abi value))
           (range) field-abi values))))

(defn- guest-arguments [domain policy values]
  (cond
    (seq (:argument-records policy))
    (do
      (argument-count! domain values (count (:argument-records policy)))
      (mapv (fn [record-abi record-input]
              (record-values domain record-abi record-input))
            (:argument-records policy) values))

    (:input-record policy)
    [(record-values domain (:input-record policy) values)]

    :else
    (let [input-abi (:input-abi policy)]
      (argument-count! domain values (count input-abi))
      (mapv (fn [position abi value]
              (guest-argument domain position abi value))
            (range) input-abi values))))

(defn call-policy
  "Run an indexed product domain or shared-host Kotoba policy export.

  `values` is either the product core's scalar ABI, its single input-record
  fields, or one map/vector per record in `:argument-records`. This function
  owns ABI conversion only; the Kotoba export remains the decision authority."
  [policy-id export values]
  (let [policy (or (policy-info policy-id)
                   (shared-policy-info policy-id))
        export-key (keyword (name export))
        export-policy (or (get-in policy [:exports export-key]) policy)
        core (:core policy)
        export-name (or (:export export-policy) export)]
    (when-not (and (map? policy) core export-name
                   (or (vector? (:input-abi export-policy))
                       (:input-record export-policy)
                       (seq (:argument-records export-policy))))
      (throw (ex-info "policy export is not indexed"
                      {:policy-id policy-id :export export})))
    (oracle/call core export-name
                 (guest-arguments policy-id export-policy values))))

(defn decision-code
  "Run the Kotoba policy selected by `domain` with host arguments in declared
  ABI order. `values` must match the indexed scalar ABI exactly. The result is
  the core's numeric status code; app code owns its domain-specific mapping.

  Missing policy metadata or KIR is an error. No host-side decision is made."
  [domain values]
  (oracle/i64-value
   (let [{:keys [export]} (policy-info domain)]
     (when-not export
       (throw (ex-info "domain has no indexed Kotoba product policy"
                       {:domain domain})))
     (call-policy domain export values))))
