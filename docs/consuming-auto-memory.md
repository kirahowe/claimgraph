# Consuming Auto-Memory: the Ambient Loop

*Design note, 2026-07-09. Follows from the field comparison
(`memory-systems-comparison.md` §4): claimgraph's two biggest functional gaps
are ambient capture and ambient injection, and the harnesses spent 2026
building exactly those — writing the results into unstructured, unversioned,
contradiction-blind markdown piles. The two weakness sets are complementary.
This note designs the integration: consume the harness's auto-memory rather
than compete with it.*

-----

## 1. The core move

Claude Code's auto memory (default-on since ~Feb 2026) writes to a known
location on disk: `~/.claude/projects/<project>/memory/` — a `MEMORY.md`
index plus topic files (`debugging.md`, `architecture.md`, …), updated
mid-session whenever the model judges something durable. Functionally, that
directory is a free ambient-capture daemon: it watches every session, costs
us nothing to run, and its output is **already LLM-distilled** — the model
has already made the "what in this session was worth keeping?" judgment that
`session-extract` currently performs over raw transcripts. Notes are a better
extraction substrate than transcripts: shorter, denser, pre-filtered for
durability.

So: auto-memory becomes a **fourth ingestion tier** (alongside code, session
logs, and the planned ADR ingester), and — the other half — the graph
compiles a view back *into* the file the harness auto-injects. Capture is
delegated downstream-in; injection is delegated downstream-out; claimgraph sits
in the middle as the consolidator.

```
        Claude Code session loop (theirs)
        ┌────────────────────────────────────┐
        │  session … auto-memory writes      │
        └───────────────┬────────────────────┘
                        │ MEMORY.md + topic files (distilled notes)
                        ▼
   claim ingest-notes --harness claude-code      ← delta-detect, extract,
                        │                              full conflict machinery
                        ▼
              bi-temporal graph  ──── consolidate (judge, sweep, summaries)
                        │
                        ▼
   claim compile-context  →  managed section of MEMORY.md
                        │
                        ▼
        harness injects it into every session (theirs, free)
```

## 2. The ingest half: `ingest-notes`

A new verb, mostly an adapter over existing machinery:

```
claim ingest-notes --harness claude-code [--dir <override>] [--dry-run]
```

- **Delta detection.** Hash (or mtime) per file, compared against the last
  ingestion episode for that file. Only changed files go to the extractor.
  Episode ref = file path + revision hash, so provenance answers "which note
  file, at which state, said this."
- **Extraction.** The same pluggable extractor as `session-extract`
  (`$CLAIMGRAPH_LLM_CMD`, default `claude -p`), same known-entity roster prior,
  same ambiguity backstop, with a prompt variant tuned for notes rather than
  transcripts (input is already distilled; the job is normalization into
  facts, not mining).
- **Epistemic treatment.** New source-type `agent-note`, confidence ceiling
  in the `session-log` neighborhood (0.7 or slightly below). These are agent
  inferences — second-class evidence by design. Critically, **the note tier
  cannot mint commitments**: a note doesn't record whether the user said
  something or the model inferred it, so everything ingests at
  inference-grade class/confidence. Genuine decisions still arrive via the
  direct path (`assert --class commitment`, skill-driven).
- **Full conflict machinery.** Every note-derived fact goes through
  `assert-fact`. This is the value-add, not a tax: no built-in auto-memory
  handles contradictions (Claude Code's own docs warn that contradictory
  memory files cause arbitrary behavior). The day a note says "switched to
  GraphQL for the admin panel" while `decided-against GraphQL` stands,
  claimgraph flags it instead of letting two contradictory notes coexist in
  the pile.

### Compaction-tolerance: why reconciliation must NOT apply

`ingest-code` reconciles: facts the analysis no longer produces are
invalidated, because for code, absence means the code stopped saying it.
Auto-memory has different absence semantics: Claude compacts `MEMORY.md`
near its size cap, dropping notes that are still *true* but weren't recently
useful. Absence from compacted notes ≠ falsity.

