(ns claimgraph.oplog-test
  "The append-only effect log and its reconciliation: two writers on
  separate machines, syncing nothing but log files, converging on one graph
  with their disagreements surfaced rather than merged away."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.oplog :as oplog]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(defn- db-path []
  (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-oplog-test"}) "db")))

(defn- machine
  "A writer: its own store, db path, and effect log."
  [writer-name]
  (let [db (db-path)]
    (fs/create-dirs (oplog/oplog-dir db))
    (spit (str (fs/path (oplog/oplog-dir db) "writer")) writer-name)
    (let [s (oplog/logged-store (doto (mem/create) (core/seed!)) db)]
      {:db db :store s})))

(defn- log-lines
  "One writer's log, parsed."
  [db writer]
  (->> (str/split-lines (slurp (str (fs/path (oplog/oplog-dir db) (str writer ".jsonl")))))
       (remove str/blank?)
       (mapv wire/parse-string)))

(defn- deliver-line!
  "One raw line arriving from another machine, exactly as a syncer would
  leave it — the only way to write effects a real local store cannot produce
  (a verb from a newer build, a hole in a sequence, an out-of-order clock)."
  [db writer line]
  (spit (str (fs/path (oplog/oplog-dir db) (str writer ".jsonl")))
        (str (wire/generate-string
              (merge {:writer writer :format version/format-version} line))
             "\n")
        :append true))

(defn- deliver-raw!
  "One line exactly as given, envelope and all — for logs no build of
  claimgraph writes: a format-0 line from before the envelope existed, or a
  peer's line whose fields arrived the wrong shape."
  [db writer line]
  (spit (str (fs/path (oplog/oplog-dir db) (str writer ".jsonl")))
        (str (wire/generate-string line) "\n")
        :append true))

(defn- remote-entity [id entity-name]
  {:record "entity" :id id :name entity-name :scope "project"})

(defn- warnings-of [r kind]
  (filterv #(= kind (:kind %)) (:warnings r)))

(defn- applied-state [db]
  (wire/parse-string (slurp (str (fs/path (oplog/oplog-dir db) "applied.json")))))

(defn- sync-log!
  "What git/rsync/Syncthing would do: copy one writer's log file to the
  other machine's oplog directory."
  [from to]
  (doseq [f (fs/glob (oplog/oplog-dir (:db from)) "*.jsonl")]
    (fs/copy f (fs/path (oplog/oplog-dir (:db to)) (fs/file-name f))
             {:replace-existing true})))

(defn- fact-triples
  "Triples under NORMALIZED names: each machine keeps whichever display name
  it saw first (the other arrives as an alias), so convergence is about
  identity and validity, not labels."
  [s]
  (set (map (fn [f] [(logic/normalize-entity-name (get-in f [:subject :name]))
                     (:predicate f)
                     (or (some-> (get-in f [:object-ref :name])
                                 logic/normalize-entity-name)
                         (:object-lit f))
                     (some? (:t-invalid f))])
            (store/-all-facts (oplog/inner-store s)))))

(deftest writes-append-effects
  (let [{:keys [db store]} (machine "w-a")]
    (core/assert-fact store {:subject "svc" :predicate :core/prefers
                             :object "argon2" :object-kind :literal})
    (let [log (fs/path (oplog/oplog-dir db) "w-a.jsonl")]
      (is (fs/exists? log))
      (let [lines (str/split-lines (slurp (str log)))]
        (is (some #(str/includes? % "insert-fact") lines))
        (is (some #(str/includes? % "ensure-entity") lines))))))

(deftest a-registry-field-crosses-beside-the-others-not-instead-of-them
  ;; The effect payload is the whole row, so a new registry field rides along
  ;; for free — which is exactly why nothing would notice it stopping. A shape
  ;; that crosses as a string, or not at all, leaves the other machine
  ;; screening lessons as data with no error anywhere to say so.
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/register-predicate (:store a) {:id :x/lesson-learned :object-shape :prose
                                         :definition "a lesson, in sentences"})
    (let [effect (first (filter #(= "register-predicate" (:t %))
                                (log-lines (:db a) "w-a")))]
      (is (= "prose" (get-in effect [:predicate :object-shape]))
          "the field is a sibling of the ones that were always there")
      (is (= "either" (get-in effect [:predicate :object-kind]))
          "which it did not displace"))
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (is (= :prose (:object-shape (store/-get-predicate (:store b) :x/lesson-learned)))
        "and lands a keyword on the far side, not the string JSON made of it")))

(deftest two-writers-reconcile
  (let [a (machine "w-a")
        b (machine "w-b")]
    ;; machine A: a commitment and a preference, plus an entity rename
    (core/assert-fact (:store a) {:subject "api-layer" :predicate :core/decided-against
                                  :object "GraphQL" :object-kind :literal
                                  :epistemic :commitment :source-type :decision-record})
    (core/assert-fact (:store a) {:subject "AuthService" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    ;; machine B, offline, knowing nothing of A: the same claim under a
    ;; sloppier name, a contradicting stance, and something only B knows
    (core/assert-fact (:store b) {:subject "auth-service" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "api-layer" :predicate :core/prefers
                                  :object "GraphQL" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe"})

    (sync-log! b a)
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (testing "B's effects arrived"
        (is (pos? (get-in r [:effects :applied])))
        (is (empty? (get-in r [:effects :errors])))
        (is (seq (:facts (core/get-facts (:store a) {:entity "billing"})))))

      (testing "the same claim under a different name collapsed by identity, not luck"
        (let [argon (filter #(= "argon2" (:object-lit %))
                            (:facts (core/get-facts (:store a) {:entity "AuthService"
                                                                :include-invalidated true})))
              live (remove :t-invalid argon)
              retired (filter :t-invalid argon)]
          (is (= 1 (count live))
              "one live copy; B's auth-service resolved to A's AuthService")
          (is (= 1 (count retired))
              "the duplicate closed, not erased")
          (testing "and the collapse says so as structure, not only in prose"
            ;; A nil kind here is indistinguishable from a write by a build that
            ;; predates the kinds, and a retired row naming no counterpart reads
            ;; as deleted for no reason a year later.
            (is (= :reconcile-duplicate (:invalidation-kind (first retired))))
            (is (= (:id (first live)) (:successor (first retired)))
                "which twin survived is not recoverable from the graph afterwards")
            (is (str/includes? (:invalidation-reason (first retired)) "across writers")
                "the sentence stays for the human reading `claim history`"))))

      (testing "the contradiction neither writer could see is queued for the judge"
        (is (pos? (:sweep-candidates r)))))

    (testing "reconcile is idempotent"
      (let [again (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
        (is (zero? (get-in again [:effects :total])))
        (is (zero? (:duplicates-collapsed again)))))

    (testing "syncing the other way converges the two machines"
      (sync-log! a b)
      (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
      (is (= (fact-triples (:store a)) (fact-triples (:store b)))
          "same identities, same validity, on both machines")
      (is (= "auth-service"
             (get-in (core/resolve-entity (:store b) {:name "AuthService"})
                     [:entity :name]))
          "B keeps its own display name; A's name arrived as an alias"))))

(deftest curation-effects-replay
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store a) {:subject "shoply.auth" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    (core/assert-fact (:store b) {:subject "unrelated" :predicate :core/depends-on
                                  :object "x"})
    ;; A renames; the old name survives as an alias
    (core/rename-entity (:store a) {:from "shoply.auth" :to "shoply.identity"})
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (testing "the rename crossed machines"
      (is (= "shoply.identity"
             (get-in (core/resolve-entity (:store b) {:name "shoply.auth"})
                     [:entity :name]))))))

;; ---------------------------------------------------------------------------
;; The line: what every log commits to on disk
;; ---------------------------------------------------------------------------

(deftest every-line-carries-its-envelope
  (let [{:keys [db store]} (machine "w-a")]
    (core/assert-fact store {:subject "svc" :predicate :core/prefers
                             :object "argon2" :object-kind :literal})
    (let [lines (log-lines db "w-a")]
      (is (seq lines))
      (testing "identity travels in the line, so the file can be renamed or restored"
        (is (every? #(= "w-a" (:writer %)) lines)))
      (testing "a reader can tell what shape it is holding before it parses it"
        (is (every? #(= version/format-version (:format %)) lines)))
      (testing "seq is dense from 1 — the thing a high-water mark means"
        (is (= (range 1 (inc (count lines))) (map :seq lines))))
      (testing "the clock never repeats within a writer"
        (is (apply < (map :hlc lines)))))))

(deftest an-invalidate-line-keeps-its-reason-a-sentence
  ;; The line is on disk forever and read by builds that are not this one. A
  ;; reader from before the kinds existed reads :reason and nothing else, so
  ;; nesting {:kind :successor :reason} under it hands that reader a MAP for the
  ;; field it treats as prose — and it applies the line, reports :applied and
  ;; warns about nothing (on Datalevin the map is coerced into a string column
  ;; and kept forever, which is what `claim history` then prints). Adding two
  ;; fields is additive and correctly does not move the format version, so the
  ;; gate cannot catch it either: the shape has to be right instead.
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store a) {:subject "svc" :predicate :core/has-version
                                  :object "1.0" :t-valid #inst "2026-01-01"})
    (core/assert-fact (:store a) {:subject "svc" :predicate :core/has-version
                                  :object "2.0" :t-valid #inst "2026-03-01"})
    (let [line (first (filter #(= "invalidate" (:t %)) (log-lines (:db a) "w-a")))
          successor (->> (:facts (core/get-facts (:store a) {:entity "svc"}))
                         first :id)]
      (is (string? (:reason line))
          "an old reader's only field still holds a sentence")
      (is (= "superseded" (:kind line)) "the kind is a sibling of it")
      (is (= successor (:successor line)) "so is the successor"))
    (testing "and a reader that knows the siblings prefers them"
      (sync-log! a b)
      (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
      (let [retired (->> (:facts (core/get-facts (:store b) {:entity "svc"
                                                             :include-invalidated true}))
                         (filter :t-invalid)
                         first)
            live (->> (:facts (core/get-facts (:store b) {:entity "svc"})) first)]
        (is (= :superseded (:invalidation-kind retired))
            "a keyword after the JSON round trip; a string kind matches no reader's set")
        (is (= (:id live) (:successor retired)))
        (is (string? (:invalidation-reason retired)))))))

(deftest a-caller-with-no-kind-still-logs-its-sentence
  ;; The bare-string shape store/-invalidate documents: what every caller passed
  ;; before kinds existed, and what a caller not yet taught its kind still
  ;; passes. It is what lets the producers be converted one at a time instead of
  ;; in one commit that has to be right everywhere, so it is worth an assertion
  ;; rather than a call whose result nothing looks at.
  (let [{:keys [db store]} (machine "w-a")
        inner (oplog/inner-store store)
        fid (get-in (core/assert-fact store {:subject "svc"
                                             :predicate :core/deployed-via
                                             :object "Heroku" :object-kind :literal})
                    [:fact :id])]
    (store/-invalidate store fid (java.util.Date.) "migrated to Fly.io")
    (let [line (first (filter #(= "invalidate" (:t %)) (log-lines db "w-a")))]
      (is (= "migrated to Fly.io" (:reason line)))
      (is (nil? (:kind line)) "nothing is invented to fill the sibling")
      (is (nil? (:successor line))))
    (is (= "migrated to Fly.io"
           (:invalidation-reason (first (store/-select-facts inner {:ids [fid]}))))
        "and the fact records why it closed, structure or no structure")))

(deftest an-invalidate-line-from-an-older-peer-keeps-its-reason
  ;; The bare-string shape store/-invalidate documents, arriving from a writer
  ;; that has no kinds to send: the sentence is all there is, and dropping it
  ;; would leave the crossed fact retired for no recorded reason at all.
  (let [a (machine "w-a")
        inner (oplog/inner-store (:store a))
        fid (get-in (core/assert-fact (:store a) {:subject "svc"
                                                  :predicate :core/deployed-via
                                                  :object "Heroku"
                                                  :object-kind :literal})
                    [:fact :id])]
    (deliver-line! (:db a) "w-b" {:t "invalidate" :seq 1 :hlc 1000
                                  :fact-id fid :at 1767225600000
                                  :reason "superseded by f-elsewhere"})
    (let [r (oplog/reconcile! inner (:db a))
          f (first (store/-select-facts inner {:ids [fid]}))]
      (is (= 1 (get-in r [:effects :applied])))
      (is (some? (:t-invalid f)))
      (is (= "superseded by f-elsewhere" (:invalidation-reason f))
          "the sentence is recorded verbatim, not dropped for carrying no structure")
      (is (nil? (:invalidation-kind f))
          "and nothing is invented: no kind was sent")
      (is (nil? (:successor f))))))

(deftest entity-type-survives-the-crossing
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store a) {:subject "AuthService" :subject-type :service
                                  :predicate :core/prefers :object "argon2"
                                  :object-kind :literal})
    (testing "the record kind does not sit on the field an entity already owns"
      (let [ent (->> (log-lines (:db a) "w-a") (keep :entity) first)]
        (is (= "entity" (:record ent)))
        (is (= "service" (:type ent))
            "the entity's own type, not the word \"entity\" written over it")))
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (is (= :service (get-in (core/resolve-entity (:store b) {:name "AuthService"})
                            [:entity :type]))
        "a typed entity arrives typed; untyping it disarms every type-guarded read")))

(deftest an-entity-typed-entity-crosses-typed
  ;; Entity types are free-form (core/ensure-entity keywordizes whatever the
  ;; caller passed; there is no vocabulary), so :entity is as legal a type as
  ;; :service — and it is the one value the format-0 compatibility path used
  ;; to eat.
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store a) {:subject "claimgraph" :subject-type :entity
                                  :predicate :core/prefers :object "argon2"
                                  :object-kind :literal})
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (is (= :entity (get-in (core/resolve-entity (:store b) {:name "claimgraph"})
                           [:entity :type]))
        "a type that happens to spell the record kind is still the entity's own type")))

(deftest a-format-0-line-still-drops-the-old-discriminator
  ;; The compatibility path this gates still has to work: before the envelope
  ;; existed the record kind was written into :type, so a :type reading
  ;; "entity" there is the field having been eaten, not a type.
  (let [a (machine "w-a")]
    (deliver-raw! (:db a) "w-b" {:t "ensure-entity" :writer "w-b" :seq 1 :hlc 1000
                                 :entity {:type "entity" :id "e-remote"
                                          :name "billing" :scope "project"}})
    (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))
    (is (nil? (get-in (core/resolve-entity (:store a) {:name "billing"})
                      [:entity :type]))
        "an untyped entity, not one typed with the word the old discriminator left behind")))

(deftest millisecond-timestamps-cross-machines
  (let [a (machine "w-a")
        b (machine "w-b")
        t-valid (java.util.Date. 1751328000123)]
    (core/assert-fact (:store a) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe" :t-valid t-valid})
    (sync-log! a b)
    (oplog/reconcile! (oplog/inner-store (:store b)) (:db b))
    (let [f (first (:facts (core/get-facts (:store b) {:entity "billing"})))]
      (is (= (.getTime t-valid) (some-> ^java.util.Date (:t-valid f) .getTime))
          "seconds-only timestamps collapse a claim that lived under a second into no interval at all"))))

(deftest applied-state-is-stamped-and-gated
  (let [a (machine "w-a")]
    (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))
    (is (= version/format-version (:format (applied-state (:db a)))))
    (testing "a state file from a newer claimgraph is refused, never guessed at"
      (spit (str (fs/path (oplog/oplog-dir (:db a)) "applied.json"))
            (wire/generate-string {:format (inc version/format-version) :high-water {}}))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"newer claimgraph"
                            (oplog/reconcile! (oplog/inner-store (:store a)) (:db a)))))))

;; ---------------------------------------------------------------------------
;; The high-water mark: what it is allowed to move past
;; ---------------------------------------------------------------------------

(deftest unknown-verbs-are-held-never-skipped
  (let [a (machine "w-a")]
    ;; a newer writer's log: one verb this build has no reader for, one it does
    (deliver-line! (:db a) "w-b" {:t "teleport" :seq 1 :hlc 1000})
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 2 :hlc 1001
                                  :entity (remote-entity "e-remote" "billing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= 1 (get-in r [:effects :applied])))
      (is (= [["w-b" "teleport" 1]]
             (mapv (juxt :writer :t :count) (get-in r [:effects :unknown])))
          "the verb is named in the report, not counted as progress")
      (is (= 1 (:held r)))
      (is (some #(= :held (:kind %)) (:warnings r))))
    (let [st (applied-state (:db a))]
      (is (= 0 (get-in st [:high-water :w-b]))
          "the mark cannot pass an effect this build could not apply")
      (is (= [2] (get-in st [:applied-beyond :w-b]))
          "what did apply is remembered by number, so the retry cannot double it"))
    (testing "the next pass retries the effect it could not read, and only that"
      (let [again (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
        (is (= 1 (get-in again [:effects :total])))
        (is (= 0 (get-in again [:effects :applied])))))))

(deftest deferred-effects-are-retried-not-lost
  (let [a (machine "w-a")
        remote-fact {:record "fact" :id "f-remote"
                     :subject {:id "e-remote" :name "billing" :scope "project"}
                     :predicate "core/depends-on" :object-kind "literal"
                     :object-lit "stripe" :confidence 0.8 :epistemic "observation"
                     :scope "project" :source-type "user-assertion"
                     :t-valid "2026-01-01T00:00:00.000Z"
                     :recorded-at "2026-01-01T00:00:00.000Z"}]
    ;; the invalidation carries the older clock, so it is replayed before the
    ;; insert it closes: a prerequisite that has not landed yet, which is the
    ;; everyday shape of a half-synced log
    (deliver-line! (:db a) "w-b" {:t "invalidate" :seq 1 :hlc 1000
                                  :fact-id "f-remote" :at 1767225600000
                                  :reason "superseded on the other machine"})
    (deliver-line! (:db a) "w-b" {:t "insert-fact" :seq 2 :hlc 1001 :fact remote-fact})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= 1 (get-in r [:effects :applied])))
      (is (= 1 (get-in r [:effects :deferred])))
      (is (= 0 (get-in (applied-state (:db a)) [:high-water :w-b]))))
    (testing "the next pass finds the prerequisite and lands the effect"
      (let [again (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))
            state (applied-state (:db a))]
        (is (= 1 (get-in again [:effects :applied])))
        (is (= 2 (get-in state [:high-water :w-b])))
        (is (empty? (get-in state [:applied-beyond :w-b]))
            "the hole closed, so nothing is remembered out of band any more")
        (is (some? (:t-invalid (first (store/-select-facts (oplog/inner-store (:store a))
                                                          {:ids ["f-remote"]})))))))))

(deftest an-effect-that-can-never-land-is-visible-and-can-be-given-up
  ;; `claim load` applies a dump through the raw store, so nothing it restored
  ;; ever entered a log. Curating one of those facts afterwards logs an effect
  ;; naming an id no peer can obtain: the prerequisite is not late, it is never
  ;; coming, and a mark pinned at 0 forever holds everything behind it.
  (let [a (machine "w-a")
        inner (oplog/inner-store (:store a))]
    (deliver-line! (:db a) "w-b" {:t "invalidate" :seq 1 :hlc 1000
                                  :fact-id "f-never" :at 1767225600000
                                  :reason "superseded on the other machine"})
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 2 :hlc 1001
                                  :entity (remote-entity "e-remote" "billing")})
    (dotimes [_ 3] (oplog/reconcile! inner (:db a)))
    (let [r (oplog/reconcile! inner (:db a))]
      (is (= [{:writer "w-b" :seq 1 :why :deferred :passes 4}]
             (mapv #(select-keys % [:writer :seq :why :passes]) (warnings-of r :held)))
          "how long it has been stuck is in the report, not left to be inferred")
      (is (str/includes? (:hint r) "give up on")
          "and the report says what a human can do about it"))
    (testing "the way out moves the mark instead of pinning it at 0 forever"
      (let [r (oplog/reconcile! inner (:db a) {:abandon-deferred true})]
        (is (= [{:writer "w-b" :seq 1 :t "invalidate"}] (:abandoned r)))
        (is (= 2 (get-in (applied-state (:db a)) [:high-water :w-b]))
            "the effects stacked up behind it stop waiting on it")))
    (testing "what was given up is written down, not quietly forgotten"
      (is (= [{:seq 1 :t "invalidate"}]
             (mapv #(select-keys % [:seq :t])
                   (get-in (applied-state (:db a)) [:abandoned :w-b])))))
    (testing "a fresh deferral is never dropped by the same flag"
      (deliver-line! (:db a) "w-b" {:t "invalidate" :seq 3 :hlc 1002
                                    :fact-id "f-also-never" :at 1767225600000
                                    :reason "another one"})
      (let [r (oplog/reconcile! inner (:db a) {:abandon-deferred true})]
        (is (empty? (:abandoned r)))
        (is (= 1 (get-in r [:effects :deferred]))
            "half-synced is the everyday case; giving up on it on sight would lose it")))))

(deftest a-line-with-no-seq-is-named-not-swallowed
  (let [a (machine "w-a")]
    (deliver-raw! (:db a) "w-b" {:t "ensure-entity" :writer "w-b" :hlc 1000
                                 :format version/format-version
                                 :entity (remote-entity "e-remote" "billing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (zero? (get-in r [:effects :applied]))
          "an effect no high-water mark can record is an effect that would replay forever")
      (is (= [{:writer "w-b" :why :no-seq :count 1}]
             (mapv #(select-keys % [:writer :why :count]) (warnings-of r :bad-envelope)))
          "reconcile names what it could not apply; this one used to vanish between the counts")
      (is (str/includes? (:hint r) "envelope")))))

(deftest a-malformed-line-cannot-take-down-the-pass
  (let [a (machine "w-a")]
    ;; one peer's envelope arrives the wrong shape — hand-edited, half-written,
    ;; or produced by something that is not claimgraph
    (deliver-raw! (:db a) "w-b" {:t "ensure-entity" :writer "w-b" :seq "1"
                                 :hlc "1000" :format "2"
                                 :entity (remote-entity "e-bad" "nonsense")})
    ;; and one whose writer id is not even text: attribution is grouped and
    ;; sorted as text everywhere downstream
    (deliver-raw! (:db a) "w-numeric" {:t "ensure-entity" :writer 123 :seq 1 :hlc 1000
                                       :format version/format-version
                                       :entity (remote-entity "e-num" "numbering")})
    (deliver-line! (:db a) "w-c" {:t "ensure-entity" :seq 1 :hlc 1000
                                  :entity (remote-entity "e-remote" "billing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= 2 (get-in r [:effects :applied]))
          "one peer's bad line is not every peer's problem")
      (is (= ["123" "w-b" "w-c"] (:writers r))
          "a writer id that arrived as a number is one writer, not a comparison error")
      (is (= [{:writer "w-b" :why :bad-seq :count 1}]
             (mapv #(select-keys % [:writer :why :count]) (warnings-of r :bad-envelope))))
      (is (= 1 (get-in (applied-state (:db a)) [:high-water :w-c]))
          "the writers whose lines were fine still advance"))))

(deftest two-effects-under-one-seq-are-reported-not-dropped
  (let [a (machine "w-a")]
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 1 :hlc 1000
                                  :entity (remote-entity "e-one" "billing")})
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 1 :hlc 1001
                                  :entity (remote-entity "e-two" "invoicing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= [{:writer "w-b" :seq 1 :count 2}]
             (mapv #(select-keys % [:writer :seq :count])
                   (warnings-of r :duplicate-seq)))
          "a forked numbering is a reported fork, not one effect quietly winning")
      (is (= 1 (get-in r [:effects :applied]))
          "only the first in canonical order applies — the mark can record one"))))

(deftest one-log-under-two-names-stays-one-history
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store b) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe"})
    (sync-log! b a)
    (fs/copy (fs/path (oplog/oplog-dir (:db a)) "w-b.jsonl")
             (fs/path (oplog/oplog-dir (:db a)) "from-the-laptop.jsonl"))
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= (count (log-lines (:db b) "w-b")) (get-in r [:effects :total]))
          "the same log under two names is one writer's history, and reinforce is not idempotent")
      (is (empty? (warnings-of r :duplicate-seq))
          "identical lines are a copy, not a fork"))))

(deftest lines-from-a-newer-format-are-held
  (let [a (machine "w-a")]
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 1 :hlc 1000
                                  :format (inc version/format-version)
                                  :entity (remote-entity "e-remote" "billing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (zero? (get-in r [:effects :applied])))
      (is (= 1 (:held r)))
      (is (= [:future-format] (mapv :why (get-in r [:effects :unknown]))))
      (is (str/includes? (:hint r) "upgrade"))
      (is (zero? (get-in (applied-state (:db a)) [:high-water :w-b]))
          "an upgrade recovers these effects; skipping past them would not"))))

(deftest a-hole-in-a-log-is-reported-not-stepped-over
  (let [a (machine "w-a")]
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 1 :hlc 1000
                                  :entity (remote-entity "e-one" "billing")})
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 3 :hlc 1002
                                  :entity (remote-entity "e-three" "invoicing")})
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))
          state (applied-state (:db a))]
      (is (= 2 (get-in r [:effects :applied])))
      (is (= [{:writer "w-b" :missing 2}]
             (->> (:warnings r)
                  (filter #(= :seq-gap (:kind %)))
                  (mapv #(select-keys % [:writer :missing])))))
      (is (= 1 (get-in state [:high-water :w-b]))
          "the mark stops at the hole; the sequence is the promise that it can move")
      (is (= [3] (get-in state [:applied-beyond :w-b]))))))

;; ---------------------------------------------------------------------------
;; Identity, numbering, and losing the log
;; ---------------------------------------------------------------------------

(deftest a-writer-never-replays-its-own-effects
  (let [a (machine "w-a")]
    (core/assert-fact (:store a) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe"})
    ;; a backup, a restore under a new name, a syncer that renamed the file:
    ;; the effects inside are still this machine's own history
    (fs/copy (fs/path (oplog/oplog-dir (:db a)) "w-a.jsonl")
             (fs/path (oplog/oplog-dir (:db a)) "w-a-backup-2026.jsonl"))
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (empty? (:writers r)))
      (is (zero? (get-in r [:effects :total]))))))

(deftest a-renamed-log-keeps-its-writer
  (let [a (machine "w-a")
        b (machine "w-b")]
    (core/assert-fact (:store b) {:subject "billing" :predicate :core/depends-on
                                  :object "stripe"})
    (fs/copy (fs/path (oplog/oplog-dir (:db b)) "w-b.jsonl")
             (fs/path (oplog/oplog-dir (:db a)) "from-the-laptop.jsonl"))
    (let [r (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
      (is (= ["w-b"] (:writers r)) "attribution follows the line, not the filename")
      (is (pos? (get-in r [:effects :applied]))))
    (testing "the same log under its own name is not a second writer"
      (sync-log! b a)
      (let [again (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))]
        (is (zero? (get-in again [:effects :total])))))))

(deftest the-writer-file-outranks-the-environment
  ;; In a subprocess because the variable has to be set before the process
  ;; starts — which is also the only way anybody ever hits this.
  (let [db (db-path)]
    (fs/create-dirs (oplog/oplog-dir db))
    (spit (str (fs/path (oplog/oplog-dir db) "writer")) "w-a")
    (let [{:keys [out err]}
          (p/sh {:out :string :err :string
                 :extra-env {"CLAIMGRAPH_WRITER" "w-elsewhere"}}
                "bb" "-e"
                (str "(require '[claimgraph.oplog :as o]) (println (o/writer-id! \"" db "\"))"))]
      (is (= "w-a" (str/trim out))
          "one machine, one writer: an env var cannot split a history in two")
      (is (str/includes? err "ignored")
          "and the machine says which id it is actually writing as"))))

(deftest a-truncated-log-never-renumbers
  (let [{:keys [db store]} (machine "w-a")]
    (core/assert-fact store {:subject "billing" :predicate :core/depends-on
                             :object "stripe"})
    (let [before (log-lines db "w-a")
          high (apply max (map :seq before))
          warned (java.io.StringWriter.)]
      ;; compaction, rotation, a half-restored backup: the file loses its head
      (spit (str (fs/path (oplog/oplog-dir db) "w-a.jsonl"))
            (str (str/join "\n" (map wire/generate-string (drop 1 before))) "\n"))
      (let [reopened (binding [*err* warned] (oplog/logged-store (mem/create) db))]
        (store/-ensure-entity reopened {:name "later" :scope "project"}))
      (is (= (inc high) (:seq (last (log-lines db "w-a"))))
          "numbering resumes above the highest seq ever emitted, not at the line count")
      (is (str/includes? (str warned) "dense from 1")
          "and the broken invariant is said out loud"))))

(deftest two-stores-over-one-db-never-hand-out-one-seq
  ;; `claim mcp` holds one store open for a whole session under no lease, and
  ;; anything else in the process opens its own over the same db. Two counters
  ;; over one log hand out one number twice, and a duplicate (writer, seq) is
  ;; not a hole a reader can see — it collapses on arrival.
  (let [{:keys [db store]} (machine "w-a")
        session (oplog/logged-store (mem/create) db)]
    (store/-ensure-entity store {:name "one" :scope "project"})
    (store/-ensure-entity session {:name "two" :scope "project"})
    (store/-ensure-entity store {:name "three" :scope "project"})
    (is (= [1 2 3] (mapv :seq (log-lines db "w-a")))
        "one writer, one numbering, whatever it is holding the store")))

(deftest a-log-another-process-appended-to-is-not-renumbered
  (let [{:keys [db store]} (machine "w-a")]
    (store/-ensure-entity store {:name "one" :scope "project"})
    ;; a `claim assert` in its own process, appending as this same writer while
    ;; this handle stays open: the seq cached at open is now behind the file
    (deliver-line! db "w-a" {:t "ensure-entity" :seq 2 :hlc (System/currentTimeMillis)
                             :entity (remote-entity "e-two" "two")})
    (store/-ensure-entity store {:name "three" :scope "project"})
    (is (= [1 2 3] (mapv :seq (log-lines db "w-a")))
        "numbering resumes above what the log actually holds, not above what we remember writing")))

(deftest a-log-that-cannot-be-written-says-so
  (let [{:keys [db store]} (machine "w-a")
        err (java.io.StringWriter.)]
    ;; a full disk, a read-only mount, or — here — a directory sitting exactly
    ;; where the log file goes
    (fs/create-dirs (fs/path (oplog/oplog-dir db) "w-a.jsonl"))
    (binding [*err* err]
      (core/assert-fact store {:subject "billing" :predicate :core/depends-on
                               :object "stripe"}))
    (is (str/includes? (str err) "will not reach any other machine"))
    (is (seq (:facts (core/get-facts store {:entity "billing"})))
        "the store write stands; what was lost is the replication, and silently losing it is the bug")))

(deftest a-mutation-that-threw-is-not-broadcast
  (let [db (db-path)]
    (fs/create-dirs (oplog/oplog-dir db))
    (spit (str (fs/path (oplog/oplog-dir db) "writer")) "w-a")
    (let [s (oplog/logged-store
             (reify store/Store
               (-update-entity [_ _ _] (throw (ex-info "store said no" {}))))
             db)]
      (is (thrown? clojure.lang.ExceptionInfo (store/-update-entity s "e-1" {:name "x"})))
      (is (not (fs/exists? (fs/path (oplog/oplog-dir db) "w-a.jsonl")))
          "a mutation that failed here must not be applied happily somewhere else"))))

(deftest the-clock-merges-what-it-observes
  (let [a (machine "w-a")
        ahead (+ (System/currentTimeMillis) 600000)]
    (deliver-line! (:db a) "w-b" {:t "ensure-entity" :seq 1 :hlc ahead
                                  :entity (remote-entity "e-remote" "billing")})
    (oplog/reconcile! (oplog/inner-store (:store a)) (:db a))
    (is (>= (:clock (applied-state (:db a))) ahead)
        "the highest clock seen is remembered across processes")
    (core/assert-fact (:store a) {:subject "svc" :predicate :core/prefers
                                  :object "argon2" :object-kind :literal})
    (is (> (:hlc (last (log-lines (:db a) "w-a"))) ahead)
        "an effect appended after seeing a peer's sorts after it, whatever the wall clocks think")))
