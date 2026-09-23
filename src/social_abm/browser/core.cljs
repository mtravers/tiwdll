(ns social-abm.browser.core
  "Browser entry point for the Social ABM framework"
  (:require [social-abm.browser.sugarscape :as sugar]
            [social-abm.browser.jubilee :as jubilee]))

(defn- init-for-page! []
  "Dispatch to the lab whose canvas is present on this page"
  (cond
    (js/document.getElementById "jubilee-canvas") (jubilee/init-jubilee!)
    (js/document.getElementById "simulation-canvas") (sugar/init-sugarscape!)
    :else (println "No known lab canvas found on this page")))

(defn init! []
  "Initialize the browser application"
  (println "Social ABM Browser initialized!")
  (println "DOM ready state:" (.-readyState js/document))
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" init-for-page!)
    (init-for-page!)))