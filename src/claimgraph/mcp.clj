(ns claimgraph.mcp
  "The MCP front-end (roadmap #27): a thin second surface over
  claimgraph.core, trigger-gated on the latency data from #11 and finally
  tripped by the ambient loop's hook cadence — the CLI pays ~350ms of bb +
  pod cold start per invocation, the server pays it once per session.

  Speaks MCP's stdio transport: newline-delimited JSON-RPC 2.0. The handler
  is pure (request map + store -> response map); only serve! touches IO.
  Reads feed the outcome signal exactly like the CLI; the one write tool
  takes the write lease per call, so CLI and MCP writers stay serialized.

  Tool names and argument names are a wired contract in a way CLI flags are
  not: a flag is typed by a person who can read the new help, an MCP argument
  is baked into a client that ships and keeps calling. So the surface below
  only ever grows — no name here is renamed or dropped, capability arrives as
  new arguments and new tools — and each tool is `memory_` plus its CLI verb
  (memory_facts / claim facts, memory_neighbor / claim neighbor), so there is
  one rule to remember rather than a list that grows.

  Argument names are accepted in both spellings. MCP's convention is
  snake_case and claimgraph's is kebab — every CLI flag, every key in every
  result, every line of the dump — which left a client writing min_confidence
  into the same tool it read effective-confidence out of, and a client that
  guessed kebab silently ignored. Normalising on the way in keeps the
  snake_case callers that already exist and makes the guess unpunished.
  Outputs stay kebab and unnormalised: they are the same records `claim
  facts` prints and `claim dump` commits, and a second spelling of a fact is
  a second thing to diff.

  Encoding is claimgraph.wire's, not cheshire's, for the same reason the CLI's
  is: this is a SECOND surface onto the same facts, and an agent that reads a
  fact here and diffs it against the committed dump (or against the CLI's
  answer) must not find a different :recorded-at because cheshire's default
  date encoder dropped the milliseconds on one of the two paths.

  Errors use both of MCP's channels, on purpose rather than by accident. A
  failure to DISPATCH — a tool name this server does not have — is a JSON-RPC
  error: nothing ran, there is no result to describe, and what is broken is
  the client's wiring, which no amount of prompting will fix. A failure
  INSIDE a tool is an ordinary result carrying isError and the CLI's exact
  error payload — the message under :error, plus the :type, the :hint and
  whatever else the failure attached. That costs the client a second parse,
  because content[0].text is a JSON string nested in a JSON result and MCP's
  content model gives us no other slot; what it buys is that the model
  driving the tool sees the same hint a human would have seen on stderr and
  can usually repair its own call, where a transport-level error is something
  the harness reports as a tool malfunction and the model never reads.

  Wire it up:  claude mcp add claimgraph -- bin/claim mcp
  (or any MCP client; --db as usual)."
  (:require [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(def protocol-version "2024-11-05")

;; ---------------------------------------------------------------------------
;; Tools
;;
;; Schemas advertise snake_case (MCP's convention, and what shipped); kebab is
;; accepted too — see call-tool. Descriptions carry the defaults, because an
;; agent choosing arguments reads these and nothing else.
;; ---------------------------------------------------------------------------

(def tool-defs
  [{:name "memory_facts"
    :description "Facts about an entity in the project knowledge graph. Supports time travel (as_of), reverse lookups (direction=in), and the superseded past (include_invalidated)."
    :inputSchema {:type "object"
                  :properties {:entity {:type "string"}
                               :entity_scope {:type "string" :description "Scope to resolve the entity name in; default \"project\""}
                               :predicate {:type "string"}
                               :direction {:type "string" :enum ["out" "in" "both"]}
                               :as_of {:type "string" :description "ISO date/instant"}
                               :scope {:type "string" :description "Keep only facts recorded in this scope"}
                               :include_invalidated {:type "boolean" :description "Include facts whose validity interval has closed"}
                               :min_confidence {:type "number" :description "Filters on effective (disuse-decayed) confidence"}}
                  :required ["entity"]}}
   {:name "memory_neighbor"
    :description "Graph expansion from an entity: fixed-depth BFS by default, or — when query is given — an evidence-guided walk that follows only the edges resembling the query. Both answer in one shape: root, entities with hop depth, facts."
    :inputSchema {:type "object"
                  :properties {:entity {:type "string"}
                               :entity_scope {:type "string"}
                               :depth {:type "integer" :description "BFS hops; default 1"}
                               :predicate {:type "string" :description "Traverse only this predicate"}
                               :scope {:type "string"}
                               :as_of {:type "string" :description "ISO date/instant"}
                               :min_confidence {:type "number"}
                               :query {:type "string" :description "Switches BFS for the guided walk, which adds a walk-score per fact. Omit it (or leave it empty) for BFS"}
                               :budget {:type "integer" :description "Guided walk: fact budget; default 25"}
                               :beam {:type "integer" :description "Guided walk: edges kept per round; default 8"}}
                  :required ["entity"]}}
   {:name "memory_search"
    :description "Hybrid retrieval over the graph: full-text + entity resolution + neighborhood, ranked by consensus and effective confidence."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}}
                  :required ["query"]}}
   {:name "memory_recall"
    :description "Sufficiency escalation: answer from graph facts, then episode summaries, then raw evidence pages; says which tier answered."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}
                               :min_hits {:type "integer" :description "Hits a tier must return to count as sufficient; default 1. Raise it to force escalation past a thin graph answer."}}
                  :required ["query"]}}
   {:name "memory_history"
    :description "All versions of (subject, predicate), valid and superseded — what did we believe, and when did it change."
    :inputSchema {:type "object"
                  :properties {:subject {:type "string"}
                               :subject_scope {:type "string" :description "Scope to resolve the subject name in; default \"project\""}
                               :predicate {:type "string"}}
                  :required ["subject" "predicate"]}}
   {:name "memory_conflicts"
    :description "Open conflicts awaiting a human: contradicted commitments, revenants, disputed values."
    :inputSchema {:type "object" :properties {}}}
   {:name "memory_coach"
    :description "Gated push: given a task description, returns standing decisions, known failure modes, and open conflicts that bear on it — or push=false when nothing does."
    :inputSchema {:type "object"
                  :properties {:task {:type "string"}}
                  :required ["task"]}}
   {:name "memory_assert"
    :description "Record a fact through full validation and conflict resolution. Use class=commitment for human decisions (never silently overwritten). Valid time is first-class: valid_from/valid_until record when the fact was TRUE, not when it was recorded, so a closed past interval (\"true January through March\") is one call."
    :inputSchema {:type "object"
                  :properties {:subject {:type "string"}
                               :subject_type {:type "string" :description "Type for the subject entity when this call creates it"}
                               :subject_scope {:type "string" :description "Scope to resolve or create the subject entity in; defaults to scope"}
                               :predicate {:type "string"}
                               :object {:type "string"}
                               :object_type {:type "string" :description "Type for the object entity when this call creates it"}
                               :object_scope {:type "string" :description "Scope to resolve or create the object entity in; defaults to scope"}
                               :object_kind {:type "string" :enum ["entity" "literal"]}
                               :class {:type "string" :enum ["observation" "commitment" "preference"]}
                               :scope {:type "string" :description "Scope for the whole write — the fact and the entities it creates; default \"project\". Read it back with entity_scope=<scope> on memory_facts"}
                               :confidence {:type "number"}
                               :source_type {:type "string" :description "Drives trust rank and the confidence ceiling; default user-assertion"}
                               :episode {:type "string" :description "Episode id to attribute this fact to"}
                               :on_conflict {:type "string" :enum ["supersede" "flag" "ignore"]
                                             :description "Overrides the class default (commitments flag, everything else supersedes)"}
                               :valid_from {:type "string" :description "ISO date/instant the fact became true; default now"}
                               :valid_until {:type "string" :description "ISO date/instant it stopped being true; default open-ended"}}
                  :required ["subject" "predicate" "object"]}}])

