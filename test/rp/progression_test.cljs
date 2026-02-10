(ns rp.progression-test
  (:require [cljs.test :refer [deftest is testing]]
            [rp.progression :as prog]))

;; -----------------------------------------------------------------------------
;; Test data
;; -----------------------------------------------------------------------------

(def sample-events
  [{:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Squat" :set-index 0
    :performed-weight 100 :performed-reps 10 :timestamp 1000}
   {:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Squat" :set-index 1
    :performed-weight 100 :performed-reps 9 :timestamp 1001}
   {:type :set-completed
    :mesocycle "Plan" :microcycle 0 :workout :monday :exercise "Press" :set-index 0
    :performed-weight 60 :performed-reps 8 :timestamp 1002}])

;; -----------------------------------------------------------------------------
;; last-performance tests
;; -----------------------------------------------------------------------------

(deftest last-performance-test
  (testing "finds last performance from previous microcycle"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/last-performance sample-events location)]
      (is (some? result))
      (is (= 100 (:performed-weight result)))
      (is (= 10 (:performed-reps result)))))

  (testing "returns nil when no previous microcycle exists"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/last-performance sample-events location)))))

  (testing "returns nil for unknown exercise"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Deadlift" :set-index 0}]
      (is (nil? (prog/last-performance sample-events location)))))

  (testing "distinguishes between set indices"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 1}
          result (prog/last-performance sample-events location)]
      (is (= 9 (:performed-reps result))))))

;; -----------------------------------------------------------------------------
;; prescribe-weight tests
;; -----------------------------------------------------------------------------

(deftest prescribe-weight-test
  (testing "adds increment to last weight"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 102.5 (prog/prescribe-weight sample-events location)))))

  (testing "returns nil when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/prescribe-weight sample-events location))))))

;; -----------------------------------------------------------------------------
;; prescribe-reps tests
;; -----------------------------------------------------------------------------

(deftest prescribe-reps-test
  (testing "returns last reps when no weight override"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 10 (prog/prescribe-reps sample-events location)))))

  (testing "returns last reps when weight matches prescribed"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 10 (prog/prescribe-reps sample-events location 102.5)))))

  (testing "adjusts reps down when weight increased beyond prescribed"
    ;; Last: 100kg × 10 reps (1RM ≈ 133kg)
    ;; User picks 110kg → 110/133 = 82.7% → 7 reps (per 1RM table)
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 7 (prog/prescribe-reps sample-events location 110)))))

  (testing "adjusts reps up when weight decreased"
    ;; Prescribed: 102.5kg × 10 = 1025 target work
    ;; User picks 95kg → 1025/95 ≈ 10.8 → 11 reps
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (= 11 (prog/prescribe-reps sample-events location 95)))))

  (testing "adjusts reps using 1RM-based rep ranges - high rep scenario"
    ;; Issue: Prescribed 41.8kg × 15 reps, user picks 45kg
    ;; Current (work preservation): 627/45 ≈ 14 reps
    ;; Expected (1RM-based): 11 reps
    ;;
    ;; At high rep ranges, small weight increases have larger rep impacts
    ;; because the relationship between %1RM and reps is non-linear
    (let [high-rep-events [{:type :set-completed
                            :mesocycle "Plan" :microcycle 0 :workout :monday
                            :exercise "Curl" :set-index 0
                            :performed-weight 40.0 :performed-reps 15 :timestamp 1000}]
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Curl" :set-index 0}]
      ;; Prescribed weight: 40.0 + 2.5 = 42.5
      (is (= 42.5 (prog/prescribe-weight high-rep-events location)))
      ;; User picks 45kg → should get 11 reps (per 1RM tables)
      (is (= 11 (prog/prescribe-reps high-rep-events location 45)))))

  (testing "adjusts reps using 1RM-based rep ranges - moderate rep scenario"
    ;; Issue: Prescribed 73kg × 9 reps, user picks 75kg
    ;; Current (work preservation): keeps 9 reps (657/75 ≈ 8.8 → 9)
    ;; Expected (1RM-based): 7 reps
    (let [mod-rep-events [{:type :set-completed
                           :mesocycle "Plan" :microcycle 0 :workout :monday
                           :exercise "Bench" :set-index 0
                           :performed-weight 70.0 :performed-reps 9 :timestamp 1000}]
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Bench" :set-index 0}]
      ;; Prescribed weight: 70.0 + 2.5 = 72.5
      (is (= 72.5 (prog/prescribe-weight mod-rep-events location)))
      ;; User picks 75kg → should get 7 reps (per 1RM tables)
      (is (= 7 (prog/prescribe-reps mod-rep-events location 75)))))

  (testing "returns nil when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}]
      (is (nil? (prog/prescribe-reps sample-events location))))))

