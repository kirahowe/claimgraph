(ns claimgraph.judge
  "LLM judge for the semantic-conflict path. Mechanical conflict detection
  (in assert-fact) flags; the judge classifies what the flag actually means:

    contradicts — genuinely incompatible; a human must decide
    duplicate   — the newer fact restates the established one
    supersedes  — the newer fact is the legitimate successor
    compatible  — both can hold; not actually in conflict

  Functional core / imperative shell: prompt construction, verdict parsing,
  and the resolution plan are pure; `judge-conflicts!` iterates the store's
  open conflicts through the pluggable LLM (same subscription-as-judge
  mechanism as the session extractor; tests inject :judge-fn).

  By default the judge only enriches — it reports verdicts and acts on
  nothing. With :resolve it executes the plan for verdicts at or above
  :min-confidence, and even then a contradicts verdict is never auto-resolved:
  surfacing those to the human is the point of the flag machinery."
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

    {:action :invalidate :fact-id id :reason str}
    {:action :unlink}
    {:action :none :reason :needs-human|:low-confidence|:unparseable}"
  [{:keys [fact candidate]} {:keys [relation confidence]} min-confidence]
  (cond
    (= :contradicts relation) {:action :none :reason :needs-human}
    (not (relations relation)) {:action :none :reason :unparseable}
    (< confidence min-confidence) {:action :none :reason :low-confidence}
    :else (case relation
            :duplicate {:action :invalidate :fact-id (:id fact)
                        :reason (str "judged duplicate of " (:id candidate))}
            :supersedes {:action :invalidate :fact-id (:id candidate)
                         :reason (str "judged superseded by " (:id fact))}
            :compatible {:action :unlink})))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn- execute-resolution! [s at {:keys [fact candidate]} plan]
  (case (:action plan)
    :invalidate (store/-invalidate s (:fact-id plan) at (:reason plan))
    :unlink (store/-unlink-conflicts s (:id fact) [(:id candidate)])
    nil))

(defn judge-conflicts!
  "Run the judge over every open conflict.
  opts: :command (LLM command string; default $CLAIMGRAPH_LLM_CMD or claude -p)
        :judge-fn (prompt -> response; injectable, used by tests)
        :resolve (execute resolution plans; default false = enrich only)
        :min-confidence (gate for acting on a verdict; default 0.8)"
  [s {:keys [command judge-fn resolve min-confidence]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        min-confidence (double (or min-confidence default-min-confidence))
        results
        (mapv (fn [{:keys [fact candidate] :as pair}]
                (let [verdict (parse-judgment
                               (run (judgment-prompt pair
                                                     (store/-get-predicate s (:predicate fact))
                                                     (store/-get-predicate s (:predicate candidate)))))
                      plan (resolution-plan pair verdict min-confidence)]
                  (when resolve
                    (execute-resolution! s at pair plan))
                  (cond-> {:fact (fact->summary fact)
                           :candidate (fact->summary candidate)
                           :verdict verdict
                           :plan plan}
                    resolve (assoc :executed (not= :none (:action plan))))))
              (:conflicts (core/conflicts s)))]
    {:conflicts (count results)
     :resolved (count (filter :executed results))
     :results results}))

(defn sweep-conflicts!
  "Deferred candidate generation: propose judgeable pairs the write path
  can't see (pure, per-subject bounded — logic/conflict-candidates), run each
  through the LLM verdict once, and link genuine hits into the same conflict
  pipeline. Compatible and unparseable verdicts are dropped silently — a
  noisy generator can't mutate anything. Linked contradictions surface in
  `conflicts` for the human; with :resolve, duplicate/supersedes verdicts at
  or above :min-confidence are executed immediately (same resolution plans
  as judge-conflicts!)."
  [s {:keys [command judge-fn resolve min-confidence]}]
  (let [at (java.util.Date.)
        run (or judge-fn (partial llm/complete! (llm/command command)))
        min-confidence (double (or min-confidence default-min-confidence))
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
        (mapv (fn [{:keys [fact candidate reason] :as pair}]
                (let [verdict (parse-judgment
                               (run (judgment-prompt pair
                                                     (preds-by-id (:predicate fact))
                                                     (preds-by-id (:predicate candidate)))))
                      hit? (#{:contradicts :duplicate :supersedes} (:relation verdict))
                      plan (when hit? (resolution-plan pair verdict min-confidence))]
                  (when hit?
                    (store/-link-conflicts s (:id fact) [(:id candidate)])
                    (when resolve
                      (execute-resolution! s at pair plan)))
                  (cond-> {:fact (fact->summary fact)
                           :candidate (fact->summary candidate)
                           :reason reason
                           :verdict verdict}
                    hit? (assoc :linked true :plan plan)
                    (and hit? resolve) (assoc :executed (not= :none (:action plan))))))
              (logic/conflict-candidates facts preds-by-id at))]
    {:candidates (count results)
     :linked (count (filter :linked results))
     :resolved (count (filter :executed results))
     :results results}))
