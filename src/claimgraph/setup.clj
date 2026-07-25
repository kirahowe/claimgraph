(ns claimgraph.setup
  "One-shot onboarding: `claim setup` takes a project from zero to a working
  memory system in one idempotent, dry-runnable command — store initialized
  and seeded, non-default locations persisted to the project config, the live
  store gitignored, the agent skill installed, the ambient loop wired, and
  (opt-in) the MCP server registered. Built so onboarding can be delegated to
  a coding agent: nothing is interactive, every step reports independently as
  JSON (a failed step never blocks the rest), and re-running is always safe.

  No location is assumed: every path flows through claimgraph.config
  (flag > env > .claimgraph/config.json > default), and choices made here are
  written back to the config file so later bare commands honor them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [claimgraph.hooks :as hooks]))

(defn- attempt [f]
  (try (f)
       (catch Exception e
         (merge {:status :error :error (ex-message e)}
                (dissoc (ex-data e) :claimgraph/error)))))

;; ---------------------------------------------------------------------------
;; Pure: plans
;; ---------------------------------------------------------------------------

(defn check-prerequisites
  "claim itself runs on bb (if this code is running, bb is either present or
  the caller is a test JVM), but the store cannot open without the Datalevin
  pod binary — a missing dtlv is a hard stop, because everything setup wires
  (init, the SessionEnd hook) would fail at runtime. The extractor command is
  optional: without it the LLM stages degrade and the deterministic stages
  are unaffected, so it reports as a note, never an error.
  :which is injectable for tests (fn name -> path-or-nil)."
  [{:keys [extractor which]}]
  (let [which (or which #(some-> (fs/which %) str))
        dtlv-setting (System/getenv "CLAIMGRAPH_DTLV")
        dtlv (if dtlv-setting
               (when (or (fs/exists? dtlv-setting) (which dtlv-setting))
                 (str dtlv-setting))
               (which "dtlv"))
        extractor-cmd (or extractor "claude -p")
        extractor-bin (first (str/split extractor-cmd #"\s+"))]
    (merge
     {:status (if dtlv :ok :error)
      :bb (or (System/getProperty "babashka.version") (which "bb") "not found")
      :dtlv (or dtlv "not found")
      :extractor {:command extractor-cmd :found (boolean (which extractor-bin))}}
     (when-not dtlv
       {:error "the Datalevin pod binary (dtlv) is not installed"
        :hint "brew install huahaiy/brew/datalevin — or run scripts/setup.sh from the claimgraph checkout, or point $CLAIMGRAPH_DTLV at the binary"})
     (when-not (which extractor-bin)
       {:note (str "extractor '" extractor-bin "' not on PATH — LLM stages "
                   "(session-extract, ingest-notes, judge, consolidate summaries) "
                   "won't run until it is; deterministic stages are unaffected")}))))

(def gitignore-header "# claimgraph live store + local artifacts (the committable artifacts are")

(defn gitignore-entries
  "The db-derived local artifacts that must never be committed. The config
  file and dumps are deliberately absent — they are the committable surface.
  The oplog is ignored by default but syncable: drop that line if you move
  effect logs between machines via git (docs: `reconcile`)."
  [db-rel]
  [(str db-rel "/")
   (str db-rel ".lock")
   (str db-rel ".evidence/")
   (str db-rel ".oplog/")
   (str db-rel ".retrievals")
   (str db-rel ".last-consolidate")])

(defn gitignore-block [db-rel]
  (str gitignore-header "\n# `claim dump` output and .claimgraph/config.json)\n"
       (str/join "\n" (gitignore-entries db-rel)) "\n"))

(defn skill-content
  "The agent skill, rendered for this project's claim executable."
  [bin]
  (str/replace (slurp (io/resource "claimgraph/SKILL.md")) "{{CLAIM}}" bin))

;; ---------------------------------------------------------------------------
;; Shell: the steps
;; ---------------------------------------------------------------------------

(defn- write-step!
  "Idempotent file write -> :installed | :updated | :unchanged (+ :dry-run)."
  [target content dry-run]
  (let [existed (fs/exists? target)
        current (when existed (slurp (str target)))
        status (cond (not existed) :installed
                     (not= current content) :updated
                     :else :unchanged)]
    (when (and (not dry-run) (not= :unchanged status))
      (fs/create-dirs (fs/parent target))
      (spit (str target) content))
    {:status (if (and dry-run (not= :unchanged status)) :dry-run status)
     :file (str target)}))

(defn persist-config!
  "Merge explicitly-chosen non-default settings into the project config file
  so every later command (and every other writer of the repo) honors them
  without flags. Nothing chosen + no file -> skipped."
  [{:keys [project chosen dry-run]}]
  (let [path (fs/path project ".claimgraph" "config.json")
        current (when (fs/exists? path)
                  (json/parse-string (slurp (str path)) true))]
    (if (and (empty? chosen) (nil? current))
      {:status :skipped :note "all defaults — nothing to persist (see `claim config`)"}
      (write-step! path
                   (str (json/generate-string (merge current chosen) {:pretty true}) "\n")
                   dry-run))))

(defn ensure-gitignore!
  "Append the live-store ignore block once. Skips (with a note) when the db
  lives outside the project or the entries are already covered."
  [{:keys [project db dry-run]}]
  (let [db (or db ".claimgraph/db")
        target (fs/path project ".gitignore")
        db-abs (fs/absolutize (fs/path project db))
        rel (when (fs/starts-with? db-abs (fs/absolutize project))
              (str (fs/relativize (fs/absolutize project) db-abs)))]
    (if-not rel
      {:status :skipped :note (str "db " db " lives outside the project — gitignore it where it lives")}
      (let [current (if (fs/exists? target) (slurp (str target)) "")
            lines (set (map str/trim (str/split-lines current)))
            ;; the whole-directory ignore some repos use covers everything
            covered? (or (contains? lines (str (first (fs/components rel)) "/"))
                         (every? lines (gitignore-entries rel)))]
        (cond
          covered? {:status :unchanged :file (str target)}
          dry-run {:status :dry-run :file (str target) :entries (gitignore-entries rel)}
          :else (do (spit (str target)
                          (str current
                               (when-not (or (str/blank? current) (str/ends-with? current "\n")) "\n")
                               (when-not (str/blank? current) "\n")
                               (gitignore-block rel)))
                    {:status :updated :file (str target) :entries (gitignore-entries rel)}))))))

(defn install-skill!
  "Install the claimgraph agent skill where the harness discovers skills
  (default <project>/.claude/skills; --skills-dir / $CLAIMGRAPH_SKILLS_DIR /
  skills-dir in the config to relocate)."
  [{:keys [project skills-dir bin dry-run]}]
  (write-step! (fs/path (or skills-dir (fs/path project ".claude" "skills"))
                        "claimgraph" "SKILL.md")
               (skill-content bin)
               dry-run))

(defn install-mcp!
  "Register the MCP front-end in the project's .mcp.json (merged, idempotent)
  — the config-file route, so no harness CLI is required."
  [{:keys [project bin dry-run]}]
  (let [path (fs/path project ".mcp.json")
        current (when (fs/exists? path)
                  (json/parse-string (slurp (str path)) true))
        updated (assoc-in (or current {}) [:mcpServers :claimgraph]
                          {:command bin :args ["mcp"]})]
    (write-step! path
                 (str (json/generate-string updated {:pretty true}) "\n")
                 dry-run)))

(defn- run-steps!
  [prereqs {:keys [project bin mcp dry-run init-fn chosen] :as opts}]
  (let [steps (array-map
               :prerequisites prereqs
               :store (if dry-run
                        {:status :dry-run :note "would create + seed the store"}
                        (attempt init-fn))
               :config (attempt #(persist-config! {:project project :chosen chosen
                                                   :dry-run dry-run}))
               :gitignore (attempt #(ensure-gitignore! {:project project :db (:db opts)
                                                        :dry-run dry-run}))
               :skill (attempt #(install-skill! {:project project :bin bin
                                                 :skills-dir (:skills-dir opts)
                                                 :dry-run dry-run}))
               :hooks (if dry-run
                        {:status :dry-run
                         :note "would wire the SessionEnd ambient loop (hooks install)"}
                        (attempt #(hooks/install!
                                   (assoc (select-keys opts [:harness :settings-file
                                                             :consolidate-days :coach])
                                          :project project :bin bin))))
               :mcp (if mcp
                      (attempt #(install-mcp! {:project project :bin bin :dry-run dry-run}))
                      {:status :skipped :note "opt in with --mcp (or: claude mcp add claimgraph -- claim mcp)"}))]
    {:status (cond dry-run :dry-run
                   (some #(= :error (:status %)) (vals steps)) :partial
                   :else :ready)
     :project project
     :bin bin
     :steps steps
     :next [(str "just work — every session now ends by feeding the graph "
                 "and starts with its compiled view injected")
            (str "record your first decision: " bin " assert --subject <thing> "
                 "--predicate decided-against --object <alternative> --class commitment")
            (let [langs (try (seq (mapv (comp name :id)
                                        ((requiring-resolve 'claimgraph.ingest.code/detect)
                                         project)))
                             (catch Exception _ nil))]
              (str "seed the structural layer: " bin " ingest-code"
                   (if langs
                     (str "  # detected: " (str/join ", " langs)
                          " (the ambient loop keeps it fresh from here)")
                     "  # no analyzable sources detected yet — built-in analyzers: clojure, kotlin, typescript; add your own via code-analyzers in .claimgraph/config.json")))
            (str "see every setting, its value, and where it came from: " bin " config")]}))

