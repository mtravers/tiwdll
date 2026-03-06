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
        avg (fn [f] (if (> agent-count 0)
                      (/ (reduce + (map f agents)) agent-count)
                      0))
        avg-energy (avg :energy)
        avg-metabolism (avg #(or (:metabolism %) 0))
        avg-vision (avg #(or (:vision %) 0))
        avg-age (avg #(or (:age %) 0))]
    (set! (.-textContent (js/document.getElementById "tick")) tick)
    (set! (.-textContent (js/document.getElementById "agent-count-display")) agent-count)
    (set! (.-textContent (js/document.getElementById "avg-energy")) (.toFixed avg-energy 1))
    (when-let [el (js/document.getElementById "avg-metabolism")]
      (set! (.-textContent el) (.toFixed avg-metabolism 2)))
    (when-let [el (js/document.getElementById "avg-vision")]
      (set! (.-textContent el) (.toFixed avg-vision 2)))
    (when-let [el (js/document.getElementById "avg-age")]
      (set! (.-textContent el) (.toFixed avg-age 1)))))

(defn find-agent-at-pos [world canvas-x canvas-y cell-size]
  "Return the agent at canvas pixel coordinates, or nil"
  (let [gx (int (/ canvas-x cell-size))
        gy (int (/ canvas-y cell-size))]
    (first (filter (fn [a]
                     (let [[ax ay] (p/get-position a)]
                       (and (= ax gx) (= ay gy))))
                   (filter some? (p/get-agents world))))))

(defn draw-line-chart [canvas-id values color]
  "Draw a time-series line chart onto canvas-id"
  (when-let [canvas (js/document.getElementById canvas-id)]
    (let [ctx (.getContext canvas "2d")
          w   (.-width canvas)
          h   (.-height canvas)
          pl 6 pr 6 pt 6 pb 6
          pw  (- w pl pr)
          ph  (- h pt pb)
          n   (count values)
          mx  (reduce max 1 values)
          mn  (reduce min 0 values)
          rng (max 1 (- mx mn))]
      (set! (.-fillStyle ctx) "#f8f9fa")
      (.fillRect ctx 0 0 w h)
      ;; mid grid line
      (set! (.-strokeStyle ctx) "#e0e0e0")
      (set! (.-lineWidth ctx) 0.5)
      (.beginPath ctx)
      (.moveTo ctx pl (+ pt (/ ph 2)))
      (.lineTo ctx (+ pl pw) (+ pt (/ ph 2)))
      (.stroke ctx)
      ;; data line
      (when (>= n 2)
        (set! (.-strokeStyle ctx) color)
        (set! (.-lineWidth ctx) 1.5)
        (set! (.-lineJoin ctx) "round")
        (.beginPath ctx)
        (doseq [[i v] (map-indexed vector values)]
          (let [x (+ pl (* (/ i (dec n)) pw))
                y (+ pt (* (- 1.0 (/ (- v mn) rng)) ph))]
            (if (zero? i) (.moveTo ctx x y) (.lineTo ctx x y))))
        (.stroke ctx))
      ;; current value label
      (when (seq values)
        (set! (.-fillStyle ctx) color)
        (set! (.-font ctx) "10px monospace")
        (.fillText ctx (.toFixed (last values) 1) (- w 38) 14)))))

(defn draw-distribution-chart [canvas-id freq-map color]
  "Draw a bar chart for a value distribution"
  (when-let [canvas (js/document.getElementById canvas-id)]
    (let [ctx (.getContext canvas "2d")
          w   (.-width canvas)
          h   (.-height canvas)
          pl 6 pr 6 pt 6 pb 18
          ph  (- h pt pb)
          pw  (- w pl pr)
          ks  (sort (keys freq-map))
          n   (max 1 (count ks))
          mx  (reduce max 1 (vals freq-map))
          bw  (/ pw n)]
      (set! (.-fillStyle ctx) "#f8f9fa")
      (.fillRect ctx 0 0 w h)
      (doseq [[i k] (map-indexed vector ks)]
        (let [cnt (get freq-map k 0)
              bh  (* (/ cnt mx) ph)
              x   (+ pl (* i bw))
              y   (+ pt (- ph bh))]
          (set! (.-fillStyle ctx) color)
          (.fillRect ctx (+ x 3) y (- bw 6) bh)
          (set! (.-fillStyle ctx) "#555")
          (set! (.-font ctx) "9px monospace")
          (.fillText ctx (str k) (+ x (/ bw 2) -3) (- h 5)))))))

(defn draw-charts [history agents]
  "Redraw all four stat charts"
  (draw-line-chart "chart-population" (mapv :count history) "#4CAF50")
  (draw-line-chart "chart-energy"     (mapv :avg-energy history) "#2196F3")
  (draw-distribution-chart "chart-metabolism"
                           (frequencies (map :metabolism agents)) "#FF9800")
  (draw-distribution-chart "chart-vision"
                           (frequencies (map :vision agents)) "#9C27B0"))