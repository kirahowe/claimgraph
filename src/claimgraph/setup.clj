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

;; Same contract as claimgraph.harness's managed section, in .gitignore's
;; comment syntax: what sits between the markers is ours to rewrite, and the
;; markers are how a later version that adds an artifact updates the block
;; instead of appending a second copy of it.
(def gitignore-begin-marker "# claimgraph:managed:begin")
(def gitignore-end-marker "# claimgraph:managed:end")

(def gitignore-header "# claimgraph live store + local artifacts (the committable artifacts are")
(def gitignore-header-tail "# `claim dump` output and .claimgraph/config.json)")

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
  (str/join "\n" (concat [gitignore-begin-marker gitignore-header gitignore-header-tail]
                         (gitignore-entries db-rel)
                         [gitignore-end-marker])))

(defn- artifact-line?
  "Is this line one of the db-derived ignores for db-rel — `<db>/` or `<db>.x`?
  Matched by shape, never against the current gitignore-entries: a release that
  drops or renames an entry still has to recognise the whole block an older one
  wrote, or the lines it no longer knows about orphan below the marked region."
  [db-rel line]
  (let [l (str/trim line)]
    (and (seq db-rel)
         (str/starts-with? l db-rel)
         (let [tail (subs l (count db-rel))]
           (or (= tail "/")
               (and (str/starts-with? tail ".") (> (count tail) 1)))))))

(defn- unmarked-block-end
  "Last index of a block with no end marker — one written before the markers
  existed, or one a user has half-edited. It is the header comments plus the
  artifact lines they introduce and NOT one line further: users append their
  own ignores directly under the block (this repo's .gitignore does exactly
  that), and those lines are not ours to touch.

  The db path is read back from the block's own first line rather than
  assumed, because the db may have been relocated since it was written. One
  lone directory line is likelier to be a user's ignore than a block of ours,
  so it takes two matching entries to claim the run."
  [lines start]
  (let [ours? #{gitignore-begin-marker gitignore-header gitignore-header-tail}
        after-comments (loop [i start]
                         (if (and (< i (count lines)) (ours? (str/trim (nth lines i))))
                           (recur (inc i))
                           i))
        first-entry (some-> (get lines after-comments) str/trim)
        db-rel (when (and first-entry
                          (str/ends-with? first-entry "/")
                          (not (str/starts-with? first-entry "#")))
                 (subs first-entry 0 (dec (count first-entry))))
        run (if db-rel
              (count (take-while #(artifact-line? db-rel %) (drop after-comments lines)))
              0)]
    (dec (+ after-comments (if (>= run 2) run 0)))))

(defn- managed-extents
  "Every inclusive [start end] region of these lines that claimgraph wrote, in
  order: the marked one plus every unmarked block an earlier version left.
  There can be more than one — a version before the markers appended a fresh
  block whenever the db moved — and all of them are ours to collapse into the
  single managed region, or the strays below the first survive every later run."
  [lines]
  (let [n (count lines)
        marker-end (fn [from]
                     (first (keep-indexed
                             (fn [i l] (when (and (>= i from) (= (str/trim l) gitignore-end-marker)) i))
                             lines)))]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [l (str/trim (nth lines i))
              end (cond (= l gitignore-begin-marker)
                        (or (marker-end (inc i)) (unmarked-block-end lines i))
                        (= l gitignore-header)
                        (unmarked-block-end lines i))]
          (if end
            (let [end (max end i)]      ; a zero-width region would never advance
              (recur (inc end) (conj acc [i end])))
            (recur (inc i) acc)))))))

(defn splice-gitignore
  "Content with the managed block written into whatever region already holds
  it — the marked one, else an unmarked block from an earlier version, which
  is upgraded where it stands rather than duplicated beside a marked one —
  and appended at the end when there is none. A file carrying several of our
  blocks keeps the first and loses the rest, so the region stays singular.
  Every other line comes back verbatim: the rest of the file is the user's."
  [content db-rel]
  (let [content (str content)
        block (str/split-lines (gitignore-block db-rel))
        ;; -1 keeps the empty element a trailing newline produces, so the
        ;; file's own final-newline habit survives the round trip
        lines (vec (str/split content #"\n" -1))
        extents (managed-extents lines)]
    (if-let [[start end] (first extents)]
      (let [stale (into #{} (mapcat (fn [[s e]] (range s (inc e)))) (rest extents))
            after (into [] (comp (remove stale) (map lines)) (range (inc end) (count lines)))]
        (str/join "\n" (concat (subvec lines 0 start) block after)))
      (str content
           (when-not (or (str/blank? content) (str/ends-with? content "\n")) "\n")
           (when-not (str/blank? content) "\n")
           (str/join "\n" block) "\n"))))

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
  "Write the live-store ignore block, rewriting the one we already manage
  rather than appending another (see splice-gitignore). Skips (with a note)
  when the db lives outside the project, and leaves a repo that ignores these
  paths its own way alone — until it has a block of ours to keep current."
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
            ;; a repo that ignores the whole directory, or already lists these
            ;; paths its own way, needs nothing from us — but once it holds a
            ;; block of ours, that block is ours to keep current
            covered? (or (contains? lines (str (first (fs/components rel)) "/"))
                         (every? lines (gitignore-entries rel)))
            ours? (or (str/includes? current gitignore-begin-marker)
                      (str/includes? current gitignore-header))
            updated (splice-gitignore current rel)]
        (cond
          (= updated current) {:status :unchanged :file (str target)}
          (and covered? (not ours?)) {:status :unchanged :file (str target)}
          dry-run {:status :dry-run :file (str target) :entries (gitignore-entries rel)}
          :else (do (spit (str target) updated)
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
