(ns claimgraph.hooks-test
  "The ambient loop's automation: install-plan as a pure function over
  settings maps, install! against a temp project, and the hooks-run pass with
  injected extractor/summarizer fns — no LLM, no subprocess, no real ~/.claude."
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
                               (entry "claim hooks run --harness claude-code --consolidate-days 3")
                               "hooks run")]
    (testing "appends alongside foreign hooks, preserving everything else"
      (is (= 2 (count (get-in v1 [:hooks :SessionEnd]))))
      (is (= foreign (first (get-in v1 [:hooks :SessionEnd]))))
      (is (= {:allow ["Bash(bb test)"]} (:permissions v1))))
    (testing "re-install replaces our entry in place, never duplicates"
      (is (= 2 (count (get-in v2 [:hooks :SessionEnd]))))
      (is (= "claim hooks run --harness claude-code --consolidate-days 3"
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
          (is (= hooks/hook-timeout-seconds (:timeout hook))))))
    (testing "re-install updates in place"
      (let [r (hooks/install! {:project project :consolidate-days 3})]
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
;; The consolidation stamp
;; ---------------------------------------------------------------------------

(deftest consolidate-due-reads-the-stamp-not-its-mtime
  ;; The assertions that matter are the ones where payload and mtime DISAGREE:
  ;; they are what a silent revert to mtime-only would break. Negative day
  ;; counts here mean "ahead of now" — a foreign clock in a synced store.
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-stamp-test"}))
        db (str dir "/db")
        stamp (str db ".last-consolidate")
        now (core/now)
        days-ago (fn [n] (- (.getTime now) (long (* n 86400000))))
        at (fn [n] (java.util.Date. (days-ago n)))
        stamp! (fn [payload mtime-days-ago]
                 (spit stamp payload)
                 (fs/set-last-modified-time stamp (days-ago mtime-days-ago)))
        recorded (fn [n] (str (.toInstant (at n))))]
    (testing "an absent stamp is due"
      (is (hooks/consolidate-due? db 7 now)))

    (testing "a recent recorded instant is not due, however old the mtime"
      (stamp! (recorded 1) 400)
      (is (not (hooks/consolidate-due? db 7 now))))

    (testing "an old recorded instant is due, however fresh the mtime"
      ;; what rsync, a fresh checkout or a restore does to a travelling store
      (stamp! (recorded 30) 0)
      (is (hooks/consolidate-due? db 7 now)))

    (testing "a payload we cannot believe hands the decision to the mtime"
      ;; unparseable, empty, and dated ahead of us all fall back the same way:
      ;; the mtime alone decides, in BOTH directions
      (doseq [payload ["not an instant" "" "  \n" (recorded -21)]]
        (stamp! payload 0)
        (is (not (hooks/consolidate-due? db 7 now))
            (str "a fresh mtime governs: " (pr-str payload)))
        (stamp! payload 30)
        (is (hooks/consolidate-due? db 7 now)
            (str "an old mtime governs: " (pr-str payload)))))

    (testing "a stamp dated ahead of us cannot suppress its whole skew window"
      ;; another machine wrote now+21d into a synced store; our mtime is honest
      (stamp! (recorded -21) 0)
      (is (not (hooks/consolidate-due? db 7 now))
          "the mtime says we consolidated just now")
      (is (hooks/consolidate-due? db 7 (at -10))
          "ten days on the mtime says due — the future payload must not veto it"))

    (testing "days 0 is every run, even when the clock itself is ahead"
      (stamp! (recorded -5) -5)
      (is (hooks/consolidate-due? db 0 now))
      (is (not (hooks/consolidate-due? db 7 now))))

    (testing "a stamp that vanishes mid-read is due, never a crash"
      ;; exists? → slurp → mtime: a concurrent `hooks run` or a syncer
      ;; replacing the file can delete it between any two of those
      (stamp! "not an instant" 0)
      (with-redefs [fs/last-modified-time
                    (fn [& _] (throw (java.nio.file.NoSuchFileException. stamp)))]
        (is (hooks/consolidate-due? db 7 now))))))

;; ---------------------------------------------------------------------------
;; Shell: the SessionEnd pass
;; ---------------------------------------------------------------------------

(deftest hooks-run-drives-the-whole-loop
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-hooks-run-test"}))
        project (str (fs/create-temp-dir {:prefix "claimgraph-hooks-run-project"}))
        db (str dir "/db")
        s (mem/create)
        _ (core/seed! s)
        response "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\",\"confidence\":0.9}"
        base {:db db :dir dir :project project
              :extractor-fn (fn [_] response)
              :summarize-fn (fn [_] "episode summary")
              :judge-fn (fn [_] "")}]
    (spit (str dir "/MEMORY.md") "# Notes\nprefers Result types\n")

    (testing "one pass: code freshness, capture, recompile, consolidate (stamp absent = due)"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (= :skipped (get-in r [:ingest-code :status]))
            "an empty project has nothing to analyze — reported, not an error")
        (is (= 1 (get-in r [:ingest-notes :files-changed])))
        (is (= :compiled (get-in r [:compile-context :status])))
        (is (= :consolidated (get-in r [:consolidate :status])))
        (is (fs/exists? (str db ".last-consolidate")))
        (is (str/includes? (slurp (str dir "/MEMORY.md")) harness/begin-marker)
            "the compiled view landed in the inject file")))

    (testing "the stamp run! writes is read back as its payload, not its mtime"
      ;; age the mtime out from under a stamp written moments ago: nothing else
      ;; ties the writer's format to the reader, and a garbage payload would
      ;; hand the decision to a fresh mtime and look identical
      (fs/set-last-modified-time (str db ".last-consolidate")
                                 (- (.getTime (core/now)) (* 400 86400000)))
      (is (not (hooks/consolidate-due? db 7 (core/now)))))

    (testing "within the window, consolidation is skipped"
      (let [r (hooks/run! s base)]
        (is (= :ok (:status r)))
        (is (zero? (get-in r [:ingest-notes :files-changed])))
        (is (= :skipped (get-in r [:consolidate :status])))))

    (testing "--consolidate-days 0 forces the pass"
      (is (= :consolidated (get-in (hooks/run! s (assoc base :consolidate-days 0))
                                   [:consolidate :status]))))

    (testing "a broken extractor degrades to :partial — the recompile still runs"
      (spit (str dir "/MEMORY.md")
            (str (slurp (str dir "/MEMORY.md")) "\nnew durable note\n"))
      (let [r (hooks/run! s (assoc base :extractor-fn (fn [_] (throw (ex-info "no claude" {})))))]
        (is (= :partial (:status r)))
        (is (= :error (get-in r [:ingest-notes :status])))
        (is (= :compiled (get-in r [:compile-context :status]))
            "capture failing never blocks the deterministic view")))))

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
              :consolidate-days 9999
              :extractor-fn (fn [_] "")
              :summarize-fn (fn [_] "episode summary")
              :judge-fn (fn [_] "")}]
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
                           :consolidate-days 9999
                           :extractor-fn (fn [_] "")
                           :summarize-fn (fn [_] "episode summary")
                           :judge-fn (fn [_] "")
                           :which (fn [_] "npx")
                           :command-fn (fn [_] (throw (ex-info "tool exploded" {})))})]
      (is (= :partial (:status r)))
      (is (= :partial (get-in r [:ingest-code :status])))
      (is (= :error (:status (first (get-in r [:ingest-code :analyzers])))))
      (is (= :compiled (get-in r [:compile-context :status]))
          "an analyzer failure never blocks the deterministic compile"))))
