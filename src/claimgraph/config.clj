(ns claimgraph.config
  "Where claimgraph finds things. No file location is assumed: everything the
  tool touches resolves through one precedence chain —

      CLI flag  >  environment variable  >  project config file  >  default

  The project config file is JSON at $CLAIMGRAPH_CONFIG or
  ./.claimgraph/config.json, keyed by the kebab-case names below. It is
  committable (unlike the live store next to it) and is what `claim setup`
  writes when non-default locations are chosen, so one person's choices hold
  for every writer of the repo. `claim config` prints every setting with its
  resolved value and the layer it came from.

  Resolution is pure (resolve-setting over passed opts/env/config maps); the
  only impure seams are reading the real environment and the config file.

  This namespace also owns the pure compatibility gate every stamped artifact
  shares (unsupported-format below). It lives here rather than in each
  artifact's namespace because the decision is one integer comparison with one
  policy, and three copies of a policy is how two of them drift."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.logic :as logic]
            [claimgraph.version :as version]))

(def settings
  "The registry of configurable settings: option key -> where a value may
  come from. :opt-key is the CLI option when it differs from the setting name
  (--dir means the notes dir on the commands that take it). A nil :default
  means the consumer computes one (documented in :desc)."
  (array-map
   :db {:flag "--db" :env "CLAIMGRAPH_DB" :default ".claimgraph/db"
        :desc "Store path (an LMDB directory; the format stamp, oplog, evidence, lock, and stamp files derive from it as siblings)."}
   :harness {:flag "--harness" :env "CLAIMGRAPH_HARNESS" :default "claude-code"
             :desc "Which harness's auto-memory the ambient loop consumes (claude-code | codex)."}
   :notes-dir {:flag "--dir" :opt-key :dir :env "CLAIMGRAPH_NOTES_DIR"
               :desc "The harness's auto-memory notes directory. Default: resolved per harness from its own layout, honoring CLAUDE_CONFIG_DIR / CODEX_HOME."}
   :inject-file {:flag "--inject-file" :env "CLAIMGRAPH_INJECT_FILE"
                 :desc "The file the harness injects at session start — compile-context's write target, relative to the notes dir (or absolute). Default per harness: MEMORY.md / memory_summary.md."}
   :settings-file {:flag "--settings-file" :env "CLAIMGRAPH_SETTINGS_FILE"
                   :desc "The hook-settings file `hooks install` writes. Default: <project>/.claude/settings.json."}
   :skills-dir {:flag "--skills-dir" :env "CLAIMGRAPH_SKILLS_DIR"
                :desc "Where `setup` installs the agent skill. Default: <project>/.claude/skills."}
   :extractor {:flag "--extractor" :env "CLAIMGRAPH_LLM_CMD" :default "claude -p"
               :desc "LLM command for extraction and judging: prompt on stdin, completion on stdout."}
   :evidence-dir {:flag "--evidence-dir" :env "CLAIMGRAPH_EVIDENCE_DIR"
                  :desc "Content-addressed raw-evidence store. Default: <db>.evidence."}
   :consolidate-days {:flag "--consolidate-days" :env "CLAIMGRAPH_CONSOLIDATE_DAYS"
                      :default 7 :coerce :long
                      :desc "Consolidation cadence for hooks run, in days (0 = every run)."}
   :code-ingest {:flag "--code-ingest" :env "CLAIMGRAPH_CODE_INGEST" :default "session-end"
                 :desc "Whether hooks run refreshes code facts as its first stage (session-end | manual). Delta-gated either way; manual opts a project with an expensive analyzer out of the ambient pass. The code-analyzers map (config-file only) tunes which analyzers run."}))

