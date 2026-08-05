(ns claimgraph.judge-test
  "Conflict judge: verdict parsing and resolution planning as pure functions,
  plus the full loop against an in-memory store with an injected judge — no
  LLM. Also the shared shell-out the judge runs on (claimgraph.llm): its
  timeout is the one behaviour there that needs a real subprocess to pin, and
  claimgraph.llm has no test namespace of its own."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.judge :as judge]
            [claimgraph.llm :as llm]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(deftest verdict-parsing-is-tolerant
  (testing "clean verdict"
    (is (= {:relation :supersedes :confidence 0.9 :rationale "B is outdated"}
           (judge/parse-judgment
            "{\"relation\":\"supersedes\",\"confidence\":0.9,\"rationale\":\"B is outdated\"}"))))
  (testing "prose and fences around the verdict"
    (is (= :duplicate
           (:relation (judge/parse-judgment
                       (str/join "\n" ["Looking at both facts:"
                                       "```json"
                                       "{\"relation\":\"duplicate\",\"confidence\":1}"
                                       "```"]))))))
  (testing "confidence is clamped to [0,1] and non-numbers become 0"
    (is (= 1.0 (:confidence (judge/parse-judgment "{\"relation\":\"compatible\",\"confidence\":7}"))))
    (is (= 0.0 (:confidence (judge/parse-judgment "{\"relation\":\"compatible\",\"confidence\":\"high\"}")))))
  (testing "garbage and unknown relations become an unparseable verdict, not a throw"
    (is (= {:relation :unparseable :confidence 0.0} (judge/parse-judgment "no idea")))
    (is (= {:relation :unparseable :confidence 0.0}
           (judge/parse-judgment "{\"relation\":\"sideways\",\"confidence\":0.9}")))))

(deftest multi-line-verdicts-are-recovered
  (testing "pretty-printed object: no single line is valid JSON on its own"
    (is (= {:relation :supersedes :confidence 0.9 :rationale "B is outdated"}
           (judge/parse-judgment
            (str/join "\n" ["{"
                            "  \"relation\": \"supersedes\","
                            "  \"confidence\": 0.9,"
                            "  \"rationale\": \"B is outdated\""
                            "}"])))))
  (testing "pretty-printed inside a ```json fence, with prose around it"
    (is (= {:relation :duplicate :confidence 1.0 :rationale "A restates B"}
           (judge/parse-judgment
            (str/join "\n" ["Both facts say the same thing."
                            "```json"
                            "{"
                            "  \"relation\": \"duplicate\","
                            "  \"confidence\": 1,"
                            "  \"rationale\": \"A restates B\""
                            "}"
                            "```"
                            "Let me know if you want more detail."])))))
  (testing "braces and escaped quotes inside the rationale don't break the scan"
    (is (= {:relation :contradicts :confidence 0.75
            :rationale "A sets {:status :open}, B says \"closed\""}
           (judge/parse-judgment
            (str/join "\n" ["{"
                            "  \"relation\": \"contradicts\","
                            "  \"confidence\": 0.75,"
                            "  \"rationale\": \"A sets {:status :open}, B says \\\"closed\\\"\""
                            "}"])))))
  (testing "a nested object doesn't truncate the verdict"
    (is (= :compatible
           (:relation (judge/parse-judgment
                       (str/join "\n" ["{"
                                       "  \"evidence\": {\"a\": 1, \"b\": {\"c\": 2}},"
                                       "  \"relation\": \"compatible\","
                                       "  \"confidence\": 0.6"
                                       "}"]))))))
  ;; These hold with or without the brace scan, and that is the point: they
  ;; pin the fallback not overreaching. Nothing here is a regression test for
  ;; recovering a verdict — the cases above are.
  (testing "the brace scan invents nothing: garbage stays unparseable"
    (is (= {:relation :unparseable :confidence 0.0}
           (judge/parse-judgment
            (str/join "\n" ["I can't judge these two."
                            "The status field {see above} is ambiguous."])))
        "prose that merely mentions a brace is not an object")
    (is (= {:relation :unparseable :confidence 0.0}
           (judge/parse-judgment "{\"relation\": \"duplicate\", \"confidence\": 0.9"))
        "an unbalanced object is not a verdict")
    (is (= {:relation :unparseable :confidence 0.0}
           (judge/parse-judgment
            (str/join "\n" ["{" "  \"relation\": \"sideways\"," "  \"confidence\": 0.9" "}"])))
        "an unknown relation is not rescued by the multi-line path")))

(deftest brace-scan-cost-stays-linear
  ;; The scan used to restart at every '{', so a brace that never closes cost a
  ;; walk to end-of-text — and one per brace: 800 of them took ~6s on this
  ;; machine, 4000 had not finished after 110s. That is unbounded time inside
  ;; the call the LLM timeout exists to bound, on precisely the rambling
  ;; response the multi-line path was added to rescue.
  (let [garbage (str/join "\n" (repeat 4000 "{ a line of prose that never closes it"))
        parse (fn [text]
                (let [f (future (judge/parse-judgment text))
                      v (deref f 5000 ::too-slow)]
                  (future-cancel f)
                  v))]
    (is (= {:relation :unparseable :confidence 0.0} (parse garbage))
        "150 KB of unclosed braces must cost one pass, not one pass per brace")
    (is (= :supersedes
           (:relation (parse (str garbage "\n"
                                  (str/join "\n" ["{" "  \"relation\": \"supersedes\","
                                                  "  \"confidence\": 0.9" "}"])))))
        "and a verdict sitting behind all that noise is still recovered")))

(deftest resolution-planning-is-pure
  (let [pair {:fact {:id "f-new"} :candidate {:id "f-old"}}]
    (testing "contradicts is never auto-resolved, regardless of confidence"
      (is (= {:action :none :reason :needs-human}
             (judge/resolution-plan pair {:relation :contradicts :confidence 1.0} 0.8))))
    (testing "low confidence gates action"
      (is (= {:action :none :reason :low-confidence}
             (judge/resolution-plan pair {:relation :duplicate :confidence 0.5} 0.8))))
    (testing "duplicate invalidates the newer fact, naming the twin it kept"
      (is (= {:action :invalidate :fact-id "f-new" :kind :judged-duplicate
              :successor "f-old" :reason "judged duplicate of f-old"}
             (judge/resolution-plan pair {:relation :duplicate :confidence 0.9} 0.8))))
    (testing "supersedes invalidates the established fact, naming its successor"
      ;; the kind and the successor used to exist only inside the sentence,
      ;; and the sentence was phrased so that the compiled context's
      ;; ^superseded by (\S+)$ never matched it
      (is (= {:action :invalidate :fact-id "f-old" :kind :judged-superseded
              :successor "f-new" :reason "judged superseded by f-new"}
             (judge/resolution-plan pair {:relation :supersedes :confidence 0.9} 0.8))))
    (testing "compatible unlinks"
      (is (= {:action :unlink}
             (judge/resolution-plan pair {:relation :compatible :confidence 0.9} 0.8))))
    (testing "unparseable verdicts plan nothing"
      (is (= {:action :none :reason :unparseable}
             (judge/resolution-plan pair {:relation :unparseable :confidence 0.0} 0.8))))))

(defn- verdict-fn [relation confidence]
  (fn [_prompt]
    (str "{\"relation\":\"" (name relation) "\",\"confidence\":" confidence "}")))

(defn- counting-judge
  "A verdict-fn that counts its invocations. The count is the assertion that
  matters for records: a pair the graph already holds a verdict for must cost
  no model call at all."
  [calls relation confidence]
  (let [answer (verdict-fn relation confidence)]
    (fn [prompt] (swap! calls inc) (answer prompt))))

(defn- refuse-to-judge [_prompt]
  (throw (ex-info "the judge must not be called for a recorded verdict" {})))

(defn- curation-refs
  "The curation episode refs the store holds, by prefix — the whole of the
  curator's memory, read the way the code reads it."
  [s prefix]
  (->> (store/-list-episodes s)
       (filter #(= :curation (:source-type %)))
       (map (comp str :ref))
       (filterv #(str/starts-with? % prefix))))

(deftest verdict-refs-are-canonical
  (testing "ids sort and confidence is fixed to two places: one spelling per verdict"
    (is (= "verdict:f-a+f-b=duplicate@0.90" (judge/verdict-ref "f-b" "f-a" :duplicate 0.9)))
    (is (= (judge/verdict-ref "f-a" "f-b" :duplicate 0.9)
           (judge/verdict-ref "f-b" "f-a" :duplicate 0.9))
        "orientation must not change the record, or a re-judged pair mints a second one"))
  (testing "recorded-verdicts reads them back, orientation-free"
    (is (= {#{"f-a" "f-b"} {:relation :duplicate :confidence 0.9}}
           (judge/recorded-verdicts
            [{:source-type :curation :ref "verdict:f-a+f-b=duplicate@0.90"}]))))
  (testing "refs that aren't curation verdicts are skipped, never thrown on"
    ;; the cost of a ref this build can't read is one re-judgment; an
    ;; exception here would be an exception on an unrelated episode read
    (is (empty? (judge/recorded-verdicts
                 [{:source-type :session-log :ref "verdict:f-a+f-b=duplicate@0.90"}
                  {:source-type :curation :ref "enrich:e-1@authservice"}
                  {:source-type :curation :ref "verdict:f-a+f-b=sideways@0.90"}
                  {:source-type :curation :ref "verdict:f-a+f-b=duplicate@high"}
                  {:source-type :curation :ref "verdict:nonsense"}
                  {:source-type :curation}])))))

(deftest record-verdict-is-idempotent
  (let [s (mem/create)
        pair {:fact {:id "f-new" :subject {:name "ADR-1"} :predicate :core/has-status
                     :object-lit "superseded"}
              :candidate {:id "f-old" :subject {:name "ADR-1"} :predicate :core/has-status
                          :object-lit "accepted"}}
        verdict {:relation :supersedes :confidence 0.9 :rationale "B is outdated"}]
    (is (= "verdict:f-new+f-old=supersedes@0.90"
           (judge/record-verdict! s pair verdict {})))
    (testing "re-delivery records nothing: a resolve run acts on a stored verdict"
      (is (nil? (judge/record-verdict! s pair verdict {})))
      (is (= 1 (count (curation-refs s "verdict:")))))
    (testing "the episode is closed at creation, with the readable half in its summary"
      (let [ep (first (store/-list-episodes s))]
        (is (some? (:closed-at ep)))
        (is (= (str "judged supersedes (0.90): ADR-1 core/has-status superseded"
                    " vs ADR-1 core/has-status accepted — B is outdated")
               (:summary ep)))))
    (testing "an unparseable verdict records NOTHING — an unanswered question retries"
      (is (nil? (judge/record-verdict! s pair {:relation :unparseable :confidence 0.0} {})))
      (is (= 1 (count (curation-refs s "verdict:")))))))

(defn- store-with-conflict
  "A store holding one open commitment conflict: ADR-1 has-status accepted
  (established) vs superseded (newer, flagged)."
  []
  (let [s (mem/create)]
    (core/seed! s)
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "accepted"})
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "superseded"})
    s))


(deftest judge-enriches-without-resolving-by-default
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :supersedes 0.95)})]
    (is (= 1 (:conflicts r)))
    (is (zero? (:resolved r)))
    (is (= :supersedes (get-in r [:results 0 :verdict :relation])))
    (is (= 1 (:open (core/conflicts s))) "report-only mode leaves the conflict open")))

