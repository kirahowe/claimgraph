(ns claimgraph.load-test
  "dump -> JSON -> load round-trip. The fixture store exercises every
  semantic feature the dump carries: a commitment, a supersession chain with
  its invalidation reason, an open conflict with links, aliases, an x/*
  coinage, a closed episode with a summary and a fact filed under it, a
  reinforcement that moves :last-reinforced-at off :recorded-at, and
  effective-dated valid time — enough that every field of the documented fact
  wire shape is present on some fact, which the round trip asserts.

  Every comparison is against the SOURCE store, never against a second dump.
  Dump-to-dump is the check that let the :type collision live: truncation and
  erasure are both idempotent, so a field the dump destroys on the way out
  matches perfectly on the way back in. Entity ids are re-minted by design,
  so facts compare with subjects/objects projected to names."
  (:require [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.logic :as logic]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]
            [claimgraph.version :as version]
            [claimgraph.wire :as wire]))

(defn- build-fixture []
  (let [s (mem/create)]
    (core/seed! s)
    ;; commitment + a contradicting write -> flagged conflict with links
    (core/assert-fact s {:subject "api-layer" :predicate :core/decided-against
                         :object "GraphQL" :object-kind :literal
                         :epistemic :commitment :source-type :decision-record})
    (core/assert-fact s {:subject "api-layer" :predicate :core/prefers
                         :object "GraphQL" :object-kind :literal
                         :epistemic :preference})
    ;; supersession chain (cardinality :one)
    (core/assert-fact s {:subject "AuthService" :subject-type :service
                         :predicate :core/has-version :object "1.0.0"})
    (core/assert-fact s {:subject "AuthService" :predicate :core/has-version
                         :object "2.0.0"})
    ;; effective-dated closed interval, entity object, alias, x/* coinage
    (core/assert-fact s {:subject "svc" :predicate :core/deployed-via
                         :object "Heroku" :object-kind :literal
                         :t-valid #inst "2026-01-01" :t-invalid #inst "2026-03-01"})
    (core/assert-fact s {:subject "billing" :predicate :core/depends-on
                         :object "Redis" :object-type :tool :object-kind :entity})
    (core/alias-entity s {:name "AuthService" :alias "auth-svc"})
    (core/assert-fact s {:subject "billing" :predicate :x/rate-limited-by
                         :object "redis-bucket" :object-kind :literal})
    ;; a closed episode with a summary (searchable episodic memory), and a
    ;; fact filed under it: :episode is provenance no other field stands in
    ;; for, so a fact carrying one is the only way the round trip can prove it
    ;; survives
    (let [ep (core/open-episode s {:source-type :session-log :ref "sess-42"})]
      (core/assert-fact s {:subject "worker" :predicate :core/deployed-via
                           :object "Fly" :object-kind :literal
                           :episode (:id ep)})
      (core/close-episode s {:episode (:id ep) :summary "the session where it happened"}))
    ;; re-assert to reinforce, so one fact's :last-reinforced-at is later than
    ;; its :recorded-at — with the two equal, a load that copied one into the
    ;; other would round-trip perfectly
    (Thread/sleep 5)
    (core/assert-fact s {:subject "billing" :predicate :x/rate-limited-by
                         :object "redis-bucket" :object-kind :literal})
    s))

(defn- dumped
  "The dump exactly as a file round-trip delivers it — header line included,
  through the encoder the CLI writes with."
  [s]
  (mapv wire/parse-string (wire/dump-lines (core/dump s))))

(defn- by-name
  "Entities keyed by name, with the re-minted id projected away."
  [s]
  (into {} (map (juxt :name #(dissoc % :id))) (store/-list-entities s {})))

(def ^:private documented-fact-fields
  "Every field of the fact wire shape, read from the one declaration that owns
  it rather than copied. Copying is how :recorded-at, :last-reinforced-at and
  :episode dropped out of this check while the test still said \"field for
  field\" — and a second copy went stale the moment :invalidation-kind and
  :successor were added, failing here for naming the shape rather than for
  anything the round trip got wrong."
  (set store/fact-keys))

(defn- fact-semantics
  "A fact reduced to what two stores can compare — EVERY key the store hands
  back, never an enumerated subset, so a field the load side drops fails here
  whether or not anyone remembered to list it. Two projections only:
  subject/object-ref become names because entity ids are re-minted by design,
  and conflict links become a set because link order was never a promise.

  Nils are dropped so an absent key and a nil one compare equal — the dump
  drops nils, and present-nil vs absent is not a semantic difference."
  [f]
  (into {} (filter (comp some? val))
        (assoc f
               :subject (get-in f [:subject :name])
               :object-ref (get-in f [:object-ref :name])
               :conflicts (set (:conflicts f)))))

(defn- facts-by-id
  "Both stores' facts keyed by the id the dump round-trips exactly, so a
  mismatch names the fact instead of printing two anonymous sets."
  [s]
  (into {} (map (juxt :id fact-semantics)) (store/-all-facts s)))

(deftest dump-load-round-trip
  (let [src (build-fixture)
        records (dumped src)
        dst (mem/create)
        _ (core/seed! dst)
        r (core/load-dump dst records)]

    (testing "counts survive"
      (is (= :loaded (:status r)))
      (is (= 1 (:conflict-links r)))
      (is (= 2 (:invalidated r))
          "the superseded version and the closed Heroku interval")
      (let [ss (store/-stats src) ds (store/-stats dst)]
        (is (= (:facts ss) (:facts ds)))
        (is (= (:entities ss) (:entities ds)))
        (is (= (:episodes ss) (:episodes ds)))))

    (testing "every fact matches the SOURCE store, field for field"
      (is (= (facts-by-id src) (facts-by-id dst)))
      (is (= documented-fact-fields
             (into #{} (mapcat keys) (vals (facts-by-id src))))
          "and the fixture exercises the whole documented shape, so 'field for
           field' means every field — a fixture that stops producing one is a
           blind spot the comparison above cannot report"))

    (testing "every entity matches the source store, INCLUDING its type"
      (is (= (by-name src) (by-name dst))
          "the discriminator used to land on :type and erase the entity's own")
      (is (= :service (:type (get (by-name dst) "AuthService")))
          "a keyword-valued payload field comes back a keyword, not a string"))

    (testing "predicates and episodes match the source store"
      (is (= (set (store/-list-predicates src {}))
             (set (store/-list-predicates dst {})))
          "incl. the x/* coinage minted by first use")
      (is (= (set (store/-list-episodes src))
             (set (store/-list-episodes dst)))
          "ids, source types, summaries and the opened/closed instants"))

    (testing "semantics survive: history, conflicts, aliases, as-of"
      (is (= (mapv #(select-keys % [:object-lit :invalidation-reason])
                   (:history (core/get-history src {:subject "AuthService"
                                                    :predicate :core/has-version})))
             (mapv #(select-keys % [:object-lit :invalidation-reason])
                   (:history (core/get-history dst {:subject "AuthService"
                                                    :predicate :core/has-version})))))
      (is (= 1 (:open (core/conflicts dst)))
          "the flagged commitment conflict is still open after restore")
      (is (= "AuthService" (get-in (core/get-facts dst {:entity "auth-svc"})
                                   [:entity :name]))
          "aliases resolve")
      (is (= ["Heroku"]
             (mapv :object-lit
                   (:facts (core/get-facts dst {:entity "svc"
                                                :as-of #inst "2026-02-01"}))))
          "time travel into the closed interval"))

    (testing "resolution is still type-guarded after the restore"
      ;; "auth-service" normalizes onto AuthService, so the type is the only
      ;; thing standing between a :namespace lookup and a :service entity —
      ;; and an entity whose type the dump erased guards nothing.
      (is (= "AuthService" (get-in (core/resolve-entity dst {:name "auth-service"
                                                             :type :service})
                                   [:entity :name]))
          "the compatible type still resolves through normalization")
      (is (nil? (core/resolve-entity dst {:name "auth-service" :type :namespace}))
          "and the incompatible one is refused, exactly as on the source store")
      (is (= (core/resolve-entity src {:name "auth-service" :type :namespace})
             (core/resolve-entity dst {:name "auth-service" :type :namespace}))))

    (testing "loading into a non-empty store refuses"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                            (core/load-dump dst records))))))

(deftest a-dump-stamps-its-record-kind-somewhere-no-payload-field-lives
  (let [records (core/dump (build-fixture))
        entity (first (filter #(= "entity" (:record %)) records))]
    (is (= #{"predicate" "entity" "episode" "fact"}
           (set (map :record records)))
        "every record says what it is, under :record")
    (is (= :service (:type (first (filter #(= "AuthService" (:name %)) records))))
        "and an entity's :type is still the entity's own")
    (is (some? entity))))

;; ---------------------------------------------------------------------------
;; What load refuses, and why silence would be worse
;; ---------------------------------------------------------------------------

(defn- fresh []
  (doto (mem/create) core/seed!))

(deftest load-refuses-a-dump-from-the-future
  (let [src (build-fixture)
        ahead (inc version/format-version)
        records (cons (assoc (version/dump-header) :format ahead)
                      (rest (dumped src)))]
    (try
      (core/load-dump (fresh) records)
      (is false "a format this build was never taught must not load")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find (re-pattern (str ahead)) (ex-message e))
            "the message names the dump's format")
        (is (re-find (re-pattern (str version/format-version)) (ex-message e))
            "and this build's")
        (is (= :dump-format-too-new (:type (ex-data e))))
        (is (string? (:hint (ex-data e))) "and says what to do about it")))))

(deftest load-accepts-a-dump-from-the-past
  (let [src (build-fixture)
        records (cons (assoc (version/dump-header) :version "0.0.9-alpha")
                      (rest (dumped src)))]
    (is (= :loaded (:status (core/load-dump (fresh) records)))
        ":version is provenance, never a compatibility gate")))

(deftest load-refuses-a-pre-alpha-dump-rather-than-misreading-it
  (let [src (build-fixture)
        ;; exactly what claimgraph wrote before the stamp moved: no header, and
        ;; the record kind sitting on :type, on top of the entity's own type
        legacy (mapv (fn [r] (-> r (dissoc :record) (assoc :type (:record r))))
                     (core/dump src))]
    (try
      (core/load-dump (fresh) legacy)
      (is false "a pre-alpha dump must not load: its entity types are gone")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"(?i)pre-alpha" (ex-message e)))
        (is (= :dump-pre-alpha (:type (ex-data e))))
        (is (string? (:hint (ex-data e))) "and says to re-dump")))))

(deftest load-refuses-records-it-does-not-understand
  (let [src (build-fixture)
        dst (fresh)
        records (concat (dumped src) [{:record "annotation" :id "a-1" :note "hi"}])]
    (try
      (core/load-dump dst records)
      (is false "unknown records used to be a count in the result map")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"annotation" (ex-message e))
            "the message names the record kind it could not read")
        (is (= :unknown-dump-records (:type (ex-data e))))))
    (is (zero? (long (:entities (store/-stats dst))))
        "and it refuses before writing: no half-restored graph to clean up")))

(deftest load-result-no-longer-counts-unknown-records
  (let [r (core/load-dump (fresh) (dumped (build-fixture)))]
    (is (not (contains? r :unknown-records))
        "a silently-dropped-records count is not a thing load can report any more")
    (is (= 1 (:format r)) "and the header was read, not counted as a record")))

(deftest load-refuses-input-nothing-identifies-as-a-dump
  (testing "an empty file used to load with :status :loaded and exit 0"
    ;; what `: > empty.jsonl` then `claim load --file empty.jsonl` delivers:
    ;; the CLI drops blank lines, so an empty or whitespace-only file arrives
    ;; here as no records at all
    (try
      (core/load-dump (fresh) [])
      (is false "an empty file is not an empty graph")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-a-dump (:type (ex-data e))))
        (is (re-find #"(?i)no dump header and no records" (ex-message e)))
        (is (string? (:hint (ex-data e)))))))

  (testing "somebody else's JSONL is not a pre-alpha claimgraph dump"
    (try
      (core/load-dump (fresh) [{:foo 1} {:bar 2}])
      (is false "a file that says nothing about itself must not load")
      (catch clojure.lang.ExceptionInfo e
        (is (= :not-a-dump (:type (ex-data e))))
        (is (not (re-find #"(?i)pre-alpha" (ex-message e)))
            "'re-dump the source database' sends the user after a database
             that has nothing to do with this file")
        (is (= 2 (:records (ex-data e))))))))

(deftest load-accepts-a-header-with-nothing-behind-it
  (let [r (core/load-dump (fresh) [(version/dump-header)])]
    (is (= :loaded (:status r))
        "a dump of an empty store is identified by its header and restores to
         nothing — the refusal above is about input that identifies itself as
         nothing at all, not about an empty graph")
    (is (= 0 (:facts r) (:entities r) (:episodes r) (:predicates r)))
    (is (= version/format-version (:format r)))))

(deftest load-accepts-records-handed-over-in-process
  (let [r (core/load-dump (fresh) (rest (dumped (build-fixture))))]
    (is (= :loaded (:status r))
        "core/dump's records reach load-dump directly as well as through a
         file; every one of them carries its own kind stamp, which is what
         stands in for the header a file would have led with")
    (is (not (contains? r :format)) "and there is no header to report a format from")))

(deftest load-refuses-a-header-whose-format-is-not-an-integer
  (doseq [[label header] {"null" (assoc (version/dump-header) :format nil)
                          "a string" (assoc (version/dump-header) :format "2")
                          "a float" (assoc (version/dump-header) :format 1.5)
                          "absent" (dissoc (version/dump-header) :format)}]
    (try
      (core/load-dump (fresh) [header {:record "entity" :id "e-1" :name "svc"}])
      (is false (str "a :format that is " label " must not reach `long`"))
      (catch clojure.lang.ExceptionInfo e
        (is (= :dump-format-invalid (:type (ex-data e))) label)
        (is (string? (ex-message e))
            "an unguarded NullPointerException carries a nil message: nothing
             for the caller to print, let alone act on")
        (is (re-find #"(?i)format" (ex-message e)) label)
        (is (string? (:hint (ex-data e))) label)))))

(deftest load-refuses-a-stamped-dump-whose-records-lost-their-kind
  (try
    (core/load-dump (fresh) [(version/dump-header) {:id "e-1" :name "svc"}])
    (is false "a header cannot vouch for a line that says nothing about itself")
    (catch clojure.lang.ExceptionInfo e
      (is (= :dump-records-unstamped (:type (ex-data e)))
          "the header proves claimgraph wrote this file, so 'pre-alpha' and
           'not a claimgraph dump' are both the wrong diagnosis")
      (is (not (re-find #"(?i)pre-alpha" (ex-message e))))
      (is (string? (:hint (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; The pure half
;; ---------------------------------------------------------------------------

(deftest rehydration-restores-types
  (let [[t f] (logic/rehydrate-dump-record
               {:record "fact" :id "f-1"
                :subject {:id "e-1" :name "A" :type "service"}
                :predicate "core/has-version" :object-kind "literal"
                :object-lit "1.0" :t-valid "2026-01-01T00:00:00Z"
                :confidence 0.8 :epistemic "observation"
                :source-type "session-log" :conflicts []})]
    (is (= :fact t))
    (is (= :core/has-version (:predicate f)))
    (is (= :service (get-in f [:subject :type])))
    (is (instance? java.util.Date (:t-valid f)))
    (is (= :observation (:epistemic f))))

  (testing "an entity keeps the :type the discriminator used to overwrite"
    (let [[t e] (logic/rehydrate-dump-record
                 {:record "entity" :id "e-1" :name "AuthService"
                  :type "service" :scope "project" :aliases ["auth-svc"]})]
      (is (= :entity t))
      (is (= :service (:type e)))
      (is (= "AuthService" (:name e)))))

  (testing "a kind this build has no reader for keeps the line intact to report"
    (is (= [:unknown {:record "mystery" :x 1}]
           (logic/rehydrate-dump-record {:record "mystery" :x 1}))))

  (testing "a record with no kind at all is the pre-stamp shape, not a payload"
    (is (= [:unstamped {:type "entity" :name "svc"}]
           (logic/rehydrate-dump-record {:type "entity" :name "svc"}))
        "the old discriminator is left where it is: reading it is how the
         entity's real type got destroyed")))

(deftest dump-kinds-is-what-rehydration-actually-reads
  (is (= logic/dump-kinds
         (into #{} (comp (map #(hash-map logic/dump-discriminator (name %)))
                         (map logic/rehydrate-dump-record)
                         (map first))
               logic/dump-kinds))
      "the set a loader classifies with and the kinds rehydration has a reader
       for are two lists of the same thing; drifting apart turns a readable
       record into an :unknown refusal, or the reverse"))

(deftest telling-a-pre-alpha-dump-from-a-file-that-was-never-a-dump
  (is (every? logic/pre-alpha-dump-record?
              [{:type "entity" :name "svc"} {:type "fact" :id "f-1"}
               {:type :predicate :id "core/prefers"}])
      "the pre-alpha stamp is one of our kinds sitting on :type")
  (is (not-any? logic/pre-alpha-dump-record?
                [{:foo 1} {:type "user"} {:type nil} {} {:record "entity"}])
      "another tool's :type, or none, is not a claimgraph dump — and a build
       that guesses otherwise tells the user to re-dump a database that has
       nothing to do with the file"))
