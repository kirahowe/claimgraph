# TODO

Remaining roadmap, in rough priority order. Rationale for most items lives in
`docs/claimgraph-handoff.md` §7.

> **Superseded by [`ROADMAP.md`](ROADMAP.md)** (2026-07-09), which sequences
> these items together with the July 2026 research round
> (`docs/memory-systems-comparison.md`, `docs/memagent-2026-review.md`,
> `docs/consuming-auto-memory.md`). The "Next up" list below is kept for
> history; the roadmap is the current ordering.

> **Where this stands** (0.1.0-alpha, 2026-07-26). "Next up" is history in
> full now. The auto-memory loop, the failure and ADR ingesters, the predicate
> promotion command, `load` and the MCP front-end all shipped; the benchmark
> grew but has not reached the n it wants (roadmap issue 12). Vector/semantic
> search and the ACL tier are the two that did not move, on their original
> triggers — the ACL fields are still carried and still unenforced. Multi-
> language code ingestion is no longer future work: Kotlin and TypeScript/
> JavaScript analyzers ship beside the Clojure one and a project can register
> its own. Nothing below is ticked, because ticking a list the roadmap
> replaced would suggest it is still the one being worked from.
>
> One constraint this file predates: the persisted formats are stamped from
> 0.1.0-alpha — `format-version` 1 in the dump's header record, every oplog
> line, the store's `<db>.version` sibling and `:config-version` in the
> project config — so anything here that changes the shape of one of them now
> owes a version bump and a migration alongside it.

## Review feedback round (2026-06-11) — being addressed in order

- [x] 1. Traversal chattiness: BFS now hands its whole frontier to
      `-get-facts-for` — one query per direction per level, regardless of
      frontier width.
- [x] 2. Maintenance paths now read candidate sets, not the graph:
      `-select-facts` (whitelisted structural criteria, over-inclusion
      allowed) feeds `conflicts`/`decay`/`stale-facts`/consolidation;
      `-predicate-usage` aggregates store-side. Policy stayed pure and
      unmoved — logic just receives fewer facts.
- [x] 3. Conflict detection widened by candidate generation, two-tier:
      registry exclusion groups (`:stance`, `:revision`) catch cross-predicate
      stance collisions deterministically at write time (loose object
      matching, flag-by-epistemic-composition); `judge --sweep` generates
      what writes can't see — exclusive-value pairs and decision-vs-anything
      shared-object pairs — pure and per-subject bounded, judged offline,
      linked into the same pipeline. The LLM never runs on the write path.
- [x] 4. Ingestion identity discipline, both holes: ambiguous resolution
      (≥2 normalized matches) now surfaces with candidates instead of
      minting a third entity — writes throw, reads say "did you mean", and
      ingest routes such facts to the error bucket. The extraction prompt
      carries a bounded known-entity roster (top by usage, with aliases) as
      a prior, so the LLM reuses exact names instead of coining synonyms.
- [x] 5. Valid time first-class on writes (Level 1): supersede closes the
      predecessor at the successor's valid-from (abutting intervals — as-of
      between two versions returns exactly one); `--valid-until` records a
      closed past interval in one assertion; `invalidate --at` says when a
      fact stopped being true. Inverted intervals are rejected; backdated
      overlaps flag into the conflict pipeline instead of inverting. Level 2
      (transaction-time retraction history, the XTDB model) deliberately out
      of scope — `recorded-at`/`recorded-ms` stay the append-only hook if
      ever needed.
- [x] 6. Decay is disuse-based and computed, not stored: facts carry a
      `last-reinforced-at` anchor; effective confidence is a read-time view
      (90-day half-life, floor 0.05; commitments/decision-records exempt).
      Re-assertion reinforces — resets the clock and raises base toward a
      per-source ceiling (never by repetition alone; existing fact's ceiling
      governs). Every ingest-code pass now keeps code facts warm. The batch
      `decay` job is gone. Deferred behind observed need: reinforcement on
      true retrieval (batched `-touch-facts` from intent commands only —
      reads-as-writes stays out until read-hot-but-never-reasserted facts
      demonstrably decay wrongly).

## Next up