(defn run!
  "The whole onboarding pass. Prerequisites are checked first and a missing
  dtlv BLOCKS: nothing is wired that would fail at runtime (a SessionEnd
  hook without its pod binary is just session-end noise). Past preflight,
  steps report independently; a failed step is an :error entry, not an abort.

  opts: :project (default cwd) :bin (claim executable; auto-detects a
        repo-local bin/claim) :db :harness :settings-file :skills-dir
        :consolidate-days :coach :mcp :dry-run
        :chosen (explicit settings to persist to the project config)
        :which (prerequisite lookup, injectable for tests)
        :init-fn (opens/seeds the store; injectable so tests and --dry-run
        never touch a real backend)"
  [{:keys [project bin] :as opts}]
  (let [project (str (fs/canonicalize (or project ".")))
        bin (or bin
                (if (fs/exists? (fs/path project "bin" "claim")) "bin/claim" "claim"))
        prereqs (attempt #(check-prerequisites (select-keys opts [:extractor :which])))
        opts (assoc opts :project project :bin bin)]
    (if (and (= :error (:status prereqs)) (not (:dry-run opts)))
      {:status :blocked
       :project project
       :bin bin
       :steps {:prerequisites prereqs}
       :hint (:hint prereqs)}
      (run-steps! prereqs opts))))
