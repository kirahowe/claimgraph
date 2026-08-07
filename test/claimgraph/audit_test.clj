(ns claimgraph.audit-test
  "The memory-pile consistency scorecard: pure parts (prompt, clamping,
  finding classification, source scan, rendering) as plain functions, and
  the shell end-to-end over a temp fixture project with one planted instance
  of each finding class — injected extractor and judge fns, no LLM, no
  subprocess, no real store, no real ~/.claude."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.audit :as audit]
            [claimgraph.cli :as cli]
            [claimgraph.harness :as harness]))

(def ^:private isolated-ctx
  "Harness-resolution context pointing nowhere, so a developer's real
  ~/.claude auto-memory never leaks into a test audit."
  {:home "/nonexistent-home" :env {}})

;; ---------------------------------------------------------------------------
;; Pure: prompt & clamping
;; ---------------------------------------------------------------------------

(deftest audit-prompt-keeps-class-and-demands-quotes
  (let [p (audit/extraction-prompt "CLAUDE.md" "content"
                                   [{:id :core/decided-against :definition "rejected"}]
                                   ["  AuthService"] :instruction)]
    (is (str/includes? p "\"commitment\"") "the notes prompt forbids commitments; audit allows them")
    (is (str/includes? p "quote") "every claim must carry a verbatim receipt")
    (is (str/includes? p "core/decided-against") "carries the vocabulary")
    (is (str/includes? p "AuthService") "carries the entity roster")
    (is (str/includes? p "file=\"CLAUDE.md\"") "names the file")
    (is (str/includes? p "human-maintained instruction file")
        "frames what kind of file the extractor is reading")))

(deftest audit-prompt-frames-notes-differently
  (let [p (audit/extraction-prompt "memory/session.md" "content" [] [] :note)]
    (is (str/includes? p "auto-memory note")
        "a :note file is framed as the agent's own memory, not an instruction")
    (is (not (str/includes? p "human-maintained")))))

