(ns gen-origins
  "Build the published domain registry from what this deployment has received.

  Observation is regenerated; classification is not. `merge-known` overlays the
  curated fields from the existing file, so running this after somebody has said
  what a domain is does not erase them — only the counts and dates move.

    clojure -M -e \"(require 'gen-origins)(gen-origins/-main)\"

  Writes `resources/cloud-itonami-app.origins.edn`, which is also the file
  published to yabai. Nothing here reads a message body."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [cloud.itonami.app.mail-origins :as origins]
            [cloud.itonami.app.mail-projects :as mail-projects]))

(def output "resources/cloud-itonami-app.origins.edn")

(defn- domain-of [message]
  (or (mail-projects/relay-origin (:from-email message))
      (some-> (:from-email message) str str/lower-case (str/split #"@") second)))

(defn -main [& [state-path]]
  (let [state (edn/read-string
               (slurp (or state-path
                          (str (System/getProperty "user.home")
                               "/.cloud-itonami/data/state.edn"))))
        messages (vals (get-in state [:mail :messages]))
        curated (:origins (when (.isFile (io/file output))
                            (edn/read-string (slurp output))))
        entries (origins/merge-known (origins/observe messages domain-of)
                                     (or curated []))
        value {:schema origins/schema
               :note (str "このファイルは受信の観測から生成される。"
                          "件数と日付は毎回上書きされ、"
                          ":origin/kind :route/projects :trust/level は"
                          "人が書いた値が保持される。"
                          "trust は件数から推論しない。")
               :counts {:domains (count entries)
                        :messages (count messages)
                        :classified (count (remove #(= :unknown (:origin/kind %))
                                                   entries))
                        :self-registered (count (filter #(= :self-registered
                                                            (:trust/level %))
                                                        entries))}
               :origins entries}]
    (io/make-parents output)
    (spit output (with-out-str (pprint/pprint value)))
    (println "wrote" output
             (str "(" (count entries) " domains from "
                  (count messages) " messages)"))))
