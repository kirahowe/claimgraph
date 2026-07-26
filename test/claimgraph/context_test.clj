(ns claimgraph.context-test
  "compile-context: the pure parts (section building, supersession pairing,
  budget fold, managed-section splicing) as plain functions, and the shell
  end-to-end over a temp notes dir — including the loop's fixed point:
  compile → ingest-notes → compile never feeds the graph its own view."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.bench :as bench]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Pure: splicing
;; ---------------------------------------------------------------------------

(deftest splice-inserts-at-the-top
  (testing "an empty file becomes just the block"
    (let [out (harness/splice-managed-section nil "view")]
      (is (= (str harness/begin-marker "\nview\n" harness/end-marker "\n") out))))
  (testing "existing harness notes are pushed below the block, unchanged"
    (let [out (harness/splice-managed-section "# Claude's index\n- note\n" "view")]
      (is (str/starts-with? out harness/begin-marker))
      (is (str/ends-with? out "# Claude's index\n- note\n"))))
  (testing "an existing block is replaced in place; splice-then-strip is identity"
    (let [v1 (harness/splice-managed-section "before\n\nafter" "view-1")
          v2 (harness/splice-managed-section v1 "view-2")]
      (is (str/includes? v2 "view-2"))
      (is (not (str/includes? v2 "view-1")))
      (is (= (harness/strip-managed-section v1) (harness/strip-managed-section v2))
          "recompiling never perturbs the non-managed content")))
  (testing "splice output round-trips through strip to the original content"
    (let [notes "# Claude's index\n- note\n"
          round-trip #(str/trim (harness/strip-managed-section %))]
      (is (= (str/trim notes)
             (round-trip (harness/splice-managed-section notes "compiled view")))
          "only the blank line splice leaves behind differs, and ingest trims")
      (is (= (str/trim notes)
             (round-trip (-> notes
                             (harness/splice-managed-section "view-1")
                             (harness/splice-managed-section "view-2"))))
          "recompiles keep round-tripping — the block is replaced, not stacked"))))

(deftest managed-section-is-the-complement-of-the-strip
  (testing "the block strip removes is the block managed-section hands back"
    (let [notes "# Claude's index\n- note\n"
          spliced (harness/splice-managed-section notes "compiled view")]
      (is (= (str harness/begin-marker "\ncompiled view\n" harness/end-marker)
             (harness/managed-section spliced)))
      (is (= spliced (str (harness/managed-section spliced)
                          (harness/strip-managed-section spliced)))
          "block + remainder reconstitutes the file: nothing is claimed twice")))
  (testing "content with no block has no managed section"
    (is (nil? (harness/managed-section "# Notes\nplain")))
    (is (nil? (harness/managed-section nil)))))

;; The markers today's compile writes are frozen, but strip has to keep
;; recognising blocks written by every other version too: a block that stops
;; stripping is silently re-ingested as if the user wrote it (notes-test
;; covers the version-agnostic cases; these pin the cross-version ones).
(def ^:private v2-begin "<!-- claimgraph:managed:begin:v2 -->")
(def ^:private v2-end "<!-- claimgraph:managed:end:v2 -->")

(deftest strip-spans-marker-generations
  (testing "a block written by a future versioned marker strips too"
    (is (= "before\nafter"
           (harness/strip-managed-section
            (str "before\n" v2-begin "\nview\n" v2-end "after")))))
  (testing "an unterminated versioned begin still strips to EOF"
    (is (= "before\n"
           (harness/strip-managed-section (str "before\n" v2-begin "\nview, no end")))))
  (testing "a begin paired with another generation's end degrades to EOF"
    (is (= "before\n"
           (harness/strip-managed-section
            (str "before\n" v2-begin "\nview\n" harness/end-marker "\nnotes")))
        "an end we cannot pair leaves the block undelimited: assume it is all ours"))
  ;; The migration the FROZEN comment blesses, byte for byte: splice matches
  ;; only its own generation's begin marker, so the first v2 compile prepends
  ;; its block above the v1 one already in the file. If strip stopped at the
  ;; first block, the v1 one below would be re-ingested as the user's words.
  (testing "every generation's block strips, not just the first"
    (let [rolled-out (str v2-begin "\nA\n" v2-end "\n"
                          "user\n"
                          harness/begin-marker "\nB\n" harness/end-marker "\ntail")
          out (harness/strip-managed-section rolled-out)]
      ;; both blocks gone, the newlines that bracketed them left alone
      (is (= "\nuser\n\ntail" out))
      (is (not (str/includes? out harness/begin-marker))
          "a surviving old block is read back as if the user had written it")))
  (testing "near-miss marker shapes strip instead of surviving"
    (doseq [begin [v2-begin
                   "<!-- claimgraph:managed:begin:v2-->"
                   "<!--claimgraph:managed:begin:v2-->"
                   "<!--  claimgraph:managed:begin:v2\t-->"
                   "<!-- claimgraph:managed:begin: -->"]]
      (is (= "before\n"
             (harness/strip-managed-section (str "before\n" begin "\nview, no end")))
          (str "unstripped, so re-ingested: " begin))))
  (testing "an empty version suffix reads as the unversioned form"
    (is (= "before\nafter"
           (harness/strip-managed-section
            (str "before\n<!-- claimgraph:managed:begin: -->\nview\n"
                 harness/end-marker "after")))
        "':' with nothing after it pairs with today's plain end marker")))

;; The benchmark rewrites note files the way a compacting harness would, and
;; it used to carry its own marker scan. Two scanners drift: the day markers
;; gain a version, the bench drops the block it no longer recognises instead
;; of leaving it in place, and the timeline stops exercising the echo-loop
;; guard the benchmark exists to hold. It reads the block through
;; harness/managed-section now — one scanner, this test its tripwire.
(deftest bench-note-rewrite-keeps-any-generation-block
  (let [f (str (fs/create-temp-dir {:prefix "claimgraph-bench-note-test"}) "/notes.md")]
    (spit f (str v2-begin "\nview\n" v2-end "\nstale notes\n"))
    (#'bench/write-note! f "fresh notes\n")
    (let [out (slurp f)]
      (is (str/includes? out v2-begin) "compaction squeezes the notes, not our block")
      (is (str/includes? out "fresh notes"))
      (is (not (str/includes? out "stale notes"))))))

;; ---------------------------------------------------------------------------
;; Pure: sections and budget
;; ---------------------------------------------------------------------------

(def now #inst "2026-07-10T12:00:00Z")

(defn- fact [m]
  (merge {:id (str "f-" (hash m))
          :subject {:name "svc"} :predicate :core/prefers
          :object-kind :literal :object-lit "x"
          :t-valid #inst "2026-06-01" :recorded-at #inst "2026-06-01"
          :last-reinforced-at #inst "2026-06-01"
          :confidence 0.6 :epistemic :observation :source-type :session-log}
         m))

(deftest sections-partition-the-graph
  (let [facts [(fact {:id "f-c" :epistemic :commitment :predicate :core/decided-against
                      :object-lit "GraphQL" :subject {:name "api-layer"}
                      :source-type :decision-record :confidence 0.95})
               (fact {:id "f-old" :object-lit "Heroku" :predicate :core/deployed-via
                      :t-invalid #inst "2026-07-01"
                      :invalidation-reason "superseded by f-new"})
               (fact {:id "f-new" :object-lit "Fly" :predicate :core/deployed-via})
               (fact {:id "f-code" :source-type :code :predicate :core/defined-in
                      :object-kind :entity :object-ref {:name "src/x.clj"}
                      :confidence 0.95})]
        sections (context/compiled-sections {:facts facts :conflicts [] :now now})
        by-key (into {} (map (juxt :key identity)) sections)]
    (testing "commitments are the do-not-relitigate list"
      (is (= ["- api-layer decided-against \"GraphQL\" (since 2026-06-01)"]
             (:lines (by-key :commitments)))))
    (testing "recent supersessions render old → new with the change date"
      (is (= ["- svc deployed-via: \"Heroku\" → \"Fly\" (2026-07-01)"]
             (:lines (by-key :supersessions)))))
    (testing "current facts exclude commitments, code facts, and the invalidated"
      (is (= ["- svc deployed-via \"Fly\" (0.44)"]
             (:lines (by-key :facts)))
          "only the live session fact remains, at decay-aware confidence
           (0.6 base, 39 days into a 90-day half-life)"))))

(deftest supersessions-outside-the-window-drop-out
  (is (empty? (context/recent-supersessions
               [(fact {:t-invalid #inst "2026-01-01"
                       :invalidation-reason "superseded by f-x"})]
               now context/supersession-window-days))))

(deftest budget-cuts-low-priority-lines-first
  (let [sections [{:key :a :header "A" :lines ["- a1" "- a2"]}
                  {:key :b :header "B" :lines (mapv #(str "- b" %) (range 50))}]
        full (context/fit-to-budget "P" sections 100000)
        tight (context/fit-to-budget "P" sections 120)]
    (is (str/includes? full "- b49"))
    (is (str/includes? tight "- a2") "high-priority section survives")
    (is (not (str/includes? tight "- b40")))
    (is (str/includes? tight "more — query the graph") "the cut is announced")
    (is (str/blank? (context/fit-to-budget "" [{:key :e :header "E" :lines []}] 100))
        "empty sections are dropped whole")))

(deftest deterministic-output
  (let [inputs {:facts [(fact {})] :conflicts [] :now now}]
    (is (= (context/compiled-view inputs) (context/compiled-view inputs))
        "same graph + same clock = byte-identical view")))

;; ---------------------------------------------------------------------------
;; Shell: end-to-end, and the loop's fixed point
;; ---------------------------------------------------------------------------

(deftest compile-and-the-ambient-loop-fixed-point
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-context-test"}))
        s (mem/create)
        _ (core/seed! s)]
    ;; a commitment, a preference, and a supersession
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    (core/assert-fact s {:subject "AuthService" :predicate :core/prefers
                         :object "Result types" :object-kind :literal
                         :epistemic :preference})
    ;; has-version is cardinality :one — the second write supersedes
    (core/assert-fact s {:subject "svc" :predicate :core/has-version
                         :object "1.0.0" :object-kind :literal})
    (core/assert-fact s {:subject "svc" :predicate :core/has-version
                         :object "2.0.0" :object-kind :literal})

    (testing "dry-run returns the block without touching the filesystem"
      (let [r (context/compile! s {:dir dir :dry-run true})]
        (is (= :dry-run (:status r)))
        (is (str/includes? (:content r) "api-layer decided-against"))
        (is (not (fs/exists? (str dir "/MEMORY.md"))))))

    (testing "compile! writes the managed block into the inject file"
      (let [r (context/compile! s {:dir dir})
            content (slurp (str dir "/MEMORY.md"))]
        (is (= :compiled (:status r)))
        (is (= {:commitments 1 :conflicts 0 :supersessions 1 :facts 2}
               (:sections r)))
        (is (str/includes? content harness/begin-marker))
        (is (str/includes? content "Standing decisions"))
        (is (str/includes? content "\"1.0.0\" → \"2.0.0\""))
        (is (<= (:bytes r) context/default-budget))))

    (testing "the harness's own notes survive a recompile untouched"
      (let [own-notes "\n# Claude's own index\n- remembered thing\n"]
        (spit (str dir "/MEMORY.md") (str (slurp (str dir "/MEMORY.md")) own-notes))
        (context/compile! s {:dir dir})
        (let [content (slurp (str dir "/MEMORY.md"))]
          (is (str/includes? content own-notes))
          (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote harness/begin-marker))
                                  content)))
              "exactly one managed block, replaced in place"))))

    (testing "the first compile into pre-existing notes is not read as a change"
      (let [d2 (str (fs/create-temp-dir {:prefix "claimgraph-context-test2"}))
            calls (atom 0)
            xf (fn [_] (swap! calls inc) "")]
        (spit (str d2 "/MEMORY.md") "# Notes\ndurable observation\n")
        (notes/ingest! s {:dir d2 :extractor-fn xf})
        (is (= 1 @calls))
        (context/compile! s {:dir d2})
        (is (zero? (:files-changed (notes/ingest! s {:dir d2 :extractor-fn xf})))
            "the splice seam around untouched notes never re-triggers extraction")
        (is (= 1 @calls))))

    (testing "fixed point: ingest-notes never re-consumes the compiled view"
      (let [calls (atom 0)
            r (notes/ingest! s {:dir dir :extractor-fn (fn [_] (swap! calls inc) "")})]
        (is (= 1 (:files-changed r))
            "only Claude's own appended notes reach the extractor")
        (is (= 1 @calls))
        ;; recompile after ingest, then re-ingest: nothing changed
        (context/compile! s {:dir dir})
        (let [r2 (notes/ingest! s {:dir dir :extractor-fn (fn [_] (swap! calls inc) "")})]
          (is (zero? (:files-changed r2)))
          (is (= 1 @calls) "compile → ingest → compile is a fixed point"))))

    (testing "a store-only change flows to the file on recompile"
      (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                           :object "SOAP" :object-kind :literal
                           :epistemic :commitment :source-type :decision-record})
      (context/compile! s {:dir dir})
      (is (str/includes? (slurp (str dir "/MEMORY.md")) "SOAP")))))
