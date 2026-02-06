(ns rp.state-test
  (:require [cljs.test :refer [deftest is testing]]
            [rp.state :as state]
            [rp.plan :as plan]
            [rp.util :as util]))

;; --- Test data ---

(def sample-plan
  {"My Plan"
   {0 {:monday {"Squat" [{:exercise-name "Squat" :muscle-groups [:quads]}
                         {:exercise-name "Squat" :muscle-groups [:quads]}]
                "Press" [{:exercise-name "Press" :muscle-groups [:shoulders]}]}}}})

(def sample-events
  [{:mesocycle "My Plan"
    :microcycle 0
    :workout :monday
    :exercise "Squat"
    :set-index 0
    :performed-weight 100
    :performed-reps 8
    :timestamp 1000}
   {:mesocycle "My Plan"
    :microcycle 0
    :workout :monday
    :exercise "Squat"
    :set-index 1
    :performed-weight 105
    :performed-reps 6
    :timestamp 2000}])

;; =============================================================================
;; util tests
;; =============================================================================

(deftest deep-merge-with-test
  (testing "merges nested maps"
    (let [base {:a {:b 1}}
          overlay {:a {:c 2}}]
      (is (= {:a {:b 1 :c 2}}
             (util/deep-merge-with (fn [_ x] x) overlay base)))))

  (testing "applies function at leaf nodes"
    (let [base {:a [1 2]}
          overlay {:a [3 4]}]
      (is (= {:a [3 4 1 2]}
             (util/deep-merge-with into overlay base)))))

  (testing "preserves keys only in first map"
    (let [m1 {:a 1 :b 2}
          m2 {:a 10}]
      (is (= {:a 10 :b 2}
             (util/deep-merge-with (fn [_ x] x) m1 m2)))))

  (testing "preserves keys only in second map"
    (let [m1 {:a 1}
          m2 {:a 10 :b 20}]
      (is (= {:a 10 :b 20}
             (util/deep-merge-with (fn [_ x] x) m1 m2)))))

  (testing "handles deeply nested structures"
    (let [m1 {:a {:b {:c 1}}}
          m2 {:a {:b {:d 2}}}]
      (is (= {:a {:b {:c 1 :d 2}}}
             (util/deep-merge-with (fn [_ x] x) m1 m2))))))

;; =============================================================================
;; dedupe-by-latest tests (corrections via latest-wins)
;; =============================================================================

(deftest dedupe-by-latest-test
  (testing "keeps single event unchanged"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 100 :timestamp 1000}]
          result (#'state/dedupe-by-latest events)]
      (is (= 1 (count result)))
      (is (= 100 (:performed-weight (first result))))))

  (testing "keeps latest when same position has multiple events (correction)"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 100 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 105 :timestamp 2000}]  ; correction
          result (#'state/dedupe-by-latest events)]
      (is (= 1 (count result)))
      (is (= 105 (:performed-weight (first result))))))

  (testing "keeps latest regardless of input order"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 105 :timestamp 2000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 100 :timestamp 1000}]
          result (#'state/dedupe-by-latest events)]
      (is (= 105 (:performed-weight (first result))))))

  (testing "handles multiple corrections to same set"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 100 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 105 :timestamp 2000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 102 :timestamp 3000}]  ; final correction
          result (#'state/dedupe-by-latest events)]
      (is (= 1 (count result)))
      (is (= 102 (:performed-weight (first result))))))

  (testing "different set positions are kept separate"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 0 :performed-weight 100 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A"
                   :set-index 1 :performed-weight 100 :timestamp 2000}]
          result (#'state/dedupe-by-latest events)]
      (is (= 2 (count result))))))

;; =============================================================================
;; events->plan-map tests (internal function)
;; =============================================================================

