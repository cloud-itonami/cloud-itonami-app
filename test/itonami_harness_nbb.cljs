;; Harness contract checks: the five dispatch modes, mount/unmount
;; reversibility, inject refusal, and the profile layer composition.
;;
;;   nbb test/itonami_harness_nbb.cljs
;;
;;   0  all checks passed
;;   1  a check failed

(ns itonami-harness-nbb
  (:require ["node:path" :as path]
            [clojure.test :refer [deftest is run-tests]]
            [nbb.classpath :as classpath]
            [nbb.core :refer [*file*]]))

(classpath/add-classpath (path/resolve (path/dirname *file*) ".." "bin"))
(require '[itonami-harness :as harness]
         '[itonami-profile :as profile])

(deftest plugin-map-shape
  (is (= #{:name :inject :provides :description :apply}
         (set (keys (profile/chat-plugin))))))

(deftest mount-resolves-inject-and-provides
  (let [ctx (harness/make-context)]
    (harness/mount! ctx (profile/config-plugin {:app-directory "/a" :data-dir "/d" :configuration {}}))
    (harness/mount! ctx (profile/theme-plugin))
    (harness/mount! ctx (profile/chat-plugin))
    (is (= [:ctx/chat :ctx/config :ctx/theme]
           (vec (sort (keys (:services @ctx))))))
    (is (fn? (:set-skin! (harness/ctx-get ctx :ctx/theme))))
    (let [chat (harness/ctx-get ctx :ctx/chat)]
      ((:register! chat) "/help" (fn [_ _] nil))
      (is (contains? (set ((:commands chat))) "/help")))))

(deftest double-mount-refused
  (let [ctx (harness/make-context)]
    (harness/mount! ctx (profile/config-plugin {:app-directory "/a" :data-dir "/d" :configuration {}}))
    (is (thrown? js/Error (harness/mount! ctx (profile/config-plugin {}))))))

(deftest unsatisfied-inject-refused
  (let [ctx (harness/make-context)]
    (is (thrown? js/Error (harness/mount! ctx (profile/chat-plugin))))))

(deftest unmount-unwinds-and-unregisters
  (let [ctx (harness/make-context)
        disposed (atom [])
        p {:name :t.evt :inject [] :provides :ctx/t
           :description "d"
           :apply (fn [_]
                    (let [d1 (harness/on ctx :emit :tick (fn [_] (swap! disposed conj :listener)))]
                      (harness/effect ctx :t.evt (fn [] (d1) (swap! disposed conj :effect)))
                      "svc"))}]
    (harness/mount! ctx p)
    (harness/dispatch ctx :emit :tick {:n 1})
    (is (= [:listener] @disposed))
    (harness/unmount! ctx :t.evt)
    (is (= [:listener :effect] @disposed))
    (is (nil? (harness/ctx-get ctx :ctx/t)))
    (is (empty? (:plugins @ctx)))
    (harness/dispatch ctx :emit :tick {:n 1})
    (is (= [:listener :effect] @disposed))))

(deftest emit-fires-all-returns-nil
  (let [ctx (harness/make-context)
        seen (atom [])]
    (harness/on ctx :emit :e (fn [_] (swap! seen conj 1)))
    (harness/on ctx :emit :e (fn [_] (swap! seen conj 2)))
    (is (nil? (harness/dispatch ctx :emit :e {:n 1})))
    (is (= [1 2] @seen))))

(deftest waterfall-chains-and-short-circuits
  (let [ctx (harness/make-context)]
    (harness/on ctx :waterfall :e (fn [ev next] (next (assoc ev :a 1))))
    (harness/on ctx :waterfall :e (fn [ev next] (next (assoc ev :b 2))))
    (harness/on ctx :waterfall :e (fn [ev _next] (assoc ev :final true)))
    (is (= {:a 1 :b 2 :final true} (harness/dispatch ctx :waterfall :e {})))
    ;; short-circuit: a listener that never calls (next) ends the chain
    (let [ctx2 (harness/make-context)
          reached (atom false)]
      (harness/on ctx2 :waterfall :e (fn [_ev _next] :halted))
      (harness/on ctx2 :waterfall :e (fn [_ev _next] (reset! reached true)))
      (is (= :halted (harness/dispatch ctx2 :waterfall :e {})))
      (is (false? @reached)))))

(deftest bail-stops-at-first-non-nil
  (let [ctx (harness/make-context)]
    (harness/on ctx :bail :e (fn [_] nil))
    (harness/on ctx :bail :e (fn [_] :second))
    (harness/on ctx :bail :e (fn [_] :third))
    (is (= :second (harness/dispatch ctx :bail :e {})))))

(deftest serial-and-parallel-collect
  (let [ctx (harness/make-context)]
    (harness/on ctx :serial :e (fn [_] :a))
    (harness/on ctx :serial :e (fn [_] :b))
    (is (= [:a :b] (harness/dispatch ctx :serial :e {:n 1})))
    (harness/on ctx :parallel :e (fn [_] :a))
    (harness/on ctx :parallel :e (fn [_] :b))
    (is (= [:a :b] (harness/dispatch ctx :parallel :e {:n 1})))
    (is (= #{:a :b} (set (harness/dispatch ctx :parallel :e {:n 1}))))))

(deftest theme-live-reprovide
  (let [ctx (harness/make-context)]
    (harness/mount! ctx (profile/config-plugin {:app-directory "/a" :data-dir "/d" :configuration {}}))
    (harness/mount! ctx (profile/theme-plugin))
    (let [theme (harness/ctx-get ctx :ctx/theme)]
      (is (= "default" (:name ((:skin theme)))))
      (is (:ok ((:set-skin! theme) "kawaii")))
      ;; the same service value, no remount: the reader sees the new skin
      (is (= "you ♡ " (:prompt ((:skin theme)))))
      (is (not (:ok ((:set-skin! theme) "nope")))))))

(deftest dump-config-names-tree
  (let [ctx (harness/make-context)]
    (harness/mount! ctx (profile/config-plugin {:app-directory "/a" :data-dir "/d" :configuration {}}))
    (let [d (read-string (harness/dump-config ctx))]
      (is (= [:itonami.config] (mapv :name (:plugins d))))
      (is (= [:ctx/config] (:services d))))))

(defn -main [& _]
  (let [r (run-tests)]
    (js/process.exit (if (zero? (+ (:error r) (:fail r))) 0 1))))

(when-not (aget js/process.env "HARNESS_TEST_NO_AUTOPLAY")
  (apply -main (or *command-line-args* [])))
