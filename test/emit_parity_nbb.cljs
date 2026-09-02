;; Emit parity: the native kotoba CLI against the amu --jvm-free adapter, over
;; this app's own guests.
;;
;;     AMU=<amu launcher> nbb --classpath bin:test test/emit_parity_nbb.cljs
;;     KOTOBA_CLI=<kotoba binary>   # otherwise `kotoba` on PATH
;;
;;   0  both emitters ran and agree
;;   1  both ran and disagree
;;   2  could not measure  (NOT 0, NOT 1)
;;
;; ## What is compared, and what deliberately is not
;;
;; The two emitters do NOT produce the same bytes, and requiring that would fail
;; for a reason nobody can act on. Measured 2026-09-02 on `server_main` and
;; `mcp_main`: the native artifact is 87 bytes smaller in both, entirely in the
;; type, import, function, export and code sections.
;;
;; So parity is asserted where it carries meaning:
;;
;;   identity     `kotoba.target`, `kotoba.compatibility` and `kotoba.typed` are
;;                byte-identical. These are the sections that say what contract
;;                the artifact claims; two emitters disagreeing HERE would mean
;;                they compiled to different contracts.
;;   exports      the same names. The hosts call them by name.
;;   imports      native's set is a SUBSET of amu's. Not equality: native asks
;;                for two fewer host functions, which is a smaller demand on the
;;                host and therefore safe. A native import amu does not have
;;                would be a new demand, and fails.
;;   behaviour    both load through this app's own runtime/load-guest.mjs and
;;                agree on `main` and on every probe case.
;;
;; ## The probes discriminate, and are asserted to
;;
;; A `health-route?` that returned true for everything would pass an
;; agreement-only test. So the cases carry their own expected answers, and one
;; guest's admitted route is another's refusal. `main` is also required to be
;; positive: `bin/cloud-itonami-server` refuses a guest whose main stayed 0, so
;; a parity run that accepted 0 would call two broken artifacts equal.
;;
;; ## Which legs have actually been seen red
;;
;; Shown in both directions, 2026-09-02:
;;
;;   probes match expectations  breaking `health-route?` into "any GET or POST"
;;                              turned this red while "probes agree" stayed
;;                              GREEN on [true true true true] -- which is the
;;                              whole reason the expected column exists.
;;   amu-absent / cli-absent    both exit 2 with their own reason.
;;
;; NOT yet seen red: the identity-section, exports and import-subset legs. They
;; would go red only if the two emitters disagreed about the contract, which
;; cannot be staged honestly by editing this file or the guest. Treat their
;; green as untested discrimination, not as evidence.
;;
;; ## Absence is not agreement
;;
;; Missing amu, missing kotoba CLI, missing guest source and a missing host
;; runtime each exit 2 with their own reason. A comparison that could not be
;; made is not a comparison that succeeded (ADR-2608136000).

(ns emit-parity-nbb
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def app-directory
  (fs/realpathSync (path/resolve (path/dirname *file*) "..")))

(def guests
  "The guests the nbb hosts actually load, and probes for each."
  [{:id "server_main"
    :source "src/cloud/itonami/app/server_main.kotoba"
    :export "health-route?"
    :cases [{:args ["GET" "/health"] :expect true}
            {:args ["GET" "/"] :expect false}
            {:args ["POST" "/health"] :expect false}
            {:args ["GET" "/api/session"] :expect false}]}
   {:id "mcp_main"
    :source "src/cloud/itonami/app/mcp_main.kotoba"
    :export "protocol-ok?"
    :cases nil}])

;; ---------------------------------------------------------------------------

(defn- refuse! [reason detail]
  (println (str "REFUSED\t" reason "\t" detail))
  (println "Refusing to report a pass: the comparison was not made.")
  (js/process.exit 2))

(defn- sh [cmd args opts]
  (let [{:keys [status stdout stderr]}
        (js->clj (cp/spawnSync cmd (clj->js args)
                               (clj->js (merge {:encoding "utf8" :stdio "pipe"
                                                :cwd app-directory}
                                               opts)))
                 :keywordize-keys true)]
    {:exit (or status 1) :stdout (str stdout) :stderr (str stderr)}))

