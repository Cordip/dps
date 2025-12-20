(defn generate-strings [alphabet n]
  ;; Если N <= 0, возвращаем пустой список
  (if (<= n 0)
    '()
    (reduce
      (fn [current-strings _]
        (reduce
          (fn [new-acc s]
            (let [last-char (str (last s))
                  valid-chars (remove (fn [c] (= c last-char)) alphabet)
                  extensions (map (fn [c] (str s c)) valid-chars)]
              (concat new-acc extensions)))
          '() ;; Начальное значение для внутреннего аккумулятора
          current-strings))
      alphabet ;; Начальное значение для внешнего цикла (строки длины 1)
      (range 1 n)))) ;; Диапазон для выполнения цикла N-1 раз

;; Пример использования:
(def alphabet '("a" "b" "c"))
(def n 2)

(println (generate-strings alphabet n))
;; (ab ac ba bc ca cb)

(println (generate-strings alphabet 3))
;; (aba abc aca acb bab bac bca bcb cab cac cba cbc)
