(ns claimgraph.store
  "The storage abstraction: the boundary between storage-agnostic core
  operations and a concrete storage engine. Implementations speak plain
  Clojure maps in the wire shapes documented below; all temporal/conflict/
  validation semantics live in claimgraph.core and claimgraph.logic, NOT here.
  Store methods are raw primitives.

  Wire shapes:

  entity    {:id :name :type :scope :aliases [str]}
  fact      every field of `fact-fields` below, and only those — the
            declaration is the shape, not a prose copy of it
  episode   {:id :source-type :ref :summary :opened-at :closed-at
             :evidence str|nil — a tagged digest, `sha256-` + 64 hex (71
             chars); stores predating the tag hold a bare 64-hex digest}
  predicate {:id kw :label :category kw :object-kind kw :cardinality kw
             :inverse-of kw :status kw :replaced-by kw :definition
             :maps-to :default-epistemic kw}")

(def fact-fields
  "The fact wire shape, declared once, in order. Every place that has to
  enumerate a fact's attributes derives its list from here: the Datalevin
  schema, the pull pattern, the wire projection and the tx builder
  (store.datalevin/fact-attrs, which is checked against this at load), and
  the dump rehydration (logic/rehydrate-dump-record). Adding a column is then
  one line here plus one row of storage detail, instead of five edits nothing
  keeps in sync.

  Five edits, and not one of them fails loudly when it is the one you forget:
  a field missing from the pull or the wire projection is simply invisible on
  read, one missing from the tx builder is dropped on write, one missing from
  the schema is an untyped datom, and one missing from the rehydration comes
  back from a dump as a STRING and compares unequal to every keyword and every
  Date it is put beside. That is not hypothetical — it is how -list-entities
  came to omit :aliases and take every alias out of a Datalevin dump.

  :json is what a JSON round trip has to restore, and it is the only thing
  this namespace says about storage — the wire shape is the contract, the
  datoms are one backend's business:

    :string    passthrough
    :keyword   back to a keyword, never left as \"core/prefers\"
    :instant   back to a java.util.Date, milliseconds intact
    :double    back to a double, never a long
    :entity    a nested entity map, whose own :type is a keyword
    :fact-ids  a vector of fact ids (strings)

  Every field but :id, :subject, :predicate, :object-kind, :t-valid,
  :recorded-at, :confidence, :epistemic, :scope and :source-type is nullable."
  [{:key :id                  :json :string}
   {:key :subject             :json :entity}
   {:key :predicate           :json :keyword}
   {:key :object-kind         :json :keyword}   ; :entity | :literal
   {:key :object-ref          :json :entity}    ; set iff :object-kind :entity
   {:key :object-lit          :json :string}    ; set iff :object-kind :literal
   {:key :t-valid             :json :instant}
   {:key :t-invalid           :json :instant}   ; open interval when absent
   {:key :recorded-at         :json :instant}
   {:key :last-reinforced-at  :json :instant}
   {:key :confidence          :json :double}
   {:key :epistemic           :json :keyword}
   {:key :scope               :json :string}
   {:key :source-type         :json :keyword}
   {:key :episode             :json :string}    ; provenance: the episode's id
   {:key :conflicts           :json :fact-ids}  ; written by -link-conflicts
   ;; why the interval closed. The sentence is for humans and nothing parses
   ;; it; the kind (logic/invalidation-kinds) and the successor are what a
   ;; reader acts on. All three are written by -invalidate.
   {:key :invalidation-reason :json :string}
   {:key :invalidation-kind   :json :keyword}
   {:key :successor           :json :string}])

(def fact-keys
  "Every fact wire key, in declaration order."
  (mapv :key fact-fields))

(defn fact-keys-of
  "The fact wire keys whose JSON round trip is of one class — the single
  source for every field list a JSON round trip needs."
  [json-class]
  (into [] (comp (filter #(= json-class (:json %))) (map :key)) fact-fields))

(def ^:dynamic *write-warnings*
  "When bound to an atom (the CLI and MCP surfaces bind it per command), a
  store decorator that degrades without failing the write — today the oplog
  appender — pushes a structured warning here, and the command attaches the
  batch to its report (spec/replication.allium, decided 2026-07-26): a log
  that quietly stopped being written is a machine that quietly stopped
  replicating, and stderr alone never reaches a parser. Unbound, warnings
  still go to stderr; nothing blocks, nothing is lost."
  nil)

(defn push-write-warning!
  "Record one degradation warning for the current command's report, when a
  surface is collecting them. Safe to call from anywhere: a no-op unbound."
  [warning]
  (when *write-warnings*
    (swap! *write-warnings* conj warning)))

(defprotocol Store
  (-ensure-entity [s ent]
    "Exact name+scope match or create. ent = {:name :type :scope}. Returns entity.")
  (-get-entity [s name scope]
    "Entity by exact name+scope, or nil.")
  (-find-entities [s name scope]
    "Candidate entities for resolution: name or alias matches exactly, or
    normalizes (logic/normalize-entity-name) to the same form as the input.
    Over-returning is fine — precedence and ambiguity are decided purely in
    logic/pick-entity-match.")
  (-update-entity [s entity-id updates]
    "Apply {:name str, :type kw, :add-aliases [str], :remove-aliases [str]}
    to an entity (adds land before removes). Stores maintain any derived
    lookup fields (normalized names, indexes) — including not dropping a
    normalized form a surviving alias still needs when a spelling that
    shares it is removed.")
  (-repoint-facts [s from-entity-id to-entity-id]
    "Re-reference every fact whose subject or object is from-entity onto
    to-entity (the merge primitive). Returns the number of facts touched.")
  (-repoint-predicate [s from-pred to-pred]
    "Rewrite every fact's predicate from one id to another (the promotion
    primitive — a rename of the term, not a belief change: validity and
    transaction time are untouched). Returns the number of facts touched.")
  (-delete-entity [s entity-id]
    "Remove an entity row (the merged-away husk; its names live on as
    aliases of the survivor). Facts are never deleted.")
  (-list-entities [s opts]
    "All entities. opts {:type :scope} as exact filters.")
  (-insert-fact [s fact]
    "Raw insert of a complete fact map. No conflict logic. Returns the fact.")
  (-get-facts [s entity-id opts]
    "Raw facts touching an entity. opts {:direction :out|:in|:both,
    :predicate kw-or-coll} (a collection is one query with the set bound, not
    a loop). Includes invalidated facts; validity filtering happens in core.")
  (-get-facts-for [s entity-ids opts]
    "Batched -get-facts: every fact touching ANY of entity-ids, deduplicated,
    fetched in one query per direction regardless of how many ids are passed.
    The BFS frontier hands its whole level here — never loop -get-facts.")
  (-select-facts [s criteria]
    "Coarse, index-backed candidate-set read for maintenance paths. criteria
    is a whitelisted map of structural attributes, ANDed:
      :ids [fact-id]          exact fact ids
      :source-type kw         e.g. :code
      :predicates coll-of-kw  fact predicate is one of these
      :scopes coll-of-str     fact scope is one of these
      :episodes [episode-id]  provenance episode is one of these
      :recorded-before inst   recorded earlier than this (missing recorded-at
                              over-includes)
      :conflicted true        carries at least one conflict link
      :valid-cheap true       t-invalid absent — the cheap indexed check ONLY
    Over-inclusion is allowed and expected: the pure functions in logic
    (fact-valid-at?, effective-confidence, open-conflicts, stale-facts)
    remain the sole authority on policy and re-apply it over the candidate
    set.")
  (-predicate-usage [s]
    "Aggregate, store-side: map of predicate -> fact count.")
  (-entity-usage [s]
    "Aggregate, store-side: map of entity-id -> count of facts touching it
    (as subject or object). Ranks the roster shown to the extractor.")
  (-get-history [s entity-id predicate]
    "All facts (valid + invalidated) for (subject, predicate).")
  (-invalidate [s fact-id at invalidation]
    "Close the validity interval at `at`, recording WHY as structure rather
    than as a sentence. `invalidation` is {:kind kw :successor fact-id
    :reason str}, normalized through logic/invalidation — which also accepts
    a bare reason string, the shape every caller passed before the kind
    existed, so a writer that has not been taught its kind yet still records
    a fact correctly (it just records nothing a reader can act on).

    :successor is a plain fact id, deliberately not a reference the store
    resolves: a dump restores facts in file order and an oplog replays them
    in clock order, so the successor routinely does not exist yet when the
    predecessor closes. A dangling id has to degrade to \"no successor known\"
    on read, never refuse the write.")
  (-link-conflicts [s fact-id conflict-ids]
    "Record conflict links from fact-id to each id in conflict-ids.")
  (-unlink-conflicts [s fact-id conflict-ids]
    "Remove conflict links from fact-id to each id in conflict-ids.")
  (-reinforce [s fact-id {:keys [at confidence source-type]}]
    "Reset a fact's disuse clock (:last-reinforced-at, plus any derived
    mirror) and set its base confidence, in one write. :source-type, when
    present, re-sources the fact — reinforcement by a higher-ceiling source
    upgrades the row's provenance (spec/claims.allium, decided 2026-07-26);
    absent, the source is untouched.")
  (-all-facts [s])
  (-open-episode [s ep]
    "ep = {:id :source-type :ref :opened-at}. Returns episode.")
  (-close-episode [s episode-id summary at])
  (-get-episode [s episode-id])
  (-list-episodes [s])
  (-get-predicate [s pred-id])
  (-list-predicates [s opts]
    "opts {:category :status} as exact filters.")
  (-register-predicate [s pred]
    "Insert or update a predicate registry row. Returns the predicate.")
  (-search [s query opts]
    "Full-text (or best-effort substring) search across entity names, literal
    objects and episode summaries. Returns {:entities [] :facts [] :episodes []}.")
  (-stats [s])
  (-close [s]))
