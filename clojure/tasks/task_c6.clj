(ns task-c6)

;; Атом для подсчета количества попыток транзакций (включая рестарты)
(def transact-cnt (atom 0))

;;;an empty route map
(def empty-map
  {:forward {},
   :backward {}})

(defn route
  "Add a new route (route) to the given route map"
  [route-map from to price tickets-num]
  (let [tickets (ref tickets-num :validator (fn [state] (>= state 0))),     
        orig-source-desc (or (get-in route-map [:forward from]) {}),
        orig-reverse-dest-desc (or (get-in route-map [:backward to]) {}),
        route-desc {:price price,                                            
                   :tickets tickets},
        source-desc (assoc orig-source-desc to route-desc),
        reverse-dest-desc (assoc orig-reverse-dest-desc from route-desc)]
    (-> route-map
      (assoc-in [:forward from] source-desc)
      (assoc-in [:backward to] reverse-dest-desc))))

(defn book-tickets
  "Tries to book tickets and decrement appropriate references in route-map atomically"
  [route-map from to]
  (if (= from to)
    {:path '(), :price 0}
    
    (dosync
      (swap! transact-cnt inc)

      (loop [queue (sorted-set [0 from []]) ;; Очередь с приоритетом: [цена, текущий_город, [список_ребер]]
             visited #{}]
        (if (empty? queue)
          {:error "No path or tickets available"}
          (let [[cost current-city path-edges] (first queue)
                rest-queue (disj queue [cost current-city path-edges])]
            
            (if (= current-city to)
              ;; Путь найден
              (do
                (doseq [edge-desc path-edges]
                  (alter (:tickets edge-desc) dec))
                
                {:price cost
                 :path (mapv :to-city path-edges)}) 

              (if (visited current-city)
                (recur rest-queue visited)
                
                ;; Раскрываем соседей
                (let [neighbors (get-in route-map [:forward current-city])
                      next-steps (for [[next-city desc] neighbors
                                       :let [t-ref (:tickets desc)]
                                       :when (and (> @t-ref 0)
                                                  (not (visited next-city)))]
                                   (let [desc-with-name (assoc desc :to-city next-city)]
                                     [(+ cost (:price desc))
                                      next-city
                                      (conj path-edges desc-with-name)]))]
                  (recur (into rest-queue next-steps)
                         (conj visited current-city)))))))))))
  
;;;cities
(def spec1 (-> empty-map
             (route "City1" "Capital"    200 5)
             (route "Capital" "City1"    250 5)
             (route "City2" "Capital"    200 5)
             (route "Capital" "City2"    250 5)
             (route "City3" "Capital"    300 3)
             (route "Capital" "City3"    400 3)
             (route "City1" "Town1_X"    50 2)
             (route "Town1_X" "City1"    150 2)
             (route "Town1_X" "TownX_2"  50 2)
             (route "TownX_2" "Town1_X"  150 2)
             (route "Town1_X" "TownX_2"  50 2)
             (route "TownX_2" "City2"    50 3)
             (route "City2" "TownX_2"    150 3)
             (route "City2" "Town2_3"    50 2)
             (route "Town2_3" "City2"    150 2)
             (route "Town2_3" "City3"    50 3)
             (route "City3" "Town2_3"    150 2)))

(defn booking-future [route-map from to init-delay loop-delay]
  (future 
    (Thread/sleep init-delay) 
    (loop [bookings []]
      (Thread/sleep loop-delay)
      (let [booking (book-tickets route-map from to)]
        (if (booking :error)
          bookings
          (recur (conj bookings booking)))))))

(defn print-bookings [name ft]
  (println (str name ":") (count ft) "bookings")
  (doseq [booking ft]
    (println "  price:" (booking :price) "path:" (booking :path))))

(defn run []
  (reset! transact-cnt 0)
  (println "--- Starting Booking Simulation ---")
  
  ;; Подобранные параметры
  (let [f1 (booking-future spec1 "City1" "City3" 10 20),
        f2 (booking-future spec1 "City1" "City2" 0 50),
        f3 (booking-future spec1 "City2" "City3" 0 50)]
    
    (print-bookings "City1->City3" @f1)
    (print-bookings "City1->City2" @f2)
    (print-bookings "City2->City3" @f3)
    
    (println "Total transaction (re-)starts:" @transact-cnt)))

;; Запуск
(run)

;; Завершаем работу пула потоков
(shutdown-agents)
