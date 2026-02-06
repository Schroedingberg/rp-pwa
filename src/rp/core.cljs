(ns rp.core
  "App entry point - initializes storage, UI, and service worker."
  (:require [reagent.dom.client :as rdc]
            [rp.storage :as storage]
            [rp.ui :as ui]))

(defonce ^:private root (atom nil))

(defn- register-service-worker []
  (when (.-serviceWorker js/navigator)
    (-> js/navigator .-serviceWorker (.register "sw.js"))))

(defn init! []
  (storage/load-db!
   (fn []
     (when-not @root
       (reset! root (rdc/create-root (.getElementById js/document "app"))))
     (rdc/render @root [ui/app])
     (register-service-worker))))
