# Memory Systems for AI Agents: A Field Comparison

*Compiled 2026-07-09. Consolidates this repo's research
(`agent-memory-synthesis.md`, `claimgraph-handoff.md`), claimgraph's shipped v0
(README/TODO), and fresh web research current to July 2026 across three
sweeps: built-in memory in coding harnesses, dedicated memory platforms, and
open-source/research systems + benchmarks. Focus is **functional
requirements** — what each system can and cannot do for its user — not
implementation. Claims marked ⚠ are single-source or vendor-self-reported.*

*Updated 2026-07-20: added **memledger** (Apache-2.0 alpha, an OSS trust/
governance layer for multi-agent memory) to §2.2, §3, §4, and §6 — the first
OSS entrant to occupy the governed tier this doc previously called
hosted-only.*

-----

## 1. The functional dimensions

The axes every system is scored on. They come out of the synthesis doc's
analysis of why markdown piles fail, refined against what the field actually
differentiates on in 2026:

| # | Dimension | The question it answers |
|---|-----------|------------------------|
| 1 | **Domain** | Codebase/project knowledge, or user/conversation personalization? |
| 2 | **Write path** | Manual authoring, automatic extraction, agent-curated, or a mix? When do writes happen? |
| 3 | **Read path** | Always injected into context, retrieved on demand, agent-queried, or validated before use? |
| 4 | **Structure** | Freeform files, structured records, vectors, graph, or hybrid? |
| 5 | **History & temporality** | Can it answer "what did we believe in March?" — none / git / versioned / bi-temporal with as-of queries |
| 6 | **Contradiction handling** | What happens when new information conflicts with old — nothing / overwrite / supersession with history / conflict surfacing |
| 7 | **Epistemics & provenance** | Does it distinguish decided vs observed vs preferred? Track source, confidence, citations? |
| 8 | **Forgetting** | Any decay, TTL, eviction, or is growth monotonic? |
| 9 | **Ownership & portability** | Local user-owned files vs vendor-hosted; does it survive switching vendors? |
| 10 | **Team & access control** | Shareable? Governed? Any ACL model? |
| 11 | **Consolidation** | Any offline/background pass that compacts, reconciles, promotes? |
| 12 | **Evaluation** | Any credible way to know it works? |

-----

## 2. Master comparison table

Legend: ● shipped/strong · ◐ partial/limited · ○ absent · ⚠ unverified or
vendor-claimed. "Local" = user-owned files; "hosted" = vendor-side store.

### 2.1 Built-in memory in coding agents/harnesses

| System | Domain | Write path | Read path | Structure | History/time | Contradictions | Epistemics/provenance | Forgetting | Ownership | Team/ACL | Consolidation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Claude Code** (CLAUDE.md + rules + auto memory) | codebase + user | manual + **auto** (auto memory, default-on since v2.1.59, ~Feb 2026) | always-inject (CLAUDE.md, rules, MEMORY.md index ≤200 lines) + lazy topic files | markdown files | ○ (git for CLAUDE.md; auto-memory dir unversioned, timestamps only) | ○ manual; docs warn contradictions pick arbitrarily | ○ (timestamps only) | ○ (size cap forces index compaction) | local (+ org "team memory stores" emerging ⚠) | ◐ git + managed policy + org stores ⚠ | ◐ index compaction; "dream" consolidator subagent ⚠ (extracted from binary, undocumented) |
| **OpenAI Codex** (AGENTS.md + Memories + /goal) | codebase + user | manual (AGENTS.md, 32 KiB cap) + **auto** (Memories GA 2026-05-21: background extraction from idle threads, secret redaction) | always-inject (AGENTS.md concat + consolidated `memory_summary.md`); per-thread opt-out | markdown files (+ SQLite goal state) | ○ (evidence files per source thread; git for AGENTS.md) | ◐ consolidation pass rewrites summary; no explicit logic | ◐ supporting-evidence links to source threads | ○ (no TTL) | local (`~/.codex/`) | ○ (AGENTS.md via git; memories single-user) | ● dedicated consolidation model producing the summary |
| **GitHub Copilot** (instructions + Copilot Memory) | codebase + user | manual (repo/path/personal/org instructions) + **auto** (Memory: preview, default-on Pro since 2026-03-04) | instructions injected; memories **validated at use** — repo facts re-checked against current branch, only validated facts used | text instructions + structured memory entries w/ citations | ◐ lifecycle timestamps; no as-of | ● best of the built-ins: citation re-validation; stale facts silently unused | ● citations to code/dialogue quotes; validation acts as confidence gate | ● 28-day unused-entry TTL (resets on validated use) | instructions local/repo; **memory GitHub-hosted, non-portable** | ● repo-shared memories, org instructions (GA 2026-04-02), admin export/bulk-delete | ◐ validation + expiry only |
| **Cursor** (rules; Memories **removed**) | codebase | manual only (Memories shipped v1.0 Jun 2025, **removed in 2.1.x** ~Nov–Dec 2025 ⚠ no changelog entry) | always / glob auto-attach / agent-requested / @mention | markdown/MDC + frontmatter | ○ git | ○ | ○ | ○ | rules local; Team rules vendor-hosted | ● Team rules org-enforced (Business+) | ○ |
| **Windsurf → Devin Desktop** (Cascade) | codebase | **auto** memories (+ on-request) + manual rules | rules per activation mode; memories model-judged relevance | markdown files | ○ | ○ manual edit/delete | ○ | ○ | local (memories per-machine, unsynced) | ◐ rules via git + enterprise system rules; memories never shared | ○ |
| **Gemini CLI** (GEMINI.md) | codebase + user | manual (+ `save_memory` append on explicit ask); **no auto-extraction as of Jul 2026** ⚠ | always-inject (full concatenation) | markdown files | ○ git | ○ appended facts accumulate until hand-edited | ○ | ○ | local, open-source CLI | ◐ git | ○ |
| **Cline** (Memory Bank) | codebase | agent-written but **prompt-driven** (a methodology, not a feature — per Cline) | read ALL memory-bank files every task (context-heavy) | prescribed markdown file hierarchy in-repo | ◐ git (bank lives in repo → decent history) | ◐ prompted review-all rewrite | ○ | ○ | local/repo — fully portable pattern | ◐ git | ◐ manual "update memory bank" |
| **Aider** (conventions + repo map) | codebase | manual conventions; repo map auto but **ephemeral** (recomputed per turn) | conventions always; map always (token-budgeted ranking) | text + derived symbol graph (not persisted) | ○ | ● by construction — map is always fresh | ○ | ● by construction (nothing persisted) | local, vendor-neutral | ◐ git | ○ (re-ranking per turn) |

