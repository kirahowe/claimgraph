;; # The audit: score the pile you already have
;;
;; Every chapter so far assumed you adopted claimgraph. This one runs before
;; that decision. Every agent-assisted repo accumulates a memory pile — the
;; auto-memory notes a coding agent writes to on its own — and nothing ever
;; checks it for internal consistency, or against the `CLAUDE.md`/`AGENTS.md`/
;; rules files you actually wrote; the harness documentation itself concedes
;; that contradictory memory produces arbitrary behavior. `claim audit`
;; points the conflict machinery of the previous chapters at both: it scores
;; the pile for self-contradiction and staleness, and flags where the pile
;; contradicts your standing instructions — the marquee `instruction-conflict`
;; finding — alongside silent disagreements, restatements, name drift, and
;; injection bloat.
;;
;; The constraints are absolute, because this is the top of the funnel:
;; everything runs inside a throwaway in-memory store, the real store is
;; never opened, nothing is written (except an optional `--out` file), and
;; the only prerequisites are `bb` and an extractor command — not `dtlv`.
;; Someone can run the audit with zero installation commitment beyond
;; babashka.
;;
;; As in the ambient chapter, the LLM halves (extraction and the judge) are
;; pluggable subprocesses and stay out of a book build on purpose: the
;; extractions and verdicts below are injected, exactly as the tests inject
;; them. On a real run, `claude -p` produces them.

(ns audit
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.audit :as claim-audit]
            [claimgraph.harness :as harness]))

;; ## A pile with problems
;;
;; A project directory standing in for a repo that has lived with agents for
;; a while. Its two memory files disagree with each other and with the code:

(def project (str (fs/create-temp-dir {:prefix "claimgraph-book-audit"})))

(str (fs/relativize project (fs/create-dirs (fs/path project "src" "fixture"))))
(spit (str (fs/path project "src" "fixture" "app.clj"))
      "(ns fixture.app (:require [fixture.util]))\n")
(spit (str (fs/path project "src" "fixture" "util.clj"))
      "(ns fixture.util)\n")

(spit (str (fs/path project "AGENTS.md"))
      (str "# Agent guide\n"
           "The api-layer prefers GraphQL.\n"
           "auth-service prefers argon2 hashing.\n"
           "Use Terraform for infra.\n"
           "claim-cli is at 1.0.\n"))

(spit (str (fs/path project "CLAUDE.md"))
      (str "# Project notes\n"
           "We decided against GraphQL for the api-layer.\n"
           "AuthService prefers argon2 hashing.\n"
           "We decided against terraform for app deploys.\n"
           "fixture.app lives in src/legacy/app.clj.\n"
           "claim-cli is at 2.0.\n"
           "dev server port 3021 in this worktree\n"))

;; ## The injected LLM halves
;;
;; The extractor returns one JSON claim per line, each carrying a verbatim
;; `quote` — the receipt that makes every scorecard number auditable. Note
;; what it does *not* return: the dev-server port line fails the durability
;; filter and is never extracted.

(def extractions
  {"AGENTS.md"
   (str/join "\n"
             ["{\"subject\":\"api-layer\",\"predicate\":\"prefers\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"The api-layer prefers GraphQL.\"}"
              "{\"subject\":\"auth-service\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"auth-service prefers argon2 hashing.\"}"
              "{\"subject\":\"deploy-tool\",\"predicate\":\"prefers\",\"object\":\"Terraform\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"Use Terraform for infra.\"}"
              "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"1.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 1.0.\"}"])
   "CLAUDE.md"
   (str/join "\n"
             ["{\"subject\":\"api-layer\",\"predicate\":\"decided_against\",\"object\":\"GraphQL\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against GraphQL for the api-layer.\"}"
              "{\"subject\":\"AuthService\",\"predicate\":\"prefers\",\"object\":\"argon2 hashing\",\"object_kind\":\"literal\",\"class\":\"preference\",\"quote\":\"AuthService prefers argon2 hashing.\"}"
              "{\"subject\":\"deploy-tool\",\"predicate\":\"decided_against\",\"object\":\"terraform\",\"object_kind\":\"literal\",\"class\":\"commitment\",\"quote\":\"We decided against terraform for app deploys.\"}"
              "{\"subject\":\"fixture.app\",\"predicate\":\"defined_in\",\"object\":\"src/legacy/app.clj\",\"object_kind\":\"entity\",\"quote\":\"fixture.app lives in src/legacy/app.clj.\"}"
              "{\"subject\":\"claim-cli\",\"predicate\":\"has_version\",\"object\":\"2.0\",\"object_kind\":\"literal\",\"quote\":\"claim-cli is at 2.0.\"}"])})

(defn extractor [prompt]
  (or (some (fn [[file response]]
              (when (str/includes? prompt (str "file=\"" file "\"")) response))
            extractions)
      ""))

