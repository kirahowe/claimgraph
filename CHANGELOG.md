# Changelog

What changed in each release, for someone deciding whether to upgrade and what
it will cost them.

claimgraph carries two version numbers on two clocks. `release` is what a bug
report quotes. `format-version` is the one integer stamped into every artifact
a second machine has to read — the dump's header record, every oplog line, the
store's `<db>.version` sibling, `:config-version` in the project config — and
it moves only when an old reader would get a *new* file wrong. A release that
leaves it alone cannot break a file an older claimgraph already holds, which is
the whole reason they are separate numbers (`src/claimgraph/version.clj`).

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0-alpha] — 2026-07-26

**format-version: 1.** The first stamped format, not a bump. Everything written
before this release is *format 0*, which is not a format anyone designed — it
is the name for "written before stamping existed".

The first release carrying a version number at all. Most of it is not new
capability: it is the pass that made the shipped surface say what it actually
does and refuse what it cannot do. Two of the fixes below are silent data
corruption, so if you already have a store or a dump, read **Upgrading** before
anything else.

### Upgrading from pre-alpha

**A pre-alpha dump is refused, not loaded.** `claim dump` stamped each record's
kind onto `:type` — the key an entity's own
wire shape already owns — so an entity of type `service` was written as
`"type":"entity"`, and rehydration then stripped the key it had just read. The
types were destroyed at write time; nothing in the file can recover them.
`claim load` now identifies such a file (`:type :dump-pre-alpha`) and refuses
before it writes a single record. The remedy is to re-dump from the store that
produced it — the store still holds the types, only the dump lost them. Kinds
now live under `:record`, where nothing else lives.

**Every pre-alpha copy of a timestamp is truncated to the second.** Cheshire's
default `java.util.Date` encoder wrote `yyyy-MM-dd'T'HH:mm:ss'Z'` and dropped
the milliseconds. The store always held the full precision; every encoded copy
— the dump, the oplog, CLI and MCP output — lost it. A fact asserted and
superseded inside the same second therefore came back from a round trip with
`t-valid` equal to `t-invalid`: an interval valid at no instant at all, so the
fact vanished from every as-of view instead of reading as the short-lived claim
it was, and `recorded-at` ties stopped ordering deterministically. Pre-alpha
dumps are refused anyway; pre-alpha *oplog* lines still replay, and the facts
they carry restore with second precision. Nothing can reconstruct the lost
digits — the store that wrote them still has them.

**An unstamped store is stamped in place, once, on open.** `<db>.version` is a
new sibling file holding `{"format":1,"version":"0.1.0-alpha"}`. Format-0 and
format-1 stores are byte-identical in shape, so nothing about your data moves.
The stamp is a sibling and not a datom because Datalevin merges a schema on
open — a stamp living inside the store would be written by the very call meant
to check it. A store stamped *above* this build is now refused
(`:unsupported-format`) instead of opened and silently read through a whitelist
that drops what it does not know; a stamp this build cannot read is refused
(`:unreadable-format-stamp`) and never overwritten. Known cost: `cp -r db/`
alone leaves the stamp behind and the copy reads as unstamped. Copy the
siblings, or re-open once and let it re-stamp.

**Oplog lines written before the stamp still replay.** They carry no `:format`,
which reads as 0, and the compatibility path that strips the old `:type`
discriminator is gated on the line actually declaring format 0 — stripping it
on sight would re-introduce the untyping bug on the reading side, since
`:entity` is as legal an entity type as `:service`.

**`$CLAIMGRAPH_WRITER` no longer wins on every call.** `<db>.oplog/writer` *is*
this machine's identity once it exists; the environment variable only seeds a
store that has none. Letting it win split one machine into two logical writers
the moment the variable was set, unset or edited, after which the machine met
its own earlier log as a stranger's. A disagreement is warned about on stderr
and ignored.

