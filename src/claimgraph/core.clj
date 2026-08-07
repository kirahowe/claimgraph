(ns claimgraph.core
  "The imperative shell around the functional core (claimgraph.logic). Each
  operation follows the same shape: gather reads from the Store, hand plain
  values to logic for a pure decision, execute the resulting effect plan.
  Mutation is concentrated here and in the store implementations; everything
  decision-shaped lives in claimgraph.logic and claimgraph.predicates."
  (:require [clojure.string :as str]
            [claimgraph.logic :as logic]
            [claimgraph.predicates :as preds]
            [claimgraph.store :as store]
            [claimgraph.version :as version]))

(defn now ^java.util.Date [] (java.util.Date.))

(defn- new-id [prefix] (str prefix "-" (random-uuid)))

;; ---------------------------------------------------------------------------
;; Predicate registry
;; ---------------------------------------------------------------------------

(defn seed!
  "Install or refresh the core vocabulary. A true upsert: existing rows pick
  up new registry fields on re-seed (run `claim init` after upgrading)."
  [s]
  (doseq [p preds/seed]
    (store/-register-predicate s p))
  {:status :seeded :predicates (count (store/-list-predicates s {}))})

(defn resolve-predicate-id
  "Normalize a predicate reference: underscores become hyphens (LLMs and
  shells both emit them) and bare names resolve to :core/* when such a core
  predicate exists."
  [s pred]
  (let [k (logic/->kw (when pred (str/replace (str pred) "_" "-")))]
    (when-not k (logic/fail "Missing predicate" {:type :missing-predicate}))
    (if (namespace k)
      k
      (let [core-k (keyword "core" (name k))]
        (if (store/-get-predicate s core-k) core-k k)))))

(defn- ensure-predicate!
  "Read the registry row, let the pure check decide, execute its verdict:
  use the row, register the first-use :x/* coinage, or throw (enriching
  unknown-predicate errors with :did-you-mean)."
  [s pred-id]
  (let [{:keys [ok register error]} (preds/check pred-id (store/-get-predicate s pred-id))]
    (cond
      ok ok
      register (store/-register-predicate s register)
      :else (logic/fail (:message error)
                        (cond-> (dissoc error :message)
                          (= :unknown-predicate (:type error))
                          (assoc :did-you-mean
                                 (preds/did-you-mean pred-id
                                                     (map :id (store/-list-predicates s {})))))))))

(defn list-predicates [s opts]
  (let [ps (store/-list-predicates s {:category (logic/->kw (:category opts))
                                      :status (logic/->kw (:status opts))})]
    (if (:usage opts)
      (let [counts (store/-predicate-usage s)]
        (mapv #(assoc % :usage (get counts (:id %) 0)) ps))
      (vec ps))))

(defn register-predicate [s pred]
  (store/-register-predicate s (logic/prepare-registration pred)))

(defn promote-predicate
  "Graduate a staging predicate: register the stable :core/* twin (fields
  carried from the staging row, overridable), rewrite every fact onto it (a
  rename of the term, not a belief change — validity and transaction time
  untouched), and deprecate the staging term with a :replaced-by pointer, so
  further writes to it fail with the forwarding address.
  opts: :from (x/*) :to (core/*) [:label :definition :category :object-kind
        :object-shape :cardinality :maps-to :default-epistemic]"
  [s {:keys [from to] :as opts}]
  (let [from-k (logic/->kw from)
        to-k (logic/->kw to)]
    (when-not (and from-k (= "x" (namespace from-k)))
      (logic/fail "Only x/* staging predicates promote"
                  {:type :not-a-staging-predicate :from from}))
    (when-not (and to-k (= "core" (namespace to-k)))
      (logic/fail "Promote to a core/* id"
                  {:type :not-a-core-predicate :to to}))
    (let [staging (or (store/-get-predicate s from-k)
                      (logic/fail (str "Predicate not found: " from-k)
                                  {:type :unknown-predicate :predicate from-k}))]
      (when (store/-get-predicate s to-k)
        (logic/fail (str "Predicate already exists: " to-k)
                    {:type :predicate-exists :predicate to-k
                     :hint "promotion mints a new core term; merging into an existing one is a curation decision, not a promotion"}))
      (store/-register-predicate
       s (merge (dissoc staging :status :replaced-by)
                {:id to-k :status :stable}
                (into {} (filter (comp some? val))
                      (select-keys opts [:label :definition :category :object-kind
                                         :object-shape :cardinality :maps-to
                                         :default-epistemic]))))
      (let [n (store/-repoint-predicate s from-k to-k)]
        (store/-register-predicate s (assoc staging :status :deprecated :replaced-by to-k))
        {:status :promoted :from from-k :to to-k :facts-rewritten n}))))

;; ---------------------------------------------------------------------------
;; Entities
;; ---------------------------------------------------------------------------

(declare assert-fact)

(defn resolve-entity
  "Resolve a name to an entity: exact name, exact alias, then a unique
  case/separator-insensitive match (type-guarded). Returns
  {:entity e :via :exact|:alias|:normalized},
  {:via :ambiguous :candidates [...]} when ≥2 normalized matches collide,
  or nil when nothing matches at all."
  [s {:keys [name scope type]}]
  (let [scope (or scope logic/default-scope)]
    (logic/pick-entity-match {:name name
                              :norm (logic/normalize-entity-name name)
                              :type (logic/->kw type)}
                             (store/-find-entities s name scope))))

(defn- fail-ambiguous [name scope candidates]
  (logic/fail (str "Ambiguous entity: " name " matches "
                   (count candidates) " entities — refusing to guess")
              {:type :ambiguous-entity
               :entity name
               :scope scope
               :candidates (mapv #(select-keys % [:id :name :type :scope :aliases])
                                 candidates)
               :hint "use the exact name or an alias; if these are duplicates, `entity merge` them"}))

(defn ensure-entity
  "Resolve or create. A resolution through normalization self-heals: the
  queried name is recorded as an alias so the next lookup is exact. A
  detected collision (≥2 normalized matches) throws with the candidates
  attached — minting a third entity is the one outcome that must never
  happen silently."
  [s {:keys [name type scope]}]
  (when (str/blank? (str name))
    (logic/fail "Entity name required" {:type :missing-entity-name}))
  (let [name (str/trim (str name))
        scope (or scope logic/default-scope)
        type (logic/->kw type)]
    (if-let [{:keys [entity via candidates]} (resolve-entity s {:name name :scope scope :type type})]
      (if (= :ambiguous via)
        (fail-ambiguous name scope candidates)
        (do
          (when (= :normalized via)
            (store/-update-entity s (:id entity) {:add-aliases [name]}))
          (when (and type (nil? (:type entity)))
            (store/-update-entity s (:id entity) {:type type}))
          (cond-> entity
            (= :normalized via) (update :aliases (fnil conj []) name)
            (and type (nil? (:type entity))) (assoc :type type))))
      (store/-ensure-entity s {:name name :type type :scope scope}))))

(defn require-entity [s name scope]
  (let [scope (or scope logic/default-scope)
        {:keys [entity via candidates]} (resolve-entity s {:name name :scope scope})]
    (cond
      entity entity
      (= :ambiguous via) (fail-ambiguous name scope candidates)
      :else (logic/fail (str "Entity not found: " name)
                        {:type :entity-not-found :entity name :scope scope}))))

(defn rename-entity
  "Rename in place: same entity, same facts, same history; the old name is
  kept as an alias, and the NEW name is scrubbed from the alias list
  (spec/entities.allium, decided 2026-07-26) — renaming back to a previous
  name must not leave an entity aliased to itself."
  [s {:keys [from to scope]}]
  (when (str/blank? (str to))
    (logic/fail "New name required" {:type :missing-entity-name}))
  (let [scope (or scope logic/default-scope)
        e (require-entity s from scope)
        to (str/trim (str to))]
    (when-let [clash (store/-get-entity s to scope)]
      (when (not= (:id clash) (:id e))
        (logic/fail (str "Entity already exists: " to)
                    {:type :entity-exists :entity to
                     :hint "use `entity merge` to combine them"})))
    ;; Unconditional scrub: adds land before removes, so a no-op rename
    ;; (X -> X) adds and immediately drops the self-alias instead of
    ;; minting one — and renaming an entity to its own current name becomes
    ;; the repair verb for legacy self-aliased rows.
    (store/-update-entity s (:id e) {:name to
                                     :add-aliases [(:name e)]
                                     :remove-aliases [to]})
    {:status :renamed
     :entity (-> e
                 (assoc :name to)
                 (update :aliases #(vec (remove #{to}
                                                (distinct (conj (vec %) (:name e)))))))}))

(defn alias-entity
  "Record an additional name for an entity. Refused when another entity
  already owns it — as its exact name OR among its aliases (spec/
  entities.allium, decided 2026-07-26): a shared alias was the one name
  collision resolution could not refuse, and the silent pick it forced is
  exactly what the ambiguity defense exists to prevent."
  [s {:keys [name alias scope]}]
  (when (str/blank? (str alias))
    (logic/fail "Alias required" {:type :missing-alias}))
  (let [scope (or scope logic/default-scope)
        e (require-entity s name scope)
        alias (str/trim (str alias))]
    (when-let [clash (store/-get-entity s alias scope)]
      (when (not= (:id clash) (:id e))
        (logic/fail (str "Another entity is named " alias)
                    {:type :entity-exists :entity alias
                     :hint "use `entity merge` to combine them"})))
    (let [holders (->> (store/-find-entities s alias scope)
                       (filter #(some #{alias} (:aliases %)))
                       (remove #(= (:id %) (:id e))))]
      (when (seq holders)
        (logic/fail (str "Another entity already holds the alias " alias)
                    {:type :alias-taken :alias alias
                     :holders (mapv #(select-keys % [:id :name :type :scope]) holders)
                     :hint "if these are the same thing, `entity merge` them"})))
    (store/-update-entity s (:id e) {:add-aliases [alias]})
    {:status :aliased
     :entity (update e :aliases #(vec (distinct (conj (vec %) alias))))}))

(defn merge-entities
  "Two entities that turn out to be the same thing: repoint every fact from
  one onto the other, carry the merged-away names as aliases, drop the empty
  husk, and collapse the exact duplicates the repointing exposed (non-lossy —
  they are invalidated, not deleted)."
  [s {:keys [from into scope]}]
  (let [scope (or scope logic/default-scope)
        src (require-entity s from scope)
        dst (require-entity s into scope)]
    (when (= (:id src) (:id dst))
      (logic/fail "Cannot merge an entity into itself"
                  {:type :merge-self :entity (:name src)}))
    (let [t-now (now)
          repointed (store/-repoint-facts s (:id src) (:id dst))
          aliases (vec (distinct (remove #{(:name dst)}
                                         (cons (:name src) (:aliases src)))))]
      (when (seq aliases)
        (store/-update-entity s (:id dst) {:add-aliases aliases}))
      (store/-delete-entity s (:id src))
      (let [dups (logic/collapse-duplicates
                  (store/-get-facts s (:id dst) {:direction :both})
                  t-now)]
        (doseq [{:keys [id survivor]} dups]
          (store/-invalidate s id t-now
                             {:kind :merge-duplicate
                              :successor survivor
                              :reason (str "duplicate after merging " (:name src)
                                           " into " (:name dst))}))
        {:status :merged
         :from (:name src)
         :into (:name dst)
         :facts-repointed repointed
         :duplicates-invalidated (count dups)
         :aliases-added aliases}))))

(defn split-entity
  "Record that an entity split into successors: derived-from lineage edges
  are asserted for each. Facts on the source stay put — which successor
  inherits a preference or a decision is a judgment call, so they are left
  for review rather than redistributed mechanically."
  [s {:keys [from into scope]}]
  (let [scope (or scope logic/default-scope)
        src (require-entity s from scope)
        names (->> (if (sequential? into) into (str/split (str into) #","))
                   (map str/trim)
                   (remove str/blank?)
                   vec)]
    (when (empty? names)
      (logic/fail "Successor names required, e.g. --into \"UserReadService,UserWriteService\""
                  {:type :missing-successors}))
    {:status :split
     :from (:name src)
     :into names
     :lineage (mapv (fn [n]
                      (get-in (assert-fact s {:subject n
                                              :subject-scope scope
                                              :predicate :core/derived-from
                                              :object (:name src)
                                              :object-scope scope
                                              :object-kind :entity})
                              [:fact :id]))
                    names)
     :note "facts on the source were left in place for review"}))

(defn entity-duplicates
  "Likely-duplicate entity clusters (shared normalized name within a scope) —
  candidates for `entity merge`."
  [s]
  (let [clusters (logic/entity-duplicate-clusters (store/-list-entities s {}))]
    {:clusters (count clusters)
     :candidates clusters}))

;; ---------------------------------------------------------------------------
;; assert-fact: gather -> decide (pure) -> execute
;; ---------------------------------------------------------------------------

(defn- execute-assert! [s {:keys [action fact existing invalidate link candidates
                                  effective-at reason]}]
  (case action
    :reinforce (let [source (logic/resourced-type existing (:source-type fact))
                     boosted (logic/reinforced-confidence
                              (assoc existing :source-type source) (:confidence fact))
                     at (:recorded-at fact)]
                 ;; Re-sourcing (spec/claims.allium, decided 2026-07-26): a
                 ;; higher-ceiling incoming source upgrades the row before the
                 ;; base moves, so the raised ceiling applies to this very
                 ;; reinforcement. Both assertions' episodes stay in history.
                 (store/-reinforce s (:id existing)
                                   (cond-> {:at at :confidence boosted}
                                     (not= source (:source-type existing))
                                     (assoc :source-type source)))
                 {:status :reinforced
                  :fact (assoc existing :confidence boosted :last-reinforced-at at
                               :source-type source)})
    :insert {:status :created :fact (store/-insert-fact s fact)}
    :supersede (do (doseq [id invalidate]
                     (store/-invalidate s id effective-at
                                        {:kind :superseded
                                         :successor (:id fact)
                                         :reason (str "superseded by " (:id fact))}))
                   {:status :superseded
                    :fact (store/-insert-fact s fact)
                    :superseded invalidate})
    :flag (let [inserted (store/-insert-fact s fact)]
            (store/-link-conflicts s (:id inserted) link)
            (cond-> {:status :flagged
                     :fact (assoc inserted :conflicts link)
                     :candidates candidates}
              reason (assoc :reason reason)))))

(defn assert-fact
  "Insert a fact with full validation and conflict resolution.

  opts: :subject :subject-type :subject-scope
        :predicate :object :object-type :object-scope :object-kind
        :epistemic :scope :confidence :source-type :episode
        :on-conflict (:supersede | :flag | :ignore)
        :t-valid :t-invalid (valid time, both ends; defaults: now, open)

  Returns {:status :created|:reinforced|:superseded|:flagged
           :fact <fact>
           :superseded [ids] / :candidates [conflicting facts]}

  Re-asserting an existing fact reinforces it: the disuse clock resets and
  base confidence rises toward the source ceiling (never above it, never
  down). Re-asserting it with an explicitly stronger :epistemic supersedes
  instead — escalating an observation to a commitment changes how the fact
  behaves, and history keeps both rows rather than editing the class in place.

  :confidence is capped at the :source-type's ceiling; a low-trust source
  cannot mint a fact above the trust it declares."
  [s {:keys [subject subject-type subject-scope predicate
             object object-type object-scope object-kind
             epistemic scope confidence source-type episode on-conflict
             t-valid t-invalid]}]
  (when (str/blank? (str object))
    (logic/fail "Object required" {:type :missing-object}))
  (let [pred-id (resolve-predicate-id s predicate)
        pred (ensure-predicate! s pred-id)
        ;; the caller's own word on the class, kept apart from the resolved
        ;; value all the way to the decision: only a STATED class escalates a
        ;; re-assertion, and the registry default resolve-epistemic supplies
        ;; would make every ingest pass look like one
        stated-epistemic (logic/->kw epistemic)
        obj-scope (or object-scope logic/default-scope)
        kind (logic/resolve-object-kind
              pred (logic/->kw object-kind)
              (some? (resolve-entity s {:name (str object) :scope obj-scope})))
        subj (ensure-entity s {:name subject :type subject-type :scope subject-scope})
        obj-ent (when (= :entity kind)
                  (ensure-entity s {:name object :type object-type :scope obj-scope}))
        t-now (now)
        fact (logic/build-fact {:id (new-id "f")
                                :now t-now
                                :subject subj
                                :predicate pred-id
                                :object-kind kind
                                :object-ref obj-ent
                                :object object
                                :t-valid t-valid
                                :t-invalid t-invalid
                                :confidence confidence
                                :epistemic (logic/resolve-epistemic pred stated-epistemic)
                                :scope scope
                                :source-type (logic/->kw source-type)
                                :episode episode})
        group-mates (when-let [g (:exclusion-group pred)]
                      (->> (store/-list-predicates s {})
                           (filter #(= g (:exclusion-group %)))
                           (map :id)
                           (remove #{pred-id})
                           vec))
        raw (store/-get-facts s (:id subj)
                              {:direction :out
                               :predicate (if (seq group-mates)
                                            (into [pred-id] group-mates)
                                            pred-id)})
        fetched (filterv #(logic/fact-valid-at? % t-now) raw)
        same-pred-valid (filterv #(= pred-id (:predicate %)) fetched)]
    (execute-assert! s
                     (logic/decide-assert {:fact fact
                                           :pred pred
                                           :existing same-pred-valid
                                           :exclusion (filterv #(and (not= pred-id (:predicate %))
                                                                     (logic/same-object-loosely? fact %))
                                                               fetched)
                                           ;; the trust defenses' inputs: this
                                           ;; (subject, predicate)'s dead facts,
                                           ;; and the live different-valued ones
                                           :revenants (filterv #(and (= pred-id (:predicate %))
                                                                     (some? (:t-invalid %))
                                                                     (not (logic/fact-valid-at? % t-now)))
                                                               raw)
                                           :rivals same-pred-valid
                                           :stated-epistemic stated-epistemic
                                           :on-conflict (logic/->kw on-conflict)}))))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn get-facts
  "Currently-valid (or as-of a timestamp) facts about an entity.
  opts: :entity :entity-scope :direction (:out default | :in | :both)
        :predicate :scope :as-of :include-invalidated :min-confidence"
  [s {:keys [entity entity-scope direction predicate] :as opts}]
  (let [e (require-entity s entity entity-scope)
        pred-id (when predicate (resolve-predicate-id s predicate))
        at (or (:as-of opts) (now))
        keep? (logic/fact-filter {:at at
                                  :include-invalidated (:include-invalidated opts)
                                  :min-confidence (:min-confidence opts)
                                  :scope (:scope opts)})]
    {:entity e
     :facts (->> (store/-get-facts s (:id e) {:direction (or (logic/->kw direction) :out)
                                              :predicate pred-id})
                 (filter keep?)
                 (sort-by (comp logic/ms :t-valid))
                 (mapv #(assoc % :effective-confidence
                               (logic/effective-confidence % at))))}))

(defn get-neighborhood
  "BFS expansion to :depth, following entity-kind objects in both directions
  (computed inverses, not stored twins). The shell fetches each level's whole
  frontier in one batched read; the fold into nodes/edges/frontier is pure
  (logic/bfs-step).
  opts: :entity :entity-scope :depth :as-of :scope :min-confidence :predicate"
  [s {:keys [entity entity-scope depth predicate] :as opts}]
  (let [root (require-entity s entity entity-scope)
        at (or (:as-of opts) (now))
        keep? (logic/fact-filter {:at at
                                  :min-confidence (:min-confidence opts)
                                  :scope (:scope opts)
                                  :predicate (when predicate (resolve-predicate-id s predicate))})
        max-depth (long (or depth 1))]
    (loop [state {:nodes {(:id root) (assoc root :depth 0)}
                  :edges {}
                  :frontier #{(:id root)}}
           d 0]
      (if (or (>= d max-depth) (empty? (:frontier state)))
        (update (logic/neighborhood-result root state d) :facts
                (fn [fs] (mapv #(assoc % :effective-confidence
                                       (logic/effective-confidence % at))
                               fs)))
        (let [facts (store/-get-facts-for s (:frontier state) {:direction :both})]
          (recur (logic/bfs-step state facts keep? (inc d)) (inc d)))))))

(defn- walk-nodes
  "The walk's entity list, each node carrying its hop distance from the root —
  the shape the BFS neighborhood reports, so one reader parses both.

  The distance is the round the walk DISCOVERED the node in, carried out of the
  loop rather than recomputed from the facts that came back. It cannot be
  recomputed from them: a round collects up to `beam` facts and only the
  budget's worth of the best-scoring survive, and a round-2 fact routinely
  outscores the round-1 fact that reached its endpoint — so the LINKING fact is
  exactly what truncation drops, leaving a retained node with no path from the
  root through any retained fact, and a reported depth of null. Only nodes the
  returned facts mention are listed; a node whose every fact was truncated is
  not part of the subgraph being described."
  [root facts depths]
  (->> facts
       (mapcat (juxt :subject :object-ref))
       (remove nil?)
       (map (juxt :id identity))
       (into {(:id root) root})
       (map (fn [[id n]] (assoc n :depth (get depths id))))
       (sort-by (fn [n] [(or (:depth n) Integer/MAX_VALUE) (str (:id n))]))
       vec))

(defn guided-walk
  "The evidence-guided replacement for fixed-depth BFS (review §3.2,
  TierMem/MRAgent): expand from an entity following the edges that look
  like the query, not every edge to depth k. A beam of the best-scoring
  unseen facts is taken each round (logic/walk-step — token overlap ×
  effective confidence, deterministic ties), their far endpoints become the
  next frontier, until the fact budget or the graph runs out.

  Answers with :entities (nodes and their discovery depth, see walk-nodes)
  beside the walk-scored :facts. One map does double duty in the loop: what
  has been visited IS what has a recorded depth, and a walk that tracked the
  two separately could let them disagree about a node.
  opts: :entity :entity-scope :query :budget (default 25) :beam (default 8)"
  [s {:keys [entity entity-scope query budget beam]}]
  (let [root (require-entity s entity entity-scope)
        t (now)
        toks (mapv str/lower-case (logic/query-tokens query))
        budget (long (or budget 25))
        beam (long (or beam 8))]
    (loop [frontier #{(:id root)}
           depths {(:id root) 0}
           collected {}
           depth 0]
      (if (or (empty? frontier) (>= (count collected) budget) (>= depth 6))
        (let [facts (->> (vals collected)
                         (sort-by (fn [f] [(- (:walk-score f)) (str (:id f))]))
                         (take budget)
                         vec)]
          {:root root
           :query query
           :entities (walk-nodes root facts depths)
           :facts facts})
        (let [candidates (->> (store/-get-facts-for s (vec frontier) {:direction :both})
                              (filter #(logic/fact-valid-at? % t))
                              (remove (comp collected :id)))
              step (logic/walk-step candidates toks t beam)
              far (->> step
                       (mapcat (juxt (comp :id :subject) (comp :id :object-ref)))
                       (remove nil?)
                       (remove depths)
                       set)]
          (recur far
                 (into depths (map (fn [id] [id (inc depth)])) far)
                 (into collected (map (juxt :id identity)) step)
                 (inc depth)))))))

(defn get-history
  "All versions of (subject, predicate), valid and invalidated, time-ordered.
  The single best demonstration of why this beats markdown."
  [s {:keys [subject subject-scope predicate]}]
  (let [e (require-entity s subject subject-scope)
        pred-id (resolve-predicate-id s predicate)]
    {:entity e
     :predicate pred-id
     :history (->> (store/-get-history s (:id e) pred-id)
                   (sort-by (comp logic/ms :t-valid))
                   vec)}))

(defn search
  "Hybrid retrieval (the 20-point axis, review §3.2): three routes fused by
  reciprocal rank weighted by effective confidence —

    1. full-text over literals/names/summaries (the query as given),
    2. entity resolution per query token (exact, alias, normalized — so
       'auth-service' pulls AuthService's facts even when no literal
       matches), facts touching each resolved entity in both directions,
    3. the 1-hop neighborhood of those entities, at low weight (evidence
       near the query, ranked below direct hits).

  Store reads stay narrow (FTS candidates, per-entity fact sets, one
  batched neighbor read); the fusion is pure (logic/fuse-retrieval).
  Entities and episodes pass through from FTS untouched."
  [s query opts]
  (let [t (now)
        fts (store/-search s query opts)
        fts-facts (->> (logic/query-tokens query)
                       (mapcat #(:facts (store/-search s % {})))
                       (concat (:facts fts))
                       (reduce (fn [[seen out] f]
                                 (if (seen (:id f))
                                   [seen out]
                                   [(conj seen (:id f)) (conj out f)]))
                               [#{} []])
                       second)
        ents (->> (cons (str query) (logic/query-tokens query))
                  distinct
                  (keep #(:entity (resolve-entity s {:name %})))
                  (map (juxt :id identity))
                  (into {})
                  vals)
        efacts (when (seq ents)
                 (store/-get-facts-for s (mapv :id ents) {:direction :both}))
        hop-ids (when (seq efacts)
                  (let [known (set (map :id ents))]
                    (->> efacts
                         (mapcat (juxt (comp :id :subject) (comp :id :object-ref)))
                         (remove nil?)
                         (remove known)
                         distinct
                         vec)))
        hop-facts (when (seq hop-ids)
                    (store/-get-facts-for s hop-ids {:direction :out}))
        by-freshness (fn [fs] (->> fs
                                   (sort-by (fn [f] [(- (logic/effective-confidence f t))
                                                     (str (:id f))]))
                                   vec))
        ranked (logic/fuse-retrieval
                [{:weight 1.0 :facts fts-facts}
                 {:weight 1.2 :facts (by-freshness efacts)}
                 {:weight 0.3 :facts (by-freshness hop-facts)}]
                t)]
    (assoc fts
           :facts ranked
           :matched-entities (mapv #(select-keys % [:id :name :type]) ents))))

(defn recall
  "Sufficiency escalation (review §3.2, TierMem): answer from the cheapest
  tier that can support the query — graph facts first, episode summaries
  when the graph has nothing, raw evidence pages when even the summaries
  are silent. The sufficiency rule is deterministic and dumb on purpose (a
  tier is sufficient when it returned anything at or above :min-hits); the
  caller sees which tier answered and everything that tier found.
  opts: :min-hits (default 1) :evidence-dir (enables the raw tier)"
  [s query {:keys [min-hits evidence-dir]}]
  (let [min-hits (long (or min-hits 1))
        sr (search s query {})
        facts (:facts sr)
        episodes (when (< (count facts) min-hits)
                   (->> (logic/query-tokens query)
                        (mapcat #(:episodes (store/-search s % {})))
                        (concat (:episodes sr))
                        (reduce (fn [[seen out] ep]
                                  (if (seen (:id ep))
                                    [seen out]
                                    [(conj seen (:id ep)) (conj out ep)]))
                                [#{} []])
                        second))
        evidence (when (and (< (count facts) min-hits)
                            (< (count episodes) min-hits)
                            evidence-dir)
                   (let [fetch (requiring-resolve 'claimgraph.evidence/fetch)
                         toks (mapv str/lower-case (logic/query-tokens query))]
                     (vec (for [ep (filter :evidence (store/-list-episodes s))
                                :let [content (fetch evidence-dir (:evidence ep))
                                      hits (when content
                                             (filterv (fn [line]
                                                        (let [l (str/lower-case line)]
                                                          (some #(str/includes? l %) toks)))
                                                      (str/split-lines content)))]
                                :when (seq hits)]
                            {:episode (:id ep)
                             :ref (:ref ep)
                             :lines (vec (take 5 hits))}))))]
    {:tier (cond
             (>= (count facts) min-hits) :facts
             (seq episodes) :episodes
             (seq evidence) :evidence
             :else :nothing)
     :facts facts
     :episodes (vec (or episodes []))
     :evidence (vec (or evidence []))}))

(defn conflicts
  "Open conflicts: flagged facts whose conflicting candidates are still
  valid, awaiting a judge or a human. Candidate sets come from two narrow
  store reads (cheap-valid conflicted facts, then their linked candidates by
  id); logic/open-conflicts re-applies the exact validity policy."
  [s]
  (let [flagged (store/-select-facts s {:conflicted true :valid-cheap true})
        candidate-ids (vec (distinct (mapcat :conflicts flagged)))
        candidates (if (seq candidate-ids)
                     (store/-select-facts s {:ids candidate-ids})
                     [])
        deduped (vals (reduce (fn [m f] (assoc m (:id f) f)) {}
                              (concat flagged candidates)))
        open (logic/open-conflicts deduped (now))]
    {:open (count open)
     :conflicts open}))

(defn invalidate
  "Close a fact's validity interval. :at is the valid-time end — when it
  stopped being true, not merely when we noticed; defaults to now."
  [s {:keys [fact-id reason at]}]
  (when (str/blank? (str fact-id))
    (logic/fail "fact-id required" {:type :missing-fact-id}))
  (let [f (first (store/-select-facts s {:ids [fact-id]}))
        at (or at (now))]
    (when-not f
      (logic/fail (str "Fact not found: " fact-id)
                  {:type :fact-not-found :fact-id fact-id}))
    (when (< (logic/ms at) (logic/ms (:t-valid f)))
      (logic/fail "Invalid interval: the fact starts after the requested end"
                  {:type :invalid-interval :t-valid (:t-valid f) :at at}))
    (store/-invalidate s fact-id at {:kind :manual
                                     :reason (or reason "manually invalidated")})
    {:status :invalidated :fact-id fact-id :at at}))

;; ---------------------------------------------------------------------------
;; Episodes & ingestion
;; ---------------------------------------------------------------------------

(defn open-episode [s {:keys [source-type ref evidence]}]
  (store/-open-episode s (cond-> {:id (new-id "ep")
                                  :source-type (or (logic/->kw source-type) :session-log)
                                  :ref (str ref)
                                  :opened-at (now)}
                           evidence (assoc :evidence (str evidence)))))

(defn close-episode [s {:keys [episode summary]}]
  (when-not (store/-get-episode s episode)
    (logic/fail (str "Episode not found: " episode)
                {:type :episode-not-found :episode episode}))
  (store/-close-episode s episode (or summary "") (now))
  {:status :closed :episode episode})

(defn ingest
  "Batch-assert facts under one episode, each through the full conflict
  machinery. Returns per-status counts plus flagged/error details. Runs
  per-fact transactions: applying conflict policy fact-by-fact takes
  precedence over batch atomicity."
  [s {:keys [episode source-type ref evidence]} fact-maps]
  (let [ep (if episode
             (or (store/-get-episode s episode)
                 (logic/fail (str "Episode not found: " episode) {:type :episode-not-found}))
             (open-episode s {:source-type source-type :ref (or ref "ingest")
                              :evidence evidence}))
        results (mapv (fn [m]
                        (try
                          (let [r (assert-fact s (-> m
                                                     logic/normalize-ingest-fact
                                                     (assoc :episode (:id ep))))]
                            (-> (select-keys r [:status :superseded :candidates])
                                (assoc :fact-id (get-in r [:fact :id]))))
                          (catch clojure.lang.ExceptionInfo e
                            {:status :error :message (ex-message e) :input m})))
                      fact-maps)]
    {:episode (:id ep)
     :total (count results)
     :counts (frequencies (map :status results))
     :flagged (vec (filter #(= :flagged (:status %)) results))
     :errors (vec (filter #(= :error (:status %)) results))}))

;; ---------------------------------------------------------------------------
;; Maintenance
;; ---------------------------------------------------------------------------

(defn stats [s]
  (assoc (store/-stats s) :open-conflicts (:open (conflicts s))))

(defn dump
  "Export everything as a seq of records, each stamped with its kind under
  logic/dump-discriminator (the portability path). Records only — the format
  header belongs to the serializer (wire/dump-lines), because it describes the
  file, not the graph."
  [s]
  (let [stamp (fn [kind xs] (map #(assoc % logic/dump-discriminator kind) xs))]
    (concat
     (stamp "predicate" (store/-list-predicates s {}))
     (stamp "entity" (store/-list-entities s {}))
     (stamp "episode" (store/-list-episodes s))
     (stamp "fact" (store/-all-facts s)))))

(defn- dump-format
  "The format a dump declares, read off the header line, or nil when the
  records arrived without one (an in-memory caller rather than a file — every
  file wire/dump-lines writes leads with a header).

  A format ABOVE this build's is refused rather than attempted: the fields a
  newer format added are exactly the ones this reader would drop, so an
  optimistic load half-restores the graph and reports success. A format BELOW
  is fine and stays fine — that is the whole reason the format version is not
  the release version.

  A :format that is not an integer is refused here rather than coerced,
  because coercion is what a header exists to make unnecessary: without this
  guard, `null` and `\"2\"` would reach `long` and come out of the CLI as a
  bare NullPointerException (ex-message literally nil) or ClassCastException
  — a stack trace where the caller was promised claimgraph's JSON error
  contract. Absent is not defaulted either: the header arrived WITH format 1,
  so a header that declares no format is damaged, not old."
  [header]
  (when header
    (let [raw (:format header)]
      (when-not (int? raw)
        (logic/fail (str "This dump's header declares no readable format: :format is "
                         (pr-str raw))
                    {:type :dump-format-invalid
                     :dump-format raw
                     :readable-format version/format-version
                     :written-by (:version header)
                     :hint (str "every claimgraph dump header carries an integer :format; "
                                "this file's has been edited or was written by something "
                                "that is not claimgraph — re-dump the source database "
                                "with `claim dump`")}))
      (let [f (long raw)]
        (when (> f version/format-version)
          (logic/fail (str "This dump is format " f "; this claimgraph reads format "
                           version/format-version " and older")
                      {:type :dump-format-too-new
                       :dump-format f
                       :readable-format version/format-version
                       :written-by (:version header)
                       :hint (str "upgrade claimgraph to a release that reads format "
                                  f ", or re-dump from the claimgraph that wrote it")}))
        f))))

(defn- reject-unidentifiable!
  "Refuse input that nothing identifies as a dump, before a single write.

  Two things can vouch for a dump: the header line, which says what the FILE
  is, and the per-record kind stamp, which says what each LINE is. Records
  handed over in process (core/dump's return value, straight into load-dump)
  carry the second and not the first, which is why a missing header is not
  refused on its own. Input carrying neither — an empty file, a file
  truncated to nothing, a redirect that never wrote — carries nothing to
  refuse; without this check it would come back {:status :loaded} with every
  count zero — the silent success that makes a lost graph look like a
  restored one."
  [header records]
  (when-not (or header (seq records))
    (logic/fail "Refusing to load: this input holds no dump header and no records"
                {:type :not-a-dump
                 :records 0
                 :hint (str "every dump `claim dump` writes leads with a "
                            "{\"record\":\"claimgraph-dump\"} header line; an empty file "
                            "is not an empty graph, it is a file that lost its contents — "
                            "check the path, then re-dump the source database")})))

(defn- reject-unreadable!
  "Refuse a dump this build cannot read in full, before a single write. The
  alternative claimgraph shipped with — restore what parses, report the rest
  as an :unknown-records count — loses data at the one moment a user believes
  it is being preserved, and leaves a store that looks complete.

  Unstamped records get their diagnosis from the header, not from their own
  absence: \"written by a pre-alpha claimgraph\" was once the verdict on every
  unreadable file, so `claim load` of somebody else's JSONL told the user to
  re-dump a database that had nothing to do with it. A pre-alpha dump is
  recognisable — it stamped its kinds onto :type — and refused because that
  stamp landed on the entity's own type field and erased it at WRITE time;
  loading one restores untyped entities and calls it success."
  [header unstamped unknown]
  (when (seq unstamped)
    (cond
      (some logic/pre-alpha-dump-record? unstamped)
      (logic/fail (str "This dump was written by a pre-alpha claimgraph: "
                       (count unstamped) " records stamp their kind on :type")
                  {:type :dump-pre-alpha
                   :records (count unstamped)
                   :hint (str "re-dump the source database with this claimgraph and load "
                              "that instead — a pre-alpha dump stamped each record's kind "
                              "onto :type, the same field that holds an entity's own type, "
                              "so the entity types in this file are already gone")})

      header
      (logic/fail (str "This dump carries " (count unstamped)
                       " records with no record kind")
                  {:type :dump-records-unstamped
                   :records (count unstamped)
                   :hint (str "the header says claimgraph wrote this file, but these lines "
                              "say nothing about what they are — the file has been edited "
                              "or spliced; re-dump the source database with `claim dump`")})

      :else
      (logic/fail (str "This is not a claimgraph dump: no header, and none of its "
                       (count unstamped) " records says what it is")
                  {:type :not-a-dump
                   :records (count unstamped)
                   :hint (str "a claimgraph dump leads with a "
                              "{\"record\":\"claimgraph-dump\"} header and stamps every "
                              "line's kind under :record; this file does neither, so it "
                              "was written by something else — check the path")})))
  (when (seq unknown)
    (let [kinds (distinct (map #(get % logic/dump-discriminator) unknown))]
      (logic/fail (str "This dump holds record kinds this claimgraph cannot read: "
                       (str/join ", " kinds))
                  {:type :unknown-dump-records
                   :kinds (vec kinds)
                   :records (count unknown)
                   :readable-format version/format-version
                   :hint "upgrade claimgraph; loading the rest would drop these records and call it success"}))))

(defn load-dump
  "Restore a store from dump records: the other half of portability, so
  multi-machine users converge through the committed dump. A raw restore,
  deliberately NOT re-assertion — the dump already went through the conflict
  machinery once, so fact/episode ids, validity intervals, invalidation
  reasons, and conflict links round-trip exactly. Entity ids are re-minted
  (they're internal); facts re-point through an identity map keyed on the
  dumped id. Refuses a store that already holds data — load restores, it
  does not merge.

  Takes the dump's lines whole, header included, because deciding whether
  these bytes are readable at all is load's job and nobody else's. Anything
  it cannot read in full it refuses outright: a partial restore that returns
  :loaded is the failure mode worth the most noise.

  The header is optional only because core/dump's records reach here directly
  as well as through a file, and those records vouch for themselves one kind
  stamp at a time. Input that neither leads with a header NOR stamps its
  records is refused rather than loaded as an empty graph — see
  reject-unidentifiable! and reject-unreadable! for which refusal each shape
  earns, and why one message for all of them was worse than none."
  [s records]
  (let [st (store/-stats s)]
    (when (or (pos? (long (:entities st 0)))
              (pos? (long (get-in st [:facts :total] 0)))
              (pos? (long (:episodes st 0))))
      (logic/fail "Refusing to load into a non-empty store"
                  {:type :store-not-empty
                   :stats (select-keys st [:entities :facts :episodes])
                   :hint "load restores a dump into a fresh store; point --db somewhere empty"}))
    (let [header (when (version/header? (first records)) (first records))
          body (if header (rest records) records)
          _ (reject-unidentifiable! header body)
          fmt (dump-format header)
          parsed (group-by first (map logic/rehydrate-dump-record body))
          of-kind (fn [k] (mapv second (parsed k)))
          preds (of-kind :predicate)
          ents (of-kind :entity)
          eps (of-kind :episode)
          facts (of-kind :fact)
          id-map (atom {})
          remap (fn [emb]
                  (when emb
                    (or (@id-map (:id emb))
                        ;; referenced but not in the entity records — restore
                        ;; from the embedding itself
                        (let [e (ensure-entity s (select-keys emb [:name :type :scope]))]
                          (swap! id-map assoc (:id emb) e)
                          e))))]
      (reject-unreadable! header (of-kind :unstamped) (of-kind :unknown))
      (doseq [p preds] (store/-register-predicate s p))
      (doseq [e ents]
        (let [e' (store/-ensure-entity s (select-keys e [:name :type :scope]))]
          (when (seq (:aliases e))
            (store/-update-entity s (:id e') {:add-aliases (vec (:aliases e))}))
          (swap! id-map assoc (:id e)
                 (update e' :aliases #(vec (distinct (into (vec %) (:aliases e))))))))
      (doseq [ep eps]
        (store/-open-episode s (select-keys ep [:id :source-type :ref :opened-at :evidence]))
        (when (:closed-at ep)
          (store/-close-episode s (:id ep) (or (:summary ep) "") (:closed-at ep))))
      (doseq [f facts]
        (store/-insert-fact s (-> f
                                  (assoc :subject (remap (:subject f)))
                                  (assoc :object-ref (remap (:object-ref f)))
                                  (dissoc :invalidation-reason :invalidation-kind
                                          :successor :conflicts)))
        ;; the whole invalidation goes back through -invalidate, not the
        ;; insert: it is the one write that has to look identical however the
        ;; fact got here, so a dumped supersession restores with the same kind
        ;; and successor a live one records. :successor is a plain id and is
        ;; restored as one — the successor may be several thousand lines
        ;; further down this dump, and a load that waited for it would have to
        ;; order the file
        (when (and (:t-invalid f)
                   (or (:invalidation-reason f) (:invalidation-kind f) (:successor f)))
          (store/-invalidate s (:id f) (:t-invalid f)
                             {:kind (:invalidation-kind f)
                              :successor (:successor f)
                              :reason (:invalidation-reason f)})))
      (doseq [f facts
              :when (seq (:conflicts f))]
        (store/-link-conflicts s (:id f) (vec (:conflicts f))))
      (cond-> {:status :loaded
               :predicates (count preds)
               :entities (count ents)
               :episodes (count eps)
               :facts (count facts)
               :invalidated (count (filter :t-invalid facts))
               :conflict-links (count (mapcat :conflicts facts))}
        fmt (assoc :format fmt)))))