### 2.2 Dedicated memory platforms

| System | Domain | Write path | Read path | Structure | History/time | Contradictions | Epistemics/provenance | Forgetting | Ownership | Team/ACL | Consolidation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Mem0** (v3, Apr 2026) | user/conv (coding-*preference* plugins via "OpenMemory" brand) | **auto** extraction, single-pass ADD-only (v3) | hybrid search (semantic+BM25+entity boost); **graph traversal removed in v3** | fact strings + vectors; **graph memory removed** | ◐ per-memory change log; no as-of | ◐ **retreated**: write-time ADD/UPDATE/DELETE resolution removed in v3; recency ranking at read time; old + new coexist | ○ no confidence/epistemics; agent inferences stored at equal weight to user statements | ◐ expiration_date; Platform-only decay = soft ranking multiplier, never deletes | Apache-2.0 OSS + hosted; real OSS-vs-paid feature gap | ● org/project RBAC (Platform) | ○ |
| **Zep / Graphiti** | user + business data (not code-aware) | **auto** extraction to temporal KG + manual triples | hybrid search + graph traversal + rerankers; auto context block | entities + fact edges + episodes + communities | ● **strongest shipped bi-temporal**: valid_at/invalid_at + created_at/expired_at; point-in-time via datetime filters (not first-class as_of) | ● shipped: new edges LLM-compared, contradicted facts invalidated non-lossily | ◐ per-episode provenance; no confidence; no epistemic classes | ○ nothing shipped | Graphiti Apache-2.0; **Zep self-host deprecated Apr 2025** → cloud; no bulk export ⚠ | ● ABAC on API keys (2026), project isolation, SOC 2 | ◐ ingestion pipeline is the consolidator; background "Observations" |
| **Letta** (post-pivot, Mar 2026) | coding agents (Letta Code) + assistants | **agent-curated** files (MemFS, git-backed); self-edit tools deprecated; no extraction pipeline by design | blocks/`system/` always in-context; files on demand; archival vector search | markdown + git; **explicitly anti-graph** ("filesystem is all you need") | ● full git versioning (every change a commit); no as-of API | ○ rewriting = editing files; `/doctor` audits | ○ (git blame as implicit provenance) | ○ char limits force agent compression | Apache-2.0; memory is literally your git repo; `.af` agent file (no verified external adoption ⚠) | ◐ shared blocks across agents; Enterprise RBAC | ● sleep-time/"dream" subagents (client-side worktrees post-pivot) |
| **Cognee** (v1.0 GA Jun 2026) | documents/org knowledge; code-graph pipeline (`codify`) as community add-on | explicit batch `remember()/cognify()`; opt-in session capture | 16 search types: graph completion, temporal, Cypher, feedback…; rule-based router | graph + vector + relational (provenance); OWL/RDF ontology grounding | ◐ temporal = **event-time extraction only**; no belief-time/versioning | ○ **weak spot** — no contradiction detection (open issue) | ◐ document→chunk→node provenance; no confidence/epistemics | ◐ `forget()`, memify pruning + usage-based reweighting; no time decay | Apache-2.0, fully local mode; COGX export ⚠ | ● RBAC, tenants, dataset ACLs | ● memify + feedback loop + background sync |
| **supermemory** | user + project docs | auto (conversation ingest, Memory Router proxy) + explicit API | hybrid search; **Router auto-injects** profile+memories into any LLM call | vectors + claimed internal graph (Updates/Extends/Derives edges ⚠ no published schema) | ◐ real versioning (PATCH → new version, `isLatest`); no as-of search | ● contradictions create "Updates" supersession edges, old retained | ◐ **partial epistemics**: inferred ("Derives") memories flagged + down-weighted until confirmed; review queue | ◐ soft-forget + NL mass-forget with dry-run; **auto time-decay claimed but undocumented** ⚠ | MIT local server (re-opened Jun 2026) but cloud uses proprietary tuned models | ● org RBAC, scoped keys, SOC 2/HIPAA | ● async pipeline, background inference, "dreaming" clustering, SMFS |
| **LangMem / LangGraph store** | user/conv | hot-path memory tools + background reflection executor | store search (vector+filters); **no auto-injection** — you wire it | JSON docs + vectors; no graph | ○ created/updated timestamps only | ◐ LLM-judgment reconciliation only | ○ | ◐ TTL (platform-only) | MIT; portable JSON; backends Postgres/Redis/Mongo | ◐ namespace convention; enforcement only via platform auth | ● reflection executor, prompt optimizers, crons |
| **Vertex AI Memory Bank** (GA Dec 2025) | user/conv | **auto** topic-based extraction | similarity search by scope | managed records | ● **memory revisions** first-class (versioning + provenance) | ● consolidation resolves contradictions | ◐ revisions carry provenance | ● TTL | proprietary; API export only | ● IAM conditions | ● managed consolidation |
| **AWS AgentCore Memory** (GA Oct 2025) | user/conv | **auto** via strategies (semantic/summary/preference/custom/self-managed) | namespace retrieval + typed filterable metadata | events + LT records | ◐ records link to source events | ● async consolidation with supersession | ◐ "strictly-consistent metadata" bypassing LLM inference | ● TTL | proprietary; API export | ● IAM, namespaces | ● managed async extraction/consolidation |
| **Anthropic memory tool + Memory Stores** | app-defined | **agent-curated** file ops (view/create/str_replace/…) against `/memories` you host; no extraction | agent reads its own files; API injects "check memory first" preamble | plain files, any backend | ○ tool itself; ● Managed Agents **memory stores** (beta 2026-04): every mutation an immutable version + actor provenance | ○ delegated to the model | ◐ stores: actor provenance + audit; tool: none | ○ (docs recommend app-side expiry) | tool: fully yours; stores: Anthropic-hosted | ◐ stores: read-only vs read-write mounts | ○ model is the consolidation engine |
| **memledger** (Apache-2.0 alpha `v0.5.0a0`, Jul 2026) — a *governance layer*, not a store | multi-agent conversational/RAG memory; enterprise trust & compliance | explicit SDK/MCP writes carrying source + confidence; **no extraction of its own** — it wraps your writes to any vector backend | vector similarity search with **confidence gating** (`min_threshold`/`flag_threshold` → PASS/FLAG); MCP server | vectors (pgvector) **+ a trust graph** of CONFLICTS + derivation edges over any backend | ○ no valid/transaction time, no as-of | ◐ near-duplicate detection at write → CONFLICTS edges flagged; no supersession, no temporal close | ◐ **provenance chains spanning agents/sessions + per-memory confidence; weakest-link effective confidence** (bounded by min ancestor in the chain); no epistemic classes | ◐ **RTBF cascades**: deletion propagates through derivatives with a compliance-grade audit trail; no time decay | Apache-2.0 OSS (`memledger[oss]` = Postgres+pgvector+local embeddings); AWS Aurora/Bedrock + OpenSearch paths; **server/Helm/K8s**, not local-binary | ● **Namespace RBAC** (declarative per-agent access over hierarchical namespaces) + OpenTelemetry trust attributes | ○ (MQS 0–100 quality score + **MAI** eval wired to RAGAS are *measurement*, not a consolidation pass) |

