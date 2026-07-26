(ns claimgraph.config-test
  "The static surfaces: the configuration precedence chain (over passed
  opts/env/config maps — no real environment, no real config file), the wire
  encoding every artifact is written with, and the version identity every
  artifact is stamped with.

  The last of those reaches past the pure surfaces, because a format stamp
  nobody writes to disk is not a format stamp. The final two sections open
  real stores — Datalevin gated on the pod exactly as claimgraph.core-test
  gates it, in-memory always — to check that a store stamps itself, refuses a
  stamp it cannot read or one from the future, and treats a re-registration
  identically in both backends: a curated row redefined, a staging row
  amended. Registry semantics live in two implementations, so every one of
  those runs against both."
  (:require [babashka.fs :as fs]
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
         (config/resolve-setting :consolidate-days
                                 {:opts {} :env {"CLAIMGRAPH_CONSOLIDATE_DAYS" "3"}})))
  (is (= {:value 5 :source :config}
         (config/resolve-setting :consolidate-days
                                 {:opts {} :env {} :config {:consolidate-days 5}}))
      "a JSON number needs no coercion"))

(deftest opt-key-mapping
  (testing "--dir is the CLI spelling of the notes-dir setting"
    (is (= {:value "/n" :source :flag}
           (config/resolve-setting :notes-dir {:opts {:dir "/n"} :env {} :config nil})))
    (is (= {:dir "/cfg/notes"}
           (config/merge-defaults {} {:env {} :config {:notes-dir "/cfg/notes"}}
                                  [:notes-dir]))
        "merge-defaults fills the option key the commands read")))

(deftest merge-defaults-respects-flags-and-skips-static-defaults
  (let [ctx {:env {"CLAIMGRAPH_HARNESS" "codex"} :config {:harness "claude-code"}}]
    (is (= {:harness "codex"} (config/merge-defaults {} ctx [:harness]))
        "env layer fills an absent flag")
    (is (= {:harness "x"} (config/merge-defaults {:harness "x"} ctx [:harness]))
        "an explicit flag is never overwritten"))
  (is (= {} (config/merge-defaults {} {:env {} :config nil} [:harness :consolidate-days]))
      "static defaults stay owned by each consumer — merge fills nothing"))

(deftest config-file-path-override
  (is (= "/elsewhere/cfg.json"
         (config/config-file-path {"CLAIMGRAPH_CONFIG" "/elsewhere/cfg.json"})))
  (is (= ".claimgraph/config.json" (config/config-file-path {}))))

(deftest read-config-file-roundtrip
  (let [dir (fs/create-temp-dir {:prefix "claimgraph-config-test"})
        path (str (fs/path dir "config.json"))]
    (is (nil? (config/read-config-file path)) "absent file reads as nil")
    (spit path "{\"harness\": \"codex\", \"consolidate-days\": 3}")
    (is (= {:harness "codex" :consolidate-days 3} (config/read-config-file path)))))

;; ---------------------------------------------------------------------------
;; Unrecognised config keys
;; ---------------------------------------------------------------------------

(deftest a-key-claimgraph-never-reads-is-named-not-ignored
  (is (= [] (config/unknown-keys {:db "/d" :notes-dir "/n" :consolidate-days 3}))
      "every registry key is recognised")
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
        written {:harness "codex" :consolidate-days 3
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
;; The store's own format stamp
;; ---------------------------------------------------------------------------

(defn- temp-db-path []
  (str (fs/path (fs/temp-dir) (str "claimgraph-stamp-" (random-uuid)))))

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
