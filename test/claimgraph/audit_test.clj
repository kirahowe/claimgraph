(ns claimgraph.audit-test
  "The memory-pile consistency scorecard: pure parts (prompt, clamping,
  finding classification, source scan, rendering) as plain functions, and
  the shell end-to-end over a temp fixture project with one planted instance
  of each finding class — injected extractor and judge fns, no LLM, no
  subprocess, no real store, no real ~/.claude."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.audit :as audit]
            [claimgraph.harness :as harness]))

(def ^:private isolated-ctx
  "Harness-resolution context pointing nowhere, so a developer's real
  ~/.claude auto-memory never leaks into a test audit."
  {:home "/nonexistent-home" :env {}})

;; ---------------------------------------------------------------------------
;; Pure: prompt & clamping
;; ---------------------------------------------------------------------------

(deftest audit-prompt-keeps-class-and-demands-quotes
  (let [p (audit/extraction-prompt "CLAUDE.md" "content"
                                   [{:id :core/decided-against :definition "rejected"}]
                                   ["  AuthService"])]
    (is (str/includes? p "\"commitment\"") "the notes prompt forbids commitments; audit allows them")
    (is (str/includes? p "quote") "every claim must carry a verbatim receipt")
    (is (str/includes? p "core/decided-against") "carries the vocabulary")
    (is (str/includes? p "AuthService") "carries the entity roster")
    (is (str/includes? p "file=\"CLAUDE.md\"") "names the file")))

