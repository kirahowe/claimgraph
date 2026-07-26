# Roadmap

The build plan coming out of the July 2026 research round, as an ordered list
of concrete issues. Sources: the field comparison
(`docs/memory-systems-comparison.md`), the MemAgents/ICLR 2026 review
(`docs/memagent-2026-review.md`), the ambient-loop design note
(`docs/consuming-auto-memory.md`), and the standing `TODO.md`. This document
says *what* to build and *in what order*; the how lives in those docs and gets
worked out per issue.

## The ordering logic

The research settled three things that dictate the sequence:

1. **The core architecture is validated; the gaps are functional, not
   structural** (review §2, §5). Nothing here requires rearchitecting — every
   item is additive behind existing seams.
2. **The two biggest functional gaps — ambient capture and ambient injection —
   can both be closed by consuming the harness's auto-memory instead of
   building either** (comparison §4, consuming-auto-memory throughout). That
   is the cheapest high-leverage work available and it makes the system
   valuable to a user who never learns a claimgraph verb. It goes first.
3. **The eval story is the differentiator, and the bar is *net task
   improvement*, not retrieval metrics** (review §4). The AGENTS.md result
   killed "context helps" as an assumption; the claim has to be demonstrated.
   Proving comes immediately after the loop closes, before further
   capability investment — the benchmark tells us where the remaining phases
   actually pay.

After that, capability work in the order the literature argues for it
(review §3, §5): procedural memory and evidence depth (the field's biggest
measured wins), then retrieval (the 20-point axis), then hardening and scale.