(deftest audit-facts-keep-epistemic-class
  (let [{:keys [facts rejected]}
        (audit/prepare-audit-facts
         [{:subject "api" :predicate "decided_against" :object "GraphQL"
           :class "commitment" :confidence 0.95 :quote "decided against GraphQL"}
          {:subject "AuthService" :predicate "prefers" :object "argon2"
           :class "preference"}
          {:subject "db" :predicate "depends-on" :object "Redis"}
          {:subject "" :predicate "prefers" :object "x"}]
         :note)]
    (is (= 3 (count facts)))
    (is (= 1 (count rejected)))
    (testing "commitments survive — the anti-notes clamp (spec §5)"
      (is (= :commitment (:epistemic (first facts)))))
    (testing "quotes ride along for the receipt map, never stored"
      (is (= "decided against GraphQL" (:quote (first facts)))))
    (testing "preferences survive; absent class left to the predicate default"
      (is (= :preference (:epistemic (second facts))))
      (is (nil? (:epistemic (nth facts 2)))))
    (testing "confidence caps at the agent-note ceiling, source-type forced"
      (is (= [0.65 0.55 0.55] (mapv :confidence facts)))
      (is (every? #(= :agent-note (:source-type %)) facts)))))

(deftest audit-facts-instruction-kind-caps-higher
  (let [{:keys [facts]}
        (audit/prepare-audit-facts
         [{:subject "api" :predicate "decided_against" :object "GraphQL"
           :class "commitment" :confidence 0.95}]
         :instruction)]
    (testing "an :instruction file's claims mint at :user-assertion trust"
      (is (= :user-assertion (:source-type (first facts)))))
    (testing "and cap at the :user-assertion ceiling (0.9), not the notes ceiling"
      (is (= 0.9 (:confidence (first facts)))))))

;; ---------------------------------------------------------------------------
;; Pure: classification
;; ---------------------------------------------------------------------------

(deftest pair-classification-and-receipts
  (let [receipts {"f1" {:file "CLAUDE.md" :quote "no GraphQL"}}
        pile {:id "f1" :subject "api" :predicate :core/decided-against
              :object "GraphQL" :source-type :agent-note}
        code {:id "f2" :subject "app" :predicate :core/defined-in
              :object "src/app.clj" :source-type :code}]
    (testing "a code-sourced side makes the pair staleness, not contradiction"
      (is (= "stale" (:kind (audit/pair->finding {:fact pile :candidate code} receipts))))
      (is (= "contradiction"
             (:kind (audit/pair->finding {:fact pile :candidate (assoc code :source-type :agent-note)}
                                         receipts)))))
    (testing "claims carry file+quote receipts; every side carries a source label"
      (let [{:keys [claims]} (audit/pair->finding {:fact pile :candidate code} receipts)]
        (is (= {:file "CLAUDE.md" :quote "no GraphQL"}
               (select-keys (first claims) [:file :quote])))
        (is (= "note" (:source (first claims))) "an :agent-note fact labels its side \"note\"")
        (is (= "code" (:source (second claims))))
        (is (nil? (:file (second claims))))))))

(deftest pair-classification-instruction-conflict-and-contradiction
  (let [instr {:id "i1" :subject "api" :predicate :core/decided-against
               :object "GraphQL" :source-type :user-assertion}
        note-a {:id "n1" :subject "api" :predicate :core/prefers
                :object "GraphQL" :source-type :agent-note}
        note-b {:id "n2" :subject "api" :predicate :core/prefers
                :object "REST" :source-type :agent-note}]
    (testing "instruction vs note — exactly one side instruction-sourced"
      (is (= "instruction-conflict"
             (:kind (audit/pair->finding {:fact note-a :candidate instr} {})))
          "the collision decide-assert usually produces has the note as :fact")
      (is (= "instruction-conflict"
             (:kind (audit/pair->finding {:fact instr :candidate note-a} {})))
          "orientation doesn't matter, only which side is instruction-sourced"))
    (testing "note vs note — neither side instruction-sourced — stays a contradiction"
      (is (= "contradiction"
             (:kind (audit/pair->finding {:fact note-a :candidate note-b} {})))))))

(deftest claim-view-labels-every-side
  (let [code {:id "c1" :subject "app" :predicate :core/defined-in
              :object "src/app.clj" :source-type :code}
        instr {:id "i1" :subject "api" :predicate :core/decided-against
               :object "GraphQL" :source-type :user-assertion}
        note {:id "n1" :subject "api" :predicate :core/prefers
              :object "GraphQL" :source-type :agent-note}]
    (is (= "code" (:source (audit/claim-view code {}))))
    (is (= "instruction" (:source (audit/claim-view instr {}))))
    (is (= "note" (:source (audit/claim-view note {}))))))

(deftest pairs-dedupe-by-unordered-ids
  (let [a {:fact {:id "x"} :candidate {:id "y"}}
        b {:fact {:id "y"} :candidate {:id "x"}}]
    (is (= 1 (count (audit/dedupe-pairs [a b])))
        "the same pair via write path AND sweep counts once (spec §9)")))

(deftest injection-arithmetic
  (let [files [{:path "CLAUDE.md" :bytes 12000 :managed-bytes 2000 :injected? true}
               {:path "MEMORY.md" :bytes 30000 :managed-bytes 30000 :injected? true}
               {:path "notes/session.md" :bytes 40000 :managed-bytes 0 :injected? false}]
        r (audit/injection-report files)]
    (testing "injected-bytes sums raw bytes over :injected? sources only"
      (is (= 42000 (:injected-bytes r))))
    (testing "managed-bytes sums the managed share of the injected sources only"
      (is (= 32000 (:managed-bytes r))))
    (testing "on-demand-bytes sums raw bytes over the rest — scanned, never injected"
      (is (= 40000 (:on-demand-bytes r))))
    (testing "window-bytes is the injection budget"
      (is (= 25000 (:window-bytes r))))
    (testing "over-budget is keyed on injected bytes, ignoring the bigger on-demand pile"
      (is (true? (:over-budget r))))
    (testing "files-over-window names only INJECTED files individually over the window"
      (is (= ["MEMORY.md"] (:files-over-window r)))))
  (testing "an all-on-demand pile never trips over-budget, however large"
    (is (false? (:over-budget (audit/injection-report
                                [{:path "huge.md" :bytes 999999 :managed-bytes 0
                                  :injected? false}])))))
  (is (false? (:over-budget (audit/injection-report
                             [{:path "a.md" :bytes 100 :managed-bytes 0 :injected? true}])))))

(deftest restatements-mark-instruction-note-span
  (testing "occurrences spanning an :instruction file and a :note file flag restates-instructions"
    (let [fold {:occurrences {"f1" [{:file "CLAUDE.md" :kind :instruction}
                                    {:file "memory/session.md" :kind :note}]}
                :summaries {"f1" {:subject "database" :predicate :core/has-version
                                  :object "Postgres 15" :source-type :agent-note}}}]
      (is (true? (:restates-instructions (first (audit/restatements fold)))))))
  (testing "two notes restating each other never crossed an instruction file"
    (let [fold {:occurrences {"f1" [{:file "a.md" :kind :note} {:file "b.md" :kind :note}]}
                :summaries {"f1" {:subject "x" :predicate :core/prefers :object "y"
                                  :source-type :agent-note}}}]
      (is (nil? (:restates-instructions (first (audit/restatements fold)))))))
  (testing "two instruction files restating each other also never crossed a note"
    (let [fold {:occurrences {"f1" [{:file "CLAUDE.md" :kind :instruction}
                                    {:file "AGENTS.md" :kind :instruction}]}
                :summaries {"f1" {:subject "x" :predicate :core/prefers :object "y"
                                  :source-type :user-assertion}}}]
      (is (nil? (:restates-instructions (first (audit/restatements fold))))))))

(deftest alias-clusters-see-healed-drift
  (is (= [["auth-service" "AuthService"]]
         (audit/alias-clusters [{:name "auth-service" :aliases ["AuthService"]}
                                {:name "api" :aliases []}
                                {:name "db" :aliases nil}]))
      "resolution self-heals drift into aliases; the alias trail IS the cluster"))

;; ---------------------------------------------------------------------------
;; Shell: source collection
;; ---------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "claimgraph-audit-test"})))

(deftest source-scan-defaults-echo-guard-and-extras
  (let [proj (temp-dir)
        extra (temp-dir)]
    (spit (str proj "/CLAUDE.md")
          (str "real content\n" harness/begin-marker "\ncompiled view\n" harness/end-marker))
    (spit (str proj "/AGENTS.md")
          (str harness/begin-marker "\nonly our compiled view\n" harness/end-marker))
    (fs/create-dirs (fs/path proj ".cursor" "rules"))
    (spit (str proj "/.cursor/rules/style.mdc") "always use kebab-case")
    (spit (str extra "/note.md") "extra note content")
    (let [sources (audit/collect-sources {:project proj :dirs [extra]
                                          :ctx isolated-ctx
                                          :ancestor-limit proj :managed-paths []})]
      (testing "managed sections are stripped before anything else sees them"
        (is (= "real content"
               (:content (first (filter #(= "CLAUDE.md" (:path %)) sources))))))
      (testing "a file empty after the strip stays in the list — skipped for
                EXTRACTION, not dropped from the pile"
        (let [agents (first (filter #(= "AGENTS.md" (:path %)) sources))]
          (is (some? agents))
          (is (str/blank? (:content agents)))
          (is (true? (:skipped agents)))
          (is (pos? (:bytes agents)))
          (is (= (:bytes agents) (:managed-bytes agents))
              "entirely managed — every raw byte is the compiled view")))
      (testing "rules files and extra dirs are in the pile"
        (is (some #(str/ends-with? (:path %) "style.mdc") sources))
        (is (some #(str/ends-with? (:path %) "note.md") sources)))
      (testing "sorted by [kind-rank, path]: instructions first, path-sorted within kind"
        (let [by-kind (group-by :kind sources)]
          (is (= (sort (map :path (by-kind :instruction))) (map :path (by-kind :instruction))))
          (is (= (sort (map :path (by-kind :note))) (map :path (by-kind :note))))
          (is (= :instruction (:kind (first sources))) "instructions ingest before notes")))
      (testing "the default scan set classifies :instruction"
        (is (= :instruction (:kind (first (filter #(= "CLAUDE.md" (:path %)) sources))))))
      (testing "--dir extras classify :note — unknown authorship stays second-class"
        (is (= :note (:kind (first (filter #(str/ends-with? (:path %) "note.md") sources)))))))))

(deftest collect-sources-raw-and-managed-byte-accounting
  (let [proj (temp-dir)
        managed-inner "compiled view line one\ncompiled view line two"
        raw (str "real content\n" harness/begin-marker "\n" managed-inner "\n" harness/end-marker)]
    (spit (str proj "/CLAUDE.md") raw)
    (spit (str proj "/AGENTS.md")
          (str harness/begin-marker "\nonly our compiled view\n" harness/end-marker))
    (let [sources (audit/collect-sources {:project proj :ctx isolated-ctx
                                          :ancestor-limit proj :managed-paths []})
          claude (first (filter #(= "CLAUDE.md" (:path %)) sources))
          agents (first (filter #(= "AGENTS.md" (:path %)) sources))
          raw-bytes (count (.getBytes ^String raw "UTF-8"))]
      (testing ":bytes is the RAW on-disk size — what the harness reads and injects,
                managed section included — not the post-strip size"
        (is (= raw-bytes (:bytes claude))))
      (testing "extraction still sees only the stripped, trimmed content"
        (is (= "real content" (:content claude))))
      (testing ":managed-bytes is the managed section's share of the raw bytes,
                measured before trimming so surrounding whitespace isn't counted"
        (is (pos? (:managed-bytes claude)))
        (is (< (:managed-bytes claude) (:bytes claude))))
      (testing "a fully-managed file stays in the list with the skipped marker
                and its full raw byte count"
        (is (some? agents))
        (is (str/blank? (:content agents)))
        (is (true? (:skipped agents)))
        (is (pos? (:bytes agents)))
        (is (= (:bytes agents) (:managed-bytes agents))
            "entirely managed — every raw byte is the compiled view")))))

(deftest collect-sources-marks-exactly-the-injected-sources
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})
        extra (temp-dir)]
    (spit (str proj "/CLAUDE.md") "the project uses kebab-case")
    (fs/create-dirs note-dir)
    (spit (str note-dir "/MEMORY.md") "the compiled view")
    (spit (str note-dir "/session.md") "an ordinary auto-memory note")
    (spit (str extra "/note.md") "an extra note")
    (let [sources (audit/collect-sources {:project proj :dirs [extra] :ctx ctx
                                          :ancestor-limit proj :managed-paths []})
          by-suffix (fn [suffix] (first (filter #(str/ends-with? (:path %) suffix) sources)))]
      (testing "instruction sources are always :injected? — the harness injects them wholesale"
        (is (true? (:injected? (by-suffix "CLAUDE.md")))))
      (testing "the harness's own inject-file (MEMORY.md, resolved via harness/inject-target)
                is the one :note source that is :injected?"
        (is (true? (:injected? (by-suffix "MEMORY.md")))))
      (testing "any other note under the notes dir is on-demand, not injected"
        (is (false? (:injected? (by-suffix "session.md")))))
      (testing "a --dir extra is on-demand, not injected, same as any other note"
        (is (false? (:injected? (by-suffix "note.md"))))))))

(deftest source-scan-classifies-notes-dir-and-sorts-instructions-first
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})]
    (spit (str proj "/CLAUDE.md") "the project uses kebab-case")
    (fs/create-dirs note-dir)
    (spit (str note-dir "/session.md") "the agent learned something")
    (let [sources (audit/collect-sources {:project proj :ctx ctx
                                          :ancestor-limit proj :managed-paths []})
          claude (first (filter #(str/ends-with? (:path %) "CLAUDE.md") sources))
          note (first (filter #(str/ends-with? (:path %) "session.md") sources))]
      (testing "a project CLAUDE.md classifies :instruction"
        (is (some? claude))
        (is (= :instruction (:kind claude))))
      (testing "a note under the resolved harness notes dir classifies :note"
        (is (some? note))
        (is (= :note (:kind note))))
      (testing "instructions ingest before notes regardless of path"
        (is (= [:instruction :note] (mapv :kind sources)))))))

(deftest collect-sources-notes-dir-override-beats-harness-default
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        default-dir (harness/notes-path h {:project proj :ctx ctx})
        override-dir (temp-dir)]
    (fs/create-dirs default-dir)
    (spit (str default-dir "/default-note.md") "the harness default location")
    (spit (str override-dir "/override-note.md") "the explicit override location")
    (let [sources (audit/collect-sources {:project proj :ctx ctx :notes-dir override-dir
                                          :ancestor-limit proj :managed-paths []})]
      (testing "an explicit :notes-dir is scanned"
        (is (some #(str/ends-with? (:path %) "override-note.md") sources)))
      (testing "the harness default dir is never consulted once an override is given"
        (is (not-any? #(str/ends-with? (:path %) "default-note.md") sources))))))

(deftest collect-sources-inject-file-override-changes-which-note-is-injected
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})
        by-suffix (fn [sources suffix]
                    (first (filter #(str/ends-with? (:path %) suffix) sources)))]
    (fs/create-dirs note-dir)
    (spit (str note-dir "/MEMORY.md") "the ordinary compiled view")
    (spit (str note-dir "/custom.md") "a hand-picked injection target")
    (testing "a relative override resolves against the notes dir (harness/inject-target)"
      (let [sources (audit/collect-sources {:project proj :ctx ctx :inject-file "custom.md"
                                            :ancestor-limit proj :managed-paths []})]
        (is (true? (:injected? (by-suffix sources "custom.md"))))
        (is (false? (:injected? (by-suffix sources "MEMORY.md"))))))
    (testing "an absolute override wins outright, independent of the notes dir"
      (let [external-dir (temp-dir)
            external (str external-dir "/external.md")]
        (spit external "an out-of-tree injection target")
        (let [sources (audit/collect-sources {:project proj :ctx ctx
                                              :files [external]
                                              :inject-file external
                                              :ancestor-limit proj :managed-paths []})]
          (is (true? (:injected? (by-suffix sources "external.md"))))
          (is (false? (:injected? (by-suffix sources "MEMORY.md")))))))))

(deftest collect-sources-note-glob-filters-and-recurses
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})]
    (fs/create-dirs (fs/path note-dir "sub"))
    (spit (str note-dir "/session.md") "an ordinary note")
    (spit (str note-dir "/scratch.txt") "not a markdown note")
    (spit (str note-dir "/sub/nested.md") "a nested note")
    (let [paths (map :path (audit/collect-sources {:project proj :ctx ctx
                                                    :ancestor-limit proj :managed-paths []}))]
      (testing "claude-code's glob (**.md) admits a top-level note"
        (is (some #(str/ends-with? % "session.md") paths)))
      (testing "a .txt file in a claude-code notes dir is ignored — the glob is **.md, not generous"
        (is (not-any? #(str/ends-with? % "scratch.txt") paths)))
      (testing "subdirectory .md files ARE included — the glob is recursive"
        (is (some #(str/ends-with? % "nested.md") paths))))))

(deftest collect-sources-codex-harness-is-per-machine-with-a-generous-glob
  (let [proj (temp-dir)
        codex-home (temp-dir)
        ctx {:home "/nonexistent-home" :env {"CODEX_HOME" codex-home}}
        h (harness/resolve-harness "codex")
        note-dir (harness/notes-path h {:project proj :ctx ctx})]
    (is (= (str codex-home "/memories") note-dir)
        "CODEX_HOME relocates the per-machine notes dir")
    (fs/create-dirs note-dir)
    (spit (str note-dir "/memory_summary.md") "the compiled view")
    (spit (str note-dir "/thread.txt") "a durable codex memory")
    (let [sources (audit/collect-sources {:project proj :harness "codex" :ctx ctx
                                          :ancestor-limit proj :managed-paths []})
          by-suffix (fn [suffix] (first (filter #(str/ends-with? (:path %) suffix) sources)))]
      (testing "memory_summary.md is codex's inject-file, so it is the one injected note"
        (is (true? (:injected? (by-suffix "memory_summary.md")))))
      (testing "codex's generous glob (**.{md,txt}) admits the .txt memory, on-demand"
        (is (some? (by-suffix "thread.txt")))
        (is (false? (:injected? (by-suffix "thread.txt")))))
      (testing "codex has no :instruction tier of its own among these sources"
        (is (every? #(= :note (:kind %)) sources)))
      (testing "the notes dir ignores :project entirely — a different project resolves the same notes"
        (let [other-project (temp-dir)
              other-sources (audit/collect-sources {:project other-project :harness "codex" :ctx ctx
                                                     :ancestor-limit other-project :managed-paths []})]
          (is (= (set (map :path sources)) (set (map :path other-sources)))))))))

(deftest ancestor-walk-discovers-and-limits
  (let [grandparent (temp-dir)
        parent (str grandparent "/parent")
        project (str parent "/project")]
    (fs/create-dirs project)
    (spit (str parent "/CLAUDE.md") "parent claude")
    (spit (str parent "/AGENTS.md") "parent agents")
    (spit (str grandparent "/CLAUDE.local.md") "grandparent local")
    (testing ":ancestor-limit at the grandparent discovers files at both levels"
      (let [sources (audit/collect-sources {:project project :ctx isolated-ctx
                                            :managed-paths []
                                            :ancestor-limit grandparent})
            by-suffix (fn [suffix] (first (filter #(str/ends-with? (:path %) suffix) sources)))
            parent-claude (by-suffix "parent/CLAUDE.md")]
        (is (some? parent-claude))
        (is (= :instruction (:kind parent-claude)))
        (is (true? (:injected? parent-claude)))
        (is (fs/absolute? (:path parent-claude)) "an ancestor path renders absolute")
        (is (some? (by-suffix "parent/AGENTS.md")))
        (is (some? (by-suffix "CLAUDE.local.md")))))
    (testing ":ancestor-limit at the parent excludes the grandparent file"
      (let [sources (audit/collect-sources {:project project :ctx isolated-ctx
                                            :managed-paths []
                                            :ancestor-limit parent})]
        (is (some #(str/ends-with? (:path %) "parent/CLAUDE.md") sources))
        (is (not-any? #(str/ends-with? (:path %) "CLAUDE.local.md") sources))))
    (testing ":ancestor-limit at the project excludes everything above it"
      (let [sources (audit/collect-sources {:project project :ctx isolated-ctx
                                            :managed-paths []
                                            :ancestor-limit project})]
        (is (not-any? #(str/ends-with? (:path %) "CLAUDE.md") sources))
        (is (not-any? #(str/ends-with? (:path %) "AGENTS.md") sources))
        (is (not-any? #(str/ends-with? (:path %) "CLAUDE.local.md") sources))))))

(deftest global-instructions-claude-code-config-dir-and-rules
  (testing "$CLAUDE_CONFIG_DIR names the global CLAUDE.md and its rules/*.md"
    (let [proj (temp-dir)
          config-dir (temp-dir)]
      (spit (str config-dir "/CLAUDE.md") "global claude md")
      (fs/create-dirs (str config-dir "/rules"))
      (spit (str config-dir "/rules/a.md") "a rule")
      (let [ctx {:home "/nonexistent-home" :env {"CLAUDE_CONFIG_DIR" config-dir}}
            sources (audit/collect-sources {:project proj :ctx ctx
                                            :managed-paths [] :ancestor-limit proj})
            paths (set (map :path sources))
            global-claude (first (filter #(str/ends-with? (:path %) "CLAUDE.md") sources))]
        (is (contains? paths (str (fs/canonicalize (str config-dir "/CLAUDE.md")))))
        (is (contains? paths (str (fs/canonicalize (str config-dir "/rules/a.md")))))
        (is (= :instruction (:kind global-claude)))
        (is (true? (:injected? global-claude))))))
  (testing "plain :home with no env var resolves ~/.claude/CLAUDE.md"
    (let [proj (temp-dir)
          home (temp-dir)]
      (fs/create-dirs (str home "/.claude"))
      (spit (str home "/.claude/CLAUDE.md") "home claude md")
      (let [ctx {:home home :env {}}
            sources (audit/collect-sources {:project proj :ctx ctx
                                            :managed-paths [] :ancestor-limit proj})
            paths (set (map :path sources))]
        (is (contains? paths (str (fs/canonicalize (str home "/.claude/CLAUDE.md")))))))))

(deftest managed-paths-picked-up-and-empty-yields-none
  (let [proj (temp-dir)
        managed-dir (temp-dir)
        managed-file (str managed-dir "/CLAUDE.md")]
    (spit managed-file "org policy")
    (testing "a temp file passed via :managed-paths is discovered as :instruction, injected"
      (let [sources (audit/collect-sources {:project proj :ctx isolated-ctx
                                            :ancestor-limit proj
                                            :managed-paths [managed-file]})
            canon (str (fs/canonicalize managed-file))
            hit (first (filter #(= canon (:path %)) sources))]
        (is (some? hit))
        (is (= :instruction (:kind hit)))
        (is (true? (:injected? hit)))))
    (testing "[] yields none — no managed-policy source at all"
      (let [sources (audit/collect-sources {:project proj :ctx isolated-ctx
                                            :ancestor-limit proj
                                            :managed-paths []})
            canon (str (fs/canonicalize managed-file))]
        (is (not-any? #(= canon (:path %)) sources))))))

(deftest project-claude-dir-instruction-files-discovered
  (let [proj (temp-dir)]
    (fs/create-dirs (fs/path proj ".claude" "rules"))
    (spit (str proj "/.claude/CLAUDE.md") "project claude dir file")
    (spit (str proj "/.claude/rules/style.md") "style rule")
    (let [sources (audit/collect-sources {:project proj :ctx isolated-ctx
                                          :ancestor-limit proj :managed-paths []})]
      (testing ".claude/CLAUDE.md is discovered as :instruction, from default-scan-set"
        (let [hit (first (filter #(= ".claude/CLAUDE.md" (:path %)) sources))]
          (is (some? hit))
          (is (= :instruction (:kind hit)))))
      (testing ".claude/rules/*.md is discovered as :instruction, alongside .cursor/rules"
        (let [hit (first (filter #(str/ends-with? (:path %) ".claude/rules/style.md") sources))]
          (is (some? hit))
          (is (= :instruction (:kind hit))))))))

(deftest dedupe-file-reachable-via-two-routes
  (let [grandparent (temp-dir)
        project (str grandparent "/sub/project")]
    (fs/create-dirs project)
    (spit (str grandparent "/CLAUDE.md") "shared between the ancestor walk and CLAUDE_CONFIG_DIR")
    (let [ctx {:home "/nonexistent-home" :env {"CLAUDE_CONFIG_DIR" grandparent}}
          sources (audit/collect-sources {:project project :ctx ctx
                                          :ancestor-limit grandparent
                                          :managed-paths []})
          matches (filter #(str/ends-with? (:path %) "CLAUDE.md") sources)]
      (is (= 1 (count matches))
          "reachable via both the ancestor walk and the harness's global-instructions — counted once"))))

;; ---------------------------------------------------------------------------
;; Shell: prerequisites
;; ---------------------------------------------------------------------------

(deftest prerequisites-need-extractor-never-dtlv
  (let [ok (audit/check-prerequisites {:extractor "myllm -p"
                                       :which (fn [b] (str "/bin/" b))})
        missing (audit/check-prerequisites {:extractor "myllm -p"
                                            :which (fn [_] nil)})]
    (is (= :ok (:status ok)))
    (is (not (contains? ok :dtlv)) "pod-free by design — dtlv is not even checked")
    (is (= :error (:status missing)))
    (is (:hint missing)))
  (testing "a missing extractor blocks the run before anything else happens"
    (let [proj (temp-dir)
          r (audit/audit! {:project proj :which (fn [_] nil)
                           :ctx isolated-ctx
                           :ancestor-limit proj :managed-paths []})]
      (is (= "blocked" (:status r)))
      (is (:hint r)))))

;; ---------------------------------------------------------------------------
;; Shell: the fixture pile, one planted instance of each finding class
;; ---------------------------------------------------------------------------

(defn- write-fixture!
  "A project whose pile plants: one contradiction (prefers vs decided-against
  GraphQL across the two instruction files), TWO staleness cases — one from
  each tier (a CLAUDE.md defined-in claim about fixture.app, and a --dir NOTE
  defined-in claim about fixture.util, each contradicting the fixture code) —
  one restatement (argon2, in both instruction files), one name-drift pair
  (auth-service / AuthService), one disagreement (has-version 1.0 vs 2.0),
  one pair the judge must rule compatible (Terraform), and one ephemeral
  line the durability filter drops.

  The two staleness claims use DIFFERENT subjects (fixture.app vs
  fixture.util) on purpose: audit-file!'s code-baseline guard keeps the code
  fact live even after the instruction claim flags against it, so a same-
  subject second claim would also collide with the first PILE claim, not
  just the code — a real, correctly-flagged scenario (see
  audit-code-baseline-survives-instruction-collisions) but a confusing one
  to also carry in this shared fixture. Returns {:project :note-dir}."
  []
  (let [proj (temp-dir)
        note-dir (temp-dir)]
    (fs/create-dirs (fs/path proj "src" "fixture"))
    (spit (str proj "/src/fixture/app.clj") "(ns fixture.app (:require [fixture.util]))\n")
    (spit (str proj "/src/fixture/util.clj") "(ns fixture.util)\n")
    (spit (str proj "/AGENTS.md")
          (str "# Agent guide\n"
               "The api-layer prefers GraphQL.\n"
               "auth-service prefers argon2 hashing.\n"
               "Use Terraform for infra.\n"
               "claim-cli is at 1.0.\n"))
    (spit (str proj "/CLAUDE.md")
          (str "# Project notes\n"
               "We decided against GraphQL for the api-layer.\n"
               "AuthService prefers argon2 hashing.\n"
               "We decided against terraform for app deploys.\n"
               "fixture.app lives in src/legacy/app.clj.\n"
               "claim-cli is at 2.0.\n"
               "dev server port 3021 in this worktree\n"))
    (spit (str note-dir "/note.md")
          "fixture.util lives in src/legacy/util.clj.\n")
    {:project proj :note-dir note-dir}))

(def ^:private agents-extraction
  (str/join "\n"
            ["{\"subject\":\"api-layer\",\"predicate\":\"prefers\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"The api-layer prefers GraphQL.\"}"
             "{\"subject\":\"auth-service\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"auth-service prefers argon2 hashing.\"}"
             "{\"subject\":\"deploy-tool\",\"predicate\":\"prefers\",\"object\":\"Terraform\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"Use Terraform for infra.\"}"
             "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"1.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 1.0.\"}"]))

(def ^:private claude-extraction
  ;; note: the ephemeral port line is deliberately NOT extracted (the
  ;; durability filter), and the last line is an incomplete triple the
  ;; clamp must reject
  (str/join "\n"
            ["{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against GraphQL for the api-layer.\"}"
             "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"AuthService prefers argon2 hashing.\"}"
             "{\"subject\":\"deploy-tool\",\"predicate\":\"decided_against\",\"object\":\"terraform\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against terraform for app deploys.\"}"
             "{\"subject\":\"fixture.app\",\"predicate\":\"defined_in\",\"object\":\"src/legacy/app.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.app lives in src/legacy/app.clj.\"}"
             "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"2.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 2.0.\"}"
             "{\"subject\":\"\",\"predicate\":\"prefers\",\"object\":\"junk\"}"]))

(def ^:private note-extraction
  "{\"subject\":\"fixture.util\",\"predicate\":\"defined_in\",\"object\":\"src/legacy/util.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.util lives in src/legacy/util.clj.\"}")

(defn- fixture-extractor [calls]
  (fn [prompt]
    (swap! calls conj prompt)
    (cond
      (str/includes? prompt "file=\"AGENTS.md\"") agents-extraction
      (str/includes? prompt "file=\"CLAUDE.md\"") claude-extraction
      (str/includes? prompt "note.md") note-extraction
      :else "")))

(defn- fixture-judge
  "Canned verdicts: the Terraform stance pair is ruled compatible (the
  false-positive filter must drop it); everything else genuinely conflicts."
  [prompt]
  (if (str/includes? prompt "Terraform")
    "{\"relation\":\"compatible\",\"confidence\":0.95,\"rationale\":\"infra vs app deploys\"}"
    "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"opposed\"}"))

(deftest audit-end-to-end
  (let [{:keys [project note-dir]} (write-fixture!)
        calls (atom [])
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn (fixture-extractor calls)
                         :judge-fn fixture-judge})]
    (is (= "ok" (:status r)))

    (testing "the pile: three sources (two instructions, one note), ten admitted claims"
      (is (= 3 (count (:files r))))
      (is (= ["AGENTS.md" "CLAUDE.md"] (take 2 (mapv :path (:files r))))
          "instructions ingest first, sorted by path")
      (is (str/ends-with? (:path (last (:files r))) "note.md")
          "the note ingests last regardless of path")
      (is (= [4 5 1] (mapv :claims (:files r))))
      (is (= 10 (:claims r)))
      (is (= 3 (count @calls)) "one extraction per file"))

    (testing "code ground truth ingested first (the staleness prong ran)"
      (is (= :ok (get-in r [:code :status])))
      (is (= 2 (get-in r [:code :files]))))

    (testing "the summary — one planted instance of each finding class"
      (is (= {:contradictions 1 :instruction-conflicts 0 :stale 2 :disagreements 1
              :restatements 1 :name-clusters 1}
             (:summary r)))
      (testing "both fixture files are :instruction, so the pair is a plain contradiction"
        (is (empty? (get-in r [:findings :instruction-conflicts])))))

    (testing "contradiction: the GraphQL stance pair, with receipts and verdict"
      (let [[c] (get-in r [:findings :contradictions])
            by-file (into {} (map (juxt :file identity)) (:claims c))]
        (is (= 1 (count (get-in r [:findings :contradictions]))))
        (is (= "We decided against GraphQL for the api-layer."
               (:quote (by-file "CLAUDE.md"))))
        (is (= "The api-layer prefers GraphQL." (:quote (by-file "AGENTS.md"))))
        (is (= :contradicts (get-in c [:verdict :relation])))))

    (testing "the judged-compatible Terraform pair is removed from the count"
      (is (= 1 (get-in r [:judge :compatible-removed])))
      (is (not-any? (fn [f] (some #(= "deploy-tool" (:subject %)) (:claims f)))
                    (get-in r [:findings :contradictions]))))

    (testing "stale: both tiers' defined-in claims collided with the code"
      (let [stale (get-in r [:findings :stale])
            by-subject (into {} (map (juxt #(:subject (first (:claims %))) identity)) stale)
            app-finding (by-subject "fixture.app")
            util-finding (by-subject "fixture.util")]
        (is (= 2 (count stale)))
        (testing "the instruction-kind claim (CLAUDE.md) still flags — the code-baseline guard"
          (let [by-source (group-by :source (:claims app-finding))]
            (is (= "code" (:source (first (by-source "code")))) "one side is the code itself")
            (is (= "CLAUDE.md" (:file (first (by-source "instruction"))))
                "the other side is the fixture's instruction file")))
        (testing "the note-kind claim also flags — trust below code either way"
          (let [by-source (group-by :source (:claims util-finding))]
            (is (= "code" (:source (first (by-source "code")))) "one side is the code itself")
            (is (str/ends-with? (:file (first (by-source "note"))) "note.md")
                "the other side is the fixture's note")))))

    (testing "disagreement: has-version 1.0 vs 2.0, reported as a pair, no winner"
      (let [[d] (get-in r [:findings :disagreements])]
        (is (= 1 (count (get-in r [:findings :disagreements]))))
        (is (= #{"1.0" "2.0"} (set (map :object (:claims d)))))
        (is (= #{"AGENTS.md" "CLAUDE.md"} (set (map :file (:claims d)))))))

    (testing "restatement: argon2 maintained in both files"
      (let [[f] (get-in r [:findings :restatements])]
        (is (= 1 (count (get-in r [:findings :restatements]))))
        (is (= "auth-service" (:subject f)))
        (is (= 2 (:count f)))
        (is (= #{"AGENTS.md" "CLAUDE.md"} (set (:files f))))))

    (testing "name cluster: the drift resolution healed is still reported"
      (is (some #(= #{"auth-service" "AuthService"} (set %))
                (get-in r [:findings :name-clusters]))))

    (testing "extraction noise is counted, not silently dropped"
      (is (= {:rejected 1 :inadmissible 0 :ambiguous 0}
             (get-in r [:findings :extraction-noise]))))

    (testing "injection arithmetic against the 25 KB window"
      (is (pos? (get-in r [:injection :injected-bytes]))
          "the two instruction files are injected")
      (is (pos? (get-in r [:injection :on-demand-bytes]))
          "the --dir note is scanned but never injected")
      (is (zero? (get-in r [:injection :managed-bytes]))
          "none of the fixture files carry a managed section")
      (is (= 25000 (get-in r [:injection :window-bytes])))
      (is (false? (get-in r [:injection :over-budget]))))

    (testing "the funnel hint"
      (is (str/includes? (first (:next r)) "claim setup")))

    (testing "the human rendering carries the headline and the receipts"
      (let [out (audit/render-pretty r)]
        (is (str/includes? out "10 claims extracted from 3 files"))
        (is (str/includes? out "1 contradiction"))
        (is (str/includes? out "auth-service / AuthService"))
        (is (str/includes? out "We decided against GraphQL"))
        (is (str/includes? out "claim setup"))))))

(deftest injection-accounting-separates-injected-from-on-demand
  (let [{:keys [project note-dir]} (write-fixture!)
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn (fixture-extractor (atom []))
                         :no-judge true})
        by-path (fn [suffix] (first (filter #(str/ends-with? (:path %) suffix) (:files r))))
        claude-bytes (:bytes (by-path "CLAUDE.md"))
        agents-bytes (:bytes (by-path "AGENTS.md"))
        note-bytes (:bytes (by-path "note.md"))]
    (testing "the two instruction files make up the whole of injected-bytes"
      (is (= (+ claude-bytes agents-bytes) (get-in r [:injection :injected-bytes]))))
    (testing "the --dir note's bytes land in on-demand-bytes, never injected-bytes"
      (is (pos? note-bytes))
      (is (= note-bytes (get-in r [:injection :on-demand-bytes]))))
    (testing "render-pretty calls out the on-demand notes separately from what's injected"
      (is (str/includes? (audit/render-pretty r) "on-demand notes scanned")))))

(deftest fully-managed-inject-file-counts-toward-injection-not-extraction
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})
        calls (atom [])]
    (spit (str proj "/CLAUDE.md") "We decided against GraphQL for the api-layer.\n")
    (fs/create-dirs note-dir)
    (spit (str note-dir "/MEMORY.md")
          (str harness/begin-marker "\ncompiled view: decided against GraphQL\n"
               harness/end-marker))
    (let [extractor (fn [prompt]
                       (swap! calls conj prompt)
                       (when (str/includes? prompt "file=\"CLAUDE.md\"")
                         (str "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\","
                              "\"object\":\"GraphQL\",\"object_kind\":\"literal\","
                              "\"class\":\"commitment\","
                              "\"quote\":\"We decided against GraphQL for the api-layer.\"}")))
          r (audit/audit! {:project proj :ctx ctx :extractor-fn extractor :no-judge true
                           :ancestor-limit proj :managed-paths []})
          claude-file (first (filter #(= "CLAUDE.md" (:path %)) (:files r)))
          memory-file (first (filter #(str/ends-with? (:path %) "MEMORY.md") (:files r)))]
      (testing "the fully-managed MEMORY.md stays in the source list"
        (is (some? memory-file)))
      (testing "it is skipped for extraction — the echo guard's whole point — with zero claims"
        (is (true? (:skipped memory-file)))
        (is (zero? (:claims memory-file))))
      (testing "the extractor never runs on it — only CLAUDE.md gets a call"
        (is (= 1 (count @calls)))
        (is (every? #(str/includes? % "file=\"CLAUDE.md\"") @calls)))
      (testing "yet its raw bytes count fully toward injected-bytes and managed-bytes"
        (is (= (+ (:bytes claude-file) (:bytes memory-file))
               (get-in r [:injection :injected-bytes])))
        (is (= (:bytes memory-file) (get-in r [:injection :managed-bytes]))
            "MEMORY.md is entirely managed, so its whole raw size counts as managed spend"))
      (testing "render-pretty reports injected KB and calls out the managed share"
        (let [out (audit/render-pretty r)]
          (is (str/includes? out "KB injected per session against a ~25 KB window"))
          (is (str/includes? out "claimgraph's compiled view"))
          (is (not (str/includes? out "on-demand notes scanned"))
              "nothing in this fixture is on-demand"))))))

(deftest audit-code-baseline-survives-instruction-collisions
  ;; :user-assertion ties :code at trust rank 3 (logic/source-trust), so
  ;; without audit-file!'s code-baseline guard the FIRST instruction claim
  ;; on this cardinality-:one predicate would silently supersede the code
  ;; fact (a disagreement, not staleness) and remove it from the store —
  ;; so the SECOND instruction claim, from a later file, would collide
  ;; against the first instruction claim instead of the code baseline. Two
  ;; instruction files claim two different (wrong) locations for the same
  ;; namespace; with the guard, both independently flag against the code
  ;; fact, which stays valid throughout.
  (let [proj (temp-dir)
        calls (atom [])]
    (fs/create-dirs (fs/path proj "src" "fixture"))
    (spit (str proj "/src/fixture/app.clj") "(ns fixture.app (:require [fixture.util]))\n")
    (spit (str proj "/src/fixture/util.clj") "(ns fixture.util)\n")
    (spit (str proj "/AGENTS.md") "fixture.app lives in src/other/app.clj.\n")
    (spit (str proj "/CLAUDE.md") "fixture.app lives in src/legacy/app.clj.\n")
    (let [extractor
          (fn [prompt]
            (swap! calls conj prompt)
            (cond
              (str/includes? prompt "file=\"AGENTS.md\"")
              "{\"subject\":\"fixture.app\",\"predicate\":\"defined_in\",\"object\":\"src/other/app.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.app lives in src/other/app.clj.\"}"
              (str/includes? prompt "file=\"CLAUDE.md\"")
              "{\"subject\":\"fixture.app\",\"predicate\":\"defined_in\",\"object\":\"src/legacy/app.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.app lives in src/legacy/app.clj.\"}"
              :else ""))
          r (audit/audit! {:project proj
                           :ctx isolated-ctx
                           :ancestor-limit proj :managed-paths []
                           :extractor-fn extractor
                           :no-judge true})]
      (testing "both instruction claims flag against the code baseline, not a disagreement"
        (is (= 2 (get-in r [:summary :stale]))
            "each instruction claim, first and second, still collides with the code fact")
        (is (zero? (get-in r [:summary :disagreements]))
            "the code fact is never silently superseded — no disagreement is produced")
        (is (every? (fn [f] (some #(= "code" (:source %)) (:claims f)))
                    (get-in r [:findings :stale]))
            "every stale finding still cites the code-sourced fact, proving it stayed valid")))))

(deftest audit-note-vs-instruction-is-an-instruction-conflict
  (let [proj (temp-dir)
        home (temp-dir)
        ctx {:home home :env {}}
        h (harness/resolve-harness nil)
        note-dir (harness/notes-path h {:project proj :ctx ctx})
        calls (atom [])]
    (spit (str proj "/CLAUDE.md")
          "We decided against GraphQL for the api-layer.\n")
    (fs/create-dirs note-dir)
    (spit (str note-dir "/session.md")
          "The api-layer prefers GraphQL.\n")
    (let [extractor
          (fn [prompt]
            (swap! calls conj prompt)
            (cond
              (str/includes? prompt "kind=\"instruction\"")
              "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against GraphQL for the api-layer.\"}"
              (str/includes? prompt "kind=\"note\"")
              "{\"subject\":\"api-layer\",\"predicate\":\"prefers\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"The api-layer prefers GraphQL.\"}"
              :else ""))
          judge (constantly "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"opposed\"}")
          r (audit/audit! {:project proj
                           :ctx ctx
                           :ancestor-limit proj :managed-paths []
                           :extractor-fn extractor
                           :judge-fn judge})]
      (testing "a planted note claim contradicting a CLAUDE.md claim is an instruction-conflict"
        (is (= 0 (get-in r [:summary :contradictions])))
        (is (= 1 (get-in r [:summary :instruction-conflicts])))
        (is (empty? (get-in r [:findings :contradictions])))
        (let [[f] (get-in r [:findings :instruction-conflicts])]
          (is (= "instruction-conflict" (:kind f)))
          (is (= #{"instruction" "note"} (set (map :source (:claims f)))))))
      (testing "render-pretty shows the new line and both side labels"
        (let [out (audit/render-pretty r)]
          (is (str/includes? out "1 instruction conflict"))
          (is (str/includes? out "[instruction]"))
          (is (str/includes? out "[note]")))))))

(deftest audit-ancestor-instruction-vs-note-is-an-instruction-conflict
  ;; The instruction side of this conflict lives in a CLAUDE.md ABOVE the
  ;; project root, not inside it — proving the ancestor walk's sources flow
  ;; through the whole pipeline (extraction, admission, assert, finding
  ;; classification) with the same :instruction trust a project-root file
  ;; would carry.
  (let [grandparent (temp-dir)
        project (str grandparent "/project")]
    (fs/create-dirs project)
    (spit (str grandparent "/CLAUDE.md")
          "We decided against GraphQL for the api-layer.\n")
    (let [home (temp-dir)
          ctx {:home home :env {}}
          h (harness/resolve-harness nil)
          note-dir (harness/notes-path h {:project project :ctx ctx})]
      (fs/create-dirs note-dir)
      (spit (str note-dir "/session.md")
            "The api-layer prefers GraphQL.\n")
      (let [extractor
            (fn [prompt]
              (cond
                (str/includes? prompt "kind=\"instruction\"")
                "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against GraphQL for the api-layer.\"}"
                (str/includes? prompt "kind=\"note\"")
                "{\"subject\":\"api-layer\",\"predicate\":\"prefers\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"The api-layer prefers GraphQL.\"}"
                :else ""))
            judge (constantly "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"opposed\"}")
            r (audit/audit! {:project project
                             :ctx ctx
                             :ancestor-limit grandparent
                             :managed-paths []
                             :extractor-fn extractor
                             :judge-fn judge})]
        (testing "a note claim contradicting an ANCESTOR CLAUDE.md is an instruction-conflict"
          (is (= 0 (get-in r [:summary :contradictions])))
          (is (= 1 (get-in r [:summary :instruction-conflicts])))
          (let [[f] (get-in r [:findings :instruction-conflicts])
                instr-side (first (filter #(= "instruction" (:source %)) (:claims f)))]
            (is (= "instruction-conflict" (:kind f)))
            (is (= (str (fs/canonicalize (str grandparent "/CLAUDE.md"))) (:file instr-side))
                "the ancestor CLAUDE.md's path renders absolute in the finding")))))))

(deftest render-pretty-empty-pile-is-a-plain-answer
  (let [out (audit/render-pretty
             {:status "ok" :claims 0 :files []
              :findings {} :summary {}
              :injection {:injected-bytes 0 :managed-bytes 0 :on-demand-bytes 0
                          :window-bytes 25000 :over-budget false}
              :code {:status :ok :files 83 :facts 538
                     :languages [:clojure :typescript]}
              :next ["claim setup  # nothing to migrate — start the graph fresh"]})]
    (testing "no wall of zeros — says what was scanned and what was found"
      (is (str/includes? out "nothing to audit"))
      (is (str/includes? out "CLAUDE.md"))
      (is (not (str/includes? out "contradiction"))))
    (testing "the code baseline still shows its work"
      (is (str/includes? out "538 code facts from 83 files (clojure, typescript)")))
    (testing "the funnel hint adapts"
      (is (str/includes? out "start the graph fresh")))))

(deftest render-pretty-carries-the-code-baseline
  (let [out (audit/render-pretty
             {:status "ok" :claims 3 :files [{:path "CLAUDE.md" :claims 3}]
              :findings {} :summary {}
              :injection {:injected-bytes 1200 :managed-bytes 0 :on-demand-bytes 0
                          :window-bytes 25000 :over-budget false}
              :code {:status :ok :files 12 :facts 90 :languages [:clojure]}
              :next ["claim setup  # the graph tracks these instead of accumulating them"]})]
    (is (str/includes? out "3 claims extracted from 1 file"))
    (is (str/includes? out "90 code facts from 12 files (clojure)"))))

(deftest audit-no-judge-reports-raw-flags
  (let [{:keys [project note-dir]} (write-fixture!)
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn (fixture-extractor (atom []))
                         :no-judge true})]
    (testing "without the false-positive filter the Terraform pair stays"
      (is (= 2 (get-in r [:summary :contradictions])))
      (is (= 2 (get-in r [:summary :stale])))
      (is (= :skipped (get-in r [:judge :status])))
      (is (every? #(nil? (:verdict %)) (get-in r [:findings :contradictions]))))))

(deftest audit-no-code-skips-staleness
  (let [{:keys [project note-dir]} (write-fixture!)
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn (fixture-extractor (atom []))
                         :judge-fn fixture-judge
                         :no-code true})]
    (is (= :skipped (get-in r [:code :status])))
    (testing "without code ground truth the defined-in claim is just a claim"
      (is (zero? (get-in r [:summary :stale]))))))

(deftest echo-guard-a-pile-of-only-our-own-view-audits-to-zero
  (let [proj (temp-dir)
        calls (atom [])]
    (spit (str proj "/CLAUDE.md")
          (str harness/begin-marker "\ncompiled view: decided against GraphQL\n"
               harness/end-marker))
    (let [r (audit/audit! {:project proj
                           :ctx isolated-ctx
                           :ancestor-limit proj :managed-paths []
                           :extractor-fn (fn [p] (swap! calls conj p) "")
                           :judge-fn fixture-judge})]
      (is (= "ok" (:status r)))
      (is (zero? (:claims r)))
      (testing "the file stays in the scan — it was scanned, just never extracted"
        (is (= 1 (count (:files r))))
        (is (true? (:skipped (first (:files r)))))
        (is (pos? (:bytes (first (:files r))))))
      (is (empty? @calls) "the extractor never runs on our own compiled view")
      (is (= {:contradictions 0 :instruction-conflicts 0 :stale 0 :disagreements 0
              :restatements 0 :name-clusters 0}
             (:summary r))))))

;; ---------------------------------------------------------------------------
;; Shell: the model-call gate — budget, per-call isolation, the breaker,
;; the preflight, --no-llm, and progress narration
;; ---------------------------------------------------------------------------

(deftest budget-defers-past-the-cap
  (let [{:keys [project note-dir]} (write-fixture!)
        calls (atom 0)
        counting-extractor (fn [_] (swap! calls inc) "")
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :budget 1
                         :extractor-fn counting-extractor
                         :judge-fn fixture-judge})]
    (is (= 1 @calls) "the budget allows exactly one extraction call")
    (is (= 3 (count (:files r))))
    (testing "the two files the budget didn't reach carry the skip reason"
      (let [[first-file & rest-files] (:files r)]
        (is (nil? (:llm first-file)) "the one call spent lands a normal report")
        (is (every? #(= {:status :skipped :reason :budget-exhausted} (:llm %)) rest-files))))
    (testing "the :llm section names what the run couldn't reach"
      (is (= 1 (get-in r [:llm :allowed])))
      (is (= 1 (get-in r [:llm :spent])))
      (is (<= 2 (get-in r [:llm :deferred]))))
    (is (= "partial" (:status r)) "a run that deferred work is not a clean ok")))

(deftest per-call-errors-degrade-not-kill
  (let [{:keys [project note-dir]} (write-fixture!)
        extractor (fn [prompt]
                    (cond
                      (str/includes? prompt "file=\"CLAUDE.md\"")
                      (throw (ex-info "boom" {:type :llm-command-failed}))
                      (str/includes? prompt "file=\"AGENTS.md\"") agents-extraction
                      (str/includes? prompt "note.md") note-extraction
                      :else ""))
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn extractor
                         :judge-fn fixture-judge})
        [agents claude note] (:files r)]
    (testing "the run completes, and the OTHER files' claims still land"
      (is (= 4 (:claims agents)) "AGENTS.md was never touched by the failure")
      (is (= 1 (:claims note)) "the note was never touched by the failure")
      (is (str/ends-with? (:path note) "note.md")))
    (testing "the failed file carries its own error, and asserts nothing"
      (is (zero? (:claims claude)))
      (is (= :error (get-in claude [:llm :status])))
      (is (= :llm-command-failed (get-in claude [:llm :error-type]))))
    (is (= 1 (count (get-in r [:llm :errors]))) "one call failed, isolated to its own entry")
    (is (= "partial" (:status r)))))

(deftest trip-after-consecutive-failures
  (let [{:keys [project note-dir]} (write-fixture!)
        _ (spit (str note-dir "/extra1.md") "another note")
        _ (spit (str note-dir "/extra2.md") "yet another note")
        calls (atom 0)
        extractor (fn [_] (swap! calls inc) (throw (ex-info "boom" {:type :llm-command-failed})))
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :budget 100
                         :extractor-fn extractor})]
    (is (= 5 (count (:files r))) "two instruction files plus three notes")
    (is (= audit/trip-threshold @calls)
        "the breaker stops spending after exactly trip-threshold consecutive failures")
    (is (true? (get-in r [:llm :tripped?])))
    (is (pos? (count (filter #(= :tripped (get-in % [:llm :reason])) (:files r))))
        "whatever the breaker left unattempted is named :tripped, not :budget-exhausted")
    (is (= "partial" (:status r)))))

(deftest preflight-failure-blocks-with-exit-worthy-status
  (let [proj (temp-dir)
        r (audit/audit! {:project proj
                         :ctx isolated-ctx
                         :ancestor-limit proj :managed-paths []
                         :extractor-fn (fn [_] "unused")
                         :preflight-fn (fn [_] {:status :error :error "auth expired"})})]
    (is (= "blocked" (:status r)))
    (is (some? (:preflight r)))
    (is (str/includes? (:error r) "preflight"))
    (is (str/includes? (:hint r) "--no-llm"))))

(deftest preflight-unit-behaviour
  (testing "a non-blank reply is ok, with elapsed time"
    (let [r (audit/preflight! (fn [_] "ok"))]
      (is (= :ok (:status r)))
      (is (number? (:ms r)))))
  (testing "a blank reply is a failure — an extractor that answers nothing is useless"
    (let [r (audit/preflight! (fn [_] ""))]
      (is (= :error (:status r)))
      (is (some? (:error r)))))
  (testing "a thrown ExceptionInfo carries its :type through as :error-type"
    (let [r (audit/preflight! (fn [_] (throw (ex-info "boom" {:type :llm-command-failed}))))]
      (is (= :error (:status r)))
      (is (= :llm-command-failed (:error-type r))))))

(deftest no-llm-needs-no-extractor
  (let [{:keys [project note-dir]} (write-fixture!)
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :no-llm true
                         :which (fn [_] nil)})]
    (is (= "ok" (:status r)) "a missing extractor never blocks a --no-llm run")
    (is (zero? (:claims r)))
    (testing "the deterministic checks still ran in full"
      (is (= 3 (count (:files r))))
      (is (every? #(pos? (:bytes %)) (:files r)))
      (is (map? (:injection r)))
      (is (pos? (:injected-bytes (:injection r))))
      (is (= :ok (get-in r [:code :status]))))
    (is (= {:status :skipped :reason :no-llm} (:llm r)))
    (is (= :skipped (get-in r [:judge :status])))))

(deftest blocked-exits-nonzero
  (let [tmp (temp-dir)
        out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out *err* err]
               (cli/run ["audit" "--project" tmp "--extractor" "no-such-binary-xyz"
                        "--json" "--quiet"]))]
    (is (= 1 code) "a blocked audit is not a clean exit, unlike an ordinary report")))

(deftest progress-narrates
  (let [{:keys [project note-dir]} (write-fixture!)
        lines (atom [])
        r (audit/audit! {:project project
                         :dirs [note-dir]
                         :ctx isolated-ctx
                         :ancestor-limit project :managed-paths []
                         :extractor-fn (fixture-extractor (atom []))
                         :judge-fn fixture-judge
                         :progress-fn (fn [line] (swap! lines conj line))})]
    (is (= "ok" (:status r)))
    (is (>= (count (filter #(str/starts-with? % "extract [") @lines)) 3)
        "at least one narration line per file")
    (is (some #(str/starts-with? % "llm: ") @lines)
        "the final budget summary line is present")))