### 2.3 Open-source & research systems

| System | Domain | Write path | Read path | Structure | History/time | Contradictions | Epistemics/provenance | Forgetting | Ownership | Team/ACL | Consolidation |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **engram** (~5k★, very active) | codebase **experiential** memory | agent-manual (`mem_save`) + passive capture | FTS5 search, session context, timeline | typed observations (What/Why/Where/Learned) in one SQLite file | ◐ session timestamps; soft-delete | ● **only small OSS doing conflict surfacing**: lexical scan + subscription-as-judge classifies supersedes/conflicts_with | ◐ session/project provenance; judge rationale; no confidence | ○ manual prune | single Go binary, MIT; git sync (local always authoritative) | ◐ optional self-hosted cloud replication; no ACL | ◐ manual project consolidation |
| **ctxgraph** (~29★, early) | general episodes | **auto**, tiered local-first extraction (GLiNER→local LLM→cloud fallback) | fusion: FTS + vectors + recursive-CTE traversal, RRF-combined; no cloud LLM on reads | fixed 9-entity/10-relation schema, single SQLite file | ● bi-temporal-ish: valid_from/until + recorded_at; point-in-time queries | ● supersession via valid_until, history preserved | ◐ source + extraction tier per episode; no per-fact confidence | ○ | single Rust binary + SQLite, MIT, offline-capable | ○ | ◐ nightly schema promotion |
| **agemem** (~76★, stale since May 2026) | local-model assistants | layered: deterministic rules floor + LLM judgment + salience self-rating | hybrid score (cosine + recency + learning score) | SQLite + vectors | ○ recency only | ○ dedup only | ◐ self-assessed learning scores | ● deterministic STM eviction at utilization thresholds | Python, MIT, fully local | ○ | ◐ SUMMARY compression |
| **A-MEM** (NeurIPS 2025, paper code) | conversational | **auto** — LLM note construction + link generation | embedding retrieval + link expansion | self-modifying Zettelkasten note network | ○ timestamps | ◐ "evolution" rewrites neighbor notes — self-modifying, **no supersession semantics, destroys provenance** | ○ | ○ | MIT research code | ○ | ◐ evolution |
| **OpenAI temporal-KG cookbook** | closed corpus (blueprint) | **auto** pipeline transcripts→statements→triplets | multi-hop planners (task- vs hypothesis-oriented) | bi-temporal triplets/statements | ● full bi-temporal (t_valid/t_invalid + t_created/t_expired) | ● dedicated invalidation agent, `invalidated_by` links | ● **epistemic typing**: Fact/Opinion/Prediction × Static/Dynamic/Atemporal | ○ (invalidate, never delete) | educational notebook, not a library | ○ | ○ |
| **spec-kit** (~119k★ ⚠, v0.12.x, 30+ agents) | project principles/specs | human-authored via slash-command gauntlet | artifacts loaded as context per phase | markdown in-repo (constitution + specs) | ● git (full history/blame/review) | ◐ human review; invalidation = editing | ○ (authority by convention: constitution is root) | ○ | maximal — files in your repo | ● normal git permissions | ○ (write-once-by-decree) |
| **git+markdown pile** (baseline) | any | manual | grep + full-file reads | freeform | ◐ git transaction-time only | ○ contradictions accumulate silently | ○ | ○ | maximal | ◐ git | ○ |
| **claimgraph (this repo, v0)** | **codebase** | manual assert + JSONL ingest + **mechanical code ingester** (reconciling) + LLM session-extract (roster-primed, conf-capped) | agent-queried via skill/CLI: facts, BFS neighborhood, FTS, history, **as-of**; direction in/both; no auto-injection | reified-edge KG; 22-predicate controlled vocabulary anchored to PROV-O/SPDX/DOAP/DC + `x/*` staging | ● **bi-temporal modeled** (valid + transaction time), first-class as-of & history queries, non-lossy | ● epistemic-class-derived policy: observations/preferences supersede, **commitments flag** (never auto-resolved); exclusion groups; LLM judge + offline sweep | ● **epistemic classes + per-fact confidence + source-type + episode provenance** | ● **disuse decay as read-time view** (90-day half-life, reinforcement with per-source ceilings, commitments exempt) | local; LMDB live store + committable JSONL dump; two native binaries | ○ single-writer; ACL fields reserved, unenforced | ● Dreaming-style `consolidate`: episode summaries (searchable), judge, sweep, promotion review |

