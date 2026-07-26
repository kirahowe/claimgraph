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
            [claimgraph.store]
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

(defn- store-with-conflict
  "A store holding one open commitment conflict: ADR-1 has-status accepted
  (established) vs superseded (newer, flagged)."
  []
  (let [s (mem/create)]
    (core/seed! s)
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "accepted"})
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "superseded"})
    s))

(defn- verdict-fn [relation confidence]
  (fn [_prompt]
    (str "{\"relation\":\"" (name relation) "\",\"confidence\":" confidence "}")))

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

(defn- seeded []
  (doto (mem/create) (core/seed!)))

(deftest sweep-proposes-judges-and-links
  (let [s (seeded)]
    ;; two exclusive preferences: invisible to the write path by design
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "tabs"})
    (core/assert-fact s {:subject "fmt" :predicate :core/prefers :object "spaces"})
    (is (zero? (:open (core/conflicts s))) "value exclusivity is not a write-time concern")
    (testing "compatible verdicts are dropped silently — a noisy generator mutates nothing"
      (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :compatible 0.9)})]
        (is (= 1 (:candidates r)))
        (is (zero? (:linked r)))
        (is (zero? (:open (core/conflicts s))))))
    (testing "a contradicts verdict links into the pipeline for the human"
      (let [r (judge/sweep-conflicts! s {:judge-fn (verdict-fn :contradicts 0.95)})]
        (is (= 1 (:linked r)))
        (is (zero? (:resolved r)) "contradictions are never auto-resolved, even swept ones")
        (is (= 1 (:open (core/conflicts s))))))
    (testing "linked pairs are not proposed again"
      (is (zero? (:candidates (judge/sweep-conflicts!
                               s {:judge-fn (verdict-fn :contradicts 0.95)})))))))

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
