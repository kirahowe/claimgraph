(ns claimgraph.notes-test
  "Auto-memory notes ingester: the pure parts (managed-section stripping,
  hashing, delta planning, clamping) as plain functions, and the shell
  end-to-end over a temp notes directory with an injected :extractor-fn —
  no LLM, no subprocess, no real ~/.claude."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Pure: harness expectations
;; ---------------------------------------------------------------------------

(deftest project-path-munging
  (is (= "-home-kira-my-app" (harness/munge-project-path "/home/kira/my_app")))
  (is (= "-Users-k-dev-memory" (harness/munge-project-path "/Users/k/dev/memory"))))

(deftest claude-code-notes-dir
  (let [dir ((get-in harness/harnesses [:claude-code :notes-dir])
             {:home "/home/kira" :env {}} "/home/kira/dev/memory")]
    (is (= "/home/kira/.claude/projects/-home-kira-dev-memory/memory" dir))))

(deftest notes-dir-honors-harness-relocation-env
  (testing "CLAUDE_CONFIG_DIR replaces ~/.claude entirely"
    (is (= "/mnt/cfg/projects/-repo/memory"
           ((get-in harness/harnesses [:claude-code :notes-dir])
            {:home "/home/kira" :env {"CLAUDE_CONFIG_DIR" "/mnt/cfg"}} "/repo"))))
  (testing "CODEX_HOME replaces ~/.codex"
    (is (= "/mnt/codex/memories"
           ((get-in harness/harnesses [:codex :notes-dir])
            {:home "/home/kira" :env {"CODEX_HOME" "/mnt/codex"}} "/repo")))))

(deftest notes-path-resolution
  (let [h (harness/resolve-harness :claude-code)]
    (testing "an explicit :dir wins outright"
      (is (= "/anywhere/notes" (harness/notes-path h {:dir "/anywhere/notes"
                                                      :ctx {:home "/h" :env {}}}))))
    (testing "otherwise the harness default from the injected ctx"
      (is (str/ends-with? (harness/notes-path h {:project "/repo"
                                                 :ctx {:home "/h" :env {}}})
                          "/h/.claude/projects/-repo/memory")))))

(deftest inject-target-resolution
  (let [h (harness/resolve-harness :claude-code)]
    (is (= "/notes/MEMORY.md" (harness/inject-target h "/notes" nil))
        "harness default, inside the notes dir")
    (is (= "/notes/CONTEXT.md" (harness/inject-target h "/notes" "CONTEXT.md"))
        "relative override resolves against the notes dir")
    (is (= "/elsewhere/view.md" (harness/inject-target h "/notes" "/elsewhere/view.md"))
        "absolute override wins outright")))

(deftest unknown-harness-fails-with-supported-list
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown harness"
                        (harness/resolve-harness "windsurf"))))

(deftest codex-harness-expectations
  (let [h (harness/resolve-harness "codex")]
    (is (= "/home/k/.codex/memories"
           ((:notes-dir h) {:home "/home/k" :env {}} "/ignored/project"))
        "codex memories are per-machine, not per-project")
    (is (= "memory_summary.md" (:inject-file h))
        "the consolidated summary is also the injection slot")))

(deftest managed-section-stripping
  (testing "content without markers passes through untouched"
    (is (= "# Notes\nplain" (harness/strip-managed-section "# Notes\nplain"))))
  (testing "the delimited section is removed, markers included"
    (is (= "before\nafter"
           (harness/strip-managed-section
            (str "before\n" harness/begin-marker
                 "\ncompiled view\n" harness/end-marker "after")))))
  (testing "an unterminated section strips to EOF — never re-consume our own view"
    (is (= "before\n"
           (harness/strip-managed-section
            (str "before\n" harness/begin-marker "\ncompiled view, no end"))))))

;; ---------------------------------------------------------------------------
;; Pure: delta detection
;; ---------------------------------------------------------------------------

(deftest hash-ignores-the-managed-section
  (let [note "# Notes\nreal content\n"
        with-section (str note harness/begin-marker "\nview\n" harness/end-marker)]
    (is (= (notes/content-hash (harness/strip-managed-section note))
           (notes/content-hash (harness/strip-managed-section with-section)))
        "a compile-context rewrite alone must not re-trigger ingestion")))

