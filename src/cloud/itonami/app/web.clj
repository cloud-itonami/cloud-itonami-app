(ns cloud.itonami.app.web
  "DADS-backed WebKit workspace hosted by kotoba-lang/shell."
  (:require [clojure.java.io :as io]
            [hanmen.svg :as hanmen-svg]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]))

(def base-css
  "
  body{background:var(--color-neutral-solid-gray-50)}
  button{font:inherit}
  .local-app{max-width:100%;padding:0}
  .workspace{display:grid;grid-template-columns:17rem minmax(0,1fr);min-height:100vh}
  .sidebar{position:sticky;top:0;height:100vh;box-sizing:border-box;padding:1rem;
    background:var(--color-neutral-white);border-right:1px solid var(--color-neutral-solid-gray-200);
    display:flex;flex-direction:column;gap:.75rem}
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
  /* The matrix. Scrolls inside its own container so the page body never scrolls
     sideways, and the business column stays put while the planes scroll. */
  .matrix-wrap{overflow-x:auto;margin:0 0 1rem}
  .matrix{border-collapse:collapse;font-size:.8125rem;min-width:100%}
  .matrix th,.matrix td{padding:.5rem .625rem;text-align:left;vertical-align:top;
    border-bottom:1px solid var(--color-neutral-solid-gray-200);
    white-space:nowrap}
  .matrix th{font-size:.75rem;color:var(--color-neutral-solid-gray-700)}
  .matrix tbody th{position:sticky;left:0;background:var(--color-neutral-white);
    white-space:normal;min-width:9rem}
  .matrix__state{display:inline-block;min-width:4.5rem;font-weight:700}
  /* Only 'missing' and 'stale' are warnings. 'unbound' and 'unresolvable' are
     things the app cannot decide, which is the same line operator/readiness
     draws — turning 「わからない」 into 「不可」 would be as dishonest as the
     reverse. */
  .matrix__state[data-state='measured']{color:var(--color-semantic-success-1)}
  .matrix__state[data-state='missing']{color:var(--color-semantic-error-1)}
  .matrix__state[data-state='stale']{color:var(--color-semantic-error-1)}
  .matrix__detail{display:block;font-weight:400;white-space:normal;
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
  .brand__eyebrow{display:flex;align-items:center;gap:.35em;
    margin:0 0 .25rem;color:var(--color-key-900);font-size:.75rem;
    font-weight:700;letter-spacing:.08em}
  .brand__safety-mark{width:.9em;height:.9em;flex:0 0 auto;
    fill:var(--color-semantic-success-1)}
  .brand__name{margin:0;font-size:1.25rem;font-weight:700;line-height:1.5}
  .brand__mark{display:none}
  .brand__note{margin:.25rem 0 0;color:var(--color-neutral-solid-gray-600);
    font-size:.8125rem;line-height:1.6}
  .workspace-switcher{display:grid;gap:.25rem;padding:.625rem .75rem;border-radius:.75rem;
    background:var(--color-neutral-solid-gray-50);
    border:1px solid var(--color-neutral-solid-gray-200)}
  .workspace-switcher__label{font-size:.6875rem;font-weight:700;letter-spacing:.06em;
    color:var(--color-neutral-solid-gray-600)}
  .workspace-switcher select{min-height:2.5rem;width:100%;box-sizing:border-box;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;
    padding:.5rem 2rem .5rem .625rem;background:var(--color-neutral-white);font:inherit;
    font-weight:700;color:var(--color-neutral-solid-gray-800)}
  .workspace-switcher__hint{font-size:.6875rem;line-height:1.5;
    color:var(--color-neutral-solid-gray-600)}
  .local-nav{display:flex;flex:1;min-height:0;flex-direction:column;gap:.25rem;
    overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable;
    padding-right:.25rem}
  .nav-primary{display:grid;gap:.25rem}
  .nav-overflow-panel{display:contents}
  .nav-secondary{display:grid;gap:.25rem}
  .mobile-menu-toggle,.mobile-nav-backdrop{display:none}
  .nav-section{border-top:1px solid var(--color-neutral-solid-gray-100);
    padding-top:.25rem}
  .nav-section__summary{display:flex;align-items:center;gap:.75rem;min-height:2.5rem;
    box-sizing:border-box;padding:.5rem .75rem;border-radius:.5rem;cursor:pointer;
    color:var(--color-neutral-solid-gray-700);font-size:.8125rem;font-weight:700;
    list-style:none}
  .nav-section__summary::-webkit-details-marker{display:none}
  .nav-section__summary:hover{background:var(--color-neutral-solid-gray-50)}
  .nav-section__summary:focus-visible{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px}
  .nav-section__label{flex:1}.nav-section__chevron{font-size:.75rem;transition:transform .15s ease}
  .nav-section[open] .nav-section__chevron{transform:rotate(180deg)}
  .nav-section__items{display:grid;gap:.125rem;padding:.125rem 0 .375rem .75rem}
  .nav-section__items .local-nav__item{min-height:2.5rem;padding:.375rem .625rem}
  .sidebar__utility{padding-top:.5rem;border-top:1px solid var(--color-neutral-solid-gray-200)}
  .local-nav__item{width:100%;border:0;border-radius:.5rem;background:transparent;
    color:var(--color-neutral-solid-gray-800);display:flex;align-items:center;gap:.75rem;
    min-height:2.75rem;padding:.5rem .75rem;text-align:left;cursor:pointer}
  .local-nav__item:hover{background:var(--color-neutral-solid-gray-50)}
  .local-nav__item:disabled{cursor:not-allowed;opacity:.42;background:transparent}
  .local-nav__item:focus-visible{outline:4px solid var(--color-primitive-yellow-300);outline-offset:1px}
  .local-nav__item[aria-current='page']{background:var(--color-key-50);
    color:var(--color-key-900);font-weight:700;border-left:4px solid var(--color-key-900);
    padding-left:.5rem}
  .nav-icon{width:1.5rem;text-align:center;font-size:1.1rem}
  .nav-badge{margin-left:auto;min-width:1.5rem;padding:.1rem .4rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-100);font-size:.75rem;text-align:center}
  .sidebar__status{padding:.625rem .75rem;border-radius:.5rem;
    background:var(--color-neutral-solid-gray-50);font-size:.8125rem;line-height:1.6}
  .sidebar__status strong{display:block;color:var(--color-semantic-success-2)}
  .main{min-width:0}
  .topbar{min-height:5rem;padding:1rem clamp(1rem,4vw,3rem);background:var(--color-neutral-white);
    border-bottom:1px solid var(--color-neutral-solid-gray-200);display:flex;
    align-items:center;justify-content:space-between;gap:1rem;box-sizing:border-box}
  .topbar__title{margin:0;font-size:1.25rem;line-height:1.5}
  .topbar__meta{margin:0;color:var(--color-neutral-solid-gray-600);font-size:.875rem}
  .topbar__context{display:flex;align-items:center;gap:.75rem}
  .project-select{min-height:2.5rem;max-width:18rem;border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:.625rem;background:var(--color-neutral-white);padding:.4rem 2rem .4rem .75rem;font:inherit}
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
  .capture-composer{display:grid;gap:1rem}.capture-composer textarea{width:100%;box-sizing:border-box;
    min-height:14rem;resize:vertical;border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:.625rem;padding:1rem;font:inherit;line-height:1.8;background:var(--color-neutral-white)}
  .capture-composer textarea:focus{outline:4px solid var(--color-primitive-yellow-300);
    outline-offset:1px;border-color:var(--color-key-600)}
  .capture-mode-row,.capture-filters{display:flex;align-items:center;flex-wrap:wrap;gap:.5rem}
  .capture-filters{margin:1rem 0}.capture-filter[aria-pressed='true']{background:var(--color-key-900);
    color:var(--color-neutral-white)}.capture-raw{white-space:pre-wrap;line-height:1.8;
    padding:1rem;border-radius:.5rem;background:var(--color-neutral-solid-gray-50)}
  .capture-recording{color:var(--color-semantic-error-1);font-weight:700}
  .capture-chronicle{border:1px solid var(--color-neutral-solid-gray-200);border-radius:.625rem;
    padding:1rem;background:var(--color-neutral-solid-gray-50)}.capture-chronicle__header{display:flex;
    align-items:center;justify-content:space-between;gap:.75rem;flex-wrap:wrap}
  .capture-chronicle__list{list-style:none;margin:.75rem 0 0;padding:0;display:grid;gap:.5rem}
  .capture-chronicle__item{display:grid;gap:.35rem;width:100%;text-align:left;padding:.75rem;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;background:white}
  .capture-chronicle__item:hover{border-color:var(--color-key-600)}
  .capture-source{margin:1rem 0;padding:.75rem;border-left:4px solid var(--color-key-600);
    background:var(--color-neutral-solid-gray-50)}.capture-source__text{white-space:pre-wrap;
    overflow-wrap:anywhere}
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
  /* Empty until a file that could be read is chosen, and it collapses when
     it is: a gap the width of a gesture nobody is making reads as something
     missing. */
  /* Where a comment points. A dot rather than a colour alone: a cell that
     is only tinted says nothing to anyone who cannot see the tint, and the
     mark has to survive next to the styling a cell may already carry. */
  .is-commented{position:relative}
  .is-commented::after{content:\"\";position:absolute;top:2px;right:2px;
    width:.5rem;height:.5rem;border-radius:50%;
    background:var(--color-semantic-error-1)}
  .surface-row.is-commented{outline:2px solid var(--color-semantic-error-2);
    outline-offset:2px}
  .drive-import-choice{display:flex;gap:.5rem;flex-wrap:wrap}
  .drive-import-choice:empty{display:none}
  .scheduler-create{display:flex;gap:.5rem;flex-wrap:wrap;align-items:center}
  .appointment{margin-top:1rem;display:flex;flex-direction:column;gap:.75rem}
  .appointment__people{list-style:none;margin:0;padding:0;display:flex;
    flex-direction:column;gap:.25rem}
  .appointment__person{display:flex;justify-content:space-between;gap:1rem;
    font-size:.875rem}
  /* The state is said in words beside the name; the colour is a second way
     to read the same thing, not the only way. */
  .appointment__status{color:var(--color-neutral-solid-gray-600)}
  .appointment__person--accepted .appointment__status{color:var(--color-semantic-success-1)}
  .appointment__person--declined .appointment__status{color:var(--color-semantic-error-1)}
  .appointment__answers,.appointment__invite{display:flex;gap:.5rem;flex-wrap:wrap}
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
  /* One page of an uploaded PDF. Bounded like the slide stage, and with a
     paper surface behind it — a rendering that inherits the pane's
     background stops looking like a page and starts looking like text that
     happens to be arranged oddly. */
  .page-view{margin:.5rem 0;max-width:min(100%,34rem)}
  .page-view__figure{border-radius:.5rem;overflow:auto;max-height:32rem;
    border:1px solid var(--color-neutral-solid-gray-300, #d0d7de);
    background:var(--color-neutral-solid-gray-50, #f6f8fa)}
  /* `hanmen` paints in currentColor and picks no colour of its own, which is
     what makes one rendering right in both themes — so the ink is this
     pane's text colour and the paper is a token, decided here. */
  .page-view__figure .hanmen-page{--hanmen-paper:var(--color-neutral-solid-white, #fff)}
  /* Bounded like the chart: a slide is 10 inches wide and the pane is not. */
  .slide-preview{margin:.5rem 0;max-width:min(100%,28rem);border-radius:.5rem;
    border:1px solid var(--color-neutral-solid-gray-300, #d0d7de);overflow:hidden}
  .slide-preview svg{display:block;width:100%;height:auto}
  .chart-card{margin:.5rem 0}
  .chart-card__figure{max-width:100%;overflow-x:auto}
  .chart-card__figure svg{display:block;max-width:100%;height:auto}
  .export-notes{display:flex;flex-direction:column;gap:.25rem;margin-top:.25rem}
  .export-notes .surface-note{align-self:flex-start}
  .export-notes__list{margin:0;padding-left:1.25rem;display:flex;
    flex-direction:column;gap:.125rem}
  .surface-grid{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
  .surface-grid th{color:var(--color-neutral-solid-gray-600);font-size:.75rem;
    font-weight:400;padding:.25rem}
  .surface-grid td{padding:1px}
  /* A computed cell reads as a result rather than as something typed. The
     value is right-aligned the way a number is in every spreadsheet. */
  .surface-cell--computed{text-align:right;
    background:var(--color-neutral-solid-gray-50, transparent)}
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
  /* A picture is bounded by the page rather than by its own pixels: a
     photograph from a phone is wider than any column it lands in. */
  .doc-figure{margin:1rem 0}
  .doc-figure img{max-width:100%;height:auto;display:block}
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
  .messenger-view{max-width:none}
  .messenger-shell{display:grid;grid-template-columns:minmax(14rem,.65fr) minmax(24rem,1.35fr) minmax(15rem,.7fr);
    min-height:36rem;border:1px solid var(--color-neutral-solid-gray-200);border-radius:.75rem;
    overflow:hidden;background:var(--color-neutral-white)}
  .messenger-rail,.messenger-context{min-width:0;background:var(--color-neutral-solid-gray-50)}
  .messenger-rail{border-right:1px solid var(--color-neutral-solid-gray-200)}
  .messenger-context{border-left:1px solid var(--color-neutral-solid-gray-200);padding:1rem}
  .messenger-pane__header{padding:1rem;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .messenger-pane__header h2,.messenger-context h3{margin:0;font-size:1rem}
  .messenger-pane__header p,.messenger-context p{margin:.25rem 0 0;color:var(--color-neutral-solid-gray-600);
    font-size:.75rem;line-height:1.5}
  .messenger-create{display:grid;gap:.5rem;padding:1rem;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .messenger-create input,.messenger-create select,.messenger-composer textarea,.messenger-composer select{box-sizing:border-box;width:100%;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;background:var(--color-neutral-white);
    padding:.625rem .75rem;font:inherit}
  .messenger-create select[multiple]{min-height:6rem}
  .messenger-thread{display:flex;min-width:0;flex-direction:column;min-height:36rem}
  .messenger-messages{list-style:none;display:flex;flex:1;flex-direction:column;gap:1rem;
    max-height:34rem;overflow:auto;margin:0;padding:1.25rem}
  .messenger-message{max-width:85%;padding:.75rem 1rem;border-radius:.75rem;
    background:var(--color-neutral-solid-gray-50);border:1px solid var(--color-neutral-solid-gray-200)}
  .messenger-message[data-own='true']{align-self:flex-end;background:var(--color-key-50)}
  .messenger-message__head{display:flex;justify-content:space-between;gap:1rem;font-size:.75rem}
  .messenger-message__body{margin:.375rem 0 0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.65}
  .messenger-message__security{display:block;margin-top:.375rem;color:var(--color-neutral-solid-gray-600);
    font-size:.6875rem}
  .messenger-composer{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:.5rem;
    padding:1rem;border-top:1px solid var(--color-neutral-solid-gray-200)}
  .messenger-composer textarea{min-height:3rem;resize:vertical}
  .messenger-principals,.messenger-quarantine{list-style:none;margin:.75rem 0 1.25rem;padding:0}
  .messenger-principals li,.messenger-quarantine li{padding:.625rem 0;
    border-bottom:1px solid var(--color-neutral-solid-gray-200);font-size:.8125rem}
  .messenger-principal__row{display:flex;align-items:center;justify-content:space-between;gap:.5rem}
  .messenger-kind{color:var(--color-neutral-solid-gray-600);font-size:.6875rem}
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
  .memory-card{overflow:hidden;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:1rem;background:var(--color-neutral-white)}
  .memory-row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:1.5rem;
    align-items:center;padding:1.25rem 1.5rem;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .memory-row:last-child{border-bottom:0}.memory-row h2{margin:0;font-size:1rem}
  .memory-row p{margin:.35rem 0 0;max-width:50rem;color:var(--color-neutral-solid-gray-600);
    line-height:1.6}.memory-row__actions{display:flex;align-items:center;gap:.75rem}
  .memory-switch{position:relative;display:inline-flex;align-items:center;cursor:pointer}
  .memory-switch input{position:absolute;opacity:0;pointer-events:none}
  .memory-switch span{display:block;width:3rem;height:1.75rem;border-radius:999px;
    background:var(--color-neutral-solid-gray-300);transition:.15s}
  .memory-switch span::after{content:'';display:block;width:1.25rem;height:1.25rem;
    margin:.25rem;border-radius:50%;background:var(--color-neutral-white);transition:.15s}
  .memory-switch input:checked + span{background:var(--color-primitive-blue-800)}
  .memory-switch input:checked + span::after{transform:translateX(1.25rem)}
  .memory-switch input:focus-visible + span{outline:4px solid var(--color-primitive-yellow-300)}
  .memory-permission{font-size:.8125rem;font-weight:700}.memory-permission[data-state='required']{
    color:var(--color-semantic-error-1)}.memory-permission[data-state='granted']{
    color:var(--color-semantic-success-1)}
  .memory-summary{display:flex;flex-wrap:wrap;gap:.75rem;margin:1rem 0}
  .memory-stat{padding:.75rem 1rem;border-radius:.625rem;background:var(--color-neutral-solid-gray-100)}
  .memory-recent{margin:0;padding-left:1.25rem}.memory-recent li{margin:.5rem 0;line-height:1.55}
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
  .site-layout{display:grid;grid-template-columns:minmax(16rem,.65fr) minmax(0,1.35fr);gap:1rem;align-items:start}
  .site-editor{width:100%;min-height:24rem;box-sizing:border-box;resize:vertical;
    border:1px solid var(--color-neutral-solid-gray-300);border-radius:.625rem;padding:1rem;
    background:#111827;color:#e5e7eb;font:13px/1.65 ui-monospace,SFMono-Regular,Menlo,monospace}
  .site-preview{width:100%;min-height:30rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.75rem;background:white}
  .project-board{display:grid;grid-template-columns:repeat(5,minmax(12rem,1fr));gap:.75rem;overflow-x:auto}
  .project-column{min-height:10rem;padding:.75rem;border-radius:.625rem;background:var(--color-neutral-solid-gray-50)}
  .project-column h3{margin:.25rem 0 .75rem;font-size:.875rem}.project-column ul{list-style:none;margin:0;padding:0;display:grid;gap:.5rem}
  .project-issue{padding:.75rem;border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;background:white;font-size:.8125rem}
  .governance-editor{margin-top:1.25rem;padding-top:1.25rem;
    border-top:1px solid var(--color-neutral-solid-gray-200)}
  .governance-collection{display:grid;gap:.625rem}
  .governance-row{display:grid;grid-template-columns:repeat(4,minmax(0,1fr)) auto;
    gap:.5rem;align-items:end}
  .governance-row--reporting{grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto}
  .governance-row input,.governance-row select{min-height:2.5rem;box-sizing:border-box;
    width:100%;border:1px solid var(--color-neutral-solid-gray-300);border-radius:.5rem;
    padding:.5rem .625rem;background:var(--color-neutral-white);font:inherit}
  .governance-row label{display:grid;gap:.25rem;font-size:.75rem;font-weight:700}
  .governance-remove{min-height:2.5rem}
  .governance-status{min-height:1.5rem;margin:0;color:var(--color-neutral-solid-gray-600)}
  .organization-studio__summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));
    gap:.75rem;margin-bottom:1rem}
  .organization-stat{padding:1rem;border:1px solid var(--color-neutral-solid-gray-200);
    border-radius:.625rem;background:var(--color-neutral-white)}
  .organization-stat strong{display:block;font-size:1.5rem}.organization-stat span{
    color:var(--color-neutral-solid-gray-600);font-size:.8125rem}
  .organization-studio__grid{display:grid;grid-template-columns:minmax(18rem,.8fr) minmax(0,1.2fr);
    gap:1rem;align-items:start}.organization-studio__stack{display:grid;gap:1rem}
  .organization-tree,.organization-tree ul{list-style:none;margin:0;padding-left:1rem}
  .organization-tree{padding-left:0}.organization-tree li{position:relative;padding:.35rem 0 .35rem 1rem}
  .organization-tree li::before{content:\"\";position:absolute;left:0;top:1rem;width:.65rem;
    border-top:1px solid var(--color-neutral-solid-gray-300)}
  .organization-tree__node{display:flex;justify-content:space-between;gap:.5rem;padding:.625rem;
    border:1px solid var(--color-neutral-solid-gray-200);border-radius:.5rem;background:white}
  .organization-tree__node small{color:var(--color-neutral-solid-gray-600)}
  .organization-studio__directory{display:grid;gap:.5rem;list-style:none;margin:0;padding:0}
  .organization-studio__directory li{display:grid;grid-template-columns:minmax(0,1fr) auto;
    gap:.5rem;padding:.75rem;border-bottom:1px solid var(--color-neutral-solid-gray-200)}
  .governance-row--wide{grid-template-columns:repeat(3,minmax(0,1fr)) auto}
  .sidebar__organization{padding:0 .5rem}.sidebar__organization select{
    min-height:2.5rem;width:100%;border:1px solid var(--color-neutral-solid-gray-300);
    border-radius:.5rem;background:var(--color-neutral-white);padding:.5rem;font:inherit}
  .organism-activity{max-height:20rem;overflow:auto}
  .organism-activity .data-list__item{grid-template-columns:7rem minmax(0,1fr) 8rem}
  .form-help{margin:0;color:var(--color-neutral-solid-gray-600);
    font-size:.8125rem;line-height:1.6}
  /* アプリ固有レイアウトなので local-* 名前空間（ADR-0001）。DADS class は上書きしない。 */
  .local-actions{display:flex;flex-wrap:wrap;gap:.5rem;align-items:center}
  .primary-action{min-height:2.75rem;border:0;border-radius:.5rem;
    background:var(--color-key-900);color:var(--color-neutral-white);
    padding:.625rem 1rem;font-weight:700;cursor:pointer}
  .primary-action:disabled{background:var(--color-neutral-solid-gray-300);cursor:not-allowed}
  .security-callout{padding:1rem;border-left:4px solid var(--color-key-600);
    background:var(--color-key-50);font-size:.875rem;line-height:1.7}
  .settings-notice{margin-bottom:1rem;padding:.75rem 1rem;border-radius:.5rem;
    background:var(--color-primitive-green-50);color:var(--color-semantic-success-2)}
  .settings-notice:empty{display:none}
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
  @media(max-width:64rem){.local-grid,.settings-grid,.organization-studio__grid,.site-layout{
    grid-template-columns:1fr}.organization-studio__summary{grid-template-columns:1fr 1fr}
    .governance-row{grid-template-columns:1fr 1fr}.governance-row>*:last-child{justify-self:start}
    .messenger-shell{grid-template-columns:minmax(13rem,.65fr) minmax(22rem,1.35fr)}
    .messenger-context{grid-column:1/-1;border-left:0;border-top:1px solid var(--color-neutral-solid-gray-200)}}
  @media(max-width:56rem){
    .workspace{grid-template-columns:13rem minmax(0,1fr)}
    .sidebar{padding:1rem .75rem;gap:.75rem}
    .brand__eyebrow,.brand__note,.brand__mark,.sidebar__status{display:none}
    .brand__name{font-size:1.125rem}
    .workspace-switcher__hint{display:none}
    .local-nav{padding-right:.125rem}
    .local-nav__item{min-width:0}
    .nav-badge{display:none}
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
    .messenger-shell{grid-template-columns:1fr}.messenger-rail{border-right:0;
      border-bottom:1px solid var(--color-neutral-solid-gray-200)}
    .messenger-context{grid-column:auto}.messenger-messages{min-height:20rem;max-height:28rem}
    .record-list__items{max-height:19rem}.record-detail{min-height:16rem}
    .workspace-toolbar{align-items:stretch}.workspace-search{width:100%}
    .worker-form{grid-template-columns:1fr}.worker-form .primary-action{width:100%}
    .worker-output{max-height:16rem}
    .form-grid{grid-template-columns:1fr}.connector-card{grid-template-columns:2.75rem 1fr}
    .connector-card .tool-button{grid-column:1/-1;width:100%}
    .local-actions{justify-content:stretch}.local-actions .dads-button{width:100%}
  }
  @media(max-width:40rem){
    :root{--mobile-nav-height:calc(4.5rem + env(safe-area-inset-bottom))}
    .project-select{max-width:9rem}
    body.has-mobile-menu{overflow:hidden}
    .workspace{display:block;min-height:100dvh;padding-bottom:var(--mobile-nav-height)}
    .sidebar{position:fixed;inset:auto 0 0 0;z-index:40;width:auto;height:var(--mobile-nav-height);
      display:block;padding:0;background:var(--color-neutral-white);
      border:0;border-top:1px solid var(--color-neutral-solid-gray-200);overflow:visible}
    .brand,.sidebar__status{display:none}
    .workspace-switcher{position:fixed;z-index:31;top:calc(.625rem + env(safe-area-inset-top));right:.75rem;
      width:7.5rem;padding:0;border:0;background:transparent}
    .workspace-switcher__label,.workspace-switcher__hint{position:absolute;width:1px;height:1px;
      padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
    .workspace-switcher select{min-height:2.75rem;padding:.375rem 1.75rem .375rem .5rem;
      background:var(--color-neutral-white);font-size:.75rem;text-overflow:ellipsis}
    .local-nav{position:static;display:block;width:auto;overflow:visible;padding:0}
    .nav-primary{position:fixed;z-index:44;left:0;right:20%;bottom:0;
      height:var(--mobile-nav-height);display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:0;
      padding-bottom:env(safe-area-inset-bottom);box-sizing:border-box;background:var(--color-neutral-white)}
    .nav-primary .local-nav__item,.mobile-menu-toggle{min-height:4.5rem;height:4.5rem;
      display:flex;flex-direction:column;align-items:center;justify-content:center;gap:.125rem;
      padding:.375rem .125rem;border-radius:0;color:var(--color-neutral-solid-gray-700);
      font-size:.6875rem;font-weight:700;line-height:1.2}
    .nav-primary .local-nav__item[aria-current='page']{border:0;border-top:4px solid var(--color-key-900);
      padding:.25rem .125rem .375rem;background:var(--color-key-50);color:var(--color-key-900)}
    .nav-primary .nav-icon,.mobile-menu-toggle .nav-icon{width:auto;font-size:1.125rem;line-height:1.4}
    .nav-primary .nav-label,.mobile-menu-toggle .nav-label{display:block;position:static;width:auto;height:auto;
      padding:0;margin:0;overflow:visible;clip:auto;white-space:nowrap;border:0}
    .nav-overflow-panel{display:none;position:fixed;z-index:43;left:.75rem;right:.75rem;
      bottom:calc(var(--mobile-nav-height) + .75rem);max-height:calc(100dvh - var(--mobile-nav-height) - 2rem);
      box-sizing:border-box;overflow-y:auto;padding:.75rem;border:1px solid var(--color-neutral-solid-gray-200);
      border-radius:1rem;background:var(--color-neutral-white);box-shadow:0 .75rem 2rem rgb(0 0 0 / .18)}
    .sidebar[data-mobile-menu-open='true'] .nav-overflow-panel{display:block}
    .nav-secondary{display:grid;gap:.25rem}
    .nav-section__summary{min-height:3rem;padding:.625rem .75rem}
    .nav-section__items{padding:.125rem 0 .375rem .75rem}
    .nav-section__items .local-nav__item{justify-content:flex-start;min-height:2.75rem;padding:.5rem .625rem}
    .sidebar__utility{margin-top:.5rem;padding-top:.5rem;background:var(--color-neutral-white)}
    .sidebar__utility .local-nav__item{justify-content:flex-start;min-height:3rem;padding:.625rem .75rem}
    .mobile-menu-toggle{position:fixed;z-index:44;right:0;bottom:0;width:20%;
      padding-bottom:calc(.375rem + env(safe-area-inset-bottom));border:0;border-top:4px solid transparent;
      background:var(--color-neutral-white);cursor:pointer}
    .mobile-menu-toggle[aria-expanded='true']{border-top-color:var(--color-key-900);
      background:var(--color-key-50);color:var(--color-key-900)}
    .mobile-nav-backdrop{position:fixed;z-index:42;inset:0 0 var(--mobile-nav-height);border:0;
      background:rgb(0 0 0 / .32)}
    .sidebar[data-mobile-menu-open='true'] .mobile-nav-backdrop{display:block}
    .main{min-width:0}
    .topbar{position:sticky;z-index:30;top:0;min-height:calc(4rem + env(safe-area-inset-top));
      box-sizing:border-box;padding:calc(.75rem + env(safe-area-inset-top)) 8.75rem .75rem 1rem;
      background:var(--color-neutral-white)}
    .topbar__title{font-size:1rem;line-height:1.4;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
    .view{padding:1rem}.view-header h1{font-size:1.75rem;line-height:1.3}.view-lead{line-height:1.7}
    .chat-shell{height:calc(100dvh - 4rem - var(--mobile-nav-height))}
    .data-card,.settings-card{padding:1rem}
    input,select,textarea,button{max-width:100%}
  }
  /* WCAG 2.3.3, and kotoba-uiux rule 7. Both animations in this stylesheet are
     INFINITE and decorative -- a typing indicator and a loading shimmer -- which
     is the exact category `reduce` exists for; an animation that never stops is
     the one most likely to cause discomfort.

     Measured rather than assumed: design-quality scored the rendered page 87.64
     with `reduced-motion` as the ONLY finding, which is what prompted this. This
     app is on DADS rather than kotoba-ui, so it gets no reduced-motion base layer
     for free and has to say it here.

     Stopped rather than slowed. Both animations mean 'something is happening',
     and the static state still says that -- the dots are still there, the
     skeleton still occupies the space it is reserving -- so nothing is lost by
     holding them still. */
  @media (prefers-reduced-motion: reduce){
    .typing span{animation:none;opacity:1}
    .skeleton{animation:none;background:var(--color-neutral-solid-gray-100)}
  }
  ")

(def app-css
  "The app's own rules, plus the ones `hanmen` needs for a rendered page to
  look like a page.

  Taken from the library rather than copied here, so that a rule it adds
  arrives with the version that emits the markup needing it. It brings no
  colour and no font — everything in it is `currentColor` and `inherit`,
  which is why this can concatenate it without checking it against the
  tokens `core-test` guards."
  (str base-css "\n  " hanmen-svg/stylesheet "\n"))

(def interaction-js
  "The page's interaction layer, which is JavaScript and now lives in a
  JavaScript file.

  It used to be a 300 kB string literal in this namespace, and every
  backslash in it had to be doubled for the Clojure reader: `\\.` in a
  regular expression, `\\n` in a `split`. Writing one of them singly is a
  compile error five separate changes made — loud, but a cycle each time,
  and the mistake has no other cause than the value being a string in a
  place where it is a program. As a resource it is neither escaped nor
  quoted, `node --check` reads the file directly, and an editor knows what
  language it is looking at.

  Read once at load. The page is assembled per request and re-reading a
  third of a megabyte for each one would be work nobody asked for."
  (slurp (io/resource "cloud/itonami/app/interaction.js")))

(def signal-js
  "Browser-owned X25519/Ed25519, Double Ratchet, and group sender-key state.
  Loaded before the interaction layer so the messenger never races crypto
  initialization."
  (slurp (io/resource "cloud/itonami/app/signal.js")))

(defn- nav-item [view title icon badge-id]
  [:button {:class "local-nav__item" :type "button" :data-view view
            :data-title title :aria-label title
            :aria-current (if (= view "chat") "page" "false")}
   [:span {:class "nav-icon" :aria-hidden "true"} icon]
   [:span {:class "nav-label"} title]
   (when badge-id [:span {:class "nav-badge" :id badge-id} "—"])])

(defn- nav-section [id title icon items]
  [:details {:class "nav-section" :data-nav-section id}
   [:summary {:class "nav-section__summary" :aria-label title}
    [:span {:class "nav-icon" :aria-hidden "true"} icon]
    [:span {:class "nav-section__label"} title]
    [:span {:class "nav-section__chevron" :aria-hidden "true"} "⌄"]]
   (into [:div {:class "nav-section__items"}] items)])

(def ^:private green-cross
  "緑十字 — 労働安全衛生の安全第一マーク。SVG で描くのは、`✚` や `十`
  の字形がフォント依存で十字に見えないことがあるため。色は
  `--color-semantic-success-1`（DADS の意味づけされた緑）で、生の hex は
  持たない。装飾なので `aria-hidden`：意味は隣の SAFETY FIRST が担う。"
  [:svg {:class "brand__safety-mark" :viewBox "0 0 12 12" :aria-hidden "true"}
   [:path {:d "M4.5 0h3v4.5H12v3H7.5V12h-3V7.5H0v-3h4.5Z"}]])

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
      :description "安全第一（緑十字）のAIワークスペース"
      :css css :app-css app-css
      :head [[:link {:rel "icon" :type "image/png" :href "/icon.png"}]
             [:link {:rel "apple-touch-icon" :href "/icon.png"}]
             [:script signal-js] [:script interaction-js]]}
     [:div {:class "workspace" :data-brand brand}
      [:aside {:class "sidebar" :aria-label "メインメニュー"}
       [:div {:class "brand"}
        [:p {:class "brand__eyebrow"} green-cross "SAFETY FIRST"]
        [:p {:class "brand__name"} brand]
        [:p {:class "brand__mark" :role "img" :aria-label brand} "ai"]
        [:p {:class "brand__note"} "Kotoba でつながる、手元の仕事場"]]
       [:section {:class "workspace-switcher" :aria-label "Organization切替"}
        [:label {:class "workspace-switcher__label"
                 :for "organization-switcher"} "現在のOrganization"]
        [:select {:id "organization-switcher" :disabled true
                  :aria-describedby "organization-switcher-hint"}
         [:option "確認中…"]]
        [:span {:class "workspace-switcher__hint"
                :id "organization-switcher-hint"}
         "作業対象の組織を切り替えます"]]
       [:nav {:class "local-nav" :aria-label "機能メニュー"}
        [:div {:class "nav-primary"}
         (nav-item "chat" "Chat" "✦" nil)
         (nav-item "capture" "Capture" "＋" "capture-count")
         (nav-item "messenger" "Messenger" "◇" "messenger-count")
         (nav-item "projects" "Projects" "▦" "projects-count")
         (nav-item "sites" "Sites" "▦" "sites-count")
         (nav-item "drive" "Drive" "◇" "drive-count")]
        [:div {:class "nav-overflow-panel" :id "mobile-overflow-panel"}
         [:div {:class "nav-secondary"}
          (nav-section
           "business-design" "事業設計" "◱"
           [(nav-item "portfolio" "Portfolio" "▤" "portfolio-count")
            (nav-item "canvas" "Canvas" "◱" "canvas-count")
            (nav-item "loops" "Loops" "∞" "loops-count")
            (nav-item "metrics" "Metrics" "⌸" "metrics-count")
            (nav-item "repos" "Repos" "⌗" "repos-count")])
          (nav-section
          "operations" "運営" "◉"
           [(nav-item "organization" "Organization" "⌘" "organization-count")
            (nav-item "operator" "事業者" "◐" "operator-count")
            (nav-item "inbox" "Mail archive" "□" "inbox-count")
            (nav-item "fleet" "Fleet" "◉" "fleet-count")
            (nav-item "worker" "Worker" "◐" "worker-count")
            (nav-item "organisms" "Organisms" "◎" "organism-count")
            (nav-item "scheduler" "Scheduler" "○" "scheduler-count")])
          (nav-section
           "trust-records" "信頼・記録" "▣"
           [(nav-item "contracts" "Contracts" "◫" "contracts-count")
            (nav-item "esign" "eSign" "✍" "esign-count")
            (nav-item "credentials" "Credentials" "▣" "credentials-count")
            (nav-item "storage" "Storage" "◈" "storage-count")])]
         [:div {:class "sidebar__utility"}
          (nav-item "memory" "Memory" "◴" nil)
          (nav-item "settings" "Settings" "⚙" nil)]]
        [:button {:class "mobile-menu-toggle" :type "button"
                  :aria-controls "mobile-overflow-panel" :aria-expanded "false"
                  :aria-label "その他のメニュー"}
         [:span {:class "nav-icon" :aria-hidden "true"} "☰"]
         [:span {:class "nav-label"} "その他"]]]
       [:button {:class "mobile-nav-backdrop" :type "button"
                 :aria-label "メニューを閉じる"}]
       [:div {:class "sidebar__status"}
        [:strong "● ローカルモード"]
        [:span {:id "workspace-status"} "既存サービスを確認中…"]]]
      [:div {:class "main"}
       [:header {:class "topbar"}
        [:h2 {:class "topbar__title" :id "current-view"} "Chat"]
        [:div {:class "topbar__context"}
         [:label {:class "visually-hidden" :for "active-project-select"} "現在のProject"]
         [:select {:class "project-select" :id "active-project-select"}
          [:option {:value ""} "Projectを選択"]]
         [:p {:class "topbar__meta"} "許可された接続先のみ"]]]
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
        [:section {:class "view" :data-view-panel "capture" :hidden true}
         (view-header "Capture"
                      "考えを評価・分類せず、そのまま手元に残します。AIには送られません。整理は後から行います。")
         [:div {:class "local-card"}
          [:form {:class "capture-composer" :id "capture-form"}
           [:div {:class "field"}
            [:label {:for "capture-text"} "いま頭にあること"]
            [:textarea {:id "capture-text" :name "text" :required true
                        :maxlength "100000" :autocomplete "off"
                        :placeholder "止めずに書く。整えない。判断しない。"}]]
           [:div {:class "capture-mode-row"}
            [:label {:for "capture-mode"} "記録方法"]
            [:select {:id "capture-mode" :name "mode"}
             [:option {:value "quick-capture"} "Quick capture"]
             [:option {:value "freewriting"} "Freewriting"]
             [:option {:value "think-aloud"} "Think aloud"]]
            [:button {:class "tool-button" :id "capture-dictate" :type "button"}
             "音声を文字にする"]
            [:button {:class "primary-action" :id "capture-submit" :type "submit"}
             "記録だけする"]]
           [:input {:id "capture-chronicle-frame-id" :name "chronicle-frame-id"
                    :type "hidden" :value ""}]
           [:div {:class "capture-chronicle"}
            [:div {:class "capture-chronicle__header"}
             [:div
              [:strong "Chronicle の作業文脈"]
              [:p {:class "form-help"}
               "選んだ画面のOCR抜粋だけを本文へ追加します。画像とOCR全文はCaptureへ保存しません。"]]
             [:button {:class "tool-button" :id "capture-chronicle-toggle" :type "button"
                       :aria-expanded "false" :aria-controls "capture-chronicle-panel"}
              "文脈を選ぶ"]]
            [:div {:id "capture-chronicle-panel" :hidden true}
             [:div {:class "capture-mode-row"}
              [:button {:class "tool-button" :id "capture-chronicle-now" :type "button"}
               "今の画面を取得"]
              [:span {:id "capture-chronicle-selection"} "未選択"]
              [:button {:class "tool-button" :id "capture-chronicle-clear" :type "button"
                        :disabled true} "出典を外す"]]
             [:p {:class "form-help" :id "capture-chronicle-status"
                  :role "status" :aria-live "polite"} ""]
             [:ul {:class "capture-chronicle__list" :id "capture-chronicle-list"}]]]
           [:p {:class "form-help"}
            "音声文字起こしはブラウザ機能を明示的に開始した時だけ使います。ブラウザによっては外部音声認識を利用します。音声自体は保存しません。"]
           [:p {:class "drive-create__status" :id "capture-status" :aria-live "polite"}]]]
         [:div {:class "capture-filters" :id "capture-filters"
                :role "group" :aria-label "整理先で絞り込む"}
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "inbox" :aria-pressed "true"} "Inbox"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "next-action" :aria-pressed "false"} "Next actions"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "project" :aria-pressed "false"} "Projects"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "waiting-for" :aria-pressed "false"} "Waiting for"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "someday-maybe" :aria-pressed "false"} "Someday / Maybe"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "reference" :aria-pressed "false"} "Reference"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "done" :aria-pressed "false"} "Done"]
          [:button {:class "tool-button capture-filter" :type "button"
                    :data-outcome "all" :aria-pressed "false"} "Weekly review"]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "capture-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "capture-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "記録を読み込んでいます。"]]]]
        [:section {:class "view messenger-view" :data-view-panel "messenger" :hidden true}
         (view-header "Kaisya Messenger"
                      "人間・agent・organismがそれぞれのmailboxで会話します。未許可の送信者は本文を表示せず隔離します。")
         [:div {:class "workspace-toolbar"}
          [:p {:class "source-note"}
           [:span {:class "source-dot"}]
           [:span {:id "messenger-source"} "mailboxを確認中…"]]
          [:div {:class "button-row"}
           [:span {:class "state-chip" :id "messenger-security"}
            "Signal端末を準備中"]
           [:button {:class "tool-button" :id "messenger-signal-device" :type "button"}
            "端末鍵を準備"]]]
         [:p {:class "drive-create__status" :id "messenger-status"
              :role "status" :aria-live "polite"}]
         [:div {:class "messenger-shell"}
          [:aside {:class "messenger-rail" :aria-label "会話一覧"}
           [:div {:class "messenger-pane__header"}
            [:h2 "Mailboxes / conversations"]
            [:p "DM・group・channelを同じ配送モデルで扱います。"]]
           [:form {:class "messenger-create" :id "messenger-create-form"}
            [:label {:class "visually-hidden" :for "messenger-title"} "会話名"]
            [:input {:id "messenger-title" :name "title" :required true
                     :maxlength 120 :placeholder "会話名" :autocomplete "off"}]
            [:label {:class "visually-hidden" :for "messenger-kind"} "会話種別"]
            [:select {:id "messenger-kind" :name "kind"}
             [:option {:value "direct"} "Direct message"]
             [:option {:value "group"} "Group"]
             [:option {:value "channel"} "Channel"]]
            [:label {:for "messenger-members"} "参加者（複数選択可）"]
            [:select {:id "messenger-members" :name "members" :multiple true :required true}]
            [:button {:class "primary-action" :type "submit"} "会話を作成"]]
           [:ul {:class "record-list__items" :id "messenger-conversations"}
            [:li {:class "skeleton"}]]]
          [:section {:class "messenger-thread" :aria-label "メッセージ"}
           [:div {:class "messenger-pane__header"}
            [:h2 {:id "messenger-conversation-title"} "会話を選択"]
            [:p {:id "messenger-conversation-meta"}
             "通常messageは実行権限ではありません。"]]
           [:ul {:class "messenger-messages" :id "messenger-messages"
                 :role "log" :aria-live "polite"}
            [:li {:class "empty-state"} "会話を選択してください。"]]
           [:form {:class "messenger-composer" :id "messenger-message-form"}
            [:label {:class "visually-hidden" :for "messenger-message"} "メッセージ"]
           [:textarea {:id "messenger-message" :name "content" :required true
                        :maxlength 8000 :placeholder "メッセージを書く" :disabled true}]
            [:select {:id "messenger-encryption-mode" :aria-label "暗号化方式"}
             [:option {:value "signal-v1"} "Signal E2EE"]
             [:option {:value "local-plaintext"} "Local plaintext"]]
            [:button {:class "primary-action" :id "messenger-message-submit"
                      :type "submit" :disabled true} "送信"]]]
          [:aside {:class "messenger-context" :aria-label "信頼と配送状態"}
           [:h3 "Trust / participants"]
           [:p "allowlistは受信mailboxごとです。許可は既存の隔離messageにも適用されます。"]
           [:ul {:class "messenger-principals" :id "messenger-principals"}
            [:li {:class "skeleton"}]]
           [:h3 "Quarantine"]
           [:p {:id "messenger-quarantine-count"} "確認中…"]
           [:ul {:class "messenger-quarantine" :id "messenger-quarantine"}
            [:li {:class "skeleton"}]]]]]
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
          [:div {:class "view-header" :style "margin-bottom:1rem"}
           [:div
            (dds/heading 2 "面ごとの現在地" {:size "24"})
            [:p {:class "form-help" :id "matrix-note"}
             (str "事業 × 面。セルは「測定済み」「未紐付け」「解析不能」「不在」"
                  "（実測だけ stale もあり）を区別します。開いたときに計算します。")]]
           [:button {:class "tool-button" :type "button" :id "matrix-load"} "計算する"]]
          [:div {:class "matrix-wrap"} [:table {:class "matrix" :id "matrix"}]]
          [:p {:class "source-note" :id "matrix-counts"}]]
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
          (dds/heading 2 "成熟度" {:size "24"})
          [:p {:class "form-help" :id "canvas-maturity-note"}
           (str "14 次元のうち 11 は誰かが記録した判断で、3 つ（completeness / hypothesis / "
                "validation）だけがこの canvas から計算されます。区別して表示します。")]
          [:div {:class "stat-row" :id "canvas-maturity-stats"}]
          [:ul {:class "record-list__items" :id "canvas-maturity-dims"}
           [:li {:class "empty-state"} "事業を選ぶと表示します。"]]]
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
          (dds/heading 2 "感度 — どの定数が結果を動かすか" {:size "24"})
          [:p {:class "form-help" :id "loops-sensitivity-note"}
           "モデルを再実行して測ります（介入の実行しやすさを点数化しません）。"]
          [:ul {:class "record-list__items" :id "loops-sensitivity"}
           [:li {:class "empty-state"} "事業を選ぶと表示します。"]]]
         [:div {:class "local-card"}
          (dds/heading 2 "介入の効きどころ（ledger）" {:size "24"})
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
          (dds/heading 2 "Messenger transport" {:size "24"})
          [:p {:class "form-help" :id "organism-messenger-state"
               :role "status" :aria-live "polite"}
           "owner/adminがworker専用credentialを発行すると、外部supervisorがmailboxをpoll・返信できます。"]
          [:button {:class "tool-button" :id "organism-messenger-issue"
                    :type "button"} "Credentialを発行 / rotate"]]
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
         ;; Where the list is looking: the inbox, the trash, and whatever
         ;; labels this reader has put on things. Built from what the server
         ;; says is in play rather than a fixed list, so a label somebody
         ;; invents appears here without this file being told about it.
         [:div {:class "drive-import-choice" :id "inbox-labels"
                :role "group" :aria-label "ラベルで絞り込む"}]
         [:p {:class "drive-create__status" :id "inbox-status" :aria-live "polite"}]
         ;; Composing. The inbox had a reply affordance and no way to answer
         ;; anything in it: `POST /api/mail/send` existed and nothing in the
         ;; interface reached it.
         ;;
         ;; Which account sends is an explicit choice, not a default. With
         ;; several mailboxes connected there is no "the" account, and sending
         ;; a work reply from a personal address is the kind of mistake an
         ;; interface should not be able to make on somebody's behalf.
         [:details {:class "local-card" :id "mail-compose"}
          [:summary "メールを作成"]
          [:form {:class "settings-form" :id "mail-compose-form"}
           [:div {:class "field"}
            [:label {:for "mail-compose-account"} "差出人"]
            [:select {:id "mail-compose-account" :name "account-id" :required true}]
            [:span {:class "form-help" :id "mail-compose-account-help"}
             "メールアカウントを読み込み中…"]]
           [:div {:class "field"}
            [:label {:for "mail-compose-to"} "宛先"]
            [:input {:id "mail-compose-to" :name "to" :required true
                     :autocomplete "off"
                     :placeholder "a@example.com, b@example.com"}]]
           [:div {:class "field"}
            [:label {:for "mail-compose-cc"} "Cc（任意）"]
            [:input {:id "mail-compose-cc" :name "cc" :autocomplete "off"}]]
           [:div {:class "field"}
            [:label {:for "mail-compose-subject"} "件名"]
            [:input {:id "mail-compose-subject" :name "subject" :required true
                     :autocomplete "off"}]]
           [:div {:class "field"}
            [:label {:for "mail-compose-body"} "本文"]
            [:textarea {:id "mail-compose-body" :name "text" :rows "8"
                        :required true}]]
           [:input {:id "mail-compose-in-reply-to" :type "hidden"}]
           [:input {:id "mail-compose-thread-id" :type "hidden"}]
           [:p {:class "drive-create__status" :id "mail-compose-status"
                :aria-live "polite"}]
           [:button {:class "primary-action" :id "mail-compose-send"
                     :type "submit"} "送信"]]]
         [:div {:class "record-browser"}
          [:div {:class "record-list"}
           [:ul {:class "record-list__items" :id "inbox-list"}
            [:li {:class "skeleton"}]]]
          [:article {:class "record-detail" :id "inbox-detail" :aria-live "polite"}
           [:div {:class "empty-state"} "メールを読み込んでいます。"]]]]
        [:section {:class "view" :data-view-panel "projects" :hidden true}
         (view-header "Projects" "ProjectごとにChat・Issue・Site・Git repositoryをまとめます。")
         [:div {:class "local-grid"}
          [:div {:class "local-card"}
           (dds/heading 2 "新しいProject" {:size "24"})
           [:form {:class "settings-form" :id "local-project-create-form"}
            [:div {:class "field"}
             [:label {:for "local-project-id"} "Project ID"]
             [:input {:id "local-project-id" :name "project" :required true
                      :pattern "[A-Za-z0-9._-]{1,80}" :placeholder "website-redesign"}]]
            [:div {:class "field"}
             [:label {:for "local-project-title"} "表示名"]
             [:input {:id "local-project-title" :name "title" :required true
                      :placeholder "Website redesign"}]]
            [:div {:class "field"}
             [:label {:for "local-project-description"} "説明"]
             [:input {:id "local-project-description" :name "description"}]]
            [:button {:class "primary-action" :type "submit"} "Projectを作成"]]
           [:p {:class "form-help" :id "local-project-create-status" :aria-live "polite"}]]
          [:div {:class "local-card"}
           (dds/heading 2 "このOrganizationのProjects" {:size "24"})
           [:ul {:class "record-list__items" :id "local-project-list"}
            [:li {:class "skeleton"}]]]]
         [:div {:class "local-card" :id "local-project-board-card" :hidden true}
          [:div {:class "view-header" :style "margin-bottom:1rem"}
           [:div
            (dds/heading 2 "Project Board" {:size "24"})
            [:p {:class "source-note" :id "local-project-board-source"}]]
           [:form {:class "local-actions" :id "project-issue-create-form"}
            [:input {:name "title" :required true :placeholder "新しいIssue"}]
            [:button {:class "tool-button" :type "submit"} "Issueを追加"]]]
          [:div {:class "project-board" :id "local-project-board"}]]
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
          [:ul {:class "data-list" :id "project-list"} [:li {:class "skeleton"}]]]
         [:div {:class "local-card"}
          [:div {:class "view-header" :style "margin-bottom:1rem"}
           [:div
            (dds/heading 2 "Governed AgentRun Kanban" {:size "24"})
            [:p {:class "source-note"}
             "lease・approval・receipt・source basis をEDNで追跡します。"]]
           [:span {:class "state-chip" :id "work-governance-state"} "確認中"]]
          [:ul {:class "data-list" :id "work-governance-list"}
           [:li {:class "skeleton"}]]
          [:div {:class "local-actions"}
           [:button {:class "tool-button" :id "open-organization-studio"
                     :type "button"} "Organization Studioで組織と権限を編集"]]]]
        [:section {:class "view" :data-view-panel "sites" :hidden true}
         (view-header "Sites" "選択中のProjectから静的サイトを作成し、確認して公開します。")
         [:div {:class "site-layout"}
          [:div {:class "settings-stack"}
           [:div {:class "local-card"}
            (dds/heading 2 "新しいSite" {:size "24"})
            [:form {:class "settings-form" :id "site-create-form"}
             [:div {:class "field"}
              [:label {:for "site-title"} "Site名"]
              [:input {:id "site-title" :name "title" :required true :placeholder "Product site"}]]
             [:div {:class "field"}
              [:label {:for "site-slug"} "slug"]
              [:input {:id "site-slug" :name "slug" :required true
                       :pattern "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?" :placeholder "product"}]]
             [:button {:class "primary-action" :type "submit"} "Siteを作成"]]
            [:p {:class "form-help" :id "site-create-status" :aria-live "polite"}]]
           [:div {:class "local-card"}
            (dds/heading 2 "Project Sites" {:size "24"})
            [:ul {:class "record-list__items" :id "site-list"}
             [:li {:class "empty-state"} "Projectを選択してください。"]]]]
          [:div {:class "settings-stack" :id "site-editor-panel" :hidden true}
           [:div {:class "local-card"}
            [:div {:class "view-header" :style "margin-bottom:1rem"}
             [:div
              (dds/heading 2 "HTML Editor" {:size "24"})
              [:p {:class "source-note" :id "site-editor-meta"}]]
             [:div {:class "local-actions"}
              [:button {:class "tool-button" :id "site-save-button" :type "button"} "保存"]
              [:button {:class "primary-action" :id "site-publish-button" :type "button"} "公開"]]]
            [:textarea {:class "site-editor" :id "site-html" :spellcheck "false"}]
            [:p {:class "form-help" :id "site-editor-status" :aria-live "polite"}]]
           [:iframe {:class "site-preview" :id "site-preview" :title "Site preview"
                     :sandbox ""}]]]]
        [:section {:class "view" :data-view-panel "organization" :hidden true}
         (view-header "Organization Studio"
                      "組織内組織、DoDAF Performer、Position、Role、Actor Assignmentと承認経路を分離して管理します。")
         [:div {:class "view-header" :style "margin-bottom:1rem"}
          [:p {:class "source-note"}
           "上司関係は組織構造、承認権限はPolicyです。組織図だけから承認権限は発生しません。"]
          [:span {:class "state-chip" :id "organization-studio-state"} "確認中"]]
         [:div {:class "organization-studio__summary" :id "organization-studio-summary"}]
         [:datalist {:id "organization-actor-options"}]
         [:datalist {:id "organization-unit-options"}]
         [:datalist {:id "organization-position-options"}]
         [:datalist {:id "organization-performer-options"}]
         [:div {:class "organization-studio__grid"}
          [:div {:class "organization-studio__stack"}
           [:section {:class "local-card"}
            (dds/heading 2 "Organization Unit" {:size "20"})
            [:p {:class "form-help"} "Organization → Division → Department → Teamを親子表示します。"]
            [:ul {:class "organization-tree" :id "organization-studio-tree"}
             [:li {:class "skeleton"}]]]
           [:section {:class "local-card"}
            (dds/heading 2 "Person & Actor directory" {:size "20"})
            [:ul {:class "organization-studio__directory" :id "organization-studio-actors"}
             [:li {:class "skeleton"}]]]]
          [:div {:class "organization-studio__stack"}
           [:section {:class "local-card"}
            (dds/heading 2 "Assignment matrix" {:size "20"})
            [:ul {:class "organization-studio__directory" :id "organization-studio-assignments"}
             [:li {:class "skeleton"}]]]
           [:section {:class "local-card"}
            (dds/heading 2 "Approval routes" {:size "20"})
            [:ul {:class "organization-studio__directory" :id "organization-studio-policies"}
             [:li {:class "skeleton"}]]]]]
         [:section {:class "local-card governance-editor"}
          (dds/heading 2 "組織モデルを編集" {:size "24"})
          [:form {:class "settings-form" :id "governance-organization-form"}
            [:div {:class "form-grid"}
             [:div {:class "field"}
              [:label {:for "governance-org-id"} "Organization ID"]
              [:input {:id "governance-org-id" :required true :autocomplete "off"}]]
             [:div {:class "field"}
              [:label {:for "governance-org-name"} "表示名"]
              [:input {:id "governance-org-name" :required true
                       :autocomplete "organization"}]]]
            [:details {:open true}
             [:summary "1. Organization Unit"]
             [:div {:class "governance-collection" :id "governance-units"}]
             [:button {:class "tool-button" :id "governance-add-unit"
                       :type "button"} "Unitを追加"]]
            [:details {:open true}
             [:summary "2. Position & Role"]
             (dds/heading 3 "Position" {:size "16"})
             [:div {:class "governance-collection" :id "governance-positions"}]
             [:button {:class "tool-button" :id "governance-add-position"
                       :type "button"} "Positionを追加"]
             (dds/heading 3 "Role" {:size "16"})
             [:div {:class "governance-collection" :id "governance-roles"}]
             [:button {:class "tool-button" :id "governance-add-role"
                       :type "button"} "Roleを追加"]]
            [:details {:open true}
             [:summary "3. Person / Organization / System Actor"]
            (dds/heading 3 "Performer (DoDAF Person / System)" {:size "16"})
            [:div {:class "governance-collection" :id "governance-performers"}]
            [:button {:class "tool-button" :id "governance-add-performer"
                      :type "button"} "Performerを追加"]]
            [:details {:open true}
             [:summary "4. Assignment & Reporting line"]
            (dds/heading 3 "Assignment" {:size "16"})
            [:div {:class "governance-collection" :id "governance-assignments"}]
            [:button {:class "tool-button" :id "governance-add-assignment"
                      :type "button"} "Assignmentを追加"]
            (dds/heading 3 "Reporting line" {:size "16"})
            [:div {:class "governance-collection" :id "governance-reporting-lines"}]
            [:button {:class "tool-button" :id "governance-add-reporting"
                      :type "button"} "承認と独立した上司関係を追加"]
            [:p {:class "form-help"}
             "Reporting lineは組織図だけを表し、承認権限はPolicyからのみ付与されます。"]]
            [:div {:class "local-actions"}
             [:button {:class "primary-action" :type "submit"} "組織図を保存"]]
            [:p {:class "governance-status" :id "governance-organization-status"
                 :aria-live "polite"}]]]
         [:section {:class "local-card governance-editor"}
          (dds/heading 2 "Approval policyを編集" {:size "24"})
           [:form {:class "settings-form" :id "governance-policy-form"}
            [:div {:class "form-grid"}
             [:div {:class "field"}
              [:label {:for "governance-policy-id"} "Policy ID"]
              [:input {:id "governance-policy-id" :required true}]]
             [:div {:class "field"}
              [:label {:for "governance-policy-capability"} "Capability"]
              [:input {:id "governance-policy-capability" :required true
                       :placeholder "repository/change"}]]
             [:div {:class "field"}
              [:label {:for "governance-policy-roles"} "承認可能role（カンマ区切り）"]
              [:input {:id "governance-policy-roles" :required true
                       :placeholder "reviewer, owner"}]]
             [:div {:class "field"}
              [:label {:for "governance-policy-minimum"} "必要承認数"]
              [:input {:id "governance-policy-minimum" :type "number" :min 1
                       :value 1 :required true}]]]
            [:div {:class "local-actions"}
             [:label [:input {:id "governance-policy-uv" :type "checkbox"
                              :checked true}] " Passkey user verification必須"]
             [:label [:input {:id "governance-policy-sod" :type "checkbox"
                              :checked true}] " 職務分離必須"]]
            [:div {:class "local-actions"}
             [:button {:class "primary-action" :type "submit"} "Policyを保存"]]
            [:p {:class "governance-status" :id "governance-policy-status"
                 :aria-live "polite"}]]]]
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
          ;; Any bytes: a PDF, an image, a zip. When the bytes are one of the
          ;; six formats this Drive can read, the choice below offers the
          ;; other reading of the same gesture — the file as a document you
          ;; can edit rather than an attachment you can download.
          [:input {:class "workspace-search" :id "drive-upload" :type "file"}]
          [:div {:class "drive-import-choice" :id "drive-import-choice"
                 :role "group" :aria-label "読み込む形式"}]
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
         [:div {:class "drive-create-bar"}
          [:div {:class "scheduler-create" :role "group" :aria-label "予定を作成"}
           [:label {:class "visually-hidden" :for "scheduler-title"} "予定の名前"]
           [:input {:class "workspace-search" :id "scheduler-title" :type "text"
                    :placeholder "予定の名前" :autocomplete "off"}]
           [:label {:class "visually-hidden" :for "scheduler-start"} "開始"]
           [:input {:class "workspace-search" :id "scheduler-start"
                    :type "datetime-local"}]
           [:label {:class "visually-hidden" :for "scheduler-end"} "終了"]
           [:input {:class "workspace-search" :id "scheduler-end"
                    :type "datetime-local"}]
           [:label {:class "visually-hidden" :for "scheduler-attendees"}
            "招く人（カンマ区切り）"]
           [:input {:class "workspace-search" :id "scheduler-attendees" :type "text"
                    :placeholder "招く人（カンマ区切り）" :autocomplete "off"}]
           [:button {:class "tool-button" :type "button" :id "scheduler-create-button"}
            "予定を作成"]]
          [:p {:class "drive-create__status" :id "scheduler-status" :aria-live "polite"}]]
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
        [:section {:class "view" :data-view-panel "credentials" :hidden true}
         (view-header "Credentials"
                      (str "所属を、この画面の外へ持ち出せる W3C Verifiable Credential として"
                           "発行します。台帳の行と違い、発行体を信頼する相手なら"
                           "このアプリに問い合わせずに検証できます。"))
         [:p {:class "source-note"} [:span {:class "source-dot"}]
          [:span {:id "credentials-source"} "発行状況を確認中…"]]
         ;; 画面に書く。credential が何を証明し、何を証明しないかを知る必要が
         ;; あるのは、それを提示する本人だから。
         [:div {:class "security-callout" :id "credentials-notice"}
          [:strong "発行体の署名は「発行体がそう言った」ことを証明します。"]
          "「今もそう言っている」ことは失効一覧が示すもので、失効させると、"
          "提示されたどこでもこの credential は honour されなくなります。"
          [:br]
          [:strong "holder 自身の署名による提示（Verifiable Presentation）は未対応です。"]
          "これは未完成ではなく構造的な制約で、WebAuthn は自分の "
          "authenticatorData ‖ clientDataHash に署名する仕様のため、"
          "正規化した文書に対する Data Integrity proof を Passkey では作れません。"]
         [:div {:class "local-card"}
          (dds/heading 2 "発行する" {:size "24"})
          [:p {:class "form-help"}
           "いま操作している membership に対して発行します。Passkey の登録と "
           "Organization ID の設定が前提で、どちらも欠けていればその場で拒否されます。"
           "組織が did:web を公開していない間は、発行体 did:key で署名します——"
           "応答しないアドレスを名乗るより、自己記述的な鍵の方が検証できるからです。"]
          [:button {:class "primary-action" :type "button" :id "credential-issue"}
           "membership credential を発行する"]
          [:p {:class "form-help" :id "credential-issue-status"
               :role "status" :aria-live "polite"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "所属だけを示す形式で発行する（SD-JWT VC）" {:size "24"})
          [:p {:class "form-help"}
           "上の形式は credential を見た相手に中身すべてを開示します——2 つの検証者に"
           "提示すると、どちらも同じ did:key を受け取るので、その 2 回の提示は"
           "互いに結び付けられます。この形式では "
           [:strong "「この組織の誰かが auditor である」ことを、それが誰かを明かさずに"]
           "証明できます。開示を選べるのは主体の識別子（sub）だけで、role と"
           "organization は常に開示されます——役割を隠せる membership credential は"
           "何も主張していないからです。"]
          [:div {:class "security-callout"}
           [:strong "この形式は所持者拘束（key binding）を持ちません。"]
           "発行体がその主張をしたことは証明しますが、"
           [:strong "提示している人が主体本人であることは証明しません"]
           "（bearer-presentable）。これは未実装ではなく構造的な制約で、"
           "Passkey は自分の authenticatorData ‖ clientDataHash に署名するため "
           "holder proof を作れません。所持者拘束が必要な検証者は、自分の鍵を持つ"
           "wallet を要求する必要があります。"]
          [:button {:class "primary-action" :type "button" :id "credential-issue-sd-jwt"}
           "SD-JWT VC を発行する"]
          [:p {:class "form-help" :id "credential-sd-jwt-status"
               :role "status" :aria-live "polite"}]
          [:div {:id "credential-sd-jwt-result" :role "status" :aria-live "polite"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "発行済み" {:size "24"})
          [:p {:class "form-help"}
           "これは台帳で、署名済みの credential 本体は保存していません。"
           "持っているのは holder です——それが「このサーバーに聞かずに検証できる」"
           "ということの意味なので、失くした場合は再発行になります。"]
          [:ul {:class "data-list" :id "credential-list"}
           [:li {:class "skeleton"}]]]
         [:div {:class "local-card"}
          (dds/heading 2 "検証する" {:size "24"})
          [:form {:class "settings-form" :id "credential-verify-form"}
           [:label {:for "credential-verify-input"} "credential の JSON（必須）"]
           [:textarea {:id "credential-verify-input" :name "credential" :rows 8
                       :required true
                       :placeholder "{\"@context\": [...], \"proof\": {...}}"}]
           [:p {:class "form-help"}
            "「このアプリが発行したものとして検証」は自分の鍵で照合します。"
            "「他組織発行として検証」は発行体の did:web を解決しますが、"
            "信頼している発行者に限ります。"]
           [:div {:class "local-actions"}
            [:button {:class "primary-action" :type "submit"}
             "このアプリが発行したものとして検証"]
            [:button {:class "tool-button" :type "button" :id "credential-verify-external"}
             "他組織発行として検証"]
            [:button {:class "tool-button" :type "button" :id "credential-verify-sd-jwt"}
             "SD-JWT VC として検証"]]]
          [:div {:id "credential-verify-result" :role "status" :aria-live "polite"}]]
         [:div {:class "local-card"}
          (dds/heading 2 "信頼している発行者" {:size "24"})
          [:p {:class "form-help"}
           "空が既定です。did:web では信頼一覧が防御策ではなく信頼モデルそのもので——"
           "誰でも自分が支配するドメインに DID document を公開して対応鍵で署名できるため、"
           "credential が名乗った鍵を取得して検証することは、偽造者の計算を"
           "偽造者自身の鍵で検算しているのと同じです。どのドメインを信じるかは"
           "設定（:credentials :trusted-issuers）で先に決めます。"]
          [:ul {:class "data-list" :id "credential-trusted-issuers"}
           [:li {:class "skeleton"}]]]]
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
        [:section {:class "view" :data-view-panel "memory" :hidden true}
         (view-header "メモリ" "このコンピューターでローカルメモリを収集、保持、統合する方法を設定します。")
         [:p {:class "settings-notice" :id "memory-status"
              :role "status" :aria-live "polite"} ""]
         [:div {:class "memory-card"}
          [:div {:class "memory-row"}
           [:div
            (dds/heading 2 "ローカルメモリを有効にする" {:size "20"})
            [:p "この端末上のチャットから記憶を作成し、今後のチャットの補助コンテキストに使用します。"]]
           [:label {:class "memory-switch" :for "memory-local-toggle"}
            [:input {:id "memory-local-toggle" :type "checkbox"
                     :aria-label "ローカルメモリを有効にする"}]
            [:span {:aria-hidden "true"}]]]
          [:div {:class "memory-row"}
           [:div
            (dds/heading 2 "Chronicle 画面コンテキスト" {:size "20"})
            [:p "画面を定期的に端末内へ保存して OCR し、作業中の文脈を検索可能にします。画像と文字列はこの端末から送信しません。"]
            [:p {:class "memory-permission" :id "memory-screen-permission"
                 :data-state "unknown"} "画面収録の権限を確認中…"]]
           [:div {:class "memory-row__actions"}
            [:button {:class "tool-button" :id "memory-open-settings" :type "button"}
             "設定を開く"]
            [:label {:class "memory-switch" :for "memory-screen-toggle"}
             [:input {:id "memory-screen-toggle" :type "checkbox"
                      :aria-label "Chronicle 画面コンテキストを有効にする"}]
             [:span {:aria-hidden "true"}]]]]
          [:div {:class "memory-row"}
           [:div
            (dds/heading 2 "ツール支援チャットからのメモリ生成を許可" {:size "20"})
            [:p "端末操作を伴うタスクの目標と結果を記憶します。ツール出力内の命令は実行しません。"]]
           [:label {:class "memory-switch" :for "memory-tool-toggle"}
            [:input {:id "memory-tool-toggle" :type "checkbox"
                     :aria-label "ツール支援メモリを有効にする"}]
            [:span {:aria-hidden "true"}]]]
          [:div {:class "memory-row"}
           [:div
            (dds/heading 2 "ローカルメモリを削除" {:size "20"})
            [:p "保存した画面、OCR、派生メモリを削除します。チャット履歴は削除されません。"]]
           [:button {:class "danger-action" :id "memory-delete-button" :type "button"}
            "削除"]]]
         [:div {:class "memory-summary" :id "memory-counts"}]
         [:div {:class "local-card"}
          [:div {:class "section-heading"}
           (dds/heading 2 "最近のローカル記憶" {:size "20"})
           [:button {:class "tool-button" :id "memory-capture-button" :type "button"}
            "今すぐ画面を取得"]]
          [:ul {:class "memory-recent" :id "memory-recent-list"}
           [:li "まだ記憶はありません。"]]]]
        [:section {:class "view" :data-view-panel "settings" :hidden true}
         (view-header "Settings" "Cloud Itonami の組織・ユーザーと、外部サービスへの委任接続を管理します。")
         [:div {:class "local-card" :id "desktop-update-card"}
          (dds/heading 2 "Desktop update" {:size "20"})
          [:p {:class "view-lead" :id "desktop-update-status"
               :role "status" :aria-live "polite"}
           "更新状態を確認しています…"]
          [:p {:class "form-help"}
           "公開Releaseはアプリ内のEd25519公開鍵で署名を検証し、packageのSHA-256とsizeが一致した場合だけ次回起動用に準備します。"]
          [:div {:class "button-row"}
           [:button {:class "tool-button" :id "desktop-update-check" :type "button"}
            "更新を確認"]
           [:button {:class "primary-action" :id "desktop-update-download"
                     :type "button" :hidden true}
            "検証してダウンロード"]]]
         [:p {:class "settings-notice" :id "identity-status"
              :role "status" :aria-live "polite"} ""]
         [:div {:class "settings-notice" :id "connection-notice" :hidden true}]
         [:div {:class "security-callout" :id "passkey-gate-notice"
                :role "status" :aria-live "polite"}
          [:strong "サインインが必要です。"]
          " Passkey、Email、またはSSOで続行できます。重要操作ではPasskeyを追加確認します。"]
         [:div {:class "local-card" :id "identity-onboarding"}
         (dds/heading 2 "サインイン / 新規登録" {:size "24"
                                                :id "registration-title"})
          [:p {:class "view-lead" :id "registration-lead"}
           "いつもの認証方法を選べます。SSOとEmailは新規登録にも使え、Passkeyは重要操作の追加確認にも使います。"]
          [:form {:class "settings-form" :id "registration-form"}
           [:p {:class "form-help"}
            "Passkey の P-256 公開鍵から User DID（did:key）を生成します。秘密鍵は端末から出ません。"]
           [:button {:class "primary-action" :id "registration-submit"
                     :type "submit"} "Passkey で登録"]]
          [:div {:class "settings-stack" :id "registered-auth" :hidden true}
           [:div {:class "settings-form"}
            (dds/heading 3 "SSOで続ける" {:size "20"})
            [:p {:class "form-help"}
             "Google、Microsoft、GitHubの認証だけを使います。メールやリポジトリへのアクセス権は要求しません。"]
            [:div {:class "button-row" :id "sso-signin-list"}]]
           [:form {:class "settings-form" :id "email-login-form" :hidden true}
            (dds/heading 3 "Emailで続ける" {:size "20"})
            [:p {:class "form-help"}
             "10分間・一回限りのリンクを送ります。新規登録が有効な環境では、そのままUserを作成できます。"]
            [:div {:class "field"}
             [:label {:for "email-login-address"} "メールアドレス"]
             [:input {:id "email-login-address" :name "email" :type "email"
                      :required true :autocomplete "email"}]]
            [:button {:class "tool-button" :id "email-login-submit"
                      :type "submit"} "ログインリンクを送る"]]
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
           [:span {:class "state-chip"} "サインイン済み"]]
          [:div {:class "local-card" :id "auth-methods-card"}
           (dds/heading 2 "サインイン方法" {:size "24"})
           [:p {:class "view-lead" :id "current-auth-method"}
            "現在の認証方法を確認中…"]
           [:ul {:class "data-list" :id "linked-auth-methods"}]
           [:p {:class "form-help"}
            "同じEmailでも自動統合しません。既存Userへサインインした状態で接続してください。"]
           [:div {:class "button-row" :id "sso-link-list"}]]
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
            ;; Mailboxes, listed one per account rather than one per provider:
            ;; the same person's work Gmail and personal Gmail are two rows
            ;; with two sync states, because "Google: エラー" does not say
            ;; which of the two stopped working.
            [:div {:class "local-card"}
             (dds/heading 2 "メールアカウント" {:size "24"})
             [:p {:class "view-lead"}
              "Gmail と Microsoft 365 は上の「サービス接続」から、同じ提供者の2つ目以降のアカウントも同じ手順で追加できます。OAuth を持たないメールボックスは IMAP か POP3 で接続します。"]
             [:p {:class "source-note" :id "mail-account-state"}
              "メールアカウントを確認中…"]
             [:ul {:class "member-list" :id "mail-account-list"}]
             [:form {:class "settings-form" :id "mail-account-form"}
              (dds/heading 3 "IMAP / POP3 でメールボックスを追加" {:size "16"})
              [:div {:class "field"}
               [:label {:for "mail-account-protocol"} "受信プロトコル"]
               [:select {:id "mail-account-protocol" :name "protocol"}
                [:option {:value "imap" :selected true} "IMAP（推奨）"]
                [:option {:value "pop3"} "POP3"]]
               [:span {:class "form-help"}
                "POP3 はフォルダも既読状態もサーバーに持てません。IMAP が使えるなら IMAP を選んでください。"]]
              [:div {:class "field"}
               [:label {:for "mail-account-address"} "メールアドレス"]
               [:input {:id "mail-account-address" :name "address"
                        :type "email" :required true :autocomplete "email"
                        :placeholder "me@example.com"}]]
              [:div {:class "field"}
               [:label {:for "mail-account-host"} "受信サーバー"]
               [:input {:id "mail-account-host" :name "host" :required true
                        :autocomplete "off" :placeholder "imap.example.com"}]]
              [:div {:class "field"}
               [:label {:for "mail-account-smtp-host"} "SMTP サーバー（任意）"]
               [:input {:id "mail-account-smtp-host" :name "smtp-host"
                        :autocomplete "off"
                        :placeholder "空欄なら IMAP と同じホスト"}]]
              [:div {:class "field"}
               [:label {:for "mail-account-password"} "パスワード（アプリパスワード）"]
               [:input {:id "mail-account-password" :name "password"
                        :type "password" :required true
                        :autocomplete "new-password"}]
               [:span {:class "form-help"}
                "macOS Keychain に保存します。state.edn には保存しません。"]]
              [:button {:class "tool-button" :id "mail-account-submit"
                        :type "submit"} "メールボックスを追加"]]]
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
             ;; Here rather than in the Projects panel: that panel reads GitHub
             ;; Projects v2, and this moves a LOCAL project between tenants —
             ;; a question about tenants, which is what this card is (ADR-0024).
             [:form {:class "settings-form" :id "project-transfer-form"}
              (dds/heading 3 "Project を別の tenant へ移動" {:size "16"})
              [:div {:class "field"}
               [:label {:for "project-transfer-project"} "Project ID"]
               [:input {:id "project-transfer-project" :name "project"
                        :required true :autocomplete "off"
                        :placeholder "my-project"}]]
              [:div {:class "field"}
               [:label {:for "project-transfer-tenant"} "移動先"]
               [:select {:id "project-transfer-tenant" :name "tenant"
                         :required true}]
               [:span {:class "form-help" :id "project-transfer-help"}
                (str "両方の tenant で owner または admin 権限が必要です。"
                     "公開済みの Project は移動できません。")]]
              [:button {:class "tool-button" :id "project-transfer-submit"
                        :type "submit"} "移動する"]]
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
            [:div {:class "local-card"}
             (dds/heading 2 "Agent tenant connections" {:size "20"})
             [:p {:class "form-help"}
              "Agentの申請を確認し、tenant・capability・期限・予算を固定して承認します。Agent自身は承認できません。"]
             [:p {:class "source-note" :id "tenant-connection-state"
                  :role "status" :aria-live "polite"}
              "接続申請を確認中…"]
             [:ul {:class "member-list" :id "tenant-connection-list"}
              [:li {:class "skeleton"}]]]
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
