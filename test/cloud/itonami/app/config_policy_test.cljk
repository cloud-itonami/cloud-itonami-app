(ns cloud.itonami.app.config-policy-test
  "How configuration layers combine, on both runtimes and with no filesystem.

  These were expressions inside `load-config`, so exercising one meant a real
  data directory, a real `config.edn` and a real `CLOUD_ITONAMI_PROFILE` in the
  environment. That is why the profile layer could be dropped on the floor for
  as long as it was: the only test that would have caught it is one nobody
  writes, because writing it means building a deployment."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.config-policy :as policy]))

(def ^:private catalog
  [{:id "murakumo" :enabled? false :reviewed? false :base-url "https://a"}
   {:id "ollama" :enabled? true :reviewed? true :base-url "http://127.0.0.1"}])

(deftest a-later-layer-wins-only-where-it-speaks
  (is (= {:a {:b 1 :c 3} :d 4}
         (policy/deep-merge {:a {:b 1 :c 2}} {:a {:c 3} :d 4})))
  (testing "a non-map value replaces rather than merging into"
    (is (= {:a [3]} (policy/deep-merge {:a [1 2]} {:a [3]}))))
  (testing "nil on the right does not erase the left"
    (is (= {:a 1} (policy/deep-merge {:a 1} {})))))

(deftest a-profile-that-enables-a-provider-enables-it
  ;; The regression. Until 2026-08-27 the profile layer was computed and thrown
  ;; away one form later, so this exact map left the provider off while a
  ;; non-provider key in the same file applied normally.
  (let [config (policy/compose {:providers catalog :routing {:x 1}}
                               {:providers [{:id "murakumo" :enabled? true
                                             :reviewed? true}]}
                               nil)
        murakumo (first (filter #(= "murakumo" (:id %)) (:providers config)))]
    (is (true? (:enabled? murakumo)))
    (is (true? (:reviewed? murakumo)))
    (is (= "https://a" (:base-url murakumo))
        "and the keys the layer did not name survive")))

(deftest the-store-overrides-the-profile-which-overrides-the-defaults
  (let [config (policy/compose
                {:providers catalog}
                {:providers [{:id "murakumo" :enabled? true :base-url "https://profile"}]}
                {:providers [{:id "murakumo" :base-url "https://store"}]})
        murakumo (first (filter #(= "murakumo" (:id %)) (:providers config)))]
    (is (= "https://store" (:base-url murakumo)) "last layer wins")
    (is (true? (:enabled? murakumo))
        "and a key only the profile set is not lost to the layer after it")))

(deftest the-catalog-keeps-its-shape-through-an-overlay
  (let [config (policy/compose {:providers catalog}
                               {:providers [{:id "ollama" :enabled? false}]}
                               nil)]
    (is (= ["murakumo" "ollama"] (mapv :id (:providers config)))
        "order and length are the catalog's; a layer names providers, it does
         not supply a list")
    (is (false? (:enabled? (second (:providers config)))))
    (is (false? (:enabled? (first (:providers config))))
        "the provider the layer did not name is untouched")))

(deftest an-override-naming-nothing-is-refused
  ;; Ignoring it silently is the same failure one level along: a typo would
  ;; leave the operator reading a config that says the thing is on.
  (let [thrown (try (policy/overlay-providers catalog [[{:id "typo" :enabled? true}]])
                    nil
                    (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
    (is (= :config/unknown-provider (:type thrown)))
    (is (= ["typo"] (:unknown thrown)))
    (is (= ["murakumo" "ollama"] (:known thrown))
        "and it says what WAS available, which is what a typo needs")))

(deftest non-provider-keys-merge-normally-alongside
  (let [config (policy/compose {:providers catalog :server {:host "0.0.0.0" :port 1}}
                               {:server {:host "127.0.0.1"}}
                               {:server {:port 2}})]
    (is (= {:host "127.0.0.1" :port 2} (:server config)))
    (is (= 2 (count (:providers config))))))

(deftest an-absent-layer-is-not-an-empty-decision
  (let [config (policy/compose {:providers catalog :a 1} nil nil)]
    (is (= 1 (:a config)))
    (is (= catalog (:providers config)))))

(deftest a-provider-with-no-key-variable-has-no-secret-to-read
  (is (nil? (policy/secret-env-name {:id "ollama"})))
  (is (nil? (policy/secret-env-name {:id "ollama" :api-key-env ""})))
  (is (nil? (policy/secret-env-name {:id "ollama" :api-key-env "   "})))
  (is (= "MURAKUMO_API_KEY"
         (policy/secret-env-name {:id "murakumo" :api-key-env "MURAKUMO_API_KEY"}))))

(deftest the-itonami-profile-routes-bots-through-murakumo-flash
  ;; 2026-08-30: standing on openrouter/free was 77% fail. Hermes measured
  ;; z-ai/glm-5.3-flash as the primary that completes. The profile overlay
  ;; must actually land on the catalog — the 2026-08-27 regression was this
  ;; exact map being computed and thrown away.
  (let [catalog [{:id "murakumo"
                  :enabled? false :reviewed? false
                  :default-model "murakumo-main"
                  :api-key-env "MURAKUMO_API_KEY"}
                 {:id "openrouter"
                  :enabled? false :reviewed? false
                  :default-model "openrouter/free"}]
        profile {:providers [{:id "murakumo"
                              :enabled? true :reviewed? true
                              :default-model "z-ai/glm-5.3-flash"
                              :max-transient-retries 2}
                             {:id "openrouter" :enabled? false}]
                 :routing {:default-provider "murakumo"
                           :default-model "z-ai/glm-5.3-flash"
                           :cloud-enabled? true}
                 :bots {:workforce {:model "z-ai/glm-5.3-flash"}}}
        config (policy/compose {:providers catalog
                                :routing {:default-provider "ollama"}}
                               profile
                               nil)
        murakumo (first (filter #(= "murakumo" (:id %)) (:providers config)))
        openrouter (first (filter #(= "openrouter" (:id %)) (:providers config)))]
    (is (true? (:enabled? murakumo)))
    (is (true? (:reviewed? murakumo)))
    (is (false? (:enabled? openrouter))
        "OpenRouter stays a destination, not the default path")
    (is (= "z-ai/glm-5.3-flash" (:default-model murakumo)))
    (is (= 2 (:max-transient-retries murakumo)))
    (is (= "MURAKUMO_API_KEY" (:api-key-env murakumo))
        "bots still present the operator shared token, not an OpenRouter key")
    (is (= "murakumo" (get-in config [:routing :default-provider])))
    (is (= "z-ai/glm-5.3-flash" (get-in config [:routing :default-model])))
    (is (= "z-ai/glm-5.3-flash" (get-in config [:bots :workforce :model])))))
