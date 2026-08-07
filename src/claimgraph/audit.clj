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
  sits on disk, not as truncated at injection."
  [files]
  (let [injected (filter :injected? files)
        on-demand (remove :injected? files)
        injected-bytes (reduce + 0 (map :bytes injected))
        managed-bytes (reduce + 0 (map #(or (:managed-bytes %) 0) injected))
        on-demand-bytes (reduce + 0 (map :bytes on-demand))
        over (filterv #(> (:bytes %) context/default-budget) injected)]
    (cond-> {:injected-bytes injected-bytes
             :managed-bytes managed-bytes
             :on-demand-bytes on-demand-bytes
             :window-bytes context/default-budget
             :over-budget (> injected-bytes context/default-budget)}
      (seq over) (assoc :files-over-window (mapv :path over)))))

(defn scorecard
  "Fold everything into the §7 schema: summary is the marketing line,
  findings is the receipts. With the judge on, judged-compatible pairs are
  removed — the false-positive filter that keeps the headline honest."
  [{:keys [project files fold judged no-judge clusters entities code noise]}]
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
        summary {:contradictions (count contradictions)
                 :instruction-conflicts (count instruction-conflicts)
                 :stale (count stale)
                 :disagreements (count disagreements)
                 :restatements (count restated)
                 :name-clusters (count nclusters)}
        claims (reduce + 0 (map :claims files))]
    {:status "ok"
     :project project
     :files (mapv #(select-keys % [:path :bytes :claims :warning :skipped]) files)
     :claims claims
     :code code
     :judge (if no-judge
              {:status :skipped :note "raw report — mechanical flags only (--no-judge)"}
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
     :injection (injection-report files)
     :summary summary
     :next [(if (zero? claims)
              "claim setup  # nothing to migrate — start the graph fresh"
              "claim setup  # the graph tracks these instead of accumulating them")]}))

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
  An empty pile short-circuits to a plain answer instead of a wall of zeros.
  The injection line reports injected KB against the window, with the
  managed-view and on-demand shares called out underneath whenever either is
  nonzero — so the headline number is legible as 'this much actually lands
  in the harness's context window', not a re-statement of the whole scan."
  [{:keys [status claims files findings injection summary code next] :as sc}]
  (if (not= "ok" status)
    (str/join "\n" (remove nil? [(str "audit " (name status)
                                      (some->> (:error sc) (str ": ")))
                                 (some->> (:hint sc) (str "hint: "))]))
    (let [n (fn [k] (get summary k 0))
          plural (fn [c s] (if (= 1 c) s (str s "s")))
          kb (fn [b] (Math/round (/ b 1000.0)))
          code-line (when (= :ok (:status code))
                      (format "%4d code facts from %d files (%s) — the baseline stale claims are checked against"
                              (:facts code) (:files code)
                              (str/join ", " (map pred-str (:languages code)))))
          head (if (empty? files)
                 (remove nil?
                         ["nothing to audit — no instruction files or auto-memory notes found"
                          (str "     (scanned " (str/join ", " default-scan-set) ",")
                          "      .cursor/rules/* and .claude/rules/*, CLAUDE.md/AGENTS.md up"
                          "      every ancestor directory, each harness's global instructions,"
                          "      and the harness auto-memory notes)"
                          code-line])
                 (remove nil?
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
                                    "(no drift detected)"))
                          (format "%4d KB injected per session against a ~%d KB window%s"
                                  (kb (:injected-bytes injection))
                                  (kb (:window-bytes injection))
                                  (if (:over-budget injection) "  ** over budget **" ""))
                          (when (pos? (:managed-bytes injection))
                            (format "     (of which %d KB is claimgraph's compiled view)"
                                    (kb (:managed-bytes injection))))
                          (when (pos? (:on-demand-bytes injection))
                            (format "     %d KB of on-demand notes scanned, not injected"
                                    (kb (:on-demand-bytes injection))))
                          code-line]))
          details (concat (map finding-line (:contradictions findings))
                          (map finding-line (:instruction-conflicts findings))
                          (map finding-line (:stale findings))
                          (map finding-line (:disagreements findings))
                          (map finding-line (:restatements findings))
                          (map #(str "  name cluster: " (str/join " / " %))
                               (:name-clusters findings)))
          notes (cond-> []
                  (= :skipped (:status code))
                  (conj (str "  staleness prong skipped: " (:note code)))
                  (seq (keep :warning files))
                  (into (map #(str "  warning: " (:warning %)) (filter :warning files))))]
      (str/join "\n" (concat head
                             (when (seq details) (cons "" details))
                             (when (seq notes) (cons "" notes))
                             ["" (str "next: " (first next))])))))

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
        :hint "install and authenticate the claude CLI, or point --extractor / $CLAIMGRAPH_LLM_CMD at any prompt-on-stdin command"}))))

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

