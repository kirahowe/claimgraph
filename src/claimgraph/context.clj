(ns claimgraph.context
  "compile-context: the write-back half of the ambient loop
  (docs/consuming-auto-memory.md §3). Compiles a deterministic, budgeted
  'what's currently true' view into the marker-delimited managed section of
  the file the harness auto-injects (Claude Code: the head of MEMORY.md) —
  the graph's answer to ambient injection without building injection.

  Deterministic by construction: no LLM anywhere, same graph + same clock =
  byte-identical output. Priority order under the byte budget: standing
  commitments (the do-not-relitigate list), open conflicts awaiting the
  human, recent supersessions (the 'what changed since you last looked'
  briefing nothing else in the field provides), then top currently-valid
  facts by decay-aware effective confidence. Code-derived facts are excluded
  from the fact list: they are regenerable and obvious from the code itself,
  and the AGENTS.md result says ambient context must carry only what the
  code cannot say.

  The echo-loop guard is structural: ingest-notes strips the managed section
  before hashing and extraction, so compile → ingest → compile is a fixed
  point — the graph never re-consumes its own compiled view."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def injection-window
  "Bytes a harness injects at session start — Claude Code reads the first
  ~200 lines / 25 KB of MEMORY.md. The whole injected surface shares this
  one window: the compiled view, every CLAUDE.md/AGENTS.md up the ancestor
  walk, and each harness's global instructions. `claim audit` measures that
  sum against this number."
  25000)

(def default-budget
  "Bytes for the whole managed block, markers included — the compiled view's
  SHARE of injection-window, not the window itself. The view is one
  participant in a window it does not own: a budget equal to the window
  licenses the compiler to spend every byte a project's own instruction
  files also need, which puts a project over the window whenever both are
  healthy. Half leaves the other half for the files a human wrote, and the
  graph stays queryable for whatever the fold drops (fit-to-budget's
  truncation announcement points at it)."
  12500)

(def supersession-window-days 30)
(def max-facts 50)

;; ---------------------------------------------------------------------------
;; Pure: rendering
;; ---------------------------------------------------------------------------

(defn- day [d] (subs (str (.toInstant ^java.util.Date d)) 0 10))

