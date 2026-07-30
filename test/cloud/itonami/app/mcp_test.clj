(ns cloud.itonami.app.mcp-test
  "Drives the MCP server the way a client does: newline-delimited JSON-RPC in,
  newline-delimited JSON-RPC out, through `serve!`.

  The fleet catalog is the real bundled one — it is a resource, not a network
  call, and stubbing it would leave the descriptors and the search behaviour
  untested against the data they are written for."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.mcp :as mcp]
            [cloud.itonami.app.store :as store])
  (:import [java.io BufferedReader StringReader StringWriter]))

(def ^:private enabled {:agent-control {:fleet {:enabled? true}}})
(def ^:private disabled {:agent-control {:fleet {:enabled? false}}})

(defn- exchange
  "Feed `requests` through `serve!` and read the responses back."
  [configuration requests]
  (let [in (BufferedReader.
            (StringReader. (str/join "\n" (map json/write-str requests))))
        out (StringWriter.)]
    ;; The saved toggle would otherwise decide the gate instead of the argument.
    (with-redefs [store/snapshot (constantly {})]
      (mcp/serve! configuration in out))
    (->> (str/split-lines (str out))
         (remove str/blank?)
         (mapv #(json/read-str % :key-fn keyword)))))

(defn- rpc [id method params]
  (cond-> {"jsonrpc" "2.0" "id" id "method" method}
    params (assoc "params" params)))

(deftest initialize-reports-the-server
  (let [[response] (exchange enabled [(rpc 1 "initialize" nil)])]
    (is (= 1 (:id response)))
    (is (= "cloud-itonami-fleet" (get-in response [:result :serverInfo :name])))
    (is (contains? (:capabilities (:result response)) :tools))))

(deftest a-notification-gets-no-reply
  ;; The handshake sends this immediately after initialize. Answering it puts an
  ;; unsolicited error on the wire, so the only correct response is silence.
  (let [responses (exchange enabled
                            [(rpc 1 "initialize" nil)
                             {"jsonrpc" "2.0" "method" "notifications/initialized"}
                             (rpc 2 "tools/list" nil)])]
    (is (= [1 2] (mapv :id responses)) "exactly two replies for three messages")))

(deftest tools-list-serves-the-fleet-descriptors
  (let [[response] (exchange enabled [(rpc 1 "tools/list" nil)])
        tools (get-in response [:result :tools])
        by-name (into {} (map (juxt :name identity)) tools)]
    (is (= ["fleet_call" "fleet_search"] (sort (map :name tools)))
        "the descriptors fleet already owns, and nothing else")
    (testing "browser and computer tools are deliberately absent"
      (is (not-any? #(str/starts-with? (:name %) "browser_") tools))
      (is (not-any? #(str/starts-with? (:name %) "computer_") tools)))
    (testing "input schemas survive the translation, keys included"
      (is (= "object" (get-in by-name ["fleet_search" :inputSchema :type])))
      (is (contains? (get-in by-name ["fleet_search" :inputSchema :properties])
                     :callable))
      (is (= ["repo"] (get-in by-name ["fleet_call" :inputSchema :required]))))))

(deftest the-fleet-gate-decides-whether-any-tool-is-offered
  (testing "an operator who has not enabled the fleet sees no tools"
    (let [[response] (exchange disabled [(rpc 1 "tools/list" nil)])]
      (is (= [] (get-in response [:result :tools])))))
  (testing "and a call refuses rather than reaching the catalog"
    (let [[response] (exchange disabled
                               [(rpc 1 "tools/call"
                                     {"name" "fleet_search"
                                      "arguments" {"text" "marketplace"}})])]
      ;; A protocol-level error, at the top level — distinct from a tool that
      ;; ran and failed, which comes back as an isError :result.
      (is (= -32601 (get-in response [:error :code]))
          "no such tool in this manifest"))))

(deftest fleet-search-runs-against-the-real-catalog
  (let [[response] (exchange enabled
                             [(rpc 1 "tools/call"
                                   {"name" "fleet_search"
                                    "arguments" {"callable" true "limit" 5}})])
        result (get-in response [:result :structuredContent])]
    (is (false? (get-in response [:result :isError])))
    (testing "the page and the total are both reported"
      (is (pos? (:matched result)))
      (is (<= (:returned result) 5))
      (is (= (:returned result) (count (:actors result)))))
    (testing "callable=true means every hit has an address"
      (is (every? :endpoint (:actors result)))
      (is (seq (:actors result)) "the catalog has at least one deployed actor"))
    (testing "text is EDN alongside the structured payload, for clients that read it"
      (is (str/includes? (get-in response [:result :content 0 :text]) ":matched")))))

(deftest string-keys-from-json-reach-the-tool-as-keywords
  ;; A JSON client cannot send keywords; fleet/search-tool only reads them. If
  ;; the conversion were missing, every filter would be silently ignored and the
  ;; search would look like it worked.
  (let [narrow (get-in (first (exchange enabled
                                        [(rpc 1 "tools/call"
                                              {"name" "fleet_search"
                                               "arguments" {"text" "marketplace"
                                                            "limit" 100}})]))
                       [:result :structuredContent :matched])
        all (get-in (first (exchange enabled
                                     [(rpc 1 "tools/call"
                                           {"name" "fleet_search"
                                            "arguments" {"limit" 100}})]))
                    [:result :structuredContent :matched])]
    (is (pos? narrow) "the text filter matched something")
    (is (< narrow all) "and it actually narrowed the result")))

(deftest a-missing-required-argument-is-a-protocol-error
  (let [[response] (exchange enabled
                             [(rpc 1 "tools/call"
                                   {"name" "fleet_call" "arguments" {}})])]
    (is (= -32602 (get-in response [:error :code])))
    (is (str/includes? (get-in response [:error :message]) "repo"))))

(deftest a-tool-failure-is-a-result-not-a-dropped-connection
  ;; fleet distinguishes "no such actor" from "that actor has no endpoint", and
  ;; both must arrive as isError results — an escaping exception would end the
  ;; session for every later request on the same stdio pipe.
  (let [responses (exchange enabled
                            [(rpc 1 "tools/call"
                                  {"name" "fleet_call"
                                   "arguments" {"repo" "no-such-actor-anywhere"}})
                             (rpc 2 "tools/call"
                                  {"name" "fleet_call"
                                   "arguments" {"repo" "cloud-itonami-app"
                                                "path" "/../etc"}})
                             (rpc 3 "tools/list" nil)])
        [unknown traversal listing] responses]
    (is (true? (get-in unknown [:result :isError])))
    (is (= "fleet/unknown-actor" (get-in unknown [:result :structuredContent :type])))
    (is (true? (get-in traversal [:result :isError])))
    (is (= "fleet/invalid-path"
           (get-in traversal [:result :structuredContent :type]))
        "path traversal is refused before any request is made")
    (is (seq (get-in listing [:result :tools]))
        "the session survived both failures")))

(deftest an-unparseable-line-is-a-parse-error-not-a-silent-drop
  (let [in (BufferedReader. (StringReader. "{not json\n"))
        out (StringWriter.)]
    (with-redefs [store/snapshot (constantly {})]
      (mcp/serve! enabled in out))
    (let [response (json/read-str (str/trim (str out)) :key-fn keyword)]
      (is (= -32700 (get-in response [:error :code])))
      (is (nil? (:id response))))))

(deftest an-unknown-method-is-method-not-found
  (let [[response] (exchange enabled [(rpc 1 "resources/subscribe" nil)])]
    (is (= -32601 (get-in response [:error :code])))))
