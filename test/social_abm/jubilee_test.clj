(ns social-abm.jubilee-test
  (:require [clojure.test :refer :all]
            [social-abm.jubilee :as j]))

(deftest gini-test
  (testing "equal distribution has zero gini"
    (is (== 0.0 (j/gini [10 10 10 10]))))
  (testing "one-holds-everything approaches (n-1)/n"
    (is (== 0.75 (j/gini [0 0 0 100]))))
  (testing "negative net worths don't blow up the formula"
    (is (number? (j/gini [-50 -10 5 20 200])))))

(deftest borrowing-conserves-cash-test
  (testing "a loan is a pure transfer: total wealth unchanged, arrears add no cash"
    (let [agents [(assoc (j/make-agent 0 0.0) :arrears 0.0)   ; needy, no cash
                  (assoc (j/make-agent 1 100.0) :arrears 0.0)] ; rich lender
          params (assoc j/default-params :lend-reserve-multiple 1 :subsistence-cost 1.0
                        :arrears-fraction 0.0)
          unpaid [10.0 0.0]
          before (reduce + (map :wealth agents))
          {:keys [agents loans]} (j/resolve-borrowing agents unpaid [] params 0)
          after (reduce + (map :wealth agents))]
      (is (== before after))
      (is (= 1 (count loans)))
      (is (== 10.0 (:balance (first loans))))))
  (testing "pure arrears (no lender reserve) creates a liability but no cash"
    (let [agents [(j/make-agent 0 0.0) (j/make-agent 1 1.0)] ; no one has reserve
          params (assoc j/default-params :lend-reserve-multiple 100 :subsistence-cost 1.0
                        :arrears-fraction 0.5)
          unpaid [10.0 0.0]
          before (reduce + (map :wealth agents))
          {:keys [agents loans]} (j/resolve-borrowing agents unpaid [] params 0)
          after (reduce + (map :wealth agents))]
      (is (== before after) "no lender available -> everything becomes arrears, no cash created")
      (is (empty? loans))
      (is (== 10.0 (:arrears (first agents)))))))

(deftest debt-grows-without-jubilee-test
  (testing "under income stress, debt+arrears accumulate when jubilee never fires"
    (let [params (assoc j/default-params
                        :population 40 :base-income 0.5 :subsistence-cost 1.0
                        :consumption-vol 0.1 :return-mean 0.0 :return-vol 0.01
                        :jubilee-mode :manual)
          state (reduce (fn [s _] (j/step s)) (j/init-state params) (range 30))
          total-owed (+ (reduce + (map :balance (:loans state)))
                        (reduce + (map :arrears (:agents state))))]
      (is (pos? total-owed)))))

(deftest defaults-produce-visible-distress-test
  (testing "out-of-the-box params show real negative net worth before the first jubilee fires, not a boring flat line"
    (let [ticks (dec (:jubilee-period j/default-params)) ; stop just short of the periodic jubilee
          state (reduce (fn [s _] (j/step s)) (j/init-state j/default-params) (range ticks))
          frac-neg (:frac-negative (last (:history state)))]
      (is (pos? frac-neg) "at least some agents should be underwater by tick jubilee-period - 1"))))

(deftest loans-persist-across-ticks-test
  (testing "a loan created on one tick is still there (with accrued interest) on the next tick"
    (let [params (assoc j/default-params
                        :population 2 :init-wealth 0.0 :base-income 0.0 :subsistence-cost 10.0
                        :consumption-vol 0.0 :return-mean 0.0 :return-vol 0.0
                        :lend-reserve-multiple 0 :arrears-fraction 0.0 :interest-rate 0.1
                        :jubilee-mode :manual :bankruptcy-enabled? false)
          state0 (-> (j/init-state params) (assoc-in [:agents 1 :wealth] 100.0))
          state1 (j/step state0)
          state2 (j/step state1)]
      (is (= 1 (count (:loans state1))) "a loan should form on tick 1")
      (is (= 1 (count (:loans state2))) "the same loan should still be there on tick 2, not wiped")
      (is (> (:balance (first (:loans state2))) (:balance (first (:loans state1))))
          "its balance should have grown from interest, not reset"))))

