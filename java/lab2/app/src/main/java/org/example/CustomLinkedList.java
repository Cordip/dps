package org.example;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

public class CustomLinkedList implements Iterable<String> {

    public static class Node {
        String value;
        volatile Node next;
        volatile Node prev;
        
        // Блокировка на уровне отдельного узла
        final ReentrantLock lock = new ReentrantLock();

        Node(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private final Node head;
    private final Node tail;

    public CustomLinkedList() {
        // Инициализация фиктивных узлов для упрощения граничных условий
        head = new Node("HEAD");
        tail = new Node("TAIL");
        head.next = tail;
        tail.prev = head;
    }

    public void addFirst(String value) {
        // Захватываем Head и следующий за ним узел для безопасной вставки
        head.lock.lock();
        try {
            Node nextNode = head.next;
            nextNode.lock.lock();
            try {
                Node newNode = new Node(value);
                
                newNode.next = nextNode;
                newNode.prev = head;
                
                head.next = newNode;
                nextNode.prev = newNode;
            } finally {
                nextNode.lock.unlock();
            }
        } finally {
            head.lock.unlock();
        }
    }

    public Node getHead() { return head; }
    public Node getTail() { return tail; }

    @Override
    public Iterator<String> iterator() {
        return new SafeIterator();
    }

    /**
     * Thread-safe итератор, использующий Hand-over-Hand locking.
     */
    private class SafeIterator implements Iterator<String> {
        private Node current;

        public SafeIterator() {
            head.lock.lock();
            try {
                current = head;
            } finally {
                head.lock.unlock();
            }
        }

        @Override
        public boolean hasNext() {
            return current.next != null && current.next != tail;
        }

        @Override
        public String next() {
            // Сохраняем локальную копию для unlock, так как поле current изменится внутри метода
            Node nodeToUnlock = current;

            nodeToUnlock.lock.lock();
            try {
                Node nextNode = nodeToUnlock.next;

                if (nextNode == null || nextNode == tail) {
                    throw new NoSuchElementException();
                }

                // Шаг вперед. Захватываем следующий узел, держа блокировку на текущем
                nextNode.lock.lock();
                try {
                    String val = nextNode.value;
                    current = nextNode; 
                    return val;
                } finally {
                    nextNode.lock.unlock();
                }
            } finally {
                nodeToUnlock.lock.unlock();
            }
        }
    }
}
