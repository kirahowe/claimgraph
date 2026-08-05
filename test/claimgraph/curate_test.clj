(ns claimgraph.curate-test
  "The detached curation run: three stages under one model-call budget,
  against an in-memory store and a temp notes directory with every LLM seam
  injected — no subprocess, no real ~/.claude, no model.

  The things worth pinning here are the ones the old inline SessionEnd pass
  got wrong: that ONE budget spans extraction and maintenance (extraction
  first), that a stage's failure is contained to its report entry, that what
  the budget did not reach is named and comes back by derivation, that the
  write lease is taken per applied outcome rather than held across the run,
  and that a second curator exits instead of racing the live one."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.cli :as cli]
            [claimgraph.core :as core]
            [claimgraph.curate :as curate]
            [claimgraph.harness :as harness]
            [claimgraph.lease :as lease]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "claimgraph-curate-test"})))

(defn- fact-line
  "One extracted fact, as the extractor's JSONL. Literal objects on purpose:
  an entity-kind object would become a second enrichment candidate and the
  budget arithmetic under test is what these fixtures are for."
  [subject]
  (str "{\"subject\":\"" subject "\",\"predicate\":\"prefers\",\"object\":\"Result types\","
       "\"object_kind\":\"literal\",\"class\":\"preference\"}"))

