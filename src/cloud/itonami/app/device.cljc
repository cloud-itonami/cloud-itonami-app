(ns cloud.itonami.app.device
  "Which registered device this install IS.

  ADR-0062 decides whether `bot:<id>@<device>` may be addressed, and one of the
  four facts it decides from is whether the named device is THIS machine. Until
  now nothing in the application could answer that: `local-device` was passed
  as `nil` at every call site, so `device-is-local` was decided by the ABSENCE
  of a device rather than by a comparison. This namespace is the missing input,
  and it is the whole of what it does.

  ## Configured, not derived

  It would be easy to derive a name for this machine — a hash of the identity
  seed, a hostname, a MAC address — and every one of those is a **discovery
  step**, which ADR-0062 refuses by name:

  > An unregistered device is not addressable. There is no discovery step. A
  > handle is not a guess, and a message that reached a machine nobody enrolled
  > reached somewhere nobody can name.

  A machine that names itself is a machine nobody enrolled. So the value comes
  from the deployment's own configuration, under `[:devices :local]`, and it is
  expected to be the id this install was registered under with
  `messenger/register-device!`. Recording it in both places is the point: the
  registry says the device exists, and this says which one we are.

  ## nil is an answer, not a gap

  An install that has not been enrolled has no device name, `local-id` returns
  nil, and every handle carrying a device fails to be local. That is the
  fail-closed direction: the cost of being wrong is a refused note, where the
  cost of guessing is a note delivered to the wrong computer and reported as
  sent.

  ## A malformed id is reported

  A configured value the address grammar cannot express would make this install
  permanently unaddressable while `config.edn` looks like it was set up. Treated
  silently as unset, that is indistinguishable from never having configured it
  at all — the shape ADR-2608136000 calls a check that cannot run returning the
  value of a check that ran and found nothing. So it is refused loudly and once,
  and the deployment continues without a device name rather than with one
  nobody can type."
  (:require [clojure.string :as str]
            [cloud.itonami.app.peer :as peer]))

(defonce ^:private runtime-config (atom nil))

;; Reported once per configured value rather than on every read: `local-id` is
;; asked on the path of every peer note, and a warning printed there would be a
;; log nobody reads by the second minute.
(defonce ^:private reported (atom nil))

(defn- warn! [message]
  #?(:clj (binding [*out* *err*] (println message))
     :cljs (js/console.warn message)
     :default nil))

(defn- admit
  "The configured id, or nil with one report when the grammar cannot express it."
  [value]
  (let [value (some-> value str str/trim not-empty)]
    (cond
      (nil? value) nil
      (peer/device-name? value) value
      :else
      (do (when (not= value @reported)
            (reset! reported value)
            (warn! (str "cloud-itonami: [:devices :local] \"" value "\" は Bot "
                        "アドレスの device 名として使えません。この install は "
                        "device 名を持たないものとして続行します。")))
          nil))))

(defn configure!
  "Record which device this install answers to, from the loaded configuration."
  [configuration]
  (reset! runtime-config (admit (get-in configuration [:devices :local])))
  @runtime-config)

(defn local-id
  "The device id this install is registered as, or nil when it is not enrolled.

  Callers hand this to `peer/may-address?` as `:local-device`. They must not
  substitute a default for nil: a nil that became \"this machine\" would make
  every handle local, which is the one outcome `reaches-another-machine?`
  exists to prevent."
  []
  @runtime-config)

(defn reset-for-test!
  "Set the device name directly. Tests only — the running server goes through
  `configure!`, so what it serves is what is on disk."
  [device-id]
  (reset! reported nil)
  (reset! runtime-config (admit device-id)))
