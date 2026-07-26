(ns claimgraph.evidence-test
  "The raw-evidence tier: content-addressed immutability as plain functions,
  and the ingest paths stamping episodes with the artifact they were
  extracted from — no LLM, temp dirs only."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [claimgraph.core :as core]
            [claimgraph.evidence :as evidence]
            [claimgraph.ingest.notes :as notes]
            [claimgraph.ingest.session :as session]
            [claimgraph.store :as store]
            [claimgraph.store.memory :as mem]))

(deftest artifacts-are-content-addressed-and-immutable
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-test"}))
        h1 (evidence/write! dir "kira: we chose argon2\n")
        h2 (evidence/write! dir "kira: we chose argon2\n")
        h3 (evidence/write! dir "different content")]
    (is (= h1 h2) "identical content is one artifact")
    (is (not= h1 h3))
    (is (re-matches #"sha256-[0-9a-f]{64}" h1)
        "the name says which function produced it, so a later one can differ")
    (is (= h1 (evidence/content-hash "kira: we chose argon2\n"))
        "the minted identity is the pointer episodes carry — one form, not two")
    (is (some #(= h1 (str (fs/file-name %))) (fs/list-dir dir))
        "the pointer IS the filename, which is why the tag uses a hyphen")
    (is (= "kira: we chose argon2\n" (evidence/fetch dir h1)))
    (is (nil? (evidence/fetch dir "0000dead")) "absent artifacts are nil, not errors")
    (is (nil? (evidence/fetch dir nil)))
    (is (= 2 (count (fs/list-dir dir))) "write-once: two contents, two files")))

(deftest legacy-bare-hex-pointers-still-resolve
  (testing "artifacts written before the algorithm tag are named bare hex"
    (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-legacy"}))
          content "kira: argon2, decided before the tag existed\n"
          hex (subs (evidence/content-hash content) (count "sha256-"))]
      (fs/create-dirs dir)
      (spit (str (fs/path dir hex)) content)
      (is (= content (evidence/fetch dir hex))
          "a bare-hex pointer from an existing store still reaches its bytes")
      (is (= content (evidence/fetch dir (str "sha256-" hex)))
          "and the tagged pointer finds the same bytes under the old name")
      (is (= 1 (count (fs/list-dir dir))) "reading legacy artifacts rewrites nothing"))))

(deftest write-is-idempotent-against-a-legacy-artifact
  (testing "re-ingesting content an upgraded store already holds writes nothing"
    (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-legacy-write"}))
          content "legacy bytes\n"
          hex (subs (evidence/content-hash content) (count "sha256-"))]
      (fs/create-dirs dir)
      (spit (str (fs/path dir hex)) content)
      (let [pointer (evidence/write! dir content)]
        (is (= 1 (count (fs/list-dir dir)))
            "stored under the old name is stored: no second, identical copy")
        (is (= hex (str (fs/file-name (first (fs/list-dir dir)))))
            "and the bytes stay where they are — write-once means never rewritten")
        (is (= (evidence/content-hash content) pointer)
            "the tagged name is the identity new episodes carry, whatever the file is called")
        (is (= content (evidence/fetch dir pointer))
            "which is only safe because that pointer still resolves via the legacy name")))))

(deftest a-directory-in-an-artifact-slot-is-not-a-stored-artifact
  (testing "write! and fetch agree on what counts as present"
    (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-occupied"}))
          content "occupied\n"
          pointer (evidence/content-hash content)]
      (fs/create-dirs (fs/path dir pointer))
      (is (nil? (evidence/fetch dir pointer)))
      (is (thrown? java.io.FileNotFoundException (evidence/write! dir content))
          "a pointer to a directory is a pointer to nothing; better loud than recorded"))))

(deftest session-extract-stamps-its-episode
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-test"}))
        s (mem/create)
        _ (core/seed! s)
        transcript "user: we prefer Result types everywhere"
        r (session/extract! s {:transcript transcript
                               :ref "sess-9"
                               :evidence-dir dir
                               :extractor-fn (fn [_] "{\"subject\":\"api\",\"predicate\":\"prefers\",\"object\":\"Result types\",\"class\":\"preference\"}")})]
    (testing "the episode points at the artifact; the artifact is the raw input"
      (is (string? (:evidence r)))
      (let [ep (store/-get-episode s (:episode r))]
        (is (= (:evidence r) (:evidence ep)))
        (is (= transcript (evidence/fetch dir (:evidence ep)))
            "which-utterance provenance: the exact bytes, not a summary")))
    (testing "the fact's provenance chain reaches the raw bytes"
      (let [f (first (:facts (core/get-facts s {:entity "api"})))
            ep (store/-get-episode s (:episode f))]
        (is (str/includes? (evidence/fetch dir (:evidence ep)) "Result types"))))
    (testing "without an evidence dir, nothing is stored and nothing breaks"
      (let [r2 (session/extract! s {:transcript "user: also gotcha X" :ref "sess-10"
                                    :extractor-fn (fn [_] "")})]
        (is (nil? (:evidence r2)))))))

(deftest notes-ingest-stamps-per-file-revisions
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-notes"}))
        ev-dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-store"}))
        s (mem/create)
        _ (core/seed! s)]
    (spit (str dir "/MEMORY.md") "# Notes\ndurable claim one\n")
    (notes/ingest! s {:dir dir :evidence-dir ev-dir :extractor-fn (fn [_] "")})
    (spit (str dir "/MEMORY.md") "# Notes\ndurable claim one\nand claim two\n")
    (notes/ingest! s {:dir dir :evidence-dir ev-dir :extractor-fn (fn [_] "")})
    (let [eps (filter #(= :agent-note (:source-type %)) (store/-list-episodes s))]
      (is (= 2 (count eps)) "one episode per (file, revision)")
      (is (= 2 (count (distinct (map :evidence eps)))) "each revision its own artifact")
      (is (every? #(str/includes? (evidence/fetch ev-dir (:evidence %)) "durable claim one")
                  eps)
          "every revision's exact bytes are recoverable"))))

(deftest evidence-round-trips-through-dump-and-load
  (let [dir (str (fs/create-temp-dir {:prefix "claimgraph-evidence-test"}))
        s (mem/create)
        _ (core/seed! s)
        _ (session/extract! s {:transcript "user: prefer EDN" :ref "sess-11"
                               :evidence-dir dir
                               :extractor-fn (fn [_] "{\"subject\":\"api\",\"predicate\":\"prefers\",\"object\":\"EDN\",\"class\":\"preference\"}")})
        dst (mem/create)
        _ (core/seed! dst)]
    (core/load-dump dst (map #(cheshire.core/parse-string
                               (cheshire.core/generate-string %) true)
                             (core/dump s)))
    (is (= (mapv :evidence (store/-list-episodes s))
           (mapv :evidence (store/-list-episodes dst)))
        "the pointer rides the dump; the bytes stay local")))
