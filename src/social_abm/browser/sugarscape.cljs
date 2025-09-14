(ns social-abm.browser.sugarscape
  "Browser-based Sugarscape simulation"
  (:require [social-abm.browser.world :as w]
            [social-abm.browser.agent :as a]
            [social-abm.browser.canvas :as canvas]
            [social-abm.browser.protocols :as p]))

(def simulation-state (atom {:world nil
                            :running false
                            :speed 15
                            :ctx nil
                            :cell-size 8
                            :max-sugar 10}))

(defn sugar-growth
  "Grow sugar back in cells over time"
  [world growth-rate max-sugar]
  (reduce (fn [w y]
            (reduce (fn [w2 x]
                      (let [current-sugar (p/get-cell w2 x y)]
                        (if (and current-sugar (< current-sugar max-sugar))
                          (p/set-cell w2 x y (min max-sugar (+ current-sugar growth-rate)))
                          w2)))
                    w
                    (range (:width world))))
          world
          (range (:height world))))

(defn create-sugar-landscape
  "Create initial sugar distribution with peaks"
  [world peak-locations max-sugar]
  (reduce (fn [w peak]
            (let [{:keys [x y radius]} peak]
              (reduce (fn [w2 cell-y]
                        (reduce (fn [w3 cell-x]
                                  (let [dist (w/euclidean-distance x y cell-x cell-y)
                                        sugar-value (max 0 (- max-sugar (/ dist radius)))]
                                    (if (> sugar-value 0)
                                      (p/set-cell w3 cell-x cell-y (int sugar-value))
                                      w3)))
                                w2
                                (range (:width world))))
                      w
                      (range (:height world)))))
          world
          peak-locations))

(defn sugarscape-step
  "Custom step function for Sugarscape model"
  [world]
  (-> world
      ;; Remove dead agents (nil from step function)
      (update :agents #(filter some? %))
      ;; Consume sugar where agents are located
      (as-> w
        (reduce (fn [world agent]
                  (let [[x y] (p/get-position agent)]
                    (p/set-cell world x y 0)))
                w
                (p/get-agents w)))
      ;; Grow sugar back
      (sugar-growth 1 (:max-sugar @simulation-state))))

(defn simulation-step []
  "Execute one simulation step"
  (when (:running @simulation-state)
    (let [world (:world @simulation-state)
          stepped-world (p/step-world world)
          final-world (sugarscape-step stepped-world)]

      (swap! simulation-state assoc :world final-world)

      ;; Render the world
      (canvas/draw-world (:ctx @simulation-state)
                        final-world
                        (:cell-size @simulation-state)
                        (:max-sugar @simulation-state))

      ;; Update statistics
      (canvas/update-stats final-world))))

(defn start-simulation []
  "Start the simulation loop"
  (swap! simulation-state assoc :running true)
  (let [speed (:speed @simulation-state)
        interval (max 10 (- 510 (* speed 25)))] ; 10ms to 485ms
    (js/setInterval simulation-step interval)))

(defn stop-simulation []
  "Stop the simulation"
  (swap! simulation-state assoc :running false))

(defn reset-simulation []
  "Reset the simulation to initial state"
  (stop-simulation)
  (let [agent-count (.-value (js/document.getElementById "agent-count"))
        width 100
        height 75
        world (w/create-grid-world width height)

        ;; Create sugar peaks
        sugar-peaks [{:x 25 :y 25 :radius 15}
                     {:x 75 :y 50 :radius 12}]
        world-with-sugar (create-sugar-landscape world sugar-peaks (:max-sugar @simulation-state))

        ;; Create agents at random positions
        agents (repeatedly (js/parseInt agent-count)
                          #(a/create-sugar-agent
                            (gensym "agent")
                            (rand-int width)
                            (rand-int height)
                            :energy (+ 25 (rand-int 50))
                            :vision (inc (rand-int 3))
                            :metabolism (inc (rand-int 3))
                            :max-age (+ 60 (rand-int 80))))

        initial-world (reduce #(p/add-agent %1 %2) world-with-sugar agents)]

    (swap! simulation-state assoc :world initial-world)

    ;; Initial render
    (canvas/draw-world (:ctx @simulation-state)
                      initial-world
                      (:cell-size @simulation-state)
                      (:max-sugar @simulation-state))
    (canvas/update-stats initial-world)))

(defn setup-controls []
  "Set up event listeners for control buttons"
  (.addEventListener (js/document.getElementById "start-btn") "click" start-simulation)
  (.addEventListener (js/document.getElementById "stop-btn") "click" stop-simulation)
  (.addEventListener (js/document.getElementById "reset-btn") "click" reset-simulation)
  (.addEventListener (js/document.getElementById "speed-slider") "input"
                     (fn [e] (swap! simulation-state assoc :speed (js/parseInt (.-value (.-target e)))))))

(defn init-sugarscape! []
  "Initialize the Sugarscape simulation"
  (try
    (println "Attempting to initialize Sugarscape...")
    (let [canvas-element (js/document.getElementById "simulation-canvas")]
      (if canvas-element
        (do
          (println "Canvas element found")
          (let [ctx (canvas/get-canvas-context)]
            (if ctx
              (do
                (println "Canvas context obtained")
                (swap! simulation-state assoc :ctx ctx)
                (setup-controls)
                (reset-simulation)
                (println "Sugarscape initialized successfully!"))
              (println "ERROR: Could not get canvas context"))))
        (println "ERROR: Canvas element 'simulation-canvas' not found")))
    (catch js/Error e
      (println "ERROR initializing Sugarscape:" (.-message e)))))
