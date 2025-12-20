package org.example;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class SorterRunnable implements Runnable {
    // Глобальный флаг для отключения задержек в режиме бенчмарка
    public static boolean BENCHMARK_MODE = false;

    private final CustomLinkedList list;
    private final boolean useLibraryList;
    private final java.util.List<String> libraryList;
    private final AtomicLong stepCounter;

    private static final int DELAY_BETWEEN_PAIRS = 100; 
    private static final int DELAY_INSIDE_SWAP = 10;

    public SorterRunnable(CustomLinkedList list, AtomicLong stepCounter) {
        this.list = list;
        this.useLibraryList = false;
        this.libraryList = null;
        this.stepCounter = stepCounter;
    }

    public SorterRunnable(java.util.List<String> libraryList, AtomicLong stepCounter) {
        this.list = null;
        this.useLibraryList = true;
        this.libraryList = libraryList;
        this.stepCounter = stepCounter;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (useLibraryList) {
                    bubbleSortStepLibrary();
                } else {
                    bubbleSortStepCustom();
                }
                
                // Пауза между проходами
                smartSleep(100 + ThreadLocalRandom.current().nextInt(1000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Обертка для задержки. Если включен BENCHMARK_MODE, задержка игнорируется.
     */
    private void smartSleep(int millis) throws InterruptedException {
        if (!BENCHMARK_MODE) {
            Thread.sleep(millis);
        }
    }

    /**
     * fine-grained locking: Hand-over-Hand стратегия.
     * Порядок захвата: Pred -> NodeA -> NodeB -> Succ.
     */
    private void bubbleSortStepCustom() throws InterruptedException {
        CustomLinkedList.Node pred = list.getHead();
        
        pred.lock.lock();
        
        CustomLinkedList.Node nodeA = pred.next;
        if (nodeA == null || nodeA == list.getTail()) {
            pred.lock.unlock();
            return;
        }

        nodeA.lock.lock();

        try {
            while (true) {
                // ИНВАРИАНТ: Держим lock(pred) и lock(nodeA)
                CustomLinkedList.Node nodeB = nodeA.next;

                if (nodeB == null || nodeB == list.getTail()) {
                    break; 
                }

                nodeB.lock.lock();
                try {
                    stepCounter.incrementAndGet();

                    boolean swapped = false;
                    if (shouldSwap(nodeA.value, nodeB.value)) {
                        
                        // Для двусвязного списка нужен 4-й лок (Succ)
                        CustomLinkedList.Node succ = nodeB.next;
                        succ.lock.lock();
                        try {
                            smartSleep(DELAY_INSIDE_SWAP);

                            // Перестановка ссылок: Pred <-> B <-> A <-> Succ
                            pred.next = nodeB; nodeB.prev = pred;
                            nodeB.next = nodeA; nodeA.prev = nodeB;
                            nodeA.next = succ; succ.prev = nodeA;
                            
                            swapped = true;
                        } finally {
                            succ.lock.unlock();
                        }
                    }

                    // pred нам больше не нужен
                    pred.lock.unlock();

                    CustomLinkedList.Node nextPred = swapped ? nodeB : nodeA;
                    nodeA.lock.unlock();
                    nodeB.lock.unlock();
                    
                    int jitter = ThreadLocalRandom.current().nextInt(20);
                    smartSleep(DELAY_BETWEEN_PAIRS + jitter);

                    pred = nextPred;
                    pred.lock.lock();
                    
                    // Ищем актуальный nodeA (структура могла поменяться, пока мы спали)
                    nodeA = pred.next;
                    
                    if (nodeA == null || nodeA == list.getTail()) {
                        pred.lock.unlock();
                        break; // Конец списка
                    }
                    
                    nodeA.lock.lock();
                    // Инвариант восстановлен, идем на следующий круг

                } catch (Exception e) {
                    if (nodeB.lock.isHeldByCurrentThread()) {
                        nodeB.lock.unlock();
                    }
                    throw e;
                }
            } 
        } finally {
            if (nodeA != null && nodeA.lock.isHeldByCurrentThread()) {
                nodeA.lock.unlock();
            }
            if (pred != null && pred.lock.isHeldByCurrentThread()) {
                pred.lock.unlock();
            }
        }
    }

    /**
     * coarse-grained locking: Стандартный список с Collections.synchronizedList.
     */
    private void bubbleSortStepLibrary() throws InterruptedException {
        int size = libraryList.size();
        if (size < 2) return;

        for (int i = 0; i < size - 1; i++) {
            synchronized (libraryList) {
                if (i < libraryList.size() - 1) {
                    stepCounter.incrementAndGet();
                    String s1 = libraryList.get(i);
                    String s2 = libraryList.get(i+1);
                    
                    if (shouldSwap(s1, s2)) {
                        smartSleep(DELAY_INSIDE_SWAP);
                        libraryList.set(i, s2);
                        libraryList.set(i+1, s1);
                    }
                }
            }
            smartSleep(DELAY_BETWEEN_PAIRS);
        }
    }

    private boolean shouldSwap(String s1, String s2) {
        if (s1 == null || s2 == null) return false;
        return s1.compareTo(s2) > 0;
    }
}