### 2.4 Notable adjacent systems (capsules)

- **MemOS** (MemTensor, ~10k★): "memory OS" thesis — plaintext + KV-cache +
  parametric memory tiers; product is mostly plaintext + scheduling; lifecycle
  and decay are paper claims thinner in the product ⚠.
- **MIRIX** (~3.6k★): most-cited academic typed multi-store — six memory types
  each with a manager agent. Demonstrates the cognitive taxonomy taken
  literally.
- **ByteRover / Cipher** (~4.9k★): codebase-memory category leader among MCP
  tools — local file memory with built-in version control, cross-tool via MCP.
- **Basic Memory** (~3.4k★): local-first human-editable markdown knowledge
  graph (Obsidian-compatible), SQLite-indexed, over MCP. Maximal portability,
  single-user.
- **MemoryOS** (EMNLP 2025): OS-style short/mid/long tiers with heat-based
  promotion/eviction — reference design for heuristic forgetting.
- **Memory-R1** (ACL 2026): RL-trained memory manager (ADD/UPDATE/DELETE/NOOP)
  with outcome rewards — forgetting as a *learned* policy, from 152 training
  pairs.
- **The 2026 code-KG MCP wave** (CodeGraph, codebase-memory-mcp,
  code-graph-mcp…): pre-indexed *structural* code graphs became table stakes —
  three independent implementations trended in one week of May 2026. These are
  derived, regenerable indexes; they answer "what calls X", not "what did we
  decide about X".
- **MemPalace** (Apr 2026): the cautionary tale — ~42k allegedly purchased
  stars, "benchmark" shown to be unmodified default ChromaDB, public walk-back.
  Stars and self-run scores in this category are gameable.

-----

## 3. Capability gaps: who ships what

The functional capabilities that matter most, and everyone who actually ships
them (not announced, not papered):

