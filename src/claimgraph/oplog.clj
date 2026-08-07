(ns claimgraph.oplog
  "Append-only effect logs with reconciliation: the multi-device story
  (roadmap #25 v2), shaped by local-first prior art rather than a
  hand-rolled CRDT. Three ideas carried over from that literature:

  1. Per-writer append-only logs. Every store mutation appends one effect
     line to <db>.oplog/<writer-id>.jsonl, stamped with a hybrid logical
     clock. Each device only ever appends to its own file, so any file
     syncer (git, rsync, Syncthing) moves logs between machines without a
     merge conflict existing even in principle.
  2. The store is a materialized view; the logs are the record. `reconcile`
     applies unseen foreign effects in canonical (hlc, writer, seq) order,
     tracked with per-writer high-water marks. Entity identity crosses
     machines by NAME, remapped the same way `load` does it, because ids
     are internal.
  3. Convergence through surfacing, not through merge magic. A CRDT forces
     agreement by construction; claimgraph wants disagreement made visible.
     After applying, reconcile collapses exact duplicate claims non-lossily
     and counts the conflict candidates the sweep should judge. Two
     machines asserting contradictory things end up with an open conflict
     for a human, which is the point of the whole system.

  The write lease (claimgraph.lease) still serializes writers on ONE machine;
  this file is about writers who never shared a machine to begin with.

  ## The line

  One JSON object per line, a verb plus its payload plus a four-field
  envelope:

      {\"t\":\"insert-fact\",\"fact\":{...},
       \"writer\":\"w-a\",\"seq\":3,\"hlc\":1753440000123,\"format\":1}

  Every envelope field is on disk forever, so each is settled here rather
  than discovered by a reader later:

    :writer  who appended the line. Identity lives in the LINE, not in the
             filename, so a log survives being renamed, copied or restored
             under another name, and a machine recognizes its own effects
             inside a foreign file instead of replaying its history at
             itself.
    :seq     dense from 1 within a writer, and unique within it. That density
             is exactly what a high-water mark means — \"I have everything
             through N\" — so truncating, rotating or compacting a log breaks
             the contract at both ends, and both ends check: the writer
             resumes above the highest number the FILE holds (never at the
             line count, never at a number cached before another process
             appended), and reconcile reports a hole instead of stepping over
             it.
    :hlc     the writer's clock at append time; see next-hlc!.
    :format  claimgraph.version/format-version. A line stamped ABOVE the
             reader's own version is held, neither applied nor skipped past:
             guessing at a shape we were never taught is how a graph gets
             half-restored and called converged.

  Three of those four are read as numbers, and JSON promises nothing about
  types, so a line that arrives with the wrong shape in one of them — a
  hand-edited log, a half-written last line, a file that is not a claimgraph
  log at all — is diagnosed once, per line, before it can reach the sort or
  the format gate as a string. One peer's corruption is that peer's problem;
  it must never be everybody's failed reconcile.

  Payloads carrying a whole record (entity, fact, episode, predicate) tag it
  with :record, the same key the dump uses — never :type, which an entity's
  wire shape already owns — and encode through claimgraph.wire, so the
  milliseconds a bi-temporal store depends on survive the file. The scalar
  :at that invalidate, reinforce and close-episode carry is epoch millis
  instead: already exact, and unambiguous without a date format at all.
  Lines written before this (format 0) are still read; see rehydrate.

  Fields added to a payload go in BESIDE what is already there, never inside
  it: invalidate's kind and successor are siblings of its reason sentence
  precisely so a reader that predates them still finds prose where it looks.
  See invalidate-line, which is where getting that wrong corrupts a peer.

  ## What reconcile promises

  A per-writer high-water mark advances only over effects that actually
  landed or were already here. An effect this build cannot apply — an
  unknown verb from a newer writer, a prerequisite that has not synced yet,
  a throw — stays in front of the mark and is retried on the next
  reconcile, and reconcile says so in its report. Effects applied out of
  order while a hole waits are remembered by number (:applied-beyond in
  applied.json), so nothing is ever applied twice on the way to closing the
  gap.

  Every line the reader will not apply is named in :warnings: a hole in a
  sequence, a line whose envelope cannot be read, two effects sharing one
  seq, a peer's file that would not parse. The one thing that ever leaves
  without landing is an effect a human explicitly gave up on
  (:abandon-deferred, see reconcile!), and the report and applied.json both
  record which."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Writer identity and the clock
;; ---------------------------------------------------------------------------

(defn oplog-dir [db] (str db ".oplog"))

(defn- log-file [db writer] (fs/path (oplog-dir db) (str writer ".jsonl")))

(defn- warn!
  "Oplog trouble goes to stderr and nowhere else: stdout is a command's JSON
  and has to stay parseable, but a log that quietly stopped being written is
  a machine that quietly stopped replicating — not something to discover
  months later from a graph that drifted."
  [& parts]
  (binding [*out* *err*]
    (println (str "claimgraph oplog: " (apply str parts)))))

(defn writer-id!
  "This machine's writer id. <db>.oplog/writer IS the identity once it
  exists; $CLAIMGRAPH_WRITER only seeds a store that has none yet. Letting
  the environment win on every call splits one machine into two logical
  writers the moment the variable is set, unset or edited — and the machine
  then meets its own earlier log as a stranger's and replays its history
  against itself. A disagreement is reported, never obeyed."
  [db]
  (let [f (fs/path (oplog-dir db) "writer")
        env (some-> (System/getenv "CLAIMGRAPH_WRITER") str/trim not-empty)
        persisted (when (fs/exists? f)
                    (some-> (slurp (str f)) str/trim not-empty))]
    (cond
      (and persisted env (not= persisted env))
      (do (warn! "CLAIMGRAPH_WRITER=" env " ignored: this store already writes as "
                 persisted " (" (str f) "). Delete that file to mint a new identity.")
          persisted)

      persisted persisted

      :else (let [id (or env (str "w-" (random-uuid)))]
              (fs/create-dirs (oplog-dir db))
              (spit (str f) id)
              id))))

;; ---------------------------------------------------------------------------
;; Reading an envelope
;; ---------------------------------------------------------------------------

(defn- seq-of
  "A line's sequence number, or nil when it carries none this reader can place.
  Nothing in JSON makes \"seq\":\"3\" impossible, and a string compared against
  a mark is a ClassCastException in the middle of a pass that had nothing to do
  with the peer who wrote it."
  [line]
  (let [n (:seq line)] (when (integer? n) n)))

(defn- hlc-of [line] (let [h (:hlc line)] (if (number? h) h 0)))

(defn- envelope-problem
  "Why this line cannot be placed in its writer's sequence, or nil when it can.
  The reconciler orders on :hlc, marks on :seq and gates on :format, all read
  straight off the line — so one wrong-shaped field decided here, per line,
  instead of thrown from inside the pass, is the difference between one peer's
  line being reported and every peer's effects being lost to a bare cast
  error."
  [line]
  (let [n (:seq line)]
    (cond
      (nil? n) :no-seq
      (not (integer? n)) :bad-seq
      (not (pos? n)) :bad-seq
      (not (number? (:hlc line 0))) :bad-hlc
      (not (number? (:format line 0))) :bad-format)))

(defonce ^:private clocks
  ;; db path -> clock atom. Process-wide because the clock belongs to the
  ;; WRITER and not to a store handle: the MCP server opens more than one
  ;; store over a db in a single process, and reconcile — which is where
  ;; remote clocks get merged in — holds no store handle at all.
  (atom {}))

(defn- clock-for
  "This db's clock atom, raised to `floor`. Every time we observe, our own
  from a reopened log or a peer's during reconcile, becomes a floor under
  everything we append next."
  [db floor]
  (let [a (or (get @clocks db)
              (get (swap! clocks update db #(or % (atom 0))) db))]
    (swap! a max (or floor 0))
    a))

(defn- next-hlc!
  "Hybrid logical clock collapsed to one integer: wall-clock millis as the
  floor, +1 where that would repeat, and never below a clock reading we have
  observed from another writer (reconcile raises it through clock-for). That
  merge is the difference between a logical clock and a timestamp — an
  effect appended after seeing a peer's effect sorts after it no matter what
  the two machines' clocks think of each other, which is what makes
  (hlc, writer, seq) a total order worth replaying in. The price of one
  integer instead of the textbook (wall, counter) pair: a peer with a badly
  fast clock drags ours forward until wall time catches up. One comparable
  number per line is worth it."
  [clock]
  (swap! clock (fn [last] (max (System/currentTimeMillis) (inc last)))))

(declare read-log)

(defonce ^:private counters
  ;; [db path, writer] -> {:high <highest seq emitted> :size <bytes of the log
  ;; when we last looked}. Process-wide for the reason the clock is — more
  ;; than one store gets opened over one db in a process — and for a sharper
  ;; one: two counters over one log hand out one seq twice, and a repeated
  ;; number is the one numbering failure a reader cannot see. It arrives
  ;; looking exactly like one log copied under two names, which reconcile is
  ;; right to collapse. Preventing it is this atom's job; reconcile reports
  ;; the collision it could not prevent rather than collapsing that too.
  (atom {}))

(defn- log-size [db writer]
  (let [f (log-file db writer)] (if (fs/exists? f) (fs/size f) 0)))

(defn- disk-high-seq
  "The highest seq the log FILE holds. Consulted when the file has grown behind
  this handle's back: `claim mcp` holds one store open for a whole session and
  takes no lease, so an ordinary `claim assert` in another process appends as
  the same writer between two of our own appends."
  [db writer]
  (reduce max 0 (keep seq-of (:entries (read-log (log-file db writer))))))

(defn- counter-for
  "This (db, writer) pair's seq counter, raised to what the log on disk holds.
  `high` is the highest seq ever emitted, never the line count; `size` is the
  file it was read from, so an append can tell in one stat whether anything
  got in behind us instead of re-reading the whole log every time."
  [db writer high size]
  (let [k [db writer]
        a (or (get @counters k)
              (get (swap! counters update k #(or % (atom {:high 0 :size -1}))) k))]
    (swap! a (fn [c] (if (> high (:high c)) {:high high :size size} c)))
    a))

;; ---------------------------------------------------------------------------
;; Effect encoding (JSON-safe; rehydrated with the same machinery as load)
;; ---------------------------------------------------------------------------

(defn- record-out
  "Tag an embedded record with its kind under logic/dump-discriminator, the
  key the dump uses — one wire vocabulary and one rehydrator for both. Not
  :type, which an entity's wire shape already owns: writing the kind there
  overwrote it, and every entity that crossed machines arrived untyped."
  [kind m]
  (assoc m logic/dump-discriminator (name kind)))

(defn- rehydrate
  "Wire payload -> store shape. The kind comes from the effect's verb rather
  than from inside the payload, because the verb already determines it.

  Dropping a :type that spells the record kind is a format-0 compatibility
  path, and it is gated on the LINE saying format 0 (and on the payload
  carrying no :record of its own). Those lines wrote the kind into :type, so a
  :type reading \"entity\" there is the field having been eaten. From format 1
  on, :record carries the kind and :type is the entity's own — and entity
  types are free-form, so :entity is as legal a type as :service. Stripping it
  because it happens to spell the kind is the untyping bug the discriminator
  moved off :type to stop, reintroduced on the reading side."
  [line kind payload]
  (let [k (name kind)]
    (second (logic/rehydrate-dump-record
             (cond-> (assoc payload logic/dump-discriminator k)
               (and (zero? (:format line 0))
                    (nil? (get payload logic/dump-discriminator))
                    (= k (:type payload)))
               (dissoc :type))))))

(defn- ms-of [d] (some-> ^java.util.Date d .getTime))

(defn- invalidate-line
  "The wire shape of an invalidation: the sentence under :reason, with the kind
  and the successor as its SIBLINGS.

  Nesting the whole {:kind :successor :reason} map under :reason would be the
  obvious encoding and it is the wrong one. A reader from before the kinds
  existed reads :reason and nothing else, so it applies such a line — reporting
  :applied, warning about nothing — and writes a MAP into the field every
  reader treats as prose: its own regex in context.clj, `claim history`, the
  string column the Datalevin schema declares (which coerces it to
  \"{:kind \\\"superseded\\\", …}\" and keeps it forever). The format version
  cannot save that reader either: adding two fields is additive, so the version
  correctly does not move and the gate passes the line straight through.

  Siblings leave that reader RIGHT rather than merely refusing, and leave the
  version bump unspent for a change that genuinely needs one. Absent keys
  rather than nulls for a kind or successor nobody supplied: an old reader and
  a new one both read absent and null the same way, and the line stays as short
  as the effect it describes."
  [fact-id at invalidation]
  (let [{:keys [kind successor reason]} (logic/invalidation invalidation)]
    (cond-> {:t "invalidate" :fact-id fact-id :at at}
      reason (assoc :reason reason)
      kind (assoc :kind kind)
      successor (assoc :successor successor))))

(defn- line-invalidation
  "The invalidation an \"invalidate\" line carries, read back. The structured
  siblings win; a line with only :reason came from a peer that predates them
  and keeps its sentence, which is the bare-string shape store/-invalidate
  documents and logic/invalidation normalizes. The kind arrives from JSON as a
  string and is coerced there, so a replayed supersession is the same keyword a
  locally written one is — a string kind matches no reader's set."
  [line]
  {:kind (:kind line) :successor (:successor line) :reason (:reason line)})

;; ---------------------------------------------------------------------------
;; The logging decorator
;; ---------------------------------------------------------------------------

(defprotocol Logged
  (-inner [s] "The undecorated store, for replay paths that must not re-log."))

(defn inner-store [s]
  (if (satisfies? Logged s) (-inner s) s))

(defn- append!
  "Append one effect line, AFTER the store write it describes returned. The
  log records what happened rather than what was attempted: a mutation that
  threw locally must not reach machines that would apply it happily, and
  logging the store's own return value is how ids and defaults it filled in
  travel with the effect."
  [{:keys [db writer clock counter]} effect]
  (try
    (fs/create-dirs (oplog-dir db))
    ;; Allocated and written under one lock: a number burnt by a failed write
    ;; is a hole in a dense-from-1 sequence, and a hole stalls every reader.
    ;; The lock is per (db, writer) rather than per store handle, and the
    ;; number is checked against the file the moment its size says somebody
    ;; else wrote to it — a seq handed out twice loses an effect silently,
    ;; where a seq skipped at least announces itself as a gap.
    (locking counter
      (let [{:keys [high size]} @counter
            n (inc (if (= size (log-size db writer))
                     high
                     (max high (disk-high-seq db writer))))]
        (spit (str (log-file db writer))
              (str (wire/generate-string
                    (assoc effect :writer writer :seq n :hlc (next-hlc! clock)
                           :format version/format-version))
                   "\n")
              :append true)
        (reset! counter {:high n :size (log-size db writer)})))
    (catch Exception e
      ;; Never blocks the write it records — replication is an overlay, not
      ;; a gate — but never silent either (spec/replication.allium, decided
      ;; 2026-07-26): stderr for the human at the terminal, and a structured
      ;; warning on the write's own report for everything that parses it. A
      ;; full disk otherwise forks the log from the store with no symptom,
      ;; and every peer under-replays this writer while it reports success.
      (warn! "could not append " (:t effect) " to " (str (log-file db writer))
             ": " (ex-message e)
             " — this effect will not reach any other machine")
      (store/push-write-warning!
       {:warning :oplog-append-failed
        :effect (:t effect)
        :log (str (log-file db writer))
        :error (ex-message e)
        :note "this effect will not reach any other machine until the log is writable again"}))))

(defrecord LoggedStore [inner ctx]
  Logged
  (-inner [_] inner)

  store/Store
  (-ensure-entity [_ ent]
    (let [e (store/-ensure-entity inner ent)]
      (append! ctx {:t "ensure-entity" :entity (record-out :entity e)})
      e))
  (-get-entity [_ name scope] (store/-get-entity inner name scope))
  (-find-entities [_ name scope] (store/-find-entities inner name scope))
  (-update-entity [_ id updates]
    (let [r (store/-update-entity inner id updates)]
      (append! ctx {:t "update-entity" :id id :updates updates})
      r))
  (-repoint-facts [_ from to]
    (let [r (store/-repoint-facts inner from to)]
      (append! ctx {:t "repoint-facts" :from from :to to})
      r))
  (-repoint-predicate [_ from to]
    (let [r (store/-repoint-predicate inner from to)]
      (append! ctx {:t "repoint-predicate" :from (str from) :to (str to)})
      r))
  (-delete-entity [_ id]
    (let [r (store/-delete-entity inner id)]
      (append! ctx {:t "delete-entity" :id id})
      r))
  (-list-entities [_ opts] (store/-list-entities inner opts))
  (-insert-fact [_ fact]
    (let [f (store/-insert-fact inner fact)]
      (append! ctx {:t "insert-fact" :fact (record-out :fact (or f fact))})
      f))
  (-get-facts [_ id opts] (store/-get-facts inner id opts))
  (-get-facts-for [_ ids opts] (store/-get-facts-for inner ids opts))
  (-select-facts [_ criteria] (store/-select-facts inner criteria))
  (-predicate-usage [_] (store/-predicate-usage inner))
  (-entity-usage [_] (store/-entity-usage inner))
  (-get-history [_ id pred] (store/-get-history inner id pred))
  (-invalidate [_ fact-id at invalidation]
    (let [r (store/-invalidate inner fact-id at invalidation)]
      (append! ctx (invalidate-line fact-id (ms-of at) invalidation))
      r))
  (-link-conflicts [_ fact-id ids]
    (let [r (store/-link-conflicts inner fact-id ids)]
      (append! ctx {:t "link-conflicts" :fact-id fact-id :ids (vec ids)})
      r))
  (-unlink-conflicts [_ fact-id ids]
    (let [r (store/-unlink-conflicts inner fact-id ids)]
      (append! ctx {:t "unlink-conflicts" :fact-id fact-id :ids (vec ids)})
      r))
  (-reinforce [_ fact-id opts]
    (let [r (store/-reinforce inner fact-id opts)]
      ;; :source-type rides BESIDE the fields peers already read (additive,
      ;; per the payload rule): an older reader reinforces without the
      ;; re-sourcing and loses nothing it understood.
      (append! ctx (cond-> {:t "reinforce" :fact-id fact-id
                            :at (ms-of (:at opts)) :confidence (:confidence opts)}
                     (:source-type opts) (assoc :source-type (:source-type opts))))
      r))
  (-all-facts [_] (store/-all-facts inner))
  (-open-episode [_ ep]
    (let [e (store/-open-episode inner ep)]
      (append! ctx {:t "open-episode" :episode (record-out :episode (or e ep))})
      e))
  (-close-episode [_ id summary at]
    (let [r (store/-close-episode inner id summary at)]
      (append! ctx {:t "close-episode" :id id :summary summary :at (ms-of at)})
      r))
  (-get-episode [_ id] (store/-get-episode inner id))
  (-list-episodes [_] (store/-list-episodes inner))
  (-get-predicate [_ id] (store/-get-predicate inner id))
  (-list-predicates [_ opts] (store/-list-predicates inner opts))
  (-register-predicate [_ pred]
    (let [p (store/-register-predicate inner pred)]
      (append! ctx {:t "register-predicate" :predicate (record-out :predicate (or p pred))})
      p))
  (-search [_ q opts] (store/-search inner q opts))
  (-stats [_] (store/-stats inner))
  (-close [_] (store/-close inner)))

;; ---------------------------------------------------------------------------
;; Reading logs, tracking what's applied
;; ---------------------------------------------------------------------------

(defn- read-log
  "One log file -> {:writer :entries :unreadable}. The filename supplies
  :writer and 0 supplies :format for format-0 lines, which predate both
  fields; a line that will not parse is counted rather than dropped, because
  it leaves a hole in the sequence that the reader has to explain.

  Whatever :writer a line carries becomes text here, because attribution is
  grouped and sorted as text everywhere downstream — a peer whose id arrived
  as a number is still one writer, not a comparison error mid-pass."
  [file]
  (let [fallback (str (fs/strip-ext (fs/file-name file)))]
    (try
      (let [parsed (->> (str/split-lines (slurp (str file)))
                        (remove str/blank?)
                        (mapv #(try (wire/parse-string %) (catch Exception _ nil))))]
        {:file (str file)
         :writer fallback
         :entries (into [] (comp (filter map?)
                                 (map #(-> %
                                           (update :writer (fn [w] (if (some? w) (str w) fallback)))
                                           (update :format (fn [f] (or f 0))))))
                        parsed)
         :unreadable (count (remove map? parsed))})
      (catch Exception e
        {:file (str file) :writer fallback :entries [] :unreadable 0
         :error (ex-message e)}))))

(defn- state-file [db] (str (fs/path (oplog-dir db) "applied.json")))

(def ^:private empty-state
  {:format version/format-version :high-water {} :applied-beyond {}
   :entity-map {} :clock 0 :deferred {} :abandoned {}})

(defn- load-state
  "The reconciler's memory: how far it has read each writer, which effects it
  applied ahead of a hole, how many passes each waiting effect has waited,
  what a human gave up on, the highest clock it has seen, and the
  foreign->local entity map. An unreadable file is reported and treated as
  empty — starting over re-applies foreign effects, and a reconciler that
  does that silently is how a fact gets reinforced twice."
  [db]
  (let [f (state-file db)]
    (if (fs/exists? f)
      (or (try (wire/parse-string (slurp f))
               (catch Exception e
                 (warn! "cannot read " f " (" (ex-message e)
                        ") — treating every foreign effect as unseen")
                 nil))
          empty-state)
      empty-state)))

(defn- save-state!
  "Written whole, then moved into place. A torn applied.json reads as no
  memory at all, and no memory means replaying every foreign effect."
  [db state]
  (fs/create-dirs (oplog-dir db))
  (let [tmp (fs/path (oplog-dir db) (str ".applied-" (random-uuid) ".tmp"))]
    (spit (str tmp) (wire/generate-string (assoc state :format version/format-version)))
    (fs/move tmp (state-file db) {:replace-existing true :atomic-move true})))

;; ---------------------------------------------------------------------------
;; Replay
;; ---------------------------------------------------------------------------

(defn- remap-entity!
  "Foreign entity -> local entity, by name (ids are internal). Cached in the
  persistent entity map so later effects that only carry the foreign id
  still resolve."
  [s emap ent]
  (let [foreign-id (:id ent)]
    (or (some->> (get @emap (keyword foreign-id))
                 (hash-map :id))
        (let [ensure (requiring-resolve 'claimgraph.core/ensure-entity)
              local (ensure s {:name (:name ent) :type (:type ent)
                               :scope (:scope ent)})]
          (swap! emap assoc (keyword foreign-id) (:id local))
          local))))

(defn- fact-exists? [s id]
  (boolean (seq (store/-select-facts s {:ids [id]}))))

(def ^:private settled
  "The outcomes a high-water mark may move past: the effect landed, or it was
  already here. Everything else — a prerequisite that has not arrived, a verb
  this build does not know, a throw — leaves the effect in front of the mark
  to be tried again on the next reconcile."
  #{:applied :duplicate})

(defn- apply-effect!
  "One foreign effect against the raw (unlogged) store. Returns :applied,
  :duplicate (already present), :deferred (a prerequisite this store has not
  seen yet — the effect must stay unapplied and retriable) or :unknown."
  [s emap {:keys [t] :as e}]
  (case t
    "ensure-entity"
    (do (remap-entity! s emap (rehydrate e :entity (:entity e))) :applied)

    "insert-fact"
    (let [f (rehydrate e :fact (:fact e))]
      (if (fact-exists? s (:id f))
        :duplicate
        (do (store/-insert-fact
             s (cond-> (assoc f :subject (remap-entity! s emap (:subject f)))
                 (:object-ref f)
                 (assoc :object-ref (remap-entity! s emap (:object-ref f)))))
            :applied)))

    "update-entity"
    (if-let [local (get @emap (keyword (:id e)))]
      (do (store/-update-entity s local (-> (:updates e)
                                            (update :type logic/->kw)
                                            (->> (into {} (filter (comp some? val))))))
          :applied)
      :deferred)

    "repoint-facts"
    (let [from (get @emap (keyword (:from e)))
          to (get @emap (keyword (:to e)))]
      (if (and from to)
        (do (store/-repoint-facts s from to) :applied)
        :deferred))

    "delete-entity"
    (if-let [local (get @emap (keyword (:id e)))]
      (do (store/-delete-entity s local) :applied)
      :deferred)

    "invalidate"
    (if (fact-exists? s (:fact-id e))
      (do (store/-invalidate s (:fact-id e)
                             (java.util.Date. (long (:at e)))
                             (line-invalidation e))
          :applied)
      :deferred)

    ;; Conflict links go in whole or not at all: half a link set is a claim
    ;; about a graph this machine does not have yet, and -link-conflicts
    ;; appends rather than unions, so a partial apply cannot be repaired by
    ;; repeating it later.
    "link-conflicts"
    (if (and (fact-exists? s (:fact-id e))
             (every? #(fact-exists? s %) (:ids e)))
      (do (store/-link-conflicts s (:fact-id e) (:ids e)) :applied)
      :deferred)

    "unlink-conflicts"
    (if (fact-exists? s (:fact-id e))
      (do (store/-unlink-conflicts s (:fact-id e) (:ids e)) :applied)
      :deferred)

    "reinforce"
    (if (fact-exists? s (:fact-id e))
      (do (store/-reinforce s (:fact-id e)
                            (cond-> {:at (java.util.Date. (long (:at e)))
                                     :confidence (:confidence e)}
                              (:source-type e)
                              (assoc :source-type (logic/->kw (:source-type e)))))
          :applied)
      :deferred)

    "open-episode"
    (let [ep (rehydrate e :episode (:episode e))]
      (if (store/-get-episode s (:id ep))
        :duplicate
        (do (store/-open-episode s ep) :applied)))

    "close-episode"
    (if (store/-get-episode s (:id e))
      (do (store/-close-episode s (:id e) (:summary e)
                                (java.util.Date. (long (:at e))))
          :applied)
      :deferred)

    "register-predicate"
    (do (store/-register-predicate s (rehydrate e :predicate (:predicate e))) :applied)

    "repoint-predicate"
    (do (store/-repoint-predicate s (logic/->kw (:from e)) (logic/->kw (:to e)))
        :applied)

    :unknown))

;; ---------------------------------------------------------------------------
;; Reconcile
;; ---------------------------------------------------------------------------

(defn- absorb
  "Pure: one writer's new [mark applied-beyond] given what settled this pass.
  The mark walks forward only over contiguous numbers and stops dead at the
  first seq that did not settle — that effect is the entire reason the mark
  exists, and stepping over it is how an effect an older build could not
  understand disappears for good. Numbers settled ahead of the hole are kept
  by value so they are never applied a second time on the way to closing it."
  [mark beyond settled-seqs]
  (loop [m (or mark 0)
         done (into (set beyond) settled-seqs)]
    (if (done (inc m))
      (recur (inc m) (disj done (inc m)))
      [m (vec (sort (remove #(<= % m) done)))])))

(defn- gap
  "The seq a writer's mark is waiting on when the file simply does not
  contain it: a truncated, rotated or compacted log, or a line that would not
  parse. Present-but-unapplied is a different story (see :held) and not a
  gap."
  [mark seqs]
  (let [want (inc (or mark 0))]
    (when (and (seq seqs) (not (seqs want)) (some #(> % want) seqs))
      want)))

(def ^:private stuck-after
  "How many reconciles an effect may be deferred before the report stops
  calling it late and starts calling it stuck, and the point :abandon-deferred
  acts from. Three passes is well past the half-synced log deferral exists
  for, and making it the threshold is what keeps a flag typed for one stuck
  effect from dropping another that arrived a second ago."
  3)

(defn reconcile!
  "Apply every foreign effect this store hasn't seen, in canonical order,
  then make the seams visible: collapse claims both writers made
  independently, and count the conflict candidates the judge should look at.
  Takes the RAW store (reconciliation must not re-log foreign effects as
  ours).

  Effects that could not be applied are held, not skipped: the report names
  them and the next reconcile tries them again, so a log from a newer
  claimgraph costs an upgrade rather than the effects it carried.

  opts:
    :abandon-deferred  give up on effects deferred `stuck-after` passes or
                       more, so the mark can move past them. The escape hatch
                       for the one deferral that can never settle on its own:
                       `claim load` applies a dump through the raw store, so
                       nothing it restored ever entered a log, and a later
                       invalidate, reinforce or rename of one of those facts
                       reaches a peer naming an id that peer can never obtain.
                       Without a way out, that one effect pins the writer's
                       mark at 0 and everything behind it waits forever. What
                       was given up is listed in the report and written into
                       applied.json — dropping an effect is allowed to be a
                       decision, never an accident."
  ([s db] (reconcile! s db {}))
  ([s db {:keys [abandon-deferred]}]
   (let [own (writer-id! db)
         state (load-state db)
         _ (when (> (or (:format state) 0) version/format-version)
             (logic/fail "applied.json was written by a newer claimgraph"
                         {:type :unsupported-format
                          :format (:format state) :supported version/format-version
                          :hint "upgrade claimgraph; reconciling with an older reader would replay effects it cannot read"}))
         emap (atom (or (:entity-map state) {}))
         reads (when (fs/exists? (oplog-dir db))
                 (mapv read-log (fs/glob (oplog-dir db) "*.jsonl")))
         ;; Identity comes off the LINE, so our own effects are ours even in a
         ;; file somebody renamed, and a foreign log stays foreign after a copy.
         by-writer (->> (mapcat :entries reads)
                        (remove #(= own (:writer %)))
                        (group-by :writer))
         mark-of #(get-in state [:high-water (keyword %)] 0)
         beyond-of #(set (get-in state [:applied-beyond (keyword %)] []))
         unseen (mapcat (fn [[w entries]]
                          (let [hw (mark-of w) done (beyond-of w)]
                            (remove #(when-let [n (seq-of %)] (or (<= n hw) (done n)))
                                    entries)))
                        by-writer)
         ;; A line whose envelope will not read as numbers is separated out
         ;; before anything sorts or gates on it: it cannot be ordered, applied
         ;; or marked, so the only honest thing to do with it is name it.
         {bad true placeable false} (group-by #(some? (envelope-problem %)) unseen)
         canonical (juxt hlc-of :writer seq-of)
         keyed (group-by (juxt :writer seq-of) placeable)
         ;; One effect per (writer, seq). The same log under two names is one
         ;; writer's history, not two, and reinforce is not idempotent — but
         ;; two DIFFERENT effects under one number are a forked log, and
         ;; collapsing those silently destroys one of them.
         forks (->> keyed
                    (keep (fn [[[w n] es]]
                            (let [variants (distinct es)]
                              (when (next variants)
                                {:kind :duplicate-seq :writer w :seq n
                                 :count (count variants)
                                 :verbs (vec (sort (distinct (keep :t variants))))
                                 :note "two different effects share one seq; a mark can record only one, so the first in canonical order applies and the rest do not"}))))
                    (sort-by (juxt :writer :seq))
                    vec)
         pending (->> (vals keyed)
                      (map #(first (sort-by canonical (distinct %))))
                      (sort-by canonical)
                      vec)
         ;; The format gate lives INSIDE the catch with the apply: gating is
         ;; reading a foreign field too, and one peer's odd line must cost that
         ;; line, not every other writer's effects in the same pass.
         results (mapv (fn [e]
                         (try
                           (if (> (:format e 0) version/format-version)
                             [:future-format e]
                             [(apply-effect! s emap e) e])
                           (catch Exception ex
                             [:error (assoc e :error (ex-message ex))])))
                       pending)
         outcome-of (into {} (map (fn [[o e]] [[(:writer e) (seq-of e)] o])) results)
         ;; Waiting is counted across passes, because "deferred" alone cannot
         ;; tell a log that is still syncing from one that never will.
         waited (into {} (keep (fn [[o e]]
                                 (when (= :deferred o)
                                   (let [k [(:writer e) (seq-of e)]]
                                     [k (inc (get-in state [:deferred (keyword (first k))
                                                            (keyword (str (second k)))]
                                                     0))]))))
                      results)
         abandoning (if abandon-deferred
                      (->> results
                           (keep (fn [[o e]]
                                   (let [k [(:writer e) (seq-of e)]]
                                     (when (and (= :deferred o) (>= (waited k 0) stuck-after))
                                       {:writer (:writer e) :seq (seq-of e) :t (:t e)}))))
                           (sort-by (juxt :writer :seq))
                           vec)
                      [])
         abandoned-keys (into #{} (map (juxt :writer :seq)) abandoning)
         absorbed (into {} (map (fn [[w entries]]
                                  [w (absorb (mark-of w) (beyond-of w)
                                             (keep #(when-let [n (seq-of %)]
                                                      (when (or (settled (outcome-of [w n]))
                                                                (abandoned-keys [w n]))
                                                        n))
                                                   entries))]))
                        by-writer)
         held (remove (fn [[o e]] (or (settled o)
                                      (abandoned-keys [(:writer e) (seq-of e)])))
                      results)
         blockers (->> held
                       (group-by #(:writer (second %)))
                       (map (fn [[w hs]]
                              (let [[o e] (apply min-key #(seq-of (second %)) hs)]
                                (cond-> {:kind :held :writer w :seq (seq-of e)
                                         :t (:t e) :why o}
                                  (= :deferred o) (assoc :passes (waited [w (seq-of e)]))))))
                       (sort-by :writer))
         stuck (filterv #(>= (:passes % 0) stuck-after) blockers)
         bad-lines (->> bad
                        (group-by (juxt :writer envelope-problem))
                        (map (fn [[[w why] es]]
                               {:kind :bad-envelope :writer w :why why :count (count es)
                                :note "this line cannot be placed in its writer's sequence: not ordered, not applied, not marked, and reported again every pass until the log is repaired"}))
                        (sort-by (juxt :writer :why))
                        vec)
         gaps (keep (fn [[w entries]]
                      (when-let [n (gap (first (absorbed w))
                                        (into #{} (keep seq-of) entries))]
                        {:kind :seq-gap :writer w :missing n
                         :note "log truncated, rotated or corrupt; effects after the hole stay unapplied"}))
                    by-writer)
         unreadable (keep (fn [{:keys [file unreadable error]}]
                            (when (or error (pos? (or unreadable 0)))
                              (cond-> {:kind :unreadable :file file}
                                (pos? (or unreadable 0)) (assoc :lines unreadable)
                                error (assoc :error error))))
                          reads)
         now (java.util.Date.)
         touched (->> results
                      (keep (fn [[o e]]
                              (when (and (= :applied o) (= "insert-fact" (:t e)))
                                (get @emap (keyword (get-in e [:fact :subject :id]))))))
                      distinct
                      vec)
         ;; The whole plan, survivor included: which twin outlived the collapse
         ;; is knowable only inside this grouping, and a row retired against no
         ;; counterpart reads as deleted for no reason a year later. The kind
         ;; rides along for the same reason — a nil kind here is
         ;; indistinguishable from a write by a build that predates the kinds.
         dups (vec (mapcat (fn [subj]
                             (logic/collapse-duplicates
                              (store/-get-facts s subj {:direction :out}) now))
                           touched))
         _ (doseq [{:keys [id survivor]} dups]
             (store/-invalidate s id now
                                {:kind :reconcile-duplicate
                                 :successor survivor
                                 :reason "duplicate across writers (reconcile)"}))
         preds-by-id (into {} (map (juxt :id identity)) (store/-list-predicates s {}))
         candidates (logic/conflict-candidates
                     (store/-select-facts s {:valid-cheap true}) preds-by-id now)
         observed-clock (reduce max (or (:clock state) 0)
                                (map hlc-of (mapcat :entries reads)))
         counted (frequencies (map first results))
         unknown (->> results
                      (filter #(#{:unknown :future-format} (first %)))
                      (group-by (fn [[o e]] [(:writer e) (:t e) o]))
                      (mapv (fn [[[w t o] es]]
                              {:writer w :t t :count (count es)
                               :from-seq (apply min (map #(seq-of (second %)) es))
                               :why o}))
                      (sort-by (juxt :writer :t))
                      vec)
         warnings (vec (concat unreadable bad-lines forks gaps blockers))]
     (save-state! db {:high-water (reduce (fn [hw [w [m _]]] (assoc hw (keyword w) m))
                                          (or (:high-water state) {}) absorbed)
                      :applied-beyond (reduce (fn [ab [w [_ b]]] (assoc ab (keyword w) b))
                                              (or (:applied-beyond state) {}) absorbed)
                      ;; Rebuilt from this pass, so an effect that landed or was
                      ;; given up stops being counted as waiting.
                      :deferred (reduce (fn [m [[w n] passes]]
                                          (if (abandoned-keys [w n])
                                            m
                                            (assoc-in m [(keyword w) (keyword (str n))] passes)))
                                        {} waited)
                      :abandoned (reduce (fn [m a]
                                           (update m (keyword (:writer a))
                                                   (fnil conj []) (select-keys a [:seq :t])))
                                         (or (:abandoned state) {}) abandoning)
                      :clock observed-clock
                      :entity-map @emap})
     ;; Every clock we just read is a floor under everything this machine
     ;; appends from now on — the merge half of the hybrid logical clock.
     (clock-for db observed-clock)
     {:status :reconciled
      :format version/format-version
      :writers (vec (sort (keys by-writer)))
      :effects {:total (count pending)
                :applied (get counted :applied 0)
                :duplicate (get counted :duplicate 0)
                :deferred (get counted :deferred 0)
                :unknown unknown
                :errors (vec (keep #(when (= :error (first %)) (second %)) results))}
      :held (count held)
      :abandoned abandoning
      :warnings warnings
      :duplicates-collapsed (count dups)
      :sweep-candidates (count candidates)
      :hint (cond
              (seq unknown)
              (str "these logs carry effects this claimgraph does not understand (format "
                   version/format-version " reader); upgrade and reconcile again — nothing was skipped past")

              (seq bad-lines)
              (str (reduce + (map :count bad-lines))
                   " line(s) carry an envelope this reader cannot place (see :warnings): they are"
                   " named there and applied nowhere. A log gets that way by being hand-edited,"
                   " truncated mid-write, or not being a claimgraph log at all")

              (seq stuck)
              (let [b (first stuck)]
                (str "writer " (:writer b) "'s effect #" (:seq b) " (" (:t b)
                     ") has been deferred " (:passes b)
                     " passes: it needs a record that never entered a log — `claim load` applies a"
                     " dump straight to the store — so more syncing will not settle it. Bring that"
                     " machine's dump over, or reconcile with :abandon-deferred to give up on it"
                     " and let the mark move"))

              (pos? (get counted :deferred 0))
              "some effects are waiting on effects that haven't arrived; sync the rest of that writer's log and reconcile again"

              (pos? (count candidates))
              "run `claim judge --sweep` (or consolidate) to judge what the writers couldn't see")})))

;; ---------------------------------------------------------------------------
;; Opening a log
;; ---------------------------------------------------------------------------

(defn- own-log
  "This writer's own lines. Lines belonging to anyone else are ignored even
  in our own file — a restored or copied log is somebody else's history
  sitting under our name, and continuing its numbering would forge it."
  [db writer]
  (filterv #(= writer (:writer %)) (:entries (read-log (log-file db writer)))))

(defn- resume-seq
  "Where a reopened log's numbering continues: the highest seq it ever held,
  never the line COUNT. Counting lines restarts at 1 the moment a log is
  truncated, rotated or compacted, and every remote high-water mark then
  suppresses the renumbered effects for good. A sequence that is no longer
  dense from 1 says one of those things happened, so say it out loud."
  [entries]
  (let [seqs (into #{} (keep seq-of) entries)
        high (reduce max 0 seqs)]
    (when (not= seqs (set (range 1 (inc high))))
      (warn! "log holds " (count entries) " lines numbered up to " high
             " but seq must be dense from 1: it was truncated, rotated or"
             " compacted. Continuing at " (inc high)
             " — machines that never saw the missing numbers will stall there."))
    high))

(defn logged-store
  "Wrap a store so its mutations append to this writer's log."
  [inner db]
  (let [writer (writer-id! db)
        own (own-log db writer)]
    (->LoggedStore inner {:db db
                          :writer writer
                          :counter (counter-for db writer (resume-seq own)
                                                (log-size db writer))
                          :clock (clock-for db (max (reduce max 0 (keep hlc-of own))
                                                    (or (:clock (load-state db)) 0)))})))
