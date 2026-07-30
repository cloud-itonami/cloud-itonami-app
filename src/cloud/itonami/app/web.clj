(ns cloud.itonami.app.web
  "DADS-backed WebKit workspace hosted by kotoba-lang/shell."
  (:require [clojure.java.io :as io]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]))

(def app-css
  "
  body{background:var(--color-neutral-solid-gray-50)}
  button{font:inherit}
  .local-app{max-width:100%;padding:0}
  .workspace{display:grid;grid-template-columns:17rem minmax(0,1fr);min-height:100vh}
  .sidebar{position:sticky;top:0;height:100vh;box-sizing:border-box;padding:1.5rem 1rem;
    background:var(--color-neutral-white);border-right:1px solid var(--color-neutral-solid-gray-200);
    display:flex;flex-direction:column;gap:1.5rem}
  .req-row{padding:.75rem 0;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .req-row__head{display:flex;align-items:center;justify-content:space-between;gap:1rem}
  .req-row__detail{margin:.375rem 0 0;font-size:.875rem;line-height:1.7;
    color:var(--color-neutral-solid-gray-700);overflow-wrap:anywhere}
  .req-row__state{flex:0 0 auto;font-size:.8125rem;font-weight:700;white-space:nowrap;
    padding:.125rem .5rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-100);
    color:var(--color-neutral-solid-gray-700)}
  /* jp-go-dds defines -1 and -2 for each semantic colour and nothing else;
     core-test asserts app-css invents no token, which caught a -4 here. */
  .req-row__state[data-tone='warn']{color:var(--color-semantic-error-1);
    border:1px solid var(--color-semantic-error-2)}
  .req-row__state[data-tone='ok']{color:var(--color-semantic-success-1);
    border:1px solid var(--color-semantic-success-2)}
  .req-row__caveat{margin:.25rem 0 0;font-size:.8125rem;
    color:var(--color-neutral-solid-gray-600)}
  /* One bar per maturity axis. A bar is the right mark for a bounded 0-1
     magnitude, and an UNSCORED axis gets no bar at all — a zero-width bar and a
     zero score would look identical, and 70% of the fleet has no stage score. */
  .axis-row{display:grid;grid-template-columns:minmax(0,11rem) 1fr auto;
    gap:.5rem;align-items:center;margin:.25rem 0;font-size:.8125rem}
  .axis-row__track{height:.5rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-100)}
  .axis-row__fill{height:100%;border-radius:999px;
    background:var(--color-primitive-blue-800)}
  .axis-row__unscored{height:.5rem;border-radius:999px;
    border:1px dashed var(--color-neutral-solid-gray-200)}
  .axis-row__value{font-variant-numeric:tabular-nums;
    color:var(--color-neutral-solid-gray-700)}
  .kv{display:grid;grid-template-columns:minmax(0,14rem) 1fr;gap:.25rem .75rem;
    margin:0;font-size:.8125rem}
  .kv dt{font-weight:700;color:var(--color-neutral-solid-gray-700);
    overflow-wrap:anywhere}
  .kv dd{margin:0;color:var(--color-neutral-solid-gray-800);
    overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
  /* Small multiples, not one multi-series chart. XMILE variables carry their own
     units — a stock in repos and a flow in repos/day — and putting them on one
     y-axis is the dual-axis mistake with extra steps. One panel per variable,
     each with its own scale, its units named. A single series per panel also
     means no legend and no categorical palette to get wrong. */
  .sm-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(13rem,1fr));
    gap:.75rem;margin:0 0 1rem}
  .sm{min-width:0;padding:.625rem .75rem;border-radius:8px;
    background:var(--color-neutral-white);
    border:1px solid var(--color-neutral-solid-gray-200)}
  .sm__name{margin:0;font-size:.8125rem;font-weight:700;
    color:var(--color-neutral-solid-gray-800);overflow-wrap:anywhere}
  .sm__meta{margin:.125rem 0 .375rem;font-size:.75rem;
    color:var(--color-neutral-solid-gray-600)}
  .sm__plot{display:block;width:100%;height:4rem}
  /* Kind is encoded by colour AND named in .sm__meta, so identity is never
     colour-alone. These three are DADS primitives that pass the categorical
     six-checks under --pairs all (blue-800 / orange-700 / cyan-700). */
  .sm__line{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}
  .sm[data-kind='stock'] .sm__line{stroke:var(--color-primitive-blue-800)}
  .sm[data-kind='flow'] .sm__line{stroke:var(--color-primitive-orange-700)}
  .sm[data-kind='aux'] .sm__line{stroke:var(--color-primitive-cyan-700)}
  .sm__axis{stroke:var(--color-neutral-solid-gray-200);stroke-width:1}
  .sm__flat{font-size:.75rem;fill:var(--color-neutral-solid-gray-600)}
  .loop-arrow{color:var(--color-neutral-solid-gray-600)}
  /* The lean canvas is a grid because the nine blocks' arrangement carries
     meaning; a list would render the same data and lose it. Not .record-browser:
     that is a list+detail for picking one of many, and a canvas is read whole. */
  .canvas-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(15rem,1fr));
    gap:.75rem;margin:0 0 1rem}
  .canvas-block{min-width:0;padding:.75rem 1rem;border-radius:8px;
    background:var(--color-neutral-white);
    border:1px solid var(--color-neutral-solid-gray-200)}
  .canvas-block__label{margin:0 0 .5rem;font-size:.8125rem;font-weight:700;
    color:var(--color-neutral-solid-gray-700)}
  .canvas-block__items{margin:0;padding-left:1.1rem;font-size:.875rem;
    line-height:1.7;color:var(--color-neutral-solid-gray-800);overflow-wrap:anywhere}
  .canvas-block__note{margin:.5rem 0 0;font-size:.8125rem;
    color:var(--color-neutral-solid-gray-600);overflow-wrap:anywhere}
  .canvas-block__empty{margin:0;font-size:.875rem;
    color:var(--color-neutral-solid-gray-600)}
  /* The riskiest hypothesis is marked in the datoms (:hyp/risk :riskiest), so
     the highlight follows the data. A canvas that never marked one gets none. */
  .req-row--risk{border-left:4px solid var(--color-key-900);padding-left:.5rem}
  .stat-row{display:grid;grid-template-columns:repeat(auto-fill,minmax(9rem,1fr));
    gap:.75rem;margin:0 0 1rem}
  .stat-tile{min-width:0;padding:.75rem 1rem;border-radius:8px;
    background:var(--color-neutral-white);
    border:1px solid var(--color-neutral-solid-gray-200)}
  .stat-tile__label{display:block;font-size:.75rem;
    color:var(--color-neutral-solid-gray-600)}
  .stat-tile__value{display:block;font-size:1.5rem;line-height:1.4}
  .nav-badge[data-tone='warn']{color:var(--color-semantic-error-1)}
  .nav-badge[data-tone='ok']{color:var(--color-semantic-success-1)}
  .field--checkbox label{font-weight:400}
  .brand{padding:.25rem .75rem}
  .brand__eyebrow{margin:0 0 .25rem;color:var(--color-key-900);font-size:.75rem;
    font-weight:700;letter-spacing:.08em}
  .brand__name{margin:0;font-size:1.25rem;font-weight:700;line-height:1.5}
  .brand__mark{display:none}
  .brand__note{margin:.25rem 0 0;color:var(--color-neutral-solid-gray-600);
    font-size:.8125rem;line-height:1.6}
  .local-nav{display:flex;flex-direction:column;gap:.25rem}
  /* The menu separates what you work ON (a business) from what you work WITH
     (chat, drive, scheduler). Twelve flat items had both kinds in one list. */
  .local-nav__group{margin:.75rem 0 .125rem;padding:0 .75rem;font-size:.6875rem;
    font-weight:700;letter-spacing:.08em;
    color:var(--color-neutral-solid-gray-600)}
  .local-nav__group:first-child{margin-top:0}
  .local-nav__item{width:100%;border:0;border-radius:.5rem;background:transparent;
    color:var(--color-neutral-solid-gray-800);display:flex;align-items:center;gap:.75rem;
    min-height:3rem;padding:.625rem .75rem;text-align:left;cursor:pointer}
  .local-nav__item:hover{background:var(--color-neutral-solid-gray-50)}
  .local-nav__item:disabled{cursor:not-allowed;opacity:.42;background:transparent}
  .local-nav__item:focus-visible{outline:4px solid var(--color-primitive-yellow-300);outline-offset:1px}
  .local-nav__item[aria-current='page']{background:var(--color-key-50);
    color:var(--color-key-900);font-weight:700;border-left:4px solid var(--color-key-900);
    padding-left:.5rem}
  .nav-icon{width:1.5rem;text-align:center;font-size:1.1rem}
  .nav-badge{margin-left:auto;min-width:1.5rem;padding:.1rem .4rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-100);font-size:.75rem;text-align:center}
  .sidebar__status{margin-top:auto;padding:.75rem;border-radius:.5rem;
    background:var(--color-neutral-solid-gray-50);font-size:.8125rem;line-height:1.6}
  .sidebar__status strong{display:block;color:var(--color-semantic-success-2)}
  .main{min-width:0}
  .topbar{min-height:5rem;padding:1rem clamp(1rem,4vw,3rem);background:var(--color-neutral-white);
    border-bottom:1px solid var(--color-neutral-solid-gray-200);display:flex;
    align-items:center;justify-content:space-between;gap:1rem;box-sizing:border-box}
  .topbar__title{margin:0;font-size:1.25rem;line-height:1.5}
  .topbar__meta{margin:0;color:var(--color-neutral-solid-gray-600);font-size:.875rem}
  .view{padding:clamp(1rem,4vw,3rem);max-width:78rem}
  [hidden]{display:none!important}
  .view[hidden]{display:none}
  .view-header{display:flex;justify-content:space-between;align-items:flex-start;
    gap:1rem;margin-bottom:1.5rem}
  .view-header__copy{max-width:45rem}
  .view-lead{margin:.5rem 0 0;color:var(--color-neutral-solid-gray-600);line-height:1.7}
  .local-grid{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(18rem,.85fr);
    gap:1.5rem;align-items:start}
  .local-card{background:var(--color-neutral-white);border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;padding:1.5rem}
  .local-card+.local-card{margin-top:1rem}
  .local-meta{display:grid;grid-template-columns:auto 1fr;gap:.625rem 1rem;
    margin:0;font-size:.9375rem;line-height:1.7}
  .local-meta dt{font-weight:700}.local-meta dd{margin:0}
  .local-response{min-height:10rem;padding:1rem;border-radius:.5rem;
    background:var(--color-neutral-solid-gray-50);border:1px solid var(--color-neutral-solid-gray-200);
    white-space:pre-wrap;line-height:1.7}
  .local-status{margin:0;color:var(--color-neutral-solid-gray-600);font-size:.9375rem;line-height:1.7}
  .local-actions{justify-content:flex-end}
  .chat-view{max-width:none;padding:0}
  .chat-shell{position:relative;display:flex;flex-direction:column;
    height:calc(100vh - 5rem);min-height:32rem;background:var(--color-neutral-white)}
  .chat-header{display:flex;align-items:center;justify-content:space-between;gap:1rem;
    min-height:3.75rem;padding:.625rem clamp(1rem,3vw,2rem);
    border-bottom:1px solid var(--color-neutral-solid-gray-100)}
  .chat-agent{display:flex;align-items:center;gap:.75rem;min-width:0}
  .chat-agent__avatar,.message-avatar{display:grid;place-items:center;flex:0 0 auto;
    width:2rem;height:2rem;border-radius:.625rem;background:var(--color-key-900);
    color:var(--color-neutral-white);font-size:.8125rem;font-weight:700}
  .chat-agent__name{margin:0;font-weight:700;line-height:1.4}
  .chat-agent__state{margin:0;color:var(--color-neutral-solid-gray-600);
    font-size:.75rem;line-height:1.4}
  .chat-header__actions{display:flex;align-items:center;gap:.5rem}
  .model-pill,.tool-button{min-height:2.25rem;border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:999px;background:var(--color-neutral-white);padding:.35rem .75rem;
    color:var(--color-neutral-solid-gray-800);font-size:.8125rem}
  select.model-pill{max-width:15rem;cursor:pointer}
  .tool-button{cursor:pointer}.tool-button:hover{background:var(--color-neutral-solid-gray-50)}
  .tool-button:focus-visible,.suggestion-card:focus-visible,.message-action:focus-visible,
  .composer-button:focus-visible{outline:4px solid var(--color-primitive-yellow-300);outline-offset:1px}
  .chat-scroll{flex:1;overflow-y:auto;overscroll-behavior:contain;scroll-behavior:smooth}
  .chat-thread{width:min(100%,52rem);box-sizing:border-box;margin:0 auto;
    padding:2rem 1.5rem 11rem}
  .chat-empty{min-height:calc(100vh - 18rem);display:grid;place-content:center;
    padding:2rem 0;text-align:center}
  .chat-empty[hidden]{display:none}
  .chat-empty__mark{display:grid;place-items:center;width:3rem;height:3rem;margin:0 auto 1rem;
    border-radius:1rem;background:var(--color-key-50);color:var(--color-key-900);
    font-size:1.25rem;font-weight:700}
  .chat-empty h1{margin:0;font-size:2rem;line-height:1.45}
  .chat-empty>p{margin:.5rem auto 1.5rem;max-width:34rem;
    color:var(--color-neutral-solid-gray-600);line-height:1.7}
  .suggestion-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));
    gap:.75rem;width:min(100%,40rem)}
  .suggestion-card{border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;background:var(--color-neutral-white);padding:1rem;
    text-align:left;cursor:pointer;color:var(--color-neutral-solid-gray-800);line-height:1.55}
  .suggestion-card:hover{border-color:var(--color-key-600);background:var(--color-key-50)}
  .suggestion-card strong{display:block;margin-bottom:.15rem}
  .suggestion-card span{color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .message-row{display:grid;grid-template-columns:2rem minmax(0,1fr);gap:.75rem;
    margin:0 0 1.75rem;scroll-margin-top:1rem}
  .message-row--user{display:flex;justify-content:flex-end}
  .message-row--user .message-body{max-width:min(85%,40rem);padding:.75rem 1rem;
    border-radius:1rem 1rem .25rem 1rem;background:var(--color-neutral-solid-gray-100)}
  .message-body{min-width:0}
  .message-author{margin:0 0 .35rem;font-size:.8125rem;font-weight:700}
  .message-content{margin:0;color:var(--color-neutral-solid-gray-900);
    white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}
  .message-actions{display:flex;gap:.25rem;margin-top:.5rem;min-height:2rem}
  .message-action{border:0;border-radius:.375rem;background:transparent;padding:.3rem .5rem;
    color:var(--color-neutral-solid-gray-600);font-size:.75rem;cursor:pointer}
  .message-action:hover{background:var(--color-neutral-solid-gray-100);
    color:var(--color-neutral-solid-gray-900)}
  .typing{display:flex;gap:.3rem;padding:.55rem 0}
  .typing span{width:.45rem;height:.45rem;border-radius:50%;
    background:var(--color-neutral-solid-gray-400);animation:typing 1.2s infinite}
  .typing span:nth-child(2){animation-delay:.15s}.typing span:nth-child(3){animation-delay:.3s}
  @keyframes typing{0%,60%,100%{transform:translateY(0);opacity:.45}30%{transform:translateY(-.3rem);opacity:1}}
  .composer-dock{position:absolute;z-index:2;left:0;right:0;bottom:0;
    padding:2.5rem 1rem 1rem;background:linear-gradient(transparent 0%,var(--color-neutral-white) 32%)}
  .composer{width:min(100%,52rem);box-sizing:border-box;margin:0 auto;padding:.625rem;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:1.25rem;
    background:var(--color-neutral-white);box-shadow:0 .5rem 2rem rgba(0,0,0,.1)}
  .composer:focus-within{border-color:var(--color-key-600);
    box-shadow:0 0 0 2px var(--color-key-50),0 .5rem 2rem rgba(0,0,0,.1)}
  .composer textarea{display:block;width:100%;max-height:12rem;min-height:3rem;box-sizing:border-box;
    border:0;outline:0;resize:none;padding:.65rem .75rem;background:transparent;
    color:var(--color-neutral-solid-gray-900);font:inherit;line-height:1.65}
  .composer-toolbar{display:flex;align-items:center;justify-content:space-between;gap:.75rem}
  .composer-context{display:flex;align-items:center;gap:.4rem;min-width:0}
  .composer-context .model-pill{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .composer-button{display:grid;place-items:center;width:2.5rem;height:2.5rem;border:0;
    border-radius:50%;background:var(--color-key-900);color:var(--color-neutral-white);
    cursor:pointer;font-size:1.1rem;font-weight:700}
  .composer-button:disabled{background:var(--color-neutral-solid-gray-300);cursor:not-allowed}
  .composer-button--stop{background:var(--color-neutral-solid-gray-900)}
  .composer-button[hidden]{display:none}
  .composer-note{margin:.45rem 0 0;text-align:center;color:var(--color-neutral-solid-gray-500);
    font-size:.6875rem;line-height:1.4}
  .visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;
    overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
  .source-note{display:flex;gap:.5rem;align-items:center;margin:.75rem 0 0;
    color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .source-dot{width:.5rem;height:.5rem;border-radius:50%;background:var(--color-semantic-success-1)}
  .data-list{list-style:none;margin:0;padding:0;border-top:1px solid var(--color-neutral-solid-gray-200)}
  .data-list__item{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:1rem;
    padding:1rem 0;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .data-list__title{margin:0;font-weight:700;line-height:1.6;overflow-wrap:anywhere}
  .data-list__meta{margin:.25rem 0 0;color:var(--color-neutral-solid-gray-600);
    font-size:.875rem;line-height:1.6}
  .data-list__side{color:var(--color-neutral-solid-gray-600);font-size:.8125rem;white-space:nowrap}
  .state-chip{display:inline-flex;align-items:center;padding:.125rem .5rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-100);font-size:.75rem}
  .state-chip--warn{background:var(--color-primitive-yellow-200);color:var(--color-neutral-black)}
  .empty-state{padding:2rem;text-align:center;border:1px dashed var(--color-neutral-solid-gray-300);
    border-radius:.5rem;color:var(--color-neutral-solid-gray-600);line-height:1.7}
  .view-switcher{display:flex;gap:.25rem;padding:.25rem;border-radius:.625rem;
    background:var(--color-neutral-solid-gray-100);width:fit-content;margin-bottom:1rem}
  .view-switcher button{border:0;border-radius:.375rem;padding:.5rem .875rem;background:transparent;cursor:pointer}
  .view-switcher button[aria-pressed='true']{background:var(--color-neutral-white);
    color:var(--color-key-900);font-weight:700;box-shadow:0 1px 3px rgba(0,0,0,.12)}
  .integration-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}
  .integration-card{padding:1rem;border-radius:.5rem;background:var(--color-neutral-solid-gray-50)}
  .integration-card h3{margin:0;font-size:1rem}.integration-card p{margin:.35rem 0 0;
    color:var(--color-neutral-solid-gray-600);font-size:.875rem;line-height:1.6}
  .calendar-list .data-list__item{grid-template-columns:7rem minmax(0,1fr)}
  .calendar-time{color:var(--color-key-900);font-weight:700;font-size:.875rem;line-height:1.5}
  .drive-create-bar{display:flex;align-items:center;flex-wrap:wrap;gap:.5rem .75rem;
    margin:1rem 0 0}
  .drive-create{display:flex;flex-wrap:wrap;gap:.5rem}
  .drive-folders{display:flex;align-items:center;flex-wrap:wrap;gap:.375rem .5rem;
    margin:.5rem 0}
  .drive-crumb{display:flex;align-items:center;flex-wrap:wrap;gap:.25rem}
  .drive-crumb__sep{color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .drive-folder{display:inline-flex;align-items:center;gap:.375rem}
  /* The one place a folder is not a document: it is a destination, so it
     reads as a chip rather than as a row in the list below. */
  .drive-folder--chip{border-radius:999px;padding:.25rem .75rem}
  .drive-create__status{margin:0;color:var(--color-neutral-solid-gray-600);
    font-size:.8125rem;line-height:1.5}
  .drive-trash{margin-top:1.5rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;background:var(--color-neutral-white);padding:1rem 1.25rem}
  .drive-trash__head{display:flex;align-items:center;flex-wrap:wrap;gap:.75rem}
  .drive-trash__head h2{margin:0}
  .drive-trash__list{list-style:none;margin:.75rem 0 0;padding:0;display:grid;gap:.5rem}
  .trash-row{display:flex;align-items:center;flex-wrap:wrap;gap:.5rem .75rem;
    border-top:1px solid var(--color-neutral-solid-gray-200);padding-top:.5rem}
  .trash-row__name{flex:1 1 12rem;min-width:0;overflow:hidden;text-overflow:ellipsis;
    white-space:nowrap}
  .trash-row__size{color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .surface-pane{display:grid;gap:.75rem}
  .surface-editor{display:grid;gap:.75rem}
  .surface-list{display:grid;gap:.5rem}
  .surface-row{display:flex;flex-wrap:wrap;align-items:flex-end;gap:.5rem .75rem;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;padding:.75rem}
  .surface-field{display:grid;gap:.25rem}
  .surface-field__label{color:var(--color-neutral-solid-gray-600);font-size:.75rem}
  .surface-input{width:min(100%,11rem);min-height:2.25rem;padding:.35rem .75rem;
    border-radius:.5rem;font-size:.875rem}
  .surface-input--wide{width:min(100%,22rem)}
  .surface-check{width:1.25rem;height:1.25rem;margin:.5rem 0}
  .surface-note{color:var(--color-neutral-solid-gray-600);font-size:.8125rem;
    align-self:center}
  /* A column, not a row: `align-self:center` on .surface-note is for the
     flex rows it usually sits in, and left alone here every line would be
     centred against the widest one. */
  /* Bounded rather than natural size: an uploaded photograph is often
     several thousand pixels wide, and a detail pane that grows to fit one
     pushes everything else off the screen. */
  .file-preview{margin:.5rem 0;max-width:100%}
  .file-preview__image{display:block;max-width:100%;max-height:24rem;
    width:auto;height:auto;border-radius:.5rem}
  .export-notes{display:flex;flex-direction:column;gap:.25rem;margin-top:.25rem}
  .export-notes .surface-note{align-self:flex-start}
  .export-notes__list{margin:0;padding-left:1.25rem;display:flex;
    flex-direction:column;gap:.125rem}
  .surface-grid{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
  .surface-grid th{color:var(--color-neutral-solid-gray-600);font-size:.75rem;
    font-weight:400;padding:.25rem}
  .surface-grid td{padding:1px}
  .surface-cell{width:8rem;min-height:2rem;box-sizing:border-box;padding:.25rem .5rem;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.25rem;
    background:var(--color-neutral-white);font:inherit;font-size:.8125rem}
  .surface-cell:focus{outline:4px solid var(--color-primitive-yellow-300);outline-offset:-1px;
    border-color:var(--color-key-600)}
  /* ── one surface per kind ─────────────────────────────────────────────
     A form should look like a form and a deck like a deck. The fields above
     edit the same value; these render it as the artifact it is, which is the
     view that answers what was just created without reading JSON. */
  .surface-modes{display:flex;gap:.25rem;padding:.25rem;width:fit-content;
    border-radius:.625rem;background:var(--color-neutral-solid-gray-100)}
  .surface-modes button{min-height:2.25rem;border:0;border-radius:.375rem;
    background:transparent;padding:.35rem .875rem;
    color:var(--color-neutral-solid-gray-700);font-size:.8125rem;cursor:pointer}
  .surface-modes button[aria-pressed='true']{background:var(--color-neutral-white);
    color:var(--color-key-900);font-weight:700;box-shadow:0 1px 3px rgba(0,0,0,.12)}
  .surface-modes button:focus-visible{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px}
  .surface-preview{padding:1.25rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;background:var(--color-neutral-solid-gray-100);overflow:auto}
  .doc-page{max-width:40rem;margin:0 auto;box-sizing:border-box;
    padding:2.5rem clamp(1.25rem,4vw,3rem);background:var(--color-neutral-white);
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.25rem;
    box-shadow:0 1px 3px rgba(0,0,0,.08);color:var(--color-neutral-solid-gray-900);
    line-height:1.9}
  .doc-page__title{margin:0 0 1.5rem;padding-bottom:.75rem;font-size:1.75rem;
    line-height:1.45;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .doc-page h2,.doc-page h3,.doc-page h4,.doc-page h5,.doc-page h6{
    margin:1.75rem 0 .5rem;line-height:1.5}
  .doc-page h2{font-size:1.5rem}.doc-page h3{font-size:1.25rem}
  .doc-page h4{font-size:1.0625rem}.doc-page h5,.doc-page h6{font-size:1rem}
  .doc-page>*:first-child{margin-top:0}
  .doc-page p{margin:0 0 1rem;overflow-wrap:anywhere}
  .doc-page blockquote{margin:0 0 1rem;padding:.25rem 0 .25rem 1rem;
    border-left:4px solid var(--color-key-600);color:var(--color-neutral-solid-gray-700)}
  .doc-page pre{margin:0 0 1rem;padding:.875rem 1rem;overflow-x:auto;border-radius:.5rem;
    background:var(--color-neutral-solid-gray-900);color:var(--color-neutral-white);
    font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.8125rem;line-height:1.7}
  .doc-page ul{margin:0 0 1rem;padding-left:1.5rem}
  .doc-page li{margin:.25rem 0}
  .doc-table{border-collapse:collapse;width:100%;margin:0 0 1rem;font-size:.9375rem}
  .doc-table th,.doc-table td{padding:.5rem .625rem;text-align:left;vertical-align:top;
    border:1px solid var(--color-neutral-solid-gray-300)}
  .doc-table th{background:var(--color-neutral-solid-gray-50);font-weight:700}
  .doc-ref{display:inline-flex;align-items:center;gap:.375rem;padding:.375rem .75rem;
    border-radius:999px;background:var(--color-key-50);color:var(--color-key-900);
    font-size:.8125rem;font-weight:700}
  .doc-ref--dangling{background:var(--color-primitive-yellow-200);
    color:var(--color-neutral-black)}
  .doc-aside{margin:2rem 0 0;padding-top:1rem;
    border-top:1px solid var(--color-neutral-solid-gray-200)}
  .doc-aside h2{margin:0 0 .5rem;font-size:.875rem;font-weight:700;
    color:var(--color-neutral-solid-gray-600)}
  .doc-aside p{margin:0 0 .5rem;color:var(--color-neutral-solid-gray-700);
    font-size:.8125rem;line-height:1.7}
  .doc-run--bold{font-weight:700}.doc-run--italic{font-style:italic}
  .doc-run--underline{text-decoration:underline}
  .doc-run--strike{text-decoration:line-through}
  .doc-run--code{padding:.05rem .3rem;border-radius:.25rem;
    background:var(--color-neutral-solid-gray-100);
    font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.9em}
  .form-paper{max-width:40rem;margin:0 auto;display:grid;gap:.75rem}
  .form-paper__head{padding:1.5rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-top:.5rem solid var(--color-key-900);border-radius:.5rem;
    background:var(--color-neutral-white)}
  .form-paper__title{margin:0;font-size:1.5rem;line-height:1.5;overflow-wrap:anywhere}
  .form-paper__lead{margin:.5rem 0 0;color:var(--color-neutral-solid-gray-600);
    font-size:.875rem;line-height:1.7}
  .form-card{display:grid;gap:.5rem;padding:1.25rem;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;
    background:var(--color-neutral-white)}
  .form-card__label{margin:0;font-weight:700;line-height:1.6;overflow-wrap:anywhere}
  .form-card__required{margin-left:.25rem;color:var(--color-semantic-error-2)}
  .form-card__type{color:var(--color-neutral-solid-gray-500);font-size:.75rem}
  .form-control{box-sizing:border-box;width:100%;min-height:2.75rem;border:0;
    border-bottom:1px solid var(--color-neutral-solid-gray-300);padding:.5rem 0;
    background:transparent;color:var(--color-neutral-solid-gray-600);font:inherit}
  .form-control--area{min-height:5rem;padding:.5rem .75rem;resize:vertical;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem}
  .form-control--check{display:flex;align-items:center;gap:.5rem;min-height:2.75rem;
    color:var(--color-neutral-solid-gray-600);font-size:.875rem}
  .sheet-paper{border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;
    background:var(--color-neutral-white);overflow:hidden}
  .sheet-tabs{display:flex;gap:.25rem;padding:.5rem .5rem 0;overflow-x:auto;
    background:var(--color-neutral-solid-gray-50);
    border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .sheet-tab{min-height:2.25rem;border:1px solid transparent;border-bottom:0;
    border-radius:.375rem .375rem 0 0;background:transparent;padding:.35rem .875rem;
    color:var(--color-neutral-solid-gray-700);font-size:.8125rem;white-space:nowrap;
    cursor:pointer}
  .sheet-tab[aria-pressed='true']{background:var(--color-neutral-white);
    border-color:var(--color-neutral-solid-gray-200);color:var(--color-key-900);
    font-weight:700}
  .sheet-tab:focus-visible{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:-2px}
  .sheet-scroll{max-height:26rem;overflow:auto}
  .sheet-table{border-collapse:separate;border-spacing:0;font-size:.8125rem;
    font-variant-numeric:tabular-nums}
  .sheet-table th{position:sticky;top:0;z-index:2;min-width:6rem;padding:.3rem .5rem;
    background:var(--color-neutral-solid-gray-100);
    color:var(--color-neutral-solid-gray-600);font-size:.6875rem;font-weight:400;
    border-right:1px solid var(--color-neutral-solid-gray-200);
    border-bottom:1px solid var(--color-neutral-solid-gray-300)}
  .sheet-table th.sheet-corner{left:0;z-index:3;min-width:2.75rem}
  .sheet-table th.sheet-rownum{position:sticky;top:auto;left:0;z-index:1;
    min-width:2.75rem;text-align:center}
  .sheet-table td{padding:.3rem .5rem;background:var(--color-neutral-white);
    white-space:nowrap;border-right:1px solid var(--color-neutral-solid-gray-200);
    border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .sheet-cell--num{text-align:right}
  .sheet-cell--head{background:var(--color-neutral-solid-gray-50);font-weight:700}
  .sheet-cell--formula{color:var(--color-key-900);
    font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
  .deck-stage{display:grid;gap:.75rem}
  /* Inches on a 10 × 5.625in stage, as percentages, so one builder makes both
     the stage and the thumbnail. `container-type` is what lets a point-sized
     font survive the scale change: 1pt is 1/72in and 1in is 10cqw. */
  .deck-canvas,.deck-thumb__frame{position:relative;width:100%;aspect-ratio:16/9;
    container-type:inline-size;overflow:hidden;background:var(--color-neutral-white)}
  .deck-canvas{border:1px solid var(--color-neutral-solid-gray-300);border-radius:.375rem;
    box-shadow:0 2px 8px rgba(0,0,0,.1)}
  .deck-shape{position:absolute;box-sizing:border-box;overflow:hidden}
  .deck-shape--text{display:flex;align-items:flex-start;line-height:1.35;
    color:var(--color-neutral-solid-gray-900);white-space:pre-wrap;overflow-wrap:anywhere}
  .deck-shape--rect{border:1px solid transparent}
  .deck-shape--placeholder{display:grid;place-items:center;text-align:center;
    border:1px dashed var(--color-neutral-solid-gray-400);
    color:var(--color-neutral-solid-gray-500);font-size:3cqw;line-height:1.4}
  .deck-empty{position:absolute;inset:0;display:grid;place-items:center;
    color:var(--color-neutral-solid-gray-500);font-size:3cqw}
  .deck-film{display:flex;gap:.5rem;padding:.25rem 0;overflow-x:auto}
  .deck-thumb{flex:0 0 9rem;border:2px solid transparent;border-radius:.375rem;
    background:transparent;padding:0;text-align:left;cursor:pointer}
  .deck-thumb[aria-pressed='true']{border-color:var(--color-key-900)}
  .deck-thumb:focus-visible{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px}
  .deck-thumb__frame{border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:.25rem}
  .deck-thumb__label{display:block;overflow:hidden;padding:.25rem .125rem 0;
    color:var(--color-neutral-solid-gray-600);font-size:.6875rem;
    text-overflow:ellipsis;white-space:nowrap}
  .deck-caption{margin:0;color:var(--color-neutral-solid-gray-600);font-size:.8125rem;
    line-height:1.7}
  .sharing{margin-top:1.25rem;border-top:1px solid var(--color-neutral-solid-gray-200);
    padding-top:1rem;display:grid;gap:.75rem}
  .sharing__title{margin:0;font-size:1rem}
  .sharing__list{list-style:none;margin:0;padding:0;display:grid;gap:.5rem}
  .sharing__entry{display:flex;align-items:center;flex-wrap:wrap;gap:.5rem}
  .sharing__entry--reply{padding-left:1.25rem;
    border-left:2px solid var(--color-neutral-solid-gray-200)}
  .sharing__thread{display:grid;gap:.5rem;padding:.5rem 0;
    border-top:1px solid var(--color-neutral-solid-gray-200)}
  .sharing__thread.is-resolved{opacity:.6}
  .sharing__who{color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .sharing__token{flex:1 1 16rem;min-width:0;min-height:2.25rem;padding:.35rem .75rem;
    border-radius:.5rem;font-size:.75rem}
  .detail-actions{display:flex;flex-direction:column;align-items:stretch;gap:.75rem;
    margin-top:1.25rem}
  .detail-actions__row{display:flex;flex-wrap:wrap;align-items:center;gap:.5rem}
  .document-title{flex:1 1 12rem;min-width:0;min-height:2.25rem;padding:.35rem .75rem;
    border-radius:.5rem;font-size:.875rem}
  .document-preview{height:18rem;width:100%;box-sizing:border-box;overflow:auto;margin:0;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;
    background:var(--color-neutral-solid-gray-50);padding:.75rem;
    color:var(--color-neutral-solid-gray-800);font-size:.75rem;line-height:1.6;
    font-family:ui-monospace,SFMono-Regular,Menlo,monospace;
    white-space:pre;resize:vertical}
  .document-preview:focus{outline:4px solid var(--color-primitive-yellow-300);outline-offset:1px;
    border-color:var(--color-key-600)}
  .workspace-toolbar{display:flex;align-items:center;justify-content:space-between;gap:.75rem;
    margin:1rem 0}
  .workspace-search{width:min(100%,24rem);min-height:2.75rem;box-sizing:border-box;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;
    padding:.625rem .75rem;background:var(--color-neutral-white);font:inherit}
  .workspace-search:focus{outline:4px solid var(--color-primitive-yellow-300);outline-offset:1px;
    border-color:var(--color-key-600)}
  .record-browser{display:grid;grid-template-columns:minmax(17rem,.8fr) minmax(20rem,1.2fr);
    min-height:30rem;border:1px solid var(--color-neutral-solid-gray-200);border-radius:.75rem;
    overflow:hidden;background:var(--color-neutral-white)}
  .record-list{min-width:0;border-right:1px solid var(--color-neutral-solid-gray-200);
    background:var(--color-neutral-solid-gray-50)}
  .record-list__items{list-style:none;margin:0;padding:0;max-height:38rem;overflow:auto}
  .record-button{display:block;width:100%;border:0;border-bottom:1px solid var(--color-neutral-solid-gray-200);
    background:transparent;padding:1rem;text-align:left;cursor:pointer;color:inherit}
  .record-button:hover{background:var(--color-neutral-white)}
  .record-button[aria-pressed='true']{background:var(--color-key-50);
    box-shadow:inset 4px 0 0 var(--color-key-900)}
  .record-button:focus-visible,.date-button:focus-visible{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:-4px}
  .record-button__top{display:flex;justify-content:space-between;gap:.75rem;align-items:baseline}
  .record-button__title{display:block;font-weight:700;overflow:hidden;text-overflow:ellipsis;
    white-space:nowrap}
  .record-button__time{flex:none;color:var(--color-neutral-solid-gray-600);font-size:.75rem}
  .record-button__meta,.record-button__snippet{display:block;margin-top:.25rem;
    color:var(--color-neutral-solid-gray-600);font-size:.8125rem;line-height:1.5;
    overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .record-detail{min-width:0;padding:clamp(1.25rem,3vw,2.25rem)}
  .record-detail__eyebrow{margin:0 0 .5rem;color:var(--color-key-900);
    font-size:.8125rem;font-weight:700}
  .record-detail h2{margin:0;font-size:1.5rem;line-height:1.5;overflow-wrap:anywhere}
  .record-detail__body{margin:1.5rem 0 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}
  .record-detail__meta{margin-top:1.5rem;padding-top:1rem;
    border-top:1px solid var(--color-neutral-solid-gray-200)}
  .date-rail{display:flex;gap:.5rem;overflow-x:auto;padding:.25rem 0 1rem}
  .date-button{flex:0 0 5.5rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.625rem;background:var(--color-neutral-white);padding:.625rem .5rem;
    text-align:center;cursor:pointer}
  .date-button[aria-pressed='true']{border-color:var(--color-key-900);
    background:var(--color-key-50);color:var(--color-key-900);font-weight:700}
  .date-button span{display:block;font-size:.75rem}.date-button strong{font-size:1.25rem}
  .result-count{color:var(--color-neutral-solid-gray-600);font-size:.8125rem;white-space:nowrap}
  .settings-grid{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(18rem,.75fr);
    gap:1.5rem;align-items:start}
  .settings-stack{display:grid;gap:1rem}
  .identity-summary{display:flex;align-items:center;justify-content:space-between;gap:1rem;
    padding:1.25rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;background:var(--color-neutral-white)}
  .identity-summary__avatar{display:grid;place-items:center;flex:none;width:3rem;height:3rem;
    border-radius:1rem;background:var(--color-key-50);color:var(--color-key-900);
    font-size:1rem;font-weight:700}
  .identity-summary__copy{min-width:0;flex:1}.identity-summary__copy p{margin:.15rem 0}
  .identity-summary__name{font-weight:700}.identity-summary__meta{
    color:var(--color-neutral-solid-gray-600);font-size:.875rem}
  .connector-list{display:grid;gap:.75rem;margin-top:1rem}
  .connector-card{display:grid;grid-template-columns:2.75rem minmax(0,1fr) auto;
    gap:1rem;align-items:center;padding:1rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.625rem}
  .connector-logo{display:grid;place-items:center;width:2.75rem;height:2.75rem;
    border-radius:.75rem;background:var(--color-neutral-solid-gray-100);font-weight:700}
  .connector-card h3{margin:0;font-size:1rem}.connector-card p{margin:.25rem 0 0;
    color:var(--color-neutral-solid-gray-600);font-size:.8125rem;line-height:1.55}
  .connector-card .tool-button{white-space:nowrap}
  .connector-card .tool-button:disabled{opacity:.55;cursor:not-allowed}
  .settings-form{display:grid;gap:1rem}.form-grid{display:grid;
    grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}
  .field{display:grid;gap:.375rem}.field label{font-weight:700;font-size:.875rem}
  .field input,.field select{min-height:2.75rem;box-sizing:border-box;width:100%;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;
    padding:.625rem .75rem;background:var(--color-neutral-white);font:inherit}
  .field input:focus,.field select:focus{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px;border-color:var(--color-key-600)}
  .sidebar__organization{padding:0 .5rem}.sidebar__organization select{
    min-height:2.5rem;width:100%;border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:.5rem;background:var(--color-neutral-white);padding:.5rem;font:inherit}
  .organism-activity{max-height:20rem;overflow:auto}
  .organism-activity .data-list__item{grid-template-columns:7rem minmax(0,1fr) 8rem}
  .form-help{margin:0;color:var(--color-neutral-solid-gray-600);
    font-size:.8125rem;line-height:1.6}
  .primary-action{min-height:2.75rem;border:0;border-radius:.5rem;
    background:var(--color-key-900);color:var(--color-neutral-white);
    padding:.625rem 1rem;font-weight:700;cursor:pointer}
  .primary-action:disabled{background:var(--color-neutral-solid-gray-300);cursor:not-allowed}
  .security-callout{padding:1rem;border-left:4px solid var(--color-key-600);
    background:var(--color-key-50);font-size:.875rem;line-height:1.7}
  .settings-notice{margin-bottom:1rem;padding:.75rem 1rem;border-radius:.5rem;
    background:var(--color-primitive-green-50);color:var(--color-semantic-success-2)}
  .settings-notice--error{background:var(--color-primitive-red-50);color:var(--color-semantic-error-1)}
  .member-list{list-style:none;margin:1rem 0 0;padding:0}
  .member-list li{display:flex;justify-content:space-between;gap:1rem;padding:.75rem 0;
    border-top:1px solid var(--color-neutral-solid-gray-200)}
  .member-list strong,.member-list span{display:block}.member-list span{
    color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .state-chip--run{background:var(--color-key-50);color:var(--color-key-900);font-weight:700}
  .state-chip--done{background:var(--color-primitive-green-50);color:var(--color-semantic-success-2)}
  .state-chip--fail{background:var(--color-primitive-red-50);color:var(--color-semantic-error-1)}
  .worker-form{display:grid;grid-template-columns:minmax(0,1fr) minmax(13rem,.5fr);
    gap:1rem;align-items:start}
  .worker-form textarea{min-height:6rem;box-sizing:border-box;width:100%;resize:vertical;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;
    padding:.625rem .75rem;background:var(--color-neutral-white);font:inherit;line-height:1.65}
  .worker-form textarea:focus{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px;border-color:var(--color-key-600)}
  .worker-summary{display:flex;flex-wrap:wrap;gap:.5rem;min-width:0}
  .worker-output{margin:1.25rem 0 0;padding:1rem;max-height:24rem;overflow:auto;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;
    background:var(--color-neutral-solid-gray-50);white-space:pre-wrap;
    overflow-wrap:anywhere;line-height:1.8}
  .worker-actions{display:flex;justify-content:flex-end;margin-top:1rem}
  .skeleton{height:4.5rem;margin:.5rem 0;border-radius:.5rem;
    background:linear-gradient(90deg,var(--color-neutral-solid-gray-50),var(--color-neutral-solid-gray-100),var(--color-neutral-solid-gray-50));
    background-size:200% 100%;animation:pulse 1.4s infinite}
  @keyframes pulse{to{background-position:-200% 0}}
  @media(max-width:64rem){.local-grid,.settings-grid{grid-template-columns:1fr}}
  @media(max-width:56rem){
    .workspace{grid-template-columns:4.75rem minmax(0,1fr)}
    .sidebar{position:sticky;top:0;height:100dvh;padding:.75rem .5rem;overflow:hidden;
      border-right:1px solid var(--color-neutral-solid-gray-200);border-bottom:0;gap:1rem}
    .brand{padding:.25rem 0;text-align:center}
    .brand__eyebrow,.brand__name,.brand__note,.sidebar__status{display:none}
    .sidebar__organization{display:none}
    .brand__mark{display:block;margin:0;color:var(--color-key-900);font-size:1.125rem;
      font-weight:700;line-height:2.5rem}
    .local-nav{width:100%;flex-direction:column;overflow:visible}
    .local-nav__item{width:100%;min-width:0;justify-content:center;padding:.625rem .25rem}
    .local-nav__item[aria-current='page']{border-left:0;border-bottom:4px solid var(--color-key-900);
      padding:.625rem .25rem .375rem}
    .nav-label,.nav-badge{position:absolute;width:1px;height:1px;padding:0;margin:-1px;
      overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
    /* The labels are hidden here, so a group heading over icon-only buttons
       would name a grouping the user cannot see. */
    .local-nav__group{display:none}
    .nav-icon{font-size:1.25rem}
    .topbar{min-height:auto;padding:.75rem 1rem}.topbar__meta{display:none}
    .view{padding:1rem}.view-header{display:block}.view-header .dads-button{margin-top:1rem}
    .chat-view{padding:0}.chat-shell{height:calc(100dvh - 3.75rem)}
    .chat-header{padding:.5rem .75rem}.chat-header .model-pill{display:none}
    .chat-thread{padding:1.25rem 1rem 10.5rem}
    .chat-empty{min-height:calc(100dvh - 15rem)}.chat-empty h1{font-size:1.625rem}
    .suggestion-grid{grid-template-columns:1fr}.suggestion-card:nth-child(n+3){display:none}
    .composer-dock{padding:2rem .5rem .5rem}.composer{border-radius:1rem}
    .message-row--user .message-body{max-width:92%}
    .integration-grid{grid-template-columns:1fr}.calendar-list .data-list__item{grid-template-columns:1fr}
    .record-browser{grid-template-columns:1fr}.record-list{border-right:0;
      border-bottom:1px solid var(--color-neutral-solid-gray-200)}
    .record-list__items{max-height:19rem}.record-detail{min-height:16rem}
    .workspace-toolbar{align-items:stretch}.workspace-search{width:100%}
    .worker-form{grid-template-columns:1fr}.worker-form .primary-action{width:100%}
    .worker-output{max-height:16rem}
    .form-grid{grid-template-columns:1fr}.connector-card{grid-template-columns:2.75rem 1fr}
    .connector-card .tool-button{grid-column:1/-1;width:100%}
    .local-actions{justify-content:stretch}.local-actions .dads-button{width:100%}
  }
  ")

(def interaction-js
  "
  document.addEventListener('DOMContentLoaded', () => {
    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
    const make = (tag, className, text) => {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = text;
      return node;
    };
    const initialParams = new URLSearchParams(location.search);
    const requestedView = location.hash.slice(1) || 'chat';
    let appUnlocked = false;
    let appBootstrapped = false;
    // Views whose data is public, so the Passkey gate would protect nothing.
    // `storage` reads public Filecoin chain state and computes a PieceCID —
    // there is no workspace content in it. Everything else stays gated.
    const publicViews = new Set(['settings', 'storage']);
    let currentView = 'settings';
    // Assigned once the worker surface is defined further down; showView runs
    // before that, so it must not name the worker helpers directly.
    let onViewChange = () => {};
    const formatDate = (value, timeOnly = false) => {
      if (!value) return '日時不明';
      const date = new Date(value);
      if (Number.isNaN(date.valueOf())) return value;
      return new Intl.DateTimeFormat('ja-JP', timeOnly
        ? {hour:'2-digit', minute:'2-digit'}
        : {month:'numeric', day:'numeric', weekday:'short', hour:'2-digit', minute:'2-digit'}
      ).format(date);
    };
    const showView = (name) => {
      if (!appUnlocked && !publicViews.has(name)) name = 'settings';
      $$('.local-nav__item').forEach((item) => item.setAttribute(
        'aria-current', item.dataset.view === name ? 'page' : 'false'));
      $$('.view').forEach((panel) => { panel.hidden = panel.dataset.viewPanel !== name; });
      const active = $(`.local-nav__item[data-view='${name}']`);
      $('#current-view').textContent = active?.dataset.title || 'Chat';
      history.replaceState(null, '', `#${name}`);
      const brand = document.querySelector('.workspace')?.dataset.brand || 'Cloud Itonami';
      document.title = `${active?.dataset.title || 'Chat'} | ${brand}`;
      currentView = name;
      onViewChange(name);
    };
    $$('.local-nav__item').forEach((item) =>
      item.addEventListener('click', () => showView(item.dataset.view)));
    showView('settings');

    const form = $('#chat-form');
    const prompt = $('#prompt');
    const send = $('#send-button');
    const stop = $('#stop-button');
    const status = $('#request-status');
    const thread = $('#chat-thread');
    const empty = $('#chat-empty');
    const scroll = $('#chat-scroll');
    const chatShell = $('#chat-shell');
    const modelSelect = $('#model-select');
    let sessionId = localStorage.getItem('cloud-itonami-session') || 'desktop';
    let currentController = null;
    let lastPrompt = '';
    let generating = false;
    const announce = (message) => {
      status.textContent = message;
      $('#chat-agent-state').textContent = message;
    };
    const scrollToEnd = () => requestAnimationFrame(() => {
      scroll.scrollTop = scroll.scrollHeight;
    });
    const resizePrompt = () => {
      prompt.style.height = 'auto';
      prompt.style.height = `${Math.min(prompt.scrollHeight, 192)}px`;
      send.disabled = !prompt.value.trim() || generating;
    };
    const setGenerating = (value) => {
      generating = value;
      send.hidden = value;
      stop.hidden = !value;
      prompt.disabled = value;
      if (!value) { prompt.disabled = false; resizePrompt(); }
    };
    const addAction = (container, label, handler) => {
      const button = make('button', 'message-action', label);
      button.type = 'button';
      button.addEventListener('click', handler);
      container.append(button);
    };
    const addMessage = (role, content, options = {}) => {
      empty.hidden = true;
      const row = make('article', `message-row message-row--${role}`);
      row.dataset.role = role;
      const body = make('div', 'message-body');
      const text = make('div', 'message-content', content);
      const actions = make('div', 'message-actions');
      if (role === 'assistant') {
        row.append(make('div', 'message-avatar', 'ai'));
        body.append(make('p', 'message-author', options.author || 'Local'), text, actions);
        row.append(body);
      } else {
        body.append(text);
        row.append(body);
      }
      thread.append(row);
      if (content && role === 'assistant') {
        addAction(actions, 'コピー', async () => {
          await navigator.clipboard.writeText(text.textContent);
          announce('応答をコピーしました。');
        });
      }
      scrollToEnd();
      return {row, body, text, actions};
    };
    const addAssistantActions = (message, promptValue) => {
      addAction(message.actions, 'コピー', async () => {
        await navigator.clipboard.writeText(message.text.textContent);
        announce('応答をコピーしました。');
      });
      if (promptValue) addAction(message.actions, 'もう一度', () => {
        prompt.value = promptValue;
        resizePrompt();
        form.requestSubmit();
      });
    };
    const loadSession = async () => {
      try {
        const request = await fetch(`/api/session?session=${encodeURIComponent(sessionId)}`);
        const data = await request.json();
        thread.querySelectorAll('.message-row').forEach((node) => node.remove());
        data.messages.forEach((message) => {
          if (message.role === 'user') lastPrompt = message.content;
          const rendered = addMessage(message.role, message.content,
            {author: message.role === 'assistant' ? 'Local' : 'あなた'});
          if (message.role === 'assistant' && lastPrompt) {
            addAction(rendered.actions, 'もう一度', () => {
              prompt.value = lastPrompt;
              resizePrompt();
              form.requestSubmit();
            });
          }
        });
        empty.hidden = data.messages.length > 0;
        announce(data.messages.length ? '会話を復元しました。' : 'ローカルモデルの準備ができています。');
      } catch (error) {
        announce(`履歴を読み込めませんでした: ${error.message}`);
      }
    };
    const parseStream = async (response, assistant, promptValue) => {
      if (!response.ok || !response.body) throw new Error('応答ストリームを開始できませんでした。');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let received = false;
      while (true) {
        const {value, done} = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
        const lines = buffer.split('\\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          const event = JSON.parse(line);
          if (event.type === 'delta') {
            if (!received) { assistant.text.replaceChildren(); received = true; }
            assistant.text.textContent += event.content;
            announce('応答を生成しています…');
            scrollToEnd();
          } else if (event.type === 'done') {
            addAssistantActions(assistant, promptValue);
            announce(`${event.provider} / ${event.model} から応答しました。`);
          } else if (event.type === 'error') {
            throw new Error(event.message);
          }
        }
        if (done) break;
      }
      if (!received) throw new Error('モデルから空の応答が返されました。');
    };
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const value = prompt.value.trim();
      if (!value || generating) {
        if (!value) announce('メッセージを入力してください。');
        return;
      }
      lastPrompt = value;
      addMessage('user', value);
      prompt.value = '';
      resizePrompt();
      const assistant = addMessage('assistant', '');
      const typing = make('div', 'typing');
      typing.setAttribute('aria-label', '応答を生成中');
      typing.append(make('span'), make('span'), make('span'));
      assistant.text.append(typing);
      currentController = new AbortController();
      setGenerating(true);
      announce('モデルに接続しています…');
      try {
        const request = await fetch('/api/chat/stream', {
          method:'POST', headers:{'Content-Type':'application/json'},
          signal:currentController.signal,
          body:JSON.stringify({prompt:value, session:sessionId, agent:'local',
            model:modelSelect.value})
        });
        await parseStream(request, assistant, value);
      } catch (error) {
        assistant.text.replaceChildren();
        assistant.text.textContent = error.name === 'AbortError'
          ? '生成を停止しました。'
          : `応答を生成できませんでした: ${error.message}`;
        addAction(assistant.actions, '再試行', () => {
          prompt.value = value; resizePrompt(); form.requestSubmit();
        });
        announce(error.name === 'AbortError' ? '生成を停止しました。' : '応答でエラーが発生しました。');
      } finally {
        currentController = null;
        setGenerating(false);
        prompt.focus();
      }
    });
    stop.addEventListener('click', () => currentController?.abort());
    prompt.addEventListener('input', resizePrompt);
    prompt.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault(); form.requestSubmit();
      }
    });
    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && generating) currentController?.abort();
    });
    $$('.suggestion-card').forEach((button) => button.addEventListener('click', () => {
      prompt.value = button.dataset.prompt; resizePrompt(); prompt.focus();
    }));
    $('#new-chat-button').addEventListener('click', () => {
      currentController?.abort();
      sessionId = `chat-${crypto.randomUUID()}`;
      localStorage.setItem('cloud-itonami-session', sessionId);
      chatShell.dataset.session = sessionId;
      thread.querySelectorAll('.message-row').forEach((node) => node.remove());
      empty.hidden = false;
      prompt.value = ''; resizePrompt(); prompt.focus();
      announce('新しいチャットを開始しました。');
    });
    modelSelect.addEventListener('change', () => {
      $('#active-model-label').textContent = `${modelSelect.dataset.provider} / ${modelSelect.value}`;
      announce(`${modelSelect.value} を選択しました。`);
    });
    fetch('/v1/models').then((request) => request.json()).then((data) => {
      const selected = modelSelect.value;
      const models = data.data || [];
      if (models.length) {
        modelSelect.replaceChildren();
        models.forEach((model) => {
          const option = make('option', null, model.id);
          option.value = model.id;
          option.selected = model.id === selected;
          modelSelect.append(option);
        });
        const workerModel = $('#worker-model');
        const workerSelected = workerModel.value;
        workerModel.replaceChildren();
        const inherited = make('option', null, '既定のモデル');
        inherited.value = '';
        workerModel.append(inherited);
        models.forEach((model) => {
          const option = make('option', null, model.id);
          option.value = model.id;
          option.selected = model.id === workerSelected;
          workerModel.append(option);
        });
      }
    }).catch(() => {});
    chatShell.dataset.session = sessionId;
    resizePrompt();

    const listItem = (title, meta, side, warn = false) => {
      const item = make('li', 'data-list__item');
      const copy = make('div');
      copy.append(make('p', 'data-list__title', title), make('p', 'data-list__meta', meta));
      const chip = make('span', `state-chip${warn ? ' state-chip--warn' : ''}`, side);
      item.append(copy, chip);
      return item;
    };
    const bytes = (value) => {
      if (!Number.isFinite(value)) return 'サイズ不明';
      if (value < 1024) return `${value} B`;
      if (value < 1048576) return `${(value / 1024).toFixed(1)} KB`;
      return `${(value / 1048576).toFixed(1)} MB`;
    };
    const setDetail = (target, eyebrow, title, body, metadata) => {
      target.replaceChildren();
      target.append(make('p', 'record-detail__eyebrow', eyebrow),
        make('h2', null, title), make('p', 'record-detail__body', body));
      const meta = make('dl', 'local-meta record-detail__meta');
      metadata.forEach(([label, value]) => {
        meta.append(make('dt', null, label), make('dd', null, value || '—'));
      });
      target.append(meta);
    };
    const recordButton = (item, selected, onSelect, fields) => {
      const row = make('li');
      const button = make('button', 'record-button');
      button.type = 'button';
      button.setAttribute('aria-pressed', selected ? 'true' : 'false');
      const top = make('span', 'record-button__top');
      top.append(make('span', 'record-button__title', fields.title),
        make('span', 'record-button__time', fields.time));
      button.append(top, make('span', 'record-button__meta', fields.meta));
      if (fields.snippet) button.append(make('span', 'record-button__snippet', fields.snippet));
      button.addEventListener('click', () => onSelect(item));
      row.append(button);
      return row;
    };
    let inboxData = {items:[]};
    let selectedInbox = null;
    const renderInbox = (data) => {
      inboxData = data;
      const query = ($('#inbox-search').value || '').trim().toLocaleLowerCase('ja');
      const items = data.items.filter((item) =>
        [item.subject, item.from, item['from-email'], item.snippet]
          .some((value) => String(value || '').toLocaleLowerCase('ja').includes(query)));
      if (!items.some((item) => item.id === selectedInbox?.id)) selectedInbox = items[0] || null;
      const list = $('#inbox-list'); list.replaceChildren();
      const select = (item) => { selectedInbox = item; renderInbox(inboxData); };
      items.forEach((item) => list.append(recordButton(item, item.id === selectedInbox?.id, select, {
        title:item.subject, time:item['received-at'] || '日時不明',
        meta:item.from || item['from-email'], snippet:item.snippet || '本文プレビューなし'})));
      if (!items.length) list.append(make('li', 'empty-state', '条件に一致するメールはありません。'));
      if (selectedInbox) setDetail($('#inbox-detail'), selectedInbox.from,
        selectedInbox.subject, selectedInbox.snippet || '本文は安全なプレビューを作成できませんでした。',
        [['差出人', selectedInbox['from-email']], ['受信', selectedInbox['received-at']],
         ['保管状態', selectedInbox['available?'] ? '本文あり' : '暗号化・封印済み'],
         ['サイズ', bytes(selectedInbox['size-bytes'])]]);
      else $('#inbox-detail').replaceChildren(make('div', 'empty-state', 'メールを選択してください。'));
      $('#inbox-visible-count').textContent = `${items.length} 件を表示`;
      $('#inbox-count').textContent = data.count;
      $('#inbox-source').textContent = `${data.source} · ${data.count} 件`;
    };
    let driveData = {items:[]};
    let selectedDrive = null;
    // Where in the tree the list is looking. Null is the root, which is
    // where everything was before folders existed and where a first visit
    // starts.
    let driveFolder = null;
    let folderData = {folders:[], path:[], all:[]};
    const loadFolders = async () => {
      const url = '/api/workspace/drive/folders'
        + (driveFolder ? `?folder=${encodeURIComponent(driveFolder)}` : '');
      const request = await fetch(url);
      const data = await request.json();
      // A folder that is gone — purged by another session, or trashed —
      // puts the listing back at the root rather than leaving it pointing
      // at nothing.
      if (!request.ok || data.error) { driveFolder = null; return; }
      folderData = data;
    };
    const goToFolder = async (id) => {
      driveFolder = id;
      await loadFolders();
      renderDrive(driveData);
    };
    const renderFolders = (query) => {
      const nav = $('#drive-folders'); if (!nav) return;
      nav.replaceChildren();
      if (query) {
        // Searching looks everywhere, so a breadcrumb saying where you are
        // standing would be describing a place the results are not from.
        nav.append(make('span', 'surface-note', '検索中はすべてのフォルダから探します。'));
        return;
      }
      const crumb = make('div', 'drive-crumb');
      (folderData.path || []).forEach((step, index) => {
        if (index) crumb.append(make('span', 'drive-crumb__sep', '›'));
        const button = make('button', 'tool-button drive-folder--chip', step.name);
        button.type = 'button';
        button.disabled = index === (folderData.path || []).length - 1;
        button.addEventListener('click', () => goToFolder(index === 0 ? null : step.id));
        crumb.append(button);
      });
      nav.append(crumb);
      if (folderData.owner && folderData.owner !== folderData.you) {
        nav.append(make('span', 'surface-note',
          `${folderData.owner} のドライブです。ここで作成したものはその人のドライブに入ります。`));
      }
      const openable = (folder, shared) => {
        const button = make('button', 'tool-button drive-folder',
          `${shared ? '共有 · ' : ''}${folder.name}（${folder.count}）`);
        button.type = 'button';
        button.addEventListener('click', () => goToFolder(folder.id));
        return button;
      };
      (folderData.folders || []).forEach((folder) => nav.append(openable(folder, false)));
      // Folders from another Drive, at the top level only: they are not
      // inside anything here, so there is nowhere else they could appear.
      // Marked, because creating in one puts the document in somebody
      // else's Drive and against their quota.
      (folderData.shared || []).forEach((folder) => nav.append(openable(folder, true)));
      const upload = $('#drive-upload');
      if (upload && !upload.dataset.wired) {
        upload.dataset.wired = 'true';
        upload.addEventListener('change', uploadFile);
      }
      const add = make('button', 'tool-button', 'フォルダを作成');
      add.type = 'button';
      add.addEventListener('click', createFolder);
      nav.append(add);
    };
    const uploadFile = async () => {
      const input = $('#drive-upload');
      const file = input?.files?.[0];
      if (!file) return;
      const status = $('#drive-create-status');
      status.textContent = `${file.name} をアップロードしています…`;
      try {
        // The body is the file and the name is in the query — the same
        // shape import uses, and no multipart parser for a boundary string.
        const query = new URLSearchParams({filename:file.name,
                                           'media-type':file.type || ''});
        if (driveFolder) query.set('folder', driveFolder);
        const response = await fetch(`/api/workspace/drive/upload?${query}`, {
          method:'POST',
          // The same header every other authenticated write sends —
          // `identityHeaders` names it, and inventing a second spelling
          // here would be a request the server rejects for a reason nobody
          // would look for in this function.
          headers:{...identityHeaders(), 'Content-Type':'application/octet-stream'},
          body:await file.arrayBuffer()});
        const data = await response.json();
        if (!response.ok) throw new Error(data?.error?.message || 'アップロードできませんでした。');
        status.textContent = `${data.item.name} をアップロードしました。`;
        input.value = '';
        await loadFolders();
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const createFolder = async () => {
      const status = $('#drive-create-status');
      const name = ($('#drive-folder-name')?.value || '').trim();
      status.textContent = 'フォルダを作成しています…';
      try {
        const made = await postJSON('/api/workspace/drive/folders',
          {title:name || '無題のフォルダ', folder:driveFolder}, true);
        status.textContent = `${made.item.name} を作成しました。`;
        // Cleared, or the next folder silently gets the same name.
        if ($('#drive-folder-name')) $('#drive-folder-name').value = '';
        await loadFolders();
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    const renderDrive = (data) => {
      driveData = data;
      const query = ($('#drive-search').value || '').trim().toLocaleLowerCase('ja');
      const matches = data.items.filter((item) =>
        [item.name, item.folder, item['media-type']]
          .some((value) => String(value || '').toLocaleLowerCase('ja').includes(query)));
      // Searching looks everywhere. A search scoped to the folder you happen
      // to be standing in is a search that cannot find what you are looking
      // for, which is the only reason to search. Shared items are not in
      // this tree at all — they have no reachable parent here — so they stay
      // visible at the root rather than disappearing into a folder nobody
      // can open.
      const here = folderData.folder || null;
      const items = query ? matches : matches.filter((item) => {
        // A document shared from somebody else's Drive sits in their tree,
        // so it has no folder in this one. It belongs at the top rather
        // than nowhere.
        if (!item['parent-id']) return !driveFolder;
        return item['parent-id'] === here;
      });
      if (!items.some((item) => item.id === selectedDrive?.id)) selectedDrive = items[0] || null;
      const list = $('#drive-list'); list.replaceChildren();
      const select = (item) => { selectedDrive = item; renderDrive(driveData); };
      items.forEach((item) => list.append(recordButton(item, item.id === selectedDrive?.id, select, {
        title:item.name, time:bytes(item['size-bytes']),
        meta:item.origin === 'workspace' ? `${item.label} · ${item.folder}` : item.folder,
        snippet:item['media-type']})));
      if (!items.length) {
        list.append(make('li', 'empty-state',
          query ? '条件に一致するファイルはありません。'
                : 'このフォルダにはまだ何もありません。'));
      }
      renderFolders(query);
      if (selectedDrive && selectedDrive.origin === 'workspace') {
        setDetail($('#drive-detail'), selectedDrive.label,
          selectedDrive.name, selectedDrive['trashed?']
            ? 'ゴミ箱にあります。復元するまで編集できません。'
            : 'この Drive で作成した Kotoba ドキュメントです。',
          [['種類', selectedDrive['resource-kind']],
           ['権限', selectedDrive['own?'] ? '所有者'
             : `${selectedDrive.role || '—'}（${selectedDrive.owner || '不明'} から共有）`],
           ['形式', selectedDrive['media-type']],
           ['サイズ', bytes(selectedDrive['size-bytes'])],
           ['全版の合計', bytes(selectedDrive['held-bytes'])],
           ['版数', String(selectedDrive.versions ?? 1)],
           ['作成', selectedDrive['created-at'] || '—'],
           ['最終更新', selectedDrive['updated-at'] || '—'],
           ['最終更新者', selectedDrive['updated-by'] || '—']]);
        $('#drive-detail').append(documentActions(selectedDrive));
      } else if (selectedDrive) {
        setDetail($('#drive-detail'), selectedDrive.folder,
          selectedDrive.name, 'OneDrive アーカイブに保存されているファイルです。',
          [['種類', selectedDrive['media-type']], ['サイズ', bytes(selectedDrive['size-bytes'])],
           ['保管状態', selectedDrive['available?'] ? '利用可能' : 'アーカイブ参照'],
           ['相対位置', selectedDrive.id]]);
      } else {
        $('#drive-detail').replaceChildren(make('div', 'empty-state', 'ファイルを選択してください。'));
      }
      // Only the first response carries the cursor; a later append keeps
      // whatever the last page said.
      if (data['next-cursor'] !== undefined) driveCursor = data['next-cursor'];
      const more = $('#drive-more');
      if (more) {
        more.hidden = !driveCursor;
        more.textContent = driveCursor ? 'さらに読み込む' : '';
      }
      renderDriveCreateBar(data.kinds || []);
      renderDriveTrash(data.trash || []);
      const quota = data.quota;
      $('#drive-quota').textContent = quota
        ? `${bytes(quota['used-bytes'])} / ${bytes(quota['quota-bytes'])} を使用`
        : '';
      $('#drive-visible-count').textContent = `${items.length} 件を表示`;
      $('#drive-count').textContent = data.count || data.items.length;
      $('#drive-source').textContent = data.source;
    };
    // Trashing is only honest if untrashing exists, and the quota only comes
    // back when something is purged — so the trash is on the page rather than
    // behind a route nobody visits.
    const renderDriveTrash = (trash) => {
      const section = $('#drive-trash'); if (!section) return;
      section.hidden = !trash.length;
      const list = $('#drive-trash-list'); list.replaceChildren();
      $('#drive-trash-count').textContent = `${trash.length} 件`;
      trash.forEach((item) => {
        const row = make('li', 'trash-row');
        row.append(make('span', 'trash-row__name', `${item.name}（${item.label}）`),
          make('span', 'trash-row__size', bytes(item['held-bytes'])));
        const restore = make('button', 'tool-button', '復元');
        restore.type = 'button';
        restore.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/restore`, {},
          `${item.name} を復元しました。`));
        const purge = make('button', 'tool-button', '完全に削除');
        purge.type = 'button';
        purge.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/purge`, {},
          `${item.name} を削除しました。`));
        row.append(restore, purge);
        list.append(row);
      });
    };
    const driveAction = async (path, body, done) => {
      const status = $('#drive-create-status');
      status.textContent = '実行しています…';
      try {
        const result = await postJSON(path, body, true);
        status.textContent = result['freed-bytes']
          ? `${done}（${bytes(result['freed-bytes'])} を回収）`
          : done;
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    // The create bar is rendered from the server's `kinds`, not from a list
    // written out here: the three surfaces are a closed table in
    // `cloud.itonami.app.documents`, and a second copy in the UI is a second
    // thing to keep in step.
    const renderDriveCreateBar = (kinds) => {
      const bar = $('#drive-create'); if (!bar) return;
      if (bar.dataset.rendered === String(kinds.length) && kinds.length) return;
      bar.replaceChildren();
      kinds.forEach((kind) => {
        const button = make('button', 'tool-button', `${kind.label}を作成`);
        button.type = 'button';
        button.addEventListener('click', () => createDocument(kind));
        bar.append(button);
      });
      bar.dataset.rendered = String(kinds.length);
    };
    const createDocument = async (kind) => {
      const status = $('#drive-create-status');
      status.textContent = `${kind.label}を作成しています…`;
      try {
        const created = await postJSON('/api/workspace/drive/documents',
          {kind:kind.kind, folder:driveFolder}, true);
        status.textContent = `${created.item.name} を作成しました。`;
        selectedDrive = created.item;
        await loadWorkspace('drive', renderDrive);
      } catch (error) {
        status.textContent = error.message;
      }
    };
    // ── structured editors ────────────────────────────────────────────────
    // Two views of one value. Both the fields below and the JSON textarea
    // mutate the projected payload — the same object the versions endpoint
    // accepts — so a save does not care which one produced it, and neither
    // is a parallel format that can drift from the other.
    //
    // These are not app-sheets / app-docs / app-forms, which are separate
    // applications on their own origin. Reaching those would mean widening
    // `connect-src 'self'` in the page CSP, which is a decision about what
    // this app is allowed to talk to and not one to make while adding an
    // editor. What is here needs no such change.
    const field = (label, control) => {
      const wrap = make('label', 'surface-field');
      wrap.append(make('span', 'surface-field__label', label), control);
      return wrap;
    };
    const textInput = (value, onInput, className) => {
      const input = make('input', `workspace-search ${className || 'surface-input'}`);
      input.type = 'text';
      input.value = value ?? '';
      input.addEventListener('input', () => onInput(input.value));
      return input;
    };
    const selectInput = (value, options, onChange) => {
      const select = make('select', 'model-pill');
      (options || []).forEach((name) => {
        const option = make('option', null, name);
        option.value = name;
        select.append(option);
      });
      if (value !== undefined && value !== null) select.value = value;
      select.addEventListener('change', () => onChange(select.value));
      return select;
    };
    const removeButton = (onClick) => {
      const button = make('button', 'tool-button', '削除');
      button.type = 'button';
      button.addEventListener('click', onClick);
      return button;
    };
    const formsEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['forms/title'],
        (value) => { payload['forms/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['forms/fields'] || []).forEach((entry, index) => {
        const row = make('div', 'surface-row');
        row.append(
          field('ID', textInput(entry['forms/id'],
            (value) => { entry['forms/id'] = value; changed(false); })),
          field('ラベル', textInput(entry['forms/label'],
            (value) => { entry['forms/label'] = value; changed(false); })),
          field('種類', selectInput(entry['forms/field-type'], vocabulary,
            (value) => { entry['forms/field-type'] = value; changed(false); })));
        const required = make('input', 'surface-check');
        required.type = 'checkbox';
        required.checked = Boolean(entry['forms/required?']);
        required.addEventListener('change', () => {
          entry['forms/required?'] = required.checked; changed(false);
        });
        row.append(field('必須', required));
        row.append(removeButton(() => {
          payload['forms/fields'].splice(index, 1); changed(true);
        }));
        list.append(row);
      });
      if (!(payload['forms/fields'] || []).length) {
        list.append(make('p', 'empty-state', 'まだ質問がありません。'));
      }
      const add = make('button', 'tool-button', '質問を追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['forms/fields'] = payload['forms/fields'] || [];
        // The shape the model produces, so a field added here is one
        // `forms.validate` recognises rather than one it reports.
        payload['forms/fields'].push({
          'forms/id': `q${payload['forms/fields'].length + 1}`,
          'forms/label': '新しい質問',
          'forms/field-type': (vocabulary && vocabulary.includes('text')) ? 'text' : (vocabulary || ['text'])[0],
          'forms/required?': false
        });
        changed(true);
      });
      root.append(list, add);
      return root;
    };
    // The block kinds that name another document. Kept beside the editor
    // because it is the editor that has to offer a picker for them; the
    // server's `documents/reference-kinds` is what decides whether one
    // resolves.
    const refKinds = ['table-ref', 'file-ref', 'deck-ref'];
    const docsEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['docs/title'],
        (value) => { payload['docs/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['docs/blocks'] || []).forEach((block, index) => {
        const row = make('div', 'surface-row');
        row.append(
          field('ID', textInput(block['docs/id'],
            (value) => { block['docs/id'] = value; changed(false); })),
          field('種類', selectInput(block['docs/kind'], vocabulary,
            (value) => { block['docs/kind'] = value; changed(true); })));
        if (block['docs/kind'] === 'heading') {
          row.append(field('レベル', selectInput(String(block['docs/level'] ?? 1),
            ['1', '2', '3', '4', '5', '6'],
            (value) => { block['docs/level'] = Number(value); changed(false); })));
        }
        if (refKinds.includes(block['docs/kind'])) {
          // A reference names another document by its Drive id, so the field
          // is a picker over the ones this principal can see rather than a
          // box to type an id into. Free text stays: a draft may name
          // something that is about to be shared, which the server reports
          // as a warning rather than refusing.
          const targets = (driveData.items || [])
            .filter((candidate) => candidate.origin === 'workspace'
                                   && candidate.id !== driveEditor.id);
          const picker = selectInput(block['docs/target'],
            [''].concat(targets.map((t) => t.id)),
            (value) => { if (value) { block['docs/target'] = value; changed(true); } });
          // Labelled by name, valued by id — nobody navigates by uuid.
          Array.from(picker.options).forEach((option) => {
            const hit = targets.find((t) => t.id === option.value);
            option.textContent = hit ? `${hit.name}（${hit.label}）` : '選択してください';
          });
          row.append(field('参照先', picker));
          const hit = targets.find((t) => t.id === block['docs/target']);
          row.append(make('span', 'surface-note',
            hit ? `→ ${hit.name}` : `→ ${block['docs/target'] || '未設定'}（解決できません）`));
          row.append(field('ID を直接指定', textInput(block['docs/target'],
            (value) => { block['docs/target'] = value; changed(false); })));
        } else if ('docs/text' in block || block['docs/kind'] === 'heading'
            || block['docs/kind'] === 'paragraph' || block['docs/kind'] === 'quote'
            || block['docs/kind'] === 'code') {
          row.append(field('本文', textInput(block['docs/text'],
            (value) => { block['docs/text'] = value; changed(false); }, 'surface-input--wide')));
        } else {
          // A table or a list. Editing those structurally is a bigger surface
          // than this pane; saying so beats a field that silently edits only
          // part of one.
          row.append(make('span', 'surface-note', 'この種類は JSON で編集してください。'));
        }
        row.append(removeButton(() => {
          payload['docs/blocks'].splice(index, 1); changed(true);
        }));
        list.append(row);
      });
      if (!(payload['docs/blocks'] || []).length) {
        list.append(make('p', 'empty-state', 'まだブロックがありません。'));
      }
      const add = make('button', 'tool-button', '段落を追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['docs/blocks'] = payload['docs/blocks'] || [];
        payload['docs/blocks'].push({
          'docs/id': `b${payload['docs/blocks'].length + 1}`,
          'docs/kind': 'paragraph',
          'docs/text': ''
        });
        changed(true);
      });
      root.append(list, add);
      return root;
    };
    // The string form [1 1] is what `transit.core/write-json` makes of the
    // vector key, and what `sheets.wire/cell-address` parses back. The format
    // is duplicated here because this is JavaScript; the round trip through
    // it is asserted server-side in documents_test.
    const cellKey = (row, col) => `[${row} ${col}]`;
    const sheetsEditor = (payload, _vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      const tabs = payload['sheets/tabs'] || {};
      const tabIds = Object.keys(tabs);
      if (!tabIds.length) {
        root.append(make('p', 'empty-state', 'タブがありません。JSON で編集してください。'));
        return root;
      }
      const current = tabIds.includes(driveEditor.tab) ? driveEditor.tab : tabIds[0];
      driveEditor.tab = current;
      root.append(field('タブ', selectInput(current, tabIds, (value) => {
        driveEditor.tab = value; changed(true);
      })));
      const tab = tabs[current];
      root.append(field('タブ名', textInput(tab['sheets/title'],
        (value) => { tab['sheets/title'] = value; changed(false); })));
      const cells = tab['sheets/cells'] || {};
      // Two beyond whatever is used, so there is always somewhere to type.
      let maxRow = 3; let maxCol = 3;
      Object.keys(cells).forEach((key) => {
        // Doubled backslashes: this JavaScript lives inside a Clojure string,
        // so the reader sees them first.
        const match = /^\\[(-?\\d+) (-?\\d+)\\]$/.exec(key);
        if (match) {
          maxRow = Math.max(maxRow, Number(match[1]) + 2);
          maxCol = Math.max(maxCol, Number(match[2]) + 2);
        }
      });
      const grid = make('table', 'surface-grid');
      const head = make('tr');
      head.append(make('th', null, ''));
      for (let col = 1; col <= maxCol; col += 1) head.append(make('th', null, String(col)));
      grid.append(head);
      for (let row = 1; row <= maxRow; row += 1) {
        const tr = make('tr');
        tr.append(make('th', null, String(row)));
        for (let col = 1; col <= maxCol; col += 1) {
          const td = make('td');
          const cell = cells[cellKey(row, col)] || {};
          const shown = cell['sheets/formula'] !== undefined
            ? `=${cell['sheets/formula']}` : (cell['sheets/value'] ?? '');
          const input = make('input', 'surface-cell');
          input.type = 'text';
          input.value = shown;
          input.setAttribute('aria-label', `${row}行${col}列`);
          input.addEventListener('change', () => {
            tab['sheets/cells'] = tab['sheets/cells'] || {};
            const key = cellKey(row, col);
            const text = input.value;
            if (text === '') delete tab['sheets/cells'][key];
            // A leading = is a formula, which is the convention every
            // spreadsheet uses and the distinction `sheets.model` draws
            // between :sheets/value and :sheets/formula.
            else if (text.startsWith('=')) tab['sheets/cells'][key] = {'sheets/formula': text.slice(1)};
            else tab['sheets/cells'][key] = {'sheets/value': text};
            changed(false);
          });
          td.append(input);
          tr.append(td);
        }
        grid.append(tr);
      }
      root.append(grid);
      return root;
    };
    const slidesEditor = (payload, vocabulary, changed) => {
      const root = make('div', 'surface-editor');
      root.append(field('タイトル', textInput(payload['slides/title'],
        (value) => { payload['slides/title'] = value; changed(false); })));
      const list = make('div', 'surface-list');
      (payload['slides/slides'] || []).forEach((slide, index) => {
        const card = make('div', 'surface-row');
        card.append(
          field('ID', textInput(slide['slides/id'],
            (value) => { slide['slides/id'] = value; changed(false); })),
          field('見出し', textInput(slide['slides/title'],
            (value) => { slide['slides/title'] = value; changed(false); },
            'surface-input--wide')));
        (slide['slides/shapes'] || []).forEach((shape) => {
          if (shape['slides/shape'] === 'text') {
            card.append(field(`テキスト（${shape['slides/id']}）`,
              textInput(shape['slides/text'],
                (value) => { shape['slides/text'] = value; changed(false); },
                'surface-input--wide')));
          } else {
            // A rect, an image, a component. Position, fill and image data
            // are a canvas's job, not this pane's, and half-editing a shape
            // is worse than handing it over.
            card.append(make('span', 'surface-note',
              `${shape['slides/shape'] || '?'}（${shape['slides/id']}）は JSON で編集してください。`));
          }
        });
        const addText = make('button', 'tool-button', 'テキストを追加');
        addText.type = 'button';
        addText.addEventListener('click', () => {
          slide['slides/shapes'] = slide['slides/shapes'] || [];
          // The shape `slides.model/text-box` produces, defaults included —
          // a text box without a box is one the renderer has to guess at.
          slide['slides/shapes'].push({
            'slides/id': `t${slide['slides/shapes'].length + 1}`,
            'slides/shape': 'text', 'slides/text': '',
            'slides/x': 0.8, 'slides/y': 0.8, 'slides/w': 8.4, 'slides/h': 1.0,
            'slides/font-size': 28
          });
          changed(true);
        });
        card.append(addText, removeButton(() => {
          payload['slides/slides'].splice(index, 1); changed(true);
        }));
        list.append(card);
      });
      if (!(payload['slides/slides'] || []).length) {
        list.append(make('p', 'empty-state', 'まだスライドがありません。'));
      }
      const add = make('button', 'tool-button', 'スライドを追加');
      add.type = 'button';
      add.addEventListener('click', () => {
        payload['slides/slides'] = payload['slides/slides'] || [];
        const n = payload['slides/slides'].length + 1;
        payload['slides/slides'].push({
          'slides/id': `slide${n}`, 'slides/title': `スライド ${n}`, 'slides/shapes': []
        });
        changed(true);
      });
      root.append(list, add);
      return root;
    };
    const surfaceEditors = {forms:formsEditor, docs:docsEditor, sheets:sheetsEditor,
                            slides:slidesEditor};
    // ── rendered surfaces ─────────────────────────────────────────────────
    // The editors above are the fields of the value. These are the value as
    // the thing it is: a form as a respondent sees it, a document as a page,
    // a workbook as a grid with A1 addresses, a deck as slides on a stage.
    //
    // Read-only, and the same projected payload the editors mutate — so this
    // is a third view and not a third format. What it is not is a second
    // renderer: `slides.pptx` and the Markdown and CSV writers are on the
    // server, and export is what goes through them. Nothing here is offered
    // as a substitute for what a file will look like.
    //
    // A text input's `type` is the same table `answerPanel` fills a real form
    // from, declared once here because both need it and two copies of it
    // would drift.
    const inputTypes = {email:'email', number:'number', date:'date'};
    // `:docs/style` on a text run is whoever-wrote-it's map, so a class is
    // looked up per truthy key rather than assumed to be one name.
    const docRunClasses = {bold:'doc-run--bold', italic:'doc-run--italic',
                           underline:'doc-run--underline', strike:'doc-run--strike',
                           code:'doc-run--code'};
    const docRunClass = (style) => {
      if (typeof style === 'string') return docRunClasses[style] || null;
      if (!style || typeof style !== 'object') return null;
      const names = Object.keys(style)
        // `{bold: false}` is a run that says it is not bold.
        .filter((key) => style[key])
        .map((key) => docRunClasses[key.replace(/^docs\\//, '')])
        .filter(Boolean);
      return names.length ? names.join(' ') : null;
    };
    // A block's text with its runs applied. Rendered rather than dropped: a
    // run that is stored and never shown is formatting the document claims to
    // have and the page denies. Overlaps are not nested — the model gives a
    // flat list of ranges, and a tree is not derivable from it — so a later
    // run starts where the previous one ended.
    const docText = (block) => {
      const text = String(block['docs/text'] ?? '');
      const runs = (block['docs/text-runs'] || [])
        .filter((run) => run && typeof run === 'object')
        .map((run) => ({from:Number(run['docs/from']), to:Number(run['docs/to']),
                        className:docRunClass(run['docs/style'])}))
        .filter((run) => Number.isFinite(run.from) && Number.isFinite(run.to))
        .map((run) => ({...run, from:Math.max(0, run.from), to:Math.min(text.length, run.to)}))
        .filter((run) => run.to > run.from)
        .sort((a, b) => a.from - b.from);
      if (!runs.length) return [document.createTextNode(text)];
      const nodes = [];
      let at = 0;
      runs.forEach((run) => {
        const from = Math.max(at, run.from);
        if (run.to <= from) return;
        if (from > at) nodes.push(document.createTextNode(text.slice(at, from)));
        nodes.push(make('span', run.className, text.slice(from, run.to)));
        at = run.to;
      });
      if (at < text.length) nodes.push(document.createTextNode(text.slice(at)));
      return nodes;
    };
    const docsPreview = (payload) => {
      const page = make('article', 'doc-page');
      page.append(make('h1', 'doc-page__title',
        payload['docs/title'] || '無題のドキュメント'));
      const blocks = (payload['docs/blocks'] || []).filter((b) => b && typeof b === 'object');
      blocks.forEach((block) => {
        const kind = String(block['docs/kind'] ?? '');
        if (kind === 'heading') {
          // The title is the h1, so a level-1 heading inside the body is an
          // h2 — otherwise the page has two first-level headings and a
          // screen reader is told the document starts twice.
          const level = Math.min(6, Math.max(1, Number(block['docs/level']) || 1));
          const heading = make(`h${Math.min(6, level + 1)}`);
          heading.append(...docText(block));
          page.append(heading);
        } else if (kind === 'quote') {
          const quote = make('blockquote');
          quote.append(...docText(block));
          page.append(quote);
        } else if (kind === 'code') {
          const pre = make('pre');
          pre.append(make('code', null, String(block['docs/text'] ?? '')));
          page.append(pre);
        } else if (kind === 'list') {
          const items = block['docs/items'] || [];
          const list = make('ul');
          items.forEach((entry) => list.append(make('li', null, String(entry ?? ''))));
          if (!items.length) list.append(make('li', null, '（項目なし）'));
          page.append(list);
        } else if (kind === 'table') {
          const rows = (block['docs/rows'] || []).filter(Array.isArray);
          if (!rows.length) {
            page.append(make('p', 'surface-note', '空の表です。'));
          } else {
            const table = make('table', 'doc-table');
            rows.forEach((row, index) => {
              const tr = make('tr');
              // First row as the header: that is what the Markdown writer
              // does with it, so the page and the export agree.
              row.forEach((cell) => tr.append(
                make(index === 0 ? 'th' : 'td', null, String(cell ?? ''))));
              table.append(tr);
            });
            page.append(table);
          }
        } else if (refKinds.includes(kind)) {
          // Resolved against what this principal can see, which is the same
          // list the picker offers and the same question the server answers
          // as a save-time warning.
          const target = block['docs/target'];
          const hit = (driveData.items || []).find((candidate) => candidate.id === target);
          const wrap = make('p');
          wrap.append(make('span', `doc-ref${hit ? '' : ' doc-ref--dangling'}`,
            hit ? `${hit.label}: ${hit.name}`
                : `${kind} → ${target || '未設定'}（解決できません）`));
          page.append(wrap);
        } else {
          const para = make('p');
          para.append(...docText(block));
          page.append(para);
        }
      });
      if (!blocks.length) {
        page.append(make('p', 'surface-note',
          'まだ本文がありません。「フォーム表示」で段落を追加してください。'));
      }
      // Beside the page rather than in the margin: an anchored comment needs
      // to know where its anchor landed, and this render does not lay text
      // out well enough to point at a character.
      const comments = (payload['docs/comments'] || []).filter((c) => c && typeof c === 'object');
      if (comments.length) {
        const aside = make('div', 'doc-aside');
        aside.append(make('h2', null, `コメント ${comments.length} 件`));
        comments.forEach((comment) => aside.append(make('p', null,
          `${comment['docs/author'] || '不明'}: ${comment['docs/text'] ?? ''}`)));
        page.append(aside);
      }
      return page;
    };
    const formsPreview = (payload) => {
      const paper = make('div', 'form-paper');
      const head = make('div', 'form-paper__head');
      head.append(make('h2', 'form-paper__title',
        payload['forms/title'] || '無題のフォーム'));
      head.append(make('p', 'form-paper__lead',
        '回答者に見える形です。実際に送信できるのは下の「このフォームに回答」で、'
        + 'ここのコントロールは形を示すだけです。'));
      paper.append(head);
      const fields = (payload['forms/fields'] || []).filter((f) => f && typeof f === 'object');
      fields.forEach((entry) => {
        const type = String(entry['forms/field-type'] ?? 'text');
        const card = make('div', 'form-card');
        const label = make('p', 'form-card__label',
          String(entry['forms/label'] ?? entry['forms/id'] ?? ''));
        if (entry['forms/required?']) label.append(make('span', 'form-card__required', '*'));
        card.append(label);
        if (type === 'textarea') {
          const area = make('textarea', 'form-control form-control--area');
          area.disabled = true;
          area.placeholder = '長い回答';
          card.append(area);
        } else if (type === 'checkbox') {
          const row = make('span', 'form-control--check');
          const box = make('input', 'surface-check');
          box.type = 'checkbox';
          box.disabled = true;
          row.append(box, make('span', null, 'はい / いいえ'));
          card.append(row);
        } else if (type === 'choice') {
          // `forms.model` gives a choice field no option list, so a deck of
          // options is whatever the document happens to carry. Saying the
          // list is empty beats rendering a select that looks configured.
          const options = (entry['forms/options'] || []).map((option) => String(option));
          const select = make('select', 'form-control');
          select.disabled = true;
          select.append(make('option', null,
            options.length ? '選択してください' : '選択肢が未設定です'));
          options.forEach((option) => select.append(make('option', null, option)));
          card.append(select);
        } else {
          const input = make('input', 'form-control');
          input.type = inputTypes[type] || 'text';
          input.disabled = true;
          input.placeholder = type === 'email' ? 'name@example.com'
            : (type === 'number' ? '0' : (type === 'date' ? '年 / 月 / 日' : '回答を入力'));
          card.append(input);
        }
        card.append(make('span', 'form-card__type',
          `${type}${entry['forms/required?'] ? ' · 必須' : ''}`));
        paper.append(card);
      });
      if (!fields.length) {
        paper.append(make('p', 'empty-state',
          'まだ質問がありません。「フォーム表示」で質問を追加してください。'));
      }
      return paper;
    };
    // A1 rather than 1行1列: the addresses are numeric in `sheets.model` and
    // this is the notation every formula in the sheet is written in.
    const columnName = (col) => {
      let name = '';
      let n = col;
      while (n > 0) {
        name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
        n = Math.floor((n - 1) / 26);
      }
      return name || String(col);
    };
    const sheetsPreview = (payload, _vocabulary, changed) => {
      const paper = make('div', 'sheet-paper');
      const tabs = payload['sheets/tabs'] || {};
      const tabIds = Object.keys(tabs);
      if (!tabIds.length) {
        paper.append(make('p', 'empty-state', 'タブがありません。'));
        return paper;
      }
      const current = tabIds.includes(driveEditor.tab) ? driveEditor.tab : tabIds[0];
      driveEditor.tab = current;
      const bar = make('div', 'sheet-tabs');
      tabIds.forEach((id) => {
        const button = make('button', 'sheet-tab', tabs[id]?.['sheets/title'] || id);
        button.type = 'button';
        button.setAttribute('aria-pressed', id === current ? 'true' : 'false');
        // The same `driveEditor.tab` the editor uses, so switching tab in
        // either view is switching it in both.
        button.addEventListener('click', () => { driveEditor.tab = id; changed(true); });
        bar.append(button);
      });
      paper.append(bar);
      const cells = tabs[current]?.['sheets/cells'] || {};
      // A floor, not just the used extent. A workbook that has just been
      // created has no cells at all, and the honest 1 × 1 answer draws a single
      // empty box that reads as a broken grid rather than as an empty sheet —
      // measured on a real just-created spreadsheet. Five is enough rows and
      // columns to be recognisable as one.
      let maxRow = 5;
      let maxCol = 5;
      Object.keys(cells).forEach((key) => {
        // Doubled backslashes: this JavaScript lives inside a Clojure string.
        const match = /^\\[(-?\\d+) (-?\\d+)\\]$/.exec(key);
        if (match) {
          maxRow = Math.max(maxRow, Number(match[1]));
          maxCol = Math.max(maxCol, Number(match[2]));
        }
      });
      const scroll = make('div', 'sheet-scroll');
      const table = make('table', 'sheet-table');
      const head = make('tr');
      head.append(make('th', 'sheet-corner', ''));
      for (let col = 1; col <= maxCol; col += 1) {
        head.append(make('th', null, columnName(col)));
      }
      table.append(head);
      for (let row = 1; row <= maxRow; row += 1) {
        const tr = make('tr');
        tr.append(make('th', 'sheet-rownum', String(row)));
        for (let col = 1; col <= maxCol; col += 1) {
          const cell = cells[cellKey(row, col)] || {};
          const formula = cell['sheets/formula'];
          const value = cell['sheets/value'];
          const shown = formula !== undefined ? `=${formula}` : (value ?? '');
          // Nothing is computed here. `sheets` has no evaluator on this path,
          // and showing a made-up result would be worse than showing the
          // formula that produces the real one.
          const numeric = formula === undefined && shown !== ''
            && Number.isFinite(Number(shown));
          const td = make('td', [formula !== undefined ? 'sheet-cell--formula' : null,
                                 numeric ? 'sheet-cell--num' : null,
                                 row === 1 ? 'sheet-cell--head' : null]
                                .filter(Boolean).join(' ') || null,
                          String(shown));
          tr.append(td);
        }
        table.append(tr);
      }
      scroll.append(table);
      paper.append(scroll);
      return paper;
    };
    // The stage `slides.pptx` writes: 10 × 5.625in, shapes in inches and font
    // sizes in points. Restated here because this is JavaScript; the writer
    // remains the only thing that produces a .pptx.
    const deckWidthIn = 10;
    const deckHeightIn = 5.625;
    // Only a hex colour, and only base64 for media. Both come out of a stored
    // document, and a style value is a place a document could otherwise ask
    // the page to fetch something.
    const hexColor = (value) => {
      const hex = String(value ?? '').replace(/^#/, '');
      return /^[0-9A-Fa-f]{3,8}$/.test(hex) ? `#${hex}` : null;
    };
    const deckShape = (shape) => {
      const inches = (value) => (Number.isFinite(Number(value)) ? Number(value) : 0);
      const kind = String(shape['slides/shape'] ?? '');
      const node = (() => {
        if (kind === 'text') {
          const text = make('div', 'deck-shape deck-shape--text',
            String(shape['slides/text'] ?? ''));
          // A point is 1/72in and an inch is a tenth of the stage width, so a
          // container query unit is the only one that survives the thumbnail.
          const pt = Number(shape['slides/font-size']) || 18;
          text.style.fontSize = `${(pt / 72 / deckWidthIn) * 100}cqw`;
          const color = hexColor(shape['slides/color']);
          if (color) text.style.color = color;
          if (shape['slides/bold']) text.style.fontWeight = '700';
          if (shape['slides/align'] === 'center') text.style.justifyContent = 'center';
          if (shape['slides/align'] === 'right') text.style.justifyContent = 'flex-end';
          return text;
        }
        if (kind === 'rect') {
          const rect = make('div', 'deck-shape deck-shape--rect');
          const fill = hexColor(shape['slides/fill']);
          const line = hexColor(shape['slides/line']);
          if (fill) rect.style.background = fill;
          if (line) rect.style.borderColor = line;
          return rect;
        }
        // An image is stored as base64 in the deck and would render from a
        // data: URI — which this page's Content-Security-Policy does not
        // allow, and widening `default-src 'none'` is a decision about what
        // the app may load, not one to make while adding a preview. So the
        // frame says what is there and the .pptx export carries the bytes.
        return make('div', 'deck-shape deck-shape--placeholder',
          kind === 'image'
            ? `画像（${shape['slides/id'] ?? ''}）· pptx に出力されます`
            : `${kind || '?'}（${shape['slides/id'] ?? ''}）`);
      })();
      node.style.left = `${(inches(shape['slides/x']) / deckWidthIn) * 100}%`;
      node.style.top = `${(inches(shape['slides/y']) / deckHeightIn) * 100}%`;
      node.style.width = `${(inches(shape['slides/w']) / deckWidthIn) * 100}%`;
      node.style.height = `${(inches(shape['slides/h']) / deckHeightIn) * 100}%`;
      return node;
    };
    const deckSlide = (slide, className) => {
      const canvas = make('div', className);
      const shapes = ((slide && slide['slides/shapes']) || [])
        .filter((shape) => shape && typeof shape === 'object');
      shapes.forEach((shape) => canvas.append(deckShape(shape)));
      if (!shapes.length) canvas.append(make('div', 'deck-empty', '空のスライド'));
      return canvas;
    };
    const slidesPreview = (payload, _vocabulary, changed) => {
      const stage = make('div', 'deck-stage');
      const slides = (payload['slides/slides'] || []).filter((s) => s && typeof s === 'object');
      if (!slides.length) {
        stage.append(make('p', 'empty-state',
          'まだスライドがありません。「フォーム表示」で追加してください。'));
        return stage;
      }
      const index = Math.min(Math.max(Number(driveEditor.slide) || 0, 0), slides.length - 1);
      driveEditor.slide = index;
      const shown = slides[index];
      stage.append(deckSlide(shown, 'deck-canvas'));
      stage.append(make('p', 'deck-caption',
        `${index + 1} / ${slides.length}・${shown['slides/title'] || shown['slides/id'] || ''}`));
      const film = make('div', 'deck-film');
      slides.forEach((slide, n) => {
        const thumb = make('button', 'deck-thumb');
        thumb.type = 'button';
        thumb.setAttribute('aria-pressed', n === index ? 'true' : 'false');
        thumb.setAttribute('aria-label',
          `スライド ${n + 1}: ${slide['slides/title'] || slide['slides/id'] || ''}`);
        thumb.append(deckSlide(slide, 'deck-thumb__frame'),
          make('span', 'deck-thumb__label',
            `${n + 1}. ${slide['slides/title'] || slide['slides/id'] || ''}`));
        thumb.addEventListener('click', () => { driveEditor.slide = n; changed(true); });
        film.append(thumb);
      });
      stage.append(film);
      return stage;
    };
    const surfacePreviews = {forms:formsPreview, docs:docsPreview, sheets:sheetsPreview,
                             slides:slidesPreview};
    // The detail pane is rebuilt on every render — a keystroke in the search
    // box is enough — so an open editor's text cannot live in the element.
    // It lived there until this was measured: typing in search while editing
    // destroyed the edit with no warning and no way back.
    // `etag` is the version the open payload came from. The server refuses a
    // save that does not carry the current one, so this is not bookkeeping —
    // it is the thing that stops one editor's save deleting another's.
    // `mode` starts at the rendered surface, because a document that was just
    // created is one nobody has been shown yet — opening on the field editor
    // asks what it is instead of saying.
    //
    // `loading` and `failed` are what stop the automatic open from firing
    // again on the next render. The detail pane is rebuilt on every keystroke
    // in the search box, and a fetch per keystroke is what the guard prevents.
    const closedEditor = (id) => ({id, open:false, mode:'preview',
                                   payload:null, text:'', tab:null, slide:0,
                                   etag:null, warnings:null,
                                   loading:false, failed:false});
    let driveEditor = closedEditor(null);
    // Three views of one document: the surface as it is, the fields of it, and
    // the JSON underneath for everything the fields do not reach. Whichever
    // is showing, what gets saved is the same projected payload.
    const documentActions = (item) => {
      const actions = make('div', 'detail-actions');
      const row = make('div', 'detail-actions__row');
      const status = make('p', 'drive-create__status', '');
      if (driveEditor.id !== item.id) driveEditor = closedEditor(item.id);
      const vocabulary = (driveData.kinds || [])
        .find((k) => k.kind === item.kind)?.vocabulary;

      if (item['trashed?']) {
        const restore = make('button', 'tool-button', '復元');
        restore.type = 'button';
        restore.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/restore`, {},
          `${item.name} を復元しました。`));
        row.append(restore);
        actions.append(row, status);
        return actions;
      }

      // A file is bytes, not a document: there is nothing for the editors to
      // open, so the pane offers the one thing that makes sense for it.
      if (item['file?']) {
        // Shown, not only offered — but only for the handful of types the
        // server will serve inline. The client does not decide from the
        // media type: `previewable?` is the report of one allowlist.
        if (item['previewable?']) {
          const figure = make('div', 'file-preview');
          const image = make('img', 'file-preview__image');
          image.src = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/preview`;
          image.alt = item.name;
          image.loading = 'lazy';
          figure.append(image);
          actions.append(figure);
        }
        const download = make('a', 'tool-button', 'ダウンロード');
        download.href = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/download`;
        download.setAttribute('download', '');
        row.append(download);
        if (item.role === 'owner') row.append(trash);
        actions.append(row, status, referencePanel(item), commentPanel(item));
        if (item.role === 'owner') actions.append(sharingPanel(item, status));
        return actions;
      }
      const open = make('button', 'tool-button', driveEditor.open ? '再読み込み' : '編集');
      open.type = 'button';
      const save = make('button', 'tool-button', '保存');
      save.type = 'button';
      save.hidden = !driveEditor.open;
      const rename = make('button', 'tool-button', '名前を変更');
      rename.type = 'button';
      const trash = make('button', 'tool-button', 'ゴミ箱へ');
      trash.type = 'button';
      // An inline field rather than window.prompt: a modal dialog blocks the
      // page, and this one would be blocking it to collect a single string
      // the detail pane already has room for.
      const titleField = make('input', 'workspace-search document-title');
      titleField.type = 'text';
      titleField.value = item.name;
      titleField.setAttribute('aria-label', '名前');
      const editor = make('textarea', 'document-preview');
      editor.spellcheck = false;
      editor.setAttribute('aria-label', `${item.name} の内容（JSON）`);
      // Every keystroke, so the text survives the next render rather than
      // only the next save.
      editor.addEventListener('input', () => { driveEditor.text = editor.value; });
      const pane = make('div', 'surface-pane');
      const modes = make('div', 'surface-modes');
      const previewButton = make('button', null, 'プレビュー');
      previewButton.type = 'button';
      const structuredButton = make('button', null, 'フォーム表示');
      structuredButton.type = 'button';
      const jsonButton = make('button', null, 'JSON 表示');
      jsonButton.type = 'button';
      modes.append(previewButton, structuredButton, jsonButton);

      // The one place the views meet. Structured edits write into `payload`
      // and the text is re-derived; JSON edits write the text and it is parsed
      // when switching away. Neither is ever stale on save.
      const syncText = () => { driveEditor.text = JSON.stringify(driveEditor.payload, null, 2); };
      const renderPane = () => {
        pane.replaceChildren();
        modes.hidden = !driveEditor.open;
        if (!driveEditor.open) { editor.hidden = true; return; }
        [[previewButton, 'preview'], [structuredButton, 'structured'],
         [jsonButton, 'json']].forEach(([button, mode]) =>
          button.setAttribute('aria-pressed', driveEditor.mode === mode ? 'true' : 'false'));
        if (driveEditor.mode === 'json') {
          editor.hidden = false;
          editor.value = driveEditor.text;
          return;
        }
        editor.hidden = true;
        const preview = driveEditor.mode === 'preview';
        const build = (preview ? surfacePreviews : surfaceEditors)[item.kind];
        if (!build || !driveEditor.payload) {
          pane.append(make('p', 'empty-state', preview
            ? 'この種類にはまだ表示できる形がありません。JSON 表示で確認してください。'
            : 'この種類はまだ JSON でのみ編集できます。'));
          return;
        }
        // A preview never writes into the payload, but it does move which tab
        // or slide is being looked at — and the callback is what redraws it.
        const rendered = build(driveEditor.payload, vocabulary, (rebuild) => {
          syncText();
          if (rebuild) renderPane();
        });
        if (preview) {
          const frame = make('div', 'surface-preview');
          frame.append(rendered);
          pane.append(frame);
        } else {
          pane.append(rendered);
        }
      };
      // Leaving the JSON view means the text is the truth and the payload has
      // to catch up. Refused rather than silently discarding whichever side is
      // wrong.
      const adoptText = () => {
        if (driveEditor.mode !== 'json') return true;
        try {
          driveEditor.payload = JSON.parse(driveEditor.text);
          return true;
        } catch (error) {
          status.textContent = `JSON として読めないので切り替えられません: ${error.message}`;
          return false;
        }
      };
      const showMode = (mode) => {
        if (driveEditor.mode === mode) return;
        if (!adoptText()) return;
        driveEditor.mode = mode;
        status.textContent = '';
        renderPane();
      };
      previewButton.addEventListener('click', () => showMode('preview'));
      structuredButton.addEventListener('click', () => showMode('structured'));
      jsonButton.addEventListener('click', () => {
        if (driveEditor.mode === 'json') return;
        syncText();
        driveEditor.mode = 'json';
        renderPane();
      });
      // One shape for every way a payload arrives — 編集, a version, a reload
      // after a refused save, and the automatic first open. Written as a
      // literal at each call site, a field added later is a field forgotten at
      // three of them; `slide` and `tab` were exactly that.
      const openedEditor = (fresh) => ({...closedEditor(item.id), open:true,
                                        mode:driveEditor.mode, tab:driveEditor.tab,
                                        slide:driveEditor.slide,
                                        payload:fresh.payload, etag:fresh.etag,
                                        warnings:fresh.warnings});

      const versions = make('div', 'detail-actions__row');
      const bytesDelta = (n) => (n > 0 ? `+${bytes(n)}` : (n < 0 ? `-${bytes(-n)}` : '±0'));
      // Owner only and never automatic: it is irreversible, and a Drive that
      // deleted history at a moment nobody chose would be worse than one
      // that fills up and says so.
      if (item.role === 'owner' && (item.versions || 0) > 1) {
        const prune = make('button', 'tool-button',
          `古い版を削除（最新 ${item.versions > 10 ? 10 : 1} 件を残す・${bytes(item['held-bytes'])} 使用中）`);
        prune.type = 'button';
        prune.addEventListener('click', async () => {
          prune.disabled = true; status.textContent = '古い版を削除しています…';
          try {
            const out = await postJSON(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/prune`,
              {keep: item.versions > 10 ? 10 : 1}, true);
            selectedDrive = out.item;
            await loadWorkspace('drive', renderDrive);
            $('#drive-create-status').textContent =
              `${out.deleted} 件の版を削除し、${bytes(out['freed-bytes'])} を回収しました。`;
          } catch (error) {
            status.textContent = error.message;
          } finally {
            prune.disabled = false;
          }
        });
        versions.append(prune);
      }
      for (let n = (item.versions || 0); n >= 1; n -= 1) {
        // Newest first, labelled with who wrote it and what it cost. On a
        // shared document the version number alone does not tell you whose
        // change you are about to open.
        const wrote = (item.history || [])[n - 1];
        const current = n === (item.versions || 0);
        const label = [`版 ${n}`, wrote?.author,
                       wrote ? bytesDelta(wrote['delta-bytes'] ?? 0) : null,
                       current ? '（現在）' : null].filter(Boolean).join('・');
        const version = make('button', 'tool-button', label);
        version.type = 'button';
        if (!current && item['writable?']) {
          const restore = make('button', 'tool-button', `版 ${n} に戻す`);
          restore.type = 'button';
          restore.addEventListener('click', async () => {
            restore.disabled = true; status.textContent = `版 ${n} に戻しています…`;
            try {
              const out = await postJSON(
                `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`
                  + `/versions/${n}/restore`,
                {etag:item.etag}, true);
              // A new version on top, not a rewrite — so the count goes up.
              driveEditor = closedEditor(item.id);
              selectedDrive = out.item;
              await loadWorkspace('drive', renderDrive);
              $('#drive-create-status').textContent =
                `版 ${out['restored-from']} の内容を版 ${out.item.versions} として保存しました。`;
            } catch (error) {
              status.textContent = error.message;
            } finally {
              restore.disabled = false;
            }
          });
          versions.append(restore);
        }
        version.addEventListener('click', async () => {
          status.textContent = `版 ${n} を読み込んでいます…`;
          try {
            const request = await fetch(
              `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/versions/${n}`);
            const data = await request.json();
            if (!request.ok) throw new Error(data?.error?.message || '版を取得できませんでした。');
            // The etag of the *current* version, not of the one being
            // viewed: saving an old version forward is a new version on top
            // of what is there, not a rewrite of history.
            driveEditor = openedEditor(
              // Warnings cleared, not carried over: the ones on screen were
              // about the current version, and an old version loaded into the
              // editor is not it.
              {payload:data.payload, etag:data.item?.etag,
               warnings:data['export-warnings'] || null});
            syncText();
            save.hidden = false;
            renderPane();
            // Loaded into the editor rather than restored behind the user's
            // back: saving it is what makes it current, and that is a new
            // version like any other.
            status.textContent = `版 ${n}（${data['created-at'] || '日時不明'}）を表示中。`
              + '保存すると新しい版になります。';
          } catch (error) {
            status.textContent = error.message;
          }
        });
        versions.append(version);
      }

      const load = async () => {
        const request = await fetch(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '内容を取得できませんでした。');
        return {payload:data.payload, etag:data.item?.etag,
                warnings:data['export-warnings'] || null};
      };
      open.addEventListener('click', async () => {
        open.disabled = true; status.textContent = '読み込んでいます…';
        try {
          const fresh = await load();
          driveEditor = openedEditor(fresh);
          syncText();
          save.hidden = false;
          renderPane();
          status.textContent = '';
        } catch (error) {
          status.textContent = error.message;
        } finally {
          open.disabled = false;
        }
      });
      save.addEventListener('click', async () => {
        let payload;
        // The JSON view is the only one whose text can be ahead of the
        // payload. The fields write into the payload and the preview does not
        // write at all, so both save what is already there.
        if (driveEditor.mode === 'json') {
          try {
            payload = JSON.parse(driveEditor.text);
          } catch (error) {
            // Refused here rather than sent: a body that is not JSON is not a
            // document the server can say anything useful about.
            status.textContent = `JSON として読めません: ${error.message}`;
            return;
          }
        } else {
          payload = driveEditor.payload;
        }
        save.disabled = true; status.textContent = '保存しています…';
        try {
          const saved = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/versions`,
            {payload, etag:driveEditor.etag}, true);
          const warnings = (saved.warnings || []).map((w) => w.message).join(' / ');
          // The save went through and the surface still had something to say.
          driveEditor = closedEditor(item.id);
          selectedDrive = saved.item;
          await loadWorkspace('drive', renderDrive);
          $('#drive-create-status').textContent = warnings
            ? `保存しました（版 ${saved.item.versions}）。注意: ${warnings}`
            : `保存しました（版 ${saved.item.versions}）。`;
        } catch (error) {
          // A refused save keeps the editor open with the text intact. The
          // work is not lost and not applied; the person decides.
          status.textContent = error.message;
          if (/更新しました/.test(error.message)) {
            const reload = make('button', 'tool-button', '相手の版を読み込む（自分の編集は破棄）');
            reload.type = 'button';
            reload.addEventListener('click', async () => {
              const fresh = await load();
              driveEditor = openedEditor(fresh);
              syncText(); renderPane();
              status.textContent = '最新の版を読み込みました。';
            });
            status.append(' ', reload);
          }
        } finally {
          save.disabled = false;
        }
      });
      rename.addEventListener('click', async () => {
        rename.disabled = true; status.textContent = '名前を変更しています…';
        try {
          const renamed = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/rename`,
            {title:titleField.value}, true);
          status.textContent = '';
          selectedDrive = renamed.item;
          await loadWorkspace('drive', renderDrive);
        } catch (error) {
          status.textContent = error.message;
        } finally {
          rename.disabled = false;
        }
      });
      trash.addEventListener('click', () => {
        driveEditor = closedEditor(null);
        driveAction(`/api/workspace/drive/documents/${encodeURIComponent(item.id)}/trash`, {},
          `${item.name} をゴミ箱へ移動しました。`);
      });
      // A viewer or commenter gets the document and not the verbs. The
      // server refuses them anyway — `writable?` is `can-write?`'s answer,
      // not a second copy of the rule — but offering a button that always
      // fails is its own kind of lie.
      if (!item['writable?']) { save.hidden = true; }
      row.append(titleField);
      if (item['writable?']) row.append(rename);
      row.append(open);
      if (item['writable?']) row.append(save);
      // Offered to anyone who can see the document, including a viewer of
      // one shared read-only — which is the case the operation exists for.
      const copy = make('button', 'tool-button', 'コピーを作成');
      copy.type = 'button';
      copy.addEventListener('click', () => driveAction(
        `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/copy`,
        {folder:driveFolder}, `${item.name} のコピーを作成しました。`));
      row.append(copy);
      if (item.role === 'owner') {
        // Owner only, because moving into a shared folder shares what was
        // moved — an editor who could move could widen the access the owner
        // granted, the same reason re-sharing is owner-only.
        const destinations = (folderData.all || [])
          .filter((folder) => folder.id !== item.id);
        const picker = selectInput(item['parent-id'] || '',
          destinations.map((folder) => folder.id),
          async (value) => {
            const status = $('#drive-create-status');
            try {
              await postJSON(
                `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/move`,
                {folder:value}, true);
              status.textContent = `${item.name} を移動しました。`;
              await loadFolders();
              await loadWorkspace('drive', renderDrive);
            } catch (error) { status.textContent = error.message; }
          });
        // Labelled by path, valued by id: two folders called Q1 are ordinary
        // and a picker showing both as Q1 asks an unanswerable question.
        Array.from(picker.options).forEach((option) => {
          const hit = destinations.find((folder) => folder.id === option.value);
          if (hit) option.textContent = hit.name;
        });
        row.append(field('フォルダ', picker));
        row.append(trash);
      }
      actions.append(row, status, modes, pane, editor, versions);
      renderPane();
      // Opened without being asked. A document that was just created and a
      // document that was just selected are both ones nobody has seen, and
      // making the surface wait behind 編集 means the answer to 「何を作った
      // のか」 is a button press away. The fetch is the one 編集 does.
      //
      // Guarded on all three of payload, loading and failed, because this runs
      // on every render of the detail pane — a keystroke in the search box is
      // one — and without the guard that is a request per keystroke.
      //
      // And on `resource-kind`, which is what `documents/item-view` carries and
      // `documents/folder-view` deliberately does not: a folder has no bytes,
      // and asking for its content is a request that can only fail.
      if (item['resource-kind'] && !driveEditor.payload
          && !driveEditor.loading && !driveEditor.failed) {
        driveEditor.loading = true;
        status.textContent = '読み込んでいます…';
        (async () => {
          try {
            const fresh = await load();
            // The selection may have moved while this was in flight, and the
            // nodes this closure holds are no longer on the page if it has.
            if (driveEditor.id !== item.id) return;
            driveEditor = openedEditor(fresh);
            syncText();
            open.textContent = '再読み込み';
            save.hidden = !item['writable?'];
            renderPane();
            status.textContent = '';
          } catch (error) {
            if (driveEditor.id !== item.id) return;
            driveEditor.loading = false;
            // Marked so the next render does not ask again: a document that
            // cannot be read is not one to retry on every keystroke. 編集
            // remains, which is the way to ask again on purpose.
            driveEditor.failed = true;
            status.textContent = error.message;
          }
        })();
      }
      if (item.kind === 'forms') actions.append(answerPanel(item));
      // Export is a plain link, not a fetch: the browser already knows how to
      // save a response with a Content-Disposition, and routing binary
      // through JavaScript to hand it back to the browser is work that only
      // adds a place to get it wrong.
      const exports = make('div', 'detail-actions__row');
      // Some formats write something other than the document — a form's CSV
      // is its responses — and those are the owner's. Offering the button to
      // a viewer would be offering one that refuses.
      const kindSpec = (driveData.kinds || []).find((k) => k.kind === item.kind);
      const ownerOnly = kindSpec?.['owner-only-exports'] || [];
      (kindSpec?.exports || [])
        .filter((format) => item.role === 'owner' || !ownerOnly.includes(format))
        .forEach((format) => {
        const label = ownerOnly.includes(format)
          ? `回答を ${format.toUpperCase()} で書き出す` : `${format.toUpperCase()} で書き出す`;
        const link = make('a', 'tool-button', label);
        link.href = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`
          + `/export?format=${encodeURIComponent(format)}`;
        link.setAttribute('download', '');
        exports.append(link);
      });
      // What a format cannot carry, said twice and for different reasons.
      // The static line is about Markdown and is true of every document, so
      // it costs nothing and is there before anything is opened. The list
      // below it is about *this* document and needs its bytes, which arrive
      // only when it is loaded — and the detail pane rebuilds on every
      // keystroke in the search box, so fetching them per render would be a
      // request per keystroke.
      const exportNotes = make('div', 'export-notes');
      if ((driveData.kinds || []).find((k) => k.kind === item.kind)
            ?.exports?.includes('md')) {
        exportNotes.append(make('p', 'surface-note',
          'Markdown はブロック ID・コメント・提案・一部の書式を保持しません。'));
      }
      const specific = driveEditor.id === item.id ? driveEditor.warnings : null;
      Object.entries(specific || {}).forEach(([format, entries]) => {
        const list = make('ul', 'export-notes__list');
        entries.forEach((entry) => {
          list.append(make('li', 'surface-note',
            `${entry.message}${entry.id ? `（${entry.id}）` : ''}`));
        });
        exportNotes.append(
          make('p', 'surface-note',
               `この文書を ${format.toUpperCase()} で書き出すと失われるもの:`),
          list);
      });
      if (item.kind === 'forms' && item.role === 'owner') {
        const snapshot = make('button', 'tool-button', '回答をスプレッドシートに');
        snapshot.type = 'button';
        snapshot.addEventListener('click', () => driveAction(
          `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/responses-sheet`,
          {}, `${item.name} の回答をスプレッドシートにしました。`));
        exports.append(snapshot);
        // Said on the screen rather than left to be discovered: it is the
        // answers as of now, and asking again makes a second one.
        exportNotes.append(make('p', 'surface-note',
          'スプレッドシートは作成時点のスナップショットです（自動更新されません）。'));
      }
      actions.append(exports, exportNotes, referencePanel(item), commentPanel(item));
      // Documents only — the server refuses a workbook or a deck, so a box
      // that always failed would be worse than none.
      if (item.kind === 'docs') actions.append(suggestionPanel(item));
      if (item.role === 'owner') actions.append(sharingPanel(item, status));
      return actions;
    };
    // Both directions. Outgoing is what this document names; incoming is
    // which documents name it — and the second is the half that makes the
    // first worth resolving, because a workbook that cannot say which memo
    // depends on it is a workbook nobody dares change.
    const referencePanel = (item) => {
      const panel = make('div', 'sharing');
      panel.hidden = true;
      const out = make('ul', 'sharing__list');
      const back = make('ul', 'sharing__list');
      const jump = (id, name) => {
        const button = make('button', 'tool-button', name || id);
        button.type = 'button';
        button.addEventListener('click', () => {
          const hit = (driveData.items || []).find((candidate) => candidate.id === id);
          if (!hit) return;
          driveEditor = closedEditor(hit.id);
          selectedDrive = hit;
          renderDrive(driveData);
        });
        return button;
      };
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/references`);
          const data = await request.json();
          if (!request.ok) return;
          const refs = data.references || [];
          const incoming = data['referenced-by'] || [];
          if (!refs.length && !incoming.length) return;
          panel.hidden = false;
          panel.append(make('h3', 'sharing__title', '参照'));
          refs.forEach((ref) => {
            const row = make('li', 'sharing__entry');
            row.append(make('span', 'sharing__who', `${ref.kind}（${ref.block}）→`));
            if (ref['resolved?']) {
              row.append(jump(ref.target, ref.name));
              if (ref['expected?'] === false) {
                row.append(make('span', 'surface-note',
                  `${ref.label} は ${ref.kind} の想定と異なります`));
              }
            } else {
              row.append(make('span', 'surface-note',
                `${ref.target || '未設定'} は見つかりません`));
            }
            out.append(row);
          });
          if (refs.length) panel.append(out);
          if (incoming.length) {
            panel.append(make('h3', 'sharing__title', '参照元'));
            incoming.forEach((from) => {
              const row = make('li', 'sharing__entry');
              row.append(jump(from.id, from.name),
                make('span', 'sharing__who', `${from.kind}（${from.block}）`));
              back.append(row);
            });
            panel.append(back);
          }
        } catch (error) { /* the panel simply stays hidden */ }
      })();
      return panel;
    };
    // Comments are shown to anyone who may read the document and written by
    // anyone above :viewer, which is what makes :commenter a role rather
    // than a word. They are not part of the stored bytes — see the comments
    // section in `cloud.itonami.app.documents` for why not.
    const commentRoles = ['owner', 'editor', 'commenter'];
    // Proposals from someone who may say what should change and may not
    // change it. Only for documents: the server refuses anything else, and
    // offering a box that always fails is worse than not offering one.
    const suggestionPanel = (item) => {
      const panel = make('div', 'sharing');
      panel.append(make('h3', 'sharing__title', '提案'));
      const list = make('ul', 'sharing__list');
      const status = make('p', 'drive-create__status', '');
      const form = make('div', 'detail-actions__row');
      const block = make('input', 'workspace-search document-title');
      block.type = 'text';
      block.placeholder = '段落 ID';
      block.setAttribute('aria-label', '提案する段落の ID');
      const text = make('input', 'workspace-search surface-input--wide');
      text.type = 'text';
      text.placeholder = '提案する本文';
      text.setAttribute('aria-label', '提案する本文');
      const add = make('button', 'tool-button', '提案する');
      add.type = 'button';
      const base = `/api/workspace/drive/documents/${encodeURIComponent(item.id)}`;
      const reload = async () => {
        try {
          const response = await fetch(`${base}/suggestions`);
          const data = await response.json();
          if (!response.ok) return;
          list.replaceChildren();
          (data.suggestions || []).forEach((s) => {
            const row = make('li', 'sharing__entry');
            row.append(make('span', 'sharing__who', `${s.author}（${s.block}）`));
            row.append(make('span', 'sharing__role', s.text));
            if (s.status !== 'open') {
              row.append(make('span', 'surface-note',
                s.status === 'accepted' ? '反映済み' : '却下'));
            } else if (s['stale?']) {
              // Said before anyone presses accept, because accepting is
              // what the server will refuse.
              row.append(make('span', 'surface-note',
                `この提案のあとに本文が変わりました（現在: ${s.current}）`));
            }
            if (s.status === 'open') {
              const act = async (verb, message) => {
                try {
                  await postJSON(`${base}/suggestions/${encodeURIComponent(s.id)}/${verb}`,
                                 {}, true);
                  status.textContent = message;
                  await reload();
                  await loadWorkspace('drive', renderDrive);
                } catch (error) { status.textContent = error.message; }
              };
              if (item['writable?']) {
                const accept = make('button', 'tool-button', '反映');
                accept.type = 'button';
                accept.addEventListener('click', () => act('accept', '提案を反映しました。'));
                row.append(accept);
              }
              const reject = make('button', 'tool-button', '却下');
              reject.type = 'button';
              reject.addEventListener('click', () => act('reject', '提案を却下しました。'));
              row.append(reject);
            }
            list.append(row);
          });
          if (!(data.suggestions || []).length) {
            list.append(make('li', 'empty-state', 'まだ提案はありません。'));
          }
        } catch (error) { status.textContent = error.message; }
      };
      add.addEventListener('click', async () => {
        try {
          await postJSON(`${base}/suggestions`,
                         {block:block.value, text:text.value}, true);
          text.value = '';
          status.textContent = '提案しました。';
          await reload();
        } catch (error) { status.textContent = error.message; }
      });
      form.append(block, text, add);
      panel.append(list, form, status);
      reload();
      return panel;
    };
    const commentPanel = (item) => {
      const panel = make('div', 'sharing');
      const heading = make('h3', 'sharing__title', 'コメント');
      panel.append(heading);
      const list = make('ul', 'sharing__list');
      const status = make('p', 'drive-create__status', '');
      const form = make('div', 'detail-actions__row');
      const text = make('input', 'workspace-search surface-input--wide');
      text.type = 'text';
      text.placeholder = 'コメント';
      text.setAttribute('aria-label', 'コメント');
      const anchor = make('input', 'workspace-search document-title');
      anchor.type = 'text';
      anchor.placeholder = '位置（任意）';
      anchor.setAttribute('aria-label', 'コメントの位置（任意）');
      const add = make('button', 'tool-button', '投稿');
      add.type = 'button';

      // One entry, root or reply. The rules it shows are the server's — the
      // author or the owner may delete, anyone who may comment may resolve —
      // rather than buttons that fail for everyone else.
      const entryRow = (entry, {root} = {}) => {
        const row = make('li', root ? 'sharing__entry sharing__entry--reply' : 'sharing__entry');
        row.append(make('span', 'sharing__who',
          `${entry.author}${entry.anchor ? ` · ${entry.anchor}` : ''} · ${entry['created-at']}`));
        row.append(make('span', 'surface-note', entry.text));
        if (entry.author === currentUserId() || item.role === 'owner') {
          const remove = make('button', 'tool-button', root ? '削除' : '削除（返信ごと）');
          remove.type = 'button';
          remove.addEventListener('click', () => submit(
            `/comments/${encodeURIComponent(entry.id)}/delete`, {}, 'コメントを削除しました。'));
          row.append(remove);
        }
        return row;
      };
      const render = (entries) => {
        list.replaceChildren();
        if (!entries.length) {
          list.append(make('li', 'empty-state', 'まだコメントはありません。'));
          return;
        }
        entries.forEach((entry) => {
          const thread = make('li', entry['resolved-at'] ? 'sharing__thread is-resolved'
                                                         : 'sharing__thread');
          const head = make('ul', 'sharing__list');
          head.append(entryRow(entry));
          if (entry['resolved-at']) {
            head.append(make('li', 'surface-note',
              `${entry['resolved-by']} が解決済みにしました（${entry['resolved-at']}）`));
          }
          (entry.replies || []).forEach((reply) => head.append(entryRow(reply, {root:entry})));
          thread.append(head);
          if (commentRoles.includes(item.role)) {
            const actions = make('div', 'detail-actions__row');
            const toggle = make('button', 'tool-button',
              entry['resolved-at'] ? '未解決に戻す' : '解決済みにする');
            toggle.type = 'button';
            toggle.addEventListener('click', () => submit(
              `/comments/${encodeURIComponent(entry.id)}/resolve`,
              {'resolved?': !entry['resolved-at']},
              entry['resolved-at'] ? '未解決に戻しました。' : '解決済みにしました。'));
            actions.append(toggle);
            if (!entry['resolved-at']) {
              // Replying to a resolved thread is refused by the server;
              // reopening is an act somebody takes on purpose.
              const replyText = make('input', 'workspace-search surface-input--wide');
              replyText.type = 'text';
              replyText.placeholder = '返信';
              replyText.setAttribute('aria-label', `${entry.text} への返信`);
              const send = make('button', 'tool-button', '返信');
              send.type = 'button';
              send.addEventListener('click', () => submit(
                '/comments', {text:replyText.value, 'parent-id':entry.id}, '返信しました。'));
              actions.append(replyText, send);
            }
            thread.append(actions);
          }
          list.append(thread);
        });
      };
      const reload = async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/comments`);
          const data = await request.json();
          if (!request.ok) return;
          render(data.comments || []);
          // The count worth seeing before opening the panel: an unresolved
          // thread is one somebody is still waiting on.
          heading.textContent = data.unresolved
            ? `コメント（未解決 ${data.unresolved}）` : 'コメント';
        } catch (error) { /* the panel simply stays empty */ }
      };
      const submit = async (suffix, body, done) => {
        status.textContent = '送信しています…';
        try {
          await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}${suffix}`,
            body, true);
          status.textContent = done;
          text.value = ''; anchor.value = '';
          await reload();
        } catch (error) {
          status.textContent = error.message;
        }
      };
      add.addEventListener('click', () => submit(
        '/comments', {text:text.value, anchor:anchor.value}, 'コメントしました。'));

      form.append(text, anchor, add);
      panel.append(list);
      if (commentRoles.includes(item.role)) panel.append(form);
      panel.append(status);
      reload();
      return panel;
    };
    // A form is the one surface with a second thing to do to it. Editing it
    // changes the questions; answering it does not, and the answers are not
    // a version of the form — so this is a panel of its own rather than
    // another mode of the editor. `inputTypes` is declared with the rendered
    // surfaces, because the preview of a form and a real one have to agree on
    // what a field type is.
    const answerPanel = (item) => {
      const panel = make('div', 'sharing');
      panel.append(make('h3', 'sharing__title', 'このフォームに回答'));
      const body = make('div', 'surface-editor');
      const status = make('p', 'drive-create__status', '');
      const answers = {};
      const send = make('button', 'tool-button', '送信');
      send.type = 'button';
      send.disabled = true;
      const responses = make('div', 'surface-editor');

      const loadResponses = async () => {
        if (item.role !== 'owner') return;
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/submissions`);
          const data = await request.json();
          if (!request.ok) return;
          responses.replaceChildren(make('h3', 'sharing__title',
            `回答 ${(data.submissions || []).length} 件`));
          (data.submissions || []).forEach((entry) => {
            const row = make('div', 'surface-row');
            row.append(make('span', 'surface-note',
              `${entry.author || '不明'} · ${entry['submitted-at'] || ''}`));
            Object.entries(entry.answers || {}).forEach(([key, value]) => {
              row.append(make('span', 'sharing__who', `${key}: ${value}`));
            });
            responses.append(row);
          });
        } catch (error) { /* the panel simply stays empty */ }
      };
      send.addEventListener('click', async () => {
        send.disabled = true; status.textContent = '送信しています…';
        try {
          const sent = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/submissions`,
            {answers}, true);
          status.textContent = `送信しました（${sent.submission['submitted-at']}）。`;
          await loadResponses();
        } catch (error) {
          // The surface's own validator answered — a missing required field
          // or an address that is not one — so the message is its message.
          status.textContent = error.message;
        } finally {
          send.disabled = false;
        }
      });
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/form`);
          const data = await request.json();
          if (!request.ok) throw new Error(data?.error?.message || 'フォームを読み込めませんでした。');
          body.replaceChildren();
          if (!(data.fields || []).length) {
            body.append(make('p', 'empty-state', 'まだ質問がありません。'));
          }
          (data.fields || []).forEach((entry) => {
            const control = entry['field-type'] === 'textarea'
              ? make('textarea', 'document-preview')
              : make('input', 'workspace-search surface-input--wide');
            if (control.tagName === 'INPUT') {
              control.type = inputTypes[entry['field-type']] || 'text';
            }
            if (entry['field-type'] === 'checkbox') {
              control.type = 'checkbox';
              control.className = 'surface-check';
              control.addEventListener('change', () => { answers[entry.id] = control.checked; });
            } else {
              control.addEventListener('input', () => { answers[entry.id] = control.value; });
            }
            body.append(field(`${entry.label}${entry['required?'] ? ' *' : ''}`, control));
          });
          send.disabled = false;
        } catch (error) {
          body.replaceChildren(make('p', 'empty-state', error.message));
        }
      })();
      panel.append(body, send, status, responses);
      loadResponses();
      return panel;
    };
    // Sharing is owner-only, and the panel says who has what rather than
    // only offering to add: a share you cannot see is one you cannot undo.
    const sharingPanel = (item, status) => {
      const panel = make('div', 'sharing');
      const heading = make('h3', 'sharing__title', '共有');
      const current = make('ul', 'sharing__list');
      const form = make('div', 'detail-actions__row');
      // A picker over the organization's other members, with the free-text
      // field kept: the server accepts any principal, so a name that is not
      // in the directory is still one that works, and removing the field
      // would make that untrue in the UI only.
      const whoPicker = make('select', 'model-pill');
      whoPicker.setAttribute('aria-label', '共有相手');
      const who = make('input', 'workspace-search document-title');
      who.type = 'text';
      who.placeholder = '共有相手の User ID';
      who.setAttribute('aria-label', '共有相手の User ID');
      whoPicker.addEventListener('change', () => {
        if (whoPicker.value) who.value = whoPicker.value;
      });
      const role = make('select', 'model-pill');
      role.setAttribute('aria-label', '権限');
      const share = make('button', 'tool-button', '共有する');
      share.type = 'button';
      const linkRole = make('select', 'model-pill');
      linkRole.setAttribute('aria-label', 'リンクの権限');
      const expiry = make('select', 'model-pill');
      expiry.setAttribute('aria-label', 'リンクの有効期限');
      [['', '期限なし'], ['24', '24時間'], ['168', '7日間']].forEach(([value, label]) => {
        const option = make('option', null, label); option.value = value; expiry.append(option);
      });
      const makeLink = make('button', 'tool-button', 'リンクを作成');
      makeLink.type = 'button';

      // Options come from the server's own lists, so `:owner` never appears
      // among them — `documents/grantable-roles` leaves it out on purpose.
      const fillOnce = (select, names) => {
        if (select.options.length || !names) return;
        names.forEach((name) => {
          const option = make('option', null, name); option.value = name; select.append(option);
        });
      };
      const render = (data) => {
        fillOnce(role, data.roles);
        fillOnce(linkRole, data['link-roles']);
        if (!whoPicker.options.length && (data.candidates || []).length) {
          const blank = make('option', null, 'Organization から選ぶ');
          blank.value = ''; whoPicker.append(blank);
          data.candidates.forEach((candidate) => {
            const option = make('option', null,
              candidate['display-name'] || candidate.email || candidate.id);
            option.value = candidate.id;
            whoPicker.append(option);
          });
        }
        whoPicker.hidden = !whoPicker.options.length;
        current.replaceChildren();
        (data.grants || []).forEach((grant) => {
          const entry = make('li', 'sharing__entry');
          entry.append(make('span', 'sharing__who', `${grant.principal}（${grant.role}）`));
          const revoke = make('button', 'tool-button', '解除');
          revoke.type = 'button';
          revoke.addEventListener('click', () => submit(
            {action:'revoke', principal:grant.principal}, `${grant.principal} の共有を解除しました。`));
          entry.append(revoke);
          current.append(entry);
        });
        (data.links || []).forEach((link) => {
          const entry = make('li', 'sharing__entry');
          const url = `${window.location.origin}/api/workspace/drive/shared/${encodeURIComponent(link.token)}`;
          const field = make('input', 'workspace-search sharing__token');
          field.type = 'text'; field.readOnly = true; field.value = url;
          field.setAttribute('aria-label', `共有リンク（${link.role}）`);
          entry.append(make('span', 'sharing__who',
            `リンク（${link.role}・${link['expires-at'] ? '期限あり' : '期限なし'}）`), field);
          const revoke = make('button', 'tool-button', '無効化');
          revoke.type = 'button';
          revoke.addEventListener('click', () => submit(
            {action:'revoke-link', token:link.token}, 'リンクを無効化しました。'));
          entry.append(revoke);
          current.append(entry);
        });
        if (!(data.grants || []).length && !(data.links || []).length) {
          current.append(make('li', 'empty-state', 'まだ誰とも共有していません。'));
        }
      };
      const submit = async (body, done) => {
        status.textContent = '共有設定を更新しています…';
        try {
          const data = await postJSON(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/sharing`,
            body, true);
          render(data);
          status.textContent = done;
        } catch (error) {
          status.textContent = error.message;
        }
      };
      share.addEventListener('click', () => submit(
        {principal:who.value, role:role.value}, `${who.value} と共有しました。`));
      makeLink.addEventListener('click', () => submit(
        {action:'link', role:linkRole.value,
         'expires-in-hours':expiry.value ? Number(expiry.value) : null},
        'リンクを作成しました。'));

      form.append(whoPicker, who, role, share);
      const linkForm = make('div', 'detail-actions__row');
      linkForm.append(make('span', 'sharing__who', '共有リンク'), linkRole, expiry, makeLink);
      panel.append(heading, current, form, linkForm);
      (async () => {
        try {
          const request = await fetch(
            `/api/workspace/drive/documents/${encodeURIComponent(item.id)}/sharing`);
          const data = await request.json();
          if (request.ok) render(data);
        } catch (error) { /* the panel simply stays empty */ }
      })();
      return panel;
    };
    const renderFilecoin = (data) => {
      const list = $('#storage-list'); list.replaceChildren();
      const val = (v) => (v && typeof v === 'object' && v.error) ? `取得失敗: ${v.error}` : String(v ?? '—');
      const rows = [
        ['Chain height', val(data['chain-height']), 'live'],
        ['Network', `${val(data['chain-network-name'])} · chainId ${val(data['chain-id'])}`, 'live'],
        ['PDP data sets', val(data['pdp-next-data-set-id']), 'live'],
        ['Challenge finality', `${val(data['pdp-challenge-finality'])} epochs`, 'live'],
        ['Staged pieces', String((data['staged-pieces'] || []).length), 'local'],
        ['Read-through', (data['retrieval-urls'] || []).length
          ? `${(data['retrieval-urls'] || []).join(' · ')} · PieceCID 検証あり`
          : '未設定', (data['retrieval-urls'] || []).length ? 'local' : 'warn'],
        ['Deals', '未実装', 'warn']
      ];
      rows.forEach(([title, meta, kind]) =>
        list.append(listItem(title, meta, kind === 'warn' ? '未対応' : kind, kind === 'warn')));
      const sample = data.sample || {};
      setDetail($('#storage-detail'), 'PieceCID v2 · FRC-0069',
        sample.cid || 'PieceCID 未計算',
        'このアプリのプロセス内で計算した実際の PieceCID です。provider も資金も'
          + '不要で、Filecoin の識別子だけを先に確定できます。',
        [['対象', sample.text], ['元サイズ', bytes(sample.bytes)],
         ['tree height', String(sample.height ?? '—')],
         ['zero padding', `${sample.padding ?? '—'} B`],
         ['padded size', bytes(sample['padded-size'])],
         ['PDPVerifier', data['pdp-verifier']],
         ['PDPVerifier (f4)', data['pdp-verifier-f4']],
         ['Warm Storage', data['warm-storage']],
         ['Filecoin Pay', data['filecoin-pay']],
         ['Retrieval', data['retrieval-domain']],
         ['Read-through', (data['retrieval-urls'] || []).length
           ? '応答ごとに PieceCID を再計算して照合し、一致しない bytes は破棄します。'
             + ' 参照元: ' + (data['retrieval-urls'] || []).join(' · ')
           : 'FILECOIN_PROVIDER_URL（provider の serviceURL）か'
             + ' FILECOIN_CLIENT_ADDRESS（FilBeam は client ごとに subdomain が違う）'
             + 'を設定すると有効になります。既定値は推測しません。']]);
      $('#storage-count').textContent = rows.filter(([, , k]) => k === 'live').length;
      $('#storage-source').textContent =
        `${val(data['chain-network-name'])} · height ${val(data['chain-height'])} · StateCall (無料・オンチェーン書き込みなし)`;
      $('#storage-write-notice').hidden = data['write-status'] !== 'not-implemented';
    };
    // Loaded outside bootstrapApp on purpose: bootstrapApp only runs once a
    // Passkey is enrolled, and this endpoint needs no session. Gating the load
    // behind the gate would render the panel and leave it empty forever.
    const loadFilecoin = () => fetch('/api/filecoin')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderFilecoin(d); return Boolean(d); })
      .catch(() => {
        $('#storage-source').textContent = 'Filecoin に接続できません。';
        return false;
      });
    // Contracts. Every field arrives as {status, value} rather than a bare
    // value, because a contract nobody has priced and a contract that costs
    // nothing must not render the same way.
    const fieldValue = (f) => (f && f.status === 'recorded') ? f.value : null;
    const fieldText = (f, missing = '未記録') => {
      if (!f) return missing;
      if (f.status === 'recorded') return String(f.value);
      if (f.status === 'unparseable') return '読めない値';
      return missing;
    };
    // Minor-unit exponent comes from ICU, not from an assumed 2: JPY has none,
    // and dividing a yen amount by 100 would understate every Japanese
    // subscription by two orders of magnitude.
    const minorFormat = (minor, currency) => {
      try {
        const fmt = new Intl.NumberFormat('ja-JP', {style:'currency', currency});
        const exp = fmt.resolvedOptions().maximumFractionDigits;
        return fmt.format(minor / Math.pow(10, exp));
      } catch (_) {
        return `${minor} ${currency}（最小単位）`;
      }
    };
    const money = (amount) => {
      const minor = fieldValue(amount && amount.minor);
      const currency = fieldValue(amount && amount.currency);
      if (minor === null || currency === null) return '金額は未記録';
      return minorFormat(minor, currency);
    };
    let contractsData = null;
    let selectedContract = null;
    // T1 公式 API > T2 ToS 許可済み browser > T3 self-submit — kaiyaku が選んだ
    // 安全側からの順序をそのまま表示する。app が tier を上げることはない。
    const tierLabel = (tier) => ({T1:'公式 API', T2:'ブラウザ操作（ToS 許可済み）',
                                  T3:'自分で申請'})[tier] || tier;
    const contractDetail = (c) => {
      const root = make('div');
      root.append(make('h3', null, c.title));
      const rows = make('dl', 'detail-grid');
      const row = (label, value, warn = false) => {
        rows.append(make('dt', null, label),
                    make('dd', warn ? 'state-chip state-chip--warn' : null, value));
      };
      row('プラン', fieldText(c.plan));
      row('状態', fieldText(c.status));
      row('金額', `${money(c.amount)} / ${fieldText(c.cycle, '周期未記録')}`);
      row('年額', c['annualized-minor'].status === 'recorded'
            ? minorFormat(c['annualized-minor'].value, fieldValue(c.amount.currency))
            : '計算できません（金額か周期が未記録）');
      const days = c['days-to-charge'];
      row('次回課金', c['next-charge'].status === 'recorded'
            ? `${c['next-charge'].value}（あと ${days.value} 日）`
            : '未記録');
      const deadline = c.notice && c.notice.deadline;
      const toDeadline = c.notice && c.notice['days-to-deadline'];
      if (deadline && deadline.status === 'recorded') {
        const late = toDeadline.status === 'recorded' && toDeadline.value < 0;
        row('予告期限', late
              ? `${deadline.value}（${Math.abs(toDeadline.value)} 日過ぎています）`
              : `${deadline.value}（あと ${toDeadline.value} 日）`, late);
      } else {
        row('予告期限', '予告日数が未記録のため計算できません');
      }
      root.append(rows);
      if (c.procedure) {
        const p = make('section', 'local-card');
        p.append(make('h4', null, `解約手順 · ${tierLabel(c.procedure.tier)}`));
        const steps = make('ol', 'data-list');
        (c.procedure.steps || []).forEach((s) => steps.append(make('li', null, s)));
        p.append(steps);
        if (c.procedure['notice-days'] || c.procedure['penalty-jpy']) {
          p.append(make('p', 'data-list__meta',
            `予告 ${c.procedure['notice-days']} 日 · 違約金 ${c.procedure['penalty-jpy']} 円（開示された解約コスト。回避しません）`));
        }
        // G6: the catalog holds a disclosed shape, not a live assertion. Saying
        // so on the screen is the difference between a hint and a promise.
        if (c.procedure['operator-verified'] === false) {
          p.append(make('p', 'data-list__meta',
            '※ この手順は未検証です。実行する前に提供元の記載を確認してください。'));
        }
        if (c.procedure.source) {
          const a = make('a', null, c.procedure.source);
          a.href = c.procedure.source; a.rel = 'noreferrer'; a.target = '_blank';
          p.append(a);
        }
        root.append(p);
      } else {
        root.append(make('p', 'data-list__meta',
          'この契約の解約手順はカタログにありません。手順を推測して表示することはしません。'));
      }
      if (c.problems && c.problems.length) {
        const warn = make('div', 'security-callout');
        warn.append(make('strong', null, '読めない項目があります。'),
          make('p', null, c.problems.map((p) => p.field).join(', ')));
        root.append(warn);
      }
      return root;
    };
    const renderContracts = (data) => {
      contractsData = data;
      const list = $('#contracts-list'); list.replaceChildren();
      const totals = $('#contracts-totals'); totals.replaceChildren();
      const badge = $('#contracts-count');
      // A locked or absent vault is not an empty one. Writing 0 in the badge
      // would answer a question the app cannot answer yet.
      if (!data.contracts) {
        badge.textContent = '—';
        $('#contracts-source').textContent = data.note || 'vault を読めません';
        const empty = make('li', 'empty-state');
        empty.append(make('strong', null,
            data.vault.status === 'locked' ? 'vault がロックされています'
                                           : 'この端末に vault がありません'),
          make('p', 'data-list__meta',
            data.vault.status === 'locked'
              ? 'unlock するまで契約は読めません。0 件という意味ではありません。'
              : `kagi init で作成するか、別の端末から kagi pull で復元してください（${data.vault.home}）。`));
        list.append(empty);
        $('#contracts-detail').replaceChildren(
          make('div', 'empty-state', '契約を読むには vault を開いてください。'));
        return;
      }
      badge.textContent = data.contracts.length;
      $('#contracts-source').textContent =
        `${data.vault.home} · ${data['as-of']} 時点`;
      const t = data.totals || {};
      const monthly = t['monthly-minor'] || {};
      const currencies = Object.keys(monthly);
      const card = make('div');
      card.append(make('h4', null, '毎月の合計'));
      if (currencies.length) {
        // Per currency, never summed into one number: adding JPY to USD needs
        // today's rate, and then the total depends on the day you looked.
        currencies.forEach((cur) => card.append(
          make('p', 'data-list__title', minorFormat(monthly[cur], cur))));
      } else {
        card.append(make('p', 'data-list__meta', '金額の分かる契約がありません。'));
      }
      if (t.unpriced) {
        card.append(make('p', 'data-list__meta',
          `${t.unpriced} 件は金額が未記録のため合計に含まれていません。`));
      }
      totals.append(card);
      data.contracts.forEach((c) => {
        const item = listItem(c.title,
          `${money(c.amount)} / ${fieldText(c.cycle, '周期未記録')}`,
          fieldText(c.status, '状態未記録'),
          (c.problems && c.problems.length) > 0);
        item.addEventListener('click', () => {
          selectedContract = c['item-id'];
          $('#contracts-detail').replaceChildren(contractDetail(c));
        });
        list.append(item);
      });
      if (!data.contracts.length) {
        list.append(make('li', 'empty-state', 'vault に契約 item がありません。'));
      } else if (selectedContract) {
        const found = data.contracts.find((c) => c['item-id'] === selectedContract);
        if (found) $('#contracts-detail').replaceChildren(contractDetail(found));
      }
    };
    let esignData = null;
    let selectedEnvelope = null;
    const esignStatusText = {
      'awaiting-signatures':'署名待ち', completed:'署名完了', declined:'辞退'
    };
    const esignTimeText = {
      accredited:{
        strong:'署名時刻は認定 TSA のタイムスタンプによって証明されています。',
        rest:' RFC 3161 の時刻認証局による時刻認証を受けており、電子帳簿保存法の真実性確保措置のうち'
             +'タイムスタンプの要件を満たし得ます（認定事業者の設定はこの deployment の管理者が行います）。'},
      'tsa-attested':{
        strong:'RFC 3161 タイムスタンプは検証できましたが、認定事業者としては設定されていません。',
        rest:' 時刻の証拠としては有効ですが、電子帳簿保存法が求める認定タイムスタンプに該当するとは限りません。'},
      'app-attested':{
        strong:'署名時刻はこのアプリが記録したもので、認定タイムスタンプではありません。',
        rest:' 認定タイムスタンプ（総務大臣認定の TSA による RFC 3161）を設定していない間は、'
             +'電子帳簿保存法が求める真実性の確保措置としては、この時刻だけでは足りません。'
             +'署名そのもの（誰が・何に同意したか）は、この画面を離れても検証できます。'}
    };
    const renderEsignTimeNotice = (attestation) => {
      const text = esignTimeText[attestation] || esignTimeText['app-attested'];
      const box = $('#esign-time-notice');
      if (!box) return;
      box.replaceChildren(make('strong', null, text.strong), document.createTextNode(text.rest));
    };
    const esignAssuranceText = {
      'hardware-attested':'ハードウェア（attestation 検証済み）',
      'platform-attested':'ハードウェア（AAGUID 一致）',
      'platform-claimed':'クライアントの申告のみ（未署名の主張）',
      unknown:'不明'
    };
    const envelopeDetail = (envelope) => {
      const root = make('div');
      root.append(make('h3', null, envelope['document-title'] || envelope['document-id']),
        make('p', 'data-list__meta',
          `${esignStatusText[envelope.status] || envelope.status} · ` +
          `${envelope.intent}`));
      const digests = make('div', 'local-card');
      digests.append(make('h4', null, '署名の対象'),
        make('p', 'wallet-address', `文書 digest: ${envelope['document-digest']}`),
        make('p', 'wallet-address', `表示 digest: ${envelope['presentation-digest']}`),
        make('p', 'form-help',
          'この 2 つが commitment に入り、その SHA-256 が challenge になります。' +
          '文書を後から編集しても、この envelope が指す版は変わりません。'));
      root.append(digests);
      // What you see is what you sign. The outline is exhaustive by
      // construction, so 'this document contains nothing you were not shown'
      // is a property of the format rather than a claim about this pane —
      // which is why the whole of it is rendered and not a summary.
      if (envelope.presentation) {
        const view = make('div', 'local-card');
        view.append(make('h4', null, '署名者に表示される内容（全文）'),
          make('p', 'form-help',
            '構造化データの中身をすべて 1 行ずつ列挙したものです。' +
            '折りたたまれた項目や表示範囲外のセルも含まれるため、' +
            '「見えていないものに署名する」ことが起こりません。'));
        const pre = make('pre', 'wallet-address');
        pre.textContent = envelope.presentation;
        pre.style.maxHeight = '18rem';
        pre.style.overflow = 'auto';
        pre.style.whiteSpace = 'pre-wrap';
        view.append(pre);
        root.append(view);
      } else if (envelope['content-forgotten?']) {
        root.append(make('p', 'form-help',
          '内容は削除要求により破棄されています。署名と digest は残っているため、' +
          'この digest の文書に署名がなされた事実は今も検証できます。'));
      }
      (envelope.signers || []).forEach((signer) => {
        const row = make('div', 'local-card');
        row.append(make('p', 'wallet-address', signer.did),
          make('p', 'data-list__meta',
            `${esignStatusText[signer.status] || signer.status}` +
            (signer.at ? ` · ${signer.at}` : '')));
        if (signer.assurance) {
          row.append(make('p', 'form-help',
            `鍵の保証: ${esignAssuranceText[signer.assurance] || signer.assurance}`));
        }
        if (signer.reason) row.append(make('p', 'form-help', signer.reason));
        if (signer.status === 'pending' && signer.did === esignData['my-did']) {
          const sign = make('button', 'primary-action', 'Passkey で署名');
          sign.type = 'button';
          sign.addEventListener('click', () => signEnvelope(envelope, sign));
          const decline = make('button', null, '辞退する');
          decline.type = 'button';
          decline.addEventListener('click', () => declineEnvelope(envelope, decline));
          row.append(sign, decline);
        }
        root.append(row);
      });
      const evidence = make('button', null, '証拠記録を取得');
      evidence.type = 'button';
      evidence.addEventListener('click', async () => {
        evidence.disabled = true;
        try {
          const record = await fetch(`/api/esign/envelopes/${encodeURIComponent(envelope.id)}/evidence`)
            .then((r) => r.json());
          const verified = await postJSON('/api/esign/verify', {evidence:record}, true);
          const panel = make('div', 'local-card');
          panel.append(make('h4', null, '検証結果'),
            make('p', 'data-list__title', verified['esign/status']),
            make('p', 'form-help', verified['esign/time-note']));
          (verified['esign/signatures'] || []).forEach((s) => {
            panel.append(make('p', 'wallet-address',
              `${s['signer-did']} · ${s.status}`));
            (s.reasons || []).forEach((r) => panel.append(
              make('p', 'form-help', `${r.reason}: ${r.detail}`)));
          });
          root.append(panel);
        } catch (error) {
          root.append(make('p', 'form-help', error.message));
        } finally {
          evidence.disabled = false;
        }
      });
      root.append(evidence);
      return root;
    };
    const renderEsign = (data) => {
      esignData = data;
      const list = $('#esign-list'); list.replaceChildren();
      $('#esign-count').textContent = data.envelopes.length;
      const waiting = data.envelopes.filter(
        (e) => e.status === 'awaiting-signatures').length;
      $('#esign-source').textContent =
        `${data.envelopes.length} 件 · 署名待ち ${waiting} 件`;
      // The WEAKEST attestation across every envelope shown. One envelope
      // without a qualified timestamp is one the measure does not cover, and the
      // banner is read as a statement about the screen.
      const order = {'app-attested':0, 'tsa-attested':1, accredited:2};
      renderEsignTimeNotice(
        data.envelopes.map((e) => e['time-attestation'] || 'app-attested')
          .sort((a, b) => (order[a] || 0) - (order[b] || 0))[0] || 'app-attested');
      data.envelopes.forEach((envelope) => {
        const item = listItem(envelope['document-title'] || envelope['document-id'],
          `${esignStatusText[envelope.status] || envelope.status} · ${envelope['created-at']}`,
          `${envelope['signature-count']} / ${(envelope.signers || []).length}`,
          false);
        item.addEventListener('click', () => {
          selectedEnvelope = envelope.id;
          $('#esign-detail').replaceChildren(envelopeDetail(envelope));
        });
        list.append(item);
      });
      if (!data.envelopes.length) {
        list.append(make('li', 'empty-state', '署名の依頼はまだありません。'));
      } else if (selectedEnvelope) {
        const found = data.envelopes.find((e) => e.id === selectedEnvelope);
        if (found) $('#esign-detail').replaceChildren(envelopeDetail(found));
      }
    };
    const refreshEsignDocuments = () => {
      const select = $('#esign-document');
      if (!select) return;
      const chosen = select.value;
      select.replaceChildren();
      // Only documents this Drive can actually freeze a version of. An archive
      // item has no object reference to digest, so offering one would produce a
      // request that fails after the user chose it.
      (driveData.items || [])
        .filter((item) => item.origin === 'workspace' && !item['trashed?'])
        .forEach((item) => {
          const option = make('option', null, item.name);
          option.value = item.id;
          select.append(option);
        });
      if (!select.children.length) {
        const option = make('option', null, '署名できるドキュメントがありません');
        option.value = '';
        select.append(option);
      }
      if (chosen) select.value = chosen;
    };
    const loadEsign = () => fetch('/api/esign')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) { renderEsign(d); refreshEsignDocuments(); }
                     return Boolean(d); })
      .catch(() => {
        $('#esign-source').textContent = 'envelope を読み込めません。';
        return false;
      });
    const signEnvelope = async (envelope, button) => {
      button.disabled = true; button.textContent = '署名の準備中…';
      try {
        requireWebAuthn();
        const started = await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/sign/start`, {}, true);
        button.textContent = '生体認証を待っています…';
        const credential = await navigator.credentials.get(assertionOptions(started));
        await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/sign/finish`,
          {'transaction-id':started['transaction-id'],
           credential:credentialJSON(credential)}, true);
        await loadEsign();
      } catch (error) {
        $('#esign-detail').append(make('p', 'form-help', error.message));
      } finally {
        button.disabled = false; button.textContent = 'Passkey で署名';
      }
    };
    const declineEnvelope = async (envelope, button) => {
      button.disabled = true;
      try {
        await postJSON(
          `/api/esign/envelopes/${encodeURIComponent(envelope.id)}/decline`,
          {reason:''}, true);
        await loadEsign();
      } catch (error) {
        $('#esign-detail').append(make('p', 'form-help', error.message));
      } finally {
        button.disabled = false;
      }
    };
    const loadContracts = () => fetch('/api/contracts')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderContracts(d); return Boolean(d); })
      .catch(() => {
        $('#contracts-source').textContent = '契約を読み込めません。';
        return false;
      });
    const renderProjects = (data) => {
      const list = $('#project-list'); list.replaceChildren();
      data.items.forEach((item) => list.append(listItem(
        item.title, `${item.graph || data.scope} · #${item.number}`,
        item['closed?'] ? 'Closed' : 'Open')));
      if (!data.items.length) {
        const empty = make('li', 'empty-state');
        empty.append(make('strong', null, data.message || 'Project はありません。'),
          make('p', 'data-list__meta', '接続後は Table・Board・Roadmap を同じデータから切り替えます。'));
        list.append(empty);
      }
      $('#projects-count').textContent = data.items.length;
      $('#projects-source').textContent = `${data.source} · ${data.scope}`;
      $('#projects-state').textContent = data.status === 'connected' ? '接続済み' : '権限確認が必要';
      $('#projects-state').className = `state-chip${data.status === 'connected' ? '' : ' state-chip--warn'}`;
    };
    let calendarData = {items:[], days:[]};
    let selectedDay = null;
    let selectedEvent = null;
    const renderCalendar = (data) => {
      calendarData = data;
      const days = data.days || [];
      if (!days.some((day) => day.date === selectedDay)) selectedDay = days[0]?.date || null;
      const rail = $('#calendar-days'); rail.replaceChildren();
      days.forEach((day) => {
        const date = new Date(`${day.date}T00:00:00`);
        const button = make('button', 'date-button');
        button.type = 'button';
        button.setAttribute('aria-pressed', day.date === selectedDay ? 'true' : 'false');
        button.append(make('span', null, new Intl.DateTimeFormat('ja-JP', {weekday:'short'}).format(date)),
          make('strong', null, String(date.getDate())),
          make('span', null, `${day.items.length} 件`));
        button.addEventListener('click', () => {
          selectedDay = day.date; selectedEvent = null; renderCalendar(calendarData);
        });
        rail.append(button);
      });
      const activeDay = days.find((day) => day.date === selectedDay);
      const items = activeDay?.items || [];
      if (!items.some((item) => item.id === selectedEvent?.id)) selectedEvent = items[0] || null;
      const list = $('#calendar-list'); list.replaceChildren();
      data.items.forEach((item) => {
        if (!items.some((candidate) => candidate.id === item.id)) return;
        list.append(recordButton(item, item.id === selectedEvent?.id,
          (event) => { selectedEvent = event; renderCalendar(calendarData); }, {
            title:item.title,
            time:item['all-day?'] ? '終日' : formatDate(item.start, true),
            meta:item.calendar || 'Calendar',
            snippet:item['all-day?'] ? '終日の予定' : `${formatDate(item.end, true)} まで`
          }));
      });
      if (!items.length) list.append(make('li', 'empty-state', data.message || 'この日の予定はありません。'));
      if (selectedEvent) setDetail($('#calendar-detail'), selectedEvent.calendar || 'Calendar',
        selectedEvent.title, selectedEvent['all-day?'] ? '終日の予定です。' : '時間を確保した予定です。',
        [['開始', selectedEvent['all-day?'] ? '終日' : formatDate(selectedEvent.start)],
         ['終了', selectedEvent['all-day?'] ? '終日' : formatDate(selectedEvent.end)],
         ['カレンダー', selectedEvent.calendar || 'Calendar']]);
      else $('#calendar-detail').replaceChildren(make('div', 'empty-state', 'この日の予定はありません。'));
      $('#scheduler-count').textContent = data.items.length;
      $('#calendar-source').textContent = `${data.source} · ${data.status}`;
    };
    // Appended rather than replacing: a cursor says where to continue from,
    // so a later page is more of the same list and not a different view of it.
    let driveCursor = null;
    const loadMoreDrive = async () => {
      if (!driveCursor) return;
      try {
        const request = await fetch(
          `/api/workspace/drive?cursor=${encodeURIComponent(driveCursor)}`);
        const data = await request.json();
        if (!request.ok) return;
        const seen = new Set((driveData.items || []).map((i) => i.id));
        const added = (data.items || []).filter((i) => !seen.has(i.id));
        driveCursor = data['next-cursor'] || null;
        renderDrive({...driveData, items:(driveData.items || []).concat(added),
                     'next-cursor':driveCursor});
      } catch (error) { /* the button simply stays */ }
    };
    const loadWorkspace = async (name, renderer) => {
      try {
        const request = await fetch(`/api/workspace/${name}`);
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || `${name} を読み込めませんでした。`);
        // The tree the Drive list is scoped by. Fetched here rather than
        // inside renderDrive, which runs on every keystroke in the search
        // box — that would be a request per character.
        if (name === 'drive' && !(folderData.path || []).length) await loadFolders();
        renderer(data);
        return true;
      } catch (error) {
        $(`#${name === 'scheduler' ? 'calendar' : name}-list`)?.replaceChildren(
          make('li', 'empty-state', error.message));
        return false;
      }
    };
    let organismWorkers = [];
    let selectedOrganism = null;
    let organismCursor = null;
    let organismTimer = null;
    let organismReceipts = [];
    const sendOrganismIntent = async (payload) => {
      if (!selectedOrganism) throw new Error('AO workerを選択してください。');
      return postJSON(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/intents`,
        payload, true);
    };
    const decideOrganismIntent = async (intentId, decision) => {
      if (!selectedOrganism) throw new Error('AO workerを選択してください。');
      return postJSON(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}`
          + `/intents/${encodeURIComponent(intentId)}/decision`,
        {decision}, true);
    };
    const renderOrganismReceipts = (data) => {
      organismReceipts = data.items || [];
      const decidedIntents = new Set(
        organismReceipts
          .filter((receipt) => receipt.capability === 'approval/submit'
            && receipt.parent)
          .map((receipt) => receipt.parent));
      const list = $('#organism-receipts'); list.replaceChildren();
      organismReceipts.forEach((receipt) => {
        const item = make('li', 'data-list__item');
        const copy = make('div');
        copy.append(
          make('strong', null,
            `${receipt.capability || 'intent'} · ${receipt.status || 'unknown'}`),
          make('p', 'data-list__meta',
            `${receipt.intent} · effect ${receipt['effect-status'] || 'unknown'}`
              + `${receipt.evidence?.['run-id']
                ? ` · run ${receipt.evidence['run-id']}` : ''}`));
        item.append(copy);
        if (['admitted', 'awaiting-approval'].includes(receipt.status)
            && receipt.capability === 'intent/submit'
            && !decidedIntents.has(receipt.intent)) {
          const actions = make('div', 'worker-actions');
          [['approved', '承認'], ['rejected', '拒否']].forEach(([decision, label]) => {
            const button = make('button', 'tool-button', label);
            button.type = 'button';
            button.addEventListener('click', async () => {
              button.disabled = true;
              try {
                await decideOrganismIntent(receipt.intent, decision);
                await loadOrganismReceipts();
              } catch (error) {
                $('#organism-intent-state').textContent = error.message;
                button.disabled = false;
              }
            });
            actions.append(button);
          });
          item.append(actions);
        }
        list.append(item);
      });
      if (!organismReceipts.length) {
        list.append(make('li', 'empty-state', 'intent receiptはまだありません。'));
      }
      $('#organism-receipt-state').textContent =
        `${organismReceipts.length} receipts · effect完了とは別です`;
    };
    const loadOrganismReceipts = async () => {
      if (!selectedOrganism) {
        renderOrganismReceipts({items:[]});
        return;
      }
      const request = await fetch(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/receipts`);
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'receiptを取得できません。');
      renderOrganismReceipts(data);
    };
    const renderOrganismActivity = (data, replace = false) => {
      const list = $('#organism-activity');
      if (replace) list.replaceChildren();
      (data.items || []).forEach((activity) => {
        const item = make('li', 'data-list__item');
        item.append(
          make('span', 'data-list__meta', formatDate(activity.at, true)),
          make('strong', null, String(activity.kind || 'system')),
          make('span', 'data-list__meta',
            String(activity.run || activity.parent || activity.stream || 'system')));
        list.append(item);
      });
      while (list.children.length > 100) list.firstElementChild.remove();
      organismCursor = data.cursor || organismCursor;
      $('#organism-activity-state').textContent =
        `${list.children.length} events · cursor ${String(organismCursor || '—').slice(-10)}`;
      $('#organism-live-state').textContent =
        (data.items || []).length ? 'live' : 'waiting';
      $('#organism-live-state').className =
        `state-chip${(data.items || []).length ? ' state-chip--run' : ''}`;
    };
    const loadOrganismActivity = async (replace = false) => {
      if (!selectedOrganism) return;
      const params = new URLSearchParams({limit:'100'});
      if (!replace && organismCursor) params.set('cursor', organismCursor);
      const request = await fetch(
        `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/activity?${params}`);
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'activityを取得できません。');
      renderOrganismActivity(data, replace);
    };
    const renderOrganismDetail = async () => {
      const target = $('#organism-detail');
      if (!selectedOrganism) {
        target.replaceChildren(make('div', 'empty-state',
          'active organization にAO workerはありません。'));
        return;
      }
      target.replaceChildren(make('div', 'skeleton'));
      try {
        const request = await fetch(
          `/api/organism-workers/${encodeURIComponent(selectedOrganism.id)}/snapshot`);
        const snapshot = await request.json();
        if (!request.ok) throw new Error(snapshot?.error?.message || 'snapshotを取得できません。');
        const worker = snapshot.worker || selectedOrganism;
        setDetail(target, worker.organization || 'Organization',
          worker.id, '外部supervisorで稼働するrepository-bound AOです。',
          [['状態', worker.status || 'unknown'],
           ['Repository', worker.repository || '—'],
           ['Runtime', worker.runtime || '—'],
           ['Event authority', snapshot.connection?.['event-authority'] || '—'],
           ['Recent activity', String(snapshot.activity?.recent || 0)],
           ['AgentRuns', String(snapshot.activity?.['agent-runs'] || 0)]]);
      } catch (error) {
        target.replaceChildren(make('div', 'empty-state', error.message));
      }
    };
    const renderOrganismDirectory = (data) => {
      organismWorkers = data.items || [];
      if (!organismWorkers.some((item) => item.id === selectedOrganism?.id)) {
        selectedOrganism = organismWorkers[0] || null;
        organismCursor = null;
      }
      const list = $('#organism-list'); list.replaceChildren();
      organismWorkers.forEach((worker) => {
        list.append(recordButton(worker, worker.id === selectedOrganism?.id,
          (selected) => {
            selectedOrganism = selected; organismCursor = null;
            renderOrganismDirectory(data);
          }, {title:worker.id, time:worker.status || 'unknown',
              meta:worker.repository || 'repository',
              snippet:`${worker.runtime} · ${(worker.capabilities || []).length} capabilities`}));
      });
      if (!organismWorkers.length) {
        list.append(make('li', 'empty-state',
          'このOrganizationに割り当てられたAO workerはありません。'));
      }
      $('#organism-count').textContent = organismWorkers.length;
      $('#organism-source').textContent =
        `${data.organization || 'organization'} · ${organismWorkers.length} AO`;
      renderOrganismDetail();
      loadOrganismActivity(true).catch((error) => {
        $('#organism-activity-state').textContent = error.message;
      });
      loadOrganismReceipts().catch((error) => {
        $('#organism-receipt-state').textContent = error.message;
      });
      scheduleOrganismPoll();
    };
    const loadOrganisms = async () => {
      const request = await fetch('/api/organism-workers');
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || 'AO workerを読み込めません。');
      renderOrganismDirectory(data);
    };
    const scheduleOrganismPoll = () => {
      if (organismTimer) { clearTimeout(organismTimer); organismTimer = null; }
      if (!appUnlocked || currentView !== 'organisms' || !selectedOrganism) return;
      organismTimer = setTimeout(async () => {
        try {
          await Promise.all([
            loadOrganismActivity(false),
            loadOrganismReceipts()
          ]);
        }
        finally { scheduleOrganismPoll(); }
      }, 2000);
    };
    $('#organism-intent-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organism-intent-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = 'admit中…';
      try {
        const receipt = await sendOrganismIntent({
          capability:'intent/submit',
          type:'objective',
          summary:fields.summary
        });
        event.currentTarget.reset();
        $('#organism-intent-state').textContent =
          `${receipt.intent} をadmitしました。effectは未実行です。`;
        await loadOrganismReceipts();
      } catch (error) {
        $('#organism-intent-state').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = 'Tamaki inboxへ送る';
      }
    });
    $('#organism-stop').addEventListener('click', async () => {
      const button = $('#organism-stop');
      button.disabled = true;
      try {
        const receipt = await sendOrganismIntent({
          capability:'stop/request',
          type:'stop',
          summary:'Human operator requested a governed stop.'
        });
        $('#organism-intent-state').textContent =
          `${receipt.intent} のstop requestをadmitしました。`;
        await loadOrganismReceipts();
      } catch (error) {
        $('#organism-intent-state').textContent = error.message;
      } finally { button.disabled = false; }
    });
    const bootstrapApp = () => {
      if (appBootstrapped) return;
      appBootstrapped = true;
      loadSession();
      loadWorkspace('worker', renderWorker);
      loadOrganisms().catch((error) => {
        $('#organism-list').replaceChildren(make('li', 'empty-state', error.message));
      });
      Promise.all([
        loadWorkspace('inbox', renderInbox),
        loadWorkspace('projects', renderProjects),
        loadWorkspace('drive', renderDrive),
        loadWorkspace('scheduler', renderCalendar),
        // Inside bootstrapApp, unlike loadFilecoin: this endpoint decrypts vault
        // items behind a session, and every reveal writes a line into kagi's
        // audit ledger. Loading it before a Passkey exists would put unattributed
        // reveals in the ledger of a user who has not logged in yet.
        loadContracts(),
        // After loadWorkspace('drive', …) has run, because the request form's
        // document list is built from what that loaded. Promise.all does not
        // order these, so `refreshEsignDocuments` is also called on every
        // subsequent `loadEsign` rather than only here.
        loadEsign(),
        loadFleet(),
        loadOperator(),
        // Inside bootstrapApp, not beside loadFilecoin: a business belongs to an
        // organization and the portfolio is that organization's own record, so
        // it needs the session that loadFilecoin's public chain reads do not.
        loadPortfolio()
      ]).then((results) => {
        const connected = results.filter(Boolean).length;
        $('#workspace-status').textContent = `${connected} / ${results.length} サービス接続`;
      });
    };
    $('#esign-request-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = event.submitter;
      const status = $('#esign-request-status');
      button.disabled = true; button.textContent = '依頼を作成中…';
      try {
        const dids = $('#esign-signers').value.split('\\n')
          .map((line) => line.trim()).filter(Boolean);
        if (!dids.length) throw new Error('署名者の DID を 1 件以上入力してください。');
        if (!$('#esign-document').value) throw new Error('ドキュメントを選んでください。');
        await postJSON('/api/esign/envelopes', {
          'document-id':$('#esign-document').value,
          purpose:$('#esign-purpose').value,
          'signer-dids':dids
        }, true);
        $('#esign-signers').value = '';
        status.textContent = '署名を依頼しました。';
        await loadEsign();
      } catch (error) {
        status.textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = '署名を依頼する';
      }
    });

    // ── Fleet directory + 事業者としての参与 ──────────────────────────
    //
    // Two rules this code exists to hold, both of which are easy to violate
    // by accident in a renderer:
    //   1. a licence is 自己表明 and is never drawn as verified;
    //   2. a blueprint without :deploy-config gets no deploy affordance —
    //      the text says the operator builds it, and there is no button.
    const reqLabel = {maturity:'成熟度', governor:'governor', licence:'許認可',
      technologies:'必要技術', 'deploy-path':'deploy 経路'};
    const stateLabel = {met:'充足', unmet:'未充足', attested:'自己表明',
      absent:'同梱なし', none:'宣言なし', unknown:'不明'};
    const stateTone = {met:'ok', unmet:'warn', attested:'note',
      absent:'note', none:'note', unknown:'note'};
    let fleetFacets = null;

    const fillSelect = (id, pairs) => {
      const el = $('#' + id); if (!el || !pairs) return;
      const keep = el.value;
      el.replaceChildren(make('option', null, 'すべて'));
      el.firstChild.value = '';
      pairs.forEach(([v, n]) => {
        const o = make('option', null, `${v} (${n})`);
        o.value = String(v).replace(/^:/, '');
        el.append(o);
      });
      el.value = keep;
    };

    // Its own class rather than .data-list__item: that grid is
    // minmax(0,1fr) auto for a two-child row, and a requirement carries four
    // things (label, state, reason, and sometimes a caveat that must never be
    // dropped). Reusing the grid put the reason on top of the label.
    const requirementRow = (r) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, reqLabel[String(r.requirement).replace(/^:/,'')]
        || String(r.requirement)));
      // Not .nav-badge: that class is sized for the sidebar counter and
      // squeezes a word like 「同梱なし」 to 24px.
      const badge = make('span', 'req-row__state',
        stateLabel[String(r.state).replace(/^:/,'')] || String(r.state));
      badge.dataset.tone = stateTone[String(r.state).replace(/^:/,'')] || 'note';
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', r.detail || ''));
      // The caveat rides with the requirement, so a licence row cannot be
      // rendered anywhere without it.
      if (r.caveat) li.append(make('p', 'req-row__caveat', r.caveat));
      return li;
    };

    const fleetDetail = (repo) => fetch('/api/operator/readiness/' + encodeURIComponent(repo))
      .then((r) => r.ok ? r.json() : null)
      .then((d) => {
        const box = $('#fleet-detail'); if (!box) return;
        if (!d) { box.replaceChildren(make('div', 'empty-state', '読み込めません。')); return; }
        const a = d.actor || {};
        box.replaceChildren();
        box.append(make('h3', null, a.name || a.repo));
        box.append(make('p', 'data-list__meta',
          [a.repo, a.domain, a.role, a.maturity].filter(Boolean).join(' · ')));
        if (a.endpoint) {
          box.append(make('p', 'data-list__meta', '稼働中: ' + a.endpoint));
        }
        box.append(make('h4', null, '運用に必要なもの'));
        const ul = make('ul', 'record-list__items');
        (d.requirements || []).forEach((r) => ul.append(requirementRow(r)));
        box.append(ul);
        const ad = d.adoption;
        box.append(make('p', 'form-help', ad
          ? `参与: ${String(ad.stage).replace(/^:/,'')}（${ad['declared-by']} / ${ad['declared-on']}）`
          : 'まだ参与を表明していません。事業者タブから表明できます。'));
      })
      .catch(() => {});

    const renderFleet = (data) => {
      const list = $('#fleet-list'); if (!list) return;
      list.replaceChildren();
      (data.actors || []).forEach((a) => {
        const fit = a.fit && a.fit.score ? ` · 適合 ${a.fit.score}` : '';
        const item = listItem(a.name || a.repo,
          [a.role, a.domain, a.maturity].filter(Boolean).join(' · ') + fit,
          a.endpoint ? '稼働' : (a['deploy-config'] ? 'deploy可' : ''));
        item.addEventListener('click', () => fleetDetail(a.repo));
        list.append(item);
      });
      if (!(data.actors || []).length) {
        list.append(make('li', 'empty-state', '該当する blueprint がありません。'));
      }
      // Say the truncation out loud. A directory that quietly shows 200 of
      // 1,213 reads as a complete answer.
      const fs = $('#fleet-source');
      if (fs) fs.textContent = data.total > data.shown
        ? `${data.total} 件中 ${data.shown} 件を表示`
        : `${data.total} 件`;
      // Guarded like every other lookup in these two renderers: the badge
      // lives in the sidebar, and a renderer that assumes its own chrome is
      // present cannot be reused anywhere the chrome is not.
      const fc = $('#fleet-count'); if (fc) fc.textContent = data.total;
    };

    const searchFleet = () => {
      const q = new URLSearchParams();
      const v = (id) => ($('#' + id)?.value || '').trim();
      if (v('fleet-text')) q.set('text', v('fleet-text'));
      if (v('fleet-role')) q.set('role', v('fleet-role'));
      if (v('fleet-maturity')) q.set('maturity', v('fleet-maturity'));
      if (v('fleet-iso3166')) q.set('iso3166', v('fleet-iso3166'));
      if ($('#fleet-callable')?.checked) q.set('callable', 'true');
      return fetch('/api/fleet/search?' + q.toString())
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { if (d) renderFleet(d); return Boolean(d); })
        .catch(() => { $('#fleet-source').textContent = 'catalog を読み込めません。'; return false; });
    };

    const loadFleet = () => fetch('/api/fleet')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => {
        if (!d) return false;
        fleetFacets = d.facets;
        fillSelect('fleet-role', d.facets.role);
        fillSelect('fleet-maturity', d.facets.maturity);
        fillSelect('fleet-iso3166', d.facets.iso3166);
        return searchFleet();
      })
      .catch(() => false);

    const statTile = (label, value) => {
      const d = make('div', 'stat-tile');
      d.append(make('span', 'stat-tile__label', label));
      d.append(make('strong', 'stat-tile__value', String(value)));
      return d;
    };

    const renderOperator = (d) => {
      const s = d.summary || {};
      const stats = $('#operator-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('参与', s.adoptions || 0),
          statTile('稼働', s.deployed || 0),
          statTile('fleet 全体', (s.fleet && s.fleet.actors) || 0),
          statTile('fleet の稼働', (s.fleet && s.fleet.callable) || 0));
      }
      const oc = $('#operator-count'); if (oc) oc.textContent = s.adoptions || 0;
      const os_ = $('#operator-source');
      if (os_) os_.textContent = d.profile
        ? `${d.profile.name} として参与しています`
        : '事業者プロファイルが未登録です。';
      const cv = $('#operator-licence-caveat'); if (cv && d.caveat) cv.textContent = d.caveat;

      if (d.profile) {
        const p = d.profile;
        const setv = (id, val) => { const e = $('#' + id); if (e && !e.value) e.value = val; };
        setv('operator-name', p.name || '');
        setv('operator-isic', (p.isic || []).join(', '));
        setv('operator-isco', (p.isco || []).join(', '));
        setv('operator-iso3166', (p.iso3166 || []).join(', '));
      }

      const m = $('#operator-matches');
      if (m) {
        m.replaceChildren();
        (d.matches || []).forEach((a) => {
          const item = listItem(a.name || a.repo,
            [a.role, a.domain, a.maturity].filter(Boolean).join(' · '),
            `適合 ${a.fit?.score ?? 0}`);
          item.addEventListener('click', () => {
            document.querySelector('[data-view=fleet]')?.click();
            fleetDetail(a.repo);
          });
          m.append(item);
        });
        if (!(d.matches || []).length) {
          m.append(make('li', 'empty-state',
            d.profile ? '適合する blueprint がありません。業種・職種・管轄を確認してください。'
                      : 'プロファイルを保存すると表示します。'));
        }
      }

      const ad = $('#operator-adoptions');
      if (ad) {
        ad.replaceChildren();
        (d.adoptions || []).forEach((a) => {
          const stage = String(a.stage).replace(/^:/, '');
          const item = listItem(a.repo,
            `${a['declared-by']} · ${a['declared-on']}`,
            stage, stage === 'withdrawn');
          item.addEventListener('click', () => {
            document.querySelector('[data-view=fleet]')?.click();
            fleetDetail(a.repo);
          });
          ad.append(item);
        });
        if (!(d.adoptions || []).length) {
          ad.append(make('li', 'empty-state', 'まだ参与を表明していません。'));
        }
      }
    };

    const loadOperator = () => fetch('/api/operator')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderOperator(d); return Boolean(d); })
      .catch(() => { $('#operator-source').textContent = '事業者プロファイルを読み込めません。'; return false; });

    const splitList = (id) => ($('#' + id)?.value || '')
      .split(',').map((x) => x.trim()).filter(Boolean);

    // ── Portfolio（事業の面）──────────────────────────────────────────
    //
    // ADR-2607309600. The one thing this renderer must never do is draw an
    // absent plane as an empty one. `unresolvable`（workspace checkout が未設定）
    // と `missing`（設定済みで、その canvas / repo が無い）は別の事実で、直し方
    // も別 — 前者は設定、後者は checkout か紐付けの誤り。両方を「0件」に畳むと、
    // 何も測っていない状態が「測って空だった」ように読める。
    //
    // WIRE CONTRACT, easy to get wrong in both directions:
    // `clojure.data.json/write-str` drops the NAMESPACE of a keyword key, so
    // `:business/id` arrives as `id` and `:adoption/repo` as `repo`. Reading
    // `b['business/id']` yields undefined — silently, and it renders as an empty
    // row rather than an error. (The document panes read `d['docs/blocks']` and
    // are fine only because those keys are STRINGS server-side and pass through
    // untouched; never copy that shape onto a keyword-keyed payload.) Keyword
    // VALUES lose their namespace and colon the same way, which is why `bare`
    // strips a leading colon defensively instead of assuming either form.
    const bare = (v) => String(v ?? '').replace(/^:/, '');
    const faceStateLabel = {resolved:'解決', partial:'一部解決', unbound:'未紐付け',
      unresolvable:'解析不能', missing:'不在', unreadable:'読取不能'};
    // `missing`/`unreadable` だけが warn。`unresolvable` と `unbound` は
    // アプリが決められないことなので、警告にはしない（operator の
    // blocking-states と同じ線引き）。
    const faceStateTone = {resolved:'ok', partial:'note', unbound:'note',
      unresolvable:'note', missing:'warn', unreadable:'warn'};
    const faceRow = (f) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, f.label || bare(f.face)));
      const badge = make('span', 'req-row__state',
        faceStateLabel[bare(f.state)] || bare(f.state));
      badge.dataset.tone = faceStateTone[bare(f.state)] || 'note';
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', f.detail || ''));
      // An unresolved face names the key it looked for and the file it looked
      // in, so the reader can act on it instead of only knowing it failed.
      const key = Array.isArray(f.key) ? f.key.join(' / ') : bare(f.key);
      if (key) li.append(make('p', 'req-row__caveat', `${key} — ${f.source || ''}`));
      return li;
    };

    let selectedBusinessId = null;
    let reposBusinessId = null;
    let metricsBusinessId = null;

    const renderBusinessDetail = (b) => {
      const box = $('#portfolio-detail'); if (!box) return;
      const card = $('#portfolio-bind-card');
      if (!b) {
        box.replaceChildren(make('div', 'empty-state', '事業を選ぶと、5面の状態を表示します。'));
        if (card) card.hidden = true;
        return;
      }
      const c = b.coverage || {};
      box.replaceChildren();
      box.append(make('p', 'record-detail__eyebrow', b.slug || ''));
      box.append(make('h3', null, b.name || b.slug || ''));
      // Two numbers, not one score: 紐付け数 is what the owner declared and
      // 解決数 is what the workspace can confirm. A single percentage would
      // hide which of the two is missing.
      box.append(make('p', 'record-detail__body',
        `${c.bound ?? 0}/${c.faces ?? 0} 面を紐付け · ${c.resolved ?? 0} 面を解決`
        + (c.unresolvable ? ` · ${c.unresolvable} 面は workspace 未設定のため解析不能` : '')));
      if (b.note) box.append(make('p', 'req-row__caveat', b.note));
      const list = make('ul', 'record-list__items');
      (b.faces || []).forEach((f) => list.append(faceRow(f)));
      box.append(list);
      if (card) {
        card.hidden = false;
        const set = (id, v) => { const e = $('#' + id); if (e) e.value = v || ''; };
        set('portfolio-bind-canvas', bare(b.canvas));
        set('portfolio-bind-model', b.model);
        set('portfolio-bind-leverage', b.leverage);
        set('portfolio-bind-adoptions', (b.adoptions || []).join(', '));
        set('portfolio-bind-repos', (b.repos || []).join(', '));
        set('portfolio-bind-lei', b.lei);
        const st = $('#portfolio-bind-status'); if (st) st.textContent = '';
      }
    };

    const renderPortfolio = (d) => {
      const rows = d.businesses || [];
      const counts = d.counts || {};
      const ws = d.workspace || {};

      const stats = $('#portfolio-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('事業', counts.businesses || 0),
          statTile('5面すべて解決', counts['fully-resolved'] || 0),
          statTile('未割当の参与', counts['unassigned-adoptions'] || 0));
      }
      const badge = $('#portfolio-count');
      if (badge) badge.textContent = counts.businesses || 0;
      const src = $('#portfolio-source');
      if (src) src.textContent = rows.length
        ? `${rows.length} 件の事業`
        : '事業がまだありません。';

      // The workspace notice is the difference between 「測って空だった」 and
      // 「どこを見るか誰も言っていない」, so it always states which one it is.
      const notice = $('#portfolio-workspace');
      if (notice) {
        notice.replaceChildren();
        if (bare(ws.state) === 'present') {
          notice.append(make('strong', null, 'workspace checkout: '), ws.root || '');
        } else {
          notice.append(make('strong', null,
            bare(ws.state) === 'missing' ? 'workspace checkout が見つかりません。'
                                         : 'workspace checkout が未設定です。'));
          notice.append(document.createTextNode(' ' + (ws.detail || '')));
        }
      }

      const list = $('#portfolio-list');
      if (list) {
        list.replaceChildren();
        rows.forEach((b) => {
          const c = b.coverage || {};
          const item = listItem(b.name || b.slug,
            `${c.bound ?? 0}/${c.faces ?? 0} 面を紐付け`,
            `解決 ${c.resolved ?? 0}`,
            (c.resolved ?? 0) === 0);
          item.addEventListener('click', () => {
            selectedBusinessId = b.id;
            renderBusinessDetail(b);
          });
          list.append(item);
        });
        if (!rows.length) {
          list.append(make('li', 'empty-state', 'まだ事業がありません。'));
        }
      }
      renderBusinessDetail(rows.find((b) => b.id === selectedBusinessId));
      fillCanvasBusinesses(rows);
      fillLoopsBusinesses(rows);
      fillBusinessSelect('repos-business', rows, loadRepos, reposBusinessId);
      fillBusinessSelect('metrics-business', rows, loadMetrics, metricsBusinessId);

      const un = $('#portfolio-unassigned');
      if (un) {
        un.replaceChildren();
        const items = (d.unassigned && d.unassigned.adoptions) || [];
        items.forEach((a) => {
          un.append(listItem(a.repo,
            `${a['declared-by'] || ''} · ${a['declared-on'] || ''}`,
            bare(a.stage)));
        });
        if (!items.length) {
          un.append(make('li', 'empty-state', '未割当の参与はありません。'));
        }
      }
      const cav = $('#portfolio-unassigned-caveat');
      if (cav && d.unassigned && d.unassigned.caveat) {
        cav.textContent = '参与を表明したが、どの事業にも紐付いていない blueprint です。'
          + d.unassigned.caveat;
      }
    };

    const loadPortfolio = () => fetch('/api/business')
      .then((r) => r.ok ? r.json() : null)
      .then((d) => { if (d) renderPortfolio(d); return Boolean(d); })
      .catch(() => {
        const src = $('#portfolio-source');
        if (src) src.textContent = '事業を読み込めません。';
        return false;
      });

    $('#portfolio-create-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#portfolio-create-status');
      fetch('/api/business', {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          slug: ($('#portfolio-slug')?.value || '').trim(),
          name: ($('#portfolio-name')?.value || '').trim(),
          note: ($('#portfolio-note')?.value || '').trim()
        })
      }).then(async (r) => {
        const body = await r.json().catch(() => null);
        if (!r.ok) {
          // The server's own refusal text, not a generic one: slug-taken and
          // slug-invalid need different corrections.
          if (status) status.textContent = body?.error?.message
            || body?.error?.type || `保存できません (${r.status})`;
          return;
        }
        if (status) status.textContent = '追加しました。';
        ['portfolio-slug','portfolio-name','portfolio-note'].forEach((id) => {
          const e2 = $('#' + id); if (e2) e2.value = '';
        });
        selectedBusinessId = body && body.id;
        return loadPortfolio();
      }).catch(() => { if (status) status.textContent = '保存できません。'; });
    });

    // ── Canvas（事業の仮説）────────────────────────────────────────────
    //
    // Two things this renderer must not do. It must not draw a canvas it could
    // not read as an empty canvas — `unresolvable` / `missing` / `unreadable`
    // each say what to fix. And it must not draw a proposal as landed on the
    // strength of having recorded it: the state comes back from the server,
    // which derived it by reading the projection.
    const canvasStateLabel = {resolved:'読み込み済み', unbound:'canvas 未紐付け',
      unresolvable:'解析不能', missing:'投影が無い', unreadable:'読取不能'};
    const proposalStateLabel = {'awaiting-governor':'governor 待ち', landed:'着地',
      unverifiable:'判定不能', withdrawn:'取り下げ'};
    const proposalStateTone = {'awaiting-governor':'note', landed:'ok',
      unverifiable:'note', withdrawn:'note'};
    let canvasData = null;

    const canvasBlockCard = (b) => {
      const d = make('div', 'canvas-block');
      d.append(make('p', 'canvas-block__label', b.label || bare(b.block)));
      const items = b.items || [];
      if (items.length) {
        const ul = make('ul', 'canvas-block__items');
        items.forEach((i) => ul.append(make('li', null, i)));
        d.append(ul);
      } else {
        // An empty block is a real state in a lean canvas — say so rather than
        // rendering a card with nothing in it.
        d.append(make('p', 'canvas-block__empty', 'item がありません'));
      }
      if (b.note) d.append(make('p', 'canvas-block__note', b.note));
      return d;
    };

    const hypothesisRow = (h, riskiest) => {
      const li = make('li', 'req-row');
      if (bare(h.id) === bare(riskiest)) li.classList.add('req-row--risk');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, bare(h.id)
        + (bare(h.risk) === 'riskiest' ? '  最も危険な仮説' : '')));
      // Two chips on purpose: :hyp/status is what the ledger says, :gate/status
      // is what the metrics say, and one of them moving without the other is
      // the interesting case.
      const badge = make('span', 'req-row__state', bare(h.status));
      badge.dataset.tone = bare(h.status) === 'validated' ? 'ok'
        : (bare(h.status) === 'refuted' ? 'warn' : 'note');
      head.append(badge);
      li.append(head);
      li.append(make('p', 'req-row__detail', h.claim || ''));
      if (h.gate) li.append(make('p', 'req-row__caveat', 'gate: ' + h.gate));
      const gs = bare(h['gate-status'] ?? '');
      if (gs) {
        const line = make('p', 'req-row__caveat',
          `測定: ${gs}` + (h['gate-distance'] ? ` — ${h['gate-distance']}` : '')
          + (h['gate-evidence'] ? ` — ${h['gate-evidence']}` : ''));
        li.append(line);
      } else {
        li.append(make('p', 'req-row__caveat', '測定: 未測定（gate spec か metrics がありません）'));
      }
      if (h.evidence) li.append(make('p', 'req-row__caveat', '根拠: ' + h.evidence));
      return li;
    };

    const renderCanvas = (d) => {
      canvasData = d;
      const c = (d && d.canvas) || {};
      const state = bare(c.state);
      const note = $('#canvas-state');
      if (note) {
        note.replaceChildren();
        note.append(make('strong', null, (canvasStateLabel[state] || state) + ' '));
        if (c.detail) note.append(document.createTextNode(c.detail));
        if (state === 'resolved') {
          note.append(document.createTextNode(
            `投影 ${c.source || ''}（as-of ${c['as-of'] || '不明'}）`));
          // A projection whose file disagrees with its own header is truncated;
          // saying so beats rendering eight of nine blocks as the canvas.
          if (c.counts && c.counts['complete?'] === false) {
            note.append(make('strong', null,
              ` 投影が宣言した件数と一致しません（block ${c.counts.blocks}/${c.counts['declared-blocks']}）`));
          }
        }
      }
      const meta = $('#canvas-meta');
      if (meta) meta.textContent = state === 'resolved'
        ? `${(c.blocks || []).length} block · ${(c.hypotheses || []).length} 仮説`
        : '';
      const src = $('#canvas-source');
      if (src) src.textContent = d && d.business
        ? `${d.business.name || d.business.slug}${c.product ? ' → ' + bare(c.product) : ''}`
        : '事業を選択してください。';
      const auth = $('#canvas-authority');
      if (auth && d && d.authority) auth.textContent = d.authority;

      const grid = $('#canvas-blocks');
      if (grid) {
        grid.replaceChildren();
        (c.blocks || []).forEach((b) => grid.append(canvasBlockCard(b)));
      }

      const hs = $('#canvas-hypotheses');
      if (hs) {
        hs.replaceChildren();
        (c.hypotheses || []).forEach((h) => hs.append(hypothesisRow(h, c['riskiest-hyp'])));
        if (!(c.hypotheses || []).length) {
          hs.append(make('li', 'empty-state',
            state === 'resolved' ? '仮説が登録されていません。' : 'canvas を読み込めていません。'));
        }
      }

      // The block select is filled from the projection, so a proposal cannot
      // name a block that does not exist. With no projection there is nothing
      // honest to offer, and the form stays hidden.
      const card = $('#canvas-propose-card');
      const blockSelect = $('#canvas-propose-block');
      if (blockSelect) {
        const keep = blockSelect.value;
        blockSelect.replaceChildren(make('option', null, 'block を選択…'));
        blockSelect.firstChild.value = '';
        (c.blocks || []).forEach((b) => {
          const o = make('option', null, `${b.label || bare(b.block)} (${bare(b.id)})`);
          o.value = bare(b.id);
          blockSelect.append(o);
        });
        blockSelect.value = keep;
      }
      if (card) card.hidden = state !== 'resolved';

      const list = $('#canvas-proposals');
      if (list) {
        list.replaceChildren();
        (d?.proposals || []).forEach((p) => {
          const st = bare(p.state);
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null,
            `${bare(p.action)} ${bare(p['canvas-id'])}`));
          const badge = make('span', 'req-row__state', proposalStateLabel[st] || st);
          badge.dataset.tone = proposalStateTone[st] || 'note';
          head.append(badge);
          li.append(head);
          li.append(make('p', 'req-row__detail', p.value || ''));
          li.append(make('p', 'req-row__caveat',
            `${p['proposed-by'] || ''} · ${p['proposed-at'] || ''}`
            + (p.reason ? ` · ${p.reason}` : '')));
          // The exact command, because there is no Apply button this app could
          // honestly offer.
          if (st !== 'landed' && st !== 'withdrawn' && p.command) {
            li.append(make('p', 'req-row__caveat', p.command));
            const drop = make('button', 'tool-button', '取り下げる');
            drop.type = 'button';
            drop.addEventListener('click', () => withdrawProposal(p.id));
            li.append(drop);
          }
          list.append(li);
        });
        if (!(d?.proposals || []).length) {
          list.append(make('li', 'empty-state', 'まだ提案はありません。'));
        }
      }
      const badge = $('#canvas-count');
      if (badge) {
        const awaiting = (d?.proposals || [])
          .filter((p) => bare(p.state) === 'awaiting-governor').length;
        badge.textContent = awaiting;
      }
    };

    const loadCanvas = (businessId) => {
      if (!businessId) { renderCanvas(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/canvas`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { renderCanvas(d); return Boolean(d); })
        .catch(() => {
          const src = $('#canvas-source');
          if (src) src.textContent = 'canvas を読み込めません。';
          return false;
        });
    };

    const withdrawProposal = (proposalId) => {
      const businessId = $('#canvas-business')?.value;
      if (!businessId || !proposalId) return;
      fetch(`/api/business/${encodeURIComponent(businessId)}/canvas/proposals/`
            + `${encodeURIComponent(proposalId)}/withdraw`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({by: ($('#canvas-propose-by')?.value || '').trim()})
      }).then(() => loadCanvas(businessId))
        .catch(() => {
          const st = $('#canvas-propose-status');
          if (st) st.textContent = '取り下げられません。';
        });
    };

    // Filled from the portfolio, so the two panes cannot disagree about which
    // businesses exist.
    const fillCanvasBusinesses = (rows) => {
      const sel = $('#canvas-business'); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== canvasData?.business?.id) loadCanvas(next);
      if (!next) renderCanvas(null);
    };

    $('#canvas-business')?.addEventListener('change', (e) => {
      selectedBusinessId = e.currentTarget.value || selectedBusinessId;
      loadCanvas(e.currentTarget.value);
    });

    $('#canvas-propose-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#canvas-propose-status');
      const businessId = $('#canvas-business')?.value;
      if (!businessId) { if (status) status.textContent = '事業を選んでください。'; return; }
      fetch(`/api/business/${encodeURIComponent(businessId)}/canvas/propose`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          action: ($('#canvas-propose-action')?.value || '').trim(),
          'canvas-id': ($('#canvas-propose-block')?.value || '').trim(),
          value: ($('#canvas-propose-value')?.value || '').trim(),
          reason: ($('#canvas-propose-reason')?.value || '').trim(),
          by: ($('#canvas-propose-by')?.value || '').trim()
        })
      }).then(async (r) => {
        const body = await r.json().catch(() => null);
        if (!r.ok) {
          if (status) status.textContent = body?.error?.message
            || body?.error?.type || `記録できません (${r.status})`;
          return;
        }
        if (status) status.textContent = '提案を記録しました。ledger へは governor が入れます。';
        const v = $('#canvas-propose-value'); if (v) v.value = '';
        return loadCanvas(businessId);
      }).catch(() => { if (status) status.textContent = '記録できません。'; });
    });

    // ── Loops（stock-flow 構造とシミュレーション）──────────────────────
    //
    // Small multiples rather than one multi-series chart, because XMILE
    // variables carry their own units: a stock in `repos` and a flow in
    // `repos/day` on one y-axis is the dual-axis mistake. One panel per
    // variable, its own scale, units named — which also means one series per
    // panel, so there is no legend to omit and no categorical palette to get
    // wrong. Kind is carried by colour AND by the text under the name, never by
    // colour alone.
    const svgEl = (tag, attrs) => {
      const n = document.createElementNS('http://www.w3.org/2000/svg', tag);
      Object.entries(attrs || {}).forEach(([k, v]) => n.setAttribute(k, String(v)));
      return n;
    };
    const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);

    const sparkline = (values) => {
      const pts = (values || []).map(num);
      const known = pts.filter((v) => v !== null);
      const svg = svgEl('svg', {class:'sm__plot', viewBox:'0 0 100 40',
                                preserveAspectRatio:'none', role:'img'});
      if (known.length < 2) {
        // One point is not a trajectory. Saying so beats drawing a flat line
        // that looks like a simulated constant.
        svg.append(svgEl('line', {class:'sm__axis', x1:0, y1:39, x2:100, y2:39}));
        const t = svgEl('text', {class:'sm__flat', x:2, y:22});
        t.textContent = '系列が短すぎて描けません';
        svg.append(t);
        return svg;
      }
      const lo = Math.min(...known), hi = Math.max(...known);
      const span = hi - lo;
      // A constant series is real (a stock nothing drains). Drawn on the
      // midline, and the panel's own meta line reports the value, so a flat
      // line is never mistaken for a missing one.
      const y = (v) => (span === 0 ? 20 : 38 - ((v - lo) / span) * 36);
      const x = (i) => (i / (pts.length - 1)) * 100;
      let d = '', open = false;
      pts.forEach((v, i) => {
        if (v === null) { open = false; return; }
        d += `${open ? 'L' : 'M'}${x(i).toFixed(2)},${y(v).toFixed(2)} `;
        open = true;
      });
      svg.append(svgEl('line', {class:'sm__axis', x1:0, y1:39, x2:100, y2:39}));
      svg.append(svgEl('path', {class:'sm__line', d: d.trim(),
                                'vector-effect':'non-scaling-stroke'}));
      return svg;
    };

    const smPanel = (name, kind, units, values) => {
      const d = make('div', 'sm');
      d.dataset.kind = String(kind || '').replace(/^:/, '');
      d.append(make('p', 'sm__name', name));
      const known = (values || []).map(num).filter((v) => v !== null);
      const range = known.length
        ? `${known[0].toPrecision(4)} → ${known[known.length - 1].toPrecision(4)}`
        : '値なし';
      d.append(make('p', 'sm__meta',
        `${d.dataset.kind}${units ? ' · ' + units : ''} · ${range}`));
      d.append(sparkline(values));
      return d;
    };

    let loopsData = null;

    const renderLoops = (d) => {
      loopsData = d;
      const m = (d && d.model) || {};
      const lv = (d && d.leverage) || {};
      const state = bare(m.state);
      const traj = m.trajectory || {};

      const src = $('#loops-source');
      if (src) src.textContent = d && d.business
        ? (d.business.name || d.business.slug)
        : '事業を選択してください。';

      const note = $('#loops-model-state');
      if (note) {
        note.replaceChildren();
        if (state === 'resolved') {
          note.append(make('strong', null, `モデル ${m.name || m['simulated-model'] || ''} `));
          note.append(document.createTextNode(m.source || ''));
          // Which model ran, when a document declares several. Picking the first
          // is a choice, so it is stated rather than hidden.
          if ((m.models || []).length > 1) {
            note.append(make('strong', null,
              ` ${m.models.length} 個のモデルのうち「${m['simulated-model']}」を実行`));
          }
          if (bare(traj.state) !== 'simulated') {
            note.append(make('strong', null, ' シミュレーションできません: '));
            note.append(document.createTextNode(traj.reason || ''));
          }
        } else {
          note.append(make('strong', null, (m.detail ? '' : state) + ' '));
          note.append(document.createTextNode(m.detail || ''));
        }
      }
      const meta = $('#loops-meta');
      if (meta) meta.textContent = bare(traj.state) === 'simulated'
        ? `${traj.steps} step` : '';

      const st = $('#loops-structure');
      if (st) {
        st.replaceChildren();
        const s = m.structure || {};
        (s.stocks || []).forEach((v) => {
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null, v.name));
          const b = make('span', 'req-row__state', 'stock');
          b.dataset.tone = 'note';
          head.append(b);
          li.append(head);
          li.append(make('p', 'req-row__detail',
            `${(v.inflows || []).join('・') || '流入なし'} → ${v.name} → `
            + `${(v.outflows || []).join('・') || '流出なし'}`));
          if (v.units) li.append(make('p', 'req-row__caveat', v.units));
          st.append(li);
        });
        [['flow', s.flows], ['aux', s.auxes]].forEach(([kind, vs]) => {
          (vs || []).forEach((v) => {
            const li = make('li', 'req-row');
            const head = make('div', 'req-row__head');
            head.append(make('strong', null, v.name));
            const b = make('span', 'req-row__state', kind);
            b.dataset.tone = 'note';
            head.append(b);
            li.append(head);
            if (v.units) li.append(make('p', 'req-row__caveat', v.units));
            st.append(li);
          });
        });
        if (!st.childElementCount) {
          st.append(make('li', 'empty-state',
            state === 'resolved' ? '変数がありません。' : 'モデルを読み込めていません。'));
        }
      }

      // The series grid and the table are the same data in two forms; the table
      // is the accessible view and also the one that shows every variable when
      // the grid gets long.
      const grid = $('#loops-series');
      const table = $('#loops-table');
      const series = traj.series || {};
      const names = Object.keys(series).sort();
      const kindOf = {};
      const unitsOf = {};
      ['stocks','flows','auxes'].forEach((g) => {
        ((m.structure || {})[g] || []).forEach((v) => {
          kindOf[v.name] = bare(v.kind); unitsOf[v.name] = v.units;
        });
      });
      if (grid) {
        grid.replaceChildren();
        if (bare(traj.state) === 'simulated') {
          names.forEach((n) => grid.append(
            smPanel(n, kindOf[n] || 'aux', unitsOf[n], series[n])));
        }
      }
      if (table) {
        table.replaceChildren();
        if (bare(traj.state) === 'simulated') {
          const t = make('table', 'dads-table');
          const thead = make('thead');
          const hr = make('tr');
          hr.append(make('th', null, 't'));
          names.forEach((n) => hr.append(make('th', null, n)));
          thead.append(hr); t.append(thead);
          const tb = make('tbody');
          (traj.times || []).forEach((tv, i) => {
            const row = make('tr');
            row.append(make('td', null, String(tv)));
            names.forEach((n) => {
              const v = (series[n] || [])[i];
              // An absent value is not zero. `—` is what the funding pane does
              // with an unknown balance, for the same reason.
              row.append(make('td', null,
                typeof v === 'number' && Number.isFinite(v) ? v.toPrecision(6) : '—'));
            });
            tb.append(row);
          });
          t.append(tb);
          table.append(t);
        }
      }

      const cav = $('#loops-leverage-caveat');
      if (cav) {
        cav.textContent = bare(lv.state) === 'resolved'
          ? (lv['models-what'] || '')
          : (lv.detail || '');
      }
      const list = $('#loops-leverage');
      if (list) {
        list.replaceChildren();
        (lv.ranked || []).forEach((r) => {
          const li = make('li', 'req-row');
          const head = make('div', 'req-row__head');
          head.append(make('strong', null, r.id));
          const b = make('span', 'req-row__state',
            `${bare(r.band)} · ${typeof r.score === 'number' ? r.score.toPrecision(3) : '—'}`);
          b.dataset.tone = (lv['top-3'] || []).includes(r.id) ? 'ok' : 'note';
          head.append(b);
          li.append(head);
          li.append(make('p', 'req-row__detail', r['band-label'] || ''));
          list.append(li);
        });
        if (!(lv.ranked || []).length) {
          list.append(make('li', 'empty-state', lv.detail || 'ranking がありません。'));
        }
      }
      const strength = $('#loops-strength');
      if (strength) {
        const s = lv['structural-strength'];
        // nil from dynamics.core is carried through as its own state; it is not
        // rendered as 0, and not omitted either.
        strength.textContent = !s ? ''
          : (bare(s.state) === 'computed'
             ? `構造的強度: ${Number(s.value).toPrecision(4)}`
             : `構造的強度: — ${s.detail || ''}`);
      }
      const bands = $('#loops-bands');
      if (bands) {
        bands.replaceChildren();
        (d?.bands || []).forEach((b) => bands.append(
          listItem(`${bare(b.band)} — ${b.label}`,
                   `Meadows tier ${(b.tiers || []).join(', ')}`,
                   `重み ${b.weight}`)));
      }
      const badge = $('#loops-count');
      if (badge) badge.textContent = (lv.ranked || []).length || '—';
    };

    const loadLoops = (businessId) => {
      if (!businessId) { renderLoops(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/loops`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { renderLoops(d); return Boolean(d); })
        .catch(() => {
          const src = $('#loops-source');
          if (src) src.textContent = 'loops を読み込めません。';
          return false;
        });
    };

    const fillLoopsBusinesses = (rows) => {
      const sel = $('#loops-business'); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== loopsData?.business?.id) loadLoops(next);
      if (!next) renderLoops(null);
    };

    $('#loops-business')?.addEventListener('change', (e) => {
      selectedBusinessId = e.currentTarget.value || selectedBusinessId;
      loadLoops(e.currentTarget.value);
    });

    $('#loops-table-toggle')?.addEventListener('click', (e) => {
      const table = $('#loops-table'), grid = $('#loops-series');
      const showTable = table.hidden;
      table.hidden = !showTable;
      if (grid) grid.hidden = showTable;
      e.currentTarget.setAttribute('aria-pressed', String(showTable));
      e.currentTarget.textContent = showTable ? 'グラフで見る' : '表で見る';
    });

    // ── Repos（事業の実装と成熟度）─────────────────────────────────────
    //
    // An UNSCORED axis gets no bar. A zero-width bar and a score of 0.0 look
    // identical, and :maturity/stage-score is nil for 2,732 of 3,899 repos —
    // drawing those as empty bars would put 70% of the fleet at the bottom of an
    // axis nobody assessed them on.
    const axisRow = (a) => {
      const row = make('div', 'axis-row');
      row.append(make('span', null, a.label || bare(a.axis)));
      if (a['scored?'] && typeof a.score === 'number') {
        const track = make('div', 'axis-row__track');
        const fill = make('div', 'axis-row__fill');
        fill.style.width = `${Math.round(Math.max(0, Math.min(1, a.score)) * 100)}%`;
        track.append(fill);
        row.append(track);
        // The method rides beside the number: an :impl score is a
        // size-and-scaffold heuristic and a :stage score is a parsed marker,
        // and two identical-looking decimals hide which is which.
        row.append(make('span', 'axis-row__value',
          a.score.toFixed(2) + (a.method ? ` (${bare(a.method)})` : '')));
      } else {
        row.append(make('div', 'axis-row__unscored'));
        row.append(make('span', 'axis-row__value', '未評価'));
      }
      row.title = a.detail || '';
      return row;
    };

    const repoRow = (r) => {
      const li = make('li', 'req-row');
      const head = make('div', 'req-row__head');
      head.append(make('strong', null, r.path || r.repo || '(path 不明)'));
      const chip = make('span', 'req-row__state',
        typeof r.composite === 'number' ? r.composite.toFixed(2) : '未評価');
      chip.dataset.tone = typeof r.composite === 'number' ? 'ok' : 'note';
      head.append(chip);
      li.append(head);
      const bits = [bare(r.source) === 'adoptions' ? '参与' : '宣言',
                    r.kind ? bare(r.kind) : null,
                    r.stage ? `stage ${bare(r.stage)}` : null,
                    r.traits || null].filter(Boolean);
      li.append(make('p', 'req-row__detail', bits.join(' · ')));
      if (r['kind-evidence']) li.append(make('p', 'req-row__caveat', r['kind-evidence']));
      if (r.detail) li.append(make('p', 'req-row__caveat', r.detail));
      (r.axes || []).forEach((a) => li.append(axisRow(a)));
      return li;
    };

    const renderRepos = (d) => {
      const rows = (d && d.repos) || [];
      const plane = (d && d.plane) || {};
      const roll = (d && d['roll-up']) || {};
      const src = $('#repos-source');
      if (src) src.textContent = d && d.business
        ? (d.business.name || d.business.slug) : '事業を選択してください。';
      const note = $('#repos-plane');
      if (note) {
        note.replaceChildren();
        if (bare(plane.state) === 'resolved') {
          note.append(make('strong', null, 'generated plane: '));
          note.append(document.createTextNode(
            `${d.sources?.taxonomy || ''} + ${d.sources?.maturity || ''}`));
        } else {
          note.append(make('strong', null, bare(plane.state) + ' '));
          note.append(document.createTextNode(plane.detail || ''));
        }
      }
      const stats = $('#repos-stats');
      if (stats) {
        stats.replaceChildren(
          statTile('repo', roll.repos ?? 0),
          statTile('評価済み', roll.scored ?? 0),
          // Shown beside the mean on purpose: a mean over 3 of 12 is a different
          // claim from a mean over 12.
          statTile('未評価', roll.unscored ?? 0),
          statTile('composite 平均',
            typeof roll['mean-composite'] === 'number'
              ? roll['mean-composite'].toFixed(2) : '—'));
      }
      const list = $('#repos-list');
      if (list) {
        list.replaceChildren();
        rows.forEach((r) => list.append(repoRow(r)));
        if (!rows.length) {
          list.append(make('li', 'empty-state',
            'この事業に repo / 参与が紐付いていません。Portfolio で紐付けてください。'));
        }
      }
      const meta = $('#repos-meta');
      if (meta) meta.textContent = rows.length ? `${rows.length} repo` : '';
      const badge = $('#repos-count');
      if (badge) badge.textContent = rows.length || '—';
    };

    const loadRepos = (businessId) => {
      if (!businessId) { renderRepos(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/repos`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { reposBusinessId = businessId; renderRepos(d); return Boolean(d); })
        .catch(() => {
          const s = $('#repos-source');
          if (s) s.textContent = 'repo を読み込めません。';
          return false;
        });
    };

    // ── Metrics（実測と、その古さ）─────────────────────────────────────
    //
    // Freshness leads. A 28-day-old file's numbers are not current numbers, and
    // one of the twelve real metrics files is exactly that.
    const freshnessLabel = {fresh:'新しい', stale:'古い', undated:'日付なし'};
    const kv = (dl, pairs) => {
      dl.replaceChildren();
      pairs.forEach(([k, v]) => {
        if (v === null || v === undefined || v === '') return;
        dl.append(make('dt', null, k), make('dd', null, String(v)));
      });
    };

    const renderMetrics = (d) => {
      const state = bare(d && d.state);
      const f = (d && d.freshness) || {};
      const src = $('#metrics-source');
      if (src) src.textContent = d && d.business
        ? `${d.business.name || d.business.slug}`
          + (d.business.canvas ? ` → ${bare(d.business.canvas)}` : '')
        : '事業を選択してください。';

      const note = $('#metrics-freshness');
      if (note) {
        note.replaceChildren();
        if (state === 'resolved') {
          const fs = bare(f.state);
          note.append(make('strong', null,
            `測定 ${freshnessLabel[fs] || fs}: ${f['as-of'] || '不明'} `));
          if (typeof f['age-days'] === 'number') {
            note.append(document.createTextNode(
              `（${f['age-days'].toFixed(1)} 日前 / 上限 ${f['max-age-days']} 日）`));
          }
          if (fs === 'stale') {
            note.append(make('strong', null,
              ' この数値は現在の値ではありません。emitter を再実行してください。'));
          }
          if (fs === 'undated') note.append(document.createTextNode(' ' + (f.detail || '')));
        } else {
          note.append(make('strong', null, state + ' '));
          note.append(document.createTextNode((d && d.detail) || ''));
        }
      }

      const t = (d && d.traffic) || {};
      kv($('#metrics-traffic'), [
        ['zone', t.zone],
        ['requests / 7d', t['requests-7d']],
        ['pageviews / 7d', t['pageviews-7d']],
        ['uniques / 7d', t['uniques-7d']],
        [`probe 4xx (${t.window || ''})`, t['probe-4xx-pct'] != null ? t['probe-4xx-pct'] + '%' : null],
        [`5xx (${t.window || ''})`, t['error-5xx-pct'] != null ? t['error-5xx-pct'] + '%' : null],
        ['health', d && d['health-status']]
      ]);
      const cav = $('#metrics-traffic-caveat');
      // The caveat comes from the server, which decided it from the same numbers
      // it sent — the renderer does not re-derive a threshold of its own.
      if (cav) cav.textContent = t.caveat || '';

      const sig = $('#metrics-signal');
      if (sig) sig.textContent = (d && d.signal) || '—';
      const tp = $('#metrics-top-paths');
      if (tp) tp.textContent = (d && d['top-paths']) || '';
      const so = $('#metrics-sources');
      if (so) so.textContent = (d && d.sources || []).length
        ? `sources: ${d.sources.join(' · ')}${d.note ? ' — ' + d.note : ''}` : '';

      kv($('#metrics-specific'),
         ((d && d['product-specific']) || []).map((e) => [e.key, e.value]));
      const meta = $('#metrics-meta');
      if (meta) meta.textContent = state === 'resolved' ? (d.source || '') : '';
      const badge = $('#metrics-count');
      if (badge) badge.textContent = state === 'resolved' ? bare(f.state).slice(0, 1).toUpperCase() : '—';
    };

    const loadMetrics = (businessId) => {
      if (!businessId) { renderMetrics(null); return Promise.resolve(false); }
      return fetch(`/api/business/${encodeURIComponent(businessId)}/metrics`)
        .then((r) => r.ok ? r.json() : null)
        .then((d) => { metricsBusinessId = businessId; renderMetrics(d); return Boolean(d); })
        .catch(() => {
          const s = $('#metrics-source');
          if (s) s.textContent = 'metrics を読み込めません。';
          return false;
        });
    };

    // One filler per pane, all fed from the portfolio, so no two panes can
    // disagree about which businesses exist.
    const fillBusinessSelect = (id, rows, load, currentId) => {
      const sel = $('#' + id); if (!sel) return;
      const keep = sel.value || selectedBusinessId || '';
      sel.replaceChildren(make('option', null, '事業を選択…'));
      sel.firstChild.value = '';
      (rows || []).forEach((b) => {
        const o = make('option', null, b.name || b.slug);
        o.value = b.id;
        sel.append(o);
      });
      const next = (rows || []).some((b) => b.id === keep) ? keep : '';
      sel.value = next;
      if (next && next !== currentId) load(next); else if (!next) load(null);
    };

    ['repos-business', 'metrics-business'].forEach((id) => {
      $('#' + id)?.addEventListener('change', (e) => {
        selectedBusinessId = e.currentTarget.value || selectedBusinessId;
        (id === 'repos-business' ? loadRepos : loadMetrics)(e.currentTarget.value);
      });
    });

    $('#portfolio-bind-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const status = $('#portfolio-bind-status');
      if (!selectedBusinessId) { if (status) status.textContent = '事業を選んでください。'; return; }
      fetch(`/api/business/${encodeURIComponent(selectedBusinessId)}/bind`, {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          canvas: ($('#portfolio-bind-canvas')?.value || '').trim(),
          model: ($('#portfolio-bind-model')?.value || '').trim(),
          leverage: ($('#portfolio-bind-leverage')?.value || '').trim(),
          lei: ($('#portfolio-bind-lei')?.value || '').trim(),
          adoptions: splitList('portfolio-bind-adoptions'),
          repos: splitList('portfolio-bind-repos')
        })
      }).then((r) => {
        if (!r.ok) { if (status) status.textContent = `保存できません (${r.status})`; return; }
        if (status) status.textContent = '紐付けを保存しました。';
        return loadPortfolio();
      }).catch(() => { if (status) status.textContent = '保存できません。'; });
    });

    $('#fleet-filter-form')?.addEventListener('submit', (e) => { e.preventDefault(); searchFleet(); });
    ['fleet-role','fleet-maturity','fleet-iso3166','fleet-callable'].forEach((id) => {
      $('#' + id)?.addEventListener('change', searchFleet);
    });
    $('#fleet-text')?.addEventListener('input', () => {
      clearTimeout(window.__fleetDebounce);
      window.__fleetDebounce = setTimeout(searchFleet, 250);
    });

    $('#operator-profile-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      const kind = ($('#operator-licence-kind')?.value || '').trim();
      const by = ($('#operator-licence-by')?.value || '').trim();
      // A licence with no attester is not an attestation, so it is not sent.
      const licences = (kind && by) ? [{
        'licence/kind': kind,
        'licence/authority': ($('#operator-licence-authority')?.value || '').trim(),
        'licence/number': ($('#operator-licence-number')?.value || '').trim(),
        'licence/attested-by': by,
        'licence/attested-on': new Date().toISOString().slice(0, 10)
      }] : [];
      fetch('/api/operator/profile', {
        method: 'POST', headers: identityHeaders(),
        body: JSON.stringify({
          name: ($('#operator-name')?.value || '').trim(),
          isic: splitList('operator-isic'),
          isco: splitList('operator-isco'),
          iso3166: splitList('operator-iso3166'),
          technologies: splitList('operator-tech'),
          licences: licences
        })
      }).then(() => loadOperator())
        .catch(() => { $('#operator-source').textContent = 'プロファイルを保存できません。'; });
    });

    $('#inbox-search').addEventListener('input', () => renderInbox(inboxData));
    // The box filters the list as you type, which is instant and local, and
    // separately asks the server what is inside the documents, which is not.
    // Debounced because the server read is every readable document's bytes —
    // a request per keystroke would be a scan per keystroke.
    let contentSearchTimer = null;
    const runContentSearch = async (query) => {
      const panel = $('#drive-found');
      if (!query) { panel.hidden = true; panel.replaceChildren(); return; }
      try {
        const request = await fetch(
          `/api/workspace/drive/search?q=${encodeURIComponent(query)}`);
        const data = await request.json();
        if (!request.ok) return;
        const inside = (data.results || []).filter((r) => r.where === 'content');
        panel.replaceChildren();
        if (!inside.length) { panel.hidden = true; return; }
        panel.hidden = false;
        panel.append(make('h3', 'sharing__title', `本文に一致 ${inside.length} 件`));
        const list = make('ul', 'sharing__list');
        inside.forEach((hit) => {
          const row = make('li', 'sharing__entry');
          const open = make('button', 'tool-button', `${hit.name}（${hit.label}）`);
          open.type = 'button';
          open.addEventListener('click', () => {
            const item = (driveData.items || []).find((i) => i.id === hit.id);
            if (!item) return;
            driveEditor = closedEditor(item.id);
            selectedDrive = item;
            $('#drive-search').value = '';
            renderDrive(driveData);
          });
          row.append(open, make('span', 'surface-note', hit.snippet));
          list.append(row);
        });
        panel.append(list);
      } catch (error) { panel.hidden = true; }
    };
    $('#drive-search').addEventListener('input', () => {
      renderDrive(driveData);
      const query = ($('#drive-search').value || '').trim();
      window.clearTimeout(contentSearchTimer);
      contentSearchTimer = window.setTimeout(() => runContentSearch(query), 300);
    });
    $('#drive-more').addEventListener('click', loadMoreDrive);
    $('#drive-trash-empty').addEventListener('click', () => driveAction(
      '/api/workspace/drive/trash/empty', {}, 'ゴミ箱を空にしました。'));
    let identityState = null;
    // The same value the server sees as the actor: a user record is stored
    // under its own id, so `user.id` is `(:user-id session)`.
    const currentUserId = () => identityState?.user?.id;
    const connectorMarks = {github:'GH', google:'G', microsoft:'M'};
    const identityHeaders = () => ({
      'Content-Type':'application/json',
      'X-CLOUD-ITONAMI-CSRF':identityState?.csrf || ''
    });
    const b64urlToBytes = (value) => {
      const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
        .padEnd(Math.ceil(value.length / 4) * 4, '=');
      return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
    };
    const bytesToB64url = (value) => {
      if (value === null || value === undefined) return null;
      const bytes = new Uint8Array(value);
      let binary = '';
      bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
      return btoa(binary).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/g, '');
    };
    const creationOptions = (payload) => {
      const options = payload.options;
      options.publicKey.challenge = b64urlToBytes(options.publicKey.challenge);
      options.publicKey.user.id = b64urlToBytes(options.publicKey.user.id);
      (options.publicKey.excludeCredentials || []).forEach((item) => {
        item.id = b64urlToBytes(item.id);
      });
      return options;
    };
    const assertionOptions = (payload) => {
      const options = payload.options;
      options.publicKey.challenge = b64urlToBytes(options.publicKey.challenge);
      (options.publicKey.allowCredentials || []).forEach((item) => {
        item.id = b64urlToBytes(item.id);
      });
      return options;
    };
    const credentialJSON = (credential) => {
      const response = credential.response;
      const result = {
        id:credential.id, rawId:bytesToB64url(credential.rawId),
        type:credential.type,
        authenticatorAttachment:credential.authenticatorAttachment || null,
        clientExtensionResults:credential.getClientExtensionResults(),
        response:{clientDataJSON:bytesToB64url(response.clientDataJSON)}
      };
      if (response.attestationObject) {
        result.response.attestationObject = bytesToB64url(response.attestationObject);
        result.response.transports = response.getTransports ? response.getTransports() : [];
      } else {
        result.response.authenticatorData = bytesToB64url(response.authenticatorData);
        result.response.signature = bytesToB64url(response.signature);
        result.response.userHandle = bytesToB64url(response.userHandle);
      }
      return result;
    };
    const postJSON = async (path, body={}, authenticated=false) => {
      const request = await fetch(path, {
        method:'POST',
        headers:authenticated ? identityHeaders() : {'Content-Type':'application/json'},
        body:JSON.stringify(body)
      });
      const data = await request.json();
      if (!request.ok) throw new Error(data?.error?.message || '認証要求を完了できませんでした。');
      return data;
    };
    const requireWebAuthn = () => {
      if (!window.PublicKeyCredential || !navigator.credentials) {
        throw new Error('このブラウザは Passkey / WebAuthn に対応していません。');
      }
    };
    const registerCurrentPasskey = async () => {
      requireWebAuthn();
      const started = await postJSON('/api/passkeys/register/start', {}, true);
      const credential = await navigator.credentials.create(creationOptions(started));
      await postJSON('/api/passkeys/register/finish', {
        'transaction-id':started['transaction-id'],
        credential:credentialJSON(credential)
      }, true);
      await loadIdentity();
      $('#identity-status').textContent = 'Passkey を登録しました。';
    };
    const renderMembers = (organization) => {
      const list = $('#member-list'); list.replaceChildren();
      (organization?.users || []).forEach((user) => {
        const row = make('li');
        const copy = make('div');
        copy.append(make('strong', null, user['display-name']),
          make('span', null, user.email));
        row.append(copy, make('span', 'state-chip', user.role));
        list.append(row);
      });
    };
    const renderConnectors = (data) => {
      const list = $('#connector-list'); list.replaceChildren();
      const connections = new Map((data.connections || [])
        .map((connection) => [connection.provider, connection]));
      (data.providers || []).forEach((provider) => {
        const connection = connections.get(provider.id);
        const card = make('article', 'connector-card');
        card.append(make('div', 'connector-logo', connectorMarks[provider.id] || '•'));
        const copy = make('div');
        copy.append(make('h3', null, provider.name));
        const description = connection
          ? `${connection['display-name'] || connection.email || '接続済み'} · ${connection.scopes?.length || 0} 権限`
          : provider['configured?']
            ? `${provider.scopes.length} 個の読み取り権限を確認して接続`
            : 'OAuth クライアント設定が必要です';
        copy.append(make('p', null, description));
        const button = make('button', 'tool-button',
          connection ? '再接続' : provider['configured?'] ? '接続する' : '未設定');
        button.type = 'button';
        button.disabled = !provider['configured?'];
        button.addEventListener('click', async () => {
          button.disabled = true; button.textContent = '接続を準備中…';
          try {
            const request = await fetch(`/api/connections/${provider.id}/start`, {
              method:'POST', headers:identityHeaders(), body:'{}'
            });
            const result = await request.json();
            if (!request.ok) throw new Error(result?.error?.message || '接続を開始できませんでした。');
            location.assign(result.url);
          } catch (error) {
            button.disabled = false; button.textContent = '接続する';
            $('#identity-status').textContent = error.message;
          }
        });
        card.append(copy, button); list.append(card);
      });
    };
    const renderIdentity = (data) => {
      identityState = data;
      const passkeyReady = Boolean(
        data['authenticated?'] && data.user?.['passkey-enrolled?']);
      appUnlocked = passkeyReady;
      $$('.local-nav__item').forEach((item) => {
        item.disabled = !passkeyReady && !publicViews.has(item.dataset.view);
        item.setAttribute('aria-disabled', String(item.disabled));
      });
      document.body.dataset.identityGate = passkeyReady ? 'ready' : 'required';
      $('#passkey-gate-notice').hidden = passkeyReady;
      if (!passkeyReady) {
        // a public view the user actually asked for stays put
        showView(publicViews.has(requestedView) ? requestedView : 'settings');
        $('#current-view').textContent =
          currentView === 'storage' ? 'Storage' : 'Passkey 登録';
        $('#workspace-status').textContent = 'Passkey 登録が必要です';
      } else {
        bootstrapApp();
        showView(requestedView);
      }
      const onboarding = $('#identity-onboarding');
      const workspace = $('#identity-workspace');
      onboarding.hidden = data['registered?'];
      workspace.hidden = !data['authenticated?'];
      $('#registered-auth').hidden = !(data['registered?'] && !data['authenticated?']);
      if (data['registered?'] && !data['authenticated?']) {
        onboarding.hidden = false;
        const pendingPasskey = data['passkey-required?'];
        $('#registration-title').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'Passkey でサインイン';
        $('#registration-lead').textContent = pendingPasskey
          ? '仮登録は完了しています。Passkey を作成するとアプリを利用できます。'
          : '登録済みの Passkey で本人確認するとアプリを開けます。';
        $('#passkey-signin').textContent = pendingPasskey
          ? 'Passkey 登録を再開'
          : 'Passkey でサインイン';
        $('#registration-form').hidden = true;
      }
      if (!data['authenticated?']) return;
      $('#identity-avatar').textContent =
        (data.user['display-name'] || 'U').slice(0, 2);
      $('#identity-name').textContent = data.user['display-name'] || 'Passkey user';
      $('#identity-email').textContent =
        data.user['account-id'] ? data.user.email : 'Organization ID 未設定';
      $('#identity-did').textContent = data.user.did || 'Passkey 登録後に発行';
      $('#passkey-state').textContent = data.user['passkey-enrolled?']
        ? 'Passkey 登録済み'
        : '必須: 続行するには Passkey を登録してください。';
      $('#passkey-register').textContent = data.user['passkey-enrolled?']
        ? '別の Passkey を追加' : 'Passkey を登録';
      const organizationReady = Boolean(data.organization?.['profile-complete?']);
      $('#organization-name').textContent =
        organizationReady ? data.organization.name : 'Organization ID 未設定';
      $('#organization-domain').textContent = organizationReady
        ? `${data.organization.domain} · ${data.organization.role}`
        : 'Passkey 登録後に設定できます';
      $('#organization-did').textContent =
        data.organization?.did || 'Organization DID は ID 設定後に発行';
      const organizationSwitcher = $('#organization-switcher');
      organizationSwitcher.replaceChildren();
      (data.organizations || []).forEach((organization) => {
        const option = document.createElement('option');
        option.value = organization.id;
        option.textContent = organization.name || organization['organization-id']
          || organization.id;
        option.selected = Boolean(organization['active?']);
        organizationSwitcher.append(option);
      });
      organizationSwitcher.disabled = (data.organizations || []).length < 2;
      const invitations = data['organization-invitations'] || [];
      $('#organization-invitation-state').textContent = invitations.length
        ? `${invitations.length}件の参加待ち招待があります。コードを入力して参加できます。`
        : '参加待ちの招待はありません。';
      $('#organization-form').hidden = organizationReady;
      $('#member-card').hidden = !organizationReady;
      renderMembers(data.organization);
      renderConnectors(data);
      loadCloudAlias(data);
    };
    const loadCloudAlias = async (identity) => {
      const state = $('#cloud-alias-state');
      const button = $('#cloud-alias-reserve');
      if (!state || !identity?.['authenticated?']) return;
      if (!identity.user?.['account-id']) {
        state.textContent = 'Organization ID を設定すると公開メールアドレスを予約できます。';
        button.disabled = true;
        return;
      }
      try {
        const request = await fetch('/api/cloud/alias');
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'クラウド状態を確認できません。');
        if (!data['configured?']) {
          state.textContent = 'Private Relay provider はまだ設定されていません。';
          button.disabled = true;
          return;
        }
        const alias = data.alias;
        state.textContent = data.found
          ? `${alias.address} · ${alias.status}`
          : `${identity.user.email} はグローバル未予約です。`;
        button.disabled = data.found && alias.status === 'active';
      } catch (error) {
        state.textContent = error.message;
        button.disabled = true;
      }
    };
    const loadIdentity = async () => {
      try {
        const request = await fetch('/api/identity');
        const data = await request.json();
        if (!request.ok) throw new Error('アカウント情報を読み込めませんでした。');
        renderIdentity(data);
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    };
    $('#registration-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#registration-submit');
      button.disabled = true; button.textContent = '登録中…';
      try {
        const request = await fetch('/api/identity/register', {
          method:'POST', headers:{'Content-Type':'application/json'},
          body:JSON.stringify({})
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || '登録できませんでした。');
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey を登録します。';
        try { await registerCurrentPasskey(); }
        catch (passkeyError) {
          $('#identity-status').textContent =
            `アカウントは登録済みです。Passkey 登録: ${passkeyError.message}`;
        }
      } catch (error) {
        $('#identity-status').textContent = error.message;
        button.disabled = false; button.textContent = 'Passkey で登録';
      }
    });
    $('#organization-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '設定中…';
      try {
        const data = await postJSON('/api/identity/organization', fields, true);
        renderIdentity(data);
        $('#identity-status').textContent =
          `${data.organization.domain} と ${data.user.email} を設定しました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
        button.disabled = false; button.textContent = 'Organization ID を設定';
      }
    });
    $('#organization-create-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-create');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '追加中…';
      try {
        const data = await postJSON('/api/identity/organizations', fields, true);
        event.currentTarget.reset();
        renderIdentity(data);
        $('#identity-status').textContent =
          `${fields['organization-name'] || fields['organization-id']} を追加しました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = 'Organizationを追加';
      }
    });
    $('#organization-switcher').addEventListener('change', async (event) => {
      const select = event.currentTarget;
      select.disabled = true;
      try {
        const data = await postJSON('/api/identity/organizations/switch',
          {'organization-id':select.value}, true);
        organismCursor = null;
        organismWorkers = [];
        selectedOrganism = null;
        renderIdentity(data);
        await loadOrganisms();
        $('#identity-status').textContent =
          `${data.organization.name} に切り替えました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
        renderIdentity(identityState);
      }
    });
    $('#member-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const memberForm = event.currentTarget;
      const fields = new FormData(memberForm);
      try {
        const request = await fetch('/api/identity/users', {
          method:'POST', headers:identityHeaders(),
          body:JSON.stringify(Object.fromEntries(fields))
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'ユーザーを追加できませんでした。');
        memberForm.reset(); renderIdentity(data.identity);
        const invitation = data.invitation;
        const result = $('#enrollment-result');
        result.hidden = false;
        const existing = invitation.kind === 'organization-invitation';
        const code = existing
          ? invitation['invitation-code'] : invitation['enrollment-code'];
        result.textContent = existing
          ? `${invitation.email} のOrganization招待コード: ${code}（24時間・1回限り）`
          : `${invitation.email} のenrollment code: ${code}（24時間・1回限り）`;
        $('#identity-status').textContent = existing
          ? '既存UserへOrganization参加招待を発行しました。'
          : '組織Userとenrollment codeを発行しました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      }
    });
    $('#organization-invitation-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#organization-invitation-accept');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      button.disabled = true; button.textContent = '参加中…';
      try {
        const data = await postJSON('/api/identity/organizations/accept',
          fields, true);
        event.currentTarget.reset();
        organismCursor = null;
        organismWorkers = [];
        selectedOrganism = null;
        renderIdentity(data);
        await loadOrganisms();
        $('#identity-status').textContent =
          `${data.organization.name} に参加して切り替えました。`;
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally {
        button.disabled = false; button.textContent = '招待を承認して参加';
      }
    });
    $('#passkey-register').addEventListener('click', async () => {
      const button = $('#passkey-register');
      button.disabled = true;
      try { await registerCurrentPasskey(); }
      catch (error) { $('#identity-status').textContent = error.message; }
      finally { button.disabled = false; }
    });
    $('#cloud-alias-reserve').addEventListener('click', async () => {
      const button = $('#cloud-alias-reserve');
      const destination = $('#cloud-alias-destination').value.trim();
      button.disabled = true;
      try {
        const request = await fetch('/api/cloud/alias', {
          method:'POST',
          headers:identityHeaders(),
          body:JSON.stringify({destination})
        });
        const data = await request.json();
        if (!request.ok) throw new Error(data?.error?.message || 'グローバル予約に失敗しました。');
        $('#cloud-alias-state').textContent =
          `${data.address} を予約しました。転送先の確認メールを送信しました。`;
      } catch (error) {
        $('#cloud-alias-state').textContent = error.message;
        button.disabled = false;
      }
    });
    $('#passkey-signin').addEventListener('click', async () => {
      const button = $('#passkey-signin'); button.disabled = true;
      try {
        if (identityState?.['passkey-required?']) {
          const resumed = await postJSON('/api/passkeys/onboarding/resume');
          renderIdentity(resumed);
          await registerCurrentPasskey();
          $('#identity-status').textContent = 'Passkey を登録してアプリを開きました。';
          return;
        }
        requireWebAuthn();
        const started = await postJSON('/api/passkeys/authenticate/start');
        const credential = await navigator.credentials.get(assertionOptions(started));
        const data = await postJSON('/api/passkeys/authenticate/finish', {
          'transaction-id':started['transaction-id'],
          credential:credentialJSON(credential)
        });
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey でサインインしました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    $('#enrollment-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#enrollment-submit'); button.disabled = true;
      try {
        requireWebAuthn();
        const fields = Object.fromEntries(new FormData(event.currentTarget));
        const started = await postJSON('/api/passkeys/enroll/start', fields);
        const credential = await navigator.credentials.create(creationOptions(started));
        const data = await postJSON('/api/passkeys/enroll/finish', {
          'transaction-id':started['transaction-id'],
          credential:credentialJSON(credential)
        });
        renderIdentity(data);
        $('#identity-status').textContent = 'Passkey を登録してサインインしました。';
      } catch (error) {
        $('#identity-status').textContent = error.message;
      } finally { button.disabled = false; }
    });
    const workerLabels = {queued:'待機中', running:'実行中', done:'完了',
      failed:'失敗', cancelled:'中止'};
    const workerChipClass = {queued:'', running:' state-chip--run',
      done:' state-chip--done', failed:' state-chip--fail', cancelled:''};
    let workerData = {items:[], counts:{}, active:0};
    let selectedWorker = null;
    let workerTimer = null;
    const workerHelp = (message) => {
      $('#worker-form-help').textContent = message;
    };
    const renderWorkerDetail = () => {
      const target = $('#worker-detail');
      const previous = target.querySelector('.worker-output');
      const follow = !previous || (previous.scrollTop + previous.clientHeight
        >= previous.scrollHeight - 8);
      target.replaceChildren();
      const run = selectedWorker;
      if (!run) {
        target.append(make('div', 'empty-state', 'ジョブを選択してください。'));
        return;
      }
      target.append(
        make('p', 'record-detail__eyebrow',
          `${workerLabels[run.status] || run.status} · ${run.agent}`),
        make('h2', null, run.title),
        make('p', 'record-detail__body', run.prompt));
      if (run.error) {
        target.append(make('p', 'settings-notice settings-notice--error', run.error));
      }
      const output = make('div', 'worker-output', run.output
        || (run.status === 'queued' ? 'まだ実行を開始していません。' : '出力はまだありません。'));
      output.setAttribute('aria-live', 'polite');
      target.append(output);
      if (run['truncated?']) {
        target.append(make('p', 'form-help',
          '出力が上限に達したため、以降は保存していません。'));
      }
      const meta = make('dl', 'local-meta record-detail__meta');
      [['状態', workerLabels[run.status] || run.status],
       ['モデル', run.model || '既定のモデル'],
       ['プロバイダ', run.provider],
       ['登録', formatDate(run['created-at'])],
       ['開始', run['started-at'] ? formatDate(run['started-at']) : null],
       ['終了', run['finished-at'] ? formatDate(run['finished-at']) : null],
       ['トークン', run.usage?.total_tokens]
      ].forEach(([label, value]) => {
        meta.append(make('dt', null, label), make('dd', null,
          value === null || value === undefined || value === '' ? '—' : String(value)));
      });
      target.append(meta);
      if (run.status === 'queued' || run.status === 'running') {
        const cancel = make('button', 'tool-button', 'このジョブを中止');
        cancel.type = 'button';
        cancel.addEventListener('click', async () => {
          cancel.disabled = true;
          try {
            const request = await fetch(
              `/api/workers/${encodeURIComponent(run.id)}/cancel`,
              {method:'POST', headers:identityHeaders(), body:'{}'});
            const data = await request.json();
            if (!request.ok) {
              throw new Error(data?.error?.message || 'ジョブを中止できませんでした。');
            }
            renderWorker(data);
          } catch (error) {
            cancel.disabled = false;
            workerHelp(error.message);
          }
        });
        const actions = make('div', 'worker-actions');
        actions.append(cancel);
        target.append(actions);
      }
      if (follow) output.scrollTop = output.scrollHeight;
    };
    const renderWorker = (data) => {
      workerData = data;
      const items = data.items || [];
      const previousId = selectedWorker?.id;
      selectedWorker = items.find((item) => item.id === previousId)
        || items[0] || null;
      const list = $('#worker-list');
      list.replaceChildren();
      const select = (run) => { selectedWorker = run; renderWorker(workerData); };
      items.forEach((item) => list.append(recordButton(
        item, item.id === selectedWorker?.id, select, {
          title:item.title,
          time:workerLabels[item.status] || item.status,
          meta:`${item.agent} · ${item.model || '既定のモデル'}`,
          snippet:String(item.output || item.error || '出力はまだありません').slice(0, 140)
        })));
      if (!items.length) {
        list.append(make('li', 'empty-state',
          'まだジョブはありません。指示を登録すると背後で実行します。'));
      }
      renderWorkerDetail();
      const summary = $('#worker-summary');
      summary.replaceChildren();
      ['running', 'queued', 'done', 'failed', 'cancelled'].forEach((key) => {
        const count = data.counts?.[key] || 0;
        if (!count) return;
        summary.append(make('span', `state-chip${workerChipClass[key]}`,
          `${workerLabels[key]} ${count}`));
      });
      if (!summary.childElementCount) {
        summary.append(make('span', 'state-chip', 'ジョブなし'));
      }
      $('#worker-count').textContent = data.active || 0;
      $('#worker-source').textContent = `${data.source} · 同時実行 ${data['max-concurrency']}`
        + ` · 保持 ${items.length} / ${data['max-runs']} 件`;
      $('#worker-clear').disabled = items.length === (data.active || 0);
      scheduleWorkerPoll();
    };
    const scheduleWorkerPoll = () => {
      if (workerTimer) { clearTimeout(workerTimer); workerTimer = null; }
      const active = (workerData.active || 0) > 0;
      if (!appUnlocked || (!active && currentView !== 'worker')) return;
      workerTimer = setTimeout(() => loadWorkspace('worker', renderWorker),
        active ? 1500 : 5000);
    };
    onViewChange = () => {
      scheduleWorkerPoll();
      scheduleOrganismPoll();
      if (currentView === 'organisms' && !organismWorkers.length) {
        loadOrganisms().catch((error) => {
          $('#organism-activity-state').textContent = error.message;
        });
      }
    };
    $('#worker-form').addEventListener('submit', async (event) => {
      event.preventDefault();
      const button = $('#worker-submit');
      const fields = Object.fromEntries(new FormData(event.currentTarget));
      if (!String(fields.prompt || '').trim()) {
        workerHelp('実行する指示を入力してください。');
        return;
      }
      const label = button.textContent;
      button.disabled = true;
      button.textContent = '登録中…';
      try {
        const request = await fetch('/api/workers', {
          method:'POST', headers:identityHeaders(), body:JSON.stringify(fields)
        });
        const data = await request.json();
        if (!request.ok) {
          throw new Error(data?.error?.message || 'ジョブを登録できませんでした。');
        }
        $('#worker-prompt').value = '';
        $('#worker-title').value = '';
        selectedWorker = data;
        workerHelp(`${data.title} をバックグラウンドで実行しています。`);
        await loadWorkspace('worker', renderWorker);
      } catch (error) {
        workerHelp(error.message);
      } finally {
        button.disabled = false;
        button.textContent = label;
      }
    });
    $('#worker-clear').addEventListener('click', async () => {
      const button = $('#worker-clear');
      button.disabled = true;
      try {
        const request = await fetch('/api/workers/clear', {
          method:'POST', headers:identityHeaders(), body:'{}'
        });
        const data = await request.json();
        if (!request.ok) {
          throw new Error(data?.error?.message || '完了したジョブを整理できませんでした。');
        }
        selectedWorker = null;
        renderWorker(data);
      } catch (error) {
        button.disabled = false;
        workerHelp(error.message);
      }
    });
    if (initialParams.get('connection')) {
      const notice = $('#connection-notice');
      const connected = initialParams.get('connection') === 'connected';
      notice.hidden = false;
      notice.className = `settings-notice${connected ? '' : ' settings-notice--error'}`;
      notice.textContent = connected
        ? `${initialParams.get('provider')} を接続しました。`
        : `${initialParams.get('provider')} の接続を完了できませんでした。`;
    }
    loadIdentity();
    // after every const above is defined — calling this next to the initial
    // showView() would hit `Cannot access 'loadFilecoin' before initialization`
    loadFilecoin();
    $$('.view-switcher button').forEach((button) => button.addEventListener('click', () => {
      $$('.view-switcher button').forEach((item) =>
        item.setAttribute('aria-pressed', item === button ? 'true' : 'false'));
    }));
  });
  ")

(defn- nav-item [view title icon badge-id]
  [:button {:class "local-nav__item" :type "button" :data-view view
            :data-title title :aria-label title
            :aria-current (if (= view "chat") "page" "false")}
   [:span {:class "nav-icon" :aria-hidden "true"} icon]
   [:span {:class "nav-label"} title]
   (when badge-id [:span {:class "nav-badge" :id badge-id} "—"])])

(defn- nav-group
  "A heading over one run of nav items. Presentational only — the buttons keep
  carrying their own `data-view`, so `showView` is unchanged and a group cannot
  become a thing that has to be opened before its items work."
  [title]
  [:p {:class "local-nav__group" :aria-hidden "true"} title])

(defn- view-header [title lead]
  [:header {:class "view-header"}
   [:div {:class "view-header__copy"}
    (dds/heading 1 title {:size "36"})
    [:p {:class "view-lead"} lead]]])

(defn page-html [configuration]
  (let [cloud? (get-in configuration [:routing :cloud-enabled?])
        provider (get-in configuration [:routing :default-provider])
        model (get-in configuration [:routing :default-model])
        brand (get-in configuration [:brand :name] "Cloud Itonami")
        css (slurp (io/resource "jp_go_dds/dds.css"))]
    (page/->page
     {:title (str "Chat | " brand)
      :description "ローカル優先のAIワークスペース"
      :css css :app-css app-css :head [[:script interaction-js]]}
     [:div {:class "workspace" :data-brand brand}
      [:aside {:class "sidebar" :aria-label "メインメニュー"}
       [:div {:class "brand"}
        [:p {:class "brand__eyebrow"} "LOCAL-FIRST"]
        [:p {:class "brand__name"} brand]
        [:p {:class "brand__mark" :role "img" :aria-label brand} "ai"]
        [:p {:class "brand__note"} "Kotoba でつながる、手元の仕事場"]]
       [:div {:class "field sidebar__organization"}
        [:label {:for "organization-switcher"} "Organization"]
        [:select {:id "organization-switcher" :disabled true}
         [:option "確認中…"]]]
       [:nav {:class "local-nav"}
        ;; 対象（事業とその実装）と道具（chat・drive・scheduler）を分ける。
        ;; ADR-2607309600 決定4。12 項目が平らに並んでいた状態では、事業を1つ
        ;; 増やすことと道具を1つ増やすことが同じ操作に見えていた。
        (nav-group "BUSINESS")
        (nav-item "portfolio" "Portfolio" "▤" "portfolio-count")
        (nav-item "canvas" "Canvas" "◱" "canvas-count")
        (nav-item "loops" "Loops" "∞" "loops-count")
        (nav-item "repos" "Repos" "⌗" "repos-count")
        (nav-item "metrics" "Metrics" "⌸" "metrics-count")
        (nav-item "fleet" "Fleet" "◉" "fleet-count")
        (nav-item "operator" "\u4e8b\u696d\u8005" "◐" "operator-count")
        (nav-item "contracts" "Contracts" "◫" "contracts-count")
        (nav-group "WORKSPACE")
        (nav-item "chat" "Chat" "✦" nil)
        (nav-item "worker" "Worker" "◐" "worker-count")
        (nav-item "organisms" "Organisms" "◎" "organism-count")
        (nav-item "inbox" "Inbox" "□" "inbox-count")
        (nav-item "projects" "Projects" "▦" "projects-count")
        (nav-item "drive" "Drive" "◇" "drive-count")
        (nav-item "esign" "eSign" "✍" "esign-count")
        (nav-item "scheduler" "Scheduler" "○" "scheduler-count")
        (nav-item "storage" "Storage" "◈" "storage-count")
        (nav-group "SETTINGS")
        (nav-item "settings" "Settings" "⚙" nil)]
       [:div {:class "sidebar__status"}
        [:strong "● ローカルモード"]
        [:span {:id "workspace-status"} "既存サービスを確認中…"]]]
      [:div {:class "main"}
       [:header {:class "topbar"}
        [:h2 {:class "topbar__title" :id "current-view"} "Chat"]
        [:p {:class "topbar__meta"} "データは許可された接続先からのみ読み込みます"]]
       [:main {:id "main-content"}
        [:section {:class "view chat-view" :data-view-panel "chat"}
         [:div {:class "chat-shell" :id "chat-shell"}
          [:header {:class "chat-header"}
           [:div {:class "chat-agent"}
            [:div {:class "chat-agent__avatar" :aria-hidden "true"} "ai"]
            [:div
             [:p {:class "chat-agent__name"} "Local"]
             [:p {:class "chat-agent__state" :id "chat-agent-state"}
              "ローカルモデルを準備中…"]]]
           [:div {:class "chat-header__actions"}
            [:span {:class "model-pill" :id "active-model-label"} (str provider " / " model)]
            [:button {:class "tool-button" :type "button" :id "new-chat-button"}
             "＋ 新しいチャット"]]]
          [:div {:class "chat-scroll" :id "chat-scroll"}
           [:div {:class "chat-thread" :id "chat-thread"
                  :role "log" :aria-live "polite" :aria-relevant "additions"}
            [:div {:class "chat-empty" :id "chat-empty"}
             [:div {:class "chat-empty__mark" :aria-hidden "true"} "ai"]
             [:h1 "今日は何を進めますか？"]
             [:p (if cloud?
                   "必要に応じて許可済みクラウドモデルを使えます。送信前の接続先を確認してください。"
                   "この会話はローカルモデルで処理され、端末の外へ送信されません。")]
             [:div {:class "suggestion-grid"}
              [:button {:class "suggestion-card" :type "button"
                        :data-prompt "今日の予定と優先タスクを整理して"}
               [:strong "今日を整理する"] [:span "予定と優先順位をまとめる"]]
              [:button {:class "suggestion-card" :type "button"
                        :data-prompt "このプロジェクトの次の実装ステップを提案して"}
               [:strong "実装を進める"] [:span "次のステップを具体化する"]]
              [:button {:class "suggestion-card" :type "button"
                        :data-prompt "考えを整理したいので、論点を質問して"}
               [:strong "考えを深める"] [:span "対話しながら論点を見つける"]]
              [:button {:class "suggestion-card" :type "button"
                        :data-prompt "次の文章を簡潔で自然な日本語に直して"}
               [:strong "文章を整える"] [:span "伝わりやすい表現に書き換える"]]]]]]
          [:div {:class "composer-dock"}
           [:form {:class "composer" :id "chat-form"}
            [:label {:class "visually-hidden" :for "prompt"} "メッセージ"]
            [:textarea {:id "prompt" :name "prompt" :rows 1
                        :placeholder (str brand " にメッセージ")
                        :autocomplete "off" :aria-describedby "composer-note request-status"}]
            [:div {:class "composer-toolbar"}
             [:div {:class "composer-context"}
              [:select {:class "model-pill" :id "model-select" :aria-label "モデル"
                        :data-provider provider}
               [:option {:value model} model]]
              [:span {:class "state-chip"} (if cloud? "接続先を確認" "Local only")]]
             [:div
              [:button {:class "composer-button" :id "send-button" :type "submit"
                        :aria-label "送信" :title "送信（Enter）"} "↑"]
              [:button {:class "composer-button composer-button--stop"
                        :id "stop-button" :type "button" :hidden true
                        :aria-label "生成を停止" :title "生成を停止（Esc）"} "■"]]]
            [:p {:class "composer-note" :id "composer-note"}
             "Enterで送信 · Shift+Enterで改行 · AIの回答は確認してください"]]
           [:p {:class "visually-hidden" :id "request-status"
                :role "status" :aria-live "polite"}
            "ローカルモデルを準備中です。"]]]]
        [:section {:class "view" :data-view-panel "worker" :hidden true}
         (view-header "Worker"
                      "時間のかかる指示をキューに積み、チャットを離れても手元のモデルが順番に処理します。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "worker-source"} "worker キューを確認中…"]]
         [:div {:class "local-card"}
          (dds/heading 2 "ジョブを登録" {:size "24"})
          [:form {:class "settings-form" :id "worker-form"}
           [:div {:class "worker-form"}
            [:div {:class "field"}
             [:label {:for "worker-prompt"} "指示"]
             [:textarea {:id "worker-prompt" :name "prompt" :rows 3 :required true
                         :placeholder "例: 今週の受信メールを要約して、返信が必要なものを挙げる"
                         :aria-describedby "worker-form-help"}]]
            [:div {:class "settings-stack"}
             [:div {:class "field"}
              [:label {:for "worker-title"} "タイトル（任意）"]
              [:input {:id "worker-title" :name "title" :autocomplete "off"
                       :placeholder "未入力なら指示の先頭を使います"}]]
             [:div {:class "field"}
              [:label {:for "worker-model"} "モデル"]
              [:select {:id "worker-model" :name "model"}
               [:option {:value ""} "既定のモデル"]]]
             [:button {:class "primary-action" :id "worker-submit" :type "submit"}
              "バックグラウンドで実行"]]]
           [:p {:class "form-help" :id "worker-form-help"}
            "実行はローカル優先のプロバイダ選択に従います。結果はこの端末の中だけに残り、再起動すると消えます。"]]]
         [:div {:class "workspace-toolbar"}
          [:div {:class "worker-summary" :id "worker-summary"}
           [:span {:class "state-chip"} "確認中"]]
          [:button {:class "tool-button" :id "worker-clear" :type "button"}
           "完了したジョブを整理"]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "worker-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "worker-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "ジョブを読み込んでいます。"]]]]
        ;; ── Fleet directory ────────────────────────────────────────
        ;; The whole catalog, 1,213 actors plus the company records, with the
        ;; facets read from the catalog rather than hardcoded — a fixed filter
        ;; list would drift from the fleet the first time it grew.
        ;; ── Portfolio（事業の面）───────────────────────────────────
        ;; ADR-2607309600. BMC・system dynamics・fleet の3面は互いに join でき
        ;; ないので、Business entity が各面の鍵を1つずつ持つ。この pane は
        ;; その面の「今どこまで解決できるか」だけを出す — 解析値は出さない。
        ;; 未紐付け・未解決・不在・解析不能を区別して描き、無い面を空の面と
        ;; して描かないのがこの pane の唯一の仕事。
        [:section {:class "view" :data-view-panel "portfolio" :hidden true}
         (view-header "Portfolio"
                      (str "事業ごとに、Canvas(BMC)・Loops(XMILE)・参与している blueprint・"
                           "repo・法人実体 の5面がどこまで結び付いているかを表示します。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "portfolio-source"} "事業を確認中…"]]
         [:div {:class "security-callout" :id "portfolio-workspace"
                :role "status" :aria-live "polite"}
          "workspace checkout の状態を確認中…"]
         [:div {:class "stat-row" :id "portfolio-stats"}]
         [:div {:class "local-card"}
          (dds/heading 2 "事業を追加" {:size "24"})
          [:p {:class "form-help"}
           (str "slug は ADR や commit でこの事業を指す名前です。どの repo や canvas が"
                "どの事業かは判断なので、名前から推測して自動で紐付けることはしません。")]
          [:form {:class "settings-form" :id "portfolio-create-form"}
           [:div {:class "field"}
            [:label {:for "portfolio-slug"} "slug"]
            [:input {:id "portfolio-slug" :name "slug" :required true
                     :autocomplete "off"
                     :pattern "[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]"
                     :placeholder "cloud-itonami-5820"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-name"} "表示名（任意）"]
            [:input {:id "portfolio-name" :name "name" :autocomplete "off"
                     :placeholder "未入力なら slug を使います"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-note"} "メモ（任意）"]
            [:input {:id "portfolio-note" :name "note" :autocomplete "off"}]]
           [:button {:class "primary-action" :type "submit"} "事業を追加"]]
          [:p {:class "form-help" :id "portfolio-create-status" :aria-live "polite"}]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "portfolio-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "portfolio-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "事業を選ぶと、5面の状態を表示します。"]]]
         [:div {:class "local-card" :id "portfolio-bind-card" :hidden true}
          (dds/heading 2 "面を紐付ける" {:size "24"})
          [:p {:class "form-help"}
           (str "workspace に無い canvas や repo を指定しても保存します — "
                "「この事業はこの canvas に属する」は事業についての真の記述で、"
                "checkout が無いことは別の事実（:unresolvable）として表示します。")]
          [:form {:class "settings-form" :id "portfolio-bind-form"}
           [:div {:class "field"}
            [:label {:for "portfolio-bind-canvas"} "Canvas — :canvas/product"]
            [:input {:id "portfolio-bind-canvas" :autocomplete "off"
                     :placeholder "cloud-itonami"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-bind-model"} "Loops — XMILE モデルの相対パス"]
            [:input {:id "portfolio-bind-model" :autocomplete "off"
                     :placeholder "orgs/kotoba-lang/loop-system-dynamics/…"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-bind-adoptions"} "参与している blueprint（カンマ区切り）"]
            [:input {:id "portfolio-bind-adoptions" :autocomplete "off"
                     :placeholder "cloud-itonami-isic-5820"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-bind-repos"} "repo path（カンマ区切り）"]
            [:input {:id "portfolio-bind-repos" :autocomplete "off"
                     :placeholder "orgs/cloud-itonami/cloud-itonami-app"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-bind-leverage"} "Leverage — ledger の相対パス"]
            [:input {:id "portfolio-bind-leverage" :autocomplete "off"
                     :placeholder "orgs/kotoba-lang/loop-system-dynamics/ledger/…"}]]
           [:div {:class "field"}
            [:label {:for "portfolio-bind-lei"} "法人実体 LEI"]
            [:input {:id "portfolio-bind-lei" :autocomplete "off"
                     :placeholder "ZSN2LWNPYW6ISMRUC664"}]]
           [:button {:class "primary-action" :type "submit"} "紐付けを保存"]]
          [:p {:class "form-help" :id "portfolio-bind-status" :aria-live "polite"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "未割当の参与" {:size "24"})
          [:p {:class "form-help" :id "portfolio-unassigned-caveat"}
           "参与を表明したが、どの事業にも紐付いていない blueprint です。"]
          [:ul {:class "record-list__items" :id "portfolio-unassigned"}
           [:li {:class "empty-state"} "確認中…"]]]]

        ;; ── Canvas（事業の仮説）─────────────────────────────────────
        ;; 読みは fold 済み投影（superproject の `gftd canvas datoms` が生成）、
        ;; 書きは提案。canvas-ledger は governed append-only で、このアプリに
        ;; governor は無い。だから ledger へ書く経路は「失敗する route」ではなく
        ;; 「存在しない」。提案が着地したかは投影を読み直して判定する（保存しない）。
        [:section {:class "view" :data-view-panel "canvas" :hidden true}
         (view-header "Canvas"
                      (str "事業に紐付いた lean canvas（BMC）と仮説。"
                           "変更はこのアプリからは提案までで、ledger へは governor が入れます。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "canvas-source"} "事業を確認中…"]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "canvas-business"} "事業"]
          [:select {:id "canvas-business"}
           [:option {:value ""} "事業を選択…"]]
          [:span {:class "result-count" :id "canvas-meta"}]]
         [:div {:class "security-callout" :id "canvas-state"
                :role "status" :aria-live "polite"}
          "canvas を確認中…"]
         [:div {:class "canvas-grid" :id "canvas-blocks"}]
         [:div {:class "local-card"}
          (dds/heading 2 "仮説と gate" {:size "24"})
          [:p {:class "form-help"}
           (str "gate は metrics が在るときだけ付きます。測っていない仮説に gate の"
                "状態は表示しません — それは失敗ではなく未測定です。")]
          [:ul {:class "record-list__items" :id "canvas-hypotheses"}
           [:li {:class "empty-state"} "事業を選ぶと表示します。"]]]
         [:div {:class "local-card" :id "canvas-propose-card" :hidden true}
          (dds/heading 2 "変更を提案する" {:size "24"})
          [:p {:class "form-help" :id "canvas-authority"}
           "このアプリは canvas-ledger に書きません。"]
          [:form {:class "settings-form" :id "canvas-propose-form"}
           [:div {:class "field"}
            [:label {:for "canvas-propose-action"} "操作"]
            [:select {:id "canvas-propose-action"}
             [:option {:value "add-item"} "item を追加 (canvas add)"]
             [:option {:value "retract-item"} "item を撤回 (canvas retract)"]
             [:option {:value "note"} "note を差替 (canvas note)"]]]
           [:div {:class "field"}
            [:label {:for "canvas-propose-block"} "block"]
            [:select {:id "canvas-propose-block"}
             [:option {:value ""} "block を選択…"]]]
           [:div {:class "field"}
            [:label {:for "canvas-propose-value"} "内容"]
            [:textarea {:id "canvas-propose-value" :rows 2 :required true}]]
           [:div {:class "field"}
            [:label {:for "canvas-propose-reason"} "理由（任意）"]
            [:input {:id "canvas-propose-reason" :autocomplete "off"}]]
           [:div {:class "field"}
            [:label {:for "canvas-propose-by"} "提案者"]
            [:input {:id "canvas-propose-by" :required true :autocomplete "off"
                     :placeholder "山田 太郎"}]]
           [:button {:class "primary-action" :type "submit"} "提案を記録"]]
          [:p {:class "form-help" :id "canvas-propose-status" :aria-live "polite"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "提案の状態" {:size "24"})
          [:p {:class "form-help"}
           (str "着地したかは投影を読み直して判定します（保存された値ではありません）。"
                "workspace checkout が無いときは landed とも awaiting とも言えません。")]
          [:ul {:class "record-list__items" :id "canvas-proposals"}
           [:li {:class "empty-state"} "まだ提案はありません。"]]]]

        ;; ── Loops（stock-flow 構造とシミュレーション）───────────────
        ;; 実行は xmile.execute/run（OASIS XMILE 1.0、Euler/RK4）。第2の
        ;; シミュレータは書かない。計算できなかった軌跡は空の系列として
        ;; 描かない — engine 自身のメッセージを出す。leverage の band は
        ;; dynamics.core から読む（このアプリで言い換えない）。
        [:section {:class "view" :data-view-panel "loops" :hidden true}
         (view-header "Loops"
                      (str "事業に紐付いた stock-flow モデルを実際に走らせ、"
                           "介入の効きどころ（Meadows band）を並べます。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "loops-source"} "事業を確認中…"]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "loops-business"} "事業"]
          [:select {:id "loops-business"}
           [:option {:value ""} "事業を選択…"]]
          [:span {:class "result-count" :id "loops-meta"}]]
         [:div {:class "security-callout" :id "loops-model-state"
                :role "status" :aria-live "polite"}
          "モデルを確認中…"]
         [:div {:class "local-card"}
          (dds/heading 2 "構造" {:size "24"})
          [:p {:class "form-help"}
           "stock は状態、flow はその微分です。矢印は XMILE の inflow / outflow 宣言そのものです。"]
          [:ul {:class "record-list__items" :id "loops-structure"}
           [:li {:class "empty-state"} "事業を選ぶと表示します。"]]]
         [:div {:class "local-card"}
          [:div {:class "view-header" :style "margin-bottom:1rem"}
           [:div
            (dds/heading 2 "シミュレーション" {:size "24"})
            [:p {:class "form-help" :id "loops-sim-note"}
             (str "変数ごとに別の軸で描きます — stock（repos）と flow（repos/日）を"
                  "同じ軸に載せるのは誤りです。")]]
           [:button {:class "tool-button" :type "button" :id "loops-table-toggle"
                     :aria-pressed "false"} "表で見る"]]
          [:div {:class "sm-grid" :id "loops-series"}]
          [:div {:id "loops-table" :hidden true}]]
         [:div {:class "local-card"}
          (dds/heading 2 "介入の効きどころ" {:size "24"})
          [:p {:class "form-help" :id "loops-leverage-caveat"}
           "leverage ledger を確認中…"]
          [:ul {:class "record-list__items" :id "loops-leverage"}
           [:li {:class "empty-state"} "事業を選ぶと表示します。"]]
          [:p {:class "source-note" :id "loops-strength"}]]
         [:div {:class "local-card"}
          (dds/heading 3 "Meadows band" {:size "18"})
          [:p {:class "form-help"}
           "重みは Meadows (1999) の順序を近似した監査可能な heuristic であって、測定された物理定数ではありません。"]
          [:ul {:class "data-list" :id "loops-bands"}]]]

        ;; ── Repos（事業の実装と成熟度）──────────────────────────────
        ;; repo-taxonomy と repo-maturity を :repo/path で join する。未評価の軸は
        ;; bar を描かない — 幅 0 の bar と score 0 は見分けが付かず、stage-score は
        ;; 3,899 repo のうち 2,732 が未評価。平均も評価済みだけで取り、除外件数を出す。
        [:section {:class "view" :data-view-panel "repos" :hidden true}
         (view-header "Repos"
                      (str "事業が実装されている repository と、その成熟度。"
                           "未評価の軸は 0 ではなく未評価として表示します。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "repos-source"} "事業を確認中…"]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "repos-business"} "事業"]
          [:select {:id "repos-business"}
           [:option {:value ""} "事業を選択…"]]
          [:span {:class "result-count" :id "repos-meta"}]]
         [:div {:class "security-callout" :id "repos-plane"
                :role "status" :aria-live "polite"}
          "generated plane を確認中…"]
         [:div {:class "stat-row" :id "repos-stats"}]
         [:ul {:class "record-list__items" :id "repos-list"}
          [:li {:class "empty-state"} "事業を選ぶと表示します。"]]]

        ;; ── Metrics（実測と、その古さ）───────────────────────────────
        ;; 鮮度を最初に出す。:funnel の形は product ごとに違うので統一しない —
        ;; freeClaim を signup と同一視する判断の根拠がこのアプリに無い。
        ;; requests は traffic-quality と必ず同じ塊で出す。
        [:section {:class "view" :data-view-panel "metrics" :hidden true}
         (view-header "Metrics"
                      (str "product の実測値と、その測定がいつのものか。"
                           "古い測定を現在の数値として出しません。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "metrics-source"} "事業を確認中…"]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "metrics-business"} "事業"]
          [:select {:id "metrics-business"}
           [:option {:value ""} "事業を選択…"]]
          [:span {:class "result-count" :id "metrics-meta"}]]
         [:div {:class "security-callout" :id "metrics-freshness"
                :role "status" :aria-live "polite"}
          "鮮度を確認中…"]
         [:div {:class "local-card"}
          (dds/heading 2 "トラフィック" {:size "24"})
          [:p {:class "form-help" :id "metrics-traffic-caveat"}]
          [:dl {:class "kv" :id "metrics-traffic"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "emitter の要約" {:size "24"})
          [:p {:class "form-help"}
           "測定した側が書いた 1 行をそのまま出します（数値から別の要約を作り直しません）。"]
          [:p {:class "record-detail__body" :id "metrics-signal"} "—"]
          [:p {:class "req-row__caveat" :id "metrics-top-paths"}]
          [:p {:class "source-note" :id "metrics-sources"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "product 固有の測定" {:size "24"})
          [:p {:class "form-help"}
           (str "このアプリが意味を主張しないキーです。:funnel の形は product ごとに"
                "違うので、共通の funnel に畳まずそのまま出します。")]
          [:dl {:class "kv" :id "metrics-specific"}]]]

        [:section {:class "view" :data-view-panel "fleet" :hidden true}
         (view-header "Fleet"
                      "cloud-itonami の org と repo。どれも fork して運用できる OSS 事業の設計図です。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "fleet-source"} "catalog を読み込み中…"]]
         [:div {:class "local-card"}
          [:form {:class "settings-form" :id "fleet-filter-form"}
           [:div {:class "field"}
            [:label {:for "fleet-text"} "検索"]
            [:input {:id "fleet-text" :name "text" :type "search"
                     :placeholder "id・名称・ドメイン"}]]
           [:div {:class "field"}
            [:label {:for "fleet-role"} "族"]
            [:select {:id "fleet-role" :name "role"}
             [:option {:value ""} "すべて"]]]
           [:div {:class "field"}
            [:label {:for "fleet-maturity"} "成熟度"]
            [:select {:id "fleet-maturity" :name "maturity"}
             [:option {:value ""} "すべて"]]]
           [:div {:class "field"}
            [:label {:for "fleet-iso3166"} "管轄"]
            [:select {:id "fleet-iso3166" :name "iso3166"}
             [:option {:value ""} "すべて"]]]
           [:div {:class "field field--checkbox"}
            [:label {:for "fleet-callable"}
             [:input {:id "fleet-callable" :name "callable" :type "checkbox"}]
             " 稼働しているものだけ"]]]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "fleet-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "fleet-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "blueprint を選ぶと、運用に何が要るかを表示します。"]]]]

        ;; ── 事業者としての参与 ─────────────────────────────────────
        ;; ① 発見 → ② 適合 → ③ 要件 → ④ 表明 → ⑤ 稼働。
        ;; The two things the pane must never imply: that the app verified a
        ;; licence (it cannot), and that a blueprint has a deploy path when it
        ;; does not. Both are rendered as their own state with the reason.
        [:section {:class "view" :data-view-panel "operator" :hidden true}
         (view-header "事業者として参与する"
                      "自分の業種・職種・管轄を登録すると、運用できる blueprint と、運用に必要なものが分かります。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "operator-source"} "事業者プロファイルを確認中…"]]
         [:div {:class "stat-row" :id "operator-stats"}]
         [:div {:class "local-card"}
          (dds/heading 2 "① 事業者プロファイル" {:size "24"})
          [:p {:class "form-help"}
           "業種(ISIC)・職種(ISCO)・管轄(ISO 3166)は、適合する blueprint を絞るためだけに使います。"]
          [:form {:class "settings-form" :id "operator-profile-form"}
           [:div {:class "field"}
            [:label {:for "operator-name"} "事業者名"]
            [:input {:id "operator-name" :name "name" :required true}]]
           [:div {:class "field"}
            [:label {:for "operator-isic"} "業種 ISIC（カンマ区切り）"]
            [:input {:id "operator-isic" :name "isic" :placeholder "6910, 6920"}]]
           [:div {:class "field"}
            [:label {:for "operator-isco"} "職種 ISCO-08（カンマ区切り）"]
            [:input {:id "operator-isco" :name "isco" :placeholder "2611"}]]
           [:div {:class "field"}
            [:label {:for "operator-iso3166"} "管轄 ISO 3166（カンマ区切り）"]
            [:input {:id "operator-iso3166" :name "iso3166" :placeholder "JPN"}]]
           [:div {:class "field"}
            [:label {:for "operator-tech"} "保有技術（カンマ区切り）"]
            [:input {:id "operator-tech" :name "technologies"
                     :placeholder "identity, forms, audit-ledger"}]]
           [:fieldset {:class "field"}
            [:legend "許認可"]
            [:p {:class "form-help" :id "operator-licence-caveat"}
             "自己表明です。このアプリは許認可の実在を検証していません。"]
            [:div {:class "field"}
             [:label {:for "operator-licence-kind"} "種別"]
             [:input {:id "operator-licence-kind" :placeholder "bengoshi"}]]
            [:div {:class "field"}
             [:label {:for "operator-licence-authority"} "登録先"]
             [:input {:id "operator-licence-authority" :placeholder "東京弁護士会"}]]
            [:div {:class "field"}
             [:label {:for "operator-licence-number"} "登録番号"]
             [:input {:id "operator-licence-number" :placeholder "第12345号"}]]
            [:div {:class "field"}
             [:label {:for "operator-licence-by"} "表明者"]
             [:input {:id "operator-licence-by" :placeholder "山田 太郎"}]]]
           [:button {:class "tool-button" :type "submit"} "プロファイルを保存"]]]
         [:div {:class "local-card"}
          (dds/heading 2 "② 適合する blueprint" {:size "24"})
          [:p {:class "form-help"}
           "適合度は 業種3点・職種3点・管轄1点 の単純な加算です。0点のものは出しません。"]
          [:ul {:class "record-list__items" :id "operator-matches"}
           [:li {:class "empty-state"} "プロファイルを保存すると表示します。"]]]
         [:div {:class "local-card"}
          (dds/heading 2 "③〜⑤ 参与している blueprint" {:size "24"})
          [:ul {:class "record-list__items" :id "operator-adoptions"}
           [:li {:class "empty-state"} "まだ参与を表明していません。"]]]]

        [:section {:class "view" :data-view-panel "organisms" :hidden true}
         (view-header "Artificial organisms"
                      "active organization に所属するAO workerと、Tamakiの実活動を確認します。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "organism-source"}
           "OrganismWorker directory を確認中…"]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "organism-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "organism-detail"
                     :aria-live "polite"}
           [:div {:class "empty-state"} "AO workerを読み込んでいます。"]]]
         [:div {:class "local-card"}
          (dds/heading 2 "Governed intent" {:size "24"})
          [:p {:class "form-help" :id "organism-intent-state"
               :role "status" :aria-live "polite"}
           "admitは実行完了ではありません。Tamakiのcapability・homeostasis・HITL gateへ送ります。"]
          [:form {:class "settings-form" :id "organism-intent-form"}
           [:div {:class "field"}
            [:label {:for "organism-intent-summary"} "Objective / intent"]
            [:textarea {:id "organism-intent-summary" :name "summary"
                        :rows 3 :required true
                        :maxlength 4000
                        :placeholder "Tamakiに検討・実行してほしいobjectiveを入力"}]]
           [:div {:class "worker-actions"}
            [:button {:class "primary-action" :id "organism-intent-submit"
                      :type "submit"} "Tamaki inboxへ送る"]
            [:button {:class "tool-button" :id "organism-stop"
                      :type "button"} "Governed stopを要求"]]]
          (dds/heading 3 "Intent receipts" {:size "18"})
          [:p {:class "source-note" :id "organism-receipt-state"}
           "receiptを確認中…"]
          [:ul {:class "data-list" :id "organism-receipts"}
           [:li {:class "skeleton"}]]]
         [:div {:class "local-card"}
          [:div {:class "view-header"}
           [:div
            (dds/heading 2 "Live activity" {:size "24"})
            [:p {:class "source-note" :id "organism-activity-state"}
             "cursorを準備中…"]]
           [:span {:class "state-chip" :id "organism-live-state"} "確認中"]]
          [:ul {:class "data-list organism-activity" :id "organism-activity"}
           [:li {:class "skeleton"}]]]]
        [:section {:class "view" :data-view-panel "inbox" :hidden true}
         (view-header "Inbox" "kotoba-lang/mail のメールボックスモデルで、受信履歴を安全に検索・確認します。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "inbox-source"} "m365-archive を読み込み中…"]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "inbox-search"} "メールを検索"]
          [:input {:class "workspace-search" :id "inbox-search" :type "search"
                   :placeholder "差出人、件名、本文を検索" :autocomplete "off"}]
          [:span {:class "result-count" :id "inbox-visible-count"} "読み込み中…"]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "inbox-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "inbox-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "メールを読み込んでいます。"]]]]
        [:section {:class "view" :data-view-panel "projects" :hidden true}
         (view-header "Projects" "GitHub Projects と同じデータを、Table・Board・Roadmap の視点で整理します。")
         [:div {:class "local-card"}
          [:div {:class "view-header" :style "margin-bottom:1rem"}
           [:div
            (dds/heading 2 "kotoba-lang / kotoba" {:size "24"})
            [:p {:class "source-note"} [:span {:class "source-dot"}]
             [:span {:id "projects-source"} "com-github を確認中…"]]]
           [:span {:class "state-chip" :id "projects-state"} "確認中"]]
          [:div {:class "view-switcher" :aria-label "Project 表示"}
           [:button {:type "button" :aria-pressed "true"} "Table"]
           [:button {:type "button" :aria-pressed "false"} "Board"]
           [:button {:type "button" :aria-pressed "false"} "Roadmap"]]
          [:ul {:class "data-list" :id "project-list"} [:li {:class "skeleton"}]]]]
        [:section {:class "view" :data-view-panel "drive" :hidden true}
         (view-header "Drive"
                      (str "kotoba-lang/drive のファイルモデルで、OneDrive アーカイブを"
                           "検索・確認し、Sheets / Docs / Forms / Slides を作成します。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "drive-source"} "m365-archive を読み込み中…"]]
         [:div {:class "drive-create-bar"}
          [:div {:class "drive-create" :id "drive-create"
                 :role "group" :aria-label "新しいドキュメントを作成"}]
          [:span {:class "result-count" :id "drive-quota"}]
          [:p {:class "drive-create__status" :id "drive-create-status" :aria-live "polite"}]]
         [:nav {:class "drive-folders" :id "drive-folders" :aria-label "フォルダ"}]
         [:div {:class "drive-folders"}
          [:label {:class "visually-hidden" :for "drive-upload"} "ファイルをアップロード"]
          ;; Any bytes: a PDF, an image, a zip. Not an import — an import
          ;; becomes one of the four surfaces and this stays a file.
          [:input {:class "workspace-search" :id "drive-upload" :type "file"}]
          [:label {:class "visually-hidden" :for "drive-folder-name"} "新しいフォルダの名前"]
          ;; An inline field rather than window.prompt, for the same reason
          ;; renaming uses one: a modal blocks the page to collect a single
          ;; string this bar already has room for.
          [:input {:class "workspace-search" :id "drive-folder-name" :type "text"
                   :placeholder "新しいフォルダの名前" :autocomplete "off"}]]
         [:div {:class "workspace-toolbar"}
          [:label {:class "visually-hidden" :for "drive-search"} "ファイルを検索"]
          [:input {:class "workspace-search" :id "drive-search" :type "search"
                   :aria-describedby "drive-found"
                   :placeholder "ファイル名、フォルダー、種類を検索" :autocomplete "off"}]
          [:span {:class "result-count" :id "drive-visible-count"} "読み込み中…"]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "drive-list"} [:li {:class "skeleton"}]]
           [:button {:class "tool-button" :type "button" :id "drive-more" :hidden true}]]
          [:article {:class "record-detail" :id "drive-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "ファイルを読み込んでいます。"]]]
         [:section {:class "sharing" :id "drive-found" :hidden true
                    :aria-live "polite"}]
         [:section {:class "drive-trash" :id "drive-trash" :hidden true}
          [:div {:class "drive-trash__head"}
           (dds/heading 2 "ゴミ箱" {:size "20"})
           [:span {:class "result-count" :id "drive-trash-count"}]
           [:button {:class "tool-button" :type "button" :id "drive-trash-empty"}
            "ゴミ箱を空にする"]]
          [:p {:class "drive-create__status"}
           "ゴミ箱にあるものも容量を使っています。完全に削除すると容量が戻ります。"]
          [:ul {:class "drive-trash__list" :id "drive-trash-list"}]]]
        [:section {:class "view" :data-view-panel "scheduler" :hidden true}
         (view-header "Scheduler" "kotoba-lang/calendar と EventKit で、この先7日間を日ごとに整理します。")
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "calendar-source"} "EventKit を読み込み中…"]]
         [:div {:class "date-rail" :id "calendar-days" :aria-label "日付を選択"}]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "calendar-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "calendar-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "予定を読み込んでいます。"]]]]
        [:section {:class "view" :data-view-panel "storage" :hidden true}
         (view-header "Storage"
                      (str "kotoba-lang/cloud-filecoin の PieceCID v2 でファイルを"
                           "アドレス指定し、Filecoin mainnet の PDP 状態を直接読みます。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "storage-source"} "Filecoin mainnet に接続中…"]]
         [:div {:class "security-callout" :id "storage-write-notice"
                :role "status" :aria-live "polite"}
          [:strong "書き込みは未実装です。"]
          " このアプリは内容の PieceCID を計算して待機領域に保管し、"
          "チェーン上の状態を読みますが、ストレージ取引は作成しません。"
          "provider への転送 API は cloud-filecoin に実装済みですが、"
          "アップロードだけでは bytes は預かられるだけで、"
          "オンチェーンの addPieces で data set に入って初めて永続化されます"
          "（資金と鍵が必要）。"]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "storage-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "storage-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "Filecoin の状態を読み込んでいます。"]]]]
        [:section {:class "view" :data-view-panel "esign" :hidden true}
         (view-header "eSign"
                      (str "Drive のドキュメントを Passkey で署名します。署名の対象は"
                           "文書そのものではなく、文書 digest と「あなたが読んだ内容」の"
                           "digest を含む commitment で、その SHA-256 が WebAuthn の"
                           "challenge になります。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "esign-source"} "envelope を確認中…"]]
         ;; Stated on the screen, not only in the evidence record. A UI that
         ;; showed "署名済み" without this would be implying more than the
         ;; signature establishes, and the person reading it is the one who has
         ;; to know.
         ;; Filled in per envelope by renderEsign — the three attestations are
         ;; different legal objects and a fixed sentence would be wrong for two
         ;; of them. Defaults to the weakest, which is the honest floor before
         ;; anything is loaded.
         [:div {:class "security-callout" :id "esign-time-notice"}
          [:strong "署名時刻はこのアプリが記録したもので、認定タイムスタンプではありません。"]
          " 認定タイムスタンプ（総務大臣認定の TSA による RFC 3161）を設定していない間は、"
          "電子帳簿保存法が求める真実性の確保措置としては、この時刻だけでは足りません。"
          "署名そのもの（誰が・何に同意したか）は、この画面を離れても検証できます。"]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "esign-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "esign-detail" :aria-live "polite"}
           [:div {:class "empty-state"}
            "envelope を選ぶと、署名の対象と、署名者に表示される内容の全文を確認できます。"]]]
         [:div {:class "local-card"}
          (dds/heading 2 "署名を依頼する" {:size "24"})
          [:form {:class "settings-form" :id "esign-request-form"}
           [:label {:for "esign-document"} "ドキュメント"]
           [:select {:id "esign-document" :name "document"}]
           [:label {:for "esign-purpose"} "目的"]
           [:select {:id "esign-purpose" :name "purpose"}
            [:option {:value "contract/execute"} "契約に署名する"]
            [:option {:value "consent/give"} "同意する"]
            [:option {:value "minutes/approve"} "議事録を承認する"]
            [:option {:value "acknowledgement/receive"} "受領を確認する"]
            [:option {:value "application/submit"} "申請を提出する"]]
           ;; DIDs, not email addresses. A commitment names the key that will
           ;; sign, and a person with two Passkeys has two DIDs — asking for an
           ;; address would make the app pick one of them silently.
           [:label {:for "esign-signers"} "署名者の DID（1 行に 1 件）"]
           [:textarea {:id "esign-signers" :name "signers" :rows 3
                       :placeholder "did:key:z..."}]
           [:p {:class "form-help"}
            "Passkey を登録していない相手は指定できません。署名待ちのまま永久に"
            "完了しない envelope を作らないため、その場で拒否されます。"]
           [:button {:class "primary-action" :type "submit"} "署名を依頼する"]]
          [:p {:class "visually-hidden" :id "esign-request-status"
               :role "status" :aria-live "polite"}]]]
        [:section {:class "view" :data-view-panel "contracts" :hidden true}
         (view-header "Contracts"
                      (str "契約している継続課金を、kagi の vault から復号して読みます。"
                           "保管は端末とクラウドの両方で暗号文のままで、"
                           "次回課金日も予告期限もこの画面の中で計算されます。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "contracts-source"} "vault を確認中…"]]
         [:div {:class "security-callout" :id "contracts-e2e-notice"}
          [:strong "この一覧はサーバ側では検索できません。"]
          " 契約は end-to-end で封緘されているため、集計も期限の判定も"
          "端末で unlock したあとにしか行えません。"
          "解約の実行はこの画面からは行いません — 表示するのは開示された手順だけです。"]
         [:div {:class "local-card" :id "contracts-totals"}]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "contracts-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "contracts-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "契約を選ぶと、解約手順と予告期限を表示します。"]]]]
        [:section {:class "view" :data-view-panel "settings" :hidden true}
         (view-header "Settings" "Cloud Itonami の組織・ユーザーと、外部サービスへの委任接続を管理します。")
         [:p {:class "visually-hidden" :id "identity-status"
              :role "status" :aria-live "polite"} "アカウント情報を確認中です。"]
         [:div {:class "settings-notice" :id "connection-notice" :hidden true}]
         [:div {:class "security-callout" :id "passkey-gate-notice"
                :role "status" :aria-live "polite"}
          [:strong "Passkey 登録が必須です。"]
          " 登録が完了するまで、この端末ではワークスペースとチャットを利用できません。"]
         [:div {:class "local-card" :id "identity-onboarding"}
         (dds/heading 2 "Passkey で利用登録" {:size "24"
                                             :id "registration-title"})
          [:p {:class "view-lead" :id "registration-lead"}
           "入力は不要です。Passkey だけで User を作成し、Organization ID やプロフィールは後から設定できます。"]
          [:form {:class "settings-form" :id "registration-form"}
           [:p {:class "form-help"}
            "Passkey の P-256 公開鍵から User DID（did:key）を生成します。秘密鍵は端末から出ません。"]
           [:button {:class "primary-action" :id "registration-submit"
                     :type "submit"} "Passkey で登録"]]
          [:div {:class "settings-stack" :id "registered-auth" :hidden true}
           [:button {:class "primary-action" :id "passkey-signin" :type "button"}
            "Passkey でサインイン"]
           [:form {:class "settings-form" :id "enrollment-form"}
            (dds/heading 3 "招待された User" {:size "20"})
            [:div {:class "field"}
             [:label {:for "enrollment-account"} "アカウントID"]
             [:input {:id "enrollment-account" :name "account-id"
                      :required true :autocomplete "username"}]]
            [:div {:class "field"}
             [:label {:for "enrollment-code"} "Enrollment code"]
             [:input {:id "enrollment-code" :name "enrollment-code"
                      :required true :autocomplete "one-time-code"}]]
            [:button {:class "tool-button" :id "enrollment-submit" :type "submit"}
             "Passkey を登録して参加"]]]]
         [:div {:class "settings-stack" :id "identity-workspace" :hidden true}
          [:div {:class "identity-summary"}
           [:div {:class "identity-summary__avatar" :id "identity-avatar"
                  :aria-hidden "true"} "U"]
           [:div {:class "identity-summary__copy"}
            [:p {:class "identity-summary__name" :id "identity-name"} "User"]
            [:p {:class "identity-summary__meta" :id "identity-email"} "—"]
            [:p {:class "identity-summary__meta" :id "identity-did"} "—"]]
           [:span {:class "state-chip"} "端末認証済み"]]
          [:div {:class "settings-grid"}
           [:div {:class "settings-stack"}
            [:div {:class "local-card"}
             (dds/heading 2 "Passkey" {:size "24"})
             [:p {:class "view-lead" :id "passkey-state"} "Passkey 状態を確認中…"]
             [:button {:class "primary-action" :id "passkey-register"
                       :type "button"} "Passkey を登録"]]
            [:div {:class "local-card"}
             (dds/heading 2 "Private Email Relay" {:size "24"})
             [:p {:class "view-lead" :id "cloud-alias-state"}
              "グローバル予約状態を確認中…"]
             [:div {:class "field"}
              [:label {:for "cloud-alias-destination"} "実メール転送先"]
              [:input {:id "cloud-alias-destination" :type "email"
                       :autocomplete "email"
                       :placeholder "private@example.jp"}]]
             [:p {:class "form-help"}
              "公開アドレスへの受信を転送し、返信時も実メールアドレスを相手に開示しません。"]
             [:button {:class "primary-action" :id "cloud-alias-reserve"
                       :type "button"} "グローバル予約して確認メールを送信"]]
            [:div {:class "local-card"}
             (dds/heading 2 "サービス接続" {:size "24"})
             [:p {:class "view-lead"}
              "読み取り権限を用途別に確認し、owner の操作で接続します。接続先の token は macOS Keychain に保存されます。"]
             [:div {:class "connector-list" :id "connector-list"}
              [:div {:class "skeleton"}]]]
            [:div {:class "security-callout"}
             [:strong "認証境界"]
             " Passkey は challenge・origin・RP ID・署名・user verification・counter をサーバーで検証します。OAuth は state を一度だけ使用し、PKCE S256 と10分の期限を適用します。"]]
           [:aside {:class "settings-stack"}
            [:div {:class "local-card"}
             (dds/heading 2 "Organization" {:size "20"})
             [:p {:class "identity-summary__name" :id "organization-name"} "—"]
             [:p {:class "identity-summary__meta" :id "organization-domain"} "—"]
             [:p {:class "identity-summary__meta" :id "organization-did"} "—"]
             [:form {:class "settings-form" :id "organization-form"}
              [:div {:class "field"}
               [:label {:for "organization-id"} "Organization ID"]
               [:input {:id "organization-id" :name "organization-id"
                        :required true :autocomplete "off"
                        :pattern "[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]"
                        :placeholder "my-organization"}]
               [:span {:class "form-help"}
                "設定後は変更できません。管理ドメインと owner の公開アドレスを発行します。"]]
              [:button {:class "primary-action" :id "organization-submit"
                        :type "submit"} "Organization ID を設定"]]
             [:form {:class "settings-form" :id "organization-create-form"}
              (dds/heading 3 "別のOrganizationを作成" {:size "16"})
              [:div {:class "field"}
               [:label {:for "new-organization-name"} "表示名"]
               [:input {:id "new-organization-name" :name "organization-name"
                        :autocomplete "organization"
                        :placeholder "Etzhayyim"}]]
              [:div {:class "field"}
               [:label {:for "new-organization-id"} "Organization ID"]
               [:input {:id "new-organization-id" :name "organization-id"
                        :required true :autocomplete "off"
                        :pattern "[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]"
                        :placeholder "etzhayyim"}]]
              [:button {:class "tool-button" :id "organization-create"
                        :type "submit"} "Organizationを追加"]]
             [:form {:class "settings-form"
                     :id "organization-invitation-form"}
              (dds/heading 3 "既存Organizationへ参加" {:size "16"})
              [:p {:class "form-help" :id "organization-invitation-state"}
               "参加待ちの招待を確認中…"]
              [:div {:class "field"}
               [:label {:for "organization-invitation-code"} "招待コード"]
               [:input {:id "organization-invitation-code"
                        :name "invitation-code"
                        :required true
                        :autocomplete "one-time-code"}]]
              [:button {:class "tool-button"
                        :id "organization-invitation-accept"
                        :type "submit"}
               "招待を承認して参加"]]
             [:ul {:class "member-list" :id "member-list"}]]
            [:div {:class "local-card" :id "member-card"}
             (dds/heading 2 "User を追加" {:size "20"})
             [:form {:class "settings-form" :id "member-form"}
              [:div {:class "field"}
               [:label {:for "member-name"} "表示名"]
               [:input {:id "member-name" :name "display-name" :required true
                        :autocomplete "off"}]]
              [:div {:class "field"}
               [:label {:for "member-account-id"} "アカウントID"]
               [:input {:id "member-account-id" :name "account-id"
                        :required true :autocomplete "off"
                        :pattern "[a-z0-9][a-z0-9._-]{1,30}[a-z0-9]"}]
               [:span {:class "form-help"} "管理ドメインのアドレスを発行"]]
              [:div {:class "field"}
               [:label {:for "member-email"} "連絡先メール（任意）"]
               [:input {:id "member-email" :name "contact-email" :type "email"
                        :autocomplete "off"}]]
              [:div {:class "field"}
               [:label {:for "member-role"} "ロール"]
               [:select {:id "member-role" :name "role"}
                [:option {:value "member"} "Member"]
                [:option {:value "admin"} "Admin"]]]
              [:button {:class "primary-action" :type "submit"} "User を登録"]]
             [:p {:class "security-callout" :id "enrollment-result"
                  :hidden true :aria-live "polite"}]]]]]]]]])))
