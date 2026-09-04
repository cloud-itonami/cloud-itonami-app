#!/usr/bin/env nbb
;; itonami — the cloud-itonami-app front end, composed as a dsh-style plugin
;; tree (deepseek-harness contract, ported to nbb ClojureScript).
;;
;; Every behavior is a plugin mounted into one context:
;;   itonami.config   claims :ctx/config   (paths, EDN readers)
;;   itonami.theme    claims :ctx/theme    (skins, hermes skin-engine parity)
;;   itonami.chat     claims :ctx/chat     (slash registry, REPL state)
;;   itonami.agent    claims :ctx/agent    (run submission + SSE pump)
;;   itonami.commands claims :ctx/commands (top-level generated command surface)
;;
;; The plugin list below is the shipped profile (`default`). Any layer can be
;; replaced by appending a plugin with the same :name — the same patch rule a
;; dsh profile uses. `itonami --dump-config` prints the mounted tree.

(require '[itonami-harness :as h]
         '[itonami-theme :as theme]
         '[itonami-chat :as chat])
(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '["node:fs" :as fs]
         '["node:os" :as os]
         '["node:path" :as path]
         '["node:child_process" :as cp]
         '["nbb.classpath" :as classpath]
         '[nbb.core :as nbb*])

(def *file* nbb.core/*file*)
