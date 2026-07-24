# The mental model

Everything in claimgraph follows from a handful of commitments. This chapter
states them once, in order, so the executable chapters can move fast.

## A fact is a claim with a biography

The unit of storage is not a triple but a reified edge: a first-class record
that says *subject, predicate, object* and then keeps talking.

```clojure
{:id          "f-1a2b..."
 :subject     {:name "AuthService" :type :service}
 :predicate   :core/prefers
 :object-kind :literal            ; or :entity, and then :object-ref
 :object-lit  "argon2"
 :t-valid     #inst "2026-03-01"  ; when this became true of the world
 :t-invalid   nil                 ; nil means: still true
 :recorded-at #inst "2026-03-04"  ; when the store learned it
 :epistemic   :preference         ; observation | commitment | preference
 :confidence  0.8
 :source-type :user-assertion     ; code | user-assertion | decision-record |
                                  ; session-log | failure-report | agent-note |
                                  ; inferred
 :episode     "ep-…"              ; provenance anchor
 :scope       "project"
 :conflicts   []}                 ; links to rival facts, when flagged
```

The metadata bundle is the entire point. It is what a line of markdown
cannot carry, and each field drives real behavior: the clocks drive time
travel, the epistemic class drives conflict policy, the source type drives
trust and confidence ceilings, the episode drives provenance and audit.

## Two clocks, and nothing is ever deleted

