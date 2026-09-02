;; Shared nbb guest loader. Hosts call into the wasm; they do not
;; reimplement health-route? / protocol-ok? as the authority.

(ns guest-runtime
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- loader-url [app-directory]
  (str "file://" (path/resolve app-directory "runtime" "load-guest.mjs")))

(defn require-wasm! [app-directory rel]
  (let [wasm (path/resolve app-directory rel)]
    (when-not (fs/existsSync wasm)
      (throw (ex-info
              (str "guest wasm missing: " wasm
                   "; run bin/kotoba compile --target wasm --json on the matching entry")
              {:path wasm})))
    wasm))

(defn load-guest [app-directory rel]
  (let [wasm (require-wasm! app-directory rel)]
    (-> (js/import (loader-url app-directory))
        (.then (fn [mod]
                 (.instantiateGuest ^js mod wasm app-directory))))))

(defn export-fn [hosted name]
  (let [f (aget (.-exports (.-instance hosted)) name)]
    (when-not f
      (throw (ex-info (str "guest export missing: " name) {:name name})))
    f))

(defn call-bool [hosted name & args]
  (let [f (export-fn hosted name)
        result (apply f args)]
    (true? result)))

(defn call-i64 [hosted name]
  (let [f (export-fn hosted name)
        result (f)]
    (if (instance? js/BigInt result)
      (js/Number result)
      (long result))))
