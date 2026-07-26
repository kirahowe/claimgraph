---
name: claimgraph
description: Consult and maintain this project's knowledge graph — a bi-temporal, epistemically-typed memory store. Use when starting work on an entity (query what's known), when the user states a preference or decision (record it with the right epistemic class), when asked "why" or "since when" about the codebase (history), or at the end of a substantial session (ingest what was learned).
---

# claimgraph — the project's structured memory

This repo carries a queryable knowledge graph instead of relying on markdown
piles. Every fact is bi-temporal (valid time + record time), epistemically
typed (observation / commitment / preference), confidence-weighted, and
provenance-anchored to an episode. The CLI is `bin/claim`; all output is
JSON (add `--pretty` when showing a human). Paths and settings resolve
flag > env > `.claimgraph/config.json` > default — `bin/claim config` shows
every setting, its value, and where it came from.

## When to READ

- **Before modifying an entity** (service, namespace, module): check what's known.
  ```
  bin/claim facts --entity AuthService
  bin/claim neighbor --entity AuthService --depth 2
  ```
- **Before proposing architecture or a library**: check for standing decisions.
  A `decided-against` or `prefers` fact outlives any code state — respect it
  or explicitly surface that you're proposing to revisit it.
  ```
  bin/claim search "GraphQL"
  bin/claim facts --entity api-layer --predicate decided-against
  ```
- **When asked "why" / "since when" / "what changed"**: use history and as-of.
  ```
  bin/claim history --subject AuthService --predicate depends-on
  bin/claim facts --entity AuthService --as-of 2026-03-01
  ```
- **Reverse lookups** ("what depends on X?"): `--direction in`.

## When to WRITE

Choose the epistemic class deliberately — it sets the conflict behavior:

| Class | Use for | On contradiction |
|---|---|---|
| `observation` | code-derived or verifiable facts | supersedes silently (old version kept in history) |
| `preference` | style/idiom/tool preferences | supersedes silently |
| `commitment` | human decisions ("we decided against X") | **flags** — never silently overwritten |

- **User states a preference**: record it immediately.
  ```
  bin/claim assert --subject <scope-entity> --predicate prefers \
    --object "small focused PRs" --class preference
  ```
- **User makes or reports a decision**: record as commitment, ideally with
  `--source-type decision-record`.
  ```
  bin/claim assert --subject api-layer --predicate decided-against \
    --object GraphQL --class commitment --source-type decision-record
  ```
- **End of a substantial session**: extract durable knowledge from the
  transcript (preferred — review with `--dry-run` first):
  ```
  bin/claim ingest-session --file transcript.txt --ref <session-id> --dry-run
  bin/claim ingest-session --file transcript.txt --ref <session-id>
  ```
  (`session-extract` is the older name for this verb and still works.)
  Or batch hand-written facts under one episode (JSONL via stdin or file,
  snake_case or kebab-case keys, `class` = epistemic class):
  ```
  bin/claim ingest --source-type session-log --ref <session-id> --file facts.jsonl
  ```
- **After structural refactors**: refresh the mechanical layer:
  ```
  bin/claim ingest-code
  ```
  Analyzes every language the adapter registry detects — Clojure, Kotlin,
  TypeScript/JavaScript (via a pinned dependency-cruiser; needs npx, skips
  with a hint without it) — in one pass; `code-analyzers` in
  `.claimgraph/config.json` overrides, disables, or adds analyzers.
  Idempotent: unchanged facts no-op; a unit that moved files supersedes its
  old `defined-in`; facts about deleted code are invalidated; imports that
  can't be resolved locally become external-scoped facts, never wrong local
  edges. The ambient loop runs this at session end too (delta-gated on the
  git SHA, so it's free when nothing changed) — a manual pass is only
  needed mid-session, right after a big refactor.
- **Periodically (or after ingesting)**: run the consolidation pass:
  ```
  bin/claim consolidate
  ```
  Closes open episodes with summaries (making episodic history searchable),
  reviews and sweeps conflicts, and surfaces `x/*` predicates worth promoting.
  (Forgetting needs no pass: confidence decays by disuse, computed at read
  time; re-asserting a fact reinforces it.)

## The ambient loop (zero-effort floor)

The capture/injection loop can run itself: `bin/claim hooks install` wires
a SessionEnd hook so every session ends with `hooks run` —
`ingest-code-if-changed` first (the mechanical code pass, delta-gated on
`<git-sha>+<dirty-digest>` against the last code episode: free when nothing
changed, reconciling when anything did, including teammates' pulled
changes; opt out with the `code-ingest: manual` setting), then
`ingest-notes` (the harness's auto-memory notes, delta-detected, ingested
as inference-grade `agent-note` facts) then `compile-context` (the graph's
current view written into the managed section of the file the harness
auto-injects into the next session), with `consolidate` running when due
(default: weekly). Every location involved is configurable — the notes
dir, the inject file, the hook-settings file — see `bin/claim config`.

The hook is the floor beneath this skill, not a replacement for it:
note-derived facts are second-class evidence (capped 0.65, never
commitments), so **genuine decisions still need the direct path** — when the
user decides something, `assert --class commitment` it yourself; when you
need history, provenance, or time-travel, query — the injected view only
carries the headlines. Never edit the marker-delimited managed section of
the inject file by hand; it is regenerated on every compile and never
re-ingested.

## Handling responses

- `status: flagged` means the new fact contradicts a standing commitment. Do
  NOT pick a winner yourself — show the user the `candidates` and ask which
  holds. Resolve with `bin/claim invalidate --fact-id <loser> --reason "..."`
  or re-assert with `--on-conflict supersede` once the user rules.
- Conflicts accumulate across sessions: `bin/claim conflicts` lists what's
  open. `bin/claim judge` classifies each pair (contradicts / duplicate /
  supersedes / compatible); add `--resolve` to auto-close the easy ones —
  contradictions always remain for the user to decide.
- `did-you-mean` on an unknown predicate: use the suggestion if it matches your
  intent. For a genuinely new relation, coin it in the staging namespace:
  `--predicate x/my-relation` (auto-registers with :testing status). Never
  invent `core/*` predicates.
- Predicates: `bin/claim predicates --usage` lists the vocabulary. Prefer
  `core/*` predicates; bare names like `depends-on` resolve to `core/*`.

## Entity hygiene

- Lookups are forgiving: aliases and case/separator variants resolve
  automatically (`auth-service` finds `AuthService`), so don't create
  near-duplicate entities on purpose — reuse existing names.
- When the code renames or restructures things, curate the graph:
  `bin/claim entity rename --from X --to Y` (history intact, old name
  aliased), `entity merge --from X --into Y` for accidental duplicates,
  `entity split --from X --into "A,B"` to record lineage. Check
  `bin/claim entity duplicates` when things look doubled.

## Phrasing facts well

- Subject and entity-objects are graph nodes: name them like stable entities
  (`AuthService`, `claimgraph.core`, `ADR-7`), not sentences.
- Free-text goes in literal objects: `--object "idempotency keys on retries"`.
- Scope facts when they're not project-wide: `--scope module:auth`.
- Confidence: default 0.8 is right for user assertions; use 0.95+ only for
  mechanically verified facts, 0.5-0.6 for your own inferences (and
  `--source-type inferred`).
