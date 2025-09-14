(ns social-abm.browser.canvas
  "HTML5 Canvas rendering for the simulation"
  (:require [social-abm.browser.protocols :as p]))

(defn get-canvas-context []
  "Get the 2D context of the simulation canvas"
  (let [canvas (js/document.getElementById "simulation-canvas")]
    (if canvas
      (do
        (println "Canvas dimensions:" (.-width canvas) "x" (.-height canvas))
        (.getContext canvas "2d"))
      (do
        (println "Canvas element not found!")
        nil))))

(defn clear-canvas [ctx width height]
  "Clear the canvas"
  (set! (.-fillStyle ctx) "#f0f8ff")
  (.fillRect ctx 0 0 width height))

(defn draw-cell [ctx x y cell-size color]
  "Draw a single cell on the canvas"
  (set! (.-fillStyle ctx) color)
  (.fillRect ctx (* x cell-size) (* y cell-size) cell-size cell-size))

(defn draw-agent [ctx x y cell-size color]
  "Draw an agent as a circle"
  (set! (.-fillStyle ctx) color)
  (.beginPath ctx)
  (let [center-x (+ (* x cell-size) (/ cell-size 2))
        center-y (+ (* y cell-size) (/ cell-size 2))
        radius (/ cell-size 3)]
    (.arc ctx center-x center-y radius 0 (* 2 js/Math.PI)))
  (.fill ctx))

(defn sugar-color [sugar-value max-sugar]
  "Get color for sugar based on its value"
  (if (and sugar-value (> sugar-value 0))
    (let [intensity (/ sugar-value max-sugar)
          r (int (* 255 (+ 0.8 (* 0.2 intensity))))
          g (int (* 255 (+ 0.8 (* 0.2 intensity))))
          b 0]
      (str "rgb(" r "," g "," b ")"))
    nil))

(defn agent-color [agent]
  "Get color for agent based on its properties"
  (let [energy (:energy agent)
        max-energy 100]
    (cond
      (> energy (* 0.7 max-energy)) "#00aa00"  ; Green - healthy
      (> energy (* 0.4 max-energy)) "#aaaa00"  ; Yellow - medium
      :else "#aa0000")))                        ; Red - low energy

(defn draw-world [ctx world cell-size max-sugar]
  "Draw the entire world state"
  (let [width (:width world)
        height (:height world)
        agents (p/get-agents world)
        agents-by-pos (into {} (map (fn [agent]
                                     (let [[x y] (p/get-position agent)]
                                       [[x y] agent]))
                                   agents))]

    ;; Clear canvas
    (clear-canvas ctx (* width cell-size) (* height cell-size))

    ;; Draw sugar cells
    (doseq [x (range width)
            y (range height)]
      (let [sugar-value (p/get-cell world x y)]
        (when-let [color (sugar-color sugar-value max-sugar)]
          (draw-cell ctx x y cell-size color))))

    ;; Draw agents
    (doseq [agent agents
            :when agent] ; Filter out nil (dead) agents
      (let [[x y] (p/get-position agent)
            color (agent-color agent)]
        (draw-agent ctx x y cell-size color)))))

(defn update-stats [world]
  "Update the statistics display"
  (let [tick (:tick world)
        agents (filter some? (p/get-agents world))
        agent-count (count agents)
        total-energy (reduce + (map :energy agents))
        avg-energy (if (> agent-count 0)
                     (/ total-energy agent-count)
                     0)]

    (set! (.-textContent (js/document.getElementById "tick")) tick)
    (set! (.-textContent (js/document.getElementById "agent-count-display")) agent-count)
    (set! (.-textContent (js/document.getElementById "avg-energy"))
          (.toFixed avg-energy 1))))