(defn- audit-file!
  "Extract one pile file and push every admitted claim through the full
  conflict machinery, one episode per file (ref audit:<path>@<hash>). The
  roster and admission context are recomputed per file so later files see
  the entities earlier files (and the code pass) established.

  The file's :kind (§1, collect-sources) decides its trust for this whole
  pass — it frames the extraction prompt, sets prepare-audit-facts' ceiling
  and source-type, and stamps the episode itself — so a fact born here
  carries the same trust its file would carry if a human had typed it
  straight into `claim assert`. Before each assert, code-baseline? checks
  whether a code fact already answers this claim's (subject, predicate);
  if so the assert is forced to :on-conflict :flag, pinning code as
  ground truth the pile can collide with but never quietly overwrite.

  Only ever called on a source whose stripped content is non-blank (audit!
  filters skipped sources to skipped-report instead) — content is always
  something to extract here."
  [s run {:keys [path content hash bytes managed-bytes injected? kind]}]
  (let [entities (store/-list-entities s {})
        predicates (store/-list-predicates s {:status :stable})
        roster (session/entity-roster entities (store/-entity-usage s)
                                      session/roster-limit)
        prompt (extraction-prompt path content predicates roster kind)
        {:keys [facts rejected]} (prepare-audit-facts
                                  (session/parse-extraction (run prompt)) kind)
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

(defn- skipped-report
  "The report for a source read-source marked :skipped — stripped content
  blank, usually a note that is entirely claimgraph's own compiled view.
  It never reaches audit-file! (nothing to extract, and re-extracting our
  own compiled view is exactly the echo loop the guard exists to prevent),
  but it must not vanish from the file list: its raw bytes are still what
  the harness reads and injects, so injection-report still needs to see
  them."
  [{:keys [path kind bytes managed-bytes injected?]}]
  {:path path :kind kind :bytes bytes :managed-bytes managed-bytes
   :injected? injected? :claims 0 :results [] :rejected 0 :inadmissible 0
   :skipped true})

(defn audit!
  "The whole §4 pipeline, inside one throwaway in-memory store: collect the
  pile, seed, ingest code ground truth, extract + assert every pile claim,
  sweep + judge (report-only — NEVER :resolve; audit fixes nothing), fold
  into the scorecard. Writes nothing anywhere.

  opts: :project (default cwd) :files/:dirs (extra sources)
        :harness (default claude-code) :notes-dir / :inject-file (override
        the resolved auto-memory dir / which note in it counts as injected,
        same precedence as everywhere else) :ctx (harness-resolution
        context, injectable)
        :ancestor-limit / :managed-paths (collect-sources' test-isolation
        seams — pass through untouched; nil/absent gives the real scan)
        :no-code :no-judge
        :extractor (command string) :extractor-fn / :judge-fn (injectable,
        tests) :which (prerequisite lookup, injectable)"
  [{:keys [project harness files dirs notes-dir inject-file ctx no-code no-judge
           extractor extractor-fn judge-fn which ancestor-limit managed-paths]}]
  (let [project (str (fs/canonicalize (or project ".")))
        prereqs (when-not extractor-fn
                  (check-prerequisites {:extractor extractor :which which}))]
    (if (= :error (:status prereqs))
      {:status "blocked" :project project :prerequisites prereqs
       :error (:error prereqs) :hint (:hint prereqs)}
      (let [sources (collect-sources {:project project :files files :dirs dirs
                                      :harness harness :notes-dir notes-dir
                                      :inject-file inject-file :ctx ctx
                                      :ancestor-limit ancestor-limit
                                      :managed-paths managed-paths})
            s (mem/create)
            _ (core/seed! s)
            code (ingest-code! s project no-code)
            run (or extractor-fn (partial llm/complete! (llm/command extractor)))
            ;; A source read-source marked :skipped strips to nothing — no
            ;; extractor call, no episode — but it still counts toward
            ;; injection (skipped-report), the whole point of keeping it in
            ;; collect-sources.
            reports (mapv (fn [src]
                            (if (:skipped src)
                              (skipped-report src)
                              (audit-file! s run src)))
                          sources)
            fold (fold-results reports)
            _ (when-not no-judge
                (judge/sweep-conflicts! s {:judge-fn judge-fn :command extractor}))
            judged (when-not no-judge
                     (:results (judge/judge-conflicts! s {:judge-fn judge-fn
                                                          :command extractor})))]
        (scorecard {:project project
                    :files reports
                    :fold fold
                    :judged (vec judged)
                    :no-judge (boolean no-judge)
                    :clusters (:candidates (core/entity-duplicates s))
                    :entities (store/-list-entities s {})
                    :code code
                    :noise (extraction-noise reports (:errors fold))})))))
