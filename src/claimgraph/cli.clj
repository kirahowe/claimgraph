(ns claimgraph.cli
  "Thin CLI front-end over claimgraph.core. All commands emit JSON to stdout
  (--pretty for humans) so the same output is consumable at a terminal, by a
  skill via bash, and by a future MCP wrapper. The Datalevin backend is loaded
  lazily so --help and tests don't pay the pod tax."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [claimgraph.config :as config]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(def ^:private global-spec
  {:db {:desc "Database path (default: $CLAIMGRAPH_DB, .claimgraph/config.json, or ./.claimgraph/db)"}
   :pretty {:coerce :boolean :desc "Pretty-print JSON output"}
   :json {:coerce :boolean :desc "Force JSON output (audit defaults to the human scorecard at a terminal)"}
   :llm-timeout-ms {:coerce :long :desc "Per-call LLM timeout in ms (default 120000)"}})

;; ---------------------------------------------------------------------------
;; Exit status
;; ---------------------------------------------------------------------------

(def ok-exit 0)

(def error-exit
  "A command that ran and could not do what it was asked — the documented
  JSON-on-stderr contract."
  1)

(def usage-exit
  "A command line claimgraph could not act on at all: an unknown verb, an
  unknown subcommand, a verb given none, a flag value it could not read.
  Distinct from error-exit because the two need different responses and a
  caller that sees one number cannot tell them apart — a judge that could not
  reach its LLM is worth retrying, a typo in a SessionEnd hook command line
  never is. 2 is the shell's own convention for a usage error."
  2)

(defn- db-path [opts]
  (config/value :db opts))

(defn- accept-alias
  "Fold a verb's older flag spelling onto the one that setting is now called,
  unless the canonical flag was passed too.

  Renaming a flag in an alpha still breaks the command lines the project's own
  README, SKILL.md and installed hooks teach, so every rename here keeps its
  predecessor working. Folded BEFORE config/with-defaults runs, so an explicit
  legacy flag still outranks the environment and the config file — merging the
  layers first would let $CLAIMGRAPH_NOTES_DIR quietly beat an explicit
  --dir."
  [opts canonical alias]
  (cond-> opts
    (and (nil? (get opts canonical)) (some? (get opts alias)))
    (assoc canonical (get opts alias))))