**Supersessions recorded as prose still appear in the briefing.** Before this
release a superseded fact recorded its successor in a sentence. "Changed
recently" now selects on the structured `:invalidation-kind`, so every
supersession already in a user's store would have silently vanished from that
section on upgrade. A bounded fallback reads the old sentence, and only when
there is no kind — anchored and single-token, so a human's
`--reason "superseded by a better plan"` is prose and not a link. Every write
since carries a kind, so the shim can only ever fire on rows that predate it,
and it is deletable the day no store does.

**Datalevin dumps taken before this release are missing every alias.**
`-list-entities` projected four attributes and omitted `:entity/aliases`, and
`dump` reads entities through it, so a restore answered "not found" for every
name the graph had learned. Re-dump; the store has the aliases.

**Evidence artifacts stored under a bare 64-hex name still resolve.** New
pointers are `sha256-<hex>`; `fetch` reads both spellings and `write!` treats
either as already-stored, so the first write into an upgraded store does not
lay down a second identical copy. Episodes recorded before the change keep
their bare pointers — nothing rewrites them.

**Re-run `claim setup`.** It rewrites the `.gitignore` block as a
marker-delimited managed region (absorbing every legacy unmarked block into
one), adds `<db>.version` to it, and stamps `.claimgraph/config.json` with
`"config-version": 1`. A notes directory chosen during an earlier onboarding
also starts taking effect: `setup` persisted it under `notes-dir` while every
consumer read `dir`, so the choice was written to a key nothing looked at.

**`claim init` on an existing store now re-seeds it.** The seed reconciles a
predicate row to the seed's shape including removals, but only an empty store
was ever seeded, so the reconciliation was unreachable and an upgrade kept the
old vocabulary forever. Facts are untouched.

### Added

- **`claim version`** — prints `release` and `format-version`, plus the commit
  sha when claimgraph runs from its own git checkout (marked `dirty` when the
  tree is modified, since `bin/claim` always runs from a tree the user can
  edit). The sha is located from where the namespace loaded and never from the
  cwd, so `claim version` inside a user's project never reports that project's
  sha; the key is absent, not null, when there is no checkout to ask.
- **A header record on every dump.** `{"record":"claimgraph-dump","format":1,"version":"0.1.0-alpha"}`,
  with no timestamp and nothing machine-specific, so two dumps of an unchanged
  store are byte-identical and a committed dump diffs to nothing.
- **`memory_neighbor`** — graph expansion over MCP, which was CLI-only. The
  roster is eight tools, and the naming rule is now stated as a rule: each tool
  is `memory_` plus its CLI verb.
- **Valid time and seven other arguments on `memory_assert`**, which
  advertised seven properties and now advertises sixteen. `valid_from` and
  `valid_until` came first: an agent driving claimgraph over MCP could not
  record "true from January to March" in a project whose whole point is
  bi-temporality. Then `scope`, `subject_scope`, `object_scope`,
  `subject_type`, `object_type`, `episode` and `on_conflict`. A lone `scope`
  now applies to the fact *and* the entities it mints, so a write no longer
  lands in a scope whose entities live in another one.
- **`:invalidation-kind` and `:successor` on a fact.** Seven kinds, each with a
  producer a test now requires to exist; the reason sentence stays for humans
  and nothing parses it. The set is deliberately open — an unrecognised kind
  from a newer writer is kept verbatim and matches nothing, which is why adding
  one is additive and does not move `format-version`.
- **Epistemic escalation.** Re-asserting a fact with an explicitly *stronger*
  class supersedes rather than reinforces, so history reads observation, then
  commitment. The check keys off whether the caller stated a class, never the
  resolved value — the registry fills in a default, so comparing resolved
  values would make every ingest pass supersede its own facts and grow the
  store without bound.
- **A heartbeat on the write lease**, renewing while the operation runs.
  `consolidate` and `hooks run` shell out to an LLM dozens of times per pass
  (~51 prompts measured at scale) against a fixed 30s TTL, so a second writer
  correctly concluded the lease had expired and both walked into the
  read-decide-write cycle the lease exists to serialize. Renewal is
  token-guarded and refuses to renew an already-expired lease. Losing the lease
  is raised after the body runs, carrying the body's own result.
