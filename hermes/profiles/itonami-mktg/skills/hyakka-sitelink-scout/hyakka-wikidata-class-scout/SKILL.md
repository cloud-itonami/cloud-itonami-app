---
name: hyakka-wikidata-class-scout
description: Use when the hyakka wikidata class-scout cron bot runs.
---

# hyakka wikidata class scout — procedure + pitfalls

The cron prompt carries the canonical steps (enumerate one class via WDQS, rank by
sitelinks, fetch entity JSON, gate, land ≤10, PR). This file holds what the prompt
does not: failure modes hit in real runs.

## Pitfalls (verified 2026-09-04, PR #436)

- **/tmp/hyakka-source-proposal.edn is SHARED** — sibling cron bots overwrite it
  concurrently. Always copy to a run-unique path
  (e.g. /tmp/hyakka-wikidata-class-proposal-YYYYMMDD.edn) and gate THAT.
- **Gate crash `Unable to resolve symbol: <<<<<<<` = conflict markers committed on
  main**, not a proposal rejection. Happened in merge cf81e6b
  (src/hyakka/corpus/world_legal.cljc, PR #428). Repair pattern: take the common
  prefix (assert byte-identical to BOTH merge parents via `git show parent:path`),
  then append parent1's tail, then parent2's tail (both marker-free, additive
  conflict). Assert no duplicate def names after. Commit the repair SEPARATELY
  from the config addition.
- **nbb script files must be ClojureScript** (`nbb script.cljs`), not JS. No
  `slurp`, no `clojure.java.io`, no `System/exit`. Use `(js/require "fs")` + 
  `(.readFileSync fs path "utf8")`, exit with `(js/process.exit n)`.
- **Orphaned tail recurs per-bot**: 09-05 main again carried a standalone ']}' mid-file
  (sitelink-scout batch Q37..Q902 sat OUTSIDE the closed map; evidence said 660 while
  670 ids existed). The rule held: remove the early ']}', re-seat the tail
  byte-identical, append your batch, put ']}' back as the LAST config line; assert
  close-count==1 before and that the close is the final line after. Expect the
  evidence 'sources-configured' delta to include the re-seated batch (+20, not +10).
- **Before/after :id-count assert catches template typos**: an f-string '{{"id'
  rendered '{"id' (invalid EDN); the ':id "wikidata-' count assert failed cleanly
  (289→289 instead of +10) BEFORE any write. Fix was f'{{:id …'. Keep those asserts.
- **Config entries close with `}` on the same line as `:access ...`** — anchor
  insertions on that, not on a lone `}` line. After inserting, verify with an
  EDN parse-check script: total count (496→506), zero dupe ids, all new ids
  present (scout0904d_parse_check.cljs pattern).
- **PR-clash scan**: before landing, fetch every OPEN PR diff and grep for your
  QIDs (gh pr diff → capture_output + decode(errors="replace"); not UTF-8-safe in
  Python text mode).
- **Branchy enum scripts trip Pyright** `reportPossiblyUnboundVariable` (cand/fresh only bound inside branches) — bind them before the branches (init + a collect(rows) helper), and expect patch-tool misses on freshly written files: full write_file rewrite is the fallback.
- **wiki_growth_evidence.cljs needs a raised timeout** — 180s fg default timed out at 754 sources; it finished within a 560s fg run. Give it timeout≥300.
- **Class labels are discovery-only**: read en/ja labels from the fetched
  EntityData JSON, never from memory (past run: Q686822 mislabeled 'radio';
  09-05h: rotation list itself mislabeled Q699 'planet' — label-check the CLASS
  QID via WDQS rdfs:label/P31 before proposing, not just the candidates).
- cron runtime refuses `-e`/`-c` flags and heredocs — put helper code in
  files (scripts/ is fine, they stay untracked) and run the files.

## 0912b run notes (skyscraper, PR #947)

- **NOT-YET extractor: strip the 'Q' from URL-form regex captures**
  (`re.findall(r"Special:EntityData/(Q\d+)\.json", cfg)` → `m[1:]`) before
  unioning with id-form digit captures — unioning Q-prefixed and bare digits
  crashes the `key=int` sort with `int('q1235886')` (caught on the very first
  extraction this run; keep the id∪URL rule, mind the forms).
- **Mid-run main move hit again** (adb3b98d→ad9fec71, #944) — splice script
  aborted pre-write on the pin check; recovery per doctrine: re-checkout →
  re-extract NOT-YET → re-gate (10/10) → re-land on the fresh pin. Batch
  unchanged when the merged PR doesn't touch the lane.
- **nbb string interop: `(.slice s N)` mis-dispatched** (Function.prototype.apply
  on a number) even though `.split`/`.trim` on the same string worked — use
  `(subs s N)` for tail checks.
- Evidence gate finished in ~10s at 2749 sources — the old 754-source/560s
  note is stale; still give it a raised (600s) timeout.
- Commit message with two `-m` flags (subject + body) worked clean this run
  (multi-paragraph, no literal-\n bug).

## WDQS resolver shape rot (0910l — NEW, replaces the GROUP BY join)

- **The `?cls rdfs:label "token"@en . ?item wdt:P31 ?cls` GROUP BY/HAVING
  resolver STARTED RETURNING 0 for everything** (0910l: even lake Q23397, which
  0909j enumerated fine with the same shape). WDQS itself healthy (label probes,
  P31 counts fine). Do NOT debug the endpoint — suspect the join shape.
- **Working replacement — two-step resolver (cs0910l_enum2.py)**: (a) `?cls
  rdfs:label "token"@en` (plain, no join) → candidate QIDs; (b) per candidate,
  `SELECT (COUNT) WHERE { ?it wdt:P31 wd:QID }` ≥12 + en-label token guard; then
  the proven enum (`?item wdt:P31 wd:QID . ?item wikibase:sitelinks ?sl .
  FILTER(?sl >= 10) ORDER BY DESC(?sl) LIMIT 400`). First chain token (canyon
  Q150784, en 'canyon', P31 'feature type') resolved + enumerated 58 fresh
  sitelinked on the first try with this shape.
- **Memory-QID 'canonical instances' are unreliable**: 5 of 7 probed (Grand
  Canyon Q11982→biological process, Q9199→modern language, etc.) were wrong —
  labels are discovery-only, extend that rule to remembered QIDs of ENTITY
  examples too.
- Chain after 0914a: **bridge LANDED #965** (via Q12280, first token tried). Next:
  pick a fresh token via the two-step resolver; operas/olympics/automobile/
  railway-lines dead; planet REJECT #4 + 'language' trap still unresolved.

## 0913c run notes (theatre-buildings, PR #961)

- **theatre-buildings RESOLVED via Q24354** (`theatre building`, en label verified, 13967 instances, subclass-of-building ASK true). The label-probe step-A candidates list was the discovery path: alongside the dead Q11635/Q17172848 it returned Q106778944 whose P31 pointed at Q24354 — read the dead candidates' own P31 values for the real class. Chain next: pick a fresh token (re-resolve via the same two-step probe).
- **Gate order is validate-BEFORE-splice**: running the gate AFTER splicing the batch into the worktree config rejects all 10 with ':id already configured' (structural, not real). Gate-3-in-finisher is wrong; the pre-splice gate(s) on the pin are the gate evidence. Finisher needs a pre-push pin check instead.
- **rfind off-by-one trap twice in one run**: `cfg.rfind("\n}")` returns the index of the NEWLINE, so `cfg[fe]` is '\n' not '}' (probe `repr(cfg[-12:])` first). And `rfind(x, 0, end)` is END-EXCLUSIVE — a 2-char match at end-2 needs end-1. Assert tail anatomy by POSITION: `cfg[-1]=='}'`, `cfg[-4]==']'`, `vi=len-5`, `cfg[vi:vi+3]=='\n]\n'`. Tail is deterministic: `...}\n]\n\n}`.
- **PR body 65536-char limit**: the raw evidence log is now ~5500 lines (290KB) — inlining it whole fails `gh pr create` with 'Body is too long' AFTER push (recoverable: branch already up, write trimmed body, create again; #961). Inline only head(~14)+tail(~6) lines of the evidence log in the body.
- **`edn/readString` does not exist in nbb** — use `(require '[cljs.reader :as reader])` + `(reader/read-string cfg)` in parse-check scripts. Parse-check asserts: total sources 2796→2806, wikidata ids 1416→1426, 0 dupes, 10 new ids present.
- Silent-death pattern persists: an aborted Python lander can show only its first log lines with NO traceback — re-run with `-u` and stdout/stderr redirect to a file to surface it; the traceback landed there.
- Chain landed: **theatre-buildings #961 (0913c: Q24354, 327 sitelinked/325 fresh, NOT-YET 1463; Bolshoi Q138908 … Concertgebouw Q849957; gate 10/10 twice pre-splice; wikidata 1416→1426, sources 2806; base 44b130ec #958)**. Mid-run main move hit (63948a39→44b130ec, sitelink-scout #958 merged in the gap); recovery doctrine held (re-checkout, re-extract NOT-YET 1453→1463, re-gate 10/10, splice on fresh pin).

## 0914a run notes (bridge, PR #965)

- **NOT-YET regex digit-capture rule held twice more**: both
  `r'"wikidata-(q\\d+)"'` and `r'Special:EntityData/(Q\\d+)\\.json'` capture
  the Q/q PREFIX — `int()` on the capture crashes (`int('q31')`,
  `int('Q17')`). Include the prefix in the group and slice it off
  (`m.group(1)[1:]`), or exclude the prefix from the group. The 0912b lesson
  said 'strip Q from URL-form captures'; extend it: strip in BOTH forms.
- Mid-run main move (0914a flavor): #957 (OFAC lane, +8 config lines) merged
  between gate-1 and land; pin assert aborted pre-splice, config untouched.
  Recovery: re-checkout, re-extract NOT-YET (unchanged 1463), re-gate 10/10,
  re-land on fresh pin e6ebfb1e — batch unchanged when the merged PR doesn't
  touch the lane. Bridge was the FIRST token resolved by the two-step
  resolver after the 0913c run (Q12280 'bridge', 33,995 instances, first
  token tried) — resolver healthy again.

## 0914b run notes (volcano, PR #976)

- **Never name scratch scripts after stdlib modules**: `enum.py` shadowed
  stdlib `enum` (imported transitively by `re`/`json`) →
  `AttributeError: partially initialized module 're'` with a traceback that
  points nowhere near the filename. Rename (e.g. `cs_enum.py`).
- **Reader-vs-regex census mismatch is EXPECTED on current main (delta 5)**:
  5 nested `:subjects {:id …}` maps (`world/authority/jp-nihon-nenkin-kikou`,
  `world/authority/jp-zenkoku-kenkou-hoken-kyoukai`,
  `world/company/houjin-2000012100001`, `world/company/houjin-6000012070001`,
  +1 wrap) make `re.findall(r'\{:id "')` count 5 MORE than reader maps.
  Regex total 2829 = reader total 2824 (2814+10 spliced). Parse-check asserts
  must use READER counts (`sources-total`) for absolute numbers; regex counts
  are fine for DELTAS (wikidata `{:id` maps: regex==reader, 1482→1492).
- **Mid-run main move hit again** (e7155108→9408e3c2, #970/#971 merged during
  the gate→land window). Doctrine held: re-checkout → re-extract NOT-YET
  (unchanged 1473, batch still fresh) → re-gate 10/10 → re-land on fresh pin.
  Lander reads the pin from a file (/tmp/hyakka-cs/pin.txt) so recovery only
  rewrites that file, not the script.
- **Patch tool + silent shell output**: patch on a freshly written script may
  report not-found even when the content matches, and heredoc python edits
  return empty stdout even on success — always verify the edit with read_file
  (grep/ls in terminal can also come back empty) before re-running.
- Chain landed: **volcano #976 (0914b: Q8072, 400 sitelinked/396 fresh,
  NOT-YET 1473; Teide Q38954 … Île Saint-Paul Q204821, sitelinks 105..55;
  gate 10/10 twice pre-splice; wikidata regex maps 1482→1492, id-form
  1416→1426; sources-configured 2814→2824; base 9408e3c2 #971; commit
  87aa77b2, branch bot/wikidata-class-20260909-1358).**

## 0913a run notes (comet, PR #953)

- **nbb classpath rot, fleet-wide (09-09 main f3f29b22)**: the kotoba-text
  merge swapped `clojure.string`→`kotoba.lang.text` in 72 .cljc (incl.
  `hyakka.ingest`, which the gate loads). The gate died at load: `Could not
  find namespace: kotoba.lang.text`. Fix for EVERY nbb run over repo src
  (gate AND wiki_growth_evidence): append the gitlib source path —
  `nbb --classpath src:$HOME/.gitlibs/libs/io.github.kotoba-lang/text/73bdb13ae7a3d004b44bca08be03a3191157a38f/src`.
  Gate then passed 10/10 first try; the earlier failure was load-time, not a
  proposal rejection.
- **ssh flap bursts (rc=128, `'origin' does not appear to be a git repository`)**
  hit ls-remote repeatedly while sibling fleet traffic hammers github ssh;
  `git fetch` succeeded 3/3 in the same window. Pin-check and push-verify
  patterns: 5 ls-remote retries with stderr logged, then fetch+
  `rev-parse FETCH_HEAD` fallback. push rc=0 was trustworthy — only the
  readback flapped (cost one abort AFTER a good push; recover via fetch-verify).
- **PR body file ordering**: the lander wrote the body AFTER push — push
  succeeded, a later stage aborted, and recovery then had to write the body
  externally before `gh pr create`. Write the body file FIRST (before splice
  even), so no ordering of aborts can leave the PR create without it.
- **parse-check argv is [node, nbb, script, ...args] — config path is argv[3]**
  (the 0911a 'argv 2' note is off by one for nbb scripts given args after the
  script). Robust fix: scan argv for the `.edn` suffix instead of indexing.
- Splice asserts held: ids 1452→1462 (+10), whole-line `]` 1→1, clean-tail
  anatomy, sources-configured 2773→2783 as predicted. 09-09 main drained the
  PR queue (0 open): #934/#941/#947 merged into 6073b5fb — NOT-YET from live
  config covered the landed batches, zero overlap with comet.

## 0920a run notes (airport — no PR)

- **Token 'airport' (doy 263) resolved to NO class**: step-A label probe `?cls
  rdfs:label "airport"@en` returned a single NIL `?cls` binding (parser must
  skip `"value"`-less bindings or KeyError); step-B never ran. Endpoint was
  otherwise healthy (same proven plain-probe shape resolved 4 tokens 0919a).
  No proposal, no gate, no PR — rotation advances to 'museum' (doy 264 mod 7).
  NOT-YET covered = 1554 at base 2f9293e2 (#1052 merged). If 'museum' or
  'film' also probe-empty, re-try 'airport' with the plural label or check
  the probe against a known item (e.g. Q189381 Tokyo airport) before
  suspecting the resolver again.
- **Patch/write_file stale-view dance on /tmp scratch scripts**: a paginated
  read (limit/offset) blocks both patch and full rewrite until every page has
  been read in-session — for throwaway scripts just use a fresh filename
  per run (cs_enum_<mmdd>.py).

## Landed-run ledger (chain classes; all PRs against main)

Chain landed: diseases #577, mountains #586, TV #592, moons #598,
proglang #600, languages #614, currencies #625, asteroids #632, ships #638,
musical groups #641, chemical compounds #651, stadiums #655, islands #664,
musical instruments #682, space probes #689, football clubs #700,
constellations #708, castle #713, national park #717, church #722,
operating system #727 (re-emit #736 after the #732 merge regression), airline
#749, monument #757, library #762, mountain range #775, galaxy #784, lake #785,
waterfall #790, glacier #795, lighthouse #800, monastery #804, palace #812,
mosque #822, opera house #827, cathedral #833, temple #837, synagogue #843,
botanical garden #852, metro station #848, canal #858, zoo #863, valley #865,
shrine #871, bank #886, magazine #893, cave #898, strait #905, board game #916,
**canyon #927 (0910l: Q150784 Grand Canyon Q118841 … Charyn Canyon Q2231598;
gate 10/10; wikidata 1382→1392; sources-configured 2668 at landing).
**desert #934 (0911a: Q8514, 117 sitelinked/115 fresh; Sinai Q36755 … Great
Victoria Q145165; gate 10/10; wikidata 1346→1356; parse-check sources=2701;
base 0803a82b)**.
**peninsula #941 (0912a: Q34763, 400 sitelinked/399 fresh; Crimea Q7835 …
Yucatan Q130978; gate 10/10 re-run on fresh base; wikidata 1422→1432;
evidence sources-configured=2727 at landing; base 4c8316fa7)**.
**skyscraper #947 (0912b: Q11303, 400 sitelinked/400 fresh — zero overlap,
NOT-YET 1423; Burj Khalifa Q12495 … Abeno Harukas Q16318627; gate 10/10
twice — once pre-splice abort, re-run on fresh base; wikidata 1376→1386;
sources-configured=2749 at landing; base ad9fec71).**
**dam #955 (0913b: Q12323, 188 sitelinked/188 fresh, NOT-YET 1443; Afsluitdijk
Q240960 … Mangla Dam Q1286541; gate 10/10 twice — fresh-pin re-gate after
config decontamination; wikidata 1396→1406, sources 2786; base b16072a1
#954).**
**theatre-buildings #961 (0913c: Q24354, 327 sitelinked/325 fresh, NOT-YET
1463; Bolshoi Q138908 … Concertgebouw Q849957; gate 10/10 twice pre-splice;
wikidata 1416→1426, sources 2806; base 44b130ec #958).**
**bridge #965 (0914a: Q12280, 110 sitelinked/100 fresh, NOT-YET 1463; Gamla
bron Q3603782 … Dyavolski most Q2458142, sitelinks 30..20; gate 10/10 twice —
first pin 2849d10d moved to e6ebfb1e when #957 merged during the gate window,
recovery doctrine applied; wikidata 1416→1426, evidence sources 2800→2810;
base e6ebfb1e #957).**
**volcano #976 (0914b: Q8072, 400 sitelinked/396 fresh, NOT-YET 1473; Teide
Q38954 … Île Saint-Paul Q204821, sitelinks 105..55; gate 10/10 twice —
re-gate on fresh pin after mid-run move; wikidata 1416→1426 id-form,
sources-configured 2814→2824 at landing; base 9408e3c2 #971).**

