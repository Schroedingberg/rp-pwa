(ns rp.storage
  "localStorage persistence with auto-save on DB changes."
  (:require [rp.db :as db]))

(def ^:private DB-KEY "rp-workout-db")

(defn- save-db! []
  (try
    (.setItem js/localStorage DB-KEY (db/db->edn))
    (catch :default e
      (js/console.error "Failed to save:" e))))

(defn load-db!
  "Load persisted data and set up auto-save. Calls on-complete when ready."
  [on-complete]
  (when-let [data (.getItem js/localStorage DB-KEY)]
    (db/load-from-edn! data))
  ;; Auto-save on every transaction
  (add-watch db/db-version :auto-save (fn [_ _ _ _] (save-db!)))
  (on-complete))

(defn clear-db!
  "Clear all persisted data and reset the database."
  []
  (.removeItem js/localStorage DB-KEY)
  (db/clear-all!))
