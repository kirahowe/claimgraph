(ns claimgraph.code-ingest-test
  "Code ingester: pure analysis and reconciliation planning as plain
  functions, plus the full pass against an in-memory store on a real
  temp directory."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.ingest.clj-code :as code]
            [claimgraph.store.memory :as mem]))

(deftest source-analysis-is-pure
  (is (= {:ns 'app.a :requires '[app.b clojure.string]}
         (code/analyze-source
          "(ns app.a (:require [app.b :as b] [clojure.string :as str]))")))
  (is (nil? (code/analyze-source "(def not-a-ns 1)")))
  (is (nil? (code/analyze-source "(((")) "unparseable source is skipped, not fatal"))

(deftest fact-derivation-scopes-external-deps
  (let [facts (code/analyses->facts [{:ns 'app.a :file "a.clj" :requires '[app.b clojure.string]}
                                     {:ns 'app.b :file "b.clj" :requires []}]
                                    "code")
        dep-scopes (->> facts
                        (filter #(= :core/depends-on (:predicate %)))
                        (map (juxt :object :scope))
                        (into {}))]
    (is (= "code" (dep-scopes "app.b")) "deps inside the analyzed set keep the scope")
    (is (= "external" (dep-scopes "clojure.string")) "deps outside it are scoped external")))

(deftest stale-facts-is-a-pure-plan
  (let [at #inst "2026-06-10"
        code-fact (fn [id subject pred object]
                    {:id id :subject {:name subject} :predicate pred
                     :object-kind :entity :object-ref {:name object}
                     :source-type :code :scope "code" :t-valid #inst "2026-01-01"})
        existing [(code-fact "f-keep" "app.a" :core/depends-on "app.b")
                  (code-fact "f-gone" "app.a" :core/depends-on "app.c")
                  (assoc (code-fact "f-other-scope" "x" :core/depends-on "y") :scope "elsewhere")
                  (assoc (code-fact "f-user" "app.a" :core/depends-on "app.d")
                         :source-type :user-assertion)
                  (assoc (code-fact "f-already-gone" "app.a" :core/depends-on "app.e")
                         :t-invalid #inst "2026-02-01")]
        produced [{:subject "app.a" :predicate :core/depends-on :object "app.b"}]]
    (is (= ["f-gone"]
           (code/stale-facts existing produced {:scope "code" :at at}))
        "only valid, code-sourced, in-scope facts absent from the new analysis")))

(deftest reingest-invalidates-what-the-code-no-longer-says
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-code-test"})
        s (mem/create)]
    (try
      (core/seed! s)
      (spit (str (fs/path dir "a.clj")) "(ns app.a (:require [app.b]))")
      (spit (str (fs/path dir "b.clj")) "(ns app.b)")
      (let [r (code/ingest! s {:dir (str dir)})]
        (is (zero? (:invalidated r)))
        (is (pos? (get-in r [:counts :created]))))
      ;; a human-recorded fact about the same entity must survive reconciliation
      (core/assert-fact s {:subject "app.a" :predicate :core/prefers
                           :object "small functions"})

      ;; drop the require, delete b.clj entirely
      (spit (str (fs/path dir "a.clj")) "(ns app.a)")
      (fs/delete (fs/path dir "b.clj"))
      (let [r (code/ingest! s {:dir (str dir)})]
        (testing "second pass invalidates the vanished facts"
          ;; app.a depends-on app.b; app.b defined-in b.clj; b.clj written-in clojure
          (is (= 3 (:invalidated r)))
          (is (= {:reinforced 2} (:counts r))
              "surviving facts are reinforced — the pass keeps the graph warm"))
        (testing "the graph now reflects the code"
          (is (empty? (:facts (core/get-facts s {:entity "app.a"
                                                 :predicate :core/depends-on}))))
          (let [{:keys [history]} (core/get-history s {:subject "app.a"
                                                       :predicate :core/depends-on})
                dead (first history)]
            (is (= 1 (count history)) "the dead dependency survives in history")
            (is (some? (:t-invalid dead)))
            (testing "and says why as structure, not only in its sentence"
              ;; Whether a retired fact can be re-derived from the source is a
              ;; question about the KIND. Left in prose it is a regex per
              ;; producer, which is the condition the kinds replaced.
              (is (= :code-absent (:invalidation-kind dead)))
              (is (nil? (:successor dead))
                  "a declaration that vanished has no replacement to name")
              (is (str/includes? (:invalidation-reason dead) "absent at")
                  "the sentence still names the ref a human would go looking at"))))
        (testing "non-code facts are untouched"
          (is (= 1 (count (:facts (core/get-facts s {:entity "app.a"
                                                     :predicate :core/prefers})))))))
      (finally
        (fs/delete-tree dir)))))
