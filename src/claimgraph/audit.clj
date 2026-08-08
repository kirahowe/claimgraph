(ns claimgraph.audit
  "`claim audit`: the memory-consistency scorecard (docs/memory-audit.md).
  Points the existing conflict machinery at the project's agent-memory pile
  — the auto-memory notes a coding agent accumulates on its own — together
  with its human-maintained instruction files (CLAUDE.md, AGENTS.md, rules
  files), and reports what neither can see about itself: contradictions,
  silent disagreements, staleness against the code, restatements, name
  drift, injection bloat.

  Top of the funnel, so the constraints are absolute: everything runs inside
  a throwaway in-memory store (store.memory/create + core/seed!), the real
  store is never opened, nothing is written (except the CLI's optional --out),
  and the only hard prerequisites are bb and an extractor command — not dtlv.

  Two deliberate deviations from the ambient notes tier (spec §5), both
  because this store never feeds durable memory: epistemic classes are KEPT
  at their predicate defaults (a reported decision ingests as a commitment so
  stance collisions flag instead of silently superseding), and code facts
  ingest FIRST so a pile claim colliding with the code flags against a
  code-sourced candidate — the staleness signal.

  A third deviation: every source carries a :kind (§1) — human-maintained
  :instruction files (CLAUDE.md, AGENTS.md, rules files) mint at
  :user-assertion trust, auto-memory :note files and --file/--dir extras
  (unknown authorship, so they stay second-class) mint at :agent-note — and
  instructions ingest before notes, so a note arriving against a standing
  instruction is the canonical collision direction. decide-assert turns
  that into the marquee instruction-conflict finding instead of two
  equally-disposable rows shrugging at each other.

  Functional core / imperative shell like the notes ingester it adapts:
  prompt, clamping, finding classification, scorecard fold, and rendering are
  pure; the effects are the source scan, the pluggable extractor/judge
  shell-outs, and the in-memory store."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.code :as code]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.ingest.session :as session]
            [claimgraph.judge :as judge]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(def default-confidence notes/default-confidence)

(def default-call-budget
  "audit's own hard cap on model calls for the whole run — the same
  budget discipline claimgraph.curate/curate! runs under: one shared
  counter spanning every call the pipeline makes (extraction AND judge),
  never a per-stage carve-out. Work the cap doesn't reach is deferred and
  named in the scorecard's :llm section, never dropped silently — the next
  run, or a wider --budget, picks it up exactly where this one stopped."
  20)

(def trip-threshold
  "Consecutive failed model calls after which the gate stops spending
  altogether, independent of how much budget remains. Every call already
  waits up to the 120s per-call timeout (claimgraph.llm/default-timeout-ms);
  a dead extractor or an expired auth token fails every call the same way,
  and without a breaker a run would burn its whole remaining budget one
  timeout at a time instead of surfacing the failure once and moving on.

  The breaker counts failures the gate has ALREADY been told about, so
  with n extraction calls in flight it stops the n+1st, not the ones
  already waiting on a subprocess. Overshoot is bounded by the pool
  (default-extract-concurrency) and paid once per run, which is the price
  of overlapping the waits at all; the budget, by contrast, is a hard cap
  and llm-gate spends it atomically."
  3)

(def default-extract-concurrency
  "How many extraction calls audit keeps in flight at once.

  Each call is a whole `claude -p` subprocess that spends nearly all of a
  ~70s round trip waiting on the model, so a serial pass costs one wait
  per pile file — eleven files is thirteen minutes of a run that is doing
  nothing. The calls are independent (extraction reads no store state; see
  audit-wave!), so the waits overlap for free.

  BOUNDED rather than one thread per file because the resource being spent
  is processes: an unbounded fan-out over a large pile forks a subprocess
  per source at once, and the machine — or the account's rate limit —
  becomes the failure instead of the model. Six keeps the wait dominant
  in the arithmetic while staying a number a laptop and a rate limit can
  both absorb; --concurrency moves it."
  6)

(def kind->source-type
  "Where a claim's trust comes from, keyed by its file's :kind (§1): an
  :instruction file is human-maintained, so its claims mint at the same
  trust and ceiling as a direct user assertion; a :note file — auto-memory
  or a --file/--dir extra alike — mints as agent inference. This is what
  makes :kind load-bearing rather than decorative: a note colliding with an
  instruction collides with something logic/source-trust recognises as
  authoritative, not another equally-disposable :agent-note row."
  {:instruction :user-assertion
   :note :agent-note})

(defn- mapped-source-type [kind] (get kind->source-type kind :agent-note))

(def file-warn-bytes
  "Per-file extraction degrades somewhere above this (spec §9); v1 warns,
  chunking is v2. Keyed on the STRIPPED content — what actually goes to the
  extractor — never the raw on-disk size: a big managed section (claimgraph's
  own compiled view) never reaches the extractor at all, so warning on its
  raw bytes would flag a file that isn't actually at extraction risk."
  50000)

(def default-scan-set
  "The default instruction-file scan set (spec §3) — human-maintained files
  audited alongside the memory pile, not the pile itself — relative to
  --project, each existing file only. collect-sources adds the rest of the
  project's actual discovery surface around this list: the .cursor/rules/*
  and .claude/rules/*.md globs, the ancestor walk (ancestor-scan-set) up to
  the filesystem root, each configured harness's own global instruction
  files, the OS managed-policy path, and the harness auto-memory notes dir
  (the pile proper, resolved exactly like ingest-notes)."
  ["CLAUDE.md" "CLAUDE.local.md" "AGENTS.md" "AGENT.md"
   ".github/copilot-instructions.md" ".cursorrules" ".claude/CLAUDE.md"])

(def ancestor-scan-set
  "Exact filenames checked at every ancestor directory of the project root
  (spec §3 extension, 2026-08-07): Claude Code loads CLAUDE.md /
  CLAUDE.local.md from every ancestor of the working directory up to the
  filesystem root at session start, and the Codex family walks AGENTS.md the
  same way — a cross-harness union, like default-scan-set, of exact names
  only, never a glob (a subdirectory CLAUDE.md loads lazily on descent, not
  at session start, so it is correctly absent from this list). Every match
  is :instruction, :injected? true — an ancestor file is exactly as
  human-maintained as one sitting in the project root, and a harness that
  walks the tree injects it wholesale the same way."
  ["CLAUDE.md" "CLAUDE.local.md" "AGENTS.md" "AGENT.md"])

(def managed-policy-paths
  "The OS-specific managed-policy CLAUDE.md paths Claude Code reads
  (organization-distributed, outside any project or home directory) —
  collect-sources' :managed-paths default, filtered to whichever of the
  three actually exists on this machine. At most one applies to any given
  OS; the other two are simply absent everywhere else and drop out at the
  same existence filter every other source goes through."
  ["/Library/Application Support/ClaudeCode/CLAUDE.md"
   "/etc/claude-code/CLAUDE.md"
   "C:\\Program Files\\ClaudeCode\\CLAUDE.md"])

;; ---------------------------------------------------------------------------
;; Pure: extraction prompt (audit variant, spec §6) & clamping
;; ---------------------------------------------------------------------------

(def ^:private kind-frame
  "How the extraction prompt names what it's reading, per source :kind
  (§1) — the one thing the extractor could not tell from the bytes alone.
  Everything else about the prompt (the JSONL contract, the kept class
  signal, the durability filter, the roster) is identical either way; only
  the frame sentence tells the extractor whether it's reading a human's
  rules file or the agent's own memory of itself."
  {:instruction "a human-maintained instruction file for a coding agent (CLAUDE.md / AGENTS.md / rules files)"
   :note "an auto-memory note a coding agent wrote for itself"})

(defn extraction-prompt
  "The notes prompt with the audit differences: the epistemic class signal
  is KEPT for every kind (commitments allowed — 'we decided against X' is
  exactly the claim whose collisions we want flagged, whether a human wrote
  it or the agent inferred it), every claim must carry a verbatim quote so
  findings render with receipts, not vibes, and the opening line frames
  what kind of file this is (kind-frame)."
  [path content predicates roster kind]
  (str
   "Extract durable project memory from " (get kind-frame kind (kind-frame :note)) ",\n"
   "one file of a coding agent's memory pile, for a consistency audit.\n"
   "Restate what the file asserts as structured claims.\n\n"
   "Emit one JSON object per line (JSONL) and nothing else — no prose, no code fences.\n"
   "Keys: subject (entity name), predicate, object, object_kind (\"entity\"|\"literal\"),\n"
   "class (\"observation\"|\"commitment\"|\"preference\"), confidence (0.0-1.0),\n"
   "quote (a short VERBATIM snippet of the file backing this claim), scope (optional).\n\n"
   "Keep the epistemic signal: a decision the file reports (\"we decided against X\",\n"
   "\"never use Y\") is class \"commitment\"; a stated preference is \"preference\";\n"
   "everything else is \"observation\".\n"
   "Extract ONLY knowledge meant to still matter in a month: conventions,\n"
   "constraints, gotchas, architecture, decisions, preferences. Skip working-memory\n"
   "ephemera — ports, running processes, current-task state, worktree paths, TODO lists.\n"
   "Subjects and entity-kind objects must be stable names (services, namespaces,\n"
   "tools, files, people), never sentences; free text belongs in literal objects.\n\n"
   "Allowed predicates (coin x/<new-name> only if none fits):\n"
   (session/vocabulary-lines predicates)
   (when (seq roster)
     (str "\n\nKnown entities — when you mean one of these, use its EXACT name\n"
          "(synonym drift fragments the graph); coin a new name only when none\n"
          "of these is the thing you mean:\n"
          (str/join "\n" roster)))
   "\n\nIf nothing qualifies, output nothing.\n\n"
   "<pile file=\"" path "\" kind=\"" (name (or kind :note)) "\">\n" content "\n</pile>"))

(defn prepare-audit-facts
  "Audit's clamp — the anti-notes (spec §5): the emitted class is KEPT
  (an invalid or absent class falls through to the predicate's default at
  assert time, so decided-against still mints the commitment that flags),
  confidence is capped at the ceiling the file's own :kind carries —
  0.9 for an :instruction file, 0.65 for a :note file, via the same
  logic/confidence-ceiling every other tier clamps against — and the
  verbatim :quote rides along
  on the candidate for the audit-side receipt map; the store never sees it.
  Incomplete triples are returned as :rejected."
  [extracted kind]
  (let [source-type (mapped-source-type kind)
        ceiling (logic/confidence-ceiling source-type)
        complete? (fn [m] (every? #(not (str/blank? (str (get m %))))
                                  [:subject :predicate :object]))
        {complete true rejected false} (group-by complete? extracted)
        clamp (fn [m]
                (let [c (:confidence m)
                      class (logic/->kw (some-> (or (:class m) (:epistemic m))
                                                name str/lower-case))]
                  (-> m
                      (dissoc :class :epistemic)
                      (assoc :confidence (min ceiling
                                              (if (number? c) (double c) default-confidence))
                             :source-type source-type)
                      (cond-> (contains? logic/epistemic-classes class)
                        (assoc :epistemic class)))))]
    {:facts (mapv clamp complete)
     :rejected (vec rejected)}))

;; ---------------------------------------------------------------------------
;; Pure: assert results -> findings (the §4 status table)
;; ---------------------------------------------------------------------------

(defn- code-sourced? [summary] (= :code (:source-type summary)))
(defn- instruction-sourced? [summary] (= :user-assertion (:source-type summary)))

(defn- source-type-label
  "A finding side's :source label (§6): what kind of thing asserted this,
  not merely whether it happens to carry a file. Anything this build
  doesn't recognise degrades to \"note\" — the lower-trust reading is also
  the honest one for an unexpected source-type."
  [source-type]
  (case source-type
    :code "code"
    :user-assertion "instruction"
    "note"))

(defn claim-view
  "One side of a finding, with its receipt: a fact summary joined against
  the audit-side quote map (§6), plus a :source label naming what kind of
  thing this side is — code, an instruction file, or a note — so a finding
  never has to be reverse-engineered from which side happens to carry a
  file. Code-sourced sides have no file — the code is the receipt."
  [summary receipts]
  (let [{:keys [file quote]} (get receipts (:id summary))]
    (cond-> {:subject (:subject summary)
             :predicate (:predicate summary)
             :object (:object summary)
             :file file
             :source (source-type-label (:source-type summary))}
      quote (assoc :quote quote))))

(defn pair->finding
  "A conflict pair -> a finding: staleness when a side is code-sourced
  (code ingested first, so the pile claim collided with ground truth) wins
  the classification outright — code vs. anything is about being stale, not
  about who said it. Otherwise, exactly one side instruction-sourced makes
  it an instruction-conflict: the agent's memory at odds with a human-
  maintained file, the marquee signal this tool exists to surface.
  Everything else — note-vs-note, or instruction-vs-instruction — is a
  plain contradiction: opposed claims coexisting with no trust asymmetry
  between the two sides. The judge's verdict rides along when it ran."
  [{:keys [fact candidate verdict]} receipts]
  (cond-> {:kind (cond
                   (or (code-sourced? fact) (code-sourced? candidate)) "stale"
                   (= 1 (count (filter instruction-sourced? [fact candidate])))
                   "instruction-conflict"
                   :else "contradiction")
           :claims [(claim-view fact receipts) (claim-view candidate receipts)]}
    verdict (assoc :verdict (select-keys verdict [:relation :confidence :rationale]))))

(defn dedupe-pairs
  "§9: the same pair can surface via the write-path flag AND the sweep —
  dedupe by unordered fact-id pair before scoring."
  [pairs]
  (->> pairs
       (reduce (fn [acc p]
                 (let [k #{(get-in p [:fact :id]) (get-in p [:candidate :id])}]
                   (if (contains? acc k) acc (assoc acc k p))))
               {})
       vals
       (sort-by #(str (get-in % [:fact :id])))
       vec))

(defn fold-results
  "Pure: per-file assert results -> the audit-side bookkeeping. The write
  path's status vocabulary maps directly onto finding classes (§4): :flagged
  pairs (contradiction/stale), :superseded pairs (disagreement), :reinforced
  occurrences (restatement). Quotes and occurrences key on fact id — the
  receipts live here, never in the store."
  [file-reports]
  (reduce
   (fn [acc {:keys [path kind results]}]
     (reduce
      (fn [a {:keys [status fact candidates superseded quote error-type]}]
        (if (= :error status)
          (update-in a [:errors (or error-type :other)] (fnil inc 0))
          (let [id (:id fact)
                summary (judge/fact->summary fact)
                occ (cond-> {:file path :kind kind} quote (assoc :quote quote))
                a (-> a
                      (update-in [:occurrences id] (fnil conj []) occ)
                      (assoc-in [:summaries id] summary))]
            (case status
              :reinforced a
              (cond-> (assoc-in a [:receipts id] occ)
                (= :superseded status)
                (update :disagreements conj {:fact-id id :superseded (vec superseded)})
                (= :flagged status)
                (update :pairs into (map (fn [c] {:fact summary
                                                  :candidate (judge/fact->summary c)})
                                         candidates)))))))
      acc results))
   {:receipts {} :occurrences {} :summaries {} :pairs [] :disagreements [] :errors {}}
   file-reports))

(defn extraction-noise
  "Inadmissible and ambiguous candidates are counted, not silently dropped
  (§4) — and assert errors are broken out by type, so 'the extractor coined
  an unknown predicate' never masquerades as entity ambiguity."
  [reports errors]
  (cond-> {:rejected (reduce + 0 (map :rejected reports))
           :inadmissible (reduce + 0 (map :inadmissible reports))
           :ambiguous (get errors :ambiguous-entity 0)}
    (seq (dissoc errors :ambiguous-entity))
    (assoc :errors (dissoc errors :ambiguous-entity))))

(defn restatements
  "Facts the pile maintains in more than one place, from the occurrence map:
  every reinforcement is a restatement (§4). A pile claim reinforcing a
  code-sourced fact restates what the code already says — reported with
  :restates-code (deviation noted in the spec doc). When the occurrences
  span both an :instruction file and a :note file — and the fact isn't
  code-sourced — the note is redundantly maintaining what an instruction
  file already states: :restates-instructions, injection spend for a fact
  the pile's own human-maintained half already carries."
  [{:keys [occurrences summaries]}]
  (->> occurrences
       (keep (fn [[id occs]]
               (let [s (summaries id)
                     n (count occs)
                     kinds (set (map :kind occs))]
                 (when (or (> n 1) (code-sourced? s))
                   (cond-> {:kind "restatement"
                            :subject (:subject s)
                            :predicate (:predicate s)
                            :object (:object s)
                            :files (vec (distinct (map :file occs)))
                            :count n}
                     (code-sourced? s) (assoc :restates-code true)
                     (and (not (code-sourced? s))
                          (contains? kinds :instruction)
                          (contains? kinds :note))
                     (assoc :restates-instructions true))))))
       (sort-by (fn [f] [(str (:subject f)) (str (:predicate f)) (str (:object f))]))
       vec))

(defn disagreement-findings
  "Superseded pairs, reported as pairs and never a winner — ingestion order
  decides which claim mechanically 'wins', which is meaningless for truth
  (§9). In markdown, whichever the model reads last silently wins; here it
  is a finding."
  [{:keys [disagreements summaries receipts]}]
  (->> disagreements
       (map (fn [{:keys [fact-id superseded]}]
              {:kind "disagreement"
               :claims (->> (cons fact-id superseded)
                            (keep summaries)
                            (mapv #(claim-view % receipts)))}))
       (sort-by #(str (:subject (first (:claims %)))))
       vec))

(defn alias-clusters
  "Entity resolution self-heals separator/case drift by recording the
  queried name as an alias instead of minting a duplicate — so in the
  throwaway store, an entity the pile referred to by two or more distinct
  names IS a name cluster, even though entity-duplicates (which needs two
  minted entities) can't see it."
  [entities]
  (->> entities
       (keep (fn [e]
               (let [names (vec (distinct (cons (:name e) (:aliases e))))]
                 (when (> (count names) 1) names))))
       (sort-by first)
       vec))

(defn name-clusters
  "entity-duplicates clusters (same normalized name, e.g. type-guarded
  collisions) plus the alias clusters resolution already healed."
  [duplicate-candidates entities]
  (->> (concat (map (fn [c] (mapv :name (:entities c))) duplicate-candidates)
               (alias-clusters entities))
       distinct
       vec))

(def remedy-file-count
  "How many injected files the over-budget remedy names. Two, because the
  names are absolute paths on every source outside the project root: three
  of those wrap a terminal line twice and bury the managed-view breakdown
  printed under them, and the largest two already account for nearly all
  of a window that is only 25 KB wide. The remedy is a pointer at where
  the bytes are, not an inventory."
  2)

(defn- kb [b] (Math/round (/ (double (or b 0)) 1000.0)))

(defn- file-sizes [files]
  (str/join ", " (map (fn [f] (str (:path f) " " (kb (:bytes f)) " KB")) files)))

(defn- injection-remedy
  "The one thing to do about an over-budget injection window, chosen by
  which half of the window is actually spending it.

  Two causes with two different levers, and a scorecard that names the
  overrun without separating them hands the user a red flag and no move:
  claimgraph's own compiled view is trimmed with `claim compile-context --budget`
  (context/default-budget is the view's designed SHARE of the window,
  never the window itself), while a human's instruction files are trimmed
  by hand. :managed-view only when the view is the larger half AND the
  hand-written half still fits — if what a human wrote already fills the
  window on its own, the view could vanish entirely and the run would
  still be over, so the honest lever is the files.

  The suggested compile budget clears the overrun by construction (the
  view's bytes less the overrun) and is capped at the designed share, so
  following it can only ever land inside the window."
  [{:keys [cause over-by managed-bytes own-bytes injected-bytes largest]}]
  (case cause
    :managed-view
    (str "claim compile-context --budget "
         (-> (- managed-bytes over-by) (min context/default-budget) (max 0)
             (quot 500) (* 500))
         "  # " (kb over-by) " KB over: " (kb managed-bytes) " of the "
         (kb injected-bytes) " KB injected is claimgraph's compiled view")
    (str "trim or consolidate " (str/join ", " (map :path largest))
         "  # " (kb over-by) " KB over: " (kb own-bytes) " KB of "
         (kb injected-bytes) " injected is your own instruction files")))

(defn injection-report
  "The byte arithmetic against the ~25 KB injection window — but only for
  what a harness actually injects at session start (§1, collect-sources'
  :injected?), never the whole scanned pile: auto-memory notes besides the
  harness's own inject-file are recalled on demand, not injected, and
  summing them in would inflate the number by everything the window math
  was never trying to describe.

  :injected-bytes is raw on-disk bytes (managed section included — that's
  what the harness actually reads off disk) summed over :injected? sources;
  :managed-bytes is how much of that is claimgraph's own compiled view,
  broken out separately (0 before adoption); :on-demand-bytes is everything
  else scanned — real bytes, real consistency-check surface, just never part
  of what lands in the injection window. :files-over-window names INJECTED
  files individually over the window, by raw bytes — an on-demand file
  being huge is not an injection problem. Claude Code injects at most the
  first ~25 KB / 200 lines of MEMORY.md; this report counts the file as it
  sits on disk, not as truncated at injection.

  An over-budget report carries its own remedy: :over-by (the overrun),
  :largest (the injected files the bytes are actually in, biggest first),
  :cause (:managed-view | :instructions) and :remedy, the one command or
  action that addresses that cause (injection-remedy). A finding whose
  answer is 'this is bad' and nothing else is a number the reader can only
  worry about, and the two causes have nothing in common: on this project
  73% of the injected bytes were claimgraph's own view, which is a knob,
  not a writing problem."
  [files]
  (let [injected (filter :injected? files)
        on-demand (remove :injected? files)
        injected-bytes (reduce + 0 (map :bytes injected))
        managed-bytes (reduce + 0 (map #(or (:managed-bytes %) 0) injected))
        own-bytes (- injected-bytes managed-bytes)
        on-demand-bytes (reduce + 0 (map :bytes on-demand))
        window context/injection-window
        over (filterv #(> (:bytes %) window) injected)
        over-budget? (> injected-bytes window)
        detail {:cause (if (and (> managed-bytes own-bytes) (<= own-bytes window))
                         :managed-view
                         :instructions)
                :over-by (- injected-bytes window)
                :managed-bytes managed-bytes
                :own-bytes own-bytes
                :injected-bytes injected-bytes
                :largest (->> injected
                              (sort-by (juxt (comp - :bytes) :path))
                              (take remedy-file-count)
                              (mapv #(select-keys % [:path :bytes])))}]
    (cond-> {:injected-bytes injected-bytes
             :managed-bytes managed-bytes
             :on-demand-bytes on-demand-bytes
             :window-bytes window
             :over-budget over-budget?}
      (seq over) (assoc :files-over-window (mapv :path over))
      over-budget? (assoc :over-by (:over-by detail)
                          :cause (:cause detail)
                          :largest (:largest detail)
                          :remedy (injection-remedy detail)))))

(defn scorecard
  "Fold everything into the §7 schema: summary is the marketing line,
  findings is the receipts. With the judge on, judged-compatible pairs are
  removed — the false-positive filter that keeps the headline honest.

  :llm and :preflight ride straight through from audit! (a direct pure
  call may omit both — :status stays \"ok\" and neither key appears in the
  output). :llm's own :status decides the top-level
  one: \"partial\" whenever the model-call gate degraded (a per-call error,
  a deferral, a tripped breaker), \"ok\" otherwise. A :no-llm :llm section
  overrides :judge's usual --no-judge note, so a --no-llm run reads as
  needing an extractor rather than the generic raw-report phrasing."
  [{:keys [project files fold judged no-judge clusters entities code noise llm preflight]}]
  (let [pairs (dedupe-pairs
               (if no-judge
                 (:pairs fold)
                 (->> judged
                      (remove #(= :compatible (get-in % [:verdict :relation])))
                      (map #(select-keys % [:fact :candidate :verdict])))))
        pair-findings (mapv #(pair->finding % (:receipts fold)) pairs)
        {stale "stale" contradictions "contradiction"
         instruction-conflicts "instruction-conflict"} (group-by :kind pair-findings)
        disagreements (disagreement-findings fold)
        restated (restatements fold)
        nclusters (name-clusters clusters entities)
        injection (injection-report files)
        summary {:contradictions (count contradictions)
                 :instruction-conflicts (count instruction-conflicts)
                 :stale (count stale)
                 :disagreements (count disagreements)
                 :restatements (count restated)
                 :name-clusters (count nclusters)}
        claims (reduce + 0 (map :claims files))]
    (cond->
      {:status (if (= :partial (:status llm)) "partial" "ok")
       :project project
       :files (mapv #(select-keys % [:path :bytes :claims :warning :skipped :llm]) files)
       :claims claims
       :code code
       :judge (cond
                (= :no-llm (:reason llm))
                {:status :skipped :note "skipped by --no-llm (deterministic checks only)"}
                no-judge
                {:status :skipped :note "raw report — mechanical flags only (--no-judge)"}
                :else
                {:status :ok :judged (count judged)
                 :compatible-removed (count (filter #(= :compatible (get-in % [:verdict :relation]))
                                                    judged))})
       :findings {:contradictions (vec contradictions)
                  :instruction-conflicts (vec instruction-conflicts)
                  :stale (vec stale)
                  :disagreements disagreements
                  :restatements restated
                  :name-clusters nclusters
                  :extraction-noise noise}
       :injection injection
       :summary summary
       ;; Every entry is a thing to run or do, in the order a reader would
       ;; do them: the funnel's own next step first, then whatever a
       ;; finding hands back a lever for. render-pretty prints them all —
       ;; a remedy that only reaches JSON is a remedy nobody at a terminal
       ;; ever sees.
       :next (cond-> [(if (zero? claims)
                        "claim setup  # nothing to migrate — start the graph fresh"
                        "claim setup  # the graph tracks these instead of accumulating them")]
               (:remedy injection) (conj (:remedy injection)))}
      llm (assoc :llm llm)
      preflight (assoc :preflight preflight))))

;; ---------------------------------------------------------------------------
;; Pure: human rendering
;; ---------------------------------------------------------------------------

(defn- pred-str [p] (if (keyword? p) (name p) (str p)))

(defn- claim-str
  "One claim, receipts and all: a file claim shows its :source alongside
  the file (\"CLAUDE.md [instruction]\"), a fileless (code) claim shows
  just the source (\"code\")."
  [{:keys [subject predicate object file quote source]}]
  (str subject " " (pred-str predicate) " " object
       " (" (if file (cond-> file source (str " [" source "]")) (or source "?"))
       (when quote (str ": \"" quote "\"")) ")"))

(defn- finding-line [{:keys [kind claims subject predicate object files
                             restates-code restates-instructions] :as f}]
  (case kind
    "restatement" (str "  restatement: " subject " " (pred-str predicate) " " object
                       " — " (:count f) "x in " (str/join ", " files)
                       (when restates-code " (already what the code says)")
                       (when restates-instructions " (already what your instructions say)"))
    (str "  " kind ": " (str/join (if (= "disagreement" kind) "  vs  " "  <->  ")
                                  (map claim-str claims)))))

(defn render-pretty
  "The §1 scorecard block plus per-finding detail — every number auditable.
  An empty pile short-circuits to a plain answer instead of a wall of
  zeros; so does a --no-llm run, whose deterministic-only head skips every
  finding count a model call would have been needed to produce (zeros
  there would read as \"unchecked\", not \"clean\"). \"partial\" renders
  exactly like \"ok\" — only a genuinely blocked run takes the error path
  — with whatever the model-call gate degraded on appended below the
  ordinary findings as warnings and notes.
  The injection line reports injected KB against the window, with the
  managed-view and on-demand shares called out underneath whenever either is
  nonzero — so the headline number is legible as 'this much actually lands
  in the harness's context window', not a re-statement of the whole scan.
  An over-budget window adds the overrun and the injected files the bytes
  are in, and its remedy rides down to the next: lines with the rest."
  [{:keys [status claims files findings injection summary code next llm] :as sc}]
  (if (not (contains? #{"ok" "partial"} status))
    (str/join "\n" (remove nil? [(str "audit " (name status)
                                      (some->> (:error sc) (str ": ")))
                                 (some->> (:hint sc) (str "hint: "))]))
    (let [n (fn [k] (get summary k 0))
          plural (fn [c s] (if (= 1 c) s (str s "s")))
          no-extractor? (= :no-llm (:reason llm))
          code-line (when (= :ok (:status code))
                      (format "%4d code facts from %d files (%s) — the baseline stale claims are checked against"
                              (:facts code) (:files code)
                              (str/join ", " (map pred-str (:languages code)))))
          injection-lines (remove nil?
                                  [(format "%4d KB injected per session against a ~%d KB window%s"
                                          (kb (:injected-bytes injection))
                                          (kb (:window-bytes injection))
                                          (if (:over-budget injection) "  ** over budget **" ""))
                                   (when (:over-budget injection)
                                     (format "     %d KB over — largest injected: %s"
                                             (kb (:over-by injection))
                                             (file-sizes (:largest injection))))
                                   (when (pos? (:managed-bytes injection))
                                     (format "     (of which %d KB is claimgraph's compiled view)"
                                             (kb (:managed-bytes injection))))
                                   (when (pos? (:on-demand-bytes injection))
                                     (format "     %d KB of on-demand notes scanned, not injected"
                                             (kb (:on-demand-bytes injection))))])
          head (cond
                 (empty? files)
                 (remove nil?
                         ["nothing to audit — no instruction files or auto-memory notes found"
                          (str "     (scanned " (str/join ", " default-scan-set) ",")
                          "      .cursor/rules/* and .claude/rules/*, CLAUDE.md/AGENTS.md up"
                          "      every ancestor directory, each harness's global instructions,"
                          "      and the harness auto-memory notes)"
                          code-line])

                 no-extractor?
                 (remove nil?
                         (concat [(format "%4d files scanned — deterministic checks only (--no-llm)"
                                          (count files))]
                                 injection-lines
                                 [code-line]))

                 :else
                 (remove nil?
                         (concat
                          [(format "%4d claims extracted from %d %s"
                                   claims (count files) (plural (count files) "file"))
                           (format "%4d %-15s (opposed claims coexisting in the pile)"
                                   (n :contradictions) (plural (n :contradictions) "contradiction"))
                           (format "%4d %-15s (agent memory at odds with your instruction files)"
                                   (n :instruction-conflicts)
                                   (plural (n :instruction-conflicts) "instruction conflict"))
                           (format "%4d %-15s (same subject, different values — the last one read silently wins)"
                                   (n :disagreements) (plural (n :disagreements) "disagreement"))
                           (format "%4d %-15s (contradicted by what the code says today)"
                                   (n :stale) "stale")
                           (format "%4d %-15s (the same fact maintained in more than one place)"
                                   (n :restatements) (plural (n :restatements) "restatement"))
                           (format "%4d %-15s %s"
                                   (n :name-clusters) (plural (n :name-clusters) "name cluster")
                                   (if-let [c (first (:name-clusters findings))]
                                     (str "(" (str/join " / " c) ")")
                                     "(no drift detected)"))]
                          injection-lines
                          [code-line])))
          details (if no-extractor?
                    []
                    (concat (map finding-line (:contradictions findings))
                            (map finding-line (:instruction-conflicts findings))
                            (map finding-line (:stale findings))
                            (map finding-line (:disagreements findings))
                            (map finding-line (:restatements findings))
                            (map #(str "  name cluster: " (str/join " / " %))
                                 (:name-clusters findings))))
          per-file-errors (keep (fn [f] (when (= :error (get-in f [:llm :status]))
                                          (str "  warning: " (:path f) ": extraction failed ("
                                               (name (get-in f [:llm :error-type])) ")")))
                                files)
          gate-errors (:errors llm)
          error-freq-line (when (seq gate-errors)
                            (str "  warning: " (count gate-errors) " model call"
                                 (when (not= 1 (count gate-errors)) "s")
                                 " failed ("
                                 (str/join ", " (map (fn [[k v]] (str (name k) ": " v))
                                                     (frequencies (map :error-type gate-errors))))
                                 ")"))
          tripped-line (when (:tripped? llm)
                        (str "  warning: model calls stopped after " trip-threshold
                             " consecutive failures — the scorecard is partial"))
          deferred-line (when (pos? (or (:deferred llm) 0))
                         (str "  note: " (:deferred llm) " model calls were deferred by the --budget "
                              (:allowed llm) " cap — raise --budget for full coverage"))
          notes (cond-> []
                  no-extractor?
                  (conj (str "  LLM checks skipped: contradictions, instruction conflicts, "
                             "disagreements, stale claims, restatements and name drift need an "
                             "extractor — run again without --no-llm"))
                  (= :skipped (:status code))
                  (conj (str "  staleness prong skipped: " (:note code)))
                  (seq (keep :warning files))
                  (into (map #(str "  warning: " (:warning %)) (filter :warning files)))
                  (seq per-file-errors) (into per-file-errors)
                  error-freq-line (conj error-freq-line)
                  tripped-line (conj tripped-line)
                  deferred-line (conj deferred-line))]
      (str/join "\n" (concat head
                             (when (seq details) (cons "" details))
                             (when (seq notes) (cons "" notes))
                             (cons "" (map #(str "next: " %) next)))))))

;; ---------------------------------------------------------------------------
;; Shell: prerequisites, source scan, pipeline
;; ---------------------------------------------------------------------------

(defn check-prerequisites
  "Audit's variant of setup/check-prerequisites (spec §5): pod-free by
  design, so dtlv is not checked at all; the extractor is the hard
  requirement — without it there is nothing to extract claims with.
  :which is injectable for tests (fn name -> path-or-nil)."
  [{:keys [extractor which]}]
  (let [which (or which #(some-> (fs/which %) str))
        cmd (llm/command extractor)
        bin (first (str/split cmd #"\s+"))
        found (boolean (which bin))]
    (merge
     {:status (if found :ok :error)
      :bb (or (System/getProperty "babashka.version") (which "bb") "not found")
      :extractor {:command cmd :found found}}
     (when-not found
       {:error (str "extractor '" bin "' is not on PATH — audit extracts claims with it")
        :hint (str "install and authenticate the claude CLI, or point --extractor / "
                   "$CLAIMGRAPH_LLM_CMD at any prompt-on-stdin command; or run "
                   "`claim audit --no-llm` for the deterministic checks alone")}))))

(defn- source-label [root p]
  (let [abs (fs/canonicalize p)]
    (if (fs/starts-with? abs root) (str (fs/relativize root abs)) (str abs))))

(defn- utf8-bytes [^String s] (count (.getBytes s "UTF-8")))

(defn- read-source
  "One pile file -> {:path :content :bytes :managed-bytes :skipped :hash}.
  Unlike read-notes, nothing is dropped here for stripping to nothing — a
  harness injects the file as it sits on disk, managed section and all, so
  the injection arithmetic needs every source that exists, not just the ones
  with something left to extract.

  :bytes is the RAW on-disk size in UTF-8 bytes — what a harness actually
  reads off disk and injects — never the post-strip size. :managed-bytes is
  how much of that the managed-section strip (the echo guard: never audit
  our own compiled view back at ourselves) removed, measured against the
  UNTRIMMED stripped content so ordinary surrounding whitespace in a file
  with no managed section at all isn't miscounted as managed spend. :content
  is the stripped, TRIMMED text — what extraction actually sees — and
  :skipped is true when that content is blank:
  a file entirely claimgraph's own compiled view stays in the source list
  (it still counts toward injection) but carries the marker the caller uses
  to skip it for extraction, same rule read-notes applies by dropping it
  outright."
  [root p]
  (when (fs/regular-file? p)
    (let [raw (slurp (str p))
          stripped (harness/strip-managed-section raw)
          content (str/trim stripped)]
      {:path (source-label root p)
       :content content
       :bytes (utf8-bytes raw)
       :managed-bytes (- (utf8-bytes raw) (utf8-bytes stripped))
       :skipped (str/blank? content)
       :hash (notes/content-hash content)})))

(def ^:private kind-rank
  "Ingestion order within the pile scan, once code has already gone first
  (§4 stage 3): instructions before notes, so the canonical collision
  direction is a note arriving against an already-standing instruction —
  the collision decide-assert turns into the instruction-conflict finding."
  {:instruction 0 :note 1})

(defn- ancestor-dirs
  "Every directory from root's parent up to the filesystem root, inclusive —
  the walk Claude Code performs for CLAUDE.md/CLAUDE.local.md at session
  start, and the Codex family performs for AGENTS.md. :limit, given, stops
  the walk at that directory (inclusive) instead of the real filesystem
  root — collect-sources' test-isolation seam, so a temp-tree test never
  reads a developer's actual ancestor directories; nil walks the real tree."
  [root limit]
  (let [limit (some-> limit fs/canonicalize)]
    (loop [d (fs/parent root) acc []]
      (if (or (nil? d) (and limit (not (fs/starts-with? d limit))))
        acc
        (recur (fs/parent d) (conj acc d))))))

(defn collect-sources
  "The pile (spec §3, extended 2026-08-07): every instruction file a coding
  harness actually discovers at session start, plus the auto-memory notes
  dir and --file/--dir extras.

  :instruction sources (human-maintained, mint at :user-assertion trust):
  default-scan-set at the project root; .cursor/rules/* and
  .claude/rules/*.md (globbed, guarded by fs/directory?); the ancestor
  walk — ancestor-scan-set checked at every directory from the project
  root's parent up to the filesystem root, or up to :ancestor-limit when
  given (ancestor-dirs; the test-isolation seam — nil walks the real tree);
  each configured harness's own :global-instructions (e.g. Claude Code's
  $CLAUDE_CONFIG_DIR/CLAUDE.md and its rules/*.md); and the managed-policy
  CLAUDE.md (:managed-paths, defaulting to managed-policy-paths filtered to
  whichever OS path exists on this machine).

  :note sources (unknown or agent authorship, mint at :agent-note): the
  harness auto-memory notes dir (resolved exactly like ingest-notes —
  honors every override and $CLAUDE_CONFIG_DIR) and --file/--dir extras.
  Extras are typed :note rather than :instruction because we don't know who
  wrote them — a --file could be anything a caller points at — and audit's
  bias is maximal conflict surfacing: a lower-trust source flags a
  collision instead of silently winning it, never the reverse.

  A live limitation: @-imports inside a CLAUDE.md (`@path/to/file`) are not
  followed — an imported file is invisible to this scan even though Claude
  Code injects it too.

  Sorted by [kind-rank, path]: code ingests first, then instructions before
  notes (kind-rank) — fully deterministic, path-sorted within each kind.
  Deduped by canonical path, first :kind seen wins, so the same file
  reachable by two routes (an ancestor walk landing on a CLAUDE.md that a
  $CLAUDE_CONFIG_DIR override also names, say) counts once.

  Every source also gets :injected? (§1): true for every :instruction
  source — each is injected wholesale by its harness (CLAUDE.md-family,
  copilot-instructions, .cursorrules; .cursor/rules and .claude/rules are
  technically glob-conditional, but v1 counts them as injected too,
  accepted noise in the §9 spirit) — and true for exactly one :note source,
  the harness's own inject-file (harness/inject-target, honoring an
  optional :inject-file override the same way :notes-dir already does).
  Every other note and every --file/--dir extra is on-demand: recalled,
  never injected — so the injection number never counts notes that never
  reach a session at all."
  [{:keys [project files dirs harness notes-dir inject-file ctx
           ancestor-limit managed-paths]}]
  (let [root (fs/canonicalize (or project "."))
        h (harness/resolve-harness harness)
        resolved-ctx (or ctx (harness/env-ctx))
        ndir (harness/notes-path h {:dir notes-dir :project (str root) :ctx resolved-ctx})
        target-raw (harness/inject-target h ndir inject-file)
        target (when (fs/exists? target-raw) (fs/canonicalize target-raw))
        rules-dir (fs/path root ".cursor" "rules")
        claude-rules-dir (fs/path root ".claude" "rules")
        global-instructions ((or (:global-instructions h) (constantly [])) resolved-ctx)
        managed (or managed-paths managed-policy-paths)
        tag (fn [kind paths] (map (fn [p] {:path p :kind kind}) paths))
        tagged (concat
                (tag :instruction (map #(fs/path root %) default-scan-set))
                (when (fs/directory? rules-dir) (tag :instruction (fs/glob rules-dir "*")))
                (when (fs/directory? claude-rules-dir)
                  (tag :instruction (fs/glob claude-rules-dir "*.md")))
                (tag :instruction (mapcat (fn [d] (map #(fs/path d %) ancestor-scan-set))
                                          (ancestor-dirs root ancestor-limit)))
                (tag :instruction (map fs/path global-instructions))
                (tag :instruction (map fs/path managed))
                (when (fs/directory? ndir)
                  (tag :note (fs/glob ndir (or (:note-glob h) "**.md"))))
                (tag :note (map fs/path files))
                (tag :note (mapcat #(when (fs/directory? %) (fs/glob % "**.md"))
                                   (map fs/path dirs))))]
    (->> tagged
         (filter #(fs/exists? (:path %)))
         (map #(update % :path fs/canonicalize))
         ;; distinct by canonical path, first :kind seen wins (instructions
         ;; are concat'd first, so an accidental overlap favors the higher
         ;; trust rather than silently downgrading it)
         (reduce (fn [seen {:keys [path] :as t}]
                   (if (contains? seen path) seen (assoc seen path t)))
                 {})
         vals
         (keep (fn [{:keys [path kind]}]
                 (some-> (read-source root path)
                         (assoc :kind kind
                                :injected? (boolean (or (= kind :instruction)
                                                        (and target (= path target))))))))
         (sort-by (juxt (comp kind-rank :kind) :path))
         vec)))

(defn- ingest-code!
  "The staleness prong's ground truth: mechanical code facts land at 0.95 /
  source-type :code BEFORE any pile claim, so a colliding pile claim flags
  against a code-sourced candidate. Languages come from the analyzer
  registry (walking the project root, plus any code-analyzers config);
  skipping is honest, not silent."
  [s project no-code]
  (cond
    no-code {:status :skipped :note "skipped by --no-code"}
    :else
    (let [detected (code/detect project)]
      (if (empty? detected)
        {:status :skipped
         :note (str "no analyzable sources detected (analyzers: "
                    (str/join ", " (map (comp name :id) (code/registry)))
                    ", plus any code-analyzers config) — every other finding class still applies")}
        (let [r (code/ingest! s {:dir (str project) :scope "code"})]
          (cond-> {:status :ok :files (:files r) :facts (:total r) :ref (:ref r)
                   :languages (mapv (comp name :id) detected)}
            (:analyzers r) (assoc :analyzers (:analyzers r))))))))

(defn- code-baseline?
  "True when a currently-valid CODE-sourced fact already exists for this
  claim's (subject, predicate) — audit-file!'s pre-assert guard, deciding
  whether THIS assert needs :on-conflict :flag forced.

  WHY this is load-bearing, not decorative: :instruction mints at
  :user-assertion, which TIES :code at trust rank 3 (logic/source-trust).
  decide-assert's outranked defense only fires when the EXISTING fact's
  trust is STRICTLY greater than the new one's, so equal trust falls
  through to a clean supersede. Without this guard, an instruction claim on
  a cardinality-:one predicate (defined-in is the shape) would silently
  close the code fact's validity interval — which both misclassifies the
  pair (a disagreement instead of the staleness it actually is) and
  removes the code baseline from the store, so every LATER pile claim on
  that (subject, predicate) collides against the instruction instead of
  ground truth. Forcing :flag pins code as the un-supersedable ground
  truth this whole prong exists to check pile claims against.

  Inert everywhere else: decide-assert only consults :on-conflict once it
  reaches the conflict branch, so this is a no-op for a duplicate
  (reinforce short-circuits earlier — restates-code is unaffected) and for
  a plain insert. The exclusion-group path (stance predicates like prefers
  / decided-against) never needs it either: code analyzers don't emit
  those predicates, so a code fact never appears among a claim's exclusion
  antagonists, and stance collisions between commitments already flag via
  conflict-policy regardless of trust.

  Resolves the subject the same way assert-fact would (exact/alias/
  normalized match), but un-throwing: a subject audit hasn't ensured yet
  trivially has no code facts, and this check is advisory — it must never
  fail the claim over what it's merely picking an :on-conflict for."
  [s subject predicate]
  (boolean
   (when-let [{:keys [entity via]} (core/resolve-entity s {:name subject})]
     (when (not= :ambiguous via)
       (some #(= :code (:source-type %))
             (:facts (core/get-facts s {:entity (:name entity) :predicate predicate})))))))

(def preflight-prompt
  "The one round-trip preflight! spends to prove the extractor actually
  answers, before audit! commits any of its budget to real extraction."
  "Reply with the single word: ok")

(defn preflight!
  "One real extractor round-trip, run before the pipeline spends anything —
  so an unauthenticated CLI or a rate-limited account blocks the run up
  front, in the seconds a single call takes, instead of minutes into a
  budget that was never going to land anything.

  NOT metered against --budget: the budget bounds the pipeline's own
  extraction and judge calls, and this is the fixed +1 call the README
  already documents as the cost of running audit at all.

  run is called exactly like every other model call (prompt -> reply
  string). A blank or nil reply is as much a failure here as a thrown
  exception — an extractor that answers nothing is exactly as useless as
  one that errors."
  [run]
  (try
    (let [started (System/nanoTime)
          reply (run preflight-prompt)
          ms (/ (- (System/nanoTime) started) 1e6)]
      (if (str/blank? reply)
        {:status :error :error "the extractor answered the preflight prompt with empty output"}
        {:status :ok :ms ms}))
    (catch clojure.lang.ExceptionInfo e
      {:status :error :error (ex-message e) :error-type (:type (ex-data e))})
    (catch Exception e
      {:status :error :error (str e)})))

(defn- gate-error-type
  [e]
  (if (instance? clojure.lang.ExceptionInfo e)
    (or (:type (ex-data e)) :unexpected)
    :unexpected))

(defn- spend-decision
  "Pure: against this snapshot of a gate's state, may one more model call
  be spent — and when it may not, which of the two reasons applies?

  Pure and snapshot-taking so llm-gate can decide and count in ONE swap!:
  audit extracts concurrently, and a gate that reads the counter, decides,
  and then writes it lets every thread in flight see the same last
  affordable slot and take it, spending as many calls as there are threads
  against a cap of one. --budget is the user's money and the user's
  minutes, so it is a hard cap, not a target.

  A tripped breaker outranks an exhausted budget in the label because the
  two ask for different responses: a budget is a knob to raise, a dead
  extractor is a fault to fix, and a run that reports the knob when the
  truth is the fault sends the reader to spend more on nothing."
  [{:keys [spent consecutive]} budget]
  (cond
    (>= consecutive trip-threshold) {:spend? false :reason :tripped}
    (>= spent budget) {:spend? false :reason :budget-exhausted}
    :else {:spend? true}))

(defn llm-gate
  "One gate in front of every model call audit! makes, extraction and judge
  alike, so both draw from the SAME shared budget (curate!'s pattern: one
  call counter for a whole run, never a per-stage carve-out) and fail the
  same way: a thrown call is isolated to its own report entry instead of
  killing the run, and a breaker (trip-threshold consecutive failures)
  stops spending once the extractor is clearly dead rather than burning
  the rest of the budget one 120s timeout at a time.

  opts: :extract-run (prompt -> reply, the extraction shell-out)
        :judge-run (prompt -> reply, the judge shell-out — kept separate
                    from :extract-run because an injected :extractor-fn
                    must never silently stand in for the judge and vice
                    versa: audit! wires each independently)
        :budget (calls this gate may spend before every further call is
                 deferred rather than attempted)

  Returns:
    :spend! — 0-arg; true + :spent++ when a call is affordable, false +
              :deferred++ when the budget is exhausted or the breaker has
              tripped. The exact contract judge-conflicts!/sweep-conflicts!
              already accept, so the judge stage spends from this same
              gate. Decision and counter move in one swap! (spend-decision)
              so concurrent callers serialise into distinct verdicts.
    :defer! — 0-arg; :deferred++ for work this run decided up front it
              could not afford, so a source select-for-extraction left out
              is counted in the same total as one the gate turned away.
              The alternative — letting every source ask the gate — hands
              the last affordable slot to whichever thread wins the race
              instead of to the file worth spending it on.
    :call!  — (fn [label prompt]) for the extraction path: spends, then
              runs :extract-run guarded. {:status :ok :text s} on success;
              {:status :skipped :reason :budget-exhausted|:tripped} without
              calling; {:status :error :error-type kw :message s} on a
              thrown exception (:type from ex-data for an ExceptionInfo —
              llm/complete! throws :llm-command-failed /
              :llm-command-timeout — else :unexpected).
    :judge-fn — (fn [prompt]), ready to hand straight to
              judge-conflicts!/sweep-conflicts!'s own :judge-fn: runs
              :judge-run guarded with NO spend check of its own (those fns
              already consult :spend! before calling it) and returns the
              reply text, or nil on a thrown exception. A nil reply parses
              as an unparseable verdict inside judge.clj, which records
              nothing and kills nothing.
    :report — 0-arg; {:allowed :spent :deferred :errors :tripped?}."
  [{:keys [extract-run judge-run budget]}]
  (let [state (atom {:spent 0 :deferred 0 :errors [] :consecutive 0})
        tripped? (fn [] (>= (:consecutive @state) trip-threshold))
        ;; swap-vals! hands back the state the successful CAS applied to,
        ;; so re-reading spend-decision on it yields exactly the verdict
        ;; the counter was moved by — never a second, racier look.
        decide! (fn []
                  (let [[before _] (swap-vals!
                                    state
                                    (fn [st]
                                      (if (:spend? (spend-decision st budget))
                                        (update st :spent inc)
                                        (update st :deferred inc))))]
                    (spend-decision before budget)))
        guarded-run (fn [run label prompt]
                      (try
                        (let [text (run prompt)]
                          (swap! state assoc :consecutive 0)
                          {:status :ok :text text})
                        (catch Exception e
                          (let [error-type (gate-error-type e)]
                            (swap! state (fn [st]
                                           (-> st
                                               (update :errors conj
                                                       {:at label :error-type error-type
                                                        :message (ex-message e)})
                                               (update :consecutive inc))))
                            {:status :error :error-type error-type :message (ex-message e)}))))]
    {:spend! (fn [] (:spend? (decide!)))
     :defer! (fn [] (swap! state update :deferred inc) nil)
     :call! (fn [label prompt]
              (let [{:keys [spend? reason]} (decide!)]
                (if spend?
                  (guarded-run extract-run label prompt)
                  {:status :skipped :reason reason})))
     :judge-fn (fn [prompt] (:text (guarded-run judge-run "judge" prompt)))
     :report (fn []
               (let [st @state]
                 {:allowed budget :spent (:spent st) :deferred (:deferred st)
                  :errors (:errors st) :tripped? (tripped?)}))}))

(defn- code-progress-line
  [{:keys [status files facts note languages]}]
  (case status
    :ok (format "code: %d facts from %d files (%s)"
                facts files (str/join ", " (map pred-str languages)))
    :skipped (str "code: skipped (" note ")")
    (str "code: " (name (or status :unknown)))))

(defn- extract-outcome-line
  [i total path {:keys [claims llm]}]
  (let [prefix (format "extract [%d/%d] %s" i total path)]
    (cond
      (= :error (:status llm)) (str prefix ": failed (" (name (:error-type llm)) ")")
      (contains? #{:budget-exhausted :tripped} (:reason llm))
      (str prefix ": deferred (" (name (:reason llm)) ")")
      :else (str prefix ": " claims " claims"))))

(defn- skipped-report
  "The report for a source read-source marked :skipped — stripped content
  blank, usually a note that is entirely claimgraph's own compiled view.
  It never reaches extraction (nothing to extract, and re-extracting our
  own compiled view is exactly the echo loop the guard exists to prevent),
  but it must not vanish from the file list: its raw bytes are still what
  the harness reads and injects, so injection-report still needs to see
  them."
  [{:keys [path kind bytes managed-bytes injected?]}]
  {:path path :kind kind :bytes bytes :managed-bytes managed-bytes
   :injected? injected? :claims 0 :results [] :rejected 0 :inadmissible 0
   :skipped true})

(defn- unextracted-report
  "skipped-report's shape for a source --no-llm left alone on purpose:
  scanned (its bytes still count toward injection), but no extractor call
  was ever made and no episode was ever opened — the :llm reason is what
  tells this apart from an ordinary echo-guard skip."
  [src]
  (assoc (skipped-report src) :llm {:status :skipped :reason :no-llm}))

(defn extraction-order
  "The pile, most worth spending a model call on first (§2): :injected?
  sources ahead of on-demand ones, then larger files ahead of smaller
  ones, with the canonical [kind-rank, path] order as the tiebreak so the
  sort is total and two runs over an unchanged pile select the same set.

  Injected first because an injected file is paid for on EVERY session —
  a contradiction inside one costs context and misleads the agent
  continuously, where the same contradiction inside an on-demand note
  costs only the sessions that happen to recall it. Larger second because
  bytes are where claims are: nothing else about a file is visible before
  it has been read.

  SELECTION ONLY. Whatever this picks is processed in the canonical order,
  never this one — value decides who gets a call, ingestion order decides
  what a collision between two claims MEANS, and folding the two together
  would let a note's claims land in the store ahead of an instruction
  file's and turn the marquee instruction-conflict finding into an
  ordinary contradiction."
  [sources]
  (sort-by (juxt #(if (:injected? %) 0 1)
                 #(- (long (or (:bytes %) 0)))
                 (comp kind-rank :kind)
                 :path)
           sources))

(defn select-for-extraction
  "The set of source paths a budget of `budget` calls is actually spent on:
  the most valuable that many (extraction-order).

  Letting the gate truncate instead makes the cut alphabetical — the
  pipeline walks the canonical [kind-rank, path] order, so `--budget 20`
  against forty notes audits the first twenty BY NAME, which has nothing
  to do with which twenty carry the injection cost or the claims. Deciding
  up front is also what keeps the choice deterministic once extraction
  runs concurrently: six threads asking one gate hand the last affordable
  slot to whichever thread wins the race.

  Sources outside the set never call the gate at all; they report deferred
  against the gate's :defer!, so the run's deferred count still names
  every file the budget did not reach."
  [sources budget]
  (into #{} (comp (take (max 0 budget)) (map :path)) (extraction-order sources)))

(defn- prompt-snapshot
  "What the extraction prompt knows about the store, read once per wave:
  the stable predicate vocabulary and the entity roster.

  A wave's calls all run concurrently, so they share one snapshot instead
  of each seeing whatever the files before them established. The waves are
  cut on :kind precisely to bound what that costs: every instruction file
  extracts AND applies before a single note's prompt is built, so a note's
  roster still carries every entity the code pass and the instruction
  files minted — the names that anchor the graph. Only enrichment WITHIN
  a wave is traded away, and the roster's job is to stop synonym drift
  against established names, not to propagate a name coined seconds ago in
  a sibling file."
  [s]
  (let [entities (store/-list-entities s {})]
    {:predicates (store/-list-predicates s {:status :stable})
     :roster (session/entity-roster entities (store/-entity-usage s)
                                    session/roster-limit)}))

(defn- extract-file!
  "The EXTRACT half of one file's audit: build the prompt from the wave's
  snapshot and spend one gated model call. Touches the gate and nothing
  else — no store reads, no store writes, no ordering of its own — which
  is exactly what makes it safe to run on a pool while every store effect
  stays serial on the calling thread. Returns the gate's result verbatim:
  {:status :ok :text s}, or the :skipped / :error map it hands back
  instead."
  [gate {:keys [path content kind]} {:keys [predicates roster]}]
  ((:call! gate) path (extraction-prompt path content predicates roster kind)))

(defn- apply-extraction!
  "The APPLY half: one file's raw extraction pushed through the full
  conflict machinery, one episode per file (ref audit:<path>@<hash>).
  Every store read and every store write of the pile pass lives here, and
  audit-wave! calls it SERIALLY in the canonical [kind-rank, path] order.

  That order is the invariant the concurrency is arranged around: a note
  arriving against an already-standing instruction is what decide-assert
  turns into the marquee instruction-conflict finding, and the same two
  claims in the other arrival order are an ordinary contradiction between
  equals. Extraction may finish in any order it likes; assertion may not.

  Entities and predicates are read fresh here rather than taken from the
  wave's prompt snapshot, so admission still screens against everything
  the files before this one established — the half of the per-file
  recompute that decides what is admissible, as opposed to the half that
  merely suggests names to a prompt.

  The file's :kind (§1, collect-sources) decides its trust for this whole
  pass — it framed the extraction prompt, and here it sets
  prepare-audit-facts' ceiling and source-type and stamps the episode —
  so a fact born here carries the same trust its file would carry if a
  human had typed it straight into `claim assert`. Before each assert,
  code-baseline? checks whether a code fact already answers this claim's
  (subject, predicate); if so the assert is forced to :on-conflict :flag,
  pinning code as ground truth the pile can collide with but never
  quietly overwrite."
  [s {:keys [path content hash bytes managed-bytes injected? kind]} text]
  (let [entities (store/-list-entities s {})
        predicates (store/-list-predicates s {:status :stable})
        {:keys [facts rejected]} (prepare-audit-facts
                                  (session/parse-extraction text) kind)
        {:keys [admitted inadmissible]} (logic/screen-candidates
                                         facts (logic/admission-ctx entities predicates))
        ep (core/open-episode s {:source-type (mapped-source-type kind)
                                 :ref (str "audit:" path "@" hash)})
        results (mapv (fn [f]
                        (let [quote (:quote f)
                              fact (dissoc f :quote :admission-score)
                              on-conflict (when (code-baseline? s (:subject fact) (:predicate fact))
                                            :flag)]
                          (try
                            (-> (core/assert-fact s (assoc fact :episode (:id ep)
                                                          :on-conflict on-conflict))
                                (select-keys [:status :fact :candidates :superseded])
                                (assoc :quote quote))
                            (catch clojure.lang.ExceptionInfo e
                              {:status :error :message (ex-message e)
                               :error-type (:type (ex-data e)) :input fact}))))
                      admitted)
        content-bytes (utf8-bytes content)]
    (core/close-episode s {:episode (:id ep)
                           :summary (str "audit " path "@" hash ": "
                                         (count admitted) " claims ("
                                         (pr-str (frequencies (map :status results))) "), "
                                         (count rejected) " rejected, "
                                         (count inadmissible) " inadmissible")})
    (cond-> {:path path :kind kind :bytes bytes :managed-bytes managed-bytes
             :injected? injected? :claims (count admitted)
             :results results
             :rejected (count rejected)
             :inadmissible (count inadmissible)}
      (> content-bytes file-warn-bytes)
      (assoc :warning (str path " is " content-bytes
                           " bytes — extraction degrades above ~50 KB/file")))))

(defn- file-report!
  "One source's report from its extraction result. A non-:ok result
  (:skipped or :error) never opens an episode or touches the store at all:
  it folds straight into skipped-report's shape with the gate's own result
  attached as :llm, same as a source read-source marked :skipped, so a
  caller reading :files never has to special-case why a file carries zero
  claims."
  [s src call-result]
  (if (= :ok (:status call-result))
    (apply-extraction! s src (:text call-result))
    (assoc (skipped-report src) :llm (dissoc call-result :text))))

(defn- run-bounded!
  "Map f over xs on a bounded thread pool, returning results in xs' order
  however the tasks interleave.

  Bounded because each task is a subprocess, not a computation: an
  unbounded fan-out over a large pile forks one `claude -p` per file at
  once. The pool is shut down on every exit path — a fixed pool's threads
  are non-daemon, so a leaked executor outlives the run and keeps the
  process alive after it has printed its answer — and a task's exception
  is rethrown at its own cause, so a failure inside a worker reads exactly
  as it would have on the calling thread. One task, or a pool of one,
  skips the executor entirely rather than paying for threads to serialise
  work that was already serial."
  [n xs f]
  (if (or (<= n 1) (< (count xs) 2))
    (mapv f xs)
    (let [pool (java.util.concurrent.Executors/newFixedThreadPool n)]
      (try
        (let [futures (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))) xs)]
          (mapv (fn [^java.util.concurrent.Future fut]
                  (try (.get fut)
                       (catch java.util.concurrent.ExecutionException e
                         (throw (or (.getCause e) e)))))
                futures))
        (finally (.shutdownNow pool))))))

(defn- audit-wave!
  "One wave of the pile pass: every source in it EXTRACTS concurrently
  against a single prompt snapshot, then APPLIES serially in the order
  given.

  This split is what makes a real run finish. An extraction call is a ~70s
  wait on a subprocess that reads no store state, so the waits overlap for
  free; assertion is the whole conflict machinery against one in-memory
  store, and its order is load-bearing (apply-extraction!), so it stays on
  the calling thread. Completion order and apply order are therefore
  independent by construction — the file that answers first does not get
  to assert first.

  A source outside the run's selected set never reaches the extractor: it
  spends nothing, records its deferral against the gate, and reports the
  same shape a gate-refused call would. Deciding that here rather than at
  the gate is what makes the choice value-based instead of whichever-
  thread-got-there-first (select-for-extraction).

  Progress narrates twice per file, as it always has. The start line comes
  from the worker as its call actually begins, so start lines interleave
  with whatever else is in flight; the outcome line comes from the apply
  loop, in canonical order, because that is where the claim count exists.
  Both carry an index assigned from the canonical order and never from
  arrival, so a file's two lines always name the same [i/total] and the
  counter reads as a position rather than a race."
  [s gate sources {:keys [concurrency selected progress index total]}]
  (let [snapshot (prompt-snapshot s)
        call-results (run-bounded!
                      concurrency sources
                      (fn [src]
                        (progress (format "extract [%d/%d] %s (%d KB)…"
                                          (index (:path src)) total (:path src)
                                          (kb (:bytes src))))
                        (if (contains? selected (:path src))
                          (extract-file! gate src snapshot)
                          (do ((:defer! gate))
                              {:status :skipped :reason :budget-exhausted}))))]
    (mapv (fn [src call-result]
            (let [report (file-report! s src call-result)]
              (progress (extract-outcome-line (index (:path src)) total (:path src) report))
              report))
          sources call-results)))

(defn audit!
  "The whole §4 pipeline, inside one throwaway in-memory store: preflight
  the extractor, collect the pile, seed, ingest code ground truth, extract
  + assert every pile claim under one shared model-call budget (llm-gate),
  sweep + judge from that same budget (report-only — NEVER :resolve; audit
  fixes nothing), fold into the scorecard. Writes nothing anywhere.

  opts: :project (default cwd) :files/:dirs (extra sources)
        :harness (default claude-code) :notes-dir / :inject-file (override
        the resolved auto-memory dir / which note in it counts as injected,
        same precedence as everywhere else) :ctx (harness-resolution
        context, injectable)
        :ancestor-limit / :managed-paths (collect-sources' test-isolation
        seams — pass through untouched; nil/absent gives the real scan)
        :no-code :no-judge
        :no-llm (skip every model call outright — extraction AND judge,
                 with it the extractor prerequisite and the preflight; the
                 deterministic checks (file scan, injection arithmetic,
                 code baseline) still run in full)
        :budget (model calls for the WHOLE run — extraction and judge draw
                 from one shared pool, curate!'s pattern; default
                 default-call-budget. Which sources the cap is spent on is
                 decided by value, not by name: select-for-extraction)
        :concurrency (extraction calls in flight at once; default
                 default-extract-concurrency, 1 for a strictly serial run,
                 irrelevant under :no-llm where no call is made at all)
        :extractor (command string) :extractor-fn / :judge-fn (injectable,
        tests — an injected :extractor-fn is never used for judging, and
        vice versa)
        :which (prerequisite lookup, injectable)
        :preflight-fn (injectable, tests; default preflight!)
        :progress-fn (fn [line]) called with human-readable narration as
        the pipeline runs — the preflight, the code baseline, each file's
        extraction, sweep/judge, the final budget summary; default a
        no-op. cli.clj wires this to stderr so `claim audit | jq` still
        narrates at a terminal. Called from the extraction pool as well as
        the calling thread, so start lines interleave with each other while
        each file still reports its start and its outcome exactly once
        (audit-wave!)."
  [{:keys [project harness files dirs notes-dir inject-file ctx no-code no-judge
           extractor extractor-fn judge-fn which ancestor-limit managed-paths
           budget concurrency no-llm progress-fn preflight-fn]}]
  (let [project (str (fs/canonicalize (or project ".")))
        no-judge (or no-judge no-llm)
        progress (or progress-fn (fn [_]))
        prereqs (when-not (or extractor-fn no-llm)
                  (check-prerequisites {:extractor extractor :which which}))]
    (if (= :error (:status prereqs))
      {:status "blocked" :project project :prerequisites prereqs
       :error (:error prereqs) :hint (:hint prereqs)}
      (let [run (or extractor-fn (partial llm/complete! (llm/command extractor)))
            ;; An injected :extractor-fn skips the preflight unless a test
            ;; injects :preflight-fn explicitly — an injected fn is already
            ;; known-good, and a real extractor is verified before spending
            ;; anything on it.
            run-preflight? (and (not no-llm) (or preflight-fn (not extractor-fn)))
            pf (when run-preflight?
                 (progress (str "preflight: extractor round-trip ("
                               (llm/command extractor) ")…"))
                 (let [r ((or preflight-fn preflight!) run)]
                   (when (= :ok (:status r))
                     (progress (format "preflight: ok (%.1fs)" (/ (double (:ms r)) 1000.0))))
                   r))]
        (if (= :error (:status pf))
          {:status "blocked" :project project :prerequisites prereqs :preflight pf
           :error (str "the extractor failed its preflight call: " (:error pf))
           :hint (str "see the failure yourself: echo ok | " (llm/command extractor)
                      "  — or run `claim audit --no-llm` for the deterministic checks alone")}
          (let [sources (collect-sources {:project project :files files :dirs dirs
                                          :harness harness :notes-dir notes-dir
                                          :inject-file inject-file :ctx ctx
                                          :ancestor-limit ancestor-limit
                                          :managed-paths managed-paths})
                s (mem/create)
                _ (core/seed! s)
                code (ingest-code! s project no-code)
                _ (progress (code-progress-line code))
                resolved-budget (max 0 (if (number? budget) (long budget) default-call-budget))
                ;; Both extraction and judge spend from ONE gate — the
                ;; curate! pattern — so :judge-run is wired independently
                ;; of :extract-run: an injected :extractor-fn must never
                ;; silently stand in for the judge, only an injected
                ;; :judge-fn does that.
                gate (when-not no-llm
                       (llm-gate {:extract-run run
                                  :judge-run (or judge-fn (partial llm/complete! (llm/command extractor)))
                                  :budget resolved-budget}))
                resolved-concurrency (max 1 (if (number? concurrency)
                                              (long concurrency)
                                              default-extract-concurrency))
                ;; A source read-source marked :skipped strips to nothing —
                ;; no extractor call, no episode — but it still counts
                ;; toward injection (skipped-report), the whole point of
                ;; keeping it in collect-sources.
                extractable (vec (remove :skipped sources))
                total (count extractable)
                ;; Positions come from the canonical order, so a file's
                ;; start line and its outcome line agree even though one is
                ;; emitted from the pool and the other from the apply loop.
                index (into {} (map-indexed (fn [i src] [(:path src) (inc i)])) extractable)
                selected (when gate (select-for-extraction extractable resolved-budget))
                ;; One wave per :kind, in kind-rank order — instructions
                ;; extract and apply in full before a note's prompt is
                ;; built, which is what keeps the roster meaningful under
                ;; concurrent extraction (prompt-snapshot). sources is
                ;; already sorted by [kind-rank, path], so partition-by
                ;; cuts exactly there.
                wave-reports (when-not no-llm
                               (into []
                                     (mapcat #(audit-wave! s gate (vec %)
                                                           {:concurrency resolved-concurrency
                                                            :selected selected
                                                            :progress progress
                                                            :index index
                                                            :total total}))
                                     (partition-by :kind extractable)))
                by-path (into {} (map (juxt :path identity)) wave-reports)
                reports (mapv (fn [src]
                                (cond
                                  (:skipped src) (skipped-report src)
                                  ;; a --no-llm scan is instant, so per-file
                                  ;; narration would be all noise: 2n lines
                                  ;; that each say only "no call was made"
                                  no-llm (unextracted-report src)
                                  :else (by-path (:path src))))
                              sources)
                fold (fold-results reports)
                judge-progress (fn [line] (progress (str "judge: " line)))
                sweep-r (when-not no-judge
                         (judge/sweep-conflicts! s {:judge-fn (:judge-fn gate)
                                                    :spend! (:spend! gate)
                                                    :progress-fn judge-progress}))
                _ (when sweep-r
                   (progress (format "sweep: %d candidates, %d linked"
                                     (:candidates sweep-r) (:linked sweep-r))))
                judge-r (when-not no-judge
                         (judge/judge-conflicts! s {:judge-fn (:judge-fn gate)
                                                    :spend! (:spend! gate)
                                                    :progress-fn judge-progress}))
                judged (vec (:results judge-r))
                _ (when judge-r
                   (progress (format "judge: %d verdicts, %d compatible removed"
                                     (count judged)
                                     (count (filter #(= :compatible (get-in % [:verdict :relation]))
                                                    judged)))))
                llm-r (when gate ((:report gate)))
                _ (when llm-r
                   (progress (format "llm: %d calls, %d failed, %d deferred (budget %d)"
                                     (:spent llm-r) (count (:errors llm-r))
                                     (:deferred llm-r) (:allowed llm-r))))]
            (scorecard {:project project
                        :files reports
                        :fold fold
                        :judged judged
                        :no-judge (boolean no-judge)
                        :clusters (:candidates (core/entity-duplicates s))
                        :entities (store/-list-entities s {})
                        :code code
                        :noise (extraction-noise reports (:errors fold))
                        :llm (if no-llm
                              {:status :skipped :reason :no-llm}
                              (let [degraded (or (:tripped? llm-r) (seq (:errors llm-r))
                                                 (pos? (:deferred llm-r)))]
                                (assoc llm-r :status (if degraded :partial :ok))))
                        :preflight pf})))))))
