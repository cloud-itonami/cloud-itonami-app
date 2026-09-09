(ns cloud.itonami.app.controller-standby-test
  (:require [clojure.test :refer [deftest is]]
            [cloud.itonami.app.controller-entry :as entry]
            [cloud.itonami.app.controller-standby :as standby])
  (:import [java.net URI HttpURLConnection]))

(deftest mode-is-chosen-before-active-namespace-load
  (is (= 'cloud.itonami.app.server/-main (entry/entry-symbol nil)))
  (is (= 'cloud.itonami.app.controller-standby/-main (entry/entry-symbol "standby")))
  (is (thrown? clojure.lang.ExceptionInfo (entry/entry-symbol "typo"))))

(deftest standby-refuses-all-work
  (let [server (standby/start! 0)
        port (.getPort (.getAddress server))]
    (try
      (is (= "127.0.0.1" (.getHostString (.getAddress server))))
      (doseq [[method path status] [["GET" "/health" 200]
                                   ["POST" "/health" 503]
                                   ["GET" "/api/bots" 503]
                                   ["POST" "/api/agent-bots/goals" 503]
                                   ["GET" "/health/extra" 503]]]
        (let [^HttpURLConnection conn (.openConnection
                                      (.toURL (URI/create (str "http://127.0.0.1:" port path))))]
          (try
            (.setRequestMethod conn method)
            (is (= status (.getResponseCode conn)))
            (is (re-find #"\"executionEnabled\":false"
                         (slurp (if (= status 200) (.getInputStream conn) (.getErrorStream conn)))))
            (finally (.disconnect conn)))))
      (finally (.stop server 0)))))