- **A timeout on every LLM call** — 120000 ms by default, `--llm-timeout-ms` or
  `$CLAIMGRAPH_LLM_TIMEOUT_MS` to override, taking the child's whole process
  tree with it. One wedged call used to eat the SessionEnd hook's entire budget.
- **`audit --scorecard`** for the human-readable score, and a stated precedence
  (`--json` > `--scorecard` > `--pretty`) enforced in one place.
- **A `LICENSE`** — MIT. Unlicensed public code is all-rights-reserved by
  default, so nobody could legally use the alpha.
- **Homebrew CI on macOS** — the install route the README recommends was
  exercised nowhere. The ubuntu job keeps covering the pinned-download
  fallback; the jobs are named `ubuntu (pinned downloads)` and
  `macOS (Homebrew)`, which a required-status-check rule has to match. The book
  is now built on PRs, not only on push to main.

### Changed

- **`session-extract` is now `ingest-session`** — it was the only ingestion
  tier not named `ingest-*`. The old name still dispatches, because it is in
  installed `SKILL.md` files, in the README, and in hook command lines already
  on disk; it is never advertised, and the `:expected` list on an unknown-verb
  error is canonical verbs only.
- **`--dir` had five meanings and now has one per verb**: `--project` on
  `ingest-code` (where `--project` was accepted and silently ignored),
  `--adr-dir` on `ingest-adr`, `--scan-dir` on `audit` (repeatable), and
  `--notes-dir` everywhere else. `--dir` is kept as a documented alias wherever
  it worked, folded on before the environment and config file are consulted so
  an explicit legacy flag still outranks `$CLAIMGRAPH_NOTES_DIR`. On `audit`,
  `--dir` and `--scan-dir` both add; neither shadows the other.
- **Three exit codes instead of one.** 0 success; 1 the command ran and could
  not do what it was asked (the JSON-on-stderr contract, now including `setup`
  when it is `:blocked`, having wired nothing); 2 the command line itself was
  wrong. An unrecognised verb used to print help to *stdout* and exit 0, so a
  typo in a SessionEnd hook read as success. A judge that could not reach its
  LLM is worth retrying and a misspelled verb never is, which is why they no
  longer arrive under one number.
- **`entity ensure`, `predicate register` and `episode open`** answer
  `{:status … :entity/:predicate/:episode {…}}` like every other verb, instead
  of a bare object. `episode open` was flattened and renamed its `:id` to
  `:episode`; the row's own id now reads as `.episode.id`.
- **`neighbor --query`** answers in a strict superset of the BFS shape rather
  than a different one: `entities` and `depth` are present, and facts carry
  both `walk-score` and `effective-confidence`. Hop distance comes from the
  round the walk *discovered* a node in rather than being reconstructed from
  the facts it returned — a round-2 fact routinely outscores the round-1 fact
  that reached its endpoint, so the linking fact is exactly what the budget
  truncates and depth read null for most entities. This reached MCP too.
- **MCP accepts both argument spellings.** Schemas advertise snake_case,
  kebab-case is accepted; output stays kebab and unnormalised, matching the CLI
  and the dump. A client used to write `min_confidence` and read
  `effective-confidence` from the same tool.
- **`dump --out` reports `:lines` beside `:records`** — the graph is what
  `load` reports back, the file is what `wc -l` says — and `:format`.
- **`reconcile` only advances a writer's high-water mark past effects that
  settled** (`:applied` or `:duplicate`). A verb the reader does not
  understand, a prerequisite that has not synced, or a throw now stays in front
  of the mark and is retried, instead of being skipped permanently with the
  only trace being that the totals no longer added up. Effects applied ahead of
  a hole are remembered by number so nothing is applied twice, and a hole is
  reported as `:seq-gap` rather than stepped over.
- **The oplog envelope carries `writer` and `format`.** Identity lives in the
  line and not the filename, so a log survives being renamed, copied or
  restored under another name, and a machine recognises its own effects inside
  a foreign file instead of replaying its history at itself. A line stamped
  above the reader's format is held — neither applied nor skipped past.