(deftest judge-resolves-supersedes
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :supersedes 0.95) :resolve true})]
    (is (= 1 (:resolved r)))
    (is (zero? (:open (core/conflicts s))))
    (let [{:keys [facts]} (core/get-facts s {:entity "ADR-1"})]
      (is (= ["superseded"] (mapv :object-lit facts)) "only the newer status survives"))
    (let [{:keys [history]} (core/get-history s {:subject "ADR-1" :predicate :core/has-status})
          retired (first (filter :t-invalid history))
          survivor (first (remove :t-invalid history))]
      (is (str/includes? (str (:invalidation-reason retired)) "judged superseded"))
      (testing "and the link the compiled view reads is structure, not that sentence"
        (is (= :judged-superseded (:invalidation-kind retired)))
        (is (= (:id survivor) (:successor retired)))))))

(deftest judge-resolves-duplicate-against-the-newer-fact
  (let [s (store-with-conflict)]
    (judge/judge-conflicts! s {:judge-fn (verdict-fn :duplicate 0.9) :resolve true})
    (let [{:keys [facts]} (core/get-facts s {:entity "ADR-1"})]
      (is (= ["accepted"] (mapv :object-lit facts)) "the established fact survives"))))

(deftest judge-unlinks-compatible-pairs
  (let [s (store-with-conflict)]
    (judge/judge-conflicts! s {:judge-fn (verdict-fn :compatible 0.9) :resolve true})
    (is (zero? (:open (core/conflicts s))) "conflict closed without invalidating")
    (is (= 2 (count (:facts (core/get-facts s {:entity "ADR-1"})))) "both facts stay valid")))

