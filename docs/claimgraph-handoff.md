# claimgraph — Prototype Handoff Document

A bi-temporal, epistemically-typed knowledge graph for AI coding-agent memory.
This document captures the architectural decisions, their rationale, the current
design state, and the open questions. It is the build-oriented companion to the
conceptual doc (`agent-memory-synthesis.md`).

Working name: **claimgraph** (placeholder).

-----

## 1. Purpose & scope

The problem: coding-agent memory today is a pile of auto-generated markdown
(the notes a harness accumulates on its own between sessions) that collapses
working/episodic/semantic/procedural memory into one undifferentiated
bucket, with no structured retrieval, no invalidation, no epistemic typing,
no provenance, and no way to answer “what do we currently believe about X”
vs “what did we believe last month.” `CLAUDE.md`/`AGENTS.md`/ad-hoc notes
are a separate, human-maintained artifact.

The target: a **structured, self-invalidating, queryable graph** that remembers
a codebase’s preferences, standards, idioms, design decisions, architecture
records, and conventions — at the level of the codebase across sessions, not a
single session. Owned, portable, inspectable. Not a hosted service.

First beachhead: a single developer’s single codebase. Single writer, consult a
handful of times per session, write occasionally.

-----

## 2. Current status

Design stage. Settled: language/runtime, storage engine, layering, data model,
operation surface, conflict semantics, predicate vocabulary + registry,
interface strategy. Not yet built. Open forks listed in §10.

*(v0 has since been built from this document; see the README for what shipped
and the deviations taken.)*

-----

## 3. Core architectural decisions

Each decision below is stated with its rationale and the alternatives weighed,
so the reasoning survives even if the conclusion is later revisited.

### 3.1 Language / runtime: Babashka (Clojure)

**Decision.** Implement as a Babashka CLI tool.

**Rationale.** Ergonomics the author actually wants to work in; fast-start
native binary (no JVM boot tax per invocation); EDN/Clojure data model is a
natural fit for a datom-shaped store with no serialization impedance; Datalog
queries are far cleaner than recursive SQL for the traversal workload.

**Constraints accepted.** Babashka runs on SCI (Small Clojure Interpreter), not
full Clojure. The main incompatibility is that SCI cannot implement *Java
interfaces* on `defrecord`/`deftype`. Our design stays clear of that (see §8).

### 3.2 Storage: Datalevin via Babashka pod

**Decision.** Use Datalevin as the storage engine, accessed as a Babashka pod
(`pod.huahaiy.datalevin`), behind a storage protocol.

**Rationale.** The earlier objection to Datalevin was “it drags in a JVM,”
which conflicts with the single-binary aesthetic. The pod path inverts that:
`dtlv` is *already* a GraalVM-compiled native binary that also speaks the pod
protocol, auto-downloadable from the pod registry. So we get the Datomic-family
data model (datoms, Datalog, schema-on-write, EDN in/out), built-in full-text
search, and even an MCP server in the same binary — without a JVM at runtime.
Two fast-start native binaries (our CLI + the pod), not a JVM process.

**Alternatives weighed (and why not):**

- **SQLite + recursive CTEs** — the incumbent, and still the portability/ubiquity
  champion (the store is one universally-readable file; `ctxgraph` is a working
  reference impl in our exact domain). Rejected as the *primary* engine only
  because, given we’re writing Babashka, Datalog ≫ recursive CTEs for
  expressiveness and the datom model removes the ORM layer. **Kept as the
  fallback backend** behind the storage protocol, and as the likely target of
  the future `dump` command.
- **KuzuDB** — purpose-built embeddable graph DB, technically excellent, but
  **archived/abandoned by its sponsor Oct 2025**. Disqualified on maintenance
  risk.
- **CozoDB** — best philosophical fit (embeddable Datalog + native time-travel
  `Validity` + pluggable backends), but **no release since Dec 2023**, never hit
  1.0. Disqualified on dormancy.
