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
  (testing "pure: candidates are alias-less, fact-bearing, bounded"
    (let [cands (consolidate/enrichment-candidates
                 [{:id "e1" :name "A" :aliases []}
                  {:id "e2" :name "B" :aliases ["already"]}
                  {:id "e3" :name "C" :aliases []}]
                 {"e1" 5 "e2" 9 "e3" 0})]
      (is (= ["A"] (mapv :name cands))
          "aliased and fact-less entities are skipped")))
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
      (testing "second pass skips entities that now carry aliases"
        (let [r2 (consolidate/consolidate!
                  s {:summarize-fn (fn [_] "summary")
                     :judge-fn (fn [_] "")
                     :enrich-fn (fn [_] "[\"more\"]")})]
          (is (not-any? #(= "AuthService" (:entity %))
                        (:enriched (:enrichment r2)))))))))
