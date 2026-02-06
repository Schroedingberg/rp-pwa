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
  1. Filter to set-level events only (those with :set-index)
  2. Dedupe events (latest timestamp wins per position)
  3. Transform to nested structure matching plan
  4. Deep merge with plan template
  
  Returns the plan structure with performed data merged in:
  - `:performed-weight`, `:performed-reps` when a set is logged
  - `:exercise-name`, `:muscle-groups` etc from the plan"
  [events plan]
  (let [set-events (filter :set-index events)
        event-map (-> set-events dedupe-by-latest events->plan-map)]
    (util/deep-merge-with merge-sets event-map plan)))

;; -----------------------------------------------------------------------------
;; Feedback detection
;; -----------------------------------------------------------------------------



(defn soreness-reported?
  "Has soreness already been reported for this muscle group in this workout?"
  [events {:keys [mesocycle microcycle workout muscle-group]}]
  (some #(and (= (:type %) :soreness-reported)
              (= (:mesocycle %) mesocycle)
              (= (:microcycle %) microcycle)
              (= (keyword (:workout %)) (keyword workout))
              (= (:muscle-group %) muscle-group))
        events))

(defn session-rated?
  "Has session rating already been given for this muscle group in this workout?"
  [events {:keys [mesocycle microcycle workout muscle-group]}]
  (some #(and (= (:type %) :session-rated)
              (= (:mesocycle %) mesocycle)
              (= (:microcycle %) microcycle)
              (= (keyword (:workout %)) (keyword workout))
              (= (:muscle-group %) muscle-group))
        events))

(defn muscle-group-sets
  "Get all sets (from plan+progress) for a muscle group in a workout.
  Returns a flat list of set-data maps."
  [progress mesocycle microcycle workout muscle-group]
  (let [exercises (get-in progress [mesocycle microcycle (keyword workout)])]
    (->> exercises
         vals
         (mapcat identity)
         (filter #(some #{muscle-group} (:muscle-groups %))))))

(defn muscle-group-started?
  "Has at least one set for this muscle group been completed/skipped?"
  [progress {:keys [mesocycle microcycle workout muscle-group]}]
  (let [sets (muscle-group-sets progress mesocycle microcycle workout muscle-group)]
    (some #(or (:performed-weight %) (= (:type %) :set-skipped)) sets)))

(defn muscle-group-finished?
  "Are all sets for this muscle group completed or skipped?"
  [progress {:keys [mesocycle microcycle workout muscle-group]}]
  (let [sets (muscle-group-sets progress mesocycle microcycle workout muscle-group)]
    (and (seq sets)
         (every? #(or (:performed-weight %) (= (:type %) :set-skipped)) sets))))

(defn last-active-workout
  "Get the workout context from the most recently logged set.
  Returns {:mesocycle ... :microcycle ... :workout ...} or nil."
  [events]
  (when-let [last-set (->> events
                           (filter :set-index)
                           (sort-by :timestamp)
                           last)]
    {:mesocycle (:mesocycle last-set)
     :microcycle (:microcycle last-set)
     :workout (keyword (:workout last-set))}))

(defn pending-soreness-feedback
  "Return muscle groups that need soreness feedback in this workout.
  (Started but not yet reported.)"
  [events progress {:keys [mesocycle microcycle workout]} workout-muscle-groups]
  (->> workout-muscle-groups
       (filter #(muscle-group-started? progress {:mesocycle mesocycle :microcycle microcycle
                                                 :workout workout :muscle-group %}))
       (remove #(soreness-reported? events {:mesocycle mesocycle :microcycle microcycle
                                            :workout workout :muscle-group %}))))

(defn pending-session-rating
  "Return muscle groups that need session rating in this workout.
  (Finished but not yet rated.)"
  [events progress {:keys [mesocycle microcycle workout]} workout-muscle-groups]
  (->> workout-muscle-groups
       (filter #(muscle-group-finished? progress {:mesocycle mesocycle :microcycle microcycle
                                                  :workout workout :muscle-group %}))
       (remove #(session-rated? events {:mesocycle mesocycle :microcycle microcycle
                                        :workout workout :muscle-group %}))))