- **Oxigraph** — the strongest “keep a `.ttl` on disk” option (Rust, SPARQL,
  Turtle dump/load), but forces full RDF, has awkward bi-temporal ergonomics,
  and stores to a RocksDB directory rather than one file. Overkill.
- **XTDB** — the only option with *native* bi-temporality, but JVM/server-
  oriented and heavy. Violates portability.
- **DuckDB + DuckPGQ** — columnar OLAP engine; wrong write profile for an agent
  constantly updating individual facts.

**Portability mitigation.** Datalevin’s store is LMDB (a directory), less
universally readable than a `.sqlite` file. We accept this because (a) the
storage protocol keeps SQLite a live fallback, and (b) a future `dump` command
will export to a common format (JSON-lines / SQLite / Turtle). Portability is
preserved as an *exportable property*, not a property of the live engine.

### 3.3 Layering: storage-agnostic core behind a `Store` protocol

**Decision.** Three layers — CLI/skill front-end → storage-agnostic core
operations → `Store` protocol → Datalevin implementation. The core layer never
sees a datom, a Datalog query, or a `:db/`-anything; it speaks plain Clojure
maps and calls the protocol.

**Rationale.** This is the seam that makes the storage engine swappable (the
whole reason we can pick Datalevin now without betting the project on it). It
also gives a trivially mockable boundary for tests (an in-memory map-backed
`reify Store`). Protocols are the one place the author considers them clearly
warranted, and SCI supports plain Clojure protocols fully.

```
CLI / skill front-end       (arg parsing, JSON in/out, --pretty)
        │
   core operations          (assert-fact, get-neighborhood, …) — storage-agnostic
        │  ┌──────────────┐
        └──┤ Store protocol│  ← the swappable seam
           └──────────────┘
        │
   datalevin impl           (pod calls; only layer that knows Datalog/EDN)
```

### 3.4 Interface: skill + CLI now, MCP later

**Decision.** Ship as an Agent Skill wrapping a CLI. Keep the core operations as
a clean Clojure namespace so an MCP server is a thin second front-end later.

**Rationale.** For a single consumer (the author’s coding agent), MCP’s
advantages (cross-app reuse, typed schemas, warm connection) don’t yet apply,
while a CLI + skill is radically less infrastructure, trivially inspectable, and
composable with bash/git. The skill is also the natural home for the *usage
judgment* (when to consult memory, when to write, how to phrase queries, which
epistemic class to assign) — an MCP server has nowhere to put that. `dtlv`
already bundles an MCP server, so the migration is cheap when warranted.
Real-world validation: `engram` and `supermemory` both ship skill + MCP as thin
front-ends over a core.

**Migration trigger.** Move to MCP when the graph is large enough that
cold-start per query hurts, or query patterns become iterative enough that many
calls happen per turn.

### 3.5 Bi-temporal modeling: explicit columns, not engine-native

**Decision.** Model valid time (`t-valid`/`t-invalid`) and transaction time
(`recorded-at`) as explicit attributes on each fact. Never hard-delete;
invalidation closes the `t-invalid` interval.

**Rationale.** Neither Datalevin nor SQLite is natively bi-temporal (Datalevin’s
own docs point to XTDB for that). Modeling it explicitly keeps the temporal
logic in *our* code, identical in shape across backends — which is exactly what
a clean storage swap needs. Non-lossy invalidation (set `t-invalid`, don’t
delete) is the Graphiti/ctxgraph/Datomic pattern and gives us `get-history` and
`as-of` queries for free.

### 3.6 Facts as reified edges carrying a metadata bundle

**Decision.** A fact is a first-class entity, not a bare triple. It carries:
subject, predicate, object (entity-ref or literal), `t-valid`, `t-invalid`,
`recorded-at`, `confidence`, `source` (episode), `source-type`, `epistemic`,
`scope`, and conflict links.

