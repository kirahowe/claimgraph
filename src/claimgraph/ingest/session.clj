(ns claimgraph.ingest.session
  "Session-log extractor: turn a session transcript into durable facts via a
  pluggable LLM extractor. Functional core / imperative shell: transcript
  normalization, prompt construction, and response parsing/clamping are all
  pure; the only effects are reading the transcript and shelling out to the
  extractor command.

  The default extractor is `claude -p` — an already-authenticated agent CLI
  (subscription-as-judge, ~$0 marginal). Override with --extractor or
  $CLAIMGRAPH_LLM_CMD; tests inject :extractor-fn and never shell out.

  Session-derived facts are second-class evidence by design: confidence is
  capped at 0.7 and source-type is forced to :session-log."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.predicates :as preds]
            [claimgraph.store :as store]))

(def max-confidence 0.7)
(def default-confidence 0.6)
(def roster-limit 200)

;; ---------------------------------------------------------------------------
;; Pure: transcript normalization
;; ---------------------------------------------------------------------------

(defn- content-text [content]
  (cond
    (string? content) content
    (sequential? content) (->> content
                               (keep #(when (= "text" (:type %)) (:text %)))
                               (str/join "\n"))
    :else nil))

(defn- jsonl-message
  "One JSONL line -> {:role :text}, :skip for JSON lines with no message
  text (tool results, summaries), nil for non-JSON."
  [line]
  (try
    (let [m (json/parse-string line true)]
      (when (map? m)
        (let [msg (or (:message m) m)
              text (content-text (:content msg))]
          (if (and (:role msg) (not (str/blank? (str text))))
            {:role (:role msg) :text text}
            :skip))))
    (catch Exception _ nil)))

(defn ->transcript
  "Plain text passes through untouched; Claude-Code-style session JSONL
  (every line a JSON map) is flattened to \"role: text\" turns."
  [content]
  (let [lines (remove str/blank? (str/split-lines content))
        parsed (map jsonl-message lines)]
    (if (and (seq parsed) (every? some? parsed))
      (->> parsed
           (remove #{:skip})
           (map #(str (name (:role %)) ": " (:text %)))
           (str/join "\n\n"))
      content)))

;; ---------------------------------------------------------------------------
;; Pure: prompt
;; ---------------------------------------------------------------------------

(defn entity-roster
  "The known-entity slice shown to the extractor: top entities by fact count
  (then name), rendered one per line with aliases and type. A prior, not a
  constraint — entities are open-world, so the roster says 'prefer these
  exact names when you mean them', never 'only these exist'."
  [entities usage limit]
  (->> entities
       (sort-by (fn [e] [(- (get usage (:id e) 0)) (:name e)]))
       (take limit)
       (mapv (fn [{ename :name etype :type aliases :aliases}]
               (str "  " ename
                    (when (seq aliases) (str " (aka " (str/join ", " aliases) ")"))
                    (when etype (str " [" (name etype) "]")))))))

(def prose-object-note
  "What a prose-shaped predicate's line says beyond its definition.

  The extractor cannot read the registry, so an unmarked vocabulary listing
  teaches it that every object is a short datum — and it obliges, trimming
  lessons into slogans before the admission screen ever sees them. That is
  self-censorship no widened bound can undo: the screen can only admit what
  was written. One clause per prose predicate, and nothing at all on the
  others, so the marking reads as permission rather than as instruction."
  " [object may be a full lesson, a sentence or three]")

(defn vocabulary-lines
  "The allowed-predicate listing both extractors show: one line per predicate,
  its definition, and — on the prose-shaped ones — the note above."
  [predicates]
  (str/join "\n"
            (for [p predicates]
              (str "  " (subs (str (:id p)) 1) " — " (:definition p)
                   (when (= :prose (preds/object-shape p)) prose-object-note)))))

(defn extraction-prompt [transcript predicates roster]
  (str
   "Extract durable project memory from this coding-session transcript.\n\n"
   "Emit one JSON object per line (JSONL) and nothing else — no prose, no code fences.\n"
   "Keys: subject (entity name), predicate, object, object_kind (\"entity\"|\"literal\"),\n"
   "class (\"observation\"|\"commitment\"|\"preference\"), confidence (0.0-1.0), scope (optional).\n\n"
   "Extract ONLY durable knowledge: stated preferences, decisions made or reported\n"
   "(including rejected alternatives), discovered gotchas and constraints, and conventions.\n"
   "Do NOT extract transient task chatter, edit-by-edit narration, or anything already\n"
   "obvious from the code itself. Prefer fewer, higher-value facts.\n"
   "Subjects and entity-kind objects must be stable names (services, namespaces, tools,\n"
   "ADR ids, people), never sentences; free text belongs in literal objects.\n\n"
   "Allowed predicates (coin x/<new-name> only if none fits):\n"
   (vocabulary-lines predicates)
   (when (seq roster)
     (str "\n\nKnown entities — when you mean one of these, use its EXACT name\n"
          "(synonym drift fragments the graph); coin a new name only when none\n"
          "of these is the thing you mean:\n"
          (str/join "\n" roster)))
   "\n\nIf nothing qualifies, output nothing.\n\n"
   "<transcript>\n" transcript "\n</transcript>"))

;; ---------------------------------------------------------------------------
;; Pure: response parsing & clamping
;; ---------------------------------------------------------------------------

(defn parse-extraction
  "Tolerant JSONL parse of an LLM response: keeps JSON-object lines, drops
  prose, fences, and junk."
  [response]
  (->> (str/split-lines (or response ""))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "```")))
       (keep #(try (let [m (json/parse-string % true)]
                     (when (map? m) m))
                   (catch Exception _ nil)))
       (mapv logic/normalize-keys)))

