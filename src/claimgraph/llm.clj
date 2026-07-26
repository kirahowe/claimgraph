(ns claimgraph.llm
  "Shared shell-out to an authenticated agent CLI — the subscription-as-judge
  mechanism used by the session extractor and the conflict judge. The command
  receives the prompt on stdin and replies on stdout. Resolution order:
  explicit command > $CLAIMGRAPH_LLM_CMD > \"claude -p\".

  Every call is bounded: a wedged CLI would otherwise block forever, and
  under the SessionEnd hook a single hung call eats the hook's whole 600s
  budget. The bound is 120s, overridable per install via
  $CLAIMGRAPH_LLM_TIMEOUT_MS; on expiry the child's process tree dies with
  the call rather than outliving it."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [claimgraph.logic :as logic]))

(def default-command "claude -p")
(def default-timeout-ms 120000)

;; How long a timed-out child gets to honour SIGTERM before it is killed, and
;; how long we then wait to reap it. Both are paid only on the timeout path.
(def ^:private term-grace-ms 500)
(def ^:private reap-ms 2000)

(defn command [override]
  (or override (System/getenv "CLAIMGRAPH_LLM_CMD") default-command))

(defn- positive-ms [v]
  (cond
    (number? v) (when (pos? v) (long v))
    (string? v) (try (let [n (Long/parseLong (str/trim v))] (when (pos? n) n))
                     (catch Exception _ nil))))

(defn timeout-ms
  "Resolve the per-call timeout: explicit option > env > 120s. A malformed or
  non-positive override is a misconfiguration the run can survive, so it falls
  back to the default instead of throwing — losing the timeout tuning is a
  smaller failure than losing the batch. The 2-arity takes the env value
  directly so the resolution stays testable."
  ([override] (timeout-ms override (System/getenv "CLAIMGRAPH_LLM_TIMEOUT_MS")))
  ([override env]
   (or (positive-ms override) (positive-ms env) default-timeout-ms)))

(defn- alive? [^java.lang.ProcessHandle h] (.isAlive h))

(defn- kill-tree!
  "Signal the timed-out child and everything it spawned, escalating to a kill
  for whatever ignores the signal: a wedged CLI is exactly the process least
  likely to honour a polite one, and an orphan left holding the pipe outlives
  the caller — under the SessionEnd hook, past the hook itself. What survives
  the grace is decided by the snapshot, and the grace is waited out on its
  handles rather than on the proc, because a wrapper that dies on SIGTERM while
  the child it spawned ignores it — every agent CLI's shape — defeats both: the
  parent's exit says nothing about the tree, and `deref` doesn't even return
  until the pipes the grandchild is still holding close."
  [proc]
  (let [handle (.toHandle ^java.lang.Process (:proc proc))
        ;; snapshot the tree first — after destroy-tree the descendants are
        ;; no longer reachable from the parent handle
        tree (cons handle (vec (.toArray (.descendants handle))))
        grace-until (+ (System/currentTimeMillis) term-grace-ms)]
    (p/destroy-tree proc)
    (while (and (some alive? tree) (< (System/currentTimeMillis) grace-until))
      (Thread/sleep 25))
    (when-let [survivors (seq (filter alive? tree))]
      (run! (fn [^java.lang.ProcessHandle h] (.destroyForcibly h)) survivors)
      (deref proc reap-ms nil))))

(defn- feed-stdin!
  "Hand the prompt to the child on its own thread and close stdin. Ours rather
  than babashka's :in copier because the kill breaks the pipe mid-write on
  every timeout with a real (large) prompt, and that copier reports the break
  on stderr — beside the CLI's own JSON error, where a user reads it as one."
  [proc ^String prompt]
  (future
    (try (with-open [^java.io.OutputStream os (:in proc)]
           (when prompt (.write os (.getBytes prompt "UTF-8"))))
         (catch java.io.IOException _ nil))))

(defn complete!
  "Send prompt on stdin to cmd; return stdout. Throws on non-zero exit, and on
  a timeout (opts :timeout-ms, else the env/default resolution above) after
  killing the child's process tree."
  ([cmd prompt] (complete! cmd prompt nil))
  ([cmd prompt {ms :timeout-ms}]
   (let [ms (timeout-ms ms)
         proc (p/process (p/tokenize cmd) {:in :pipe :out :string :err :string})
         _ (feed-stdin! proc prompt)
         result (deref proc ms ::timeout)]
     (if (= ::timeout result)
       (do (kill-tree! proc)
           (logic/fail (str "LLM command timed out after " ms "ms: " cmd)
                       {:type :llm-command-timeout :command cmd :timeout-ms ms}))
       (let [{:keys [exit out err]} result]
         (when-not (zero? exit)
           (logic/fail (str "LLM command failed: " cmd)
                       {:type :llm-command-failed :exit exit
                        :stderr (str/trim (or err ""))}))
         out)))))
