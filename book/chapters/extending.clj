;; # Extending: a new language, two ways
;;
;; The mechanical code tier ends the language-chasing game by contract, not
;; by coverage. Analyzers live behind a registry, and the only thing that
;; ever crosses the analyzer boundary is a tiny interchange format — one
;; JSON object per source unit. Everything hard sits on the driver's side
;; of that line and is identical for every language: fact derivation,
;; reconciliation against the previous pass, external scoping, the ambient
;; delta gate, degradation when tooling is missing. So "adding a language"
;; reduces to one job: emit the interchange format.
;;
;; There are two roads, and this chapter walks both. The **config seam**: a
;; `code-analyzers` entry in your repo pointing at any command that emits
;; the format — a ten-line script, no claimgraph change, committed with
;; your project so every writer of the repo gets it. And **upstream**: a
;; built-in adapter contributed to claimgraph itself, for languages worth
;; carrying for everyone.

(ns extending
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.core :as core]
            [claimgraph.ingest.code :as code]
            [claimgraph.store.memory :as mem]))

;; ## The contract
;;
;; One JSON object per source unit, as JSONL or a JSON array — the driver
;; accepts both:

(code/parse-interchange
 (str "{\"unit\":\"app.db\",\"file\":\"app/db.py\",\"requires\":[]}\n"
      "{\"unit\":\"app.api\",\"file\":\"app/api.py\","
      "\"requires\":[\"app.db\",\"external:requests\"]}"))

;; Four keys. `unit` is the stable name that becomes a graph entity; `file`
;; is the repo-relative path; `requires` lists the unit names this unit
;; depends on; `language` is optional (it defaults from the analyzer's
;; registry entry). Two rules carry all the weight:
;;
;; - **Units are file-grained**, one unit per file, because the
;;   `defined-in` predicate is cardinality-one. Emit `<package>.<stem>`
;;   for a JVM-ish language, path-sans-extension for a filesystem-ish one —
;;   whatever is stable — but never a package spanning files.
;; - **When resolution is uncertain, miss toward `external:`.** Anything
;;   you cannot confidently match to a local unit should either carry the
;;   `external:` prefix or simply not match — unprefixed requires resolve
;;   against the emitted unit set and anything unmatched is scoped external
;;   anyway. A heuristic miss therefore costs one external-scoped fact; it
;;   can never mint a wrong local edge. This is the property that makes a
;;   ten-line analyzer safe to trust.
;;
;; From those maps the driver derives every fact mechanically, at 0.95
;; confidence under source-type `:code`:

(code/units->facts
 [{:unit "app.api" :file "app/api.py" :language "python" :unit-type :module
   :requires ["app.db" "external:requests" "app.vanished"]}
  {:unit "app.db" :file "app/db.py" :language "python" :unit-type :module
   :requires []}]
 "code")