| Capability | Who ships it | Who doesn't |
|---|---|---|
| **Bi-temporal validity with as-of/history queries** | Zep/Graphiti (filters), **claimgraph** (first-class), ctxgraph (early), OpenAI cookbook (blueprint) | every built-in harness, Mem0, Letta, Cognee, supermemory, LangMem |
| **Epistemic typing** (decided vs observed vs preferred, driving behavior) | **claimgraph** (3 classes → conflict policy), OpenAI cookbook (blueprint only) | everyone else; nearest fragments: supermemory's down-weighted "Derives", Zep's typed entities |
| **Contradiction → conflict surfacing for humans** (not silent resolution) | **claimgraph** (commitments flag, never auto-resolved), engram (judge classifies, human reviews) | everyone else silently overwrites, silently coexists, or does nothing |
| **Retrieval-time validation of memory against ground truth** | **GitHub Copilot only** (repo facts re-validated against current branch) | everyone — claimgraph's analogue is write-time reconciliation on `ingest-code`, not read-time |
| **Mechanical (no-LLM) invalidation from code changes** | **claimgraph** (reconciling code ingester), Aider (by recomputation, ephemeral), Copilot (validation gate) | all other persistent systems |
| **Principled forgetting** | Copilot (28-day unused TTL), **claimgraph** (disuse half-life + reinforcement ceilings, commitments exempt), agemem (deterministic eviction), Vertex/AgentCore/LangGraph (plain TTL), Mem0 (soft ranking bias) | Zep (nothing), Letta, Cognee (usage reweighting only), all other built-ins; learned eviction (Memory-R1) not in any product |
| **Automatic background extraction** | Claude Code, Codex, Copilot, Windsurf, Mem0, Zep, Cognee (opt-in), supermemory, Vertex, AgentCore, ctxgraph | Cursor (removed it), Gemini CLI, Letta (by design), Anthropic memory tool (by design), **claimgraph** (session-extract is invoked, not ambient) |
| **Provenance with citations/evidence** | Copilot (citations), Codex (evidence files), Zep (episodes), **claimgraph** (episodes + source-type + confidence), Anthropic memory stores (actor + versions), AgentCore (consistent metadata), memledger (cross-agent derivation chains) | all file-pile systems, Mem0, A-MEM (actively destroys it) |
| **Chain/derivation confidence propagation** (effective confidence bounded by ancestors, not just the stored number) | **memledger only** (weakest-link: min over the derivation chain) | everyone — claimgraph derives effective confidence too, but from **time decay + per-source ceilings**, not chain ancestry; the two are orthogonal |
| **Consolidation pass** | Codex (dedicated model), Letta (sleep-time agents), supermemory, Cognee (memify), **claimgraph** (`consolidate`), Vertex/AgentCore, LangMem | Cursor, Windsurf, Gemini, Copilot, Zep (pipeline-inline only) |
| **Structured + experiential in one store** | **claimgraph** (code facts + decisions/preferences in one graph) — the open problem per OSS Insight's field survey; engram is experiential-only, the code-KG wave is structural-only | essentially everyone |
| **Team sharing with governance** | Copilot (repo memories + admin controls), Cursor Team rules, Zep ABAC, hyperscalers (IAM), Cognee RBAC, **memledger (Namespace RBAC — the first OSS entrant)** | most local-first systems including claimgraph (ACL fields reserved, unenforced) |
| **Codebase-memory benchmark** | **claimgraph** (own seed: shoply fixture, 15 mechanics Qs + LLM layer), a handful of 2026 preprints (SWE Context Bench, Memory Transfer Learning) | no LoCoMo/LongMemEval-equivalent standard exists yet |

**What *nobody* ships**, anywhere in the field:

- A first-class `as_of=` parameter on a hosted memory API (Graphiti gets
  closest via filter composition).
- Memory and permissions in the same graph (the synthesis doc's §6.4
  prediction) — ACLs where they exist are bolted onto the platform layer.
  memledger's Namespace RBAC is the closest OSS attempt, but it's a governance
  layer *over* the store, not permissions expressed as edges in the memory graph.
- Portable agent identity — Letta's `.af` has no verified external adoption;
  the memory-layer standard remains unwritten.
- Learned eviction in production — Memory-R1 (ACL 2026) exists, no product
  uses it.
- Read-time validation *plus* temporal history *plus* epistemics in one
  system. Copilot has the first, claimgraph the second and third.

-----

## 4. Where claimgraph stands

### Differentiated (nothing else in the survey has the combination)

1. **Epistemic typing driving conflict policy.** The only *shipped* system
   where "we decided against GraphQL" is a different class of thing from "the
   code imports X", with different revision behavior (flag vs supersede). The
   OpenAI cookbook designed this; claimgraph runs it.
2. **Bi-temporal + as-of + history as first-class query verbs.** Zep has the
   model but exposes it as filters over four timestamps; claimgraph's
   `--as-of` / `history` are the direct answer to "what did we believe in
   March, and why did it change."
