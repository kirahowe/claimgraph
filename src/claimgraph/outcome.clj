(ns claimgraph.outcome
  "The outcome signal (review §3.5): usefulness reaches the store without
  learned components. Read verbs append the fact ids they surfaced to a
  retrieval log next to the db — reads stay reads; the log defers the
  decision. When the work's outcome is known, `outcome accepted` reinforces
  every fact retrieved since the last mark (disuse clock reset, never a
  confidence raise — usefulness is evidence of aliveness, not of truth),
  and `outcome rejected` reinforces nothing and reports the facts that were
  in play, the review candidates. The rejection's LESSON arrives separately
  through ingest-failure (#15) — the two halves of the valence signal.

  The log is plain JSONL, capped, and entirely local; losing it costs
  nothing but reinforcement opportunities."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [claimgraph.store :as store]))

(def max-log-entries 1000)

(defn log-file [db] (str db ".retrievals"))

(defn- append!
  "One entry, stamped. The stamp is epoch millis rather than an encoded date
  because this log never leaves the machine that wrote it — see
  claimgraph.wire on which artifacts carry a Date and why. Without it an entry
  cannot be aged out at all: the cap below is a line count, so a store that
  goes quiet for a month keeps month-old retrievals as evidence of what was
  useful, and `outcome accepted` reinforces on them."
  [db m]
  (try
    (let [f (log-file db)]
      (spit f (str (json/generate-string (assoc m :at (System/currentTimeMillis))) "\n")
            :append true)
      ;; cap: keep the newest half when the log doubles the cap
      (let [lines (str/split-lines (slurp f))]
        (when (> (count lines) (* 2 max-log-entries))
          (spit f (str (str/join "\n" (take-last max-log-entries lines)) "\n")))))
    (catch Exception _ nil)))

(defn log-reads!
  "Record that a read surfaced these facts. Failure-proof and silent — a
  broken log must never break a read."
  [db verb fact-ids]
  (when (seq fact-ids)
    (append! db {:type "read" :verb (name verb) :fact-ids (vec (distinct fact-ids))})))

(defn- entries [db]
  (try
    (when (fs/exists? (log-file db))
      (->> (str/split-lines (slurp (log-file db)))
           (remove str/blank?)
           (keep #(try (json/parse-string % true) (catch Exception _ nil)))
           vec))
    (catch Exception _ [])))

(defn since-last-mark
  "Pure: the read entries after the most recent outcome mark."
  [es]
  (->> (reverse es)
       (take-while #(not= "outcome" (:type %)))
       reverse
       (filterv #(= "read" (:type %)))))

(defn outcome!
  "Consume the retrievals since the last mark under a valence.
  opts: :valence :accepted|:rejected"
  [s db {:keys [valence]}]
  (let [valence (keyword valence)
        _ (when-not (#{:accepted :rejected} valence)
            (throw (ex-info "valence must be accepted or rejected"
                            {:type :invalid-valence :given valence})))
        reads (since-last-mark (entries db))
        ids (vec (distinct (mapcat :fact-ids reads)))
        facts (if (seq ids) (store/-select-facts s {:ids ids}) [])
        now (java.util.Date.)]
    (when (= :accepted valence)
      (doseq [f facts]
        ;; clock reset at the existing base: retrieval-in-accepted-work is
        ;; evidence of usefulness, never of higher confidence
        (store/-reinforce s (:id f) {:at now :confidence (:confidence f)})))
    (append! db {:type "outcome" :valence (name valence) :facts (count facts)})
    {:status :recorded
     :valence valence
     :reads (count reads)
     :facts-in-play (count facts)
     :reinforced (if (= :accepted valence) (count facts) 0)
     :review (when (= :rejected valence)
               (mapv (fn [f] {:id (:id f)
                              :subject (get-in f [:subject :name])
                              :predicate (:predicate f)
                              :object (or (get-in f [:object-ref :name])
                                          (:object-lit f))})
                     facts))}))
