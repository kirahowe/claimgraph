(ns claimgraph.ingest.notes
  "Auto-memory notes ingester: the fourth ingestion tier
  (docs/consuming-auto-memory.md). Consumes the harness's own memory notes —
  already LLM-distilled by the model that watched the session — as an
  extraction substrate, delta-detected per file so only changed notes reach
  the extractor.

  Functional core / imperative shell like the session ingester it adapts:
  hashing, delta planning, prompt construction, and clamping are pure; the
  effects are directory reads and the pluggable extractor shell-out.

  Epistemic treatment: notes flatten who-said-what, so everything ingests as
  agent inference — source-type :agent-note, confidence capped below the
  session tier, and NEVER a commitment (a reported decision is demoted to an
  observation; genuine decisions arrive via the direct assert path). No
  reconciliation applies: the harness compacts notes under space pressure,
  and absence-by-compaction is not falsity — facts that stop being restated
  simply stop being reinforced and fade by disuse decay."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.session :as session]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def max-confidence 0.65)
(def default-confidence 0.55)

;; ---------------------------------------------------------------------------
;; Pure: hashing & delta detection
;; ---------------------------------------------------------------------------

(defn content-hash
  "Short, stable content hash (sha-256, first 12 hex chars) of a note's
  ingestible content — callers strip the managed section first, so a
  compile-context rewrite alone never re-triggers ingestion."
  [content]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str content) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) d)) 0 12)))

(defn episode-ref
  "Provenance answers \"which note file, at which state, said this\"."
  [harness-id path hash]
  (str (name harness-id) ":" path "@" hash))