(deftest delta-plan-from-episode-refs
  (let [eps [{:source-type :agent-note :ref "claude-code:MEMORY.md@abc123def456"}
             {:source-type :agent-note :ref "claude-code:debugging.md@111111111111"}
             {:source-type :session-log :ref "claude-code:MEMORY.md@zzzzzzzzzzzz"}
             {:source-type :agent-note :ref "codex:MEMORY.md@ffffffffffff"}]
        seen (notes/seen-hashes eps :claude-code)]
    (is (= {"MEMORY.md" #{"abc123def456"}
            "debugging.md" #{"111111111111"}}
           seen)
        "only agent-note episodes of the same harness count")
    (is (= [{:path "MEMORY.md" :hash "new000000000"}]
           (mapv #(select-keys % [:path :hash])
                 (notes/changed-notes
                  [{:path "MEMORY.md" :hash "new000000000"}
                   {:path "debugging.md" :hash "111111111111"}]
                  seen)))
        "unchanged files are skipped, changed ones re-ingest")))

;; ---------------------------------------------------------------------------
;; Pure: clamping
;; ---------------------------------------------------------------------------

(deftest note-facts-are-inference-grade
  (let [{:keys [facts demoted rejected]}
        (notes/prepare-note-facts
         [{:subject "api" :predicate "decided_against" :object "GraphQL"
           :class "commitment" :confidence 0.95}
          {:subject "AuthService" :predicate "prefers" :object "Result types"
           :class "preference" :confidence 0.9}
          {:subject "db" :predicate "depends-on" :object "Redis"}
          {:subject "" :predicate "prefers" :object "x"}])]
    (is (= 3 (count facts)))
    (is (= 1 (count rejected)))
    (testing "notes never mint commitments — reported decisions demote to observation"
      (is (= 1 demoted))
      (is (= :observation (:epistemic (first facts))))
      (is (nil? (:class (first facts))) "the raw class never leaks past the clamp"))
    (testing "preferences survive as preferences"
      (is (= :preference (:epistemic (second facts)))))
    (testing "missing class is an observation, not the predicate's default"
      (is (= :observation (:epistemic (nth facts 2)))))
    (testing "confidence caps at 0.65, defaults below it, source-type forced"
      (is (= [0.65 0.65 0.55] (mapv :confidence facts)))
      (is (every? #(= :agent-note (:source-type %)) facts)))))

;; ---------------------------------------------------------------------------
;; Shell: end-to-end over a temp notes dir
;; ---------------------------------------------------------------------------

(defn- temp-notes-dir []
  (str (fs/create-temp-dir {:prefix "claimgraph-notes-test"})))

(deftest ingest-notes-end-to-end
  (let [dir (temp-notes-dir)
        s (mem/create)
        _ (core/seed! s)
        response (str/join "\n"
                           ["{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\",\"confidence\":0.9}"
                            "{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\"}"])
        prompts (atom [])
        run! (fn [& [opts]]
               (notes/ingest! s (merge {:dir dir
                                        :extractor-fn (fn [p] (swap! prompts conj p) response)}
                                       opts)))]
    (spit (str dir "/MEMORY.md") "# Notes\nWe prefer Result types. Rejected GraphQL.\n")
    (spit (str dir "/debugging.md") "dev server port 3021 in this worktree\n")

    (testing "dry-run extracts changed files but writes nothing"
      (let [r (run! {:dry-run true})]
        (is (= :dry-run (:status r)))
        (is (= 2 (:files-scanned r)))
        (is (= 2 (:files-changed r)))
        (is (zero? (get-in (core/stats s) [:facts :total])))))

    (testing "first real pass ingests every note under per-file agent-note episodes"
      (reset! prompts [])
      (let [r (run!)]
        (is (= :ok (:status r)))
        (is (= 2 (:files-changed r)))
        (is (= 2 (get-in r [:counts :created])))
        (is (= 2 (get-in r [:counts :reinforced]))
            "the second file restates the same facts — they reinforce, not duplicate")
        (is (= 1 (:demoted (first (:files r))))
            "the reported decision was demoted, not minted as a commitment"))
      (let [{:keys [facts]} (core/get-facts s {:entity "api-layer"})]
        (is (= :core/decided-against (:predicate (first facts))))
        (is (= :observation (:epistemic (first facts)))
            "note-derived decisions land as observations")
        (is (= :agent-note (:source-type (first facts))))
        (is (= 0.55 (:confidence (first facts))) "missing confidence -> notes default"))
      (testing "episodes carry file@hash provenance and close mechanically"
        (let [eps (filter #(= :agent-note (:source-type %))
                          (store/-list-episodes s))]
          (is (= 2 (count eps)))
          (is (every? #(re-matches #"claude-code:[^@]+@[0-9a-f]{12}" (:ref %)) eps))
          (is (every? :closed-at eps)))))

    (testing "an unchanged pass extracts nothing"
      (reset! prompts [])
      (let [r (run!)]
        (is (zero? (:files-changed r)))
        (is (empty? @prompts) "the extractor never runs on unchanged notes")))

    (testing "appending a managed section alone does not re-trigger ingestion"
      (spit (str dir "/MEMORY.md")
            (str "# Notes\nWe prefer Result types. Rejected GraphQL.\n"
                 harness/begin-marker "\ncompiled view\n" harness/end-marker)
            :append false)
      (let [r (run!)]
        (is (zero? (:files-changed r)))))

    (testing "a real edit re-ingests just that file, and restatement reinforces"
      (spit (str dir "/MEMORY.md")
            "# Notes\nWe prefer Result types. Rejected GraphQL. Also: new fact.\n")
      (reset! prompts [])
      (let [r (run!)]
        (is (= 1 (:files-changed r)))
        (is (= 2 (get-in r [:counts :reinforced]))
            "identical facts from the re-extracted note reinforce, not duplicate")
        (is (str/includes? (first @prompts) "MEMORY.md") "prompt names the file")
        (is (str/includes? (first @prompts) "core/decided-against")
            "prompt carries the vocabulary")))

    (testing "missing notes dir degrades gracefully"
      (let [r (notes/ingest! s {:dir (str dir "/nope") :extractor-fn (fn [_] "")})]
        (is (= :no-notes-dir (:status r)))))))

;; ---------------------------------------------------------------------------
;; The rejection this ingester was actually losing knowledge to
;; ---------------------------------------------------------------------------

(defn- lesson
  "A lesson of roughly n characters — sentences, because that is the shape the
  failure tier's own rule (\"the lesson, not the diff\") asks for."
  [n]
  (subs (apply str (repeat 100 "the pod dies silently when the socket closes mid-write; "))
        0 n))

(deftest a-prose-shaped-lesson-survives-the-admission-screen-end-to-end
  ;; The live failure this exists to stop: every 252–606 char lesson the
  ;; extractor produced landed in :inadmissible, so the ingester dropped
  ;; exactly the facts it was best at finding.
  (let [dir (temp-notes-dir)
        s (doto (mem/create) (core/seed!))
        response (str "{\"subject\":\"claimgraph\",\"predicate\":\"failure_mode\","
                      "\"object\":\"" (lesson 400) "\",\"object_kind\":\"literal\"}\n"
                      "{\"subject\":\"claimgraph\",\"predicate\":\"has_version\","
                      "\"object\":\"" (lesson 400) "\",\"object_kind\":\"literal\"}")]
    (spit (str dir "/MEMORY.md") "# Notes\nWhat we learned about the pod.\n")
    (let [r (notes/ingest! s {:dir dir :extractor-fn (constantly response)})
          file (first (:files r))]
      (is (= 1 (get-in r [:counts :created]))
          "the 400-char lesson is admitted — its predicate declares prose")
      (is (= [(lesson 400)]
             (mapv :object-lit (:facts (core/get-facts
                                        s {:entity "claimgraph"
                                           :predicate :core/failure-mode}))))
          "and lands in the store whole, not truncated")
      (testing "the same length on a value-shaped predicate is still junk"
        (is (= 1 (count (:inadmissible file))))
        (is (= "has_version" (str (:predicate (first (:inadmissible file))))))
        (is (false? (get-in (first (:inadmissible file))
                            [:admission-signals :object-sane])))))))

(deftest the-prompt-marks-which-predicates-take-prose
  ;; A vocabulary listing that says nothing about shape teaches the extractor
  ;; to keep every object short, which is self-censorship the screen never
  ;; gets to see.
  (let [predicates [{:id :core/failure-mode :definition "the lesson"}
                    {:id :core/depends-on :definition "requires"}]
        marked-line (fn [prompt id]
                      (first (filter #(str/includes? % (subs (str id) 1))
                                     (str/split-lines prompt))))
        prompt (notes/extraction-prompt "MEMORY.md" "content" predicates [])]
    (is (str/includes? (marked-line prompt :core/failure-mode) "full lesson")
        "the prose predicate's line says its object may be sentences")
    (is (not (str/includes? (marked-line prompt :core/depends-on) "full lesson"))
        "and a value predicate's line is unchanged")))

;; ---------------------------------------------------------------------------
;; Shell: the budget gate and the write wrapper the curator drives this with
;; ---------------------------------------------------------------------------

(def ^:private one-fact
  "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"object_kind\":\"literal\",\"class\":\"preference\"}")

(deftest a-file-the-budget-cannot-reach-is-deferred-not-dropped
  (let [dir (temp-notes-dir)
        s (doto (mem/create) (core/seed!))
        calls (atom 0)
        extractor (fn [_] (swap! calls inc) one-fact)
        budget (fn [n] (let [spent (atom 0)]
                         (fn [] (when (< @spent n) (swap! spent inc) true))))]
    (spit (str dir "/a.md") "note a\n")
    (spit (str dir "/b.md") "note b\n")

    (let [r (notes/ingest! s {:dir dir :extractor-fn extractor :spend! (budget 1)})]
      (is (= 2 (:files-changed r)) "the delta gate still names everything that moved")
      (is (= 1 (count (:files r))))
      (is (= 1 (:deferred r)) "and a bounded run never reads as a complete one")
      (is (= 1 @calls) "the deferred file was never extracted"))

    (testing "no bookkeeping brings it back — its hash still has no episode"
      (let [r (notes/ingest! s {:dir dir :extractor-fn extractor})]
        (is (= 1 (:files-changed r)))
        (is (nil? (:deferred r)))
        (is (= 2 @calls))))))

(deftest the-write-runs-inside-apply-and-the-model-call-outside-it
  (let [dir (temp-notes-dir)
        s (doto (mem/create) (core/seed!))
        trace (atom [])]
    (spit (str dir "/a.md") "note a\n")
    (notes/ingest! s {:dir dir
                      :extractor-fn (fn [_] (swap! trace conj :extract) one-fact)
                      :apply! (fn [f]
                                (swap! trace conj :enter)
                                (let [v (f)] (swap! trace conj :leave) v))})
    (is (= [:extract :enter :leave] @trace)
        "ingestion runs the full conflict machinery per fact, so its WRITE is what
         has to be serialized — a wrapper around the extraction would hold a lease
         across a completion and queue every other writer behind it")
    (is (seq (:facts (core/get-facts s {:entity "AuthService"})))
        "and the write still landed")))

(defn- context-compile-into [dir s]
  ((requiring-resolve 'claimgraph.context/compile!) s {:harness "codex" :dir dir}))

(deftest cross-harness-consolidation
  ;; the endgame (consuming-auto-memory §5): Claude Code notes and Codex
  ;; memories about the same project merge into ONE graph — restatement
  ;; across harnesses reinforces instead of duplicating, and each side's
  ;; provenance stays its own
  (let [claude-dir (temp-notes-dir)
        codex-dir (temp-notes-dir)
        s (mem/create)
        _ (core/seed! s)
        extraction (fn [& lines] (fn [_] (str/join "\n" lines)))]
    (spit (str claude-dir "/MEMORY.md")
          "# notes\nAuthService prefers argon2. We use trunk-based development.\n")
    (spit (str codex-dir "/thread-042.txt")
          "durable: AuthService prefers argon2. deploys via fly.io.\n")

    (notes/ingest! s {:harness "claude-code" :dir claude-dir
                      :extractor-fn (extraction
                                     "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"argon2\",\"class\":\"preference\"}"
                                     "{\"subject\":\"shoply\",\"predicate\":\"prefers\",\"object\":\"trunk-based development\",\"class\":\"preference\"}")})
    (let [r (notes/ingest! s {:harness "codex" :dir codex-dir
                              :extractor-fn (extraction
                                             "{\"subject\":\"auth-service\",\"predicate\":\"prefers\",\"object\":\"argon2\",\"class\":\"preference\"}"
                                             "{\"subject\":\"shoply\",\"predicate\":\"deployed_via\",\"object\":\"fly.io\",\"object_kind\":\"literal\"}")})]
      (testing "the codex .txt note was ingested at all (generous glob)"
        (is (= 1 (:files-changed r))))
      (testing "cross-harness restatement reinforces through entity resolution"
        (is (= 1 (get-in r [:counts :reinforced]))
            "codex's auth-service aligned to claude's AuthService, same fact")
        (let [fs (filter #(= "argon2" (:object-lit %))
                         (:facts (core/get-facts s {:entity "AuthService"})))]
          (is (= 1 (count fs)) "one fact, two harnesses agreeing"))))

    (testing "each harness's episodes keep their own provenance"
      (let [refs (map :ref (filter #(= :agent-note (:source-type %))
                                   (store/-list-episodes s)))]
        (is (some #(str/starts-with? % "claude-code:") refs))
        (is (some #(str/starts-with? % "codex:") refs))))

    (testing "each side contributes what only it knew"
      (is (seq (:facts (core/get-facts s {:entity "shoply"
                                          :predicate :core/deployed-via}))))
      (is (contains? (set (map :object-lit (:facts (core/get-facts s {:entity "shoply"
                                                                      :predicate :core/prefers}))))
                     "trunk-based development")))

    (testing "compile-context writes into codex's slot"
      (context-compile-into codex-dir s)
      (is (str/includes? (slurp (str codex-dir "/memory_summary.md"))
                         harness/begin-marker)))))
