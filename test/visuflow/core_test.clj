(ns visuflow.core-test
  (:use clojure.test
        visuflow.core))

(def vgs
  {:>5 [[#(> (first %) 5) 2]
        [#(<= (first %) 5) 1]]})

(def state [2 3])

(defn inc-state
  [[a b]]
  [(inc a) (inc b)])

(defn dec-state
  [[a b]]
  [(dec a) (dec b)])

(defn fold-repeat-inc
  [x]
  (if (> (count x) 1) ;;(sequential? x)
    [:!d#0 (+ (first x) (second x))]
    (inc x)))

(defn combine
  [x y]
  [x (second y)])

(def flow
  '(inc-state inc-state inc-state [:!f identity :>5] (dec-state fold-repeat-inc combine)))

(defn exe [] (println (walk {:tree flow :validargs vgs :stack [1 1]})))

(def scoll '(a b c))
(def sstack '(1 2 3))

(deftest funtest
  (testing "contains-cb? fun with a keyword"
    (is (= true (contains-cb? :!b))))
  (testing "contains-cb? fun with a seq where first elem is a keyword"
    (is (= true (contains-cb? [:!b 3]))))
  (testing "parse-cb-stmt with a cb"
    (is (= '[(b c) (1 2 3)]
           (parse-cb-stmt {:coll scoll :cb :!d#1 :stack sstack}))))
  (testing "parse-cb-res with a single cb"
    (is (= '[(b c) (1 2 3)]
           (parse-cb-res {:coll scoll :cbs :!d#1 :stack sstack}))))
  (testing "parse-cb-res with chained cbs"
    (is (= '[(b c) (1 2)]
           (parse-cb-res {:coll scoll :cbs [:!d#1 :!_#2] :stack sstack}))))
  (testing "eval-with-stack with a one-arg function"
    (is (= '([2 3] [1 2])
           (eval-with-stack {:car inc-state :stack '([1 2])}))))
  (testing "eval-with-stack with a two-arg function"
    (is (= '([1 3] 1 [2 3])
           (eval-with-stack {:car combine :stack '(1 [2 3])}))))
  (testing "parse-validarg-stmt number"
    (is (= '(3)
           (parse-validarg-stmt '(1 2 3) 2))))
  (testing "parse-validarg-stmt keyword with map"
    (is (= 5
           (parse-validarg-stmt '(1 {:a 5} 3) :a))))
  (testing "parse-validarg-stmt keyword with list"
    (is (= '(4 :b 5)
           (parse-validarg-stmt '(1 2 3 :a 4 :b 5) :a))))
  )

(deftest basic-test
  (testing "Testing a basic flow"
    (is (= [6 3] (exe)))))