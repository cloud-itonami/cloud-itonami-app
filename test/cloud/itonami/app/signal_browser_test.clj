(ns cloud.itonami.app.signal-browser-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest browser-key-management-ratchet-and-group-session
  (let [{:keys [exit out err]} (shell/sh "node" "test/signal_browser_test.js")]
    (is (zero? exit) (str err "\n" out))
    (is (str/includes? out "direct ratchet + group sender-key: ok"))))
