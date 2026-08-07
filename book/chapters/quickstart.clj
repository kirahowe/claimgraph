;; # Quick start
;;
;; This chapter gets a graph running and shows the write and read verbs you
;; will use most. It is a real namespace: every form below executes against
;; the actual claimgraph source when the book builds.
;;
;; ## Installing the tool
;;
;; The CLI runs on two fast-start native binaries, no JVM:
;;
;; ```bash
;; scripts/setup.sh     # installs babashka (bb) and the Datalevin pod (dtlv)
;;                      # — via Homebrew when available, else pinned downloads
;; bin/claim init    # creates ./.claimgraph/db, seeds the 23-predicate vocabulary
;; ```
;;
;; Every command accepts `--db PATH` (default `$CLAIMGRAPH_DB` or
;; `./.claimgraph/db`) and emits JSON on stdout; add `--pretty` for humans.
;;
;; ## Before installing anything: audit what you already have
;;
;; One verb runs without the store, the pod, or any commitment: `claim
;; audit` points the conflict machinery at your existing agent-memory pile
;; — the auto-memory notes a coding agent writes to on its own — together
;; with your `CLAUDE.md`/`AGENTS.md`/rules files, and scores both for
;; internal consistency, including where the pile contradicts a standing
;; instruction. It needs only `bb` and an extractor command, everything
;; happens in a throwaway in-memory store, and nothing is written:
;;
;; ```bash
;; bin/claim audit --pretty
;;   87 claims extracted from 4 files
;;    7 contradictions  (opposed claims coexisting in the pile)
;;    2 instruction conflicts (agent memory at odds with your instruction files)
;;   12 disagreements   (same subject, different values — the last one read silently wins)
;;    9 stale           (contradicted by what the code says today)
;;   23 restatements    (the same fact maintained in more than one place)
;;    3 name clusters   (AuthSvc / auth-service / AuthService)
;;   41 KB injected per session against a ~25 KB window  ** over budget **
;;      (of which 8 KB is claimgraph's compiled view)
;;      15 KB of on-demand notes scanned, not injected
;; ```
;;
;; Every number carries verbatim quote receipts, and the findings are
;; precisely the diseases the rest of this book cures. The audit chapter
;; runs the whole pipeline executably; the rest of this chapter builds the
;; graph that replaces the pile.
;;
;; ## A store, in code
;;
;; The CLI's Datalevin backend and the in-memory backend used here implement
;; the same storage protocol and share every line of decision logic, so what
;; you see below is what the CLI does. `seed!` installs the predicate
;; vocabulary, exactly like `claim init`.

(ns quickstart
  (:require [claimgraph.core :as core]
            [claimgraph.store.memory :as mem]))

(def store
  (doto (mem/create) (core/seed!)))

;; ## Writing facts
;;
;; A preference. The epistemic class defaults from the predicate
;; (`prefers` defaults to `:preference`), and the source type defaults to
;; `:user-assertion`:

(core/assert-fact store
                  {:subject "AuthService"
                   :predicate :core/prefers
                   :object "Result types over exceptions"
                   :object-kind :literal})

;; The CLI spelling of the same write:
;;
;; ```bash
;; bin/claim assert --subject AuthService --predicate prefers \
;;   --object "Result types over exceptions" --class preference
;; ```
;;
;; A commitment, the class that can never be silently overwritten. Note the
;; source type: decision records sit at the top of the trust ranking:

(core/assert-fact store
                  {:subject "api-layer"
                   :predicate :core/decided-against
                   :object "GraphQL"
                   :object-kind :literal
                   :epistemic :commitment
                   :source-type :decision-record})

;; And a couple of observations of the kind the mechanical code ingester
;; produces (at 0.95 confidence, under a `:code` episode, with no LLM
;; involved):

(core/assert-fact store
                  {:subject "AuthService"
                   :predicate :core/depends-on
                   :object "TokenStore"
                   :source-type :code
                   :confidence 0.95})

(core/assert-fact store
                  {:subject "AuthService"
                   :predicate :core/written-in
                   :object "Clojure"
                   :source-type :code
                   :confidence 0.95})

;; ## Reading
;;
;; Everything known about an entity. Each fact comes back with its stored
;; base confidence and its `:effective-confidence` after disuse decay
;; (identical here, since everything was just written). A small helper keeps
;; the output readable:

(defn brief [f]
  {:subject   (get-in f [:subject :name])
   :predicate (:predicate f)
   :object    (or (some-> (:object-ref f) :name) (:object-lit f))
   :class     (:epistemic f)
   :confidence (:effective-confidence f)})

(->> (core/get-facts store {:entity "AuthService"})
     :facts
     (mapv brief))

;; Entity lookups are forgiving: aliases and case or separator variants
;; resolve to the same node, so `auth-service` finds `AuthService`:

(->> (core/get-facts store {:entity "auth-service"})
     :facts
     (mapv brief))

;; Reverse lookups answer "what depends on this" by computing inverses at
;; query time (nothing is stored twice):

(->> (core/get-facts store {:entity "TokenStore" :direction :in})
     :facts
     (mapv brief))

;; Full-text search runs hybrid retrieval: FTS over literals and names,
;; entity resolution per query token, and a one-hop neighborhood, fused by
;; reciprocal rank weighted by effective confidence:

(->> (core/search store "GraphQL" {})
     :facts
     (mapv brief))

;; ```bash
;; bin/claim facts --entity AuthService --pretty
;; bin/claim facts --entity TokenStore --direction in
;; bin/claim search "GraphQL"
;; ```
;;
;; ## The part markdown cannot do
;;
;; Supersede a fact by asserting a new value for a single-valued predicate:

(core/assert-fact store
                  {:subject "AuthService"
                   :predicate :core/has-version
                   :object "1.0.0"
                   :object-kind :literal
                   :source-type :code})

(:status (core/assert-fact store
                           {:subject "AuthService"
                            :predicate :core/has-version
                            :object "2.0.0"
                            :object-kind :literal
                            :source-type :code}))

;; Both versions are still in the store. History shows the full biography of
;; (subject, predicate), including the closed interval:

(->> (core/get-history store {:subject "AuthService"
                              :predicate :core/has-version})
     :history
     (mapv (fn [f] {:object (:object-lit f)
                    :t-invalid (:t-invalid f)
                    :invalidated (:invalidation-reason f)})))

;; ```bash
;; bin/claim history --subject AuthService --predicate has-version
;; bin/claim facts --entity AuthService --as-of 2026-03-01   # time travel
;; ```
;;
;; The next chapters take each of these behaviors apart: the two clocks and
;; time travel, the conflict machinery and trust model, retrieval, the
;; ambient loop, and multi-machine reconciliation.