(defn- install-llm-timeout!
  "Make the LLM call timeout resolve like every other knob.

  claimgraph.llm reads $CLAIMGRAPH_LLM_TIMEOUT_MS itself and nothing threads a
  per-call timeout down to it, so a value in .claimgraph/config.json was a
  setting `claim config` could report and no call would obey. The shell
  resolves the chain once and installs the answer — the same shape as every
  other setting, resolved in the one place that owns resolution. Nothing is
  installed when the knob is unset anywhere, so an ordinary run neither loads
  llm.clj early nor pays for the lookup.

  Installing it as llm's process default is not enough on its own:
  llm/timeout-ms consults the environment BEFORE that default, so an exported
  $CLAIMGRAPH_LLM_TIMEOUT_MS beat both --llm-timeout-ms and the config file —
  precisely inverting the flag > env > config > default chain this project
  documents, on exactly the machines that set the variable. So the resolver
  itself is bound to feed the already-resolved answer in where it looks first:
  once the shell has folded the environment into that number, a second read of
  the same layer at a different priority can only contradict it. A per-call
  :timeout-ms (the judge's own tests) still outranks everything, which is what
  makes it an override."
  [opts]
  (let [ms (config/value :llm-timeout-ms opts)]
    (when (and (number? ms) (pos? ms))
      (let [ms (long ms)]
        (when-let [default (try (requiring-resolve 'claimgraph.llm/default-timeout-ms)
                                (catch Throwable _ nil))]
          (alter-var-root default (constantly ms))
          (alter-var-root (requiring-resolve 'claimgraph.llm/timeout-ms)
                          (fn [resolve-ms]
                            (fn ([override] (resolve-ms override ms))
                              ([override _env] (resolve-ms override ms)))))))))
  opts)

(defn- llm-opts
  "Preflight for every verb that shells out to an LLM. Two resolutions no
  command should reinvent: the call timeout (above), and --command falling
  back to the chain --extractor resolves through (flag > $CLAIMGRAPH_LLM_CMD >
  config > claude -p). That fallback used to resolve against an EMPTY opts
  map, which meant `judge --extractor mycmd` and `consolidate --extractor
  mycmd` accepted the flag, ignored it, and shelled out to claude -p."
  [opts]
  (install-llm-timeout! opts)
  (update opts :command #(or % (config/value :extractor opts))))

(defn- emit
  "Every command's output, through the canonical encoder. Command output is
  not a lesser artifact than the dump: `facts`, `history` and `episode list`
  all hand a caller timestamps, and a caller that diffs them against the dump
  or its own clock deserves the same millisecond truth the store holds.

  Degradation warnings the store surfaced during the command (an oplog
  append that failed without blocking the write) ride the report as
  :warnings — appended to any the command already carries, never replacing
  them."
  [opts data]
  (let [ws (some-> store/*write-warnings* deref seq)]
    (println (wire/generate-string
              (cond-> data
                (and (map? data) ws) (update :warnings #(into (vec %) ws)))
              {:pretty (boolean (:pretty opts))}))))

(defn- tty?
  "True when stdout is an interactive terminal — a human at a prompt, not a
  pipe, a script, or an agent capturing output. Pre-JDK-22 a non-nil console
  already implies a terminal; isTerminal refines that where it exists."
  []
  (if-let [c (System/console)]
    (try (.isTerminal c) (catch Throwable _ true))
    false))

(defn- evidence-dir [opts]
  (or (config/value :evidence-dir opts)
      ((requiring-resolve 'claimgraph.evidence/default-dir) (db-path opts))))

(defn- parse-time [s] (logic/parse-instant s))

(defn- log-reads!
  "Feed the outcome signal (#24): every read verb records which facts it
  surfaced. Silent and failure-proof."
  [opts verb facts]
  (try ((requiring-resolve 'claimgraph.outcome/log-reads!)
        (db-path opts) verb (keep :id facts))
       (catch Exception _ nil)))

(defn- open-store [opts]
  (let [open (requiring-resolve 'claimgraph.store.datalevin/open-store)
        s (open (db-path opts))]
    ;; auto-seed the vocabulary on first contact with a fresh store
    (when (empty? (store/-list-predicates s {}))
      (core/seed! s))
    ;; every mutation appends to this writer's effect log (#25): the store
    ;; is the materialized view, the logs are what other machines sync
    ((requiring-resolve 'claimgraph.oplog/logged-store) s (db-path opts))))

(defn- with-store [opts f]
  (binding [store/*write-warnings* (atom [])]
    (let [s (open-store opts)]
      (try (f s) (finally (store/-close s))))))

(defn- with-write-store
  "Write commands run under the write lease (multi-writer safety, #25):
  the conflict machinery is read-decide-write, so whole operations
  serialize at this boundary. Reads never take the lease.

  The lease renews itself while the command runs, so a verb that shells out to
  an LLM dozens of times (consolidate, hooks run) does not expire under its own
  duration and hand a waiting writer a lease it was right to think dead (#20).
  Losing it anyway — a suspended process, a hand-deleted lock file — fails the
  command with :lease-lost after the work finishes: the writes landed and the
  report says what they were, but nothing may report an unserialized write as a
  serialized one."
  [opts f]
  (let [with-lease (requiring-resolve 'claimgraph.lease/with-lease)]
    (with-lease (db-path opts)
                {:owner (or (System/getenv "CLAIMGRAPH_WRITER") "claimgraph-cli")
                 :wait-ms (:lease-wait opts)}
                #(with-store opts f))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn cmd-init [{:keys [opts]}]
  (with-write-store opts
    (fn [s]
      ;; unconditionally, not just on a fresh store: seed! reconciles a
      ;; predicate row to the seed's shape including REMOVALS, and this is the
      ;; only path that reaches it. open-store seeds an empty store, so without
      ;; this an upgrade keeps whatever the old vocabulary said forever — which
      ;; is how a store went on reporting the non-bijective :inverse-of that
      ;; e154b6d removed. core/seed!'s docstring promises `claim init` does it.
      (let [r (core/seed! s)]
        (emit opts {:status "initialized"
                    :db (str (fs/canonicalize (db-path opts)))
                    :predicates (:predicates r)})))))

(defn cmd-assert [{:keys [opts]}]
  (with-write-store opts
    (fn [s]
      (emit opts (core/assert-fact s (-> opts
                                         (select-keys [:subject :subject-type :subject-scope
                                                       :predicate :object :object-type
                                                       :object-scope :object-kind
                                                       :epistemic :scope :confidence
                                                       :source-type :episode :on-conflict])
                                         (assoc :epistemic (or (:class opts) (:epistemic opts))
                                                :t-valid (parse-time (:valid-from opts))
                                                :t-invalid (parse-time (:valid-until opts)))))))))

(defn cmd-facts [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (let [r (core/get-facts s (assoc (select-keys opts [:entity :entity-scope :direction
                                                          :predicate :scope :include-invalidated
                                                          :min-confidence])
                                       :as-of (parse-time (:as-of opts))))]
        (log-reads! opts :facts (:facts r))
        (emit opts r)))))

(defn- walk-neighborhood
  "The guided walk in the neighborhood's shape, so `neighbor` answers with one
  object rather than two. With --query it used to drop :entities and :depth
  and report :walk-score where the BFS reported :effective-confidence — the
  same verb, two incompatible payloads, and no way to write one reader for
  it. The walk keeps everything that makes it a walk (:query, :walk-score,
  its own ordering) and gains everything the neighborhood promised.

  The hop distances come from the walk itself. This wrapper used to measure
  them over the facts it was handed, which reports null for every node whose
  linking fact the budget truncated away (core/walk-nodes) — and since the MCP
  surface shares this wrapper, it reported null there too."
  [{:keys [entities facts] :as walk} now]
  (assoc walk
         :facts (mapv #(assoc % :effective-confidence (logic/effective-confidence % now))
                      facts)
         :depth (reduce max 0 (keep :depth entities))))

(defn cmd-neighbor [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts
            (if (:query opts)
              (walk-neighborhood
               (core/guided-walk s (select-keys opts [:entity :entity-scope
                                                      :query :budget :beam]))
               (core/now))
              (core/get-neighborhood s (assoc (select-keys opts [:entity :entity-scope :depth
                                                                 :scope :min-confidence :predicate])
                                              :as-of (parse-time (:as-of opts)))))))))

(defn cmd-recall [{:keys [opts args]}]
  (let [query (or (first args) (:query opts))]
    (when (str/blank? (str query))
      (logic/fail "recall requires a query" {:type :missing-query}))
    (with-store opts
      (fn [s]
        (let [r (core/recall s (str query)
                             {:min-hits (:min-hits opts)
                              :evidence-dir (evidence-dir opts)})]
          (log-reads! opts :recall (:facts r))
          (emit opts r))))))

(defn cmd-history [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (emit opts (core/get-history s (select-keys opts [:subject :subject-scope :predicate]))))))

(defn cmd-search [{:keys [opts args]}]
  (let [query (or (first args) (:query opts))]
    (when (str/blank? (str query))
      (logic/fail "search requires a query" {:type :missing-query}))
    (with-store opts
      (fn [s]
        (let [r (core/search s (str query) {})]
          (log-reads! opts :search (:facts r))
          (emit opts r))))))

(defn cmd-invalidate [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/invalidate s (assoc (select-keys opts [:fact-id :reason])
                                                 :at (parse-time (:at opts))))))))

(defn cmd-conflicts [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/conflicts s)))))

(defn cmd-judge [{:keys [opts]}]
  (let [opts (-> opts
                 (accept-alias :min-verdict-confidence :min-confidence)
                 llm-opts)
        judge (requiring-resolve (if (:sweep opts)
                                   'claimgraph.judge/sweep-conflicts!
                                   'claimgraph.judge/judge-conflicts!))]
    (with-write-store opts
      (fn [s]
        (emit opts (judge s {:command (:command opts)
                             :resolve (:resolve opts)
                             :min-confidence (:min-verdict-confidence opts)
                             :evidence-dir (evidence-dir opts)}))))))

(defn cmd-entity-ensure
  "Reports {status, entity} like `entity rename` and `entity alias` do. It
  used to answer with the bare entity — a mutation whose output a caller had
  to recognise by its shape rather than read a status off, alone among the
  entity verbs."
  [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts {:status :ensured
                        :entity (core/ensure-entity s {:name (:name opts)
                                                       :type (:type opts)
                                                       :scope (:scope opts)})}))))

(defn cmd-entity-list [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (store/-list-entities s {:type (logic/->kw (:type opts))
                                                :scope (:scope opts)})))))

(defn cmd-entity-rename [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/rename-entity s (select-keys opts [:from :to :scope]))))))

(defn cmd-entity-alias [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/alias-entity s (select-keys opts [:name :alias :scope]))))))

(defn cmd-entity-merge [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/merge-entities s (select-keys opts [:from :into :scope]))))))

(defn cmd-entity-split [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/split-entity s (select-keys opts [:from :into :scope]))))))

(defn cmd-entity-duplicates [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/entity-duplicates s)))))

(defn cmd-predicates [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/list-predicates s (select-keys opts [:category :status :usage]))))))

(defn- checked-object-shape
  "--object-shape, normalized, or the opts untouched when it was not passed.

  Refused with usage-exit rather than stored, because the shape is a closed
  enum the admission screen reads: an unrecognised one is not a narrower
  bound, it is no declaration at all (preds/object-shape falls back), so a
  typo would register a row that silently keeps rejecting the very lessons the
  flag was reached for — and report success."
  [opts]
  (if-some [raw (:object-shape opts)]
    (let [k (logic/->kw raw)]
      (when-not (#{:value :prose} k)
        (logic/fail (str "Unknown object shape: " raw)
                    {:type :invalid-object-shape
                     :object-shape (str raw)
                     :expected ["value" "prose"]
                     :claimgraph/exit usage-exit
                     :hint (str "value caps a literal object at "
                                logic/max-literal-chars " characters; prose at "
                                logic/prose-literal-chars ", for predicates whose "
                                "objects are lessons or rationales")}))
      (assoc opts :object-shape k))
    opts))

(defn cmd-predicate-register
  "Reports {status, predicate}. The registry row is nested rather than merged
  with the status: a predicate row HAS a :status of its own (staging, stable,
  deprecated) and flattening the report over it would answer a question about
  the vocabulary with a fact about the command."
  [{:keys [opts]}]
  (let [opts (checked-object-shape opts)]
    (with-write-store opts
      (fn [s] (emit opts {:status :registered
                          :predicate (core/register-predicate
                                      s (select-keys opts [:id :label :category :object-kind
                                                           :object-shape :cardinality
                                                           :definition
                                                           :default-epistemic]))})))))

(defn cmd-predicate-promote [{:keys [opts]}]
  (let [opts (checked-object-shape opts)]
    (with-write-store opts
      (fn [s] (emit opts (core/promote-predicate
                          s (select-keys opts [:from :to :label :definition :category
                                               :object-kind :object-shape :cardinality
                                               :maps-to :default-epistemic])))))))

(defn cmd-episode-open
  "Reports {status, episode} — the row nested under the name of the thing it
  is, exactly as `entity ensure` and `predicate register` report {status,
  entity} and {status, predicate}.

  It used to flatten the row into the report and rename its :id to :episode,
  which made three sibling mutations answer in three different shapes and left
  the episode row with no id of its own — the one field that identifies it.
  `jq -r .episode.id` is two characters more than `jq -r .episode` and one
  fewer shape for a caller to learn."
  [{:keys [opts]}]
  (with-write-store opts
    (fn [s]
      (emit opts {:status :opened
                  :episode (core/open-episode s (select-keys opts [:source-type :ref]))}))))

(defn cmd-episode-close [{:keys [opts]}]
  (with-write-store opts
    (fn [s] (emit opts (core/close-episode s (select-keys opts [:episode :summary]))))))

(defn cmd-episode-list [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (store/-list-episodes s)))))

(defn cmd-ingest [{:keys [opts]}]
  (let [lines (if-let [f (:file opts)]
                (str/split-lines (slurp f))
                (line-seq (java.io.BufferedReader. *in*)))
        facts (into []
                    (comp (remove str/blank?)
                          (map #(logic/normalize-keys (wire/parse-string %))))
                    lines)]
    (with-write-store opts
      (fn [s]
        (emit opts (core/ingest s (select-keys opts [:episode :source-type :ref]) facts))))))

(defn cmd-ingest-code [{:keys [opts]}]
  ;; --project is the project root on every other verb, and ingest-code takes
  ;; nothing else; it accepted --project silently and analysed the cwd anyway.
  (let [opts (accept-alias opts :project :dir)
        ingest-code (requiring-resolve 'claimgraph.ingest.code/ingest!)]
    (with-write-store opts
      (fn [s] (emit opts (ingest-code s (assoc (select-keys opts [:scope :language])
                                               :dir (:project opts))))))))

(defn cmd-ingest-session [{:keys [opts]}]
  (let [opts (-> (config/with-defaults opts [:extractor]) llm-opts)
        extract (requiring-resolve 'claimgraph.ingest.session/extract!)]
    (with-write-store opts
      (fn [s]
        (emit opts (extract s (assoc (select-keys opts [:file :ref :extractor :dry-run])
                                     :evidence-dir (evidence-dir opts))))))))

(defn cmd-ingest-notes [{:keys [opts]}]
  (let [opts (-> (accept-alias opts :notes-dir :dir)
                 (config/with-defaults [:harness :notes-dir :extractor])
                 llm-opts)
        ingest-notes (requiring-resolve 'claimgraph.ingest.notes/ingest!)]
    (with-write-store opts
      (fn [s]
        (emit opts (ingest-notes s (assoc (select-keys opts [:harness :project
                                                             :extractor :dry-run])
                                          :dir (:notes-dir opts)
                                          :evidence-dir (evidence-dir opts))))))))

(defn cmd-ingest-adr [{:keys [opts]}]
  (let [opts (accept-alias opts :adr-dir :dir)
        ingest-adr (requiring-resolve 'claimgraph.ingest.adr/ingest!)]
    (with-write-store opts
      (fn [s] (emit opts (ingest-adr s (assoc (select-keys opts [:file :dry-run])
                                              :dir (:adr-dir opts))))))))

(defn cmd-ingest-failure [{:keys [opts]}]
  (let [opts (-> (config/with-defaults opts [:extractor]) llm-opts)
        extract (requiring-resolve 'claimgraph.ingest.failure/extract!)]
    (with-write-store opts
      (fn [s]
        (emit opts (extract s (assoc (select-keys opts [:file :ref :context
                                                        :extractor :dry-run])
                                     :evidence-dir (evidence-dir opts))))))))

(defn cmd-evidence [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (let [ep-id (:episode opts)
            ep (when ep-id (store/-get-episode s ep-id))
            hash (or (:hash opts) (:evidence ep))
            fetch (requiring-resolve 'claimgraph.evidence/fetch)]
        (when (and ep-id (not ep))
          (logic/fail (str "Episode not found: " ep-id) {:type :episode-not-found}))
        (when-not hash
          (logic/fail "No evidence recorded"
                      {:type :no-evidence :episode ep-id
                       :hint "episodes ingested before the evidence tier (or with it disabled) carry none"}))
        (emit opts {:episode ep-id
                    :ref (:ref ep)
                    :evidence hash
                    :content (or (fetch (evidence-dir opts) hash)
                                 (logic/fail (str "Evidence artifact not on this machine: " hash)
                                             {:type :evidence-missing :evidence hash}))})))))

(defn cmd-compile-context [{:keys [opts]}]
  (let [opts (-> (accept-alias opts :notes-dir :dir)
                 (config/with-defaults [:harness :notes-dir :inject-file]))
        compile-context (requiring-resolve 'claimgraph.context/compile!)]
    (with-store opts
      (fn [s]
        (emit opts (compile-context s (assoc (select-keys opts [:harness :project
                                                                :inject-file :budget
                                                                :dry-run])
                                             :dir (:notes-dir opts))))))))

(defn cmd-coach [{:keys [opts args]}]
  (let [consult (requiring-resolve 'claimgraph.coach/consult)]
    (if (:hook opts)
      ;; hook mode: harness JSON on stdin; print injection JSON or nothing
      (let [input (try (wire/parse-string (slurp *in*)) (catch Exception _ {}))
            query ((requiring-resolve 'claimgraph.coach/hook-input->query) input)]
        (when-not (str/blank? (str query))
          (with-store opts
            (fn [s]
              (when-let [out ((requiring-resolve 'claimgraph.coach/hook-output)
                              (consult s (str query)))]
                (emit opts out))))))
      (let [query (or (first args) (:query opts))]
        (when (str/blank? (str query))
          (logic/fail "coach requires a query (or --hook with stdin)"
                      {:type :missing-query}))
        (with-store opts
          (fn [s]
            (let [r (consult s (str query))]
              (when (:push r)
                (log-reads! opts :coach (concat (:commitments r) (:hazards r))))
              (emit opts r))))))))

(defn cmd-outcome [{:keys [opts args]}]
  (let [valence (or (first args) (:valence opts))
        outcome! (requiring-resolve 'claimgraph.outcome/outcome!)]
    (with-write-store opts
      (fn [s] (emit opts (outcome! s (db-path opts) {:valence valence}))))))

(defn cmd-hooks-run
  "The SessionEnd pass: deterministic capture, then a detached curator. It
  makes no model calls of its own — the settings it resolves that the curator
  needs (notes dir, inject file, extractor) are forwarded on the child's
  command line, and everything else the child resolves for itself.

  A partial pass still exits 0 — a hook that fails is worse than a hook that
  reports, and a stage failing is exactly when the next session most needs the
  view the other stages did recompile — but CI and cron need to be able to see
  it, so --fail-on-partial turns the same report into a non-zero status
  without changing what it says."
  [{:keys [opts]}]
  (let [opts (-> opts
                 (accept-alias :notes-dir :dir)
                 (config/with-defaults [:harness :notes-dir :inject-file
                                        :extractor :code-ingest]))
        run (requiring-resolve 'claimgraph.hooks/run!)
        r (with-write-store opts
            (fn [s]
              (run s (assoc (select-keys opts [:harness :project :inject-file
                                               :extractor :code-ingest :no-curate])
                            :dir (:notes-dir opts)
                            :db (db-path opts)))))]
    (emit opts r)
    (when (and (:fail-on-partial opts) (= :partial (:status r)))
      error-exit)))

(defn cmd-hooks-install [{:keys [opts]}]
  (let [opts (config/with-defaults opts [:harness :settings-file])
        install (requiring-resolve 'claimgraph.hooks/install!)]
    (emit opts (install (select-keys opts [:project :harness :settings-file
                                           :coach :bin])))))

(defn cmd-curate
  "The detached curation run the SessionEnd hook spawns — also runnable by
  hand, which is the same thing: notes extraction, then the maintenance
  stages, then the recompile, under one model-call budget.

  A SINGLETON by try-acquire on the curation lease (spec/maintenance.allium,
  rule Curate; wait 0, never a wait): a live curator means this run's work is
  already in hand, so exiting is SUCCESS and reports :already-running at exit
  0. Two curators racing would buy the same verdicts twice and the loser's
  work is derivable by the winner.

  Inside the lease it opens the store WITHOUT the write lease. The curator
  holds no standing write lease at all — it takes one per applied outcome and
  never across a model call, so a session's capture is never queued behind a
  completion (see claimgraph.curate)."
  [{:keys [opts]}]
  (let [opts (-> (accept-alias opts :notes-dir :dir)
                 (config/with-defaults [:harness :notes-dir :inject-file
                                        :extractor :budget])
                 llm-opts)
        curate! (requiring-resolve 'claimgraph.curate/curate!)
        lease-key ((requiring-resolve 'claimgraph.curate/curation-lease-key)
                   (db-path opts))
        with-lease (requiring-resolve 'claimgraph.lease/with-lease)]
    (try
      (with-lease lease-key
                  {:owner @(requiring-resolve 'claimgraph.curate/curator-owner)
                   :wait-ms 0}
                  (fn []
                    (with-store opts
                      (fn [s]
                        (emit opts
                              (curate! s (assoc (select-keys opts [:harness :project
                                                                   :inject-file :extractor
                                                                   :command :budget])
                                                :dir (:notes-dir opts)
                                                :db (db-path opts)
                                                :evidence-dir (evidence-dir opts))))))))
      (catch clojure.lang.ExceptionInfo e
        (if (= :store-locked (:type (ex-data e)))
          (emit opts {:status :already-running
                      :holder (:holder (ex-data e))
                      :note "another curator holds the curation lease; its run covers this one"})
          (throw e))))))

(defn cmd-dump
  "Export to stdout, or to --out with a report of what landed there.

  The report carries BOTH counts, because one number cannot answer both
  questions a caller asks of a JSONL artifact: :records is the graph (what
  `load` will report back), :lines is the file (what `wc -l` says, header
  included). Reporting only :records made the cheapest integrity check there
  is — compare the count against the line count — flag every dump as
  truncated by exactly one line."
  [{:keys [opts]}]
  (with-store opts
    (fn [s]
      (let [records (core/dump s)
            out (wire/dump-lines records)]
        (if-let [f (:out opts)]
          (do (spit f (str (str/join "\n" out) "\n"))
              (emit opts {:status "dumped" :records (count records)
                          :lines (count out)
                          :format version/format-version :out f}))
          (doseq [line out] (println line)))))))

(defn cmd-load [{:keys [opts]}]
  (let [lines (if-let [f (:file opts)]
                (str/split-lines (slurp f))
                (line-seq (java.io.BufferedReader. *in*)))
        records (into []
                      (comp (remove str/blank?)
                            (map wire/parse-string))
                      lines)]
    (with-write-store opts
      (fn [s] (emit opts (core/load-dump
                          ((requiring-resolve 'claimgraph.oplog/inner-store) s)
                          records))))))

(defn cmd-reconcile [{:keys [opts]}]
  (let [reconcile! (requiring-resolve 'claimgraph.oplog/reconcile!)
        inner (requiring-resolve 'claimgraph.oplog/inner-store)]
    (with-write-store opts
      (fn [s] (emit opts (reconcile! (inner s) (db-path opts)))))))

(defn cmd-mcp [{:keys [opts]}]
  (let [serve! (requiring-resolve 'claimgraph.mcp/serve!)]
    (with-store opts
      (fn [s] (serve! s (db-path opts))))))

(defn cmd-stats [{:keys [opts]}]
  (with-store opts
    (fn [s] (emit opts (core/stats s)))))

(defn cmd-consolidate [{:keys [opts]}]
  (let [opts (-> opts
                 (accept-alias :min-verdict-confidence :min-confidence)
                 (config/with-defaults [:budget])
                 llm-opts)
        consolidate (requiring-resolve 'claimgraph.consolidate/consolidate!)]
    (with-write-store opts
      (fn [s]
        (emit opts (consolidate s (assoc (select-keys opts [:command :resolve :min-usage
                                                            :budget])
                                         :min-confidence (:min-verdict-confidence opts)
                                         :evidence-dir (evidence-dir opts))))))))

(defn- audit-scan-dirs
  "audit's extra scan directories under both spellings. --scan-dir is what
  they are; --dir is what they were called when it also meant the notes dir
  everywhere else. They are a repeatable list of extra sources, so passing
  both adds both rather than one shadowing the other."
  [opts]
  (into (vec (:scan-dir opts)) (:dir opts)))

(defn- audit-scorecard?
  "Pure: does this invocation want the human scorecard rather than JSON?

  --pretty means pretty-printed JSON here exactly as it does on every other
  verb. It used to switch the format outright, so `audit --pretty | jq` got a
  human scorecard and no JSON at all — one flag with two meanings on the one
  verb whose output people pipe. The scorecard is --scorecard now, and stays
  what a human at a terminal gets without asking.

  Nothing stops a caller passing two of these, so the precedence is decided
  here and stated in help — help used to say --pretty and --scorecard both
  \"force\" their format, and --pretty silently won. --json wins outright: a
  caller that asked for machine output must never be handed prose. Then
  --scorecard, the only flag that asks for the scorecard at all, over --pretty,
  which is a global flag a wrapper may put on every command line and which says
  how JSON is printed rather than whether JSON is what comes out."
  [{:keys [json pretty scorecard]} tty]
  (boolean (and (not json) (or scorecard (and (not pretty) tty)))))

(defn cmd-audit [{:keys [opts]}]
  ;; The one verb that must NEVER open the real store: everything runs in a
  ;; throwaway in-memory store, so no with-store here.
  (let [opts (-> (config/with-defaults opts [:harness :extractor])
                 install-llm-timeout!)
        audit! (requiring-resolve 'claimgraph.audit/audit!)
        r (audit! {:project (:project opts)
                   :harness (:harness opts)
                   :files (:file opts)
                   :dirs (audit-scan-dirs opts)
                   :notes-dir (config/value :notes-dir opts)
                   :no-code (:no-code opts)
                   :no-judge (:no-judge opts)
                   :extractor (:extractor opts)})]
    (when-let [f (:out opts)]
      (spit f (str (wire/generate-string r {:pretty true}) "\n")))
    (if (audit-scorecard? opts (tty?))
      (println ((requiring-resolve 'claimgraph.audit/render-pretty) r))
      (emit opts r))))

(def ^:private setup-persist-keys
  "Settings a `claim setup` invocation may persist to .claimgraph/config.json —
  only when passed explicitly, so the config file records choices, not defaults."
  [:db :harness :notes-dir :inject-file :settings-file :skills-dir
   :extractor :budget])

(defn cmd-setup
  "Onboarding. :blocked exits non-zero: a blocked setup wired nothing, and a
  caller that reads exit 0 as \"claimgraph is installed\" has no other signal
  saying it isn't. The report still goes to stdout — it names the missing
  prerequisite, which is the thing worth having."
  [{:keys [opts]}]
  (let [opts (accept-alias opts :notes-dir :dir)
        chosen (select-keys opts setup-persist-keys)
        opts (config/with-defaults opts [:harness :notes-dir :settings-file
                                         :skills-dir])
        run! (requiring-resolve 'claimgraph.setup/run!)
        r (run! (assoc (select-keys opts [:project :bin :db :harness :settings-file
                                          :skills-dir :coach :mcp :dry-run])
                       :chosen chosen
                       :init-fn (fn []
                                  (with-write-store opts
                                    (fn [s]
                                      {:status :initialized
                                       :db (str (fs/canonicalize (db-path opts)))
                                       :predicates (count (store/-list-predicates s {}))})))))]
    (emit opts r)
    (when (= :blocked (:status r)) error-exit)))

(defn cmd-config [{:keys [opts]}]
  (let [opts (accept-alias opts :notes-dir :dir)
        opts+ (config/with-defaults opts [:harness :notes-dir :inject-file
                                          :settings-file :skills-dir])
        h ((requiring-resolve 'claimgraph.harness/resolve-harness) (:harness opts+))
        notes-dir ((requiring-resolve 'claimgraph.harness/notes-path)
                   h {:dir (:notes-dir opts+) :project (:project opts+)})
        project (str (fs/canonicalize (or (:project opts+) ".")))]
    (emit opts
          (assoc (config/describe opts)
                 :resolved
                 {:db (str (fs/absolutize (db-path opts)))
                  :evidence-dir (str (fs/absolutize (evidence-dir opts)))
                  :harness (name (:id h))
                  :notes-dir notes-dir
                  :inject-file ((requiring-resolve 'claimgraph.harness/inject-target)
                                h notes-dir (:inject-file opts+))
                  :settings-file (str (or (:settings-file opts+)
                                          (fs/path project ".claude" "settings.json")))
                  :skills-dir (str (or (:skills-dir opts+)
                                       (fs/path project ".claude" "skills")))}))))

(defn- source-checkout
  "claimgraph's OWN checkout as {:sha ... :dirty ...}, located from where this
  namespace was loaded from and never from the cwd — `claim version` run
  inside a user's project must not report that project's sha as the tool's.
  nil when claimgraph isn't running out of a git checkout at all. Both the git
  shell-out and the process namespace that makes it are behind this call, so
  only the version verb pays for either.

  :dirty is not a nicety here. bin/claim execs bb against this checkout, so
  every alpha user IS running editable source, and a sha reported off a
  modified tree sends the maintainer to code the reporter was not running —
  the one failure a version verb exists to prevent. Dirty is `git status
  --porcelain`, the same signal ingest/code's ref uses, and it counts
  untracked files on purpose: an untracked src/claimgraph/*.clj is loaded code
  that exists on no machine but the reporter's. A status call that fails at
  all reads as dirty — an unvouched sha is exactly as misleading as a stale
  one, and only the marker says so."
  []
  (try
    (let [url (io/resource "claimgraph/cli.clj")
          root (when (= "file" (.getProtocol url))
                 ;; src/claimgraph/cli.clj -> src/claimgraph -> src -> checkout
                 (-> url .toURI java.io.File. .getParentFile .getParentFile .getParentFile))
          sh (requiring-resolve 'babashka.process/sh)
          {:keys [exit out]} (when root (sh {:dir (str root)} "git" "rev-parse" "HEAD"))]
      (when (= 0 exit)
        (when-let [sha (not-empty (str/trim out))]
          (let [status (try (sh {:dir (str root)} "git" "status" "--porcelain")
                            (catch Exception _ nil))]
            {:sha sha
             :dirty (or (not= 0 (:exit status))
                        (not (str/blank? (:out status))))}))))
    (catch Exception _ nil)))

(defn cmd-version [{:keys [opts]}]
  (let [{:keys [sha dirty]} (source-checkout)]
    (emit opts (version/describe sha dirty))))

(def help-text "claimgraph — bi-temporal, epistemically-typed knowledge graph for coding-agent memory

Usage: claim <command> [options]

All commands accept --db PATH and --pretty. All output is JSON on stdout;
errors are JSON on stderr. Exit 0 on success, 1 when a command ran and could
not do what it was asked, 2 when the command line itself was wrong (unknown
verb, unknown or missing subcommand, a flag value that would not parse) — a
typo and a failure are different problems and a wrapper needs to tell them
apart. One exception to the output rule: audit prints its human scorecard when
stdout is a terminal. --scorecard forces the scorecard anywhere and outranks
--pretty (which only says how JSON is printed); --json forces JSON and
outranks both; piped or captured output is JSON unless --scorecard asks
otherwise.

Nothing about file locations is assumed. Every setting resolves through one
precedence chain — CLI flag > environment variable > .claimgraph/config.json
> default — and `claim config` shows each one's value and where it came from.
A setting's flag is its own name (--notes-dir sets notes-dir); where a flag
has been renamed the old spelling still works on the verbs that took it.
Harness defaults honor the harness's own relocations ($CLAUDE_CONFIG_DIR,
$CODEX_HOME). Every LLM shell-out is bounded by --llm-timeout-ms /
$CLAIMGRAPH_LLM_TIMEOUT_MS / llm-timeout-ms (default 120000).

Commands:
  setup               One-shot onboarding for a project (idempotent, safe to
                        re-run): verify prerequisites (a missing dtlv blocks —
                        nothing is wired that would fail at runtime), create +
                        seed the store, persist non-default choices to
                        .claimgraph/config.json, gitignore the live store,
                        install the agent skill, wire the ambient loop
                        (hooks install). [--project DIR] [--db PATH]
                        [--harness claude-code] [--notes-dir DIR]
                        [--inject-file F] [--settings-file F] [--skills-dir D]
                        [--extractor CMD] [--budget 20] [--coach]
                        [--mcp] (also register the MCP server in .mcp.json)
                        [--bin claim] [--dry-run]
  config              Show every setting: resolved value, which layer set it
                        (flag/env/config-file/default), and the fully resolved
                        paths (db, notes dir, inject file, settings file, ...)
  version             What is running here: the release version, the
                        persisted-format version every dump/oplog/store stamps
                        (a loader gates on that integer, not on the release),
                        and the sha of claimgraph's own checkout when it runs
                        from one — plus \"dirty\":true when that checkout has
                        uncommitted or untracked changes, which means the sha
                        alone does not describe the code that ran. Quote this
                        in a bug report.
  audit               Consistency scorecard over the project's agent-memory
                        pile (CLAUDE.md, AGENTS.md, .cursorrules, .cursor/rules,
                        copilot instructions, auto-memory notes) — runs BEFORE
                        claimgraph is installed: throwaway in-memory store,
                        nothing written, no dtlv needed (prerequisites: bb +
                        an extractor). Code facts ingest first (every language
                        the analyzer registry detects), so pile claims
                        colliding with the code read as staleness; then every
                        pile claim goes through the full conflict machinery.
                        Findings: contradictions, silent disagreements, stale
                        claims, restatements, name clusters, injection bloat —
                        each with verbatim quote receipts. The judge pass
                        filters false positives (judged-compatible pairs are
                        removed); it only reports — audit never resolves.
                        Exit 0 even with findings: it's a report.
                        [--project DIR] [--file F]... [--scan-dir D]...
                        (extra sources beyond the default scan: every *.md
                        inside; --dir is the older spelling and still adds
                        one) [--notes-dir D] (the auto-memory dir to read)
                        [--no-code] (skip the staleness-vs-code prong)
                        [--no-judge] (skip the LLM verdict pass; report raw)
                        [--extractor CMD] [--out FILE] (also write the JSON
                        scorecard to FILE) [--scorecard|--json|--pretty]
                        (the human scorecard is the default at a terminal and
                        --scorecard forces it anywhere, outranking --pretty;
                        --json forces JSON and outranks --scorecard; --pretty
                        means pretty-printed JSON here as everywhere else)
  init                Create the store and seed the predicate vocabulary
                        (setup calls this; use directly for a bare store)
  assert              Assert a fact through validation + conflict resolution
                        --subject S --predicate P --object O
                        [--subject-type T] [--object-type T] [--object-kind entity|literal]
                        [--class observation|commitment|preference] [--scope SCOPE]
                        [--confidence 0.9] [--source-type code|user-assertion|inferred|decision-record|session-log]
                        [--episode ID] [--on-conflict supersede|flag|ignore]
                        [--valid-from ISO] [--valid-until ISO]
                        Valid time is first-class: --valid-from/--valid-until
                        record when a fact was true (a closed past interval is
                        one assertion). Superseding closes the predecessor at
                        the successor's valid-from; a successor starting
                        at-or-before its predecessor flags as backdated-overlap
                        instead of inverting an interval.
  facts               Facts about an entity: --entity E [--predicate P] [--scope S]
                        [--as-of ISO] [--direction out|in|both] [--include-invalidated]
                        [--min-confidence 0.5]
                        Results carry effective-confidence: the stored base
                        halved per 90-day half-life since last reinforcement
                        (re-assertion resets the clock; commitments and
                        decision-records never fade). --min-confidence
                        filters on the effective value.
  neighbor            BFS neighborhood: --entity E [--depth 2] [--as-of ISO] [--min-confidence 0.5]
                        With --query \"...\" the fixed-depth BFS becomes an
                        evidence-guided walk: each round expands only the
                        [--beam 8] best edges by query-overlap × effective
                        confidence, until [--budget 25] facts. The walk
                        answers in the same shape (root, entities with their
                        hop distance, depth, facts with effective-confidence)
                        plus the query and each fact's walk-score, so one
                        reader parses both.
  coach               Gated push: claim coach \"task text\" — decides
                        WHETHER the graph holds something worth interrupting
                        with (standing decisions, known failure modes, open
                        conflicts touching the task); silent otherwise.
                        --hook reads Claude Code hook JSON on stdin and
                        emits additionalContext only when the gate fires
                        (wired by hooks install --coach).
  recall              Sufficiency escalation: claim recall \"query\"
                        [--min-hits 1]. Answers from the cheapest tier that
                        can support the query — graph facts (hybrid search),
                        then episode summaries, then raw evidence pages;
                        the result says which tier answered.
  history             All versions of (subject, predicate): --subject S --predicate P
  search              Full-text search: claim search \"redis migration\"
  invalidate          Close a fact's validity interval: --fact-id F [--reason R]
                        [--at ISO] (when it stopped being true; default now)
  conflicts           List open conflicts (flagged facts with still-valid candidates)
  judge               LLM-judge open conflicts: relation contradicts|duplicate|
                        supersedes|compatible per pair. Reports only, unless
                        --resolve, which acts on verdicts at/above
                        --min-verdict-confidence (0.8; --min-confidence is the
                        older spelling): invalidates duplicates and superseded
                        facts, unlinks compatible pairs. The gate is the
                        judge's confidence in its own verdict, which is why it
                        is not the --min-confidence that filters facts on read.
                        A contradicts verdict is never auto-resolved.
                        [--command CMD | --extractor CMD] (both resolve the
                        same chain: flag > $CLAIMGRAPH_LLM_CMD > config >
                        claude -p)
                        --sweep generates candidates the write path can't
                        see (exclusive-value pairs, decision-category facts
                        sharing an object across predicates), judges them,
                        and links genuine hits into the same pipeline.
  entity ensure       --name N [--type T] [--scope S]
  entity list         [--type T] [--scope S]
  entity rename       --from X --to Y [--scope S]  (old name kept as alias;
                        facts and history untouched)
  entity alias        --name X --alias Y [--scope S]
  entity merge        --from X --into Y [--scope S]  (repoints facts, carries
                        names as aliases, invalidates exposed duplicates)
  entity split        --from X --into \"A,B\" [--scope S]  (records derived-from
                        lineage; facts stay on the source for review)
  entity duplicates   Report likely-duplicate entity clusters

  Entity lookups everywhere resolve exact names, then aliases, then a unique
  case/separator-insensitive match (\"auth-service\" finds \"AuthService\");
  near-match resolutions self-heal by recording the queried name as an alias.
  predicates          List the vocabulary [--category C] [--status S] [--usage]
  predicate register  Coin an :x/* predicate: --id x/uses-pattern [--definition ...]
                        [--object-shape value|prose]. object-shape says what
                        this predicate's LITERAL objects are: value (default) a
                        datum, capped at 250 characters; prose a lesson or
                        rationale, capped at 1000 — the extracted-fact
                        admission screen reads the declaration off the row.
  predicate promote   Graduate a staging term: --from x/uses-pattern
                        --to core/uses-pattern [--definition ...] [--label ...]
                        [--category ...] [--object-kind ...] [--object-shape ...]
                        [--cardinality ...] [--maps-to ...]. Registers the
                        stable twin carrying the staging row's fields (each
                        flag overrides one), rewrites every fact onto it (term
                        rename, history untouched), deprecates the x/* id with
                        a replaced-by pointer — further writes to it fail with
                        the forwarding address.
  evidence            The raw bytes an episode was extracted from:
                        --episode ID | --hash SHA256 [--evidence-dir DIR]
                        Provenance past the summary: ingest-session and
                        ingest-notes keep their raw input as immutable
                        content-addressed artifacts in <db>.evidence/ —
                        what the extractor dropped is never unrecoverable.
  episode open        --source-type session-log|code|... [--ref REF]
  episode close       --episode ID --summary \"...\"
  episode list
  ingest              Batch assert JSONL (one fact per line): --file F | stdin
                        [--episode ID | --source-type T --ref R]
  ingest-code         Mechanical code analysis through the language-adapter
                        registry (no LLM): Clojure (edamame, built-in),
                        Kotlin (line parse, built-in), TypeScript/JavaScript
                        (dependency-cruiser via npx, version-pinned). Walks
                        the project root, runs every detected analyzer in
                        one pass under one episode; missing tooling (no npx)
                        skips that analyzer with a hint, never an error.
                        Unresolvable imports become external-scoped facts —
                        never a wrong local edge. Bring your own analyzer:
                        a code-analyzers map in .claimgraph/config.json
                        (config-file only) overrides a built-in's command,
                        disables one (\"typescript\": false), or adds a
                        language whose command emits the interchange format
                        (one JSON object per unit: unit, file, requires,
                        language; JSONL or array).
                        [--project DIR] (the root to analyse, default cwd;
                        --dir is the older spelling) [--scope code]
                        [--language clojure] (--language filters to one
                        analyzer; reconciliation stays scoped to it)
  ingest-session      LLM-extract durable facts from a session transcript
                        (plain text or Claude Code session JSONL): --file F | stdin
                        (session-extract is the older name and still works)
                        [--ref ID] [--dry-run] [--extractor \"claude -p\"]
                        Default extractor: $CLAIMGRAPH_LLM_CMD or \"claude -p\".
                        Extracted facts are capped at 0.7 confidence, source-type
                        session-log. Use --dry-run to review before ingesting.
  ingest-notes        Ingest the harness's auto-memory notes (the ambient
                        capture tier): delta-detects changed note files and
                        extracts only those, one episode per (file, revision).
                        [--harness claude-code] [--project DIR] [--notes-dir D]
                        [--dry-run] [--extractor \"claude -p\"]
                        The notes dir defaults per harness (honoring
                        $CLAUDE_CONFIG_DIR / $CODEX_HOME); override with
                        --notes-dir (--dir still works), $CLAIMGRAPH_NOTES_DIR,
                        or notes-dir in the project config.
                        Notes ingest as agent inference: source-type agent-note,
                        confidence capped at 0.65, never commitments (a decision
                        reported by a note is demoted to an observation). No
                        reconciliation: notes the harness compacts away fade by
                        disuse instead of being invalidated. The managed
                        claimgraph section of MEMORY.md is never re-consumed.
  ingest-adr          Mechanically parse decision records (no LLM): --adr-dir D
                        (--dir is the older spelling) |
                        --file F [--dry-run]; default dirs docs/adr,
                        docs/decisions, adr. Title/filename -> the ADR
                        entity; Status: -> has-status (a change supersedes,
                        history accumulates); Supersedes:/Superseded by: ->
                        revision edges; considered-options-minus-chosen and
                        Rejected: -> decided-against commitments. All at
                        decision-record authority (1.0).
  ingest-failure      Extract lessons from rejected/reverted/failed work
                        (review text, revert message, error transcript):
                        --file F | stdin [--context \"what was attempted\"]
                        [--ref ID] [--dry-run] [--extractor CMD]
                        The lesson, not the diff: known hazards land as
                        failure-mode facts, corrective practices as prefers,
                        human rulings as decided-against. Episode source-type
                        failure-report (the valence signal), raw material
                        kept as evidence. Capped at 0.7 like all extraction.
  compile-context     Compile the graph's current view into the managed
                        section of the file the harness auto-injects
                        (Claude Code: the head of MEMORY.md) — the ambient
                        read path. Deterministic (no LLM), budgeted,
                        idempotent; only the marker-delimited block is
                        rewritten, the harness's own notes stay untouched.
                        Sections in priority order: standing decisions,
                        open conflicts, recent supersessions, top current
                        facts by effective confidence (code-derived facts
                        excluded — the view carries what the code can't say).
                        [--harness claude-code] [--project DIR] [--notes-dir D]
                        [--inject-file F] (write target; default per harness,
                        relative to the notes dir or absolute)
                        [--budget 25000] [--dry-run]
  hooks install       Wire the ambient loop into the project's hook settings
                        (SessionEnd): every session ends with `hooks run`.
                        Idempotent; foreign hooks and other settings are
                        preserved. Default target
                        <project>/.claude/settings.json — override with
                        --settings-file / $CLAIMGRAPH_SETTINGS_FILE /
                        settings-file in the project config.
                        [--project DIR] [--harness claude-code]
                        [--settings-file F] [--coach] [--bin claim]
                        --coach also wires a UserPromptSubmit hook running
                        the gated push (see coach).
  hooks run           The SessionEnd pass, and CAPTURE only — deterministic
                        end to end, seconds, never waiting on a model:
                        ingest-code-if-changed (first, so the curator's
                        entity roster and conflict ground truth are fresh —
                        delta-gated on <git-sha>+<dirty-digest> against the
                        last :code episode, so it's free when nothing changed
                        and reconciles when anything did, including
                        teammates' pulled changes; non-git projects always
                        run), compile-context, then a DETACHED `claim curate`
                        it spawns and does not await (log: <db>.curate.log).
                        Every model call belongs to that curator.
                        Stages report independently — an analyzer failure
                        never blocks the deterministic recompile, and the
                        pass still exits 0 with \"status\":\"partial\" — a
                        hook that fails is worse than a hook that reports.
                        [--fail-on-partial] turns that same report into
                        exit 1 for CI.
                        [--harness claude-code] [--project DIR]
                        [--notes-dir D] [--inject-file F] [--extractor CMD]
                        [--code-ingest session-end|manual] (manual opts the
                        code stage out of the ambient loop)
                        [--no-curate] (capture only; run curate yourself)
  curate              The detached curation run `hooks run` spawns, also
                        runnable by hand: ingest-notes (the just-ended
                        session's knowledge is the freshest), then
                        consolidate (judge, summaries, sweep, enrichment —
                        enrich-only, never resolving unattended), then
                        compile-context so the next session's injected view
                        carries what curation learned. Stages are attempted
                        independently.
                        ONE model-call budget spans the run [--budget 20];
                        every call lands a durable outcome, so runs converge
                        toward a free no-op pass and whatever the budget did
                        not reach is named as deferred and picked up next
                        run. A SINGLETON: if another curator holds the
                        curation lease (<db>.curate.lock) this reports
                        \"already-running\" and exits 0 — the live one's
                        work covers this run.
                        [--harness claude-code] [--project DIR]
                        [--notes-dir D] [--inject-file F] [--extractor CMD]
  outcome             Close the loop on retrieved facts: claim outcome
                        accepted|rejected. Read verbs (facts/search/recall/
                        coach) log which facts they surfaced (<db>.retrievals);
                        accepted reinforces everything retrieved since the
                        last outcome mark (disuse clock reset — usefulness is
                        evidence of aliveness, never higher confidence);
                        rejected reinforces nothing and reports the facts
                        that were in play. Wire it to PR merge/close, or run
                        by hand; the rejection's lesson goes to ingest-failure.
  dump                Export everything as JSONL [--out FILE]. The first line
                        is a header record (\"record\":\"claimgraph-dump\", the
                        persisted-format version, the claimgraph that wrote
                        it), then the graph records one per line. Timestamps
                        carry milliseconds, so validity intervals shorter than
                        a second survive the file. --out reports :records (the
                        graph) and :lines (the file, header included) — the
                        second is what `wc -l` will say.
  load                Restore a store from a dump: --file F | stdin. The
                        two-way half of portability — fact/episode ids,
                        validity intervals, invalidation reasons, and
                        conflict links round-trip exactly (a raw restore;
                        the conflict machinery does NOT re-run). Refuses a
                        store that already holds data, and refuses a dump it
                        cannot read in full rather than restoring the part it
                        understands: a pre-alpha dump (no header, kinds keyed
                        on \"type\") lost its entity types when it was written,
                        so re-dump from the source store instead.
  mcp                 Serve the graph over MCP (stdio): the store opens once
                        per session instead of paying the ~350ms bb+pod cold
                        start per call. Tools: memory_facts, memory_search,
                        memory_recall, memory_history, memory_conflicts,
                        memory_coach, memory_assert (lease-guarded).
                        Wire up: claude mcp add claimgraph -- bin/claim mcp
  reconcile           Merge other writers' effect logs into this store.
                        Every write already appends to your own log in
                        <db>.oplog/<writer>.jsonl; sync that directory
                        between machines however you like (git, rsync,
                        Syncthing) and run reconcile on arrival. Foreign
                        effects apply in canonical clock order with entity
                        identity matched by name; claims both writers made
                        independently collapse non-lossily; contradictions
                        neither writer could see become sweep candidates
                        for the judge. Idempotent.
  stats               Store counts
  consolidate         Offline consolidation pass: LLM-summarize and close open
                        episodes that contain facts (summaries become
                        full-text searchable; mechanical digest if the LLM is
                        unavailable), judge open conflicts (report-only unless
                        --resolve), sweep for conflict candidates the write
                        path can't see, and report x/* predicates earning
                        promotion review. One shared model-call budget,
                        spent most-valuable-first; what it does not reach is
                        reported per stage as deferred and stays pending by
                        derivation for the next run.
                        [--resolve] [--min-verdict-confidence 0.8]
                        [--min-usage 3] [--budget 20]
                        [--command CMD | --extractor CMD] (default
                        $CLAIMGRAPH_LLM_CMD, then claude -p)
")

(defn cmd-help [_]
  (println help-text))

(declare table)

(defn- cmd-unknown
  "The table's catch-all. Bare `claim` is the help screen and a success;
  anything else that reaches here is a verb claimgraph does not have, and
  printing the whole help text to stdout and exiting 0 is how a typo in a
  SessionEnd hook command line reads as a session that went fine. The verbs
  are named the way babashka.cli names the subcommands of a verb it does
  know, so one error shape answers \"what could I have written\" either way.

  Canonical verbs only: an alias stays dispatchable and is never advertised.
  Handing somebody recovering from a typo the spelling the rename exists to
  retire is how a deprecated name gets written into brand-new command lines —
  by the tool that deprecated it."
  [{:keys [args]}]
  (if-let [verb (first args)]
    (logic/fail (str "Unknown command: " verb)
                {:type :unknown-command
                 :command verb
                 :expected (->> table
                                (remove :alias-of)
                                (keep (comp first :cmds))
                                distinct sort vec)
                 :claimgraph/exit usage-exit
                 :hint "run `claim help` for the full command list"})
    (cmd-help nil)))

(defn- expand-aliases
  "Pure: one dispatch entry per accepted spelling of a verb.

  A renamed verb has to answer to its old name for a long time — the name is
  already written into installed SKILL.md files, README snippets, and hook
  command lines on machines this release will never see, and none of those
  get fixed by the rename. Aliases carry :alias-of so anything reading the
  table can tell a second name from a second command."
  [entries]
  (into []
        (mapcat (fn [{:keys [cmds aliases] :as e}]
                  (let [e (dissoc e :aliases)]
                    (cons e (map #(assoc e :cmds % :alias-of cmds) aliases)))))
        entries))

(def table
  (expand-aliases
   [{:cmds ["setup"] :fn cmd-setup
     :spec {:coach {:coerce :boolean} :mcp {:coerce :boolean}
            :dry-run {:coerce :boolean} :budget {:coerce :long}}}
    {:cmds ["audit"] :fn cmd-audit
     :spec {:file {:coerce []} :dir {:coerce []} :scan-dir {:coerce []}
            :scorecard {:coerce :boolean}
            :no-code {:coerce :boolean} :no-judge {:coerce :boolean}}}
    {:cmds ["config"] :fn cmd-config}
    {:cmds ["version"] :fn cmd-version}
    {:cmds ["init"] :fn cmd-init}
    {:cmds ["assert"] :fn cmd-assert :spec {:confidence {:coerce :double}}}
    {:cmds ["facts"] :fn cmd-facts :spec {:min-confidence {:coerce :double}
                                          :include-invalidated {:coerce :boolean}}}
    {:cmds ["neighbor"] :fn cmd-neighbor :spec {:depth {:coerce :long}
                                                :budget {:coerce :long}
                                                :beam {:coerce :long}
                                                :min-confidence {:coerce :double}}}
    {:cmds ["recall"] :fn cmd-recall :spec {:min-hits {:coerce :long}}}
    {:cmds ["coach"] :fn cmd-coach :spec {:hook {:coerce :boolean}}}
    {:cmds ["outcome"] :fn cmd-outcome}
    {:cmds ["mcp"] :fn cmd-mcp}
    {:cmds ["history"] :fn cmd-history}
    {:cmds ["search"] :fn cmd-search}
    {:cmds ["invalidate"] :fn cmd-invalidate}
    {:cmds ["conflicts"] :fn cmd-conflicts}
    {:cmds ["judge"] :fn cmd-judge :spec {:resolve {:coerce :boolean}
                                          :sweep {:coerce :boolean}
                                          :min-confidence {:coerce :double}
                                          :min-verdict-confidence {:coerce :double}}}
    {:cmds ["entity" "ensure"] :fn cmd-entity-ensure}
    {:cmds ["entity" "list"] :fn cmd-entity-list}
    {:cmds ["entity" "rename"] :fn cmd-entity-rename}
    {:cmds ["entity" "alias"] :fn cmd-entity-alias}
    {:cmds ["entity" "merge"] :fn cmd-entity-merge}
    {:cmds ["entity" "split"] :fn cmd-entity-split}
    {:cmds ["entity" "duplicates"] :fn cmd-entity-duplicates}
    {:cmds ["predicates"] :fn cmd-predicates :spec {:usage {:coerce :boolean}}}
    {:cmds ["predicate" "register"] :fn cmd-predicate-register}
    {:cmds ["predicate" "promote"] :fn cmd-predicate-promote}
    {:cmds ["evidence"] :fn cmd-evidence}
    {:cmds ["episode" "open"] :fn cmd-episode-open}
    {:cmds ["episode" "close"] :fn cmd-episode-close}
    {:cmds ["episode" "list"] :fn cmd-episode-list}
    {:cmds ["ingest-code"] :fn cmd-ingest-code}
    {:cmds ["ingest"] :fn cmd-ingest}
    {:cmds ["ingest-session"] :fn cmd-ingest-session
     :aliases [["session-extract"]]
     :spec {:dry-run {:coerce :boolean}}}
    {:cmds ["ingest-notes"] :fn cmd-ingest-notes :spec {:dry-run {:coerce :boolean}}}
    {:cmds ["ingest-failure"] :fn cmd-ingest-failure :spec {:dry-run {:coerce :boolean}}}
    {:cmds ["ingest-adr"] :fn cmd-ingest-adr :spec {:dry-run {:coerce :boolean}}}
    {:cmds ["compile-context"] :fn cmd-compile-context
     :spec {:budget {:coerce :long} :dry-run {:coerce :boolean}}}
    {:cmds ["hooks" "run"] :fn cmd-hooks-run
     :spec {:fail-on-partial {:coerce :boolean} :no-curate {:coerce :boolean}}}
    {:cmds ["hooks" "install"] :fn cmd-hooks-install
     :spec {:coach {:coerce :boolean}}}
    {:cmds ["curate"] :fn cmd-curate :spec {:budget {:coerce :long}}}
    {:cmds ["dump"] :fn cmd-dump}
    {:cmds ["load"] :fn cmd-load}
    {:cmds ["reconcile"] :fn cmd-reconcile}
    {:cmds ["stats"] :fn cmd-stats}
    {:cmds ["consolidate"] :fn cmd-consolidate
     :spec {:resolve {:coerce :boolean} :min-confidence {:coerce :double}
            :min-verdict-confidence {:coerce :double}
            :min-usage {:coerce :long} :budget {:coerce :long}}}
    {:cmds ["help"] :fn cmd-help}
    {:cmds [] :fn cmd-unknown}]))

(defn- emit-error!
  "The error contract, in the one place that implements it: JSON on stderr,
  never stdout, whatever went wrong. Returns the status so the caller decides
  nothing."
  [payload code]
  (binding [*out* *err*]
    (println (wire/generate-string payload {:pretty true})))
  code)

(defn- usage-payload
  "babashka.cli's own parse failures, in claimgraph's error shape. They travel
  as ex-data on an ExceptionInfo with NO message, so the generic handler
  emitted {\"error\": null} — from the one failure mode a typo in a hook
  command line actually produces, to a caller whose only contract is that
  :error says what happened.

  Two unrelated mistakes arrive under that one :type, and only :cause tells
  them apart: a verb claimgraph does not have, and a VALUE it could not read.
  A coercion failure carries an :option and no :dispatch at all, so rendering
  every failure as the first told a user whose verb was fine that the verb did
  not exist, named the empty string as what they attempted, offered [] as the
  alternatives — and never mentioned the flag actually at fault."
  [{:keys [dispatch wrong-input all-commands cause option value msg spec]}]
  (cond
    (= :coerce cause)
    (let [flag (str "--" (name option))
          t (some-> (get-in spec [option :coerce]) name)]
      {:error (str "Invalid value for " flag ": " (pr-str value)
                   (when t (str " (expected a " t ")")))
       :type :invalid-option-value
       :option flag
       :value value
       :hint (str "run `claim help` for what " flag " takes")})

    ;; :require / :validate / :restrict. claimgraph's specs declare none of
    ;; them, so babashka's own message is passed through rather than
    ;; paraphrased into a shape no command line can currently produce.
    option
    {:error (or msg (str "Invalid option: --" (name option)))
     :type :invalid-option
     :option (str "--" (name option))
     :hint "run `claim help` for the options this command takes"}

    :else
    (let [attempted (str/join " " (remove nil? (concat dispatch [wrong-input])))
          exhausted (= :input-exhausted cause)]
      {:error (if exhausted
                (str "Incomplete command: `" attempted "` needs a subcommand")
                (str "Unknown command: " attempted))
       :type (if exhausted :incomplete-command :unknown-command)
       :command attempted
       :expected (vec (sort (map str all-commands)))
       :hint "run `claim help` for the full command list"})))

(defn run
  "Dispatch one command line; return the process exit status. Separate from
  -main because a status is a value a test can read and System/exit is not."
  [args]
  (try
    (let [r (cli/dispatch table (vec args) {:spec global-spec})]
      (if (int? r) r ok-exit))
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (if (= :org.babashka/cli (:type d))
          (emit-error! (usage-payload d) usage-exit)
          (emit-error! (merge {:error (ex-message e)}
                              (dissoc d :claimgraph/error :claimgraph/exit))
                       (or (:claimgraph/exit d) error-exit)))))))

(defn -main [& args]
  (let [code (run args)]
    ;; flush before exiting: System/exit gives stdout no chance to drain, and
    ;; the report a non-zero status refers to is on stdout
    (flush)
    (when-not (zero? code) (System/exit code))))
