(ns claimgraph.config-test
  "The static surfaces: the configuration precedence chain (over passed
  opts/env/config maps — no real environment, no real config file), the wire
  encoding every artifact is written with, the version identity every
  artifact is stamped with, and the CLI surface itself — flag names, exit
  status, and the shape of a report. That last section is here because every
  one of those is a promise the moment somebody scripts against it, and the
  registry this namespace owns is where half of them are decided.

  The version section reaches past the pure surfaces, because a format stamp
  nobody writes to disk is not a format stamp. The final two sections open
  real stores — Datalevin gated on the pod exactly as claimgraph.core-test
  gates it, in-memory always — to check that a store stamps itself, refuses a
  stamp it cannot read or one from the future, and treats a re-registration
  identically in both backends: a curated row redefined, a staging row
  amended. Registry semantics live in two implementations, so every one of
  those runs against both."
  (:require [babashka.classpath :as cp]
            [babashka.cli :as bcli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.cli :as cli]
            [claimgraph.config :as config]
            [claimgraph.core :as core]
            [claimgraph.core-test :as core-test]
            [claimgraph.logic :as logic]
            [claimgraph.predicates :as preds]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(deftest precedence-chain
  (let [ctx {:env {"CLAIMGRAPH_DB" "/from-env/db"}
             :config {:db "/from-file/db"}}]
    (testing "flag beats env beats config beats default"
      (is (= {:value "/flag/db" :source :flag}
             (config/resolve-setting :db (assoc ctx :opts {:db "/flag/db"}))))
      (is (= {:value "/from-env/db" :source :env}
             (config/resolve-setting :db (assoc ctx :opts {}))))
      (is (= {:value "/from-file/db" :source :config}
             (config/resolve-setting :db {:opts {} :env {}
                                          :config {:db "/from-file/db"}})))
      (is (= {:value ".claimgraph/db" :source :default}
             (config/resolve-setting :db {:opts {} :env {} :config nil}))))))

(deftest unset-without-default-is-nil
  (is (= {:value nil :source nil}
         (config/resolve-setting :notes-dir {:opts {} :env {} :config nil}))))

(deftest numeric-coercion-from-env-and-config
  (is (= {:value 3 :source :env}
         (config/resolve-setting :budget
                                 {:opts {} :env {"CLAIMGRAPH_BUDGET" "3"}})))
  (is (= {:value 5 :source :config}
         (config/resolve-setting :budget
                                 {:opts {} :env {} :config {:budget 5}}))
      "a JSON number needs no coercion"))

(deftest a-settings-name-is-the-flag-that-sets-it
  (testing "one spelling per setting, at every layer"
    (is (= [] (remove (fn [[k spec]] (= (str "--" (name k)) (:flag spec)))
                      config/settings))
        "the registry used to let a setting be spelled differently on the command
         line — :notes-dir was --dir — and two names for one setting is how
         `setup --notes-dir` came to persist a key no runtime consumer read
         while `setup --dir` persisted nothing at all"))
  (is (= {:value "/n" :source :flag}
         (config/resolve-setting :notes-dir {:opts {:notes-dir "/n"} :env {} :config nil})))
  (is (= {:notes-dir "/cfg/notes"}
         (config/merge-defaults {} {:env {} :config {:notes-dir "/cfg/notes"}}
                                [:notes-dir]))
      "merge-defaults fills the setting's own key, which is the key commands read"))

(deftest merge-defaults-respects-flags-and-skips-static-defaults
  (let [ctx {:env {"CLAIMGRAPH_HARNESS" "codex"} :config {:harness "claude-code"}}]
    (is (= {:harness "codex"} (config/merge-defaults {} ctx [:harness]))
        "env layer fills an absent flag")
    (is (= {:harness "x"} (config/merge-defaults {:harness "x"} ctx [:harness]))
        "an explicit flag is never overwritten"))
  (is (= {} (config/merge-defaults {} {:env {} :config nil} [:harness :budget]))
      "static defaults stay owned by each consumer — merge fills nothing"))

(deftest config-file-path-override
  (is (= "/elsewhere/cfg.json"
         (config/config-file-path {"CLAIMGRAPH_CONFIG" "/elsewhere/cfg.json"})))
  (is (= ".claimgraph/config.json" (config/config-file-path {}))))

(deftest config-file-path-anchors-to-a-project-root
  (testing "no project: the exact bare default, unchanged — nothing churns
            for a caller that never passes one"
    (is (= ".claimgraph/config.json" (config/config-file-path {} nil))))
  (testing "a project resolves the default under it"
    (is (= (str (fs/path "/somewhere" ".claimgraph" "config.json"))
           (config/config-file-path {} "/somewhere"))))
  (testing "$CLAIMGRAPH_CONFIG wins outright, project or not — the caller's
            own env var is the caller's own anchoring, passed as data (the
            1-arity already takes env this way)"
    (is (= "/elsewhere/cfg.json"
           (config/config-file-path {"CLAIMGRAPH_CONFIG" "/elsewhere/cfg.json"}
                                    "/somewhere")))))

(deftest read-config-file-roundtrip
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-test"})
        path (str (fs/path dir "config.json"))]
    (is (nil? (config/read-config-file path)) "absent file reads as nil")
    (spit path "{\"harness\": \"codex\", \"budget\": 3}")
    (is (= {:harness "codex" :budget 3} (config/read-config-file path)))))

;; ---------------------------------------------------------------------------
;; Unrecognised config keys
;; ---------------------------------------------------------------------------

