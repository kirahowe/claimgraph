# claimgraph

A bi-temporal, epistemically-typed knowledge graph for AI coding-agent memory.
Owned, portable, inspectable — a structured replacement for the auto-memory
pile your agent accumulates on its own. It reads your `CLAUDE.md`/`AGENTS.md`
as high-trust instruction files and audits them against the code and the
graph.

Every fact is a reified edge carrying a metadata bundle: valid time +
transaction time, confidence, epistemic class (observation / commitment /
preference), source type, scope, and provenance (episode). Nothing is ever
hard-deleted: contradictions close a validity interval, so the graph answers
both *"what do we currently believe about X"* and *"what did we believe in
March, and why did it change."*

```
$ bin/claim history --subject AuthService --predicate has-version
1.0.0   t-invalid: 2026-06-10T00:07:14Z   (superseded)
2.0.0   t-invalid: null                   (current)
```

## Setup

Onboarding is designed to be delegated. Tell your coding agent:

> Set up claimgraph as this project's memory system: clone
> https://github.com/kirahowe/claimgraph somewhere stable (e.g. `~/tools`),
> run its `scripts/setup.sh`, then run `claim setup` in this project and
> follow the "next" steps it prints.

**Prerequisites.** claimgraph runs on two native binaries — babashka (`bb`)
and the Datalevin pod (`dtlv`), no JVM — and `scripts/setup.sh` installs
both, so the prompt above is genuinely the whole setup. With Homebrew on
PATH it installs through each project's official tap (`borkdude/brew/babashka`,
`huahaiy/brew/datalevin`); without brew it falls back to pinned GitHub
release downloads into a user-writable dir — either way, no sudo. They are
hard requirements: `claim` refuses to run without `bb`, and `claim setup`
checks for `dtlv` before wiring anything (a SessionEnd hook without its pod
binary would just be session-end noise, so a failed preflight blocks with
the fix attached). The LLM tiers additionally want an authenticated `claude` CLI
(or any command via `$CLAIMGRAPH_LLM_CMD`) — that one is optional: without
it, extraction and judging sit out while every deterministic layer works.
Likewise optional: the TypeScript/JavaScript code analyzer shells out to a
pinned dependency-cruiser via `npx`, so it wants Node on PATH — a TS repo
has that by definition, and without it the analyzer just skips with a hint
while every other language still ingests.

Or by hand — the same two binaries, then one command in your project:

```bash
git clone https://github.com/kirahowe/claimgraph ~/tools/claimgraph
~/tools/claimgraph/scripts/setup.sh   # babashka (bb) + the Datalevin pod (dtlv)
                                      # via Homebrew (falls back to pinned GitHub
                                      # downloads) + a global `claim` launcher
cd ~/your-project
claim audit                           # start here: score the memory pile you
                                      # already have — read-only, no store
claim setup                           # the whole onboarding, one idempotent command
```

