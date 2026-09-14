(ns cheshire.core)
(defn parse-string [s keyword?]
  (js->clj (.parse js/JSON s) :keywordize-keys keyword?))
(defn generate-string [v] (.stringify js/JSON (clj->js v)))
