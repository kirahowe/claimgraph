# `claim audit`: Find the Contradictions in Your Agent's Memory

*Spec + handoff, 2026-07-23. First of three measurement tiers brainstormed
for the dogfooding round: (1) **audit** — instant, mechanical scorecard over
the project's existing memory pile (this doc); (2) **exam** — before/after
agent Q&A against repo ground truth; (3) **longitudinal** — relitigation /
repeat-mistake / correction rates from the ambient loop. Build (1) now;
(2) and (3) get their own notes when picked up.*

***Status: shipped 2026-07-23*** *(`src/claimgraph/audit.clj`,
`claimgraph.audit-test`, the `audit` CLI verb). Implementation deviations
from this spec, all in the settle-in-implementation spirit:*

- *On this verb `--dir` means extra scan directories (§2), so the
  auto-memory notes-dir override flows only through `$CLAIMGRAPH_NOTES_DIR`
  / `notes-dir` in the project config — not the `--dir` flag the other
  ambient verbs use for it.*
- *Name clusters: `core/entity-duplicates` alone can't see the drift the
  write path already self-healed (a normalized near-match resolves and
  records an alias instead of minting a duplicate entity), so audit also
  reports alias clusters — entities the pile referred to by two or more
  distinct names. Semantic drift with different normalizations (AuthSvc vs
  AuthService) remains extractor-roster territory and is not mechanically
  clustered.*
- *`extraction-noise` reports `{rejected, inadmissible, ambiguous}` —
  admission-screen rejections are split out from incomplete triples.*
- *A pile claim that reinforces a code-sourced fact is reported as a
  restatement with `restates-code: true` (the pile maintaining what the
  code already says is injection spend for zero information).*
- *`--pretty` prints the §1 human scorecard (plus per-finding receipts)
  instead of pretty JSON; the JSON schema is the default stdout output and
  the `--out` payload.*
- *2026-08-06: sources are typed by origin — the default-scan-set files and
  `.cursor/rules/*` are `:instruction` (human-maintained; mint at
  `:user-assertion` trust/ceiling), the auto-memory notes dir and
  `--file`/`--dir` extras are `:note` (unknown or agent authorship; stay at
  `:agent-note`). Instructions ingest before notes, so a note colliding with
  a standing instruction is reported as `instruction-conflict`, alongside
  (never inside) `contradiction`. Every claim side carries a `:source` label
  (`code`/`instruction`/`note`), and a restatement spanning an instruction
  file and a note file is flagged `restates-instructions`, the same idea as
  `restates-code`. `:user-assertion` shares `logic/source-trust`'s rank 3
  with `:code`, so without a guard an instruction claim would cleanly
  supersede a colliding code fact (a disagreement) rather than being
  outranked (stale) and would remove it from the store for every later
  claim besides — `audit-file!` closes this by checking, before each
  assert, whether a code fact already answers that claim's (subject,
  predicate) and forcing `:on-conflict :flag` when it does, pinning code as
  un-supersedable ground truth. The staleness signal is reliable for both
  kinds.*
