(ns rp.ui
  "Main app shell: navigation and page routing.
  
  ## Data-Driven UI Pattern
  
  This codebase uses a consistent pattern where UI structure is defined as data,
  and generic renderers interpret that data. This provides:
  
  1. **Discoverability** - See all nav items, pages, actions in one place
  2. **Consistency** - Same renderer = same behavior everywhere
  3. **Extensibility** - Add features by adding data, not code
  
  ### Pattern examples:
  
  ```clojure
  ;; Navigation: vector of [key label] pairs
  (def nav-items [[:workouts \"Workouts\"] [:plans \"Plans\"]])
  
  ;; Pages: map of page-key → {:title :subtitle :content}
  (def pages {:workouts {:title \"Workouts\" :content workouts-fn}})
  
  ;; Actions: vector of {:label :on-click :confirm?} maps
  (def actions [{:label \"Clear\" :confirm \"Sure?\" :on-click clear!}])
  ```
  
  Components are split into modules:
    - rp.ui.components - Generic form primitives (radio-group, modal-dialog)
    - rp.ui.feedback   - Feedback popup components
    - rp.ui.workout    - Set/exercise display components"
  (:require [reagent.core :as r]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [rp.config :as config]
            [rp.db :as db]
            [rp.plan :as plan]
            [rp.state :as state]
            [rp.storage :as storage]
            [rp.ui.feedback :as feedback]
            [rp.ui.workout :as workout]))

(defonce current-page (r/atom :workouts))

;; =============================================================================
;; LAYER 1: Layout Primitives
;; =============================================================================

(defn- page-header [title subtitle]
  [:header [:h1 title] [:p subtitle]])

(defn- section [title & children]
  (into [:section [:h2 title]] children))

;; =============================================================================
;; LAYER 2: Navigation (data-driven)
;; =============================================================================

(def ^:private nav-items
  "Navigation tabs. Add a page here to show it in the nav bar."
  [[:workouts "Workouts"]
   [:plans    "Plans"]
   [:settings "Settings"]])

