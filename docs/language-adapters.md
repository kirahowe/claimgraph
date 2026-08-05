# Language Adapters and Code Freshness

*Spec + handoff, 2026-07-24. Out of the dogfooding round: two couplings in
the mechanical code tier, fixed together because they constrain each other —
what analyzes the code decides what it costs, and what it costs decides
where it can run ambiently. Shipped 2026-07-24: driver + registry in
`claimgraph.ingest.code`, per-language analysis in
`claimgraph.ingest.clj-code` / `kotlin-code` / `ts-code` (clj-code keeps
back-compat wrappers, so the pre-existing suite and bench pass unchanged).
Implementation deviations from this spec:*

- *The pinned TS command grew `-p typescript@5` in the same npx prefix
  (`npx --yes -p typescript@5 -p dependency-cruiser@16 depcruise ...`):
  dependency-cruiser only enables `.ts` parsing when a supported typescript
  (>=2 <6) is resolvable next to it, which a bare `npx dependency-cruiser`
  install is not (and today's latest typescript is 7.x, out of its range).
  Verified end-to-end against a real bare TS repo.*
- *Reconciliation is language-guarded, making "a miss never creates a wrong
  edge" hold for degradation too: candidate facts are attributed to a
  language through their own `written-in`/`defined-in` chain, and a pass
  only invalidates facts of languages it actually re-analyzed — so a
  skipped analyzer (no npx), a failed one, or a `--language`-filtered run
  can never invalidate facts belonging to files it didn't look at.*
- *An analyzer `:error` closes the episode with ref suffixed `+partial`,
  which never satisfies the gate — transient tool failures retry next
  session. A `:skipped` analyzer (missing tooling) does NOT unmoor the
  gate: a stray `.ts` file on a machine without npx would otherwise force a
  full re-analysis every session end; installing the tool takes effect at
  the next code change. `--language`-filtered runs also mark `+partial` so
  a manual single-language pass never convinces the gate a full pass ran.*
- *When detection finds nothing analyzable, `ingest!` reports `:skipped`
  without opening an episode or invalidating anything (previously an empty
  dir invalidated every code fact — hazardous for an ambient stage run in
  the wrong cwd).*
- *Kotlin's `:detect` is `**.kt` only — including `.kts` would mint junk
  units for every `build.gradle.kts`.*

-----

## 1. The two problems

**Clojure-only.** The mechanical tier (`ingest-code`, and the audit's
staleness prong on top of it) parses only Clojure. The coupling is one leaf
namespace: `claimgraph.ingest.clj-code`'s edamame parsing and file glob.
Its other ~60% — `analyses->facts`, `stale-facts` (the reconciliation
plan), `ingest!` (invalidate-absent → assert → close episode) — is
language-agnostic in structure and merely lives there with `:namespace` /
`"clojure"` hardcoded. The predicate vocabulary needs nothing: `defined-in`
already reads "(function, class, namespace) … (file, module)", `written-in`
takes any literal, entity types are free-form keywords.

**Stale by default.** `ingest-code` runs only when invoked. It is not in
the ambient loop (`hooks run` = ingest-notes → compile-context →
consolidate), so code facts stay fresh only if an agent obeys the skill's
"after structural refactors" nudge — and agents don't reliably respect
skills, and *teammates' pushed changes never trigger anything at all*.
Anything that must work reliably has to be mechanical and systematic, not
markdown instructions. Two existing properties soften the blast radius —
compile-context excludes code facts from the injected view, and disuse
decay fades unrefreshed code facts — but a 90-day half-life is a backstop,
not freshness. Worst concrete failure: conflict detection using dead code
facts as ground truth flags *correct* new claims against a *stale* graph.

## 2. Design overview

1. **Command-shaped analyzer adapters** behind a registry (the third
   instance of an existing pattern — harness registry, config settings
   registry): each language is a map declaring how to detect itself, how to
   produce a unit graph, and what it costs. Analyzers may be internal
   functions (Clojure stays edamame — free and exact) or external commands
   (the same seam as the pluggable extractor and the pod binary: claimgraph
   already shells out for LLM and storage; analysis is the third).
2. **A tiny interchange format** so the shell-out boundary is data, never
   behavior: whatever produces the unit graph, the SAME pure
   `units->facts` + `stale-facts` + reconciliation machinery consumes it.
3. **`ingest-code` joins the ambient loop**: a delta-gated first stage of
   `hooks run`, free when nothing changed (SHA compare), reconciling when
   anything did — including teammates' pulled changes.

Deliberate rejection recorded: maintaining our own regex import parsers for
TS/JS was considered and dropped — the syntax surface is a moving target
(static/dynamic/type-only imports, `require`, re-exports) and every miss is
a silent wrong fact. Shelling out to the language's own tooling costs a
prerequisite the target audience already has (a TS repo has node by
definition) and buys correctness on exactly the hard parts (tsconfig
paths, workspaces). Where no good tool exists AND the grammar is rigid
(Kotlin's `package`/`import` lines), line parsing is acceptable.

## 3. The interchange format

One JSON object per source unit (JSONL or a JSON array — accept both):

```jsonc
{"unit": "shoply.auth",            // stable unit name (entity)
 "file": "src/shoply/auth.clj",    // repo-relative path
 "requires": ["shoply.db",         // unit names this unit depends on
              "external:react"],   // "external:" prefix = not in this repo
 "language": "clojure"}
```

Unprefixed `requires` entries are resolved against the emitted unit set;
anything unmatched is treated as external (same as today's out-of-set
scoping). Facts derived per unit, all at `:source-type :code`,
`:epistemic :observation`:

```
<unit> core/defined-in <file>        (subject-type :namespace... see below)
<file> core/written-in "<language>"
<unit> core/depends-on <unit>        (scope "external" when external)
```

`defined-in` is cardinality `:one` (verified in the registry) — one unit,
one file. This forces the unit-granularity decisions in §4 and means
adapters must emit file-grained units, never package-grained.

Subject/object entity types: keep `:namespace` for Clojure; use `:module`
for TS and Kotlin units. The type guard in entity resolution
(`logic/pick-entity-match`) already prevents silent cross-type matches.

## 4. The adapters

Registry entry shape (in a new `claimgraph.ingest.code` ns):

```clojure
{:id        :typescript
 :label     "TypeScript/JavaScript (dependency-cruiser)"
 :detect    "**.{ts,tsx,mts,cts,js,jsx}"   ; project matches if any file does
 :ignore    #{"node_modules" "dist" "build" ".git" ".claimgraph"}
 :language  "typescript"
 :unit-type :module
 :cost      :slow                          ; :fast = fine to run eagerly
 ;; exactly one of:
 :analyze-fn (fn [root] [unit-map ...])    ; internal (clojure, kotlin)
 :command    "npx --yes dependency-cruiser@16 --no-config --output-type json <roots>"
 :parse      (fn [stdout] [unit-map ...])} ; tool output -> interchange
```

Detection walks the project root (honoring `:ignore`), not a hardcoded
`src/` — Kotlin convention is `src/main/kotlin`, TS repos vary; the audit's
current `src/`-only assumption is fixed by this change.

- **Clojure** (`:cost :fast`): today's edamame analysis wrapped as
  `:analyze-fn`, emitting interchange maps. Behavior-identical to
  `clj-code` — the existing `code_ingest_test` suite must pass unchanged
  against the new driver.
- **TypeScript** (`:cost :slow`): shell to
  [dependency-cruiser](https://github.com/sverweij/dependency-cruiser),
  version-pinned via npx. Parse its `modules[].source` +
  `modules[].dependencies[]` into interchange; its `coreModule`/
  `couldNotResolve`/node_modules classifications map to `external:`. Unit
  name = repo-relative path sans extension. Prereq: `npx` on PATH — checked
  per-adapter with the `setup/check-prerequisites` `:which` pattern; absent
  tooling means the adapter reports `skipped` with a hint, never an error
  that blocks the loop. Pin the output-schema expectations in `:parse` and
  nowhere else (the harness-registry rule: one pinned seam per upstream).
- **Kotlin** (`:cost :fast`): internal line parse — the deliberate
  exception, because the grammar is rigid (one `package` decl, `import
  a.b.C` lines before code) and no maintained import-graph CLI exists in
  that ecosystem. Unit = `<package>.<file-stem>` (file-grained per §3;
  top-level class conventionally matches the stem). Import resolution,
  best-effort and safe-by-construction: exact unit match → local edge;
  `import a.b.*` → edge to every local unit in package `a.b`; member
  imports strip trailing segments until a local unit matches; anything
  unmatched → `external:` (a miss creates an external-scoped fact, not a
  wrong local one).

**Config extension point — bring your own analyzer.** A `code-analyzers`
map in `.claimgraph/config.json` (config-file only; structured values
don't fit flags/env) merges over the registry by id: override a built-in's
`:command`, disable one (`"typescript": false`), or add a new language
whose command emits the interchange format directly (`:parse` defaults to
identity). This is the escape hatch that ends the language-chasing game —
Rust or Python support is a ten-line script in the user's repo, no
claimgraph change.

Multi-language repos: `ingest-code` runs every detected adapter in one
pass, one episode, facts distinguished by `written-in` and entity types.
`--language ID` filters explicitly.

## 5. Freshness: the ambient code stage

`hooks run` gains a stage, FIRST (before ingest-notes, so extraction's
entity roster and conflict ground truth are fresh):

```
ingest-code-if-changed → compile-context → spawn curate (ingest-notes → consolidate → compile-context)
```

**The delta gate is already half-built**: every code pass closes its
episode with `ref` = git SHA. Extend the ref to `<sha>[+<dirty-digest>]`
where the digest is a short hash of `git status --porcelain` output
(uncommitted edits move the digest; sessions often end dirty). Gate: skip
when the current ref equals the newest `:code` episode's ref — the episode
log is the delta state, exactly the `ingest-notes` hash trick. Same ref →
milliseconds; changed ref → the reconciling pass, already idempotent and
non-lossy.

Consequences worth stating:

- **Teammates' changes flow in mechanically.** Pull moves HEAD; the next
  session end reconciles. No agent judgment, no markdown instruction, no
  human in the loop.
- **Renames/deletes are handled by the machinery that exists**: a moved
  namespace supersedes its `defined-in`; deleted units' facts are
  invalidated with `code-invalidation: absent at <ref>`. (Entity-level
  rename curation — carrying aliases — remains a human/skill verb; the
  mechanical layer records the change, not the identity judgment.)
- **Multi-writer is already correct**: each machine's pass appends to its
  own oplog; `reconcile` collapses independently-derived identical code
  facts non-lossily.
- **Cost control**: stage honors `:cost` only via configuration, v1 — a
  `code-ingest` setting (`"session-end"` default | `"manual"`) in the
  config/env/flag chain lets a project with an expensive analyzer opt the
  stage out. No auto-deciding on `:cost` yet; the hint is recorded for
  when someone needs it. The stage reports independently like the others —
  an analyzer failure never blocks the compile.

`hooks install` needs no change (the hook command already just runs
`hooks run`). The audit's code prong switches to the same registry
detection (multi-language staleness for free) and keeps its honest
`skipped` note when nothing is detected.

## 6. Handoff: build order and integration points

| Need | Existing seam |
|---|---|
| generic driver home | extract from `ingest/clj_code.clj`: `analyses->facts` (parameterize unit-type/language), `stale-facts`, `ingest!`, git-sha helper |
| registry pattern | `harness/harnesses` (shape, docstring conventions, one-pinned-seam rule) |
| shell-out + injectable for tests | `llm/complete!` command pattern; adapters take `:command-fn` the way extraction takes `:extractor-fn` |
| per-adapter prereq check | `setup/check-prerequisites` `:which` pattern |
| delta gate | episode refs via `store/-list-episodes` (see `notes/seen-hashes` for parsing refs back out, and `ingest/code`'s `<git-sha>+<dirty-digest>` ref for the shape) — derived state, never a stamp file |
| config setting | `config/settings` registry (`code-ingest`); `code-analyzers` read straight from `config/read-config-file` (documented as config-file-only) |
| audit code prong | `audit.clj` `ingest-code!` (replace `src/` + clj-glob hardcoding with registry detection) |

Build order:

1. **Extract the driver** (`claimgraph.ingest.code`): registry, interchange
   validation, `units->facts`, reconciliation, `ingest!` over all detected
   adapters. Port the Clojure adapter. `code_ingest_test` passes unchanged;
   `bb test` green.
2. **Kotlin adapter**: pure `analyze-source` line parse + resolution
   heuristics, inline-source tests (planted: package decl, exact import,
   wildcard, member import, unresolvable → external).
3. **TypeScript adapter**: `:command` + `:parse` over canned
   dependency-cruiser JSON fixtures (injected `:command-fn`, no npx in
   tests); prereq degradation test (no npx → skipped + hint). One manual
   end-to-end against a real TS repo before shipping; note the pinned
   dependency-cruiser major version in the adapter docstring.
4. **The ambient stage**: ref extension (`<sha>+<digest>`), gate fn (pure,
   tested against synthetic episode lists), wire into `hooks/run!` as first
   stage, `code-ingest` config setting. Hooks tests: same-ref skip,
   changed-sha run, dirty-digest run, `manual` opt-out, analyzer failure →
   `:partial` with compile intact.
5. **Call-site cleanup**: `cli.clj` (`ingest-code` help — de-Clojure,
   `--language`), `audit.clj` registry detection, `setup.clj` next-hint
   ("seed the structural layer" unconditionally, listing detected
   languages), skill template + regenerated dogfood copy, README
   (Ingestion tiers + Configuration table + prerequisites note for the TS
   analyzer), `book/chapters/cli_reference.md`.
6. **Dogfood**: run `bin/claim hooks run` on this repo twice — first run
   reconciles, second skips on the gate — and show both in the PR.

## 7. Open questions / accepted risks

- **dependency-cruiser drift**: majors change output; pinned via npx and
  isolated in `:parse`. If it breaks, the adapter skips with a hint —
  degraded, never wrong.
- **tsconfig discovery**: v1 lets dependency-cruiser's defaults find the
  nearest tsconfig; monorepos with per-package tsconfigs may misresolve
  aliases to `external:` — safe direction (missing local edge, never a
  false one). Revisit with real usage.
- **npx cold start** (seconds): acceptable at session end behind the gate;
  the `code-ingest: manual` opt-out exists for pathological setups.
- **Kotlin heuristics**: multi-class files and stem≠class conventions
  produce unit names that are file-stems, not class names — consistent,
  just less pretty. Entity aliases can bridge if it ever matters.
- **Dirty-digest churn**: `git status --porcelain` changes on ANY file,
  not just analyzable ones — over-triggering costs one cheap re-analysis
  (idempotent, no-op ingest). Filter to adapter globs only if it shows up
  in practice.
- **Non-git projects**: today's fallback ref (abs path) never changes, so
  the gate would always skip; keep the old behavior there by treating a
  path-ref as always-stale (run every time, matching today's manual
  semantics).
