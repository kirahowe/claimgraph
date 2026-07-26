# CLI reference

Every command accepts `--db PATH` and `--pretty`. Output is JSON on stdout —
except `dump`, which writes JSONL, `mcp`, which speaks JSON-RPC, `coach
--hook`, which prints nothing when the gate holds, and `audit`, which shows a
human scorecard at a terminal. Errors are JSON on stderr, and there are three
exit codes rather than one, because a typo and a failure want different
responses: `0` succeeded, `1` ran and could not do what it was asked, `2` was
a command line claimgraph could not act on at all. A judge that could not
reach its model is worth retrying; a misspelled verb in a hook never is.

Every setting resolves flag > env var > `.claimgraph/config.json` > default;
`claim config` shows each one's value and provenance. `bin/claim help` is the
authoritative, always-current version of this list.

## Onboarding and configuration

| Command | Does |
|---|---|
| `audit` | Consistency scorecard over the agent-memory pile (CLAUDE.md, AGENTS.md, rules files, auto-memory notes), pre-install: contradictions, disagreements, staleness-vs-code, restatements, name clusters, injection bloat — verbatim quote receipts, throwaway in-memory store, nothing written, no dtlv; `--no-code`, `--no-judge`, `--out` |
| `setup` | One-shot project onboarding, idempotent: store + seed, config persisted, live store gitignored, agent skill installed, ambient loop wired; `--dry-run`, `--mcp` |
| `config` | Every setting: resolved value, the layer that set it, and the fully resolved paths |

## Store and writes

| Command | Does |
|---|---|
| `init` | Create the store and seed the 23-predicate vocabulary (`setup` calls this). Re-seeding reconciles an existing store's rows to the current vocabulary, including fields the seed has dropped — this is the upgrade path |
| `version` | The release, the persisted-format version, and the source sha when running from a checkout (marked `dirty` when the tree is modified) — what a bug report should quote |
| `assert` | One fact through validation and conflict resolution: `--subject --predicate --object`, with `--class`, `--source-type`, `--confidence`, `--scope`, `--valid-from/--valid-until`, `--on-conflict` |
| `invalidate` | Close a fact's validity interval: `--fact-id`, `--reason`, `--at` (when it stopped being true) |
| `ingest` | Batch-assert JSONL (file or stdin) under one episode |

## Reads

| Command | Does |
|---|---|
| `facts` | Facts about an entity; `--as-of`, `--direction out\|in\|both`, `--predicate`, `--min-confidence` (effective), `--include-invalidated` |
| `history` | Every version of (subject, predicate), valid and invalidated, ordered |
| `search` | Hybrid full-text, entity, and neighborhood retrieval, rank-fused |
| `neighbor` | BFS to `--depth`; with `--query` it becomes the evidence-guided walk (`--beam`, `--budget`) |
| `recall` | Sufficiency escalation: facts, then episode summaries, then raw evidence; reports which tier answered |
| `coach` | Gated push: interrupts only when the graph holds something that bears on the task; `--hook` for Claude Code hook wiring |
| `conflicts` | Open conflicts awaiting a ruling |
| `stats` | Store counts |

## Entities and predicates

| Command | Does |
|---|---|
| `entity ensure / list / rename / alias / merge / split / duplicates` | Curation: renames keep old names as aliases with history intact; merge repoints and collapses non-lossily; split records lineage |
| `predicates` | List the vocabulary (`--usage` shows what earns its place) |
| `predicate register` | Coin an `x/*` staging predicate |
| `predicate promote` | Graduate `x/*` to `core/*`: register, rewrite facts, deprecate with a forwarding pointer |

## Ingestion tiers

| Command | Does |
|---|---|
| `ingest-code` | Mechanical multi-language analysis through the adapter registry (Clojure via edamame, Kotlin via line parse, TS/JS via pinned dependency-cruiser, `code-analyzers` config for your own); reconciling, no LLM, 0.95 confidence; `--language` filters to one analyzer; missing tooling skips with a hint |
| `ingest-session` | LLM extraction from transcripts; capped 0.7, `--dry-run`, pluggable `--extractor`. `session-extract` is the older name and still works |
| `ingest-notes` | The ambient tier: harness auto-memory, delta-detected, capped 0.65, never commitments |
| `ingest-adr` | Mechanical decision-record parsing at full authority |
| `ingest-failure` | Lessons from rejected work; failure modes, valence, evidence kept |

## Episodes and evidence

| Command | Does |
|---|---|
| `episode open / close / list` | Provenance anchors; closing attaches the searchable summary |
| `evidence` | The raw bytes an episode was extracted from, by episode or hash |

## Maintenance and automation

| Command | Does |
|---|---|
| `consolidate` | Offline pass: summarize episodes, judge, sweep, enrichment, promotion review |
| `judge` | Classify open conflicts; `--resolve` acts on high-confidence verdicts, never on contradictions; `--sweep` generates candidates |
| `outcome` | `accepted` reinforces everything retrieved since the last mark; `rejected` reports it |
| `compile-context` | Write the graph's current view into the harness's injection file |
| `hooks install / run` | Wire and run the ambient SessionEnd loop: `ingest-code-if-changed` (delta-gated on `<git-sha>+<dirty-digest>`; `--code-ingest manual` opts out) → `ingest-notes` → `compile-context` → consolidate-when-due (`--coach` adds the prompt-time gate) |
| `dump` / `load` | JSONL export and exact restore (the committable, portable artifact) |
| `reconcile` | Apply other writers' effect logs; idempotent |
| `mcp` | Serve the graph over MCP stdio |

## Environment

| Variable | Meaning |
|---|---|
| `CLAIMGRAPH_DB` | Default store path |
| `CLAIMGRAPH_DTLV` | Path to the Datalevin pod binary (otherwise `$PATH`) |
| `CLAIMGRAPH_LLM_CMD` | Default extractor and judge command (`claude -p`) |
| `CLAIMGRAPH_CODE_INGEST` | The ambient code stage: `session-end` (default) or `manual` |
| `CLAIMGRAPH_WRITER` | This machine's writer id for the effect log |
| `CLAIMGRAPH_TEST_SKIP_DATALEVIN=1` | Run the test suite pod-free |
| `CLAIMGRAPH_BENCH_STORE=memory` | Run benchmark mechanics pod-free |