**Rationale.** The rich per-edge metadata is the whole point — it’s what
markdown can’t represent. Reifying the edge is the same move RDF-star and
Datomic’s reified transactions make; in a datom store it’s natural. (`read-acl`
/`write-acl` are intentionally *omitted from the live surface* but should be
carried as nullable from day one — retrofitting an ACL dimension into queries
later is far more painful than carrying unused fields now. Governance is real,
just not prototype-urgent.)

### 3.7 Object kind: entity OR literal (RDF-style)

**Decision.** A fact’s object is either an **entity** (traversable, terminal
node you can walk into) or a **literal** (terminal value you cannot). A
discriminator (`object-kind`) plus `object-ref` / `object-lit` distinguishes
them. Traversal queries only follow entity-kind objects.

**Rationale.** Mirrors RDF: subjects/predicates are always references, only
objects may be literals. Lets preferences/conventions live as first-class facts
(`AuthService prefers "Result-types"`) without minting junk entities, while
keeping graph traversal clean. Enforced at write time (see §6) so traversal is
guaranteed honest.

### 3.8 Controlled predicate vocabulary with a self-describing registry

**Decision.** A curated core of 23 predicates (`:core/*`), each a first-class
queryable row in the same store carrying its own definition, category, object-
kind, cardinality, status, inverse, default epistemic class, and a `maps-to`
projection note to an established standard (PROV-O / SPDX / DOAP / SKOS / Dublin
Core). New/experimental predicates must be coined in an `:x/*` namespace with
`:testing` status, and can be promoted to `:core/*` once proven.

**Rationale.** A controlled core buys reliable querying; the namespaced staging
area plus promotion workflow buys safe extensibility (the schema.org
pending→core pattern). Making predicates first-class data is Datomic’s “schema
as data” — the vocabulary is queryable with the same Datalog as everything else,
needs no separate config system, and is self-describing. Anchoring to standards
via `maps-to` buys future RDF/PROV-O interoperability essentially for free
without adopting RDF machinery now. This is a differentiator: none of the
surveyed agent-memory systems anchor their relation taxonomy to formal
standards.

### 3.9 Conflict policy derived from epistemic class

**Decision.** When a new fact contradicts a currently-valid fact with the same
(subject, predicate) but a different object, the resolution policy defaults from
the fact’s epistemic class:

- **observation → supersede** (close the old interval, insert the new — clean
  non-lossy invalidation)
- **commitment → flag** (insert the new fact AND return the conflicting fact
  ids; do *not* silently overwrite a human decision)
- **preference → supersede** (with history retained)

The caller may override via `on-conflict {:supersede|:flag|:ignore}`.

**Rationale.** Code-derived observations *should* auto-update when the code
changes; human commitments (“we decided against GraphQL”) should *never* be
silently clobbered by new evidence — they should surface for review. Deriving
the default from epistemic class means the predicate you choose sets the default
revision behavior (pick `:core/supersedes` → flag semantics; pick
`:core/depends-on` → clean supersession). The vocabulary encodes the judgment.

-----

## 4. Data model (Datalevin schema)

