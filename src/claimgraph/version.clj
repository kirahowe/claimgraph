(ns claimgraph.version
  "claimgraph's two version numbers, kept apart because they answer different
  questions and move on different clocks.

  `release` answers \"what did this user run?\" — the number a bug report
  quotes, the number `claim version` prints. It moves whenever anything ships,
  including changes that touch no persisted byte.

  `format-version` answers \"can this reader make sense of these bytes?\" — one
  integer stamped into the artifacts a second machine has to read: the dump's
  header record, every oplog line and the applied-state file beside them, the
  store's own <db>.version sibling, and :config-version in the project config
  file. (The store SCHEMA does not carry it: Datalevin merges a schema on
  open, so a stamp living in the store proper would be written by the very
  call that was supposed to check it — hence the sibling file. See
  store.datalevin/open-store.) Purely local artifacts are deliberately
  unstamped: the lease file and the retrieval log never leave the machine that
  wrote them, and stamping them would promise a compatibility check nothing
  performs.

  format-version moves ONLY when an old reader would get a NEW file wrong: a
  field whose meaning changed, a record type it cannot skip safely, an
  encoding it would misparse. Additive changes an old reader can ignore leave
  it where it is. Bumping it is a promise to write the migration; bumping it
  for a cosmetic change spends that promise for nothing. That rule governs the
  moves AFTER the first one — 1 is not a bump, it is where stamping starts
  (see format-version).

  They are separate so the loader never needs a matrix of release numbers: it
  compares one integer, and claimgraph 0.9 reads a 0.1 dump because the FORMAT
  didn't move, not because someone enumerated the versions in between.")

(def release
  "The product version. Alpha: the persisted formats are stamped but not yet
  frozen, and the migration promise starts at 1.0."
  "0.1.0-alpha")

(def format-version
  "The persisted-format version every portable artifact stamps. 1 is the first
  stamped format, not a bump: dumps lead with a header record, oplog lines and
  the store's <db>.version sibling carry the same integer, and a reader that
  finds one knows what it is holding before it parses a byte of payload.

  Format 0 is not an older format anyone designed — it is everything written
  before stamping existed, pre-alpha, and it is REFUSED rather than loaded.
  Not out of strictness: those artifacts were written when the record
  discriminator was :type, the same key an entity's own :type occupies
  (logic/dump-discriminator), so their entity types were destroyed at write
  time and no reader can recover them. Loading one silently restores untyped
  entities and reports success, which is the corruption the stamp exists to
  make impossible. Re-dump from the store that wrote it instead."
  1)

(def dump-type
  "The header's kind. Deliberately unlike the kinds that follow it
  (predicate/entity/episode/fact), so a reader can tell a claimgraph dump from
  any other JSONL file before it commits to parsing one."
  "claimgraph-dump")

(defn dump-header
  "The first line of every dump, exactly:

      {\"record\":\"claimgraph-dump\",\"format\":1,\"version\":\"0.1.0-alpha\"}

    :record   always the literal \"claimgraph-dump\" — identification. The same
              key every graph record stamps its kind under
              (logic/dump-discriminator), so one key answers \"what is this
              line?\" for every line in the file; a header keyed differently
              from the records reads as a second, competing convention, and
              `group-by` over the file silently buckets it under nil
    :format   the persisted-format version, an integer; what a loader gates on
    :version  the release that wrote the file; provenance for a bug report,
              never a compatibility gate (a loader that refuses on this string
              breaks every future release for no reason)

  Nothing machine-specific and no timestamp: two dumps of an unchanged store
  are byte-identical, which is what makes a committed dump diff to nothing.

  For readers (enforced in claimgraph.core/load-dump), two refusals:

  A :format ABOVE the reader's own format-version. A loader that guesses at a
  shape it was never taught half-restores a graph and calls it success. The
  error names both integers, because \"upgrade claimgraph\" is the whole remedy.

  A first line that is not this header. There is no header to miss in a
  claimgraph dump this build wrote, so the file is one of two things, and
  which one it is decides the remedy — hence the two errors:
    - a pre-alpha dump, recognisable by records keyed :type rather than
      :record. Refused, and not for tidiness: :type is where an entity's own
      type lives, so the kinds in that file overwrote the types (see
      format-version). The remedy is to re-dump from the source store; there
      is nothing in the file to salvage.
    - not a claimgraph dump at all — no :record, no recognisable kind. The
      remedy is a different path, and a reader that answers this with
      \"pre-alpha dump\" sends someone hunting for a store they never had."
  []
  ;; :record spelled literally, not read from logic/dump-discriminator: this
  ;; namespace is a leaf on purpose, and constants describing the format should
  ;; not drag in the core that implements it. A test pins the two together.
  {:record dump-type :format format-version :version release})

(defn header?
  "Is this parsed dump record (keyword keys, as claimgraph.wire/parse-string
  returns them) the header rather than a graph record?"
  [record]
  (= dump-type (:record record)))

(defn describe
  "The `claim version` payload. The sha is the caller's to supply — locating
  and interrogating a git checkout is a shell concern — and the key is ABSENT
  (not nil, not \"unknown\") when claimgraph runs from anything but a checkout,
  so a report that carries a sha carries a real one.

  :dirty rides beside it, and only when it is true, because a sha off a
  modified tree names code nobody else has. Absent means the checkout was
  clean at the moment it was asked; there is no third state to encode, since a
  caller that cannot tell reports dirty (cli/source-checkout)."
  ([sha] (describe sha false))
  ([sha dirty]
   (cond-> {:version release :format format-version}
     sha (assoc :sha sha)
     (and sha dirty) (assoc :dirty true))))