- *2026-08-06: injection arithmetic counts what each harness actually
  injects at session start. Instruction files count raw, on-disk, in full —
  that's what the harness reads and injects — and so does the harness's own
  notes inject-file (e.g. `MEMORY.md`), managed section included, with the
  managed share broken out separately as `managed-bytes`. Every other
  auto-memory note is on-demand — recalled, never injected at session
  start — and is excluded from the injection window math (`on-demand-bytes`
  instead), even though it is still scanned for every other consistency
  finding. A source that strips to nothing (a `MEMORY.md` that is entirely
  claimgraph's compiled view) stays in the file list, marked `:skipped`, so
  the biggest injected file on a post-adoption repo never disappears from
  its own accounting — it is only kept out of extraction, which is what the
  echo guard protects.*
- *2026-08-07: the scan covers every instruction file Claude Code actually
  discovers at session start, not just the project root — `.claude/CLAUDE.md`
  and `.claude/rules/*.md` alongside the existing `.cursor/rules/*`; a
  `CLAUDE.md`/`CLAUDE.local.md`/`AGENTS.md`/`AGENT.md` ancestor walk from the
  project root up to the filesystem root (bounded only in tests, via an
  internal `collect-sources` opt — real runs walk to the real root); each
  configured harness's own
  `:global-instructions` (Claude Code: `$CLAUDE_CONFIG_DIR/CLAUDE.md` and its
  `rules/*.md`; Codex declares none); and the OS-specific managed-policy
  `CLAUDE.md` path, filtered to whichever exists. All mint `:instruction`,
  `:injected? true`. `@`-imports inside a `CLAUDE.md` are not followed — a
  live v1 limitation, not a planned one.*
- *2026-08-07: model calls are budgeted and isolated rather than run
  open-loop. A one-call extractor preflight runs before the pipeline;
  failure there is a `status: "blocked"` scorecard and exit 1, the same
  shape a missing `dtlv` gives `setup` — a run that can't reach its
  extractor fails up front, not partway through a pile. Past preflight,
  `--budget` (default 20, the same knob `curate` uses) caps total calls
  across extraction and judging; the cap is spent on the sources worth it —
  injected files first, since those cost context in every session, then
  largest — and work past it is deferred and named in the scorecard rather
  than silently dropped. Extraction runs `--concurrency` calls at a time
  (default 6) because each is a subprocess that spends its time waiting;
  assertion stays serial, so the order claims reach the store, and with it
  the note-against-standing-instruction collision direction, is unchanged by
  how the calls interleave. Each remaining call is
  isolated — one file's or one pair's failure doesn't abort the run — and
  surfaces as a `status: "partial"` scorecard carrying an `:llm` receipts
  section; three consecutive failures trip a breaker rather than spending
  the rest of the budget against a dead extractor. Progress narrates on
  stderr as each call lands (`--quiet` silences it), and `--no-llm` skips
  the extractor entirely for the deterministic subset — file scan,
  injection arithmetic, the code baseline.*

-----

## 1. Purpose and pitch

Every agent-assisted repo accumulates a memory pile: `CLAUDE.md`,
`AGENTS.md`, auto-memory notes, rules files. Nothing ever checks that pile
for internal consistency, and Claude Code's own docs warn that contradictory
memory causes arbitrary behavior. `claim audit` points claimgraph's existing
conflict machinery at the pile and produces a scorecard:

```
$ claim audit --pretty
  87 claims extracted from 4 files
   7 contradictions   (opposed claims coexisting in the pile)
  12 disagreements    (same subject, different values — whichever the
                       model reads last silently wins)
   9 stale            (contradicted by what the code says today)
  23 restatements     (the same fact maintained in more than one place)
   3 name clusters    (AuthSvc / auth-service / AuthService)
  41 KB injected per session against a ~25 KB window
```

This is the top of the funnel: it runs **before** claimgraph is installed,
writes **nothing**, needs **no store backend** (in-memory, pod-free — see
§5), and its findings are precisely the diseases the graph cures. The
"after" story is structural, not aspirational: post-adoption, staleness ~0
by construction (code reconciliation invalidates what the code stopped
saying), contradictions become tracked open conflicts instead of silent
coexistence, restatement becomes reinforcement, name drift becomes aliases.

**Non-goals.** Audit does not fix anything, does not write to any store,
does not measure agent behavior (that's the exam), and does not need to be
deterministic to the claim — it's a diagnostic, not a regression gate (but
see §9 on variance).

## 2. UX

```
claim audit [--project DIR]           # default cwd
            [--file F]...             # extra sources beyond the default scan
            [--dir D]...              # extra directories (every *.md inside)
            [--no-code]               # skip the staleness-vs-code prong
            [--no-judge]              # skip the LLM verdict pass (report raw)
            [--no-llm]                # deterministic subset only — no extractor
            [--budget N]              # model-call cap, extraction + judging (default 20)
            [--concurrency N]         # extraction calls in flight (default 6; 1 = serial)
            [--extractor CMD]         # the usual chain ($CLAIMGRAPH_LLM_CMD…)
            [--quiet]                 # silence the stderr progress lines
            [--out FILE]              # also write the scorecard JSON to FILE
            [--pretty]
```

JSON scorecard on stdout like every other verb; `--pretty` for the human
rendering above plus per-finding detail (each finding carries its source
files and the claims involved, so every number is auditable). Exit 0 with
findings — it's a report, not a gate — but an extractor preflight failure
blocks before scanning anything and exits 1, the same shape a missing
`dtlv` gives `setup`. (`--fail-on N` for CI is a later idea, §9.)

The scorecard ends with a `next` hint: `claim setup` — the funnel step.

## 3. What counts as the pile

Default scan set, all relative to `--project`, each existing file only:

- `CLAUDE.md`, `CLAUDE.local.md`, `AGENTS.md`, `AGENT.md`
- `.github/copilot-instructions.md`
- `.cursorrules`, `.cursor/rules/*` (plain text/mdc — treat as markdown)
- the harness auto-memory notes dir, resolved exactly like `ingest-notes`
  (`harness/notes-path` — honors every override and `$CLAUDE_CONFIG_DIR`)

Plus anything from `--file`/`--dir`. Two guards:

- **Echo guard**: strip the marker-delimited managed section
  (`harness/strip-managed-section`) from every source before extraction —
  if claimgraph is already installed, we must not audit our own compiled
  view back at ourselves.
- Skip empty-after-strip files (same rule as `notes/read-notes`).

## 4. Pipeline

Six stages, all inside one throwaway in-memory store:

1. **Collect** sources (§3): `{path, content, bytes}` per file, sorted by
   path for deterministic ingestion order.
2. **Seed** a fresh `claimgraph.store.memory/create` store with the
   predicate vocabulary (`core/seed!`).
3. **Code first** (unless `--no-code`, auto-skipped when no `src/**/*.clj`):
   `ingest.clj-code/ingest!` — the mechanical ground truth lands at 0.95
   confidence, source-type `:code`, *before* any pile claim, so a pile claim
   colliding with the code flags against a code-sourced candidate. That
   collision is the **staleness** signal.
4. **Extract + ingest the pile**, one episode per file (ref like the notes
   ingester: `audit:<path>@<hash>`). Extraction reuses the session/notes
   machinery (pluggable extractor, entity-roster prior, JSONL parse,
   `logic/screen-candidates` admission). Prompt is a new audit variant —
   see §6 for the two deliberate differences from the notes prompt.
   Every claim goes through `core/assert-fact`, and **the write path's
   status vocabulary maps directly onto finding classes**:

   | assert result | audit finding | meaning in a pile |
   |---|---|---|
   | `:flagged` | **contradiction** (or **stale** when a candidate is code-sourced) | opposed claims coexist; commitment machinery + stance exclusion groups caught it |
   | `:superseded` | **disagreement** | single-valued predicate, two different values across the pile — in markdown, whichever the model reads last silently wins; here it's a finding |
   | `:reinforced` | **restatement** | exact duplicate maintained in N places |
   | `:created` | (clean) | |

   Inadmissible/ambiguous candidates are counted (`extraction-noise`), not
   silently dropped.
5. **Sweep + judge** (unless `--no-judge`): `judge/sweep-conflicts!`
   generates the cross-predicate pairs the write path can't see (`prefers X`
   in one file, `depends-on Y` + `decided-against Y` across files), then
   `judge/judge-conflicts!` classifies every flagged pair — both take
   `:judge-fn` for tests and run fine on the memory store. Judged-`compatible`
   pairs are *removed* from the contradiction count: the judge pass is the
   false-positive filter that keeps the headline number honest. Never
   `--resolve` — audit fixes nothing.
