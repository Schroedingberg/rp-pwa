;; # Architecture: Event Sourcing
;;
;; Deep dive into the event sourcing pattern used in this app.

^{:nextjournal.clerk/visibility {:code :hide}}
(ns architecture
  {:nextjournal.clerk/toc true}
  (:require [nextjournal.clerk :as clerk]))

;; ## Why Event Sourcing?
;;
;; Traditional apps store **current state**: "User has 5 items in cart."
;;
;; Event sourcing stores **facts**: "User added item A. User added item B. User removed item A."
;;
;; Benefits for workout tracking:
;;
;; 1. **Complete history** - Every set ever performed is preserved
;; 2. **Corrections without data loss** - Later events override earlier ones
;; 3. **Algorithm flexibility** - Progression code can change without migration
;; 4. **Offline safety** - Events can be replayed after sync conflicts

;; ## The Core Pattern
;;
;; ### Event Structure
;;
;; Events are flat maps with these required keys:

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def example-event
  {:type :set-completed
   :mesocycle "Hypertrophy Block 1"
   :microcycle "Week 1"
   :workout :push-a
   :exercise "Bench Press"
   :set-index 0
   :performed-weight 135
   :performed-reps 10
   :prescribed-weight 130
   :prescribed-reps 10
   :timestamp 1707235200000
   :id "abc-123"})

;; The event captures:
;; - **WHERE** (mesocycle, microcycle, workout, exercise, set-index)
;; - **WHAT** (type, performed-weight, performed-reps)
;; - **WHEN** (timestamp)
;; - **CONTEXT** (prescribed values for deviation analysis)

;; ### State Reconstruction
;;
;; The `view-progress-in-plan` function merges events with a plan template:

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(comment
  ;; Input: flat events
  [{:type :set-completed :workout :push-a :exercise "Bench" :set-index 0 :performed-weight 135}
   {:type :set-completed :workout :push-a :exercise "Bench" :set-index 1 :performed-weight 135}]
  
  ;; + Plan template
  {"Meso 1" {"Week 1" {:push-a {"Bench" [{:weight 130 :reps 10}
                                          {:weight 130 :reps 10}
                                          {:weight 130 :reps 10}]}}}}
  
  ;; = Merged result
  {"Meso 1" {"Week 1" {:push-a {"Bench" [{:weight 130 :reps 10 :performed-weight 135}
                                          {:weight 130 :reps 10 :performed-weight 135}
                                          {:weight 130 :reps 10}]}}}}) ;; third set still pending

;; ### Deduplication
;;
;; If user logs the same set twice, only the latest event wins:

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(comment
  ;; User logs 135 lbs...
  {:set-index 0 :performed-weight 135 :timestamp 1000}
  
  ;; Then corrects to 140 lbs
  {:set-index 0 :performed-weight 140 :timestamp 2000}
  
  ;; dedupe-by-latest keeps only the correction
  ;; → {:set-index 0 :performed-weight 140 :timestamp 2000}
  )

;; ## Data Flow Diagram
;;
;; ```
;; ┌──────────────────────────────────────────────────────────────────┐
;; │                         User Action                              │
;; │                    (check off a set in UI)                       │
;; └──────────────────────────────┬───────────────────────────────────┘
;;                                │
;;                                ▼
;; ┌──────────────────────────────────────────────────────────────────┐
;; │                      rp.db/log-set!                              │
;; │         Create event map, transact to DataScript                 │
;; └──────────────────────────────┬───────────────────────────────────┘
;;                                │
;;                                ▼
;; ┌──────────────────────────────────────────────────────────────────┐
;; │                      DataScript conn                             │
;; │              In-memory database of all events                    │
;; └──────────────────────────────┬───────────────────────────────────┘
;;                                │
;;               ┌────────────────┴────────────────┐
;;               ▼                                 ▼
;; ┌─────────────────────────┐     ┌──────────────────────────────────┐
;; │    rp.storage/save!     │     │       db-version atom            │
;; │  Persist to localStorage │     │   Increments → triggers re-render│
;; └─────────────────────────┘     └──────────────────────────┬───────┘
;;                                                             │
;;                                                             ▼
;;                                 ┌──────────────────────────────────┐
;;                                 │   rp.state/view-progress-in-plan │
;;                                 │   Merge events with plan template │
;;                                 └──────────────────────────────────┘
;; ```

;; ## Key Functions

;; ### `rp.db/transact-event!`
;;
;; Auto-adds `:id` and `:timestamp` to every event:
;;
;; ```clojure
;; (defn- transact-event! [event]
;;   (d/transact! conn [(-> event
;;                          ns-keys
;;                          (assoc :event/id (str (random-uuid))
;;                                 :event/timestamp (js/Date.now)))]))
;; ```

;; ### `rp.state/events->plan-map`
;;
;; Transforms flat events into nested structure:
;;
;; ```clojure
;; (defn- events->plan-map [events]
;;   (reduce
;;    (fn [acc {:keys [mesocycle microcycle workout exercise set-index] :as event}]
;;      (update-in acc [mesocycle microcycle (keyword workout) exercise]
;;                 (fn [sets]
;;                   (let [sets (or sets [])
;;                         padded (into sets (repeat (max 0 (- (inc set-index) (count sets))) {}))]
;;                     (assoc padded set-index event)))))
;;    {}
;;    events))
;; ```

;; ### `rp.util/deep-merge-with`
;;
;; Recursively merges nested maps, with custom merge function for leaf vectors:
;;
;; ```clojure
;; (defn deep-merge-with [f & maps]
;;   (apply merge-with
;;          (fn [a b]
;;            (if (and (map? a) (map? b))
;;              (deep-merge-with f a b)
;;              (f a b)))
;;          maps))
;; ```

;; ---
;;
;; [← Back to Index](./index)