(defn- which [program]
  (let [{:keys [exit stdout]} (sh "/usr/bin/which" [program] {})]
    (when (zero? exit)
      (let [p (str/trim (str stdout))]
        (when-not (str/blank? p) p)))))

(defn- emitters []
  (let [amu (or (some-> (aget js/process.env "AMU") str/trim not-empty)
                (which "amu"))
        cli (or (some-> (aget js/process.env "KOTOBA_CLI") str/trim not-empty)
                (which "kotoba"))]
    (when-not (and amu (fs/existsSync amu))
      (refuse! "amu-absent" "set AMU=<amu launcher>; an unrun adapter is not an agreeing one"))
    (when-not (and cli (fs/existsSync cli))
      (refuse! "kotoba-cli-absent" "set KOTOBA_CLI=<kotoba binary>; nothing to compare against"))
    {:amu amu :cli cli}))

;; ---------------------------------------------------------------------------
;; wasm sections
;; ---------------------------------------------------------------------------

(defn- uleb
  "[value bytes-consumed] at `i`."
  [b i]
  (loop [i i shift 0 acc 0 n 0]
    (let [x (aget b i)]
      (if (< x 128)
        [(bit-or acc (bit-shift-left x shift)) (inc n)]
        (recur (inc i) (+ shift 7)
               (bit-or acc (bit-shift-left (bit-and x 127) shift))
               (inc n))))))

(defn sections
  "{section-name -> {:size n :sha256 hex}} for one wasm file."
  [file]
  (let [b (fs/readFileSync file)]
    (loop [i 8 out {}]
      (if (>= i (.-length b))
        out
        (let [id (aget b i)
              [size n] (uleb b (inc i))
              start (+ i 1 n)
              name (if (zero? id)
                     (let [[len ln] (uleb b start)]
                       (.toString (.subarray b (+ start ln) (+ start ln len)) "utf8"))
                     (str "section-" id))
              body (.subarray b start (+ start size))
              digest (-> (crypto/createHash "sha256") (.update body) (.digest "hex"))]
          (recur (+ start size) (assoc out name {:size size :sha256 digest})))))))

(def identity-sections
  "The sections that say what contract the artifact claims."
  ["kotoba.target" "kotoba.compatibility" "kotoba.typed"])

