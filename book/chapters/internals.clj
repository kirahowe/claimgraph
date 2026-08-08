;; # Internals: a functional core you can call
;;
;; claimgraph is built as a functional core inside an imperative shell. All
;; decision logic (conflict policy, temporal filters, retrieval fusion,
;; admission control, decay, BFS folds) lives in `claimgraph.logic` as pure
;; functions over plain values: time and fresh ids are passed in, decisions
;; come back as data. `claimgraph.core` is the thin shell that gathers store
;; reads, asks logic for a decision, and executes the returned plan. Below
;; the shell sits a storage protocol with two implementations.
;;
;; ```
;; CLI / skill / MCP        src/claimgraph/cli.clj, mcp.clj    parsing, JSON I/O
;;         |
;;    imperative shell      src/claimgraph/core.clj            reads -> decision -> plan
;;         |
;;    functional core       src/claimgraph/logic.clj           PURE decisions
;;         |                src/claimgraph/predicates.clj      vocabulary
;;    Store protocol        src/claimgraph/store.clj           the swappable seam
;;         |
;;    datalevin backend     src/claimgraph/store/datalevin.clj (production; via pod)
;;    memory backend        src/claimgraph/store/memory.clj    (tests, this book)
;; ```
;;
;; The payoff is that the interesting parts of the system can be called
;; directly, with no store, no clock, and no I/O. This chapter does exactly
;; that.

(ns internals
  (:require [claimgraph.logic :as logic]))

;; ## The assertion decision, as data
;;
;; `decide-assert` is the write path's brain. Given the candidate fact, its
;; predicate row, and the relevant standing facts (all gathered by the
;; shell), it returns an effect plan, and the shell's only job is to execute
;; it. A commitment threatened by a new claim produces a flag plan:

(def standing-commitment
  {:id "f-adr" :predicate :core/decided-against
   :object-kind :literal :object-lit "GraphQL"
   :epistemic :commitment :source-type :decision-record
   :t-valid #inst "2026-01-01"})

(logic/decide-assert
 {:fact {:id "f-new" :predicate :core/prefers
         :object-kind :literal :object-lit "GraphQL"
         :epistemic :preference :source-type :session-log
         :t-valid #inst "2026-07-01"}
  :pred {:id :core/prefers :cardinality :many :exclusion-group :stance}
  :existing []
  :exclusion [standing-commitment]
  :revenants [] :rivals []
  :on-conflict nil})

;; No mocks, no fixtures, no store: the policy is just a function. The same
;; call with `:source-type :decision-record` on both sides still flags
;; (commitments flag regardless of rank), and swapping the commitment for an
;; observation on a single-valued predicate returns a supersede plan with
;; the ids to invalidate. The test suite leans on this shape heavily, and so
;; does reconciliation.
;;
;; ## Rank fusion, purely
;;
;; `fuse-retrieval` merges ranked candidate lists from the retrieval routes
;; using reciprocal rank fusion, then weighs each fact by its effective
;; confidence, so consensus across routes and freshness both count. Two
;; routes that disagree about ordering, one stale fact, all visible in the
;; final score:

(def now #inst "2026-07-18")

(defn fact [id conf reinforced]
  {:id id :object-lit id :epistemic :observation :source-type :session-log
   :confidence conf :recorded-at reinforced :last-reinforced-at reinforced
   :t-valid #inst "2026-01-01" :t-invalid nil})

(def fresh-a (fact "a" 0.9 #inst "2026-07-01"))
(def fresh-b (fact "b" 0.9 #inst "2026-07-01"))
(def stale-c (fact "c" 0.9 #inst "2025-10-01"))

(->> (logic/fuse-retrieval
      [{:weight 1.0 :facts [fresh-a stale-c fresh-b]}
       {:weight 1.2 :facts [fresh-b fresh-a]}]
      now)
     (mapv (juxt :id :retrieval-score)))

;; `c` held a top rank in one route, but nine months of disuse cut its
;; effective confidence in half three times over, and the fusion reflects
;; that. An invalidated fact would not surface at all.
;;
;; ## Admission control, purely
;;
;; Extraction output passes an admission screen before the write path.
;; The hard rules are shape and floor (confidence at least 0.3, subject at
;; most 80 characters, literal at most 250 characters — or 1000 where the
;; predicate's registry row declares prose-shaped objects, as the seed does
;; for the lesson-bearing predicates); soft signals (known subject,
;; known predicate, class weight) only affect the score. Junk never reaches
;; the graph, and because the raw evidence tier keeps the extractor's full
;; input, screening is safe: gate the graph, keep the log.

(def ctx
  (logic/admission-ctx [{:name "AuthService" :aliases ["auth-service"]}]
                       [{:id :core/prefers} {:id :core/depends-on}]))

(logic/screen-candidates
 [{:subject "AuthService" :predicate "prefers"
   :object "argon2" :confidence 0.6}
  {:subject "AuthService" :predicate "prefers"
   :object "argon2" :confidence 0.1}
  {:subject "the whole discussion we had about maybe possibly restructuring the api at some point"
   :predicate "prefers" :object "..." :confidence 0.6}]
 ctx)

;; ## The storage seam
;;
;; The `Store` protocol (`src/claimgraph/store.clj`) is the only boundary the
;; shell talks through: entities, facts, episodes, predicates, narrow
;; selects, FTS. The Datalevin implementation speaks Datalog through the
;; `dtlv` pod binary; the in-memory implementation is atoms and filters. The
;; entire core-semantics test suite runs against both, which is the proof
;; the seam holds. This book's chapters run on the memory backend for the
;; same reason the tests can: behavior above the seam is identical.
;;
;; ## What lives next to a store
;;
;; A database directory accrues sidecar files, each owned by one subsystem:
;;
;; | Path | Owner | Contents |
;; |---|---|---|
;; | `<db>/` | store backend | the LMDB directory (gitignored; the dump is the committable artifact) |
;; | `<db>.evidence/` | evidence tier | immutable content-addressed raw inputs, named `sha256-<hex>` — the tag is what lets the hash function ever change |
;; | `<db>.oplog/` | multi-writer | `writer` (this machine's id), `<writer>.jsonl` per-writer effect logs, `applied.json` high-water marks and entity map |
;; | `<db>.retrievals` | outcome signal | append-only log of which facts reads surfaced |
;; | `<db>.lock` | lease | same-machine write serialization (token, TTL) |
;; | `<db>.curate.lock` | lease | the curation lease: a second instance of the same mechanics, making the detached curator a singleton (try-acquire, never a wait) |
;; | `<db>.capture.log` | hooks | stdout+stderr of the capture pass the SessionEnd hook spawns and does not await — the pass's own JSON report, and the reason if it died |
;; | `<db>.curate.log` | hooks | stdout+stderr of the curator that capture spawns and does not await — a detached process reports to nobody, so this is its whole crash-visibility surface |
;; | `<db>.version` | store backend | the persisted-format version this store was written at; a store from a future format is refused rather than schema-merged |
;;
;; ## Reading order
;;
;; For a tour of the source: `logic.clj` first (every important decision is
;; there, pure and testable), then `core.clj` for how plans execute, then
;; one store backend, then the verticals (`oplog.clj`, `context.clj`,
;; `ingest/notes.clj`, `ingest/code.clj` and its per-language adapters,
;; `hooks.clj`, `curate.clj`, `consolidate.clj`, `audit.clj`, `mcp.clj`).
;; The test suite mirrors that
;; structure, 400 tests and 2392 assertions across 26 test namespaces, and
;; `bb test` runs it against both backends.
