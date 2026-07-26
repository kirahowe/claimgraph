(ns claimgraph.lease
  "Write lease: multi-writer safety for the v0 single-store world
  (review §3.7). The conflict machinery is a read-decide-write cycle —
  two concurrent writers can both read 'no conflict' and insert a
  contradiction LMDB will happily hold. The lease serializes whole write
  operations at the CLI boundary instead: one lease file next to the db,
  atomically created, token-guarded, TTL'd so a crashed writer never
  wedges the store.

  Deliberately a lease, not a queue: coding-agent writers are short-lived
  CLI invocations, so waiting a few seconds covers real contention and
  anything longer deserves a loud :store-locked error naming the holder.

  Liveness is a heartbeat, not a longer TTL (#20). `consolidate` and `hooks
  run` shell out to an LLM dozens of times per pass, so they routinely outlive
  any TTL guessed for them — and the lease then expired under its own
  operation, a waiting writer correctly broke it, and both processes went back
  into the read-decide-write cycle this namespace exists to serialize. A TTL
  guessed long enough for a slow model is also long enough to wedge the store
  for minutes after a crash, so the TTL stays short and says one thing only:
  how long a DEAD writer's lease outlives it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(def default-ttl-ms
  "How long a lease outlives the writer holding it. A live writer renews
  (heartbeat!), so this bounds only the wedge a crash leaves behind."
  30000)

(def default-wait-ms 5000)
(def retry-sleep-ms 100)

(def min-renew-ms
  "Floor under the renewal interval, so a tiny TTL (tests, tuning) cannot turn
  the heartbeat into a spin loop on the filesystem."
  50)

(def unreadable-grace-ms
  "How long a lock file that yields no lease at all is left alone before any
  writer may delete it.

  Such a file has no expiry to be broken on, so without this the TTL's promise
  — a crashed writer never wedges the store — did not cover the way a crash
  most often leaves the file: spit truncates before it writes, and the
  heartbeat runs one of those windows per renewal for the whole operation where
  acquisition ran one, so a Ctrl-C during a long consolidate can land in it.

  Deliberately not the acquirer's own TTL. An unreadable file is ambiguous in a
  way an expired one is not: a live acquirer's file says nothing for the
  microseconds between creating it and writing its lease into it, and a writer
  that breaks THAT is how two processes end up holding the store at once. A
  file still saying nothing a full TTL later can only be debris."
  default-ttl-ms)

(defn lock-file [db] (str db ".lock"))

(defn- read-lease [db]
  (try (json/parse-string (slurp (lock-file db)) true)
       (catch Exception _ nil)))

(defn- expiry
  "When this lease runs out, or nil when the file does not say — which the
  holder and an acquirer answer differently, so neither may guess here. Reading
  it in one place is also what keeps a half-written :expires-at from throwing a
  ClassCastException out of a lock-file read."
  [held]
  (let [e (:expires-at held)]
    (when (number? e) (long e))))

(defn- renew-interval-ms
  "Renew at a third of the TTL: two ticks can be lost to a GC pause or a slow
  filesystem and the lease still outlives the gap."
  [ttl-ms]
  (max min-renew-ms (quot (long ttl-ms) 3)))

(defn- lease-loss
  "Nil while the lease file still names our token and has not expired;
  otherwise what became of it, which is what an error about it has to say.

  An expired lease still bearing our token counts as lost even though nobody
  has taken it yet: any waiting writer is entitled to break it, so we can no
  longer claim the operation was serialized."
  [db token now-ms]
  (let [held (read-lease db)]
    (cond
      (nil? held) {:reason :released}
      (not= token (:token held)) {:reason :taken :holder (dissoc held :token)}
      (>= now-ms (or (expiry held) 0)) {:reason :expired}
      :else nil)))

(defn- debris?
  "Has this lock file been saying nothing for a whole grace period
  (unreadable-grace-ms)? Age is all there is to go on — a file with no lease in
  it says nothing about who wrote it or when they meant it to lapse."
  [f now-ms]
  (let [modified (try (.lastModified (fs/file f)) (catch Exception _ 0))]
    (and (pos? modified)
         (> (- now-ms modified) unreadable-grace-ms))))

(defn- breakable?
  "May this lock file be deleted so acquisition can retry? Only a lease that
  outlived the writer holding it: expired on its own terms, or debris."
  [db now-ms]
  (let [held (read-lease db)]
    (if-let [e (expiry held)]
      (> now-ms e)
      (debris? (lock-file db) now-ms))))

(defn- try-acquire!
  "One atomic attempt: create-if-absent, or break a lease its writer outlived."
  [db lease now-ms]
  (let [f (lock-file db)]
    (or (try
          (fs/create-dirs (fs/parent (fs/absolutize f)))
          (fs/create-file f)
          (spit f (json/generate-string lease))
          true
          (catch java.nio.file.FileAlreadyExistsException _ false)
          (catch Exception _ false))
        (when (breakable? db now-ms)
          ;; break it and retry from scratch next round
          (try (fs/delete-if-exists f) (catch Exception _ nil))
          false)
        false)))

(defn acquire!
  "Take the write lease or throw :store-locked naming the holder.
  opts: :owner (label for errors) :ttl-ms :wait-ms"
  [db {:keys [owner ttl-ms wait-ms]}]
  (let [ttl (long (or ttl-ms default-ttl-ms))
        deadline (+ (System/currentTimeMillis) (long (or wait-ms default-wait-ms)))
        token (str (random-uuid))]
    (loop []
      (let [now (System/currentTimeMillis)
            lease {:token token
                   :owner (str (or owner "claimgraph"))
                   :acquired-at now
                   :expires-at (+ now ttl)}]
        (cond
          (try-acquire! db lease now) token

          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep retry-sleep-ms) (recur))

          :else
          (let [held (read-lease db)]
            (throw (ex-info (str "Store is write-locked by " (:owner held "unknown"))
                            {:type :store-locked
                             :claimgraph/error true
                             :holder (dissoc held :token)
                             :hint "another writer holds the lease; it expires on its TTL, or delete <db>.lock if the holder is dead"}))))))))

(defn held?
  "Is this token still the lease? The question a long operation has to be able
  to ask about itself — a renewal that stopped landing looks like a fast
  operation by every other measure."
  [db token]
  (nil? (lease-loss db token (System/currentTimeMillis))))

(defn renew!
  "Extend our lease. Returns the new expiry, or nil when the lease was not ours
  to extend. Both guards — our token, and not already expired — are the point
  of this function.

  An unconditional renewer would, in the one case that matters, do something
  strictly worse than the race renewal exists to prevent: our lease genuinely
  expired, another writer legitimately broke it and took the store, and we
  write our token back over theirs. Nobody races then — one writer silently
  steals the lease from another that believes it holds it. Refusing to renew an
  already-expired lease closes the same hole from the other side, in the window
  after the TTL passed but before the new holder has written its own token.

  Read-then-write is not a filesystem compare-and-swap, so a renewal stalled
  past the expiry between the check and the write can still land on another
  writer's lease. Renewing at a third of the TTL keeps that window at 'this
  process froze for a whole TTL' — the same condition that loses the lease
  anyway, and the one the caller is told about."
  [db token ttl-ms]
  (let [now (System/currentTimeMillis)
        held (read-lease db)]
    (when (and (= token (:token held))
               (< now (or (expiry held) 0)))
      (let [expires (+ now (long ttl-ms))]
        ;; :acquired-at and :owner ride along untouched — an error about this
        ;; lease should name who took it and when, not when it last renewed
        (try (spit (lock-file db) (json/generate-string (assoc held :expires-at expires)))
             expires
             (catch Exception _ nil))))))

(defn heartbeat!
  "Keep this token's lease alive while the operation runs.

  Returns a handle: :stop! ends the renewals, :lost holds the loss (see
  lease-loss) once a tick found the lease is no longer ours, :thread is the
  thread doing it. :stop! is idempotent and waits for a renewal in flight, so
  none can land after release! and leave a lock file no live process owns.

  The thread is a daemon: a renewal loop must never be the reason `claim`
  refuses to exit.

  It reports and does not interrupt. Nothing this wraps is transactional, so
  there is no safe point at which to abort a half-written consolidate, and
  killing one mid-pass throws away work that already landed. The caller decides
  what a lost lease means once the body is done (with-lease).

  A renewal that fails for any reason other than losing the lease — a full
  disk, a transient IO error — is retried on the next tick rather than reported
  as loss: the lease is still ours until the file says otherwise, and if the
  writes keep failing the expiry says so on its own."
  [db token {:keys [ttl-ms renew-ms]}]
  (let [ttl (long (or ttl-ms default-ttl-ms))
        every (long (or renew-ms (renew-interval-ms ttl)))
        lost (atom nil)
        running (atom true)
        thread (Thread.
                ^Runnable
                (fn []
                  (try
                    (while (and @running (nil? @lost))
                      (Thread/sleep every)
                      (when (and @running (nil? (renew! db token ttl)))
                        (reset! lost (lease-loss db token (System/currentTimeMillis)))))
                    (catch InterruptedException _ nil)
                    (catch Exception _ nil)))
                "claimgraph-lease-heartbeat")]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread
     :lost lost
     :stop! (fn []
              (reset! running false)
              (.interrupt thread)
              (try (.join thread 1000) (catch InterruptedException _ nil))
              nil)}))

(defn release!
  "Give the lease back — only if it is still ours (an expired-and-broken
  lease may have been reacquired by someone else)."
  [db token]
  (when (= token (:token (read-lease db)))
    (try (fs/delete-if-exists (lock-file db)) (catch Exception _ nil))))

(defn- lease-lost
  "The error a write that was not serialized has to fail with. Carries f's
  value when it had one: a caller that had not emitted its report yet (hooks
  run, the MCP write tool) must not lose the record of what landed just because
  the lease did."
  [{:keys [reason holder]} result]
  (ex-info (str "Write lease lost mid-operation"
                (when-let [o (:owner holder)] (str " to " o))
                ": this write was not serialized against other writers")
           (cond-> {:type :lease-lost
                    :claimgraph/error true
                    :reason reason
                    :hint (str "the writes landed, but a concurrent writer could not be "
                               "excluded from them — run `claim conflicts` and "
                               "`claim judge --sweep` before trusting them. A lease is "
                               "lost when the process is suspended past its TTL (laptop "
                               "sleep, SIGSTOP) or <db>.lock is deleted by hand.")}
             holder (assoc :holder holder)
             (some? result) (assoc :result result))))

(defn with-lease
  "Run f under the write lease, renewed by a heartbeat for as long as f runs
  (#20) — a pass that makes an unknown number of LLM calls must not lose the
  lease to its own duration.

  A lost lease throws, and throws AFTER f returns. Both halves are deliberate.
  Returning normally would report a serialized write that wasn't one, which is
  the failure the lease exists to prevent, only quieter. Aborting f mid-flight
  costs more than it saves: nothing here is transactional, so an interrupted
  pass leaves its writes behind with no report of them, while finishing first
  keeps the report (write verbs emit inside the body) and still refuses to call
  the pass a success. An f that throws on its own wins — that failure is the
  specific one."
  [db opts f]
  (let [token (acquire! db opts)
        beat (heartbeat! db token opts)]
    (try
      (let [v (f)]
        ;; stop renewing before the verdict: a tick landing afterwards could
        ;; only contradict what we already reported
        ((:stop! beat))
        (if-let [loss (or @(:lost beat) (lease-loss db token (System/currentTimeMillis)))]
          (throw (lease-lost loss v))
          v))
      (finally
        ((:stop! beat))
        (release! db token)))))
