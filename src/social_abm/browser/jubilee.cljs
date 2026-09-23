(ns social-abm.browser.jubilee
  "Browser UI for the debt-jubilee lab: non-spatial, no grid/movement."
  (:require [social-abm.jubilee :as j]
            [social-abm.browser.canvas :as canvas]))

(def simulation-state (atom {:sim nil
                              :running false
                              :speed 15
                              :ctx nil
                              :interval-id nil
                              :ladder []}))

(def live-slider-ids
  ["return-mean" "return-vol" "base-income" "consumption-vol" "subsistence-cost"
   "interest-rate" "lend-reserve-multiple" "repay-reserve-multiple" "repayment-rate"
   "arrears-fraction" "jubilee-period" "jubilee-hazard" "gini-threshold"
   "jubilee-cooldown" "haircut" "bankruptcy-debt-multiple" "bankruptcy-streak"
   "bankruptcy-haircut" "bankruptcy-exclusion"])

;; DOM id for every param that isn't structural (population/init-wealth) or
;; handled specially (jubilee-mode is a <select>, bankruptcy-enabled? a checkbox).
(def param-id-map
  {:return-mean "return-mean" :return-vol "return-vol" :base-income "base-income"
   :consumption-vol "consumption-vol" :subsistence-cost "subsistence-cost"
   :interest-rate "interest-rate" :lend-reserve-multiple "lend-reserve-multiple"
   :repay-reserve-multiple "repay-reserve-multiple" :repayment-rate "repayment-rate"
   :arrears-fraction "arrears-fraction" :jubilee-period "jubilee-period"
   :jubilee-hazard "jubilee-hazard" :gini-threshold "gini-threshold"
   :jubilee-cooldown "jubilee-cooldown" :haircut "haircut"
   :bankruptcy-debt-multiple "bankruptcy-debt-multiple" :bankruptcy-streak "bankruptcy-streak"
   :bankruptcy-haircut "bankruptcy-haircut" :bankruptcy-exclusion "bankruptcy-exclusion"})

