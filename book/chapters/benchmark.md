# The benchmark

No LoCoMo or LongMemEval equivalent exists for codebase memory, and the
conversational benchmarks stopped being informative in any case. The
[field survey's credibility-crisis section](https://github.com/kirahowe/claimgraph/blob/main/docs/memory-systems-comparison.md#5-benchmarks-and-the-credibility-crisis)
catalogs the damage, with sources: five vendors simultaneously claiming
state of the art on LoCoMo with self-run numbers, a LoCoMo audit finding
ground-truth errors in about 6% of its questions, and one project caught
faking tens of thousands of stars for a "benchmark" that turned out to be
an unmodified vector store. claimgraph's evaluation was
designed against that backdrop, with three rules: deterministic scoring
wherever the claim allows it, measurement focused on the axes where the
field is weakest and structure should pay, and the headline claim
demonstrated as net end-task improvement, because the [AGENTS.md study](https://arxiv.org/abs/2602.11988)
proved that context merely existing does not help.

Everything below is reproducible from the repo. The mechanics layer and the
ablation run in seconds with recorded LLM outputs; the A/B spends real
`claude -p` calls.

## The fixture: shoply

`bench/` holds a synthetic project that lives through six months: three
code-ingestion passes (January, March, June) and four sessions, with real
events threaded through them. A hosting migration from Heroku to Fly. A
namespace renamed and a duplicate merged. A dependency adopted against a
standing rejection. A decision relitigated. An observation nobody ever
restates. A poisoned session that plants an instruction-shaped preference
and a plausible false fact (the [MINJA](https://arxiv.org/abs/2503.03704) pattern, miniaturized). Notes that
restate facts, contradict a commitment, and get compacted away. And a
contamination control: a fixture entity named React that is an in-house
Clojure queueing library, so any correct answer about it must come from the
graph rather than the model's parametric knowledge ([DialSim](https://arxiv.org/abs/2406.13144)'s
adversarial-renaming trick).

## Layer one: deterministic mechanics

`bb bench` asks 33 questions across twelve capability areas: retrieval,
time travel, history, identity, conflicts, forgetting, provenance, the
ambient loop, staleness, abstention, poisoning, and shift recovery. Scoring
is rule-based, no LLM judge anywhere, and the suite exits non-zero below a
perfect score, so it runs in CI as a longitudinal regression gate. The
current state is 33 of 33 on both store backends, with per-read latency
reported next to accuracy (median well under a millisecond in-process; the
honest cost is CLI cold start, which is what the MCP front-end exists to
amortize).

Highlights of what the questions actually check: as-of queries between
supersessions return exactly one answer; the rename resolves old names at
Recovery@0 (the alias machinery needs zero reads to catch up, where
[ShiftBench](https://openreview.net/attachment?id=CCSztIjmOy&name=pdf) found method rankings invert under shift); compaction-absence
fades instead of invalidating; the echo guard holds (compile, ingest,
compile is a fixed point); the planted preference stays capped, decays on
schedule, flags rather than overrides the standing commitment, and traces
to one quarantinable episode; and questions whose correct answer is "the
graph does not know" return empty rather than near-miss garbage.

## Layer two: LLM quality, kept out of CI

`bb bench llm` measures the judgment-dependent parts with a real model:
extraction precision and recall against annotated transcripts, entity
fragmentation, and judge verdict accuracy on labeled conflict pairs. Each
pair is judged k times (default 3) with the flip rate reported alongside
accuracy, because the judge literature measured average flip rates of 14%
on single runs ([The Coin Flip Judge](https://arxiv.org/abs/2606.13685)):
a pair that flips under repetition is a pair `--resolve` must not touch,
whatever its confidence claims.

## The ablation: where structure pays, honestly

Following the [retrieval-versus-utilization diagnosis](https://iclr.cc/virtual/2026/10021251), `bb bench ablation`
holds the fixture fixed and compares three arms: claimgraph in full, raw
transcript chunks with TF-IDF retrieval, and claimgraph's facts with
retrieval degraded to bare FTS.

| Arm | Score |
|---|---|
| claimgraph, full | **1.00** |
| raw chunks + TF-IDF | 0.38 |
| facts + degraded FTS | 0.25 |

The negative half is published on purpose: plain recall and single-chunk
history are where raw retrieval keeps up, exactly as the literature
predicts. Current-truth disambiguation, time travel, conflicts, forgetting,
and abstention are where structure pays. One question (the current hosting
provider, after the poisoning) is answerable only because the trust model
revenant-flags the resurrected value; no retrieval-only arm can
disambiguate it, because for a retriever the poison is just another
well-ranked chunk.

## The headline: a four-arm end-task A/B

`bb bench ab` adopts the AGENTS.md study's protocol: same tasks, same
agent, only the memory arm varies. Seven memory-dependent tasks (including
a skill-layer abstention probe) run under four arms: no memory, a static
context file, auto-memory (the agent-maintained markdown pile, the actual
incumbent), and claimgraph's ambient loop. JSON-scored, real `claude -p`
calls.

Pilot results (n=7, one run per arm):

| Arm | Score |
|---|---|
| claimgraph (ambient loop) | **0.71 → 0.86** after the trust model landed |
| no memory | 0.43 |
| static context file | 0.43, including one confabulation off the stale file |
| auto-memory | **0.29** |

Two results matter more than the ranking. First, auto-memory scored *below
no memory*: the compacted pile plus the planted note actively misled the
agent, which reproduces the AGENTS.md finding from the incumbent's side.
Second, the claimgraph arm's evolution on the poisoned hosting question is
the whole design argument in one number: before the trust model, the agent
confidently answered Heroku off the planted fact (0.71 includes that miss);
after the revenant check and the disputed-fact exclusion from
compile-context, the same question produces an honest "provider unknown."
The attack can no longer buy a wrong confident answer; it can only buy
uncertainty, which is what an attack on a well-behaved memory should buy.

The pilot's limits are stated in the roadmap where its numbers are
recorded: n=7 with single runs has no confidence intervals, and growing to
about 20 task pairs with repeated runs, plus a queryable-CLI arm (pull-side
judgment rather than the compiled view), is the standing follow-up.

## Scale

`bb bench scale 100` generates synthetic history at 100x the fixture
(about 2,300 facts) with rule-based QA: 20 of 20 correct, reads about
10 ms, searches about 22 ms, writes about 21 ms each through the pod, and a
consolidate pass at 51 prompts (about 208 KB), the maintenance cost the
evaluation-critique literature says everyone omits. Nothing structural
breaks; the first real ceiling is per-fact write latency during bulk
ingestion.

## The measurement tiers around it

The benchmark measures claimgraph against alternatives on a fixture. A
second axis of measurement, planned around the dogfooding round, measures
*the user's own repo*: (1) `claim audit`, shipped — the instant, mechanical
consistency scorecard over the existing memory pile (the audit chapter),
which quantifies the "before" state without installing anything; (2) an
exam tier — before/after agent Q&A against repo ground truth; and (3) a
longitudinal tier — relitigation, repeat-mistake, and correction rates from
the ambient loop. Tiers two and three are specified in
[`docs/memory-audit.md`](https://github.com/kirahowe/claimgraph/blob/main/docs/memory-audit.md)'s
header note and get their own design notes when picked up. Audit counts
wobble run-to-run with a live extractor (accepted for a diagnostic; the
quote receipts keep it honest), which is why it stays a report and CI
gating on it is deferred until the variance is understood.

## What this benchmark is not

It is one system's fixture, self-run, at pilot scale, and the numbers above
should be read with exactly the same skepticism this chapter applies to
everyone else's. Its defensible contributions are the protocol choices:
deterministic mechanics as a CI gate, adversarial tiers (poisoning,
staleness, abstention, contamination) as first-class questions, published
negative ablations, and end-task measurement against the real incumbent. A
neutral multi-system codebase-memory benchmark remains the
highest-leverage artifact anyone could ship in this field; the roadmap
sequences it after this one proves out, and that sequencing stands.
