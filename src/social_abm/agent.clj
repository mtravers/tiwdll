(ns social-abm.agent
  "Basic agent implementations and behaviors"
  (:require [social-abm.core :refer [Agent]]
            [social-abm.world :as w]))

(defrecord BasicAgent [id x y energy vision metabolism]
  Agent
  (step [agent world]
    (let [neighbors (.get-neighbors world x y vision)
          value-cells (filter :value neighbors)
          best-cell (when (seq value-cells)
                     (apply max-key :value value-cells))
          new-pos (if best-cell
                   [(:x best-cell) (:y best-cell)]
                   [x y])
          new-energy (- energy metabolism)]
      (-> agent
          (assoc :x (first new-pos) :y (second new-pos))
          (assoc :energy new-energy))))

  (get-position [agent]
    [x y])

  (set-position [agent pos]
    (assoc agent :x (first pos) :y (second pos))))

(defn create-basic-agent
  "Create a basic agent with given properties"
  [id x y & {:keys [energy vision metabolism]
             :or {energy 50 vision 1 metabolism 1}}]
  (->BasicAgent id x y energy vision metabolism))

(defn random-walk
  "Move agent randomly to an adjacent cell"
  [agent world]
  (let [[x y] (.get-position agent)
        directions [[-1 -1] [-1 0] [-1 1] [0 -1] [0 1] [1 -1] [1 0] [1 1]]
        [dx dy] (rand-nth directions)
        new-x (+ x dx)
        new-y (+ y dy)]
    (if (and (>= new-x 0) (< new-x (:width world))
             (>= new-y 0) (< new-y (:height world)))
      (.set-position agent [new-x new-y])
      agent)))

(defn move-towards
  "Move agent towards a target position"
  [agent target-x target-y]
  (let [[x y] (.get-position agent)
        dx (cond (< x target-x) 1
                 (> x target-x) -1
                 :else 0)
        dy (cond (< y target-y) 1
                 (> y target-y) -1
                 :else 0)]
    (.set-position agent [(+ x dx) (+ y dy)])))

(defn find-resource
  "Find the nearest resource within vision range"
  [agent world resource-predicate]
  (let [[x y] (.get-position agent)
        vision (:vision agent)
        neighbors (.get-neighbors world x y vision)
        resources (filter resource-predicate neighbors)]
    (when (seq resources)
      (apply min-key #(w/manhattan-distance x y (:x %) (:y %)) resources))))

(defn consume-resource
  "Consume a resource at the agent's current position"
  [agent world resource-amount]
  (let [[x y] (.get-position agent)]
    (-> world
        (.set-cell x y nil)
        (as-> w (assoc agent :energy (+ (:energy agent) resource-amount))))))

(defrecord SocialAgent [id x y energy vision metabolism friends enemies reputation]
  Agent
  (step [agent world]
    (let [nearby-agents (filter #(let [[ax ay] (.get-position %)]
                                  (< (w/manhattan-distance x y ax ay) vision))
                                (remove #{agent} (.get-agents world)))
          social-influence (reduce + (map #(if (contains? friends (:id %)) 1 -1) nearby-agents))
          energy-change (- metabolism social-influence)]
      (-> agent
          (random-walk world)
          (update :energy - energy-change)
          (update :reputation + (count nearby-agents)))))

  (get-position [agent] [x y])
  (set-position [agent pos] (assoc agent :x (first pos) :y (second pos))))

(defn create-social-agent
  "Create a social agent with relationships"
  [id x y & {:keys [energy vision metabolism friends enemies reputation]
             :or {energy 50 vision 2 metabolism 1 friends #{} enemies #{} reputation 0}}]
  (->SocialAgent id x y energy vision metabolism friends enemies reputation))