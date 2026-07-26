(ns claimgraph.logic
  "The functional core: pure decision logic over plain values. Nothing in
  this namespace touches a store, the clock, or a random-number generator —
  time and fresh ids arrive as arguments, store reads arrive as values, and
  writes leave as effect plans for the shell (claimgraph.core) to execute.
  Every function here is referentially transparent (throws are deterministic)."
  (:require [clojure.string :as str]
            [claimgraph.predicates :as preds]
            ;; the wire-shape DECLARATION, not a store: claimgraph.store is a
            ;; leaf holding the protocol and the field table, and reading the
            ;; table here is what keeps the dump's rehydration from being a
            ;; second, drifting copy of it
            [claimgraph.store :as store]))

(def default-scope "project")

(defn fail [msg data]
  (throw (ex-info msg (assoc data :claimgraph/error true))))

;; ---------------------------------------------------------------------------
;; Time (values in, booleans out)
;; ---------------------------------------------------------------------------

(defn ms ^long [^java.util.Date d] (.getTime d))

(defn fact-valid-at?
  "Valid-time check: t-valid <= at < t-invalid (open interval when no t-invalid)."
  [fact ^java.util.Date at]
  (let [tv (:t-valid fact) ti (:t-invalid fact)]
    (boolean (and tv
                  (<= (ms tv) (ms at))
                  (or (nil? ti) (> (ms ti) (ms at)))))))

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(defn ->kw
  "Normalize a CLI/JSON value to a keyword: \"core/depends-on\", \":core/depends-on\"
  and :core/depends-on all become :core/depends-on."
  [v]
  (cond
    (keyword? v) v
    (nil? v) nil
    :else (let [s (str/replace (str/trim (str v)) #"^:" "")]
            (when (seq s) (keyword s)))))

(defn normalize-keys
  "snake_case or kebab-case JSON keys -> kebab-case keywords."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v])) m))

