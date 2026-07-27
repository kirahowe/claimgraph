(ns claimgraph.store.datalevin
  "Datalevin Store implementation, accessed as a Babashka pod. The pod binary
  is `dtlv` (a GraalVM native binary that speaks the pod protocol); override
  its location with the CLAIMGRAPH_DTLV env var. This is the only namespace that
  knows about datoms, Datalog, or :db/* anything.

  Pod discipline: the pod is a serialization boundary — push whole queries
  across and get result sets back, never loop chattily."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [claimgraph.config :as config]
            [claimgraph.logic :as logic]
            [claimgraph.predicates :as preds]
            [claimgraph.store :as store]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(pods/load-pod (or (System/getenv "CLAIMGRAPH_DTLV") "dtlv"))

(require '[pod.huahaiy.datalevin :as d])

;; ---- wire <-> datom translation -------------------------------------------

(def ^:private entity-pull
  [:entity/id :entity/name :entity/type :entity/scope :entity/aliases])

(defn- ent->wire [m]
  (when m
    {:id (:entity/id m) :name (:entity/name m)
     :type (:entity/type m) :scope (:entity/scope m)
     :aliases (vec (:entity/aliases m))}))

(def ^:private fact-attrs
  "Every fact attribute, declared once: the datom it lives in, its schema, how
  it is pulled, how it projects back to the wire, and how it is written.

  Those were four separate lists here (the schema map, fact-pull, fact->wire,
  fact->tx) plus a fifth a namespace away (the dump's rehydration, now driven
  off store/fact-fields), and adding a column meant editing all of them with
  nothing checking that you had. Each omission failed differently and none of
  them failed loudly: no pull or no projection and the column is simply
  invisible on read; no tx entry and it is dropped on write; no schema and the
  datom is untyped. store/fact-fields is the wire half of this declaration,
  and the two are checked against each other at load — see
  undeclared-fact-fields.

  Defaults cover the plain columns: pull the attribute, project it with a
  get, write the wire value under it. A row only says more when it is a
  reference, when it maintains a derived index column beside itself, or when
  something other than -insert-fact owns the write:

    :pull     what to put in fact-pull (default: the attribute)
    :project  pulled map -> wire value (default: get the attribute)
    :tx       fact -> tx fragment, possibly several attributes (default: the
              wire value under the attribute); one that returns nil writes
              nothing, i.e. -insert-fact does not own this column
    :derived  extra schema for index columns this field maintains"
  (array-map
   :id {:attr :fact/id
        :schema {:db/valueType :db.type/string :db/unique :db.unique/identity}}

   :subject {:attr :fact/subject
             :schema {:db/valueType :db.type/ref}
             :pull {:fact/subject entity-pull}
             :project #(ent->wire (:fact/subject %))
             :tx (fn [f] {:fact/subject [:entity/id (get-in f [:subject :id])]})}

   :predicate {:attr :fact/predicate :schema {:db/valueType :db.type/keyword}}

   :object-kind {:attr :fact/object-kind :schema {:db/valueType :db.type/keyword}}

   :object-ref {:attr :fact/object-ref
                :schema {:db/valueType :db.type/ref}
                :pull {:fact/object-ref entity-pull}
                :project #(ent->wire (:fact/object-ref %))
                :tx (fn [f] {:fact/object-ref (when-let [o (:object-ref f)]
                                                [:entity/id (:id o)])})}

   :object-lit {:attr :fact/object-lit
                :schema {:db/valueType :db.type/string :db/fulltext true}}

   :t-valid {:attr :fact/t-valid :schema {:db/valueType :db.type/instant}}

   :t-invalid {:attr :fact/t-invalid :schema {:db/valueType :db.type/instant}}

   ;; the -ms twins are derived longs for indexed selection: Datalog
   ;; comparison predicates work on numbers, not boxed dates
   :recorded-at {:attr :fact/recorded-at
                 :schema {:db/valueType :db.type/instant}
                 :derived {:fact/recorded-ms {:db/valueType :db.type/long}}
                 :tx (fn [f]
                       {:fact/recorded-at (:recorded-at f)
                        :fact/recorded-ms (some-> ^java.util.Date (:recorded-at f)
                                                  .getTime)})}

   :last-reinforced-at {:attr :fact/last-reinforced-at
                        :schema {:db/valueType :db.type/instant}
                        :derived {:fact/last-reinforced-ms {:db/valueType :db.type/long}}
                        :tx (fn [f]
                              {:fact/last-reinforced-at (:last-reinforced-at f)
                               :fact/last-reinforced-ms
                               (some-> ^java.util.Date (:last-reinforced-at f) .getTime)})}

   :confidence {:attr :fact/confidence :schema {:db/valueType :db.type/double}}

   :epistemic {:attr :fact/epistemic :schema {:db/valueType :db.type/keyword}}

   :scope {:attr :fact/scope :schema {:db/valueType :db.type/string}}

   :source-type {:attr :fact/source-type :schema {:db/valueType :db.type/keyword}}

   :episode {:attr :fact/source
             :schema {:db/valueType :db.type/ref}
             :pull {:fact/source [:episode/id]}
             :project #(get-in % [:fact/source :episode/id])
             :tx (fn [f] {:fact/source (when-let [ep (:episode f)] [:episode/id ep])})}

   ;; -link-conflicts owns this one, and has to: the linked facts are refs,
   ;; and a load restores facts in file order, so at insert time the other
   ;; side of the link routinely does not exist yet
   :conflicts {:attr :fact/conflicts
               :schema {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
               :pull {:fact/conflicts [:fact/id]}
               :project #(mapv :fact/id (:fact/conflicts %))
               :tx (constantly nil)}

   :invalidation-reason {:attr :fact/invalidation-reason
                         :schema {:db/valueType :db.type/string}}

   :invalidation-kind {:attr :fact/invalidation-kind
                       :schema {:db/valueType :db.type/keyword}}

   ;; a fact id as a STRING, not a ref: the successor is written when the
   ;; predecessor closes, which is before the successor exists on a replay or
   ;; a restore. A ref would refuse that write; a dangling id just reads back
   ;; as no successor known (store/-invalidate)
   :successor {:attr :fact/successor :schema {:db/valueType :db.type/string}}))

(defn undeclared-fact-fields
  "Where fact-attrs and store/fact-fields disagree: {:missing [wire-keys this
  store cannot read or write] :extra [attributes nothing documents]}. Empty
  both ways is the invariant; it is checked at load below, and named here so a
  test can say which half is wrong instead of only that the namespace refused
  to load."
  []
  {:missing (vec (remove (set (keys fact-attrs)) store/fact-keys))
   :extra (vec (remove (set store/fact-keys) (keys fact-attrs)))})

(let [{:keys [missing extra]} (undeclared-fact-fields)]
  (when (or (seq missing) (seq extra))
    (throw (ex-info (str "claimgraph.store.datalevin and claimgraph.store "
                         "disagree about the fact wire shape")
                    {:type :fact-shape-mismatch
                     :undeclared-here missing
                     :undocumented-in-store extra}))))

(def ^:private fact-schema
  (into {} (mapcat (fn [[_ {:keys [attr schema derived]}]]
                     (cons [attr schema] derived)))
        fact-attrs))

(def schema
  (merge
   {;; ---- Entity ----
    :entity/id        {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :entity/name      {:db/valueType :db.type/string :db/fulltext true}
    :entity/type      {:db/valueType :db.type/keyword}
    :entity/scope     {:db/valueType :db.type/string}
    :entity/aliases   {:db/valueType :db.type/string :db/cardinality :db.cardinality/many
                       :db/fulltext true}
    ;; derived lookup fields for near-match resolution, maintained on write
    :entity/norm-name    {:db/valueType :db.type/string}
    :entity/norm-aliases {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}}

   ;; ---- Fact (reified edge + metadata bundle) ----
   fact-schema
   {;; reserved-but-unused: retrofitting an ACL dimension later is far more
    ;; painful than carrying nullable fields now. No wire key, so no row in
    ;; fact-attrs — the day they carry a value they become one.
    :fact/read-acl    {:db/valueType :db.type/string}
    :fact/write-acl   {:db/valueType :db.type/string}}

   {;; ---- Episode (provenance anchor) ----
    :episode/id          {:db/valueType :db.type/string :db/unique :db.unique/identity}
    :episode/source-type {:db/valueType :db.type/keyword}
    :episode/ref         {:db/valueType :db.type/string}
    :episode/summary     {:db/valueType :db.type/string :db/fulltext true}
    :episode/opened-at   {:db/valueType :db.type/instant}
    :episode/closed-at   {:db/valueType :db.type/instant}
    ;; content-address (sha-256) of the raw-evidence artifact this episode
    ;; was extracted from; the bytes live outside the store (claimgraph.evidence)
    :episode/evidence    {:db/valueType :db.type/string}}

   {;; ---- Predicate registry (self-describing vocabulary) ----
    :predicate/id          {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
    :predicate/label       {:db/valueType :db.type/string}
    :predicate/category    {:db/valueType :db.type/keyword}
    :predicate/object-kind {:db/valueType :db.type/keyword}
    :predicate/cardinality {:db/valueType :db.type/keyword}
    :predicate/inverse-of  {:db/valueType :db.type/keyword}
    :predicate/exclusion-group {:db/valueType :db.type/keyword}
    :predicate/value-exclusivity {:db/valueType :db.type/keyword}
    :predicate/status      {:db/valueType :db.type/keyword}
    :predicate/replaced-by {:db/valueType :db.type/keyword}
    :predicate/definition  {:db/valueType :db.type/string}
    :predicate/maps-to     {:db/valueType :db.type/string}
    :predicate/default-epistemic {:db/valueType :db.type/keyword}
    :predicate/alt-labels  {:db/valueType :db.type/string
                            :db/cardinality :db.cardinality/many}}))

(def ^:private fact-pull
  (mapv (fn [[_ {:keys [attr pull]}]] (or pull attr)) fact-attrs))

(defn- fact->wire [m]
  (into {}
        (map (fn [[k {:keys [attr project]}]]
               [k (if project (project m) (get m attr))]))
        fact-attrs))

(defn- episode->wire [m]
  {:id (:episode/id m) :source-type (:episode/source-type m)
   :ref (:episode/ref m) :summary (:episode/summary m)
   :opened-at (:episode/opened-at m) :closed-at (:episode/closed-at m)
   :evidence (:episode/evidence m)})

(def ^:private predicate-attrs
  "Wire key -> attribute for every registry field -register-predicate owns.
  Ownership is the point: this list bounds what a re-registration may write
  and, on a curated row, what it may retract. :predicate/alt-labels is
  deliberately absent — nothing writes it yet, and a reconcile that cleared
  every attribute it merely didn't recognise would delete a future writer's
  data on the next `claim init`."
  (array-map
   :label :predicate/label
   :category :predicate/category
   :object-kind :predicate/object-kind
   :cardinality :predicate/cardinality
   :inverse-of :predicate/inverse-of
   :status :predicate/status
   :replaced-by :predicate/replaced-by
   :definition :predicate/definition
   :maps-to :predicate/maps-to
   :default-epistemic :predicate/default-epistemic
   :exclusion-group :predicate/exclusion-group
   :value-exclusivity :predicate/value-exclusivity))

(defn- curated?
  "Does registering this id REDEFINE the row, or AMEND it? The store cannot
  see which caller it is serving, but the id says which half of the
  vocabulary the row belongs to, and the two halves are owned by different
  writers.

  :core/* is curated: the seed map IS the row, because nothing else may write
  one — logic/prepare-registration refuses a runtime coinage outside :x/*. So
  a field the seed drops has to be retracted. :core/defined-in lost
  :inverse-of when the containment pair stopped being bijective, and an
  add-only upsert would leave every store that ever seeded the old row
  reporting the retired value forever.

  :x/* accumulates instead, across writers that each know only part of the
  row: coined on first use from preds/auto-registration, amended by `claim
  predicate register` with the fields the user named (never the whole row),
  deprecated by promotion with the forwarding address. Reconciling one of
  those against a partial map is how `predicate register x/foo --definition
  ...` erased :maps-to, :default-epistemic and the :replaced-by pointing at
  the promoted twin.

  Anything else — a namespace only a dump or an oplog replay can introduce —
  arrives as a whole row from a store that already held it, so it reconciles
  like the curated half. That includes an id that is not a keyword at all: a
  hand-written dump can put anything in :id, and such a row should fail on
  the write, where the error names the row, rather than inside a namespace
  check that never had an opinion about it."
  [pred-id]
  (not (and (keyword? pred-id) (preds/experimental? pred-id))))

(defn- pred->wire
  "Registry row -> wire map, driven off predicate-attrs so the field list has
  one home.

  Key order is not part of the wire contract and never was: a map of nine
  keys or more is a hash map whichever way it is built, so every seeded
  predicate serializes to the same bytes it did before this was rewritten
  (measured: all 23). What did change order is a row short enough to stay an
  array map — a freshly coined :x/* predicate, seven keys — which now leads
  with :id instead of wherever hashing put it. That is a one-time reordering
  of those lines in a committed dump, with no value changed and nothing for a
  loader to notice, which is why it costs no format bump: JSON object key
  order carries no meaning, and pinning the old order would mean keeping the
  13-key literal whose duplication of this list is what went wrong first."
  [m]
  (into {:id (:predicate/id m)}
        (keep (fn [[k attr]] (when-some [v (get m attr)] [k v])))
        predicate-attrs))

(defn- strip-nils [m] (into {} (filter (comp some? val)) m))

(defn- fact->tx [f]
  (strip-nils
   (into {}
         (map (fn [[k {:keys [attr tx]}]]
                (if tx (tx f) {attr (get f k)})))
         fact-attrs)))

;; ---- queries ---------------------------------------------------------------

(defn- q-facts
  "One Datalog query per direction, with the entity ids bound as a collection —
  the round-trip count is independent of how many ids are passed."
  [db entity-ids direction predicate]
  (let [ids (vec entity-ids)
        preds (when predicate (if (coll? predicate) (vec predicate) [predicate]))
        out '[?f :fact/subject ?e]
        in '[?f :fact/object-ref ?e]
        runner (fn [clause]
                 (if preds
                   (d/q [:find [(list 'pull '?f fact-pull) '...]
                         :in '$ '[?eid ...] '[?pred ...]
                         :where '[?e :entity/id ?eid] clause '[?f :fact/predicate ?pred]]
                        db ids preds)
                   (d/q [:find [(list 'pull '?f fact-pull) '...]
                         :in '$ '[?eid ...]
                         :where '[?e :entity/id ?eid] clause]
                        db ids)))]
    (case direction
      :out (runner out)
      :in (runner in)
      :both (->> (concat (runner out) (runner in))
                 (reduce (fn [acc m] (assoc acc (:fact/id m) m)) {})
                 vals))))

(defn- q-select
  "Build and run one Datalog query from whitelisted structural criteria.
  Binding clauses come first (they ground ?f), predicate clauses after; with
  no binding criterion a grounding clause is prepended so the predicates have
  something to range over."
  [db {:keys [ids source-type predicates scopes episodes recorded-before conflicted valid-cheap]}]
  (let [acc (cond-> {:where [] :in [] :args []}
              ids (-> (update :where conj '[?f :fact/id ?id])
                      (update :in conj '[?id ...])
                      (update :args conj (vec ids)))
              source-type (-> (update :where conj '[?f :fact/source-type ?st])
                              (update :in conj '?st)
                              (update :args conj source-type))
              predicates (-> (update :where conj '[?f :fact/predicate ?p])
                             (update :in conj '[?p ...])
                             (update :args conj (vec predicates)))
              scopes (-> (update :where conj '[?f :fact/scope ?sc])
                         (update :in conj '[?sc ...])
                         (update :args conj (vec scopes)))
              episodes (-> (update :where into '[[?ep :episode/id ?epid]
                                                 [?f :fact/source ?ep]])
                           (update :in conj '[?epid ...])
                           (update :args conj (vec episodes)))
              conflicted (update :where conj '[?f :fact/conflicts _])
              true (as-> a (if (empty? (:where a))
                             (update a :where conj '[?f :fact/id _])
                             a))
              valid-cheap (update :where conj '[(missing? $ ?f :fact/t-invalid)])
              recorded-before (-> (update :where into
                                          '[[(get-else $ ?f :fact/recorded-ms 0) ?rms]
                                            [(< ?rms ?cut)]])
                                  (update :in conj '?cut)
                                  (update :args conj (.getTime ^java.util.Date recorded-before))))
        query (-> [:find [(list 'pull '?f fact-pull) '...] :in '$]
                  (into (:in acc))
                  (conj :where)
                  (into (:where acc)))]
    (apply d/q query db (:args acc))))

(defrecord DatalevinStore [conn]
  store/Store
  (-ensure-entity [_ {:keys [name type scope]}]
    (let [db (d/db conn)
          eid (d/q '[:find ?e . :in $ ?n ?s
                     :where [?e :entity/name ?n] [?e :entity/scope ?s]]
                   db name scope)]
      (if eid
        (let [existing (d/pull db entity-pull eid)]
          (when (and type (nil? (:entity/type existing)))
            (d/transact! conn [{:db/id eid :entity/type type}]))
          (ent->wire (cond-> existing
                       (and type (nil? (:entity/type existing))) (assoc :entity/type type))))
        (let [ent (strip-nils {:entity/id (str "e-" (random-uuid))
                               :entity/name name :entity/type type :entity/scope scope
                               :entity/norm-name (logic/normalize-entity-name name)})]
          (d/transact! conn [ent])
          (ent->wire ent)))))

  (-get-entity [_ name scope]
    (some-> (d/q (into [:find (list 'pull '?e entity-pull) '.]
                       '[:in $ ?n ?s
                         :where [?e :entity/name ?n] [?e :entity/scope ?s]])
                 (d/db conn) name scope)
            ent->wire))

  (-find-entities [_ name scope]
    (let [db (d/db conn)
          norm (logic/normalize-entity-name name)
          q-attr (fn [attr v]
                   (d/q [:find '[?e ...] :in '$ '?v '?s
                         :where ['?e attr '?v] '[?e :entity/scope ?s]]
                        db v scope))]
      (->> (distinct (concat (q-attr :entity/name name)
                             (q-attr :entity/aliases name)
                             (q-attr :entity/norm-name norm)
                             (q-attr :entity/norm-aliases norm)))
           (mapv #(ent->wire (d/pull db entity-pull %))))))

  (-update-entity [_ entity-id {:keys [name type add-aliases remove-aliases]}]
    (d/transact! conn [(cond-> {:db/id [:entity/id entity-id]}
                         name (assoc :entity/name name
                                     :entity/norm-name (logic/normalize-entity-name name))
                         type (assoc :entity/type type)
                         (seq add-aliases)
                         (assoc :entity/aliases (vec add-aliases)
                                :entity/norm-aliases (mapv logic/normalize-entity-name
                                                           add-aliases)))])
    (when (seq remove-aliases)
      ;; Removes land after adds, against the post-add alias set. A normalized
      ;; form is retracted only when no SURVIVING alias still needs it —
      ;; "FooBar" and "foo-bar" share one norm, and dropping the norm with the
      ;; first would disarm normalized lookup for the second.
      (let [current (vec (:entity/aliases
                          (d/pull (d/db conn) [:entity/aliases]
                                  [:entity/id entity-id])))
            gone (set remove-aliases)
            survivors (remove gone current)
            surviving-norms (set (map logic/normalize-entity-name survivors))
            dead-norms (->> (filter gone current)
                            (map logic/normalize-entity-name)
                            (remove surviving-norms)
                            distinct)]
        (d/transact! conn
                     (concat (for [a (filter gone current)]
                               [:db/retract [:entity/id entity-id] :entity/aliases a])
                             (for [n dead-norms]
                               [:db/retract [:entity/id entity-id] :entity/norm-aliases n])))))
    entity-id)

  (-repoint-facts [_ from-id to-id]
    (let [db (d/db conn)
          eid (fn [id] (d/q '[:find ?e . :in $ ?id :where [?e :entity/id ?id]] db id))
          from-eid (eid from-id)
          to-eid (eid to-id)
          subj (d/q '[:find [?f ...] :in $ ?e :where [?f :fact/subject ?e]] db from-eid)
          obj (d/q '[:find [?f ...] :in $ ?e :where [?f :fact/object-ref ?e]] db from-eid)]
      (d/transact! conn
                   (concat (map (fn [f] {:db/id f :fact/subject to-eid}) subj)
                           (map (fn [f] {:db/id f :fact/object-ref to-eid}) obj)))
      (count (distinct (concat subj obj)))))

  (-repoint-predicate [_ from-pred to-pred]
    (let [eids (d/q '[:find [?f ...] :in $ ?p :where [?f :fact/predicate ?p]]
                    (d/db conn) from-pred)]
      (d/transact! conn (mapv (fn [e] {:db/id e :fact/predicate to-pred}) eids))
      (count eids)))

  (-delete-entity [_ entity-id]
    (d/transact! conn [[:db/retractEntity [:entity/id entity-id]]])
    entity-id)

  (-list-entities [_ {:keys [type scope]}]
    ;; entity-pull, not a narrower list: core/dump reads entities through here,
    ;; so a field this projection omits is a field the dump silently drops. It
    ;; omitted :entity/aliases, which took every alias out of a datalevin dump
    ;; and disarmed alias resolution on restore.
    (cond->> (map ent->wire
                  (d/q [:find [(list 'pull '?e entity-pull) '...]
                        :where '[?e :entity/id _]]
                       (d/db conn)))
      type (filter #(= type (:type %)))
      scope (filter #(= scope (:scope %)))
      true vec))

  (-insert-fact [_ fact]
    (d/transact! conn [(fact->tx fact)])
    fact)

  (-get-facts [_ entity-id opts]
    (mapv fact->wire
          (q-facts (d/db conn) [entity-id] (or (:direction opts) :out) (:predicate opts))))

  (-get-facts-for [_ entity-ids opts]
    (mapv fact->wire
          (q-facts (d/db conn) entity-ids (or (:direction opts) :out) (:predicate opts))))

  (-get-history [_ entity-id predicate]
    (mapv fact->wire (q-facts (d/db conn) [entity-id] :out predicate)))

  (-invalidate [_ fact-id at invalidation]
    (let [{:keys [kind successor reason]} (logic/invalidation invalidation)]
      (d/transact! conn [(strip-nils {:fact/id fact-id
                                      :fact/t-invalid at
                                      :fact/invalidation-reason reason
                                      :fact/invalidation-kind kind
                                      :fact/successor successor})]))
    fact-id)

  (-link-conflicts [_ fact-id conflict-ids]
    (d/transact! conn [{:fact/id fact-id
                        :fact/conflicts (mapv (fn [cid] [:fact/id cid]) conflict-ids)}])
    fact-id)

  (-unlink-conflicts [_ fact-id conflict-ids]
    (d/transact! conn (mapv (fn [cid]
                              [:db/retract [:fact/id fact-id]
                               :fact/conflicts [:fact/id cid]])
                            conflict-ids))
    fact-id)

  (-reinforce [_ fact-id {:keys [at confidence source-type]}]
    (d/transact! conn [(cond-> {:fact/id fact-id
                                :fact/confidence (double confidence)
                                :fact/last-reinforced-at at
                                :fact/last-reinforced-ms (.getTime ^java.util.Date at)}
                         source-type (assoc :fact/source-type source-type))])
    fact-id)

  (-all-facts [_]
    (mapv fact->wire (q-select (d/db conn) {})))

  (-select-facts [_ criteria]
    (mapv fact->wire (q-select (d/db conn) criteria)))

  (-predicate-usage [_]
    (into {} (d/q '[:find ?p (count ?f)
                    :where [?f :fact/predicate ?p]]
                  (d/db conn))))

  (-entity-usage [_]
    (let [db (d/db conn)
          as-subject (d/q '[:find ?id (count ?f)
                            :where [?f :fact/subject ?e] [?e :entity/id ?id]]
                          db)
          as-object (d/q '[:find ?id (count ?f)
                           :where [?f :fact/object-ref ?e] [?e :entity/id ?id]]
                         db)]
      (merge-with + (into {} as-subject) (into {} as-object))))

  (-open-episode [_ ep]
    (d/transact! conn [(strip-nils {:episode/id (:id ep)
                                    :episode/source-type (:source-type ep)
                                    :episode/ref (:ref ep)
                                    :episode/opened-at (:opened-at ep)
                                    :episode/evidence (:evidence ep)})])
    ep)

  (-close-episode [_ episode-id summary at]
    (d/transact! conn [{:episode/id episode-id
                        :episode/summary summary
                        :episode/closed-at at}])
    episode-id)

  (-get-episode [_ episode-id]
    (some-> (d/q '[:find (pull ?ep [*]) . :in $ ?id :where [?ep :episode/id ?id]]
                 (d/db conn) episode-id)
            episode->wire))

  (-list-episodes [_]
    (mapv episode->wire
          (d/q '[:find [(pull ?ep [*]) ...] :where [?ep :episode/id _]] (d/db conn))))

  (-get-predicate [_ pred-id]
    (some-> (d/q '[:find (pull ?p [*]) . :in $ ?id :where [?p :predicate/id ?id]]
                 (d/db conn) pred-id)
            pred->wire))

  (-list-predicates [_ {:keys [category status]}]
    (cond->> (map pred->wire
                  (d/q '[:find [(pull ?p [*]) ...] :where [?p :predicate/id _]] (d/db conn)))
      category (filter #(= category (:category %)))
      status (filter #(= status (:status %)))
      true (sort-by (comp str :id))
      true vec))

  (-register-predicate [_ pred]
    (let [id (:id pred)
          row (into {:predicate/id id}
                    (keep (fn [[k attr]] (when-some [v (get pred k)] [attr v])))
                    predicate-attrs)
          ;; On a curated row a field the caller dropped is retracted; on a
          ;; staging row it is left alone (see curated?). The existing row is
          ;; only read when the answer can depend on it, which also keeps the
          ;; first-use :x/* coinage a single round trip to the pod.
          retractions (when (curated? id)
                        (let [existing (d/q '[:find (pull ?p [*]) . :in $ ?id
                                              :where [?p :predicate/id ?id]]
                                            (d/db conn) id)]
                          (keep (fn [[k attr]]
                                  (let [old (get existing attr)]
                                    (when (and (nil? (get pred k)) (some? old))
                                      [:db/retract [:predicate/id id] attr old])))
                                predicate-attrs)))]
      (d/transact! conn (conj (vec retractions) row)))
    pred)

  (-search [this query _opts]
    (let [db (d/db conn)
          eids (distinct (map first
                              (d/q '[:find ?e ?a ?v :in $ ?q
                                     :where [(fulltext $ ?q) [[?e ?a ?v]]]]
                                   db query)))
          pulled (map #(d/pull db '[*] %) eids)]
      {:entities (->> pulled (filter :entity/id) (mapv ent->wire))
       :facts (->> pulled
                   (filter :fact/id)
                   (mapv #(fact->wire (d/pull db fact-pull (:db/id %)))))
       :episodes (->> pulled (filter :episode/id) (mapv episode->wire))}))

  (-stats [_]
    (let [db (d/db conn)
          cnt (fn [attr] (or (d/q (into [:find '(count ?e) '.]
                                        [:where ['?e attr '_]]) db) 0))
          invalidated (or (d/q '[:find (count ?f) . :where [?f :fact/t-invalid _]] db) 0)
          total (cnt :fact/id)]
      {:format version/format-version
       :entities (cnt :entity/id)
       :facts {:total total :valid (- total invalidated) :invalidated invalidated}
       :episodes (cnt :episode/id)
       :predicates (frequencies
                    (map second
                         (d/q '[:find ?p ?s :where [?p :predicate/id _] [?p :predicate/status ?s]] db)))}))

  (-close [_] (d/close conn)))

;; ---- format stamp ----------------------------------------------------------

(defn version-file
  "The store's format stamp: <db>.version, alongside <db>.oplog, <db>.evidence,
  <db>.lock and <db>.retrievals. A sibling FILE rather than a datom, and the
  reason is ordering, not taste — see open-store."
  [path]
  (str path ".version"))

(defn stamped-format
  "The format this store declares: the :format out of <db>.version, or nil
  when there is no stamp file at all.

  A stamp that exists but says nothing this build can read — truncated by a
  crash mid-spit, empty, or JSON without a :format — is REFUSED here, not
  reported as unstamped. The two look alike and are opposites: absent means
  written before stamping existed, which is every store claimgraph has ever
  written and is safe to open; unreadable means something wrote a stamp and
  this build cannot tell which format it claimed. Reading that as unstamped
  is the worst available outcome, because open-store would then merge this
  build's schema into a store that may be newer and stamp! would replace the
  file with this build's number — destroying, on exactly the input the gate
  cannot read, the only evidence the store was ever anything else."
  [path]
  (let [f (version-file path)]
    (when (fs/exists? f)
      (let [raw (slurp f)
            parsed (try (wire/parse-string raw) (catch Exception _ nil))]
        (if (some? (:format parsed))
          (:format parsed)
          (logic/fail
           (str f " is not a readable format stamp (it holds: "
                (pr-str (subs raw 0 (min 200 (count raw))))
                "), so this claimgraph cannot tell what wrote " path ".")
           {:type :unreadable-format-stamp
            :artifact f
            :supported version/format-version
            :hint (str "open the store with the claimgraph that wrote it, which "
                       "will re-stamp it; delete " f " only if you know this store "
                       "is this build's or older — this build would then treat it "
                       "as unstamped, merge its own schema in, and stamp it format "
                       version/format-version)}))))))

(defn- stamp!
  "Write <db>.version if it does not already say exactly this. Rewriting an
  unchanged stamp on every open would churn the mtime of a file that backup
  and sync tools watch.

  Only ever reached once stamped-format has accepted the file, which is what
  makes an unconditional write safe: the one stamp that must never be
  overwritten is the one nobody could parse, and that is refused upstream
  rather than replaced here."
  [path]
  (let [f (version-file path)
        content (wire/generate-string {:format version/format-version
                                       :version version/release})]
    (when-not (= content (when (fs/exists? f) (slurp f)))
      (spit f content))))

(defn open-store
  "Open (creating if needed) a Datalevin-backed store at path, refusing one
  written by a claimgraph newer than this build — or one whose stamp this
  build cannot read at all (stamped-format).

  The gate runs BEFORE d/get-conn, and that ordering is the whole design.
  get-conn MERGES `schema` into the store as it opens: a check that waits for
  a connection has already written this build's :db/valueType and
  :db/cardinality over a newer build's, so detecting the incompatibility would
  be the act that commits it — and the newer claimgraph the user goes back to
  now finds a store this one quietly rewrote. That is also why the stamp is a
  sibling file and not a datom: a datom cannot be read until the merge that it
  exists to prevent has happened.

  What the sibling costs is that `cp -r db/` alone leaves the stamp behind and
  the copy reads as unstamped. That copy has also lost the oplog (which is the
  record — the store is its materialized view), the evidence blobs and the
  lease, so it is not a store anyone can use anyway, and it is not worth
  trading the ordering guarantee to defend.

  An unstamped store is stamped in place rather than refused. Every store
  written before this change is unstamped, including claimgraph's own dogfood
  store, and they are format-0 stores in name only: stamping added no
  attribute and changed no valueType, so a format-0 and a format-1 store are
  byte-identical in shape. Refusing them would strand every existing user over
  a difference that does not exist."
  [path]
  (config/require-format (version-file path) (stamped-format path))
  (let [conn (d/get-conn path schema)]
    ;; after the open, so the stamp lands next to a store that exists: the
    ;; parent directory is datalevin's to create on a fresh path
    (stamp! path)
    (->DatalevinStore conn)))
