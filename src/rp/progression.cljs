(ns rp.progression
  "Compute workout prescriptions from training history.
  
  Core principles:
  - No planning ahead — prescriptions computed on-the-fly from events
  - Weight-first progression — add weight each session
  - Work preservation — if user overrides weight, adjust reps to maintain
    similar total work increase
  - Feedback-driven — adjust weight increment based on recovery and session feedback
  
  Work model: work ≈ weight × reps (simplified)
  
  Example:
    Last session: 100kg × 10 = 1000 work
    Prescribed:   102.5kg × 10 = 1025 work (+2.5%)
    User picks 110kg → reps adjusted to 1025/110 ≈ 9 reps")

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(def ^:private base-weight-increment 2.5)  ; kg to add each session

;; Feedback-based increment modifiers
(def ^:private soreness-modifiers
  {:never-sore         1.5    ; Recovered fast, push harder
   :healed-early       1.25   ; Recovered well, slight increase
   :healed-just-in-time 1.0   ; Perfect recovery, maintain
   :still-sore         0.5})  ; Still recovering, back off

(def ^:private workload-modifiers
  {:easy          1.25   ; Session felt easy, push harder
   :just-right    1.0    ; Perfect effort
   :pushed-limits 1.0    ; Good challenge, maintain
   :too-much      0.75}) ; Overreached, reduce

(def ^:private joint-pain-override
  {:none   nil      ; No override
   :some   0.75     ; Reduce due to discomfort
   :severe 0.0})    ; Zero increase with pain

;; Rep ↔ %1RM lookup table
;; Covers hypertrophy range (5-30 reps), with lower reps for 1RM estimation
;; Values tuned to match expected rep adjustments from user feedback
(def ^:private rep-percentage-table
  {1  1.00
   2  0.95
   3  0.93
   4  0.90
   5  0.87
   6  0.85
   7  0.83
   8  0.80
   9  0.77
   10 0.75
   11 0.72
   12 0.70
   15 0.65
   20 0.60
   25 0.55
   30 0.50})

(defn- lerp
  "Linear intERPolation: returns value between a and b based on t (0-1)."
  [a b t]
  (+ a (* t (- b a))))

(defn- interpolate-in-table
  "Look up value in table; interpolate linearly between neighbors if not found."
  [table value]
  (let [ks (keys table)
        lower (apply max (filter #(< % value) ks))
        upper (apply min (filter #(> % value) ks))
        t (/ (- value lower) (- upper lower))]
    (lerp (table lower) (table upper) t)))

(defn- reps->percentage
  "Convert reps to %1RM. Interpolates between known values."
  [reps]
  (let [reps (max 1 (min 30 reps))]
    (or (get rep-percentage-table reps)
        (interpolate-in-table rep-percentage-table reps))))

;; Inverted table: percentage -> reps (for reverse lookup)
(def ^:private percentage-rep-table
  (into {} (map (fn [[r p]] [p r]) rep-percentage-table)))

(defn- percentage->reps
  "Convert %1RM to reps. Interpolates between known values."
  [pct]
  (let [pct (max 0.50 (min 1.0 pct))]
    (or (get percentage-rep-table pct)
        (interpolate-in-table percentage-rep-table pct))))

(defn- estimate-1rm
  "Estimate 1RM from weight and reps using percentage table."
  [weight reps]
  (/ weight (reps->percentage reps)))

(defn- reps-for-weight
  "Calculate reps for a given weight based on estimated 1RM."
  [one-rm weight]
  (let [pct (/ weight one-rm)]
    (js/Math.round (percentage->reps pct))))

;; -----------------------------------------------------------------------------
;; History queries
;; -----------------------------------------------------------------------------

(defn- same-slot?
  "Check if event is for the same workout slot (exercise + set-index on same day)."
  [event {:keys [mesocycle workout exercise set-index]}]
  (and (= (:mesocycle event) mesocycle)
       (= (keyword (:workout event)) (keyword workout))
       (= (:exercise event) exercise)
       (= (:set-index event) set-index)))

(defn last-performance
  "Find the most recent completed set for the same slot in a PREVIOUS microcycle.
  Returns nil if no history exists."
  [events {:keys [microcycle] :as location}]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(same-slot? % location))
       (filter #(< (:microcycle %) microcycle))  ; only previous weeks
       (sort-by :timestamp)
       last))

(defn all-performances
  "Get all completed sets for this slot, across all microcycles."
  [events location]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(same-slot? % location))
       (sort-by :timestamp)))

;; -----------------------------------------------------------------------------
;; Feedback queries
;; -----------------------------------------------------------------------------

(defn- get-feedback
  "Get the latest feedback of given type for a muscle group from previous microcycle."
  [events event-type {:keys [mesocycle microcycle]} muscle-group]
  (->> events
       (filter #(= (:type %) event-type))
       (filter #(= (:mesocycle %) mesocycle))
       (filter #(= (:microcycle %) (dec microcycle))) ; previous week's feedback
       (filter #(some #{(:muscle-group %)} (if (keyword? muscle-group)
                                             [muscle-group]
                                             muscle-group)))
       (sort-by :timestamp)
       last))

(defn- compute-weight-increment
  "Calculate weight increment based on feedback from previous microcycle.
  Returns the adjusted increment (may be 0 if joint pain is severe)."
  [events location muscle-groups]
  (if (or (nil? muscle-groups) (<= (:microcycle location) 0))
    ;; No muscle groups or first week: use base increment
    base-weight-increment
    ;; Check feedback for any of the exercise's muscle groups
    (let [mg (first muscle-groups)  ; Use primary muscle group
          soreness (get-feedback events :soreness-reported location mg)
          session (get-feedback events :session-rated location mg)

          ;; Calculate modifiers
          soreness-mod (get soreness-modifiers (:soreness soreness) 1.0)
          workload-mod (get workload-modifiers (:sets-workload session) 1.0)
          pain-override (get joint-pain-override (:joint-pain session))]

      ;; Joint pain overrides everything
      (if (some? pain-override)
        (* base-weight-increment pain-override)
        ;; Otherwise combine modifiers
        (* base-weight-increment soreness-mod workload-mod)))))

;; -----------------------------------------------------------------------------
;; Prescription
;; -----------------------------------------------------------------------------

(defn prescribe-weight
  "Suggest weight for next set: last weight + feedback-adjusted increment.
  Returns nil if no history (first workout of meso).
  
  muscle-groups is optional - if provided, feedback for those groups affects increment."
  ([events location]
   (prescribe-weight events location nil))
  ([events location muscle-groups]
   (when-let [last-perf (last-performance events location)]
     (let [increment (compute-weight-increment events location muscle-groups)]
       (+ (:performed-weight last-perf) increment)))))

(defn prescribe-reps
  "Suggest reps for next set.
  
  If actual-weight is provided and differs from prescribed weight,
  adjusts reps using 1RM-based calculation to maintain equivalent intensity.
  
  Returns nil if no history."
  ([events location]
   (prescribe-reps events location nil nil))
  ([events location actual-weight]
   (prescribe-reps events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   (when-let [last-perf (last-performance events location)]
     (let [last-weight (:performed-weight last-perf)
           last-reps (:performed-reps last-perf)
           increment (compute-weight-increment events location muscle-groups)
           prescribed-weight (+ last-weight increment)
           ;; Estimate 1RM from last performance
           estimated-1rm (estimate-1rm last-weight last-reps)]
       (if (and actual-weight (not= actual-weight prescribed-weight))
         ;; User overrode weight → adjust reps using 1RM curve
         (max 1 (reps-for-weight estimated-1rm actual-weight))
         ;; Use last reps as target
         last-reps)))))

(defn prescribe
  "Get full prescription for a set location.
  Returns {:weight ... :reps ...} or nil for each if no history.
  
  muscle-groups is optional - if provided, feedback for those groups affects increment."
  ([events location]
   (prescribe events location nil nil))
  ([events location actual-weight]
   (prescribe events location actual-weight nil))
  ([events location actual-weight muscle-groups]
   {:weight (prescribe-weight events location muscle-groups)
    :reps (prescribe-reps events location actual-weight muscle-groups)}))

;; -----------------------------------------------------------------------------
;; Analysis (for future feedback-based volume adjustment)
;; -----------------------------------------------------------------------------

(defn exercise-volume
  "Count completed sets for an exercise in a microcycle."
  [events {:keys [mesocycle microcycle workout exercise]}]
  (->> events
       (filter #(= (:type %) :set-completed))
       (filter #(and (= (:mesocycle %) mesocycle)
                     (= (:microcycle %) microcycle)
                     (= (keyword (:workout %)) (keyword workout))
                     (= (:exercise %) exercise)))
       count))


(comment

  (prescribe-reps [{:type :set-completed
                    :mesocycle "plan1"
                    :microcycle 0
                    :workout "day1"
                    :exercise "squat"
                    :set-index 0
                    :performed-weight 100
                    :performed-reps 10
                    :timestamp 123456789}]
                  {:mesocycle "plan1" :microcycle 1 :workout "day1" :exercise "squat" :set-index 0}
                  105)


  ;; end of rich comment block
  ())