(ns claimgraph.lease-test
  "The write lease: atomic acquisition, token-guarded release and renewal,
  expiry breaking, contention errors, and serialization under real concurrency
  — including operations that run longer than the TTL, which is what the
  heartbeat is for. The last tests reach through the CLI boundary, because
  which wrapper a verb runs under is the whole of what the lease protects."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.cli :as cli]
            [claimgraph.core :as core]
            [claimgraph.lease :as lease]
            [claimgraph.store.memory :as mem]))

(defn- temp-db []
  (str (fs/path (fs/create-temp-dir {:prefix "claimgraph-lease-test"}) "db")))

(def ^:private ttl
  "Short enough that a test can outlive several TTLs, long enough that the
  renewal interval derived from it (a third) is not at the floor."
  200)

(defn- contend!
  "What a second writer gets right now: its own token when it took the store,
  :store-locked when the holder still holds it. wait-ms is the caller's, because
  the two answers need different waits — breaking an expired lease costs one
  retry round, and proving a live lease is held must not wait at all."
  [db wait-ms]
  (try (lease/acquire! db {:owner "other" :wait-ms wait-ms})
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- age!
  "Backdate a file, so a test can outlive a grace period rather than sleep for
  one."
  [f ms]
  (fs/set-last-modified-time f (- (System/currentTimeMillis) (long ms))))

(defn- steal!
  "Another writer's lease, in the file, as if it had broken ours and taken the
  store — the mid-operation loss the heartbeat must never paper over."
  [db owner]
  (spit (lease/lock-file db)
        (json/generate-string {:token (str (random-uuid))
                               :owner owner
                               :acquired-at (System/currentTimeMillis)
                               :expires-at (+ (System/currentTimeMillis) 60000)})))

(deftest acquire-release-cycle
  (let [db (temp-db)
        t1 (lease/acquire! db {:owner "a"})]
    (is (fs/exists? (lease/lock-file db)))
    (testing "a second writer times out with the holder named"
      (let [e (try (lease/acquire! db {:owner "b" :wait-ms 0})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :store-locked (:type (ex-data e))))
        (is (= "a" (get-in (ex-data e) [:holder :owner])))))
    (lease/release! db t1)
    (is (not (fs/exists? (lease/lock-file db))))
    (testing "free again"
      (let [t2 (lease/acquire! db {:owner "b" :wait-ms 0})]
        (lease/release! db t2)))))

(deftest expired-leases-break
  (let [db (temp-db)]
    (lease/acquire! db {:owner "crashed" :ttl-ms -1})
    (testing "a dead writer's lease never wedges the store"
      (let [t (lease/acquire! db {:owner "next" :wait-ms 1000})]
        (is (string? t))
        (lease/release! db t)))))

(deftest a-lock-file-with-no-lease-in-it-is-debris-eventually
  (testing "a writer killed mid-write leaves a lock file saying nothing"
    ;; spit truncates before it writes, and the heartbeat runs one of those
    ;; windows per renewal for the whole operation — so this is what a Ctrl-C
    ;; during a long consolidate can leave behind, and it has no expiry to be
    ;; broken on
    (let [db (temp-db)
          f (lease/lock-file db)]
      (spit f "")
      (is (= :store-locked (contend! db 300))
          "a fresh one is left alone: a live acquirer's file says nothing either,
           for the moment between creating it and writing its lease in")
      (age! f (+ lease/unreadable-grace-ms 1000))
      (is (string? (contend! db 1000)) "but debris never wedges the store for good")))
  (testing "a lease with no expiry to break it on"
    ;; a torn write that still parses, or a file from a version that did not
    ;; stamp one: the store used to be wedged until someone deleted it by hand
    (let [db (temp-db)
          f (lease/lock-file db)]
      (spit f (json/generate-string {:token "t" :owner "old"}))
      (is (= :store-locked (contend! db 300)))
      (age! f (+ lease/unreadable-grace-ms 1000))
      (is (string? (contend! db 1000))))))

(deftest release-is-token-guarded
  (let [db (temp-db)
        stale (lease/acquire! db {:owner "a" :ttl-ms -1})
        fresh (lease/acquire! db {:owner "b" :wait-ms 1000})]
    (testing "the expired holder's late release cannot free b's lease"
      (lease/release! db stale)
      (is (fs/exists? (lease/lock-file db))))
    (lease/release! db fresh)))

(deftest renewal-is-token-guarded
  (let [db (temp-db)
        stale (lease/acquire! db {:owner "slow" :ttl-ms -1})
        fresh (lease/acquire! db {:owner "next" :wait-ms 1000})]
    (testing "the displaced writer's renewal cannot write its token back over the new holder's"
      (is (nil? (lease/renew! db stale 30000)))
      (is (lease/held? db fresh))
      (is (not (lease/held? db stale)))
      (is (= "next" (:owner (json/parse-string (slurp (lease/lock-file db)) true)))))
    (testing "the holder renews its own"
      (is (number? (lease/renew! db fresh 30000)))
      (is (lease/held? db fresh)))
    (lease/release! db fresh)))

(deftest an-expired-lease-is-not-renewable
  (let [db (temp-db)
        t (lease/acquire! db {:owner "frozen" :ttl-ms -1})]
    (testing "a writer whose lease already lapsed cannot extend its way back in"
      ;; nobody has taken it yet, which is exactly the window in which renewing
      ;; would steal the store from a writer already inside try-acquire!
      (is (nil? (lease/renew! db t 30000)))
      (is (not (lease/held? db t))))))

(deftest a-heartbeat-holds-a-lease-past-its-ttl
  (let [db (temp-db)
        token (lease/acquire! db {:owner "slow" :ttl-ms ttl})
        beat (lease/heartbeat! db token {:ttl-ms ttl})]
    (testing "renewed across three whole TTLs, the lease is still held"
      (Thread/sleep (* 3 ttl))
      (is (lease/held? db token))
      (is (= :store-locked (contend! db 0)) "and no other writer may break it")
      (is (lease/held? db token)
          "not even by deleting it on the way to giving up: an acquirer that
           finds an expired lease breaks it BEFORE it decides whether to wait,
           so being refused is not on its own evidence the lease survived"))
    (testing "with the heartbeat stopped, the TTL takes over again"
      ((:stop! beat))
      (Thread/sleep (* 2 ttl))
      (is (not (lease/held? db token)))
      (is (string? (contend! db 1000)) "a crashed writer still frees the store"))))

(deftest a-heartbeat-never-outlives-the-operation
  (let [db (temp-db)
        token (lease/acquire! db {:owner "a" :ttl-ms ttl})
        beat (lease/heartbeat! db token {:ttl-ms ttl})]
    (testing "a daemon thread: renewals must never be why `claim` refuses to exit"
      (is (true? (.isDaemon ^Thread (:thread beat)))))
    (testing "stop! waits for the renewal in flight, and twice is harmless"
      ((:stop! beat))
      (is (not (.isAlive ^Thread (:thread beat))))
      ((:stop! beat)))
    (lease/release! db token)))

(deftest with-lease-outlives-its-own-ttl
  (let [db (temp-db)
        contender (promise)]
    (lease/with-lease db {:owner "consolidate" :ttl-ms ttl}
      (fn []
        ;; ~51 LLM calls per real consolidate: the pass IS longer than the TTL
        (Thread/sleep (* 3 ttl))
        (deliver contender [(contend! db 0) (fs/exists? (lease/lock-file db))])))
    (testing "a pass longer than the TTL is still serialized"
      ;; the lock file is the half that bites. Against a lease with no renewal
      ;; the contender is refused too — after deleting the expired lease on its
      ;; way past, which is how the NEXT contender walked in on a live writer
      (is (= [:store-locked true] @contender)))
    (is (not (fs/exists? (lease/lock-file db))) "and the lease comes back at the end")))

(deftest with-lease-fails-loudly-when-the-lease-is-lost
  (let [db (temp-db)
        e (try (lease/with-lease db {:owner "slow" :ttl-ms ttl}
                 (fn []
                   (steal! db "thief")
                   (Thread/sleep (* 2 ttl))
                   {:facts 3}))
               (catch clojure.lang.ExceptionInfo e e))
        d (ex-data e)]
    (testing "an unserialized write is never reported as a serialized one"
      (is (= :lease-lost (:type d)))
      (is (= :taken (:reason d)))
      (is (= "thief" (get-in d [:holder :owner])))
      (is (true? (:claimgraph/error d))))
    (testing "the work that landed is still reported"
      (is (= {:facts 3} (:result d))))
    (is (fs/exists? (lease/lock-file db))
        "and the release on the way out cannot free the new holder's lease")))

(deftest a-lease-that-lapses-unnoticed-is-lost-too
  (let [db (temp-db)
        d (-> (try (lease/with-lease db {:owner "frozen" :ttl-ms ttl}
                     (fn []
                       ;; the heartbeat stopped renewing (a frozen process, a
                       ;; killed thread) and nobody took the store: still not
                       ;; mutual exclusion, still not a success
                       (with-redefs [lease/renew! (fn [& _] nil)]
                         (Thread/sleep (* 2 ttl)))))
                   (catch clojure.lang.ExceptionInfo e e))
              ex-data)]
    (is (= :lease-lost (:type d)))
    (is (= :expired (:reason d)))
    (is (nil? (:holder d)) "nobody took it — there is no holder to name")))

(deftest the-heartbeat-stops-when-the-body-throws
  (let [db (temp-db)
        beats (atom [])
        start lease/heartbeat!]
    (with-redefs [lease/heartbeat! (fn [& args]
                                     (let [beat (apply start args)]
                                       (swap! beats conj beat)
                                       beat))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                            (lease/with-lease db {:owner "boom" :ttl-ms ttl}
                              (fn [] (throw (ex-info "boom" {})))))))
    (is (= 1 (count @beats)))
    (is (not (.isAlive ^Thread (:thread (first @beats))))
        "however the operation ended, nothing is left renewing")
    (is (not (fs/exists? (lease/lock-file db))) "and the lease is released")))

;; ---------------------------------------------------------------------------
;; The CLI boundary: which wrapper a verb runs under
;; ---------------------------------------------------------------------------

(defn- verb!
  "One command against an in-memory store, its stdout swallowed. Redefining the
  opener keeps datalevin out of a question that is only about the lock file."
  [db cmd opts]
  (let [s (doto (mem/create) (core/seed!))]
    (with-redefs [cli/open-store (fn [_] s)]
      (with-out-str (cmd {:opts (assoc opts :db db)})))))

(deftest reads-never-take-the-lease
  (let [db (temp-db)
        holder (lease/acquire! db {:owner "a writer" :ttl-ms 60000})]
    (testing "a read verb neither takes the lease nor waits on one"
      ;; the reason the lease can be this coarse: it costs readers nothing, so
      ;; `claim facts` during a consolidate answers instead of blocking on it
      (is (seq (verb! db cli/cmd-predicates {})))
      (is (= holder (:token (json/parse-string (slurp (lease/lock-file db)) true)))
          "and the holder's lease is untouched"))
    (lease/release! db holder)))

(deftest write-verbs-run-under-the-lease
  (let [db (temp-db)]
    (testing "the lease is taken for the command and given back after it"
      (is (seq (verb! db cli/cmd-assert {:subject "web" :predicate "core/depends-on"
                                         :object "api" :object-kind "entity"})))
      (is (not (fs/exists? (lease/lock-file db)))))
    (testing "and a second writer is refused, loudly, by name"
      (let [holder (lease/acquire! db {:owner "another agent" :ttl-ms 60000})
            e (try (verb! db cli/cmd-assert {:subject "web" :predicate "core/depends-on"
                                             :object "db" :object-kind "entity"
                                             ;; the knob the wrapper threads through
                                             :lease-wait 0})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :store-locked (:type (ex-data e))))
        (is (= "another agent" (get-in (ex-data e) [:holder :owner])))
        (lease/release! db holder)))))

(deftest a-write-verb-longer-than-the-ttl-is-still-serialized
  ;; #20 at the boundary that has the bug: consolidate and hooks run shell out
  ;; to an LLM dozens of times, and the CLI hands with-lease no TTL of its own
  (let [db (temp-db)
        contender (promise)]
    (with-redefs [lease/default-ttl-ms ttl
                  cli/open-store (fn [_] (mem/create))]
      (is (= :done (#'cli/with-write-store {:db db}
                     (fn [_]
                       (Thread/sleep (* 3 ttl))
                       (deliver contender [(contend! db 0)
                                           (fs/exists? (lease/lock-file db))])
                       :done)))))
    (is (= [:store-locked true] @contender)
        "refused, and the lease it could not break is still on disk")
    (is (not (fs/exists? (lease/lock-file db))))))

(deftest with-lease-serializes-concurrent-writers
  (let [db (temp-db)
        state (atom [])
        job (fn [id]
              (future
                (lease/with-lease db {:owner (str id) :wait-ms 10000}
                  (fn []
                    (swap! state conj [:enter id])
                    (Thread/sleep 30)
                    (swap! state conj [:exit id])))))]
    (run! deref [(job "w1") (job "w2") (job "w3")])
    (testing "critical sections never interleave"
      (is (= 6 (count @state)))
      (is (every? (fn [[[e1 id1] [e2 id2]]] (and (= :enter e1) (= :exit e2) (= id1 id2)))
                  (partition 2 @state))))))
