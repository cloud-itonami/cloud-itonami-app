;; The machinery for running a shipped decision on the native ISA.
;;
;; Copied in shape from `murakumo.native-exec` (kotoba-lang/murakumo), not
;; invented here. That file is the measured host: one compile, two engines,
;; agreement-or-mutual-refusal. This repository's cores live under
;; `src/cloud/itonami/app/*.kotoba` rather than `kotoba/`, so the lookup is
;; different and the rest is the same.
;;
;; ── it is a differential test, not an oracle test ──
;;
;; Both sides come from ONE compile: the native code and the KIR handed to the
;; reference interpreter are the same `compile-source` result. A disagreement
;; is attributable to the native backend and to nothing else — not to a
;; compiler pin, not to drift between the shipped artifact and the source.
;; `kotoba-oracle-test` still owns whether
;; `resources/cloud/itonami/app/oracle/*.kir.edn` is current.
;;
;; ── there is deliberately no production native path ──
;;
;; `cloud.itonami.app.kotoba-oracle/call` is a single seam, so pointing it at
;; native artifacts would be a small change — and it is not made. Native costs
;; a process spawn per call (tens of ms) against an interpreter that answers a
;; word-typed predicate in about 2 ms. The calls that do real work here —
;; `provider-allowed?`, `tool-admitted?` — take `[:ref …]` and cannot cross a
;; kexe export (ADR-2608110200). Adding the switch today would add a switch
;; nobody should flip.
;;
;; What would change that: an export boundary for caller-constructed
;; aggregates, or a loader that keeps one process across calls.
;;
;; ── what is refused is counted, not skipped ──
;;
;; The native host boundary takes `:i64`, `:bool` and `:string`. A parameter
;; or result that is a `[:ref …]` or a `[:record …]` cannot cross. Those
;; exports are reported as a number so the canary cannot quietly measure an
;; empty surface and call it a pass.

(ns cloud.itonami.app.native-exec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [kotoba.artifact.runtime-identity :as runtime-identity]
            [kotoba.verifier.signing :as signing]))

(def host-target
  (if (contains? #{"aarch64" "arm64"} (str/lower-case (System/getProperty "os.arch")))
    :aarch64-kotoba-v1
    :x86_64-kotoba-v1))

(def crossable #{:i64 :bool :string})

(defn crossable? [t] (contains? crossable t))

(defn pool
  "Fixed, boundary-covering values per native-crossable type.

  `:string` includes the three loopback spellings `policy.kotoba` actually
  names, so `loopback-host?` is not only ever asked about the empty string."
  [t]
  (case t
    :i64 [0 1 -1 7919 Long/MAX_VALUE Long/MIN_VALUE]
    :bool [true false]
    :string ["" "a" "127.0.0.1" "localhost" "::1" "example.com" "ノード"]))

(def tuples-per-export 3)

(defn arg-tuples [param-types]
  (if (empty? param-types)
    [[]]
    (for [round (range tuples-per-export)]
      (vec (map-indexed (fn [index t]
                          (let [values (pool t)]
                            (nth values (mod (+ index round) (count values)))))
                        param-types)))))

(defn interpret [kir export args]
  (try {:ok (ir/execute kir export (vec args))}
       (catch Throwable failure {:refused (or (:problem (ex-data failure)) :threw)})))

(defn run-native [invoke session export args]
  (try
    (let [evidence (:evidence (invoke session {} {:args (vec args)}
                                      {:now (quot (System/currentTimeMillis) 1000)
                                       :entry export}))]
      (if (= :ok (:status evidence))
        {:ok (:result evidence)}
        {:refused (:status evidence)}))
    (catch Throwable failure {:refused (or (:problem (ex-data failure)) :threw)})))

(defn loader-source-dir
  "amu owns `tools/kexe_loader.c` and does not put it on a classpath — it is C.
  Find it the only way a git dependency's non-classpath files can be found:
  from a file amu DOES put on the classpath, walk up to the checkout root."
  []
  (let [anchor (or (io/resource "kotoba/compiler/core.clj")
                   (throw (ex-info "amu is not on this classpath" {})))]
    (when-not (= "file" (.getProtocol anchor))
      (throw (ex-info "amu must be a source checkout, not a jar" {:anchor (str anchor)})))
    (->> (iterate #(.getParentFile ^java.io.File %) (io/file (.toURI anchor)))
         (take-while some?)
         (take 8)
         (map #(io/file % "tools"))
         (filter #(.isFile (io/file % "kexe_loader.c")))
         first
         (#(some-> ^java.io.File % .getPath)))))

(defn native-host []
  (let [measure (requiring-resolve 'kototama.native.executor/measure-runtime)
        {:keys [runtime loader-bytes]} (measure {:loader-source-dir (loader-source-dir)})
        loader (doto (java.io.File/createTempFile "itonami-kexe-loader-" "")
                 (.deleteOnExit))
        signing-key (signing/generate-keypair)]
    (with-open [out (io/output-stream loader)] (.write out ^bytes loader-bytes))
    (when-not (.setExecutable loader true true)
      (throw (ex-info "cannot make the measured loader executable" {})))
    {:runtime runtime
     :loader-path (.getPath loader)
     :signing-key signing-key
     :trust {:format :kotoba.trust/v1
             :trusted-signers #{(:signer signing-key)}
             :revoked-signers #{}
             :revoked-artifacts #{}
             :trusted-runtime-sha256 #{(runtime-identity/identity-sha256 runtime)}}}))

(defn core-sources []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".kotoba"))
       sort
       vec))

(defn run-core
  "Execute every native-crossable export of one core on both engines.
  `module` is a path relative to the repository root."
  [host module]
  (let [{:keys [runtime loader-path signing-key trust]} host
        prepare (requiring-resolve 'kototama.native.executor/prepare)
        invoke (requiring-resolve 'kototama.native.executor/invoke)
        close! (requiring-resolve 'kototama.native.executor/close!)
        now (quot (System/currentTimeMillis) 1000)
        result (compiler/compile-source (slurp (io/file module)) host-target {})
        artifact (:artifact result)
        kir (:kir result)
        functions (:functions kir)
        exported (filter #(contains? (:exports artifact) (:name %)) functions)
        {crossing true refused false}
        (group-by #(boolean (and (every? crossable? (:param-types %))
                                 (crossable? (:result %))))
                  exported)
        envelope (signing/sign artifact signing-key {:not-before (- now 60)
                                                     :expires (+ now 86400)})
        session (prepare envelope trust {:now now :runtime runtime
                                         :loader-path loader-path})]
    (try
      (let [outcomes
            (doall
             (for [function crossing
                   args (arg-tuples (:param-types function))
                   :let [export (:name function)
                         reference (interpret kir export args)
                         native (run-native invoke session export args)]]
               (cond
                 (and (contains? reference :refused) (contains? native :refused))
                 :both-refused

                 (= reference native) :agreed

                 :else {:module module :export export :args args
                        :reference reference :native native})))]
        {:module module
         :calls (count outcomes)
         :agreed (count (filter #{:agreed} outcomes))
         :both-refused (count (filter #{:both-refused} outcomes))
         :crossing (count crossing)
         :refused (count refused)
         :exported (count exported)
         :disagreements (vec (remove keyword? outcomes))})
      (finally (close! session)))))
