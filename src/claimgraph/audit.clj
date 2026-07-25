(ns claimgraph.audit
  "`claim audit`: the memory-pile consistency scorecard (docs/memory-audit.md).
  Points the existing conflict machinery at the project's agent-memory pile
  (CLAUDE.md, AGENTS.md, auto-memory notes, rules files) and reports what the
  markdown can't see about itself: contradictions, silent disagreements,
  staleness against the code, restatements, name drift, injection bloat.

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

(def max-confidence
  "Pile claims are :agent-note evidence like the notes tier — same cap."
  notes/max-confidence)

(def default-confidence notes/default-confidence)

(def file-warn-bytes
  "Per-file extraction degrades somewhere above this (spec §9); v1 warns,
  chunking is v2."
  50000)

(def default-scan-set
  "The default memory pile (spec §3), relative to --project, each existing
  file only. The .cursor/rules/* glob and the harness auto-memory notes dir
  (resolved exactly like ingest-notes) are added by collect-sources."
  ["CLAUDE.md" "CLAUDE.local.md" "AGENTS.md" "AGENT.md"
   ".github/copilot-instructions.md" ".cursorrules"])

;; ---------------------------------------------------------------------------
;; Pure: extraction prompt (audit variant, spec §6) & clamping
;; ---------------------------------------------------------------------------

(defn extraction-prompt
  "The notes prompt with the two audit differences: the epistemic class
  signal is KEPT (commitments allowed — 'we decided against X' is exactly
  the claim whose collisions we want flagged), and every claim must carry a
  verbatim quote so findings render with receipts, not vibes."
  [path content predicates roster]
  (str
   "Extract durable project memory from one file of a coding agent's memory\n"
   "pile (CLAUDE.md / AGENTS.md / rules files / auto-memory notes) for a\n"
   "consistency audit. Restate what the file asserts as structured claims.\n\n"
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
   (str/join "\n" (for [p predicates]
                    (str "  " (subs (str (:id p)) 1) " — " (:definition p))))
   (when (seq roster)
     (str "\n\nKnown entities — when you mean one of these, use its EXACT name\n"
          "(synonym drift fragments the graph); coin a new name only when none\n"
          "of these is the thing you mean:\n"
          (str/join "\n" roster)))
   "\n\nIf nothing qualifies, output nothing.\n\n"
   "<pile file=\"" path "\">\n" content "\n</pile>"))

(defn prepare-audit-facts
  "Audit's clamp — the anti-notes (spec §5): the emitted class is KEPT
  (an invalid or absent class falls through to the predicate's default at
  assert time, so decided-against still mints the commitment that flags),
  confidence is capped at the agent-note ceiling, and the verbatim :quote
  rides along on the candidate for the audit-side receipt map — the store
  never sees it. Incomplete triples are returned as :rejected."
  [extracted]
  (let [complete? (fn [m] (every? #(not (str/blank? (str (get m %))))
                                  [:subject :predicate :object]))
        {complete true rejected false} (group-by complete? extracted)
        clamp (fn [m]
                (let [c (:confidence m)
                      class (logic/->kw (some-> (or (:class m) (:epistemic m))
                                                name str/lower-case))]
                  (-> m
                      (dissoc :class :epistemic)
                      (assoc :confidence (min max-confidence
                                              (if (number? c) (double c) default-confidence))
                             :source-type :agent-note)
                      (cond-> (contains? logic/epistemic-classes class)
                        (assoc :epistemic class)))))]
    {:facts (mapv clamp complete)
     :rejected (vec rejected)}))

;; ---------------------------------------------------------------------------
;; Pure: assert results -> findings (the §4 status table)
;; ---------------------------------------------------------------------------

(defn- code-sourced? [summary] (= :code (:source-type summary)))

(defn claim-view
  "One side of a finding, with its receipt: a fact summary joined against
  the audit-side quote map (§6). Code-sourced sides have no file — the code
  is the receipt."
  [summary receipts]
  (let [{:keys [file quote]} (get receipts (:id summary))]
    (cond-> {:subject (:subject summary)
             :predicate (:predicate summary)
             :object (:object summary)
             :file file}
      quote (assoc :quote quote)
      (code-sourced? summary) (assoc :source "code"))))

(defn pair->finding
  "A conflict pair -> a finding: staleness when a side is code-sourced
  (code ingested first, so the pile claim collided with ground truth),
  contradiction otherwise. The judge's verdict rides along when it ran."
  [{:keys [fact candidate verdict]} receipts]
  (cond-> {:kind (if (or (code-sourced? fact) (code-sourced? candidate))
                   "stale" "contradiction")
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
   (fn [acc {:keys [path results]}]
     (reduce
      (fn [a {:keys [status fact candidates superseded quote error-type]}]
        (if (= :error status)
          (update-in a [:errors (or error-type :other)] (fnil inc 0))
          (let [id (:id fact)
                summary (judge/fact->summary fact)
                occ (cond-> {:file path} quote (assoc :quote quote))
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
  :restates-code (deviation noted in the spec doc)."
  [{:keys [occurrences summaries]}]
  (->> occurrences
       (keep (fn [[id occs]]
               (let [s (summaries id)
                     n (count occs)]
                 (when (or (> n 1) (code-sourced? s))
                   (cond-> {:kind "restatement"
                            :subject (:subject s)
                            :predicate (:predicate s)
                            :object (:object s)
                            :files (vec (distinct (map :file occs)))
                            :count n}
                     (code-sourced? s) (assoc :restates-code true))))))
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
  "The byte arithmetic: the whole pile (managed sections already stripped)
  against the ~25 KB injection window, with individually-oversized files
  called out."
  [files]
  (let [total (reduce + 0 (map :bytes files))
        over (filterv #(> (:bytes %) context/default-budget) files)]
    (cond-> {:pile-bytes total
             :window-bytes context/default-budget
             :over-budget (> total context/default-budget)}
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
        {stale "stale" contradictions "contradiction"} (group-by :kind pair-findings)
        disagreements (disagreement-findings fold)
        restated (restatements fold)
        nclusters (name-clusters clusters entities)
        summary {:contradictions (count contradictions)
                 :stale (count stale)
                 :disagreements (count disagreements)
                 :restatements (count restated)
                 :name-clusters (count nclusters)}
        claims (reduce + 0 (map :claims files))]
    {:status "ok"
     :project project
     :files (mapv #(select-keys % [:path :bytes :claims :warning]) files)
     :claims claims
     :code code
     :judge (if no-judge
              {:status :skipped :note "raw report — mechanical flags only (--no-judge)"}
              {:status :ok :judged (count judged)
               :compatible-removed (count (filter #(= :compatible (get-in % [:verdict :relation]))
                                                  judged))})
     :findings {:contradictions (vec contradictions)
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

(defn- claim-str [{:keys [subject predicate object file quote source]}]
  (str subject " " (pred-str predicate) " " object
       " (" (or file source "?")
       (when quote (str ": \"" quote "\"")) ")"))

(defn- finding-line [{:keys [kind claims subject predicate object files
                             restates-code] :as f}]
  (case kind
    "restatement" (str "  restatement: " subject " " (pred-str predicate) " " object
                       " — " (:count f) "x in " (str/join ", " files)
                       (when restates-code " (already what the code says)"))
    (str "  " kind ": " (str/join (if (= "disagreement" kind) "  vs  " "  <->  ")
                                  (map claim-str claims)))))

(defn render-pretty
  "The §1 scorecard block plus per-finding detail — every number auditable.
  An empty pile short-circuits to a plain answer instead of a wall of zeros."
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
                         ["no memory pile found — nothing to audit"
                          (str "     (scanned " (str/join ", " default-scan-set) ",")
                          "      .cursor/rules/*, and the harness auto-memory notes)"
                          code-line])
                 (remove nil?
                         [(format "%4d claims extracted from %d %s"
                                  claims (count files) (plural (count files) "file"))
                          (format "%4d %-15s (opposed claims coexisting in the pile)"
                                  (n :contradictions) (plural (n :contradictions) "contradiction"))
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
                                  (kb (:pile-bytes injection))
                                  (kb (:window-bytes injection))
                                  (if (:over-budget injection) "  ** over budget **" ""))
                          code-line]))
          details (concat (map finding-line (:contradictions findings))
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

(defn- read-source
  "One pile file -> {:path :content :bytes :hash}, managed section stripped
  first (the echo guard — never audit our own compiled view back at
  ourselves) and blank-after-strip files skipped, same rule as read-notes."
  [root p]
  (when (fs/regular-file? p)
    (let [content (str/trim (harness/strip-managed-section (slurp (str p))))]
      (when-not (str/blank? content)
        {:path (source-label root p)
         :content content
         :bytes (count (.getBytes ^String content "UTF-8"))
         :hash (notes/content-hash content)}))))

(defn collect-sources
  "The pile (spec §3): the default scan set, .cursor/rules/*, the harness
  auto-memory notes dir (resolved exactly like ingest-notes — honors every
  override and $CLAUDE_CONFIG_DIR), plus --file/--dir extras. Sorted by path
  for deterministic ingestion order."
  [{:keys [project files dirs harness notes-dir ctx]}]
  (let [root (fs/canonicalize (or project "."))
        h (harness/resolve-harness harness)
        ndir (harness/notes-path h {:dir notes-dir :project (str root) :ctx ctx})
        rules-dir (fs/path root ".cursor" "rules")
        paths (concat (map #(fs/path root %) default-scan-set)
                      (when (fs/directory? rules-dir) (fs/glob rules-dir "*"))
                      (when (fs/directory? ndir)
                        (fs/glob ndir (or (:note-glob h) "**.md")))
                      (map fs/path files)
                      (mapcat #(when (fs/directory? %) (fs/glob % "**.md"))
                              (map fs/path dirs)))]
    (->> paths
         (filter fs/exists?)
         (map fs/canonicalize)
         distinct
         (keep #(read-source root %))
         (sort-by :path)
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

(defn- audit-file!
  "Extract one pile file and push every admitted claim through the full
  conflict machinery, one episode per file (ref audit:<path>@<hash>). The
  roster and admission context are recomputed per file so later files see
  the entities earlier files (and the code pass) established."
  [s run {:keys [path content hash bytes]}]
  (let [entities (store/-list-entities s {})
        predicates (store/-list-predicates s {:status :stable})
        roster (session/entity-roster entities (store/-entity-usage s)
                                      session/roster-limit)
        prompt (extraction-prompt path content predicates roster)
        {:keys [facts rejected]} (prepare-audit-facts
                                  (session/parse-extraction (run prompt)))
        {:keys [admitted inadmissible]} (logic/screen-candidates
                                         facts (logic/admission-ctx entities predicates))
        ep (core/open-episode s {:source-type :agent-note
                                 :ref (str "audit:" path "@" hash)})
        results (mapv (fn [f]
                        (let [quote (:quote f)
                              fact (dissoc f :quote :admission-score)]
                          (try
                            (-> (core/assert-fact s (assoc fact :episode (:id ep)))
                                (select-keys [:status :fact :candidates :superseded])
                                (assoc :quote quote))
                            (catch clojure.lang.ExceptionInfo e
                              {:status :error :message (ex-message e)
                               :error-type (:type (ex-data e)) :input fact}))))
                      admitted)]
    (core/close-episode s {:episode (:id ep)
                           :summary (str "audit " path "@" hash ": "
                                         (count admitted) " claims ("
                                         (pr-str (frequencies (map :status results))) "), "
                                         (count rejected) " rejected, "
                                         (count inadmissible) " inadmissible")})
    (cond-> {:path path :bytes bytes :claims (count admitted)
             :results results
             :rejected (count rejected)
             :inadmissible (count inadmissible)}
      (> bytes file-warn-bytes)
      (assoc :warning (str path " is " bytes " bytes — extraction degrades above ~50 KB/file")))))

(defn audit!
  "The whole §4 pipeline, inside one throwaway in-memory store: collect the
  pile, seed, ingest code ground truth, extract + assert every pile claim,
  sweep + judge (report-only — NEVER :resolve; audit fixes nothing), fold
  into the scorecard. Writes nothing anywhere.

  opts: :project (default cwd) :files/:dirs (extra sources)
        :harness (default claude-code) :notes-dir (override the resolved
        auto-memory dir) :ctx (harness-resolution context, injectable)
        :no-code :no-judge
        :extractor (command string) :extractor-fn / :judge-fn (injectable,
        tests) :which (prerequisite lookup, injectable)"
  [{:keys [project harness files dirs notes-dir ctx no-code no-judge
           extractor extractor-fn judge-fn which]}]
  (let [project (str (fs/canonicalize (or project ".")))
        prereqs (when-not extractor-fn
                  (check-prerequisites {:extractor extractor :which which}))]
    (if (= :error (:status prereqs))
      {:status "blocked" :project project :prerequisites prereqs
       :error (:error prereqs) :hint (:hint prereqs)}
      (let [sources (collect-sources {:project project :files files :dirs dirs
                                      :harness harness :notes-dir notes-dir
                                      :ctx ctx})
            s (mem/create)
            _ (core/seed! s)
            code (ingest-code! s project no-code)
            run (or extractor-fn (partial llm/complete! (llm/command extractor)))
            reports (mapv #(audit-file! s run %) sources)
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
