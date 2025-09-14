(ns social-abm.examples.sugarscape
  "Sugarscape-inspired agent-based model"
  (:require [social-abm.core :refer [Agent]]
            [social-abm.world :as w]
            [social-abm.agent :as a]
            [social-abm.simulation :as sim]))

(defrecord SugarAgent [id x y energy vision metabolism max-age age]
  Agent
  (step [agent world]
    (let [neighbors (.get-neighbors world x y vision)
          sugar-cells (filter #(and (:value %) (> (:value %) 0)) neighbors)
          best-sugar (when (seq sugar-cells)
                       (apply max-key :value sugar-cells))
          current-sugar (.get-cell world x y)

          ;; Move to best sugar location or stay put
          [new-x new-y] (if (and best-sugar (> (:value best-sugar) (or current-sugar 0)))
                         [(:x best-sugar) (:y best-sugar)]
                         [x y])

          ;; Consume sugar at new location
          sugar-at-pos (.get-cell world new-x new-y)
          sugar-consumed (or sugar-at-pos 0)

          new-energy (+ (- energy metabolism) sugar-consumed)
          new-age (inc age)]

      ;; Agent dies if energy <= 0 or age >= max-age
      (if (or (<= new-energy 0) (>= new-age max-age))
        nil ;; Return nil to indicate agent death
        (-> agent
            (assoc :x new-x :y new-y)
            (assoc :energy new-energy)
            (assoc :age new-age)))))

  (get-position [agent] [x y])
  (set-position [agent pos] (assoc agent :x (first pos) :y (second pos))))

(defn create-sugar-agent
  "Create a sugar-consuming agent"
  [id x y & {:keys [energy vision metabolism max-age age]
             :or {energy 50 vision 1 metabolism 1 max-age 100 age 0}}]
  (->SugarAgent id x y energy vision metabolism max-age age))

(defn sugar-growth
  "Grow sugar back in cells over time"
  [world growth-rate max-sugar]
  (reduce (fn [w y]
            (reduce (fn [w2 x]
                      (let [current-sugar (.get-cell w2 x y)]
                        (if (and current-sugar (< current-sugar max-sugar))
                          (.set-cell w2 x y (min max-sugar (+ current-sugar growth-rate)))
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
                                      (.set-cell w3 cell-x cell-y (int sugar-value))
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
                  (let [[x y] (.get-position agent)]
                    (.set-cell world x y 0)))
                w
                (.get-agents w)))
      ;; Grow sugar back
      (sugar-growth 1 10)))

(defn run-sugarscape
  "Run a Sugarscape simulation"
  [& {:keys [width height num-agents steps]
      :or {width 50 height 50 num-agents 100 steps 500}}]

  (println "Initializing Sugarscape simulation...")

  ;; Create world with sugar peaks
  (let [world (w/create-grid-world width height)
        sugar-peaks [{:x 15 :y 15 :radius 10}
                     {:x 35 :y 35 :radius 8}]
        world-with-sugar (create-sugar-landscape world sugar-peaks 10)

        ;; Create agents at random positions
        agents (repeatedly num-agents
                          #(create-sugar-agent
                            (gensym "agent")
                            (rand-int width)
                            (rand-int height)
                            :energy (+ 25 (rand-int 50))
                            :vision (inc (rand-int 3))
                            :metabolism (inc (rand-int 3))
                            :max-age (+ 60 (rand-int 80))))

        initial-world (reduce #(.add-agent %1 %2) world-with-sugar agents)]

    (println (str "Starting with " num-agents " agents"))

    ;; Run simulation
    (sim/run-simulation initial-world steps
                        :step-fn sugarscape-step
                        :print-every 50)))

;; Example usage and demo
(defn demo []
  (println "=== Sugarscape Demo ===")
  (let [final-world (run-sugarscape :width 30 :height 30 :num-agents 50 :steps 200)]
    (println "\n=== Final World State ===")
    (sim/print-world final-world)
    (println "\n=== Final Statistics ===")
    (println (sim/world-stats final-world))))