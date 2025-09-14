(ns social-abm.core-test
  (:require [clojure.test :refer :all]
            [social-abm.core :refer :all]
            [social-abm.world :as w]
            [social-abm.agent :as a]))

(deftest basic-world-test
  (testing "Grid world creation and basic operations"
    (let [world (w/create-grid-world 10 10)]
      (is (= 10 (:width world)))
      (is (= 10 (:height world)))
      (is (= 0 (:tick world)))
      (is (empty? (.get-agents world)))

      ;; Test cell operations
      (let [world-with-value (.set-cell world 5 5 42)]
        (is (= 42 (.get-cell world-with-value 5 5)))
        (is (nil? (.get-cell world-with-value 0 0)))

        ;; Test bounds checking
        (is (nil? (.get-cell world-with-value -1 0)))
        (is (nil? (.get-cell world-with-value 0 -1)))
        (is (nil? (.get-cell world-with-value 10 0)))
        (is (nil? (.get-cell world-with-value 0 10)))))))

(deftest agent-test
  (testing "Basic agent creation and behavior"
    (let [agent (a/create-basic-agent 1 5 5)
          world (w/create-grid-world 10 10)]
      (is (= 1 (:id agent)))
      (is (= [5 5] (.get-position agent)))
      (is (= 50 (:energy agent)))

      ;; Test position setting
      (let [moved-agent (.set-position agent [7 8])]
        (is (= [7 8] (.get-position moved-agent)))))))

(deftest simulation-test
  (testing "World with agents simulation"
    (let [world (w/create-grid-world 5 5)
          agent (a/create-basic-agent 1 2 2)
          world-with-agent (.add-agent world agent)]

      (is (= 1 (count (.get-agents world-with-agent))))

      ;; Test world stepping
      (let [stepped-world (.step-world world-with-agent)]
        (is (= 1 (:tick stepped-world)))
        (is (= 1 (count (.get-agents stepped-world))))))))

(deftest distance-test
  (testing "Distance calculations"
    (is (= 6 (w/manhattan-distance 0 0 3 3)))
    (is (= 5.0 (w/euclidean-distance 0 0 3 4)))
    (is (= 0 (w/manhattan-distance 5 5 5 5)))
    (is (= 0.0 (w/euclidean-distance 5 5 5 5)))))