```clojure
(def schema
  {;; ---- Entity ----
   :entity/id        {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :entity/name      {:db/valueType :db.type/string}
   :entity/type      {:db/valueType :db.type/keyword}  ; :service :module :file :decision :person …
   :entity/scope     {:db/valueType :db.type/string}

   ;; ---- Fact (reified edge + metadata bundle) ----
   :fact/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :fact/subject     {:db/valueType :db.type/ref}      ; -> entity (traversable)
   :fact/predicate   {:db/valueType :db.type/keyword}  ; -> :predicate/id
   :fact/object-ref  {:db/valueType :db.type/ref}      ; -> entity ─┐ one of
   :fact/object-lit  {:db/valueType :db.type/string}   ; literal   ─┘
   :fact/object-kind {:db/valueType :db.type/keyword}  ; :entity | :literal
   :fact/t-valid     {:db/valueType :db.type/instant}
   :fact/t-invalid   {:db/valueType :db.type/instant}  ; absent => currently valid
   :fact/recorded-at {:db/valueType :db.type/instant}  ; transaction time
   :fact/confidence  {:db/valueType :db.type/double}
   :fact/source      {:db/valueType :db.type/ref}      ; -> episode
   :fact/source-type {:db/valueType :db.type/keyword}  ; :code :user-assertion :inferred :decision-record :session-log
   :fact/epistemic   {:db/valueType :db.type/keyword}  ; :observation :commitment :preference
   :fact/scope       {:db/valueType :db.type/string}
   :fact/conflicts   {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   ;; reserved-but-unused (carry from day one): :fact/read-acl :fact/write-acl

   ;; ---- Episode (provenance anchor) ----
   :episode/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :episode/source-type {:db/valueType :db.type/keyword}
   :episode/ref         {:db/valueType :db.type/string}  ; git SHA, session id, file path
   :episode/summary     {:db/valueType :db.type/string}
   :episode/opened-at   {:db/valueType :db.type/instant}

   ;; ---- Predicate registry (self-describing vocabulary) ----
   :predicate/id          {:db/valueType :db.type/keyword :db/unique :db.unique/identity} ; :core/… or :x/…
   :predicate/label       {:db/valueType :db.type/string}
   :predicate/category    {:db/valueType :db.type/keyword} ; :structural :procedural :decision :provenance
   :predicate/object-kind {:db/valueType :db.type/keyword} ; :entity | :literal | :either
   :predicate/cardinality {:db/valueType :db.type/keyword} ; :one | :many
   :predicate/inverse-of  {:db/valueType :db.type/keyword}
   :predicate/status      {:db/valueType :db.type/keyword} ; :stable | :testing | :deprecated
   :predicate/replaced-by {:db/valueType :db.type/keyword}
   :predicate/definition  {:db/valueType :db.type/string}
   :predicate/maps-to     {:db/valueType :db.type/string}  ; "prov:wasRevisionOf" etc.
   :predicate/default-epistemic {:db/valueType :db.type/keyword}
   :predicate/alt-labels  {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}})
```

Note on transaction time: Datalevin logs additions but is not a full time-travel
store, so `recorded-at` is stored explicitly rather than relying on the engine —
keeping the logic identical across backends.

-----

## 5. Operation surface

### 5.1 The `Store` protocol (the swappable seam)

```clojure
(defprotocol Store
  (-ensure-entity     [s name type scope])
  (-assert-fact       [s fact])                 ; returns {:fact-id … :candidates [...]}
  (-get-facts         [s entity opts])          ; opts {:scope :predicate :as-of :include-invalidated :min-confidence}
  (-get-neighborhood  [s entity opts])          ; opts {:max-depth :as-of :scope :predicate-filter :min-confidence}
  (-get-history       [s subject predicate])
  (-invalidate        [s fact-id at reason])
  (-ingest            [s episode-id facts])      ; batch assert, one transaction
  (-open-episode      [s source-type ref])
  (-close-episode     [s episode-id summary])
  (-get-predicate     [s pred-id])
  (-list-predicates   [s opts])                  ; opts {:category :status}
  (-register-predicate [s pred-map]))
```

### 5.2 Core operations (storage-agnostic)

- `assert-fact` — validates the predicate (existence, not-deprecated,
  object-kind match) with a `:did-you-mean` fuzzy suggestion on unknown
  predicates; resolves epistemic class (caller > predicate default >
  `:observation`); applies the conflict policy from §3.9; returns status +
  `candidates` on the flag path.
- `get-facts` — currently-valid facts about an entity (or as-of a timestamp),
  filtered by scope/predicate/confidence.
- `get-neighborhood` — BFS expansion to `max-depth`, temporally and
  confidence-filtered as plain Clojure predicates over pulled maps. (For the
  prototype, one Datalog query per depth level, in-process behind the persistent
  pod — cheap at our scale.)
- `get-history` — all versions of a (subject, predicate), valid + invalidated,
  time-ordered. The single best demonstration of why this beats markdown.