(deftest judge-leaves-contradictions-for-humans
  (let [s (store-with-conflict)
        r (judge/judge-conflicts! s {:judge-fn (verdict-fn :contradicts 1.0) :resolve true})]
    (is (zero? (:resolved r)))
    (is (= :needs-human (get-in r [:results 0 :plan :reason])))
    (is (= 1 (:open (core/conflicts s))))))

(deftest stats-count-open-conflicts
  (let [s (store-with-conflict)]
    (is (= 1 (:open-conflicts (core/stats s))))))

(deftest judged-pairs-are-recorded-and-never-re-judged
  (let [s (store-with-conflict)
        calls (atom 0)
        r (judge/judge-conflicts! s {:judge-fn (counting-judge calls :duplicate 0.9)})]
    (is (= 1 @calls))
    (is (nil? (get-in r [:results 0 :from-record])) "a fresh verdict is not a record")
    (is (= 1 (count (curation-refs s "verdict:"))))
    (testing "an enrich-only re-run reports the record and calls nobody"
      (let [again (judge/judge-conflicts! s {:judge-fn refuse-to-judge})]
        (is (= 1 (:conflicts again)))
        (is (true? (get-in again [:results 0 :from-record])))
        (is (= {:relation :duplicate :confidence 0.9}
               (get-in again [:results 0 :verdict])))
        (is (= 1 (:open (core/conflicts s))) "reporting a record resolves nothing")))
    (testing "a resolve run executes the RECORDED verdict, still without a model call"
      (let [resolved (judge/judge-conflicts! s {:judge-fn refuse-to-judge :resolve true})]
        (is (= 1 (:resolved resolved)))
        (is (true? (get-in resolved [:results 0 :from-record])))
        (is (zero? (:open (core/conflicts s))))
        (is (= ["accepted"] (mapv :object-lit (:facts (core/get-facts s {:entity "ADR-1"}))))
            "duplicate closes the newer fact, exactly as a fresh verdict would")))
    (is (= 1 @calls) "one pair, one verdict, one call — ever")))

