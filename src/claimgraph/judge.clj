(ns claimgraph.judge
  "LLM judge for the semantic-conflict path. Mechanical conflict detection
  (in assert-fact) flags; the judge classifies what the flag actually means:

    contradicts — genuinely incompatible; a human must decide
    duplicate   — the newer fact restates the established one
    supersedes  — the newer fact is the legitimate successor
    compatible  — both can hold; not actually in conflict

  Functional core / imperative shell: prompt construction, verdict parsing,
  the verdict ref, and the resolution plan are pure; `judge-conflicts!`
  iterates the store's open conflicts through the pluggable LLM (same
  subscription-as-judge mechanism as the session extractor; tests inject
  :judge-fn).

  By default the judge only enriches — it reports verdicts and acts on
  nothing. With :resolve it executes the plan for verdicts at or above
  :min-confidence, and even then a contradicts verdict is never auto-resolved:
  surfacing those to the human is the point of the flag machinery.

  Every delivered verdict is RECORDED as a curation episode whose ref is the
  verdict itself (`record-verdict!`), so no unchanged pair is ever judged
  twice: the episode log is the judge's memory, exactly as it is ingestion's
  delta state. A pair whose verdict the graph already holds costs no model
  call — an enrich-only run reports the record, a resolve run acts on it.
  That is also what makes the sweep terminate: before records, the same
  benign `compatible` pairs were re-bought from the judge every single pass."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.llm :as llm]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

