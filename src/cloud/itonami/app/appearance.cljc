(ns cloud.itonami.app.appearance
  "Appearance modes of the loopback workspace — today `light` (DADS as shipped)
  and `8bit`.

  8-bit mode is an APPEARANCE of the one workspace, not a second UI: the
  document, its views, its ids and its information architecture are identical
  in both modes, and `interaction.js` cannot tell them apart. What changes is
  one attribute, `data-appearance`, on the `.workspace` root, and one CSS layer
  that reads it. That is the same shape kotoba-ui uses for light/dark on the
  itonami.cloud cockpit (`kotoba-ui.theme/appearance-attr`), and it is what
  keeps the single-page rule (ADR-2608080100) true: a mode is state, not a
  location.

  The palette is the one the cockpit's 8-BIT MODE floor already draws with
  (`cloud_itonami.site.home`), so a Bot looks like the same Bot on the public
  floor and in the workspace. Design decisions and their rationale are in
  docs/adr/0091-8bit-mode-is-an-appearance-of-one-workspace.md.

  Portable on purpose: the resolution and the stylesheet are data, and the
  ClojureScript half of the test suite executes them (`test/portable_nbb.cljs`)."
  (:require [clojure.string :as str]))

(def modes
  "Every appearance the workspace can render. Order is the toggle order."
  ["light" "8bit" "grok"])

(def default-mode "light")

(def storage-key
  "Where the browser remembers the person's choice. The server default from
  `[:ui :appearance]` applies until a choice has been made on this device."
  "cloud-itonami-appearance")

(defn normalize
  "A configured value → a mode name, or nil when it names nothing we render.
  Accepts keywords and strings (`:8bit`, \"8bit\", \"8-bit\", \"eightbit\")."
  [value]
  (let [s (some-> value
                  (cond-> (keyword? value) name)
                  str str/trim str/lower-case)]
    (case s
      ("8bit" "8-bit" "eightbit" "eight-bit" "pixel" "retro") "8bit"
      ("grok" "grob" "dark-chat" "chat-dark") "grok"
      ("light" "default" "dads") "light"
      nil)))

(defn resolve-mode
  "The appearance the server renders for a fresh document: the configured
  `[:ui :appearance]`, or `light` when it is absent or names nothing."
  [config]
  (or (normalize (get-in config [:ui :appearance])) default-mode))

(defn residency-plane
  "Where the agent lives, as the workspace shows it: `:cloud` when the
  configuration says so, `:local` for everything else — absent, nil, a typo.
  Same shape as `resolve-mode`: a value that names nothing is not a crash."
  [config]
  (let [v (get-in config [:residency :plane])
        s (some-> v (cond-> (keyword? v) name) str str/trim str/lower-case)]
    (if (= "cloud" s) :cloud :local)))

(defn next-mode
  "The mode a toggle moves to. Unknown input starts the cycle over."
  [mode]
  (let [current (or (normalize mode) default-mode)
        i (or (first (keep-indexed (fn [i m] (when (= m current) i)) modes)) 0)]
    (nth modes (mod (inc i) (count modes)))))

;; ── the 8-bit palette ────────────────────────────────────────────────────
;; Eleven of these are the cockpit floor's colours (cloud_itonami.site.home:
;; night, indigo, cream, sun, leaf, grass, sky, blue, pink, wood, white); the
;; other five (slate, sand, orange, red, gray) are this workspace's state and
;; chrome colours, chosen in the same family. Sixteen entries is the whole
;; system: an 8-bit palette that keeps growing stops being one.

(def palette
  {:night   "#181425"   ; ink, borders, hard shadows
   :indigo  "#262b5c"   ; sidebar, chrome
   :slate   "#3a4466"   ; secondary chrome, disabled
   :cream   "#fff1d2"   ; paper, primary text on night
   :sand    "#f4dfb4"   ; secondary paper (cards)
   :sun     "#ffd866"   ; accent: primary actions, focus
   :orange  "#f77622"   ; warning
   :red     "#e43b44"   ; danger / error
   :leaf    "#6abe30"   ; success, live
   :grass   "#8bd450"   ; the floor
   :sky     "#73eff7"   ; information, links
   :blue    "#3b7cff"   ; selected, screens
   :pink    "#ff77a8"   ; people, attention
   :wood    "#c97b42"   ; furniture, neutral warm
   :gray    "#8b9bb4"   ; muted text on night
   :white   "#ffffff"})

