(ns user
  "Development utilities for Clerk notebooks."
  (:require [nextjournal.clerk :as clerk]))

(defn start!
  "Start Clerk in watch mode for interactive development."
  []
  (clerk/serve! {:browse? true
                 :watch-paths ["notebooks"]}))

(defn build-docs!
  "Build static HTML for GitHub Pages deployment."
  [& _]
  (clerk/build! {:paths ["notebooks/index.clj"
                         "notebooks/architecture.clj"
                         "notebooks/events.clj"]
                 :out-path "docs/clerk"
                 :bundle true}))

(comment
  ;; Start interactive development
  (start!)

  ;; Build static docs
  (build-docs!))
