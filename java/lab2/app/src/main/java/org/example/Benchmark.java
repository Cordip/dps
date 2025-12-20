package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.example.SorterRunnable;

public class Benchmark {

    private static final int THREAD_COUNT = 4;
    private static final int DURATION_SEC = 5;
    private static final int LIST_SIZE = 5000;

    public static void main(String[] args) throws InterruptedException {
        SorterRunnable.BENCHMARK_MODE = true;

        printTheoreticalReport();

        System.out.println("\n>>> ЗАПУСК ТЕСТОВ (" + DURATION_SEC + " сек)...");

        // Тест стандартного списка
        long stdSteps = runStandardTest();
        System.out.printf("Standard List: %,d шагов\n", stdSteps);

        // Тест собственного списка
        long customSteps = runCustomTest();
        System.out.printf("Custom List:   %,d шагов\n", customSteps);

        printFinalAnalysis(stdSteps, customSteps);
        
        System.exit(0);
    }

    private static void printTheoreticalReport() {
        System.out.println("=============================================================");
        System.out.println("               ТЕОРЕТИЧЕСКИЙ РАСЧЕТ                          ");
        System.out.println("=============================================================");
        System.out.printf("Вводные: N (потоки) = %d, T (время) = %ds\n", THREAD_COUNT, DURATION_SEC);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Легенда:");
        System.out.println("  S  : Суммарное кол-во шагов");
        System.out.println("  dt : Среднее время одного шага");
        System.out.println("  ~  : Знак пропорциональности (игнорируем overhead)");
        System.out.println("-------------------------------------------------------------");
        
        System.out.println("1. Standard List (Coarse-Grained)");
        System.out.println("   Механизм: Глобальная блокировка (Serial Execution).");
        System.out.println("   Формула:  S ~ T / dt");
        System.out.println("   Пояснение: Работает как 1 поток, N не влияет на скорость.");

        System.out.println("\n2. Custom List (Fine-Grained)");
        System.out.println("   Механизм: Блокировка узлов (Parallel Execution).");
        System.out.println("   Формула:  S ~ N * (T / dt)");
        System.out.println("   Пояснение: Работают N потоков одновременно.");

        System.out.println("-------------------------------------------------------------");
        System.out.printf("Ожидаемое ускорение (Speedup): ~%d.0x\n", THREAD_COUNT);
        System.out.println("=============================================================");
    }

    private static void printFinalAnalysis(long std, long custom) {
        System.out.println("\n=============================================================");
        System.out.println("                  ИТОГОВЫЙ РЕЗУЛЬТАТ                         ");
        System.out.println("=============================================================");
        
        double ratio = (double) custom / std;
        System.out.printf("Speedup: %.2fx (Теоретический максимум: %d.0x)\n", ratio, THREAD_COUNT);
        System.out.println("-------------------------------------------------------------");

        if (ratio > THREAD_COUNT) {
            System.out.println("ВЫВОД: Аномально высокий результат (> N).");
            System.out.println("Возможно, Standard List страдал от сильного 'голодания' потоков.");
        
        } else if (ratio >= THREAD_COUNT * 0.9) { 
            System.out.println("ВЫВОД: Отлично! Почти достигли теоретического предела.");
            System.out.println("Эффективность параллелизма близка к 100%.");
            
        } else if (ratio > 1.5) {
            System.out.println("ВЫВОД: Хороший прирост. Fine-grained locking работает.");
            System.out.println("Custom List значительно быстрее, но есть накладные расходы на блокировки.");
            
        } else if (ratio > 1.0) {
            System.out.println("ВЫВОД: Прирост есть, но незначительный.");
            System.out.println("Вероятно, список слишком мал, и потоки мешают друг другу.");
            
        } else {
            System.out.println("ВЫВОД: Прироста нет (или регрессия).");
            System.out.println("Накладные расходы на Hand-over-Hand превышают выгоду.");
        }
        System.out.println("=============================================================");
    }

    // --- ЗАПУСК ТЕСТОВ ---

    private static long runStandardTest() throws InterruptedException {
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        fillData(list, null);
        
        AtomicLong counter = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(new SorterRunnable(list, counter));
        }

        Thread.sleep(DURATION_SEC * 1000L);
        
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
        
        return counter.get();
    }

    private static long runCustomTest() throws InterruptedException {
        CustomLinkedList list = new CustomLinkedList();
        fillData(null, list);

        AtomicLong counter = new AtomicLong(0);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(new SorterRunnable(list, counter));
        }

        Thread.sleep(DURATION_SEC * 1000L);

        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        return counter.get();
    }

    private static void fillData(List<String> std, CustomLinkedList custom) {
        Random r = new Random();
        for (int i = 0; i < LIST_SIZE; i++) {
            String val = String.valueOf(r.nextInt(10000));
            if (std != null) std.add(val);
            if (custom != null) custom.addFirst(val);
        }
    }
}