(deftest a-key-claimgraph-never-reads-is-named-not-ignored
  (is (= [] (config/unknown-keys {:db "/d" :notes-dir "/n" :budget 3}))
      "every registry key is recognised")
  (is (= [] (config/unknown-keys {:llm-timeout-ms 30000}))
      "including the LLM call timeout, which read its environment variable
       directly and so warned as cruft when set in the file it belongs in")
  (is (= [] (config/unknown-keys {:code-analyzers {:python "bb analyze.clj"}
                                  :config-version 1}))
      "the two config-file-only keys are known on purpose: code-analyzers has
       no flag to print, config-version is the file's own stamp")
  (is (= [:extactor :notes_dir]
         (config/unknown-keys {:notes_dir "/n" :db "/d" :extactor "claude -p"}))
      "the near-misses that used to resolve to nothing at all, sorted")
  (is (= [] (config/unknown-keys nil)))
  (is (nil? (config/unknown-key-warning "cfg.json" {:db "/d"})))
  (let [w (config/unknown-key-warning ".claimgraph/config.json" {:notes_dir "/n"})]
    (is (str/includes? w ".claimgraph/config.json") "names the file")
    (is (str/includes? w "notes_dir") "and the key, which is the whole point")
    (is (str/includes? w "claim config") "and where to look up the real spelling")))

(deftest an-unrecognised-key-warns-on-stderr-once
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-unknown-key-test"})
        path (str (fs/path dir "config.json"))
        err (java.io.StringWriter.)]
    (spit path "{\"harness\": \"codex\", \"notes_dir\": \"/n\"}")
    (binding [*err* err]
      (is (= {:harness "codex" :notes_dir "/n"} (config/read-config-file path))
          "the file still parses and still resolves what it can")
      (config/read-config-file path))
    (is (str/includes? (str err) "notes_dir"))
    (is (= 1 (count (re-seq #"notes_dir" (str err))))
        "once per process: config/value re-reads the file for every setting,
         and a warning repeated five times reads as five failures")))

;; ---------------------------------------------------------------------------
;; The format gate every stamped artifact shares
;; ---------------------------------------------------------------------------

(deftest the-format-gate-refuses-only-the-future
  (testing "readable: unstamped, older, and exactly ours"
    (is (nil? (config/unsupported-format "a.version" nil)))
    (is (nil? (config/unsupported-format "a.version" 0)))
    (is (nil? (config/unsupported-format "a.version" version/format-version))))
  (testing "refused: anything this build was never taught"
    (let [ahead (inc version/format-version)
          e (config/unsupported-format "/tmp/db.version" ahead)]
      (is (= :unsupported-format (:type e)))
      (is (= ahead (:found e)))
      (is (= version/format-version (:supported e)))
      (is (str/includes? (:message e) "/tmp/db.version") "names the artifact")
      (is (str/includes? (:message e) (str ahead)) "and both integers")
      (is (str/includes? (:message e) (str version/format-version)))))
  (testing "require-format throws that, and passes the version through"
    (is (nil? (config/require-format "a" nil)))
    (is (= version/format-version
           (config/require-format "a" version/format-version)))
    (let [e (try (config/require-format "a" (inc version/format-version))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :unsupported-format (:type (ex-data e))))
      (is (:claimgraph/error (ex-data e)) "surfaces through the CLI error path"))))

(deftest a-stamp-that-is-not-a-format-number-is-refused-as-unreadable
  (testing "JSON has one number type, so these are what a stamp arrives as"
    (doseq [found [2.0 "2" true -1 [1] {}]]
      (let [e (config/unsupported-format "cfg.json" found)]
        (is (= :unreadable-format (:type e)) (str "refused: " (pr-str found)))
        (is (= found (:found e)) "and reported verbatim, since it is the evidence")
        (is (str/includes? (:message e) "not a claimgraph format stamp")))))
  (testing "a bignum is an integer, and a stamp far above ours is the future"
    (let [e (config/unsupported-format "cfg.json" (biginteger 99999999999999999999N))]
      (is (= :unsupported-format (:type e))
          "not :unreadable-format: `int?` is false for a BigInteger, which is how
           a stamp past 2^63 used to pass the gate as if it were absent")))
  (testing "and the readable shapes stay readable"
    (is (nil? (config/unsupported-format "cfg.json" nil)))
    (is (nil? (config/unsupported-format "cfg.json" 0)))
    (is (nil? (config/unsupported-format "cfg.json" version/format-version)))))

(deftest a-config-file-whose-stamp-is-not-a-number-is-refused-by-name
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-badstamp-test"})
        path (str (fs/path dir "config.json"))
        refusal (fn [raw]
                  (spit path raw)
                  (try (config/read-config-file path)
                       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (is (= :unreadable-format (refusal "{\"config-version\": 2.0, \"db\": \"/d\"}"))
        "measured with the project's own reader: 2.0 parses to a Double")
    (is (= :unreadable-format (refusal "{\"config-version\": \"2\", \"db\": \"/d\"}"))
        "and a quoted stamp to a String, which no integer comparison refuses")
    (is (= :unsupported-format
           (refusal "{\"config-version\": 99999999999999999999, \"db\": \"/d\"}"))
        "past 2^63 it is a BigInteger, and a BigInteger above ours is the future")
    (is (= {:config-version version/format-version :db "/d"}
           (do (spit path (json/generate-string {:config-version version/format-version
                                                 :db "/d"}))
               (config/read-config-file path)))
        "the file claimgraph itself writes still reads")))

(deftest the-file-setup-writes-reads-back-stamped
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-stamp-test"})
        path (str (fs/path dir "config.json"))
        written {:harness "codex" :budget 3
                 :config-version version/format-version}]
    (spit path (json/generate-string written {:pretty true}))
    (is (= written (config/read-config-file path))
        "the shape setup/persist-config! writes: settings plus the stamp, and
         the gate reads it back instead of the format 0 that a config.json
         written without one stays forever")
    (is (= [] (config/unknown-keys written))
        "the stamp is a key claimgraph knows, not cruft it then warns about")))

(deftest a-config-file-from-the-future-is-refused-by-name
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-format-test"})
        path (str (fs/path dir "config.json"))]
    (spit path (json/generate-string {:config-version (inc version/format-version)
                                      :db "/somewhere"}))
    (let [e (try (config/read-config-file path)
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :unsupported-format (:type (ex-data e)))
          "a config whose keys may have changed meaning decides where the store
           lives — guessing writes the wrong graph"))
    (spit path (json/generate-string {:config-version version/format-version
                                      :db "/somewhere"}))
    (is (= "/somewhere" (:db (config/read-config-file path))))
    (spit path (json/generate-string {:db "/somewhere"}))
    (is (= "/somewhere" (:db (config/read-config-file path)))
        "unstamped is format 0 and still read: every file written before this
         change is unstamped")))

(deftest describe-surfaces-the-stamp-and-the-cruft
  (let [d (config/describe {} {:env {}
                               :config-file "/p/.claimgraph/config.json"
                               :config {:config-version version/format-version
                                        :harness "codex" :notes_dir "/n"}})]
    (is (= "/p/.claimgraph/config.json" (get-in d [:config-file :path])))
    (is (= version/format-version (get-in d [:config-file :config-version])))
    (is (= [:notes_dir] (get-in d [:config-file :unknown-keys]))
        "`claim config` is where you go to find out why a setting isn't taking;
         a key claimgraph never reads is the likeliest answer")
    (is (= {:value "codex" :source :config} (select-keys (get-in d [:settings :harness])
                                                         [:value :source]))
        "the recognised keys resolve exactly as before")))

(deftest describe-reports-every-setting-with-provenance
  (let [d (config/describe {:db "/flag/db"})]
    (is (= "flag > env > config-file > default" (:precedence d)))
    (is (= (set (keys config/settings)) (set (keys (:settings d)))))
    (is (= :flag (get-in d [:settings :db :source])))
    (is (every? (fn [[_ v]] (and (:flag v) (:env v) (:config-key v) (:desc v)))
                (:settings d))
        "each setting documents how to set it at every layer")))

;; ---------------------------------------------------------------------------
;; The wire encoding
;; ---------------------------------------------------------------------------

(def ^:private t-open (java.util.Date. 1784946480255))
(def ^:private t-close (java.util.Date. 1784946480366))

(deftest wire-dates-keep-their-milliseconds
  (is (= "2026-07-25T02:28:00.255Z"
         (:t (json/parse-string (wire/generate-string {:t t-open}) true))))
  (is (= t-open (logic/parse-instant (:t (wire/parse-string (wire/generate-string {:t t-open})))))
      "an instant survives generate -> parse -> rehydrate unchanged")
  (is (= {:a 1} (wire/parse-string "{\"a\":1}"))
      "parsing always yields keyword keys — the only shape claimgraph reads")
  (is (= "2026-07-25T02:28:00Z"
         (:t (json/parse-string (json/generate-string {:t t-open}) true)))
      "why this namespace exists: cheshire's default encoder truncates to the second"))

(deftest an-interval-inside-one-second-survives-the-round-trip
  (let [fact {logic/dump-discriminator "fact" :id "f-1" :predicate "core/has-version"
              :t-valid t-open :t-invalid t-close}
        restore (fn [generate] (second (logic/rehydrate-dump-record
                                        (wire/parse-string (generate fact)))))
        wired (restore wire/generate-string)
        truncated (restore json/generate-string)]
    (is (= [t-open t-close] ((juxt :t-valid :t-invalid) wired)))
    (is (logic/fact-valid-at? wired t-open)
        "a fact asserted and superseded inside one second is still valid while it was valid")
    (is (= (:t-valid truncated) (:t-invalid truncated))
        "the default encoding collapses that interval to zero length ...")
    (is (not (logic/fact-valid-at? truncated t-open))
        "... and a zero-length interval is valid at no instant: the fact would
         disappear from every valid-time view rather than read as short-lived")))

(deftest a-dump-leads-with-a-header-naming-its-format
  (let [d logic/dump-discriminator
        lines (wire/dump-lines [{d "entity" :id "e-1" :name "svc"}
                                {d "fact" :id "f-1"}])
        [header & records] (map wire/parse-string lines)]
    (is (= {:record "claimgraph-dump" :format version/format-version
            :version version/release}
           header)
        "the exact shape the load side implements against")
    (is (= logic/dump-discriminator (first (keys (version/dump-header))))
        "one key answers 'what is this line?' for the header and every record
         alike — version.clj spells :record literally to stay a leaf namespace,
         so nothing but this pins it to the discriminator the records use")
    (is (version/header? header))
    (is (not-any? version/header? records))
    (is (= ["entity" "fact"] (mapv d records))
        "the graph records follow the header, in the order they were given")))

;; ---------------------------------------------------------------------------
;; Version identity
;; ---------------------------------------------------------------------------

(deftest release-and-format-versions-are-independent
  (is (= "0.1.0-alpha" version/release))
  (is (int? version/format-version)
      "a loader gates on one integer, never on the release string")
  (is (= {:version version/release :format version/format-version}
         (version/describe nil))
      "no checkout, no :sha key — an absent sha is absent, never \"unknown\"")
  (is (= "abc1234" (:sha (version/describe "abc1234")))))

(deftest version-verb-reports-what-is-running
  (is (some #(= ["version"] (:cmds %)) cli/table)
      "wired into the dispatch table")
  (is (re-find #"(?m)^  version\s" cli/help-text)
      "and into the help text, like every other verb")
  (let [r (json/parse-string (with-out-str (cli/cmd-version {:opts {}})) true)]
    (is (= version/release (:version r)))
    (is (= version/format-version (:format r)))
    (when-let [sha (:sha r)]
      (is (re-matches #"[0-9a-f]{40}" sha)
          "run from a checkout: a real sha of claimgraph's own tree"))))

(deftest command-output-is-encoded-like-the-dump
  (let [out (with-out-str (#'cli/emit {} {:recorded-at t-open}))]
    (is (= "2026-07-25T02:28:00.255Z" (:recorded-at (json/parse-string out true)))
        "a caller parsing CLI JSON had the same truncation problem the dump did")))

;; ---------------------------------------------------------------------------
;; The CLI surface: flag names, exit status, and the shape of a report
;;
;; Everything below freezes something a caller writes a script against. It is
;; all cheap to change now and a compatibility promise the day someone adopts
;; the tool, which is the only reason it is tested at this altitude at all.
;; ---------------------------------------------------------------------------

(defn- temp-db-path []
  (str (fs/path (fs/temp-dir) (str "claimgraph-stamp-" (random-uuid)))))

(defn- table-entry [cmds]
  (first (filter #(= cmds (:cmds %)) cli/table)))

(defn- argv
  "One whole command line through the dispatcher -> {:exit :out :err}. No
  store is reached: what is under test is the argv, not the graph."
  [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out *err* err] (cli/run args))]
    {:exit code
     :out (str out)
     :err (some-> (not-empty (str/trim (str err))) (json/parse-string true))}))

(defn- cli-json
  "One command against a given store, its stdout parsed. Redefining the store
  opener is what keeps the pod (and a real db) out of this."
  [cmd s opts]
  (json/parse-string
   (with-redefs [cli/open-store (fn [_] s)]
     (with-out-str (cmd {:opts (assoc opts :db (temp-db-path))})))
   true))

(deftest a-command-line-claimgraph-cannot-act-on-is-a-usage-error
  (testing "a verb that does not exist"
    (let [{:keys [exit out err]} (argv ["frobnicate"])]
      (is (= 2 exit)
          "printing the whole help screen to stdout and exiting 0 is how a typo
           in a SessionEnd hook command line reads as a session that went fine")
      (is (= "" out) "stdout is the JSON channel; an error is not JSON output")
      (is (= "unknown-command" (:type err)))
      (is (str/includes? (:error err) "frobnicate"))
      (is (contains? (set (:expected err)) "ingest-session"))
      (is (not (contains? (set (:expected err)) "session-extract"))
          "the suggestions are the canonical verbs: the list was built from the
           dispatch table after alias expansion, so somebody recovering from a
           typo was handed the spelling the rename exists to retire — and would
           write new command lines against it")))
  (testing "a value a flag cannot take"
    (let [{:keys [exit out err]} (argv ["neighbor" "--entity" "api" "--depth" "two"])]
      (is (= 2 exit) "the command line was wrong, so not error-exit")
      (is (= "" out))
      (is (= "invalid-option-value" (:type err))
          "every babashka.cli failure went through the unknown-verb rendering,
           and a coercion failure carries none of the keys that rendering reads:
           a fine verb was reported as nonexistent, the command attempted came
           out \"\" and the alternatives came out []")
      (is (= "--depth" (:option err)) "the flag actually at fault is named")
      (is (str/includes? (:error err) "\"two\"") "with the value it could not read")
      (is (str/includes? (:error err) "long") "and what that flag takes instead")
      (is (nil? (:command err)) "and nothing is claimed about the verb, which was fine")))
  (testing "a subcommand that does not exist"
    (let [{:keys [exit err]} (argv ["entity" "frobnicate"])]
      (is (= 2 exit))
      (is (= "unknown-command" (:type err)))
      (is (str/includes? (str (:error err)) "entity frobnicate")
          "babashka.cli's dispatch failure carries no message at all, so this
           reached the caller as {\"error\": null} — from the one failure mode a
           mistyped hook actually produces")
      (is (contains? (set (:expected err)) "ensure"))))
  (testing "a verb given no subcommand"
    (let [{:keys [exit err]} (argv ["entity"])]
      (is (= 2 exit))
      (is (= "incomplete-command" (:type err)))))
  (testing "and asking for help is still a success, both ways"
    (is (= 0 (:exit (argv ["help"]))))
    (is (= 0 (:exit (argv []))))
    (is (str/includes? (:out (argv [])) "Usage: claim"))))

;; ---------------------------------------------------------------------------
;; A text-valued flag survives, whatever it looks like
;;
;; Without an explicit :coerce, babashka.cli guesses a flag's type from its
;; value: "true"/"false" -> boolean, a numeral -> long/double, a leading ':'
;; -> keyword. Every flag in this CLI whose value is arbitrary user text —
;; the ones below, and the many more like them across the table — needs its
;; own {:coerce :string} (or {:coerce [:string]} where it repeats) or that
;; guess corrupts it: `claim assert --object false` asserted a boolean, and
;; `claim audit --extractor false` silently fell back to `claude -p`.
;; ---------------------------------------------------------------------------

(deftest a-text-flag-is-never-auto-coerced
  (testing "the assert command's spec forces every text flag to a string"
    (let [spec (:spec (table-entry ["assert"]))
          parse (fn [args] (bcli/parse-opts args {:spec spec}))]
      (is (= "false" (:object (parse ["--object" "false"])))
          "\"false\" is a fact object, not a boolean")
      (is (= "1.5" (:object (parse ["--object" "1.5"])))
          "a numeral-looking object stays a string")
      (is (= ":kw" (:object (parse ["--object" ":kw"])))
          "and so does one that looks like a keyword")
      (is (= 0.9 (:confidence (parse ["--confidence" "0.9"])))
          "a genuinely numeric flag is untouched by this")))
  (testing "repeatable text flags collect as strings too — audit's --file,
            --dir and --scan-dir used {:coerce []}, which collects into a
            vector but still auto-coerces each element"
    (let [spec (:spec (table-entry ["audit"]))]
      (is (= ["false" "1.5"]
             (:file (bcli/parse-opts ["--file" "false" "--file" "1.5"] {:spec spec})))))))

(deftest audit-extractor-false-blocks-instead-of-silently-falling-back
  (let [tmp (str (fs/create-temp-dir {:prefix "claimgraph-extractor-false-test"}))
        {:keys [exit out]} (argv ["audit" "--project" tmp "--extractor" "false"
                                  "--json" "--quiet"])]
    (is (= 1 exit)
        "\"false\" used to parse as the boolean false, which llm/command reads
         as unset and falls back to `claude -p` — silently running the wrong
         extractor. A string \"false\" resolves to /usr/bin/false: it passes
         the on-PATH prerequisite check (it exists) and then fails the
         preflight round-trip, so the run reports blocked instead of quietly
         substituting a different command")
    (is (= "blocked" (:status (json/parse-string out true))))
    (is (= "false" (get-in (json/parse-string out true) [:prerequisites :extractor :command])))))

(deftest a-blocked-setup-exits-non-zero
  (let [out (java.io.StringWriter.)
        code (with-redefs-fn {(requiring-resolve 'claimgraph.setup/run!)
                              (fn [_] {:status :blocked :hint "dtlv is not installed"})}
               (fn [] (binding [*out* out] (cli/cmd-setup {:opts {:dry-run true}}))))]
    (is (= 1 code)
        "a blocked setup wired nothing, and exit 0 is the only signal a caller
         has that says claimgraph is installed")
    (is (= "blocked" (:status (json/parse-string (str out) true)))
        "the report still goes to stdout — it names the missing prerequisite,
         which is the part worth having")))

(deftest a-partial-hooks-run-still-exits-zero-unless-ci-asks-otherwise
  (let [s (mem/create)
        report {:status :partial :ingest-notes {:status :error :error "no claude on PATH"}}
        run (fn [opts]
              (let [out (java.io.StringWriter.)
                    code (with-redefs-fn {(requiring-resolve 'claimgraph.hooks/run!)
                                          (fn [_ _] report)}
                           (fn [] (with-redefs [cli/open-store (fn [_] s)]
                                    (binding [*out* out]
                                      (cli/cmd-hooks-run
                                       {:opts (assoc opts :db (temp-db-path))})))))]
                [code (json/parse-string (str out) true)]))]
    (let [[code r] (run {})]
      (is (nil? code)
          "failing a SessionEnd hook is worse than reporting: the stages that did
           run recompiled the view the next session reads")
      (is (= "partial" (:status r))))
    (let [[code r] (run {:fail-on-partial true})]
      (is (= 1 code) "but a scheduled run needs a way to see it")
      (is (= "partial" (:status r)) "and sees the same report either way"))))

(deftest a-renamed-verb-still-answers-to-the-name-already-written-down
  (let [new (table-entry ["ingest-session"])
        old (table-entry ["session-extract"])]
    (is (some? new)
        "the fifth ingestion tier, named like the four beside it")
    (is (some? old)
        "and the old name still dispatches: it is in the installed SKILL.md, the
         README, and hook command lines on machines this release will never see")
    (is (= (:fn new) (:fn old)) "to the same command, not to a copy of it")
    (is (= (:spec new) (:spec old)) "with the same flags — --dry-run parses either way")
    (is (= ["ingest-session"] (:alias-of old)) "marked as the second name it is")
    (is (nil? (:alias-of new)))
    (is (not-any? :aliases cli/table)
        "declarations are expanded into entries, never dispatched on")
    (is (re-find #"(?m)^  ingest-session\s" cli/help-text))))

(deftest one-flag-name-per-directory-a-verb-actually-means
  (let [alias #'cli/accept-alias]
    (testing "--dir keeps working as each verb's older spelling"
      (is (= {:dir "/n" :notes-dir "/n"} (alias {:dir "/n"} :notes-dir :dir)))
      (is (= {:dir "/n" :notes-dir "/c"} (alias {:dir "/n" :notes-dir "/c"} :notes-dir :dir))
          "and the canonical flag wins when a caller spells both")
      (is (= {:dir "/p" :project "/p"} (alias {:dir "/p"} :project :dir))
          "on ingest-code --dir was the project root all along; --project was
           accepted and silently ignored")
      (is (= {:dir "/adr" :adr-dir "/adr"} (alias {:dir "/adr"} :adr-dir :dir))))
    (testing "folded before the env and config layers, so a flag still outranks them"
      (is (= "/flag"
             (:notes-dir (config/merge-defaults (alias {:dir "/flag"} :notes-dir :dir)
                                                {:env {"CLAIMGRAPH_NOTES_DIR" "/env"}}
                                                [:notes-dir])))))
    (testing "which is what makes `setup --dir` persist the choice it announces"
      (is (= {:notes-dir "/n"}
             (select-keys (alias {:dir "/n"} :notes-dir :dir)
                          @#'cli/setup-persist-keys)))))
  (testing "and on audit --dir was never the notes dir at all"
    (is (= ["extra" "legacy"] (#'cli/audit-scan-dirs {:scan-dir ["extra"] :dir ["legacy"]}))
        "a repeatable list of extra sources: both spellings add, neither shadows")))

(deftest config-resolves-the-notes-dir-flag-it-documents
  (let [resolved #(:resolved (json/parse-string
                              (with-out-str (cli/cmd-config {:opts %})) true))]
    (is (= "/tmp/claimgraph-notes" (:notes-dir (resolved {:notes-dir "/tmp/claimgraph-notes"})))
        "`claim config --notes-dir` used to resolve nothing: setup documented and
         persisted notes-dir while every consumer, this one included, read --dir")
    (is (= "/tmp/claimgraph-notes" (:notes-dir (resolved {:dir "/tmp/claimgraph-notes"})))
        "and the older spelling still answers")))

;; ---------------------------------------------------------------------------
;; --project anchors the config system, not just the thing a verb scans
;;
;; config-file-path (and everything built on it: with-defaults, value,
;; describe) used to resolve .claimgraph/config.json relative to the process
;; cwd always, and db-path resolved a relative db the same cwd-relative way —
;; two anchors that agreed only by coincidence, when cwd happened to be the
;; project. `claim config --project X` run from Y reported notes-dir/
;; inject-file/settings-file resolved against X's config file but db/
;; evidence-dir against Y's cwd-relative default: one report, two projects.
;; ---------------------------------------------------------------------------

(deftest config-resolves-db-and-evidence-dir-under-the-project-not-cwd
  ;; Canonicalized up front: cmd-config resolves settings-file/skills-dir
  ;; through fs/canonicalize (which follows a symlink, e.g. macOS's
  ;; /tmp -> /private/tmp) but db/evidence-dir through fs/absolutize (which
  ;; does not) — a pre-existing asymmetry between the two, not something this
  ;; fix changes. Starting from the canonical form makes every :resolved path
  ;; comparable the same way regardless of which fs fn produced it.
  (let [tmp (str (fs/canonicalize (fs/create-temp-dir {:prefix "claimgraph-project-anchor-test"})))
        _ (fs/create-dirs (fs/path tmp ".claimgraph"))
        _ (spit (str (fs/path tmp ".claimgraph" "config.json"))
                (json/generate-string {:harness "codex"}))
        {:keys [exit out]} (argv ["config" "--project" tmp "--json"])
        r (json/parse-string out true)]
    (is (= 0 exit))
    (testing "the project's own config file is the one read, not the repo
              this test runs from — its --harness codex proves it, since
              nothing on this command line passed --harness directly"
      (is (str/includes? (get-in r [:config-file :path]) tmp))
      (is (= "codex" (get-in r [:settings :harness :value])))
      (is (= :config (keyword (get-in r [:settings :harness :source])))))
    (testing "db and evidence-dir anchor to the project, coherently with
              settings-file and skills-dir, which already did"
      (is (str/starts-with? (get-in r [:resolved :db]) tmp)
          "used to resolve against cwd: `claim config --project X` run from Y
           reported Y's db")
      (is (str/starts-with? (get-in r [:resolved :evidence-dir]) tmp))
      (is (str/starts-with? (get-in r [:resolved :settings-file]) tmp))
      (is (str/starts-with? (get-in r [:resolved :skills-dir]) tmp))
      (is (= (str (fs/path tmp ".claimgraph" "db"))
             (get-in r [:resolved :db]))
          "the exact default location, not merely somewhere under tmp")
      (is (= (str (fs/path tmp ".claimgraph" "db.evidence"))
             (get-in r [:resolved :evidence-dir]))))))

(deftest a-bare-run-with-no-project-is-unaffected
  (testing "config-file-path's own default is untouched by the project
            plumbing when no --project is passed anywhere on the line"
    (is (= ".claimgraph/config.json" (config/config-file-path (into {} (System/getenv))))))
  (let [{:keys [exit out]} (argv ["config" "--db" "/explicit/abs/db" "--json"])
        r (json/parse-string out true)]
    (is (= 0 exit))
    (is (= "/explicit/abs/db" (get-in r [:resolved :db]))
        "an absolute --db is unchanged by anchoring: fs/path's own resolve
         rule returns an absolute second component untouched")))

(deftest the-extractor-flag-reaches-the-command-it-names
  (is (= "mycmd" (:command (#'cli/llm-opts {:extractor "mycmd"})))
      "judge, consolidate and hooks run resolved --extractor against an EMPTY
       opts map: the flag parsed, changed nothing, and the run shelled out to
       whatever the environment said instead")
  (is (= "explicit" (:command (#'cli/llm-opts {:command "explicit" :extractor "mycmd"})))
      "--command still wins where a caller passes both"))

(deftest the-verdict-gate-is-not-the-read-filter
  (testing "--min-confidence means two unrelated things, so the gate is renamed"
    (is (= 0.8 (:min-verdict-confidence
                (#'cli/accept-alias {:min-confidence 0.8} :min-verdict-confidence
                                    :min-confidence)))
        "0.8 reads as \"high-confidence facts\" and silently meant \"act on
         verdicts\"; the old spelling still resolves to the gate")
    (doseq [cmds [["judge"] ["consolidate"]]]
      (is (contains? (:spec (table-entry cmds)) :min-verdict-confidence)
          (str (str/join " " cmds) " takes the verdict gate"))
      (is (contains? (:spec (table-entry cmds)) :min-confidence)
          "and still parses the name its README teaches"))
    (is (not-any? #(contains? (:spec (table-entry ["hooks" "run"])) %)
                  [:min-verdict-confidence :min-confidence :resolve])
        "`hooks run` took the gate while it consolidated inline; it captures now
         and every verdict belongs to the curator it spawns"))
  (testing "while the read verbs keep --min-confidence for what it filters"
    (doseq [cmds [["facts"] ["neighbor"]]]
      (is (contains? (:spec (table-entry cmds)) :min-confidence))
      (is (not (contains? (:spec (table-entry cmds)) :min-verdict-confidence))))))

(deftest audits-pretty-is-pretty-json-like-everywhere-else
  (let [scorecard? #'cli/audit-scorecard?]
    (is (not (scorecard? {:pretty true} true))
        "`audit --pretty | jq` used to get a human scorecard and no JSON at all —
         one flag meaning \"pretty-print the JSON\" on every verb but this one")
    (is (scorecard? {} true) "a human at a terminal still gets the scorecard unasked")
    (is (scorecard? {:scorecard true} false) "and a pipe can now ask for it")
    (is (not (scorecard? {} false)) "captured output stays JSON")
    (is (scorecard? {:scorecard true :pretty true} false)
        "--pretty says how JSON is printed, not whether JSON is what comes out,
         so it does not veto a flag that asks for the scorecard outright — help
         claimed both flags \"force\" their format and --pretty quietly won")
    (is (scorecard? {:scorecard true :pretty true} true) "at a terminal too")
    (is (not (scorecard? {:json true :scorecard true} true))
        "--json remains the override that always wins: a caller that asked for
         machine output must never be handed prose")))

(deftest every-mutation-verb-reports-a-status
  (let [s (doto (mem/create) (core/seed!))]
    (let [r (cli-json cli/cmd-entity-ensure s {:name "AuthService"})]
      (is (= "ensured" (:status r))
          "ensure answered with the bare entity: alone among the entity verbs, a
           mutation a caller had to recognise by shape instead of read a status off")
      (is (= "AuthService" (get-in r [:entity :name]))
          "{status, entity} — the shape `entity rename` and `entity alias` use"))
    (let [r (cli-json cli/cmd-episode-open s {:source-type "session-log"})]
      (is (= "opened" (:status r)))
      (is (string? (get-in r [:episode :id]))
          "{status, episode} — the row nested under the name of the thing it is,
           like the two siblings above. It used to be flattened into the report
           with its :id renamed to :episode: a third report shape among three
           sibling mutations, and an episode row carrying no id at all")
      (is (= "session-log" (get-in r [:episode :source-type]))
          "and the row still reports what it recorded"))
    (let [r (cli-json cli/cmd-predicate-register s {:id "x/pairs-well-with"})]
      (is (= "registered" (:status r)))
      (is (= "x/pairs-well-with" (get-in r [:predicate :id])))
      (is (= (name (:status (store/-get-predicate s :x/pairs-well-with)))
             (get-in r [:predicate :status]))
          "the row is nested rather than merged with the report: a predicate row
           HAS a :status of its own, and flattening would answer a question about
           the vocabulary with a fact about the command"))))

(deftest the-guided-walk-answers-in-the-neighborhoods-shape
  (let [s (doto (mem/create) (core/seed!))
        neighbor #(cli-json cli/cmd-neighbor s %)]
    (core/assert-fact s {:subject "AuthService" :predicate :core/depends-on
                         :object "TokenStore" :source-type :code})
    (core/assert-fact s {:subject "TokenStore" :predicate :core/depends-on
                         :object "Redis" :source-type :code})
    (let [bfs (neighbor {:entity "AuthService" :depth 2})
          walk (neighbor {:entity "AuthService" :query "token storage"})]
      (is (every? (set (keys walk)) (keys bfs))
          "one verb answered with two incompatible objects depending on whether
           --query was passed: the walk dropped :entities and :depth outright")
      (is (= "AuthService" (get-in walk [:root :name])))
      (is (= "token storage" (:query walk)) "and keeps what makes it a walk")
      (is (= [0 1 2] (mapv :depth (:entities walk)))
          "entities carry the hop distance the neighborhood promises — the round
           the walk discovered each node in, not a distance re-measured over
           whichever facts the budget left behind")
      (is (= (:depth walk) (reduce max (map :depth (:entities walk)))))
      (is (seq (:facts walk)))
      (is (every? #(and (contains? % :effective-confidence) (contains? % :walk-score))
                  (:facts walk))
          ":walk-score was reported INSTEAD of :effective-confidence, so a reader
           of one shape could not read the other; it is additional now"))))

(deftest a-truncated-walk-still-reports-every-hop-distance
  (let [s (doto (mem/create) (core/seed!))]
    ;; Two branches off the root. The query's evidence sits at the end of the
    ;; weak branch and outscores everything; the edge that DISCOVERED that
    ;; branch scores lowest of all, so the budget is what drops it.
    (core/assert-fact s {:subject "api" :predicate :core/depends-on
                         :object "backup" :confidence 0.35})
    (core/assert-fact s {:subject "api" :predicate :core/depends-on
                         :object "svcC" :confidence 0.9})
    (core/assert-fact s {:subject "backup" :predicate :core/prefers
                         :object "cache strategy notes" :object-kind :literal
                         :confidence 0.9})
    (core/assert-fact s {:subject "svcC" :predicate :core/depends-on
                         :object "svcD" :confidence 0.9})
    (let [r (cli-json cli/cmd-neighbor s {:entity "api" :query "cache strategy"
                                          :budget 3 :beam 2})]
      (is (= 3 (count (:facts r))) "a round overshoots the budget; the sort truncates")
      (is (not-any? #(= ["api" "backup"] [(get-in % [:subject :name])
                                          (get-in % [:object-ref :name])])
                    (:facts r))
          "and what it truncated is the edge that reached `backup` at all")
      (is (= {:api 0 :backup 1 :svcC 1 :svcD 2}
             (into {} (map (juxt (comp keyword :name) :depth)) (:entities r)))
          "`\"depth\": null` for `backup`: hop distance was re-measured over the
           returned facts, and a round-2 fact routinely outscores the round-1
           fact that discovered its endpoint — so the linking fact is exactly
           what truncation drops and no retained fact reaches the node from the
           root. The MCP surface shares this wrapper, so it reported null too.")
      (is (= 2 (:depth r)) "and the walk's own depth is the deepest hop in it"))))

(deftest the-llm-timeout-is-a-knob-like-every-other
  (is (= {:value 30000 :source :config}
         (config/resolve-setting :llm-timeout-ms {:opts {} :env {}
                                                  :config {:llm-timeout-ms 30000}}))
      "settable from the project config file, which an environment-only read
       could never be")
  (is (= {:value 45000 :source :env}
         (config/resolve-setting :llm-timeout-ms
                                 {:opts {} :env {"CLAIMGRAPH_LLM_TIMEOUT_MS" "45000"}})))
  (is (contains? (:settings (config/describe {})) :llm-timeout-ms)
      "and listed by `claim config`, where a user goes to find out why a setting
       is not taking")
  (testing "the resolved value reaches the call that has to obey it"
    (let [v (requiring-resolve 'claimgraph.llm/default-timeout-ms)
          resolver (requiring-resolve 'claimgraph.llm/timeout-ms)
          [before before-fn] [@v @resolver]]
      (try
        (#'cli/install-llm-timeout! {:llm-timeout-ms 4321})
        (is (= 4321 @v)
            "claimgraph.llm reads the environment itself and nothing threads a
             per-call timeout down to it, so the shell resolves the chain once
             and installs the answer as the process default")
        (is (= 4321 (@resolver nil "9999"))
            "and the environment layer is spent: llm/timeout-ms consults the
             environment BEFORE that default, so installing the default alone
             left $CLAIMGRAPH_LLM_TIMEOUT_MS beating --llm-timeout-ms and the
             config file both — exactly inverting flag > env > config > default")
        (is (= 150 (@resolver 150))
            "a per-call override still wins, which is what makes it an override")
        (finally (alter-var-root v (constantly before))
                 (alter-var-root resolver (constantly before-fn))))))
  (testing "against a variable that is really exported"
    ;; A child process because that is the whole question: no in-process
    ;; binding can put a value in front of System/getenv, and this is the
    ;; shape every machine with the variable in its shell profile runs.
    (let [script (str "(require '[claimgraph.cli :as cli] '[claimgraph.llm :as llm])"
                      "(#'cli/install-llm-timeout! {:llm-timeout-ms 100})"
                      "(println (llm/timeout-ms nil))")
          {:keys [out]} (p/sh {:extra-env {"CLAIMGRAPH_LLM_TIMEOUT_MS" "5000"}}
                              "bb" "--classpath" (cp/get-classpath) "-e" script)]
      (is (= "100" (str/trim out))
          "the flag the user passed, not the variable the machine exports"))))

(deftest a-runtime-hint-teaches-the-flag-help-documents
  (testing "ingest-adr finding no decision records"
    (let [empty-dir (str (fs/create-temp-dir {:prefix "claimgraph-adr-hint"}))
          r ((requiring-resolve 'claimgraph.ingest.adr/ingest!)
             nil {:dir empty-dir :dry-run true})]
      (is (= :no-adr-dir (:status r)))
      (is (str/includes? (:hint r) "--adr-dir")
          "the hint named --dir while help documents --adr-dir. The alias still
           dispatches, so nothing breaks — but the failure path is where a user
           reads a flag name, and teaching the retired spelling there is how a
           deprecated one outlives its deprecation")))
  (testing "ingest-notes finding no auto-memory directory"
    (let [absent (str (fs/path (fs/temp-dir) (str "claimgraph-absent-" (random-uuid))))
          r ((requiring-resolve 'claimgraph.ingest.notes/ingest!) nil {:dir absent})]
      (is (= :no-notes-dir (:status r)))
      (is (str/includes? (:hint r) "--notes-dir")
          "same rename, same failure path, same hint that outlived it"))))

;; ---------------------------------------------------------------------------
;; The store's own format stamp
;; ---------------------------------------------------------------------------

(defn- open-datalevin [path]
  ((requiring-resolve 'claimgraph.store.datalevin/open-store) path))

(defn- stamp-file [path]
  ((requiring-resolve 'claimgraph.store.datalevin/version-file) path))

(deftest a-store-stamps-itself-on-open
  (when core-test/datalevin?
    (let [path (temp-db-path)
          s (open-datalevin path)]
      (try
        (is (fs/exists? (stamp-file path)) "<db>.version, a sibling like <db>.oplog")
        (is (= {:format version/format-version :version version/release}
               (wire/parse-string (slurp (stamp-file path))))
            "the format a loader gates on, plus the release for a bug report")
        (is (= version/format-version (:format (store/-stats s))))
        (finally (store/-close s))))))

(deftest an-unstamped-store-is-stamped-in-place-not-refused
  (when core-test/datalevin?
    (let [path (temp-db-path)
          s (open-datalevin path)
          _ (store/-close s)
          _ (fs/delete (stamp-file path))
          reopened (open-datalevin path)]
      (try
        (is (= version/format-version
               ((requiring-resolve 'claimgraph.store.datalevin/stamped-format) path))
            "every store written before stamping existed is unstamped, including
             claimgraph's own; refusing them would strand every existing user
             over a shape difference that does not exist")
        (finally (store/-close reopened))))))

(deftest a-stamp-this-build-cannot-read-is-refused-not-replaced
  (when core-test/datalevin?
    (let [path (temp-db-path)
          s (open-datalevin path)
          _ (store/-close s)]
      (doseq [[what content] [["a crash mid-spit" "{\"format\":9,\"vers"]
                              ["an empty file" ""]
                              ["JSON with no format in it" "{\"version\":\"9.9.9\"}"]]]
        (testing what
          (spit (stamp-file path) content)
          (let [e (try (open-datalevin path)
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :unreadable-format-stamp (:type (ex-data e))))
            (is (str/includes? (ex-message e) (stamp-file path)) "names the file")
            (is (str/includes? (:hint (ex-data e)) "delete")
                "and says what deleting it would cost, because that is the only
                 way forward and it is not free"))
          (is (= content (slurp (stamp-file path)))
              "the stamp is left exactly as found. Read as merely unstamped it
               would be overwritten with this build's number on the way past —
               destroying the one piece of evidence that the store was ever
               anything else, on precisely the input the gate cannot read"))))))

(deftest a-store-from-the-future-is-refused-before-anything-opens-it
  (when core-test/datalevin?
    (let [path (temp-db-path)
          ahead (inc version/format-version)]
      (fs/create-dirs (fs/parent (stamp-file path)))
      (spit (stamp-file path) (wire/generate-string {:format ahead :version "9.9.9"}))
      (let [e (try (open-datalevin path)
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :unsupported-format (:type (ex-data e))))
        (is (= ahead (:found (ex-data e))))
        (is (= version/format-version (:supported (ex-data e))))
        (is (not (fs/exists? path))
            "and refused BEFORE d/get-conn ran: get-conn merges this build's
             schema into whatever it opens, so a gate that waited for a
             connection would commit the corruption it was detecting")))))

;; ---------------------------------------------------------------------------
;; Re-seeding a predicate row, in both backends
;; ---------------------------------------------------------------------------

(defn- stores
  "Both backends, so a divergence in registry semantics cannot hide in one."
  []
  (cond-> {:memory (mem/create)}
    core-test/datalevin? (assoc :datalevin (open-datalevin (temp-db-path)))))

(deftest re-registering-a-predicate-reconciles-it-including-removals
  (doseq [[kind s] (stores)]
    (testing (str "[" (name kind) "] ")
      (try
        (store/-register-predicate s {:id :core/defined-in :label "defined in"
                                      :category :structural :cardinality :one
                                      :inverse-of :core/contains :status :stable})
        (is (= :core/contains (:inverse-of (store/-get-predicate s :core/defined-in))))
        (store/-register-predicate s {:id :core/defined-in :label "defined in"
                                      :category :structural :cardinality :one
                                      :status :stable})
        (let [row (store/-get-predicate s :core/defined-in)]
          (is (nil? (:inverse-of row))
              "the field the seed dropped is retracted, not remembered: we
               removed :inverse-of from :core/defined-in precisely because it
               broke the contains/part-of bijection, and an add-only upsert
               would leave every seeded store reporting the retired value")
          (is (= "defined in" (:label row)) "what the row still claims survives")
          (is (= :one (:cardinality row))))
        (finally (store/-close s))))))

(deftest re-seeding-retires-a-field-the-seed-has-dropped
  (doseq [[kind s] (stores)]
    (testing (str "[" (name kind) "] ")
      (try
        (core/seed! s)
        ;; :core/defined-in exactly as claimgraph seeded it before e154b6d,
        ;; when it still claimed an inverse that gave :core/contains two
        ;; claimants and broke the containment bijection
        (store/-register-predicate
         s (assoc (first (filter #(= :core/defined-in (:id %)) preds/seed))
                  :inverse-of :core/contains))
        (is (= :core/contains (:inverse-of (store/-get-predicate s :core/defined-in)))
            "the row an existing store is carrying")
        (core/seed! s)
        (is (nil? (:inverse-of (store/-get-predicate s :core/defined-in)))
            "re-seeding is how that store stops reporting it: the seed map IS the
             curated row, so a field the seed no longer mentions is retracted
             rather than remembered")
        (finally (store/-close s))))))

(deftest a-staging-row-is-amended-by-a-re-register-not-replaced-by-it
  (doseq [[kind s] (stores)]
    (testing (str "[" (name kind) "] ")
      (try
        (core/seed! s)
        (core/register-predicate s {:id :x/pairs-well-with :definition "coined here"
                                    :maps-to "skos:related"
                                    :default-epistemic "preference"})
        (core/promote-predicate s {:from :x/pairs-well-with :to :core/pairs-well-with})
        (core/seed! s)
        (let [staging (store/-get-predicate s :x/pairs-well-with)]
          (is (= :deprecated (:status staging)))
          (is (= :core/pairs-well-with (:replaced-by staging))
              "the seed owns the fields of the rows it defines; whole rows it
               never mentions — runtime coinages, promotion husks — are the
               store's, and a re-seed does not touch them"))
        (is (= "coined here" (:definition (store/-get-predicate s :core/pairs-well-with)))
            "nor the promoted twin, which is not a seed row either")
        ;; and now the verb a user actually runs against a row that already
        ;; exists: `claim predicate register` sends the fields it was given
        ;; (through logic/prepare-registration) and never the whole row
        (core/register-predicate s {:id :x/pairs-well-with :definition "amended"})
        (let [staging (store/-get-predicate s :x/pairs-well-with)]
          (is (= "amended" (:definition staging)) "what the amendment says wins")
          (is (= "skos:related" (:maps-to staging))
              "and what it never mentioned stays: reconciling a staging row
               against a partial map erases whatever an earlier writer put there")
          (is (= :preference (:default-epistemic staging)))
          (is (= :core/pairs-well-with (:replaced-by staging))
              "including the forwarding address promotion left behind, which is
               the store's to keep and no registration's to drop"))
        (finally (store/-close s))))))