## 0913b run notes (dam, PR #955)

- **Contaminated config on detached HEAD**: after checkout origin/main the
  worktree config still read stale (ids 1406 vs main's 1396, tail anatomy
  mismatched the 0905k-era template). `git checkout -q -- config/...` in the
  lander fixed it; ground truth became ids 1396 + tail
  `[:official-funder-registry]\n   :interval-seconds 86400}\n]\n\n}` — re-splice
  anchor must be derived from the LIVE tail each run, never hardcode.
- **`git checkout -B <branch> origin/main`** (force-reset) replaces the
  checkout -b dance when a prior abort left the branch behind.
- NEW_IDS needles need the full `wikidata-q…` prefix (a `wikidata-240960`
  needle misses — false 'missing' abort). Same lowercase rule as parse2.
- `gh pr list` from the agent cwd (not the worktree) fails rc=1 silently —
  always pass cwd=worktree to gh calls; verify with a stderr-logging run
  before trusting an empty clash scan.

## 0919a run notes (aircraft, PR #1030)

- **`.edn`-scan in nbb parse-check needs `first`, not `second`** — only ONE `.edn` string is in argv (script is .cljk), so `second` returns nil → readFileSync(null) TypeError.
- **Top-level config shape is `{:version … :sources [...]}` — NO `:config` wrapper**; `(get form :sources)` directly.
- Chain landed: **aircraft #1030 (0919a: Q11436, 217 sitelinked/217 fresh zero overlap, NOT-YET 1523; Q763256 aeromedical … Q1854827 Russky Vityaz, sitelinks 30..21; gate 10/10 pre-splice; reader wikidata 1532→1542, sources 2985→2995; base 1a0008b2, commit branch bot/wikidata-class-20260919-0947).** Tokens resolved this run (two-step): novel Q11325729, aqueduct Q474, cemetery Q39614, aircraft Q11436, fountain Q483453, library-building Q856584, observatory Q62832, research-institute Q31855; 'train station'/'wine region' unresolved. Day-of-year 262 mod over a 7-token list picked aircraft.