- [ ] **Auto-memory consumption loop** (design: `docs/consuming-auto-memory.md`).
      In build order: (1) `ingest-notes --harness claude-code` — delta-detect
      the harness's auto-memory dir, extract via the session-extract
      machinery with a notes-tuned prompt, new `agent-note` source-type
      (inference-grade ceiling, never mints commitments), NO reconciliation —
      compacted-away notes fade by disuse instead of invalidating;
      (2) `compile-context` — deterministic, budgeted graph view (current
      facts, standing commitments, open conflicts, recent supersessions)
      written into a marker-delimited managed section of MEMORY.md that
      ingest-notes excludes (echo-loop guard); (3) SessionEnd hook wiring;
      (4) Codex adapter (`~/.codex/memories/`, evidence-file provenance) to
      prove the cross-harness abstraction; (5) bench fixture for
      compaction/decay semantics and the echo guard.
- [ ] **Failure ingester.** When agent work is rejected or reverted, extract
      why — this is where procedural memory grows from.
- [ ] **Decision-record (ADR) ingester.** Highest-authority source; parse ADR
      files into `supersedes` / `decided-against` / `has-status` commitments.
- [ ] **Predicate promotion command.** `x/*` → `core/*`: register the stable
      twin, rewrite facts, deprecate the staging term with `:replaced-by`
      (never delete). Usage counts already exist (`predicates --usage`).
- [ ] **`load` command.** Restore a store from `dump` JSONL — completes the
      portability loop (dump is currently one-way).
- [ ] **MCP front-end.** Thin second front-end over `claimgraph.core` when
      cold-start per query hurts or per-turn call counts grow.
- [ ] **Vector/semantic search.** Datalevin has SIMD vector search; defer
      until FTS + graph retrieval proves insufficient.
- [ ] **ACL tier.** `read-acl`/`write-acl` are carried in the schema but
      unenforced; activate when multi-user.
- [ ] **Multi-language code ingesters.** `ingest-code` is Clojure-only
      (edamame ns-form parsing); tree-sitter would generalize it.
- [ ] **Grow the benchmark.** v0 exists (`bb bench`, see README): the shoply
      fixture, 15 mechanics questions, and the LLM quality layer. Next:
      run `bb bench llm` against a real model and use the numbers to tune
      the knobs (session cap, judge gate, half-life); add judge labels
      beyond contradicts; consider generalizing to a multi-system harness
      only after it proves out here.

## Done (benchmark)

- [x] Codebase-memory benchmark v0: the shoply fixture timeline (three
      sessions, three code passes, curation, a migration, standing decisions
      violated and relitigated), 15 mechanics questions across seven
      capabilities scored deterministically with recorded LLM outputs
      (`bb bench`, perfect score enforced in the test suite), plus the LLM
      quality layer (`bb bench llm`): extraction precision/recall, judge
      verdict accuracy, fragmentation suspects.

## Decided against

- **dump-to-SQLite migration path** (2026-06-10). JSONL dump suffices for
  portability; a SQLite backend remains possible behind the `Store` protocol
  if ever actually needed.

## Done

- [x] v0: schema, Store protocol, two backends, conflict machinery, predicate
      registry, bi-temporal reads, BFS, FTS, episodes, JSONL ingest, Clojure
      code ingester, decay, dump, CLI, skill.
- [x] Functional-core/imperative-shell refactor: pure decision logic in
      `claimgraph.logic`, effects concentrated in `claimgraph.core` + store impls.
- [x] Session-log extractor (`session-extract`): pluggable LLM extractor over
      transcripts (plain text or Claude Code session JSONL), dry-run mode,
      confidence capped at 0.7, source-type `:session-log`.
- [x] Mechanical invalidation on git events: every `ingest-code` pass
      reconciles the store against the new analysis and invalidates
      code-sourced facts it no longer produces (deleted files, removed
      requires, dropped namespaces). Non-lossy; history retains them.
- [x] LLM judge on the semantic-conflict path (`conflicts` + `judge`):
      classifies open conflict pairs as contradicts / duplicate / supersedes /
      compatible. Report-only by default; `--resolve` acts above a confidence
      gate and never auto-resolves contradictions. Shared LLM machinery in
      `claimgraph.llm` (`$CLAIMGRAPH_LLM_CMD`).
- [x] Consolidation (`consolidate`): one offline pass that LLM-summarizes and
      closes open episodes (with a mechanical fallback; summaries become
      full-text searchable), judges open conflicts, decays stale confidence,
      and reports `x/*` promotion candidates. Pattern promotion to procedural
      memory remains future work alongside the promotion command.
- [x] Entity resolution: layered lookup (exact → alias → unique normalized
      match, type-guarded; ambiguity never guesses), write-path self-healing
      aliases, and curation verbs — `entity rename` / `alias` / `merge`
      (repoint + collapse duplicates) / `split` (derived-from lineage) /
      `duplicates` (cluster report).
