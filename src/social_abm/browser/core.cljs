(ns social-abm.browser.core
  "Browser entry point for the Social ABM framework"
  (:require [social-abm.browser.sugarscape :as sugar]
            [social-abm.browser.canvas :as canvas]))

(defn init! []
  "Initialize the browser application"
  (println "Social ABM Browser initialized!")
  (println "DOM ready state:" (.-readyState js/document))
  (if (= "loading" (.-readyState js/document))
    (.addEventListener js/document "DOMContentLoaded" sugar/init-sugarscape!)
    (sugar/init-sugarscape!)))