(defn- one-line [s] (str/replace (str s) #"\s*\n\s*" " "))

(defn- object-str [f]
  (if (= :entity (:object-kind f))
    (get-in f [:object-ref :name])
    (str "\"" (one-line (:object-lit f)) "\"")))

(defn- subject-str [f] (get-in f [:subject :name]))
(defn- pred-str [f] (name (:predicate f)))

(def ^:private legacy-supersession-re
  "The two sentences claimgraph itself wrote for a supersession before the kind
  existed: assert-fact's \"superseded by <id>\" and the judge's \"judged
  superseded by <id>\". Anchored and single-token on purpose — a human's
  `claim invalidate --reason \"superseded by a better plan\"` is prose, not a
  fact id, and must not be read as a link."
  #"^(?:judged )?superseded by (\S+)$")

(defn- supersession
  "The successor a closed fact names, as {:successor id-or-nil}, or nil when
  this invalidation was not a supersession at all.

  The kind decides it whenever there is one. When there is not, the prose is
  parsed as a MIGRATION SHIM, and it can only ever fire on rows written before
  :invalidation-kind existed, because every write since carries a kind: it is
  deletable the day no store predates the kinds (or a migration has backfilled
  them). Without it, every fact a user's store already retired matches nothing
  the moment they upgrade and drops out of the \"Changed recently\" briefing —
  a silent loss of the one section that reports what changed, in the stores
  that have the most history to report. The same shim covers the receiving end
  of replication, where a log written by an older peer restores a fact with no
  kind at all."
  [f]
  (if-let [kind (:invalidation-kind f)]
    (when (logic/supersession-kinds kind) {:successor (:successor f)})
    (when-let [[_ id] (re-matches legacy-supersession-re
                                  (str (:invalidation-reason f)))]
      {:successor id})))

(defn recent-supersessions
  "Pure: facts invalidated by supersession within the window, newest first,
  each paired with its successor.

  Selection is on :invalidation-kind and the pairing is on :successor, both
  structural (see `supersession` for the pre-kind fallback). They were
  recovered from the reason sentence by matching ^superseded by (\\S+)$, and
  the producers did not all agree with that regex: the judge wrote \"judged
  superseded by <id>\", which whole-string matching never matches, so a
  supersession an LLM resolved could not appear here at all. A successor that
  is not in `facts` — invalidated itself, filtered out, or not yet loaded —
  renders as \"no longer\" rather than dropping the line: that the claim
  stopped holding is the part the reader needs."
  [facts now window-days]
  (let [by-id (into {} (map (juxt :id identity)) facts)
        cutoff (- (logic/ms now) (* window-days 86400000))]
    (->> facts
         (keep (fn [f]
                 (when-let [ti (:t-invalid f)]
                   (when (>= (logic/ms ti) cutoff)
                     (when-let [{:keys [successor]} (supersession f)]
                       {:old f :new (get by-id successor) :at ti})))))
         (sort-by (comp - logic/ms :at))
         vec)))

(defn compiled-sections
  "Pure: the whole store's facts + open conflict pairs + the clock -> the
  priority-ordered sections of the compiled view, ready for the budget fold."
  [{:keys [facts conflicts now]}]
  (let [valid (filterv #(logic/fact-valid-at? % now) facts)
        commitments (->> valid
                         (filter #(= :commitment (:epistemic %)))
                         (sort-by (comp logic/ms :t-valid)))
        top (->> valid
                 (remove #(= :commitment (:epistemic %)))
                 (remove #(= :code (:source-type %)))
                 ;; disputed facts belong in the conflicts section, never in
                 ;; the current-truth list — a flagged poison must not read
                 ;; as settled fact in the injected view (review §3.6)
                 (remove #(seq (:conflicts %)))
                 (map #(assoc % :effective-confidence (logic/effective-confidence % now)))
                 (sort-by (fn [f] [(- (:effective-confidence f)) (subject-str f)]))
                 (take max-facts))
        sups (recent-supersessions facts now supersession-window-days)]
    [{:key :commitments
      :header "Standing decisions (do not relitigate)"
      :lines (mapv (fn [f] (str "- " (subject-str f) " " (pred-str f) " "
                                (object-str f) " (since " (day (:t-valid f)) ")"))
                   commitments)}
     {:key :conflicts
      :header "Open conflicts (awaiting review — `claim conflicts`)"
      :lines (mapv (fn [{:keys [fact candidate]}]
                     (str "- " (subject-str fact) " " (pred-str fact) ": "
                          (object-str fact) " vs " (object-str candidate)))
                   conflicts)}
     {:key :supersessions
      :header "Changed recently"
      :lines (mapv (fn [{:keys [old new at]}]
                     (str "- " (subject-str old) " " (pred-str old) ": "
                          (object-str old)
                          (if new (str " → " (object-str new)) " (no longer)")
                          " (" (day at) ")"))
                   sups)}
     {:key :facts
      :header "Current facts (by effective confidence)"
      :lines (mapv (fn [f] (str "- " (subject-str f) " " (pred-str f) " "
                                (object-str f)
                                (format " (%.2f)" (:effective-confidence f))))
                   top)}]))

(defn- byte-len [s] (alength (.getBytes (str s) "UTF-8")))

(defn fit-to-budget
  "Pure fold of sections into one string within budget bytes. Sections are
  priority-ordered: each contributes its header plus as many of its lines as
  fit. A truncated section always announces how much the graph holds beyond
  the cut — lines are dropped to make room for the announcement — and a
  section that cannot fit even its announcement is dropped whole, as are
  empty sections."
  [preamble sections budget]
  (reduce
   (fn [out {:keys [header lines]}]
     (if (empty? lines)
       out
       (let [render (fn [ls extra]
                      (apply str out "\n\n### " header
                             (concat (map #(str "\n" %) ls)
                                     (when extra [(str "\n" extra)]))))
             fits? (fn [s] (<= (byte-len s) budget))
             kept (loop [kept [] ls lines]
                    (if (and (seq ls) (fits? (render (conj kept (first ls)) nil)))
                      (recur (conj kept (first ls)) (rest ls))
                      kept))]
         (if (= (count kept) (count lines))
           (render kept nil)
           (loop [kept kept]
             (let [s (render kept (str "- … " (- (count lines) (count kept))
                                       " more — query the graph"))]
               (cond
                 (fits? s) s
                 (seq kept) (recur (vec (butlast kept)))
                 :else out)))))))
   preamble
   sections))

(defn render-view
  "Pure: sections -> everything compile! writes between the markers, budget
  applied to the whole managed block (markers included)."
  [sections {:keys [now budget]}]
  (let [preamble (str "## Project memory — compiled by claimgraph (as of " (day now) ")\n\n"
                      "The knowledge graph's current view. Regenerated on each compile; edits\n"
                      "here are discarded and never re-ingested. History, provenance, and\n"
                      "time-travel: `bin/claim help`.")
        marker-overhead (+ (byte-len harness/begin-marker) (byte-len harness/end-marker) 2)]
    (fit-to-budget preamble sections (- (or budget default-budget) marker-overhead))))

(defn compiled-view
  "Pure: the whole store's facts + open conflicts + the clock -> the managed
  section's content."
  [inputs]
  (render-view (compiled-sections inputs) inputs))

;; ---------------------------------------------------------------------------
;; Shell: read the store, splice the file
;; ---------------------------------------------------------------------------

(defn compile!
  "Compile the graph's current view into the managed section of the file the
  harness auto-injects. Creates the notes dir and inject file when absent;
  replaces only the marker-delimited block when present — the harness's own
  notes around it are untouched.

  opts: :harness (default claude-code) :project (default cwd)
        :dir (override the resolved notes dir)
        :inject-file (override the write target; relative to the notes dir
                      or absolute) :budget (bytes, default 25000)
        :ctx (harness-resolution context {:home :env}; injectable, tests)
        :dry-run (return the block, write nothing) :now (injectable clock)"
  [s {:keys [harness inject-file budget dry-run now] :as opts}]
  (let [h (harness/resolve-harness harness)
        notes-dir (harness/notes-path h (select-keys opts [:dir :project :ctx]))
        target (harness/inject-target h notes-dir inject-file)
        now (or now (core/now))
        inputs {:facts (store/-all-facts s)
                :conflicts (:conflicts (core/conflicts s))
                :now now
                :budget budget}
        sections (compiled-sections inputs)
        inner (render-view sections inputs)
        result {:harness (name (:id h))
                :file target
                :bytes (+ (byte-len inner)
                          (byte-len harness/begin-marker)
                          (byte-len harness/end-marker) 2)
                :sections (into {} (map (juxt :key (comp count :lines))) sections)}]
    (if dry-run
      (assoc result :status :dry-run :content inner)
      (do (fs/create-dirs notes-dir)
          (some-> (fs/parent target) fs/create-dirs)
          (spit target (harness/splice-managed-section
                        (when (fs/exists? target) (slurp target))
                        inner))
          (assoc result :status :compiled)))))
