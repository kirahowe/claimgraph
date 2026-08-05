(ns claimgraph.hooks-test
  "The ambient loop's automation: install-plan as a pure function over
  settings maps, install! against a temp project, and the SessionEnd pass with
  its analyzer and its curator hand-off injected — no LLM, no real ~/.claude.

  The pass under test is CAPTURE: a delta-gated code pass, a deterministic
  recompile, and a spawn nobody waits for. What it must NOT do is as much the
  subject as what it must — no extraction, no consolidation, no model call
  inside a bounded lifecycle hook."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.hooks :as hooks]
            [claimgraph.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Pure: the settings merge
;; ---------------------------------------------------------------------------

(defn- entry [cmd] {:hooks [{:type "command" :command cmd}]})

(deftest install-plan-is-idempotent-and-preserves-neighbors
  (let [foreign (entry "echo bye")
        base {:permissions {:allow ["Bash(bb test)"]}
              :hooks {:SessionEnd [foreign]}}
        v1 (hooks/install-plan base :SessionEnd
                               (entry "claim hooks run --harness claude-code")
                               "hooks run")
        v2 (hooks/install-plan v1 :SessionEnd
                               (entry "claim hooks run --harness codex")
                               "hooks run")]
    (testing "appends alongside foreign hooks, preserving everything else"
      (is (= 2 (count (get-in v1 [:hooks :SessionEnd]))))
      (is (= foreign (first (get-in v1 [:hooks :SessionEnd]))))
      (is (= {:allow ["Bash(bb test)"]} (:permissions v1))))
    (testing "re-install replaces our entry in place, never duplicates"
      (is (= 2 (count (get-in v2 [:hooks :SessionEnd]))))
      (is (= "claim hooks run --harness codex"
             (-> v2 (get-in [:hooks :SessionEnd]) second :hooks first :command))))
    (testing "events are independent"
      (let [v3 (hooks/install-plan v2 :UserPromptSubmit
                                   (entry "claim coach --hook") "coach --hook")]
        (is (= 1 (count (get-in v3 [:hooks :UserPromptSubmit]))))
        (is (= 2 (count (get-in v3 [:hooks :SessionEnd]))))))))

;; ---------------------------------------------------------------------------
;; Shell: install! against a temp project
;; ---------------------------------------------------------------------------

(deftest install-writes-project-settings
  (let [project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-test"}))]
    (testing "fresh install creates .claude/settings.json"
      (let [r (hooks/install! {:project project})]
        (is (= :installed (:status r)))
        (is (= "claim hooks run --harness claude-code" (:command r)))
        (let [settings (json/parse-string (slurp (:settings r)) true)
              hook (-> settings :hooks :SessionEnd first :hooks first)]
          (is (= "command" (:type hook)))
          (is (= 60 (:timeout hook)))
          (is (= hooks/hook-timeout-seconds (:timeout hook))
              "sized for capture: the hook makes no model calls, so reaching this
               bound is a bug rather than a budget being spent")
          (is (not (str/includes? (:command hook) "--consolidate-days"))
              "the cadence is gone with the pass it rationed — the curator's
               budget is the only bound left"))))
    (testing "re-install updates in place"
      (let [r (hooks/install! {:project project :harness "codex"})]
        (is (= :updated (:status r)))
        (let [settings (json/parse-string (slurp (:settings r)) true)]
          (is (= 1 (count (get-in settings [:hooks :SessionEnd])))))))
    (testing "a repo-local bin/claim is auto-detected"
      (fs/create-dirs (fs/path project "bin"))
      (spit (str (fs/path project "bin" "claim")) "#!/bin/sh\n")
      (let [r (hooks/install! {:project project})]
        (is (= "bin/claim hooks run --harness claude-code" (:command r)))))))

(deftest install-honors-a-settings-file-override
  (let [project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-test"}))
        target (str (fs/path project ".claude" "settings.local.json"))
        r (hooks/install! {:project project :settings-file target})]
    (is (= target (:settings r)))
    (is (fs/exists? target))
    (is (not (fs/exists? (fs/path project ".claude" "settings.json")))
        "the default location is not touched when overridden")))

;; ---------------------------------------------------------------------------
;; Shell: the SessionEnd pass is capture, and the curator is detached
;; ---------------------------------------------------------------------------

(deftest hooks-run-captures-and-hands-curation-off
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-run-test"}))
        project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-run-project"}))
        db (str dir "/db")
        s (doto (mem/create) (core/seed!))
        spawned (atom [])
        extracted (atom 0)
        base {:db db :dir dir :project project :harness "claude-code"
              ;; if any of these ever runs, the hook is making model calls again
              :extractor-fn (fn [_] (swap! extracted inc) "")
              :summarize-fn (fn [_] (swap! extracted inc) "")
              :judge-fn (fn [_] (swap! extracted inc) "")
              :spawn-fn (fn [opts]
                          (swap! spawned conj (hooks/curate-args opts))
                          {:status :spawned :log (hooks/curate-log db)})}]
    (spit (str dir "/MEMORY.md") "# Notes\nprefers Result types\n")

    (testing "one pass: code freshness, deterministic recompile, detach"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (= #{:status :ingest-code :compile-context :curator} (set (keys r)))
            "no :ingest-notes and no :consolidate: every model call moved to the curator")
        (is (= :skipped (get-in r [:ingest-code :status]))
            "an empty project has nothing to analyze — reported, not an error")
        (is (= :compiled (get-in r [:compile-context :status])))
        (is (= :spawned (get-in r [:curator :status])))
        (is (= (str db ".curate.log") (get-in r [:curator :log])))
        (is (zero? @extracted) "the hook itself never waits on a model")
        (is (str/includes? (slurp (str dir "/MEMORY.md")) harness/begin-marker)
            "the compiled view lands even if the curator never starts")))

    (testing "the curator is told where the store is and whose notes to read"
      (let [argv (first @spawned)]
        (is (= "curate" (first argv)))
        (is (= ["--harness" "claude-code"] (subvec argv 1 3)))
        (is (= db (nth argv (inc (.indexOf ^java.util.List argv "--db")))))
        (is (= dir (nth argv (inc (.indexOf ^java.util.List argv "--notes-dir")))))))

    (testing "--no-curate captures and stops there"
      (let [r (hooks/run! s (assoc base :no-curate true))]
        (is (= :ok (:status r)))
        (is (= :skipped (get-in r [:curator :status])))
        (is (str/includes? (get-in r [:curator :reason]) "claim curate"))
        (is (= 1 (count @spawned)) "nothing was spawned")))

    (testing "a hand-off that fails degrades to :partial with the capture intact"
      (let [r (hooks/run! s (assoc base :spawn-fn
                                   (fn [_] (throw (ex-info "no claim on PATH" {})))))]
        (is (= :partial (:status r)))
        (is (= :error (get-in r [:curator :status])))
        (is (= :compiled (get-in r [:compile-context :status])))))))

(deftest curate-args-carry-only-what-this-run-resolved
  (is (= ["curate" "--harness" "claude-code" "--db" "/s/db"]
         (hooks/curate-args {:db "/s/db"}))
      "a setting nobody set is left to the child's own flag > env > config chain
       rather than frozen into its command line as the parent's default")
  (is (= ["curate" "--harness" "codex" "--db" "/s/db"
          "--notes-dir" "/n" "--inject-file" "view.md"
          "--extractor" "llm -m small" "--budget" "7"]
         (hooks/curate-args {:harness "codex" :db "/s/db" :dir "/n"
                             :inject-file "view.md" :extractor "llm -m small"
                             :budget 7}))))

(deftest a-spawned-curator-outlives-the-hook-and-logs-where-it-said
  ;; the riskiest part of the hand-off is the part no unit can see: two
  ;; streams into one file, a log that does not accumulate across runs, and a
  ;; child that is never awaited
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-spawn-test"}))
        project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-spawn-project"}))
        db (str dir "/db")
        log (hooks/curate-log db)
        read-log (fn [] (if (fs/exists? log) (slurp log) ""))]
    (fs/create-dirs (fs/path project "bin"))
    (spit (str (fs/path project "bin" "claim"))
          "#!/bin/sh\necho \"argv: $*\"\necho 'and stderr' 1>&2\n")
    (fs/set-posix-file-permissions (fs/path project "bin" "claim") "rwxr-xr-x")
    (spit log "a previous curator's crash\n")

    (let [r (hooks/spawn-curator! {:db db :project project :harness "codex" :budget 5})]
      (is (= :spawned (:status r)))
      (is (= log (:log r)))
      (is (str/ends-with? (first (:command r)) "/bin/claim")
          "the project's own claim, absolute — the child's cwd is the project")
      ;; not awaited, so wait on the output rather than on an exit status
      (loop [n 0]
        (when (and (< n 200) (not (str/includes? (read-log) "and stderr")))
          (Thread/sleep 20)
          (recur (inc n))))
      (let [content (read-log)]
        (is (str/includes? content "argv: curate --harness codex"))
        (is (str/includes? content "--budget 5"))
        (is (str/includes? content "and stderr")
            "stderr shares the file: a curator that dies says why in one place")
        (is (not (str/includes? content "a previous curator's crash"))
            "and the log is this run's, not an accumulating pile")))))

;; ---------------------------------------------------------------------------
;; The ambient code stage (docs/language-adapters.md §5)
;; ---------------------------------------------------------------------------

(defn- git! [dir & args]
  (let [{:keys [exit err]} (apply p/sh {:dir (str dir)}
                                  "git" "-c" "user.email=t@test" "-c" "user.name=t" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (first args) " failed: " err) {})))))

(deftest hooks-run-code-stage-is-delta-gated
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-code-test"}))
        project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-code-project"}))
        s (mem/create)
        _ (core/seed! s)
        base {:db (str dir "/db") :dir dir :project project
              :spawn-fn (fn [_] {:status :spawned})}]
    (spit (str project "/app.clj") "(ns app.core)")
    (git! project "init" "-q")
    (git! project "add" "-A")
    (git! project "commit" "-q" "-m" "x")

    (testing "first run reconciles the code"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (= :ok (get-in r [:ingest-code :status])))
        (is (pos? (get-in r [:ingest-code :total])))))

    (testing "an unchanged ref skips on the gate"
      (let [r (hooks/run! s base)]
        (is (= :skipped (get-in r [:ingest-code :status])))
        (is (= "code unchanged since the last pass"
               (get-in r [:ingest-code :reason])))))

    (testing "a moved sha (committed change) re-runs"
      (spit (str project "/app.clj") "(ns app.core (:require [clojure.set]))")
      (git! project "add" "-A")
      (git! project "commit" "-q" "-m" "y")
      (is (= :ok (get-in (hooks/run! s base) [:ingest-code :status]))))

    (testing "an uncommitted edit (dirty digest) re-runs"
      (spit (str project "/app.clj") "(ns app.core)")
      (let [r (hooks/run! s base)]
        (is (= :ok (get-in r [:ingest-code :status])))
        (is (str/includes? (get-in r [:ingest-code :ref]) "+")
            "the ref carries the dirty digest")))

    (testing "code-ingest: manual opts the stage out"
      (let [r (hooks/run! s (assoc base :code-ingest "manual"))]
        (is (= :ok (:status r)))
        (is (= :skipped (get-in r [:ingest-code :status])))
        (is (str/includes? (get-in r [:ingest-code :reason]) "manual"))))))

(deftest hooks-run-analyzer-failure-is-partial-with-compile-intact
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-fail-test"}))
        project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-fail-project"}))
        s (mem/create)
        _ (core/seed! s)]
    (spit (str project "/index.ts") "import './x';")
    (spit (str dir "/MEMORY.md") "# Notes\n")
    (let [r (hooks/run! s {:db (str dir "/db") :dir dir :project project
                           :spawn-fn (fn [_] {:status :spawned})
                           :which (fn [_] "npx")
                           :command-fn (fn [_] (throw (ex-info "tool exploded" {})))})]
      (is (= :partial (:status r)))
      (is (= :partial (get-in r [:ingest-code :status])))
      (is (= :error (:status (first (get-in r [:ingest-code :analyzers])))))
      (is (= :compiled (get-in r [:compile-context :status]))
          "an analyzer failure never blocks the deterministic compile"))))
