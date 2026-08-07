# Agent Memory: A Synthesis

A working overview of the state of LLM/agent memory as of mid-2026 — the
frameworks, analogies, and mental models worth carrying into prototype design.

-----

## 1. The core problem

Modern coding agents and conversational LLMs are stateless at the API level:
each completion is an independent function call. To simulate continuity across
sessions, every framework reinvents some kind of “memory” — and the dominant
solution today is **a pile of auto-generated markdown**: notes a harness
accumulates on its own, session after session, with nothing curating what
goes in (Claude Code's per-project memory dir, Codex's memories, ad hoc
scratch notes) — that pile is the subject of this document. `CLAUDE.md`,
`AGENTS.md`, and ADR folders are a different artifact: deliberate,
human-maintained configuration and decision records. The failure modes
below are the pile's.

This works at the smallest scale and falls apart everywhere else:

- No structured retrieval (grep + full-file reads)
- No invalidation (contradictions accumulate silently)
- No epistemic typing (a passing thought has equal weight to an architectural
  commitment)
- No consolidation (the pile grows monotonically; nobody compiles “what’s
  currently true”)
- No scoping or access control
- No portability across vendors (or, conversely, total portability but no
  structure — depending on which markdown approach)

The thesis of the conversation: this is roughly where computing was before
operating systems — it works, but the abstractions for serious work haven’t
been built yet.

-----

## 2. Three lenses we kept returning to

### 2.1 OS memory hierarchy

The dominant working metaphor in serious agent-memory research. Operates at
two layers of the stack:

- **Infrastructure layer**: vLLM’s PagedAttention literally borrows virtual
  memory and paging from OS design — KV-cache stored in fixed-size blocks
  mapped to non-contiguous physical memory.
- **Agent layer**: MemGPT / Letta frames agent memory as virtual memory
  management — a small “main context” backed by a large external store, with
  the model issuing function calls to page data in and out.

The implied hierarchy nobody has built end-to-end:

|Tier           |Analogue                   |What lives here                    |
|---------------|---------------------------|-----------------------------------|
|Registers      |Current reasoning step     |Variables in the active completion |
|L1/L2 cache    |Working memory             |Recent turns, scratchpad           |
|RAM            |Session context            |Current conversation, files in view|
|SSD            |Per-agent persistent memory|This user, this codebase           |
|Network storage|Org-wide shared memory     |Team conventions, shared knowledge |

Each tier wants its own retention policy, its own eviction rule, and its own
coherence guarantees. Today’s systems mostly collapse this into a single tier.

### 2.2 HTTP statelessness

HTTP solved statelessness with cookies → sessions → JWTs: a *protocol
convention* for handing the client an opaque token the server uses to
reconstitute state. The genius is the standardization, not the storage.

LLMs are stateless in the same way — but no agreed-upon convention exists.
Every framework invents its own: LangChain thread IDs, OpenAI Assistants
thread_id, Letta agent IDs, Claude Code session files, Cursor conversations.
None portable. **MCP is closer to “HTTP for tools” than to a session spec.**

The deeper observation: there are actually two conflated problems.

1. **Conversation continuity** — “this is turn 47 of the same chat.” Solvable
   with any opaque ID + server-side log. Mostly solved per-vendor.
1. **Agent identity** — “this is the same agent instance from last week, with
   accumulated memory, learned preferences, and procedural knowledge, even
   though the model weights may have updated and the process has restarted
   1000 times.” Unsolved. No portable serialization format for “agent state.”

The OS analogue: Unix PIDs die with the process. What persists is the *user
account* (UID, home directory, credentials in `~/.ssh`). The thing missing in
agent-land is the equivalent of a UID — a stable identity surviving process
death, framework changes, and model upgrades, pointing to a portable bundle
of “this agent’s accumulated self.”

### 2.3 Cognitive-science memory taxonomy

The four-way split now standard in serious agent designs:

- **Working memory** — the live context window
- **Episodic memory** — logs of what happened when (interaction traces)
- **Semantic memory** — extracted facts about the user, world, codebase
- **Procedural memory** — learned workflows, tool-use patterns, conventions

Markdown-pile systems collapse all four into one undifferentiated bucket. The
fix is keeping them as distinct stores with different update rules, retrieval
policies, and forgetting policies. A fifth dimension — *forgetting* — needs to
be layered on per-tier: working memory forgets every turn; episodic decays
and consolidates; semantic rarely forgets but updates; procedural accumulates
and rarely contradicts.

