(ns social-abm.browser.agent
  "Basic agent implementations and behaviors for ClojureScript"
  (:require [social-abm.browser.protocols :refer [Agent] :as p]
            [social-abm.browser.world :as w]))

(defrecord BasicAgent [id x y energy vision metabolism]
  Agent
  (step [agent world]
    (let [neighbors (p/get-neighbors world x y vision)
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
  (let [[x y] (p/get-position agent)
        directions [[-1 -1] [-1 0] [-1 1] [0 -1] [0 1] [1 -1] [1 0] [1 1]]
        [dx dy] (rand-nth directions)
        new-x (+ x dx)
        new-y (+ y dy)]
    (if (and (>= new-x 0) (< new-x (:width world))
             (>= new-y 0) (< new-y (:height world)))
      (p/set-position agent [new-x new-y])
      agent)))

(defn move-towards
  "Move agent towards a target position"
  [agent target-x target-y]
  (let [[x y] (p/get-position agent)
        dx (cond (< x target-x) 1
                 (> x target-x) -1
                 :else 0)
        dy (cond (< y target-y) 1
                 (> y target-y) -1
                 :else 0)]
    (p/set-position agent [(+ x dx) (+ y dy)])))

(defn find-resource
  "Find the nearest resource within vision range"
  [agent world resource-predicate]
  (let [[x y] (p/get-position agent)
        vision (:vision agent)
        neighbors (p/get-neighbors world x y vision)
        resources (filter resource-predicate neighbors)]
    (when (seq resources)
      (apply min-key #(w/manhattan-distance x y (:x %) (:y %)) resources))))

(defrecord SugarAgent [id x y energy vision metabolism max-age age]
  Agent
  (step [agent world]
    (let [neighbors (p/get-neighbors world x y vision)
          sugar-cells (filter #(and (:value %) (> (:value %) 0)) neighbors)
          current-sugar (p/get-cell world x y)

          ;; Find all cells with maximum sugar value within vision
          max-sugar-value (if (seq sugar-cells)
                           (:value (apply max-key :value sugar-cells))
                           0)
          best-sugar-cells (filter #(= (:value %) max-sugar-value) sugar-cells)

          ;; Move to a random best sugar location, or stay if current location is equally good
          [new-x new-y] (cond
                         ;; If current location has max sugar, stay put
                         (= (or current-sugar 0) max-sugar-value) [x y]
                         ;; If there are better sugar locations, pick one randomly
                         (seq best-sugar-cells) (let [chosen-cell (rand-nth best-sugar-cells)]
                                                  [(:x chosen-cell) (:y chosen-cell)])
                         ;; If no sugar visible, move randomly
                         :else (let [empty-neighbors (filter #(or (nil? (:value %)) (= 0 (:value %))) neighbors)
                                     move-options (if (seq empty-neighbors) empty-neighbors neighbors)
                                     chosen-cell (if (seq move-options) (rand-nth move-options) {:x x :y y})]
                                 [(:x chosen-cell) (:y chosen-cell)]))

          ;; Consume sugar at new location
          sugar-at-pos (p/get-cell world new-x new-y)
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