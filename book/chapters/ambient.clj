;; # The ambient loop
;;
;; Nothing in the earlier chapters required the user to type a claimgraph
;; verb, but it all assumed *someone* writes facts. The ambient loop removes
;; that assumption. The harness's own auto-memory (the notes Claude Code and
;; Codex maintain about a project) becomes an ingestion tier, and the graph
;; compiles its current view back into the file the harness injects at
;; session start. Capture is delegated in, injection is delegated out, and
;; the graph consolidates in the middle. A SessionEnd hook runs the loop
;; automatically, at the end of every session.
;;
;; This chapter executes the deterministic halves: the compile side in full,
;; and the pure machinery of the ingest side (delta detection, the echo
;; guard, the inference-grade clamp). The LLM half of ingestion is a
;; pluggable subprocess and stays out of a book build on purpose.

(ns ambient
  (:require [babashka.fs :as fs]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.store.memory :as mem]))

;; ## Harness adapters
;;
;; Each harness is described by an adapter: where its notes live, which
;; files count as notes, and which file it injects into every session.
;; Claude Code injects `MEMORY.md`; Codex injects `memory_summary.md`:

(-> (harness/resolve-harness :claude-code)
    (select-keys [:id :note-glob :inject-file]))

(-> (harness/resolve-harness :codex)
    (select-keys [:id :note-glob :inject-file]))

;; Two harnesses, one graph: notes from both merge through the same entity
;; resolution and conflict machinery, which makes claimgraph a cross-harness
;; consolidator. Nothing else in the 2026 field survey does this.
;;
;; ## A graph worth compiling
;;
;; The compiled view leads with what a session most needs to know: standing
;; decisions, open conflicts, what changed recently, then the top current
;; facts. Build a store with one of each:

(def store (doto (mem/create) (core/seed!)))