(defn- per-file-extractor
  "An extractor that mints one fact NAMED AFTER the note it read, so a file
  the budget deferred is visible in the graph by its absence. Records which
  files it was actually asked about."
  [asked]
  (fn [prompt]
    (let [file (second (re-find #"<notes file=\"([^\"]+)\">" prompt))]
      (swap! asked conj file)
      (fact-line (str/replace (str file) #"\.md$" "")))))

(defn- entity-names [s]
  (set (map :name (store/-list-entities s {}))))

(defn- pending-summary-store
  "A seeded store with one OPEN fact-bearing episode: exactly one model call
  of maintenance work, so what consolidation is handed can be read off its
  budget rather than inferred."
  []
  (let [s (doto (mem/create) (core/seed!))]
    (core/ingest s {:source-type :session-log :ref "sess-1"}
                 [{:subject "shoply" :predicate "prefers"
                   :object "trunk-based development" :object-kind :literal}])
    s))

;; ---------------------------------------------------------------------------
;; One run, three stages, one budget
;; ---------------------------------------------------------------------------

(deftest curate-runs-capture-then-maintenance-then-recompile
  (let [dir (temp-dir)
        inject (str (fs/path (temp-dir) "VIEW.md"))
        s (pending-summary-store)
        asked (atom [])
        r (do (spit (str dir "/a.md") "note a\n")
              (spit (str dir "/b.md") "note b\n")
              (curate/curate! s {:dir dir
                                 :inject-file inject
                                 :budget 3
                                 :apply! (fn [f] (f))
                                 :extractor-fn (per-file-extractor asked)
                                 :summarize-fn (constantly "what the session settled")
                                 :judge-fn (constantly "{}")
                                 :enrich-fn (constantly "[]")}))]
    (testing "every stage reports, and the run is clean"
      (is (= #{:status :budget :ingest-notes :consolidate :compile-context} (set (keys r))))
      (is (= :ok (:status r)))
      (is (= :ok (get-in r [:ingest-notes :status])))
      (is (= :consolidated (get-in r [:consolidate :status])))
      (is (= :compiled (get-in r [:compile-context :status]))))

    (testing "capture ran first and landed"
      (is (= 2 (get-in r [:ingest-notes :files-changed])))
      (is (= ["a.md" "b.md"] @asked))
      (is (contains? (entity-names s) "a")))

    (testing "ONE budget spans the run, and extraction is served first"
      (is (= {:allowed 1 :spent 1} (get-in r [:consolidate :budget]))
          "two notes off a budget of three leaves consolidation exactly one call")
      (is (= {:allowed 3 :spent 3} (:budget r)))
      (is (= 1 (count (get-in r [:consolidate :episodes :closed])))
          "which the summary stage took, being the most valuable work left")
      (is (pos? (get-in r [:consolidate :enrichment :deferred]))
          "and enrichment, reached with nothing left, is named rather than dropped"))

    (testing "the recompile carries what curation just learned into the injected view"
      (is (str/includes? (slurp inject) harness/begin-marker))
      (is (str/includes? (slurp inject) "Result types")))))

(deftest a-stage-that-throws-does-not-take-the-others-with-it
  (let [dir (temp-dir)
        s (pending-summary-store)
        _ (spit (str dir "/a.md") "note a\n")
        r (curate/curate! s {:dir dir
                             :budget 5
                             :apply! (fn [f] (f))
                             :extractor-fn (fn [_] (throw (ex-info "no claude on PATH" {})))
                             :summarize-fn (constantly "what the session settled")
                             :judge-fn (constantly "{}")
                             :enrich-fn (constantly "[]")})]
    (is (= :partial (:status r)))
    (is (= :error (get-in r [:ingest-notes :status])))
    (is (str/includes? (get-in r [:ingest-notes :error]) "no claude"))
    (is (= :consolidated (get-in r [:consolidate :status]))
        "an extractor failure must never stop the maintenance stages")
    (is (= 1 (count (get-in r [:consolidate :episodes :closed]))))
    (is (= :compiled (get-in r [:compile-context :status]))
        "nor the deterministic compile, which the next session reads either way")))

;; ---------------------------------------------------------------------------
;; Deferral: bounded, named, and convergent without bookkeeping
;; ---------------------------------------------------------------------------

(deftest a-budget-under-the-changed-file-count-defers-the-rest
  (let [dir (temp-dir)
        s (doto (mem/create) (core/seed!))
        asked (atom [])
        run! (fn [budget]
               (curate/curate! s {:dir dir
                                  :budget budget
                                  :apply! (fn [f] (f))
                                  :extractor-fn (per-file-extractor asked)
                                  :summarize-fn (constantly "s")
                                  :judge-fn (constantly "{}")
                                  :enrich-fn (constantly "[]")}))]
    (doseq [f ["a.md" "b.md" "c.md"]] (spit (str dir "/" f) (str "note " f "\n")))

    (let [r (run! 2)]
      (testing "extraction stops at the budget and says so"
        (is (= 3 (get-in r [:ingest-notes :files-changed])))
        (is (= 1 (get-in r [:ingest-notes :deferred])))
        (is (= ["a.md" "b.md"] @asked) "the third file was never extracted"))
      (testing "and nothing was minted for it"
        (is (= #{"a" "b"} (disj (entity-names s) "Result types"))))
      (testing "a run the budget bounded never reads as a complete one"
        (is (= {:allowed 2 :spent 2} (:budget r)))
        (is (= {:allowed 0 :spent 0} (get-in r [:consolidate :budget])))))

    (testing "the next run picks it up by derivation — no counter, no stamp"
      (reset! asked [])
      (let [r (run! 5)]
        (is (= 1 (get-in r [:ingest-notes :files-changed]))
            "the two already-ingested revisions are unchanged; only c.md is owed")
        (is (nil? (get-in r [:ingest-notes :deferred])))
        (is (= ["c.md"] @asked))
        (is (contains? (entity-names s) "c"))))))

;; ---------------------------------------------------------------------------
;; The write lease: per applied outcome, never across a model call
;; ---------------------------------------------------------------------------

(deftest apply-wraps-the-decide-bearing-writes-and-no-model-call
  (let [dir (temp-dir)
        s (doto (mem/create) (core/seed!))
        trace (atom [])
        call (fn [reply] (fn [_] (swap! trace conj :call) reply))
        _ (spit (str dir "/a.md") "note a\n")
        r (curate/curate! s {:dir dir
                             :budget 5
                             :apply! (fn [f]
                                       (swap! trace conj :enter)
                                       (let [v (f)] (swap! trace conj :leave) v))
                             :extractor-fn (fn [_] (swap! trace conj :call)
                                             (fact-line "AuthService"))
                             :summarize-fn (call "s")
                             :judge-fn (call "{}")
                             :enrich-fn (call "[\"identity service\"]")})]
    (testing "the two writes that DECIDE something run inside it"
      (is (= 2 (count (filter #{:enter} @trace)))
          "notes ingestion (full conflict machinery) and alias application (clash refusal)")
      (is (= 1 (count (get-in r [:ingest-notes :files]))))
      (is (= 1 (get-in r [:consolidate :enrichment :considered])))
      (is (= ["identity service"]
             (:aliases (first (filter #(= "AuthService" (:name %))
                                      (store/-list-entities s {})))))))
    (testing "and every model call runs outside it"
      (is (= [:call :enter :leave :call :enter :leave] @trace)
          "a hold that spanned the completion would queue the next session's
           capture behind somebody else's slow model"))))

;; ---------------------------------------------------------------------------
;; The curation lease: a singleton by try-acquire, never a wait
;; ---------------------------------------------------------------------------

(deftest a-second-curator-exits-rather-than-racing-the-live-one
  (let [dir (temp-dir)
        db (str dir "/db")
        s (doto (mem/create) (core/seed!))
        opened (atom 0)
        key (curate/curation-lease-key db)
        token (lease/acquire! key {:owner curate/curator-owner :ttl-ms 60000})]
    (is (= (str db ".curate") key))
    (is (fs/exists? (str db ".curate.lock"))
        "a second lease file beside the store, not the write lock")
    (is (not (fs/exists? (str db ".lock"))))
    (try
      (let [out (java.io.StringWriter.)
            code (with-redefs [cli/open-store (fn [_] (swap! opened inc) s)]
                   (binding [*out* out]
                     (cli/cmd-curate {:opts {:db db :dir dir}})))
            r (json/parse-string (str out) true)]
        (is (nil? code)
            "a live curator means the work is in hand: exiting is SUCCESS, not an error")
        (is (= "already-running" (:status r)))
        (is (= curate/curator-owner (get-in r [:holder :owner])))
        (is (zero? @opened) "and nothing ran — the store was never even opened"))
      (finally (lease/release! key token)))))
