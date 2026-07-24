# Background: the problem and the field

## Statelessness and the markdown pile

A large language model completes one request at a time. Each API call is an
independent function invocation; whatever continuity a coding agent appears
to have across sessions is reconstructed from whatever it persisted between
calls. Most systems persist to files on disk, and the dominant format in 2026
is markdown: `CLAUDE.md`, `AGENTS.md`, auto-memory directories, ADR folders,
scratch notes. The harness loads some subset of these into the context window
at session start and hopes the relevant parts are in there.

This works for a week and then degrades with no recovery mechanism. The
failure modes are structural, not incidental:

- **No structured retrieval.** Finding what the pile knows about a service
  means grep and full-file reads.
- **No invalidation.** When the deployment target changes from Heroku to Fly,
  the note saying Heroku does not go away. Contradictions accumulate
  silently, and [Claude Code's own memory
  documentation](https://code.claude.com/docs/en/memory) concedes the
  consequence: when two files "give different guidance for the same
  behavior, Claude may pick one arbitrarily."
- **No epistemic typing.** "We decided against GraphQL after a real incident"
  and "the model noticed we use kebab-case" are the same kind of line in the
  same kind of file. One of these should be nearly immovable and the other
  should fade if it stops being true. The pile cannot tell them apart.
- **No history.** An edited file destroys its own past. "What did we believe
  before, and why did it change" is unanswerable, and so is "did this ever
  change at all."
- **No consolidation.** The pile grows monotonically until someone compacts
  it by hand, or the harness truncates it by size, and neither operation
  knows what it is throwing away.

Cognitive science has distinguished kinds of memory since
[Tulving (1972)](https://alicekim.ca/EMSM72.pdf) separated episodic memory
(what happened) from semantic memory (what is known), and the agent
literature added working memory (the live context) and procedural memory
(how we do things here). A markdown pile collapses all four into one bucket
with a single retention policy (whatever fits).

## The architectural camps

As of 2026 the field has converged on five distinct approaches.

**Vector and RAG stores** ([Mem0](https://arxiv.org/abs/2504.19413),
[LangMem](https://github.com/langchain-ai/langmem), most platform offerings)
embed extracted fact strings and retrieve by similarity. Cheap and fast, but
structurally blind to contradiction: old and new versions of a fact coexist
as separate vectors, with recency ranking as the only arbiter.
[Mem0's v3 changelog](https://docs.mem0.ai/changelog) is a telling example:
earlier versions tried to reconcile contradictions at write time, but v3
abandoned that. It switched to an ADD-only model — in its own words,
"Memories accumulate; nothing is overwritten or deleted" — and dropped the
external graph backends that did the reconciling. Faced with the cost of
resolving conflicts on write, a leading system chose to stop trying.

**Temporal knowledge graphs** ([Zep/Graphiti](https://arxiv.org/abs/2501.13956),
[Cognee](https://github.com/topoteretes/cognee), the
[OpenAI temporal-agents cookbook](https://developers.openai.com/cookbook/examples/partners/temporal_agents_with_knowledge_graphs/temporal_agents))
store entities and fact edges with validity timestamps, invalidating
contradicted edges rather than deleting them. This is the strongest shipped
structure for update-heavy recall; a
[twelve-system study](https://arxiv.org/abs/2606.24775) found temporal-graph
systems leading in the workloads where facts change. This is the one family
that holds up for adaptive, long-running agent memory: because every fact
carries when it was true, the store can separate what has been superseded
from what still holds. Systems without that temporal dimension cannot
disambiguate when things happened, so they lose track of what is currently
the case.

**OS-style hierarchies** ([MemGPT](https://arxiv.org/abs/2310.08560), the
theory behind [Letta](https://www.letta.com/)) model agent memory on how an
operating system manages memory: the context window is RAM, the external
store is disk, and the model pages data in and out through function calls as
it needs it. The analogy is productive, but the hard part — deciding what to
evict from the limited context when it fills up — comes down to rules of
thumb rather than any principled policy.

**Files plus agency**, the camp that grew fastest in 2026: give the agent a
filesystem and file tools and let it curate its own notes
([Letta's pivot](https://www.letta.com/blog/context-repositories/),
[Anthropic's memory tool](https://docs.claude.com/en/docs/agents-and-tools/tool-use/memory-tool),
[Claude Code auto-memory](https://code.claude.com/docs/en/memory),
[Basic Memory](https://github.com/basicmachines-co/basic-memory)). In effect
these are agent-maintained wikis, distilled from sessions on the fly: the same
markdown pile, now curated by the agent instead of the human. What this
approach cannot answer is unchanged: what did we believe in March, is this a
decision or an observation, what contradicts what.

**Self-modifying note networks** ([A-MEM](https://arxiv.org/abs/2502.12110))
let new notes rewrite their neighbors' content in place. This has its own
problems: the store drifts under its own influence and destroys provenance as
it goes.

## What the field measured in 2025 and 2026

Agent memory has been an unusually active research area over the past two
years, and many of the approaches the field settled on are now backed by
measurement rather than intuition. The results below are the ones that shaped
claimgraph most directly.

**Ambient context injection does not pay.** The
[AGENTS.md study](https://arxiv.org/abs/2602.11988) (Gloaguen et al., an oral
at the workshop) measured repository context files across SWE-bench tasks and
a set of developer-committed ones. Its finding, verbatim:
"Providing context files does not generally improve task success rates,
while increasing inference cost by over 20% on average."
The lesson is narrower than "context files are useless": always-injected
context loses to selective retrieval at the moment of need. Any memory
system whose read path is "dump everything into the prompt" is on the wrong
side of this result.

**The LLM should not adjudicate the write path.**
[Don't Ask the LLM to Track Freshness](https://arxiv.org/abs/2606.01435)
found deterministic version-aware conflict resolution beat LLM-mediated
resolution by 10.8 points. [SAGE](https://arxiv.org/abs/2605.30711) made
add-or-skip decisions deterministic and beat Mem0 while cutting cost 3.4x.
[A-MAC](https://arxiv.org/abs/2603.04549) kept four of its five admission
signals rule-based. [TOKI](https://arxiv.org/abs/2606.06240) formalized
bi-temporal contradiction handling as a typed operator algebra. All of these
results point to the same conclusion: writes should be decided by policy,
with the LLM at most proposing candidates.

**Lossy extraction faces a write-before-query barrier.**
[TierMem](https://arxiv.org/abs/2602.17913) named the problem: compression
decides what to keep before any future query exists, so whatever the
extractor drops is unrecoverable and no answer can be audited past the
summary. Its fix, an immutable raw tier under the extractions with
escalation when summaries cannot support an answer, is cheap and structural.

**Retrieval is where the accuracy points are.** A
[3x3 study of write strategy against retrieval method](https://iclr.cc/virtual/2026/10021251)
found a 20-point accuracy spread across retrievers and only 3 to 8 points
across write strategies. The implication for any structured store is that
write-time structure is not justified by plain recall, where flat stores with
good retrieval keep up. It is justified by the queries flat stores cannot
answer at all: history, time travel, conflict surfacing, provenance.

**Agents do not invent structure.**
[StructMemEval](https://arxiv.org/abs/2602.11243) showed memory agents
succeed at organization-requiring tasks only when told how to organize — left
to themselves, agents do not reliably invent a usable structure. claimgraph's
answer is a controlled vocabulary enforced at the API level, which bakes that
instruction in permanently instead of hoping the agent supplies it each time.

**Memory poisoning is practical.** [MINJA](https://arxiv.org/abs/2503.03704)
achieved 98% injection success into agent memories through ordinary queries
with no privileged access required. A memory system that ingests transcripts
and notes has an attack surface, and therefore needs a trust model, not just
a confidence cap.

**Belief revision has theory waiting to be used.** The
[AGM postulates](https://doi.org/10.2307/2274239) (Alchourrón, Gärdenfors,
Makinson, 1985) describe rational belief change; [Kumiho](https://arxiv.org/abs/2603.17244)
proved AGM properties for a versioned graph memory, and the workshop's
[Belief Engine paper](https://iclr.cc/virtual/2026/10021252) showed
rule-updated belief state is more stable than asking the LLM to update
beliefs. claimgraph takes this to heart with a rule of its own: a commitment
is never silently clobbered.

## The strategy that follows

Two conclusions came out of this. Both shaped the design that follows.

First, the baseline to beat is auto-memory — the agent-maintained markdown the
harnesses now ship by default — not hand-written context files. Its capture is
genuinely valuable (the model already judged what was worth keeping) and its
storage is exactly the pile the literature dismantled. So claimgraph does not
compete with ambient capture; it consumes it. The harness's notes become an
ingestion tier, and the graph compiles its current view back into the file the
harness injects. Capture is delegated in, injection is delegated out, and the
structured store sits in the middle as the consolidator. More detail is in the
original research and design note,
[`docs/consuming-auto-memory.md`](https://github.com/kirahowe/claimgraph/blob/main/docs/consuming-auto-memory.md).
The failure modes this chapter opened with are also mechanically measurable:
`claim audit` runs those same checks over an existing markdown pile and scores
it — contradictions, silent disagreements, staleness against the code,
restatement, name drift, injection bloat — before anything is installed.

Second, the claim has to be demonstrated as improvement on the actual end
task, not as a score on some memory benchmark. The point is not doing well on
contrived retrieval metrics (insofar as good ones even exist); it is whether
an agent using claimgraph completes more real work correctly than one without
it. The AGENTS.md result set the bar: context that merely exists does not
help. The benchmark chapter takes that protocol (same tasks, same agent,
memory arms varied) and reports where claimgraph wins, where it merely ties,
and what failure remains.