(deftest resolve-bankruptcy-test
  (testing "an agent over the debt threshold long enough gets an individual discharge; others are untouched"
    (let [agents [(assoc (j/make-agent 0 0.0) :arrears 10.0 :distress-streak 7)  ; one tick from firing
                  (assoc (j/make-agent 1 0.0) :arrears 2.0 :distress-streak 7)]  ; well under threshold
          params (assoc j/default-params :bankruptcy-enabled? true
                        :bankruptcy-debt-multiple 3 :subsistence-cost 1.0
                        :bankruptcy-streak 8 :bankruptcy-haircut 1.0 :bankruptcy-exclusion 5)
          {:keys [agents bankrupt-ids]} (j/resolve-bankruptcy agents [] params)]
      (is (= [0] bankrupt-ids) "only the over-threshold agent should fire")
      (is (zero? (:arrears (first agents))) "discharged agent's arrears wiped")
      (is (= 5 (:exclusion-remaining (first agents))) "discharged agent enters the exclusion window")
      (is (== 2.0 (:arrears (second agents))) "unaffected agent keeps their smaller arrears")
      (is (zero? (:distress-streak (second agents))) "agent under threshold: streak resets"))))

(deftest bankruptcy-is-individual-not-collective-test
  (testing "two agents both over the debt threshold, different streaks: only the one meeting the streak fires"
    (let [agents [(assoc (j/make-agent 0 0.0) :arrears 10.0 :distress-streak 7)  ; about to fire
                  (assoc (j/make-agent 1 0.0) :arrears 10.0 :distress-streak 2)] ; also over, but early
          params (assoc j/default-params :bankruptcy-enabled? true
                        :bankruptcy-debt-multiple 3 :subsistence-cost 1.0
                        :bankruptcy-streak 8 :bankruptcy-haircut 1.0 :bankruptcy-exclusion 5)
          {:keys [agents bankrupt-ids]} (j/resolve-bankruptcy agents [] params)]
      (is (= [0] bankrupt-ids) "only agent 0 has met the streak requirement")
      (is (zero? (:arrears (first agents))) "agent 0 discharged")
      (is (== 10.0 (:arrears (second agents))) "agent 1 is still over threshold but not yet discharged")
      (is (= 3 (:distress-streak (second agents))) "agent 1's streak keeps counting up, undischarged"))))

(deftest bankruptcy-streak-frozen-while-disabled-test
  (testing "distress-streak does not silently accumulate while bankruptcy is disabled, so enabling it
            mid-run doesn't trigger a surprise mass discharge on the very next tick"
    (let [agents [(assoc (j/make-agent 0 0.0) :arrears 10.0 :distress-streak 7 :exclusion-remaining 4)]
          params (assoc j/default-params :bankruptcy-enabled? false
                        :bankruptcy-debt-multiple 3 :subsistence-cost 1.0 :bankruptcy-streak 8)
          {:keys [agents bankrupt-ids]} (j/resolve-bankruptcy agents [] params)]
      (is (empty? bankrupt-ids))
      (is (zero? (:distress-streak (first agents)))
          "streak resets to 0 while disabled instead of continuing to climb toward the trigger")
      (is (= 3 (:exclusion-remaining (first agents)))
          "an already-incurred lockout still counts down even while the feature is off -- it's a consequence
           already imposed, not an ongoing policy, so it shouldn't freeze and strand the agent forever"))))

(deftest bankruptcy-exclusion-blocks-new-loans-test
  (testing "an agent still inside their exclusion window can't get a new loan; the whole shortfall becomes arrears"
    (let [agents [(assoc (j/make-agent 0 0.0) :exclusion-remaining 5)
                  (j/make-agent 1 100.0)]
          params (assoc j/default-params :lend-reserve-multiple 1 :subsistence-cost 1.0
                        :arrears-fraction 0.3)
          unpaid [10.0 0.0]
          {:keys [agents loans]} (j/resolve-borrowing agents unpaid [] params 0)]
      (is (empty? loans) "no lender should have been matched")
      (is (== 10.0 (:arrears (first agents))) "entire shortfall became arrears despite arrears-fraction < 1"))))

(deftest jubilee-cancels-debt-test
  (testing "a full-haircut jubilee zeroes out loans and arrears"
    (let [params (assoc j/default-params
                        :population 40 :base-income 0.5 :subsistence-cost 1.0
                        :consumption-vol 0.1 :return-mean 0.0 :return-vol 0.01
                        :jubilee-mode :manual :haircut 1.0)
          stressed (reduce (fn [s _] (j/step s)) (j/init-state params) (range 20))
          _ (is (pos? (+ (reduce + (map :balance (:loans stressed)))
                         (reduce + (map :arrears (:agents stressed))))))
          jubileed (j/force-jubilee stressed)]
      (is (zero? (reduce + (map :balance (:loans jubileed)))))
      (is (zero? (reduce + (map :arrears (:agents jubileed)))))
      (is (true? (:jubilee? (last (:history jubileed))))))))
