(ns claimgraph.hooks
  "The ambient loop's automation (docs/consuming-auto-memory.md §4): one verb
  a Claude Code SessionEnd hook can call, and an installer that wires it into
  the project's hook configuration.

  `hooks run` is CAPTURE, and capture is deterministic (spec/maintenance.allium,
  rule AmbientSessionEnd): ingest-code-if-changed → compile-context → spawn a
  DETACHED curator. It runs in seconds and never waits on a model. With
  --detach — the form `hooks install` wires in — the hook itself is only a
  spawn: it opens no store and runs no stage, re-invoking that same pass as a
  detached child that logs to <db>.capture.log, so the session's exit costs a
  process start (~0.1s) whatever the code delta is. Attached is what a human
  or CI gets by asking for it, and what --fail-on-partial reports on.

  The code stage runs FIRST, so the curator's entity roster and conflict ground
  truth are fresh, and is delta-gated on <git-sha>+<dirty-digest> against the
  newest :code episode's ref — free when nothing changed, reconciling when
  anything did, including teammates' pulled changes; the `code-ingest` setting
  (session-end | manual) opts it out. The recompile is not redundant with the
  curator's: this one guarantees the freshest DETERMINISTIC view even if the
  curator never starts, and the curator's adds what extraction and judging
  learned. Stages are attempted independently — a failed stage is an :error
  entry, never an abort.

  Neither hand-off is a call — curation decided 2026-08-05, capture
  2026-08-07: each child is started with its output redirected
  (<db>.curate.log, <db>.capture.log) and is never awaited, so the session's
  exit waits on nothing at all. Curation moved out first, because an inline
  ingest-notes + consolidate pass put dozens of LLM shell-outs inside a bounded
  lifecycle hook: it hit the 600s timeout every session, landed nothing, and
  re-queued everything. Capture followed for the same reason at a smaller
  scale — a code-reconciliation pass, ~7.6s exactly when the session had
  touched code, sat between the user and their next prompt. The timeout here
  is sized for a spawn and hitting it is a bug, not a budget.

  `hooks install` merges a SessionEnd entry into the project's hook settings
  (default <project>/.claude/settings.json; overridable via --settings-file /
  $CLAIMGRAPH_SETTINGS_FILE / the project config), idempotently: re-running
  updates the claimgraph entry in place and never duplicates it; everything
  else in the file is preserved."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.context :as context]))

(def hook-timeout-seconds
  "The SessionEnd hook's timeout, sized for what the hook does: start one
  detached process and say where its log is — bb's startup plus a spawn, ~0.1s
  measured. Two orders of magnitude of headroom over that, because a hook that
  is killed leaves the session with no capture at all. A run that reaches this
  bound is a bug to investigate, not a budget being spent."
  10)

;; ---------------------------------------------------------------------------
;; hooks run
;; ---------------------------------------------------------------------------

(defn- attempt [f]
  (try (f)
       (catch Exception e
         (merge {:status :error :error (ex-message e)}
                (dissoc (ex-data e) :claimgraph/error)))))

(defn curate-log
  "Where a spawned curator's stdout and stderr land: a sibling of the store,
  like every other artifact derived from it. A detached process reports to
  nobody, so this file is its entire crash-visibility surface."
  [db]
  (str db ".curate.log"))

(defn capture-log
  "Where a detached capture pass's stdout and stderr land: a sibling of the
  store, like every other artifact derived from it. A detached process reports
  to nobody, so this file is its entire crash-visibility surface — it holds the
  pass's own JSON report and anything the pass printed to stderr."
  [db]
  (str db ".capture.log"))

(defn curate-args
  "Pure: the argv `claim curate` is spawned with. Only settings this run
  actually resolved are passed — everything else the child resolves through
  the same flag > env > config > default chain the parent would have, and
  passing a value we merely defaulted would freeze the parent's default into
  the child's command line."
  [{:keys [harness db dir inject-file extractor budget]}]
  (cond-> ["curate" "--harness" (name (or harness :claude-code)) "--db" (str db)]
    dir (conj "--notes-dir" (str dir))
    inject-file (conj "--inject-file" (str inject-file))
    extractor (conj "--extractor" (str extractor))
    budget (conj "--budget" (str budget))))

(defn capture-args
  "Pure: the argv the detached capture pass is spawned with — this same verb,
  attached. Same philosophy as curate-args: only settings this invocation was
  explicitly given are forwarded, and everything else the child resolves
  through the flag > env > config > default chain, including the harness (its
  default is this process's default). Freezing a value we merely defaulted into
  the child's command line would outrank a config the child reads for itself.

  Never --detach: the child IS the attached pass, which is the point. Never
  --fail-on-partial: a detached child's exit status reports to nobody, so the
  partial report belongs in the log, not in a status no one reads."
  [{:keys [harness db project dir inject-file extractor code-ingest no-curate]}]
  (cond-> ["hooks" "run"]
    harness (conj "--harness" (name harness))
    db (conj "--db" (str db))
    project (conj "--project" (str project))
    dir (conj "--notes-dir" (str dir))
    inject-file (conj "--inject-file" (str inject-file))
    extractor (conj "--extractor" (str extractor))
    code-ingest (conj "--code-ingest" (name code-ingest))
    no-curate (conj "--no-curate")))

(defn- claim-bin
  "The claim executable to spawn: a repo-local bin/claim when the project has
  one, else whatever is on PATH. Absolute, because the child is started with
  the project as its working directory but nothing guarantees the parent's."
  [project]
  (let [local (fs/path (or project ".") "bin" "claim")]
    (if (fs/exists? local) (str local) "claim")))

(defn spawn-curator!
  "Start the curator and walk away. Not awaited, not deref'd: the exiting
  session hands curation off and returns, and the child outlives it.

  Both streams are redirected (in APPEND mode, so two descriptors onto one
  file interleave instead of overwriting each other) into a log truncated per
  run — a curator that dies leaves the reason there, and the work it did not
  do is still pending by derivation for the next run."
  [{:keys [db project] :as opts}]
  (let [log (curate-log db)
        args (curate-args opts)
        bin (claim-bin project)]
    (fs/create-dirs (fs/parent (fs/absolutize log)))
    (fs/delete-if-exists log)
    (apply process/process
           {:dir (str (or project "."))
            :out :append :out-file (fs/file log)
            :err :append :err-file (fs/file log)}
           bin args)
    {:status :spawned :log log :command (into [bin] args)}))

(defn spawn-capture!
  "Start the capture pass and walk away. The child is this same verb attached,
  so the pass is unchanged — only who waits for it is: the hook returns as soon
  as the process exists, and the third stage of the child's own run is the
  curator spawn, so the chain is hook → capture → curator with nobody blocking.

  Both streams are redirected (in APPEND mode, so two descriptors onto one
  file interleave instead of overwriting each other) into a log truncated per
  run — the pass's JSON report lands there, a pass that dies leaves the reason
  there, and the work it did not do is still owed by derivation at the next
  session end."
  [{:keys [db project] :as opts}]
  (let [log (capture-log db)
        args (capture-args opts)
        bin (claim-bin project)]
    (fs/create-dirs (fs/parent (fs/absolutize log)))
    (fs/delete-if-exists log)
    (apply process/process
           {:dir (str (or project "."))
            :out :append :out-file (fs/file log)
            :err :append :err-file (fs/file log)}
           bin args)
    {:status :spawned :log log :command (into [bin] args)}))

(defn run!
  "The SessionEnd pass: refresh code facts when the code moved, recompile the
  injected view, hand curation to a detached process. Every stage here is
  deterministic; every model call belongs to the curator.

  opts: :db (the store path — the curator's --db and its log's location)
        :code-ingest (\"session-end\" default | \"manual\")
        :command-fn :which :analyzers (ingest-code, injectable for tests)
        :harness :project :dir :ctx (harness resolution)
        :inject-file (compile-context's write-target override)
        :extractor :budget (forwarded to the curator when set)
        :no-curate (skip the hand-off; run `claim curate` yourself)
        :spawn-fn (injectable, tests)

  Note :budget here is the curator's MODEL-CALL budget, not compile-context's
  byte budget — the compile takes its own default, which is the only budget
  the deterministic half of this pass has ever had."
  [s {:keys [code-ingest no-curate spawn-fn] :as opts}]
  (let [code (if (= "manual" (some-> code-ingest name))
               {:status :skipped :reason "code-ingest is set to manual — run ingest-code yourself"}
               (attempt #((requiring-resolve 'claimgraph.ingest.code/ingest-if-changed!)
                          s (assoc (select-keys opts [:command-fn :which :analyzers])
                                   :dir (:project opts)))))
        ;; before the hand-off, so the deterministic view lands even if the
        ;; curator never starts
        compiled (attempt #(context/compile! s (select-keys opts [:harness :project :dir
                                                                  :ctx :inject-file])))
        curator (if no-curate
                  {:status :skipped
                   :reason "curation opted out (--no-curate) — run `claim curate` yourself"}
                  (attempt #((or spawn-fn spawn-curator!)
                             (select-keys opts [:harness :db :project :dir
                                                :inject-file :extractor :budget]))))]
    {:status (if (some #(contains? #{:error :partial} (:status %))
                       [code compiled curator])
               :partial :ok)
     :ingest-code code
     :compile-context compiled
     :curator curator}))

