(ns rp.state
  "Reconstruct workout progress from an event log.
  
  The key function is `view-progress-in-plan` which merges:
  - A flat list of workout events (what you did)
  - A structured plan (what you should do)
  
  Into a single nested map showing both planned and performed data.
  
  When multiple events exist for the same set position (corrections),
  the latest timestamp wins."
  (:require [rp.util :as util]))

(defn- set-location
  "Extract the natural key that identifies a set position."
  [event]
  (select-keys event [:mesocycle :microcycle :workout :exercise :set-index]))

(defn- dedupe-by-latest
  "Given events sorted by timestamp, keep only the latest for each set position.
  This enables corrections: just log another event for the same position."
  [events]
  (->> events
       (group-by set-location)
       vals
       (map #(apply max-key :timestamp %))))

(defn- events->plan-map
  "Transform flat events into nested plan structure.
  
  Input:  [{:mesocycle \"X\" :microcycle 0 :workout :monday :exercise \"Squat\" :set-index 0 ...}]
  Output: {\"X\" {0 {:monday {\"Squat\" [{event} nil nil ...]}}}}"
  [events]
  (reduce
   (fn [acc {:keys [mesocycle microcycle workout exercise set-index] :as event}]
     (update-in acc [mesocycle microcycle (keyword workout) exercise]
                (fn [sets]
                  (let [sets (or sets [])
                        padded (into sets (repeat (max 0 (- (inc set-index) (count sets))) {}))]
                    (assoc padded set-index event)))))
   {}
   events))

(defn- merge-sets
  "Merge two vectors of sets, combining planned and performed data."
  [performed planned]
  (let [n (max (count performed) (count planned))]
    (mapv #(merge (nth planned % {}) (nth performed % {}))
          (range n))))

(defn view-progress-in-plan
  "Merge event log with plan to show progress.
  
  Pipeline:
  1. Dedupe events (latest timestamp wins per position)
  2. Transform to nested structure matching plan
  3. Deep merge with plan template
  
  Returns the plan structure with performed data merged in:
  - `:performed-weight`, `:performed-reps` when a set is logged
  - `:exercise-name`, `:muscle-groups` etc from the plan"
  [events plan]
  (let [event-map (-> events dedupe-by-latest events->plan-map)]
    (util/deep-merge-with merge-sets event-map plan)))
