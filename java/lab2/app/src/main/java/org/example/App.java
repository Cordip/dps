package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class App {

    private static final int THREAD_COUNT = 4;
    
    // Переключатель режима: false = Задание 1 (Fine-Grained), true = Задание 2 (Coarse-Grained)
    private static final boolean USE_STANDARD_LIST = false; 

    public static void main(String[] args) {
        CustomLinkedList customList = new CustomLinkedList();
        java.util.List<String> standardList = Collections.synchronizedList(new ArrayList<>());
        AtomicLong stepCounter = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        System.out.println("=== Concurrent Sorter Demo ===");
        System.out.println("Mode: " + (USE_STANDARD_LIST ? "Standard Lib" : "Custom List (Fine-Grained)"));
        System.out.println("Enter text lines. Empty line to print current state.");

        for (int i = 0; i < THREAD_COUNT; i++) {
            if (USE_STANDARD_LIST) {
                executor.submit(new SorterRunnable(standardList, stepCounter));
            } else {
                executor.submit(new SorterRunnable(customList, stepCounter));
            }
        }

        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                if (line.isEmpty()) {
                    printListState(customList, standardList, stepCounter.get());
                } else {
                    // Строки длиннее 80 символов разрезаются
                    if (line.length() > 80) {
                        int parts = (int) Math.ceil(line.length() / 80.0);
                        for (int i = 0; i < parts; i++) {
                            int start = i * 80;
                            int end = Math.min(start + 80, line.length());
                            addToList(customList, standardList, line.substring(start, end));
                        }
                    } else {
                        addToList(customList, standardList, line);
                    }
                }
            }
        }
        
        executor.shutdownNow();
    }

    private static void addToList(CustomLinkedList custom, java.util.List<String> standard, String val) {
        if (USE_STANDARD_LIST) {
            standard.add(0, val);
        } else {
            custom.addFirst(val);
        }
    }

    private static void printListState(CustomLinkedList custom, java.util.List<String> standard, long steps) {
        synchronized (System.out) {
            System.out.println();
            System.out.println("============================================");
            System.out.println("--- LIST STATE (Checks: " + steps + ") ---");
            System.out.println("============================================");
            
            System.out.flush(); 
            
            int count = 0;
            
            if (USE_STANDARD_LIST) {
                synchronized (standard) {
                    for (String s : standard) {
                        System.out.println(s);
                        count++;
                    }
                }
            } else {
                for (String s : custom) {
                    System.out.println(s);
                    count++;
                }
            }
            System.out.println("--- Total items: " + count + " ---");
            System.out.println();
            System.out.flush();
        }
    }
}