3. **The three-way conflict discipline**: mechanical reconciliation (code
   re-ingestion invalidates what the code stopped saying), deterministic
   exclusion groups at write time, LLM judge + sweep offline — with genuine
   contradictions *always* escalated to the human. Copilot silently drops
   stale facts; Mem0 lets old and new coexist; claimgraph is the only one that
   treats a contradicted commitment as a question for its owner.
4. **Forgetting that distinguishes disuse from falsity.** Disuse decay as a
   computed view with reinforcement ceilings (a fact re-derived 500× stays
   below a human decision) is more principled than any shipped TTL, and
   commitments are exempt — Copilot's 28-day TTL would happily forget an
   unreferenced architectural decision.
5. **Structural + experiential in one graph.** The 2026 field split cleanly
   into structural code indexes (regenerable, no decisions) and experiential
   memory (decisions, no code grounding). claimgraph's code ingester and
   session extractor write into the same bi-temporal store — the combination
   the field survey called the open problem.
6. **Standards-anchored vocabulary.** No surveyed system anchors its relation
   taxonomy to PROV-O/SPDX/DOAP — still true a year after the handoff doc
   claimed it as a differentiator.
7. **A codebase-memory benchmark at all.** The validation gap the handoff doc
   identified ("no LongMemEval for codebases") remains open field-wide;
   claimgraph's `bb bench` is a seed of exactly that, and the 2026 preprint
   activity (SWE Context Bench et al.) confirms the gap is real and current.

### Behind the field

1. **No ambient write path.** Claude Code, Codex, and Copilot extract
   automatically while you work; claimgraph's `session-extract` must be invoked
   (the skill mitigates, but it's judgment-dependent). The premier harnesses
   won the "zero effort" property in 2026.
2. **No auto-injection read path.** Everything arrives via explicit
   agent-driven query. That's a deliberate design (query > inject), but the
   field's evidence (Letta's filesystem benchmark; Copilot's validated
   injection) says the default-on read path is what makes memory actually get
   used.
3. **No MCP front-end yet** (TODO) — in a year where cross-tool MCP memory
   became the norm for every system in §2.3's traction list.
4. **Ingestion breadth.** Code ingester is Clojure-only; failure and ADR
   ingesters (the procedural-memory growth path) are still TODO.
5. **Single-writer, no ACL enforcement, no team story** — fine for the stated
   beachhead, but the field's governed tier (Copilot, Zep, hyperscalers) shows
   where enterprise demand went — and as of July 2026 that tier has its first
   OSS entrant, memledger (Apache-2.0), which packages Namespace RBAC, RTBF
   cascades, and an attribution-integrity eval. The governed tier is no longer
   hosted-only, so "we're local-first, governance is someone else's layer" is a
   weaker answer than it was a year ago.
6. **No independent validation.** Everyone's benchmark numbers are self-run —
   but claimgraph's are too, and it has ~0 users. The credibility bar in this
   field is now high (see §5).

### Strategic reads, updated for July 2026

- **The local-owned-codebase niche is still open, but no longer empty.**
  engram (~5k★ in five months) is the closest neighbor: codebase-experiential,
  local, single-binary, conflict-surfacing, subscription-as-judge — it
  validated four of claimgraph's bets simultaneously. It lacks temporality,
  epistemics, confidence, decay, and any code grounding. The differentiation
  holds; the "nobody is here" claim doesn't.
- **The files+agency camp is ascendant and is the real competitor** — not the
  platforms. Letta's pivot, Anthropic's memory tool and file-memory-trained
  models, memU, Basic Memory: the field's momentum says "give the agent a
  filesystem and let it curate." The markdown pile got a promotion. What that
  camp still cannot answer: "what did we believe in March," "is this a
  decision or an observation," "what contradicts what." Those remain
  claimgraph's case, and Copilot Memory's validation gate is the only
  mass-market acknowledgment that the pile's staleness problem is real.
- **Mem0's v3 retreat is evidence for conservative scope.** The
  highest-funded player removed graph traversal *and* write-time conflict
  resolution for latency and simplicity. Rich semantics only survive if the
  write path stays cheap — claimgraph's "LLM never on the write path" rule and
  mechanical-first ingestion are the right instincts, confirmed.
- **Auto-memory is now table stakes in harnesses; interop is the wedge.**
  Every premier harness ships its own silo (local, unshared, unstructured,
  no temporality). None validates, none types, none remembers *why*. The
  practical positioning: claimgraph doesn't compete with auto-memory for
  ambient capture — it's the structured, queryable layer those captures
  should consolidate *into* (the "eat their output" strategy from the
  synthesis doc, now with more outputs to eat).

-----

## 5. Benchmarks and the credibility crisis

The evaluation story changed materially in 2025-26 and it matters for how any
claim in this space is read:

- **Conversational benchmarks saturated and broke.** Five vendors
  simultaneously claim LoCoMo "SOTA" (Mem0 92.5, memU 92.09, MemOS 88.83,
  MIRIX 85.4, Memobase ~85 — all self-run ⚠). A LoCoMo audit found ground-truth
  errors in ~6.4% of questions. The Mem0-vs-Zep dispute (each alleging the
  other misconfigured their system) is unresolved on both sides. Letta showed
  a trivial filesystem agent scores 74% on LoCoMo — arguing the benchmark
  can't differentiate memory systems at all.
- **MemPalace** faked ~42k stars and a benchmark that was unmodified default
  ChromaDB. Treat stars and self-run scores as marketing until independently
  replicated — which, for essentially every headline number in this document,
  they have not been.
- **Codebase-scoped memory evaluation is nascent**: SWE Context Bench
  (arXiv 2602.08316), Memory Transfer Learning (arXiv 2604.14004), "Code Isn't
  Memory" (arXiv 2606.22417), a GitHub production A/B (+7 pts PR merge rate
  with memory ⚠), and self-published harness experiments. No standard exists.
  claimgraph's benchmark seed is honest about being one system's fixture; the
  TODO's "consider generalizing to a multi-system harness only after it proves
  out here" is the right sequencing — a *neutral* codebase-memory benchmark is
  arguably the highest-leverage artifact anyone could ship in this field right
  now.

-----

## 6. Corrections to our earlier research

Things the branch's synthesis/handoff docs said that the July 2026 sweep
revises:

1. **The three-camps table needs a fourth, dominant camp.** Vector/RAG,
   knowledge-graph, hierarchical/OS-style — add **files+agency** (Letta
   MemFS, Anthropic memory tool, Claude Code auto memory, memU, Basic
   Memory). It's not the markdown pile: the agent curates, consolidation
   exists, and models are being trained for it. It is, however, still
   structurally blind on temporality, epistemics, and contradiction.
2. **Mem0 no longer belongs in the graph conversation.** v3 (Apr 2026)
   removed graph memory and write-time contradiction resolution — the two
   features its 2025 paper marketed. The synthesis doc's "bad at
   contradictions, last-write-wins" critique of the vector camp is now
   *conceded by the camp's leader*.
3. **Cursor regressed.** The synthesis-era assumption that auto-memory only
   accumulates proved wrong: Cursor shipped Memories (Jun 2025) and removed
   them (~Nov-Dec 2025) in favor of manual rules — the only vendor to walk
   auto-memory back.
4. **The forgetting literature moved into products, barely.** Copilot's
   28-day TTL is the first mass-market forgetting; Memory-R1 got into ACL
   2026 but no product ships learned eviction. §6.1's "under-solved" verdict
   stands.
5. **Provenance got its first hosted wins.** Copilot citations +
   re-validation, Anthropic memory stores' immutable versions + actor
   provenance, AgentCore's strictly-consistent metadata. §6.3's core claim
   (observed vs inferred vs hallucinated is untracked) still holds — nobody
   types the *epistemic status* of a memory — but source-tracking is no
   longer rare.
6. **supermemory re-open-sourced** (MIT local server, Jun 2026), weakening
   the handoff doc's "opposite pole on owned-vs-hosted" framing — though the
   extraction models stay proprietary, so inspectability remains the divide.
7. **spec-kit became infrastructure** (~119k★ ⚠, 30+ agent integrations).
   The "eat their output" strategy now has a much bigger output stream: an
   ADR/constitution ingester (TODO) would plug directly into the largest
   authoring pipeline in the field.
8. **The reference-repo table gained a member.** ctxgraph and engram both
   proved out; the new one to watch is the code-KG MCP wave — structural
   indexes are becoming free/commodity, which *raises* the value of the
   experiential+temporal layer they don't attempt.
9. **The governed tier got an OSS entrant, and it corroborates four bets.**
   memledger (Apache-2.0 alpha `v0.5.0a0`, `memledger-ai` org, Jul 2026) is a
   trust/governance *layer* between agents and a vector store — a different game
   than claimgraph (multi-agent conversational/RAG memory + compliance, not a
   codebase temporal KG). It independently arrived at four primitives this doc
   tracks: **provenance chains, effective-confidence-as-derived-not-stored,
   conflict-as-edge (CONFLICTS) rather than overwrite, and forgetting-with-an-
   audit-trail (RTBF cascades).** Same signal engram gave — these are the right
   primitives — now from the governance/RAG direction. Its confidence model
   (weakest-link over the derivation chain) and forgetting model (RTBF cascade)
   are *orthogonal* mechanisms to claimgraph's (time decay + per-source ceilings;
   non-lossy disuse decay), reached for different reasons. What it does **not**
   have: bi-temporality, as-of/history, or epistemic typing — claimgraph's two
   strongest differentiators survive intact. What it **does** have that claimgraph
   doesn't: a shipped OSS governed tier (Namespace RBAC + OpenTelemetry) and an
   attribution-integrity eval (MAI) wired into RAGAS. It is alpha and
   server/Postgres-based, so treat maturity claims with the §5 caution.