- `ingest` — batch assert under one episode in a single transaction (a
  half-ingested session is worse than none).
- `open-episode` / `close-episode` — provenance anchor; close is the
  consolidation hook.
- `decay` / `consolidate` — defined in the surface, stubbed for MVP (decay =
  drop confidence on un-referenced facts; consolidate = write episode summary).

### 5.3 CLI verbs (thin shell over the core)

```
claim assert   --subject … --predicate … --object … --class observation --scope module:auth
claim facts    --entity AuthService --as-of 2026-03-01
claim neighbor --entity AuthService --depth 2
claim search   "redis migration"
claim history  --subject AuthService --predicate depends_on
claim ingest   --episode <id> --file facts.jsonl
claimgraph decay
claim consolidate
```

All commands emit JSON to stdout (`--pretty` for humans) so the same output is
consumable by the author at a terminal, by the skill via bash, and by a future
MCP wrapper.

-----

## 6. Predicate registry — seed vocabulary (23)

Blessed `:core/*` predicates, by category, each with object-kind, cardinality,
default epistemic class, and a `maps-to` standard mapping. The two inverse
rows named in passing below — `dependency-of` and `superseded-by` — are seeded
predicates in their own right, which is how the four categories reach 23
(structural 9, procedural 5, decision 6, provenance 3; see
`claimgraph.predicates/seed`).

**Structural:** `depends-on` (↔ `dependency-of`, SPDX DEPENDS_ON, observation),
`imports` (CodeOntology, observation), `defined-in` (SPDX
CONTAINED_BY, observation), `contains` (↔ `part-of`, SPDX CONTAINS /
dcterms:hasPart), `part-of` (dcterms:isPartOf), `implements` (DOAP/SEON, observation),
`written-in` (DOAP programming-language; either), `has-version` (dcterms:hasVersion;
literal).

**Procedural:** `tested-by` (SPDX hasTest, observation), `built-with` (SPDX
BUILD_DEPENDENCY_OF), `generated-from` (SPDX GENERATED_FROM / prov:wasGeneratedBy),
`deployed-via` (LOCAL), `failure-mode` (LOCAL, literal — added with the failure
ingester).

**Decision/preference:** `supersedes` (↔ `superseded-by`, prov:wasRevisionOf /
dcterms:replaces, commitment), `decided-against` (LOCAL/ADR, either, commitment),
`prefers` (LOCAL, either, preference), `motivated-by` (prov:wasInfluencedBy),
`has-status` (LOCAL/ADR, literal).

**Provenance:** `derived-from` (prov:wasDerivedFrom / dcterms:source),
`asserted-by` (prov:wasAttributedTo), `primary-source` (prov:hadPrimarySource /
dcterms:provenance).

**Validation path** enforces: predicate exists; not deprecated; object-kind
matches (`:either` accepts both). Unknown predicates throw with a fuzzy
`:did-you-mean` — the cheap defense against LLM-driven predicate proliferation
(the Mem0/Cognee failure mode), turning silent drift into self-correction.

**Health/promotion.** A Datalog query counts usages per `:x/*` predicate; unused
ones are drop candidates, heavily-used ones are promotion candidates. Promotion
registers the `:core/*` twin as `:stable`, rewrites `:x/*` facts, and deprecates
the staging term with a `:replaced-by` pointer (never deletes).

-----

## 7. Roadmap (deferred, captured as vision)

- **Pluggable LLM-judge for semantic steps.** A dependency-injection seam on the
  semantic-conflict path of `assert-fact` and on `consolidate`: an optional
  `judge(fact-a, fact-b) -> {relation, confidence}`. Default impl shells out to
  an already-authenticated agent CLI (engram’s “subscription-as-judge” — uses
  the Claude/Codex subscription you already pay for, ~$0 marginal). MVP ships
  mechanical-only; the seam means no API change when the judge arrives.