(deftest audit-facts-keep-epistemic-class
  (let [{:keys [facts rejected]}
        (audit/prepare-audit-facts
         [{:subject "api" :predicate "decided_against" :object "GraphQL"
           :class "commitment" :confidence 0.95 :quote "decided against GraphQL"}
          {:subject "AuthService" :predicate "prefers" :object "argon2"
           :class "preference"}
          {:subject "db" :predicate "depends-on" :object "Redis"}
          {:subject "" :predicate "prefers" :object "x"}])]
    (is (= 3 (count facts)))
    (is (= 1 (count rejected)))
    (testing "commitments survive — the anti-notes clamp (spec §5)"
      (is (= :commitment (:epistemic (first facts)))))
    (testing "quotes ride along for the receipt map, never stored"
      (is (= "decided against GraphQL" (:quote (first facts)))))
    (testing "preferences survive; absent class left to the predicate default"
      (is (= :preference (:epistemic (second facts))))
      (is (nil? (:epistemic (nth facts 2)))))
    (testing "confidence caps at the agent-note ceiling, source-type forced"
      (is (= [0.65 0.55 0.55] (mapv :confidence facts)))
      (is (every? #(= :agent-note (:source-type %)) facts)))))

;; ---------------------------------------------------------------------------
;; Pure: classification
;; ---------------------------------------------------------------------------

(deftest pair-classification-and-receipts
  (let [receipts {"f1" {:file "CLAUDE.md" :quote "no GraphQL"}}
        pile {:id "f1" :subject "api" :predicate :core/decided-against
              :object "GraphQL" :source-type :agent-note}
        code {:id "f2" :subject "app" :predicate :core/defined-in
              :object "src/app.clj" :source-type :code}]
    (testing "a code-sourced side makes the pair staleness, not contradiction"
      (is (= "stale" (:kind (audit/pair->finding {:fact pile :candidate code} receipts))))
      (is (= "contradiction"
             (:kind (audit/pair->finding {:fact pile :candidate (assoc code :source-type :agent-note)}
                                         receipts)))))
    (testing "claims carry file+quote receipts; the code side carries source"
      (let [{:keys [claims]} (audit/pair->finding {:fact pile :candidate code} receipts)]
        (is (= {:file "CLAUDE.md" :quote "no GraphQL"}
               (select-keys (first claims) [:file :quote])))
        (is (= "code" (:source (second claims))))
        (is (nil? (:file (second claims))))))))

(deftest pairs-dedupe-by-unordered-ids
  (let [a {:fact {:id "x"} :candidate {:id "y"}}
        b {:fact {:id "y"} :candidate {:id "x"}}]
    (is (= 1 (count (audit/dedupe-pairs [a b])))
        "the same pair via write path AND sweep counts once (spec §9)")))

(deftest injection-arithmetic
  (let [r (audit/injection-report [{:path "a.md" :bytes 20000}
                                   {:path "b.md" :bytes 30000}])]
    (is (= 50000 (:pile-bytes r)))
    (is (true? (:over-budget r)))
    (is (= ["b.md"] (:files-over-window r))))
  (is (false? (:over-budget (audit/injection-report [{:path "a.md" :bytes 100}])))))

(deftest alias-clusters-see-healed-drift
  (is (= [["auth-service" "AuthService"]]
         (audit/alias-clusters [{:name "auth-service" :aliases ["AuthService"]}
                                {:name "api" :aliases []}
                                {:name "db" :aliases nil}]))
      "resolution self-heals drift into aliases; the alias trail IS the cluster"))

;; ---------------------------------------------------------------------------
;; Shell: source collection
;; ---------------------------------------------------------------------------

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "claimgraph-audit-test"})))

(deftest source-scan-defaults-echo-guard-and-extras
  (let [proj (temp-dir)
        extra (temp-dir)]
    (spit (str proj "/CLAUDE.md")
          (str "real content\n" harness/begin-marker "\ncompiled view\n" harness/end-marker))
    (spit (str proj "/AGENTS.md")
          (str harness/begin-marker "\nonly our compiled view\n" harness/end-marker))
    (fs/create-dirs (fs/path proj ".cursor" "rules"))
    (spit (str proj "/.cursor/rules/style.mdc") "always use kebab-case")
    (spit (str extra "/note.md") "extra note content")
    (let [sources (audit/collect-sources {:project proj :dirs [extra]
                                          :ctx isolated-ctx})]
      (testing "managed sections are stripped before anything else sees them"
        (is (= "real content"
               (:content (first (filter #(= "CLAUDE.md" (:path %)) sources))))))
      (testing "files empty after the strip are skipped outright"
        (is (empty? (filter #(= "AGENTS.md" (:path %)) sources))))
      (testing "rules files and extra dirs are in the pile"
        (is (some #(str/ends-with? (:path %) "style.mdc") sources))
        (is (some #(str/ends-with? (:path %) "note.md") sources)))
      (testing "sorted by path for deterministic ingestion order"
        (is (= (sort (map :path sources)) (map :path sources)))))))

;; ---------------------------------------------------------------------------
;; Shell: prerequisites
;; ---------------------------------------------------------------------------

(deftest prerequisites-need-extractor-never-dtlv
  (let [ok (audit/check-prerequisites {:extractor "myllm -p"
                                       :which (fn [b] (str "/bin/" b))})
        missing (audit/check-prerequisites {:extractor "myllm -p"
                                            :which (fn [_] nil)})]
    (is (= :ok (:status ok)))
    (is (not (contains? ok :dtlv)) "pod-free by design — dtlv is not even checked")
    (is (= :error (:status missing)))
    (is (:hint missing)))
  (testing "a missing extractor blocks the run before anything else happens"
    (let [r (audit/audit! {:project (temp-dir) :which (fn [_] nil)
                           :ctx isolated-ctx})]
      (is (= "blocked" (:status r)))
      (is (:hint r)))))

;; ---------------------------------------------------------------------------
;; Shell: the fixture pile, one planted instance of each finding class
;; ---------------------------------------------------------------------------

(defn- write-fixture!
  "A project whose pile plants: one contradiction (prefers vs decided-against
  GraphQL across the two files), one staleness case (a defined-in claim the
  fixture code contradicts), one restatement (argon2, in both files), one
  name-drift pair (auth-service / AuthService), one disagreement (has-version
  1.0 vs 2.0), one pair the judge must rule compatible (Terraform), and one
  ephemeral line the durability filter drops."
  []
  (let [proj (temp-dir)]
    (fs/create-dirs (fs/path proj "src" "fixture"))
    (spit (str proj "/src/fixture/app.clj") "(ns fixture.app (:require [fixture.util]))\n")
    (spit (str proj "/src/fixture/util.clj") "(ns fixture.util)\n")
    (spit (str proj "/AGENTS.md")
          (str "# Agent guide\n"
               "The api-layer prefers GraphQL.\n"
               "auth-service prefers argon2 hashing.\n"
               "Use Terraform for infra.\n"
               "claim-cli is at 1.0.\n"))
    (spit (str proj "/CLAUDE.md")
          (str "# Project notes\n"
               "We decided against GraphQL for the api-layer.\n"
               "AuthService prefers argon2 hashing.\n"
               "We decided against terraform for app deploys.\n"
               "fixture.app lives in src/legacy/app.clj.\n"
               "claim-cli is at 2.0.\n"
               "dev server port 3021 in this worktree\n"))
    proj))

(def ^:private agents-extraction
  (str/join "\n"
            ["{\"subject\":\"api-layer\",\"predicate\":\"prefers\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"The api-layer prefers GraphQL.\"}"
             "{\"subject\":\"auth-service\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"auth-service prefers argon2 hashing.\"}"
             "{\"subject\":\"deploy-tool\",\"predicate\":\"prefers\",\"object\":\"Terraform\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"Use Terraform for infra.\"}"
             "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"1.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 1.0.\"}"]))

(def ^:private claude-extraction
  ;; note: the ephemeral port line is deliberately NOT extracted (the
  ;; durability filter), and the last line is an incomplete triple the
  ;; clamp must reject
  (str/join "\n"
            ["{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against GraphQL for the api-layer.\"}"
             "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"AuthService prefers argon2 hashing.\"}"
             "{\"subject\":\"deploy-tool\",\"predicate\":\"decided_against\",\"object\":\"terraform\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against terraform for app deploys.\"}"
             "{\"subject\":\"fixture.app\",\"predicate\":\"defined_in\",\"object\":\"src/legacy/app.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.app lives in src/legacy/app.clj.\"}"
             "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"2.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 2.0.\"}"
             "{\"subject\":\"\",\"predicate\":\"prefers\",\"object\":\"junk\"}"]))

(defn- fixture-extractor [calls]
  (fn [prompt]
    (swap! calls conj prompt)
    (cond
      (str/includes? prompt "file=\"AGENTS.md\"") agents-extraction
      (str/includes? prompt "file=\"CLAUDE.md\"") claude-extraction
      :else "")))

(defn- fixture-judge
  "Canned verdicts: the Terraform stance pair is ruled compatible (the
  false-positive filter must drop it); everything else genuinely conflicts."
  [prompt]
  (if (str/includes? prompt "Terraform")
    "{\"relation\":\"compatible\",\"confidence\":0.95,\"rationale\":\"infra vs app deploys\"}"
    "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"opposed\"}"))

(deftest audit-end-to-end
  (let [proj (write-fixture!)
        calls (atom [])
        r (audit/audit! {:project proj
                         :ctx isolated-ctx
                         :extractor-fn (fixture-extractor calls)
                         :judge-fn fixture-judge})]
    (is (= "ok" (:status r)))

    (testing "the pile: two files, nine admitted claims, deterministic order"
      (is (= ["AGENTS.md" "CLAUDE.md"] (mapv :path (:files r))))
      (is (= [4 5] (mapv :claims (:files r))))
      (is (= 9 (:claims r)))
      (is (= 2 (count @calls)) "one extraction per file"))

    (testing "code ground truth ingested first (the staleness prong ran)"
      (is (= :ok (get-in r [:code :status])))
      (is (= 2 (get-in r [:code :files]))))

    (testing "the summary — one planted instance of each finding class"
      (is (= {:contradictions 1 :stale 1 :disagreements 1
              :restatements 1 :name-clusters 1}
             (:summary r))))

    (testing "contradiction: the GraphQL stance pair, with receipts and verdict"
      (let [[c] (get-in r [:findings :contradictions])
            by-file (into {} (map (juxt :file identity)) (:claims c))]
        (is (= 1 (count (get-in r [:findings :contradictions]))))
        (is (= "We decided against GraphQL for the api-layer."
               (:quote (by-file "CLAUDE.md"))))
        (is (= "The api-layer prefers GraphQL." (:quote (by-file "AGENTS.md"))))
        (is (= :contradicts (get-in c [:verdict :relation])))))

    (testing "the judged-compatible Terraform pair is removed from the count"
      (is (= 1 (get-in r [:judge :compatible-removed])))
      (is (not-any? (fn [f] (some #(= "deploy-tool" (:subject %)) (:claims f)))
                    (get-in r [:findings :contradictions]))))

    (testing "stale: the pile's defined-in claim collided with the code"
      (let [[f] (get-in r [:findings :stale])
            sides (group-by #(some? (:source %)) (:claims f))]
        (is (= 1 (count (get-in r [:findings :stale]))))
        (is (= "code" (:source (first (sides true)))) "one side is the code itself")
        (is (= "CLAUDE.md" (:file (first (sides false)))))
        (is (every? #(= "fixture.app" (:subject %)) (:claims f)))))

    (testing "disagreement: has-version 1.0 vs 2.0, reported as a pair, no winner"
      (let [[d] (get-in r [:findings :disagreements])]
        (is (= 1 (count (get-in r [:findings :disagreements]))))
        (is (= #{"1.0" "2.0"} (set (map :object (:claims d)))))
        (is (= #{"AGENTS.md" "CLAUDE.md"} (set (map :file (:claims d)))))))

    (testing "restatement: argon2 maintained in both files"
      (let [[f] (get-in r [:findings :restatements])]
        (is (= 1 (count (get-in r [:findings :restatements]))))
        (is (= "auth-service" (:subject f)))
        (is (= 2 (:count f)))
        (is (= #{"AGENTS.md" "CLAUDE.md"} (set (:files f))))))

    (testing "name cluster: the drift resolution healed is still reported"
      (is (some #(= #{"auth-service" "AuthService"} (set %))
                (get-in r [:findings :name-clusters]))))

    (testing "extraction noise is counted, not silently dropped"
      (is (= {:rejected 1 :inadmissible 0 :ambiguous 0}
             (get-in r [:findings :extraction-noise]))))

    (testing "injection arithmetic against the 25 KB window"
      (is (pos? (get-in r [:injection :pile-bytes])))
      (is (= 25000 (get-in r [:injection :window-bytes])))
      (is (false? (get-in r [:injection :over-budget]))))

    (testing "the funnel hint"
      (is (str/includes? (first (:next r)) "claim setup")))

    (testing "the human rendering carries the headline and the receipts"
      (let [out (audit/render-pretty r)]
        (is (str/includes? out "9 claims extracted from 2 files"))
        (is (str/includes? out "1 contradiction"))
        (is (str/includes? out "auth-service / AuthService"))
        (is (str/includes? out "We decided against GraphQL"))
        (is (str/includes? out "claim setup"))))))

(deftest render-pretty-empty-pile-is-a-plain-answer
  (let [out (audit/render-pretty
             {:status "ok" :claims 0 :files []
              :findings {} :summary {}
              :injection {:pile-bytes 0 :window-bytes 25000 :over-budget false}
              :code {:status :ok :files 83 :facts 538
                     :languages [:clojure :typescript]}
              :next ["claim setup  # nothing to migrate — start the graph fresh"]})]
    (testing "no wall of zeros — says what was scanned and what was found"
      (is (str/includes? out "no memory pile found"))
      (is (str/includes? out "CLAUDE.md"))
      (is (not (str/includes? out "contradiction"))))
    (testing "the code baseline still shows its work"
      (is (str/includes? out "538 code facts from 83 files (clojure, typescript)")))
    (testing "the funnel hint adapts"
      (is (str/includes? out "start the graph fresh")))))

(deftest render-pretty-carries-the-code-baseline
  (let [out (audit/render-pretty
             {:status "ok" :claims 3 :files [{:path "CLAUDE.md" :claims 3}]
              :findings {} :summary {}
              :injection {:pile-bytes 1200 :window-bytes 25000 :over-budget false}
              :code {:status :ok :files 12 :facts 90 :languages [:clojure]}
              :next ["claim setup  # the graph tracks these instead of accumulating them"]})]
    (is (str/includes? out "3 claims extracted from 1 file"))
    (is (str/includes? out "90 code facts from 12 files (clojure)"))))

(deftest audit-no-judge-reports-raw-flags
  (let [proj (write-fixture!)
        r (audit/audit! {:project proj
                         :ctx isolated-ctx
                         :extractor-fn (fixture-extractor (atom []))
                         :no-judge true})]
    (testing "without the false-positive filter the Terraform pair stays"
      (is (= 2 (get-in r [:summary :contradictions])))
      (is (= 1 (get-in r [:summary :stale])))
      (is (= :skipped (get-in r [:judge :status])))
      (is (every? #(nil? (:verdict %)) (get-in r [:findings :contradictions]))))))

(deftest audit-no-code-skips-staleness
  (let [proj (write-fixture!)
        r (audit/audit! {:project proj
                         :ctx isolated-ctx
                         :extractor-fn (fixture-extractor (atom []))
                         :judge-fn fixture-judge
                         :no-code true})]
    (is (= :skipped (get-in r [:code :status])))
    (testing "without code ground truth the defined-in claim is just a claim"
      (is (zero? (get-in r [:summary :stale]))))))

(deftest echo-guard-a-pile-of-only-our-own-view-audits-to-zero
  (let [proj (temp-dir)
        calls (atom [])]
    (spit (str proj "/CLAUDE.md")
          (str harness/begin-marker "\ncompiled view: decided against GraphQL\n"
               harness/end-marker))
    (let [r (audit/audit! {:project proj
                           :ctx isolated-ctx
                           :extractor-fn (fn [p] (swap! calls conj p) "")
                           :judge-fn fixture-judge})]
      (is (= "ok" (:status r)))
      (is (zero? (:claims r)))
      (is (empty? (:files r)))
      (is (empty? @calls) "the extractor never runs on our own compiled view")
      (is (= {:contradictions 0 :stale 0 :disagreements 0
              :restatements 0 :name-clusters 0}
             (:summary r))))))