(defn prepare-facts
  "Validate and clamp extracted candidates: complete triples become facts
  with confidence capped at 0.7 and source-type forced — :session-log by
  default; the failure tier passes :failure-report so its facts carry the
  source the trust ranking names (spec/ingestion.allium, decided
  2026-07-26). Incomplete candidates are returned as :rejected."
  ([extracted] (prepare-facts extracted :session-log))
  ([extracted source-type]
   (let [complete? (fn [m] (every? #(not (str/blank? (str (get m %))))
                                   [:subject :predicate :object]))
         clamp (fn [m]
                 (let [c (:confidence m)]
                   (-> m
                       (assoc :confidence (min max-confidence
                                               (if (number? c) (double c) default-confidence)))
                       (assoc :source-type source-type))))
         {facts true rejected false} (group-by complete? extracted)]
     {:facts (mapv clamp facts)
      :rejected (vec rejected)})))

;; ---------------------------------------------------------------------------
;; Shell: extractor process + ingestion
;; ---------------------------------------------------------------------------

(defn extract!
  "Run the pluggable extractor over a transcript and ingest the results.
  opts: :file (path; stdin when absent) | :transcript (string, wins over :file)
        :ref (episode ref) :extractor (command string)
        :extractor-fn (prompt -> response; injectable, used by tests)
        :evidence-dir (keep the raw transcript as a content-addressed
                       artifact the episode points to; skipped when absent)
        :dry-run (extract and report, write nothing)"
  [s {:keys [file transcript ref extractor extractor-fn evidence-dir dry-run]}]
  (let [content (or transcript (if file (slurp file) (slurp *in*)))
        entities (store/-list-entities s {})
        predicates (store/-list-predicates s {:status :stable})
        run (or extractor-fn
                (partial llm/complete! (llm/command extractor)))
        prompt (extraction-prompt (->transcript content)
                                  predicates
                                  (entity-roster entities
                                                 (store/-entity-usage s)
                                                 roster-limit))
        {:keys [facts rejected]} (prepare-facts (parse-extraction (run prompt)))
        {:keys [admitted inadmissible]} (logic/screen-candidates
                                         facts (logic/admission-ctx entities predicates))]
    (if dry-run
      (cond-> {:status :dry-run :facts admitted :rejected rejected}
        (seq inadmissible) (assoc :inadmissible inadmissible))
      (let [evidence (when evidence-dir
                       ((requiring-resolve 'claimgraph.evidence/write!)
                        evidence-dir content))]
        (cond-> (core/ingest s {:source-type :session-log
                                :ref (or ref (some-> file str) "session")
                                :evidence evidence}
                             admitted)
          evidence (assoc :evidence evidence)
          (seq inadmissible) (assoc :inadmissible inadmissible)
          (seq rejected) (assoc :rejected rejected))))))
