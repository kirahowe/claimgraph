(ns render
  "Build the claimgraph book: Clay evaluates the chapter namespaces against the
  real source under src/, writes a Quarto book project, and Quarto renders it
  to HTML. Prose chapters are plain markdown; code chapters are Clojure
  namespaces whose forms actually run at build time, so every example in the
  book is checked on every build.

  Run through the bb tasks:
    bb book            render to book/rendered/_book
    bb book:preview    render, then serve with quarto preview

  Needs a JVM and the quarto CLI on PATH (https://quarto.org)."
  (:require [scicloj.clay.v2.api :as clay]))

(def chapters
  ;; index.qmd becomes the book's index page (see build!); the rest render in
  ;; this order, grouped into the three parts that the index page's "How to
  ;; read this book" section describes. Each {:part <title> :chapters [...]}
  ;; entry is turned by Clay into a Quarto book part divider; the part titles
  ;; here must stay in sync with the labels on the index page. Prose chapters
  ;; are synced in as markdown; .clj chapters are evaluated by Clay.
  [{:part "Part I — Foundations"
    :chapters ["background.md"
               "mental_model.md"]}
   {:part "Part II — The System in Practice"
    :chapters ["quickstart.clj"
               "temporal.clj"
               "conflicts.clj"
               "retrieval.clj"
               "ambient.clj"
               "audit.clj"
               "multiwriter.clj"
               "internals.clj"]}
   {:part "Part III — Operations and Reference"
    :chapters ["advanced.md"
               "extending.clj"
               "benchmark.md"
               "comparison.md"
               "cli_reference.md"
               "references.md"]}])

(def repo-url "https://github.com/kirahowe/claimgraph")
(def repo-branch "main")

(def config
  {:format [:quarto :book]
   :base-source-path "book/chapters"
   :source-path chapters
   :base-target-path "book/rendered"
   ;; markdown chapters are not evaluated, only referenced; syncing the
   ;; chapter directory into the target is how they reach the Quarto project.
   ;; keep-sync-root false drops the "book/chapters/" prefix so prose chapters
   ;; land flat at the book root, matching the rendered .clj chapters (else
   ;; they publish at /book/chapters/*.html while code chapters sit at /*.html).
   :subdirs-to-sync ["book/chapters"]
   :keep-sync-root false
   :clean-up-target-dir true
   :show false
   ;; Clay's own quarto invocation is skipped; build! runs quarto after
   ;; installing the real index page over Clay's generated stub
   :run-quarto false
   ;; Every page links to the file it was written in. Two settings are needed
   ;; because the two kinds of chapter reach the reader by different routes.
   ;;
   ;; Evaluated chapters are renamed on the way through: quickstart.clj is
   ;; rendered as quickstart.qmd, and Quarto builds its "View source" href from
   ;; the rendered name, so it would link a .qmd that is not in the repo. Clay
   ;; knows the original name, and :remote-repo turns the "source: ..." line it
   ;; already prints at the foot of those chapters into a link.
   :remote-repo {:git-url repo-url
                 :branch  repo-branch}
   :book {:title "claimgraph: Structured Memory for Coding Agents"
          :author "Kira Howe"
          ;; one line: this string reaches _quarto.yml (and the page's meta
          ;; description) verbatim, embedded newlines and indentation included
          :description "A bi-temporal, epistemically typed knowledge graph for coding-agent memory: the problem and the field, the design, the working system chapter by chapter, the benchmark, and the reference."
          ;; Prose chapters are copied rather than evaluated, so they get no
          ;; such line -- but they keep their names, which means Quarto's own
          ;; repo-actions resolve them correctly from repo-subdir alone. The
          ;; link lands under the table of contents, and in the footer on
          ;; narrow screens. index.qmd is named to match for this reason.
          :repo-url repo-url
          :repo-branch repo-branch
          :repo-subdir "book/chapters"
          :repo-actions [:source]}
   ;; Of this map, only :format reaches _quarto.yml; Clay writes the rest into
   ;; the front matter of each .qmd it generates, which is exactly the set of
   ;; pages that need repo-actions switched off.
   :quarto {:repo-actions false
            :format {:html {;; Ship both themes and let respect-user-color-scheme
                            ;; choose between them from the reader's
                            ;; prefers-color-scheme, defaulting to the first
                            ;; listed. The toggle in the navbar still wins, and
                            ;; that choice is kept in local storage.
                            :theme {:light "cosmo"
                                    :dark "darkly"}
                            :respect-user-color-scheme true
                            :toc true
                            :code-overflow "wrap"}}}})

(defn- generate! []
  (clay/make! config)
  ;; Clay writes a bare title stub as index.qmd; the preface is the real
  ;; front page of the book. The preface is kept as index.qmd rather than
  ;; index.md so that its name in the repo matches its name in the rendered
  ;; project, which is what makes its "View source" link resolve.
  (spit "book/rendered/index.qmd" (slurp "book/chapters/index.qmd")))

(defn- quarto! [& args]
  (let [exit (-> (ProcessBuilder. (into ["quarto"] args))
                 (.directory (java.io.File. "book/rendered"))
                 (.inheritIO)
                 (.start)
                 (.waitFor))]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println "quarto failed with exit" exit))
      (System/exit exit))))

(defn build!
  "Render the whole book once to book/rendered/_book. Exits the JVM when done
  (Clay leaves a server thread alive otherwise)."
  [_]
  (generate!)
  (quarto! "render")
  (println "Book rendered to book/rendered/_book/index.html")
  (System/exit 0))

(defn preview!
  "Render, then serve a browsable live preview. Re-run after editing
  chapters; quarto preview watches the generated files, not the Clojure
  sources."
  [_]
  (generate!)
  (quarto! "preview")
  (System/exit 0))
