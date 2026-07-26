(ns claimgraph.wire
  "The one place that knows how claimgraph turns values into JSON and back.

  Everything that carries a store timestamp or crosses a machine boundary
  goes through here: the dump, every oplog line and the applied-state file
  beside them, the store's <db>.version stamp, and both command surfaces —
  the CLI's stdout (cli/emit) and the MCP tools' content (mcp/handle). Those
  describe the same facts to the same agent, so an encoding that drifts
  between any two of them turns a diff into a false conflict.

  What does NOT come through here, and why the list is short enough to name:
  .claimgraph/config.json and .mcp.json (config, setup), the harness's own
  settings.json (hooks), <db>.lock (lease), <db>.retrievals (outcome), and
  the JSON exchanged with an LLM or an analyzer subprocess (judge,
  consolidate, ingest.*). None of them holds a java.util.Date — the two
  local logs keep epoch millis, the rest keep no time at all — and none of
  them is read by another machine, so the date encoder they would inherit
  from cheshire has nothing to corrupt. Anything that acquires a Date, or a
  second reader, belongs on the list above instead.

  What it exists to prevent: cheshire's default java.util.Date encoder writes
  yyyy-MM-dd'T'HH:mm:ss'Z' and throws the milliseconds away. In a bi-temporal
  store that is silent corruption, not a rounding nicety — a fact asserted and
  superseded inside the same second comes back with t-valid = t-invalid, an
  interval valid at no instant at all, so the fact disappears from every
  as-of view instead of showing up as the short-lived claim it was; and
  recorded-at ties stop ordering deterministically across a round trip.

  Milliseconds, still ISO-8601. Epoch integers would round-trip just as
  exactly and cost less, but a dump is a committed artifact that people read
  and diff, and \"inspectable\" is worth more here than four bytes a line.
  Reading is unchanged either way: logic/parse-instant goes through
  Instant/parse, which accepts both widths, so the second-granularity
  artifacts that are still read at all — format-0 oplog lines — parse
  without a special case. (A format-0 DUMP is refused, and not over its
  timestamps; see claimgraph.version/format-version.)

  The dump's header record — the first line of every dump, and the shape a
  loader implements against — is documented at claimgraph.version/dump-header."
  (:require [cheshire.core :as json]
            [claimgraph.version :as version]))

(def ^:private date-format
  "ISO-8601 with milliseconds. Cheshire pins the zone to UTC itself, so the
  trailing Z is the truth on any machine."
  "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

(defn generate-string
  "Cheshire's, with claimgraph's date encoding — a drop-in at every call site,
  which is the point: a call site that reaches for cheshire directly is how
  the encoding drifts back apart."
  ([data] (generate-string data nil))
  ([data opts] (json/generate-string data (assoc opts :date-format date-format))))

(defn parse-string
  "Parse with keyword keys, the only way claimgraph ever wants JSON back:
  dump records, oplog lines, harness hook payloads and ingest JSONL all feed
  keyword-keyed maps to logic. Dates stay strings — rehydrating them is
  logic/rehydrate-dump-record's job, because which fields are dates is a
  schema question, not an encoding one."
  [s]
  (json/parse-string s true))

(defn dump-lines
  "A dump as newline-free strings: the header first, so a reader knows what
  file it has before it has parsed a single graph record, then one record per
  line in the order core/dump produced them."
  [records]
  (cons (generate-string (version/dump-header))
        (map generate-string records)))