(defn- module-names [file kind]
  (let [m (js/WebAssembly.Module. (fs/readFileSync file))]
    (case kind
      :imports (set (map #(str (.-module %) "/" (.-name %))
                         (js/WebAssembly.Module.imports m)))
      :exports (set (map #(.-name %) (js/WebAssembly.Module.exports m))))))

;; ---------------------------------------------------------------------------
;; emit
;; ---------------------------------------------------------------------------

(defn- envelope-code [text]
  (try
    (let [m (edn/read-string (str/trim (str text)))]
      (when (map? m) (:kotoba.cli/code m)))
    (catch :default _ nil)))

(defn- emit! [{:keys [amu cli]} emitter source output]
  (fs/mkdirSync (path/dirname output) #js {:recursive true})
  (let [entry (path/resolve app-directory source)
        {:keys [exit stdout stderr]}
        (case emitter
          :native (sh cli ["compile" entry "--target" "wasm" "--output" output] {})
          :amu (sh amu ["compile" entry "--target" "wasm32" "--jvm-free"
                        "--output" output] {}))
        text (str stdout "\n" stderr)]
    (when (str/includes? text ":command/planned")
      (refuse! "emitter-planned"
               (str (name emitter) " answered :command/planned; planned is not emit")))
    (cond
      (and (= :native emitter) (not= :compile/emitted (envelope-code stdout)))
      (refuse! "native-did-not-emit"
               ;; The native CLI exits 0 for commands it has not implemented, so
               ;; the envelope code is what is checked here, never the status.
               (str "code=" (pr-str (envelope-code stdout)) " exit=" exit))

      (and (= :amu emitter) (not (zero? exit)))
      (refuse! "amu-compile-failed" (str "exit=" exit " " (subs text 0 (min 300 (count text)))))

      (not (fs/existsSync output))
      (refuse! "emitter-wrote-no-file" (str (name emitter) " " output))

      (not (pos? (.-size (fs/statSync output))))
      (refuse! "emitter-wrote-empty-file" (str (name emitter) " " output))

      :else output)))

;; ---------------------------------------------------------------------------
;; behaviour
;; ---------------------------------------------------------------------------

(defn- load-guest [wasm]
  (-> (js/import (str "file://" (path/resolve app-directory "runtime" "load-guest.mjs")))
      (.then (fn [mod] (.loadAndCallMain ^js mod wasm app-directory)))))

(defn- probe [loaded {:keys [export cases]}]
  (let [hosted (.-hosted loaded)
        f (aget (.-exports (.-instance hosted)) export)]
    {:main (.-main loaded)
     :export-present? (fn? f)
     :answers (when (and (fn? f) (seq cases))
                (mapv (fn [{:keys [args]}] (apply f args)) cases))}))

;; ---------------------------------------------------------------------------

(defn- check [acc label pass? detail]
  (println (str (if pass? "PASS" "FAIL") "\t" label (when detail (str "\t" detail))))
  (conj acc pass?))

(defn compare-guest! [tools {:keys [id source export cases] :as guest} out-root]
  (when-not (fs/existsSync (path/resolve app-directory source))
    (refuse! "guest-source-absent" source))
  (let [a (emit! tools :amu source (path/join out-root "amu" (str id ".wasm")))
        n (emit! tools :native source (path/join out-root "native" (str id ".wasm")))
        sa (sections a) sn (sections n)
        results (atom [])]
    (doseq [s identity-sections]
      (let [x (get sa s) y (get sn s)]
        (swap! results check (str id " " s " byte-identical")
               (and x y (= (:sha256 x) (:sha256 y)))
               (str (:sha256 x) " vs " (:sha256 y)))))
    (swap! results check (str id " exports identical")
           (= (module-names a :exports) (module-names n :exports))
           (str (sort (module-names n :exports))))
    (let [ia (module-names a :imports) in (module-names n :imports)]
      (swap! results check (str id " native imports subset of amu")
             (every? ia in)
             (str (count in) " of " (count ia)
                  (when-let [extra (seq (remove ia in))]
                    (str "; native-only " (vec extra))))))
    (-> (js/Promise.all #js [(load-guest a) (load-guest n)])
        (.then
         (fn [pair]
           (let [pa (probe (aget pair 0) guest)
                 pn (probe (aget pair 1) guest)]
             (swap! results check (str id " export " export " present in both")
                    (and (:export-present? pa) (:export-present? pn)) nil)
             (swap! results check (str id " main agrees and is positive")
                    (and (= (:main pa) (:main pn)) (pos? (:main pa)))
                    (str (:main pa) " vs " (:main pn)))
             (when (seq cases)
               (swap! results check (str id " probes agree")
                      (= (:answers pa) (:answers pn))
                      (str (:answers pa) " vs " (:answers pn)))
               ;; Agreement alone would pass for a predicate that answers the
               ;; same thing to everything. The expected column is what makes
               ;; these cases discriminate.
               (swap! results check (str id " probes match expectations")
                      (= (mapv :expect cases) (:answers pn))
                      (str (mapv :expect cases) " vs " (:answers pn))))
             @results))))))

(defn -main []
  (let [tools (emitters)
        out-root (path/join (or (aget js/process.env "TMPDIR") "/tmp")
                            (str "emit-parity-" (.getTime (js/Date.))))]
    (println "AMU" (:amu tools))
    (println "KOTOBA" (:cli tools))
    (println (str "SCANNED\t" (count guests)))
    (-> (js/Promise.all (clj->js (map #(compare-guest! tools % out-root) guests)))
        (.then (fn [all]
                 (let [flat (vec (mapcat vec (js->clj all)))]
                   (println)
                   (println (str "CHECKS " (count flat)
                                 " FAILED " (count (remove true? flat))))
                   (fs/rmSync out-root #js {:recursive true :force true})
                   (js/process.exit (if (every? true? flat) 0 1)))))
        (.catch (fn [e]
                  (refuse! "comparison-threw" (or (.-message e) (str e))))))))

(-main)
