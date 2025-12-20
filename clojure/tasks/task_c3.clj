(ns task-c3
  (:require [clojure.test :refer [deftest is testing run-tests]]))

(defn pfilter
  "Параллельный фильтр.
   pred - функция-предикат.
   coll - входная коллекция.
   chunk-size - размер блока."
  [pred coll chunk-size]
  (let [
        chunks (partition-all chunk-size coll)
        
        futures (map (fn [block]
                       (future (doall (filter pred block))))
                     chunks)
        
        parallelism 4
        
        triggers (concat (drop parallelism futures) (repeat nil))]
    
    (->> (map (fn [f _trigger] (deref f)) futures triggers)
         (mapcat identity))))

;; Тесты

(deftest test-pfilter-correctness
  (testing "Работа с конечным списком"
    (let [data (range 20)]
      (is (= (filter even? data)
             (pfilter even? data 5)))))

  (testing "Работа с бесконечным списком (ленивость)"
    (let [infinite-nums (iterate inc 0)
          result (take 10 (pfilter even? infinite-nums 100))]
      (is (= '(0 2 4 6 8 10 12 14 16 18) result))))

  (testing "Пустой список"
    (is (= '() (pfilter even? '() 5))))
  
  (testing "Размер блока больше размера списка"
    (is (= '(2 4) (pfilter even? '(1 2 3 4 5) 100)))))

;; Демонстрация эффективности

(defn heavy-pred [x]
  (Thread/sleep 5)
  (even? x))

(defn demo-performance []
  (let [n 400 
        data (doall (range n))] 
    
    (println "--- DEMO START ---")
    (println "Processing" n "items with 5ms delay per item.")
    (println "Theoretical sequential time: ~" (* n 5) "ms")
    
    (println "\n1. Standard filter (Sequential):")
    (time 
      (doall (filter heavy-pred data)))
    
    (println "\n2. Parallel filter (Chunk size 20, Window 4):")
    (time
      (doall (pfilter heavy-pred data 20)))
      
    (println "--- DEMO END ---")))

;; Запуск

(run-tests)
(demo-performance)
