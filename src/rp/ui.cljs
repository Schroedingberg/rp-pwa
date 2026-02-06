(ns rp.ui
  "Reagent components for the workout tracking UI.
  
  Component hierarchy:
    app
    ├── nav-menu (navigation)
    └── current page:
        ├── workouts-page
        │   └── microcycle-section (week)
        │       └── workout-section (day)
        │           └── exercise-card
        │               └── set-row (weight/reps input)
        ├── plans-page
        └── settings-page"
  (:require [reagent.core :as r]
            [cljs.reader :as reader]
            [rp.db :as db]
            [rp.plan :as plan]
            [rp.state :as state]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Navigation state
;; -----------------------------------------------------------------------------

(defonce current-page (r/atom :workouts))

;; -----------------------------------------------------------------------------
;; Set row component
;; -----------------------------------------------------------------------------
;; TODO: This is still quite nested. Consider:
;; - Moving to a state machine approach (e.g. {:state :editing/:completed/:skipped})
;; - Extracting the action buttons into a separate component
;; - Using a multimethod dispatch on state for rendering
;; -----------------------------------------------------------------------------

(defn- set-location
  "Build the location map for a set (used by db functions)."
  [mesocycle microcycle workout exercise set-index]
  {:mesocycle mesocycle
   :microcycle microcycle
   :workout workout
   :exercise exercise
   :set-index set-index})

(defn- weight-input
  "Weight input field."
  [{:keys [value placeholder disabled skipped? on-change]}]
  [:input {:type "number"
           :placeholder placeholder
           :value value
           :disabled disabled
           :on-change on-change
           :style (cond-> {:width "5rem"}
                    skipped? (assoc :text-decoration "line-through" :opacity "0.5"))}])

(defn- reps-input
  "Reps input field."
  [{:keys [value placeholder disabled skipped? on-change]}]
  [:input {:type "number"
           :placeholder placeholder
           :value value
           :disabled disabled
           :on-change on-change
           :style (cond-> {:width "4rem"}
                    skipped? (assoc :text-decoration "line-through" :opacity "0.5"))}])

(defn- set-row
  "A single set with weight/reps inputs.
  Completed sets can be clicked to enter edit mode for corrections.
  Skipped sets show as disabled with a skip indicator."
  [_mesocycle _microcycle _workout _exercise _set-index _set-data]
  (let [weight (r/atom "")
        reps (r/atom "")
        editing? (r/atom false)]
    (fn [mesocycle microcycle workout exercise set-index set-data]
      (let [{:keys [performed-weight performed-reps prescribed-weight prescribed-reps type]} set-data
            skipped? (= type :set-skipped)
            completed? (some? performed-weight)
            in-edit-mode? @editing?
            editable? (and (not skipped?) (or (not completed?) in-edit-mode?))
            location (set-location mesocycle microcycle workout exercise set-index)

            ;; Handlers
            save-set! (fn []
                        (when (and (seq @weight) (seq @reps))
                          (db/log-set! (assoc location
                                              :weight (js/parseFloat @weight)
                                              :reps (js/parseInt @reps)
                                              :prescribed-weight prescribed-weight
                                              :prescribed-reps prescribed-reps))
                          (reset! editing? false)))
            skip-set! (fn [] (db/skip-set! location))
            enter-edit! (fn []
                          (reset! weight (str performed-weight))
                          (reset! reps (str performed-reps))
                          (reset! editing? true))
            cancel-edit! (fn [] (reset! editing? false))]

        [:form {:style {:display "flex" :gap "0.5rem" :align-items "center" :margin-bottom "0.5rem"}}
         [weight-input {:value (cond in-edit-mode? @weight completed? performed-weight :else @weight)
                        :placeholder (if prescribed-weight (str prescribed-weight " kg") "kg")
                        :disabled (not editable?)
                        :skipped? skipped?
                        :on-change #(reset! weight (-> % .-target .-value))}]
         [:span "×"]
         [reps-input {:value (cond in-edit-mode? @reps completed? performed-reps :else @reps)
                      :placeholder (if prescribed-reps (str prescribed-reps) "reps")
                      :disabled (not editable?)
                      :skipped? skipped?
                      :on-change #(reset! reps (-> % .-target .-value))}]

         (cond
           skipped?
           [:button.secondary.outline
            {:type "button"
             :style {:padding "0.25rem 0.5rem" :margin 0 :opacity "0.5"}
             :title "Skipped - click to undo"
             :on-click cancel-edit!}
            "⊘"]

           (and completed? (not in-edit-mode?))
           [:button.outline
            {:type "button"
             :style {:padding "0.25rem 0.5rem" :margin 0}
             :on-click enter-edit!}
            "✓"]

           :else
           [:<>
            [:input {:type "checkbox" :checked false :on-change save-set!}]
            [:button.secondary.outline
             {:type "button"
              :style {:padding "0.25rem 0.5rem" :margin 0 :font-size "0.8rem"}
              :title "Skip this set"
              :on-click skip-set!}
             "Skip"]])

         (when in-edit-mode?
           [:button.secondary.outline
            {:type "button"
             :style {:padding "0.25rem 0.5rem" :margin 0}
             :on-click cancel-edit!}
            "✕"])]))))

(defn- exercise-card
  "An exercise with its sets."
  [mesocycle microcycle workout-key exercise-name sets]
  (let [muscle-groups (some :muscle-groups sets)]
    [:article {:key exercise-name}
     [:h4 exercise-name
      (when muscle-groups
        [:small {:style {:font-weight "normal" :margin-left "0.5rem" :color "var(--pico-muted-color)"}}
         (str/join ", " (map name muscle-groups))])]
     (for [[idx set-data] (map-indexed vector sets)]
       ^{:key idx}
       [set-row mesocycle microcycle workout-key exercise-name idx set-data])]))

(defn- workout-section
  "A workout day with its exercises."
  [mesocycle microcycle workout-key exercises-map]
  [:section {:key (name workout-key)}
   [:h3 (str/capitalize (name workout-key))]
   (for [[exercise-name sets] exercises-map]
     ^{:key exercise-name}
     [exercise-card mesocycle microcycle workout-key exercise-name sets])])

(defn- microcycle-section
  "A week with its workouts."
  [mesocycle-name microcycle-idx workouts-map]
  [:section {:key microcycle-idx}
   [:h2 (str "Week " (inc microcycle-idx))]
   (for [[workout-key exercises-map] workouts-map]
     ^{:key workout-key}
     [workout-section mesocycle-name microcycle-idx workout-key exercises-map])])

;; -----------------------------------------------------------------------------
;; Navigation menu
;; -----------------------------------------------------------------------------

(defn- nav-menu
  "Top navigation bar with page links."
  []
  (let [nav-item (fn [page label]
                   [:li [:a {:href "#"
                             :class (when (= @current-page page) "contrast")
                             :on-click (fn [e]
                                         (.preventDefault e)
                                         (reset! current-page page))}
                         label]])]
    [:nav.container
     [:ul
      [:li [:strong "RP"]]]
     [:ul
      [nav-item :workouts "Workouts"]
      [nav-item :plans "Plans"]
      [nav-item :settings "Settings"]]]))

;; -----------------------------------------------------------------------------
;; Pages
;; -----------------------------------------------------------------------------

(defn- workouts-page
  "Main workout tracking page."
  []
  (let [events (db/get-all-events)
        plan (plan/get-plan)
        plan-name (plan/get-plan-name)
        progress (state/view-progress-in-plan events plan)
        mesocycle-data (get progress plan-name)]
    [:<>
     [:header
      [:h1 plan-name]
      [:p "Track your workout progression"]]

     (for [[microcycle-idx workouts-map] (sort-by first mesocycle-data)]
       ^{:key microcycle-idx}
       [microcycle-section plan-name microcycle-idx workouts-map])]))

;; -----------------------------------------------------------------------------
;; Plan import
;; -----------------------------------------------------------------------------

(defn- handle-import-result
  "Process parsed EDN, validate, and save as current plan."
  [text]
  (try
    (let [template (reader/read-string text)]
      (if-let [err (plan/validate-template template)]
        (js/alert (str "Invalid plan: " err))
        (do
          (plan/set-template! template)
          (js/alert (str "Imported: " (:name template)))
          (reset! current-page :workouts))))
    (catch :default ex
      (js/alert (str "Parse error: " (.-message ex))))))

(defn- handle-file-select
  "Handle file input change event."
  [e]
  (when-let [file (-> e .-target .-files (aget 0))]
    (-> (.text file)
        (.then handle-import-result))))

(defn- plans-page
  "Plan management page - view, import, create plans."
  []
  (let [current-template (plan/get-template)
        current-name (:name current-template)]
    [:<>
     [:header
      [:h1 "Plans"]
      [:p "Manage your workout plans"]]

     [:section
      [:h2 "Current Plan"]
      [:p [:strong current-name]]]

     [:section
      [:h2 "Available Plans"]
      (for [template plan/available-templates
            :let [template-name (:name template)
                  is-current? (= template-name current-name)]]
        ^{:key template-name}
        [:article {:style {:margin-bottom "1rem"}}
         [:header [:strong template-name]]
         [:p (str (:n-microcycles template) " weeks • "
                  (count (:workouts template)) " days/week")]
         (if is-current?
           [:button.secondary {:disabled true} "Current"]
           [:button {:on-click #(do (plan/set-template! template)
                                    (reset! current-page :workouts))}
            "Use This Plan"])])]

     [:section
      [:h2 "Import Plan"]
      [:p "Import a plan from EDN file"]
      [:input {:type "file"
               :accept ".edn"
               :on-change handle-file-select}]]]))

(defn- settings-page
  "App settings page."
  []
  [:<>
   [:header
    [:h1 "Settings"]
    [:p "Configure the app"]]

   [:section
    [:h2 "Data"]
    [:button.secondary {:on-click #(js/console.log "Export data")}
     "Export All Data"]
    [:button.secondary.outline {:style {:margin-left "0.5rem"}
                                :on-click #(when (js/confirm "Clear all workout logs?")
                                             (js/console.log "Clear data"))}
     "Clear Logs"]]

   [:section
    [:h2 "About"]
    [:p "Romance Progression"]
    [:small "Local-first PWA for workout tracking"]]])

(defn app
  "Main app component - renders navigation and current page."
  []
  [:div
   [nav-menu]
   [:main.container
    (case @current-page
      :workouts [workouts-page]
      :plans    [plans-page]
      :settings [settings-page]
      [workouts-page])

    [:footer {:style {:margin-top "2rem" :text-align "center"}}
     [:small "Romance Progression • Local-first PWA"]]]])
