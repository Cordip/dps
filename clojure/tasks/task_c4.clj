(ns task-c4)

(declare supply-msg)
(declare notify-msg)

(defn storage
  [ware notify-step & consumers]
  (let [counter (atom 0 :validator #(>= % 0)),
        worker-state {:storage counter,
                      :ware ware,
                      :notify-step notify-step,
                      :consumers consumers}]
    {:storage counter,
     :ware ware,
     :worker (agent worker-state)}))

(defn factory
  [amount duration target-storage & ware-amounts]
  (let [bill (apply hash-map ware-amounts),
        buffer (reduce-kv (fn [acc k _] (assoc acc k 0)) 
                          {} bill),
        worker-state {:amount amount,
                      :duration duration,
                      :target-storage target-storage,
                      :bill bill,
                      :buffer buffer}]
    {:worker (agent worker-state)}))

(defn source
  [amount duration target-storage]
  (new Thread 
       (fn []
         (Thread/sleep duration)
         (send (target-storage :worker) supply-msg amount)
         (recur))))

(defn supply-msg
  [state amount]
  (swap! (state :storage) #(+ % amount))
  (let [ware (state :ware),
        cnt @(state :storage),                
        notify-step (state :notify-step),
        consumers (state :consumers)]
    (when (and (> notify-step 0)
               (> (int (/ cnt notify-step))
                  (int (/ (- cnt amount) notify-step))))
      (locking System/out
        (println (.format (new java.text.SimpleDateFormat "hh.mm.ss.SSS") (new java.util.Date)) 
                 "|" ware "amount: " cnt)))
    
    (when consumers
      (doseq [consumer (shuffle consumers)]
        (send (consumer :worker) notify-msg ware (state :storage) amount))))
  state)

(defn notify-msg
  [state ware storage-atom amount]
  (let [bill (:bill state)
        current-buffer (:buffer state)
        req-amount (get bill ware)]
    
    (if (and req-amount (< (get current-buffer ware) req-amount))
      (let [needed (- req-amount (get current-buffer ware))
            available @storage-atom
            to-take (min needed available)]
        
        (if (> to-take 0)
          (try
            (swap! storage-atom - to-take)
            (let [new-buffer (assoc current-buffer ware (+ (get current-buffer ware) to-take))
                  ready-to-produce? (every? (fn [[k v]] (>= v (get bill k))) new-buffer)]
              (if ready-to-produce?
                (do
                  (Thread/sleep (:duration state))
                  (send ((:target-storage state) :worker) supply-msg (:amount state))
                  (let [empty-buffer (reduce-kv (fn [acc k _] (assoc acc k 0)) {} bill)]
                    (assoc state :buffer empty-buffer)))
                (assoc state :buffer new-buffer)))
            (catch Exception e state))
          state))
      state)))

;;; Конфигурация
(def safe-storage (storage "Safe" 1))
(def safe-factory (factory 1 3000 safe-storage "Metal" 3))
(def cuckoo-clock-storage (storage "Cuckoo-clock" 1))
(def cuckoo-clock-factory (factory 1 2000 cuckoo-clock-storage "Lumber" 5 "Gears" 10))
(def gears-storage (storage "Gears" 20 cuckoo-clock-factory))
(def gears-factory (factory 4 1000 gears-storage "Ore" 4))
(def metal-storage (storage "Metal" 5 safe-factory))
(def metal-factory (factory 1 1000 metal-storage "Ore" 10))
(def lumber-storage (storage "Lumber" 20 cuckoo-clock-factory))
(def lumber-mill (source 5 4000 lumber-storage))
(def ore-storage (storage "Ore" 10 metal-factory gears-factory))
(def ore-mine (source 2 1000 ore-storage))

;;; Функции для запуска
(defn start []
  (.start ore-mine)
  (.start lumber-mill)
  (println "Production started..."))

(defn stop []
  (println "Production stopped."))

;;; Запуск

(println "--- Запуск симуляции ---")
(println "Ожидайте логов...")

(start)

(Thread/sleep 120000) 

(println "--- Остановка симуляции ---")
(stop)
(shutdown-agents)
(System/exit 0)
