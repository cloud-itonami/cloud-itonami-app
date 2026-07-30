(ns cloud.itonami.app.fleet-tools-test
  "The agent-facing half: the fleet_search and fleet_call tool behaviour.

  Tests the fleet namespace rather than agent-control, which requires
  agent.run and so only loads under the :dev alias's sibling layout."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.fleet :as fleet]))

(deftest search-reports-the-total-not-just-the-page
  (testing "a truncated result says how much was truncated"
    ;; A model handed 20 of 340 matches without being told so will reason as if
    ;; it saw all of them.
    (let [r (fleet/search-tool {:execution "on-demand" :limit 5})]
      (is (= 5 (:returned r)))
      (is (< 5 (:matched r)))
      (is (= 5 (count (:actors r))))))

  (testing "string arguments are coerced to the keywords the catalog uses"
    (let [r (fleet/search-tool {:execution "resident"})]
      (is (= 8 (:matched r)))
      (is (every? #(= :resident (:execution %)) (:actors r)))))

  (testing "callable filters to actors that declare an address"
    (let [r (fleet/search-tool {:callable true :limit 100})]
      (is (pos? (:matched r)))
      (is (every? :endpoint (:actors r)))))

  (testing "the default page is bounded and the cap is enforced"
    (is (= 20 (:returned (fleet/search-tool {}))))
    (is (= 100 (:returned (fleet/search-tool {:limit 5000}))))))

(deftest call-cannot-be-pointed-anywhere
  (testing "the tool takes a repository, never a URL"
    ;; This is the SSRF property and it is structural: no parameter of
    ;; fleet_call can carry a host.
    (let [params (->> fleet/tools (filter #(= "fleet_call" (:name %))) first
                      :parameters :properties keys set)]
      (is (= #{:repo :path} params))))

  (testing "paths may not escape the actor they resolved to"
    (is (= "/orders" (fleet/valid-path! "/orders")))
    (is (= "/" (fleet/valid-path! nil)))
    (doseq [bad ["orders" "//evil.example" "/a/../../b" (apply str "/" (repeat 300 "x"))]]
      (is (thrown? clojure.lang.ExceptionInfo (fleet/valid-path! bad))
          (str (pr-str bad) " should be rejected"))))

  (testing "an unknown repo and an undeployed one fail differently"
    ;; 1,197 of 1,206 actors have no endpoint. Telling the caller "not
    ;; deployed" is more useful than letting it read a connection error, and it
    ;; is a different fact from "no such actor".
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"カタログにありません"
                          (fleet/call-tool {:repo "no-such-actor"})))
    (let [undeployed (first (remove fleet/callable? (fleet/actors)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"未デプロイ"
                            (fleet/call-tool {:repo (:repo undeployed)}))))))
