(ns social-abm.simulation
  "Simulation runner and utilities"
  (:require [social-abm.core :refer [World]]
            [social-abm.world :as w]))

(defn run-simulation
  "Run a simulation for n steps"
  [world n-steps & {:keys [step-fn print-every]
                    :or {step-fn identity print-every 10}}]
  (loop [current-world world
         step 0]
    (if (< step n-steps)
      (do
        (when (and print-every (zero? (mod step print-every)))
          (println (str "Step " step " - Agents: " (count (.get-agents current-world)))))
        (let [stepped-world (.step-world current-world)
              modified-world (step-fn stepped-world)]
          (recur modified-world (inc step))))
      current-world)))


(defn print-world
  "Print a simple ASCII representation of the world"
  [world & {:keys [agent-char empty-char resource-char]
            :or  {agent-char \O empty-char \. resource-char \+}}] ;Unicode would be nice but loses equal width
  (let [agents-by-pos (into {} (map (fn [agent]
                                     (let [[x y] (.get-position agent)]
                                       [[x y] agent]))
                                   (.get-agents world)))]
    (doseq [y (range (:height world))]
      (doseq [x (range (:width world))]
        (let [cell-value (.get-cell world x y)
              agent (get agents-by-pos [x y])]
          (cond
            agent (print agent-char)
            cell-value (print resource-char)
            :else (print empty-char))))
      (println))))

(defn world-stats
  "Get basic statistics about the world state"
  [world]
  (let [agents (.get-agents world)
        alive-agents (filter #(> (:energy %) 0) agents)
        total-energy (reduce + (map :energy alive-agents))
        avg-energy (if (seq alive-agents)
                     (/ total-energy (count alive-agents))
                     0)]
    {:tick (:tick world)
     :total-agents (count agents)
     :alive-agents (count alive-agents)
     :total-energy total-energy
     :avg-energy avg-energy}))

(defn log-stats
  "Log world statistics"
  [world]
  (let [stats (world-stats world)]
    (println (format "Tick %d | Agents: %d/%d | Avg Energy: %.2f"
                     (:tick stats)
                     (:alive-agents stats)
                     (:total-agents stats)
                     (:avg-energy stats))))
  world)

(defn remove-dead-agents
  "Remove agents with zero or negative energy"
  [world]
  (update world :agents #(filter (fn [agent] (> (:energy agent) 0)) %)))

(defn spawn-resources
  "Randomly spawn resources in empty cells"
  [world resource-value spawn-rate]
  (let [empty-cells (w/get-empty-cells world)
        num-to-spawn (int (* spawn-rate (count empty-cells)))
        cells-to-fill (take num-to-spawn (shuffle empty-cells))]
    (reduce (fn [w cell]
              (.set-cell w (:x cell) (:y cell) resource-value))
            world
            cells-to-fill)))

(defn create-simulation
  "Create a simulation with initial setup"
  [world-width world-height initial-agents initial-resources]
  (let [world (w/create-grid-world world-width world-height)]
    (-> world
        (as-> w (reduce #(.add-agent %1 %2) w initial-agents))
        (spawn-resources 10 initial-resources))))
