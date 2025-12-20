(ns task-c5
  (:import [java.util.concurrent Executors TimeUnit]))

;; Глобальные счетчики
(def transaction-attempts (atom 0))

(defn create-forks [n]
  (vec (repeatedly n #(ref 0))))

(defn philosopher
  "Функция, описывающая поведение одного философа."
  [id forks config sim-latch]
  (let [n (count forks)
        left-fork (nth forks id)
        right-fork (nth forks (mod (inc id) n))
        
        {:keys [meals think-ms eat-ms name]} config]
    
    (future
      (dotimes [i meals]
        (Thread/sleep think-ms)
        
        (dosync
          (swap! transaction-attempts inc)
          
          (alter left-fork inc)
          (alter right-fork inc)
          
          (Thread/sleep eat-ms)))
      
      (.countDown sim-latch))))

(defn run-simulation [label n-philosophers config]
  (reset! transaction-attempts 0)
  
  (let [forks (create-forks n-philosophers)
        latch (java.util.concurrent.CountDownLatch. n-philosophers)
        start-time (System/currentTimeMillis)]
    
    (println (format "\n--- Simulation: %s (N=%d) ---" label n-philosophers))
    (println "Config:" config)
    
    ;; Запускаем философов
    (dotimes [i n-philosophers]
      (philosopher i forks config latch))
    
    ;; Ждем завершения
    (.await latch)
    
    (let [end-time (System/currentTimeMillis)
          total-time (- end-time start-time)
          total-meals (* n-philosophers (:meals config))
          total-attempts @transaction-attempts

          restarts (- total-attempts total-meals)]
      
      (println (format "Finished in: %d ms" total-time))
      (println (format "Total meals served: %d" total-meals))
      (println (format "STM Transaction attempts: %d" total-attempts))
      (println (format "STM Restarts (Collisions): %d" restarts))
      (println (format "Efficiency (Restarts per meal): %.2f" (float (/ restarts total-meals))))
      
      {:time total-time :restarts restarts})))

;; Сценарии

(defn demo []
  ;; Сценарий 1: Низкая конкуренция
  (run-simulation "Low Contention (Happy Path)" 
                  5 
                  {:meals 10 :think-ms 10 :eat-ms 1})

  ;; Сценарий 2: Высокая конкуренция
  (run-simulation "High Contention (Stress Test)" 
                  5 
                  {:meals 10 :think-ms 0 :eat-ms 10})

  ;; Сценарий 3: Четное количество
  (run-simulation "Even Philosophers" 
                  4 
                  {:meals 10 :think-ms 2 :eat-ms 2})

  ;; Сценарий 4: Нечетное количество
  (run-simulation "Odd Philosophers" 
                  5 
                  {:meals 10 :think-ms 2 :eat-ms 2}))

;; Запуск
(demo)

;; Завершаем работу агентов
(shutdown-agents)