(deftest events->plan-map-test
  (testing "transforms single event into nested structure"
    (let [event {:mesocycle "Plan" :microcycle 0 :workout :monday
                 :exercise "Squat" :set-index 0 :performed-weight 100 :timestamp 1000}
          result (#'state/events->plan-map [event])]
      (is (= 100 (get-in result ["Plan" 0 :monday "Squat" 0 :performed-weight])))))

  (testing "handles out-of-order events"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 2 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 0 :timestamp 2000}]
          result (#'state/events->plan-map events)
          sets (get-in result ["P" 0 :mon "A"])]
      (is (= 3 (count sets)))
      (is (= 0 (:set-index (nth sets 0))))
      (is (= {} (nth sets 1)))  ;; gap filled with empty map
      (is (= 2 (:set-index (nth sets 2))))))

  (testing "handles events with gaps in set indices"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 0 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 3 :timestamp 2000}]
          result (#'state/events->plan-map events)
          sets (get-in result ["P" 0 :mon "A"])]
      (is (= 4 (count sets)))
      (is (some? (nth sets 0)))
      (is (= {} (nth sets 1)))
      (is (= {} (nth sets 2)))
      (is (some? (nth sets 3)))))

  (testing "handles multiple exercises in same workout"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 0 :timestamp 1000}
                  {:mesocycle "P" :microcycle 0 :workout :mon :exercise "B" :set-index 0 :timestamp 2000}]
          result (#'state/events->plan-map events)]
      (is (some? (get-in result ["P" 0 :mon "A"])))
      (is (some? (get-in result ["P" 0 :mon "B"])))))

  (testing "handles multiple microcycles"
    (let [events [{:mesocycle "P" :microcycle 0 :workout :mon :exercise "A" :set-index 0 :timestamp 1000}
                  {:mesocycle "P" :microcycle 1 :workout :mon :exercise "A" :set-index 0 :timestamp 2000}]
          result (#'state/events->plan-map events)]
      (is (some? (get-in result ["P" 0 :mon "A"])))
      (is (some? (get-in result ["P" 1 :mon "A"])))))

  (testing "handles string workout names (converts to keyword)"
    (let [events [{:mesocycle "P" :microcycle 0 :workout "monday" :exercise "A" :set-index 0 :timestamp 1000}]
          result (#'state/events->plan-map events)]
      ;; The workout key should be keywordized
      (is (some? (get-in result ["P" 0 :monday "A"]))))))

;; =============================================================================
;; view-progress-in-plan tests
;; =============================================================================

(deftest view-progress-in-plan-test
  (testing "returns plan unchanged when no events"
    (is (= sample-plan
           (state/view-progress-in-plan [] sample-plan))))

  (testing "merges performed data into plan"
    (let [result (state/view-progress-in-plan sample-events sample-plan)
          squat-sets (get-in result ["My Plan" 0 :monday "Squat"])]
      (is (= 100 (:performed-weight (first squat-sets))))
      (is (= 8 (:performed-reps (first squat-sets))))
      (is (= 105 (:performed-weight (second squat-sets))))
      (is (= [:quads] (:muscle-groups (first squat-sets))))))

  (testing "preserves unperformed sets"
    (let [result (state/view-progress-in-plan sample-events sample-plan)
          press-sets (get-in result ["My Plan" 0 :monday "Press"])]
      (is (= 1 (count press-sets)))
      (is (nil? (:performed-weight (first press-sets))))))

  (testing "handles extra sets beyond plan"
    (let [extra-event {:mesocycle "My Plan" :microcycle 0 :workout :monday
                       :exercise "Squat" :set-index 5 :performed-weight 90 :timestamp 1000}
          result (state/view-progress-in-plan [extra-event] sample-plan)
          squat-sets (get-in result ["My Plan" 0 :monday "Squat"])]
      ;; Should have 6 sets (0-5)
      (is (= 6 (count squat-sets)))
      ;; Original plan data in first 2
      (is (= [:quads] (:muscle-groups (first squat-sets))))
      ;; Extra set at index 5
      (is (= 90 (:performed-weight (nth squat-sets 5))))))

  (testing "handles exercise not in plan"
    (let [new-exercise-event {:mesocycle "My Plan" :microcycle 0 :workout :monday
                              :exercise "Deadlift" :set-index 0 :performed-weight 150 :timestamp 1000}
          result (state/view-progress-in-plan [new-exercise-event] sample-plan)
          deadlift-sets (get-in result ["My Plan" 0 :monday "Deadlift"])]
      (is (= 1 (count deadlift-sets)))
      (is (= 150 (:performed-weight (first deadlift-sets))))))

  (testing "correction replaces original data (latest wins)"
    (let [events [{:mesocycle "My Plan" :microcycle 0 :workout :monday
                   :exercise "Squat" :set-index 0 :performed-weight 100 :timestamp 1000}
                  {:mesocycle "My Plan" :microcycle 0 :workout :monday
                   :exercise "Squat" :set-index 0 :performed-weight 105 :timestamp 2000}]  ; correction
          result (state/view-progress-in-plan events sample-plan)
          squat-sets (get-in result ["My Plan" 0 :monday "Squat"])]
      (is (= 105 (:performed-weight (first squat-sets)))))))

;; =============================================================================
;; plan expansion tests
;; =============================================================================

(deftest expand-exercises-test
  (testing "expands n-sets into vector of set maps"
    (let [template {:exercises {"Squat" {:n-sets 3 :muscle-groups [:quads]}}}
          expanded (#'plan/expand-exercises template)]
      (is (= 3 (count (get expanded "Squat"))))
      (is (every? #(= "Squat" (:exercise-name %)) (get expanded "Squat")))
      (is (every? #(= [:quads] (:muscle-groups %)) (get expanded "Squat")))))

  (testing "removes n-sets key from expanded sets"
    (let [template {:exercises {"Squat" {:n-sets 2 :muscle-groups [:quads]}}}
          expanded (#'plan/expand-exercises template)
          set-map (first (get expanded "Squat"))]
      (is (not (contains? set-map :n-sets)))))

  (testing "preserves exercise order"
    (let [template {:exercises (array-map "First" {:n-sets 1}
                                          "Second" {:n-sets 1}
                                          "Third" {:n-sets 1})}
          expanded (#'plan/expand-exercises template)]
      (is (= ["First" "Second" "Third"] (keys expanded))))))

(deftest plan-expansion-test
  (testing "->plan creates full structure"
    (let [template {:name "Test"
                    :n-microcycles 2
                    :workouts {:monday {:exercises {"Squat" {:n-sets 2}}}}}
          plan (plan/->plan template)]
      (is (contains? plan "Test"))
      (is (= 2 (count (get plan "Test"))))
      (is (contains? (get-in plan ["Test" 0]) :monday))
      (is (contains? (get-in plan ["Test" 1]) :monday))))

  (testing "all microcycles have identical structure"
    (let [template {:name "Test" :n-microcycles 3
                    :workouts {:monday {:exercises {"A" {:n-sets 2}}}
                               :thursday {:exercises {"B" {:n-sets 3}}}}}
          plan (plan/->plan template)]
      (doseq [i (range 3)]
        (is (= 2 (count (get-in plan ["Test" i :monday "A"]))))
        (is (= 3 (count (get-in plan ["Test" i :thursday "B"])))))))

  (testing "microcycle indices are sorted"
    (let [template {:name "Test" :n-microcycles 4
                    :workouts {:monday {:exercises {"A" {:n-sets 1}}}}}
          plan (plan/->plan template)]
      (is (= [0 1 2 3] (keys (get plan "Test")))))))

;; =============================================================================
;; merge-sets tests (internal function)
;; =============================================================================

(deftest merge-sets-test
  (testing "merges performed into planned"
    (let [performed [{:performed-weight 100}]
          planned [{:exercise-name "Squat" :muscle-groups [:quads]}]
          result (#'state/merge-sets performed planned)]
      (is (= 1 (count result)))
      (is (= 100 (:performed-weight (first result))))
      (is (= "Squat" (:exercise-name (first result))))))

  (testing "handles more performed than planned"
    (let [performed [{:a 1} {:a 2} {:a 3}]
          planned [{:b 1}]
          result (#'state/merge-sets performed planned)]
      (is (= 3 (count result)))
      (is (= {:a 1 :b 1} (first result)))))

  (testing "handles more planned than performed"
    (let [performed [{:a 1}]
          planned [{:b 1} {:b 2} {:b 3}]
          result (#'state/merge-sets performed planned)]
      (is (= 3 (count result)))
      (is (= {:a 1 :b 1} (first result)))
      (is (= {:b 2} (second result)))))

  (testing "handles empty inputs"
    (is (= [] (#'state/merge-sets [] [])))
    (is (= [{:a 1}] (#'state/merge-sets [{:a 1}] [])))
    (is (= [{:b 1}] (#'state/merge-sets [] [{:b 1}])))))
