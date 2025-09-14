(ns social-abm.world
  "Grid-based world implementation for agent-based modeling"
  (:require [social-abm.core :refer [World]]))

(defrecord GridWorld [width height cells agents tick]
  World
  (get-agents [world] agents)

  (add-agent [world agent]
    (update world :agents conj agent))

  (remove-agent [world agent]
    (update world :agents #(remove #{agent} %)))

  (get-cell [world x y]
    (when (and (>= x 0) (< x width) (>= y 0) (< y height))
      (get-in cells [y x])))

  (set-cell [world x y value]
    (if (and (>= x 0) (< x width) (>= y 0) (< y height))
      (assoc-in world [:cells y x] value)
      world))

  (get-neighbors [world x y radius]
    (for [dx (range (- radius) (inc radius))
          dy (range (- radius) (inc radius))
          :when (not (and (zero? dx) (zero? dy)))
          :let [nx (+ x dx) ny (+ y dy)]
          :when (and (>= nx 0) (< nx width) (>= ny 0) (< ny height))]
      {:x nx :y ny :value (get-in cells [ny nx])}))

  (step-world [world]
    (-> world
        (update :tick inc)
        (assoc :agents (map #(.step % world) agents)))))

(defn create-grid-world
  "Create a new grid world with given dimensions"
  [width height & {:keys [initial-value] :or {initial-value nil}}]
  (->GridWorld width height
               (vec (repeat height (vec (repeat width initial-value))))
               []
               0))

(defn get-empty-cells
  "Get all empty cells in the world"
  [world]
  (for [x (range (:width world))
        y (range (:height world))
        :when (nil? (.get-cell world x y))]
    {:x x :y y}))

(defn random-empty-cell
  "Get a random empty cell from the world"
  [world]
  (let [empty-cells (get-empty-cells world)]
    (when (seq empty-cells)
      (rand-nth empty-cells))))

(defn manhattan-distance
  "Calculate Manhattan distance between two points"
  [x1 y1 x2 y2]
  (+ (Math/abs (- x2 x1)) (Math/abs (- y2 y1))))

(defn euclidean-distance
  "Calculate Euclidean distance between two points"
  [x1 y1 x2 y2]
  (Math/sqrt (+ (* (- x2 x1) (- x2 x1)) (* (- y2 y1) (- y2 y1)))))