(def ^:private font-stack
  ;; No network font: the page's zero-external-request default stays true
  ;; (jp-go-dds.page). DotGothic16 / Press Start 2P are used when the person has
  ;; installed them; otherwise the system monospace at a pixel-friendly size.
  "\"DotGothic16\",\"Press Start 2P\",\"Silkscreen\",ui-monospace,SFMono-Regular,Menlo,monospace")

(defn- root-tokens []
  (str/join ""
            (for [[k v] (sort-by key palette)]
              (str "--eightbit-" (name k) ":" v ";"))))

;; ── the grok palette ─────────────────────────────────────────────────────
;; Modelled on the Grok Bot desktop client's dark chat surfaces: a near-black
;; window, a slightly lighter rail, raised cards, one accent. Eight entries;
;; a palette that keeps growing stops being one.
(def ^:private grok-palette
  {:base   "#0d0d0d"   ; window background
   :rail   "#161616"   ; sidebar / bots rail
   :raised "#1f1f21"   ; cards, bubbles, inputs
   :hover  "#2a2a2d"   ; hover, selected, chips
   :border "#2e2e30"   ; hairlines
   :text   "#f2f2f2"   ; primary text
   :muted  "#9b9b9f"   ; secondary text, metadata
   :accent "#3b82f6"}) ; the one accent (person bubbles, primary actions)

(defn- root-tokens-grok []
  (str/join ""
            (for [[k v] (sort-by key grok-palette)]
              (str "--grok-" (name k) ":" v ";"))))