;; ---------------------------------------------------------------------------
;; hooks install
;; ---------------------------------------------------------------------------

(defn install-plan
  "Pure: merge one claimgraph hook entry into settings under an event key.
  `marker` identifies our entry among foreign hooks (idempotent: replaced
  in place when present, appended otherwise); everything else in the file
  is untouched."
  [settings event entry marker]
  (let [existing (vec (get-in settings [:hooks event]))
        ours? (fn [e] (some #(str/includes? (str (:command %)) marker)
                            (:hooks e)))]
    (assoc-in settings [:hooks event]
              (if (some ours? existing)
                (mapv #(if (ours? %) entry %) existing)
                (conj existing entry)))))

(defn install!
  "Wire the ambient loop into the project's hook settings: a SessionEnd
  entry always, and with :coach also a UserPromptSubmit entry that runs the
  gated push (claim coach --hook) — a briefing lands only when standing
  decisions, failure modes, or open conflicts touch the task.
  opts: :project (default cwd) :harness (default claude-code)
        :settings-file (where the harness reads hook config from; default
        <project>/.claude/settings.json — override for settings.local.json,
        a relocated config dir, or another harness's layout)
        :coach :bin (the claim executable for the hook command; auto-detects
        a repo-local bin/claim, else assumes PATH)"
  [{:keys [project harness settings-file coach bin]}]
  (let [project (str (fs/canonicalize (or project ".")))
        settings-file (str (or settings-file
                               (fs/path project ".claude" "settings.json")))
        settings (if (fs/exists? settings-file)
                   (json/parse-string (slurp settings-file) true)
                   {})
        bin (or bin
                (if (fs/exists? (fs/path project "bin" "claim"))
                  "bin/claim" "claim"))
        run-cmd (str bin " hooks run --harness " (name (or harness :claude-code))
                     " --detach")
        coach-cmd (str bin " coach --hook")
        updated (cond-> (install-plan settings :SessionEnd
                                      {:hooks [{:type "command" :command run-cmd
                                                :timeout hook-timeout-seconds}]}
                                      "hooks run")
                  coach (install-plan :UserPromptSubmit
                                      {:hooks [{:type "command" :command coach-cmd
                                                :timeout 30}]}
                                      "coach --hook"))
        added? (not= (count (get-in settings [:hooks :SessionEnd]))
                     (count (get-in updated [:hooks :SessionEnd])))]
    (fs/create-dirs (fs/parent settings-file))
    (spit settings-file (str (json/generate-string updated {:pretty true}) "\n"))
    (cond-> {:status (if added? :installed :updated)
             :settings settings-file
             :command run-cmd
             :note (str "every session end now costs a spawn: the capture pass "
                        "(ingest-code-if-changed + compile-context) runs detached, "
                        "logging to <db>.capture.log, and hands curation (notes "
                        "extraction, judging, summaries, enrichment) to a detached "
                        "`claim curate`, logging to <db>.curate.log")}
      coach (assoc :coach-command coach-cmd))))