6. **Score**: fold results into the scorecard (§7) — plus
   `core/entity-duplicates` for **name clusters** and the byte arithmetic
   for **injection bloat** (injected bytes vs. the 25 KB
   `context/injection-window`; flag files individually over it, and when the
   sum is over, name the dominant cause — `:managed-view` when claimgraph's
   own compiled view is the larger half and the hand-written half still fits,
   `:instructions` otherwise — with the remedy that addresses it).

## 5. Two deliberate deviations from the ambient tier

Both exist because the throwaway store never feeds durable memory — audit
wants maximal conflict surfacing, the opposite bias from ingestion:

- **Audit keeps predicate-default epistemic classes.** The notes tier
  demotes every claim to observation (attribution flattening — a note can't
  mint a commitment). Audit must NOT demote: `decided-against` claims ingest
  as commitments so stance collisions *flag* instead of silently
  superseding. The demotion rule protects the durable graph; there is no
  durable graph here.
- **Pod-free by design.** The store is `store.memory`, so audit's only hard
  prerequisites are `bb` and an extractor command — **not `dtlv`**. Someone
  can run the audit with zero installation commitment beyond babashka. The
  prerequisite check is a variant of `setup/check-prerequisites` (extractor
  required this time, dtlv not checked at all).

## 6. The extraction prompt (audit variant)