- **Candidates-in-the-response (flag path).** Already in the `assert-fact`
  return shape: on `:flag`, return `{:status :flagged :fact-id … :candidates
  [conflicting-ids]}`. MVP populates `candidates` from mechanical detection;
  judge enriches later.
- **`dump` command.** Export the Datalevin store to a common format (JSON-lines
  / SQLite / Turtle) for portability and as the SQLite-backend migration path.
- **Vector/semantic search.** Datalevin has SIMD vector search; defer until
  keyword (FTS) + graph retrieval proves insufficient.
- **Multi-source ingesters.** Mechanical code-analysis (AST, no LLM) for the
  bulk; session-log extractor (LLM) for preferences/decisions; decision-record
  ingester (highest authority); failure ingester (procedural memory from
  reverted/rejected work).
- **ACL/governance tier.** Activate the reserved `read-acl`/`write-acl` fields;
  graph-based entitlements when multi-user/enterprise.
- **Consolidation (Dreaming-style).** Offline pass: promote repeated patterns to
  procedural memory, reconcile, decay, summarize.

-----

## 8. Babashka / Datalevin implementation constraints

Verified working, with caveats to respect:

- **Protocols & records work in SCI.** `defprotocol`/`defrecord` are implemented
  via multimethods and regular maps. Our `Store` protocol + `DatalevinStore`
  record is the canonical supported case.
- **The one hard limit:** SCI cannot implement *Java interfaces* on
  records/types. We never do this — `Store` is a pure Clojure protocol (no
  `IFn`/`IAtom`/`java.io.*`). Keep it that way.
- **Use options maps, not arity overloading.** Cleaner design and sidesteps any
  multi-arity edge cases. Already how the surface is designed (`opts` maps).
- **`reify` works for one protocol at a time** — fine for an in-memory mock
  `Store` in tests, with no pod required.
- **The pod is a serialization boundary.** Every pod call marshals args/results
  (transit/EDN). Fine for our access pattern (a few reads, occasional writes);
  push whole Datalog queries across and get result sets back rather than
  chattily looping. The pod is a persistent process for the script’s lifetime,
  so the “warm vs cold” concern only reappears if a fresh `bb` script is spawned
  per agent action.
- **`defpodfn`** is required to define custom functions used *inside* a Datalog
  query across the pod boundary (can’t pass arbitrary inline closures). Rarely
  needed for this workload; relevant only for fancy in-query scoring.
- **Pull patterns across refs** (`{:fact/subject [:entity/id]}`) are where early
  debugging time goes — ergonomic once it clicks, fiddly the first hour.
- **No native path-pattern queries.** Datalog gives clean *neighborhood*
  expansion but “paths where every intermediate is a Service” is still manual,
  same as SQL. If path-constrained queries become central, that’s a revisit
  signal (and the protocol seam contains the blast radius).
- **Version pinning.** Not every Datalevin release is in the pod registry; pin
  to a registered version. (v0 sidesteps the registry entirely: it loads the
  pinned `dtlv` binary from `$PATH` / `$CLAIMGRAPH_DTLV`.)

-----

## 9. Reference implementations surveyed

Six data points triangulating the design space; each nails one axis, none has
the full stack. The synthesis target is their union.

|Project                              |Nails                                                                                                                  |Lacks                                                                                                               |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
|**GitHub spec-kit**                  |Authoring discipline; typed docs; human-ratified “constitution” as commitment root                                     |Maintenance, invalidation, consolidation (write-once-by-decree)                                                     |
|**git + markdown** (LinkedIn pattern)|Substrate philosophy: owned, portable, vendor-neutral; git = free transaction-time                                     |Structure, invalidation, consolidation, retrieval                                                                   |
|**agemem** (gianpd)                  |Control architecture: deterministic floor + LLM judgment + salience; double-boundary overflow guard                    |Graph model; vendor-coupled; trivial retrieval                                                                      |
|**ctxgraph** (rohansx)               |Graph storage model in our exact domain: recursive CTEs, bi-temporal-ish edges, fusion retrieval, single SQLite file   |Confidence/entailment/source weighting; closed-corpus assumption                                                    |
|**engram** (Gentleman-Programming)   |Product engineering: one-binary multi-front-end (CLI/HTTP/MCP/TUI), git sync, conflict surfacing, subscription-as-judge|Bi-temporality; epistemic typing; graph traversal (note-shaped, not triple-shaped)                                  |
|**supermemory**                      |Commercial polish; SOTA benchmarks; MemoryBench harness; memory-not-RAG framing; auto-forgetting                       |The opposite pole on every axis we chose: hosted (not owned), user-profiles (not codebase), opaque (not inspectable)|