(def relations #{:contradicts :duplicate :supersedes :compatible})
(def default-min-confidence 0.8)

;; ---------------------------------------------------------------------------
;; Pure: prompt
;; ---------------------------------------------------------------------------

(defn fact->summary
  "Compact, prompt- and report-friendly view of a fact."
  [f]
  {:id (:id f)
   :subject (get-in f [:subject :name])
   :predicate (:predicate f)
   :object (if (= :entity (:object-kind f))
             (get-in f [:object-ref :name])
             (:object-lit f))
   :epistemic (:epistemic f)
   :source-type (:source-type f)
   :scope (:scope f)
   :confidence (:confidence f)
   :recorded-at (:recorded-at f)})

(defn judgment-prompt
  "Prompt for one conflict pair. pred-a/pred-b are the registry rows for the
  two facts' predicates — equal for mechanically-flagged pairs, possibly
  different for swept cross-predicate candidates."
  [{:keys [fact candidate]} pred-a pred-b]
  (let [pred-line (fn [p] (str "Predicate " (subs (str (:id p)) 1) ": " (:definition p)))]
    (str
     "Two facts about the same subject in a project knowledge graph were\n"
     "proposed as a possible conflict. Fact A is the newer assertion; fact B\n"
     "is the established one. Judge the semantic relationship of A to B.\n\n"
     "Respond with a single JSON object and nothing else — no prose, no fences:\n"
     "{\"relation\": \"contradicts\"|\"duplicate\"|\"supersedes\"|\"compatible\",\n"
     " \"confidence\": 0.0-1.0,\n"
     " \"rationale\": \"one sentence\"}\n\n"
     "Definitions:\n"
     "- contradicts: genuinely incompatible claims; a human must decide.\n"
     "- duplicate: A restates B in different words; A adds nothing.\n"
     "- supersedes: A is the legitimate successor of B; B is outdated.\n"
     "- compatible: both can be true at once; not actually in conflict.\n\n"
     (->> (distinct (keep #(when % (pred-line %)) [pred-a pred-b]))
          (str/join "\n"))
     "\n\nFact A (newer):\n" (json/generate-string (fact->summary fact))
     "\n\nFact B (established):\n" (json/generate-string (fact->summary candidate)) "\n")))

;; ---------------------------------------------------------------------------
;; Pure: verdict parsing & resolution plan
;; ---------------------------------------------------------------------------

(defn- parse-verdict
  "One candidate JSON string -> verdict, or nil when it is not one. Anything
  that throws is just another non-verdict: the caller keeps looking."
  [s]
  (try (let [m (json/parse-string s true)
             relation (logic/->kw (:relation m))]
         (when (relations relation)
           {:relation relation
            :confidence (let [c (:confidence m)]
                          (if (number? c)
                            (-> c double (max 0.0) (min 1.0))
                            0.0))
            :rationale (:rationale m)}))
       (catch Exception _ nil)))

;; Bounds on the brace-scan fallback. A verdict is a few hundred bytes; a
;; megabyte of response, or a few dozen candidate objects, is already far past
;; anything a judgment can be hiding in, and this runs inside the call the LLM
;; timeout is there to bound. The candidate cap also keeps the substring copies
;; linear when the braces are deeply nested.
(def ^:private max-scan-chars 1048576)
(def ^:private max-candidates 64)

(defn- json-objects
  "Every brace-balanced {...} run in text, in start order: one left-to-right
  pass keeping a stack of open positions, not a fresh scan per '{' — that shape
  walked to end-of-text for every brace that never closes, which is quadratic on
  exactly the rambling response this fallback exists to rescue.

  track-strings? decides whether a brace inside a JSON string counts. It must
  not (a rationale may contain one), but tracking carries the model's own quote
  parity forward, so a stray quote can hide the verdict below it — hence both
  passes, string-aware first. Escapes are honoured, and a raw newline ends a
  string, since a JSON string cannot contain one."
  [^String text track-strings?]
  (let [n (min (count text) max-scan-chars)]
    (loop [i 0, in-string? false, escaped? false, opens (), spans []]
      (if (>= i n)
        (->> (sort-by first spans)
             (take max-candidates)
             (map (fn [[s e]] (subs text s e))))
        (let [c (.charAt text i), j (inc i)]
          (cond
            escaped? (recur j true false opens spans)
            (and in-string? (= \\ c)) (recur j true true opens spans)
            (and in-string? (= \newline c)) (recur j false false opens spans)
            in-string? (recur j (not= \" c) false opens spans)
            (= \{ c) (recur j false false (conj opens i) spans)
            (and (= \} c) (seq opens)) (recur j false false (pop opens)
                                             (conj spans [(peek opens) j]))
            ;; quotes only matter inside an object; at depth 0 they are prose
            (and track-strings? (= \" c) (seq opens)) (recur j true false opens spans)
            :else (recur j false false opens spans)))))))

(defn parse-judgment
  "Tolerant parse of the judge's response: first JSON object with a known
  relation wins. A one-line object is the common case and is tried first;
  failing that the whole response is brace-scanned, which recovers a verdict
  pretty-printed across lines or wrapped in a ```json fence. Unparseable
  responses become a zero-confidence verdict rather than an exception — one
  bad judgment must not kill the batch."
  [response]
  (let [text (or response "")]
    (or (->> (str/split-lines text)
             (map str/trim)
             (remove #(or (str/blank? %) (str/starts-with? % "```")))
             (keep parse-verdict)
             first)
        (first (keep parse-verdict
                     (concat (json-objects text true)
                             ;; lazy: the quote-blind retry is only paid for when
                             ;; the string-aware pass found no verdict at all
                             (lazy-seq (json-objects text false)))))
        {:relation :unparseable :confidence 0.0})))

(defn resolution-plan
  "Pure: verdict -> effect plan for one conflict pair.

    {:action :invalidate :fact-id id :kind kw :successor id :reason str}
    {:action :unlink}
    {:action :none :reason :needs-human|:low-confidence|:unparseable}

  The invalidation carries its kind and its successor as data, not only as
  prose in the reason sentence: the compiled context recovers a supersession
  from the structured :successor field, so a verdict's own phrasing of the
  reason can never keep it out of the briefing that reports what changed."
  [{:keys [fact candidate]} {:keys [relation confidence]} min-confidence]
  (cond
    (= :contradicts relation) {:action :none :reason :needs-human}
    (not (relations relation)) {:action :none :reason :unparseable}
    (< confidence min-confidence) {:action :none :reason :low-confidence}
    :else (case relation
            :duplicate {:action :invalidate :fact-id (:id fact)
                        :kind :judged-duplicate
                        :successor (:id candidate)
                        :reason (str "judged duplicate of " (:id candidate))}
            :supersedes {:action :invalidate :fact-id (:id candidate)
                         :kind :judged-superseded
                         :successor (:id fact)
                         :reason (str "judged superseded by " (:id fact))}
            :compatible {:action :unlink})))

;; ---------------------------------------------------------------------------
;; Pure: the verdict record (a curation episode ref)
;; ---------------------------------------------------------------------------

(def verdict-ref-prefix "verdict:")

(defn- conf-str
  "Confidence at two decimal places, LOCALE-FIXED. `format` follows the
  default locale, and under a comma-decimal one (de_DE, fr_FR) the same
  verdict would spell its ref \"@0,90\" — a second, unreadable record for a
  verdict the graph already holds, which is precisely the re-judging this
  record exists to end."
  [confidence]
  (String/format java.util.Locale/ROOT "%.2f" (to-array [(double confidence)])))

(defn verdict-ref
  "The curation episode ref that records one pair's verdict:

    verdict:<idA>+<idB>=<relation>@<confidence>

  The ids are sorted lexically and the confidence is fixed to two places, so
  the spelling is CANONICAL: the same judgment about the same pair produces
  a byte-identical ref no matter which side the caller called newer. That is
  what lets `record-verdict!` be idempotent on re-delivery and what lets
  `recorded-verdicts` answer for a pair without knowing its orientation.

  Fact ids are immutable content snapshots, which is why a per-id-pair
  record can be final: changed content mints a new id, so re-judgment happens
  exactly when the claim actually changed and never on a clock."
  [id-a id-b relation confidence]
  (let [[x y] (sort [(str id-a) (str id-b)])]
    (str verdict-ref-prefix x "+" y "=" (name relation) "@" (conf-str confidence))))

(def ^:private verdict-ref-re #"^verdict:([^+]+)\+([^=]+)=([^@]+)@([^@]+)$")

(defn recorded-verdicts
  "Episodes -> {#{id-a id-b} {:relation kw :confidence double}}: the verdicts
  the judge has already delivered, parsed back out of curation episode refs.
  The episode log IS the judge's memory — no counters, no stamp files, no
  bookkeeping surface beside the graph.

  Tolerant like every other ref parser here: a ref that doesn't match the
  shape, names a relation this build doesn't know, or carries a
  non-numeric confidence is skipped silently. The cost of a ref we can't read
  is one re-judgment, and that must never be an exception on an unrelated
  read."
  [episodes]
  (reduce (fn [m {:keys [source-type ref]}]
            (if-not (= :curation source-type)
              m
              (let [[_ a b relation confidence] (re-matches verdict-ref-re (str ref))
                    relation (some-> relation logic/->kw)
                    confidence (some-> confidence parse-double)]
                (if (and (relations relation) confidence)
                  (assoc m #{a b} {:relation relation :confidence confidence})
                  m))))
          {} episodes))

(defn- fact-phrase [f]
  (let [{:keys [subject predicate object]} (fact->summary f)]
    (str subject " " (subs (str predicate) 1) " " object)))

(defn verdict-summary
  "The human-readable half of a verdict record: what was judged, how sure,
  and why. The ref carries the machine-readable copy; this is what a person
  reading `claim episodes` — or a full-text search over episode summaries —
  actually sees."
  [{:keys [fact candidate]} {:keys [relation confidence rationale]}]
  (str "judged " (name relation) " (" (conf-str confidence) "): "
       (fact-phrase fact) " vs " (fact-phrase candidate)
       (when-not (str/blank? (str rationale)) (str " — " rationale))))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn record-verdict!
  "Mint the curation episode that makes one delivered verdict durable:
  source :curation, the canonical ref, closed at creation with a readable
  summary. Returns the ref it recorded, or nil when it recorded nothing.

  IDEMPOTENT on the ref — an episode already carrying it means this verdict
  is already the graph's, and re-delivery (a resolve run acting on a stored
  verdict, a re-run of the same pass) must not mint a second copy.

  An UNPARSEABLE verdict records NOTHING: an unanswered question has to be
  retried under a later budget, while a recorded non-answer would skip a
  real conflict forever.

  With :evidence-dir the judge's raw reply is kept as a content-addressed
  artifact and pointed at by the episode, the same tier the ingest paths
  write to — a verdict is a judgment call and the bytes behind it are what
  makes it auditable. An evidence write that fails must not cost the record
  itself: without the record the pair would be re-judged forever, so the
  episode is minted regardless and simply carries no pointer."
  [s pair {:keys [relation confidence] :as verdict} {:keys [evidence-dir reply]}]
  (when (relations relation)
    (let [ref (verdict-ref (:id (:fact pair)) (:id (:candidate pair)) relation confidence)]
      (when-not (some #(= ref (str (:ref %))) (store/-list-episodes s))
        (let [evidence (when (and evidence-dir reply)
                         (try ((requiring-resolve 'claimgraph.evidence/write!)
                               evidence-dir reply)
                              (catch Exception _ nil)))
              ep (core/open-episode s {:source-type :curation :ref ref
                                       :evidence evidence})]
          (core/close-episode s {:episode (:id ep)
                                 :summary (verdict-summary pair verdict)})
          ref)))))

(defn- execute-resolution! [s at {:keys [fact candidate]} plan]
  (case (:action plan)
    :invalidate (store/-invalidate s (:fact-id plan) at
                                   (select-keys plan [:kind :successor :reason]))
    :unlink (store/-unlink-conflicts s (:id fact) [(:id candidate)])
    nil))

(defn judge-conflicts!
  "Run the judge over every open conflict THE JUDGE HAS NOT ALREADY ANSWERED.
  opts: :command (LLM command string; default $CLAIMGRAPH_LLM_CMD or claude -p)
        :judge-fn (prompt -> response; injectable, used by tests)
        :resolve (execute resolution plans; default false = enrich only)
        :min-confidence (gate for acting on a verdict; default 0.8)
        :spend! (0-arg budget gate; truthy = one model call is affordable.
                 Default unlimited)
        :evidence-dir (keep judge replies as content-addressed artifacts)

  A pair whose verdict is already recorded never costs a second model call:
  an enrich-only run reports the record (:from-record true, the conflict
  listing enriched for free), and a resolve run runs the recorded relation
  and confidence through the same resolution plan a fresh verdict would take
  — acting on knowledge the graph holds is free, so it is not metered.

  Pairs the budget did not reach are counted in :deferred rather than
  dropped quietly: nothing was recorded for them, so they are still pending
  by derivation and the next run picks them up where this one stopped."
  [s {:keys [command judge-fn resolve min-confidence spend! evidence-dir]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        spend! (or spend! (constantly true))
        min-confidence (double (or min-confidence default-min-confidence))
        recorded (recorded-verdicts (store/-list-episodes s))
        deferred (atom 0)
        results
        (into []
              (keep (fn [{:keys [fact candidate] :as pair}]
                      (let [seen (recorded #{(:id fact) (:id candidate)})
                            ;; wrapped, so a judge-fn answering nil is still a
                            ;; delivery (it parses to unparseable) and not a
                            ;; deferral
                            reply (when-not seen
                                    (if (spend!)
                                      {:text (run (judgment-prompt
                                                   pair
                                                   (store/-get-predicate s (:predicate fact))
                                                   (store/-get-predicate s (:predicate candidate))))}
                                      (do (swap! deferred inc) nil)))]
                        (when (or seen reply)
                          (let [verdict (or seen (parse-judgment (:text reply)))
                                plan (resolution-plan pair verdict min-confidence)]
                            (when reply
                              (record-verdict! s pair verdict
                                               {:evidence-dir evidence-dir
                                                :reply (:text reply)}))
                            (when resolve
                              (execute-resolution! s at pair plan))
                            (cond-> {:fact (fact->summary fact)
                                     :candidate (fact->summary candidate)
                                     :verdict verdict
                                     :plan plan}
                              seen (assoc :from-record true)
                              resolve (assoc :executed (not= :none (:action plan)))))))))
              (:conflicts (core/conflicts s)))]
    (cond-> {:conflicts (count results)
             :resolved (count (filter :executed results))
             :results results}
      (pos? @deferred) (assoc :deferred @deferred))))

(defn sweep-conflicts!
  "Deferred candidate generation: propose judgeable pairs the write path
  can't see (pure, per-subject bounded — logic/conflict-candidates), run each
  through the LLM verdict ONCE EVER, and link genuine hits into the same
  conflict pipeline. Linked contradictions surface in `conflicts` for the
  human; with :resolve, duplicate/supersedes verdicts at or above
  :min-confidence are executed immediately (same resolution plans as
  judge-conflicts!).

  \"Once ever\" is structural, not remembered in place: candidates skip pairs
  that are already linked AND pairs with a recorded verdict, and EVERY
  parseable verdict is recorded — compatible included, which is the whole
  point. A compatible verdict still mutates nothing (the pair was never
  linked), but dropping it silently is what made the sweep re-buy the same
  benign pairs from the judge every pass. Only an unparseable reply leaves no
  record and retries under a later budget.

  opts: as judge-conflicts!, including :spend! and :evidence-dir."
  [s {:keys [command judge-fn resolve min-confidence spend! evidence-dir]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        spend! (or spend! (constantly true))
        min-confidence (double (or min-confidence default-min-confidence))
        recorded (recorded-verdicts (store/-list-episodes s))
        deferred (atom 0)
        preds (store/-list-predicates s {})
        preds-by-id (into {} (map (juxt :id identity)) preds)
        watched (->> preds
                     (filter #(or (:exclusion-group %)
                                  (= :exclusive (:value-exclusivity %))
                                  (= :decision (:category %))))
                     (mapv :id))
        ;; two-step candidate fetch: watched facts find the interesting
        ;; subjects; those subjects' FULL out-fact sets (any predicate) give
        ;; the cross-predicate clause its partners. Bounded by subjects
        ;; holding decision-shaped facts, never the graph.
        seeds (if (seq watched)
                (store/-select-facts s {:valid-cheap true :predicates watched})
                [])
        subject-ids (distinct (map (comp :id :subject) seeds))
        facts (if (seq subject-ids)
                (store/-get-facts-for s subject-ids {:direction :out})
                [])
        results
        (into []
              (keep (fn [{:keys [fact candidate reason] :as pair}]
                      (if-not (spend!)
                        (do (swap! deferred inc) nil)
                        (let [reply (run (judgment-prompt pair
                                                          (preds-by-id (:predicate fact))
                                                          (preds-by-id (:predicate candidate))))
                              verdict (parse-judgment reply)
                              hit? (#{:contradicts :duplicate :supersedes} (:relation verdict))
                              plan (when hit? (resolution-plan pair verdict min-confidence))]
                          (record-verdict! s pair verdict {:evidence-dir evidence-dir
                                                           :reply reply})
                          (when hit?
                            (store/-link-conflicts s (:id fact) [(:id candidate)])
                            (when resolve
                              (execute-resolution! s at pair plan)))
                          (cond-> {:fact (fact->summary fact)
                                   :candidate (fact->summary candidate)
                                   :reason reason
                                   :verdict verdict}
                            hit? (assoc :linked true :plan plan)
                            (and hit? resolve) (assoc :executed (not= :none (:action plan))))))))
              (remove (fn [{:keys [fact candidate]}]
                        (recorded #{(:id fact) (:id candidate)}))
                      (logic/conflict-candidates facts preds-by-id at)))]
    (cond-> {:candidates (count results)
             :linked (count (filter :linked results))
             :resolved (count (filter :executed results))
             :results results}
      (pos? @deferred) (assoc :deferred @deferred))))