(deftest a-recorded-verdict-keeps-the-judges-reply-as-evidence
  (let [s (store-with-conflict)
        dir (str (fs/create-temp-dir {:prefix "claimgraph-verdict-evidence"}))
        reply "{\"relation\":\"duplicate\",\"confidence\":0.9,\"rationale\":\"A restates B\"}"]
    (judge/judge-conflicts! s {:judge-fn (constantly reply) :evidence-dir dir})
    (let [ep (first (filter #(= :curation (:source-type %)) (store/-list-episodes s)))]
      (is (some? (:evidence ep)))
      (is (= reply ((requiring-resolve 'claimgraph.evidence/fetch) dir (:evidence ep)))
          "a verdict is a judgment call — the bytes behind it stay auditable"))))

(deftest a-recorded-verdict-below-the-gate-still-costs-nothing
  ;; The gate is on ACTING, not on remembering: a 0.5 duplicate is recorded
  ;; and replayed as a plan that declines, rather than re-bought every pass in
  ;; the hope of a more confident answer to an unchanged question.
  (let [s (store-with-conflict)
        calls (atom 0)]
    (judge/judge-conflicts! s {:judge-fn (counting-judge calls :duplicate 0.5)})
    (let [r (judge/judge-conflicts! s {:judge-fn refuse-to-judge :resolve true})]
      (is (zero? (:resolved r)))
      (is (= :low-confidence (get-in r [:results 0 :plan :reason])))
      (is (= 1 (:open (core/conflicts s))))
      (is (= 1 @calls)))))

(deftest unparseable-verdicts-record-nothing-and-are-retried
  (let [s (store-with-conflict)
        calls (atom 0)
        garbage (fn [_] (swap! calls inc) "I can't judge these two.")]
    (judge/judge-conflicts! s {:judge-fn garbage})
    (is (empty? (curation-refs s "verdict:"))
        "a recorded non-answer would skip a real conflict forever")
    (judge/judge-conflicts! s {:judge-fn garbage})
    (is (= 2 @calls) "the question is asked again next run, bounded by the budget")))

(deftest the-budget-defers-unjudged-pairs-instead-of-dropping-them
  (let [s (store-with-conflict)
        calls (atom 0)
        r (judge/judge-conflicts! s {:judge-fn (counting-judge calls :duplicate 0.9)
                                     :spend! (constantly false)})]
    (is (zero? @calls))
    (is (zero? (:conflicts r)))
    (is (= 1 (:deferred r)) "a bounded run must never read as a complete one")
    (is (empty? (curation-refs s "verdict:")))
    (testing "and the next run, with budget, picks it up"
      (judge/judge-conflicts! s {:judge-fn (counting-judge calls :duplicate 0.9)})
      (is (= 1 @calls))
      (is (= 1 (count (curation-refs s "verdict:")))))))

(defn- seeded []
  (doto (mem/create) (core/seed!)))

(defn- store-with-swept-pair
  "Two exclusive preferences on one subject: invisible to the write path by
  design, and exactly one candidate for the deferred sweep."
  []
  (let [s (seeded)]
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "tabs"})
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "spaces"})
    s))

(deftest sweep-records-the-compatible-verdicts-it-acts-on-least
  ;; The pair that mutates nothing is the one that used to be re-bought
  ;; forever: compatible verdicts were dropped silently, so the same benign
  ;; candidates went back to the judge every single pass. The record IS the
  ;; entire effect here, and it is the reason a converged store's sweep is
  ;; free.
  (let [s (store-with-swept-pair)
        calls (atom 0)
        judge (counting-judge calls :compatible 0.9)]
    (is (zero? (:open (core/conflicts s))) "value exclusivity is not a write-time concern")
    (let [r (judge/sweep-conflicts! s {:judge-fn judge})]
      (is (= 1 (:candidates r)))
      (is (zero? (:linked r)))
      (is (zero? (:open (core/conflicts s))) "a noisy generator still mutates nothing"))
    (is (= 1 (count (curation-refs s "verdict:"))))
    (testing "a second pass proposes nothing and asks nobody"
      (let [again (judge/sweep-conflicts! s {:judge-fn judge})]
        (is (zero? (:candidates again)))
        (is (zero? (:linked again)))
        (is (= 1 @calls))
        (is (zero? (:open (core/conflicts s))))))))

(deftest sweep-proposes-judges-links-and-records
  (let [s (store-with-swept-pair)
        calls (atom 0)
        judge (counting-judge calls :contradicts 0.95)]
    (testing "a contradicts verdict links into the pipeline for the human"
      (let [r (judge/sweep-conflicts! s {:judge-fn judge})]
        (is (= 1 (:linked r)))
        (is (zero? (:resolved r)) "contradictions are never auto-resolved, even swept ones")
        (is (= 1 (:open (core/conflicts s))))
        (is (= 1 (count (curation-refs s "verdict:"))) "the hit is recorded too")))
    (testing "linked pairs are not proposed again, and cost nothing when they aren't"
      (let [again (judge/sweep-conflicts! s {:judge-fn judge})]
        (is (zero? (:candidates again)))
        (is (= 1 @calls))
        (is (= 1 (:open (core/conflicts s))) "and nothing is linked a second time")))))

(deftest sweep-records-nothing-for-an-unparseable-reply
  (let [s (store-with-swept-pair)
        calls (atom 0)
        garbage (fn [_] (swap! calls inc) "no idea")]
    (let [r (judge/sweep-conflicts! s {:judge-fn garbage})]
      (is (= 1 (:candidates r)))
      (is (zero? (:linked r))))
    (is (empty? (curation-refs s "verdict:")))
    (is (= 1 (:candidates (judge/sweep-conflicts! s {:judge-fn garbage})))
        "an unanswered candidate retries under the next run's budget")
    (is (= 2 @calls))))

(deftest sweep-defers-what-the-budget-cannot-reach
  (let [s (store-with-swept-pair)
        calls (atom 0)
        r (judge/sweep-conflicts! s {:judge-fn (counting-judge calls :contradicts 0.9)
                                     :spend! (constantly false)})]
    (is (zero? @calls))
    (is (zero? (:candidates r)))
    (is (= 1 (:deferred r)))
    (is (empty? (curation-refs s "verdict:")))
    (is (zero? (:open (core/conflicts s))) "deferred is pending, not decided")))

(deftest sweep-catches-decision-vs-structure
  (let [s (seeded)]
    (core/assert-fact s {:subject "claimgraph" :predicate :core/decided-against
                         :object "KuzuDB" :object-kind :literal})
    (core/assert-fact s {:subject "claimgraph" :predicate :core/depends-on :object "kuzu-db"})
    (is (zero? (:open (core/conflicts s)))
        "depends-on is in no exclusion group — conservative groups stay quiet at write")
    (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :contradicts 0.9)})]
      (is (= 1 (:candidates r)))
      (is (= "cross-predicate" (name (get-in r [:results 0 :reason]))))
      (is (= 1 (:open (core/conflicts s)))
          "acting against a standing decision surfaces on the deferred pass"))))