(def config-only-keys
  "Keys the config file legitimately carries that are NOT settings, listed so
  the unknown-key check has one authority to consult and a deliberate
  omission cannot be mistaken for an oversight.

  :code-analyzers is a map of analyzer id -> command, read straight from the
  file by ingest/code.clj. It stays out of the registry on purpose: a setting
  earns its place there by having a flag and an env var, and neither spelling
  makes sense for nested JSON on a command line, so `claim config` would print
  a row it could not teach you to set.

  :config-version is the file's own format stamp — the same integer every
  other persisted artifact carries (claimgraph.version/format-version), not a
  setting anyone resolves."
  #{:code-analyzers :config-version})

(defn unknown-keys
  "Pure. The config-file keys claimgraph recognises nothing about, sorted.

  A misspelled key (`notes_dir`, `extactor`) parses as valid JSON, resolves to
  nothing, and changes nothing — the exact silent-configuration failure this
  namespace refuses to tolerate elsewhere, wearing a disguise. Naming the keys
  is the only way a user finds out the setting never took effect; the file
  cannot warn about them retroactively once people have accumulated cruft in
  it, which is why this is cheap now and impossible later."
  [config]
  (->> (when (map? config) (keys config))
       (remove #(contains? settings %))
       (remove config-only-keys)
       (sort-by name)
       vec))

(defn unknown-key-warning
  "Pure. The one-line warning for unknown-keys, or nil when there is nothing
  to say."
  [path config]
  (when-let [ks (seq (unknown-keys config))]
    (str "claimgraph: " path ": ignoring " (count ks) " unrecognised key"
         (when (next ks) "s") " — " (str/join ", " (map name ks))
         ". `claim config` lists every setting claimgraph reads.")))

;; ---------------------------------------------------------------------------
;; Pure: the format gate every stamped artifact shares
;; ---------------------------------------------------------------------------

(defn format-number?
  "Pure. Is this what a claimgraph format stamp is — one non-negative integer?
  Zero is the floor, being \"written before stamping existed\"; below that is
  a number no artifact has ever carried.

  Deliberately narrow, because the values that are not a format number arrive
  looking like one. JSON has a single number type: a stamp written 2.0 parses
  to a Double, one written past 2^63 to a BigInteger, and a hand-edit that
  quotes it to a String. `int?` calls none of those three an integer, so a
  gate that asks `int?` before comparing treats all three as no stamp at all
  and reads the file anyway; a gate that compares first throws on the String.
  Whether a stamp is legible has to be decided before it is compared."
  [x]
  (and (integer? x) (not (neg? x))))

(defn unsupported-format
  "Pure. Can this build read an artifact stamped `found`? nil when it can,
  the error data to fail with when it cannot.

  An absent stamp is format 0 — everything written before stamping existed —
  and reads. So does any format BELOW ours, by construction: format-version
  moves only when an OLD reader would get a NEW artifact wrong, so a new
  reader still understands every older shape, and refusing a file it can read
  is a worse failure than the missing stamp was.

  Two shapes are refused, and the remedies differ, so the errors do too. A
  stamp ABOVE ours is :unsupported-format — the case worth stopping the world
  for, because the alternative is applying the only rules this build knows to
  bytes that stopped following them and calling the result a success. The
  error names both integers because \"upgrade claimgraph\" is the whole
  remedy and a version number is how you tell whether you have one. A stamp
  that is not a format number at all is :unreadable-format — upgrading fixes
  nothing, since no claimgraph ever wrote it; the file was hand-edited or was
  never claimgraph's, and the one thing this gate must not do is guess which
  shape its bytes follow."
  [artifact found]
  (cond
    (nil? found) nil

    (not (format-number? found))
    {:type :unreadable-format
     :artifact (str artifact)
     :found found
     :supported version/format-version
     :message (str artifact " declares format " (pr-str found)
                   ", which is not a claimgraph format stamp (that is one"
                   " non-negative integer, currently " version/format-version
                   "). Nothing this build can read wrote that, so it will not"
                   " guess what the rest of the file means.")}

    (> found version/format-version)
    {:type :unsupported-format
     :artifact (str artifact)
     :found found
     :supported version/format-version
     :message (str artifact " was written by a newer claimgraph: format "
                   found ", but this build (" version/release ") reads format "
                   version/format-version " and below. Upgrade claimgraph.")}))

(defn require-format
  "unsupported-format, thrown. Returns `found` so it composes into a read."
  [artifact found]
  (when-let [e (unsupported-format artifact found)]
    (logic/fail (:message e) (dissoc e :message)))
  found)

;; ---------------------------------------------------------------------------
;; Pure: resolution
;; ---------------------------------------------------------------------------

(defn- coerce-value [spec v]
  (if (and (= :long (:coerce spec)) (string? v))
    (parse-long v)
    v))

(defn resolve-setting
  "Pure. One setting against the three layers + default ->
  {:value v :source :flag|:env|:config|:default}, or {:value nil :source nil}
  when unset everywhere and there is no static default."
  [k {:keys [opts env config]}]
  (let [spec (get settings k)
        opt (get opts (or (:opt-key spec) k))
        env-v (some->> (:env spec) (get env))
        cfg-v (get config k)]
    (cond
      (some? opt) {:value opt :source :flag}
      (some? env-v) {:value (coerce-value spec env-v) :source :env}
      (some? cfg-v) {:value (coerce-value spec cfg-v) :source :config}
      (some? (:default spec)) {:value (:default spec) :source :default}
      :else {:value nil :source nil})))

(defn merge-defaults
  "Pure: fill absent CLI opts from the env/config layers for the given
  setting keys — flags stay authoritative, commands stay oblivious. Static
  defaults are NOT merged: those remain owned by each consumer, so a command
  only sees a value the user actually set somewhere."
  [opts ctx ks]
  (reduce (fn [o k]
            (let [ok (or (:opt-key (get settings k)) k)
                  {:keys [value source]} (resolve-setting k (assoc ctx :opts o))]
              (if (and (contains? #{:env :config} source) (nil? (get o ok)))
                (assoc o ok value)
                o)))
          opts ks))

;; ---------------------------------------------------------------------------
;; Shell: the real environment and config file
;; ---------------------------------------------------------------------------

(defn config-file-path
  "Where the project config file lives: $CLAIMGRAPH_CONFIG or
  ./.claimgraph/config.json (relative to cwd, like the default db path)."
  ([] (config-file-path (into {} (System/getenv))))
  ([env] (or (get env "CLAIMGRAPH_CONFIG") ".claimgraph/config.json")))

(defonce ^:private warned
  ;; Warned-about (path, keys) pairs. Every config/value call re-reads the
  ;; file, so a command that resolves four settings would otherwise print the
  ;; same warning four times, and a warning that repeats reads as a cascade of
  ;; failures rather than one typo.
  (atom #{}))

(defn read-config-file
  "Parsed config map (keyword keys) or nil.

  Three ways this refuses to ignore configuration silently, which is the rule
  the whole namespace is built on: malformed JSON throws, a file stamped with
  a format this build cannot read is refused by name, and keys claimgraph does
  not recognise warn on stderr (once per process) instead of resolving to
  nothing in a corner. The warning is stderr and not an error because cruft
  accumulates in committed files and a hard failure would lock people out of
  their own repo over a dead key."
  [path]
  (when (fs/exists? path)
    (let [config (json/parse-string (slurp (str path)) true)]
      (require-format path (:config-version config))
      (when-let [w (unknown-key-warning path config)]
        (when-not (contains? @warned w)
          (swap! warned conj w)
          (binding [*out* *err*] (println w))))
      config)))

(defn context
  "The two ambient layers, read once: {:env ... :config ... :config-file path}."
  []
  (let [env (into {} (System/getenv))
        path (config-file-path env)]
    {:env env :config (read-config-file path) :config-file (str path)}))

(defn with-defaults
  "Shell version of merge-defaults against the real env + config file."
  [opts ks]
  (merge-defaults opts (context) ks))

(defn value
  "Resolve one setting against the real environment. -> the value or nil."
  [k opts]
  (:value (resolve-setting k (assoc (context) :opts opts))))

(defn describe
  "The `claim config` payload: every setting with its resolved value, the
  layer it came from, and how to set it at each layer.

  :unknown-keys is here as well as on stderr because this is the command a
  user runs to find out why a setting isn't taking, and a key claimgraph never
  reads is the likeliest answer. :config-version is the file's own stamp, nil
  for a file written before stamping (which resolves exactly the same way — it
  gates readers, not values).

  The two-arity takes the ambient layers as data, the way resolve-setting and
  merge-defaults do, so what `claim config` reports about a given config file
  is checkable without one on disk."
  ([opts] (describe opts (context)))
  ([opts base-ctx]
   (let [ctx (assoc base-ctx :opts opts)]
     {:config-file {:path (:config-file ctx)
                    :exists (boolean (:config ctx))
                    :config-version (:config-version (:config ctx))
                    :unknown-keys (unknown-keys (:config ctx))}
      :precedence "flag > env > config-file > default"
      :settings
      (into (array-map)
            (for [[k spec] settings]
              [k (merge (resolve-setting k ctx)
                        {:flag (:flag spec)
                         :env (:env spec)
                         :config-key (name k)
                         :desc (:desc spec)})]))})))
