(ns cloud.itonami.app.device-test
  "Which device this install is, and what it does when it cannot tell.

  The tests that matter here are the ones that keep three OUTCOMES apart:
  configured, never configured, and configured with something unusable. All
  three make `local-id` return a device name or nil, so a test that only reads
  `local-id` would pass on a deployment whose configuration was silently
  discarded — the shape ADR-2608136000 names."
  (:require [clojure.test :refer [deftest is testing]]
            [cloud.itonami.app.device :as device]
            [cloud.itonami.app.peer :as peer]))

(defn- with-device [device-id f]
  (try (device/reset-for-test! device-id) (f)
       (finally (device/reset-for-test! nil))))

(defn- stderr-of
  "Run `f` and return what it wrote to stderr.

  The warning is the only thing that separates \"this deployment set a device
  name we cannot use\" from \"this deployment set none\". Asserting on it is
  therefore not incidental to the behaviour; it IS the behaviour."
  [f]
  (let [captured (java.io.StringWriter.)]
    (binding [*err* captured] (f))
    (str captured)))

(deftest a-device-name-is-the-half-of-an-address-that-can-carry-one
  (testing "names a handle can carry"
    (is (peer/device-name? "studio"))
    (is (peer/device-name? "mac-mini.local"))
    (is (peer/device-name? "a1"))
    (is (peer/device-name? "M4"))
    (is (peer/device-name? "one_two")))
  (testing "names it cannot"
    (is (not (peer/device-name? nil)))
    (is (not (peer/device-name? "")))
    (is (not (peer/device-name? "   ")))
    (is (not (peer/device-name? "-leading-dash")) "must start alphanumeric")
    (is (not (peer/device-name? ".leading-dot")))
    (is (not (peer/device-name? "two words")))
    (is (not (peer/device-name? "at@sign")) "a second @ would split the handle")
    (is (not (peer/device-name? "bot:x")) "a colon is the scheme's, not a device's"))
  (testing "the answer is the address grammar's, not a second copy of it"
    ;; If these ever disagree, one of them is a rule nobody is enforcing.
    (doseq [candidate ["studio" "mac-mini.local" "-leading-dash" "two words" "at@sign"]]
      (is (= (peer/device-name? candidate)
             (= candidate (:device (peer/parse-address (str "bot:d@" candidate)))))
          (str "device-name? disagreed with parse-address about " (pr-str candidate))))))

(deftest an-install-that-was-never-enrolled-has-no-device-name
  (with-device nil
    (fn []
      (is (nil? (device/local-id))
          "nil is the fail-closed answer: no handle can be local")))
  (testing "and says nothing about it, because there is nothing wrong"
    (is (= "" (stderr-of #(device/configure! {})))
        "a deployment that set no device name is not misconfigured")
    (is (= "" (stderr-of #(device/configure! {:devices {:local nil}}))))))

(deftest a-configured-device-name-is-what-this-install-answers-to
  (with-device nil
    (fn []
      (device/configure! {:devices {:local "studio"}})
      (is (= "studio" (device/local-id)))))
  (testing "whitespace around it is not part of the name"
    (with-device nil
      (fn []
        (device/configure! {:devices {:local "  studio  "}})
        (is (= "studio" (device/local-id)))))))

(deftest a-device-name-nobody-could-type-is-refused-out-loud
  (with-device nil
    (fn []
      (let [written (stderr-of #(device/configure! {:devices {:local "two words"}}))]
        (is (nil? (device/local-id))
            "an unusable name leaves the install unenrolled rather than unaddressable")
        (is (re-find #"two words" written)
            "the refusal names the value, so the operator can find it in config.edn")
        (is (re-find #"device" written)))))
  (testing "and is reported once for a value, not on every read"
    ;; `local-id` is on the path of every peer note. A warning repeated there
    ;; would be a log nobody is reading by the second minute.
    (with-device nil
      (fn []
        (device/configure! {:devices {:local "two words"}})
        (let [again (stderr-of
                     #(dotimes [_ 5]
                        (device/configure! {:devices {:local "two words"}})))]
          (is (= "" again) "the same bad value was reported more than once"))))))