## 0919b run notes (library building, PR #1040)

- **Q856584 'library building' has ZERO direct `wdt:P31` instances** — two-step resolver step-B (direct P31 count >=12) refuses it. Fix: count/enumerate via the subclass path `?item wdt:P31/wdt:P279* wd:Q856584` (3227 instances; only 28 sitelinked >=10, all 28 fresh). When a class resolves to 0 direct instances, try the subclass path before abandoning the token.
- **write_file double-escape trap**: Python scripts written via write_file that contain `\n` inside string literals land as LITERAL backslash-n — asserts then compare bytes+backslash+n. Use `NL = chr(10)` and concat, or single-escape awareness: verify by reading the written file first (probe tail with repr before asserting).
- **Config tail -4 is `'\n\n}\n'`** (not `'\n}\n'`): full tail `...true}\n]\n\n}\n`; splice index `rfind('\n]', 0, len-1)`, insert `'\n' + batch` there.
- Chain landed: **library-building #1040 (0919b: Q856584 via P31/P279*, 28 sitelinked/28 fresh zero overlap, NOT-YET 1543; British Library Q23308 … Herzog August Library Q663820, sitelinks 78..20; gate 10/10 pre-splice; wikidata ids 1496→1506, sources 3033→3043; base 16858686, branch bot/wikidata-class-20260919-2149).**

