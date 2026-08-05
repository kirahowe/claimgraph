(ns claimgraph.consolidate-test
  "Consolidation: episode planning, summary parsing, the mechanical fallback,
  and promotion-candidate selection as pure functions; the full pass against
  an in-memory store with injected LLM functions."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.consolidate :as consolidate]
            [claimgraph.core :as core]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(deftest episode-planning-is-pure
  (let [episodes [{:id "ep-1"}                          ; open, has facts
                  {:id "ep-2"}                          ; open, empty
                  {:id "ep-3" :closed-at #inst "2026-06-01"}] ; already closed
        facts [{:id "f1" :episode "ep-1"}
               {:id "f2" :episode "ep-1"}
               {:id "f3" :episode "ep-3"}]
        {:keys [to-close skipped-empty]} (consolidate/plan-episodes episodes facts)]
    (is (= ["ep-1"] (mapv (comp :id :episode) to-close)))
    (is (= 2 (count (:facts (first to-close)))))
    (is (= ["ep-2"] skipped-empty)
        "open-but-empty episodes are left alone — they may still be in flight")))

(deftest summary-parsing-is-tolerant
  (is (= "Settled on Result types; rejected GraphQL."
         (consolidate/parse-summary
          "```\nSettled on Result types;\nrejected GraphQL.\n```")))
  (is (nil? (consolidate/parse-summary "   \n```\n```\n"))
      "blank responses become nil so the caller can fall back")
  (testing "runaway responses are capped"
    (let [long-summary (consolidate/parse-summary (apply str (repeat 2000 "x")))]
      (is (<= (count long-summary) (inc consolidate/max-summary-chars))))))

(deftest mechanical-fallback-digests-the-episode
  (let [episode {:id "ep-1" :source-type :session-log :ref "sess-9"}
        facts [{:predicate :core/prefers} {:predicate :core/prefers}
               {:predicate :core/decided-against}]]
    (is (= "session-log episode (sess-9): 3 facts — 2 core/prefers, 1 core/decided-against"
           (consolidate/mechanical-summary episode facts)))))

(deftest promotion-candidates-respect-the-threshold
  (let [predicates [{:id :x/hot :status :testing :definition "used a lot"}
                    {:id :x/cold :status :testing :definition "barely used"}
                    {:id :core/depends-on :status :stable}]
        usage {:x/hot 4 :x/cold 1 :core/depends-on 10}]
    (is (= [{:id :x/hot :usage 4 :definition "used a lot"}]
           (consolidate/promotion-candidates predicates usage 3))
        "only staging predicates above the threshold; stable ones never appear")))

(defn- seeded-store []
  (let [s (mem/create)]
    (core/seed! s)
    s))

(defn- curation-refs
  "The curation episode refs the store holds, by prefix — the curator's whole
  memory, read the way the code reads it."
  [s prefix]
  (->> (store/-list-episodes s)
       (filter #(= :curation (:source-type %)))
       (map (comp str :ref))
       (filterv #(str/starts-with? % prefix))))

(defn- enrich-recorder
  "An enrich-fn that records WHICH entity each prompt asked about: with
  attempts recorded, the calls a pass does not make are the behaviour under
  test, and a count of aliases added cannot see them."
  [asked reply]
  (fn [prompt]
    (swap! asked conj (str/trim (second (re-find #"(?m)^Entity: ([^\n\[]+)" prompt))))
    reply))

(deftest full-pass-with-injected-llm
  (let [s (seeded-store)
        ;; an open session episode with facts, including a staging predicate
        ;; used enough to surface as a promotion candidate
        r (core/ingest s {:source-type :session-log :ref "sess-1"}
                       (concat
                        [{:subject "AuthService" :predicate "prefers" :object "Result types"}]
                        (for [obj ["a" "b" "c"]]
                          {:subject "AuthService" :predicate "x/pairs-well-with" :object obj})))
        ;; an open commitment conflict
        _ (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "accepted"})
        _ (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "superseded"})
        result (consolidate/consolidate!
                s {:summarize-fn (constantly "Chose Result types for AuthService.")
                   :judge-fn (constantly "{\"relation\":\"supersedes\",\"confidence\":0.9}")
                   :resolve true})]
    (testing "the open episode is closed with the LLM summary"
      (is (= [{:episode (:episode r) :facts 4
               :summary "Chose Result types for AuthService."}]
             (get-in result [:episodes :closed])))
      (is (= "Chose Result types for AuthService."
             (:summary (store/-get-episode s (:episode r))))))
    (testing "episodic history is now searchable by its summary"
      (is (= 1 (count (:episodes (core/search s "Result types" {}))))))
    (testing "the conflict was judged and resolved"
      (is (= 1 (get-in result [:conflicts :resolved])))
      (is (zero? (:open (core/conflicts s)))))
    (testing "the staging predicate surfaces for promotion review"
      (is (= [:x/pairs-well-with]
             (mapv :id (:promotion-candidates result)))))
    (testing "a second pass has nothing left to do"
      (let [again (consolidate/consolidate!
                   s {:summarize-fn (constantly "noop")
                      :judge-fn (constantly "{}")})]
        (is (empty? (get-in again [:episodes :closed])))
        (is (zero? (get-in again [:conflicts :conflicts])))))))

(deftest llm-failure-falls-back-to-mechanical-summary
  (let [s (seeded-store)
        r (core/ingest s {:source-type :session-log :ref "sess-2"}
                       [{:subject "A" :predicate "depends-on" :object "B"}])
        result (consolidate/consolidate!
                s {:summarize-fn (fn [_] (throw (ex-info "LLM unavailable" {})))
                   :judge-fn (constantly "{}")})]
    (let [{:keys [summary]} (first (get-in result [:episodes :closed]))]
      (is (str/starts-with? summary "session-log episode (sess-2): 1 facts")
          "the pass still closes the episode with a mechanical digest"))
    (is (some? (:summary (store/-get-episode s (:episode r)))))))

(deftest enrichment-gives-entities-searchable-aliases
  (testing "pure: candidates are alias-less, fact-bearing, most-used first, minus the attempted"
    ;; The cap of 20 this used to carry is gone: the shared call budget is the
    ;; only bound now, and with attempts recorded there is nothing left to
    ;; ration — a hard cap would only hide the remainder from the report.
    (let [cands (consolidate/enrichment-candidates
                 [{:id "e1" :name "A" :aliases []}
                  {:id "e2" :name "B" :aliases ["already"]}
                  {:id "e3" :name "C" :aliases []}
                  {:id "e4" :name "D" :aliases []}
                  {:id "e5" :name "E" :aliases []}]
                 {"e1" 5 "e2" 9 "e3" 0 "e4" 7 "e5" 6}
                 #{(consolidate/enrich-ref {:id "e4" :name "D"})})]
      (is (= ["E" "A"] (mapv :name cands))
          "aliased, fact-less and already-asked entities are all skipped")))
  (testing "pure: alias parsing is tolerant and self-excluding"
    (is (= ["identity service" "sso"]
           (consolidate/parse-aliases
            "Here you go:\n```json\n[\"identity service\", \"sso\", \"AuthService\", \"\"]\n```"
            {:name "AuthService" :aliases []}))))
  (testing "the stage adds aliases through the clash guard"
    (let [s (mem/create)
          _ (core/seed! s)
          _ (core/assert-fact s {:subject "AuthService" :predicate :core/prefers
                                 :object "argon2" :object-kind :literal})
          _ (core/assert-fact s {:subject "sso" :predicate :core/depends-on
                                 :object "AuthService"})
          r (consolidate/consolidate!
             s {:summarize-fn (fn [_] "summary")
                :judge-fn (fn [_] "")
                :enrich-fn (fn [_] "[\"identity service\", \"sso\"]")})]
      (is (= [{:entity "AuthService" :aliases ["identity service"]}]
             (vec (sort-by :entity (:enriched (:enrichment r)))))
          "AuthService's sso suggestion clashed with the sso ENTITY, and
          sso's identity-service suggestion clashed with the ALIAS
          AuthService had just taken — the guard refuses both kinds of
          clash (spec/entities.allium, decided 2026-07-26), so no alias is
          ever held by two entities")
      (is (contains? (set (:aliases (:entity (core/get-facts s {:entity "identity service"}))))
                     "identity service")
          "the alias resolves like any other name")
      (testing "second pass asks nobody: one is aliased now, the other was answered"
        (let [asked (atom [])
              r2 (consolidate/consolidate!
                  s {:summarize-fn (fn [_] "summary")
                     :judge-fn (fn [_] "")
                     :enrich-fn (enrich-recorder asked "[\"more\"]")})]
          (is (empty? @asked))
          (is (zero? (get-in r2 [:enrichment :considered])))
          (is (not-any? #(= "AuthService" (:entity %))
                        (:enriched (:enrichment r2)))))))))

(deftest an-answered-empty-enrichment-is-never-asked-again
  (let [s (seeded-store)
        asked (atom [])
        opts {:summarize-fn (constantly "summary")
              :judge-fn (constantly "{}")
              :enrich-fn (enrich-recorder asked "[]")}]
    (core/assert-fact s {:subject "AuthService" :predicate :core/prefers
                         :object "argon2" :object-kind :literal})
    (let [r (consolidate/consolidate! s opts)]
      (is (= ["AuthService"] @asked))
      (is (= 1 (get-in r [:enrichment :considered])))
      (is (empty? (:enriched (:enrichment r)))))
    (is (= [(consolidate/enrich-ref (:entity (core/get-facts s {:entity "AuthService"})))]
           (curation-refs s "enrich:"))
        "the DELIVERY is what is recorded, whatever it contained")
    (testing "a second pass asks nobody: no-aliases is an answer, not a failure"
      (reset! asked [])
      (let [r2 (consolidate/consolidate! s opts)]
        (is (empty? @asked))
        (is (zero? (get-in r2 [:enrichment :considered])))
        (is (zero? (get-in r2 [:budget :spent])) "a converged store's pass is free")))))

(deftest an-errored-enrichment-call-records-nothing-and-retries
  (let [s (seeded-store)
        calls (atom 0)
        boom (fn [_] (swap! calls inc) (throw (ex-info "LLM unavailable" {})))
        opts {:summarize-fn (constantly "summary")
              :judge-fn (constantly "{}")
              :enrich-fn boom}]
    (core/assert-fact s {:subject "AuthService" :predicate :core/prefers
                         :object "argon2" :object-kind :literal})
    (let [r (consolidate/consolidate! s opts)]
      (is (= 1 @calls))
      (is (empty? (curation-refs s "enrich:")))
      (is (nil? (get-in r [:enrichment :error]))
          "a failed enrichment is a skip, never a blocker"))
    (consolidate/consolidate! s opts)
    (is (= 2 @calls) "an errored call left the question open; it retries next budget")))

(deftest external-only-entities-are-not-worth-a-model-call
  (testing "pure: what the project's own knowledge touches"
    (is (false? (consolidate/project-facing?
                 {:id "e-lib"} [{:subject {:id "e-api"} :scope "external"}]))
        "only ever the target of an external-scoped edge: somebody else's library")
    (is (true? (consolidate/project-facing?
                {:id "e-lib"} [{:subject {:id "e-lib"} :scope "external"}]))
        "as the SUBJECT, the graph is saying something of its own about it")
    (is (true? (consolidate/project-facing?
                {:id "e-lib"} [{:subject {:id "e-api"} :scope "project"}]))))
  (testing "the stage never buys aliases for clojure.string"
    ;; observed 2026-08-05: entities like this are the most-depended-on in a
    ;; code-ingested store, so usage order alone puts them first in line
    (let [s (seeded-store)
          asked (atom [])
          opts {:summarize-fn (constantly "summary")
                :judge-fn (constantly "{}")
                :enrich-fn (enrich-recorder asked "[]")}]
      (core/assert-fact s {:subject "shoply.api" :predicate :core/depends-on
                           :object "clojure.string" :scope "external"})
      (consolidate/consolidate! s opts)
      (is (= ["shoply.api"] @asked))
      (testing "but a fact the project asserts ABOUT it makes it a candidate"
        (reset! asked [])
        (core/assert-fact s {:subject "clojure.string" :predicate :core/prefers
                             :object "the reader-friendly join" :object-kind :literal})
        (consolidate/consolidate! s opts)
        (is (= ["clojure.string"] @asked))))))

(defn- pending-work-store
  "Exactly three model calls of pending work, one per budgeted stage: an open
  fact-bearing episode to summarize, an open conflict to judge, and one
  alias-less entity to enrich. (The swept pair is the same one the write path
  already flagged, so the sweep proposes nothing.)"
  []
  (let [s (seeded-store)]
    (core/ingest s {:source-type :session-log :ref "sess-b"}
                 [{:subject "ADR-1" :predicate "has-status" :object "accepted"}])
    (core/assert-fact s {:subject "ADR-1" :predicate :core/has-status :object "superseded"})
    s))

(deftest the-budget-bounds-the-pass-and-names-what-it-deferred
  (testing "budget 0: nothing is called, everything is named as deferred"
    (let [s (pending-work-store)
          calls (atom 0)
          count-call (fn [reply] (fn [_] (swap! calls inc) reply))
          r (consolidate/consolidate! s {:budget 0
                                         :summarize-fn (count-call "summary")
                                         :judge-fn (count-call "{}")
                                         :enrich-fn (count-call "[]")})]
      (is (zero? @calls))
      (is (= {:allowed 0 :spent 0} (:budget r)))
      (is (= 1 (get-in r [:conflicts :deferred])))
      (is (= 1 (get-in r [:episodes :deferred])))
      (is (= 1 (get-in r [:enrichment :deferred])))
      (is (empty? (get-in r [:episodes :closed])))
      (testing "and the episode the budget never reached stays OPEN for next run"
        (is (nil? (:closed-at (first (remove #(= :curation (:source-type %))
                                             (store/-list-episodes s)))))))))
  (testing "a budget of 2 against 3 pending calls stops at 2 and defers the rest"
    (let [s (pending-work-store)
          calls (atom 0)
          count-call (fn [reply] (fn [_] (swap! calls inc) reply))
          r (consolidate/consolidate!
             s {:budget 2
                :summarize-fn (count-call "summary")
                :judge-fn (count-call "{\"relation\":\"duplicate\",\"confidence\":0.9}")
                :enrich-fn (count-call "[]")})]
      (is (= 2 @calls) "judgments then summaries: most valuable first")
      (is (= {:allowed 2 :spent 2} (:budget r)))
      (is (nil? (get-in r [:conflicts :deferred])))
      (is (= 1 (count (get-in r [:episodes :closed]))))
      (is (= 1 (get-in r [:enrichment :deferred])))
      (testing "the next pass, with a fresh budget, picks up exactly the remainder"
        (reset! calls 0)
        (let [again (consolidate/consolidate!
                     s {:summarize-fn (count-call "summary")
                        :judge-fn (count-call "{\"relation\":\"duplicate\",\"confidence\":0.9}")
                        :enrich-fn (count-call "[]")})]
          (is (= 1 @calls) "the recorded verdict replays free; only enrichment is left")
          (is (= {:allowed consolidate/default-call-budget :spent 1} (:budget again)))
          (is (= 1 (get-in again [:enrichment :considered])))
          (is (nil? (get-in again [:enrichment :deferred]))))))))