(deftest sweep-resolves-with-the-same-plans
  (let [s (seeded)
        last-week (java.util.Date. (- (System/currentTimeMillis) (* 7 86400000)))]
    ;; backdate the older preference so newer/older is unambiguous
    (claimgraph.store/-insert-fact s {:id "f-tabs" :subject (core/ensure-entity s {:name "fmt"})
                                    :predicate :core/prefers :object-kind :literal
                                    :object-lit "tabs" :t-valid last-week :recorded-at last-week
                                    :confidence 0.8 :epistemic :preference :scope "project"
                                    :source-type :user-assertion})
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "spaces"})
    (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :supersedes 0.9)
                                       :resolve true})]
      (is (= 1 (:resolved r))))
    (let [{:keys [facts]} (core/get-facts s {:entity "fmt"})]
      (is (= ["spaces"] (mapv :object-lit facts))
          "the newer preference superseded the older"))
    (is (zero? (:open (core/conflicts s))))))

;; ---------------------------------------------------------------------------
;; The shell-out underneath the judge
;; ---------------------------------------------------------------------------

(deftest llm-timeout-resolution-survives-bad-config
  (testing "explicit option wins, then the env var, then the default"
    (is (= 250 (llm/timeout-ms 250 "5000")))
    (is (= 5000 (llm/timeout-ms nil "5000")))
    (is (= llm/default-timeout-ms (llm/timeout-ms nil nil))))
  (testing "unparseable or non-positive overrides fall back, they don't throw"
    (is (= llm/default-timeout-ms (llm/timeout-ms nil "soon")))
    (is (= llm/default-timeout-ms (llm/timeout-ms nil "0")))
    (is (= llm/default-timeout-ms (llm/timeout-ms nil "-1")))
    (is (= llm/default-timeout-ms (llm/timeout-ms nil "")))))