Key strategic read: the most successful player (supermemory) is pointed the
opposite direction on owned-vs-hosted, codebase-vs-user, and inspectable-vs-
opaque — evidence the local-owned-codebase niche is genuinely open. Steal
engram’s subscription-as-judge and conflict-surfacing; steal ctxgraph’s storage
model; don’t compete with supermemory’s hosted personalization market.

Also noted: **there is no LongMemEval/LoCoMo equivalent for *codebase* memory.**
That’s both a validation obstacle (how do we prove it works?) and possibly a
thing worth building.

-----

## 10. Open decisions (the forks)

1. **Materialize inverse facts vs compute at query time.** When asserting
   `A depends-on B`, also write `B dependency-of A`, or compute inverses in the
   traversal query? Leaning **compute-at-query-time** (the
   `(or [?f :fact/subject ?e] [?f :fact/object-ref ?e])` pattern) — less to keep
   consistent on invalidation, and the `inverse-of` registry field documents the
   relationship without stored twins. Cost: every traversal checks both
   directions. *(v0 resolution: compute-at-query-time, as leaned.)*
1. **`has-status` as a predicate (literal-valued fact) vs an attribute on the
   decision entity.** Leaning **predicate**: keeps everything uniform and
   bi-temporal (status history for free), and a decision moving accepted →
   superseded *is* exactly the belief revision the system exists to track. Means
   status changes flow through the conflict machinery — arguably correct.
   *(v0 resolution: predicate, as leaned.)*
1. **Entity resolution (`ensure-entity`).** Prototype = exact name+scope match
   or create. But this is where real-world messiness concentrates: renames,
   moves, splits (`UserService` → `UserReadService` + `UserWriteService`). It
   deserves to be its own operation now even with a trivial body, because fuzzy
   matching / alias handling / split-identity logic will eventually live here.
   **Next design target.** *(v0: own operation with the trivial body, as specified.)*

-----

## 11. Companion documents

- `agent-memory-synthesis.md` — the conceptual landscape: OS/HTTP/cognitive-
  science lenses, the three architectural camps, the declarative shift, the
  unsolved problems (forgetting, belief revision, provenance, ACLs), the
  end-state schema.
- *(this doc)* `claimgraph-handoff.md` — the build: decisions, rationale, schema,
  operation surface, registry, constraints, open forks.
- `memory-systems-comparison.md` — the field comparison (July 2026): updates
  and corrections to the survey tables in §9 here and §9 of the synthesis doc.

-----

## 12. Suggested next steps

1. Resolve forks #1 and #2 (inverses; `has-status`). ✅ (v0)
1. Design `ensure-entity` and the entity-resolution seam (fork #3). ✅ seam only (v0)
1. Stand up the Datalevin pod + schema + seed predicates; get `assert-fact` and
   `get-facts` working end-to-end behind the protocol. ✅ (v0)
1. Add `get-neighborhood` and `get-history`; validate the Datalog traversal
   ergonomics on real data. ✅ (v0)
1. Build the mechanical (no-LLM) code-analysis ingester — it produces the bulk
   of facts and replaces ~70% of what goes in CLAUDE.md today. ✅ Clojure-only (v0)
1. Wrap in a skill; dogfood on this very project’s codebase. ✅ (v0)
