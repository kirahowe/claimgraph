(ns claimgraph.consolidate
  "Dreaming-style offline consolidation: one pass that summarizes and closes
  open episodes (LLM, with a mechanical fallback), judges open conflicts,
  sweeps for conflict candidates the write path can't see, and reports :x/*
  predicates that have earned promotion review. (Disuse decay needs no pass:
  effective confidence is computed at read time.)

  Functional core / imperative shell: episode planning, prompt construction,
  summary parsing, the mechanical fallback, enrichment candidacy and
  promotion-candidate selection are pure; `consolidate!` executes the stages.
  The LLM is pluggable as everywhere ($CLAIMGRAPH_LLM_CMD, default claude -p;
  tests inject :summarize-fn / :judge-fn / :enrich-fn).

  Closing an episode with a summary is what turns bulky episodic memory into
  something retrievable: summaries are full-text indexed, so \"why did we do
  X\" becomes a search.

  ONE budget, spent most-valuable-first (judgments, then summaries, then the
  sweep, then enrichment), and every model call lands a durable outcome — a
  recorded verdict, a closed episode, a recorded enrichment attempt. What is
  left to do is therefore DERIVED from the store rather than remembered
  beside it: each run shrinks the remainder and a converged store makes this
  pass a free no-op. Work the budget did not reach is named in the report by
  stage (:deferred) — a bounded run must never read as a complete one — and
  stays pending by derivation for the next run."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.judge :as judge]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def default-min-usage 3)
(def max-summary-chars 600)

;; The ONE bound on a pass, replacing the per-stage caps and the cadence that
;; used to ration a pass which redid all its work every time. Every call now
;; lands a durable outcome, so runs converge toward the free no-op and the
;; only question left is how much a single run may spend.
(def default-call-budget 20)

;; ---------------------------------------------------------------------------
;; Pure: episode planning
;; ---------------------------------------------------------------------------

(defn plan-episodes
  "Which episodes the pass should close: open ones that contain facts.
  Open-but-empty episodes are left alone (they may belong to a session still
  in flight) and reported."
  [episodes facts]
  (let [by-ep (group-by :episode facts)
        open (remove :closed-at episodes)]
    {:to-close (vec (for [ep open
                          :let [efacts (by-ep (:id ep))]
                          :when (seq efacts)]
                      {:episode ep :facts (vec efacts)}))
     :skipped-empty (vec (map :id (filter #(empty? (by-ep (:id %))) open)))}))

;; ---------------------------------------------------------------------------
;; Pure: prompt, parsing, fallback
;; ---------------------------------------------------------------------------

(defn- fact-line [f]
  (str "- " (get-in f [:subject :name])
       " " (subs (str (:predicate f)) 1)
       " " (or (get-in f [:object-ref :name]) (:object-lit f))
       " (" (name (:epistemic f :observation))
       ", " (name (:source-type f :user-assertion)) ")"))

(defn summary-prompt [episode facts]
  (str
   "Summarize what this episode added to a project's knowledge graph, in 1-3\n"
   "sentences of plain text. Write for a developer skimming project history:\n"
   "lead with decisions and preferences, mention structural facts only in\n"
   "aggregate. Output the summary only — no preamble, no quotes, no markdown.\n\n"
   "Episode: source " (name (:source-type episode :unknown))
   ", ref \"" (:ref episode) "\""
   (when-let [t (:opened-at episode)] (str ", opened " t)) "\n"
   "Facts:\n"
   (str/join "\n" (map fact-line facts)) "\n"))

(defn parse-summary
  "Tolerant cleanup of the LLM's summary: drop fences, collapse to one
  paragraph, cap length. Blank responses become nil so the caller can fall
  back."
  [response]
  (let [text (->> (str/split-lines (or response ""))
                  (map str/trim)
                  (remove #(str/starts-with? % "```"))
                  (remove str/blank?)
                  (str/join " "))]
    (when-not (str/blank? text)
      (if (> (count text) max-summary-chars)
        (str (subs text 0 max-summary-chars) "…")
        text))))

(defn mechanical-summary
  "The no-LLM fallback: a digest of what the episode recorded."
  [episode facts]
  (str (name (:source-type episode :unknown))
       " episode (" (:ref episode) "): " (count facts) " facts — "
       (str/join ", " (for [[p n] (sort-by (comp - val)
                                           (frequencies (map :predicate facts)))]
                        (str n " " (subs (str p) 1))))))

;; ---------------------------------------------------------------------------
;; Pure: retrieval enrichment (SIRA-style, review §3.2)
;; ---------------------------------------------------------------------------

(def max-aliases-per-entity 3)

(def enrich-ref-prefix "enrich:")

(defn enrich-ref
  "The curation episode ref that records one enrichment attempt:
  \"enrich:<entity-id>@<normalized-name>\".

  The normalized name is the reopening clause. A rename changes it, which is
  exactly when \"what else might someone call this?\" is a genuinely new
  question; an answered [] does not change it, so no-aliases is remembered as
  an ANSWER rather than a failure. Before that distinction existed, an empty
  reply left the entity a candidate forever and the same twenty entities were
  re-bought from the model every pass."
  [entity]
  (str enrich-ref-prefix (:id entity) "@" (logic/normalize-entity-name (:name entity))))

(defn attempted-enrich-refs
  "Episodes -> the set of enrichment refs already delivered. Curation
  episodes are the enrichment stage's memory the same way they are the
  judge's; nothing else records that an entity was asked."
  [episodes]
  (into #{}
        (comp (filter #(= :curation (:source-type %)))
              (map (comp str :ref))
              (filter #(str/starts-with? % enrich-ref-prefix)))
        episodes))

(defn project-facing?
  "Pure: does the project's own knowledge touch this entity, or is it only
  somebody else's library? It qualifies when at least one of its
  currently-valid facts has it as the SUBJECT, or carries a scope other than
  \"external\".

  The shape this excludes is the one code ingestion mints in bulk:
  clojure.string appears only as the target of external-scoped depends-on
  edges. Inventing searchable nicknames for a language's standard library is
  spent budget — and because those entities are heavily depended on, usage
  order alone puts them at the FRONT of the queue (observed 2026-08-05: the
  top candidates were exactly those)."
  [entity facts]
  (boolean (some (fn [f] (or (= (:id entity) (get-in f [:subject :id]))
                             (not= "external" (:scope f))))
                 facts)))

(defn enrichment-candidates
  "Entities worth asking about, most-used first: they carry facts (usage),
  have no aliases yet, and no enrichment episode records them as already
  asked at this name.

  Returns a lazy-ish seq and imposes NO cap of its own — the shared call
  budget is the only bound now. The old per-pass cap of 20 rationed a stage
  that re-asked the same twenty entities every run; with attempts recorded
  there is nothing left to ration, and a hard cap would only hide the
  remainder from the report. The caller fetches each candidate's facts as it
  consumes the stream (it needs them for the prompt anyway), which is where
  `project-facing?` applies."
  [entities usage attempted]
  (->> entities
       (filter #(empty? (:aliases %)))
       (keep (fn [e]
               (let [n (get usage (:id e) 0)]
                 (when (pos? n) (assoc e :usage n)))))
       (remove #(contains? attempted (enrich-ref %)))
       (sort-by (fn [e] [(- (:usage e)) (:name e)]))))

(defn enrichment-prompt [entity facts]
  (str
   "An entity in a project knowledge graph needs searchable alternative\n"
   "names. Emit a JSON array of up to " max-aliases-per-entity " short\n"
   "alternative names or synonyms a developer might type when searching for\n"
   "it — nothing else, no prose. Only genuinely synonymous names (nicknames,\n"
   "expansions, the concept it implements); never invent variants that could\n"
   "mean something else, and never emit the name itself. [] when none fit.\n\n"
   "Entity: " (:name entity)
   (when-let [t (:type entity)] (str " [" (name t) "]")) "\n"
   "What the graph knows about it:\n"
   (str/join "\n" (map fact-line facts))))

(defn parse-aliases
  "Tolerant parse of the enrichment reply: a JSON array of strings, fences
  and prose stripped, blanks and the entity's own names dropped, capped."
  [response entity]
  (let [own (set (map str/lower-case (cons (:name entity) (:aliases entity))))]
    (->> (try (json/parse-string
               (or (re-find #"(?s)\[.*?\]" (str response)) "[]"))
              (catch Exception _ []))
         (filter string?)
         (map str/trim)
         (remove str/blank?)
         (remove #(own (str/lower-case %)))
         distinct
         (take max-aliases-per-entity)
         vec)))

;; ---------------------------------------------------------------------------
;; Pure: promotion candidates
;; ---------------------------------------------------------------------------

(defn promotion-candidates
  "Staging (:testing) predicates used at least min-usage times — the ones
  worth promoting to :core/* (or pruning, if the usage is junk). usage is a
  predicate->count map (the store-side aggregate)."
  [predicates usage min-usage]
  (->> predicates
       (filter #(= :testing (:status %)))
       (keep (fn [p]
               (let [n (get usage (:id p) 0)]
                 (when (>= n min-usage)
                   {:id (:id p) :usage n :definition (:definition p)}))))
       (sort-by (comp - :usage))
       vec))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn- summarize-episodes!
  "Close every planned episode with a summary. A summary call the budget did
  not reach leaves its episode OPEN and counts as deferred — the next run
  retries it. A summary call that ERRORED still closes the episode with the
  mechanical digest, exactly as before: the two are different failures, and
  the report has to tell \"no budget\" from \"broken LLM\"."
  [s run spend! {:keys [to-close skipped-empty]}]
  (let [deferred (atom 0)
        closed (into []
                     (keep (fn [{:keys [episode facts]}]
                             (if-not (spend!)
                               (do (swap! deferred inc) nil)
                               (let [summary (or (parse-summary
                                                  (try (run (summary-prompt episode facts))
                                                       (catch Exception _ nil)))
                                                 (mechanical-summary episode facts))]
                                 (store/-close-episode s (:id episode) summary (core/now))
                                 {:episode (:id episode)
                                  :facts (count facts)
                                  :summary summary}))))
                     to-close)]
    (cond-> {:closed closed :skipped-empty skipped-empty}
      (pos? @deferred) (assoc :deferred @deferred))))

(defn- record-enrichment-attempt!
  "Mint the curation episode that marks this entity asked AT THIS NAME:
  source :curation, the enrich ref, closed at creation. Idempotent on the
  ref, like the verdict record it mirrors.

  Minted on any DELIVERY, including one that parsed to no aliases at all —
  that is the whole point: an answered [] is an answer. A call that ERRORED
  never reaches here, so it retries under a later budget."
  [s entity added]
  (let [ref (enrich-ref entity)]
    (when-not (some #(= ref (str (:ref %))) (store/-list-episodes s))
      (let [ep (core/open-episode s {:source-type :curation :ref ref})]
        (core/close-episode s {:episode (:id ep)
                               :summary (str "alias enrichment for " (:name entity) ": "
                                             (if (seq added)
                                               (str/join ", " added)
                                               "no aliases suggested"))})
        ref))))

(defn- add-aliases!
  "Every alias goes through core/alias-entity, so a name clash with another
  entity is refused there and skipped here — enrichment must never create
  ambiguity."
  [s entity aliases]
  (vec (for [a aliases
             :when (try (core/alias-entity s {:name (:name entity) :alias a})
                        (catch Exception _ nil))]
         a)))

(defn- enrich-entities!
  "The SIRA-style stage: give alias-less, fact-bearing entities searchable
  alternative names, most-used first, under the shared budget. Failures (no
  LLM) skip silently: enrichment is a bonus, never a blocker.

  The candidate stream is consumed lazily rather than capped up front —
  every entity costs a fact fetch to judge (`project-facing?`) and to
  prompt, so the ones past the budget are never touched. :deferred is
  therefore the count of candidates left UNREACHED, an upper bound on the
  calls still owed: some of them will turn out external-only and cost
  nothing. Naming an upper bound beats walking the whole tail on a store
  where most alias-less entities are somebody else's library.

  `apply!` wraps the alias application and nothing else: adding an alias is
  the one decide-bearing write in this pass (core/alias-entity refuses a clash
  with another entity's name), so it is the one that has to be serialized
  against other writers. The attempt record minted beside it decides nothing
  and stays leaseless — and neither may be held across the model call above."
  [s run spend! apply!]
  (let [now (core/now)
        attempted (attempted-enrich-refs (store/-list-episodes s))
        candidates (enrichment-candidates (store/-list-entities s {})
                                          (store/-entity-usage s)
                                          attempted)]
    (loop [[e & more :as remaining] (seq candidates), asked 0, enriched []]
      (if-not e
        {:considered asked :enriched enriched}
        (let [facts (filterv #(logic/fact-valid-at? % now)
                             (store/-get-facts s (:id e) {:direction :both}))]
          (cond
            (not (and (seq facts) (project-facing? e facts)))
            (recur more asked enriched)

            (not (spend!))
            {:considered asked :enriched enriched :deferred (count remaining)}

            :else
            (let [reply (try {:text (run (enrichment-prompt e facts))}
                             (catch Exception _ nil))]
              (if-not reply
                ;; errored: nothing recorded, so the entity stays a candidate
                (recur more (inc asked) enriched)
                (let [added (apply! #(add-aliases! s e (parse-aliases (:text reply) e)))]
                  (record-enrichment-attempt! s e added)
                  (recur more (inc asked)
                         (cond-> enriched
                           (seq added) (conj {:entity (:name e) :aliases added}))))))))))))

(defn sub-stage-errors
  "The internally-caught failures a consolidate! report carries, keyed by
  stage — a report aggregator, and nothing more.

  It used to feed the stamp gate, which withheld the stamp when any of these
  appeared so the whole pass stayed due. There is no stamp to withhold now
  (spec/maintenance.allium, decided 2026-08-05): a failed call minted no
  outcome record, so its work is still pending BY DERIVATION and the next run
  retries exactly it rather than the pass around it. (Episode summaries never
  appear here: the mechanical digest means that stage always makes progress.)"
  [{:keys [conflicts sweep enrichment]}]
  (into {}
        (keep (fn [[stage r]] (when (:error r) [stage (:error r)])))
        {:conflicts conflicts :sweep sweep :enrichment enrichment}))

(defn consolidate!
  "Run the full consolidation pass.
  opts: :command (LLM command; default $CLAIMGRAPH_LLM_CMD or claude -p)
        :summarize-fn :judge-fn :enrich-fn (prompt -> response; injectable)
        :resolve :min-confidence (forwarded to the conflict judge)
        :min-usage (promotion-candidate threshold, default 3)
        :budget (model calls for the WHOLE pass; default 20)
        :evidence-dir (keep judge replies as content-addressed artifacts)
        :apply! (fn [thunk] ...) wrapping the ONE decide-bearing write in the
                pass — alias application, which refuses a name clash. Default:
                run it. The curator passes a write-lease wrapper so that hold
                lasts one application instead of the whole pass.

  The stages share one budget and are spent in the order written —
  judgments, summaries, sweep, enrichment: most valuable first, because a
  budget that runs out has to run out on the least valuable work. The meter
  counts ACTUAL model calls; acting on a recorded verdict, minting records,
  and every mechanical stage are free and are not requests at all.

  A stage the budget did not reach, or reached partway, reports its
  remainder as :deferred and leaves it pending by derivation. There is no
  stamp and no cadence: what is due is whatever the records say is still
  undone."
  [s {:keys [command summarize-fn judge-fn enrich-fn resolve min-confidence min-usage
             budget evidence-dir apply!]}]
  (let [apply! (or apply! (fn [f] (f)))
        allowed (max 0 (if (number? budget) (long budget) default-call-budget))
        spent (atom 0)
        spend! (fn [] (when (< @spent allowed) (swap! spent inc) true))
        run (or summarize-fn (partial llm/complete! (llm/command command)))
        judge-opts {:command command
                    :judge-fn judge-fn
                    :resolve resolve
                    :min-confidence min-confidence
                    :spend! spend!
                    :evidence-dir evidence-dir}
        conflicts (try (judge/judge-conflicts! s judge-opts)
                       (catch Exception e {:error (ex-message e)}))
        ;; after the judge, so the verdict episodes it just minted (closed at
        ;; creation) are never mistaken for work this pass owes
        all-episodes (store/-list-episodes s)
        open-ids (mapv :id (remove :closed-at all-episodes))
        ep-facts (if (seq open-ids)
                   (store/-select-facts s {:episodes open-ids})
                   [])
        episodes (summarize-episodes! s run spend! (plan-episodes all-episodes ep-facts))
        sweep (try (judge/sweep-conflicts! s judge-opts)
                   (catch Exception e {:error (ex-message e)}))
        enrichment (try (enrich-entities! s (or enrich-fn run) spend! apply!)
                        (catch Exception e {:error (ex-message e)}))]
    {:status :consolidated
     :budget {:allowed allowed :spent @spent}
     :episodes episodes
     :conflicts conflicts
     :sweep sweep
     :enrichment enrichment
     :promotion-candidates (promotion-candidates
                            (store/-list-predicates s {:status :testing})
                            (store/-predicate-usage s)
                            (or min-usage default-min-usage))}))
