(ns claimgraph.logic-test
  "The payoff of the functional core: assertion decisions, confidence views,
  and BFS folds tested as plain functions over values — no store, no clock,
  no fixtures. The seeded vocabulary is a value too, so its invariants are
  checked here rather than through a store."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.logic :as logic]
            [claimgraph.predicates :as preds]))

(def t0 #inst "2026-01-01T00:00:00Z")
(def t1 #inst "2026-06-01T00:00:00Z")

(def version-pred {:id :core/has-version :cardinality :one :object-kind :literal})

(defn- candidate [overrides]
  (logic/build-fact (merge {:id "f-new" :now t1
                            :subject {:id "e1" :name "svc"}
                            :predicate :core/has-version
                            :object-kind :literal :object "2.0"
                            :epistemic :observation}
                           overrides)))

(def existing-v1
  {:id "f-old" :predicate :core/has-version :object-kind :literal
   :object-lit "1.0" :epistemic :observation :t-valid t0 :confidence 0.8})

;; The seed ships into every user's store and is effectively frozen once
;; shipped, so a malformed inverse is only cheap to fix today. Nothing reads
;; :inverse-of yet (inverses are computed at query time), which is exactly
;; why the rot goes unnoticed without this check.
(deftest seed-inverses-are-bijective
  (let [by-id (into {} (map (juxt :id identity)) preds/seed)
        declared (into {} (keep (fn [{:keys [id inverse-of]}]
                                  (when inverse-of [id inverse-of])))
                       preds/seed)]
    (is (seq declared) "the seed still declares inverses; this test is not vacuous")
    (doseq [[id inv] declared]
      (is (contains? by-id inv)
          (str id " declares :inverse-of " inv ", which is not in the seed"))
      (is (= id (get declared inv))
          (str id " and " inv " must name each other; an inverse with two "
               "claimants is a lie the query layer never checks")))))

(deftest decide-assert-is-a-pure-function
  (testing "no existing facts -> insert"
    (is (= :insert (:action (logic/decide-assert {:fact (candidate {}) :pred version-pred
                                                  :existing []})))))
  (testing "same object -> reinforce the existing fact"
    (let [d (logic/decide-assert {:fact (candidate {:object "1.0"}) :pred version-pred
                                  :existing [existing-v1]})]
      (is (= :reinforce (:action d)))
      (is (= "f-old" (get-in d [:existing :id])))))
  (testing "observation conflict -> supersede plan naming the losers"
    (let [d (logic/decide-assert {:fact (candidate {}) :pred version-pred
                                  :existing [existing-v1]})]
      (is (= :supersede (:action d)))
      (is (= ["f-old"] (:invalidate d)))))
  (testing "commitment on either side -> flag with candidates"
    (is (= :flag (:action (logic/decide-assert
                           {:fact (candidate {:epistemic :commitment}) :pred version-pred
                            :existing [existing-v1]}))))
    (is (= :flag (:action (logic/decide-assert
                           {:fact (candidate {}) :pred version-pred
                            :existing [(assoc existing-v1 :epistemic :commitment)]})))))
  (testing "caller override wins"
    (is (= :supersede (:action (logic/decide-assert
                                {:fact (candidate {:epistemic :commitment}) :pred version-pred
                                 :existing [existing-v1] :on-conflict :supersede}))))
    (is (= :insert (:action (logic/decide-assert
                             {:fact (candidate {}) :pred version-pred
                              :existing [existing-v1] :on-conflict :ignore})))))
  (testing "many-cardinality predicates never conflict"
    (is (= :insert (:action (logic/decide-assert
                             {:fact (candidate {}) :pred (assoc version-pred :cardinality :many)
                              :existing [existing-v1]}))))))

(deftest a-stated-stronger-class-escalates-instead-of-reinforcing
  ;; Reinforcement carries the EXISTING fact forward, so an escalation that
  ;; reinforced was an escalation discarded: the fact stayed an observation —
  ;; superseding silently on the next contradiction, decaying by disuse — and
  ;; the caller was told it had been recorded.
  (let [decide (fn [{:keys [stated resolved existing]}]
                 (logic/decide-assert
                  {:fact (candidate {:object "1.0"
                                     :epistemic (or resolved stated :observation)})
                   :pred version-pred
                   :existing [existing]
                   :stated-epistemic stated}))
        commitment (assoc existing-v1 :epistemic :commitment)]
    (testing "\"we decided it\" over an observation supersedes"
      (let [d (decide {:stated :commitment :existing existing-v1})]
        (is (= :supersede (:action d)))
        (is (= ["f-old"] (:invalidate d)))
        (is (= t1 (:effective-at d))
            "the observation closes exactly where the commitment starts")))
    (testing "the order is a ladder, not a commitment special case"
      (is (= :supersede (:action (decide {:stated :preference :existing existing-v1})))))
    (testing "an equal or weaker stated class changes nothing"
      (is (= :reinforce (:action (decide {:stated :observation :existing existing-v1}))))
      (is (= :reinforce (:action (decide {:stated :observation :existing commitment})))))
    (testing "an unstated class never escalates, whatever it resolved to"
      ;; the regression that would hurt most, because it is silent and grows the
      ;; store without bound: :core/decided-against DEFAULTS to :commitment, so
      ;; comparing resolved values makes a tier that states :observation
      ;; supersede its own facts on every pass
      (is (= :reinforce (:action (decide {:stated nil :resolved :commitment
                                          :existing existing-v1})))))
    (testing "a backdated escalation flags rather than closing a row before it opened"
      (let [d (logic/decide-assert
               {:fact (candidate {:object "1.0" :epistemic :commitment
                                  :t-valid #inst "2025-06-01T00:00:00Z"})
                :pred version-pred :existing [existing-v1]
                :stated-epistemic :commitment})]
        (is (= :flag (:action d)))
        (is (= :backdated-overlap (:reason d)))
        (is (= ["f-old"] (:link d)))))))

(deftest a-fact-is-born-no-higher-than-its-source-ceiling
  (testing "an explicit confidence above the source's ceiling is clamped at birth"
    (is (= 0.7 (:confidence (candidate {:source-type :session-log :confidence 0.95}))))
    (is (= 0.7 (:confidence (candidate {:source-type :failure-report :confidence 0.95})))
        "failure-report has its own ceiling row — extraction grade, not the 0.9 fallback")
    (is (= 0.65 (:confidence (candidate {:source-type :agent-note :confidence 1.0}))))
    (is (= 0.9 (:confidence (candidate {:confidence 0.99})))
        "no source-type means :user-assertion, capped like any other"))
  (testing "confidence is range-clamped into [0,1] before the ceiling applies"
    (is (= 0.0 (:confidence (candidate {:confidence -0.4})))
        "a negative confidence is a caller bug, corrected at birth like over-claiming")
    (is (= 1.0 (:confidence (candidate {:source-type :decision-record :confidence 3.0})))
        "the top is its own bound, not an accident of the 1.0 ceiling"))
  (testing "the 0.8 default is not an exemption"
    (is (= 0.6 (:confidence (candidate {:source-type :inferred})))))
  (testing "sources that outrank the default keep what they claim"
    (is (= 0.8 (:confidence (candidate {:source-type :code}))))
    (is (= 0.95 (:confidence (candidate {:source-type :code :confidence 0.99}))))
    (is (= 1.0 (:confidence (candidate {:source-type :decision-record :confidence 1.0}))))
    (is (= 0.9 (:confidence (candidate {:source-type :carrier-pigeon :confidence 0.95})))
        "a source-type with no ceiling row falls back to 0.9, not to no cap")))

(deftest loose-object-matching
  (is (logic/same-object-loosely?
       {:object-kind :entity :object-ref {:name "GraphQL"}}
       {:object-kind :literal :object-lit "graph-ql"})
      "entity and literal clothes, same object")
  (is (not (logic/same-object-loosely?
            {:object-kind :entity :object-ref {:name "GraphQL"}}
            {:object-kind :literal :object-lit "REST"}))))

(deftest decide-assert-exclusion-antagonists
  (let [pred {:id :core/decided-against :cardinality :many :object-kind :either}
        fact (logic/build-fact {:id "f-new" :now t1 :subject {:id "e1"}
                                :predicate :core/decided-against
                                :object-kind :literal :object "GraphQL"
                                :epistemic :commitment})
        standing {:id "f-pref" :predicate :core/prefers :object-kind :entity
                  :object-ref {:id "e9" :name "GraphQL"} :epistemic :preference
                  :t-valid t0 :confidence 0.8}]
    (testing "a many-cardinality predicate alone never conflicts"
      (is (= :insert (:action (logic/decide-assert
                               {:fact fact :pred pred :existing [] :exclusion []})))))
    (testing "an exclusion antagonist flags via epistemic composition"
      (let [d (logic/decide-assert {:fact fact :pred pred :existing []
                                    :exclusion [standing]})]
        (is (= :flag (:action d)))
        (is (= ["f-pref"] (:link d)))))
    (testing "caller override remains meaningful for a stance change"
      (is (= :supersede (:action (logic/decide-assert
                                  {:fact fact :pred pred :existing []
                                   :exclusion [standing] :on-conflict :supersede})))))))

(deftest conflict-candidate-generation
  (let [preds {:core/prefers {:id :core/prefers :category :decision
                              :exclusion-group :stance :value-exclusivity :exclusive}
               :core/decided-against {:id :core/decided-against :category :decision
                                      :exclusion-group :stance}
               :core/depends-on {:id :core/depends-on :category :structural}}
        f (fn [id pred obj recorded]
            {:id id :subject {:id "e1" :name "S"} :predicate pred
             :object-kind :literal :object-lit obj
             :t-valid t0 :recorded-at recorded :confidence 0.8})
        facts [(f "f-tabs" :core/prefers "tabs" t0)
               (f "f-spaces" :core/prefers "spaces" t1)
               (f "f-dep" :core/depends-on "KuzuDB" t1)
               (f "f-against" :core/decided-against "kuzu-db" t0)
               (f "f-dep2" :core/depends-on "Redis" t1)]
        at #inst "2026-12-01"
        cands (logic/conflict-candidates facts preds at)
        by-reason (group-by :reason cands)]
    (testing "exclusive many-valued pairs are proposed, newer first"
      (is (= [["f-spaces" "f-tabs"]]
             (mapv (juxt (comp :id :fact) (comp :id :candidate))
                   (:exclusive-values by-reason)))))
    (testing "decision facts sharing an object across predicates are proposed (loose match)"
      (is (= [["f-dep" "f-against"]]
             (mapv (juxt (comp :id :fact) (comp :id :candidate))
                   (:cross-predicate by-reason)))))
    (testing "accumulative structural facts never pair with each other"
      (is (= 2 (count cands))))
    (testing "already-linked pairs are skipped"
      (let [linked (mapv #(if (= "f-spaces" (:id %)) (assoc % :conflicts ["f-tabs"]) %)
                         facts)]
        (is (= [:cross-predicate]
               (mapv :reason (logic/conflict-candidates linked preds at))))))
    (testing "different subjects never pair"
      (let [other (mapv #(if (= "f-against" (:id %))
                           (assoc % :subject {:id "e2" :name "T"}) %)
                        facts)]
        (is (empty? (filter #(= :cross-predicate (:reason %))
                            (logic/conflict-candidates other preds at))))))))

(deftest valid-time-in-plans
  (testing "supersede closes predecessors at the successor's valid-from"
    (let [d (logic/decide-assert {:fact (candidate {:t-valid #inst "2026-03-01T00:00:00Z"})
                                  :pred version-pred :existing [existing-v1]})]
      (is (= :supersede (:action d)))
      (is (= #inst "2026-03-01T00:00:00Z" (:effective-at d)))))
  (testing "a successor starting before its predecessor flags, never inverts"
    (let [d (logic/decide-assert {:fact (candidate {:t-valid #inst "2025-06-01T00:00:00Z"})
                                  :pred version-pred :existing [existing-v1]})]
      (is (= :flag (:action d)))
      (is (= :backdated-overlap (:reason d)))))
  (testing "closed past intervals build; inverted ones fail"
    (is (= t0 (:t-invalid (candidate {:t-valid #inst "2025-06-01T00:00:00Z"
                                      :t-invalid t0}))))
    (is (thrown? clojure.lang.ExceptionInfo (candidate {:t-valid t1 :t-invalid t0}))))
  (testing "ingest payloads carry valid time as ISO strings"
    (is (= #inst "2026-01-01T00:00:00Z"
           (:t-valid (logic/normalize-ingest-fact {:valid-from "2026-01-01"}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (logic/normalize-ingest-fact {:valid-from "not-a-date"})))))

(deftest confidence-is-a-view
  (let [fact {:confidence 0.8 :epistemic :observation :source-type :inferred
              :recorded-at t0 :last-reinforced-at t0}
        day (fn [n] (java.util.Date. (+ (logic/ms t0) (* n 86400000))))]
    (is (= 0.8 (logic/effective-confidence fact t0)) "no decay at the anchor")
    (is (= 0.4 (logic/effective-confidence fact (day 90))) "one half-life halves")
    (is (= 0.05 (logic/effective-confidence fact (day 3650))) "the floor holds")
    (is (= 0.8 (logic/effective-confidence (assoc fact :last-reinforced-at (day 90))
                                           (day 90)))
        "reinforcement resets the disuse clock")
    (is (= 0.8 (logic/effective-confidence (assoc fact :epistemic :commitment) (day 365)))
        "commitments never fade")
    (is (= 0.8 (logic/effective-confidence (assoc fact :source-type :decision-record)
                                           (day 365)))
        "decision-record facts never fade")
    (is (= 0.8 (logic/effective-confidence (dissoc fact :last-reinforced-at) t0))
        "legacy facts anchor on recorded-at")
    (is (= 0.8 (logic/effective-confidence fact #inst "2020-01-01"))
        "an as-of before the anchor sees the undecayed base")))

(deftest reinforcement-confidence-rules
  (is (= 0.7 (logic/reinforced-confidence {:confidence 0.6 :source-type :session-log} 0.95))
      "growth is capped by the source ceiling")
  (is (= 0.65 (logic/reinforced-confidence {:confidence 0.65 :source-type :session-log} 0.5))
      "weaker evidence never lowers the base")
  (is (= 0.99 (logic/reinforced-confidence {:confidence 0.99 :source-type :session-log} 0.95))
      "a base already above its ceiling is preserved, not clawed back")
  (is (= 0.95 (logic/reinforced-confidence {:confidence 0.9 :source-type :code} 0.99))
      "code facts may rise to the mechanical ceiling"))

(deftest bfs-step-folds-purely
  (let [a {:id "ea" :name "A"} b {:id "eb" :name "B"}
        fact {:id "f1" :subject a :object-ref b :object-kind :entity
              :t-valid t0 :confidence 0.9}
        state {:nodes {"ea" (assoc a :depth 0)} :edges {} :frontier #{"ea"}}
        next-state (logic/bfs-step state [fact] (logic/fact-filter {:at t1}) 1)]
    (is (= #{"eb"} (:frontier next-state)))
    (is (= 1 (get-in next-state [:nodes "eb" :depth])))
    (is (contains? (:edges next-state) "f1"))
    (testing "already-seen facts and nodes are not re-added"
      (let [again (logic/bfs-step next-state [fact] (logic/fact-filter {:at t1}) 2)]
        (is (empty? (:frontier again)))))))

(deftest entity-name-normalization
  (is (= "authservice"
         (logic/normalize-entity-name "AuthService")
         (logic/normalize-entity-name "auth-service")
         (logic/normalize-entity-name "auth_service")
         (logic/normalize-entity-name "Auth Service")))
  (is (= "claimgraphcore" (logic/normalize-entity-name "claimgraph.core"))))

(deftest entity-match-precedence
  (let [auth {:id "e1" :name "AuthService" :type :service :aliases ["auth"]}
        other {:id "e2" :name "auth-service" :type :service :aliases []}
        pick (fn [name cands & [type]]
               (logic/pick-entity-match
                {:name name :norm (logic/normalize-entity-name name) :type type}
                cands))]
    (testing "exact name beats everything"
      (is (= [:exact "e2"] ((juxt :via (comp :id :entity))
                            (pick "auth-service" [auth other])))))
    (testing "alias beats normalized"
      (is (= [:alias "e1"] ((juxt :via (comp :id :entity)) (pick "auth" [auth])))))
    (testing "unique normalized match resolves"
      (is (= [:normalized "e1"] ((juxt :via (comp :id :entity)) (pick "auth_service" [auth])))))
    (testing "ambiguous normalized match returns the collision, never a guess"
      (let [r (pick "auth_service" [auth other])]
        (is (= :ambiguous (:via r)))
        (is (= ["e1" "e2"] (mapv :id (:candidates r))))))
    (testing "zero candidates is nil — genuinely new"
      (is (nil? (pick "auth_service" []))))
    (testing "type incompatibility blocks a normalized match"
      (is (nil? (pick "auth_service" [auth] :file)))
      (is (some? (pick "auth_service" [auth] :service))))))

(deftest duplicate-collapse-keeps-the-earliest
  (let [fact (fn [id recorded & {:as over}]
               (merge {:id id :subject {:id "e1"} :predicate :core/depends-on
                       :object-kind :entity :object-ref {:id "e2"}
                       :scope "project" :epistemic :observation
                       :t-valid t0 :recorded-at recorded :confidence 0.8}
                      over))
        facts [(fact "f-early" t0)
               (fact "f-late" t1)
               (fact "f-other-scope" t1 :scope "module:x")
               (fact "f-dead" t0 :t-invalid t1)]]
    (is (= [{:id "f-late" :survivor "f-early"}]
           (logic/collapse-duplicates facts #inst "2026-12-01"))
        "only true duplicates collapse; scope-distinct and invalidated facts don't")))

(deftest an-invalidation-normalizes-both-shapes
  ;; The bare string is what every caller passed before kinds existed and what
  ;; a caller that has not been taught its kind still passes; both backends
  ;; route through here, so this is the one place the compatibility can be
  ;; pinned. Dropping it would retire facts with no recorded reason at all.
  (is (= {:kind nil :successor nil :reason "migrated"}
         (logic/invalidation "migrated")))
  (is (= {:kind :superseded :successor "f-new" :reason "superseded by f-new"}
         (logic/invalidation {:kind :superseded :successor "f-new"
                              :reason "superseded by f-new"})))
  (is (= :superseded (:kind (logic/invalidation {:kind "superseded"})))
      "a kind off the wire arrives as a string; a string matches no reader's set"))

(deftest every-invalidation-kind-has-a-producer
  ;; invalidation-kinds is a claim about the rest of the codebase — each kind
  ;; names the call site that writes it — and two of the seven shipped with no
  ;; call site at all: reconcile and the code ingester still passed a bare
  ;; sentence, so their rows arrived with a nil kind, indistinguishable from a
  ;; write by a build that predates kinds. Nothing failed, because nothing
  ;; compared the set against the code. Grep is crude; a vocabulary that lies
  ;; about what it records is worse.
  (let [root (loop [d (fs/absolutize (or *file* "."))]
               (when d
                 (if (fs/exists? (fs/path d "bb.edn")) d (recur (fs/parent d)))))
        written (->> (fs/glob (fs/path root "src") "**/*.clj")
                     (mapcat #(re-seq #":kind\s+(:[a-z-]+)" (slurp (str %))))
                     (map (comp keyword #(subs % 1) second))
                     set)]
    (is (some? root) "the checkout is locatable; this test is not vacuous")
    (is (empty? (remove written logic/invalidation-kinds))
        "every declared kind is written by some -invalidate call site")))

(deftest duplicate-entity-clusters
  (let [entities [{:id "e1" :name "FooBar" :scope "project"}
                  {:id "e2" :name "foo-bar" :scope "project"}
                  {:id "e3" :name "foo-bar" :scope "other"}]]
    (is (= [{:normalized "foobar" :scope "project"
             :entities [{:id "e1" :name "FooBar"}
                        {:id "e2" :name "foo-bar"}]}]
           (logic/entity-duplicate-clusters entities))
        "clusters are per-scope")))

(deftest open-conflicts-pairs-valid-facts
  (let [facts [{:id "f-new" :conflicts ["f-old" "f-dead" "f-missing"]
                :t-valid t0 :confidence 0.8}
               {:id "f-old" :t-valid t0 :confidence 0.8}
               {:id "f-dead" :t-valid t0 :t-invalid t1 :confidence 0.8}]]
    (is (= [{:fact "f-new" :candidate "f-old"}]
           (mapv #(-> % (update :fact :id) (update :candidate :id))
                 (logic/open-conflicts facts #inst "2026-12-01")))
        "invalidated and missing candidates drop out")
    (is (empty? (logic/open-conflicts facts #inst "2025-01-01"))
        "nothing is in conflict before the facts are valid")))

(deftest normalization
  (is (= {:object-kind "entity"} (logic/normalize-keys {:object_kind "entity"})))
  (is (= {:epistemic "preference"}
         (logic/normalize-ingest-fact {:class "preference"}))
      ":class is an accepted alias for :epistemic")
  (is (= {:epistemic :commitment}
         (logic/normalize-ingest-fact {:epistemic :commitment :class "preference"}))
      "explicit :epistemic wins over :class"))

(deftest admission-control-screens-shape-not-substance
  (let [ctx (logic/admission-ctx [{:name "AuthService" :aliases ["auth-svc"]}]
                                 [{:id :core/prefers} {:id :core/depends-on}])
        good {:subject "AuthService" :predicate "prefers"
              :object "argon2" :confidence 0.7 :epistemic :preference}
        junk-subject {:subject (apply str (repeat 120 "x")) :predicate "prefers"
                      :object "y" :confidence 0.7}
        junk-floor {:subject "A" :predicate "prefers" :object "y" :confidence 0.1}
        junk-essay {:subject "A" :predicate "prefers"
                    :object (apply str (repeat 300 "words ")) :confidence 0.7}
        novel {:subject "BrandNewThing" :predicate "x/odd-relation"
               :object "z" :confidence 0.5}]
    (testing "hard rules gate shape and floor"
      (is (logic/admit? (logic/admission-signals good ctx)))
      (is (not (logic/admit? (logic/admission-signals junk-subject ctx))))
      (is (not (logic/admit? (logic/admission-signals junk-floor ctx))))
      (is (not (logic/admit? (logic/admission-signals junk-essay ctx)))))
    (testing "soft signals scale the score, never the verdict"
      (let [sig-good (logic/admission-signals good ctx)
            sig-novel (logic/admission-signals novel ctx)]
        (is (logic/admit? sig-novel) "unknown subject + coined predicate still admits")
        (is (> (logic/admission-score sig-good) (logic/admission-score sig-novel)))))
    (testing "screen splits and keeps the log"
      (let [{:keys [admitted inadmissible]} (logic/screen-candidates
                                             [good junk-floor] ctx)]
        (is (= ["AuthService"] (mapv :subject admitted)))
        (is (= 1 (count inadmissible)))
        (is (false? (get-in (first inadmissible) [:admission-signals :above-floor]))
            "the inadmissible carry their signals — auditable, not silent")))))

;; ---------------------------------------------------------------------------
;; Object shape: which literal bound a predicate's registry row selects
;; ---------------------------------------------------------------------------

(defn- lit
  "A literal of exactly n characters — the bound is measured in characters,
  so the fixtures have to be too."
  [n]
  (apply str (repeat n "x")))

(deftest the-seed-declares-prose-on-exactly-the-lesson-bearing-predicates
  (let [declared (into {} (keep (fn [{:keys [id object-shape]}]
                                  (when object-shape [id object-shape])))
                       preds/seed)]
    (is (= {:core/failure-mode :prose
            :core/decided-against :prose
            :core/prefers :prose
            :core/motivated-by :prose}
           declared)
        "prose is earned by being lesson-bearing BY DESIGN; every other seed
         row omits the key and reads as :value")
    (is (= declared preds/shipped-shapes)
        "shipped-shapes is derived from the seed, never hand-maintained — the
         fallback chain's authority and the seed cannot be allowed to drift")))

(deftest a-rows-effective-object-shape-falls-back-to-the-shipped-seed
  ;; The no-migration guarantee (spec/claims.allium, Predicate.object_shape):
  ;; every store seeded before the field existed carries core/* rows without
  ;; it, and none of them may lose the bound their name has always implied.
  (testing "a row that declares one is authoritative"
    (is (= :prose (preds/object-shape {:id :x/long-story :object-shape :prose})))
    (is (= :value (preds/object-shape {:id :core/failure-mode :object-shape :value}))))
  (testing "a core/* row written before the field existed materializes the seed"
    (is (= :prose (preds/object-shape {:id :core/failure-mode})))
    (is (= :value (preds/object-shape {:id :core/depends-on}))))
  (testing "anything the seed never shipped is a value"
    (is (= :value (preds/object-shape {:id :x/long-story})))
    (is (= :value (preds/object-shape {:id :core/invented-yesterday})))
    (is (= :value (preds/object-shape {})))))

(deftest a-prose-predicate-admits-the-lesson-a-value-predicate-cannot
  ;; The measured failure: the first curated store rejected 252–606 char
  ;; literals, every one of them a lesson on failure-mode / prefers /
  ;; decided-against. A flat cap defined the prose class as junk.
  (let [ctx (logic/admission-ctx
             [{:name "AuthService"}]
             ;; rows WITHOUT :object-shape, exactly as an existing store
             ;; carries them — the fallback chain is what makes this work
             [{:id :core/failure-mode} {:id :core/depends-on} {:id :x/long-story}])
        candidate (fn [pred n] {:subject "AuthService" :predicate pred
                                :object (lit n) :confidence 0.7})]
    (testing "600 chars of lesson admits on a prose predicate"
      (is (logic/admit? (logic/admission-signals (candidate "failure-mode" 600) ctx))
          "the bare name resolves to :core/*, exactly as :predicate-known does")
      (is (logic/admit? (logic/admission-signals (candidate "core/failure-mode" 600) ctx))
          "and so does the namespaced form"))
    (testing "the same 600 chars on a coinage rejects: prose is declared, not measured"
      (is (not (logic/admit? (logic/admission-signals (candidate "x/long-story" 600) ctx))))
      (is (not (logic/admit? (logic/admission-signals (candidate "x/long-story" 251) ctx)))))
    (testing "prose is a ceiling, not an exemption — a lesson is not a document"
      (is (logic/admit? (logic/admission-signals (candidate "failure-mode" 1000) ctx)))
      (is (not (logic/admit? (logic/admission-signals (candidate "failure-mode" 1100) ctx)))))
    (testing "the value bound is exactly where it always was"
      (is (logic/admit? (logic/admission-signals (candidate "depends-on" 249) ctx)))
      (is (logic/admit? (logic/admission-signals (candidate "depends-on" 250) ctx)))
      (is (not (logic/admit? (logic/admission-signals (candidate "depends-on" 251) ctx)))))
    (testing "an x/* row may declare prose for itself"
      (let [ctx' (logic/admission-ctx [] [{:id :x/long-story :object-shape :prose}])]
        (is (logic/admit? (logic/admission-signals (candidate "x/long-story" 600) ctx')))
        (is (not (logic/admit? (logic/admission-signals
                                (candidate "x/long-story" 1100) ctx'))))))
    (testing "the screen carries the verdict, and the rejection says which signal"
      (let [{:keys [admitted inadmissible]}
            (logic/screen-candidates [(candidate "failure-mode" 600)
                                      (candidate "depends-on" 600)]
                                     ctx)]
        (is (= 1 (count admitted)))
        (is (= 1 (count inadmissible)))
        (is (false? (get-in (first inadmissible)
                            [:admission-signals :object-sane])))))))