(deftest llm-completion-is-bounded
  (testing "the two-arity call every ingest path uses still round-trips stdin"
    (is (= "hello" (llm/complete! "cat" "hello"))))
  (testing "a hung command fails fast with actionable ex-data instead of blocking"
    (let [t0 (System/currentTimeMillis)
          e (try (llm/complete! "sleep 30" "prompt" {:timeout-ms 150})
                 (catch clojure.lang.ExceptionInfo e e))
          elapsed (- (System/currentTimeMillis) t0)]
      (is (instance? clojure.lang.ExceptionInfo e) "a wedged CLI must not hang the run")
      (is (= :llm-command-timeout (:type (ex-data e))))
      (is (= "sleep 30" (:command (ex-data e))))
      (is (= 150 (:timeout-ms (ex-data e))))
      (is (true? (:claimgraph/error (ex-data e))) "surfaces as a JSON CLI error")
      (is (< elapsed 10000) "returned on the timeout, not on the command"))))

(deftest llm-timeout-leaves-no-orphan
  ;; The child ignores SIGTERM and would touch the marker well after the
  ;; timeout fires; if anything survived the kill, the marker appears.
  (let [marker (str (fs/create-temp-dir {:prefix "claimgraph-llm-test"}) "/orphan")
        cmd (str "sh -c \"trap '' TERM; sleep 1.5; touch " marker "\"")]
    (is (thrown? clojure.lang.ExceptionInfo (llm/complete! cmd "prompt" {:timeout-ms 100})))
    (Thread/sleep 2000)
    (is (not (fs/exists? marker)) "the killed command's tree never ran on")))

