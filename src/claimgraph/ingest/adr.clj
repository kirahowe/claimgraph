(ns claimgraph.ingest.adr
  "Decision-record (ADR) ingester: the highest-authority source, parsed
  MECHANICALLY — no LLM anywhere. Decision records are structured enough to
  parse, they are the one source that warrants decision-record confidence
  (1.0 ceiling), and spec-kit-style constitution/spec output is now the
  largest authoring pipeline in the field — this is the \"eat their output\"
  wedge (comparison §6.7).

  Grammar (MADR-flavored, forgiving):
    # ADR-7: Use argon2              -> the ADR entity (or from NNNN-*.md)
    Status: accepted                 -> has-status (a status CHANGE in the
                                        file supersedes: the record is the
                                        source of truth for its own status,
                                        and history accumulates bi-temporally)
    Supersedes: ADR-3                -> core/supersedes (entity edge)
    Superseded by: ADR-12            -> core/superseded-by
    ## Considered Options            -> each option NOT chosen becomes a
    * kuzu-db                           decided-against commitment
    * plain LMDB
    Chosen option: \"plain LMDB\"
    Rejected: GraphQL, SOAP          -> explicit rejections, same treatment

  Functional core / imperative shell: parsing and fact derivation are pure;
  ingest! walks a directory and drives core/ingest. Re-runs reinforce; a
  changed status supersedes its predecessor; nothing is reconciled away —
  decision records outlive code state by design."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [claimgraph.core :as core]))

;; ---------------------------------------------------------------------------
;; Pure: parsing
;; ---------------------------------------------------------------------------

(defn- adr-id
  "From the title line (# ADR-7: ...) or the filename (0007-use-argon2.md)."
  [filename content]
  (or (some->> (re-find #"(?im)^#\s*(ADR[-\s]?\d+)" (str content))
               second
               (#(str/replace % #"\s+" "-"))
               str/upper-case)
      (some->> (re-find #"^(\d{1,5})\D" (str (fs/file-name filename)))
               second
               Long/parseLong
               (str "ADR-"))
      (str (fs/strip-ext (fs/file-name filename)))))

(defn- header-list
  "Comma-separated values of a `Key: a, b` header line (case-insensitive)."
  [content re]
  (some->> (re-find re (str content))
           second
           (#(str/split % #","))
           (map str/trim)
           (remove str/blank?)
           vec))

(defn- section-options
  "List items under '## Considered Options' (or '## Options'), up to the
  next heading."
  [content]
  (when-let [[_ body] (re-find #"(?ims)^##\s*(?:considered\s+)?options\s*\n(.*?)(?:\n#|\z)"
                               (str content))]
    (->> (str/split-lines body)
         (keep #(second (re-find #"^\s*[-*]\s+(.+?)\s*$" %)))
         (map #(str/replace % #"^[\"']|[\"']$" ""))
         vec)))

(defn- chosen-option [content]
  (some-> (re-find #"(?i)chosen option:?\s*[\"']?([^\"'\n,.]+)" (str content))
          second
          str/trim))

(defn parse-adr
  "Pure: one ADR file -> what it decides."
  [filename content]
  (let [chosen (chosen-option content)
        options (or (section-options content) [])
        norm #(str/lower-case (str/trim (str %)))
        rejected (concat (when chosen
                           (remove #(= (norm chosen) (norm %)) options))
                         (or (header-list content #"(?im)^rejected(?:\s+alternatives)?:\s*(.+)$")
                             []))]
    {:id (adr-id filename content)
     :title (some-> (re-find #"(?m)^#\s*(?:ADR[-\s]?\d+\s*[:—-]\s*)?(.+)$" (str content))
                    second str/trim)
     :status (some-> (or (second (re-find #"(?im)^status:\s*(\w[\w\s-]*)$" (str content)))
                         (second (re-find #"(?is)^##\s*status\s*\n+\s*(\w[\w-]*)" (str content))))
                     str/trim str/lower-case)
     :supersedes (or (header-list content #"(?im)^supersedes:\s*(.+)$") [])
     :superseded-by (or (header-list content #"(?im)^superseded[\s-]by:\s*(.+)$") [])
     :rejected (vec (distinct rejected))}))

(defn adr->facts
  "Pure: parsed ADR -> fact maps for core/ingest, all at decision-record
  authority. The status fact supersedes on conflict — the record is the
  source of truth for its own status, and the old status stays in history."
  [{:keys [id status supersedes superseded-by rejected]}]
  (let [base {:subject id :subject-type :decision-record
              :source-type :decision-record :confidence 1.0}]
    (vec
     (concat
      (when-not (str/blank? (str status))
        [(merge base {:predicate :core/has-status :object status
                      :object-kind :literal :on-conflict :supersede})])
      (for [other supersedes]
        (merge base {:predicate :core/supersedes :object other
                     :object-kind :entity :object-type :decision-record}))
      (for [other superseded-by]
        (merge base {:predicate :core/superseded-by :object other
                     :object-kind :entity :object-type :decision-record}))
      (for [r rejected]
        (merge base {:predicate :core/decided-against :object r
                     :object-kind :literal :epistemic :commitment}))))))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn ingest!
  "Parse and ingest every ADR under a directory (or one --file), one
  :decision-record episode per pass.
  opts: :dir (default docs/adr, then docs/decisions, then adr) | :file
        :dry-run"
  [s {:keys [dir file dry-run]}]
  (let [files (cond
                file [(str file)]
                dir (mapv str (fs/glob dir "**.md"))
                :else (some #(when (fs/directory? %)
                               (mapv str (fs/glob % "**.md")))
                            ["docs/adr" "docs/decisions" "adr" "docs/architecture/decisions"]))
        parsed (mapv #(assoc (parse-adr % (slurp %)) :file %) (sort (or files [])))
        facts (vec (mapcat adr->facts parsed))]
    (cond
      (empty? files)
      {:status :no-adr-dir
       ;; --adr-dir, not the --dir it answered to first: the alias still
       ;; dispatches, and a failure path that teaches it is how a deprecated
       ;; spelling outlives its own deprecation.
       :hint "pass --adr-dir or --file; looked in docs/adr, docs/decisions, adr"}

      dry-run
      {:status :dry-run :adrs (mapv #(dissoc % :file) parsed) :facts facts}

      :else
      (let [result (core/ingest s {:source-type :decision-record
                                   :ref (str "adr:" (or dir file))}
                                facts)]
        (core/close-episode s {:episode (:episode result)
                               :summary (str "ADR pass: " (count parsed) " records ("
                                             (str/join ", " (map :id parsed)) "), "
                                             (:total result) " facts")})
        (assoc result :adrs (count parsed))))))
