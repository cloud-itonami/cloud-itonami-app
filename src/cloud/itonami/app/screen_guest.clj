(ns cloud.itonami.app.screen-guest
  "The screen judgment gate, loaded from the compiled artifact.

  The judgments this resident makes about screen observations and screen
  operations come from kotoba-lang/screen, compiled by amu to a kexe whose
  `:program` (KIR v4) runs here through kotoba.kir/execute — the same
  byte-equality-parity pattern css's .kotoba port is gated with. The
  artifact is a sha256-recorded build (kotoba-lang/screen artifacts/, repo
  main 63378dd5); it is not rebuilt by this app. If the interpreter and the
  native route ever disagree, that is a compiler bug to file against amu,
  not something this bridge absorbs.

  Fail-closed: any error loading or executing the gate makes every caller
  take the HOST-native conservative answer (keep the frame / refuse the
  act) rather than a bridged one, and the refusal is the same shape the
  pre-gate behavior had."
  (:require [clojure.java.io :as io]
            [kotoba.kir :as kir]
            [kotoba.lang.edn :as edn]))

(def ^:private artifact-path "kotoba/screen/gate-aarch64.kexe")

(defonce ^:private gate
  (delay
    (try
      (let [kexe (edn/read-string (slurp (io/resource "kotoba/screen/gate-aarch64.kexe")))]
        {:program (:program kexe)
         :version (kir/execute (:program kexe) 'gate-version [])})
      (catch Exception e
        {:-error (.getMessage e)}))))

(defn- exec-gate
  "Execute one gate export. Returns the i64 answer, or nil when the gate
  could not be loaded (caller takes the host-native answer)."
  [function args]
  (let [{:keys [program -error]} @gate]
    (when (and program (not -error))
      (try
        (kir/execute program function args)
        (catch Exception _ nil)))))

(defn available?
  "True when the compiled gate loaded. A false here is a load-time defect,
  not a runtime refusal — log it at most once."
  []
  (let [{:keys [program -error]} @gate]
    (boolean (and program (not -error)))))

(defn gate-version []
  (or (exec-gate 'gate-version []) 0))

(defn frame-keep?
  "The chronicle dedup judgment. The host computes both combined digests
  (content in the high 47 bits, app code in the low 16 — screen.diff's
  layout); the guest only answers equal/not-equal, which is exactly what
  `(= (digest text) (:text-digest previous))` decided inline before this
  bridge existed, now carried by the compiled artifact instead.

  Returns true = KEEP the new frame. Falls back to host-native keep (the
  pre-bridge behavior) when the gate is unavailable."
  [prev-combined new-combined]
  (let [answer (exec-gate 'chronicle-keep? [prev-combined new-combined])]
    (if (nil? answer)
      true ; fail open to the host-native dedup decision point
      (= 1 answer))))

(defn frame-unchanged?
  "0 = changed (keep), 1 = unchanged (drop)."
  [prev-digest prev-app new-digest new-app]
  (let [answer (exec-gate 'gate-frame-unchanged? [prev-digest prev-app new-digest new-app])]
    (if (nil? answer)
      false
      (= 1 answer))))

(defn press-ok?
  "The act shape gate: 1 = spend an act grant, 0 = refused by shape.
  Unavailable gate refuses by shape (fail closed — the host's own
  authority check still runs after this)."
  [ref-id expect-digest node-count]
  (let [answer (exec-gate 'gate-judge-press [ref-id expect-digest node-count])]
    (= 1 answer)))
