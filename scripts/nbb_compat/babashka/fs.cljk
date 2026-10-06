(ns babashka.fs (:require [scripts.nbb-compat :as compat]
                          [clojure.string :as str]))
(defn path [& xs] (apply compat/file xs))
(defn exists? [f] (.exists f))
(defn parent [f] (compat/file (compat/parent-path f)))
(defn real-path [f] (compat/file (compat/canonical-path f)))
(defn create-dirs [f] (.mkdirs (if (string? f) (compat/file f) f)))
(defn which [tool]
  (let [r (compat/sh "which" tool)]
    (when (zero? (:exit r)) (compat/file (str/trim (:out r))))))
