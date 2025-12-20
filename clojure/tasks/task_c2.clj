(ns task-c2
  (:require [clojure.test :refer [deftest is testing run-tests]]))

(defn sieve [stream]
  (cons (first stream)
        (lazy-seq 
          (sieve
            (remove #(zero? (mod % (first stream))) 
                    (rest stream))))))

(def primes (sieve (iterate inc 2)))


;; Тесты

(deftest test-primes-sequence
  (testing "Первые 5 простых чисел"
    (is (= (take 5 primes) '(2 3 5 7 11))))

  (testing "Проверка конкретных позиций (10-е и 20-е простое число)"
    (is (= (nth primes 9) 29))
    (is (= (nth primes 19) 71)))
  
  (testing "Проверка, что составные числа отсутствуют"
    (let [first-100 (take 100 primes)]
      (is (not (some #(= % 4) first-100)))
      (is (not (some #(= % 9) first-100)))
      (is (not (some #(= % 15) first-100))))))

;; Запуск тестов
(run-tests)
