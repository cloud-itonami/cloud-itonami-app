(ns babashka.curl (:require [scripts.nbb-compat :as compat]
                             [clojure.string :as str]))

(defn- split-status
  "curl -w \"\\n%{http_code}\" appends the status code after a final newline;
   split it back off (bodies may contain newlines of their own, so split on
   the LAST one)."
  [out]
  (let [idx (str/last-index-of out "\n")]
    (if idx [(subs out 0 idx) (subs out (inc idx))] ["" out])))

(defn- auth-args [opts]
  (when-let [[user pass] (:basic-auth opts)]
    ["-u" (str user ":" (or pass ""))]))

(defn- do-request [base-args opts]
  (let [args (cond-> base-args
               (:headers opts) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) (:headers opts)))
               (:basic-auth opts) (into (auth-args opts))
               (:raw-args opts) (into (:raw-args opts)))
        r (apply compat/sh args)
        [body status-str] (split-status (:out r))
        status (js/parseInt status-str 10)]
    {:status (if (js/isNaN status) (:exit r) status) :body body :headers {}}))

(defn get [url & {:as opts}]
  (do-request ["curl" "-sS" "-L" "-w" "\n%{http_code}" url] opts))

(defn post [url & {:as opts}]
  (do-request (cond-> ["curl" "-sS" "-L" "-X" "POST" "-w" "\n%{http_code}" url]
                (:body opts) (into ["--data" (:body opts)]))
              opts))
