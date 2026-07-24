;; # Multiple writers, multiple machines
;;
;; A laptop and a desktop both use the graph. Coding agents spawn subagents.
;; The multi-writer design answers both without a server, borrowing its
;; shape from the local-first literature: every mutation appends one effect
;; line to the current writer's own append-only log
;; (`<db>.oplog/<writer>.jsonl`), stamped with a hybrid logical clock, and
;; the live store is a materialized view over the logs. Because each machine
;; appends only to its own file, any file syncer (git, rsync, Syncthing)
;; moves logs between machines and a merge conflict cannot occur in
;; transport.
;;
;; What this deliberately is not: a [CRDT](https://hal.inria.fr/inria-00609399). A CRDT's contract is convergence
;; by construction, which means disagreement gets merged away. Two machines
;; disagreeing about a fact is exactly the situation a memory system should
;; surface to a human, and claimgraph already has a representation for that:
;; an open conflict. Convergence here means both machines converge on the
;; same facts, the same identities, and the same visible disagreements.

(ns multiwriter
  (:require [babashka.fs :as fs]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.oplog :as oplog]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

;; ## Two machines
;;
;; A "machine" is a store, a db path, and a writer identity. The CLI wraps
;; every store it opens in the logging decorator exactly like this
;; (writer identity comes from `$CLAIMGRAPH_WRITER`, or a generated id kept in
;; the oplog directory):

(defn machine [writer-name]
  (let [db (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-book"}) "db"))]
    (fs/create-dirs (oplog/oplog-dir db))
    (spit (str (fs/path (oplog/oplog-dir db) "writer")) writer-name)
    {:db db
     :store (oplog/logged-store (doto (mem/create) (core/seed!)) db)}))

(def laptop (machine "laptop"))
(def desktop (machine "desktop"))

;; The laptop records a decision and a preference, under the display name
;; its user types:

(core/assert-fact (:store laptop)
                  {:subject "api-layer" :predicate :core/decided-against
                   :object "GraphQL" :object-kind :literal
                   :epistemic :commitment :source-type :decision-record})

(core/assert-fact (:store laptop)
                  {:subject "AuthService" :predicate :core/prefers
                   :object "argon2" :object-kind :literal})

;; The desktop, offline and knowing none of that, records the same
;; preference under a sloppier name, a stance that contradicts the laptop's
;; decision, and something only it knows:

(core/assert-fact (:store desktop)
                  {:subject "auth-service" :predicate :core/prefers
                   :object "argon2" :object-kind :literal})

(core/assert-fact (:store desktop)
                  {:subject "api-layer" :predicate :core/prefers
                   :object "GraphQL" :object-kind :literal})

(core/assert-fact (:store desktop)
                  {:subject "billing" :predicate :core/depends-on
                   :object "stripe"})

;; Each write appended an effect line to its own log. The log is plain
;; JSONL; here are the effect types the desktop produced:

(->> (fs/glob (oplog/oplog-dir (:db desktop)) "*.jsonl")
     first str slurp
     clojure.string/split-lines
     (map #(second (re-find #"\"t\":\"([^\"]+)\"" %)))
     frequencies)

;; ## Sync is a file copy
;;
;; Whatever moves the log file works. This is the entire transport layer:

(defn sync-log! [from to]
  (doseq [f (fs/glob (oplog/oplog-dir (:db from)) "*.jsonl")]
    (fs/copy f (fs/path (oplog/oplog-dir (:db to)) (fs/file-name f))
             {:replace-existing true})))

(sync-log! desktop laptop)

;; ## Reconcile
;;
;; `reconcile!` reads foreign logs past its high-water marks and applies the
;; unseen effects in canonical (clock, writer, sequence) order. Entity
;; identity crosses machines by name: the desktop's `auth-service` resolves
;; to the laptop's `AuthService`, so the duplicate argon2 claim collapses
;; (closed, not erased) instead of doubling. And the desktop's GraphQL
;; preference lands against the laptop's commitment as an open conflict,
;; queued for the judge, because neither machine could see the contradiction
;; alone. Reconciliation always operates on the raw store beneath the
;; logging decorator, so replayed foreign effects are never re-logged as if
;; this writer made them:

(oplog/reconcile! (oplog/inner-store (:store laptop)) (:db laptop))

;; The laptop now knows what the desktop knew:

(->> (core/get-facts (:store laptop) {:entity "billing"})
     :facts
     (mapv (fn [f] [(get-in f [:subject :name]) (:predicate f)
                    (get-in f [:object-ref :name])])))

;; And the duplicate preference is one live fact plus one closed one:

(->> (core/get-facts (:store laptop) {:entity "AuthService"
                                      :include-invalidated true})
     :facts
     (filterv #(= "argon2" (:object-lit %)))
     (mapv (fn [f] {:live (nil? (:t-invalid f))})))

;; ## Convergence, with disagreement intact
;;
;; Sync the other way and both machines hold the same graph. Comparison runs
;; under normalized names because display names are local property: each
;; machine keeps the name it saw first and receives the other's as an alias.

(sync-log! laptop desktop)
(oplog/reconcile! (oplog/inner-store (:store desktop)) (:db desktop))

(defn triples [machine]
  (set (map (fn [f] [(logic/normalize-entity-name (get-in f [:subject :name]))
                     (:predicate f)
                     (or (some-> (get-in f [:object-ref :name])
                                 logic/normalize-entity-name)
                         (:object-lit f))
                     (some? (:t-invalid f))])
            (store/-all-facts (oplog/inner-store (:store machine))))))

(= (triples laptop) (triples desktop))

;; The desktop still calls the entity by its own name, with the laptop's
;; name attached as an alias:

(-> (core/resolve-entity (:store desktop) {:name "AuthService"})
    :entity
    (select-keys [:name :aliases]))

;; The GraphQL disagreement deserves a closer look. Neither writer could see
;; it (each machine held only its own half), and reconciliation does not
;; resolve it or even rule on it: `:sweep-candidates 1` in the reports above
;; is the contradiction being queued for the judge. The next
;; `judge --sweep` (or `consolidate`) classifies it, and a genuine
;; contradiction lands in the open-conflict queue for the human, on both
;; machines. Divergence surfaces; it is never merged away.
;;
;; Running reconcile again applies nothing: the high-water marks make it
;; idempotent, so a cron or a git hook can fire it blindly.

(select-keys (oplog/reconcile! (oplog/inner-store (:store desktop))
                               (:db desktop))
             [:effects :duplicates-collapsed])

;; ```bash
;; # machine A                        # machine B
;; export CLAIMGRAPH_WRITER=laptop      export CLAIMGRAPH_WRITER=desktop
;; bin/claim assert ...            bin/claim assert ...
;; # sync <db>.oplog/ however you like (git, rsync, Syncthing), then:
;; bin/claim reconcile
;; ```
;;
;; On a single machine, concurrent writers serialize through a lease
;; (`<db>.lock`: atomic, token-guarded, 30-second TTL so a crashed writer
;; expires instead of wedging the store). Reads never take it. The lease
;; handles same-machine concurrency; the logs handle everything else.
