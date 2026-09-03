(ns cloud.itonami.app.cli-aliases-test
  "The named commands, against the generated registry and against themselves.

  `resources/cloud-itonami-app.cli-aliases.edn` carries what `cli.clj` used to
  carry as seventeen hand-written functions. A table can be wrong in ways a
  function cannot: a `{param}` with nothing to fill it, a body spec naming a
  parse that does not exist, a name that collides with a generated command and
  quietly shadows it. These are those failures."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.commands :as commands]))

(def ^:private aliases (commands/alias-commands))
(def ^:private generated (commands/all))

(defn- normalize-template
  "The path with every `{placeholder}` reduced to `{}`."
  [template]
  (str/replace template #"\{[^}]+\}" "{}"))

(defn- template-params [template]
  (set (map second (re-seq #"\{([^}]+)\}" template))))

(deftest every-alias-is-addressable
  (is (seq aliases) "the alias table is empty — is the resource on the classpath?")
  (doseq [{:keys [command method template] :as entry} aliases]
    (testing (str/join " " command)
      (is (vector? command))
      (is (seq command))
      (is (contains? #{:get :post} method)
          (str "unsupported method " (pr-str method)))
      (is (string? template))
      (is (str/starts-with? template "/")
          (str "template must be a path: " (pr-str template)))
      (is (not (contains? entry :route))
          "aliases carry a template, never a pre-filled route"))))

(deftest every-template-parameter-has-a-source
  (doseq [{:keys [command template params]} aliases]
    (testing (str/join " " command)
      (let [declared (set (map :name params))
            used (template-params template)]
        (is (= used declared)
            (str "template uses " used " and :params declares " declared))
        (doseq [{:keys [name flag]} params]
          (is (keyword? flag)
              (str "path parameter " name " has no flag to fill it from")))))))

(def ^:private known-parses #{:long :comma-list :boolish :file-contents})

(defn- leaf-specs [body]
  (mapcat (fn [[k v]]
            (if (or (contains? v :flag) (contains? v :enrollment-key))
              [[k v]]
              (leaf-specs v)))
          body))

(deftest body-specs-use-known-vocabulary
  (doseq [{:keys [command body]} aliases
          [k spec] (leaf-specs (or body {}))]
    (testing (str (str/join " " command) " body " k)
      (is (empty? (set/difference (set (keys spec))
                                  #{:flag :required? :parse :default :enrollment-key}))
          (str "unknown spec keys " (pr-str (keys spec))))
      (when-let [p (:parse spec)]
        (is (contains? known-parses p)
            (str ":parse " p " is not one commands.cljc implements"))))))

(deftest get-commands-carry-no-body
  (doseq [{:keys [command method body]} aliases]
    (when (= :get method)
      (is (nil? body)
          (str (str/join " " command) " is a GET and declares a body")))))

(deftest a-collision-with-the-registry-must-agree
  ;; Aliases win over the generated registry, so a name in both is a shadow.
  ;; That is allowed -- `business list` is deliberately in both -- but only
  ;; while they mean the same request. The day they diverge, the operator gets
  ;; the alias and the registry's help text describes something else.
  (let [by-name (into {} (map (juxt commands/command-name identity)) generated)]
    (doseq [{:keys [command method template]} aliases]
      (when-let [g (get by-name (str/join " " command))]
        (testing (str/join " " command)
          (is (= (str/upper-case (name method)) (:method g))
              "alias and generated command disagree on method")
          ;; Placeholder NAMES are flag names, not route identity: the alias
          ;; takes `--id` where the generated command takes `--business`, and
          ;; both fill the same segment. Normalising them keeps this checking
          ;; the path and not the spelling of a flag.
          (is (= (normalize-template template) (normalize-template (:template g)))
              "alias and generated command disagree on path"))))))

(deftest host-side-commands-are-not-also-routed
  (let [routed (set (map :command aliases))]
    (doseq [{:keys [command why]} (commands/host-side-commands)]
      (testing (str/join " " command)
        (is (not (contains? routed command))
            "a command cannot be both host-side and a route")
        (is (not (str/blank? why))
            "a host-side command must say why it has no route")))))

(deftest resolution-prefers-the-longest-name
  (is (= ["bots" "list"] (:command (commands/resolve-alias ["bots" "list"]))))
  (is (= ["itonami" "session" "messages"]
         (:command (commands/resolve-alias ["itonami" "session" "messages"]))))
  (is (nil? (commands/resolve-alias ["bots"]))
      "a group name alone is not a command"))

(deftest requests-match-what-cli-clj-sent
  (testing "bots task"
    (is (= {:method :post
            :path "/api/agent-bots/b-1/messages"
            :body {:text "hello"}
            :timeout-seconds 660}
           (commands/alias-request (commands/resolve-alias ["bots" "task"])
                                   {:id "b-1" :text "hello"} {}))))
  (testing "itonami session list defaults the profile"
    (is (= {:method :get :path "/p/default/api/sessions"}
           (commands/alias-request (commands/resolve-alias ["itonami" "session" "list"])
                                   {} {}))))
  (testing "tenant connect nests the budget and drops absent keys"
    (is (= {:method :post
            :path "/v1/tenant-connections"
            :body {:tenant_id "t-1"
                   :capabilities ["read" "write"]
                   :budget {:max_operations 10}}}
           (commands/alias-request (commands/resolve-alias ["tenant" "connect"])
                                   {:tenant "t-1" :cap "read, write"
                                    :max-operations "10"} {}))))
  (testing "a missing required flag is refused by name"
    (is (= :text
           (:flag (ex-data (try (commands/alias-request
                                 (commands/resolve-alias ["bots" "task"])
                                 {:id "b-1"} {})
                                (catch clojure.lang.ExceptionInfo e e))))))))

(deftest business-bind-refuses-to-clear-everything
  ;; `bind!` reads a present-but-empty key as "clear this face", so a call that
  ;; named no face at all must not reach the server.
  (is (thrown? clojure.lang.ExceptionInfo
               (commands/alias-request (commands/resolve-alias ["business" "bind"])
                                       {:id "b-1"} {})))
  (is (= {:method :post :path "/api/business/b-1/bind" :body {:model "m"}}
         (commands/alias-request (commands/resolve-alias ["business" "bind"])
                                 {:id "b-1" :model "m"} {}))))