(def ^:private grok-css
  (str
   ".workspace[data-appearance=\"grok\"]{" (root-tokens-grok)
   "--grok-radius:12px;--grok-radius-lg:16px;"
   "font-family:-apple-system,BlinkMacSystemFont,'Hiragino Sans','Hiragino Kaku Gothic ProN',sans-serif;"
   "font-size:14px;line-height:1.6;"
   "color:var(--grok-text);background:var(--grok-base)}\n"
   ;; chrome
   ".workspace[data-appearance=\"grok\"] .sidebar{background:var(--grok-rail);color:var(--grok-text);"
   "border-right:1px solid var(--grok-border)}\n"
   ".workspace[data-appearance=\"grok\"] .brand__eyebrow{color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] .brand__name{color:var(--grok-text)}\n"
   ".workspace[data-appearance=\"grok\"] .brand__mark{background:var(--grok-accent);color:#fff;border-radius:8px}\n"
   ".workspace[data-appearance=\"grok\"] .local-nav__item{color:var(--grok-muted);border-radius:8px}\n"
   ".workspace[data-appearance=\"grok\"] .local-nav__item:hover{background:var(--grok-hover);color:var(--grok-text)}\n"
   ".workspace[data-appearance=\"grok\"] .local-nav__item[aria-current=\"page\"]{background:var(--grok-hover);color:var(--grok-text)}\n"
   ".workspace[data-appearance=\"grok\"] .sidebar__status{color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] .topbar{background:var(--grok-base);border-bottom:1px solid var(--grok-border);color:var(--grok-text)}\n"
   ;; controls
   ".workspace[data-appearance=\"grok\"] button:not(.mobile-nav-backdrop),"
   ".workspace[data-appearance=\"grok\"] .tool-button,"
   ".workspace[data-appearance=\"grok\"] .context-button,"
   ".workspace[data-appearance=\"grok\"] .composer-button:not(.composer-button--stop){"
   "border-radius:8px;border:1px solid transparent;background:var(--grok-hover);color:var(--grok-text);font:inherit;font-weight:600}\n"
   ".workspace[data-appearance=\"grok\"] .tool-button:not([disabled]):hover,"
   ".workspace[data-appearance=\"grok\"] .bots-rail__item:hover,"
   ".workspace[data-appearance=\"grok\"] .local-nav__item:hover{background:var(--grok-hover)}\n"
   ".workspace[data-appearance=\"grok\"] .primary-action,"
   ".workspace[data-appearance=\"grok\"] button[type=\"submit\"]{"
   "background:var(--grok-accent);color:#fff}\n"
   ".workspace[data-appearance=\"grok\"] button[disabled],"
   ".workspace[data-appearance=\"grok\"] .tool-button[disabled]{background:var(--grok-hover);color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] input,"
   ".workspace[data-appearance=\"grok\"] select,"
   ".workspace[data-appearance=\"grok\"] textarea{"
   "border-radius:8px;border:1px solid var(--grok-border);background:var(--grok-raised);color:var(--grok-text);font:inherit}\n"
   ".workspace[data-appearance=\"grok\"] :focus-visible{outline:2px solid var(--grok-accent);outline-offset:2px}\n"
   ;; surfaces
   ".workspace[data-appearance=\"grok\"] .local-card,.workspace[data-appearance=\"grok\"] .data-card,"
   ".workspace[data-appearance=\"grok\"] .connector-card,.workspace[data-appearance=\"grok\"] .suggestion-card,"
   ".workspace[data-appearance=\"grok\"] .settings-card,.workspace[data-appearance=\"grok\"] .bots-card{"
   "border-radius:var(--grok-radius);border:1px solid var(--grok-border);background:var(--grok-raised)}\n"
   ".workspace[data-appearance=\"grok\"] .settings-notice{border-radius:var(--grok-radius);border:1px solid var(--grok-border);background:var(--grok-raised)}\n"
   ".workspace[data-appearance=\"grok\"] .req-row__state,.workspace[data-appearance=\"grok\"] .bots-chip,"
   ".workspace[data-appearance=\"grok\"] .bots-card__state,.workspace[data-appearance=\"grok\"] .matrix__state{"
   "border-radius:999px;border:1px solid var(--grok-border);background:var(--grok-hover)}\n"
   ;; chat: quiet grey bubbles, muted metadata, pill composer
   ".workspace[data-appearance=\"grok\"] .bots-msg__bubble{"
   "border-radius:var(--grok-radius-lg);border:1px solid var(--grok-border);background:var(--grok-raised)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-msg[data-role='person'] .bots-msg__bubble{"
   "background:var(--grok-accent);color:#fff;border-color:transparent}\n"
   ".workspace[data-appearance=\"grok\"] .bots-msg__resident{color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-msg__resident-at{color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-msg__bubble code{background:var(--grok-hover)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-msg__bubble pre{background:var(--grok-rail);border:1px solid var(--grok-border)}\n"
   ".workspace[data-appearance=\"grok\"] .composer{border-radius:999px;border:1px solid var(--grok-border);background:var(--grok-raised)}\n"
   ".workspace[data-appearance=\"grok\"] .composer textarea{border:0;background:transparent}\n"
   ;; the rail: a conversation list
   ".workspace[data-appearance=\"grok\"] .bots-rail{background:var(--grok-rail);border-right:1px solid var(--grok-border)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-rail__item{border-radius:10px}\n"
   ".workspace[data-appearance=\"grok\"] .bots-rail__item[aria-current=\"true\"],"
   ".workspace[data-appearance=\"grok\"] .bots-rail__item.is-selected{background:var(--grok-hover)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-rail__name{color:var(--grok-text)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-rail__last,.workspace[data-appearance=\"grok\"] .bots-rail__time,"
   ".workspace[data-appearance=\"grok\"] .bots-rail__group{color:var(--grok-muted)}\n"
   ".workspace[data-appearance=\"grok\"] .bot-avatar{border-radius:10px;background:var(--grok-hover)}\n"
   ".workspace[data-appearance=\"grok\"] .bot-avatar::before,.workspace[data-appearance=\"grok\"] .bot-avatar::after{"
   "background:var(--grok-text)}\n"
   ".workspace[data-appearance=\"grok\"] .bots-dot{background:var(--grok-accent)}\n"
   ;; the toggle itself shows what it will do
   ".appearance-toggle[data-next=\"grok\"]::before{content:\"◍ \"}\n"
   "@media(prefers-reduced-motion:reduce){.workspace[data-appearance=\"grok\"] *{animation:none}}\n"))

