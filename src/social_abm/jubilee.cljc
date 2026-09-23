(ns social-abm.jubilee
  "Pure, non-spatial debt-jubilee model: wealth dynamics, bilateral loans,
   arrears, and a king who can cancel debt. No DOM/rendering here.")

(def default-params
  {:population 150
   :init-wealth 0.5              ; thin starting cushion: shocks bite from tick 1
   :return-mean 0.02            ; mean return on wealth per tick
   :return-vol 0.05              ; +/- uniform noise on return
   :base-income 1.0              ; breakeven with subsistence-cost on average; debt comes from noise, not a
                                  ; guaranteed structural deficit (a permanent gap here drains the whole
                                  ; economy to near-zero within ~100 ticks regardless of jubilee/bankruptcy
                                  ; policy, which confounds any comparison between the two -- don't reintroduce one)
   :consumption-vol 0.3          ; +/- uniform noise on cost-of-living bill
   :subsistence-cost 1.0         ; mean cost-of-living bill per tick
   :interest-rate 0.02           ; per-tick interest on outstanding loans
   :lend-reserve-multiple 2      ; lenders keep this many x subsistence-cost in reserve
   :repay-reserve-multiple 2     ; agents repay once wealth exceeds this x subsistence-cost
   :repayment-rate 0.2           ; fraction of surplus above reserve paid toward debt each tick
   :arrears-fraction 0.3         ; share of any shortfall that becomes arrears (no lender) vs a loan
   :jubilee-mode :periodic       ; :periodic :stochastic :threshold :manual
   :jubilee-period 50
   :jubilee-hazard 0.02
   :gini-threshold 0.6
   :jubilee-cooldown 20
   :haircut 1.0                  ; fraction of debt/arrears forgiven when jubilee fires (collective)

   :bankruptcy-enabled? false        ; individual relief, independent of the king's jubilee
   :bankruptcy-debt-multiple 3       ; discharge trigger: owed > this x subsistence-cost
   :bankruptcy-streak 8              ; must stay over the trigger this many consecutive ticks
   :bankruptcy-haircut 1.0           ; fraction of that agent's debt+arrears forgiven
   :bankruptcy-exclusion 20})        ; ticks after discharge with no new loans (arrears still possible)

;; ---------------------------------------------------------------------------
;; Agents & state

(defn make-agent [id wealth]
  {:id id :wealth wealth :arrears 0.0 :distress-streak 0 :exclusion-remaining 0})

(defn init-state [params]
  (let [n (:population params)]
    {:agents (mapv #(make-agent % (:init-wealth params)) (range n))
     :loans []
     :tick 0
     :next-loan-id 0
     :king-treasury 0.0
     :jubilee-ticks []
     :bankruptcy-ticks []
     :total-bankruptcies 0
     :history []
     :net-worths []
     :params params}))

;; ---------------------------------------------------------------------------
;; Step A/B: returns on wealth, income, cost-of-living (capped at available cash)

(defn apply-returns-income-consumption
  "Returns [agent' unpaid-bill] for one agent's per-tick cash flow."
  [agent params]
  (let [{:keys [return-mean return-vol base-income consumption-vol subsistence-cost]} params
        shock (* return-vol (dec (* 2 (rand))))
        w1 (max 0 (* (:wealth agent) (+ 1 return-mean shock)))
        w2 (+ w1 base-income)
        bill-noise (* consumption-vol (dec (* 2 (rand))))
        bill (max 0 (* subsistence-cost (+ 1 bill-noise)))
        payable (min w2 bill)
        unpaid (- bill payable)]
    [(assoc agent :wealth (- w2 payable)) unpaid]))

;; ---------------------------------------------------------------------------
;; Step C: interest accrual on outstanding loans

(defn accrue-interest [loans]
  (mapv (fn [loan] (update loan :balance * (+ 1 (:rate loan)))) loans))

;; ---------------------------------------------------------------------------
;; Step D/E: cover shortfalls via arrears (no cash, a claim on the king) or a
;; bilateral loan from a lender with surplus above their reserve. Agents still
;; inside their post-bankruptcy exclusion window can't get a new loan -- their
;; whole shortfall becomes arrears instead (no lender would extend them credit;
;; an unpaid bill still accrues, since they still have to eat).

(defn resolve-borrowing
  [agents unpaid existing-loans params next-loan-id]
  (let [{:keys [arrears-fraction lend-reserve-multiple subsistence-cost interest-rate]} params
        reserve (* subsistence-cost lend-reserve-multiple)
        borrower-ids (shuffle (filter #(pos? (nth unpaid %)) (range (count agents))))]
    (loop [agents agents
           bids borrower-ids
           loans existing-loans
           next-id next-loan-id]
      (if (empty? bids)
        {:agents agents :loans loans :next-loan-id next-id}
        (let [bid (first bids)
              amt (nth unpaid bid)
              excluded? (pos? (:exclusion-remaining (nth agents bid)))
              arrears-amt (if excluded? amt (* amt arrears-fraction))
              loan-need (if excluded? 0 (- amt arrears-amt))
              agents (update agents bid update :arrears + arrears-amt)
              lender-idx (->> (range (count agents))
                               (remove #(= % bid))
                               (filter #(> (- (:wealth (nth agents %)) reserve) 0))
                               shuffle
                               first)]
          (if (and lender-idx (pos? loan-need))
            (let [surplus (- (:wealth (nth agents lender-idx)) reserve)
                  transfer (min loan-need surplus)
                  remainder (- loan-need transfer)
                  agents (-> agents
                             (update lender-idx update :wealth - transfer)
                             (update bid update :wealth + transfer))
                  agents (if (pos? remainder)
                           (update agents bid update :arrears + remainder)
                           agents)
                  loan {:id next-id :lender lender-idx :borrower bid
                        :balance transfer :rate interest-rate}]
              (recur agents (rest bids) (conj loans loan) (inc next-id)))
            (recur (if (pos? loan-need)
                     (update agents bid update :arrears + loan-need)
                     agents)
                   (rest bids) loans next-id)))))))

;; ---------------------------------------------------------------------------
;; Step F: repayment. Agents with wealth above their reserve pay down loans
;; first (to their actual lenders), then arrears (to the king; that cash
;; leaves the modeled economy).

(defn resolve-repayment [agents loans params]
  (let [{:keys [subsistence-cost repay-reserve-multiple repayment-rate]} params
        reserve (* subsistence-cost repay-reserve-multiple)]
    (reduce
     (fn [{:keys [agents loans treasury]} bid]
       (let [agent (nth agents bid)
             surplus (max 0 (- (:wealth agent) reserve))
             budget (* surplus repayment-rate)]
         (if (<= budget 0)
           {:agents agents :loans loans :treasury treasury}
           (let [[loans' agents' budget']
                 (reduce (fn [[ls ag bud] loan]
                           (if (and (= (:borrower loan) bid) (pos? bud) (pos? (:balance loan)))
                             (let [pay (min bud (:balance loan))]
                               [(conj ls (update loan :balance - pay))
                                (-> ag
                                    (update bid update :wealth - pay)
                                    (update (:lender loan) update :wealth + pay))
                                (- bud pay)])
                             [(conj ls loan) ag bud]))
                         [[] agents budget]
                         loans)
                 arrears-amt (:arrears (nth agents' bid))
                 arrears-pay (min budget' arrears-amt)
                 agents'' (if (pos? arrears-pay)
                            (-> agents'
                                (update bid update :arrears - arrears-pay)
                                (update bid update :wealth - arrears-pay))
                            agents')]
             {:agents agents'' :loans loans' :treasury (+ treasury arrears-pay)}))))
     {:agents agents :loans loans :treasury 0.0}
     (range (count agents)))))

(defn drop-dead-loans [loans]
  (filterv #(> (:balance %) 1e-6) loans))

(defn- claims-by-agent [loans]
  (reduce (fn [m l] (update m (:lender l) (fnil + 0) (:balance l))) {} loans))

(defn- debts-by-agent [loans]
  (reduce (fn [m l] (update m (:borrower l) (fnil + 0) (:balance l))) {} loans))

;; ---------------------------------------------------------------------------
;; Bankruptcy: individual relief, independent of the king. An agent whose debt
;; burden (loans + arrears) has stayed above the trigger for `bankruptcy-streak`
;; consecutive ticks gets `bankruptcy-haircut` of it discharged, and is locked
;; out of new loans for `bankruptcy-exclusion` ticks (arrears can still accrue).

(defn resolve-bankruptcy [agents loans params]
  (let [{:keys [bankruptcy-enabled? bankruptcy-debt-multiple bankruptcy-streak
                bankruptcy-haircut bankruptcy-exclusion subsistence-cost]} params
        debts (debts-by-agent loans)
        threshold (* bankruptcy-debt-multiple subsistence-cost)
        agents' (mapv (fn [a]
                         (let [owed (+ (get debts (:id a) 0) (:arrears a))
                               over? (and bankruptcy-enabled? (> owed threshold))]
                           (-> a
                               (assoc :distress-streak (if over? (inc (:distress-streak a)) 0))
                               (update :exclusion-remaining #(max 0 (dec (or % 0)))))))
                       agents)
        bankrupt-ids (if bankruptcy-enabled?
                       (set (keep (fn [a] (when (>= (:distress-streak a) bankruptcy-streak) (:id a)))
                                  agents'))
                       #{})]
    (if (empty? bankrupt-ids)
      {:agents agents' :loans loans :bankrupt-ids []}
      {:agents (mapv (fn [a]
                        (if (contains? bankrupt-ids (:id a))
                          (-> a
                              (update :arrears * (- 1 bankruptcy-haircut))
                              (assoc :distress-streak 0 :exclusion-remaining bankruptcy-exclusion))
                          a))
                      agents')
       :loans (drop-dead-loans
               (mapv (fn [l] (if (contains? bankrupt-ids (:borrower l))
                               (update l :balance * (- 1 bankruptcy-haircut))
                               l))
                     loans))
       :bankrupt-ids (vec bankrupt-ids)})))

;; ---------------------------------------------------------------------------
;; Jubilee

(defn apply-jubilee
  "Forgive `haircut` fraction of every loan balance and every agent's arrears."
  [state]
  (let [haircut (get-in state [:params :haircut])
        loans' (->> (:loans state)
                    (mapv #(update % :balance * (- 1 haircut)))
                    (filterv #(> (:balance %) 1e-6)))
        agents' (mapv #(update % :arrears * (- 1 haircut)) (:agents state))]
    (-> state
        (assoc :loans loans' :agents agents')
        (update :jubilee-ticks conj (:tick state)))))

(defn should-jubilee? [state gini-now]
  (let [{:keys [jubilee-mode jubilee-period jubilee-hazard gini-threshold jubilee-cooldown]} (:params state)
        tick (:tick state)
        last-tick (or (last (:jubilee-ticks state)) (- jubilee-cooldown))]
    (case jubilee-mode
      :periodic (and (pos? tick) (zero? (mod tick jubilee-period)))
      :stochastic (< (rand) jubilee-hazard)
      :threshold (and (> gini-now gini-threshold) (>= (- tick last-tick) jubilee-cooldown))
      false)))

;; ---------------------------------------------------------------------------
;; Metrics

(defn net-worths
  "Vector of net worth (cash + claims held - debt owed - arrears owed), indexed like agents."
  [agents loans]
  (let [claims (claims-by-agent loans)
        debts (debts-by-agent loans)]
    (mapv (fn [a] (- (+ (:wealth a) (get claims (:id a) 0))
                      (get debts (:id a) 0)
                      (:arrears a)))
          agents)))

(defn gini
  "Gini coefficient via the relative mean absolute difference, normalized by mean |x|
   rather than mean x. Plain mean-x blows up (wild swings, wrong sign) whenever the
   population's net worth hovers near zero, which happens routinely in a stressed
   economy; mean |x| stays well-behaved and bounded unless everyone is at exactly 0."
  [xs]
  (let [n (count xs)]
    (if (< n 2)
      0.0
      (let [sorted (vec (sort xs))
            mean-abs-x (/ (reduce + (map (fn [x] (if (neg? x) (- x) x)) sorted)) n)
            numerator (reduce + (map-indexed (fn [idx x] (* x (- (* 2 (inc idx)) n 1))) sorted))]
        (if (zero? mean-abs-x) 0.0 (/ numerator (* n n mean-abs-x)))))))

(defn top-decile-share [xs]
  (let [n (count xs)]
    (if (zero? n)
      0.0
      (let [k (max 1 (quot n 10))
            sorted-desc (vec (sort > xs))
            top-sum (reduce + (take k sorted-desc))
            total (reduce + xs)]
        (if (zero? total) 0.0 (/ top-sum total))))))

(defn frac-negative [xs]
  (let [n (count xs)]
    (if (zero? n) 0.0 (/ (count (filter neg? xs)) n))))

(defn- metrics-entry [state nw jubilee? n-bankrupt]
  {:tick (:tick state)
   :gini (gini nw)
   :top-decile (top-decile-share nw)
   :frac-negative (frac-negative nw)
   :total-debt (reduce + 0.0 (map :balance (:loans state)))
   :total-arrears (reduce + 0.0 (map :arrears (:agents state)))
   :n-loans (count (:loans state))
   :total-wealth (reduce + 0.0 (map :wealth (:agents state)))
   :jubilee? jubilee?
   :n-bankrupt n-bankrupt
   :total-bankruptcies (:total-bankruptcies state)})

(defn- push-history [state entry]
  (let [h (conj (:history state) entry)]
    (assoc state :history (if (> (count h) 200) (subvec h (- (count h) 200)) h))))

;; ---------------------------------------------------------------------------
;; Top-level step

(defn step [state]
  (let [params (:params state)
        pairs (mapv #(apply-returns-income-consumption % params) (:agents state))
        agents1 (mapv first pairs)
        unpaid (mapv second pairs)
        loans1 (accrue-interest (:loans state))
        {agents2 :agents loans2 :loans next-id :next-loan-id}
        (resolve-borrowing agents1 unpaid loans1 params (:next-loan-id state))
        {agents3 :agents loans3 :loans treasury-delta :treasury}
        (resolve-repayment agents2 loans2 params)
        loans3 (drop-dead-loans loans3)
        nw (net-worths agents3 loans3)
        g (gini nw)
        mid (assoc state
                   :agents agents3 :loans loans3 :next-loan-id next-id
                   :king-treasury (+ (:king-treasury state) treasury-delta)
                   :tick (inc (:tick state)))
        fire? (should-jubilee? mid g)
        post-jubilee (if fire? (apply-jubilee mid) mid)
        {bagents :agents bloans :loans bankrupt-ids :bankrupt-ids}
        (resolve-bankruptcy (:agents post-jubilee) (:loans post-jubilee) params)
        n-bankrupt (count bankrupt-ids)
        final (cond-> (assoc post-jubilee :agents bagents :loans bloans)
                (pos? n-bankrupt) (-> (update :bankruptcy-ticks conj {:tick (:tick post-jubilee) :ids bankrupt-ids})
                                      (update :total-bankruptcies + n-bankrupt)))
        nw-final (net-worths (:agents final) (:loans final))]
    (-> final
        (assoc :net-worths nw-final)
        (push-history (metrics-entry final nw-final fire? n-bankrupt)))))

(defn force-jubilee
  "King's-discretion jubilee: apply immediately regardless of trigger mode."
  [state]
  (let [state' (apply-jubilee state)
        nw (net-worths (:agents state') (:loans state'))]
    (-> state'
        (assoc :net-worths nw)
        (push-history (metrics-entry state' nw true 0)))))
