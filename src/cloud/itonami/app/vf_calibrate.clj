(ns cloud.itonami.app.vf-calibrate
  "The XMILE calibration bridge (ADR-2609111230 slice 3).

  The formalism boundary from ADR-2608153000 stands and this namespace does
  not cross it: a VF EconomicEvent is a discrete economic fact, an XMILE
  flow is a continuous rate, `:different-formalism`, not convertible. The
  ONLY bridge is calibration in one direction — measured events recalibrate
  the model's constants, the next run reflects them, nothing else moves.

  What calibration means here, mechanically:

    a measured EVENT carries a quantity for a named resource. The model
    carries leaf constants (bare-number equations, `loops/parameters`).
    `:vf/xmile` names the correspondence: resource id → constant name. An
    event whose resource is named sets that constant's baseline to the
    resource's CURRENT register value after the fold — the model's
    operating point becomes the journal's measured present. An event whose
    resource is NOT named moves nothing: an unnamed resource is an
    observation the model has no place for, and inventing a slot for it
    would be inventing structure.

  The recomputed run is `loops/model` verbatim, over the calibrated model —
  the same re-run the portfolio already does per business, moved behind the
  journal-advance hook. This namespace never runs the simulator itself and
  never writes flow rates."
  (:require [clojure.string :as str]
            [xmile.model :as xmodel]))

(def ^:dynamic *correspondences*
  "org → {resource-id → XMILE constant name}. The ADR's `:vf/xmile` table.
  Production resolves it from the business binding; tests bind a literal.
  A resource absent from the table calibrates nothing — the honest default,
  because a wrong correspondence writes a measured number into an unrelated
  constant, which is worse than a stale one."
  {})

(defn- constant-name [resource-id]
  (get (get *correspondences* nil) resource-id))

(defn- leaf-constant?
  "Exactly the shape `loops/sensitivity` perturbs: a variable whose equation
  is a bare number. Calibrating a computed variable would overwrite a
  derived value with a fixed one — not a calibration, a different model."
  [m name]
  (when-some [v (xmodel/lookup m name)]
    (let [s (some-> (:xmile/eqn v) str str/trim)]
      (when (seq s)
        (try (Double/parseDouble s) (catch Exception _ nil))))))

(defn calibrated-model
  "The model with every correspondence the INVENTORY has a value for
  rebased to that value. Returns the model unchanged (identical value)
  when nothing corresponds — the common case and the safe one."
  [m org inventory]
  (let [table (get *correspondences* org)]
    (reduce-kv
     (fn [model resource-id constant-nm]
       (if-some [measure (get-in inventory [resource-id :onhand-quantity
                                            :has-numerical-value])]
         (if (leaf-constant? model constant-nm)
           (assoc-in model [:xmile/variables constant-nm :xmile/eqn]
                     (str (double measure)))
           model)
         model))
     m
     table)))

(defn calibration
  "Which constants the journal's current inventory rebases, for ORG's model M.

  Read-only: the report an observer bot reads. `:rebased` names what would
  move and to what; `:unmatched` names correspondences the inventory has no
  value for (binding exists, measurement absent — a different fix from a
  missing binding); `:ignored` names bindings the model cannot accept
  (not a leaf constant)."
  [m org inventory]
  (let [table (get *correspondences* org)]
    (reduce-kv
     (fn [acc resource-id constant-nm]
       (let [measure (get-in inventory [resource-id :onhand-quantity
                                        :has-numerical-value])]
         (cond
           (nil? measure)
           (update acc :unmatched conj {:resource resource-id :constant constant-nm})

           (leaf-constant? m constant-nm)
           (update acc :rebased conj {:resource resource-id
                                      :constant constant-nm
                                      :from (leaf-constant? m constant-nm)
                                      :to (double measure)})

           :else
           (update acc :ignored conj {:resource resource-id :constant constant-nm}))))
     {:rebased [] :unmatched [] :ignored []}
     table)))