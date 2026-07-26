(ns claimgraph.setup-test
  "One-shot onboarding against a temp project: every step idempotent and
  dry-runnable, no real store backend (init-fn injected), no LLM, no real
  ~/.claude.

  Also the home of the checks about what a user's INSTALL looks like, because
  they answer to the same question and nothing else in the suite owns it: the
  installer against bb.edn, this repo's own .gitignore against the block setup
  manages, and the command surfaces a user actually invokes — `dump`, `load`,
  `version`, and the MCP tools — driven end to end through cli/mcp against an
  in-memory store, so what lands on disk is asserted rather than assumed from
  the units underneath.

  Cheshire, not claimgraph.wire, on purpose in these tests: the artifacts are
  being checked as bytes a foreign reader sees, and going back through the
  encoder under test would let a drift in it agree with itself."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.cli :as cli]
            [claimgraph.config :as config]
            [claimgraph.core :as core]
            [claimgraph.mcp :as mcp]
            [claimgraph.setup :as setup]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]
            [claimgraph.version :as version]))

(defn- temp-project []
  (str (fs/create-temp-dir {:prefix "claimgraph-setup-test"})))

(defn- containing-checkout
  "Nearest ancestor of p holding a bb.edn, or nil."
  [p]
  (loop [d (fs/absolutize p)]
    (when d
      (if (fs/exists? (fs/path d "bb.edn")) (str d) (recur (fs/parent d))))))

(def repo-root
  "This checkout, located from the test file itself rather than from the
  runner's cwd. The repo-wide invariants below (installer vs bb.edn, the
  dogfood skill) are exactly the ones that must not go quietly unchecked
  because the suite happened to be started from somewhere else."
  (containing-checkout (or *file* ".")))

(def setup-sh (str (fs/path (or repo-root ".") "scripts" "setup.sh")))

(def fake-init (constantly {:status :initialized :predicates 23}))

(defn- fake-which
  "Prerequisite lookup with everything installed — tests must not depend on
  the machine's real PATH."
  [bin]
  (str "/fake/bin/" bin))

(def base-opts {:init-fn fake-init :which fake-which})