- **`append!` runs after the store write it describes**, and logs that write's
  own return value: a mutation that threw locally must not reach machines that
  would apply it happily. A failure to append now warns on stderr instead of
  being swallowed.
- **The consolidation stamp's payload is authoritative**, with the file's mtime
  kept only as a fallback for a payload that cannot be believed. The gate used
  the mtime, and every way a store travels — rsync, a fresh checkout, a backup
  restore, the file syncer this project supports for the oplog — rewrites it
  without any consolidation having happened.
- **`core/defined-in` no longer claims an inverse.** It named `contains`, which
  already named `part-of`, so `contains` had two claimants and the relation was
  not bijective. Nothing consumes `:inverse-of` yet — inverses are computed at
  query time — which is exactly why this was free to fix now and expensive once
  the vocabulary is seeded into everyone's store. The seed is 23 predicates.
- **The managed-section markers are frozen.** `strip-managed-section` tolerates
  a versioned marker and removes *all* managed blocks rather than the first,
  which is what makes a future marker-version rollout survivable: splice cannot
  find a differently-versioned block, so it prepends, and stripping only the
  first would leave the old one to be re-ingested.
- **Unknown keys in `.claimgraph/config.json` are reported** — on stderr once
  per process, and in `claim config` — rather than silently ignored, so a typo
  no longer does nothing at all.
- **The installer is stricter about what it trusts.** `curl` runs with `-f`,
  HTTPS-only and retries, so a 404 or a rate-limit page is no longer saved as
  the artifact; downloads land in a temp dir cleaned up on exit rather than the
  caller's cwd, which clobbered `./install` and failed outright on a read-only
  one; the babashka installer script is version-pinned rather than fetched from
  master; `curl`, `unzip` and `awk` are checked per route at point of use, so
  an image with `bb` and `dtlv` baked in is not failed for lacking tools it
  never needs; `$CLAIMGRAPH_DTLV` is honoured; and `bb`'s *version* is checked
  against `bb.edn`'s `:min-bb-version` rather than its mere presence.
- **`bb test` says when the Datalevin half was skipped**, after the summary. A
  contributor could read "0 failures" off a run that never opened the real
  store.

### Deprecated

- `session-extract` — still dispatches, never advertised. Use `ingest-session`.
- `--dir` — still works on every verb that took it, as a documented alias for
  that verb's named flag.
- `--min-confidence` on `judge`, `consolidate` and `hooks run` — an alias for
  `--min-verdict-confidence`. One name used to mean both "filter facts on read"
  and "act on verdicts", and it still means the first on every verb that reads.
- Bare 64-hex evidence pointers — read, never written.

### Fixed

- **`claim load` refused nothing.** An empty or truncated file loaded as
  `{:status :loaded}` with exit 0, a malformed `:format` escaped as a raw
  NullPointerException whose message was literally nil, and every unreadable
  file was diagnosed as a pre-alpha dump — sending a reader to re-dump a
  database they never had. Refusals now happen before a single write and
  distinguish eight causes, each with the remedy attached: the target store is
  not empty; the file is empty or truncated; it holds records but none of them
  a claimgraph kind, so it was never a dump; it is a pre-alpha dump; it carries
  a header but its records lost their kind, so it has been edited; its
  `:format` is not an integer; its `:format` is above this reader's; or it
  holds record kinds this build has no reader for. Partial restore is gone.
- **Every supersession the LLM judge resolved was missing from "Changed
  recently".** The compiled briefing recovered the successor by matching
  `^superseded by (\S+)$` against the reason sentence, and the judge wrote
  "judged superseded by …", which that pattern never matched. Six other
  producers wrote six other sentences.
- **`:reconcile-duplicate` and `:code-absent` were declared and unreachable** —
  both producers still passed bare sentences, and reconcile threw away the
  surviving twin it had just computed.