;; `defined-in`, `written-in`, `depends-on` — with `requests` external by
;; prefix and `app.vanished` external because nothing emitted it. Note the
;; entity types: config-added languages default to `:module`, and the type
;; guard in entity resolution keeps a `:module` from silently colliding
;; with a `:namespace` of the same name.
;;
;; ## The analyzer: any command, in any language
;;
;; The command runs from the project root and prints interchange to stdout.
;; Since claimgraph runs on babashka, `bb` is already on every user's PATH —
;; so a repo-local bb script is the zero-dependency choice. A complete
;; Python analyzer:
;;
;; ```clojure
;; #!/usr/bin/env bb
;; ;; .claimgraph/analyzers/python.bb — emit claimgraph interchange for **.py
;; (require '[babashka.fs :as fs] '[cheshire.core :as json]
;;          '[clojure.string :as str])
;; (let [root  (fs/canonicalize ".")
;;       files (->> (fs/glob root "**.py")
;;                  (remove #(str/includes? (str %) ".venv")))
;;       unit  #(-> (str (fs/relativize root %))
;;                  (str/replace #"\.py$" "") (str/replace "/" "."))]
;;   (doseq [f files]
;;     (println (json/generate-string
;;               {:unit (unit f)
;;                :file (str (fs/relativize root f))
;;                :requires (->> (str/split-lines (slurp (str f)))
;;                               (keep #(second (re-find
;;                                               #"^(?:from|import)\s+([\w.]+)" %)))
;;                               distinct)}))))
;; ```
;;
;; That is the whole thing. It does not classify imports as local or
;; external — the driver's resolution rule makes that unnecessary:
;; `app.db` matches an emitted unit and becomes a local edge, `requests`
;; matches nothing and lands external-scoped. The chapter will run this
;; exact logic below, as a function, through the same injectable command
;; seam the test suite uses — a book build never shells out, and neither
;; does the suite (the TypeScript adapter's tests run on canned
;; [dependency-cruiser](https://github.com/sverweij/dependency-cruiser) JSON the same way).

(defn python-analyzer-output
  "The script above, as a function of the project root -> its stdout."
  [root]
  (let [root (fs/canonicalize root)
        files (->> (fs/glob root "**.py")
                   (remove #(str/includes? (str %) ".venv"))
                   sort)
        unit #(-> (str (fs/relativize root %))
                  (str/replace #"\.py$" "") (str/replace "/" "."))]
    (str/join "\n"
              (for [f files]
                (json/generate-string
                 {:unit (unit f)
                  :file (str (fs/relativize root f))
                  :requires (->> (str/split-lines (slurp (str f)))
                                 (keep #(second (re-find
                                                 #"^(?:from|import)\s+([\w.]+)" %)))
                                 distinct)})))))

;; ## Wiring it in
;;
;; Analyzers are configured in `.claimgraph/config.json` — config-file
;; only, because structured values do not fit flags or environment
;; variables, and committable, so the analyzer travels with the repo:
;;
;; ```json
;; {"code-analyzers":
;;  {"python": {"detect": "**.py",
;;              "ignore": [".venv", ".git", ".claimgraph"],
;;              "command": "bb .claimgraph/analyzers/python.bb"}}}
;; ```
;;
;; The same map merges over the registry in code, which is how this
;; chapter exercises it. Everything unstated is defaulted: the language
;; name from the id, `:module` as the unit type, and — the important one —
;; `:parse` defaults to reading the interchange format directly:

(def analyzers
  {:python {:detect "**.py"
            :ignore [".venv" ".git" ".claimgraph"]
            :command "bb .claimgraph/analyzers/python.bb"}})

(-> (code/registry analyzers)
    (->> (filter #(= :python (:id %))))
    first
    (select-keys [:id :language :unit-type :detect :command]))

;; The same map can also tune what ships: override a built-in's `:command`
;; (which replaces its internal analyzer outright), or disable one —
;; `"typescript": false` — for a repo where detection would misfire.
;;
;; ## The pass, end to end
;;
;; A little Python project:

(def project (str (fs/create-temp-dir {:prefix "claimgraph-book-extending"})))

(fs/create-dirs (fs/path project "app"))
(spit (str (fs/path project "app" "db.py")) "import sqlite3\n")
(spit (str (fs/path project "app" "api.py")) "import app.db\nimport requests\n")

;; Detection walks the project root against each analyzer's `:detect` glob
;; (honoring its `:ignore` directories — never a hardcoded `src/`):

(mapv :id (code/detect project (code/registry analyzers)))

;; Run the pass. `:command-fn` stands in for the shell-out and `:which` for
;; the PATH lookup, exactly as in the test suite; on a real machine both
;; default to the real thing:

(def store (doto (mem/create) (core/seed!)))

(-> (code/ingest! store {:dir project
                         :analyzers analyzers
                         :which (fn [_] "bb")
                         :command-fn (fn [{:keys [dir]}]
                                       (python-analyzer-output dir))})
    (select-keys [:status :files :ref :invalidated :analyzers]))

;; And the graph now knows things no one typed:

(->> (core/get-facts store {:entity "app.api"})
     :facts
     (mapv (fn [f] {:predicate (:predicate f)
                    :object (or (some-> (:object-ref f) :name) (:object-lit f))
                    :scope (:scope f)})))

;; `app.db` resolved local; `requests` and `sqlite3` landed external, with
;; zero resolution logic in the analyzer.
;;
;; ## What you inherit for free
;;
;; Everything the built-in adapters get, a config-added analyzer gets too,
;; because it all lives driver-side. Reconciliation — delete a file and the
;; next pass invalidates its facts, non-lossily:

(fs/delete (fs/path project "app" "db.py"))
(spit (str (fs/path project "app" "api.py")) "import requests\n")

(-> (code/ingest! store {:dir project
                         :analyzers analyzers
                         :which (fn [_] "bb")
                         :command-fn (fn [{:keys [dir]}]
                                       (python-analyzer-output dir))})
    (select-keys [:status :files :invalidated :counts]))

;; Degradation — a machine without the tool skips the analyzer with a hint
;; instead of erroring, and (because reconciliation is language-guarded)
;; never invalidates the facts it could not re-derive:

(-> (code/ingest! store {:dir project
                         :analyzers analyzers
                         :which (fn [_] nil)})
    (select-keys [:status :analyzers]))

;; And the ambient loop — `hooks run` already runs `ingest-code-if-changed`
;; as its first stage, delta-gated on `<git-sha>+<dirty-digest>`, so the
;; moment the config entry lands in the repo, every writer's session end
;; keeps the new language's facts fresh. Nothing else to wire.
;;
;; ## Merging it upstream
;;
;; A config-seam analyzer serves one repo. When a language is worth
;; carrying for everyone, the same work graduates into a built-in adapter —
;; a contribution to claimgraph itself. What that takes, beyond the script:
;;
;; **Pick the analysis strategy deliberately.** The registry's standing
;; rule (recorded in `docs/language-adapters.md`): shell out to the
;; language's own tooling when the import surface is a moving target —
;; that is why TypeScript rides dependency-cruiser rather than a regex
;; collection, since every parser miss is a silent wrong fact. An internal
;; line parse is acceptable only when the grammar is rigid *and* no
;; maintained import-graph tool exists in that ecosystem — the Kotlin
;; position. Python, for instance, would want its own tooling for real
;; resolution (packages, `__init__.py`, namespace packages), which the
;; ten-line script above deliberately sidesteps by missing toward external.
;;
;; **The shape of the contribution** mirrors the existing adapters:
;;
;; - a registry entry in `claimgraph.ingest.code/builtin` — `:detect` glob,
;;   `:ignore` set, `:language`, `:unit-type`, `:cost`, and either
;;   `:analyze-fn` (internal) or `:command` + `:parse` + `:prereq`
;;   (external);
;; - for external tools, a per-language namespace modeled on
;;   `claimgraph.ingest.ts-code`: the command version-pinned (majors change
;;   output schemas), and every schema expectation isolated in `:parse` —
;;   one pinned seam per upstream, so drift breaks one function;
;; - for internal parses, a namespace modeled on
;;   `claimgraph.ingest.kotlin-code`: a pure `analyze-source`, resolution
;;   heuristics that miss toward `external:`, and `analyze-root` wiring;
;; - tests in the style of `claimgraph.code-adapters-test`: inline-source
;;   tests for a line parse, canned tool-output fixtures behind
;;   `:command-fn` for a shell-out — the suite must pass with none of the
;;   language's tooling installed — plus a missing-prereq test proving the
;;   adapter skips with a hint;
;; - one manual end-to-end against a real repo in that language before
;;   shipping (the TypeScript adapter's pin gained `-p typescript@5`
;;   precisely because the real run caught what the fixtures could not);
;; - the call sites: `ingest-code` help text, the README's ingestion tiers
;;   and prerequisites notes, the CLI reference in this book, and the spec
;;   doc's adapter list.
;;
;; The acceptance bar is the one every adapter meets: file-grained units,
;; misses land external, missing tooling degrades to a skip, and the
;; existing suite stays green untouched. Then it is an ordinary pull
;; request to [the repository](https://github.com/kirahowe/claimgraph) —
;; and until it merges, the config seam means you were never blocked on it.
