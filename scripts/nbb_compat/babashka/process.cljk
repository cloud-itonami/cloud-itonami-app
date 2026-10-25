(ns babashka.process (:require [scripts.nbb-compat :as compat]))

(defn- process-options [opts]
  (cond-> {}
    (:dir opts) (assoc :cwd (:dir opts))
    (:env opts) (assoc :env (:env opts))
    (:inherit opts) (assoc :stdio "inherit")))

(defn process [command opts]
  (delay (apply compat/sh (into (vec command) [(process-options opts)]))))

(defn shell [opts & command]
  (let [opts-map? (map? opts)
        command (if opts-map? command (cons opts command))
        opts (if opts-map? opts {})]
    (apply compat/sh (concat command [(process-options opts)]))))

(defn sh [command]
  (apply compat/sh command))