## 0921a run notes (museum, PR #1060)

- Chain landed: **museum #1060 (0921a: Q33506, 49,006 instances, 400 sitelinked/371 fresh; Royal Observatory Q192988 … Saint Isaac's Cathedral Q215423, sitelinks 61..56; gate 10/10 pre-splice exit 0; reader wikidata 1507→1517, sources 3066→3076 (regex 3079→3089, delta 13); base 68efba6a, commit fdd23434, branch bot/wikidata-class-20260921-museum).** Two-step resolver clean on first try (Q33506 vs decoy Q55360890 count=0); WDQS 429 once, retried OK. No mid-run main move; pin held gate→land→push. Note: first gate call used bare `--classpath src` and died on kotoba.lang.text — the kotoba src append (73bdb13a…) was forgotten; re-run with it passed. Next: 'film' (doy 265 mod 7).

## 0921b run notes (film, PR #1069)

- Chain landed: **film #1069 (0921b: Q11424, 349,430 direct instances; 16 sitelinked>=10 in pool, all fresh, NOT-YET 1563; Titanic Q44578 137sl … Back to the Future Q91540 103sl; gate 10/10 exit 0; reader wikidata 1563→1573, sources 3066→3076; base 6876a20a, commit 350a4ec1, branch bot/wikidata-class-20260921-film, PR #1069 OPEN, verified via `gh pr view 1069` = state OPEN).** Two-step resolver picked film (doy 265 mod 7) clean on first try; Q11424 en-label 'film' count=349430 vs decoy Q140398100 count=0.
- **en-label-empty drop**: pool #2 Q134773 (Forrest Gump, 137sl tied #1) had en-label `""` (ja-only label) — dropped from the shortlist, Q91540 (11th, 103sl) promoted into the top-10. Rule: when building the shortlist, skip any candidate whose en-label is empty; the title `Wikidata <label> entity (<class>)` needs a real en label.
- **Live evidence script name is `.cljk`**: first `nbb … scripts/wiki_growth_evidence.cljs` died ENOENT rc=1 (refusal-mode note re-confirmed); live origin/main tracks `scripts/wiki_growth_evidence.cljk`. Run the evidence step with the `.cljk` name from the first attempt; the gate likewise runs `verify_source_proposal.cljk` on live main.
- **WDQS 429/502/504 churn on enumeration**: the class-instance SPARQL (P31 wd:Q11424 + sitelinks ORDER BY DESC LIMIT 400) needed 3 retries (429→504→502) before landing. The sitelink shortlist query and resolver probes are cheap and stable; only the big ORDER-BY-LIMIT enumeration query flakes. Retry the enumeration query (not the whole run) on 429/502/504.
- **Terminal stdout intermittently dies** (exit 0, empty result) on this host — every script's stdout was redirected to a `/tmp/hyakka-cs/*.out` file and read back with read_file; that pattern held the whole run together. Foreground cap 600s: the branch+evidence step (checkout + kotoba-classpath nbb evidence) ran as background session and was polled to completion.