Start from `notes/extraction-prompt` and change two things:

1. **Keep the class signal.** Allow `"commitment"` in the emitted `class`
   field (the notes prompt forbids it) — "we decided against X" in a
   CLAUDE.md is exactly the claim whose collisions we want flagged.
2. **Anchor every claim.** Add a `quote` field: a short verbatim snippet
   from the source. Findings render as *"CLAUDE.md says 'deploys to Heroku';
   src says Fly"* — traceability is what makes the scorecard trustworthy
   marketing rather than LLM vibes. Implementation note:
   `session/parse-extraction` passes unknown keys through, but
   `core/assert-fact` won't store them — carry quotes in an audit-side map
   keyed by fact id (or (subject, predicate, object)) built as results come
   back; don't try to persist them in the store.

Same durability filter as the notes prompt (skip ports/TODOs/worktree
ephemera), same entity-roster prior, same "output nothing if nothing
qualifies".

## 7. Scorecard schema (sketch — settle in implementation)

```jsonc
{"status": "ok",
 "project": "/abs/path",
 "files": [{"path": "CLAUDE.md", "bytes": 18234, "claims": 41}, ...],
 "claims": 87,
 "findings": {
   "contradictions": [{"kind": "contradiction",
                       "claims": [{"file": "CLAUDE.md", "quote": "...",
                                   "subject": "api-layer",
                                   "predicate": "decided-against",
                                   "object": "GraphQL"}, ...],
                       "verdict": {"relation": "contradicts", "confidence": 0.9}}],
   "stale": [...],          // same shape; one side {"file": null, "source": "code"}
   "disagreements": [...],
   "restatements": [{"subject": ..., "predicate": ..., "object": ...,
                     "files": ["CLAUDE.md", "memory/architecture.md"], "count": 3}],
   "name-clusters": [["AuthSvc", "AuthService"]],
   "extraction-noise": {"rejected": 2, "ambiguous": 1}},
 "injection": {"injected-bytes": 41200, "managed-bytes": 8100,
               "on-demand-bytes": 15000, "window-bytes": 25000,
               "over-budget": true, "over-by": 16200, "cause": "instructions",
               "largest": [{"path": "CLAUDE.md", "bytes": 22400}],
               "remedy": "..."},
 "summary": {"contradictions": 7, "stale": 9, "disagreements": 12,
             "restatements": 23, "name-clusters": 3},
 "next": ["claim setup  # the graph tracks these instead of accumulating them"]}
```

