(ns claimgraph.mcp-test
  "The MCP front-end's pure handler: JSON-RPC in, response maps out — no
  stdio, in-memory store, no pod. Tool names and argument names are what a
  shipped client is wired to, so the tests here read as the contract: every
  option the matching CLI command takes must reach the same core function,
  and nothing already advertised may change spelling."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.cli :as cli]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.mcp :as mcp]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]
            [claimgraph.wire :as wire]))

(defn- setup []
  (let [s (doto (mem/create) (core/seed!))
        db (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-mcp-test"}) "db"))]
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    [s db]))

(defn- chain!
  "web -> api -> db: the smallest graph a traversal can be wrong about, and
  the one both neighbor modes are asked to walk."
  [s]
  (core/assert-fact s {:subject "web" :predicate :core/depends-on
                       :object "api" :object-kind :entity})
  (core/assert-fact s {:subject "api" :predicate :core/depends-on
                       :object "db" :object-kind :entity}))

(defn- tool-result [resp]
  (json/parse-string (get-in resp [:result :content 0 :text]) true))

(defn- call!
  "One tools/call, parsed out of content[0].text — what a client actually sees."
  [s db tool args]
  (tool-result (mcp/handle s db {:id 1 :method "tools/call"
                                 :params {:name tool :arguments args}})))

(defn- as-wire
  "A claimgraph value as a client reads it: through the canonical encoder and
  back, so a comparison against a tool result compares like with like."
  [x]
  (json/parse-string (wire/generate-string x) true))

(defn- cli-stderr
  "One whole command line through cli/run, its error JSON parsed off stderr.

  The CLI's rendering, produced by the CLI. A test that re-implements the
  formula it is checking cannot catch the two surfaces drifting apart, which
  is the only thing such a test is for. Redefining the store opener keeps the
  pod and a real db out of it, and both streams are captured so a command line
  under test never writes on the test runner's output."
  [s args]
  (let [err (java.io.StringWriter.)]
    (with-redefs [cli/open-store (fn [_] s)]
      (binding [*err* err *out* (java.io.StringWriter.)]
        (cli/run args)))
    (some-> (not-empty (str/trim (str err))) (json/parse-string true))))

(deftest lifecycle-and-tools
  (let [[s db] (setup)]
    (testing "initialize"
      (let [r (mcp/handle s db {:id 1 :method "initialize" :params {}})]
        (is (= "claimgraph" (get-in r [:result :serverInfo :name])))
        (is (get-in r [:result :capabilities :tools]))))

    (testing "notifications get no reply"
      (is (nil? (mcp/handle s db {:method "notifications/initialized"}))))

    (testing "tools/list advertises the surface"
      (let [r (mcp/handle s db {:id 2 :method "tools/list" :params {}})]
        (is (= #{"memory_facts" "memory_neighbor" "memory_search" "memory_recall"
                 "memory_history" "memory_conflicts" "memory_coach" "memory_assert"}
               (set (map :name (get-in r [:result :tools])))))))

    (testing "a read tool answers from the graph"
      (let [r (mcp/handle s db {:id 3 :method "tools/call"
                                :params {:name "memory_facts"
                                         :arguments {:entity "api-layer"}}})
            body (tool-result r)]
        (is (false? (get-in r [:result :isError])))
        (is (= "GraphQL" (get-in body [:facts 0 :object-lit])))))

    (testing "the write tool goes through the full machinery under the lease"
      (let [r (mcp/handle s db {:id 4 :method "tools/call"
                                :params {:name "memory_assert"
                                         :arguments {:subject "AuthService"
                                                     :predicate "prefers"
                                                     :object "argon2"
                                                     :class "preference"}}})]
        (is (= "created" (:status (tool-result r))))
        (is (not (fs/exists? (str db ".lock"))) "lease released")))

    (testing "the coach gates over MCP too"
      (let [r (mcp/handle s db {:id 5 :method "tools/call"
                                :params {:name "memory_coach"
                                         :arguments {:task "adopt graphql in the api-layer"}}})]
        (is (true? (:push (tool-result r))))))

    (testing "tool errors come back as isError content, not protocol failures"
      (let [r (mcp/handle s db {:id 6 :method "tools/call"
                                :params {:name "memory_facts"
                                         :arguments {:entity "no-such-entity"}}})]
        (is (true? (get-in r [:result :isError])))
        (is (= "entity-not-found" (:type (tool-result r))))))

    (testing "unknown methods are JSON-RPC errors"
      (is (= -32601 (get-in (mcp/handle s db {:id 7 :method "bogus/thing"})
                            [:error :code]))))))

(deftest tool-names-are-a-shipped-contract
  (let [advertised (set (map :name mcp/tool-defs))]
    (testing "every name a client may already be wired to still answers"
      ;; renaming one of these breaks a config file nobody will think to
      ;; re-read; the only safe move on this list is adding to it
      (is (every? advertised ["memory_facts" "memory_search" "memory_recall"
                              "memory_history" "memory_conflicts" "memory_coach"
                              "memory_assert"])))
    (testing "and each name is memory_ plus a real CLI verb — one rule, not eight"
      (let [cli-verbs (set (keep (comp first :cmds) cli/table))]
        (is (every? cli-verbs (map #(subs % (count "memory_")) advertised)))))))

(deftest input-casing-is-accepted-in-both-spellings
  (let [[s db] (setup)
        facts #(:facts (call! s db "memory_facts" %))]
    (testing "the snake_case a shipped client sends still filters"
      (is (= 1 (count (facts {:entity "api-layer" :min_confidence 0.5}))))
      (is (empty? (facts {:entity "api-layer" :min_confidence 0.9}))))
    (testing "and the kebab a client copies off the CLI filters identically"
      (is (= 1 (count (facts {:entity "api-layer" :min-confidence 0.5}))))
      (is (empty? (facts {:entity "api-layer" :min-confidence 0.9}))))
    (testing "outputs stay kebab either way — the CLI's spelling and the dump's"
      (let [f (first (facts {:entity "api-layer"}))]
        (is (contains? f :effective-confidence))
        (is (not (contains? f :effective_confidence)))))))

(deftest assert-can-say-when-a-fact-was-true
  (let [[s db] (setup)]
    (testing "a closed past interval is one call, as it has always been on the CLI"
      (is (= "created" (:status (call! s db "memory_assert"
                                       {:subject "billing" :predicate "core/has-version"
                                        :object "1.4" :object_kind "literal"
                                        :valid_from "2026-01-01"
                                        :valid_until "2026-03-01"})))))
    (testing "and the fact is visible inside that window and nowhere else"
      (is (= ["1.4"] (mapv :object-lit
                           (:facts (call! s db "memory_facts"
                                          {:entity "billing" :as_of "2026-02-01"})))))
      (is (empty? (:facts (call! s db "memory_facts"
                                 {:entity "billing" :as-of "2026-06-01"})))
          "the window closed in March — and as-of reads the same as as_of")
      (is (empty? (:facts (call! s db "memory_facts" {:entity "billing"})))
          "not valid now either"))
    (testing "an inverted interval fails as a tool error, with the CLI's type"
      (let [r (mcp/handle s db {:id 1 :method "tools/call"
                                :params {:name "memory_assert"
                                         :arguments {:subject "billing"
                                                     :predicate "core/has-version"
                                                     :object "9.9"
                                                     :valid_from "2026-05-01"
                                                     :valid_until "2026-04-01"}}})]
        (is (true? (get-in r [:result :isError])))
        (is (= "invalid-interval" (:type (tool-result r))))))))

(deftest assert-reaches-the-rest-of-the-write-path
  (let [[s db] (setup)]
    (testing "scope, episode and the types of the entities the write mints"
      (let [r (call! s db "memory_assert"
                     {:subject "PaymentsAPI" :subject_type "service"
                      :predicate "core/depends-on" :object "stripe-sdk"
                      :object_kind "entity" :object_type "library"
                      :scope "team" :episode "ep-mcp"})]
        (is (= "created" (:status r)))
        (is (= "ep-mcp" (get-in r [:fact :episode])))
        (is (= "service" (get-in r [:fact :subject :type])))
        (is (= "library" (get-in r [:fact :object-ref :type])))
        (is (= ["team" "team"] [(get-in r [:fact :subject :scope])
                                (get-in r [:fact :object-ref :scope])])
            "one scope argument scopes the whole write, the entities included"))
      (is (= 1 (count (:facts (call! s db "memory_facts"
                                     {:entity "PaymentsAPI" :entity_scope "team"}))))
          "so the obvious follow-up read finds the fact the write just made")
      (is (= "entity-not-found" (:type (call! s db "memory_facts" {:entity "PaymentsAPI"})))
          "and the project scope never saw it"))

    (testing "subject_scope and object_scope split the write where a client needs it split"
      (let [r (call! s db "memory_assert"
                     {:subject "gateway" :subject_scope "project"
                      :predicate "core/depends-on" :object "kong"
                      :object_kind "entity" :object_scope "team"
                      :scope "team"})]
        (is (= "project" (get-in r [:fact :subject :scope])))
        (is (= "team" (get-in r [:fact :object-ref :scope])))
        (is (= "team" (get-in r [:fact :scope])) "the fact is still recorded in scope")))
    (testing "on_conflict overrides the default the epistemic class picks"
      (call! s db "memory_assert" {:subject "invoicing" :predicate "core/has-version"
                                   :object "1.0"})
      (is (= "flagged" (:status (call! s db "memory_assert"
                                       {:subject "invoicing" :predicate "core/has-version"
                                        :object "2.0" :on_conflict "flag"})))
          "an observation would have superseded silently"))))

(deftest reads-take-the-filters-the-cli-takes
  (let [[s db] (setup)]
    (testing "include_invalidated reaches the superseded past"
      (let [fid (get-in (call! s db "memory_assert"
                               {:subject "search-index" :predicate "core/has-version"
                                :object "3.1"})
                        [:fact :id])]
        (core/invalidate s {:fact-id fid :reason "test"})
        (is (empty? (:facts (call! s db "memory_facts" {:entity "search-index"}))))
        (is (= 1 (count (:facts (call! s db "memory_facts"
                                       {:entity "search-index"
                                        :include_invalidated true})))))))

    (testing "entity_scope reaches an entity that only exists in another scope"
      (core/assert-fact s {:subject "cache" :subject-scope "team" :scope "team"
                           :predicate :core/written-in :object "Redis"
                           :object-kind :literal :object-scope "team"})
      (is (= "entity-not-found" (:type (call! s db "memory_facts" {:entity "cache"})))
          "the default project scope has no such entity")
      (is (= 1 (count (:facts (call! s db "memory_facts"
                                     {:entity "cache" :entity_scope "team"}))))))

    (testing "min_hits forces recall past a graph answer it would have accepted"
      (is (= "facts" (:tier (call! s db "memory_recall" {:query "GraphQL"}))))
      (is (= "nothing" (:tier (call! s db "memory_recall"
                                     {:query "GraphQL" :min_hits 99})))))

    (testing "subject_scope does the same for history"
      (is (= 1 (count (:history (call! s db "memory_history"
                                       {:subject "cache" :subject_scope "team"
                                        :predicate "core/written-in"}))))))))

(deftest graph-expansion-is-reachable-over-mcp
  (let [[s db] (setup)]
    (chain! s)
    (testing "fixed-depth BFS, the traversal the CLI has had all along"
      (is (= #{"web" "api"}
             (set (map :name (:entities (call! s db "memory_neighbor" {:entity "web"}))))))
      (is (= #{"web" "api" "db"}
             (set (map :name (:entities (call! s db "memory_neighbor"
                                               {:entity "web" :depth 2})))))))
    (testing "and a query switches it to the evidence-guided walk"
      (let [r (call! s db "memory_neighbor" {:entity "web" :query "depends on api"})]
        (is (= "web" (get-in r [:root :name])))
        (is (seq (:facts r)) "the walk followed the query-shaped edge")))

    (testing "the walk answers in the BFS's shape, not a second incompatible one"
      ;; one tool, one payload: a client cannot be asked to switch readers on
      ;; whether it passed an optional argument, and the CLI's `neighbor` was
      ;; fixed for exactly this
      (let [bfs (call! s db "memory_neighbor" {:entity "web" :depth 2})
            walk (call! s db "memory_neighbor" {:entity "web" :query "depends on api"})]
        (is (every? (set (keys walk)) (keys bfs))
            "every key the BFS answers with, the walk answers with too")
        (is (seq (:entities walk)))
        (is (int? (:depth walk)))
        (is (contains? (first (:facts walk)) :effective-confidence))
        (is (contains? (first (:facts walk)) :walk-score)
            "and the walk still keeps what makes it a walk")))

    (testing "and it is the CLI's wrapper, not a second copy of it"
      ;; frozen clock: effective-confidence decays continuously, so two
      ;; renderings a millisecond apart differ in the last digits and only an
      ;; equality this exact can prove the two surfaces run the same code
      (let [at (core/now)]
        (with-redefs [core/now (constantly at)]
          (is (= (as-wire (#'cli/walk-neighborhood
                           (core/guided-walk s {:entity "web" :query "depends on api"})
                           at))
                 (call! s db "memory_neighbor" {:entity "web" :query "depends on api"}))))))))

(deftest a-blank-argument-means-absent
  (let [[s db] (setup)]
    (chain! s)
    (testing "an empty query leaves the BFS alone — and its depth alone"
      ;; "" is what a model writes for an optional property it has no value
      ;; for, so a client that fills in the whole schema used to get a guided
      ;; walk it never asked for, with the depth it did ask for discarded
      (let [r (call! s db "memory_neighbor" {:entity "web" :query "" :depth 2})]
        (is (nil? (:query r)))
        (is (= #{"web" "api" "db"} (set (map :name (:entities r)))))))
    (testing "an empty number is absent too, not a coercion failure"
      (is (= 1 (:depth (call! s db "memory_neighbor" {:entity "web" :depth ""})))))
    (testing "and an empty scope resolves in the default scope, not one named \"\""
      (is (= 1 (count (:facts (call! s db "memory_facts" {:entity "web" :entity_scope ""}))))))))

(deftest numbers-arrive-as-numbers-however-the-model-typed-them
  (let [[s db] (setup)]
    (chain! s)
    (testing "a stringified number filters; it used to be a ClassCastException"
      (is (= 1 (count (:facts (call! s db "memory_facts"
                                     {:entity "api-layer" :min_confidence "0.5"})))))
      (is (empty? (:facts (call! s db "memory_facts"
                                 {:entity "api-layer" :min_confidence "0.9"}))))
      (is (= #{"web" "api" "db"}
             (set (map :name (:entities (call! s db "memory_neighbor"
                                               {:entity "web" :depth "2"}))))))
      (is (= "nothing" (:tier (call! s db "memory_recall"
                                     {:query "GraphQL" :min_hits "99"}))))
      (is (= "created" (:status (call! s db "memory_assert"
                                       {:subject "ledger" :predicate "core/has-version"
                                        :object "2.0" :confidence "0.7"})))))
    (testing "and a value no number can be read out of carries a type and a hint"
      (let [resp (mcp/handle s db {:id 1 :method "tools/call"
                                   :params {:name "memory_facts"
                                            :arguments {:entity "api-layer"
                                                        :min_confidence "high"}}})
            body (tool-result resp)]
        (is (true? (get-in resp [:result :isError])))
        (is (= "invalid-argument" (:type body)))
        (is (= "min-confidence" (:argument body)) "the payload names the argument")
        (is (seq (:hint body)) "a cast exception is the one failure a model cannot repair"))
      (is (= "invalid-argument" (:type (call! s db "memory_neighbor"
                                              {:entity "web" :depth "2.5"})))
          "an integer argument is not a place to put 2.5"))))

(deftest a-tool-call-with-no-query-fails-the-way-the-cli-fails
  (let [[s db] (setup)]
    (testing "search and recall refuse it with the CLI's own message and type"
      (doseq [[tool cmd] [["memory_search" cli/cmd-search]
                          ["memory_recall" cli/cmd-recall]]]
        (let [body (call! s db tool {})
              from-cli (try (cmd {:opts {} :args []})
                            (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo from-cli)
              "the CLI fails on this input — that is the point of comparison")
          (is (= "missing-query" (:type body)) tool)
          (is (= (ex-message from-cli) (:error body))))))
    (testing "a blank query is the same call — answering \"nothing found\" to it
              teaches the model the call was fine"
      (is (= "missing-query" (:type (call! s db "memory_search" {:query ""}))))
      (is (= "missing-query" (:type (call! s db "memory_recall" {:query "   "})))))
    (testing "and coach, whose argument this surface calls task"
      (let [body (call! s db "memory_coach" {})]
        (is (= "missing-query" (:type body)))
        (is (str/includes? (:error body) "task")
            "the message names the argument a client can actually pass")))))

(deftest errors-take-the-channel-that-fits-the-failure
  (let [[s db] (setup)]
    (testing "a tool that ran and failed reports the CLI's payload under isError"
      (store/-ensure-entity s {:name "FooBar" :scope "project"})
      (store/-ensure-entity s {:name "foo-bar" :scope "project"})
      (let [resp (mcp/handle s db {:id 1 :method "tools/call"
                                   :params {:name "memory_facts"
                                            :arguments {:entity "foo_bar"}}})
            body (tool-result resp)]
        (is (true? (get-in resp [:result :isError])))
        (is (= "ambiguous-entity" (:type body)))
        (is (seq (:hint body)) "the hint is what lets the caller repair the call")
        (is (= (cli-stderr s ["facts" "--entity" "foo_bar" "--db" db]) body)
            "identical to what `claim facts` prints on stderr — the CLI's own
             rendering, run, rather than this test's copy of the formula")))

    (testing "and the CLI's internals stay internal"
      ;; :claimgraph/exit is a process exit status. cli/run strips it before
      ;; printing; an MCP client has no process to exit and no way to read a
      ;; number it was never told about
      (let [boom (fn [& _] (logic/fail "the write lease is held"
                                       {:type :lease-held :hint "retry shortly"
                                        :claimgraph/exit 2}))
            from-mcp (with-redefs [core/conflicts boom]
                       (call! s db "memory_conflicts" {}))
            from-cli (with-redefs [core/conflicts boom]
                       (cli-stderr s ["conflicts" "--db" db]))]
        (is (= "the write lease is held" (:error from-cli))
            "the CLI rendered the planted failure — otherwise this proves nothing")
        (is (= from-cli from-mcp) "one error payload behind two surfaces")
        (is (not (contains? from-mcp :claimgraph/exit)))))

    (testing "a tool name this server does not have never ran — protocol error"
      (let [r (mcp/handle s db {:id 2 :method "tools/call"
                                :params {:name "memory_delete" :arguments {}}})]
        (is (nil? (:result r)) "no tool ran, so there is no result to flag")
        (is (= -32602 (get-in r [:error :code])))
        (is (= :unknown-tool (get-in r [:error :data :type])))
        (is (some #{"memory_facts"} (get-in r [:error :data :known]))
            "the reply lists the surface, so a mis-wired client can self-correct")))))