(deftest llm-timeout-kills-the-grandchild-too
  ;; `claude -p` is a wrapper: it honours SIGTERM itself while what it spawned
  ;; need not. Here the wrapper dies politely and the grandchild ignores the
  ;; signal, which is the case an escalation gated on the parent skips
  ;; entirely — and, because the grandchild holds the output pipe, the case
  ;; that also stretches the call itself past the timeout it is enforcing.
  (let [marker (str (fs/create-temp-dir {:prefix "claimgraph-llm-test"}) "/grandchild")
        cmd (str "sh -c \"(trap '' TERM; sleep 1.5; touch " marker ") & wait\"")
        t0 (System/currentTimeMillis)]
    (is (thrown? clojure.lang.ExceptionInfo (llm/complete! cmd "prompt" {:timeout-ms 100})))
    (is (< (- (System/currentTimeMillis) t0) 1400)
        "the call returns on its own kill, not when the grandchild happens to finish")
    (Thread/sleep 2000)
    (is (not (fs/exists? marker)) "a grandchild that ignores SIGTERM still dies with the call")))

(deftest llm-timeout-is-quiet-on-stderr
  ;; Killing the child mid-write breaks the stdin pipe, and babashka's own :in
  ;; copier reports that on the process's real stderr — where, under the
  ;; SessionEnd hook, it lands beside the CLI's JSON error and reads as part of
  ;; it. No in-process binding sees that stream (neither *err* nor System/err
  ;; captures it), so the only honest assertion is a child bb's stderr.
  (let [script (str "(require '[claimgraph.llm :as llm])"
                    "(try (llm/complete! \"sleep 30\" (apply str (repeat 200000 \\x))"
                    "                    {:timeout-ms 150})"
                    "     (catch Exception _ nil))"
                    "(Thread/sleep 500)")
        {:keys [err]} (p/sh "bb" "--classpath" (cp/get-classpath) "-e" script)]
    (is (str/blank? err)
        (str "a timeout with a real-sized prompt must say nothing on stderr, got: "
             (pr-str err)))))
