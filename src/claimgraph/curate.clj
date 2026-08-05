(ns claimgraph.curate
  "The detached curation run (spec/maintenance.allium, rule Curate): everything
  the graph wants from a model, in one budgeted, convergent pass that no
  session's exit waits on. The SessionEnd hook captures deterministically and
  spawns this; every model call in the ambient loop belongs here.

  Three stages, attempted INDEPENDENTLY (an extractor failure must never stop
  the recompile), in the order the spec spends the budget:

    1. ingest-notes    the just-ended session's knowledge is the freshest, and
                       capturing it is the loop's whole point
    2. consolidate     judge, summarize, sweep, enrich — under whatever the
                       extraction stage left of the run's ONE call budget
    3. compile-context so the next session's injected view carries what
                       curation just learned

  There is no stamp and no cadence: every model call lands a durable outcome
  (a closed episode, a recorded verdict, a recorded enrichment attempt), so
  what remains is DERIVED from the store and each run shrinks the remainder.
  A converged store makes this pass a free no-op.

  ## Write-lease discipline: leaseless by default, held per applied outcome

  The curator holds NO standing write lease, and never holds one across a
  model call (spec/replication.allium, NeverHeldAcrossAModelCall). The lease
  exists to serialize read-DECIDE-write cycles; in a resolve-off curation run
  exactly two writes decide anything:

    - notes ingestion, which runs the full conflict machinery per fact, and
    - alias application, which refuses a clash with another entity's name.

  Those run inside `:apply!` — a wrapper that takes the write lease on the db
  for the duration of that one application and gives it straight back. It
  WAITS (30s, not the CLI's 5) rather than failing loud: a background process
  can afford to wait out a live session's capture, and the capture must never
  queue behind a curator mid-thought.

  Everything else the pass writes — verdict records, conflict links, episode
  closures, enrichment-attempt records — decides nothing and is atomic at the
  engine, so it is deliberately leaseless. Taking a lock for a write that
  decides nothing buys no safety and costs the next session's capture."
  (:require [claimgraph.consolidate :as consolidate]
            [claimgraph.context :as context]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.lease :as lease]))

(def curator-owner
  "What the curator calls itself in both leases it touches — the curation
  lease it holds for the whole run, and the write lease it takes per applied
  outcome. One spelling, so a :store-locked error names something a reader can
  match against the log file beside it."
  "claimgraph-curator")

(def apply-wait-ms
  "How long an application waits for the write lease before giving up. Longer
  than the CLI's 5s on purpose: the contended case is a live session's
  SessionEnd capture, which is seconds of deterministic work, and a background
  process losing a race to it should wait rather than drop the outcome its
  model call already paid for."
  30000)

(defn curation-lease-key
  "The curation lease's key: `<db>.curate`, which lease/lock-file turns into
  `<db>.curate.lock` — the second instance of replication.allium's Lease,
  beside the write lease's `<db>.lock`. Same mechanics, different meaning on
  acquisition: a write acquirer waits then fails loud, a curation acquirer
  never waits at all."
  [db]
  (str db ".curate"))

(defn- attempt
  "Run one stage, containing its failure to its own report entry. A stage that
  throws is an :error entry and the later stages still run — the deterministic
  compile in particular must always get its chance."
  [f]
  (try (f)
       (catch Exception e
         (merge {:status :error :error (ex-message e)}
                (dissoc (ex-data e) :claimgraph/error)))))

(defn- write-lease-apply!
  "The default :apply!: run one decide-bearing application under the write
  lease, taken here and released before the next model call. A db path we do
  not have (tests, an in-memory store) applies unwrapped rather than writing a
  lock file into the cwd."
  [db]
  (if (empty? (str db))
    (fn [thunk] (thunk))
    (fn [thunk]
      (lease/with-lease (str db) {:owner curator-owner :wait-ms apply-wait-ms} thunk))))

(defn curate!
  "One curation run against an OPEN store. The caller owns the curation lease
  (cli/cmd-curate try-acquires it) — this function owns the budget.

  opts: :db (for the per-outcome write lease; see the ns docstring)
        :budget (model calls for the WHOLE run; default 20)
        :harness :project :dir :ctx :extractor :extractor-fn (notes)
        :evidence-dir (raw inputs and judge replies, content-addressed)
        :command :summarize-fn :judge-fn :enrich-fn :min-usage (consolidate)
        :inject-file (compile-context's write target)
        :apply! (fn [thunk] ...) wrapping each decide-bearing write;
                injectable, defaults to the write lease

  ONE budget spans the run, extraction first: what the notes stage spent is
  subtracted before consolidation is asked for its remainder, so a session
  whose notes exhaust the budget defers maintenance rather than starving
  capture. Consolidation is enrich-only — no :resolve — because nothing
  unattended may invalidate a fact on a verdict nobody has read.

  -> {:status :ok|:partial :budget {:allowed n :spent n}
      :ingest-notes ... :consolidate ... :compile-context ...}"
  [s {:keys [db budget apply!] :as opts}]
  (let [allowed (max 0 (if (number? budget) (long budget) consolidate/default-call-budget))
        spent (atom 0)
        spend! (fn [] (when (< @spent allowed) (swap! spent inc) true))
        apply! (or apply! (write-lease-apply! db))
        ingested (attempt #(notes/ingest!
                            s (assoc (select-keys opts [:harness :project :dir :ctx
                                                        :extractor :extractor-fn
                                                        :evidence-dir])
                                     :spend! spend!
                                     :apply! apply!)))
        ;; whatever extraction left, and never less than nothing
        remaining (max 0 (- allowed @spent))
        consolidated (attempt #(consolidate/consolidate!
                                s (assoc (select-keys opts [:command :summarize-fn :judge-fn
                                                            :enrich-fn :min-usage
                                                            :evidence-dir])
                                         :budget remaining
                                         :apply! apply!)))
        compiled (attempt #(context/compile!
                            s (select-keys opts [:harness :project :dir :ctx :inject-file])))]
    {:status (if (some #(contains? #{:error :partial} (:status %))
                       [ingested consolidated compiled])
               :partial :ok)
     :budget {:allowed allowed
              :spent (+ @spent (get-in consolidated [:budget :spent] 0))}
     :ingest-notes ingested
     :consolidate consolidated
     :compile-context compiled}))