;; -----------------------------------------------------------------------------
;; prescribe tests
;; -----------------------------------------------------------------------------

(deftest prescribe-test
  (testing "returns both weight and reps"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location)]
      (is (= 102.5 (:weight result)))
      (is (= 10 (:reps result)))))

  (testing "adjusts reps when actual weight provided"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location 110)]
      (is (= 102.5 (:weight result)))  ; prescribed weight unchanged
      (is (= 7 (:reps result)))))      ; reps adjusted per 1RM curve

  (testing "returns nils when no history"
    (let [location {:mesocycle "Plan" :microcycle 0 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe sample-events location)]
      (is (nil? (:weight result)))
      (is (nil? (:reps result))))))

;; -----------------------------------------------------------------------------
;; Feedback-based progression tests
;; -----------------------------------------------------------------------------

(deftest feedback-based-weight-increment-test
  (testing "base increment with no feedback"
    (let [location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe-weight sample-events location [:quads])]
      ;; No feedback from week 0, so base increment = 2.5
      (is (= 102.5 result))))

  (testing "increased increment when never sore"
    (let [events (conj sample-events
                       {:type :soreness-reported
                        :mesocycle "Plan" :microcycle 0 :workout :monday
                        :muscle-group :quads :soreness :never-sore :timestamp 1500})
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe-weight events location [:quads])]
      ;; 2.5 * 1.5 = 3.75
      (is (= 103.75 result))))

  (testing "decreased increment when still sore"
    (let [events (conj sample-events
                       {:type :soreness-reported
                        :mesocycle "Plan" :microcycle 0 :workout :monday
                        :muscle-group :quads :soreness :still-sore :timestamp 1500})
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe-weight events location [:quads])]
      ;; 2.5 * 0.5 = 1.25
      (is (= 101.25 result))))

  (testing "zero increment with severe joint pain"
    (let [events (conj sample-events
                       {:type :session-rated
                        :mesocycle "Plan" :microcycle 0 :workout :monday
                        :muscle-group :quads :joint-pain :severe :pump 2 :sets-workload :just-right
                        :timestamp 1500})
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe-weight events location [:quads])]
      ;; severe pain = 0 increment
      (is (= 100 result))))

  (testing "combined modifiers - easy session + healed early"
    (let [events (-> sample-events
                     (conj {:type :soreness-reported
                            :mesocycle "Plan" :microcycle 0 :workout :monday
                            :muscle-group :quads :soreness :healed-early :timestamp 1500})
                     (conj {:type :session-rated
                            :mesocycle "Plan" :microcycle 0 :workout :monday
                            :muscle-group :quads :pump 3 :joint-pain :none :sets-workload :easy
                            :timestamp 1600}))
          location {:mesocycle "Plan" :microcycle 1 :workout :monday
                    :exercise "Squat" :set-index 0}
          result (prog/prescribe-weight events location [:quads])]
      ;; 2.5 * 1.25 (healed-early) * 1.25 (easy) = 3.90625
      (is (= 103.90625 result)))))
