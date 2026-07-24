# Advanced usage

The executable chapters covered the core loop. This chapter is the
operator's tour of everything else: the ingestion tiers beyond notes, the
offline passes, the second front-ends, and the judgment layer that tells an
agent when to use any of it.

## The skill: judgment lives outside the store

`.claude/skills/claimgraph/SKILL.md` is the usage policy an agent loads: when
to read (before modifying an entity, before proposing architecture, whenever
asked "why" or "since when"), when to write (a stated preference
immediately, a decision as a commitment with `--source-type
decision-record`), how to phrase facts (entities as stable names, free text
in literal objects), and what to do with a `flagged` response (never pick a
winner; show the human the candidates). The store enforces what it can;
the skill carries what only judgment can. StructMemEval's finding that
agents organize memory well only when told how is the reason this file
exists and travels with the repo.

## Ingestion beyond the ambient loop

**`ingest-code`** is the mechanical tier: a registry of language analyzers
(no LLM anywhere) emits `defined-in`, `depends-on`, and `written-in` facts
at 0.95 confidence under a `:code` episode referenced to
`<git-sha>[+<dirty-digest>]`. Clojure is parsed internally with edamame,
Kotlin with an internal line parse, TypeScript/JavaScript by shelling to a
version-pinned dependency-cruiser (missing npx skips that analyzer with a
hint), and a `code-analyzers` map in the project config overrides, disables,
or adds analyzers — every analyzer emits the same per-unit interchange
format and feeds the same driver (the extending chapter walks through
adding a language end to end, config-seam first, upstream after). Each
pass reconciles: facts the analysis
no longer produces are invalidated mechanically, unchanged facts reinforce,
and a unit that moved files supersedes its old location. Absence in code
means the code stopped saying it, which is the one place absence does imply
falsity — guarded per language, so an analyzer that didn't run never
invalidates what it didn't look at. The same pass runs ambiently as the
first stage of `hooks run`, delta-gated on the episode ref, so the graph
tracks the code (including teammates' pulled changes) with nobody in the
loop.

**`session-extract`** mines full transcripts (plain text or Claude Code
session JSONL). The extractor is a pluggable subprocess (`--extractor`,
`$CLAIMGRAPH_LLM_CMD`, default `claude -p`), and its prompt carries a bounded
roster of known entities with aliases so it aligns synonyms instead of
coining `AuthSvc` next to `AuthService`. Output is capped at 0.7 and typed
`session-log`; `--dry-run` shows what would land before anything does.

**`ingest-adr`** parses MADR-style decision records mechanically: the title
becomes the decision entity, `Status:` becomes a `has-status` fact whose
changes supersede (so status history accumulates), supersession links become
revision edges, and rejected options become `decided-against` commitments,
all at decision-record authority.

**`ingest-failure`** turns rejected or reverted work into procedural
memory, following the workshop literature's guidance to extract the lesson
rather than the diff: hazards land as `failure-mode` facts, corrective
practices as `prefers`, human rulings as `decided-against`. The episode's
`failure-report` source type doubles as the valence signal for the outcome
loop, and the raw material is kept as evidence.

## The offline passes

**`consolidate`** is the Dreaming-style pass, run weekly by the hook or by
hand: it summarizes and closes open episodes (summaries become full-text
searchable, which is what makes "why did we do X" a query), judges open
conflicts, sweeps for conflict candidates the write path cannot see, emits
SIRA-style alias enrichment so future FTS queries land better, and reports
`x/*` predicates earning promotion. When no LLM is available it falls back
to a mechanical digest, so the pass always makes progress.

**`judge`** classifies flagged pairs as `contradicts`, `duplicate`,
`supersedes`, or `compatible`. It reports by default; `--resolve` acts only
on verdicts at or above `--min-confidence` (0.8), and a `contradicts`
verdict is never auto-resolved regardless of confidence. `judge --sweep`
generates candidates writes cannot see (two values on an exclusive
predicate, a dependency on something a standing decision rejected), with
generation pure and bounded per subject, never O(graph squared). The
benchmark measures judge stability by running each labeled pair k times and
reporting flip rate next to accuracy, because a pair that flips is a pair
`--resolve` must not act on.

## The pre-install audit

**`audit`** points the judge-and-sweep machinery just described (plus the
write path's flag/supersede/reinforce vocabulary) at a repo's existing
memory pile, before claimgraph is installed: throwaway in-memory store,
nothing written, no `dtlv` — `bb` and an extractor are the whole
prerequisite list. Code facts ingest first so pile claims colliding with
the code read as staleness, epistemic classes keep their predicate defaults
so reported decisions flag instead of silently superseding, every finding
carries verbatim quote receipts, and judged-compatible pairs are removed
from the contradiction count. It never resolves anything, and exit code is
0 even with findings: it is a diagnostic, not a gate. The audit chapter
runs the whole pipeline executably; `--no-judge` and `--no-code` degrade it
honestly when the LLM or the code analyzers are unavailable.

## The outcome signal

Read verbs log which facts they surface into `<db>.retrievals`. After the
work ships or dies:

```bash
bin/claim outcome accepted    # everything retrieved since the last mark
                                 # gets its disuse clock reset
bin/claim outcome rejected    # nothing reinforced; the facts in play are
                                 # reported, and the lesson goes to ingest-failure
```

Acceptance is evidence of aliveness, so it resets clocks and never raises
confidence. This closes the gap where a fact read constantly but never
restated would fade identically to one nobody needs.

## The MCP front-end

`bin/claim mcp` serves the graph over stdio JSON-RPC. The store and the
Datalevin pod open once per session instead of paying the cold start per
CLI call, which matters once the coach hook runs on every prompt. Seven
tools: `memory_facts`, `memory_search`, `memory_recall`, `memory_history`,
`memory_conflicts`, `memory_coach`, and `memory_assert` (lease-guarded,
full conflict machinery). Wire it with:

```bash
claude mcp add claimgraph -- bin/claim mcp
```

The protocol handler is a pure function from request to response, so the
whole server is tested without a process. The CLI and skill remain the
primary surface; MCP is the low-latency second door.

## The vocabulary as a living thing

New relations start in the staging namespace: asserting `--predicate
x/uses-pattern` auto-registers it with `:testing` status. `predicates
--usage` shows what is earning its place, and promotion graduates a term:

```bash
bin/claim predicate promote --from x/uses-pattern --to core/uses-pattern
```

Promotion registers the stable twin, rewrites every fact onto it (a term
rename; history untouched), and deprecates the staging id with a forwarding
pointer, so further writes to the old name fail with the new address.

## Portability

`dump` exports the whole graph as JSONL; the live LMDB directory stays
gitignored and the dump is the committable artifact. `load` restores a
fresh store exactly (ids, intervals, invalidation reasons, conflict links),
deliberately without re-running the conflict machinery, and refuses a
non-empty target. Evidence artifacts stay local by design: the graph is
the portable thing, evidence is the audit trail.

## Scale, measured

The benchmark's scale tier (100x the fixture: about 2,300 facts) puts
numbers where the design talk is: rule-based QA stays 20 of 20, point reads
run about 10 ms and searches about 22 ms through the pod, and a real
consolidate pass costs 51 prompts (about 208 KB) plus roughly a second of
mechanical work. The visible ceiling is write throughput, about 21 ms per
fact through the pod's read-decide-write cycle; bulk ingestion at the
10,000-fact scale wants a batched assert path before anything else does,
and that item is recorded in the roadmap's findings rather than built
speculatively.