(deftest prerequisites-are-checked-first
  (testing "everything present -> :ok, extractor reported"
    (let [r (setup/check-prerequisites {:which fake-which})]
      (is (= :ok (:status r)))
      (is (= "/fake/bin/dtlv" (:dtlv r)))
      (is (= {:command "claude -p" :found true} (:extractor r)))))
  (testing "a missing extractor is a note, never an error"
    (let [r (setup/check-prerequisites {:which #(when (= % "dtlv") "/fake/bin/dtlv")})]
      (is (= :ok (:status r)))
      (is (false? (get-in r [:extractor :found])))
      (is (str/includes? (:note r) "deterministic stages are unaffected"))))
  (testing "a missing dtlv is a hard error with the fix attached"
    (let [r (setup/check-prerequisites {:which (constantly nil)})]
      (is (= :error (:status r)))
      (is (str/includes? (:hint r) "scripts/setup.sh")))))

(deftest missing-dtlv-blocks-and-writes-nothing
  (let [project (temp-project)
        r (setup/run! {:project project :which (constantly nil)
                       :init-fn (fn [] (throw (ex-info "must not run" {})))})]
    (is (= :blocked (:status r)))
    (is (= [:prerequisites] (keys (:steps r))) "no other step even attempted")
    (is (str/includes? (:hint r) "scripts/setup.sh"))
    (is (empty? (fs/list-dir project)) "a hook that would fail at runtime is never wired"))
  (testing "dry-run still shows the plan, with the prereq error visible"
    (let [r (setup/run! {:project (temp-project) :dry-run true
                         :which (constantly nil) :init-fn fake-init})]
      (is (= :dry-run (:status r)))
      (is (= :error (get-in r [:steps :prerequisites :status]))))))

(deftest full-pass-is-idempotent
  (let [project (temp-project)
        r1 (setup/run! (assoc base-opts :project project))]
    (testing "first pass installs everything"
      (is (= :ready (:status r1)))
      (is (= "claim" (:bin r1)) "no repo-local bin/claim -> PATH")
      (is (= :initialized (get-in r1 [:steps :store :status])))
      (is (= :updated (get-in r1 [:steps :gitignore :status])))
      (is (= :installed (get-in r1 [:steps :skill :status])))
      (is (= :installed (get-in r1 [:steps :hooks :status])))
      (is (= :skipped (get-in r1 [:steps :mcp :status])) "MCP is opt-in")
      (is (seq (:next r1)) "the agent gets its next steps"))
    (testing "the artifacts landed"
      (let [skill (slurp (str (fs/path project ".claude" "skills" "claimgraph" "SKILL.md")))
            ignore (slurp (str (fs/path project ".gitignore")))
            settings (json/parse-string
                      (slurp (str (fs/path project ".claude" "settings.json"))) true)]
        (is (str/includes? skill "claim facts --entity"))
        (is (not (str/includes? skill "{{CLAIM}}")) "template fully rendered")
        (is (str/includes? ignore ".claimgraph/db/"))
        (is (str/includes? ignore ".claimgraph/db.oplog/"))
        (is (some? (get-in settings [:hooks :SessionEnd])))))
    (testing "second pass changes nothing"
      (let [r2 (setup/run! (assoc base-opts :project project))]
        (is (= :unchanged (get-in r2 [:steps :gitignore :status])))
        (is (= :unchanged (get-in r2 [:steps :skill :status])))
        (is (= :updated (get-in r2 [:steps :hooks :status]))
            "hooks re-install updates in place (never duplicates)")))))

(deftest dry-run-writes-nothing
  (let [project (temp-project)
        r (setup/run! {:project project :dry-run true :mcp true :which fake-which
                       :init-fn (fn [] (throw (ex-info "must not run" {})))})]
    (is (= :dry-run (:status r)))
    (is (every? #(contains? #{:ok :dry-run :skipped :unchanged} (:status %))
                (vals (:steps r))))
    (is (empty? (fs/list-dir project)) "not a single file written")))

(deftest chosen-settings-persist-to-project-config
  (let [project (temp-project)
        r (setup/run! (assoc base-opts :project project
                             :harness "codex" :chosen {:harness "codex" :consolidate-days 3}))
        cfg (json/parse-string
             (slurp (str (fs/path project ".claimgraph" "config.json"))) true)]
    (is (= :installed (get-in r [:steps :config :status])))
    (is (= {:harness "codex" :consolidate-days 3
            :config-version version/format-version}
           cfg))
    (testing "re-running with new choices merges, preserving earlier ones"
      (setup/run! (assoc base-opts :project project
                         :chosen {:extractor "llm -m small"}))
      (is (= {:harness "codex" :consolidate-days 3 :extractor "llm -m small"
              :config-version version/format-version}
             (json/parse-string
              (slurp (str (fs/path project ".claimgraph" "config.json"))) true))))
    (testing "all defaults + no file -> nothing persisted"
      (is (= :skipped (get-in (setup/run! (assoc base-opts :project (temp-project)))
                              [:steps :config :status]))))))

(deftest the-config-we-write-is-the-config-config-clj-reads
  ;; the format gate on the config file could only ever fire on a hand-edited
  ;; one while the writer stamped nothing — a gate that cannot fire is a gate
  ;; nobody notices is missing until a future release needs it
  (let [project (temp-project)
        _ (setup/persist-config! {:project project :chosen {:harness "codex"}})
        path (str (fs/path project ".claimgraph" "config.json"))
        cfg (json/parse-string (slurp path) true)]
    (is (= version/format-version (:config-version cfg)))
    (is (empty? (config/unknown-keys cfg))
        "the stamp is a key config.clj knows, not one it warns about")
    (is (nil? (config/unsupported-format path (:config-version cfg)))
        "and this build reads what it just wrote")
    (testing "a file from before stamping is stamped on the next pass, settings intact"
      (let [project (temp-project)
            path (str (fs/path project ".claimgraph" "config.json"))]
        (fs/create-dirs (fs/parent path))
        (spit path "{\"harness\":\"codex\"}\n")
        (setup/persist-config! {:project project :chosen {}})
        (is (= {:harness "codex" :config-version version/format-version}
               (json/parse-string (slurp path) true)))))))

(deftest gitignore-respects-existing-coverage-and-external-dbs
  (testing "a repo already ignoring the whole directory is left alone"
    (let [project (temp-project)]
      (spit (str (fs/path project ".gitignore")) ".claimgraph/\n")
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project}))))))
  (testing "a db outside the project is not our gitignore to manage"
    (let [r (setup/ensure-gitignore! {:project (temp-project) :db "/elsewhere/db"})]
      (is (= :skipped (:status r)))))
  (testing "appending preserves existing content"
    (let [project (temp-project)]
      (spit (str (fs/path project ".gitignore")) "node_modules/\n")
      (setup/ensure-gitignore! {:project project})
      (let [content (slurp (str (fs/path project ".gitignore")))]
        (is (str/starts-with? content "node_modules/\n"))
        (is (str/includes? content ".claimgraph/db/"))))))

