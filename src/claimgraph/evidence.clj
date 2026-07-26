(ns claimgraph.evidence
  "The raw-evidence tier (TierMem's argument, review §3.1): extraction
  decides what to keep before knowing what a future query will hinge on, so
  the raw input is kept too — as immutable, content-addressed artifacts next
  to the store, pointed to by the episode they were extracted under.
  Provenance upgrades from \"which session\" to \"the exact bytes\", and
  nothing an extractor drops is unrecoverable.

  Artifacts are write-once by construction: the name is minted from the
  sha-256 of the content and nothing else (the exact spelling is the naming
  rule below), so re-ingesting identical content is a no-op and nothing can
  be edited in place. The tier is a local fallback (notes-as-primary,
  transcripts-as-fallback); artifacts do not ride the dump — the graph
  remains the portable artifact, evidence the local audit trail.

  Names carry their algorithm: `sha256-<hex>`. A pointer that cannot say
  which function produced it cannot be told apart from the next function's
  output, and the pointer is already in stores and dumps — so the tag goes
  in now, while it is one `str`. The separator is a hyphen and not the
  conventional colon because this string is a FILENAME before it is an
  identifier, and a colon is awkward on unix and illegal on Windows.
  Pointers written before the tag are bare 64-hex; `fetch` still reads
  them, `write!` never produces them again.

  Two known limits, deferred for the alpha rather than missed:

  - Content is TEXT ONLY. `write!` spits and `fetch` slurps, and the digest
    is over `(.getBytes (str content) \"UTF-8\")` — hand this tier binary
    evidence (an image, a gzipped log) and it comes back corrupted. Nothing
    validates that today; ingest paths only ever pass transcripts and notes.
  - Artifacts land in ONE flat directory, which the filesystem stops
    enjoying somewhere in the tens of thousands. The usual fix is a
    two-character fanout (ab/cdef…); it is a relocation of existing names
    rather than a format change, so it can wait for a store that is big
    enough to need it."
  (:require [babashka.fs :as fs]))

(defn default-dir
  "Evidence lives next to the store: <db>.evidence/"
  [db-path]
  (str db-path ".evidence"))

(def ^:private pointer-re
  ;; Both accepted forms in one place: the tagged name written now, and the
  ;; bare hex written before the tag existed. Matching is also what keeps a
  ;; pointer honest as a filename — `..`, separators and absolute paths fail
  ;; here rather than reaching the filesystem.
  #"(?:sha256-)?([0-9a-f]{64})")

(defn- digest-hex
  "The sha-256 hex inside a pointer of either form, or nil for a string that
  is not an evidence pointer at all."
  [pointer]
  (second (re-matches pointer-re (str pointer))))

(defn content-hash
  "The artifact's identity: `sha256-` + the full sha-256 hex of the content.
  Tagged, not bare — this is the single place the identity is minted, and a
  caller holding the untagged form would silently disagree with what the
  episode's :evidence points at."
  [content]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str content) "UTF-8"))]
    (str "sha256-" (apply str (map #(format "%02x" %) d)))))

(defn write!
  "Store content as an immutable artifact; returns its tagged name.
  Idempotent, including on stores upgraded in place: content already on
  disk under EITHER name is already stored, so re-ingesting a pre-tag
  store's transcripts does not leave two identical copies. The TAGGED name
  comes back regardless — it is the identity going forward and `fetch`
  resolves both spellings, so a pointer landing on legacy bytes still
  reads; the alternative is new episodes spelling their pointer according
  to when the store was first written, which is what the tag exists to end.

  Presence is `fs/regular-file?`, the same question `fetch` asks: a
  directory sitting on the name is not an artifact, so the `spit` throws
  rather than reporting a pointer to nothing as stored."
  [dir content]
  (let [hash (content-hash content)
        stored? (fn [filename] (fs/regular-file? (fs/path dir filename)))]
    (when-not (some stored? [hash (digest-hex hash)])
      (fs/create-dirs dir)
      (spit (str (fs/path dir hash)) (str content)))
    hash))

(defn fetch
  "The raw bytes behind an evidence pointer, or nil when the artifact isn't
  present on this machine (evidence is local; the graph survives without
  it). A pointer of either form is looked up under both names: a store
  upgraded in place still holds bare-hex pointers to bytes a later ingest
  may have re-landed under the tagged name, and the bytes are the same
  bytes either way."
  [dir hash]
  (when-let [hex (digest-hex hash)]
    (some (fn [filename]
            (let [path (fs/path (str dir) filename)]
              (when (fs/regular-file? path)
                (slurp (str path)))))
          [(str "sha256-" hex) hex])))
