# claimgraph and the field

This chapter condenses the July 2026 field survey
([`docs/memory-systems-comparison.md`](https://github.com/kirahowe/claimgraph/blob/main/docs/memory-systems-comparison.md),
which carries the full tables, capsule reviews, and sources). Scores are
functional: what a system can do for its user, not how it is built. A
standing caveat travels with every row: benchmark figures in this field are
vendor-self-reported unless stated otherwise, star counts are gameable, and
at least one prominent system was caught faking both.

Legend: ● shipped and strong, ◐ partial, ○ absent.

| System | Domain | Structure | Bi-temporal history | Contradictions | Epistemics & provenance | Forgetting | Ownership |
|---|---|---|---|---|---|---|---|
| **claimgraph** | codebase | reified-edge KG, 23-predicate vocabulary | ● first-class as-of and history | ● class-driven policy; commitments flag, never auto-resolved | ● classes + confidence + source trust + episodes + raw evidence | ● disuse half-life, ceilings, commitment exemption | local files, two-way dump |
| Claude Code auto-memory | codebase + user | markdown files | ○ | ○ (docs warn contradictions behave arbitrarily) | ○ timestamps only | ○ size-cap compaction | local |
| OpenAI Codex Memories | codebase + user | markdown + summary | ○ | ◐ consolidation rewrites, no explicit logic | ◐ evidence links to threads | ○ | local |
| GitHub Copilot Memory | codebase + user | structured entries + citations | ◐ lifecycle timestamps | ● re-validates against the branch; stale facts silently unused | ● citations; validation as confidence gate | ● 28-day unused TTL | GitHub-hosted, non-portable |
| Cursor (rules) | codebase | markdown rules | ○ | ○ | ○ | ○ | local + team-hosted |
| Mem0 (v3) | user/conversation | fact strings + vectors (graph removed) | ◐ change log, no as-of | ◐ write-time resolution removed; recency ranking | ○ | ◐ expiry; soft ranking decay | OSS core + hosted |
| Zep / Graphiti | user + business data | temporal KG | ● strongest hosted bi-temporal (filters, not first-class as-of) | ● LLM-compared edges, non-lossy invalidation | ◐ episode provenance, no confidence or classes | ○ | cloud (self-host deprecated) |
| Letta (MemFS) | coding + assistants | markdown + git, anti-graph by design | ● git versioning, no as-of API | ○ editing files | ○ (git blame implicitly) | ○ char limits | your git repo |
| Cognee | documents/org | graph + vector + relational | ◐ event-time only | ○ (open issue) | ◐ document lineage, no confidence | ◐ manual forget, usage reweighting | OSS, local mode |
| supermemory | user + docs | vectors + claimed graph | ◐ versioning, no as-of search | ● supersession edges, old retained | ◐ inferred memories down-weighted | ◐ soft-forget; decay claimed, undocumented | OSS server, proprietary models |
| LangMem / LangGraph | user/conversation | JSON docs + vectors | ○ timestamps | ◐ LLM reconciliation | ○ | ◐ TTL (platform) | OSS, portable JSON |
| engram | codebase (experiential) | typed notes, SQLite | ◐ timestamps, soft-delete | ● conflict surfacing with judge | ◐ session provenance, no confidence | ○ manual | single binary, git sync |
| A-MEM (research) | conversational | self-modifying note network | ○ | ◐ rewrites neighbors in place, destroying provenance | ○ | ○ | research code |

## Where claimgraph is differentiated

Five combinations that, per the survey, no other shipped system has:

1. **Epistemic typing that drives behavior.** Several systems track *where*
   a memory came from; none but claimgraph types *what kind of claim it is*
   and lets that type decide revision policy. "We decided against GraphQL"
   flagging instead of superseding is the only shipped implementation of
   the pattern the [OpenAI temporal-agents cookbook](https://developers.openai.com/cookbook/examples/partners/temporal_agents_with_knowledge_graphs/temporal_agents) designed.
2. **First-class time travel.** Zep has the strongest hosted bi-temporal
   model but exposes it as timestamp filters. `--as-of` and `history` as
   direct verbs, answering "what did we believe in March and why did it
   change," have no shipped equivalent.
3. **Conflict surfacing for humans.** The field's default on contradiction
   is silent: overwrite (files), coexist (vectors), or auto-resolve (LLM
   comparison). claimgraph and engram are the only surveyed systems that
   treat a genuine contradiction as a question for a person, and only
   claimgraph composes that with temporality and trust.
4. **Structural and experiential memory in one store.** 2026 split cleanly
   into regenerable code indexes (no decisions) and experiential stores (no
   code grounding). The code ingester and the session/notes extractors
   write into the same bi-temporal graph, which the field survey called the
   open problem.
5. **Forgetting that distinguishes disuse from falsity.** Copilot's 28-day
   TTL would happily forget an unreferenced architectural decision;
   claimgraph's decay is a read-time view with reinforcement ceilings and
   commitment exemption, and falsity is handled by a different mechanism
   entirely (mechanical invalidation).

Two more are worth naming: the cross-harness story (Claude Code and Codex
notes about the same repo consolidating into one graph, which nothing else
attempts) and the local-first multi-writer design (append-only per-writer
logs with conflict-surfacing reconciliation; the survey found no memory
system with any multi-machine story beyond hosted sync).

## Where the field is ahead

Honesty section. GitHub Copilot's retrieval-time validation (it "checks
those citations against the current branch to confirm the information is
still accurate", per its
[memory documentation](https://docs.github.com/en/copilot/concepts/agents/copilot-memory))
has no claimgraph equivalent; the code ingester reconciles at write time,
which is weaker at the moment of use. The governed tier (Copilot, Zep, the hyperscalers)
ships team sharing and access control that claimgraph deliberately defers
(ACL fields are carried but unenforced). The hosted platforms ship
polished automatic extraction at a scale of engineering the pluggable
`claude -p` subprocess does not pretend to match. And the survey's two
"behind the field" findings from July 2026 (no ambient capture, no
auto-injection) were real then; the ambient loop closed both, but that
closure is one repo's implementation against features the harnesses ship
to millions of users.

## What nobody ships

Still true across the whole survey, and worth keeping as a map of open
territory: a first-class `as_of` parameter on a hosted memory API; memory
and permissions in the same graph; a portable agent-identity format with
real adoption; learned eviction in production; and any one system
combining read-time validation, temporal history, and epistemics. Copilot
has the first, claimgraph the second and third.