`summary` is the marketing line; `findings` is the receipts.

## 8. Handoff: build order and integration points

New namespace `claimgraph.audit`, same functional-core/imperative-shell
split as the notes ingester. Reuse, don't rebuild:

| Need | Existing seam |
|---|---|
| throwaway store | `store.memory/create` + `core/seed!` |
| source scan defaults + echo guard | `harness/notes-path`, `harness/strip-managed-section`; scan-set list is new, pin it in one def |
| extraction plumbing | `ingest.session/parse-extraction`, `entity-roster`, `llm/complete!` + `llm/command`; prompt variant per §6 |
| admission / ambiguity | `logic/screen-candidates`, `logic/admission-ctx` |
| conflict machinery | `core/assert-fact` statuses (see §4 table), `core/ingest` episode handling |
| code ground truth | `ingest.clj-code/ingest!` |
| judge + sweep | `judge/judge-conflicts!`, `judge/sweep-conflicts!` (`:judge-fn` injectable; never pass `:resolve`) |
| name drift | `core/entity-duplicates` |
| budget arithmetic | `context/injection-window` (the whole injected surface; `context/default-budget` is the compiled view's share of it) |
| prerequisite check | pattern from `setup/check-prerequisites` (`:which` injectable); extractor hard, dtlv irrelevant |

Steps:

1. Pure core: scan-set def; finding classification (assert-result → finding,
   including the code-sourced-candidate ⇒ stale rule); scorecard fold;
   pretty rendering. All testable with no store.
2. Shell: `audit!` driving §4's pipeline with `:extractor-fn` / `:judge-fn`
   / `:which` injectable.
3. CLI: `cmd-audit` + table entry + help text. Settings flow through
   `config/with-defaults` (`:extractor`; `--project` as elsewhere). NOTE:
   audit must not open the real store — do not use `with-store`.
4. Tests (`test/claimgraph/audit_test.clj`, register in `run_tests.clj`):
   fixture pile with one planted contradiction (prefers vs decided-against
   across two files), one staleness case (claim about a namespace the
   fixture code contradicts), one restatement, one name-drift pair, one
   ephemeral line the durability filter must drop; injected extractor
   returning canned JSONL (notes_test pattern); judge-fn canned verdicts;
   assert the summary counts and that a judged-compatible pair is excluded.
   Echo-guard test: a pile whose only content is a managed section audits
   to zero claims.
5. Docs: README — short section under Quickstart with the scorecard as the
   hook ("find the contradictions in your agent's memory — before you
   install anything"); `book/chapters/cli_reference.md` row; help text.
6. Dogfood: run it on this repo and paste the scorecard into the PR.

## 9. Open questions / accepted risks

- **Extraction variance.** Counts wobble run-to-run with a live extractor.
  Accepted for a diagnostic; the receipts (quotes) keep it honest. If it
  matters later: majority-of-N like `bench llm`'s flip-rate guard. CI
  gating (`--fail-on`) deferred until variance is understood.
- **Large piles.** A 200 KB CLAUDE.md in one prompt will degrade. v1:
  extract per file (already the unit), warn above ~50 KB/file; chunking is
  v2.
- **Disagreement direction.** Ingestion order decides which claim
  "supersedes" in a disagreement pair — meaningless for truth, so audit
  reports the *pair*, never a winner. Sorted-path order keeps it
  deterministic.
- **Rules-file formats.** `.cursorrules`/`.mdc` have front-matter/globs;
  v1 treats them as plain text and accepts some noise.
- **Non-Clojure repos.** Staleness prong reports `skipped` with an honest
  note (code ingester is Clojure-only). Everything else works everywhere —
  say so in the README section, since the audience for the marketing line
  is mostly not Clojure.
- **Double-counting.** A pair can surface via write-path flag AND the
  sweep. Dedupe findings by unordered fact-id pair before scoring.