10. **A database arrived with this doc's temporal semantics built in — and
    was evaluated and declined as a backend** (2026-08-06). NornicDB
    (github.com/orneryd/NornicDB, MIT, Go, ~838★) is a graph database
    *server* — Bolt/Cypher + Qdrant-gRPC compatible, HNSW + BM25 hybrid
    retrieval, MVCC — shipping **native tritemporal facts** (valid,
    transaction, *and decision* time, with as-of reads) and policy-based
    Ebbinghaus-style memory decay, pitched explicitly at AI-agent memory.
    That makes it the first *database* observed with first-class axis-5
    semantics, and strong corroboration of revision #8: the storage
    primitives are commoditizing, so the judgment layer above them is where
    the value concentrates. Evaluated as a Datalevin replacement and
    **decided against**: it is a daemon (binary/Docker/Helm — memledger's
    same objection) where claimgraph's premise is an embedded, per-project,
    inspectable store; it would replace the bi-temporal indexing that
    already works while the actual differentiators (epistemic classes →
    conflict policy, confidence, provenance, audit) stay application logic
    regardless of store; its in-database intelligence (auto-relationship
    generation, LLM reranking, in-store decay) sits on the wrong side of
    the no-LLM-on-write-path and forgetting-we-control constraints; bus
    factor is one (maintainer publicly soliciting co-maintainers, benchmarks
    self-reported); and the one real gap it would close — vector retrieval
    for the roadmap's retrieval phase — Datalevin's built-in vector + FTS
    indexes already cover in-process. Kept as a design reference: the
    decision-time third axis and the decay-policy API are worth mining for
    claimgraph's own history and forgetting verbs.

-----

## 7. Sources

Repo-internal: `docs/agent-memory-synthesis.md`, `docs/claimgraph-handoff.md`,
`README.md`, `TODO.md`, `.claude/skills/claimgraph/SKILL.md`.

External (fetched 2026-07-09; abbreviated — key primary sources only):

- Claude Code memory docs (code.claude.com/docs/en/memory) + changelog;
  Anthropic memory tool (platform.claude.com/docs/…/memory-tool); context
  management announcement (anthropic.com/news/context-management)
- Codex memories docs + changelog (developers.openai.com/codex/memories,
  /changelog, /use-cases/follow-goals); agents.md
- GitHub Copilot Memory concept docs (docs.github.com/en/copilot/concepts/agents/copilot-memory)
  + github.blog changelogs 2025-12-19, 2026-01-15, 2026-03-04, 2026-04-02
- Cursor rules docs + 2.1 changelog + forum threads on Memories removal
- Devin Desktop (ex-Windsurf) memories/rules docs (docs.devin.ai)
- Gemini CLI GEMINI.md + memory tool docs (google-gemini.github.io)
- Cline Memory Bank docs (docs.cline.bot/features/memory-bank)
- Aider conventions + repo map docs (aider.chat)
- Mem0 v3 migration/decay/graph docs (docs.mem0.ai); arXiv 2504.19413
- Zep/Graphiti docs (help.getzep.com; github.com/getzep/graphiti);
  arXiv 2501.13956; the Zep–Mem0 benchmark dispute posts
- Letta pivot ("Our Next Phase", letta.com/blog, 2026-03-16); sleep-time
  compute (arXiv 2504.13171); filesystem-memory benchmark post
- Cognee 1.0 announcement (cognee.ai); supermemory docs + research posts;
  LangMem docs (langchain-ai.github.io/langmem)
- Vertex AI Memory Bank docs (docs.cloud.google.com); AWS AgentCore Memory
  docs + GA announcement
- engram (github.com/Gentleman-Programming/engram); ctxgraph
  (github.com/rohansx/ctxgraph); agemem (github.com/gianpd/agemem); A-MEM
  (arXiv 2502.12110, NeurIPS 2025); OpenAI temporal-agents cookbook;
  spec-kit (github.com/github/spec-kit)
- memledger (fetched 2026-07-20): memledger.com + /docs; pypi.org/project/
  memledger (`v0.5.0a0`, Apache-2.0); `memledger-ai` GitHub org (memledger-core
  SDK/MCP/Helm, memledger-ui trust graph, memledger-docs); author
  github.com/ratnopamc
- NornicDB (fetched 2026-08-06): github.com/orneryd/NornicDB (`v1.2.1`, MIT
  + defensive patent grant); dbdb.io/db/nornicdb; maintainer call
  dev.to/orneryd/im-looking-for-a-small-number-of-maintainers-for-nornicdb-2pn6
- Memory-R1 (arXiv 2508.19828, ACL 2026); MemoryOS (arXiv 2506.06326);
  "Forgetful but Faithful"/FiFA (arXiv 2512.12856); LoCoMo; LongMemEval
  (+V2 arXiv 2605.12493); MemoryBench (github.com/supermemoryai/memorybench);
  SWE Context Bench (arXiv 2602.08316); "Code Isn't Memory"
  (arXiv 2606.22417); OSS Insight "Agent Memory Race of 2026" (2026-04-13)

⚠ Reminder: benchmark figures throughout are vendor-self-reported unless
stated otherwise; star counts are point-in-time and, per the MemPalace
incident, gameable.
