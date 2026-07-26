(ns claimgraph.mcp
  "The MCP front-end (roadmap #27): a thin second surface over
  claimgraph.core, trigger-gated on the latency data from #11 and finally
  tripped by the ambient loop's hook cadence — the CLI pays ~350ms of bb +
  pod cold start per invocation, the server pays it once per session.

  Speaks MCP's stdio transport: newline-delimited JSON-RPC 2.0. The handler
  is pure (request map + store -> response map); only serve! touches IO.
  Reads feed the outcome signal exactly like the CLI; the one write tool
  takes the write lease per call, so CLI and MCP writers stay serialized.

  Encoding is claimgraph.wire's, not cheshire's, for the same reason the CLI's
  is: this is a SECOND surface onto the same facts, and an agent that reads a
  fact here and diffs it against the committed dump (or against the CLI's
  answer) must not find a different :recorded-at because cheshire's default
  date encoder dropped the milliseconds on one of the two paths.

  Wire it up:  claude mcp add claimgraph -- bin/claim mcp
  (or any MCP client; --db as usual)."
  (:require [claimgraph.core :as core]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(def protocol-version "2024-11-05")

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(def tool-defs
  [{:name "memory_facts"
    :description "Facts about an entity in the project knowledge graph. Supports time travel (as_of) and reverse lookups (direction=in)."
    :inputSchema {:type "object"
                  :properties {:entity {:type "string"}
                               :predicate {:type "string"}
                               :direction {:type "string" :enum ["out" "in" "both"]}
                               :as_of {:type "string" :description "ISO date/instant"}
                               :min_confidence {:type "number"}}
                  :required ["entity"]}}
   {:name "memory_search"
    :description "Hybrid retrieval over the graph: full-text + entity resolution + neighborhood, ranked by consensus and effective confidence."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}}
                  :required ["query"]}}
   {:name "memory_recall"
    :description "Sufficiency escalation: answer from graph facts, then episode summaries, then raw evidence pages; says which tier answered."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}}
                  :required ["query"]}}
   {:name "memory_history"
    :description "All versions of (subject, predicate), valid and superseded — what did we believe, and when did it change."
    :inputSchema {:type "object"
                  :properties {:subject {:type "string"}
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
    :description "Record a fact through full validation and conflict resolution. Use class=commitment for human decisions (never silently overwritten)."
    :inputSchema {:type "object"
                  :properties {:subject {:type "string"}
                               :predicate {:type "string"}
                               :object {:type "string"}
                               :object_kind {:type "string" :enum ["entity" "literal"]}
                               :class {:type "string" :enum ["observation" "commitment" "preference"]}
                               :confidence {:type "number"}
                               :source_type {:type "string"}}
                  :required ["subject" "predicate" "object"]}}])

(defn- log-reads! [db verb facts]
  (try ((requiring-resolve 'claimgraph.outcome/log-reads!)
        db verb (keep :id facts))
       (catch Exception _ nil)))

(defn call-tool
  "Dispatch one tool call. Returns the result data (to be JSON-encoded)."
  [s db tool args]
  (case tool
    "memory_facts"
    (let [r (core/get-facts s {:entity (:entity args)
                               :predicate (:predicate args)
                               :direction (:direction args)
                               :min-confidence (:min_confidence args)
                               :as-of (some-> (:as_of args)
                                              ((requiring-resolve 'claimgraph.logic/parse-instant)))})]
      (log-reads! db :facts (:facts r))
      r)

    "memory_search"
    (let [r (core/search s (str (:query args)) {})]
      (log-reads! db :search (:facts r))
      r)

    "memory_recall"
    (let [r (core/recall s (str (:query args))
                         {:evidence-dir ((requiring-resolve 'claimgraph.evidence/default-dir) db)})]
      (log-reads! db :recall (:facts r))
      r)

    "memory_history"
    (core/get-history s {:subject (:subject args) :predicate (:predicate args)})

    "memory_conflicts"
    (core/conflicts s)

    "memory_coach"
    (let [r ((requiring-resolve 'claimgraph.coach/consult) s (str (:task args)))]
      (when (:push r)
        (log-reads! db :coach (concat (:commitments r) (:hazards r))))
      r)

    "memory_assert"
    ((requiring-resolve 'claimgraph.lease/with-lease)
     db {:owner "claimgraph-mcp"}
     #(core/assert-fact s {:subject (:subject args)
                           :predicate (:predicate args)
                           :object (:object args)
                           :object-kind (:object_kind args)
                           :epistemic (:class args)
                           :confidence (:confidence args)
                           :source-type (or (:source_type args) :user-assertion)}))

    (throw (ex-info (str "Unknown tool: " tool) {:type :unknown-tool}))))

;; ---------------------------------------------------------------------------
;; JSON-RPC handling (pure)
;; ---------------------------------------------------------------------------

(defn- result [id r] {:jsonrpc "2.0" :id id :result r})
(defn- rpc-error [id code msg] {:jsonrpc "2.0" :id id :error {:code code :message msg}})

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
    (try
      (let [r (call-tool s db (:name params) (:arguments params))]
        (result id {:content [{:type "text"
                               :text (wire/generate-string r)}]
                    :isError false}))
      (catch Exception e
        (result id {:content [{:type "text"
                               :text (wire/generate-string
                                      (merge {:error (ex-message e)}
                                             (dissoc (ex-data e) :claimgraph/error)))}]
                    :isError true})))

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
        (when-not (clojure.string/blank? line)
          (when-let [resp (try
                            (handle s db (wire/parse-string line))
                            (catch Exception _
                              (rpc-error nil -32700 "Parse error")))]
            (println (wire/generate-string resp))
            (flush)))
        (recur)))))
