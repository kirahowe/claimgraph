(ns claimgraph.bench
  "The codebase-memory benchmark. Two layers, split by determinism:

  bb bench      — mechanics: drive the fixture timeline through the real
                  store and ingesters with RECORDED LLM outputs, then score
                  the question set. Deterministic; exits non-zero below a
                  perfect score, so it doubles as a longitudinal regression
                  gate.
  bb bench llm  — quality: build the same graph, then measure a real model
                  (via $CLAIMGRAPH_LLM_CMD, default claude -p) on extraction
                  precision/recall, judge verdict accuracy, and entity
                  fragmentation. Informational; never in CI.

  CLAIMGRAPH_BENCH_STORE=memory runs mechanics pod-free."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [claimgraph.bench.fixture :as fixture]
            [claimgraph.bench.questions :as questions]
            [claimgraph.consolidate :as consolidate]
            [claimgraph.context :as context]
            [claimgraph.core :as core]
            [claimgraph.harness :as harness]
            [claimgraph.ingest.clj-code :as code]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.ingest.session :as session]
            [claimgraph.judge :as judge]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]))

;; ---------------------------------------------------------------------------
;; Timeline execution
;; ---------------------------------------------------------------------------

(defn- transcript [resource]
  (slurp (io/resource resource)))

(defn- write-note!
  "The harness rewriting a note file: fresh content replaces the old, but an
  existing managed section stays where it is — compaction squeezes Claude's
  own notes, not claimgraph's compiled block. The block is found by the same
  reader ingestion strips with, so the bench cannot drift into measuring a
  graph that re-consumes a section it failed to recognise."
  [f content]
  (let [old (when (fs/exists? f) (slurp f))
        section (some-> old harness/managed-section)]
    (spit f (if section (str section "\n\n" content) content))))