`claim setup` creates and seeds the store (`./.claimgraph/db`), gitignores
it and every sibling it writes — the write lock, the curation lock and log,
the evidence dir, the oplog, the retrieval log, the format stamp — in one
marker-delimited block, so a later release that adds a sibling edits the
block instead of appending a second one (the committable artifacts are
`claim dump` output and `.claimgraph/config.json`), installs the agent skill into
`.claude/skills/claimgraph/` (the judgment layer: when to consult, when to
record, how to phrase facts), and wires the ambient loop — a SessionEnd hook
so every session ends by feeding the graph and the next one starts with its
compiled view injected. Every step reports as JSON, re-running is always
safe, `--dry-run` shows the plan without writing, and `--mcp` additionally
registers the MCP front-end in `.mcp.json`. Any non-default location you
pass (`--db`, `--notes-dir`, `--settings-file`, ...) is persisted to
`.claimgraph/config.json` so later commands need no flags — see
[Configuration](#configuration).

`scripts/uninstall.sh [PROJECT_DIR] [--purge] [--global]` undoes `claim
setup` in a project, by the same markers setup wrote: the hook entries, the
`.mcp.json` registration, the agent skill, and the gitignore and
compiled-view marker blocks. It keeps `.claimgraph/` (the store and its
config) unless run with `--purge`, and `--global` removes the `claim`
launcher — `bb` and `dtlv` are shared tools and stay.

## Quickstart

### Start with an audit — before you install anything

Every agent-assisted repo accumulates a memory pile — the auto-memory notes
Claude Code, Codex, and friends write to on their own — and nothing ever
checks it for internal consistency, or against the `CLAUDE.md`/`AGENTS.md`/
rules files you actually wrote. `claim audit` points claimgraph's conflict
machinery at both: it scores the pile for self-contradiction and staleness,
and — the marquee check — flags where your agent's memory contradicts your
standing instructions, an `instruction-conflict`, before you install
anything:

```
$ bin/claim audit --scorecard
  87 claims extracted from 4 files
   7 contradictions  (opposed claims coexisting in the pile)
   2 instruction conflicts (agent memory at odds with your instruction files)
  12 disagreements   (same subject, different values — the last one read silently wins)
   9 stale           (contradicted by what the code says today)
  23 restatements    (the same fact maintained in more than one place)
   3 name clusters   (AuthSvc / auth-service / AuthService)
  41 KB injected per session against a ~25 KB window  ** over budget **
     (of which 8 KB is claimgraph's compiled view)
     15 KB of on-demand notes scanned, not injected
```

It runs entirely in a throwaway in-memory store: nothing is written, the
real store is never opened, and `dtlv` isn't needed — the only
prerequisites are `bb` and an extractor command (`claude -p` by default).
Every finding carries verbatim quote receipts, an LLM judge pass filters
the false positives (skip it with `--no-judge`), and `--out report.json`
keeps the full JSON. The scorecard is what a terminal gets and what
`--scorecard` forces anywhere; piped or `--json` output is the findings as
JSON, so `audit | jq` and `audit` read by a human are the same run. Instruction
scanning matches what a harness actually injects — up-tree CLAUDE.md/AGENTS.md
files and the user-global one, not just the project root. The
injected-KB line only counts what a harness actually loads at session start
— instruction files plus whichever note is the harness's live inject
target — so the pile's other notes show up as on-demand KB, scanned for
consistency but never charged against the window. The staleness-vs-code prong covers every language the
analyzer registry detects (Clojure, Kotlin, TypeScript/JavaScript, plus
anything added via `code-analyzers`) and skips honestly when nothing is
detected; every other finding class works on any repo. The findings are precisely the
diseases the graph cures: post-adoption, staleness goes to ~zero by
construction, contradictions become tracked open conflicts, restatement
becomes reinforcement, and name drift becomes aliases.

Audit's model-call bill is stated up front: one preflight round-trip to
confirm the extractor answers, then one call per scanned file plus one per
judged conflict pair, hard-capped at `--budget` (default 20, the same knob
`curate` uses) — anything past the cap is deferred and named in the
scorecard rather than silently dropped. Calls run a few seconds to ~2
minutes each, so a typical pile scores in single-digit minutes, narrated as
progress lines on stderr (`--quiet` to silence them). `claim audit --no-llm`
runs the deterministic subset — file scan, injection arithmetic, the code
baseline — in seconds, no extractor needed. A run that can't reach its
extractor blocks before scanning anything and exits 1, instead of failing
partway through a pile with an unpredictable slice already spent.

### Feed and query the graph

```bash
# Mechanical code-analysis pass — no LLM, high confidence, idempotent.
# Runs every detected language analyzer (Clojure, Kotlin, TS/JS) in one pass.
# This alone replaces most of what people stuff into CLAUDE.md.
bin/claim ingest-code

# Record a human decision (a commitment — it will never be silently clobbered)
bin/claim assert --subject api-layer --predicate decided-against \
  --object GraphQL --class commitment --source-type decision-record

# Record a preference
bin/claim assert --subject AuthService --predicate prefers \
  --object "Result types over exceptions" --class preference

# Valid time is first-class on both ends: record history as it happened
bin/claim assert --subject svc --predicate deployed-via --object Heroku \
  --object-kind literal --valid-from 2026-01-01 --valid-until 2026-03-01

# Query
bin/claim facts --entity AuthService --pretty
bin/claim facts --entity claimgraph.store --direction in     # who depends on it?
bin/claim neighbor --entity AuthService --depth 2          # BFS expansion
bin/claim search "GraphQL"                                 # full-text
bin/claim facts --entity AuthService --as-of 2026-03-01    # time travel
bin/claim history --subject AuthService --predicate depends-on
```

Scope is a namespace on the entity before it is a label on the fact. Every
entity lives in one (`project` by default) and a name only resolves within
it: `--subject-scope` / `--object-scope` place the entities an `assert`
mints, `--entity-scope` (`--subject-scope` on `history`) picks the scope a
read resolves in, and the `entity` verbs take `--scope`. A fact's own
`--scope` is carried on the claim and filtered on read — asserting `--scope
module:auth` alone leaves the subject and object in `project`, which is
where the next read goes looking for them.

A query is a bare word or a flag — `search "GraphQL"` and `search --query
"GraphQL"` are the same call, as are `recall`, `coach`, and `outcome
accepted` / `outcome --valence accepted`. Put flags *before* the bare word:
option parsing stops at the first one, so `search "GraphQL" --db other/db`
reads the query and silently ignores the store you named.

Commands emit JSON to stdout (`--pretty` for humans) and JSON errors to
stderr. Three exit codes, because a typo and a failure are different
problems: **0** success, **1** the command ran and could not do what it was
asked, **2** the command line itself was wrong (unknown verb, a verb missing
its subcommand, a flag value that would not parse — the error names the
option and the value). Four verbs answer with something other than one JSON
object: `dump` writes JSONL, `mcp` speaks JSON-RPC over stdio, `audit`
prints its scorecard at a terminal (above), and `coach --hook` prints
nothing at all unless the gate fires. `bin/claim version` says which
claimgraph and which persisted format is running; `bin/claim help` is the
full verb list.

## Configuration

No file location is assumed. Every setting resolves through one precedence
chain — **CLI flag > environment variable > `.claimgraph/config.json` >
default** — and `claim config` prints each setting's resolved value, which
layer set it, and the fully resolved paths.

| Setting | Flag | Env var | Default |
|---|---|---|---|
| store path | `--db` | `CLAIMGRAPH_DB` | `<project>/.claimgraph/db` (project defaulting to cwd) |
| harness | `--harness` | `CLAIMGRAPH_HARNESS` | `claude-code` |
| auto-memory notes dir | `--notes-dir` | `CLAIMGRAPH_NOTES_DIR` | per harness, honoring `$CLAUDE_CONFIG_DIR` / `$CODEX_HOME` |
| inject file (write-back target) | `--inject-file` | `CLAIMGRAPH_INJECT_FILE` | per harness: `MEMORY.md` / `memory_summary.md` |
| hook-settings file | `--settings-file` | `CLAIMGRAPH_SETTINGS_FILE` | `<project>/.claude/settings.json` |
| skills dir (`setup`) | `--skills-dir` | `CLAIMGRAPH_SKILLS_DIR` | `<project>/.claude/skills` |
| LLM command | `--extractor` / `--command` | `CLAIMGRAPH_LLM_CMD` | `claude -p` |
| LLM call timeout (ms) | `--llm-timeout-ms` | `CLAIMGRAPH_LLM_TIMEOUT_MS` | `120000` |
| raw-evidence dir | `--evidence-dir` | `CLAIMGRAPH_EVIDENCE_DIR` | `<db>.evidence` |
| model-call budget (`curate`, `audit`) | `--budget` | `CLAIMGRAPH_BUDGET` | `20` |
| ambient code refresh | `--code-ingest` | `CLAIMGRAPH_CODE_INGEST` | `session-end` (or `manual`) |

A setting's name is its flag, always: `--notes-dir` sets `notes-dir`. Where a
flag was renamed the old spelling still works on the verbs that took it —
`--dir` for the notes dir, and for `ingest-code`'s `--project`,
`ingest-adr`'s `--adr-dir` and each of `audit`'s repeatable `--scan-dir`
(both spellings add, neither shadows); `--min-confidence` for `judge` and
`consolidate`'s `--min-verdict-confidence`, which gates a verdict's own
confidence rather than filtering facts on read.

The config file is JSON keyed by the kebab-case setting names
(`{"harness": "codex", "notes-dir": "/mnt/notes"}`), lives at
`$CLAIMGRAPH_CONFIG` or `<project>/.claimgraph/config.json` — project
defaulting to cwd, so nothing changes for a bare `claim <verb>` run inside the
project it manages, and a `--project`-taking verb resolves it (and a relative
`--db`) against the project it was given instead — and is **committable**
— `claim setup` writes the non-default choices you pass it, so one person's
choices hold for every writer of the repo. It carries a `config-version`
stamp (see below), and a key claimgraph does not recognise — a misspelling
resolves to nothing and changes nothing — is named on stderr and in `claim
config` rather than silently ignored. Two more env vars sit below the
settings layer: `$CLAIMGRAPH_DTLV` (path to the pod binary, default from
`$PATH`) and `$CLAIMGRAPH_WRITER` (this machine's oplog writer id, and only
until the store has one of its own — see Maintenance).

One structured setting lives in the config file only (structured values
don't fit flags/env): a `code-analyzers` map tunes the language-analyzer
registry by id — override a built-in's command, disable one
(`"typescript": false`), or add a new language whose command emits the
interchange format (`{"rust": {"detect": "**.rs", "command": "my-rust-deps
<roots>"}}` — one JSON object per source unit with `unit`, `file`,
`requires`, `language`; JSONL or a JSON array). Rust or Python support is a
ten-line script in your repo, no claimgraph change.

## Versions and formats

`claim version` says what is running — the release (`0.1.0-alpha`), the
persisted-format version (`1`), and the sha of claimgraph's own checkout when
it runs from one, with `"dirty": true` beside it when that checkout has
uncommitted or untracked changes and the sha therefore doesn't describe the
code that ran. That is the thing to quote in a bug report.

Two numbers because they answer different questions. The release moves
whenever anything ships; the format version moves only when an old reader
would get a *new* file wrong, and it is the integer stamped into every
artifact that crosses a machine or a version boundary: the dump's header
line, every oplog line and `applied.json`, the store's `<db>.version`
sibling, and `config-version` in `.claimgraph/config.json`. Each of those is
gated on the integer, never on the release string. A stamp above this build's
number is refused with "upgrade claimgraph"; a stamp that isn't a format
number at all is refused differently and never overwritten, because no
claimgraph wrote it and upgrading fixes nothing. The store's stamp is a
sibling file rather than a row inside the store because Datalevin merges a
schema as it opens: a stamp in the store would be written by the very call
meant to check it. The known cost is that `cp -r db/` alone leaves the stamp
behind — along with the oplog, which is the actual record.

## How conflicts resolve

When a new fact contradicts a currently-valid fact with the same
(subject, predicate) on a single-valued predicate, the resolution defaults
from the epistemic class:

- **observation / preference → supersede.** The predecessor's interval closes
  at the successor's `--valid-from` (non-lossy — it stays in history), so the
  new truth begins exactly where the old one ends and `--as-of` between two
  versions returns exactly one. A successor backdated to start *before* its
  predecessor is a valid-time contradiction, not a handoff — it flags as
  `backdated-overlap` instead of inverting an interval.
- **commitment → flag.** Both facts stay valid, the conflict is linked and the
  `candidates` are returned. A human decision is never silently overwritten by
  new evidence — it surfaces for review.
- Caller override: `--on-conflict supersede|flag|ignore`.
- Multi-valued predicates (e.g. `depends-on`) accumulate. Exact duplicates
  **reinforce**: the world (or the user) just confirmed the fact, so its
  disuse clock resets and its confidence may rise toward a per-source ceiling
  — never above it, never down, and never by repetition alone.
- **A stronger class escalates instead of reinforcing.** Re-asserting the same
  claim with an explicitly stated stronger epistemic class (observation →
  preference → commitment) supersedes, so "this isn't just something we
  noticed, we decided it" reads as a change in history rather than another
  reinforcement of an observation — which would keep the fact fading by
  disuse and superseding silently. Only a class the caller *stated* counts:
  the predicate registry's default is not a statement, or every mechanical
  re-ingest would supersede its own facts and grow the store without bound.
- **Exclusion groups** widen detection across predicates: registry rows can
  declare mutually-exclusive stances toward the same object (`prefers` /
  `decided-against` share the `:stance` group; `supersedes`/`superseded-by`
  the `:revision` group), matched loosely across the entity/literal divide —
  `decided-against "GraphQL"` collides with `prefers GraphQL`. Stance
  collisions always involve a commitment, so they flag by composition.
  Groups are deliberately conservative: a false conflict nags the human, so
  only clearly-opposed pairs are declared; everything fuzzier goes to the
  sweep.

An invalidated fact records what closed it, in fields rather than in prose:
`invalidation-kind` (`superseded`, `judged-superseded`, `judged-duplicate`,
`merge-duplicate`, `reconcile-duplicate`, `code-absent`, `manual`),
`successor` (the fact-id that took its place — absent when nothing did, as
when the code stopped saying it), and `invalidation-reason`, a sentence for
humans that nothing parses. The kinds are an open set: a kind from a newer
writer arrives verbatim and matches nothing, rather than being coerced into
one this build happens to know.

Flagged conflicts stay open until resolved. `claim conflicts` lists them;
`claim judge` runs an LLM over each pair and classifies the relation —
`contradicts`, `duplicate`, `supersedes`, or `compatible`. By default it only
reports; with `--resolve` it invalidates duplicates and superseded facts and
unlinks compatible pairs, gated by `--min-confidence` (0.8). A `contradicts`
verdict is never auto-resolved — genuine contradictions always go to the
human. The judge command is pluggable like the extractor (`--command` /
`$CLAIMGRAPH_LLM_CMD`, default `claude -p`).

`claim judge --sweep` generates the candidates the write path can't see —
multiple values of a `:value-exclusivity :exclusive` predicate (two `prefers`
on one subject tend to be alternatives, not accumulation), and same-subject
facts sharing an object across predicates where one side is a decision
(`depends-on X` while `decided-against X` stands). Generation is pure and
bounded per-subject, never O(graph²); the LLM never runs on the write path.
Verdicts feed the same pipeline: compatible pairs are dropped silently,
genuine hits are linked, contradictions wait for the human. `consolidate`
runs the sweep as a stage.

## The vocabulary

23 curated `core/*` predicates across four categories (structural, procedural,
decision, provenance), each a first-class queryable row in the same store with
object-kind, cardinality, default epistemic class, and a `maps-to` anchor to an
established standard (PROV-O / SPDX / DOAP / Dublin Core).

Unknown predicates throw with a `did-you-mean` fuzzy suggestion — the cheap
defense against LLM-driven predicate proliferation. New relations are coined
in the `x/*` staging namespace (auto-registered with `:testing` status) and
promoted once proven. `bin/claim predicates --usage` shows what's earning
its place.

## Architecture

Functional core, imperative shell. All decision logic — conflict resolution,
epistemic/object-kind rules, bi-temporal filters, BFS folds, decay plans — is
pure functions over plain values in `claimgraph.logic` (time and fresh ids are
passed in; decisions come back as effect plans). `claimgraph.core` is the thin
shell that gathers store reads, asks logic for a decision, and executes the
plan. Mutation is concentrated there and in the store implementations.

```
CLI / skill front-end        src/claimgraph/cli.clj        arg parsing, JSON in/out
        │
   imperative shell          src/claimgraph/core.clj       gather reads → pure decision
        │                                                → execute effect plan
   functional core           src/claimgraph/logic.clj      PURE: conflict policy, temporal
        │                    src/claimgraph/predicates.clj filters, BFS folds, decay plans,
        │                                                vocabulary + validation
   ┌────┴─────────┐
   │ Store protocol│         src/claimgraph/store.clj      the storage abstraction
   └────┬─────────┘
        │
   datalevin impl            src/claimgraph/store/datalevin.clj   the only layer that
   in-memory impl            src/claimgraph/store/memory.clj      knows Datalog/datoms
```

- **Storage**: [Datalevin](https://github.com/datalevin/datalevin) via Babashka
  pod — the `dtlv` binary is a GraalVM native image that speaks the pod
  protocol, so the whole stack is two fast-start native binaries. The pod is
  loaded from `$PATH` (override with `$CLAIMGRAPH_DTLV`) at a pinned release.
  The storage abstraction keeps other engines pluggable; the test suite runs
  identically against the in-memory implementation, which is the proof of it.
- **Bi-temporality is modeled, not engine-native**: explicit `t-valid` /
  `t-invalid` / `recorded-at` attributes, identical in shape across backends.
  Valid time is settable on both ends of every write; transaction time
  (`recorded-at`) is append-only. Full XTDB-style correction history
  (auditing how a belief about the past evolved) is deliberately out of
  scope.
- **Objects are entities or literals** (RDF-style): traversal only follows
  entity-kind objects; preferences live as literal facts without minting junk
  nodes. Enforced at write time per the predicate registry.
- **Inverses are computed at query time** (`--direction in|both`), not stored
  as twins — nothing to keep consistent on invalidation.
- **`has-status` is a predicate**, so ADR status history accumulates
  bi-temporally and status changes flow through the conflict machinery.
- **Entity resolution is layered**: lookups resolve exact names, then aliases,
  then a unique case/separator-insensitive match (type-guarded, so a namespace
  can't silently match a class). A detected collision (two or more normalized
  matches) never guesses AND never creates — it errors with the candidates
  attached, and ingestion routes such facts to the error bucket instead of
  minting a third entity. Write-path near-matches self-heal by recording the
  queried name as an alias. Curation
  verbs handle the rest: `entity rename` keeps the old name as an alias with
  facts and history intact, `entity merge` repoints facts and collapses the
  exposed duplicates non-lossily, `entity split` records `derived-from`
  lineage, and `entity duplicates` reports likely-duplicate clusters.

## Ingestion tiers

1. **`ingest-code`** — mechanical multi-language analysis (no LLM) through a
   registry of analyzer adapters: Clojure internally via edamame, Kotlin via
   an internal line parse, TypeScript/JavaScript by shelling to a
   version-pinned dependency-cruiser via npx (missing tooling skips that
   analyzer with a hint, never an error), and anything else via the
   `code-analyzers` config seam. Every detected language runs in one pass
   under one episode; all analyzers emit the same interchange format (one
   JSON object per source unit) and feed the same driver: `defined-in`,
   `depends-on`, `written-in` facts at 0.95 confidence under a `:code`
   episode ref'd to `<git-sha>[+<dirty-digest>]`. Each pass reconciles the
   store against the code: facts the analysis no longer produces (deleted
   files, removed requires, dropped units) are invalidated mechanically,
   unchanged facts no-op, a unit that moves files supersedes its old
   location, and imports that can't be resolved locally become
   external-scoped facts — never a wrong local edge. Reconciliation is
   language-guarded: a skipped analyzer's facts are exempt, so degradation
   never invalidates what it didn't look at. The graph tracks the code with
   no LLM in the loop, and the ambient loop keeps it fresh (see Maintenance).
2. **`ingest-session`** (`session-extract` still dispatches, and always will
   — the old name is in installed skills and hook command lines) — LLM
   extraction of durable knowledge (preferences,
   decisions, gotchas, conventions) from a session transcript — plain text or
   Claude Code session JSONL. The extractor is pluggable: defaults to an
   already-authenticated `claude -p` (subscription-as-judge, ~$0 marginal),
   overridable via `--extractor` / `$CLAIMGRAPH_LLM_CMD`. The prompt carries a
   bounded roster of known entities (top by fact count, with aliases) as a
   prior — "use these exact names when you mean them" — so the extractor
   aligns synonyms instead of coining `AuthSvc` next to `AuthService`;
   normalization catches typographic drift, the roster catches semantic
   drift, and ambiguity detection backstops both. Session-derived
   facts are second-class evidence by design: confidence capped at 0.7,
   source-type `session-log`. `--dry-run` shows what would be ingested.
3. **`ingest-notes`** — the ambient tier (`docs/consuming-auto-memory.md`):
   consumes the harness's auto-memory notes (Claude Code's
   `~/.claude/projects/<project>/memory/`; `--harness codex` for Codex's
   `~/.codex/memories/`, making claimgraph the cross-harness consolidator —
   notes from both harnesses about the same project merge into one graph,
   entity resolution aligns their vocabularies, and restatement across
   harnesses reinforces) as an extraction substrate —
   already LLM-distilled, delta-detected per file so only changed notes reach
   the extractor, one episode per (file, revision) so provenance answers
   "which note file, at which state, said this." Notes flatten who-said-what,
   so everything ingests as agent inference: source-type `agent-note`,
   confidence capped at 0.65, and never a commitment — a decision reported by
   a note is demoted to an observation; genuine decisions arrive via `assert`.
   No reconciliation: the harness compacts notes under space pressure, and
   absence-by-compaction isn't falsity — un-restated facts just fade by
   disuse. The marker-delimited managed section claimgraph writes into
   `MEMORY.md` is stripped before hashing and extraction (the echo-loop
   guard), so the graph never re-consumes its own compiled view.
4. **`ingest`** — batch JSONL (file or stdin) under one episode. Each line
   goes through the full conflict machinery; `class` is accepted as an alias
   for the epistemic field.
5. **`assert`** — one fact, interactively or from a skill.

## Raw evidence

Extraction decides what to keep before knowing what a future query will
hinge on (the write-before-query barrier), so `ingest-session` and
`ingest-notes` also keep their raw input: immutable, content-addressed
artifacts in `<db>.evidence/`, pointed to by the episode they were extracted
under. A pointer names the function that made it — `sha256-<64 hex>`, a
hyphen because the string is a filename before it is an identifier — and
pointers written before the tag existed still resolve. `bin/claim evidence
--episode ID` returns the exact bytes —
provenance past the summary, and nothing an extractor drops is
unrecoverable. Notes-as-primary, transcripts-as-fallback: the artifacts are
a local audit trail and don't ride the dump; the pointer does.

## Forgetting

Disuse decay is a view, not a job. Facts store a base confidence and a
`last-reinforced-at` anchor; reads compute *effective* confidence — the base
halved per 90-day half-life since last reinforcement (floor 0.05) — and
return both. `--min-confidence` filters on the effective value, search ranks
fact hits by it, and `--as-of` queries see period-appropriate decay.
Commitments and decision-record facts never fade.

Reinforcement is what counts as "use": re-asserting an existing fact (a
session restates it, the code ingester re-derives it) resets its clock and
raises its base toward a per-source ceiling (`decision-record` 1.0, `code`
0.95, `user-assertion` 0.9, `session-log` 0.7, `agent-note` 0.65, `inferred`
0.6) — so a fact re-derived 500 times stays distinguishable from a human
decision. The ceiling binds at birth too: `--confidence 0.99` on a
`session-log` fact mints it at 0.7, because reinforcement is a high-water
mark that never claws a base back down, so a fact born above its ceiling
would sit there permanently. Raising a fact's confidence means raising the
trust of the source it claims, not the number. The
ingester synergy does most of the work: every code pass reinforces what the
code still says, reconciliation invalidates what it stopped saying, and
decay is left fading the session-derived facts nobody restates. Maintenance
scans never reinforce — only intent writes do.

## Maintenance

- `hooks install` / `hooks run` — the ambient loop, automated: a Claude Code
  SessionEnd hook (wired by `hooks install` into the project's hook settings
  — default `.claude/settings.json`, overridable via `--settings-file`) runs
  `ingest-code-if-changed` → `compile-context` → spawn `curate`, at the end
  of every session. **The session's exit is capture, and capture is
  deterministic**: it takes seconds and never waits on a model. The code
  stage runs first (so the curator's entity roster and conflict ground truth
  are fresh) and is delta-gated: every code pass closes its episode with ref
  `<git-sha>[+<dirty-digest>]`, and the stage skips in milliseconds when the
  current ref matches the newest code episode's — teammates' pulled changes
  reconcile mechanically at the next session end, no agent judgment in the
  loop. `code-ingest: manual` opts a project with an expensive analyzer out;
  non-git projects always run (matching manual semantics). Stages report
  independently — an analyzer failure never blocks the deterministic
  recompile. Capture in, injection out.
- `curate` — the detached run the hook hands off to, and where every model
  call in the ambient loop lives: `ingest-notes` (the just-ended session's
  knowledge is the freshest), then `consolidate` (enrich-only), then
  `compile-context` so the next session's injected view carries what curation
  learned. Spawned, never awaited; its output goes to `<db>.curate.log`. One
  model-call budget spans the run (`--budget`, default 20) and every call
  lands a durable outcome — a recorded verdict, a closed episode, a recorded
  enrichment attempt — so what is left to do is *derived* from the store,
  each run shrinks the remainder, and a converged store makes the pass a free
  no-op. Whatever the budget did not reach is named in the report and picked
  up next run. A singleton: a second curator finding the curation lease
  (`<db>.curate.lock`) held reports `already-running` and exits 0, because
  the live one's work covers it. It holds no standing write lease — it takes
  one per applied outcome and never across a model call, so a session's
  capture is never queued behind somebody else's completion.
- `compile-context` — the write-back half of the ambient loop
  (`docs/consuming-auto-memory.md`): compiles the graph's current view into a
  marker-delimited managed section at the head of the file the harness
  auto-injects (Claude Code: `MEMORY.md`), so every session starts with it for
  free. Deterministic (no LLM), budgeted (25 KB default — the injection
  window), idempotent. Priority order: standing decisions (never relitigate),
  open conflicts, recent supersessions ("Heroku → Fly on 2026-06-02" — the
  what-changed briefing), top current facts by effective confidence, with
  code-derived facts excluded — the injected view carries only what the code
  can't say. `ingest-notes` strips the managed section before hashing, so
  compile → ingest → compile is a fixed point: the graph never re-consumes
  its own view.
- `consolidate` — the Dreaming-style offline pass: LLM-summarizes and closes
  open episodes (summaries are full-text indexed, so episodic history becomes
  searchable — "why did we do X" is a query), judges open conflicts, sweeps
  for conflict candidates the write path can't see, and reports `x/*`
  predicates earning promotion review. Falls back to a mechanical digest when
  the LLM is unavailable, so the pass always makes progress.
- `judge` — LLM review of open conflicts on its own (see "How conflicts
  resolve").
- **Multi-writer works the local-first way.** Every mutation appends an
  effect line to this writer's own append-only log in
  `<db>.oplog/<writer>.jsonl`, stamped with a hybrid logical clock. The live
  store is a materialized view; the logs are the record. Each machine only
  appends to its own file, so any file syncer (git, rsync, Syncthing) can
  move logs between machines and a merge conflict cannot occur in transport.
  `claim reconcile` applies unseen foreign effects in canonical clock
  order, matches entity identity by name (each machine keeps its own display
  name and picks up the other's as an alias), collapses claims both writers
  made independently (closed, not erased), and queues contradictions neither
  writer could see for the judge. This is deliberately not a CRDT: when two
  machines disagree, the job is to show a human the disagreement, and open
  conflicts are already how claimgraph does that.
- On one machine, concurrent writers serialize through a **lease**
  (`<db>.lock`: atomic, token-guarded). A holder renews it while it works, so
  the 30s TTL bounds only how long a *dead* writer's lease outlives it — a
  `consolidate` pass that shells out to a model fifty times no longer expires
  under its own duration and hands a waiting writer a lease it was right to
  think dead. A writer that loses the lease anyway (a suspended laptop, a
  hand-deleted lock file) still finishes its work and then fails with
  `lease-lost` and the report of what it wrote: nothing may report an
  unserialized write as a serialized one. A blocked writer waits
  `--lease-wait` ms (default 5000) before erroring with the holder's name.
  Reads never take it.
- `dump` / `load` — the portability story, two-way: `dump` exports everything
  as JSONL (the live LMDB directory is gitignored; the dump is the committable
  artifact), `load` restores a fresh store from it — fact/episode ids,
  validity intervals, invalidations with their kind and successor, and
  conflict links round-trip exactly (a raw restore; the conflict machinery
  does not re-run). The first line is a header record naming the format
  version, and `load` refuses anything it cannot read *in full* before it
  writes a single row: a non-empty target, a file with no header and nothing
  claimgraph stamped, a format above this build's, a pre-alpha dump (whose
  entity types were destroyed at write time — re-dump from the source store).
  Restoring the part it understood would leave a store that looks whole and
  isn't. Multi-machine users of the ambient loop converge through the
  committed dump.

## MCP front-end

`bin/claim mcp` serves the graph over MCP stdio — the store (and the
Datalevin pod) opens once per session instead of paying ~350ms of cold start
per CLI call, which the ambient loop's per-prompt coach hook made worth
fixing. Every tool is `memory_` plus its CLI verb — one rule instead of a
list to memorize — so the eight are `memory_facts`, `memory_neighbor`,
`memory_search`, `memory_recall`, `memory_history`, `memory_conflicts`,
`memory_coach`, and `memory_assert` (write-lease-guarded, full conflict
machinery, valid time on both ends like `assert`). Argument names are
accepted in MCP's snake_case and claimgraph's kebab alike; results stay
kebab, because they are the same records `claim facts` prints and `claim
dump` commits. A tool name this server doesn't have fails at the transport,
where the client's wiring is what's broken; a failure *inside* a tool comes
back as a result carrying the CLI's exact error payload, so the model
driving it reads the same hint a human would have seen on stderr. Wire up:
`claim setup --mcp` (writes the project's `.mcp.json`) or `claude mcp add
claimgraph -- claim mcp`. The CLI and the skill remain the primary surface;
MCP is the low-latency second front-end the handoff doc trigger-gated.

## Benchmark

No LongMemEval/LoCoMo equivalent exists for codebase memory, so `bench/` is
the seed of one: a synthetic project (shoply) that lives through three
sessions and three code passes from January to now — decisions made and
relitigated, a namespace renamed and merged, a hosting migration, a
dependency taken up against a standing rejection, an observation nobody ever
restates. Two layers, split by determinism:

```bash
bb bench       # mechanics: recorded LLM outputs, real store and ingesters,
               # 33 questions across retrieval / time-travel / history /
               # identity / conflicts / forgetting / provenance / ambient
               # (the notes loop: restatement reinforces, planted decisions
               # demote and flag, compaction ≠ falsity, echo guard holds) /
               # staleness (the code contradicts session facts and standing
               # decisions without anyone saying so) / abstention (refusal vs
               # confabulation when the graph does not know) / poisoning (MINJA-
               # style planted content: caps, decay differential, flag-not-
               # override, quarantinable provenance) / shift-recovery (Recovery@0
               # after the rename, the dropped dep, and the migration) /
               # contamination (a swapped name — React, the in-house clojure
               # queueing library — must answer from the graph). Reports
               # per-read latency and the real CLI cold-start next to accuracy.
               # Deterministic; non-zero exit below a perfect score, and it
               # runs in the test suite as a longitudinal regression gate.
bb bench llm   # quality: the same graph, a real model ($CLAIMGRAPH_LLM_CMD).
               # `bb bench llm 5` judges each labeled conflict pair 5 times
               # (default 3): accuracy-of-majority with per-pair flip rate —
               # a pair that flips is a pair --resolve must not act on.
               # Measures extraction precision/recall against annotated
               # transcripts, judge verdict accuracy on labeled conflict
               # pairs, and entity fragmentation (suspect names that
               # normalization can't rescue). Informational; never in CI.
```

`CLAIMGRAPH_BENCH_STORE=memory` runs mechanics pod-free. Fixture, questions,
and ground truth live in `bench/`; the harness drives the same core API the
CLI uses, so an MCP front-end won't invalidate it.

## Tests

```bash
bb test    # 316 tests / 1925 assertions
```

The core-semantics suite runs against BOTH store implementations (the proof of
the storage abstraction); the logic suite tests pure decision functions with no
store at all; the session suite injects a fake extractor and never shells out.
`CLAIMGRAPH_TEST_SKIP_DATALEVIN=1 bb test` runs pod-free.

## The book

`book/` holds a full-length book about the project: background and mental
model as prose, and every code-bearing chapter as a real Clojure namespace
that Clay evaluates against this source tree at build time, rendered into a
Quarto book. Build it with `bb book` (needs a JVM and the
[quarto](https://quarto.org) CLI; the tool itself needs neither), or
`bb book:preview` to serve it locally. Output lands in
`book/rendered/_book/`.

## Documents

- `ROADMAP.md` — the current build plan: 28 ordered issues from the July 2026
  research round.
- `TODO.md` — earlier roadmap and decisions taken (ordering superseded by
  `ROADMAP.md`).
- `docs/agent-memory-synthesis.md` — the conceptual landscape this grew from.
- `docs/claimgraph-handoff.md` — design decisions, rationale, and open forks.
- `docs/memagent-2026-review.md` — claimgraph vs the ICLR 2026 MemAgents
  workshop and the 2026 field: validated bets, gaps, benchmark plan.
- `docs/memory-systems-comparison.md` — field comparison (July 2026): built-in
  harness memory, platforms, OSS/research systems, and where claimgraph stands.
- `docs/consuming-auto-memory.md` — design note: consume the harnesses'
  auto-memory as an ingestion tier and compile the graph back into their
  injection surface (the ambient loop).
- `docs/memory-audit.md` — spec + handoff for `claim audit`: the scorecard
  that audits your agent's memory pile together with your instruction files
  (contradictions, instruction conflicts, staleness, restatement, name
  drift, injected-vs-on-demand bytes) that runs before claimgraph is even
  installed. First of the three measurement tiers; shipped
  (`src/claimgraph/audit.clj` — the header notes the few implementation
  deviations).
- `docs/language-adapters.md` — spec + handoff for the language-adapter
  registry (Kotlin + TypeScript via command-shaped analyzers, a
  bring-your-own-analyzer config seam) and the ambient code-freshness stage
  (delta-gated `ingest-code` in `hooks run`, so teammates' pushed changes
  reconcile mechanically). Shipped (`src/claimgraph/ingest/code.clj` + the
  per-language adapter namespaces — the header notes the few
  implementation deviations).
- `.claude/skills/claimgraph/SKILL.md` — the usage judgment: when an agent should
  consult, write, and how to phrase facts. Generated from
  `resources/claimgraph/SKILL.md` (the template `claim setup` installs into
  projects) — edit the template, not this copy; a test keeps them in sync.

## License

MIT — see [LICENSE](LICENSE).