The right semantics fall out of the existing disuse-decay design with no new
mechanism: facts that survive compaction keep being re-asserted on each
ingest pass and are **reinforced**; facts compacted away simply stop being
reinforced and **fade** on the 90-day half-life. Forgetting-by-disuse is
exactly the correct model for an upstream source that forgets by *space
pressure* rather than by falsity. (This is the second time the decay design
has turned out to be load-bearing for an ingester — see TODO item 6 history.)

## 3. The write-back half: `compile-context`

Claude Code injects the first ~200 lines / 25 KB of `MEMORY.md` into every
session, unconditionally. That injection slot is programmable — it's just a
file. So:

```
claim compile-context --harness claude-code [--budget 25kb]
```

emits a compiled "what's currently true" view into a **marker-delimited
managed section** of `MEMORY.md`:

- top currently-valid facts by effective confidence (decay-aware);
- standing commitments (which never decay) — the "do not relitigate" list;
- open conflicts awaiting the human;
- **recent supersessions** — "`deployed-via` changed Heroku→Fly on
  2026-06-02" — the "what changed since you last looked" briefing that
  nothing else in the field provides (per the comparison doc, no shipped
  system surfaces belief *changes* ambiently).

`MEMORY.md` stops being a silo and becomes a **materialized view over the
graph**. This closes the read-path gap (comparison doc §4, "no
auto-injection") without building injection: the harness injects our compiled
view for free, every session, with zero harness modification.

### The echo-loop guard

Risk: Claude reads the compiled section → writes notes influenced by it → we
ingest those notes back → the graph reinforces itself through the model. Two
existing properties already contain it (reinforcement never raises base
confidence above the source ceiling, and never by repetition alone), but the
clean fix is mechanical and non-negotiable: **the marker-delimited managed
section is excluded from `ingest-notes` entirely.** Only Claude's own notes
are upstream; our view is never re-consumed. (Also gives idempotence: a
compile → ingest → compile cycle is a fixed point.)

## 4. Automation: the SessionEnd hook

Claude Code hooks make the loop ambient today, no daemon required — with one
hard-won division of labor (spec/maintenance.allium): the hook is **capture**,
and capture is deterministic. `claim hooks run` does the delta-gated code pass
and the recompile, then spawns `claim curate` detached and exits without
waiting on it.

**The session's exit waits on nothing at all.** The installed hook is
`hooks run --detach`: it opens no store, spawns that capture pass detached
into `<db>.capture.log`, and returns in the time it takes to start a process.
Capture is cheap but not free — a code reconciliation costs seconds exactly
when the session touched code, which is exactly when someone is waiting on
their next prompt — so the chain is hook → detached capture → detached
curator, each with its own log and nobody blocking. `claim hooks run` by hand
(and `--fail-on-partial` in CI) stays attached, where a report a caller can
read is the whole point.

Every model call belongs to the curator: notes extraction, conflict judging,
episode summaries, alias enrichment — one budgeted, convergent, singleton
pass whose log lands at `<db>.curate.log`. Convergent because every call
records a durable outcome (a verdict episode, an enrichment attempt, a closed
episode), so what remains to do is derived from the store and a converged
graph makes the run a free no-op. There is no consolidation stamp and no
cadence — nothing is scheduled, everything is owed-or-done.

Two lessons are baked into that shape. Model work must never sit inside a
bounded lifecycle hook: the inline predecessor hit its 600s timeout every
session, landed nothing, and re-queued everything. And the extractor command
must run hermetically (neutral cwd, no project settings): `claude -p` is
itself an agent harness, and invoked from the project it fired this very
SessionEnd hook on exit, recursively.

The skill remains the home of judgment for the direct path (commitments,
deliberate queries, curation); the hook is the zero-effort floor beneath it.

## 5. The cross-harness endgame

File-based auto-memory quietly became a near-standard in 2026: Codex writes
`~/.codex/memories/` (per-thread summaries + durable entries + **evidence
files** — even better provenance to carry into episodes), Windsurf/Devin
keeps a local memories directory, Cline's memory bank lives in-repo. Each is
a per-tool, per-machine silo, mutually ignorant of the others.

A `--harness` adapter per format makes claimgraph the **cross-harness
consolidator**: your Claude Code notes and your Codex notes about the same
repo merge into one bi-temporal graph, entity resolution aligns their
vocabularies, and the conflict machinery reconciles their disagreements.
Nothing in the field survey does cross-harness memory consolidation.

The one you can't eat: Copilot Memory is GitHub-hosted (admin export only) —
which is itself a tidy argument for the file-based interop story.

## 6. Why consuming beats replacing

1. **Ambient capture is a distribution problem, not a technology problem.**
   The harness owns the session loop; a third-party tool can never be as
   ambient as the thing hosting the conversation. Competing there is
   unwinnable; delegating there is free.
2. **We inherit the model's judgment.** Anthropic explicitly trains models to
   be good at file-based memory curation; that improving capability flows to
   us as better-distilled input rather than competing against us.
3. **Zero behavior change.** Works for a user who never learns a claimgraph
   verb — defaults on, notes accumulate, the graph builds itself.
4. **The weaknesses are perfectly complementary.** Auto-memory has capture
   but no temporality, no epistemics, no invalidation, no history; claimgraph
   has all four but no capture. There is no overlap to fight over.
5. **It's the synthesis doc's "eat their output" strategy, upgraded.** In
   2025 the output to eat was hand-authored CLAUDE.md and ADRs; in 2026 the
   harnesses built machines that *generate* output continuously.

## 7. Caveats (honest)

- **Note noise.** Auto-memory includes ephemera ("dev server runs on 3021 in
  this worktree") that is working memory, not knowledge. The extractor prompt
  needs the same durability filter `session-extract` has; disuse decay cleans
  up what slips through — but expect the `x/*` staging namespace and
  `entity duplicates` to work harder. Watch this in the benchmark.
- **Lost attribution.** Notes flatten who-said-what; hence the hard rule that
  the note tier ingests at inference grade and never mints commitments (§2).
- **Double coverage.** Ingesting both transcripts and notes from the same
  session extracts twice. Reinforcement makes this mostly harmless
  (restatement resets clocks, never inflates), but the cleaner posture is
  **notes-as-primary**, transcripts as fallback for harnesses without
  auto-memory.
- **Path plumbing.** `~/.claude/projects/` uses munged project paths; the
  adapter needs a small project-mapping shim. Auto-memory is per-machine, so
  multi-machine users converge through the graph (via the committed dump),
  not through the note files.
- **Upstream format drift.** The memory-dir layout is undocumented internal
  surface; the adapter should degrade gracefully (treat unknown files as
  plain notes) and pin its expectations in one place per harness.

## 8. Build order

1. **`ingest-notes`, Claude Code only.** Mostly an adapter: delta detection +
   a notes-tuned prompt over the existing `session-extract` machinery. New
   source-type `agent-note` with its confidence ceiling. No reconciliation.
2. **`compile-context` with the marker-guarded write-back.** Budgeted,
   deterministic (no LLM), idempotent; the ingest-side marker exclusion ships
   in the same change.
3. **The SessionEnd hook** (documented in the skill; optionally a
   `claim hooks install` convenience).
4. **Codex adapter** — the second harness proves the abstraction (and its
   evidence files exercise richer episode provenance).
5. **Benchmark extension**: a fixture auto-memory directory in `bench/`
   (notes that restate, contradict a commitment, get compacted away) so the
   compaction/decay semantics and the echo-guard are regression-gated like
   everything else.

Steps 1–2 close both gaps the comparison doc flagged — ambient capture and
ambient injection — without building either.
