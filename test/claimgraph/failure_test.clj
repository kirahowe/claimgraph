(ns claimgraph.failure-test
  "Failure ingester: the lesson, not the diff — with an injected extractor,
  no LLM, no subprocess."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.evidence :as evidence]
            [claimgraph.ingest.failure :as failure]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(deftest prompt-carries-the-guardrails
  (let [prompt (failure/extraction-prompt
                "migrating the orders table"
                "review: rejected — the migration ran without a backup"
                [{:id :core/failure-mode :definition "Known failure mode."}]
                [])]
    (is (str/includes? prompt "the lesson, not the diff"))
    (is (str/includes? prompt "MUTATING"))
    (is (str/includes? prompt "What was being attempted: migrating the orders table"))
    (is (str/includes? prompt "core/failure-mode — Known failure mode."))
    (is (str/includes? prompt "<failure-material>"))))

(deftest failure-extraction-end-to-end
  (let [ev-dir (str (fs/create-temp-dir {:prefix "claimgraph-failure-test"}))
        s (mem/create)
        _ (core/seed! s)
        response (str/join "\n"
                           ["{\"subject\":\"migration-runner\",\"predicate\":\"failure_mode\",\"object\":\"running schema migrations without a fresh backup loses data on rollback\",\"class\":\"observation\",\"confidence\":0.9}"
                            "{\"subject\":\"orders-db\",\"predicate\":\"prefers\",\"object\":\"backup before every schema migration\",\"class\":\"preference\"}"])
        material "PR #42 review: rejected. The migration ran with no backup; rollback lost rows."
        r (failure/extract! s {:transcript material
                               :context "migrating the orders table"
                               :ref "pr-42-rejection"
                               :evidence-dir ev-dir
                               :extractor-fn (fn [_] response)})]
    (testing "lessons land through the full machinery at extraction grade"
      (is (= 2 (:total r)))
      (let [{:keys [facts]} (core/get-facts s {:entity "migration-runner"})
            f (first facts)]
        (is (= :core/failure-mode (:predicate f))
            "the new procedural predicate resolves from snake_case")
        (is (= :failure-report (:source-type f))
            "facts carry the source the trust ranking names, not the session tier's
            (spec/ingestion.allium, decided 2026-07-26)")
        (is (= 0.7 (:confidence f)) "capped like all extraction")))
    (testing "the episode carries the valence type and the raw material"
      (let [ep (store/-get-episode s (:episode r))]
        (is (= :failure-report (:source-type ep)))
        (is (= "pr-42-rejection" (:ref ep)))
        (is (= material (evidence/fetch ev-dir (:evidence ep))))))
    (testing "dry-run writes nothing"
      (let [before (get-in (core/stats s) [:facts :total])
            d (failure/extract! s {:transcript "another failure" :dry-run true
                                   :extractor-fn (fn [_] response)})]
        (is (= :dry-run (:status d)))
        (is (= before (get-in (core/stats s) [:facts :total])))))))