(defn seen-hashes
  "Episodes -> {relative-path #{content-hash}} for one harness, parsed back
  out of agent-note episode refs. The store's episode log IS the delta state;
  no extra bookkeeping surface."
  [episodes harness-id]
  (let [prefix (str (name harness-id) ":")]
    (reduce (fn [m {:keys [source-type ref]}]
              (let [ref (str ref)
                    at (str/last-index-of ref "@")]
                (if (and (= :agent-note source-type)
                         (str/starts-with? ref prefix)
                         at (> at (count prefix)))
                  (update m (subs ref (count prefix) at)
                          (fnil conj #{}) (subs ref (inc at)))
                  m)))
            {} episodes)))

(defn changed-notes
  "Delta plan: the notes whose current hash has no ingestion episode yet."
  [notes seen]
  (filterv (fn [{:keys [path hash]}] (not (contains? (get seen path) hash)))
           notes))

;; ---------------------------------------------------------------------------
;; Pure: prompt & clamping
;; ---------------------------------------------------------------------------

(defn extraction-prompt
  "Notes variant of the session prompt: the input is already distilled, so
  the job is normalization into facts, not mining — with a durability filter
  for the working-memory ephemera auto-memory accumulates, and no commitment
  class (notes don't record whether the user said it or the model inferred
  it)."
  [note-path note-content predicates roster]
  (str
   "Normalize durable project memory from a coding agent's auto-memory note file.\n\n"
   "The input is a note file the agent maintains about this project — already\n"
   "distilled, but unstructured. Restate what the notes assert as structured\n"
   "facts; normalize, don't mine.\n\n"
   "Emit one JSON object per line (JSONL) and nothing else — no prose, no code fences.\n"
   "Keys: subject (entity name), predicate, object, object_kind (\"entity\"|\"literal\"),\n"
   "class (\"observation\"|\"preference\"), confidence (0.0-1.0), scope (optional).\n\n"
   "Notes flatten who-said-what, so every claim is an agent inference: class is\n"
   "\"observation\", or \"preference\" for stated preferences — never a decision or\n"
   "commitment, even when a note reports one.\n"
   "Extract ONLY knowledge that will still matter in a month: conventions,\n"
   "constraints, gotchas, architecture, preferences. Skip working-memory ephemera —\n"
   "ports, running processes, current-task state, worktree paths, TODO lists.\n"
   "Subjects and entity-kind objects must be stable names (services, namespaces,\n"
   "tools, files, people), never sentences; free text belongs in literal objects.\n\n"
   "Allowed predicates (coin x/<new-name> only if none fits):\n"
   (str/join "\n" (for [p predicates]
                    (str "  " (subs (str (:id p)) 1) " — " (:definition p))))
   (when (seq roster)
     (str "\n\nKnown entities — when you mean one of these, use its EXACT name\n"
          "(synonym drift fragments the graph); coin a new name only when none\n"
          "of these is the thing you mean:\n"
          (str/join "\n" roster)))
   "\n\nIf nothing qualifies, output nothing.\n\n"
   "<notes file=\"" note-path "\">\n" note-content "\n</notes>"))

(defn prepare-note-facts
  "Validate and clamp extracted candidates: complete triples become facts at
  source-type :agent-note with confidence capped at 0.65; the epistemic class
  is set explicitly — :preference stays, everything else (including any
  \"commitment\" the extractor emits despite the prompt, counted as :demoted)
  becomes :observation, so a commitment-defaulting predicate can never mint
  one from a note. Incomplete triples are returned as :rejected."
  [extracted]
  (let [complete? (fn [m] (every? #(not (str/blank? (str (get m %))))
                                  [:subject :predicate :object]))
        {complete true rejected false} (group-by complete? extracted)
        clamp (fn [m]
                (let [c (:confidence m)
                      class (some-> (or (:class m) (:epistemic m)) name str/lower-case)]
                  (-> m
                      (dissoc :class)
                      (assoc :confidence (min max-confidence
                                              (if (number? c) (double c) default-confidence))
                             :epistemic (if (= "preference" class) :preference :observation)
                             :source-type :agent-note))))]
    {:facts (mapv clamp complete)
     :demoted (count (filter #(= "commitment" (some-> (or (:class %) (:epistemic %))
                                                      name str/lower-case))
                             complete))
     :rejected (vec rejected)}))

;; ---------------------------------------------------------------------------
;; Shell: directory scan + extraction + ingestion
;; ---------------------------------------------------------------------------

(defn- read-notes
  "Scan the notes dir: every markdown file, managed section stripped, hashed.
  Unknown layout degrades gracefully — anything *.md is a plain note. Files
  that are blank once stripped (e.g. a MEMORY.md holding only our compiled
  view) are skipped outright: nothing to extract, and the fixed point of
  compile → ingest → compile must not depend on the extractor's judgment.
  Content is trimmed before hashing so the whitespace seam a first
  compile-context splice leaves around the notes never reads as a change."
  [dir note-glob]
  (->> (fs/glob dir (or note-glob "**.md"))
       sort
       (keep (fn [p]
               (let [content (str/trim (harness/strip-managed-section (slurp (str p))))]
                 (when-not (str/blank? content)
                   {:path (str (fs/relativize dir p))
                    :content content
                    :hash (content-hash content)}))))
       vec))

(defn- ingest-note!
  "Extract one note revision and write what it said.

  The extraction call is OUTSIDE `apply!` and the write is INSIDE it: this is
  the one write in the pass that decides anything (the full conflict machinery
  runs per fact), so it is the one that has to be serialized against other
  writers — and a lease held across the model call would block the next
  session's capture for the length of a completion."
  [s run harness-id {:keys [path content hash]}
   {:keys [predicates roster actx evidence-dir dry-run apply!]}]
  (let [prompt (extraction-prompt path content predicates roster)
        {:keys [facts demoted rejected]} (prepare-note-facts (session/parse-extraction (run prompt)))
        {:keys [admitted inadmissible]} (logic/screen-candidates facts actx)
        facts admitted]
    (if dry-run
      (cond-> {:file path :hash hash :status :dry-run :facts facts
               :demoted demoted :rejected rejected}
        (seq inadmissible) (assoc :inadmissible inadmissible))
      (apply!
       (fn []
         (let [ref (episode-ref harness-id path hash)
               evidence (when evidence-dir
                          ((requiring-resolve 'claimgraph.evidence/write!)
                           evidence-dir content))
               result (core/ingest s {:source-type :agent-note :ref ref
                                      :evidence evidence}
                                   facts)]
           (core/close-episode s {:episode (:episode result)
                                  :summary (str "notes ingest " ref ": "
                                                (:total result) " facts ("
                                                (pr-str (:counts result)) "), "
                                                demoted " demoted, "
                                                (count rejected) " rejected")})
           (-> result
               (assoc :file path :hash hash :demoted demoted)
               (cond-> (seq inadmissible) (assoc :inadmissible inadmissible)
                       (seq rejected) (assoc :rejected rejected)))))))))

(defn ingest!
  "One notes pass: delta-detect the harness's auto-memory directory, extract
  changed files with the pluggable extractor, ingest through the full conflict
  machinery — one episode per (file, revision), closed with a mechanical
  summary so the episode log doubles as the delta state.

  opts: :harness (default claude-code) :dir (override the resolved notes dir)
        :project (project dir the harness keys its notes on; default cwd)
        :ctx (harness-resolution context {:home :env}; injectable, tests)
        :extractor (command string) :extractor-fn (injectable, tests)
        :evidence-dir (keep each ingested note revision as a content-
                       addressed artifact; skipped when absent)
        :spend! (0-arg budget gate; truthy = one model call is affordable.
                 Default unlimited)
        :apply! (fn [thunk] ...) wrapping the write half of each file, so a
                 caller can serialize it however it must. Default: run it)
        :dry-run (extract and report, write nothing)

  A file the budget did not reach is DEFERRED, not extracted, and counted in
  :deferred — never silently dropped. It needs no bookkeeping to come back:
  its content hash still has no episode, so the delta gate above finds it
  again on the next run, which is what makes a budgeted pass convergent."
  [s {:keys [harness extractor extractor-fn evidence-dir spend! apply! dry-run] :as opts}]
  (let [h (harness/resolve-harness harness)
        notes-dir (harness/notes-path h (select-keys opts [:dir :project :ctx]))]
    (if-not (fs/directory? notes-dir)
      {:status :no-notes-dir :harness (name (:id h)) :dir (str notes-dir)
       ;; --notes-dir is what this setting is called now; naming its retired
       ;; spelling on the failure path is how the rename never finishes.
       :hint "no auto-memory directory for this project yet (or pass --notes-dir)"}
      (let [notes (read-notes notes-dir (:note-glob h))
            changed (changed-notes notes (seen-hashes (store/-list-episodes s) (:id h)))
            run (or extractor-fn (partial llm/complete! (llm/command extractor)))
            spend! (or spend! (constantly true))
            entities (store/-list-entities s {})
            predicates (store/-list-predicates s {:status :stable})
            ctx {:predicates predicates
                 :roster (session/entity-roster entities
                                                (store/-entity-usage s)
                                                session/roster-limit)
                 :actx (logic/admission-ctx entities predicates)
                 :evidence-dir evidence-dir
                 :apply! (or apply! (fn [f] (f)))
                 :dry-run dry-run}
            deferred (atom 0)
            results (into []
                          (keep (fn [note]
                                  (if-not (spend!)
                                    (do (swap! deferred inc) nil)
                                    (ingest-note! s run (:id h) note ctx))))
                          changed)]
        (cond-> {:status (if dry-run :dry-run :ok)
                 :harness (name (:id h))
                 :dir (str notes-dir)
                 :files-scanned (count notes)
                 :files-changed (count changed)
                 :counts (apply merge-with + (keep :counts results))
                 :files results}
          (pos? @deferred) (assoc :deferred @deferred))))))
