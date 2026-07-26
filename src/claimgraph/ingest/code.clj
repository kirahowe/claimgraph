(ns claimgraph.ingest.code
  "Language-agnostic mechanical code ingester (docs/language-adapters.md):
  the driver behind `ingest-code` and the ambient code-freshness stage of
  `hooks run`.

  A registry of analyzer adapters — Clojure via edamame, Kotlin via an
  internal line parse, TypeScript/JavaScript by shelling to a pinned
  dependency-cruiser, plus anything a project adds under `code-analyzers`
  in .claimgraph/config.json — each produces the same tiny interchange
  format, one map per source unit (external commands emit it as JSONL or a
  JSON array):

      {\"unit\": \"shoply.auth\", \"file\": \"src/shoply/auth.clj\",
       \"requires\": [\"shoply.db\", \"external:react\"],
       \"language\": \"clojure\"}

  The interchange format is the only thing that crosses the analyzer
  boundary: everything past it — fact derivation, the reconciliation plan,
  the pass itself — is shared, pure where possible, and identical for every
  language. Facts, all :source-type :code / :epistemic :observation:

      <unit> core/defined-in <file>       (cardinality :one — file-grained
                                           units only, never package-grained)
      <file> core/written-in \"<language>\"
      <unit> core/depends-on <unit>       (scope \"external\" when external)

  Unprefixed `requires` resolve against the emitted unit set; anything
  unmatched is scoped \"external\" — a resolution miss can create an
  external-scoped fact, never a wrong local edge.

  Each pass reconciles against the previous one: code-sourced facts the new
  analysis no longer produces are invalidated mechanically (non-lossy).
  Reconciliation is language-guarded: an adapter that skipped (missing
  tooling) or failed never gets its facts invalidated by a pass that didn't
  look at its files — degraded, never wrong. Missing tooling degrades the
  adapter to :skipped with a hint; it never blocks the pass or the ambient
  loop."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.config :as config]
            [claimgraph.core :as core]
            [claimgraph.ingest.clj-code :as clj-code]
            [claimgraph.ingest.kotlin-code :as kotlin-code]
            [claimgraph.ingest.ts-code :as ts-code]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Pure: the interchange format
;; ---------------------------------------------------------------------------

(def external-prefix "external:")

(defn parse-interchange
  "Analyzer output -> unit maps. Accepts a JSON array or JSONL (one object
  per line) — the default :parse for config-added analyzers whose command
  emits the interchange format directly."
  [s]
  (let [s (str/trim (str s))]
    (cond
      (str/blank? s) []
      (str/starts-with? s "[") (vec (json/parse-string s true))
      :else (into []
                  (comp (remove str/blank?)
                        (map #(json/parse-string % true)))
                  (str/split-lines s)))))

(defn- normalize-units
  "Validate interchange units and stamp the adapter's language/unit-type
  defaults. Invalid units fail the adapter (isolated to an :error entry),
  never the pass."
  [adapter units]
  (mapv (fn [u]
          (when-not (and (map? u)
                         (string? (:unit u)) (not (str/blank? (:unit u)))
                         (string? (:file u)) (not (str/blank? (:file u))))
            (logic/fail (str "invalid interchange unit from the "
                             (name (:id adapter)) " analyzer: " (pr-str u))
                        {:type :invalid-interchange :adapter (:id adapter)}))
          {:unit (:unit u)
           :file (:file u)
           :requires (vec (distinct (map str (or (:requires u) []))))
           :language (or (:language u) (:language adapter))
           :unit-type (:unit-type adapter :module)})
        units))

;; ---------------------------------------------------------------------------
;; Pure: units -> facts, and the reconciliation plan
;; ---------------------------------------------------------------------------

(defn units->facts
  "Pure: interchange units (each carrying :language and :unit-type) -> fact
  maps ready for claimgraph.core/ingest. Requires prefixed `external:` — and
  unprefixed ones matching no emitted unit — are scoped \"external\"."
  [units scope]
  (let [local (set (map :unit units))
        base {:scope scope :source-type :code :confidence 0.95 :epistemic :observation}]
    (vec (mapcat
          (fn [{:keys [unit file requires language unit-type]
                :or {unit-type :module}}]
            (concat
             [(merge base {:subject unit :subject-type unit-type
                           :predicate :core/defined-in
                           :object file :object-type :file :object-kind :entity})
              (merge base {:subject file :subject-type :file
                           :predicate :core/written-in
                           :object (str language) :object-kind :literal})]
             (for [r requires
                   :let [ext? (str/starts-with? r external-prefix)
                         target (if ext? (subs r (count external-prefix)) r)]]
               (merge base {:subject unit :subject-type unit-type
                            :predicate :core/depends-on
                            :object target :object-type unit-type :object-kind :entity
                            :scope (if (and (not ext?) (local target)) scope "external")}))))
          units))))

(defn- fact-key
  "Identity of a code fact for reconciliation: subject name, predicate,
  object name-or-literal."
  [f]
  [(get-in f [:subject :name])
   (:predicate f)
   (if (= :entity (:object-kind f))
     (get-in f [:object-ref :name])
     (:object-lit f))])

(defn- fact-language
  "Attribute prior code facts to a language using the candidate set itself:
  file subjects through their own written-in fact, unit subjects through
  defined-in -> written-in. -> (fn [fact] language-string-or-nil)."
  [candidates]
  (let [file-lang (into {} (keep (fn [f]
                                   (when (= :core/written-in (:predicate f))
                                     [(get-in f [:subject :name]) (:object-lit f)]))
                                 candidates))
        unit-file (into {} (keep (fn [f]
                                   (when (= :core/defined-in (:predicate f))
                                     [(get-in f [:subject :name])
                                      (get-in f [:object-ref :name])]))
                                 candidates))]
    (fn [f]
      (let [subj (get-in f [:subject :name])]
        (or (file-lang subj) (file-lang (unit-file subj)))))))

(defn stale-facts
  "Pure reconciliation plan: ids of currently-valid code-sourced facts (in
  this ingest's scopes) that the new analysis no longer produces. With
  :languages (a set of language strings), only facts attributable to those
  languages are eligible — a partial pass (an adapter skipped or errored, or
  a --language filter) must never invalidate facts belonging to files it
  didn't look at. Without :languages every candidate is eligible (a complete
  pass, where absence means deletion)."
  [facts new-facts {:keys [scope at languages]}]
  (let [scopes #{scope "external"}
        produced (set (map (fn [m] [(:subject m) (:predicate m) (str (:object m))])
                           new-facts))
        in-scope (->> facts
                      (filter #(logic/fact-valid-at? % at))
                      (filter #(= :code (:source-type %)))
                      (filter #(scopes (:scope %))))
        lang-of (when languages (fact-language in-scope))
        eligible (if languages
                   (filter #(contains? languages (lang-of %)) in-scope)
                   in-scope)]
    (->> eligible
         (remove (comp produced fact-key))
         (mapv :id))))

;; ---------------------------------------------------------------------------
;; The registry
;; ---------------------------------------------------------------------------

(def builtin
  "The built-in analyzer adapters. Each entry declares how to detect its
  language (a glob against the project root, honoring :ignore directory
  names — never a hardcoded src/), what it emits (:language, :unit-type),
  what it costs (:cost :fast analyzers are fine to run eagerly; :slow ones
  are the reason the `code-ingest` setting exists), and exactly one of
  :analyze-fn (internal, root -> interchange units) or :command + :parse
  (external; :prereq is the binary `setup/check-prerequisites`-style
  lookup checks before shelling out). Output-schema expectations for an
  external tool live in its :parse and nowhere else — one pinned seam per
  upstream, the harness-registry rule."
  [{:id :clojure
    :label "Clojure (edamame, built-in)"
    :detect "**.{clj,cljc,cljs,bb}"
    :ignore #{".claimgraph" "node_modules" ".git" "target"}
    :language "clojure"
    :unit-type :namespace
    :cost :fast
    :analyze-fn clj-code/analyze-root}
   {:id :kotlin
    :label "Kotlin (line parse, built-in)"
    :detect "**.kt"
    :ignore #{".claimgraph" ".git" ".gradle" "build" "out"}
    :language "kotlin"
    :unit-type :module
    :cost :fast
    :analyze-fn kotlin-code/analyze-root}
   {:id :typescript
    :label "TypeScript/JavaScript (dependency-cruiser)"
    :detect "**.{ts,tsx,mts,cts,js,jsx}"
    :ignore #{"node_modules" "dist" "build" ".git" ".claimgraph"}
    :language "typescript"
    :unit-type :module
    :cost :slow
    :prereq "npx"
    :command ts-code/command
    :parse ts-code/parse}])

(defn registry
  "The built-in adapters merged with the project's `code-analyzers` config
  map — config-file only (structured values don't fit flags/env), keyed by
  adapter id. Override fields of a built-in, disable one entirely
  (\"typescript\": false), or add a new language whose :command emits the
  interchange format directly (:parse defaults to reading it as-is) — Rust
  or Python support is a ten-line script in the user's repo, no claimgraph
  change."
  ([] (registry (:code-analyzers (config/read-config-file (config/config-file-path)))))
  ([overrides]
   (let [base (reduce #(assoc %1 (:id %2) %2) (array-map) builtin)]
     (->> (reduce-kv
           (fn [acc k v]
             (let [id (logic/->kw k)]
               (cond
                 (false? v) (dissoc acc id)
                 (map? v) (assoc acc id
                                 (-> (merge (cond-> (get acc id)
                                              ;; a :command override replaces an
                                              ;; internal analyzer outright
                                              (:command v) (dissoc :analyze-fn))
                                            v)
                                     (assoc :id id)
                                     (update :language #(or % (name id)))
                                     (update :unit-type #(logic/->kw (or % :module)))
                                     (update :ignore #(set (map name (or % #{".git" ".claimgraph" "node_modules"}))))
                                     (update :parse #(or % parse-interchange))))
                 :else acc)))
           base (or overrides {}))
          vals
          vec))))

(defn source-files
  "The adapter's source files under root: its :detect glob, minus any path
  containing an :ignore directory name."
  [root {:keys [detect ignore]}]
  (->> (fs/glob root detect)
       (remove (fn [p] (some (or ignore #{})
                             (map str (fs/components (fs/relativize root p))))))
       (sort-by str)
       vec))

(defn detect
  "Adapters whose :detect glob matches at least one file under root —
  detection walks the project root (honoring :ignore), never a hardcoded
  src/."
  ([root] (detect root (registry)))
  ([root adapters]
   (filterv #(seq (source-files root %)) adapters)))

;; ---------------------------------------------------------------------------
;; Shell: running one adapter
;; ---------------------------------------------------------------------------

(defn- sh-command!
  "The default :command-fn — run the command string in dir, return stdout,
  throw on non-zero exit. Injectable for tests, exactly the extractor-fn
  pattern."
  [{:keys [dir command]}]
  (let [{:keys [exit out err]} @(p/process (p/tokenize command)
                                           {:dir (str dir) :out :string :err :string})]
    (when-not (zero? exit)
      (logic/fail (str "analyzer command failed: " command)
                  {:type :analyzer-command-failed :exit exit
                   :stderr (str/trim (or err ""))}))
    out))

(defn- command-roots
  "The repo-relative top-level entries containing the adapter's matched
  files — substituted for <roots> so the external tool scans only real
  source roots, never node_modules and friends."
  [root files]
  (->> files
       (map #(str (first (fs/components (fs/relativize root %)))))
       distinct
       sort
       vec))

(defn run-adapter
  "One adapter over root -> {:id :language :status :files :units | :hint |
  :error}. :skipped (missing prerequisite tooling) carries a hint and never
  blocks; :error isolates a broken tool or malformed output to this adapter."
  [root {:keys [analyze-fn command parse prereq] :as adapter}
   {:keys [command-fn which]}]
  (let [which (or which #(some-> (fs/which %) str))
        base {:id (:id adapter) :language (:language adapter)}]
    (try
      (cond
        analyze-fn
        (let [units (normalize-units adapter (analyze-fn root))]
          (assoc base :status :ok :units units :files (count units)))

        command
        (let [bin (or prereq (first (str/split command #"\s+")))]
          (if-not (which bin)
            (assoc base :status :skipped
                   :hint (str "'" bin "' is not on PATH — install it to analyze "
                              (:language adapter) ", or disable the analyzer with "
                              "{\"code-analyzers\": {\"" (name (:id adapter))
                              "\": false}} in .claimgraph/config.json"))
            (let [roots (command-roots root (source-files root adapter))
                  cmd (str/replace command "<roots>" (str/join " " roots))
                  run (or command-fn sh-command!)
                  units (normalize-units adapter (parse (run {:dir (str root) :command cmd})))]
              (assoc base :status :ok :units units :files (count units)))))

        :else
        (assoc base :status :error
               :error "adapter declares neither :analyze-fn nor :command"))
      (catch Exception e
        (assoc base :status :error :error (ex-message e))))))

;; ---------------------------------------------------------------------------
;; Shell: git, and the delta gate
;; ---------------------------------------------------------------------------

(defn- git [dir & args]
  (try
    (let [{:keys [exit out]} (apply p/sh {:dir (str dir)} "git" args)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn- short-hash [s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) d)) 0 12)))

(defn current-ref
  "The pass's episode ref and the delta gate's key:
  <git-sha>[+<dirty-digest>], where the digest is a short hash of
  `git status --porcelain` output — uncommitted edits move the ref, so
  sessions that end dirty still gate correctly. Outside git: the absolute
  root path (which the gate treats as always stale — matching the old
  manual, run-every-time semantics)."
  [root]
  (if-let [sha (git root "rev-parse" "HEAD")]
    (let [porcelain (git root "status" "--porcelain")]
      (if (str/blank? porcelain) sha (str sha "+" (short-hash porcelain))))
    (str root)))

(defn git-ref?
  "A gate-comparable ref: hex sha, optionally +digest. Path refs (non-git
  roots) never qualify."
  [ref]
  (boolean (re-matches #"[0-9a-f]{7,64}(\+[0-9a-f]{6,64})?" (str ref))))

(defn latest-code-ref
  "The newest :code episode's ref (by :opened-at) — the episode log IS the
  delta state, no extra bookkeeping surface."
  [episodes]
  (->> episodes
       (filter #(= :code (:source-type %)))
       (sort-by #(some-> ^java.util.Date (:opened-at %) .getTime))
       last
       :ref))

(defn code-stale?
  "Pure delta gate: has the code moved since the newest :code episode?
  A non-git path ref is always stale (without git the gate can't see
  edits), and a `+partial` episode ref never satisfies the gate (a failed
  analyzer retries on the next run)."
  [current-ref episodes]
  (or (not (git-ref? current-ref))
      (not= current-ref (latest-code-ref episodes))))

;; ---------------------------------------------------------------------------
;; Shell: the pass
;; ---------------------------------------------------------------------------

(defn ingest!
  "One code-analysis pass against the store: run every detected analyzer
  (all languages, one episode), invalidate what the analysis no longer
  produces, assert what it does (unchanged facts no-op and reinforce, moved
  units supersede), all under a :code episode ref'd to
  <git-sha>[+<dirty-digest>].

  A skipped analyzer (missing tooling) or a failed one degrades the pass,
  never breaks it: its facts are exempt from invalidation, the rest of the
  pass proceeds, and per-analyzer statuses ride along under :analyzers. An
  analyzer :error marks the episode ref `+partial` so the ambient gate
  retries next run; :status is :partial in that case, :ok otherwise.

  opts: :dir (project root; default cwd) :scope (default \"code\")
        :language (run one adapter only; reconciliation stays scoped to it)
        :analyzers (the code-analyzers override map; default from the
        project config file) :command-fn :which (injectable, tests)"
  [s {:keys [scope language analyzers] :as opts}]
  (let [root (fs/canonicalize (or (:dir opts) "."))
        scope (or scope "code")
        adapters (if analyzers (registry analyzers) (registry))
        detected (detect root adapters)
        wanted (if language
                 (filterv #(= (logic/->kw language) (:id %)) detected)
                 detected)]
    (if (empty? wanted)
      {:status :skipped
       :ref (current-ref root)
       :note (if language
               (str "no " (name (logic/->kw language)) " sources detected under " root)
               (str "no analyzable sources under " root " (analyzers: "
                    (str/join ", " (map (comp name :id) adapters))
                    "; add your own via code-analyzers in .claimgraph/config.json)"))}
      (let [runs (mapv #(run-adapter root % opts) wanted)
            ok-runs (filterv #(= :ok (:status %)) runs)
            errored? (boolean (some #(= :error (:status %)) runs))
            analyzers-report (mapv #(dissoc % :units) runs)]
        (if (empty? ok-runs)
          ;; nothing analyzed: report without touching the store (no episode,
          ;; so the gate stays stale and the next run retries)
          {:status (if errored? :partial :skipped)
           :ref (current-ref root)
           :analyzers analyzers-report}
          (let [units (vec (mapcat :units ok-runs))
                facts (units->facts units scope)
                complete? (and (nil? language) (= (count ok-runs) (count wanted)))
                ref (cond-> (current-ref root) (or errored? language) (str "+partial"))
                at (core/now)
                candidates (store/-select-facts s {:source-type :code
                                                   :scopes #{scope "external"}
                                                   :valid-cheap true})
                stale (stale-facts candidates facts
                                   {:scope scope :at at
                                    :languages (when-not complete?
                                                 (set (map :language ok-runs)))})
                files (reduce + 0 (map :files ok-runs))]
            ;; :code-absent as structure, not only in the sentence: a reader
            ;; deciding whether a retired fact can be re-derived from the source
            ;; has to know THAT it vanished from the code, and a prose-only
            ;; reason leaves it matching a regex per producer. No successor —
            ;; the declaration is gone, nothing took its place.
            (doseq [id stale]
              (store/-invalidate s id at
                                 {:kind :code-absent
                                  :reason (str "code-invalidation: absent at " ref)}))
            (let [result (core/ingest s {:source-type :code :ref ref} facts)]
              (core/close-episode s {:episode (:episode result)
                                     :summary (str "code-analysis pass ("
                                                   (str/join ", " (map :language ok-runs))
                                                   "): " files " files, "
                                                   (count facts) " facts, "
                                                   (count stale) " invalidated, ref " ref)})
              (assoc result
                     :status (if errored? :partial :ok)
                     :files files :ref ref :invalidated (count stale)
                     :analyzers analyzers-report))))))))

(defn ingest-if-changed!
  "The ambient code-freshness stage (spec §5): run the pass only when the
  code moved — the current ref against the newest :code episode's. Same ref
  -> milliseconds; changed ref -> the reconciling pass (idempotent,
  non-lossy), which also picks up teammates' pulled changes with no agent
  judgment in the loop."
  [s {:keys [dir] :as opts}]
  (let [root (fs/canonicalize (or dir "."))
        ref (current-ref root)]
    (if-not (code-stale? ref (store/-list-episodes s))
      {:status :skipped :ref ref :reason "code unchanged since the last pass"}
      (ingest! s opts))))