;; Canned parameter sets. Each is a COMPLETE params map (not a diff over
;; whatever's currently on screen) so scenarios are reproducible and
;; comparable -- only the relief mechanism differs between them, every
;; economic-dynamics dial (returns, income, volatility...) stays fixed.
(def scenarios
  [{:id "no-relief"
    :label "No relief (baseline)"
    :description
    "No jubilee, no bankruptcy. Debt and arrears accumulate freely and inequality climbs with no correction. This is the baseline the other scenarios are measured against -- run it first."
    :params (merge j/default-params {:jubilee-mode :manual :bankruptcy-enabled? false})}
   {:id "periodic-jubilee"
    :label "Periodic jubilee"
    :description
    "The king cancels all debt on a fixed schedule, collectively, for everyone at once. Watch debt and inequality build up between jubilees, then drop sharply at each reset (pink vertical lines on the charts)."
    :params (merge j/default-params {:jubilee-mode :periodic :jubilee-period 50
                                      :haircut 1.0 :bankruptcy-enabled? false})}
   {:id "bankruptcy-only"
    :label "Bankruptcy only"
    :description
    "No collective jubilee. Instead, any individual agent whose own debt burden stays too high for too long gets their own discharge, on their own timeline, with a credit lockout afterward (orange vertical lines). Compare the smoother, more sustained inequality here to periodic jubilee's sharp resets."
    :params (merge j/default-params {:jubilee-mode :manual :bankruptcy-enabled? true
                                      :bankruptcy-debt-multiple 3 :bankruptcy-streak 8
                                      :bankruptcy-haircut 1.0 :bankruptcy-exclusion 20})}
   {:id "both"
    :label "Jubilee + bankruptcy"
    :description
    "Both mechanisms running together: periodic collective resets, plus individual discharges for whoever falls into serious distress in between. Watch whether bankruptcy mostly mops up the agents a jubilee hasn't gotten to yet, or fires independently of the jubilee cycle."
    :params (merge j/default-params {:jubilee-mode :periodic :jubilee-period 50 :haircut 1.0
                                      :bankruptcy-enabled? true :bankruptcy-debt-multiple 3
                                      :bankruptcy-streak 8 :bankruptcy-haircut 1.0
                                      :bankruptcy-exclusion 20})}])

(defn- num [id] (js/parseFloat (.-value (js/document.getElementById id))))
(defn- int-num [id] (js/parseInt (.-value (js/document.getElementById id))))
(defn- txt [id] (.-value (js/document.getElementById id)))
(defn- bool [id] (.-checked (js/document.getElementById id)))

(defn read-live-params
  "Params a user can change mid-run without resetting the population."
  []
  {:return-mean (num "return-mean")
   :return-vol (num "return-vol")
   :base-income (num "base-income")
   :consumption-vol (num "consumption-vol")
   :subsistence-cost (num "subsistence-cost")
   :interest-rate (num "interest-rate")
   :lend-reserve-multiple (num "lend-reserve-multiple")
   :repay-reserve-multiple (num "repay-reserve-multiple")
   :repayment-rate (num "repayment-rate")
   :arrears-fraction (num "arrears-fraction")
   :jubilee-mode (keyword (txt "jubilee-mode"))
   :jubilee-period (int-num "jubilee-period")
   :jubilee-hazard (num "jubilee-hazard")
   :gini-threshold (num "gini-threshold")
   :jubilee-cooldown (int-num "jubilee-cooldown")
   :haircut (num "haircut")
   :bankruptcy-enabled? (bool "bankruptcy-enabled")
   :bankruptcy-debt-multiple (num "bankruptcy-debt-multiple")
   :bankruptcy-streak (int-num "bankruptcy-streak")
   :bankruptcy-haircut (num "bankruptcy-haircut")
   :bankruptcy-exclusion (int-num "bankruptcy-exclusion")})

(defn agent-details
  "Per-agent breakdown (cash/claims/debt/arrears/net-worth), sorted ascending by net worth."
  [sim]
  (let [agents (:agents sim)
        loans (:loans sim)
        claims (reduce (fn [m l] (update m (:lender l) (fnil + 0) (:balance l))) {} loans)
        debts (reduce (fn [m l] (update m (:borrower l) (fnil + 0) (:balance l))) {} loans)
        nws (:net-worths sim)]
    (->> (map (fn [a nw]
                {:id (:id a) :wealth (:wealth a) :arrears (:arrears a)
                 :claims (get claims (:id a) 0) :debt (get debts (:id a) 0) :net-worth nw
                 :distress-streak (:distress-streak a) :exclusion-remaining (:exclusion-remaining a)})
              agents nws)
         (sort-by :net-worth)
         vec)))

(defn update-stats! [sim]
  (let [last-entry (last (:history sim))]
    (doseq [[id v] [["tick" (:tick sim)]
                    ["population" (count (:agents sim))]
                    ["n-loans" (count (:loans sim))]
                    ["gini-val" (.toFixed (or (:gini last-entry) 0) 3)]
                    ["negshare-val" (str (.toFixed (* 100 (or (:frac-negative last-entry) 0)) 1) "%")]
                    ["wealth-val" (.toFixed (or (:total-wealth last-entry) 0) 1)]
                    ["debt-val" (.toFixed (or (:total-debt last-entry) 0) 1)]
                    ["arrears-val" (.toFixed (or (:total-arrears last-entry) 0) 1)]
                    ["treasury-val" (.toFixed (:king-treasury sim) 1)]
                    ["jubilee-count" (count (:jubilee-ticks sim))]
                    ["bankruptcy-count" (:total-bankruptcies sim)]]]
      (when-let [el (js/document.getElementById id)]
        (set! (.-textContent el) (str v))))))

(defn- tight-range
  "[min max] padded by 10% of the span, so small-but-real swings aren't flattened
   by pinning the axis to a wide fixed range like [0 1]."
  [values]
  (if (empty? values)
    [0 1]
    (let [mn (reduce min values)
          mx (reduce max values)
          pad (max 0.02 (* 0.1 (- mx mn)))]
      [(- mn pad) (+ mx pad)])))

(defn draw-jubilee-charts! [sim]
  (let [history (:history sim)
        markers (vec (keep-indexed (fn [i e] (when (:jubilee? e) i)) history))
        markers2 (vec (keep-indexed (fn [i e] (when (pos? (:n-bankrupt e 0)) i)) history))
        opts {:markers markers :markers2 markers2}
        gini-vals (mapv :gini history)
        topdecile-vals (mapv :top-decile history)
        negshare-vals (mapv #(* 100 (:frac-negative %)) history)
        [gmin gmax] (tight-range gini-vals)
        [tmin tmax] (tight-range topdecile-vals)
        [nmin nmax] (tight-range negshare-vals)]
    (canvas/draw-line-chart "chart-gini" gini-vals "#3f51b5"
                             (assoc opts :y-min gmin :y-max gmax :precision 3))
    (canvas/draw-line-chart "chart-topdecile" topdecile-vals "#ff9800"
                             (assoc opts :y-min tmin :y-max tmax :precision 3))
    (canvas/draw-line-chart "chart-negshare" negshare-vals "#e53935"
                             (assoc opts :y-min nmin :y-max nmax :precision 1))
    (canvas/draw-line-chart "chart-debt" (mapv :total-debt history) "#2196f3" opts)
    (canvas/draw-line-chart "chart-arrears" (mapv :total-arrears history) "#9c27b0" opts)))

(defn render! []
  (let [{:keys [sim ctx]} @simulation-state]
    (when (and sim ctx)
      (let [canvas-el (js/document.getElementById "jubilee-canvas")
            w (.-width canvas-el)
            h (.-height canvas-el)
            details (agent-details sim)]
        (swap! simulation-state assoc :ladder details)
        (canvas/draw-ladder-chart ctx w h details))
      (update-stats! sim)
      (draw-jubilee-charts! sim))))

(defn simulation-step []
  (when (:running @simulation-state)
    (let [live-params (read-live-params)
          sim (update (:sim @simulation-state) :params merge live-params)
          sim' (j/step sim)]
      (swap! simulation-state assoc :sim sim')
      (render!))))

(defn start-simulation-loop []
  (when (:running @simulation-state)
    (let [speed (:speed @simulation-state)
          interval (max 10 (- 510 (* speed 25)))
          interval-id (js/setInterval simulation-step interval)]
      (swap! simulation-state assoc :interval-id interval-id))))

(defn stop-simulation-loop []
  (when-let [interval-id (:interval-id @simulation-state)]
    (js/clearInterval interval-id)
    (swap! simulation-state assoc :interval-id nil)))

(defn start-simulation []
  (swap! simulation-state assoc :running true)
  (start-simulation-loop))

(defn stop-simulation []
  (stop-simulation-loop)
  (swap! simulation-state assoc :running false))

(defn restart-simulation-loop []
  (when (:running @simulation-state)
    (stop-simulation-loop)
    (start-simulation-loop)))

(defn reset-simulation []
  (stop-simulation)
  (let [population (int-num "population-input")
        init-wealth (num "init-wealth")
        params (merge j/default-params (read-live-params)
                      {:population population :init-wealth init-wealth})
        sim (j/init-state params)]
    (swap! simulation-state assoc :sim sim)
    (render!)))

(defn declare-jubilee! []
  (swap! simulation-state update :sim j/force-jubilee)
  (render!))

(defn wire-live-slider!
  "Mirror a range input's value into an adjacent '<id>-val' span."
  [id]
  (when-let [el (js/document.getElementById id)]
    (let [out (js/document.getElementById (str id "-val"))]
      (when out (set! (.-textContent out) (.-value el)))
      (.addEventListener el "input"
                          (fn [e] (when out (set! (.-textContent out) (.-value (.-target e)))))))))

(defn setup-canvas-events []
  (let [canvas-el (js/document.getElementById "jubilee-canvas")
        tooltip (js/document.getElementById "agent-tooltip")]
    (.addEventListener canvas-el "mousemove"
      (fn [e]
        (let [rect (.getBoundingClientRect canvas-el)
              cx (- (.-clientX e) (.-left rect))
              details (:ladder @simulation-state)
              w (.-width canvas-el)
              entry (when (seq details) (canvas/find-ladder-entry-at-x details w cx))]
          (if entry
            (do
              (set! (.-innerHTML tooltip)
                    (str "<b>Agent " (:id entry) "</b><br>"
                         "Net worth: " (.toFixed (:net-worth entry) 2) "<br>"
                         "Cash: " (.toFixed (:wealth entry) 2) "<br>"
                         "Claims held (lender): " (.toFixed (:claims entry) 2) "<br>"
                         "Debt owed (borrower): " (.toFixed (:debt entry) 2) "<br>"
                         "Arrears: " (.toFixed (:arrears entry) 2)
                         (when (pos? (:distress-streak entry 0))
                           (str "<br>Distress streak: " (:distress-streak entry) " ticks"))
                         (when (pos? (:exclusion-remaining entry 0))
                           (str "<br>Credit-locked: " (:exclusion-remaining entry) " ticks left"))))
              (set! (.. tooltip -style -display) "block")
              (set! (.. tooltip -style -left) (str (+ (.-clientX e) 14) "px"))
              (set! (.. tooltip -style -top) (str (+ (.-clientY e) 14) "px")))
            (set! (.. tooltip -style -display) "none")))))
    (.addEventListener canvas-el "mouseleave" (fn [_] (set! (.. tooltip -style -display) "none")))))

(defn- sync-bankruptcy-params-visibility! []
  (when-let [el (js/document.getElementById "bankruptcy-params")]
    (set! (.. el -style -display) (if (bool "bankruptcy-enabled") "flex" "none"))))

(defn- sync-jubilee-params-visibility!
  "Show only the params relevant to the selected trigger mode (elements tagged
   data-jubilee-mode=\"...\"); e.g. period only matters for :periodic."
  []
  (let [mode (txt "jubilee-mode")]
    (.forEach (js/document.querySelectorAll "[data-jubilee-mode]")
              (fn [el]
                (set! (.. el -style -display)
                      (if (= mode (.getAttribute el "data-jubilee-mode")) "flex" "none"))))))

(defn- set-value! [id v]
  (when-let [el (js/document.getElementById id)]
    (set! (.-value el) (str v))
    (when-let [out (js/document.getElementById (str id "-val"))]
      (set! (.-textContent out) (str v)))))

(defn- set-checked! [id v]
  (when-let [el (js/document.getElementById id)]
    (set! (.-checked el) v)))

(defn fill-params-into-dom!
  "Write every control on the page to match `params` (a full params map)."
  [params]
  (set-value! "population-input" (:population params))
  (set-value! "init-wealth" (:init-wealth params))
  (doseq [[k id] param-id-map] (set-value! id (get params k)))
  (set-value! "jubilee-mode" (name (:jubilee-mode params)))
  (set-checked! "bankruptcy-enabled" (:bankruptcy-enabled? params))
  (sync-bankruptcy-params-visibility!)
  (sync-jubilee-params-visibility!))

(defn find-scenario [id]
  (some #(when (= id (:id %)) %) scenarios))

(defn apply-scenario!
  "Fill the DOM from the named scenario's params, show its description, and reset."
  [id]
  (when-let [scenario (find-scenario id)]
    (fill-params-into-dom! (:params scenario))
    (when-let [desc-el (js/document.getElementById "scenario-description")]
      (set! (.-textContent desc-el) (:description scenario)))
    (reset-simulation)))

(defn setup-controls []
  (.addEventListener (js/document.getElementById "start-btn") "click" start-simulation)
  (.addEventListener (js/document.getElementById "stop-btn") "click" stop-simulation)
  (.addEventListener (js/document.getElementById "reset-btn") "click" reset-simulation)
  (.addEventListener (js/document.getElementById "king-btn") "click" declare-jubilee!)
  (.addEventListener (js/document.getElementById "speed-slider") "input"
                      (fn [e]
                        (swap! simulation-state assoc :speed (js/parseInt (.-value (.-target e))))
                        (restart-simulation-loop)))
  (.addEventListener (js/document.getElementById "bankruptcy-enabled") "change"
                      (fn [_] (sync-bankruptcy-params-visibility!)))
  (.addEventListener (js/document.getElementById "jubilee-mode") "change"
                      (fn [_] (sync-jubilee-params-visibility!)))
  (.addEventListener (js/document.getElementById "scenario-select") "change"
                      (fn [e] (apply-scenario! (.-value (.-target e)))))
  (sync-bankruptcy-params-visibility!)
  (sync-jubilee-params-visibility!)
  (doseq [id live-slider-ids] (wire-live-slider! id)))

(defn init-jubilee! []
  (try
    (let [canvas-element (js/document.getElementById "jubilee-canvas")]
      (if canvas-element
        (let [ctx (.getContext canvas-element "2d")]
          (swap! simulation-state assoc :ctx ctx)
          (setup-controls)
          (setup-canvas-events)
          (apply-scenario! (.-value (js/document.getElementById "scenario-select")))
          (println "Jubilee lab initialized"))
        (println "ERROR: Canvas element 'jubilee-canvas' not found")))
    (catch js/Error e
      (println "ERROR initializing Jubilee lab:" (.-message e)))))