Standing constraints that shape every item: no LLM on the write path, no
ambient injection built by us (compiled views into the harness's slot only),
invalidate-don't-delete, and no LoCoMo/LongMemEval chasing (review §4.3). One
more since 0.1.0-alpha: the persisted formats are stamped — `format-version`
1 rides the dump's header record, every oplog line, the store's `<db>.version`
sibling and `:config-version` in the project config — so an item that changes
the *shape* of any of them owes a bump and a migration. Additive changes an
old reader can ignore are free and deliberately do not move the integer; the
rest are not, and bumping without writing the migration spends the promise the
stamp exists to make.

-----

## Phase 1 — Close the ambient loop

Goal: zero-effort capture in, zero-effort injection out, with claimgraph as the
consolidator in the middle. Build order and design are already specified in
`docs/consuming-auto-memory.md` §8.

### 1. `ingest-notes` — Claude Code auto-memory as the fourth ingestion tier ✅ *(2026-07-10)*
Adapter over the existing `session-extract` machinery: delta-detect changed
note files, extract with a notes-tuned prompt, ingest under new source-type
`agent-note` with an inference-grade confidence ceiling. The note tier can
never mint commitments; no reconciliation (compaction-absence ≠ falsity —
disuse decay already models it). Every note-derived fact goes through the
full conflict machinery — that's the value-add over the pile.
*(consuming-auto-memory §2; comparison §4 "behind the field" #1)*

### 2. `compile-context` — the graph writes the injected view back ✅ *(2026-07-10)*
Deterministic, budgeted compilation of "what's currently true" into a
marker-delimited managed section of `MEMORY.md`: top valid facts, standing
commitments, open conflicts, recent supersessions. The harness injects it for
free every session. Ships in the same change as the echo-loop guard: the
managed section is excluded from `ingest-notes`, making compile → ingest →
compile a fixed point. *(consuming-auto-memory §3; closes comparison §4 gap #2)*

### 3. SessionEnd hook — make the loop ambient ✅ *(2026-07-10)*
Document the hook in the skill and add a `claim hooks install`
convenience: every session ends with `ingest-notes && compile-context`;
`consolidate` runs at lower frequency. *(consuming-auto-memory §4)*

### 4. `load` command — complete the portability loop ✅ *(2026-07-10)*
Restore a store from the `dump` JSONL. Already on TODO; promoted into this
phase because multi-machine users of the ambient loop converge through the
committed dump, not through per-machine note files — which requires dump to
be two-way. *(TODO; consuming-auto-memory §7 "path plumbing")*
*Update (0.1.0-alpha): two-way, but not yet safe — an empty or truncated file
loaded as `:status :loaded` with exit 0, and a dump written before the fix
restored every entity untyped and reported success, because the record kind
was stamped onto `:type`, the key an entity's own type already occupied. A
dump now leads with a format header, the kind lives under `:record`, and
`load` refuses before its first write anything it cannot read in full — naming
which of the eight causes it hit, since they want different remedies.*

-----

## Phase 2 — Prove it: the benchmark as the differentiator

Goal: measure where the field is weakest and claimgraph is strongest
(staleness, conflicts, abstention, provenance), then run the headline
experiment. Plan and rationale: review §4.3; items below keep its numbering
where relevant. The fixture tiers are cheap, extend the existing `bb bench`
harness, and build toward the A/B.

### 5. Notes-loop bench fixture ✅ *(2026-07-10)*
A fixture auto-memory directory in `bench/`: notes that restate facts,
contradict a standing commitment, and get compacted away — regression-gating
the compaction/decay semantics and the echo guard from Phase 1.
*(consuming-auto-memory §8.5)*

### 6. Staleness / implicit-conflict tier ✅ *(2026-07-10)*
Cases where the code contradicts a session-derived fact without anyone saying
so; score whether reads surface current truth, the sweep finds the conflict,
and decay buries the stale fact. The field measures ~55% on this axis; our
machinery is built for it. *(review §4.3.2, STALE)*

### 7. Abstention tier ✅ *(2026-07-10 — retrieval layer)*
Questions whose correct answer is "the graph doesn't know" — score refusal vs
confabulation at both the retrieval layer and the skill layer.
*(review §4.3.3)*
*Shipped: the retrieval-layer mechanics (near-miss refusal without minting,
empty-not-garbage on unknown aspects, as-of before knowledge, empty search).
The skill layer ("does the agent say so") needs the agent harness — it lands
with issue 12's A/B arms.*

### 8. Poisoning red-team case ✅ *(2026-07-10)*
One fixture session with a planted instruction-shaped "preference" and a
plausible-but-false fact; score whether admission/confidence/decay/conflict
machinery contains the blast radius. MINJA-style, miniaturized. Findings feed
issue 23. *(review §4.3.4, §3.6)*
*Findings for issue 23: contained — the 0.7 cap, decay differential (poison
fades at +180d, commitments stand), commitment attacks flag instead of
override, one quarantinable episode. Leaks — (1) a false fact on a `:many`
predicate coexists with the truth (q28 gates it as a documented leak; a
novelty/outlier check on writes that contradict recently-invalidated facts
would catch it); (2) a fresh 0.7 poison ranks into `compile-context`'s
current-facts section until it decays — the injection surface is downstream
of admission.*
*Update: both leaks closed by issue 23 — the revenant check flags the
resurrected value against its live rival, and disputed facts never enter the
compiled current-truth section.*

### 9. Shift-recovery case ✅ *(2026-07-10)*
The fixture already contains a rename and a migration; measure Recovery@T —
how many reads/writes until old names resolve and stale facts fade.
*(review §4.3.5, ShiftBench)*

### 10. Judge stability ✅ *(2026-07-10)*
Run each labeled conflict pair k times; report flip rate alongside accuracy
and let stability inform the 0.8 resolution gate. Cheap. *(review §3.9, §4.3.8)*

### 11. Metric hygiene ✅ *(2026-07-10)*
Report latency per read alongside accuracy (pod cold-start is the honest weak
spot — this is also the trigger data for issue 27), and add a contamination
control: fixture entities with swapped names so correct answers must come
from the graph, not parametric knowledge. *(review §4.3.9, DialSim)*

### 12. The headline four-arm end-task A/B ◐ *(2026-07-10 — harness + pilot)*
Same tasks, same agent, four arms: no memory · static context file ·
auto-memory (the actual incumbent) · claimgraph. Measure task success, tokens,
wall-clock, and re-litigation counts. Beating "no memory" is table stakes;
beating auto-memory is the product claim. Even n=20 task pairs says more than
any retrieval metric. Depends on Phase 1 (the claimgraph arm *is* the ambient
loop). *(review §4.3.1; protocol from the AGENTS.md oral + SWE-ContextBench)*
*Shipped: `bb bench ab` (7 memory-dependent tasks, JSON-scored, incl. the
skill-layer abstention probe from #7). Pilot (n=7, one run per arm, claude
-p): claimgraph **0.71** · none 0.43 · static 0.43 (1 confabulation off the
stale file) · auto-memory **0.29** (below no-memory — the compacted pile plus
the planted note actively mislead; the AGENTS.md result reproduced).
claimgraph's two misses: hosting = the poisoning leak reaching an end task
(confidently answered Heroku off the planted fact — issue 23's cost, now
measured), react-lang = honest abstention. Open: grow to n≈20 task pairs and
multiple runs for CIs; add a queryable-CLI arm variant (pull-side judgment,
not just the compiled view).*
*Rerun after issue 23 landed: claimgraph 0.86. The hosting miss became an
honest abstention ({"provider": "unknown"}) — the revenant flag plus the
disputed-fact exclusion from compile-context mean the planted value no
longer reaches the agent as current truth. The poisoning attack now buys
uncertainty, not a wrong confident answer.*

### 13. Retrieval-vs-structure ablation ✅ *(2026-07-10)*
claimgraph full vs raw chunks + BM25/embeddings vs claimgraph with degraded
retrieval. Publish where structure pays (history, time-travel, conflicts) and
where it doesn't (plain recall) — the negative half is what makes the
positive half credible. Also the baseline data for Phase 4. *(review §4.3.7)*

-----

## Phase 3 — Evidence depth and procedural memory

Goal: the two ingestion gaps the literature argues for hardest — keep raw
evidence under the extractions, and start turning failures and decisions into
procedural knowledge (the largest cluster of measured wins at the workshop).

### 14. Raw-evidence tier under episodes ✅ *(2026-07-10)*
Keep raw transcripts as immutable content-addressed artifacts with provenance
pointers from episodes, upgrading provenance from "which session" to "which
utterance" and breaking the write-before-query barrier. Prerequisite for
sufficiency escalation (issue 20) and safe admission control (issue 22).
Notes-as-primary, transcripts-as-fallback posture per the double-coverage
caveat. *(review §3.1 — its top-priority gap; consuming-auto-memory §7)*

### 15. Failure ingester ✅ *(2026-07-10)*
When agent work is rejected or reverted, extract *why* — the top standing
TODO item. The literature supplies the design guardrails already captured in
the review: extract the lesson, not the diff; preferentially capture lessons
about mutating actions. Supplies the valence half of issue 24.
*(TODO #1; review §3.4)*

### 16. Decision-record (ADR) ingester ✅ *(2026-07-10)*
Highest-authority source; parse ADR files into `supersedes` /
`decided-against` / `has-status` commitments. spec-kit's
constitution/spec output is now the largest authoring pipeline in the field —
this is the "eat their output" wedge. *(TODO #2; comparison §6.7)*

### 17. Predicate promotion command ✅ *(2026-07-10)*
`x/*` → `core/*`: register the stable twin, rewrite facts, deprecate the
staging term. Promoted from the TODO backlog because note-noise from
`ingest-notes` makes the staging namespace work harder. *(TODO #3;
consuming-auto-memory §7 "note noise")*

-----

## Phase 4 — Retrieval: the 20-point axis

Goal: the evidence says retrieval buys more points than write-path
sophistication; ours is the thinnest layer. Ordered by increasing ambition
(review §3.2). Issue 13's ablation numbers say how far down this list to go.

### 18. Hybrid retrieval ✅ *(2026-07-10 — FTS+entity+graph fusion; vectors still deferred)*
Fuse FTS + entity/graph + vector scoring (Datalevin already has SIMD vector
search — TODO's deferred item, now with evidence behind it). *(review §3.2;
TODO)*

### 19. Write-time enrichment ✅ *(2026-07-10)*
Consolidation emits search phrases / alt-labels so FTS behaves closer to
semantic search without embeddings. SIRA-style, training-free. *(review §3.2)*

### 20. Sufficiency escalation and evidence-guided walks ✅ *(2026-07-10)*
Escalate graph facts → episode summaries → raw pages when the graph can't
support an answer (needs issue 14), and replace fixed-depth BFS with an
evidence-guided walk over the same graph. *(review §3.2, TierMem/MRAgent)*

### 21. Gated push — the coach pattern ✅ *(2026-07-10)*
The push-side complement to the skill's pull-side judgment: a hook (task
start, about-to-mutate) that decides *whether* the graph has something worth
interrupting with — standing decisions being the obvious trigger.
*(review §3.2 WebCoach, §3.4 SABER)*

-----

## Phase 5 — Hardening, trust, and scale

Goal: close the remaining literature-flagged gaps once the loop is proven and
retrieval carries its weight.

### 22. Admission scoring on the ingest path ✅ *(2026-07-10)*
Compose the ingredients we already have (novelty/duplicate detection,
epistemic-class prior, source typing) into an explicit rule-based
admit/reject score with at most one optional LLM utility signal. Requires the
raw tier (gate the graph, keep the log). *(review §3.3, A-MAC/SAGE)*

### 23. Source trust model and poisoning defenses ✅ *(2026-07-10)*
Trust levels on sources and a novelty/outlier check on writes, driven by what
the red-team case (issue 8) shows leaks through the existing incidental
mitigations. *(review §3.6)*

### 24. Outcome signal into the store ✅ *(2026-07-10)*
Reinforcement on true retrieval (the deferred TODO half) plus session valence
from the failure ingester (issue 15): facts retrieved in accepted work are
evidence; facts retrieved in reverted work are too. Keeps decay from being
blind to usefulness. *(review §3.5)*

### 25. Multi-writer semantics ✅ *(2026-07-10 — lease, then v2: append-only logs + reconcile)*
Coding agents are increasingly multi-agent; two subagents asserting about the
same entity currently hit last-write-wins at the LMDB level. Even a
lease/lock or append-log-and-reconcile design closes the obvious hole.
*(review §3.7)*
*v2, on the local-first model: every mutation appends to this writer's own
log in `<db>.oplog/<writer>.jsonl` (hybrid logical clock; the store is a
materialized view). Append-only per-writer files sync through git/rsync with
no transport conflicts; `claim reconcile` applies foreign effects in
canonical order, unifies entities by name, collapses independent duplicate
claims non-lossily, and queues cross-writer contradictions for the judge.
Deliberately not a CRDT: divergence surfaces as open conflicts instead of
being merged away. The lease remains for same-machine concurrency.*
*Update (0.1.0-alpha): reconcile advanced a writer's high-water mark by
folding over every unapplied effect regardless of outcome, so an effect verb
the reader did not understand — an older binary against a newer peer — was
skipped permanently; only `:applied` and `:duplicate` move the mark now, and
everything else is retried. Lines carry the writer id and the format stamp, so
a log survives being renamed or restored under another name and a machine
recognises its own effects inside a foreign file. The lease heartbeats instead
of expiring under its own longest operation — the 30s TTL against a
consolidate pass's ~51 LLM calls was exactly how two writers both entered the
read-decide-write cycle it exists to serialize.*

### 26. Scale tier for the benchmark ✅ *(2026-07-10)*
Synthetic history at 10×/100× fixture size (AMA-Bench-style generation,
rule-based QA); report read latency, sweep cost, accuracy, and maintenance
cost per consolidate pass — the metric the field omits. *(review §3.8, §4.3.6)*
*Findings at 100× (2.3k facts, Datalevin): rule-QA 20/20, point reads ~10ms,
search ~22ms, consolidate ~1.1s mechanical + 51 prompts / ~208KB per real
pass. The visible cost is write throughput (~21ms/write through the pod's
read-decide-write cycle) — batch ingestion at 10k+ facts wants a batched
assert path before anything else does.*

### 27. MCP front-end ✅ *(2026-07-10)*
Thin second front-end over `claimgraph.core`. Stays trigger-gated as designed
(handoff §3.4): build when issue 11's latency numbers show cold-start pain or
per-turn call counts grow — the ambient loop's hook cadence may be what
finally trips it. *(TODO; comparison §4)*
*Update (0.1.0-alpha): thinner than the CLI in ways a wired client would have
frozen in place — no neighbor tool, so graph expansion (the capability that
most distinguishes this from a notes file) was CLI-only, and `memory_assert`
could not express valid time at all in a project whose whole point is
bi-temporality. Eight tools now, each `memory_` plus its CLI verb, with the
argument names and error channels settled before a client exists to break.*

### 28. Codex notes adapter ✅ *(2026-07-10)*
The second harness proves the `--harness` abstraction and its evidence files
exercise richer episode provenance; the beginning of the cross-harness
consolidator story nothing else in the field has. *(consuming-auto-memory
§5, §8.4)*

-----

## Where this stands (0.1.0-alpha, 2026-07-26)

Every numbered issue above is ticked except **12**, which is half done: the
four-arm harness and a pilot exist, and growing to n≈20 task pairs with
multiple runs for CIs does not. **18** shipped its fusion without vectors, so
standalone vector search is still where the deferred list puts it. One deferred
item moved on its own: `ingest-code` stopped being Clojure-only when the
language-adapter registry landed (2026-07-24), which is not the tree-sitter
generalization that entry imagined. The rest stand on their original triggers.

Between that and this line sits a hardening pass — nineteen commits, no new
roadmap item — that went back over work already ticked and found that several
of these issues had shipped their mechanism without shipping its refusals. The
portability loop accepted files it could not read and reported success. The
effect log skipped effects it could not apply, leaving no trace but arithmetic
that no longer added up. The second front-end was thinner than the first. The
lease expired under the longest operation that takes it. Each is recorded
against its issue above; the pattern is worth naming here, because a ✅ on this
list has meant "the mechanism exists" and now has to mean "and it says so when
it cannot".

The version numbers date from that pass: `release` 0.1.0-alpha and
`format-version` 1, which is where stamping starts rather than a bump. The
alpha's formats are stamped but not frozen — the migration promise begins at
1.0 — so a shape change before then is still cheap, and every one after is the
constraint stated at the top of this document.

## Explicitly deferred (triggers unchanged except where noted)

- **ACL tier** — fields carried, unenforced; activate when multi-user. *(TODO)*
- **Multi-language code ingesters** — no longer deferred ✅ *(2026-07-24)*: a
  per-language adapter registry, not the tree-sitter generalization this entry
  assumed it would take. Clojure, Kotlin and TypeScript/JavaScript ship; a
  project adds its own under `code-analyzers` in `.claimgraph/config.json`.
  *(TODO)*
- **Multi-system benchmark harness** — only after the benchmark proves out
  here; a neutral codebase-memory benchmark is possibly the highest-leverage
  artifact in the field, but sequencing stands. *(TODO; comparison §5)*
- **Standalone vector search** — subsumed into issue 18.
- **Learned components anywhere** (RL memory managers, learned eviction) —
  against the inspectability principle; non-learned equivalents preferred
  (issues 22, 24). *(review §3.5)*

## What we are deliberately not doing

- Competing with harnesses on ambient capture (distribution problem, not
  technology problem — consume it instead).
- Building our own always-on injection; compiled views into the harness's
  existing slot only.
- Chasing conversational-recall leaderboards (saturated, wrong domain,
  competitors' turf).
- LLM adjudication on the write path, ever (the freshness result: +10.8pp for
  deterministic versioning).