-----

## 3. The three architectural camps for agent memory

|Camp                   |Examples                      |Strengths                                          |Weaknesses                                                    |
|-----------------------|------------------------------|---------------------------------------------------|--------------------------------------------------------------|
|Vector / RAG           |Mem0, Chroma, LangChain memory|Cheap, fast, easy                                  |Bad at contradictions, “decided vs discussed,” last-write-wins|
|Knowledge graph        |Zep/Graphiti, Cognee          |Bi-temporal validity, queryable history, structured|Schema design overhead, more expensive to maintain            |
|Hierarchical / OS-style|Letta/MemGPT                  |Explicit tiers, LLM as memory manager              |Heuristic eviction, no real consolidation                     |

A fourth pattern emerging from A-MEM (Yu et al., 2026):

- **Schema-less self-modifying graphs** — atomic notes with LLM-generated
  tags and links; new notes can *rewrite* their neighbors’ context. Closer to
  Zettelkasten than to a formal KG. Strictly less expressive than Graphiti
  but more adaptive — no ontology required up front.

The interesting axis splitting the field: **static stores** (RAG, basic
vector DBs) vs. **self-modifying stores** (A-MEM, Dreaming, future Letta).
Self-modifying is more powerful and more dangerous: memory drifts under its
own influence without provenance guarantees.

-----

## 4. The declarative shift and its new primitives

The interface to coding agents is moving from imperative (“read this file,
change X to Y”) to declarative (“here’s what done looks like”).

|Primitive                |Source                           |Function                                                                      |
|-------------------------|---------------------------------|------------------------------------------------------------------------------|
|`/goal`                  |Codex CLI v0.128+                |Persistent objective, loops until success or budget exhausted, state in SQLite|
|Outcomes                 |Anthropic (Code with Claude 2026)|Specify what “done” looks like; retry-with-grader loop                        |
|Dreaming                 |Anthropic (Code with Claude 2026)|Offline consolidation of session traces into reusable patterns                |
|Multi-agent orchestration|Anthropic et al.                 |Coordinator spawns subagents in parallel                                      |

This makes memory *more*, not less, important. Imperative prompting put the
context burden on the human; declarative goals shift it onto the agent’s
memory. The more declarative the interface, the more the markdown-pile
approach collapses.

New skill emerging: **rubric authoring**. The analogue of writing good SQL or
designing a good schema. Declarative systems are only as good as the predicate
you can specify; a vague rubric produces stochastic retry, no better than
running the same prompt 10 times.

-----

## 5. Convergence on an OS-shaped architecture

When you put the new primitives next to the OS analogy, the mapping is almost
on the nose:

- Outcomes / `/goal` ≈ **syscalls** (declarative interface)
- Memory store ≈ **filesystem** (persistent state)
- Dreaming / consolidation ≈ **background daemons / sleep**
- Multi-agent orchestration ≈ **processes**
- MCP ≈ **device drivers / IPC**

Markdown-pile is to this what single-user DOS was to a modern OS.

-----

## 6. The unsolved problems

### 6.1 Forgetting / eviction

Was the most under-attended problem; recent work is starting to address it
seriously.

- **Heuristic eviction** (deployed but crude): FIFO (MemGPT), three-tier heat
  metrics (MemoryOS), Ebbinghaus decay (MemoryBank), size caps (Reflexion).
  Real but simple — closer to LRU than to learned memory management.
- **Learned eviction via RL**: Memory-R1 (Aug 2025) trains a Memory Manager
  agent with ADD / UPDATE / DELETE / NOOP operations using PPO and GRPO with
  outcome-based rewards. State-of-the-art with only 152 QA training pairs.
- **Principled forgetting with guarantees**: “Forgetful but Faithful”
  (Dec 2025) treats forgetting as submodular optimization with regret bounds;
  combines temporal hygiene, controlled summarization, and importance-aware
  eviction. Privacy-aware tie-breaking prevents sensitive content from
  becoming the marginal survivor when budgets tighten.

Status: under-solved relative to retrieval. Retrieval got ~90% of the
research attention for two years; forgetting is starting to catch up but
hasn’t migrated to production frameworks yet.