(defn- run-step! [s {:keys [code-dir notes-dir]} {:keys [op] :as step}]
  (case op
    :code
    (do (when (fs/exists? code-dir) (fs/delete-tree code-dir))
        (doseq [[path content] (:files step)]
          (let [f (fs/file (str code-dir) path)]
            (io/make-parents f)
            (spit f content)))
        (code/ingest! s {:dir (str code-dir)}))

    :notes
    (do (fs/create-dirs notes-dir)
        (doseq [[path content] (:files step)]
          (write-note! (fs/file (str notes-dir) path) content))
        (notes/ingest! s {:dir (str notes-dir)
                          :extractor-fn fixture/recorded-note-extractor}))

    :compile-context
    (context/compile! s {:dir (str notes-dir)})

    :assert
    (let [a (:args step)]
      (core/assert-fact s (-> a
                              (dissoc :valid-from)
                              (assoc :t-valid (logic/parse-instant (:valid-from a))))))

    :session
    (session/extract! s {:transcript (transcript (:resource step))
                         :ref (:ref step)
                         :extractor-fn (constantly
                                        (get fixture/recorded-extractions (:ref step)))})

    :merge
    (core/merge-entities s {:from (:from step) :into (:into step)})

    :invalidate-object
    (let [{:keys [entity predicate object-lit at reason]} step
          f (->> (:facts (core/get-facts s {:entity entity :predicate predicate}))
                 (filter #(= object-lit (:object-lit %)))
                 first)]
      (core/invalidate s {:fact-id (:id f)
                          :at (logic/parse-instant at)
                          :reason reason}))

    :raw-fact
    (let [{:keys [days-ago fact]} step
          t (java.util.Date. (- (System/currentTimeMillis) (* days-ago 86400000)))]
      (store/-insert-fact s (-> fact
                                (dissoc :subject-name)
                                (assoc :id (str "bench-aged-" (:subject-name fact))
                                       :subject (core/ensure-entity
                                                 s {:name (:subject-name fact)})
                                       :t-valid t
                                       :recorded-at t
                                       :last-reinforced-at t))))

    :probe
    (questions/run-probe! s (:id step))

    :consolidate
    (consolidate/consolidate! s {:summarize-fn fixture/recorded-summarizer
                                 :judge-fn fixture/recorded-judge})))

(defn run-timeline!
  "Execute the fixture timeline against a seeded store."
  [s]
  (let [root (fs/create-temp-dir {:prefix "claimgraph-bench"})
        dirs {:code-dir (fs/path root "code")
              :notes-dir (fs/path root "notes")}]
    (reset! questions/probe-results {})
    (try
      (doseq [step fixture/steps]
        (run-step! s dirs step))
      (finally (fs/delete-tree root))))
  s)

;; ---------------------------------------------------------------------------
;; Mechanics layer
;; ---------------------------------------------------------------------------

(defn run-questions [s]
  (mapv (fn [{:keys [id capability desc run expect]}]
          (let [t0 (System/nanoTime)
                actual (try (run s)
                            (catch Exception e {:error (ex-message e)}))
                ms (/ (- (System/nanoTime) t0) 1e6)]
            {:id id :capability capability :desc desc
             :expected expect :actual actual :pass? (= expect actual)
             :ms ms}))
        questions/questions))

(defn- median [xs]
  (let [xs (vec (sort xs)) n (count xs)]
    (when (pos? n) (nth xs (quot n 2)))))

(defn scorecard [results]
  {:by-capability (into (sorted-map)
                        (map (fn [[cap rs]]
                               [cap {:passed (count (filter :pass? rs))
                                     :total (count rs)}])
                             (group-by :capability results)))
   :passed (count (filter :pass? results))
   :total (count results)
   :score (double (/ (count (filter :pass? results)) (count results)))
   ;; metric hygiene (review §4.3.9): accuracy without latency is half a
   ;; number. Per-question read latency, in-process (pod already warm).
   :latency (let [slowest (apply max-key :ms results)]
              {:median-ms (median (map :ms results))
               :max-ms (:ms slowest)
               :max-q (:id slowest)
               :total-ms (reduce + (map :ms results))})})

(defn- open-store []
  (if (= "memory" (System/getenv "CLAIMGRAPH_BENCH_STORE"))
    ((requiring-resolve 'claimgraph.store.memory/create))
    ((requiring-resolve 'claimgraph.store.datalevin/open-store)
     (str (fs/path (fs/temp-dir) (str "claimgraph-bench-" (random-uuid)))))))

(defn- with-timeline-store [f]
  (let [s (open-store)]
    (try
      (core/seed! s)
      (run-timeline! s)
      (f s)
      (finally (store/-close s)))))

(defn run-mechanics []
  (with-timeline-store
    (fn [s]
      (let [results (run-questions s)]
        {:results results :scorecard (scorecard results)}))))

(defn- print-mechanics [{:keys [results scorecard]}]
  (doseq [{:keys [pass? capability id desc expected actual ms]} results]
    (println (format "%-4s %-14s %-4s %s (%.1fms)"
                     (if pass? "ok" "FAIL") (name capability) (name id) desc ms))
    (when-not pass?
      (println "     expected:" (pr-str expected))
      (println "     actual:  " (pr-str actual))))
  (println)
  (doseq [[cap {:keys [passed total]}] (:by-capability scorecard)]
    (println (format "  %-14s %d/%d" (name cap) passed total)))
  (let [{:keys [median-ms max-ms max-q total-ms]} (:latency scorecard)]
    (println (format "%n  reads: median %.1fms, max %.1fms (%s), total %.1fms (in-process, pod warm)"
                     median-ms max-ms (name max-q) total-ms)))
  (println (format "%nmechanics: %d/%d"
                   (:passed scorecard) (:total scorecard))))

(defn- cli-cold-start-ms
  "The per-invocation truth the in-process numbers hide: bb start + pod load
  + store open + one read, via the real CLI against a throwaway store. The
  trigger data for the MCP front-end (roadmap #27). Nil when the CLI or pod
  isn't runnable here."
  []
  (let [bin (str (fs/path (or (some-> (System/getProperty "user.dir")) ".")
                          "bin" "claim"))]
    (when (fs/exists? bin)
      (try
        (let [db (str (fs/path (fs/temp-dir) (str "claimgraph-cold-" (random-uuid))))
              t0 (System/nanoTime)
              {:keys [exit]} @(p/process [bin "stats" "--db" db]
                                         {:out :string :err :string})]
          (when (zero? exit)
            (/ (- (System/nanoTime) t0) 1e6)))
        (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; LLM quality layer
;; ---------------------------------------------------------------------------

(defn- norm-pred [p]
  (-> (str p)
      (str/replace #"^:?(core/)?" "")
      (str/replace "_" "-")))

(defn- norm-triple [f]
  [(logic/normalize-entity-name (:subject f))
   (norm-pred (:predicate f))
   (logic/normalize-entity-name (:object f))])

(defn- precision-recall [extracted expected]
  (let [hits (count (filter expected extracted))]
    {:precision (if (seq extracted) (double (/ hits (count extracted))) 1.0)
     :recall (if (seq expected) (double (/ hits (count expected))) 1.0)
     :extracted (count extracted)
     :expected (count expected)}))

(defn- llm-extraction-quality [s]
  (vec (for [{:keys [ref resource]} (filter #(= :session (:op %)) fixture/steps)]
         (try
           (let [r (session/extract! s {:transcript (transcript resource)
                                        :ref (str ref "-llm")
                                        :dry-run true})
                 triples (set (map norm-triple (:facts r)))]
             (merge {:session ref
                     :suspect-names (vec (remove fixture/known-entity-names
                                                 (map first triples)))}
                    (precision-recall triples (get fixture/expected-triples ref))))
           (catch Exception e {:session ref :error (ex-message e)})))))

(defn- llm-judge-stability
  "Run the judge k times over every open labeled pair (report-only — no
  store mutation between runs). Single-run judge accuracy is noisy enough to
  mislead (flip rates average 14% in the 2026 judge literature), so the
  headline is accuracy-of-majority WITH the per-pair flip rate next to it —
  a pair that flips at all is a pair the 0.8 resolution gate should not act
  on."
  [s k]
  (try
    (let [runs (mapv (fn [_] (:results (judge/judge-conflicts! s {}))) (range k))
          by-pair (group-by (fn [r] [(get-in r [:fact :id]) (get-in r [:candidate :id])])
                            (apply concat runs))
          graded (mapv (fn [[_ rs]]
                         (let [key (logic/normalize-entity-name
                                    (get-in (first rs) [:fact :object]))
                               label (get fixture/conflict-labels key)
                               verdicts (mapv (comp :relation :verdict) rs)
                               [majority n] (apply max-key val (frequencies verdicts))]
                           {:pair key :label label
                            :verdicts (frequencies verdicts)
                            :majority majority
                            :correct (= label majority)
                            :flip-rate (double (- 1 (/ n (count verdicts))))}))
                       by-pair)]
      {:runs k
       :pairs graded
       :accuracy (when (seq graded)
                   (double (/ (count (filter :correct graded)) (count graded))))
       :mean-flip-rate (when (seq graded)
                         (double (/ (reduce + (map :flip-rate graded))
                                    (count graded))))})
    (catch Exception e {:error (ex-message e)})))

(defn run-llm [{:keys [judge-runs] :or {judge-runs 3}}]
  (with-timeline-store
    (fn [s]
      {:extraction (llm-extraction-quality s)
       :judge (llm-judge-stability s judge-runs)})))

(defn- print-llm [{:keys [extraction judge]}]
  (println "extraction quality (real model vs annotated ground truth):")
  (doseq [{:keys [session precision recall suspect-names error]} extraction]
    (if error
      (println (format "  %-10s ERROR %s" session error))
      (println (format "  %-10s precision %.2f  recall %.2f  suspect names %s"
                       session precision recall (pr-str suspect-names)))))
  (println (format "%njudge stability over %s runs per labeled pair:" (:runs judge)))
  (if (:error judge)
    (println "  ERROR" (:error judge))
    (do (doseq [{:keys [pair label majority correct flip-rate verdicts]} (:pairs judge)]
          (println (format "  %-18s label %-12s majority %-12s flip %.2f %-6s %s"
                           pair (name label) (name (or majority :none))
                           flip-rate (if correct "ok" "WRONG")
                           (pr-str verdicts))))
        (println (format "  accuracy (of majority): %s   mean flip rate: %s"
                         (pr-str (:accuracy judge))
                         (pr-str (:mean-flip-rate judge))))
        (when (some (comp pos? :flip-rate) (:pairs judge))
          (println "  note: pairs that flip are pairs --resolve must not act on;"
                   "the 0.8 gate assumes verdict stability")))))

;; ---------------------------------------------------------------------------

(defn -main [& args]
  (cond
    (= "llm" (first args))
    (print-llm (run-llm {:judge-runs (or (some-> (second args) parse-long) 3)}))

    ;; the retrieval-vs-structure ablation (deterministic, no LLM):
    ;;   bb bench ablation [k]
    (= "ablation" (first args))
    (let [run (requiring-resolve 'claimgraph.bench.ablation/run-ablation)
          print' (requiring-resolve 'claimgraph.bench.ablation/print-ablation)]
      (with-timeline-store
        (fn [s] (print' (run s {:k (or (some-> (second args) parse-long) 3)})))))

    ;; the scale tier (informational, deterministic): bb bench scale [10|100]
    (= "scale" (first args))
    (let [factor (or (some-> (second args) parse-long) 10)
          run (requiring-resolve 'claimgraph.bench.scale/run-scale)
          print' (requiring-resolve 'claimgraph.bench.scale/print-scale)
          s (open-store)]
      (try
        (core/seed! s)
        (print' (run s factor))
        (finally (store/-close s))))

    ;; the headline four-arm A/B (informational, spends real agent calls):
    ;;   bb bench ab                  all four arms, all tasks
    ;;   bb bench ab none claimgraph    a pilot on selected arms
    (= "ab" (first args))
    (let [arms (seq (map keyword (rest args)))
          run-ab (requiring-resolve 'claimgraph.bench.ab/run-ab)
          print-ab (requiring-resolve 'claimgraph.bench.ab/print-ab)]
      (print-ab (run-ab {:arms arms})))

    :else
    (let [{:keys [scorecard] :as report} (run-mechanics)]
      (print-mechanics report)
      (when-let [ms (cli-cold-start-ms)]
        (println (format "  CLI cold start (bb + pod + open + one read): %.0fms" ms)))
      (System/exit (if (= 1.0 (:score scorecard)) 0 1)))))