## Refusal-mode (pre-run)

- **Pre-run REFUSED stale-script-name flavor (09-13/0914c run)**: collector exited 1 with ENOENT on `scripts/wiki_growth_evidence.cljs` — but the tree was HEALTHY. Post-mortem: on origin/main the script was RENAMED to `scripts/wiki_growth_evidence.cljk` (and `verify_source_proposal.cljs` is gone from scripts/ entirely; 60 tracked scripts). A pre-run hardcode of the old `.cljs` path went stale, not the worktree. If a later run refusals on these names, re-check the live `git ls-tree origin/main scripts/` names FIRST and adapt the runner to the current filenames before believing a worktree failure. Nothing was proposed or landed this run (doctrine held). Confirmed AGAIN 09-16: collector ENOENT on `.cljs` paths while origin/main (806ed48b) tracks only `.cljk` (`wiki_growth_evidence.cljk`, `verify_source_proposal.cljk`) — tree healthy, refusal was pure stale hardcode. Next run's runner MUST target `.cljk` filenames before its first gate/evidence call; refused run was not self-rescued (doctrine held). RESOLVED 09-17: `~/.hermes/profiles/hyakka-corpus/scripts/hyakka_coverage_evidence.py` now probes `.cljk`/`.cljs` live and appends the gitlibs kotoba-lang/text src to the classpath (`~/.gitlibs/libs/io.github.kotoba-lang/text/<sha>/src`, newest-mtime match) — verified producing a full evidence report (2949 sources, HEAD 8f00f849) in that run.