### 6.2 Belief revision

Identifying what an agent “currently knows to be true” — separate from raw
memory storage.

- **AGM framework** (Alchourrón, Gärdenfors, Makinson, 1985): expansion,
  contraction, revision; epistemic entrenchment ordering of beliefs. Theory
  exists but is undecidable in expressive logics; transfer to LLMs is hard.
- **Bi-temporal graphs** (Graphiti / OpenAI cookbook): the practical
  state-of-the-art. Valid time + transaction time; non-lossy invalidation
  where contradicted edges get `t_invalid` set rather than deletion.
- **Model editing literature** (in-weights updates): ROME, MEND, MEMIT, etc.
  Hase et al.‘s 2024 paper *Fundamental Problems With Model Editing*
  enumerates 12 open problems and shows edited LLMs achieve **1% accuracy**
  on downstream factual entailments — the edit “sticks” for the exact prompt
  but doesn’t propagate at all.

The bridge from formal belief revision to LLM agents is half-built. The
in-weights branch doesn’t have a coherent destination on the LLM side; the
external-store branch (bi-temporal graphs) is shipping but lacks credence,
entailment propagation, and source weighting.

### 6.3 Provenance and trust

When an agent “remembers” X, was X *observed*, *inferred*, or *hallucinated*?
Bi-temporal graphs preserve history but rarely track confidence or source
credibility. A-MEM-style in-place mutation actively destroys provenance.

This is the security angle: “memory pollution” (write-path poisoning by
attackers) vs. “memory confabulation” (the LLM over-inferring during
extraction). Both mitigated by good forgetting plus provenance, but neither
is standard.

### 6.4 Access control / governance

Currently agents have no concept of who’s allowed to access what information.
The active blocker for enterprise adoption. Recent work (DataRobot’s ACL
Hydration; multi-user memory sharing research) is converging on
*graph-based entitlements*: identity providers, application configurations,
permission lists, and platform rules represented as a single holistic graph.

The naturally emerging answer: memory and permissions are both graph-shaped
and want to live in the *same* graph.

### 6.5 Write conflicts

No MVCC or CRDT equivalent for agent memory yet. Two agents updating the
same store: last-write-wins.

### 6.6 Portable agent identity

Discussed above; no RFC-equivalent. Some scrappy open-source project will
ship a portable agent-state format, two big vendors will adopt it, then it
becomes the de facto standard. Probably 18–36 months out.

-----

## 7. The end-state schema

The thing that lives in nobody’s product but follows from the synthesis: a
knowledge graph where every edge carries metadata across multiple dimensions.

```
(subject, predicate, object,
 t_valid, t_invalid,           // bi-temporal validity
 confidence,                    // credence
 source,                        // who/what said this
 source_credibility,            // how much we trust them
 read_acl, write_acl,           // permissions
 epistemic_class,               // observation | commitment | preference
 source_type,                   // code | user_assertion | inferred | decision_record
 scope,                         // file | module | project | user | global
 git_ref)                       // for code-derived facts
```

All five things (memory, time, credence, provenance, ACL) want to be edge
properties on the same graph. Splitting them across systems is the
integration pain enterprise vendors are now trying to solve.

-----

## 8. Targeting the coding-agent use case

The natural first prototype target. Coding agents have all the right
properties: long-lived corpus, frequent updates, real consequences for
failure, a user who can give clear feedback.

Five reasons codebases are harder than the cookbook’s earnings-call corpus:

1. **Corpus mutates in place** — files renamed, deleted, refactored
1. **Heterogeneous sources** — code itself is one source; user assertions are
   another; decision records are a third
1. **Unstable entity identity** — `UserService` after a split: same entity?
1. **Commitments vs observations** — “we decided not to use GraphQL” outlives
   any code state
1. **Volume** — 500k LOC produces orders of magnitude more triplets than
   188 earnings calls; LLM-per-statement extraction is infeasible

The four cognitive memory tiers mapped to a coding agent:

- **Working**: current session context (already handled by chat UIs)
- **Episodic**: session logs — bulky, useful for “why did we do X”
- **Semantic**: facts about user, codebase, conventions — the thing markdown
  is failing to be
- **Procedural**: how we do things here — test patterns, deployment steps,
  idioms

Ingestion has to be multi-source:

1. **Code analysis pass** (mechanical, no LLM): AST walks, dependency
   graphs, naming-convention inference. High confidence, auto-invalidates on
   git changes.
1. **Session ingestion** (LLM at session end): preferences, decisions,
   gotchas surfaced during the work. Lower confidence.
1. **Decision records** (human-marked): ADRs, highest authority, never
   auto-invalidated.
1. **Failure ingestion**: when something the agent did got rejected or
   reverted, extract why. Procedural memory grows from these.

Invalidation has tiers:

- **Mechanical** (git events) — cheap, 100% reliable
- **Semantic** (LLM-driven) — for cases where rules don’t apply
- **Soft / staleness** (decay) — drop confidence when un-referenced
- **Commitment flagging** — surface conflicts to user rather than
  auto-invalidating high-authority claims

-----

## 9. The reference repos and what each gets right

Four data points triangulating the design space:

|Project                            |What it gets right                                                                                                                   |What it lacks                                                                            |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
|**OpenAI Temporal Agents cookbook**|Bi-temporal storage; epistemic typing (Fact/Opinion/Prediction × Static/Dynamic/Atemporal); non-lossy invalidation                   |Closed corpus assumption; expensive LLM-per-statement extraction; no scope/ACL           |
|**agemem** (gianpd)                |Three-layer control: deterministic floor + LLM judgment + salience signal; double-boundary overflow guard                            |Trivial token-overlap retrieval; vendor-coupled; no graph                                |
|**git+markdown** (LinkedIn pattern)|Portability, ownership, vendor neutrality; free transaction-time via git; per-branch memory possible                                 |No structure, no invalidation, no consolidation, grep retrieval                          |
|**GitHub spec-kit**                |Authoring discipline; the constitution as a typed authoritative root; clarification gauntlet produces clean ingestion-ready artifacts|Write-once-by-decree; no maintenance, invalidation, or consolidation of accumulated specs|

None has the full stack. The thing you’d build is the union: spec-kit-quality
authoring feeding a cookbook-style bi-temporal graph, maintained by
agemem-style layered control, stored on a git-backed substrate, exposed over
MCP.

The strategic implication: **don’t replace authoring tools, eat their
output.** Let people author with whatever discipline they like. Be the system
that ingests those artifacts into a queryable, self-invalidating graph.

-----

## 10. Prototype design sketch

If building tomorrow, in order:

1. **Schema and storage**: graph store in a file (Neo4j embedded / DuckDB
   with graph extension / SQLite + adjacency tables). Committed to the repo.
1. **Code analysis ingester** (mechanical, no LLM): produces the bulk of
   triplets from AST walks. Replaces ~70% of what people stuff in CLAUDE.md.
1. **MCP server over the graph**: tools like `get_facts(entity, scope, as_of)`, `query_decisions(topic)`, `record_preference(text)`. This is how
   the agent reads and writes.
1. **Session ingester**: LLM-driven extraction at session end. Start with
   user-marked moments before automatic extraction.
1. **Invalidation agent**: mechanical first (git events), semantic later.
1. **Consolidation pass**: offline, Dreaming-style, once you have enough
   data to consolidate.

The first three give you most of the value. The deal-breaker is *not*
remembering things — it’s *querying* what’s remembered and *invalidating*
what’s wrong. A graph with even mechanical invalidation beats a pile of
markdown on both axes.

-----

## 11. The honest summary

- Markdown piles are a pre-OS solution to a problem that wants an OS-shaped
  answer.
- The pieces of the OS — bi-temporal storage, learned eviction, declarative
  goals, consolidation primitives, graph-based ACLs — all exist separately.
- Nobody has integrated them. The integration is the product.
- Coding-agent memory is the right beachhead: high-frequency, high-stakes,
  rich feedback signal, technical user.
- Substrate must be portable and owned (git-backed). Vendor-locked memory
  stores are the agent equivalent of building only for Internet Explorer.
- Declarative interfaces make memory more important, not less. Outcomes-style
  goals without good memory degenerate into stochastic retry.

The unbuilt thing: a per-codebase knowledge graph with bi-temporal validity,
epistemic typing, source-credibility weighting, and an MCP interface,
ingesting from code analysis, session logs, and human-authored artifacts —
all stored in files committed alongside the code. A couple of weekends to a
working prototype, and already better than what every coding agent ships
today.