(defn parse-instant
  "ISO date or instant string -> java.util.Date (dates get midnight UTC).
  Dates pass through; unparseable input fails deterministically."
  [v]
  (cond
    (instance? java.util.Date v) v
    (nil? v) nil
    :else
    (let [s (str/trim (str v))]
      (when (seq s)
        (let [iso (if (re-matches #"\d{4}-\d{2}-\d{2}" s) (str s "T00:00:00Z") s)]
          (try (java.util.Date/from (java.time.Instant/parse iso))
               (catch Exception _
                 (fail (str "Unparseable instant: " v)
                       {:type :invalid-instant :value v}))))))))

(defn normalize-ingest-fact
  "Ingest payloads may say :class where the API says :epistemic, and
  :valid-from/:valid-until as ISO strings where the API says
  :t-valid/:t-invalid as dates."
  [m]
  (let [tv (or (:t-valid m) (parse-instant (:valid-from m)))
        ti (or (:t-invalid m) (parse-instant (:valid-until m)))]
    (cond-> (-> m
                (update :epistemic #(or % (:class m)))
                (dissoc :class :valid-from :valid-until))
      tv (assoc :t-valid tv)
      ti (assoc :t-invalid ti))))

;; ---------------------------------------------------------------------------
;; Invalidation: why a fact's interval closed, as structure
;; ---------------------------------------------------------------------------

(def invalidation-kinds
  "Every kind of invalidation claimgraph performs, and the producer of each.
  These used to be distinguishable only by reading the reason sentence, which
  meant one producer could — and did — phrase itself out of every reader:
  the judge wrote \"judged superseded by <id>\", the compiled context matched
  ^superseded by (\\S+)$, and an LLM-resolved supersession therefore never
  appeared in the briefing built to show what changed.

    :superseded          assert-fact closed it for a newer assertion
    :judged-superseded   the LLM judge ruled the newer fact its successor
    :judged-duplicate    the LLM judge ruled it a restatement of another
    :merge-duplicate     an entity merge left the same claim twice
    :reconcile-duplicate reconcile found two writers had claimed the same
    :code-absent         the declaration is gone from the source it came from
    :manual              a human ran `claim invalidate`

  Readers must NOT treat this set as closed. A store outlives the build that
  wrote it: a dump or an oplog from a newer claimgraph can carry a kind this
  one has never heard of, and the additive change that introduced it does not
  move the format version (see claimgraph.version). So an unrecognised kind
  is kept verbatim and simply matches nothing — the same outcome as the nil
  a caller that hasn't been taught its kind produces, and the reason nothing
  here validates against this set."
  #{:superseded :judged-superseded :judged-duplicate :merge-duplicate
    :reconcile-duplicate :code-absent :manual})

(def supersession-kinds
  "The kinds that mean a successor took this fact's place — what \"changed
  recently\" is asking about. Duplicate collapses are excluded deliberately:
  nothing changed, one of two identical rows was retired, and rendering that
  as X → X is noise in the one section a human reads to catch up."
  #{:superseded :judged-superseded})

(defn invalidation
  "Normalize what a caller passed to store/-invalidate into
  {:kind kw|nil :successor str|nil :reason str}.

  A bare string is a reason with no structure, and both backends route
  through here so that stays true in one place rather than two. It is what
  every caller passed before kinds existed, and what a caller that has not
  been taught its kind still passes; accepting it is what lets the producers
  be converted one at a time instead of in one commit that has to be right
  everywhere at once.

  :kind arrives as a keyword from a live caller and as a string from JSON —
  an oplog line replayed on another machine, a dump reloaded — so it is
  coerced rather than trusted, the same discipline rehydrate-dump-record
  applies to every other keyword-valued column."
  [in]
  (if (map? in)
    {:kind (->kw (:kind in))
     :successor (some-> (:successor in) str)
     :reason (some-> (:reason in) str)}
    {:kind nil :successor nil :reason (some-> in str)}))

(defn- strip-nils [m] (into {} (filter (comp some? val)) m))

(defn- kw-fields [m ks]
  (reduce (fn [a k] (if (some? (get a k)) (update a k ->kw) a)) m ks))

(defn- date-fields [m ks]
  (reduce (fn [a k] (if (some? (get a k)) (update a k parse-instant) a)) m ks))

(defn- double-fields [m ks]
  (reduce (fn [a k] (if (some? (get a k)) (update a k double) a)) m ks))

(defn- embedded-entity-fields
  "Nested entity maps carry the one keyword an embedding of them has: :type."
  [m ks]
  (reduce (fn [a k]
            (if (some? (get a k))
              (update a k #(-> % (kw-fields [:type]) strip-nils))
              a))
          m ks))

(def dump-discriminator
  "The key a dump record says its kind under. Emphatically NOT :type: an
  entity's wire shape already owns :type (store/Store), so a discriminator
  written there overwrites the entity's real type with the word \"entity\" —
  and unrecoverably, because the reader then strips the very key it read. A
  :service dumped and loaded came back typed nothing at all, which quietly
  disarms type-guarded resolution and empties `entity list --type`."
  :record)

(def dump-kinds
  "Every record kind a dump carries. Kept beside the discriminator so a reader
  can ask \"is this line one of ours?\" without reaching into
  rehydrate-dump-record's `case`, which cannot be interrogated. A test pins
  the two together."
  #{:predicate :entity :episode :fact})

(def pre-alpha-dump-discriminator
  "Where a pre-alpha claimgraph stamped a record's kind, before the stamp
  moved to :record. Kept — as the name of a mistake, never as a reader — so a
  loader can tell a lossy claimgraph dump from a JSONL file that was never a
  claimgraph dump at all, and say so. Reading a kind out of here is what
  destroyed entity types in the first place; only recognition happens here."
  :type)

(defn pre-alpha-dump-record?
  "Does this unstamped record look like it came from a pre-alpha claimgraph —
  i.e. does it carry one of our kinds on the old discriminator? A file whose
  records carry neither stamp is somebody else's JSONL, and telling its owner
  to \"re-dump the source database\" sends them after a database that has
  nothing to do with the file."
  [rec]
  (contains? dump-kinds (->kw (get rec pre-alpha-dump-discriminator))))

(defn rehydrate-dump-record
  "Pure: one JSON-parsed dump record -> [kind wire-map], keywords and dates
  restored exactly as the store protocol documents them — the inverse of what
  JSON serialization flattened. The kind is read from :record and stripped;
  every other key is payload, including an entity's own :type, which comes
  back the keyword it went out as.

  Two shapes have no payload to hand back, and both return the line verbatim
  so the caller can name what it saw: [:unknown m] for a kind this build has
  no reader for, and [:unstamped m] for a record carrying no kind at all —
  the shape of a dump written before the discriminator moved off :type.
  Neither is this function's to resolve; guessing at either is how a load
  half-restores a graph and reports success."
  [rec]
  (let [t (->kw (get rec dump-discriminator))
        m (dissoc rec dump-discriminator)]
    (case t
      :predicate [t (-> m
                        (kw-fields [:id :category :object-kind :cardinality
                                    :inverse-of :status :replaced-by
                                    :default-epistemic :exclusion-group
                                    :value-exclusivity])
                        strip-nils)]
      :entity [t (-> m (kw-fields [:type]) strip-nils)]
      :episode [t (-> m
                      (kw-fields [:source-type])
                      (date-fields [:opened-at :closed-at])
                      strip-nils)]
      ;; Read off store/fact-fields rather than spelled out: a fact column
      ;; this list forgets does not vanish, it comes back the wrong TYPE — a
      ;; keyword as "core/prefers", a Date as an ISO string — and then
      ;; compares unequal to everything it is put beside, which no round-trip
      ;; count and no error message ever shows.
      :fact [t (-> m
                   (kw-fields (store/fact-keys-of :keyword))
                   (date-fields (store/fact-keys-of :instant))
                   (double-fields (store/fact-keys-of :double))
                   (embedded-entity-fields (store/fact-keys-of :entity))
                   strip-nils)]
      nil [:unstamped rec]
      [:unknown rec])))

;; ---------------------------------------------------------------------------
;; Source trust: what a source-type may overrule, and what it may claim
;; ---------------------------------------------------------------------------

(def source-trust
  "Trust rank per source-type (review §3.6): human decisions and mechanical
  derivation at the top, extraction in the middle, agent inference at the
  bottom. Drives two write-time defenses — a weaker source never silently
  supersedes a stronger one, and non-trusted sources resurrecting a dead
  value get flagged, not believed."
  {:decision-record 3
   :user-assertion 3
   :code 3
   :session-log 2
   :failure-report 2
   :agent-note 1
   :inferred 1})

(defn trust-rank [source-type] (get source-trust source-type 2))

(def source-ceilings
  "The most a fact from each source-type may ever claim — a fact the ingester
  re-derives 500 times must stay distinguishable from a human decision. Both
  ends of a fact's life are capped here: build-fact clamps a new fact to its
  source's ceiling, and reinforcement raises confidence toward that ceiling
  rather than toward 1.0.

  Birth has to be capped because nothing later brings a fact back down:
  reinforced-confidence is a high-water mark by design, so a fact minted above
  its ceiling (an `assert --source-type session-log --confidence 0.99`) sits
  above it permanently, and the trust model is silently defeated for that row."
  {:decision-record 1.0
   :code 0.95
   :user-assertion 0.9
   :session-log 0.7
   :agent-note 0.65
   :inferred 0.6})

(defn confidence-ceiling [source-type]
  (get source-ceilings source-type 0.9))

;; ---------------------------------------------------------------------------
;; Assertion decisions
;; ---------------------------------------------------------------------------

(def epistemic-classes #{:observation :commitment :preference})

(defn resolve-object-kind
  "Decide entity vs literal for the object. The :either heuristic needs to
  know whether a matching entity exists — the shell supplies that as a value
  so no store read happens in here."
  [pred explicit object-entity-exists?]
  (let [pk (:object-kind pred)]
    (when (and explicit (not= pk :either) (not= explicit pk))
      (fail (str "Predicate " (:id pred) " requires object-kind " (name pk))
            {:type :object-kind-mismatch :predicate (:id pred)
             :required pk :given explicit}))
    (case pk
      :entity :entity
      :literal :literal
      :either (or explicit (if object-entity-exists? :entity :literal)))))

(defn resolve-epistemic
  "Caller > predicate default > :observation."
  [pred explicit]
  (let [e (or explicit (:default-epistemic pred) :observation)]
    (if (epistemic-classes e)
      e
      (fail (str "Unknown epistemic class " e)
            {:type :invalid-epistemic :given e :allowed epistemic-classes}))))

(def epistemic-strength
  "How much a class binds, as an order: an observation supersedes silently on
  the next contradiction and fades by disuse, a commitment flags for a human
  and never fades. Only ever applied to the class a caller STATED — the
  resolved class is unusable for this, because resolve-epistemic fills in the
  registry default and every ingest pass would then read as an escalation of
  the facts it wrote last time (see decide-assert)."
  {:observation 1 :preference 2 :commitment 3})

(defn valid-interval-ok?
  "A valid-time interval is open (:t-invalid nil) or strictly positive."
  [{:keys [t-valid t-invalid]}]
  (or (nil? t-invalid) (< (ms t-valid) (ms t-invalid))))

(defn build-fact
  "Assemble the candidate fact. :id and :now are supplied by the shell so
  this stays deterministic. :t-valid/:t-invalid make valid time first-class
  on both ends — a closed past interval (\"true Jan through March\") is one
  fact. Inverted intervals fail here.

  Confidence is clamped to the source-type's ceiling, the default 0.8 included:
  a fact cannot be born above the trust its own source declares. The ingest
  tiers each clamp their own candidates already, so this is the direct-assert
  hole and their belt — and it has to be closed at birth, because
  reinforced-confidence never claws a base back down (see source-ceilings)."
  [{:keys [id now subject predicate object-kind object-ref object
           t-valid t-invalid confidence epistemic scope source-type episode]}]
  (let [t-valid (or t-valid now)
        source-type (or source-type :user-assertion)]
    (when-not (valid-interval-ok? {:t-valid t-valid :t-invalid t-invalid})
      (fail "Invalid interval: valid-until must be after valid-from"
            {:type :invalid-interval :t-valid t-valid :t-invalid t-invalid}))
    {:id id
     :subject subject
     :predicate predicate
     :object-kind object-kind
     :object-ref object-ref
     :object-lit (when (= :literal object-kind) (str object))
     :t-valid t-valid
     :t-invalid t-invalid
     :recorded-at now
     :last-reinforced-at now
     :confidence (min (confidence-ceiling source-type)
                      (double (or confidence 0.8)))
     :epistemic epistemic
     :scope (or scope default-scope)
     :source-type source-type
     :episode episode}))

(defn- same-object-pred [fact]
  (if (= :entity (:object-kind fact))
    #(= (get-in % [:object-ref :id]) (get-in fact [:object-ref :id]))
    #(= (:object-lit %) (:object-lit fact))))

(defn- escalation?
  "Did the caller state a class that binds harder than the fact they just
  re-asserted? An unstated class is nil and never escalates; neither does an
  equal or weaker one, nor a class epistemic-strength does not recognise on
  either side."
  [stated-epistemic existing]
  (let [stated (epistemic-strength stated-epistemic)
        held (epistemic-strength (:epistemic existing))]
    (boolean (and stated held (> stated held)))))

(defn- escalation-plan
  "Supersede the weaker row rather than mutate it: the point of the escalation
  is that the fact stops behaving like an observation, and the history has to
  show it was one until now. A backdated escalation takes the flag path for
  the same reason a backdated supersede does — closing the predecessor at a
  valid-from earlier than its own leaves it valid at no instant at all."
  [fact existing]
  (let [effective-at (:t-valid fact)]
    (if (> (ms (:t-valid existing)) (ms effective-at))
      {:action :flag :fact fact :reason :backdated-overlap
       :link [(:id existing)] :candidates [existing]}
      {:action :supersede :fact fact
       :invalidate [(:id existing)]
       :effective-at effective-at})))

(defn conflict-policy
  "Default policy from epistemic class: a commitment on either side of the
  conflict flags (never silently overwrite a human decision); observations
  and preferences supersede cleanly with history retained."
  [new-epistemic conflicting override]
  (or override
      (if (or (= new-epistemic :commitment)
              (some #(= :commitment (:epistemic %)) conflicting))
        :flag
        :supersede)))

(defn decide-assert
  "The assertion decision, as data. Given the candidate fact, its predicate
  registry row, the currently-valid facts for (subject, predicate), and any
  exclusion antagonists — same-subject, same-object facts on predicates
  sharing the asserted predicate's exclusion group, gathered by the shell —
  return an effect plan:

    {:action :reinforce :existing fact :fact fact}
    {:action :insert    :fact fact}
    {:action :supersede :fact fact :invalidate [fact-ids] :effective-at inst}
    {:action :flag      :fact fact :link [fact-ids] :candidates [facts]
                        (:reason :backdated-overlap when time-inverted)}

  A re-assertion of an existing fact is reinforcement, not a dead end: the
  world (or the user) just confirmed it, so its disuse clock resets and its
  confidence may rise toward the source ceiling.

  One kind of re-assertion is not reinforcement: when the caller STATES a
  class stronger than the standing fact holds (:stated-epistemic, strength
  order in epistemic-strength) they are escalating it — \"this isn't just an
  observation, we decided it\" — and reinforcement would carry the old class
  forward and report the escalation as recorded. That supersedes instead, so
  history reads observation-then-commitment. It keys off what the caller
  stated and nothing else: the resolved class carries the predicate registry's
  default, so comparing resolved values would make ingest-code supersede its
  own :core/defined-in facts on every pass, unboundedly and silently.

  Two trust defenses (review §3.6) sit in the decision, both overridable
  with an explicit :on-conflict:
  - outranked supersede: a lower-trust source never silently closes a
    higher-trust fact's interval — it flags instead (:reason :outranked).
  - revenants: a non-trusted source re-asserting a value this (subject,
    predicate) already lived through and invalidated (:revenants, gathered
    by the shell) is either stale or adversarial — it flags against the
    currently-live rivals (:reason :revenant) instead of quietly coexisting."
  [{:keys [fact pred existing exclusion revenants rivals on-conflict
           stated-epistemic]}]
  (let [same? (same-object-pred fact)
        duplicate (first (filter same? existing))
        conflicting (vec (concat (when (= :one (:cardinality pred))
                                   (remove same? existing))
                                 exclusion))
        new-trust (trust-rank (:source-type fact))]
    (cond
      duplicate
      (if (escalation? stated-epistemic duplicate)
        (escalation-plan fact duplicate)
        {:action :reinforce :existing duplicate :fact fact})

      (seq conflicting)
      (let [effective-at (:t-valid fact)
            inverted? (some #(> (ms (:t-valid %)) (ms effective-at)) conflicting)
            outranked? (some #(> (trust-rank (:source-type %)) new-trust)
                             conflicting)]
        (case (conflict-policy (:epistemic fact) conflicting on-conflict)
          ;; clean succession: the new truth begins exactly where the old one
          ;; ends, so predecessors close at the successor's valid-from. Equal
          ;; starts leave the predecessor an empty interval (immediately
          ;; replaced, never observably valid). A successor starting strictly
          ;; before a predecessor is a backdated overlap — a valid-time
          ;; contradiction, never silently inverted; it takes the flag path.
          :supersede (cond
                       inverted?
                       {:action :flag :fact fact :reason :backdated-overlap
                        :link (mapv :id conflicting) :candidates conflicting}

                       outranked?
                       {:action :flag :fact fact :reason :outranked
                        :link (mapv :id conflicting) :candidates conflicting}

                       :else
                       {:action :supersede :fact fact
                        :invalidate (mapv :id conflicting)
                        :effective-at effective-at})
          :flag {:action :flag :fact fact
                 :link (mapv :id conflicting) :candidates conflicting}
          :ignore {:action :insert :fact fact}))

      (and (< new-trust 3)
           (seq (filter (same-object-pred fact) revenants))
           (seq rivals)
           (not (#{:ignore :supersede} on-conflict)))
      {:action :flag :fact fact :reason :revenant
       :link (mapv :id rivals) :candidates (vec rivals)}

      :else
      {:action :insert :fact fact})))

;; ---------------------------------------------------------------------------
;; Predicate registration
;; ---------------------------------------------------------------------------

(defn prepare-registration
  "Normalize and validate a runtime predicate coinage. Only :x/* may be
  coined at runtime; :core/* is curated in the seed vocabulary."
  [pred]
  (let [id (->kw (:id pred))]
    (when-not (and id (namespace id))
      (fail "Predicate id must be namespaced, e.g. x/uses-pattern"
            {:type :invalid-predicate-id}))
    (when-not (preds/experimental? id)
      (fail "Only :x/* predicates may be registered at runtime; :core/* is curated"
            {:type :reserved-namespace :predicate id}))
    (merge (preds/auto-registration id)
           (->> (-> pred
                    (assoc :id id)
                    (update :object-kind ->kw)
                    (update :cardinality ->kw)
                    (update :default-epistemic ->kw))
                (filter (comp some? val))
                (into {})))))

;; ---------------------------------------------------------------------------
;; Confidence: reinforcement and disuse decay
;; ---------------------------------------------------------------------------

(defn reinforced-confidence
  "New base confidence after a re-assertion: never lowered by weaker
  evidence, raised by stronger evidence only up to the existing fact's
  source ceiling (a base already above its ceiling is preserved, not clawed
  back). Repetition alone never grows it — resetting the disuse clock is the
  reinforcement mechanism; base is a ceiling-capped high-water mark."
  [existing incoming-confidence]
  (max (double (:confidence existing))
       (min (confidence-ceiling (:source-type existing))
            (double (or incoming-confidence 0.0)))))

(def default-half-life-days 90)
(def confidence-floor 0.05)

(defn effective-confidence
  "Disuse decay as a view — never stored. The stored base halves per
  half-life since the fact was last reinforced (asserted or re-derived);
  commitments and decision-record facts never fade. Facts predating the
  reinforcement field anchor on recorded-at."
  ([fact now] (effective-confidence fact now nil))
  ([{:keys [confidence epistemic source-type recorded-at last-reinforced-at]}
    now {:keys [half-life-days floor]}]
   (let [confidence (double (or confidence 0.0))]
     (if (or (= :commitment epistemic)
             (= :decision-record source-type)
             (nil? recorded-at))
       confidence
       (let [anchor (max (ms recorded-at) (ms (or last-reinforced-at recorded-at)))
             days (double (or half-life-days default-half-life-days))
             periods (max 0.0 (/ (- (ms now) anchor) (* 86400000.0 days)))]
         (max (double (or floor confidence-floor))
              (* confidence (Math/pow 0.5 periods))))))))

;; ---------------------------------------------------------------------------
;; Read filters & traversal
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Hybrid retrieval: rank fusion (pure)
;; ---------------------------------------------------------------------------

(def ^:private query-stopwords
  #{"the" "and" "for" "with" "into" "from" "that" "this" "does" "are" "was"
    "our" "not" "use" "uses" "using" "what" "which" "when" "where" "why"
    "how" "did" "should" "against" "about" "run" "add" "get" "all" "any"})

(defn query-tokens
  "Query -> candidate retrieval tokens: whitespace-split, punctuation
  trimmed at the edges so dotted/hyphenated names (shoply.api, kuzu-db)
  survive whole; stopwords and sub-3-char fragments dropped — substring
  backends would match them everywhere and drown the signal. Short queries
  still work: callers always search the full query string as well."
  [query]
  (->> (str/split (str query) #"\s+")
       (map #(str/replace % #"^[^\p{Alnum}]+|[^\p{Alnum}]+$" ""))
       (remove str/blank?)
       (remove #(< (count %) 3))
       (remove (comp query-stopwords str/lower-case))
       distinct
       vec))

(def ^:private rrf-k
  "Reciprocal-rank-fusion damping: standard 60 keeps single-list winners
  from drowning multi-list consensus."
  60)

(defn fuse-retrieval
  "Pure hybrid ranking: ranked candidate lists (each [fact ...], best
  first, from different retrieval routes) -> one ranked fact list. Score is
  reciprocal-rank fusion across routes (weighted per route) times the
  fact's effective confidence at :now — consensus and freshness both count,
  and invalidated facts never surface.

  routes: [{:weight w :facts [fact ...]} ...]"
  [routes now]
  (let [rrf (reduce (fn [acc {:keys [weight facts]}]
                      (reduce (fn [a [rank f]]
                                (update a (:id f)
                                        (fnil (fn [[score fact]]
                                                [(+ score (/ (or weight 1.0)
                                                             (+ rrf-k rank 1)))
                                                 fact])
                                              [0.0 f])))
                              acc
                              (map-indexed vector facts)))
                    {} routes)]
    (->> (vals rrf)
         (keep (fn [[score f]]
                 (when (fact-valid-at? f now)
                   (let [ec (effective-confidence f now)]
                     (assoc f
                            :effective-confidence ec
                            :retrieval-score (* score ec))))))
         (sort-by (comp - :retrieval-score))
         vec)))

(defn walk-score
  "Pure guidance for the evidence-guided walk: how much a fact looks like
  the query (token overlap over its rendered text) times how alive it is
  (effective confidence). +1 keeps zero-overlap edges walkable at low
  priority — connectivity still counts, relevance counts more."
  [f query-tokens now]
  (let [text (str/lower-case
              (str (get-in f [:subject :name]) " "
                   (some-> (:predicate f) name) " "
                   (or (get-in f [:object-ref :name]) (:object-lit f))))
        overlap (count (filter #(str/includes? text %) query-tokens))]
    (* (inc overlap) (effective-confidence f now))))

(defn walk-step
  "Pure: pick this round's expansions. candidates are unseen valid facts on
  the frontier; returns the top-beam of them by walk-score (ties broken by
  id, so the walk is deterministic across stores)."
  [candidates query-tokens now beam]
  (->> candidates
       (map #(assoc % :walk-score (walk-score % query-tokens now)))
       (sort-by (fn [f] [(- (:walk-score f)) (str (:id f))]))
       (take beam)
       vec))

(defn fact-filter
  "Predicate over facts for reads: validity at :at, plus optional
  confidence/scope/predicate filters. :min-confidence compares against
  EFFECTIVE (disuse-decayed) confidence, evaluated at :at."
  [{:keys [at include-invalidated min-confidence scope predicate]}]
  (fn [f]
    (and (or include-invalidated (fact-valid-at? f at))
         (or (nil? min-confidence)
             (>= (effective-confidence f at) (double min-confidence)))
         (or (nil? scope) (= scope (:scope f)))
         (or (nil? predicate) (= predicate (:predicate f))))))

(defn bfs-step
  "One BFS level, purely: fold this level's facts into the accumulated
  {:nodes :edges} and compute the next frontier. Only entity-kind objects
  are traversable; inverse direction comes from the shell fetching :both."
  [{:keys [nodes edges]} facts keep? next-depth]
  (let [fresh (->> facts (filter keep?) (remove (comp edges :id)))
        neighbors (->> fresh
                       (mapcat (juxt :subject :object-ref))
                       (remove nil?)
                       (remove (comp nodes :id))
                       (map #(assoc % :depth next-depth)))]
    {:nodes (into nodes (map (juxt :id identity)) neighbors)
     :edges (into edges (map (juxt :id identity)) fresh)
     :frontier (set (map :id neighbors))}))

(defn neighborhood-result [root {:keys [nodes edges]} depth]
  {:root root
   :depth depth
   :entities (vec (sort-by :depth (vals nodes)))
   :facts (vec (vals edges))})

;; ---------------------------------------------------------------------------
;; Entity resolution
;; ---------------------------------------------------------------------------

(defn normalize-entity-name
  "Equivalence class for near-match entity lookup: lowercase, separators
  stripped. \"AuthService\", \"auth-service\" and \"auth_service\" all
  normalize to \"authservice\"."
  [s]
  (-> (str s) str/lower-case (str/replace #"[\s\-_./]+" "")))

;; ---------------------------------------------------------------------------
;; Admission control (pure; review §3.3, A-MAC/SAGE)
;; ---------------------------------------------------------------------------

(def admission-floor-confidence 0.3)
(def max-subject-chars 80)
(def max-literal-chars 250)

(defn admission-signals
  "Rule-based admission signals for one extracted candidate — structured and
  interpretable, no LLM (the optional utility signal stays unspent). ctx:
  {:known-norms #{normalized entity names} :known-preds #{predicate ids}}."
  [{:keys [subject predicate object confidence epistemic class]} ctx]
  (let [pred-kw (->kw (when predicate (str/replace (str predicate) "_" "-")))
        pred-known? (or (contains? (:known-preds ctx)
                                   (keyword "core" (name (or pred-kw :none))))
                        (contains? (:known-preds ctx) pred-kw))]
    {:above-floor (>= (double (or confidence 0.5)) admission-floor-confidence)
     :subject-shaped (<= (count (str subject)) max-subject-chars)
     :object-sane (<= (count (str object)) max-literal-chars)
     :predicate-known pred-known?
     :subject-known (contains? (:known-norms ctx)
                               (normalize-entity-name (str subject)))
     :class-weight (case (->kw (or epistemic class))
                     :commitment 1.0
                     :preference 0.8
                     0.5)}))

(defn admit?
  "The hard rules: junk-shaped candidates never reach the graph. Soft
  signals (unknown subject, coined predicate, class weight) inform the
  score, not the verdict — extraction is already source-capped and the raw
  tier keeps the log, so admission only screens shape and floor."
  [{:keys [above-floor subject-shaped object-sane]}]
  (boolean (and above-floor subject-shaped object-sane)))

(defn admission-score
  "One number for observability and future thresholds: hard rules zero it,
  soft signals scale it."
  [{:keys [above-floor subject-shaped object-sane predicate-known
           subject-known class-weight] :as _signals}]
  (if-not (and above-floor subject-shaped object-sane)
    0.0
    (* class-weight
       (+ 0.6
          (if predicate-known 0.25 0.0)
          (if subject-known 0.15 0.0)))))

(defn admission-ctx
  "Entities + predicate rows -> the ctx admission-signals reads."
  [entities predicates]
  {:known-norms (into #{}
                      (comp (mapcat (fn [e] (cons (:name e) (:aliases e))))
                            (remove nil?)
                            (map normalize-entity-name))
                      entities)
   :known-preds (into #{} (map :id) predicates)})

(defn screen-candidates
  "Pure: split prepared candidates into the admitted and the inadmissible,
  each inadmissible one carrying its signals — gate the graph, keep the
  log."
  [facts ctx]
  (let [judged (map (fn [f]
                      (let [sig (admission-signals f ctx)]
                        (assoc f
                               :admission-signals sig
                               :admission-score (admission-score sig)
                               :admit (admit? sig))))
                    facts)]
    {:admitted (mapv #(dissoc % :admission-signals :admit) (filter :admit judged))
     :inadmissible (mapv #(dissoc % :admit) (remove :admit judged))}))

(defn same-object-loosely?
  "Do two facts point at the same thing, across the entity/literal divide?
  decided-against \"GraphQL\" (literal) and prefers GraphQL (entity) are the
  same object in different clothes — compare normalized name-or-literal.
  Over-matching is safe: it produces a flag the judge can rule compatible."
  [a b]
  (let [obj-str (fn [f] (or (get-in f [:object-ref :name]) (:object-lit f)))
        sa (obj-str a) sb (obj-str b)]
    (boolean (and sa sb (= (normalize-entity-name sa) (normalize-entity-name sb))))))

(defn pick-entity-match
  "Resolution order over candidate entities: exact name, exact alias, then a
  UNIQUE normalized match guarded by type compatibility (a namespace must not
  silently match a class). Two or more normalized matches is a detected
  collision, not a license to guess — it returns {:via :ambiguous} with the
  candidates so the caller can surface them. Zero candidates returns nil:
  genuinely new, creating is correct."
  [{:keys [name norm type]} candidates]
  (let [type-ok? (fn [e] (or (nil? type) (nil? (:type e)) (= type (:type e))))
        norm-of (fn [e] (cons (normalize-entity-name (:name e))
                              (map normalize-entity-name (:aliases e))))
        exact (first (filter #(= name (:name %)) candidates))
        alias-hit (first (filter #(some #{name} (:aliases %)) candidates))
        norm-hits (filterv #(and (type-ok? %) (some #{norm} (norm-of %)))
                           candidates)]
    (cond
      exact {:entity exact :via :exact}
      alias-hit {:entity alias-hit :via :alias}
      (= 1 (count norm-hits)) {:entity (first norm-hits) :via :normalized}
      (seq norm-hits) {:via :ambiguous :candidates norm-hits}
      :else nil)))

(defn entity-duplicate-clusters
  "Entities sharing a normalized name within a scope — merge candidates for
  human review."
  [entities]
  (->> entities
       (group-by (fn [e] [(:scope e) (normalize-entity-name (:name e))]))
       (keep (fn [[[scope norm] es]]
               (when (> (count es) 1)
                 {:normalized norm
                  :scope scope
                  :entities (mapv #(select-keys % [:id :name :type]) es)})))
       vec))

(defn collapse-duplicates
  "After a merge repoints facts, the same claim can exist twice. Plan the
  collapse: among currently-valid facts identical in subject, predicate,
  object, scope and epistemic class, keep the earliest-recorded and
  invalidate the rest, as [{:id retired :survivor id}].

  The survivor rides along because the caller has to record it — a retired
  duplicate whose invalidation names no counterpart is a row that looks
  deleted for no reason a year later — and only this grouping knows which of
  the twins it was."
  [facts at]
  (->> facts
       (filter #(fact-valid-at? % at))
       (group-by (fn [f] [(get-in f [:subject :id])
                          (:predicate f)
                          (:object-kind f)
                          (or (get-in f [:object-ref :id]) (:object-lit f))
                          (:scope f)
                          (:epistemic f)]))
       vals
       (mapcat (fn [group]
                 (when (> (count group) 1)
                   ;; `survivor`, not `keep`: the threading chain right above
                   ;; this one calls clojure.core/keep, and a local shadowing it
                   ;; reads as correct right until someone adds one more step
                   ;; here. It also says what the binding is.
                   (let [[survivor & retire] (sort-by (comp ms :recorded-at) group)]
                     (map (fn [f] {:id (:id f) :survivor (:id survivor)}) retire)))))
       vec))

;; ---------------------------------------------------------------------------
;; Conflicts
;; ---------------------------------------------------------------------------

(defn- unordered-pairs [xs]
  (let [v (vec xs)]
    (for [i (range (count v))
          j (range (inc i) (count v))]
      [(v i) (v j)])))

(defn- already-linked? [a b]
  (boolean (or (some #{(:id b)} (:conflicts a))
               (some #{(:id a)} (:conflicts b)))))

(defn- newer-first [a b]
  (let [t #(or (some-> ^java.util.Date (:recorded-at %) .getTime) 0)]
    (if (>= (t a) (t b)) [a b] [b a])))

(defn conflict-candidates
  "Pure candidate generation for the deferred judge sweep: over each
  subject's currently-valid facts and the predicate registry, the pairs
  worth an LLM verdict —

    :exclusive-values  multiple values of a predicate whose registry row says
                       :value-exclusivity :exclusive (two prefers on one
                       subject tend to be alternatives, not accumulation)
    :cross-predicate   different predicates, loosely the same object, at
                       least one side :decision-category (depends-on X while
                       decided-against X stands)

  O(facts-per-subject²), never O(graph²). Pairs already linked as conflicts
  are skipped; each pair is returned newer-first as {:fact :candidate :reason}."
  [facts preds-by-id at]
  (->> (filter #(fact-valid-at? % at) facts)
       (group-by (comp :id :subject))
       (mapcat
        (fn [[_ fs]]
          (concat
           (for [[p group] (group-by :predicate fs)
                 :when (= :exclusive (:value-exclusivity (preds-by-id p)))
                 pair (unordered-pairs group)]
             {:pair pair :reason :exclusive-values})
           (for [pair (unordered-pairs fs)
                 :let [[a b] pair]
                 :when (and (not= (:predicate a) (:predicate b))
                            (or (= :decision (:category (preds-by-id (:predicate a))))
                                (= :decision (:category (preds-by-id (:predicate b)))))
                            (same-object-loosely? a b))]
             {:pair pair :reason :cross-predicate}))))
       (remove (fn [{[a b] :pair}] (already-linked? a b)))
       (reduce (fn [acc {:keys [pair reason]}]
                 (let [k (set (map :id pair))]
                   (if (acc k) acc (assoc acc k {:pair pair :reason reason}))))
               {})
       vals
       (mapv (fn [{:keys [pair reason]}]
               (let [[n o] (apply newer-first pair)]
                 {:fact n :candidate o :reason reason})))))

(defn open-conflicts
  "Conflict pairs still awaiting resolution: a flagged fact and the candidate
  it conflicts with, where both are valid at `at`. (Conflict links live on
  the newer fact, so :fact is always the newer side.)"
  [facts at]
  (let [by-id (into {} (map (juxt :id identity)) facts)]
    (vec (for [f facts
               :when (fact-valid-at? f at)
               cid (:conflicts f)
               :let [c (by-id cid)]
               :when (and c (fact-valid-at? c at))]
           {:fact f :candidate c}))))