- **Pre-run REFUSED (09-05)**: the pre-script's checkout to origin/main died on **sibling worktree** hyakka-growth-bot's `index.lock` (cron-bot lock contention, not our worktree). Per prompt: report and stop, land nothing. Never clear another worktree's index.lock from the bot — it may be a live git process mid-commit; check `pgrep -fl git` first and leave removal to the user.
- **Pre-run REFUSED wiped-worktree flavor (09-07 19:38 run)**: measurement said 'no git worktree at ~/.gftd/worktrees/hyakka-growth-bot' — dir held only `.gp-analysis-out`, not registered in the superproject (~/.hermes/profiles/wiki-pr-merge/app-hyakka) worktree list, no git process on it, disk fine. Sibling-wipe, same verdict: report and stop, propose nothing. Do NOT recreate the shared worktree from the bot, and the superproject private-worktree flow does not license proceeding — it rescues only runs whose measurement did NOT refuse. Read-only post-mortem only: `ls` the dir, superproject `git worktree list`, `pgrep -fl git`, `df -h /`.
- **Pre-run REFUSED own-worktree-dirty flavor (09-08 03:41 run)**: measurement died on THIS bot's worktree — 'local changes to config/knowledge-ingest.edn would be overwritten by checkout'. Verdict unchanged: report and stop, propose nothing, even though a read-only post-mortem showed the condition had ALREADY self-cleared (porcelain clean, fetch OK, `checkout -q --detach origin/main` succeeds, HEAD at the then-origin/main). A refused run is never self-rescued mid-run — the NEXT run's fresh measurement is the rescue and it re-enumerates from the live config. Leaving HEAD detached at origin/main after the post-mortem checkout is correct: that is exactly the state step 1 wants for the next run.
- **Pre-run REFUSED tracked-flip flavor (09-08 16:49 run)**: measurement died on 'untracked working tree files would be overwritten by checkout' for src/chain/observer*.cljc + src/hyakka/*.cljc — this bot's own 0909g runtime-fix siblings were lying UNTRACKED in the worktree while origin/main had ALREADY re-tracked .cljc via the kotoba revert (1b538b19+244e7e75): the checkout wanted to materialize tracked .cljc exactly where the stale untracked copies sat. Read-only post-mortem: condition had self-cleared by run time (porcelain -uall = 0, the copies gone — not by the bot), so `git checkout -q --detach origin/main` then succeeded and HEAD was left at origin/main (313ec326, #871 shrine) for the next run. Doctrine held: nothing proposed, nothing landed, report + stop. Expect this flavor again if any pre-revert run's cleanup was interrupted.

## Land-script pitfalls (0905d run)

- **Simple-append insert still needs `block.append("]}")`** — the re-seat case
  appends the close, and so must the clean-tail append case; omitting it fails the
  close-assert pre-write (asserts caught it, config unwritten, no damage).
- **pr_clash scripts carry a per-run MINE set** — copying an old one flags false
  clashes (0905c's stale airport MINE flagged #474). Always rewrite MINE with THIS
  run's QIDs before trusting the report.
- **ls-remote compare: decode bytes** — `.split()[0]` on raw bytes never equals a
  str sha; decode before compare or the assert kills the script AFTER push, BEFORE
  the body write. Write the PR body file BEFORE commit/push so a mid-land crash
  can't leave `gh pr create` without `--body-file` (gh then refuses rather than
  opening an empty PR — the empty-body trap stays closed).
- **mergeable/mergeStateStatus read "UNKNOWN" immediately after `gh pr create`** —
not a failure. Re-query `gh pr view <branch>` separately before declaring the
readback incomplete (0912a: UNKNOWN at create, CLEAN/MERGEABLE seconds later).
- **Clean-tail append still the common case post-2720** (0912a): config ends
`…true}\n]` with the close `]` at the last `\n]` before form-end; splice at `vi`
worked first try after a mid-run main move (+12 maps) — no re-seat needed.
- **Mid-run main moves can stack** (0912a: two moves, 4f59fb31→9da56e4c→4c8316fa7).
Recovery that worked: re-checkout origin/main → re-extract NOT-YET → re-gate →
splice on the FRESH pin → push-time `ls-remote main == pin` assert. The stale
census abort is expected; don't loosen it, re-run instead.
- **Commit-message newline escaping**: `\n` sequences inside a Python string passed to `git commit -m` render as LITERAL backslash-n in the message (double-escape through the shell layer). For multi-paragraph messages use two separate `-m` flags (subject, body); amend before push is free and safe.
Gate accepts candidates with en-label but no ja-label (Q856285/Q862032/Q250523 passed #468).
Backup-candidate pattern (used in #465): if an en-label-less candidate (e.g. Q327147
Hürriyet) drops the batch below 10, pull the next sitelink-ranked names (Q373133,
Q301000…) and fetch them too, keeping sitelink-desc order in the proposal.
- **Proposal maps MUST carry `:kind :web-document` on the `{:id` line** — a
  generator that omits it makes the gate reject ALL entries with
  ":kind  is not dispatched by collect!" (exit 1, not exit 2 — malformed proposal,
  fixable: add the key, re-gate; 0905e run).
- **Close asserts must count WHOLE-LINE ']}'** (`l.strip() == "]}"`), never
  substring counts — other config lines legitimately contain ']}' mid-line
  (0905e run: substring count 11, whole-line count 1).
- **Anchor `{:id` lines by substring, not `startswith`** — later batches are
  indented one space (`' {:id ...'`), so startswith misses → pre-write
  StopIteration abort (0905k; asserts did their job, config untouched).
- **An f-string cannot contain a literal `]}`** (single `}` inside an f-string
  is a SyntaxError) — build the close token as `"]" + "}"`.
- **Tail anatomy drifts run to run**: 09-05k main tail = last map a commons
  source with 7-line anatomy (`:kind` on its own line, one-space indent), and
  the final whole-line close is ` ]}` (LEADING SPACE) as the very last line,
  no artifact after it. Peek last-16-lines first, then anchor by identity.
- **PR queue can drain mid-run** (0905k: #532 merged between the first scan
  and the clash scan) — after any main move: re-fetch, re-checkout origin/main,
  re-run the enum (NOT-YET extract is cheap) before gating, and cross-check
  `gh pr list` output directly instead of trusting one earlier scan.
- `gh pr view <branch> --json number,state,baseRefName,headRefName,body`
  readback by head-branch name works for post-create verification.

## Proposal-file pitfalls (album run, PR #444)

- `:sources` must be ONE vector containing all maps: `[{:id ...} {:id ...}]`.
  Emitting one `[{...}]` block per source (or a stray `{` between entries)
  yields odd top-level forms → gate exit 2 "does not read". Verify the file
  has exactly one `:sources` line and brackets balance before gating.
- **Anchor by identity, not just index**: line numbers drift between merges (09-05f: asserted line 7613 was the `{:id` line — it was that map's close line 7 lower; the pre-write assert caught it, config unwritten, zero damage). Peek the anchor range first, then assert BOTH the exact `{:id ...` line AND the exact close line at their 1-based indices before splicing.
- **Verify anchor lines with a numbered peek before asserting** (univ run #453):
  line numbers drift as merges land; sed-view the intended anchor range first, then
  assert `lines[n]` (0-based = 1-based n−1) before splicing. A wrong-index assert
  aborts cleanly — that is the point of asserting.
- **Pre-existing dupe id on main**: `world/company/houjin-2000012100001` (univ run
  #453 found it). Dupe asserts must exclude it, not fail the run.
- **Map anatomy for anchored inserts**: each source map is exactly 6 lines
  (`  {:id` / `   :url` / `   :title` / `   :license` / `   :source-classes` /
  `   :access "public" :interval-seconds 86400 :llm? true}`). Anchoring on the
  `:url` line means the map closes at anchor+4 and the splice goes at anchor+5
  (older 'closes 4 lines later' notes count from the `{:id` line). Peek the
  anchor range and ASSERT title/license/access lines by index before splicing —
  a wrong-index assert aborts cleanly before any write (that is the point).
- After insertion, run the nbb EDN parse check + dupe-id assert (scout0904d
  pattern) before committing; anchor on `:url "...QXXX.json"` of the last map
  of the preceding batch. Post-insert presence asserts must match the full
  `"wikidata-qXXX"` id string — a bare-QID substring check ("q1090") is the
  wrong needle and fires a false failure (0905g: assert caught the bug
  pre-write, config untouched; fix the CHECK, not the data).
- **Worktree `.git` is a FILE, not a dir** — cannot write scratch files under
  `.git/`; put PR body files in /tmp (run-unique name) and use `--body-file`.

## 0910l run notes (canyon, PR #927)

- **Splice index off-by-one on exploded anatomy**: `vi = cfg.rfind("\n]", 0, fe)`;
  splice at `vi` (BEFORE the `\n]` pair) so the batch lands inside the vector and
  the close stays `]\n\n}`. Splicing at `vi+1` leaves the batch outside the vector
  (census looks fine, parse2 misses the ids). Post-splice form-end assert must
  check the char AT `fe2` (`new_cfg[fe2] == "}"`), not `fe2-1`.
- **f-string `{}` traps**: one `%s`-format land script died on SyntaxError
  (f-string with literal `}` in the ABORT log message); one had a broken
  needle assert — plain string concatenation + `%s` formatting is the robust
  style for land scripts. write_file-based rewrite of the whole script is the
  fallback when patch misses on freshly written files.
- **Worktree is now /tmp/hyakka-cs/wt<run>** (setup.py does `git worktree add
  --detach` from the wiki-pr-merge superproject). The shared bot worktree
  (~/.gftd/worktrees/hyakka-growth-bot) is only the cron cwd — every driver must
  point scripts at the RUN worktree, and hardcoding the bot worktree path in a
  copied driver is a silent wrong-tree bug (caught by parse2 'want-ids missing').
- **parse2 wants lowercase qid**: fetched-state qid is uppercase Q118841 —
  lowercase before building the `"wikidata-…"` needle (0907a lesson, hit again).
  The id maps themselves are already lowercase in both land and proposal.
- **Print-to-stdout is unreliable in this runtime**: every script logs to its own
  file; `cat` in a shell line vanished twice (exit 0, empty) — read log files via
  read_file, never trust the tool result text.
- Gate passed FIRST try with the proposal (one rationale string, one sources
  vector, `:kind :web-document` on the `{:id` line, CC0-1.0, no ja labels
  needed — Q2278990/Q2231598 en-only accepted).
- Evidence `sources-configured=2668` at landing; wikidata ids 1382→1392;
  parse2 raw id maps 2663→2673 (+10); Chain after 0911a: **desert LANDED #934**; next: peninsula, skyscraper,
theatre, comet, dam (two-step resolver + token guard each); planet
REJECT #4 + 'language' trap still unresolved.

## 0911a run notes (desert, PR #934)

- **NOT-YET filter = id-form ∪ URL-form**: covered QIDs must union
  `"wikidata-q\d+"` ids AND `Special:EntityData/(Q\d+)\.json` URLs. Sahara
  Q6583 was configured ONLY as a URL (no `"wikidata-q6583"` id string on
  main) — an id-only filter proposed an already-covered entity; caught by a
  pre-gate dual check. The evidence report's idle-source names are NOT a
  substitute for reading the live config both ways.
- **Enum query brace shape**: `... FILTER(?sl >= 10) } ORDER BY DESC(?sl) LIMIT 400`
  — the `}` must close before ORDER BY; a dropped brace 400s with the parser
  pointing misleadingly at ORDER.
- **nbb argv keeps nbb's own flags**: under `nbb --classpath src script.cljs
  --root .`, `nth process.argv 2` is `--classpath`, not the flag value. Scan
  argv with indexOf per flag; avoid node `path` module quirks by building
  paths with `str`. (Two land retries died on `path/join` + argv reads;
  config was written but never committed — restore step made retry clean.)
- **Main moved between gate and land** (first mid-run drain since the
  doctrine: #931/#932 merged in the gate→land gap): lander's origin/main==pin
  assert aborted pre-write. Re-checkout → re-extract NOT-YET → re-gate →
  re-land with the new pin; batch unchanged when the merges don't touch it.
- **Lander idempotency**: every land attempt starts with
  `git checkout -q -- config/knowledge-ingest.edn` so a prior attempt's
  already-written config cannot double-apply.
- `.gftd` is a symlink to `.itonami-fleet` — worktree path mismatches in
  tool results are cosmetic.

## Merge-regression pitfall (09-07, #732)

A squash-merge PR can REPLACE config/knowledge-ingest.edn wholesale instead of merging — #732 dropped config len 947510→810373 (~267 source maps) while leaving its own 5 gov-source maps DUPLICATED. Detection: a merged PR's QIDs absent from origin/main config + `git log -S <id> -- config/` shows the adding commit. Response: report it, run your own class on live main, leave mass restoration to the owner (re-emit doctrine as 0907d).