- **`fact->tx` dropped `:invalidation-reason` on write** on Datalevin, and had
  been doing so all along. The four synchronised declarations a fact attribute
  needs — schema, pull, wire projection, tx builder — are one declaration now,
  checked against the wire shape at load, so a half-declared attribute refuses
  the namespace instead of losing data quietly.
- **A fact could be born above its own source's ceiling.** Confidence defaulted
  to 0.8 whatever the source and an explicit value was not clamped either, so
  `assert --source-type session-log --confidence 0.99` minted a fact
  permanently above the 0.7 its source type declares — permanently, because
  reinforcement is a high-water mark that never claws a base back down. The
  ingest tiers already clamped their own candidates; this closes the
  direct-assert hole behind them.
- **`--extractor` was accepted, ignored, and shelled out to `claude -p`
  anyway** on `judge` and `consolidate`: it resolved against an empty opts map.
- **`$CLAIMGRAPH_LLM_TIMEOUT_MS` beat both the flag and the config file** — the
  flag was installed as the *last* fallback while the environment is consulted
  *first*, exactly inverting the precedence every other setting documents.
- **A bad flag value was reported as a nonexistent verb**, naming neither the
  option nor the value. `--depth two` now says so, and an unknown subcommand no
  longer emits `{"error": null}`.
- **`audit --pretty | jq` got a human scorecard and no JSON** — `--pretty`
  switched the format outright.
- **Re-registering a coined predicate destroyed its accumulated `maps-to`,
  `default-epistemic` and `replaced-by`.** `-register-predicate` had
  become an unconditional replace across twelve fields while
  `claim predicate register` passes a partial row. Replace versus merge is now
  decided per caller: a `core/*` row is the seed's, so a field the seed drops
  must be retracted; an `x/*` row is the store's and merges.
- **A verdict the model pretty-printed across lines was silently discarded.**
  `parse-judgment` only ever tried whole lines as JSON. A brace scan behind the
  line path handles fences and nesting in one left-to-right pass (92 KB of
  unmatched braces: 46 ms, previously unfinished after 110 s).
- **A lock file with no readable lease could never be broken by anyone**, so a
  crash during the truncate-then-write window wedged the store permanently —
  and a heartbeat opens that window once per renewal interval rather than once
  per acquisition.
- **A malformed envelope field in one peer's log aborted the whole reconcile
  pass** with a bare ClassCastException naming no file, no line and no writer.
- **The per-store oplog sequence counter could hand one number out twice** when
  more than one store was opened over the same db inside a process. A repeated
  seq is the one numbering failure a reader cannot see: it arrives looking
  exactly like a log copied under two names, which reconcile is right to
  collapse, so the dedup read the two effects as one and dropped the other. The
  counter is process-wide now, keyed by db and writer.
- **Adding a `.gitignore` entry in a later version appended a second full
  block.** "Covered" was decided by requiring every entry to be present, and
  relocating the db already produced that.
- **`consolidate-due?` could kill a hook run** whose earlier stages had already
  succeeded: the stamp can vanish mid-read under a concurrent pass, and the
  call sits outside the wrapper that catches a stage's failure.
- **`memory_search`, `memory_recall` and `memory_coach` answered "nothing
  found" with no query at all**, teaching the model its call was fine. A
  stringified number raised a bare `ClassCastException` with no type and no
  hint; an empty `query` silently switched `memory_neighbor` to the guided walk
  and discarded the depth the same call asked for.

### Known limits

- Evidence artifacts are **text only** and land in **one flat directory**. Both
  are deferred, not missed.
- The retrieval log now stamps each entry with the time it was written, but
  nothing ages on it yet: selection is still "entries since the most recent
  outcome mark". The stamp makes aging possible; it does not perform it.
- The confidence clamp is by source ceiling only. It is not a `[0,1]`
  validation — a negative confidence is still stored, and a value above 1.0 is
  bounded only because the highest ceiling is 1.0.
- `format-version` 1 is where stamping starts, and the alpha's formats are
  stamped but not frozen. The migration promise begins at 1.0; until then a
  format change may still be made without one.
