(ns cloud.itonami.app.commands-test
  "The registry must still be what `server.clj` serves.

  This is the gate that makes `itonami commands` honest. Without it the registry
  is a snapshot: correct the day it was generated, and quietly wrong from the
  first route that lands afterwards — which is exactly the failure the generated
  CLI exists to fix, reintroduced one level up.

  So the routes are re-scanned here, from the same `.cljc` the generator uses, and
  compared against the checked-in resource. Adding a route without running
  `nbb --classpath src dev/gen_commands.cljs` fails the suite."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.commands :as commands]
            [cloud.itonami.app.route-scan :as scan]))

(def ^:private server-file "src/cloud/itonami/app/server.clj")

(defn- source [] (slurp (io/file server-file)))

(deftest the-scanner-finds-the-routes
  (testing "guards against the gate passing vacuously when the parse breaks and
            finds nothing, which would make every comparison below trivially true"
    (let [routes (scan/routes (source))]
      (is (< 150 (count routes)))
      (is (every? #(str/starts-with? (:template %) "/") routes))
      (is (some #(= "/api/business" (:route %)) routes)))))

(deftest the-registry-matches-the-routes
  (testing "the checked-in registry is what the current server.clj serves"
    (let [fresh (scan/registry (source) server-file)]
      (is (= (:commands fresh) (commands/all))
          (str "resources/" commands/resource-name
               " is stale. Run `nbb --classpath src dev/gen_commands.cljs`."))
      (is (= (:counts fresh) (commands/counts))))))

(deftest every-agent-reachable-route-has-a-command
  (testing "no route an agent session may reach is missing from the CLI"
    (let [reachable (->> (scan/routes (source))
                         (filter (comp #{:app :session} :gate))
                         (map (juxt :method :route))
                         set)
          covered (set (map (juxt :method :route) (commands/all)))]
      (is (empty? (set/difference reachable covered))
          "these routes accept an agent session but have no command"))))

(deftest human-only-routes-are-absent-on-purpose
  (testing "money and governed approval are not published as commands that would
            refuse, and the count of them is reported rather than hidden"
    (let [human (->> (scan/routes (source))
                     (filter (comp #{:human} :gate))
                     (map (juxt :method :route)) set)
          covered (set (map (juxt :method :route) (commands/all)))]
      (is (pos? (count human))
          "if this reaches zero the passkey boundary moved, not the test")
      (is (empty? (set/intersection human covered)))
      (is (= (count human) (:human-only (commands/counts)))))))

(deftest command-names-are-unique
  (testing "two routes sharing a name would make one of them unreachable"
    (is (empty? (->> (commands/all)
                     (group-by :command)
                     (filter #(< 1 (count (val %))))
                     keys)))))

(deftest a-command-resolves-from-its-words
  (testing "longest match wins, so a command whose words extend another's is not
            swallowed by the shorter one"
    (is (= ["workspace" "drive" "documents" "rename"]
           (get-in (commands/resolve-command
                    ["workspace" "drive" "documents" "rename"])
                   [:command :command])))
    (is (= ["business" "list"]
           (get-in (commands/resolve-command ["business" "list"])
                   [:command :command])))
    (is (nil? (commands/resolve-command ["no" "such" "thing"]))))

  (testing "what follows the command's own words is left for path parameters"
    (is (= ["env-1"]
           (:rest (commands/resolve-command
                   ["esign" "envelopes" "show" "env-1"]))))))

(deftest a-request-fills-the-path-and-splits-the-arguments
  (let [command (:command (commands/resolve-command
                           ["workspace" "drive" "documents" "rename"]))]
    (testing "the path parameter is substituted and the rest becomes the body"
      (is (= {:method :post
              :path "/api/workspace/drive/documents/doc-1/rename"
              :body {:title "New"}}
             (commands/request command {"document" "doc-1" "title" "New"} nil))))

    (testing "an explicit body overrides the flags it names"
      (is (= {:title "Explicit"}
             (:body (commands/request command
                                      {"document" "doc-1" "title" "Flag"}
                                      {:title "Explicit"})))))

    (testing "a missing path parameter names itself rather than sending {document}"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--document"
                            (commands/request command {"title" "New"} nil)))))

  (testing "a read puts what is left in the query string, not a body"
    (let [command (:command (commands/resolve-command ["workspace" "drive" "search"]))
          {:keys [method path body]} (commands/request command {"q" "notes"} nil)]
      (is (= :get method))
      (is (str/includes? path "q=notes"))
      (is (nil? body)))))

(deftest a-path-parameter-is-encoded
  (testing "an id with a slash cannot reach a different route"
    (let [command (:command (commands/resolve-command ["esign" "envelopes" "show"]))]
      (is (= "/api/esign/envelopes/a%2Fb"
             (:path (commands/request command {"envelope" "a/b"} nil)))))))

(deftest the-registry-covers-what-it-claims
  (testing "the coverage numbers an operator reads are the real ones"
    (let [{:keys [routes commands human-only unauthenticated]} (commands/counts)]
      (is (= routes (+ commands human-only unauthenticated))
          "every route is either a command, human-only, or unauthenticated")
      (is (= commands (count (commands/all)))))))
