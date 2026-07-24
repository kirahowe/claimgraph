;; # Facts and time
;;
;; Two clocks run through every fact. Valid time (`t-valid` to `t-invalid`)
;; is when the fact was true of the world. Transaction time (`recorded-at`)
;; is when the store learned it. Keeping them apart is what lets the graph
;; record history as it happened, not just as it was discovered, and it is
;; the chapter of temporal-database practice claimgraph carries over wholesale.

(ns temporal
  (:require [claimgraph.core :as core]
            [claimgraph.store.memory :as mem]))

(def store (doto (mem/create) (core/seed!)))

;; ## Recording the past on purpose
;;
;; Valid time is settable on both ends of a write. A fact that was true for
;; a known, closed interval is one assertion, useful when backfilling
;; history the store did not witness:

(-> (core/assert-fact store
                      {:subject "shoply"
                       :predicate :core/deployed-via
                       :object "Heroku"
                       :object-kind :literal
                       :source-type :user-assertion
                       :t-valid #inst "2026-01-01"
                       :t-invalid #inst "2026-03-01"})
    :fact
    (select-keys [:object-lit :t-valid :t-invalid]))

;; The current deployment, valid from the migration date onward:

(-> (core/assert-fact store
                      {:subject "shoply"
                       :predicate :core/deployed-via
                       :object "Fly"
                       :object-kind :literal
                       :source-type :user-assertion
                       :t-valid #inst "2026-03-01"})
    :fact
    (select-keys [:object-lit :t-valid :t-invalid]))

;; ## Time travel
;;
;; `:as-of` filters facts to those whose validity interval contains the
;; timestamp. In February the answer is Heroku; today it is Fly; before the
;; project existed it is nothing at all. One store, three answers, all
;; correct:

(defn deployed-at [t]
  (->> (core/get-facts store {:entity "shoply" :as-of t})
       :facts
       (mapv :object-lit)))

{:february   (deployed-at #inst "2026-02-01")
 :today      (deployed-at (core/now))
 :before-it-all (deployed-at #inst "2025-06-01")}

;; Intervals meet exactly. Heroku's interval closes at the instant Fly's
;; opens, so an as-of query between two versions returns exactly one fact,
;; never zero and never both.
;;
;; ## Supersession closes intervals; it does not erase
;;
;; `has-version` is single-valued, so a new version supersedes the old one.
;; Watch the predecessor's interval close at the successor's `t-valid`:

(core/assert-fact store
                  {:subject "AuthService" :predicate :core/has-version
                   :object "1.0.0" :object-kind :literal :source-type :code})

(core/assert-fact store
                  {:subject "AuthService" :predicate :core/has-version
                   :object "2.0.0" :object-kind :literal :source-type :code})

(->> (core/get-history store {:subject "AuthService"
                              :predicate :core/has-version})
     :history
     (mapv (fn [f] {:version (:object-lit f)
                    :valid-from (:t-valid f)
                    :valid-until (:t-invalid f)
                    :reason (:invalidation-reason f)})))

;; ## The backdated-overlap guard
;;
;; Supersession assumes a handoff: the new truth begins where the old one
;; ends. A successor backdated to start *before* its standing predecessor is
;; not a handoff, it is a claim that the past was different, and silently
;; inverting the predecessor's interval would corrupt history. So it flags
;; instead:

(-> (core/assert-fact store
                      {:subject "AuthService" :predicate :core/has-version
                       :object "0.9.0" :object-kind :literal :source-type :code
                       :t-valid #inst "2025-01-01"})
    (select-keys [:status :reason]))

;; ## Invalidation records when it stopped being true
;;
;; `invalidate` closes an interval by hand. Its `:at` is the valid-time end,
;; when the fact stopped holding in the world, which is usually earlier than
;; the moment someone noticed. The record time of the invalidation is kept
;; separately, so "we learned on Thursday that it broke on Monday" fits
;; without distortion:

(def dep
  (:fact (core/assert-fact store
                           {:subject "AuthService" :predicate :core/depends-on
                            :object "legacy-cache" :source-type :code
                            :t-valid #inst "2026-01-01"})))

(core/invalidate store {:fact-id (:id dep)
                        :reason "removed in the cache consolidation"
                        :at #inst "2026-06-15"})

;; Current reads no longer see the dependency:

(->> (core/get-facts store {:entity "AuthService" :predicate :core/depends-on})
     :facts
     (mapv (comp :name :object-ref)))

;; But nothing is gone. `:include-invalidated` shows the whole record,
;; closed intervals and reasons included:

(->> (core/get-facts store {:entity "AuthService"
                            :predicate :core/depends-on
                            :include-invalidated true})
     :facts
     (mapv (fn [f] {:object (get-in f [:object-ref :name])
                    :valid-until (:t-invalid f)
                    :reason (:invalidation-reason f)})))

;; ```bash
;; bin/claim assert --subject shoply --predicate deployed-via --object Heroku \
;;   --object-kind literal --valid-from 2026-01-01 --valid-until 2026-03-01
;; bin/claim facts --entity shoply --as-of 2026-02-01
;; bin/claim history --subject AuthService --predicate has-version
;; bin/claim invalidate --fact-id <id> --at 2026-06-15 --reason "..."
;; ```
;;
;; One scope note: claimgraph keeps one dimension of correction less than a
;; full bi-temporal store like [XTDB](https://xtdb.com/). It can say what was believed at any
;; time and when beliefs changed; it does not audit how beliefs *about the
;; past* were themselves revised. That trade was made deliberately, and the
;; handoff document records it.
