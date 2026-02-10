(ns build
  "Build hooks for shadow-cljs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn get-version
  "Read version from package.json."
  []
  (-> (io/file "package.json")
      slurp
      (json/read-str :key-fn keyword)
      :version))

(defn inject-version
  "Shadow-cljs build hook: injects VERSION from package.json.
   Called automatically during :release builds."
  {:shadow.build/stage :configure}
  [build-state]
  (let [version (get-version)]
    (println (str "[build] Injecting version: " version))
    (update-in build-state 
               [:compiler-options :closure-defines] 
               assoc 'rp.config/VERSION version)))
