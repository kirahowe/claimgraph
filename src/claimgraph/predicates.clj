(ns claimgraph.predicates
  "The controlled predicate vocabulary: 23 blessed :core/* predicates, each a
  first-class queryable row in the store, anchored to established standards
  (PROV-O / SPDX / DOAP / SKOS / Dublin Core) via :maps-to. New predicates are
  coined in the :x/* namespace with :testing status and promoted once proven."
  (:require [clojure.string :as str]))

(def seed
  [;; ---- Structural ----
   {:id :core/depends-on :label "depends on" :category :structural
    :object-kind :entity :cardinality :many :inverse-of :core/dependency-of
    :status :stable :default-epistemic :observation :maps-to "spdx:DEPENDS_ON"
    :definition "Subject requires the object to function (code, service, or build-time dependency)."}
   {:id :core/dependency-of :label "dependency of" :category :structural
    :object-kind :entity :cardinality :many :inverse-of :core/depends-on
    :status :stable :default-epistemic :observation :maps-to "spdx:DEPENDENCY_OF"
    :definition "Inverse of depends-on: the object requires the subject."}
   {:id :core/imports :label "imports" :category :structural
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "codeontology:imports"
    :definition "Subject source unit imports/requires the object unit."}
   ;; No :inverse-of: contains<->part-of is the one containment pair, and
   ;; defined-in has no true inverse here. Declaring one would give contains
   ;; two claimants and break the bijection (see logic-test).
   {:id :core/defined-in :label "defined in" :category :structural
    :object-kind :entity :cardinality :one
    :status :stable :default-epistemic :observation :maps-to "spdx:CONTAINED_BY"
    :definition "Subject (function, class, namespace) is defined in the object (file, module)."}
   {:id :core/contains :label "contains" :category :structural
    :object-kind :entity :cardinality :many :inverse-of :core/part-of
    :status :stable :default-epistemic :observation :maps-to "spdx:CONTAINS, dcterms:hasPart"
    :definition "Subject structurally contains the object."}
   {:id :core/part-of :label "part of" :category :structural
    :object-kind :entity :cardinality :many :inverse-of :core/contains
    :status :stable :default-epistemic :observation :maps-to "dcterms:isPartOf"
    :definition "Subject is a component of the larger object (module of a system, etc.)."}
   {:id :core/implements :label "implements" :category :structural
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "doap:implements, seon:implements"
    :definition "Subject implements the object interface, protocol, or specification."}
   {:id :core/written-in :label "written in" :category :structural
    :object-kind :either :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "doap:programming-language"
    :definition "Subject is implemented in the object programming language."}
   {:id :core/has-version :label "has version" :category :structural
    :object-kind :literal :cardinality :one
    :status :stable :default-epistemic :observation :maps-to "dcterms:hasVersion"
    :definition "Subject is at the object version (string literal)."}

   ;; ---- Procedural ----
   {:id :core/tested-by :label "tested by" :category :procedural
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "spdx:hasTest"
    :definition "Subject is exercised by the object test suite, file, or command."}
   {:id :core/built-with :label "built with" :category :procedural
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "spdx:BUILD_DEPENDENCY_OF"
    :definition "Subject is built using the object tool or build dependency."}
   {:id :core/generated-from :label "generated from" :category :procedural
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "spdx:GENERATED_FROM, prov:wasGeneratedBy"
    :definition "Subject artifact is generated from the object source."}
   {:id :core/deployed-via :label "deployed via" :category :procedural
    :object-kind :either :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "LOCAL"
    :definition "Subject is deployed using the object mechanism, pipeline, or command."}
   {:id :core/failure-mode :label "failure mode" :category :procedural
    :object-kind :literal :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "LOCAL"
    :definition "Subject has a known failure mode or hazard: the object records the lesson — what goes wrong, under what conditions, and what to do instead."}

   ;; ---- Decision / preference ----
   {:id :core/supersedes :label "supersedes" :category :decision
    :object-kind :entity :cardinality :many :inverse-of :core/superseded-by
    :exclusion-group :revision
    :status :stable :default-epistemic :commitment :maps-to "prov:wasRevisionOf, dcterms:replaces"
    :definition "Subject decision/record replaces the object decision/record."}
   {:id :core/superseded-by :label "superseded by" :category :decision
    :object-kind :entity :cardinality :many :inverse-of :core/supersedes
    :exclusion-group :revision
    :status :stable :default-epistemic :commitment :maps-to "dcterms:isReplacedBy"
    :definition "Inverse of supersedes."}
   {:id :core/decided-against :label "decided against" :category :decision
    :object-kind :either :cardinality :many
    :exclusion-group :stance
    :status :stable :default-epistemic :commitment :maps-to "LOCAL (ADR rejected-alternative)"
    :definition "A human decision explicitly rejected the object option. Outlives code state."}
   {:id :core/prefers :label "prefers" :category :decision
    :object-kind :either :cardinality :many
    :exclusion-group :stance :value-exclusivity :exclusive
    :status :stable :default-epistemic :preference :maps-to "LOCAL"
    :definition "Subject (person, project, module) prefers the object approach, idiom, or tool."}
   {:id :core/motivated-by :label "motivated by" :category :decision
    :object-kind :either :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "prov:wasInfluencedBy"
    :definition "Subject decision was motivated by the object reason, constraint, or event."}
   {:id :core/has-status :label "has status" :category :decision
    :object-kind :literal :cardinality :one
    :status :stable :default-epistemic :commitment :maps-to "LOCAL (ADR status)"
    :definition "Subject (typically a decision record) currently has the object status, e.g. proposed/accepted/superseded. Status history accumulates bi-temporally."}

   ;; ---- Provenance ----
   {:id :core/derived-from :label "derived from" :category :provenance
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "prov:wasDerivedFrom, dcterms:source"
    :definition "Subject was derived from the object source material."}
   {:id :core/asserted-by :label "asserted by" :category :provenance
    :object-kind :entity :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "prov:wasAttributedTo"
    :definition "Subject claim or artifact is attributed to the object agent or person."}
   {:id :core/primary-source :label "primary source" :category :provenance
    :object-kind :either :cardinality :many
    :status :stable :default-epistemic :observation :maps-to "prov:hadPrimarySource, dcterms:provenance"
    :definition "Subject's authoritative origin is the object document or artifact."}])

(defn levenshtein
  "Edit distance between two strings; used for :did-you-mean suggestions."
  [a b]
  (let [a (vec a) b (vec b)]
    (loop [i 0 prev (vec (range (inc (count b))))]
      (if (= i (count a))
        (peek prev)
        (recur (inc i)
               (loop [j 0 row [(inc i)]]
                 (if (= j (count b))
                   row
                   (recur (inc j)
                          (conj row (min (inc (peek row))
                                         (inc (prev (inc j)))
                                         (+ (prev j) (if (= (a i) (b j)) 0 1))))))))))))

(defn did-you-mean
  "Closest registered predicate ids to the unknown one, nearest first."
  [unknown registered-ids]
  (let [s (name unknown)]
    (->> registered-ids
         (map (fn [id] [(levenshtein s (name id)) id]))
         (filter (fn [[d _]] (<= d 5)))
         (sort-by first)
         (take 3)
         (mapv second))))

(defn experimental?
  "Predicates coined in the :x/* staging namespace."
  [pred-id]
  (= "x" (namespace pred-id)))

(defn auto-registration
  "Registry row for a first-use :x/* predicate."
  [pred-id]
  {:id pred-id
   :label (str/replace (name pred-id) "-" " ")
   :category :experimental
   :object-kind :either
   :cardinality :many
   :status :testing
   :definition "Auto-registered on first use; promote to :core/* once proven."})

(defn check
  "Pure check of a predicate id against its registry row (nil when absent).
  Returns {:ok row}, {:register row} for first-use :x/*, or {:error data} —
  the shell decides whether to register, throw, or enrich the error with
  :did-you-mean."
  [pred-id row]
  (cond
    (and row (= :deprecated (:status row)))
    {:error {:message (str "Predicate " pred-id " is deprecated")
             :type :deprecated-predicate
             :predicate pred-id
             :replaced-by (:replaced-by row)}}

    row {:ok row}

    (experimental? pred-id) {:register (auto-registration pred-id)}

    :else
    {:error {:message (str "Unknown predicate " pred-id)
             :type :unknown-predicate
             :predicate pred-id}}))