(def ^:private tool-names (into #{} (map :name) tool-defs))

;; ---------------------------------------------------------------------------
;; Arguments
;;
;; A tool call arrives as whatever JSON the model wrote against the schema
;; above, which is not the same thing as whatever the schema says. The three
;; normalisations here are the ones a client cannot do for itself and core
;; should not have to: spelling (see the ns docstring), blanks, and numbers.
;; ---------------------------------------------------------------------------

(def ^:private numeric-args
  "Every argument read as a number, and which kind — the coercions the CLI's
  dispatch spec declares for the matching flags, on the surface that had none.
  babashka.cli coerces because a command line is all strings; MCP arrives as
  typed JSON and nothing coerced it, so a model that wrote \"0.5\" into a
  number property reached core with a String and got back a bare
  ClassCastException: no :type, no :hint, nothing to repair the call from, on
  the mistake a model filling a schema with prose makes most often."
  {:min-confidence :double
   :confidence :double
   :depth :long
   :budget :long
   :beam :long
   :min-hits :long})

(defn- coerce-number
  "A numeric argument as a number, or a claimgraph error naming the argument."
  [k kind v]
  (let [whole? (= :long kind)
        expected (if whole? "integer" "number")
        parsed (if (number? v)
                 (double v)
                 (try (Double/parseDouble (str/trim (str v))) (catch Exception _ nil)))
        ;; an integer argument takes 2 and "2", not 2.5: silently flooring a
        ;; depth changes the answer, and a value out of long's range is the
        ;; same unusable input as "two"
        n (when parsed
            (try (if whole?
                   (when (== parsed (Math/rint parsed)) (long parsed))
                   parsed)
                 (catch Exception _ nil)))]
    (if (some? n)
      n
      (logic/fail (str "Argument " (name k) " must be " (if whole? "an" "a") " "
                       expected ": " (pr-str v))
                  {:type :invalid-argument
                   :argument (name k)
                   :value v
                   :expected expected
                   :hint (str "pass " (name k) " as a JSON " expected
                              ", not a string")}))))

(defn- read-args
  "Client JSON -> the argument map every tool branch reads.

  Blank means absent, deliberately. A model that has no value for a property
  writes \"\" about as readily as it omits the property — clients that fill in
  every advertised argument exist — and a blank is a value nowhere on this
  surface: an empty query selected the guided walk and silently discarded the
  depth the same call asked for, an empty entity_scope resolved names in a
  scope called \"\". Dropping blanks also hands the missing-argument checks a
  single question to ask."
  [raw]
  (into {}
        (keep (fn [[k v]]
                (when-not (str/blank? (str v))
                  [k (if-let [kind (numeric-args k)]
                       (coerce-number k kind v)
                       v)])))
        (logic/normalize-keys raw)))

(defn- require-query
  "The query guard cmd-search, cmd-recall and cmd-coach each run.

  core is happy to search for the empty string, so these three answered a call
  with no query at all successfully — \"nothing found\", which tells the model
  its call was fine and the graph is empty. Every other tool's required
  arguments are checked by core, which fails with a type of its own. :type
  matches the CLI's for the same failure; the message names the argument THIS
  surface advertises, since a client cannot pass --hook or a positional."
  [a k msg]
  (let [v (str (get a k))]
    (when (str/blank? v)
      (logic/fail msg {:type :missing-query}))
    v))

(defn- log-reads! [db verb facts]
  (try ((requiring-resolve 'claimgraph.outcome/log-reads!)
        db verb (keep :id facts))
       (catch Exception _ nil)))

(defn call-tool
  "Dispatch one tool call. Returns the result data (to be JSON-encoded).

  Arguments go through read-args first, so each branch names one spelling
  however the client spelled it, and every option below reaches the same core
  function the matching CLI command reaches."
  [s db tool raw-args]
  (let [a (read-args raw-args)]
    (case tool
      "memory_facts"
      (let [r (core/get-facts s (assoc (select-keys a [:entity :entity-scope :direction :predicate
                                                       :scope :include-invalidated :min-confidence])
                                       :as-of (logic/parse-instant (:as-of a))))]
        (log-reads! db :facts (:facts r))
        r)

      ;; no log-reads!: `claim neighbor` does not feed the outcome signal
      ;; either, and a surface that logged retrievals the other one didn't
      ;; would make `outcome accepted` reinforce a different set of facts
      ;; depending on which front-end asked
      "memory_neighbor"
      (if (:query a)
        ;; the CLI's own wrapper, resolved rather than reimplemented: `claim
        ;; neighbor --query` had to grow the BFS's :entities and :depth onto
        ;; the walk so that one verb has one shape and a caller can write one
        ;; reader for it, and a walk that arrives here missing the keys the
        ;; same tool returned a moment ago is that defect again, on the surface
        ;; where the reader is a wired client rather than a person
        ((requiring-resolve 'claimgraph.cli/walk-neighborhood)
         (core/guided-walk s (select-keys a [:entity :entity-scope :query :budget :beam]))
         (core/now))
        (core/get-neighborhood s (assoc (select-keys a [:entity :entity-scope :depth :predicate
                                                        :scope :min-confidence])
                                        :as-of (logic/parse-instant (:as-of a)))))

      "memory_search"
      (let [r (core/search s (require-query a :query "search requires a query") {})]
        (log-reads! db :search (:facts r))
        r)

      "memory_recall"
      (let [r (core/recall s (require-query a :query "recall requires a query")
                           {:min-hits (:min-hits a)
                            :evidence-dir ((requiring-resolve 'claimgraph.evidence/default-dir) db)})]
        (log-reads! db :recall (:facts r))
        r)

      "memory_history"
      (core/get-history s (select-keys a [:subject :subject-scope :predicate]))

      "memory_conflicts"
      (core/conflicts s)

      "memory_coach"
      (let [r ((requiring-resolve 'claimgraph.coach/consult)
               s (require-query a :task "coach requires a task"))]
        (when (:push r)
          (log-reads! db :coach (concat (:commitments r) (:hazards r))))
        r)

      ;; scope is the scope of the WHOLE write — the fact and the entities it
      ;; mints — unless subject_scope/object_scope say otherwise. core defaults
      ;; entity scope to "project" independently of the fact's scope, which let
      ;; a client write into scope "team" and then never resolve its own
      ;; subject: the natural follow-up read (memory_facts with entity_scope
      ;; "team") answered entity-not-found. `entity split` already reads a lone
      ;; scope this way, and it is the only reading under which one argument is
      ;; enough to write a subgraph a client can read back.
      "memory_assert"
      ((requiring-resolve 'claimgraph.lease/with-lease)
       db {:owner "claimgraph-mcp"}
       #(core/assert-fact s (-> (select-keys a [:subject :subject-type :predicate
                                                :object :object-type :object-kind
                                                :scope :confidence :episode :on-conflict])
                                (assoc :epistemic (or (:class a) (:epistemic a))
                                       :source-type (or (:source-type a) :user-assertion)
                                       :subject-scope (or (:subject-scope a) (:scope a))
                                       :object-scope (or (:object-scope a) (:scope a))
                                       :t-valid (logic/parse-instant (:valid-from a))
                                       :t-invalid (logic/parse-instant (:valid-until a))))))

      (throw (ex-info (str "Unknown tool: " tool) {:type :unknown-tool})))))

;; ---------------------------------------------------------------------------
;; JSON-RPC handling (pure)
;; ---------------------------------------------------------------------------

(defn- result [id r] {:jsonrpc "2.0" :id id :result r})

(defn- rpc-error
  ([id code msg] (rpc-error id code msg nil))
  ([id code msg data]
   {:jsonrpc "2.0" :id id
    :error (cond-> {:code code :message msg} data (assoc :data data))}))

(defn- error-payload
  "The CLI's error shape, verbatim (see cli/run): message under :error, then
  everything the failure attached — :type, :hint, the ambiguous candidates.
  One store behind two surfaces, so an agent that learned to read the CLI's
  error has already learned to read this one.

  Both :claimgraph/ keys come off, because both are internal: the flag that
  marks a deliberate failure, and the process exit status the CLI answers a
  shell with. An MCP client has no process to exit and no use for the number."
  [e]
  (merge {:error (ex-message e)}
         (dissoc (ex-data e) :claimgraph/error :claimgraph/exit)))

(defn handle
  "One parsed JSON-RPC message -> response map, or nil for notifications."
  [s db {:keys [id method params]}]
  (cond
    (= method "initialize")
    (result id {:protocolVersion protocol-version
                :capabilities {:tools {}}
                ;; the release, never a hand-kept literal: this string is what
                ;; an MCP client shows and a bug report quotes, and one that
                ;; drifts from `claim version` describes a build nobody has
                :serverInfo {:name "claimgraph" :version version/release}})

    (= method "tools/list")
    (result id {:tools tool-defs})

    (= method "tools/call")
    (if-not (tool-names (:name params))
      (rpc-error id -32602 (str "Unknown tool: " (:name params))
                 {:type :unknown-tool :known (vec (sort tool-names))})
      (try
        ;; Same contract as the CLI report: store-level degradation warnings
        ;; (a failed oplog append) ride the tool result as :warnings.
        (let [r (binding [store/*write-warnings* (atom [])]
                  (let [r (call-tool s db (:name params) (:arguments params))
                        ws (seq @store/*write-warnings*)]
                    (cond-> r
                      (and (map? r) ws) (update :warnings #(into (vec %) ws)))))]
          (result id {:content [{:type "text"
                                 :text (wire/generate-string r)}]
                      :isError false}))
        (catch Exception e
          (result id {:content [{:type "text"
                                 :text (wire/generate-string (error-payload e))}]
                      :isError true}))))

    (= method "ping")
    (result id {})

    (nil? id) nil                                    ; notification — no reply

    :else (rpc-error id -32601 (str "Method not found: " method))))

;; ---------------------------------------------------------------------------
;; Shell: the stdio loop
;; ---------------------------------------------------------------------------

(defn serve!
  "Blocking stdio server: one JSON-RPC message per line, until EOF. The
  store stays open for the whole session — that is the point."
  [s db]
  (let [in (java.io.BufferedReader. *in*)]
    (loop []
      (when-let [line (.readLine in)]
        (when-not (str/blank? line)
          (when-let [resp (try
                            (handle s db (wire/parse-string line))
                            (catch Exception _
                              (rpc-error nil -32700 "Parse error")))]
            (println (wire/generate-string resp))
            (flush)))
        (recur)))))
