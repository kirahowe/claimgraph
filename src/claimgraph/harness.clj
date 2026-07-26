(ns claimgraph.harness
  "Per-harness expectations for the ambient loop (docs/consuming-auto-memory.md),
  pinned in one place: where each coding harness keeps its auto-memory notes,
  which file it auto-injects, and the markers delimiting the managed section
  claimgraph compiles into that file.

  No location is assumed: every default here is computed from an injectable
  context (home dir + env), honors the harness's own relocation variables
  (Claude Code's $CLAUDE_CONFIG_DIR, Codex's $CODEX_HOME), and is overridable
  outright — --notes-dir / $CLAIMGRAPH_NOTES_DIR / notes-dir in the project
  config for the notes directory, --inject-file / $CLAIMGRAPH_INJECT_FILE /
  inject-file for the injection target. `claim config` shows what resolves.

  The managed-section markers are the echo-loop guard's anchor: ingest-notes
  strips the section before hashing and extraction (our compiled view is never
  re-consumed), and compile-context rewrites only what sits between them."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.logic :as logic]))

;; FROZEN. These two strings already sit in users' inject files on every
;; machine claimgraph has ever compiled on, and stripping them is the whole
;; echo-loop guard: a block we no longer recognise is read back as if the
;; user had written it, inflating confidence in our own output with nothing
;; to catch it (no error, just slow drift). Never edit them in place — a new
;; generation of markers appends a version suffix (":v2").
;;
;; What makes that migration safe is strip-managed-section removing EVERY
;; managed block it can recognise, of any generation. Splice is deliberately
;; not version-aware: the first compile after a v2 rollout writes its own
;; begin marker, does not find the v1 block already in the file, and so
;; leaves two blocks behind. Both strip. Keep that property when touching
;; strip, or the rollout hands the old block back as the user's own words.
(def begin-marker "<!-- claimgraph:managed:begin -->")
(def end-marker "<!-- claimgraph:managed:end -->")

;; Loose on purpose. Interior whitespace is whatever last touched the file
;; left behind (an editor reflow, a hand-edit, a harness's own rewriter),
;; and a half-recognised marker is the one shape that would survive into the
;; graph while every other unrecognised shape degrades to strip-to-EOF. An
;; empty suffix ("...begin: -->") names no generation, so it reads as the
;; unversioned form rather than as a generation of its own.
(def ^:private marker-patterns
  {:begin #"<!--\s*claimgraph:managed:begin(?::([^\s>]*))?\s*-->"
   :end #"<!--\s*claimgraph:managed:end(?::([^\s>]*))?\s*-->"})

(defn- find-marker
  "First :begin/:end marker at or after from, any version:
  {:from :to :version} with :version nil for the unversioned form."
  [s kind from]
  (let [m (re-matcher (marker-patterns kind) s)]
    (when (.find m (int from))
      {:from (.start m) :to (.end m) :version (not-empty (.group m 1))})))

(defn- next-block
  "First managed block at or after from as {:from :to}, or nil when no begin
  marker remains. :to is EOF for a begin we cannot pair — an undelimited
  block is assumed to be ours all the way down."
  [s from]
  (when-let [begin (find-marker s :begin from)]
    (let [end (loop [at (:to begin)]
                (when-let [e (find-marker s :end at)]
                  (if (= (:version e) (:version begin)) e (recur (:to e)))))]
      {:from (:from begin) :to (if end (:to end) (count s))})))

(defn strip-managed-section
  "Remove every marker-delimited managed section (markers included). Content
  without markers passes through untouched.

  Version-tolerant by design: a begin marker of any generation (the current
  one or a future ':v2' form) opens a section, and only an end marker of the
  SAME generation closes it. Anything else — no end marker, or one from a
  different generation — strips to EOF, because the only safe reading of a
  block we cannot delimit is that all of it is ours; re-consuming our own
  view is the failure we cannot detect.

  Every block goes, not just the first: a marker-version rollout leaves an
  older generation's block in the file (splice writes only its own), and one
  surviving block is one block re-ingested as if the user had written it."
  [content]
  (let [s (str content)]
    (loop [kept [] from 0]
      (if-let [b (next-block s from)]
        (recur (conj kept (subs s from (:from b))) (:to b))
        (str/join (conj kept (subs s from)))))))

(defn managed-section
  "The first managed block in content, markers included — the first of what
  strip-managed-section takes out — or nil when there is none. Callers that
  carry the block across a rewrite read it here instead of scanning for
  markers themselves; one scanner means one place that knows marker shapes."
  [content]
  (let [s (str content)]
    (when-let [b (next-block s 0)]
      (subs s (:from b) (:to b)))))

(defn splice-managed-section
  "Replace the marker-delimited managed section of content with inner
  (markers re-added around it), or insert the block at the TOP when absent —
  the harness injects the head of the file, so the compiled view must sit
  inside that window. Splice-then-strip returns the original non-managed
  content unchanged."
  [content inner]
  (let [s (str content)
        block (str begin-marker "\n" inner "\n" end-marker)
        begin (str/index-of s begin-marker)]
    (if-not begin
      (if (str/blank? s) (str block "\n") (str block "\n\n" s))
      (let [end (str/index-of s end-marker begin)
            after (if end (subs s (+ end (count end-marker))) "")]
        (str (subs s 0 begin) block after)))))

(defn munge-project-path
  "Claude Code's project-directory munging: the absolute project path with
  every non-alphanumeric character replaced by '-'
  (/home/kira/my_app -> -home-kira-my-app)."
  [abs-path]
  (str/replace (str abs-path) #"[^A-Za-z0-9]" "-"))

(def harnesses
  "Registry of known auto-memory layouts. :notes-dir is
  (fn [{:keys [home env]} abs-project-dir]) -> the directory the harness
  writes notes into, computed purely from the passed context (env is a
  string->string map, so the harness's own relocation variables are honored
  without touching the real environment); :inject-file is the file the
  harness auto-injects at session start (compile-context's write target);
  :note-glob is what counts as a note there (unknown layouts degrade
  gracefully — anything matching is a plain note)."
  {:claude-code
   {:id :claude-code
    :label "Claude Code auto memory"
    :inject-file "MEMORY.md"
    :note-glob "**.md"
    :notes-dir (fn [{:keys [home env]} abs-project-dir]
                 (str (or (get env "CLAUDE_CONFIG_DIR") (str home "/.claude"))
                      "/projects/" (munge-project-path abs-project-dir) "/memory"))}

   ;; Codex memories are per-machine, not per-project (docs/consuming-auto-
   ;; memory.md §5): thread summaries + durable entries + evidence files,
   ;; consolidated into memory_summary.md — which is also its injection
   ;; slot. The layout is undocumented upstream, so the glob is generous
   ;; and unknown files are treated as plain notes.
   :codex
   {:id :codex
    :label "Codex memories"
    :inject-file "memory_summary.md"
    :note-glob "**.{md,txt}"
    :notes-dir (fn [{:keys [home env]} _abs-project-dir]
                 (str (or (get env "CODEX_HOME") (str home "/.codex"))
                      "/memories"))}})

(defn resolve-harness
  "Harness keyword/string -> registry entry, or a deterministic failure
  listing what is supported."
  [h]
  (let [k (logic/->kw (or h :claude-code))]
    (or (get harnesses k)
        (logic/fail (str "Unknown harness: " (name k))
                    {:type :unknown-harness
                     :harness (name k)
                     :supported (mapv name (keys harnesses))}))))

(defn env-ctx
  "The real machine's context for notes-dir resolution. The only impure seam
  in this namespace; tests pass their own ctx instead."
  []
  {:home (System/getProperty "user.home")
   :env (into {} (System/getenv))})

(defn notes-path
  "Resolve where the harness keeps its auto-memory notes — the one resolver
  every consumer (ingest-notes, compile-context, hooks run) goes through.
  An explicit :dir wins outright; otherwise the harness default, computed
  from :ctx (injectable; defaults to the real home + env, so
  CLAUDE_CONFIG_DIR / CODEX_HOME relocations are honored)."
  [h {:keys [dir project ctx]}]
  (str (or dir
           ((:notes-dir h) (or ctx (env-ctx))
                           (str (fs/canonicalize (or project ".")))))))

(defn inject-target
  "Resolve the file compile-context writes: an explicit override (absolute,
  or relative to the notes dir) beats the harness default."
  [h notes-dir inject-file]
  (let [f (or inject-file (:inject-file h))]
    (str (if (fs/absolute? f) (fs/path f) (fs/path notes-dir f)))))
