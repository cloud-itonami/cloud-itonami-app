(ns cloud.itonami.app.policy-artifact
  "Release-time builder for the signed provider-policy Wasm package."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [cloud.itonami.app.policy :as policy]))

(defn -main [& [output-directory]]
  (let [private-key
        (some-> (System/getenv
                 "CLOUD_ITONAMI_POLICY_SIGNING_PRIVATE_KEY")
                str/trim not-empty)
        key-id
        (some-> (System/getenv "CLOUD_ITONAMI_POLICY_SIGNING_KEY_ID")
                str/trim not-empty)]
    (when-not (and output-directory private-key key-id)
      (binding [*out* *err*]
        (println
         (str "Usage: CLOUD_ITONAMI_POLICY_SIGNING_PRIVATE_KEY=<PKCS8 base64> "
              "CLOUD_ITONAMI_POLICY_SIGNING_KEY_ID=<id> "
              "clojure -M:policy-artifact <output-directory>")))
      (System/exit 2))
    (pprint/pprint
     (policy/write-signed-artifact!
      output-directory private-key key-id))))
