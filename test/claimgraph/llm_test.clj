(ns claimgraph.llm-test
  "The hermetic contract on the LLM shell-out: the child runs from a neutral
  directory, never the caller's cwd — the default command is itself an agent
  harness (Claude Code CLI), and spawning it inside a project re-enters that
  project's own SessionEnd hook machinery on exit (spec/maintenance.allium's
  HermeticJudge guarantee). Stdin delivery and the failure shape are covered
  here too; the timeout path is judge-test's (it needs a real hung process
  and is slow by construction, so it stays out of this fast file)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.llm :as llm]))

(deftest complete-runs-hermetically
  (testing "the child's cwd is the system temp dir, not the caller's"
    (let [pwd (fs/canonicalize (str/trim (llm/complete! "pwd" nil)))
          tmp (fs/canonicalize (str (fs/temp-dir)))
          cwd (fs/canonicalize ".")]
      (is (= tmp pwd) "the child ran from the neutral temp dir")
      (is (not= cwd pwd)
          "the child must not inherit the caller's project directory"))))

(deftest complete-still-delivers-stdin
  (is (= "hello prompt" (llm/complete! "cat" "hello prompt"))))

(deftest complete-throws-on-non-zero-exit
  (let [e (try (llm/complete! "false" nil)
               (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= :llm-command-failed (:type (ex-data e))))))