(core/assert-fact store {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record
                         :t-valid #inst "2026-05-01"})

;; A supersession (the "what changed" briefing): the version moved on
;; July 10th.

(core/assert-fact store {:subject "AuthService" :predicate :core/has-version
                         :object "1.0.0" :object-kind :literal :source-type :code
                         :t-valid #inst "2026-06-01"})
(core/assert-fact store {:subject "AuthService" :predicate :core/has-version
                         :object "2.0.0" :object-kind :literal :source-type :code
                         :t-valid #inst "2026-07-10"})

;; An open conflict (a session claim colliding with the commitment):

(core/assert-fact store {:subject "api-layer" :predicate :core/prefers
                         :object "GraphQL" :object-kind :literal
                         :source-type :session-log
                         :t-valid #inst "2026-07-15"})

;; And ordinary current facts, one of them code-derived:

(core/assert-fact store {:subject "billing" :predicate :core/prefers
                         :object "idempotency keys on retries"
                         :object-kind :literal :source-type :user-assertion
                         :t-valid #inst "2026-06-20"})
(core/assert-fact store {:subject "billing" :predicate :core/depends-on
                         :object "stripe-client" :source-type :code
                         :confidence 0.95
                         :t-valid #inst "2026-06-01"})

;; ## compile-context
;;
;; The compile is deterministic, budgeted to the harness's injection window
;; (25 KB default), and idempotent. Point it at a directory standing in for
;; `~/.claude/projects/<project>/memory/`:

(def notes-dir (str (fs/create-temp-dir {:prefix "claimgraph-book"}) "/memory"))

(def result
  (context/compile! store {:harness :claude-code
                           :dir notes-dir
                           :now #inst "2026-07-18"}))

(select-keys result [:harness :file :bytes :sections])

;; The managed section it wrote, verbatim:

(println (slurp (:file result)))

;; Read it the way a fresh session would. Standing decisions first, the open
;; conflict flagged as unresolved, the supersession dated, and the top facts
;; last. Two exclusions are deliberate: code-derived facts (the code can
;; speak for itself; the view carries what the code cannot say) and any fact
;; involved in an open conflict (disputed claims do not get presented as
;; current truth; that exclusion is what turned the benchmark's poisoning
;; attack from a wrong confident answer into an honest abstention).
;;
;; When the content outgrows the budget, lines drop from the bottom
;; (facts before conflicts before commitments) and the view always announces
;; the truncation, so a reader knows to query the graph for the rest.
;;
;; ## The ingest side, and the echo guard
;;
;; `ingest-notes` scans the notes directory, hashes each file's ingestible
;; content, and extracts only files whose hash has no ingestion episode yet.
;; The store's episode log is the delta state; there is no separate
;; bookkeeping file. The crucial move is what gets hashed: the managed
;; section is stripped first.

(def note-file (str (fs/path notes-dir "MEMORY.md")))

;; Simulate the harness adding its own note next to the managed section:

(spit note-file
      (str (slurp note-file)
           "\n## Debugging\n- payout cron fails on DST transitions\n"))

(def hash-with-note
  (notes/content-hash (harness/strip-managed-section (slurp note-file))))

;; Now recompile (the graph moved on; say the conflict got resolved). The
;; file's bytes change, but its ingestible content does not:

(core/invalidate store
                 {:fact-id (->> (core/get-facts store {:entity "api-layer"
                                                       :predicate :core/prefers})
                                :facts first :id)
                  :reason "the ADR stands"})

(context/compile! store {:harness :claude-code
                         :dir notes-dir
                         :now #inst "2026-07-19"})

(= hash-with-note
   (notes/content-hash (harness/strip-managed-section (slurp note-file))))

;; That equality is the echo guard: compile, ingest, compile is a fixed
;; point, and the graph never re-consumes its own compiled view. Only the
;; harness's own notes are upstream.
;;
;; ## Inference grade, enforced
;;
;; Whatever the extractor returns is clamped before it touches the write
;; path. Notes flatten who-said-what, so note-derived facts are always
;; second-class evidence: source-type `agent-note`, confidence capped at
;; 0.65, and never a commitment, even when the extractor claims one:

(notes/prepare-note-facts
 [{:subject "api-layer" :predicate "decided-against" :object "REST"
   :class "commitment" :confidence 0.95}
  {:subject "billing" :predicate "prefers" :object "small batches"
   :class "preference" :confidence 0.5}
  {:subject "" :predicate "prefers" :object "incomplete"}])

;; The reported decision was demoted to an observation at 0.65, the
;; preference kept its class, and the incomplete triple was rejected. A
;; genuine decision still has exactly one road into the graph: a human (or
;; the skill acting on the human's words) asserting `--class commitment`.
;;
;; ## The hook that runs it
;;
;; ```bash
;; bin/claim hooks install            # SessionEnd: ingest-code-if-changed,
;;                                       #   ingest-notes, compile-context,
;;                                       #   consolidate when due (default weekly)
;; bin/claim hooks install --coach    # also: a UserPromptSubmit gate that
;;                                       #   interrupts only when the graph holds a
;;                                       #   standing decision, failure mode, or open
;;                                       #   conflict touching the prompt
;; bin/claim hooks run                # the same pass, by hand
;; ```
;;
;; The first stage keeps the mechanical code tier fresh with the same
;; zero-effort economics as the rest of the loop: it is delta-gated on
;; `<git-sha>+<dirty-digest>` against the last code episode's ref, so it
;; costs milliseconds when nothing changed and reconciles when anything
;; did — including teammates' pulled changes, which no skill nudge would
;; ever catch (the `code-ingest` setting opts it out for projects with
;; expensive analyzers). Stages report independently: an analyzer or
;; extractor failure never blocks the deterministic recompile. The coach is
;; the push-side complement to the
;; skill's pull-side judgment, and it stays silent unless the gate fires;
;; always-on injection is the pattern the [AGENTS.md study](https://arxiv.org/abs/2602.11988) measured into the
;; ground, and the mutating-step evidence ([SABER](https://arxiv.org/abs/2512.07850)) says the moment worth
;; interrupting is the one right before a write.
;;
;; The next chapter turns this machinery around: the same conflict engine,
;; pointed at the memory pile a repo already has — before claimgraph is
;; installed at all.