(def css
  "The 8-bit layer. Scoped under the `data-appearance` attribute so that the
  light workspace is byte-for-byte what it was; every rule here is an
  override, and nothing in it invents a DADS token
  (`core-test/app-css-only-references-design-system-tokens-that-exist`
  covers this string too, because `web/app-css` concatenates it).

  Design rules, in the order the eye meets them:
    grid    4px unit; spacing is 4/8/12/16/24.
    shape   radius 0 everywhere; 3px borders; hard 4px offset shadows.
    type    pixel stack, 13px/1.6, +.02em tracking, uppercase eyebrows.
    colour  night ink on cream paper; indigo chrome; sun for the one
            primary action; leaf/sky/orange/red for the four states.
    motion  `steps()` timing only; `prefers-reduced-motion` turns it off.
    pixels  `image-rendering: pixelated` on every raster.
  What never changes: ids, ARIA, tab order, the DADS semantics of state
  (success/error), and reading order — a person who uses the light workspace
  with a screen reader hears exactly the same document in 8-bit."
  (str
   ".workspace[data-appearance=\"8bit\"]{" (root-tokens)
   "--eightbit-shadow:4px 4px 0 var(--eightbit-night);"
   "--eightbit-border:3px solid var(--eightbit-night);"
   "font-family:" font-stack ";font-size:13px;line-height:1.6;letter-spacing:.02em;"
   "color:var(--eightbit-night);background:var(--eightbit-cream);"
   "image-rendering:pixelated;image-rendering:crisp-edges}\n"
   ;; chrome
   ".workspace[data-appearance=\"8bit\"] .sidebar{background:var(--eightbit-indigo);color:var(--eightbit-cream);"
   "border-right:var(--eightbit-border);box-shadow:inset -4px 0 0 var(--eightbit-night)}\n"
   ".workspace[data-appearance=\"8bit\"] .brand__eyebrow{color:var(--eightbit-sun);text-transform:uppercase;letter-spacing:.12em;font-weight:800}\n"
   ".workspace[data-appearance=\"8bit\"] .brand__name{color:var(--eightbit-cream)}\n"
   ".workspace[data-appearance=\"8bit\"] .brand__mark{background:var(--eightbit-sun);color:var(--eightbit-night);border:var(--eightbit-border);border-radius:0;box-shadow:var(--eightbit-shadow)}\n"
   ".workspace[data-appearance=\"8bit\"] .local-nav__item{color:var(--eightbit-cream);border-radius:0;border:3px solid transparent}\n"
   ".workspace[data-appearance=\"8bit\"] .local-nav__item:hover{border-color:var(--eightbit-cream)}\n"
   ".workspace[data-appearance=\"8bit\"] .local-nav__item[aria-current=\"page\"]{background:var(--eightbit-sun);color:var(--eightbit-night);border-color:var(--eightbit-night);box-shadow:var(--eightbit-shadow)}\n"
   ".workspace[data-appearance=\"8bit\"] .nav-badge{background:var(--eightbit-pink);color:var(--eightbit-night);border-radius:0;border:2px solid var(--eightbit-night)}\n"
   ".workspace[data-appearance=\"8bit\"] .sidebar__status{color:var(--eightbit-gray)}\n"
   ".workspace[data-appearance=\"8bit\"] .sidebar__status strong{color:var(--eightbit-leaf)}\n"
   ".workspace[data-appearance=\"8bit\"] .topbar{background:var(--eightbit-sand);border-bottom:var(--eightbit-border)}\n"
   ".workspace[data-appearance=\"8bit\"] .topbar__title{text-transform:uppercase;letter-spacing:.08em}\n"
   ;; controls — every button is a pixel chip; the primary one wears the sun
   ;; `.mobile-nav-backdrop` is a <button> that is a full-viewport scrim, not
   ;; a control; `.composer-button--stop` keeps its dark stop styling.
   ".workspace[data-appearance=\"8bit\"] button:not(.mobile-nav-backdrop):not(.composer-button--stop),"
   ".workspace[data-appearance=\"8bit\"] .tool-button,"
   ".workspace[data-appearance=\"8bit\"] .context-button,"
   ".workspace[data-appearance=\"8bit\"] .composer-button:not(.composer-button--stop){"
   "border-radius:0;border:var(--eightbit-border);background:var(--eightbit-cream);color:var(--eightbit-night);"
   "box-shadow:var(--eightbit-shadow);font:inherit;font-weight:800}\n"
   ".workspace[data-appearance=\"8bit\"] button:not(.mobile-nav-backdrop):not([disabled]):hover,"
   ".workspace[data-appearance=\"8bit\"] .tool-button:not([disabled]):hover,"
   ".workspace[data-appearance=\"8bit\"] .bots-rail__item:hover,"
   ".workspace[data-appearance=\"8bit\"] .local-nav__item:hover{background:var(--eightbit-sand)}\n"
   ".workspace[data-appearance=\"8bit\"] button:not(.mobile-nav-backdrop):active,.workspace[data-appearance=\"8bit\"] .tool-button:active{"
   "transform:translate(2px,2px);box-shadow:2px 2px 0 var(--eightbit-night)}\n"
   ".workspace[data-appearance=\"8bit\"] .primary-action,.workspace[data-appearance=\"8bit\"] button[type=\"submit\"]{"
   "background:var(--eightbit-sun)}\n"
   ".workspace[data-appearance=\"8bit\"] button[disabled],.workspace[data-appearance=\"8bit\"] .tool-button[disabled]{"
   "background:var(--eightbit-slate);color:var(--eightbit-cream);box-shadow:none}\n"
   ".workspace[data-appearance=\"8bit\"] input,.workspace[data-appearance=\"8bit\"] select,.workspace[data-appearance=\"8bit\"] textarea{"
   "border-radius:0;border:var(--eightbit-border);background:var(--eightbit-white);font:inherit}\n"
   ".workspace[data-appearance=\"8bit\"] :focus-visible{outline:3px solid var(--eightbit-blue);outline-offset:2px;border-radius:0}\n"
   ;; surfaces
   ".workspace[data-appearance=\"8bit\"] .local-card,.workspace[data-appearance=\"8bit\"] .data-card,"
   ".workspace[data-appearance=\"8bit\"] .connector-card,.workspace[data-appearance=\"8bit\"] .suggestion-card,"
   ".workspace[data-appearance=\"8bit\"] .settings-card,.workspace[data-appearance=\"8bit\"] .bots-card{"
   "border-radius:0;border:var(--eightbit-border);background:var(--eightbit-sand);box-shadow:var(--eightbit-shadow)}\n"
   ".workspace[data-appearance=\"8bit\"] .settings-notice{border-radius:0;border:var(--eightbit-border);background:var(--eightbit-sand)}\n"
   ".workspace[data-appearance=\"8bit\"] .settings-notice--error{background:var(--eightbit-red);color:var(--eightbit-cream)}\n"
   ;; the four states: the chip stays a chip, the colour says which
   ".workspace[data-appearance=\"8bit\"] .req-row__state,.workspace[data-appearance=\"8bit\"] .bots-chip,"
   ".workspace[data-appearance=\"8bit\"] .bots-card__state,.workspace[data-appearance=\"8bit\"] .matrix__state{"
   "border-radius:0;border:2px solid var(--eightbit-night);background:var(--eightbit-cream);text-transform:uppercase;letter-spacing:.06em}\n"
   ".workspace[data-appearance=\"8bit\"] .req-row__state[data-tone='ok']{background:var(--eightbit-leaf);color:var(--eightbit-night)}\n"
   ".workspace[data-appearance=\"8bit\"] .req-row__state[data-tone='warn']{background:var(--eightbit-orange);color:var(--eightbit-night)}\n"
   ;; chat
   ;; Person messages are `.bots-msg[data-role='person']` (interaction.js);
   ;; the sky bubble must target that, or both sides render the same colour.
   ".workspace[data-appearance=\"8bit\"] .bots-msg__bubble{"
   "border-radius:0;border:var(--eightbit-border);box-shadow:var(--eightbit-shadow);background:var(--eightbit-white)}\n"
   ".workspace[data-appearance=\"8bit\"] .bots-msg[data-role='person'] .bots-msg__bubble{background:var(--eightbit-sky)}\n"
   ".workspace[data-appearance=\"8bit\"] .composer{border-radius:0;border:var(--eightbit-border);background:var(--eightbit-white)}\n"
   ;; the floor: the Bots rail becomes the office, each Bot a sprite on grass
   ".workspace[data-appearance=\"8bit\"] .bots-rail{background-color:var(--eightbit-grass);border:var(--eightbit-border);"
   "background-image:linear-gradient(45deg,rgba(24,20,37,.12) 25%,transparent 25%,transparent 75%,rgba(24,20,37,.12) 75%),"
   "linear-gradient(45deg,rgba(24,20,37,.12) 25%,transparent 25%,transparent 75%,rgba(24,20,37,.12) 75%);"
   "background-position:0 0,8px 8px;background-size:16px 16px}\n"
   ".workspace[data-appearance=\"8bit\"] .bots-rail__item{border-radius:0;border:var(--eightbit-border);background:var(--eightbit-sand);"
   "box-shadow:var(--eightbit-shadow);margin:8px}\n"
   ".workspace[data-appearance=\"8bit\"] .bots-rail__item[aria-current=\"true\"],.workspace[data-appearance=\"8bit\"] .bots-rail__item.is-selected{background:var(--eightbit-sun)}\n"
   ".workspace[data-appearance=\"8bit\"] .bot-avatar{border-radius:0;border:var(--eightbit-border);box-shadow:var(--eightbit-shadow);"
   "background:var(--eightbit-sky);image-rendering:pixelated}\n"
   ".workspace[data-appearance=\"8bit\"] .bot-avatar::before,.workspace[data-appearance=\"8bit\"] .bot-avatar::after{"
   "border-radius:0;background:var(--eightbit-night)}\n"
   ".workspace[data-appearance=\"8bit\"] .bot-avatar[data-status='working']{background:var(--eightbit-leaf);"
   "animation:eightbit-bob .6s steps(2,end) infinite}\n"
   ".workspace[data-appearance=\"8bit\"] .bot-avatar[data-status='waiting-approval']{background:var(--eightbit-pink)}\n"
   ".workspace[data-appearance=\"8bit\"] .bot-avatar[data-status='waiting-connection']{background:var(--eightbit-orange)}\n"
   ".workspace[data-appearance=\"8bit\"] .bots-dot{border-radius:0;border:2px solid var(--eightbit-night)}\n"
   "@keyframes eightbit-bob{0%{transform:translateY(0)}100%{transform:translateY(-3px)}}\n"
   ;; scanlines: a quiet CRT hint on the main surface only, never over text
   ;; contrast — 6% ink at 4px pitch is below the threshold that changes
   ;; measured contrast on cream.
   ".workspace[data-appearance=\"8bit\"] .main{position:relative}\n"
   ".workspace[data-appearance=\"8bit\"] .main::after{content:\"\";position:absolute;inset:0;pointer-events:none;z-index:0;"
   "background:repeating-linear-gradient(0deg,rgba(24,20,37,.06) 0 1px,transparent 1px 4px)}\n"
   ".workspace[data-appearance=\"8bit\"] .main>*{position:relative;z-index:1}\n"
   ;; the toggle itself shows what it will do
   ".appearance-toggle[data-next=\"8bit\"]::before{content:\"▣ \"}\n"
   ".appearance-toggle[data-next=\"light\"]::before{content:\"◻ \"}\n"
   "@media(prefers-reduced-motion:reduce){.workspace[data-appearance=\"8bit\"] .bot-avatar[data-status='working']{animation:none}}\n"
   ;; ── the grok layer ─────────────────────────────────────────────────────
   ;; A dark, chat-first appearance modelled on the Grok Bot desktop client:
   ;; near-black surfaces, a conversation-list rail, quiet grey bubbles on a
   ;; dark raised card, muted centred metadata, and one pill composer. Same
   ;; document, same ids, same reading order — colour and shape only.
   grok-css))

(defn toggle-button
  "The topbar control. `data-next` is what one press moves to, so the label and
  the glyph are derived from the same fact the script flips."
  [mode]
  (let [next (next-mode mode)]
    [:button {:class "tool-button appearance-toggle" :id "appearance-toggle" :type "button"
              :data-mode (or (normalize mode) default-mode)
              :data-next next
              :aria-pressed (if (not= "light" (normalize mode)) "true" "false")
              :aria-label "表示モードを切り替え（light / 8-bit / grok）"
              :title (case next
                       "8bit" "8-BIT MODE にする"
                       "grok" "grokモードにする"
                       "標準表示に戻す")}
     (case next
       "8bit" "8-BIT"
       "grok" "GROK"
       "DADS")]))