;; The judge is the false-positive filter. The Terraform pair (prefers it
;; for infra, decided against it for app deploys) flags mechanically — the
;; stance exclusion group cannot know about scopes — but the judge can, and
;; a judged-compatible pair is removed from the contradiction count:

(defn judge [prompt]
  (if (str/includes? prompt "Terraform")
    "{\"relation\":\"compatible\",\"confidence\":0.95,\"rationale\":\"infra vs app deploys\"}"
    "{\"relation\":\"contradicts\",\"confidence\":0.9,\"rationale\":\"opposed stances on the same object\"}"))

;; ## Running it

(def report
  (claim-audit/audit! {:project project
                       :ctx {:home "/nonexistent" :env {}}
                       :extractor-fn extractor
                       :judge-fn judge}))

(:summary report)

;; One of each. The write path produced most of these on its own, because
;; the audit rides the same `assert-fact` machinery as every other write and
;; **the status vocabulary maps directly onto finding classes**: a `:flagged`
;; result is a contradiction (or staleness, when the rival is code-sourced),
;; a `:superseded` result is a silent disagreement, a `:reinforced` result is
;; a restatement.
;;
;; Two deliberate inversions of the ambient tier make the collisions
;; surface. First, the audit ingests the *code* before the pile and the
;; instruction files — the mechanical facts land at 0.95 under source-type
;; `:code`, so CLAUDE.md's claim that `fixture.app` lives in
;; `src/legacy/app.clj` collides with it. Instruction files mint at code's
;; trust rank; the audit pins code as un-supersedable ground truth, so the
;; collision flags and reads as staleness:

(first (get-in report [:findings :stale]))

;; Second, where the notes ingester demotes every reported decision to an
;; observation (a note cannot mint a commitment), the audit keeps the
;; epistemic class — there is no durable graph to protect, and "we decided
;; against GraphQL" must arrive as the commitment whose stance collision
;; *flags* instead of silently superseding:

(first (get-in report [:findings :contradictions]))

;; Every claim carries its file and verbatim quote. The receipts live in an
;; audit-side map keyed by fact id, never in the store — the scorecard is
;; trustworthy because every number traces to a line someone wrote.
;;
;; The disagreement is the quietest finding and the most common disease: two
;; files state different values for the same single-valued thing, and in
;; markdown whichever the model reads last silently wins. The audit reports
;; the pair and never a winner (ingestion order decides which one
;; mechanically superseded, which is meaningless for truth):

(first (get-in report [:findings :disagreements]))

;; Restatement and name drift round out the scorecard. The same argon2 fact
;; maintained in both files reinforced instead of duplicating — and one file
;; called the service `auth-service` while the other said `AuthService`,
;; which entity resolution healed into an alias; the alias trail is the
;; cluster:

(first (get-in report [:findings :restatements]))
(get-in report [:findings :name-clusters])

;; ## The human rendering
;;
;; At a terminal, `claim audit` prints this scorecard by default, with the
;; per-finding receipts (`--scorecard` forces it anywhere; piped or
;; captured output is JSON):

(println (claim-audit/render-pretty report))

;; ## The echo guard, again
;;
;; If claimgraph is already installed, the pile contains our own compiled
;; view, and an audit that consumed it would be grading its own homework.
;; The same managed-section strip from the ambient chapter runs first; a
;; pile that is *only* our compiled view audits to zero claims:

(def echo-project (str (fs/create-temp-dir {:prefix "claimgraph-book-echo"})))

(spit (str (fs/path echo-project "CLAUDE.md"))
      (str harness/begin-marker
           "\ncompiled view: api-layer decided-against GraphQL\n"
           harness/end-marker))

(-> (claim-audit/audit! {:project echo-project
                         :ctx {:home "/nonexistent" :env {}}
                         :extractor-fn extractor
                         :judge-fn judge})
    (select-keys [:claims :files]))

;; ## At the shell
;;
;; ```bash
;; bin/claim audit                       # the scorecard above, for your repo
;; bin/claim audit --out report.json     # keep the receipts
;; bin/claim audit --no-judge            # raw mechanical flags, no LLM verdicts
;; bin/claim audit --no-code             # skip the staleness-vs-code prong
;; bin/claim audit --file NOTES.md --scan-dir docs/agent-notes   # widen the pile
;; ```
;;
;; Exit code is 0 even with findings — it is a report, not a gate. The
;; staleness prong covers every language the analyzer registry detects
;; (Clojure, Kotlin, TypeScript/JavaScript, plus `code-analyzers` config
;; additions) and skips honestly when nothing is; every other
;; finding class works on any repo. And the scorecard's findings are
;; precisely the diseases the rest of this book cures: post-adoption,
;; staleness goes to about zero by construction (code reconciliation
;; invalidates what the code stopped saying), contradictions become tracked
;; open conflicts instead of silent coexistence, restatement becomes
;; reinforcement, and name drift becomes aliases. That is why the scorecard
;; ends with `next: claim setup`.
