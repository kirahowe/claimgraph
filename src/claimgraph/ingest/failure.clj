(ns claimgraph.ingest.failure
  "Failure ingester: when agent or developer work is rejected or reverted,
  extract WHY — this is where procedural memory grows from (the largest
  cluster of measured wins at MemAgents 2026; review §3.4).

  Design guardrails straight from the literature: extract the LESSON, not
  the diff (ERL/Memory Transfer Learning — heuristics transfer, raw traces
  cause negative transfer), and preferentially capture lessons about
  MUTATING actions — writes, migrations, deploys, schema changes — where
  deviations kill outcomes (SABER). Lessons land as `core/failure-mode`
  facts (subject = the tool/namespace/action; object = the lesson literal),
  corrective practices as `prefers`, human rulings as `decided-against`.

  Same machinery as the session ingester: pluggable extractor, roster
  prior, 0.7 confidence cap, full conflict machinery, raw input kept as
  evidence. Episode AND facts are typed :failure-report (spec/
  ingestion.allium, decided 2026-07-26) — the episode is the valence signal
  the outcome-reinforcement work (roadmap #24) consumes, and the facts
  carry the source the trust ranking names, with its own 0.7 entry in
  logic/source-ceilings so nothing falls back to the 0.9 default ceiling
  above the tier's cap."
  (:require [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.ingest.session :as session]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(defn extraction-prompt
  [context material predicates roster]
  (str
   "A piece of work on this project was rejected, reverted, or failed. "
   "Extract the durable LESSONS as project memory.\n\n"
   (when-not (str/blank? (str context))
     (str "What was being attempted: " context "\n\n"))
   "Emit one JSON object per line (JSONL) and nothing else — no prose, no code fences.\n"
   "Keys: subject (entity name), predicate, object, object_kind (\"entity\"|\"literal\"),\n"
   "class (\"observation\"|\"commitment\"|\"preference\"), confidence (0.0-1.0), scope (optional).\n\n"
   "Extract the lesson, not the diff: what should be done differently next time,\n"
   "and why — never line-level details, blame, or transient noise (typos, flaky\n"
   "network, one-off environment issues). Give priority to lessons about MUTATING\n"
   "actions: writes, migrations, deploys, schema or config changes.\n"
   "- a known failure mode or hazard: predicate failure-mode — subject is the\n"
   "  tool/namespace/action, object is a short literal lesson\n"
   "  (\"...fails when...\", \"requires ... first\")\n"
   "- a corrective practice to adopt: predicate prefers, literal object\n"
   "- an approach a HUMAN explicitly ruled out here: decided-against\n"
   "  (class commitment only when a human decided; otherwise observation)\n"
   "Subjects and entity-kind objects must be stable names, never sentences.\n\n"
   "Allowed predicates (coin x/<new-name> only if none fits):\n"
   ;; This tier above all: its whole job is lessons, and a listing that told
   ;; the model every object is a short datum was asking it to throw away
   ;; the one thing it was invoked to capture
   (session/vocabulary-lines predicates)
   (when (seq roster)
     (str "\n\nKnown entities — when you mean one of these, use its EXACT name:\n"
          (str/join "\n" roster)))
   "\n\nIf nothing durable was learned, output nothing.\n\n"
   "<failure-material>\n" material "\n</failure-material>"))

(defn extract!
  "Extract lessons from failure material (a rejected diff + review, a revert
  message, an error transcript) and ingest them under a :failure-report
  episode.
  opts: :file | :transcript (string, wins) :context (what was attempted)
        :ref :extractor :extractor-fn :evidence-dir :dry-run"
  [s {:keys [file transcript context ref extractor extractor-fn evidence-dir dry-run]}]
  (let [material (or transcript (if file (slurp file) (slurp *in*)))
        entities (store/-list-entities s {})
        predicates (store/-list-predicates s {:status :stable})
        run (or extractor-fn (partial llm/complete! (llm/command extractor)))
        prompt (extraction-prompt context
                                  (session/->transcript material)
                                  predicates
                                  (session/entity-roster entities
                                                         (store/-entity-usage s)
                                                         session/roster-limit))
        {:keys [facts rejected]} (session/prepare-facts (session/parse-extraction (run prompt))
                                                        :failure-report)
        {:keys [admitted inadmissible]} (logic/screen-candidates
                                         facts (logic/admission-ctx entities predicates))]
    (if dry-run
      (cond-> {:status :dry-run :facts admitted :rejected rejected}
        (seq inadmissible) (assoc :inadmissible inadmissible))
      (let [evidence (when evidence-dir
                       ((requiring-resolve 'claimgraph.evidence/write!)
                        evidence-dir material))]
        (cond-> (core/ingest s {:source-type :failure-report
                                :ref (or ref (some-> file str) "failure")
                                :evidence evidence}
                             admitted)
          evidence (assoc :evidence evidence)
          (seq inadmissible) (assoc :inadmissible inadmissible)
          (seq rejected) (assoc :rejected rejected))))))