(defn- nav-menu []
  [:nav.container {:style {:position "sticky" :top 0 :z-index 100
                           :background "var(--pico-background-color)"}}
   [:ul [:li [:strong "RP"] [:small {:style {:margin-left "0.5rem" :opacity 0.6}}
                             (str "v" config/VERSION)]]]
   [:ul
    (doall
     (for [[page label] nav-items]
       ^{:key page}
       [:li [:a {:href "#" :class (when (= @current-page page) "contrast")
                 :on-click #(do (.preventDefault %) (reset! current-page page))}
             label]]))]])

;; =============================================================================
;; LAYER 3: Feedback Orchestration (data-driven)
;; =============================================================================

(defonce ^:private dismissed-feedback (r/atom #{}))

(def ^:private feedback-types
  "Feedback popup types. Order matters - first pending type wins."
  [{:type      :soreness
    :pending-fn state/pending-soreness-feedback
    :component  feedback/soreness-popup
    :log-fn     #(db/log-soreness-reported! (assoc %1 :muscle-group %2 :soreness %3))}
   {:type      :session
    :pending-fn state/pending-session-rating
    :component  feedback/session-rating-popup
    :log-fn     #(db/log-session-rated! (merge %1 {:muscle-group %2} %3))}])

(defn- workout-muscle-groups [exercises-map]
  (->> exercises-map vals (mapcat identity) (mapcat :muscle-groups) (remove nil?) distinct))

(defn- dismiss-key [active type mg]
  [type (:mesocycle active) (:microcycle active) (:workout active) mg])

(defn- find-pending-feedback
  "Find first pending feedback (type + muscle-group) that hasn't been dismissed."
  [{:keys [events progress active dismissed]}]
  (when active
    (let [workout-ex (get-in progress [(:mesocycle active) (:microcycle active) (:workout active)])
          muscle-groups (workout-muscle-groups workout-ex)]
      (some (fn [{:keys [type pending-fn]}]
              (when-let [mg (->> (pending-fn events progress active muscle-groups)
                                 (remove #(contains? dismissed (dismiss-key active type %)))
                                 first)]
                {:type type :muscle-group mg}))
            feedback-types))))

(defn- render-feedback-popup [{:keys [type muscle-group]} active]
  (let [{:keys [component log-fn]} (first (filter #(= (:type %) type) feedback-types))]
    [component
     {:muscle-group muscle-group
      :on-submit (fn [mg data] (log-fn active mg data))
      :on-dismiss #(swap! dismissed-feedback conj (dismiss-key active type %))}]))

;; =============================================================================
;; LAYER 4: Page Content
;; =============================================================================

;; --- Workouts ---

(defn- week-section [events plan-name week workouts]
  [:section
   [:h2 (str "Week " (inc week))]
   (doall
    (for [[day exercises] workouts]
      (let [loc {:mesocycle plan-name :microcycle week :workout day}
            swaps (state/get-swaps events loc)
            swapped-exercises (state/apply-swaps exercises swaps)]
        ^{:key day}
        [:section
         [:h3 (str/capitalize (name day))]
         (doall
          (for [[ex-name sets] swapped-exercises]
            ^{:key ex-name}
            [workout/exercise-card plan-name week day ex-name sets]))])))])

(defn- workouts-content []
  (let [events (db/get-all-events)
        plan-name (plan/get-plan-name)
        progress (state/view-progress-in-plan events (plan/get-plan))
        mesocycle-data (get progress plan-name)
        active (state/last-active-workout events)
        pending (find-pending-feedback {:events events :progress progress
                                        :active active :dismissed @dismissed-feedback})]
    [:<>
     (when pending [render-feedback-popup pending active])
     (doall
      (for [[week workouts] (sort-by first mesocycle-data)]
        ^{:key week}
        [week-section events plan-name week workouts]))]))

;; --- Plans ---

(defn- plan-card [{:keys [template current? on-select]}]
  [:article {:style {:margin-bottom "1rem"}}
   [:header [:strong (:name template)]]
   [:p (str (:n-microcycles template) " weeks • " (count (:workouts template)) " days/week")]
   (if current?
     [:button.secondary {:disabled true} "Current"]
     [:button {:on-click on-select} "Use This Plan"])])

(defn- import-plan! [text]
  (try
    (let [t (reader/read-string text)]
      (if-let [err (plan/validate-template t)]
        (js/alert (str "Invalid: " err))
        (do (plan/set-template! t)
            (js/alert (str "Imported: " (:name t)))
            (reset! current-page :workouts))))
    (catch :default ex
      (js/alert (str "Parse error: " (.-message ex))))))

(defn- file-input [{:keys [accept on-file]}]
  [:input {:type "file" :accept accept
           :on-change (fn [e]
                        (when-let [f (-> e .-target .-files (aget 0))]
                          (-> (.text f) (.then on-file))))}])

(defn- plans-content []
  (let [current-name (:name (plan/get-template))]
    [:<>
     [section "Current Plan" [:p [:strong current-name]]]
     [section "Available Plans"
      (doall
       (for [t plan/available-templates]
         ^{:key (:name t)}
         [plan-card {:template t
                     :current? (= (:name t) current-name)
                     :on-select #(do (plan/set-template! t) (reset! current-page :workouts))}]))]
     [section "Import Plan" [file-input {:accept ".edn" :on-file import-plan!}]]]))

;; --- Settings (data-driven actions) ---

(def ^:private settings-actions
  "Settings buttons. Add {:confirm-msg \"...\"} to require confirmation."
  [{:label "Export All Data" :class "secondary"
    :on-click storage/export-db!}
   {:label "Clear Logs" :class "secondary.outline"
    :confirm-msg "Clear all workout logs?"
    :on-click storage/clear-db!}])

(defn- action-button [{:keys [label class confirm-msg on-click]}]
  [(keyword (str "button." class))
   {:on-click (if confirm-msg #(when (js/confirm confirm-msg) (on-click)) on-click)}
   label])

(defn- settings-content []
  [:<>
   [section "Data"
    [:div {:style {:display "flex" :gap "0.5rem"}}
     (doall
      (for [{:keys [label] :as action} settings-actions]
        ^{:key label}
        [action-button action]))]]
   [section "About"
    [:p "Romance Progression"]
    [:small "Local-first PWA for workout tracking"]]])

;; =============================================================================
;; LAYER 5: App Shell (data-driven pages)
;; =============================================================================

(def ^:private pages
  "Page definitions. :title can be a string or (fn [] string) for dynamic titles."
  {:workouts {:title    (fn [] (plan/get-plan-name))
              :subtitle "Track your workout progression"
              :content  workouts-content}
   :plans    {:title    "Plans"
              :subtitle "Manage your workout plans"
              :content  plans-content}
   :settings {:title    "Settings"
              :subtitle "Configure the app"
              :content  settings-content}})

(defn- render-page [page-key]
  (let [{:keys [title subtitle content]} (get pages page-key (pages :workouts))
        title-text (if (fn? title) (title) title)]
    [:<>
     [page-header title-text subtitle]
     [content]]))

(defn app []
  [:div
   [nav-menu]
   [:main.container
    [render-page @current-page]
    [:footer {:style {:margin-top "2rem" :text-align "center"}}
     [:small "Romance Progression • Local-first PWA"]]]])