Valid time (`t-valid` to `t-invalid`) is when a fact was true of the world.
Transaction time (`recorded-at`) is when the store learned it. Temporal
databases have kept these apart since the 1990s
([Snodgrass](https://www2.cs.arizona.edu/~rts/tdbbook.pdf)); [Datomic](https://www.datomic.com/),
[XTDB](https://xtdb.com/), and [Graphiti](https://github.com/getzep/graphiti) carried the pattern into modern stores, and claimgraph
models it as explicit attributes so the logic is identical across storage
backends.

The consequence with the most reach: **contradiction closes an interval, it
does not erase a record.** When `has-version 2.0.0` supersedes
`has-version 1.0.0`, the old fact's `t-invalid` is set to the new fact's
`t-valid` and both remain queryable forever. Three query verbs fall out for
free:

- *current truth*: facts whose interval is open now,
- *as-of*: facts whose interval contains an arbitrary timestamp,
- *history*: every version of (subject, predicate), in order, with the
  reasons intervals closed.

"What did we believe in March, and why did it change" is a query, not an
archaeology project.

## Three kinds of claims, three revision behaviors

Every fact carries an epistemic class, and the class decides what happens
when a new fact contradicts a standing one:

| Class | What it is | On contradiction |
|---|---|---|
| `observation` | something derived or verifiable ("imports X") | supersede: close the old interval, keep it in history |
| `preference` | a stylistic or tooling stance ("prefers small PRs") | supersede, history retained |
| `commitment` | a human decision ("we decided against GraphQL") | **flag**: both facts stay valid, the conflict surfaces for a human |

Observations should update themselves when the world changes; that is what
being an observation means. A commitment is different in kind: it is a
decision someone made, often for reasons the code cannot show, and no
quantity of new evidence should silently overwrite it. Flagged conflicts
stay open until a human (or an offline judge, for the easy classes) rules.
This is the practical shape of belief-revision theory's insistence that
entrenched beliefs need more than a newer timestamp to displace
([AGM 1985](https://doi.org/10.2307/2274239)).

The predicate vocabulary encodes the defaults: 23 curated `core/*`
predicates, each carrying its object kind, cardinality, default epistemic
class, and an anchor into established vocabularies ([PROV-O](https://www.w3.org/TR/prov-o/), [SPDX](https://spdx.dev/), [DOAP](https://github.com/ewilderj/doap),
[Dublin Core](https://www.dublincore.org/specifications/dublin-core/dcmi-terms/)). Unknown predicates are rejected with a did-you-mean
suggestion; genuinely new relations go to an `x/*` staging namespace and
earn promotion by use.

## Sources have rank, and confidence has ceilings

Where a fact came from bounds how much weight it can ever carry:

| Source | Trust rank | Confidence ceiling |
|---|---|---|
| `decision-record`, `user-assertion`, `code` | 3 | 1.0 / 0.9 / 0.95 |
| `session-log`, `failure-report` | 2 | 0.7 |
| `agent-note`, `inferred` | 1 | 0.65 / 0.6 |

Re-asserting an existing fact reinforces it: the disuse clock resets and
base confidence rises toward the source's ceiling, never above it, and never
by repetition alone. A fact the code ingester re-derives five hundred times
stays distinguishable from a human decision.

Rank powers two write-path defenses that exist because memory poisoning is a
demonstrated attack ([MINJA](https://arxiv.org/abs/2503.03704)), not a
hypothetical:

- **Outranked writes cannot supersede.** A session note cannot silently
  replace what a decision record established; it flags instead.
- **Revenant detection.** When a low-trust source re-asserts a value that
  was previously invalidated and a live rival exists, the write is flagged
  against the rival rather than admitted as current truth. The resurrected
  claim gets a hearing, not a throne.

## Episodes below, evidence below that

Every ingestion happens under an episode: a provenance record naming the
source type and a stable reference (a git SHA, a session id, a note file at
a content hash). Facts point at episodes; "which session said this, in what
state" is always answerable.

Below episodes sits the raw-evidence tier. Extraction decides what to keep
before any future query exists (the write-before-query barrier,
[TierMem](https://arxiv.org/abs/2602.17913)), so the extractors keep their
raw input as immutable, content-addressed artifacts in `<db>.evidence/`.
Retrieval can escalate: graph facts first, episode summaries when the graph
is silent, raw evidence lines when even the summaries are. Nothing an
extractor drops is unrecoverable.

## Forgetting is a view, not a job

Facts fade by disuse, and the fading is computed at read time. Each fact
stores a base confidence and a last-reinforced timestamp; reads report an
*effective* confidence, the base halved per 90 days since reinforcement.
No batch job rewrites anything, `--as-of` queries see period-appropriate
decay, and commitments and decision records never fade at all.

The design leans on an old result: memory retention tracks the statistics
of use, and the odds an item is needed again decay smoothly with disuse
([Anderson and Schooler, 1991](https://doi.org/10.1111/j.1467-9280.1991.tb00174.x)).
The important distinction is between disuse and falsity. A fact the code
contradicts gets invalidated, mechanically, at ingest time. A fact nobody
has restated in six months just fades in rank. The harness compacting its
notes under space pressure stops reinforcing a fact; it does not make the
fact false, and the machinery treats those differently.

Usefulness also counts. Read verbs log which facts they surfaced, and
`claim outcome accepted` resets the disuse clocks of everything retrieved
since the last mark. Retrieval in work that was accepted is evidence of
aliveness. It never raises confidence; only sources do that.

## The determinism boundary

One rule owns the write path: **no LLM ever decides what the store believes.**

The LLM appears in exactly three places, all upstream or offline:

1. **Extraction** proposes candidate facts from transcripts and notes. Every
   candidate then passes through the same deterministic gauntlet as a manual
   assert: validation, admission scoring, conflict policy, trust checks.
2. **The judge** classifies already-flagged conflict pairs, offline, with a
   confidence gate, and is never permitted to auto-resolve a genuine
   contradiction.
3. **Consolidation** summarizes episodes, with a mechanical fallback when no
   model is available.

Everything else is a pure function. The 2026 freshness result (+10.8 points
for deterministic versioning over LLM-mediated updates,
[Don't Ask the LLM to Track Freshness](https://arxiv.org/abs/2606.01435)) arrived after this
rule was set, and said the rule was right.

## The store is a view; the log is the record

Every mutation appends one effect line to the current writer's own
append-only log (`<db>.oplog/<writer>.jsonl`), stamped with a hybrid logical
clock ([Kulkarni et al., 2014](https://cse.buffalo.edu/tech-reports/2014-04.pdf)).
The live store is a materialized view over the logs, the same inversion
event-sourced systems and replicated logs use
([Kreps, "The Log"](https://engineering.linkedin.com/distributed-systems/log-what-every-software-engineer-should-know-about-real-time-datas-unifying)).

Because each machine appends only to its own file, any file syncer (git,
rsync, Syncthing) can move logs between machines without a transport
conflict, the property the local-first literature builds on
([Kleppmann et al., 2019](https://www.inkandswitch.com/local-first/)).
`claim reconcile` replays unseen foreign effects in canonical clock
order, matches entity identity by name, collapses claims both writers made
independently, and queues the contradictions neither writer could see.

This is deliberately not a CRDT
([Shapiro et al., 2011](https://hal.inria.fr/inria-00609399)). A CRDT's
contract is convergence by construction: disagreement is merged away. In a
memory system, two machines disagreeing about a fact is signal for a human,
and claimgraph already has a first-class representation for that: an open
conflict. Convergence here means both machines end up seeing the same
disagreement.

## The ambient loop

The pieces compose into a loop that runs without anyone learning a verb:

```
harness session → auto-memory notes → ingest-notes (delta-detected,
     ↑                                  inference-grade, full conflict
     │                                  machinery)
     │                                        ↓
inject at session start  ←  compile-context  ←  graph (+ consolidate, judge)
```

A SessionEnd hook runs the loop: first a mechanical code-freshness pass
(every detected language analyzer, delta-gated on the code episode's
`<git-sha>+<dirty-digest>` ref, so it costs nothing when nothing changed
and reconciles teammates' pulled changes when it did), then capture, then
compile. The compiled view (standing decisions, open conflicts, recent
supersessions, top current facts) is written into a marker-delimited
managed section of the file the harness already injects. The managed
section is stripped before ingestion hashes its input, so the graph never
re-consumes its own view: compile, ingest, compile is a fixed point.

## The invariants

Stated once, holding everywhere; the executable chapters exercise most of
them.

1. No LLM on the write path. Ever.
2. Invalidate, never delete. History is part of the data.
3. Commitments are never auto-resolved, by the write path, the judge, or
   reconciliation.
4. Reads never mutate. Decay is computed, not applied; the outcome log
   defers its writes to an explicit `outcome` verb.
5. Reinforcement never exceeds the source ceiling and never comes from
   repetition alone.
6. Entity ids are internal. Identity crosses machines and dumps by name and
   alias, and display names are local property.
7. Deterministic where claimed: compile-context, the benchmark mechanics,
   and reconciliation ordering produce identical output from identical
   input, no exceptions.
