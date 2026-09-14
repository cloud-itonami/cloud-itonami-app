(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/release/classes")
(def uber-file "target/release/cloud-itonami-app.jar")

(defn clean [_]
  (b/delete {:path "target/release"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis (b/create-basis {:project "deps.edn"})
           :main 'clojure.main})
  {:uber-file uber-file})
