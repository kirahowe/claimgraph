(ns claimgraph.consolidate
  "Dreaming-style offline consolidation: one pass that summarizes and closes
  open episodes (LLM, with a mechanical fallback), judges open conflicts,
  sweeps for conflict candidates the write path can't see, and reports :x/*
  predicates that have earned promotion review. (Disuse decay needs no pass:
  effective confidence is computed at read time.)

  Functional core / imperative shell: episode planning, prompt construction,
  summary parsing, the mechanical fallback, and promotion-candidate selection
  are pure; `consolidate!` executes the stages. The LLM is pluggable as
  everywhere ($CLAIMGRAPH_LLM_CMD, default claude -p; tests inject
  :summarize-fn / :judge-fn).

  Closing an episode with a summary is what turns bulky episodic memory into
  something retrievable: summaries are full-text indexed, so \"why did we do
  X\" becomes a search."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.judge :as judge]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def default-min-usage 3)
(def max-summary-chars 600)

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

(def max-enrichment-entities 20)
(def max-aliases-per-entity 3)

(defn enrichment-candidates
  "Entities worth enriching this pass: they carry facts (usage) but no
  aliases yet — one shot per entity, bounded per pass, most-used first.
  Aliases are the training-free path to semantic-ish retrieval: they are
  full-text indexed and the hybrid entity route resolves through them."
  [entities usage]
  (->> entities
       (filter #(empty? (:aliases %)))
       (keep (fn [e]
               (let [n (get usage (:id e) 0)]
                 (when (pos? n) (assoc e :usage n)))))
       (sort-by (fn [e] [(- (:usage e)) (:name e)]))
       (take max-enrichment-entities)
       vec))

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

(defn- summarize-episodes! [s run {:keys [to-close skipped-empty]}]
  {:closed (mapv (fn [{:keys [episode facts]}]
                   (let [summary (or (parse-summary
                                      (try (run (summary-prompt episode facts))
                                           (catch Exception _ nil)))
                                     (mechanical-summary episode facts))]
                     (store/-close-episode s (:id episode) summary (core/now))
                     {:episode (:id episode)
                      :facts (count facts)
                      :summary summary}))
                 to-close)
   :skipped-empty skipped-empty})

(defn- enrich-entities!
  "The SIRA-style stage: give alias-less, fact-bearing entities searchable
  alternative names. Every alias goes through core/alias-entity, so a name
  clash with another entity is refused there and skipped here — enrichment
  must never create ambiguity. Failures (no LLM) skip silently: enrichment
  is a bonus, never a blocker."
  [s run]
  (let [now (core/now)
        candidates (enrichment-candidates (store/-list-entities s {})
                                          (store/-entity-usage s))
        enriched
        (vec (for [e candidates
                   :let [facts (filterv #(logic/fact-valid-at? % now)
                                        (store/-get-facts s (:id e) {:direction :both}))
                         aliases (when (seq facts)
                                   (try (parse-aliases (run (enrichment-prompt e facts)) e)
                                        (catch Exception _ nil)))
                         added (vec (for [a aliases
                                          :when (try (core/alias-entity
                                                      s {:name (:name e) :alias a})
                                                     (catch Exception _ nil))]
                                      a))]
                   :when (seq added)]
               {:entity (:name e) :aliases added}))]
    {:considered (count candidates)
     :enriched enriched}))

(defn sub-stage-errors
  "The internally-caught failures a consolidate! report carries, keyed by
  stage. The stamp gate reads this (spec/maintenance.allium, decided
  2026-07-26): a pass with any of these is not CLEAN — it must stay due and
  retry next session rather than wait out a full consolidation window.
  (Episode summaries never appear here: the mechanical digest means that
  stage always makes progress.)"
  [{:keys [conflicts sweep enrichment]}]
  (into {}
        (keep (fn [[stage r]] (when (:error r) [stage (:error r)])))
        {:conflicts conflicts :sweep sweep :enrichment enrichment}))

(defn consolidate!
  "Run the full consolidation pass.
  opts: :command (LLM command; default $CLAIMGRAPH_LLM_CMD or claude -p)
        :summarize-fn :judge-fn :enrich-fn (prompt -> response; injectable)
        :resolve :min-confidence (forwarded to the conflict judge)
        :min-usage (promotion-candidate threshold, default 3)"
  [s {:keys [command summarize-fn judge-fn enrich-fn resolve min-confidence min-usage]}]
  (let [all-episodes (store/-list-episodes s)
        open-ids (mapv :id (remove :closed-at all-episodes))
        ep-facts (if (seq open-ids)
                   (store/-select-facts s {:episodes open-ids})
                   [])
        run (or summarize-fn (partial llm/complete! (llm/command command)))
        episodes (summarize-episodes! s run (plan-episodes all-episodes ep-facts))
        judge-opts {:command command
                    :judge-fn judge-fn
                    :resolve resolve
                    :min-confidence min-confidence}
        conflicts (try (judge/judge-conflicts! s judge-opts)
                       (catch Exception e {:error (ex-message e)}))
        sweep (try (judge/sweep-conflicts! s judge-opts)
                   (catch Exception e {:error (ex-message e)}))
        enrichment (try (enrich-entities! s (or enrich-fn run))
                        (catch Exception e {:error (ex-message e)}))]
    {:status :consolidated
     :episodes episodes
     :conflicts conflicts
     :sweep sweep
     :enrichment enrichment
     :promotion-candidates (promotion-candidates
                            (store/-list-predicates s {:status :testing})
                            (store/-predicate-usage s)
                            (or min-usage default-min-usage))}))