(defn legacy-block-for
  "Verbatim what versions before the markers wrote, for a given db path. Pinned
  here because the upgrade path has to keep working against the shape users
  already have on disk (this repo's own .gitignore among them)."
  [db]
  (str "# claimgraph live store + local artifacts (the committable artifacts are\n"
       "# `claim dump` output and .claimgraph/config.json)\n"
       db "/\n" db ".lock\n" db ".evidence/\n" db ".oplog/\n"
       db ".retrievals\n" db ".last-consolidate\n"))

(def legacy-gitignore-block (legacy-block-for ".claimgraph/db"))

(deftest gitignore-block-stays-one-region-as-the-entries-grow
  ;; a later version adding one sibling artifact used to flip "already
  ;; covered" false and append a SECOND complete block, duplicate lines and all
  (let [project (temp-project)
        target (str (fs/path project ".gitignore"))
        entries setup/gitignore-entries]
    (spit target "node_modules/\n")
    (setup/ensure-gitignore! {:project project})
    (with-redefs [setup/gitignore-entries (fn [rel] (conj (entries rel) (str rel ".futures")))]
      (is (= :updated (:status (setup/ensure-gitignore! {:project project}))))
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project})))))
    (let [content (slurp target)]
      (is (= 1 (count (re-seq #"(?m)^# claimgraph:managed:begin$" content))))
      (is (= 1 (count (re-seq #"(?m)^\.claimgraph/db/$" content))) "no duplicated entry")
      (is (str/includes? content ".claimgraph/db.futures") "the new entry landed in the block")
      (is (str/starts-with? content "node_modules/\n")))))

(deftest legacy-gitignore-block-is-upgraded-in-place
  (let [project (temp-project)
        target (str (fs/path project ".gitignore"))
        ;; users append their own ignores directly under our block, with no
        ;; blank line between — this repo's .gitignore does exactly this
        before "node_modules/\n\n"
        after "*.dump.jsonl\nbench/results/\n\n# Book build artifacts\nbook/rendered/\n"]
    (spit target (str before legacy-gitignore-block after))
    (is (= :updated (:status (setup/ensure-gitignore! {:project project}))))
    (let [content (slurp target)]
      (testing "one block, now marked, standing where the unmarked one stood"
        (is (= 1 (count (re-seq #"(?m)^# claimgraph:managed:begin$" content))))
        (is (= 1 (count (re-seq #"(?m)^\.claimgraph/db/$" content))))
        (is (str/starts-with? content (str before setup/gitignore-begin-marker "\n"))))
      (testing "every other line survives verbatim, on both sides of the block"
        (is (str/ends-with? content (str setup/gitignore-end-marker "\n" after)))))
    (testing "the upgraded block is then stable"
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project})))))))

(deftest every-legacy-block-is-absorbed-not-just-the-first
  ;; relocating the db under a pre-marker version appended a SECOND complete
  ;; block, so .gitignores in the wild carry two. Adopting only the first
  ;; leaves the other as a permanent orphan: from the next run on, the begin
  ;; marker is found first and every pass reports :unchanged.
  (let [project (temp-project)
        target (str (fs/path project ".gitignore"))]
    (spit target (str "node_modules/\n\n"
                      (legacy-block-for ".claimgraph/db")
                      (legacy-block-for "old/db")
                      "\n# mine\nbuild/\n"))
    (is (= :updated (:status (setup/ensure-gitignore! {:project project}))))
    (let [content (slurp target)]
      (is (= 1 (count (re-seq #"(?m)^# claimgraph:managed:begin$" content))))
      (is (= 1 (count (re-seq #"(?m)^# claimgraph live store" content)))
          "the stale block's header went with it")
      (is (empty? (re-seq #"(?m)^old/db" content)) "no orphan left below the marked block")
      (is (str/starts-with? content "node_modules/\n"))
      (is (str/ends-with? content "\n# mine\nbuild/\n") "the user's own lines survive"))
    (testing "and the orphan is gone for good, not merely skipped"
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project})))))))

(deftest legacy-block-is-absorbed-whole-when-an-entry-is-dropped
  ;; the run used to be measured against the CURRENT entry list, so a release
  ;; that removes or renames an entry stranded the lines it no longer knew
  ;; about below the block it had just marked — one of them then duplicated
  ;; inside and outside the managed region, permanently
  (let [project (temp-project)
        target (str (fs/path project ".gitignore"))
        entries setup/gitignore-entries]
    (spit target (str "node_modules/\n\n" legacy-gitignore-block "\n# mine\nbuild/\n"))
    (with-redefs [setup/gitignore-entries
                  (fn [rel] (vec (remove #(str/ends-with? % ".retrievals") (entries rel))))]
      (is (= :updated (:status (setup/ensure-gitignore! {:project project}))))
      (let [content (slurp target)]
        (is (empty? (re-seq #"(?m)^\.claimgraph/db\.retrievals$" content))
            "the dropped entry left with the block, rather than stranding below it")
        (is (= 1 (count (re-seq #"(?m)^\.claimgraph/db\.last-consolidate$" content)))
            "and nothing ended up both inside and outside the managed region"))
      (is (= :unchanged (:status (setup/ensure-gitignore! {:project project})))))))

(deftest managed-block-covers-every-sibling-the-store-writes
  ;; each of these is a real file claimgraph drops next to the db; one missing
  ;; from the block is one that turns up untracked in every project using
  ;; claimgraph and rides the next `git add -A` into someone's history
  (let [entries (set (setup/gitignore-entries ".claimgraph/db"))]
    (doseq [sibling ["/"            ; the LMDB directory itself
                     ".lock"        ; lease/lock-file
                     ".evidence/"   ; evidence/default-dir
                     ".oplog/"      ; oplog/oplog-dir
                     ".retrievals"  ; outcome/log-file
                     ".last-consolidate"
                     ".version"]]   ; store.datalevin/version-file
      (is (contains? entries (str ".claimgraph/db" sibling))
          (str ".claimgraph/db" sibling " is written but not ignored")))))

(deftest this-repo-ignores-what-its-own-setup-manages
  ;; claimgraph is used on claimgraph: an entry the block gained that this
  ;; .gitignore never did shows up here as an untracked artifact in the repo
  ;; the maintainer commits from
  (is (some? repo-root) "the checkout has to be locatable — this check must never no-op")
  (let [ignored (set (map str/trim (str/split-lines (slurp (str (fs/path repo-root ".gitignore"))))))]
    (doseq [entry (setup/gitignore-entries ".claimgraph/db")]
      (is (contains? ignored entry)
          (str entry " is in the managed block but not in this repo's .gitignore")))))

(deftest bb-edn-pins-the-babashka-the-installer-installs
  ;; scripts/setup.sh installs BB_VERSION and reads bb.edn's :min-bb-version as
  ;; the floor it refuses to go under; that same value is what tells someone
  ;; with an older bb why nothing works. Left to drift apart, the installer
  ;; happily produces a bb the project rejects.
  (is (some? repo-root) "the checkout has to be locatable — this check must never no-op")
  (let [installed (second (re-find #"BB_VERSION=\"\$\{BB_VERSION:-([^}\"]+)\}\""
                                   (slurp setup-sh)))
        required (second (re-find #":min-bb-version\s+\"([^\"]+)\""
                                  (slurp (str (fs/path repo-root "bb.edn")))))]
    (is (some? installed) "setup.sh still pins a BB_VERSION")
    (is (some? required) "bb.edn still sets :min-bb-version")
    (is (= installed required))))

;; ---------------------------------------------------------------------------
;; scripts/setup.sh, run for real against a fabricated PATH
;; ---------------------------------------------------------------------------

(def ^:private borrowed-tools
  "The real utilities setup.sh legitimately shells out to. Everything else a
  scenario needs is a stub, so a run that reaches for a downloader it should
  not have needed dies here rather than going to the network."
  ["bash" "awk" "mktemp" "mkdir" "ln" "chmod" "dirname" "readlink" "rm" "sh" "mv" "uname"])

(defn- stub! [path body]
  (spit (str path) (str "#!/bin/sh\n" body "\n"))
  (fs/set-posix-file-permissions path "rwxr-xr-x"))

(defn- fake-bin
  "A PATH directory granting exactly what the scenario allows: the borrowed
  utilities, plus name -> sh-body stubs. curl/tar/unzip are absent unless a
  scenario names them, which is what keeps these tests offline."
  [dir stubs]
  (fs/create-dirs dir)
  (doseq [[n body] stubs] (stub! (fs/path dir n) body))
  ;; borrowed second, and never over a stub: a scenario that pins `uname` owns it
  (doseq [t borrowed-tools :when (not (contains? stubs t))
          :let [p (fs/which t)] :when p]
    (fs/create-sym-link (fs/path dir t) p))
  (str dir))

(defn- run-setup
  "setup.sh in a scrubbed environment — no inherited PATH, so no brew, no curl
  and no real bb leak in from the machine running the suite."
  [path env]
  (let [{:keys [exit out err]} (process/sh {:env (merge {"PATH" path "HOME" path} env)
                                            :out :string :err :string}
                                           (str (fs/which "bash")) setup-sh)]
    {:exit exit :out (str out err)}))

(def ^:private pinned-bb
  ;; the installer's own pin, so these fixtures cannot drift out from under it
  (second (re-find #"BB_VERSION=\"\$\{BB_VERSION:-([^}\"]+)\}\"" (slurp setup-sh))))

(defn- bb-stub [version]
  (str "case \"$1\" in --version) echo \"babashka v" version "\";; *) exit 0;; esac"))

(def ^:private inert-downloaders
  "Present so a scenario about something else cannot be short-circuited by the
  tool guard, inert so a run that does try to download fails here."
  {"curl" "exit 1" "tar" "exit 1" "unzip" "exit 1"})

(deftest brewless-route-only-demands-downloaders-when-it-downloads
  ;; a slim CI image with bb and dtlv baked in downloads nothing: gating the
  ;; whole brew-less route on curl/tar/unzip fails a setup that used to succeed
  (let [t (temp-project)
        bin (fake-bin (fs/path t "bin") {"bb" (bb-stub pinned-bb) "dtlv" "echo help"})
        r (run-setup bin {"USE_BREW" "0" "INSTALL_DIR" (str (fs/path t "target"))})]
    (is (zero? (:exit r)) (:out r))
    (is (not (str/includes? (:out r) "download needs"))
        "nothing was downloaded, so nothing may be demanded"))
  (testing "the guard still fires on the route that does download, naming that route"
    (let [t (temp-project)
          bin (fake-bin (fs/path t "bin") {"dtlv" "echo help"})
          r (run-setup bin {"USE_BREW" "0" "INSTALL_DIR" (str (fs/path t "target"))})]
      (is (= 1 (:exit r)))
      (is (str/includes? (:out r) "the pinned babashka download needs")))))

(deftest claimgraph-dtlv-override-is-honoured-end-to-end
  ;; setup.sh's own remediation says to point $CLAIMGRAPH_DTLV at a dtlv you
  ;; already have and re-run; a script that never reads the variable answers
  ;; that with the identical error. uname is pinned to the one platform with no
  ;; pinned dtlv build, so any attempt to install instead of honouring it fails.
  (let [t (temp-project)
        bin (fake-bin (fs/path t "bin") (merge inert-downloaders
                                               {"bb" (bb-stub pinned-bb)
                                                "uname" "echo \"Darwin x86_64\""}))
        mine (fs/path t "opt" "dtlv")]
    (fs/create-dirs (fs/parent mine))
    (stub! mine "echo help")
    (let [r (run-setup bin {"USE_BREW" "0" "INSTALL_DIR" (str (fs/path t "target"))
                            "CLAIMGRAPH_DTLV" (str mine)})]
      (is (zero? (:exit r)) (:out r))
      (is (str/includes? (:out r) (str "dtlv OK (" mine ")"))
          "the override is what gets verified, not whatever `dtlv` finds"))
    (testing "set but unusable is refused, not silently ignored"
      (let [r (run-setup bin {"USE_BREW" "0" "INSTALL_DIR" (str (fs/path t "target"))
                              "CLAIMGRAPH_DTLV" (str (fs/path t "nowhere"))})]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "$CLAIMGRAPH_DTLV"))))))

(deftest stale-bb-remediation-follows-the-binary-not-the-presence-of-brew
  (let [t (temp-project)
        prefix (fs/path t "brew")
        tools (fake-bin (fs/path t "bin")
                        {"brew" (str "case \"$1\" in --prefix) echo \"" prefix "\";; esac")})
        elsewhere (fake-bin (fs/path t "elsewhere") {"bb" (bb-stub "1.10.0")})]
    (fs/create-dirs (fs/path prefix "bin"))
    (testing "a stale bb outside brew's prefix is not brew's to upgrade"
      (let [r (run-setup (str elsewhere ":" tools) {})]
        (is (= 1 (:exit r)))
        (is (not (str/includes? (:out r) "brew upgrade"))
            "brew upgrade borkdude/brew/babashka answers this bb with 'No available formula'")
        (is (str/includes? (:out r) (str "remove " elsewhere "/bb")))))
    (testing "brew's own stale bb still gets the brew fix"
      (stub! (fs/path prefix "bin" "bb") (bb-stub "1.10.0"))
      (let [r (run-setup tools {})]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "brew upgrade borkdude/brew/babashka"))))))

(deftest bb-version-override-cannot-undercut-bb-edn
  (let [t (temp-project)
        bin (fake-bin (fs/path t "bin") (merge inert-downloaders
                                               {"bb" (bb-stub pinned-bb) "dtlv" "echo help"}))
        base {"USE_BREW" "0" "INSTALL_DIR" (str (fs/path t "target"))}]
    (testing "a pin below bb.edn's floor is refused, not installed"
      ;; it used to pass setup's own check and leave every later claim call
      ;; printing bb's min-version warning — the outcome the check exists for
      (let [r (run-setup bin (assoc base "BB_VERSION" "1.11.0"))]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "bb.edn requires"))))
    (testing "an overridden pin is not reported as something bb.edn said"
      (let [r (run-setup bin (assoc base "BB_VERSION" "99.0.0"))]
        (is (= 1 (:exit r)))
        (is (str/includes? (:out r) "$BB_VERSION set that bar"))
        (is (not (str/includes? (:out r) "99.0.0 is bb.edn's"))
            "bb.edn never said 99.0.0")))))

(deftest skill-honors-bin-and-skills-dir
  (let [project (temp-project)
        skills-dir (str (fs/path project "custom-skills"))]
    (setup/install-skill! {:project project :bin "bin/claim" :skills-dir skills-dir})
    (let [skill (slurp (str (fs/path skills-dir "claimgraph" "SKILL.md")))]
      (is (str/includes? skill "bin/claim facts --entity"))
      (is (not (fs/exists? (fs/path project ".claude"))) "default location untouched"))))

(deftest mcp-registration-merges
  (let [project (temp-project)]
    (spit (str (fs/path project ".mcp.json"))
          "{\"mcpServers\": {\"other\": {\"command\": \"x\"}}}")
    (setup/install-mcp! {:project project :bin "claim"})
    (let [mcp (json/parse-string (slurp (str (fs/path project ".mcp.json"))) true)]
      (is (= {:command "x"} (get-in mcp [:mcpServers :other])) "foreign servers preserved")
      (is (= {:command "claim" :args ["mcp"]} (get-in mcp [:mcpServers :claimgraph]))))))

(deftest failed-step-degrades-to-partial
  (let [r (setup/run! {:project (temp-project) :which fake-which
                       :init-fn (fn [] (throw (ex-info "store backend exploded" {})))})]
    (is (= :partial (:status r)))
    (is (= :error (get-in r [:steps :store :status])))
    (is (= :installed (get-in r [:steps :skill :status]))
        "a failed store init never blocks the file-side steps")))

;; ---------------------------------------------------------------------------
;; The command surfaces, driven end to end against an in-memory store
;; ---------------------------------------------------------------------------

(defn- a-store
  "A seeded in-memory store holding one fact, plus a db path in a temp project
  — the CLI writes its lease and retrieval log beside that path, and neither
  belongs in the machine running the suite."
  []
  (let [s (doto (mem/create) (core/seed!))]
    (core/assert-fact s {:subject "AuthService" :predicate :core/prefers
                         :object "argon2" :object-kind :literal
                         :epistemic :preference :source-type :user-assertion})
    [s (str (fs/path (temp-project) "db"))]))

(def ^:private ms-timestamp
  #"\"recorded-at\":\"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z\"")

(defn- run-cli
  "One CLI command against a given store, its stdout captured. Redefining the
  store opener is what keeps the pod (and a real db) out of this."
  [cmd s opts]
  (with-redefs [cli/open-store (fn [_] s)]
    (str/trim (with-out-str (cmd {:opts opts})))))

(deftest init-reconciles-the-vocabulary-of-a-store-that-already-has-one
  ;; seed! reconciles a predicate row to the seed's shape INCLUDING removals,
  ;; but open-store only seeds an empty store — so until `claim init` re-seeds
  ;; unconditionally the reconciliation is unreachable, and an upgraded store
  ;; keeps whatever the old vocabulary said. That is how the non-bijective
  ;; :inverse-of survived its own removal.
  (let [[s db] (a-store)]
    (store/-register-predicate s (assoc (store/-get-predicate s :core/defined-in)
                                        :inverse-of :core/contains))
    (is (= :core/contains (:inverse-of (store/-get-predicate s :core/defined-in)))
        "a store carrying the pre-removal row")
    (run-cli cli/cmd-init s {:db db})
    (is (nil? (:inverse-of (store/-get-predicate s :core/defined-in)))
        "`claim init` is the documented upgrade path and must actually reconcile")))

(deftest dump-command-writes-the-artifact-it-reports
  ;; the header requirement was pinned only at wire/dump-lines: nothing
  ;; asserted that the command a user runs puts one in the file, so a dump
  ;; command that went back to plain cheshire failed three unit assertions and
  ;; nothing that looked like a dump
  (let [[s db] (a-store)
        out (str (fs/path (fs/parent db) "graph.dump.jsonl"))
        report (json/parse-string (run-cli cli/cmd-dump s {:db db :out out}) true)
        lines (str/split-lines (slurp out))]
    (testing "the file leads with the header a loader gates on"
      (is (= (version/dump-header) (json/parse-string (first lines) true))))
    (testing "timestamps reach the file with their milliseconds"
      (is (some #(re-find ms-timestamp %) (rest lines))
          "a second-granularity dump loses intervals shorter than a second"))
    (testing "the counts describe the file, unambiguously"
      (is (= (count lines) (:lines report)) "what `wc -l` will say")
      (is (= (dec (count lines)) (:records report)) "the graph, header excluded")
      (is (= version/format-version (:format report))))
    (testing "and load reads back what dump wrote, header and all"
      (let [fresh (mem/create)
            r (json/parse-string (run-cli cli/cmd-load fresh {:db db :file out}) true)]
        (is (= "loaded" (:status r)))
        (is (= version/format-version (:format r)) "the header was read, not restored as a record")
        (is (= 1 (:facts r)))
        (is (= (get-in (core/get-facts s {:entity "AuthService"}) [:facts 0 :recorded-at])
               (get-in (core/get-facts fresh {:entity "AuthService"}) [:facts 0 :recorded-at]))
            "the round trip is exact to the millisecond")))))

(deftest mcp-and-the-cli-answer-with-the-same-bytes
  ;; two surfaces onto one store: an agent that reads a fact over MCP and
  ;; diffs it against the CLI's answer (or the committed dump) must not find a
  ;; different recorded-at because one surface truncated the milliseconds
  (let [[s db] (a-store)
        over-mcp (get-in (mcp/handle s db {:id 1 :method "tools/call"
                                           :params {:name "memory_facts"
                                                    :arguments {:entity "AuthService"}}})
                         [:result :content 0 :text])
        over-cli (run-cli cli/cmd-facts s {:db db :entity "AuthService"})
        ;; :effective-confidence is decayed at read time and moves between the
        ;; two calls by design; every other field is the same fact twice
        stable #(update (json/parse-string % true) :facts
                        (partial mapv (fn [f] (dissoc f :effective-confidence))))]
    (is (re-find ms-timestamp over-mcp) "MCP truncated the timestamp")
    (is (= (re-find ms-timestamp over-cli) (re-find ms-timestamp over-mcp)))
    (is (= (stable over-cli) (stable over-mcp))))
  (testing "and the server names the release, not a literal that drifts from it"
    (let [[s db] (a-store)]
      (is (= {:name "claimgraph" :version version/release}
             (get-in (mcp/handle s db {:id 1 :method "initialize" :params {}})
                     [:result :serverInfo]))))))

(deftest version-marks-a-checkout-it-cannot-vouch-for
  (testing "the payload carries the marker only when there is something to mark"
    (is (nil? (:dirty (version/describe "abc1234" false))))
    (is (true? (:dirty (version/describe "abc1234" true))))
    (is (nil? (:dirty (version/describe nil true))) "no checkout, nothing to vouch for"))
  (testing "and the marker is read off this checkout rather than assumed"
    (is (fs/exists? (fs/path repo-root ".git"))
        "the suite runs from a checkout — this check must never no-op")
    (let [{:keys [sha dirty]} (#'cli/source-checkout)
          porcelain (:out (process/sh {:dir repo-root :out :string :err :string}
                                      "git" "status" "--porcelain"))]
      (is (re-matches #"[0-9a-f]{40}" (str sha)) "HEAD, from claimgraph's own checkout")
      (is (= (not (str/blank? porcelain)) dirty)
          "uncommitted or untracked source is exactly what makes the sha unquotable"))))

(deftest repo-dogfood-skill-is-in-sync-with-the-template
  ;; the repo's own .claude/skills/claimgraph/SKILL.md is the template
  ;; rendered for its repo-local bin — regenerate via `claim setup` here
  (let [dogfood (fs/path (or repo-root ".") ".claude" "skills" "claimgraph" "SKILL.md")]
    (is (fs/exists? dogfood) "the dogfood skill is checked in — this check must never no-op")
    (is (= (setup/skill-content "bin/claim") (slurp (str dogfood))))))
