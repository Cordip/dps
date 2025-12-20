package org.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedNioServerTest {

    private NioServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
        // Инициализация, как в прошлом тесте, но ключи 512 бит для максимальной скорости тестов
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair issuerKp = kpg.generateKeyPair();
        CryptoService cryptoService = new CryptoService(issuerKp, "CN=TestCA");

        // Пул из 4 потоков, ключи 512 бит (быстро)
        server = new NioServer(0, 4, 512, cryptoService);
        
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                // ignore
            }
        });
        serverThread.start();
        Thread.sleep(200); // Даем серверу подняться
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        serverThread.join(1000);
    }

    /**
     * Проверка требования: "Сервер должен поддерживать не менее сотни одновременно активных клиентов"
     */
    @Test
    @Timeout(30) // Тест не должен идти дольше 30 секунд
    void test100ConcurrentClients() throws InterruptedException, ExecutionException {
        int clientCount = 100;
        int port = server.getPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            final int id = i;
            tasks.add(() -> {
                try (Socket socket = new Socket("localhost", port)) {
                    // Отправляем запрос
                    OutputStream out = socket.getOutputStream();
                    out.write(("User" + id).getBytes(StandardCharsets.US_ASCII));
                    out.write(0);
                    out.flush();

                    // Читаем ответ
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int keyLen = in.readInt();
                    in.readNBytes(keyLen); // Прочитываем тело
                    int certLen = in.readInt();
                    in.readNBytes(certLen);
                    
                    return true;
                } catch (Exception e) {
                    System.err.println("Client " + id + " failed: " + e);
                    return false;
                }
            });
        }

        // Запускаем все разом
        List<Future<Boolean>> futures = clientPool.invokeAll(tasks);
        
        for (Future<Boolean> f : futures) {
            assertTrue(f.get(), "Один из клиентов упал с ошибкой");
        }
        
        clientPool.shutdown();
    }

    /**
     * Проверка неблокируемости:
     * Медленный клиент подключается и отправляет запрос, но НЕ ЧИТАЕТ ответ.
     * Быстрый клиент подключается ПОЗЖЕ, но должен получить ответ РАНЬШЕ, чем тайм-аут.
     * Если бы сервер блокировался на записи (write) медленному клиенту, быстрый бы завис.
     */
    @Test
    void testSlowClientDoesNotBlockFastClient() throws Exception {
        int port = server.getPort();

        // 1. Запускаем "Медленного" клиента
        Socket slowSocket = new Socket("localhost", port);
        OutputStream slowOut = slowSocket.getOutputStream();
        slowOut.write("SlowUser".getBytes(StandardCharsets.US_ASCII));
        slowOut.write(0);
        slowOut.flush();
        // Мы НЕ читаем из slowSocket, буфер отправки сервера заполнится, 
        // и сервер не должен на этом зависнуть.

        // 2. Запускаем "Быстрого" клиента
        try (Socket fastSocket = new Socket("localhost", port)) {
            OutputStream fastOut = fastSocket.getOutputStream();
            fastOut.write("FastUser".getBytes(StandardCharsets.US_ASCII));
            fastOut.write(0);
            fastOut.flush();

            fastSocket.setSoTimeout(5000); // Тайм-аут 5 сек
            DataInputStream in = new DataInputStream(fastSocket.getInputStream());
            
            // Если сервер заблокирован медленным клиентом, здесь вылетит SocketTimeoutException
            int len = in.readInt(); 
            assertTrue(len > 0);
            System.out.println("Fast client received data successfully!");
        }

        // Чистим за собой
        slowSocket.close();
    }

    /**
     * Тест на фрагментацию TCP пакетов.
     * Отправляем имя по одному байту с паузами.
     * Сервер должен корректно собрать имя.
     */
    @Test
    void testFragmentedRequest() throws Exception {
        int port = server.getPort();
        String name = "FragmentedUser";

        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            
            // Пишем по 1 байту
            for (byte b : name.getBytes(StandardCharsets.US_ASCII)) {
                out.write(b);
                out.flush();
                Thread.sleep(10); // Имитация лагов сети
            }
            out.write(0); // Конец
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int keyLen = in.readInt();
            assertTrue(keyLen > 0, "Server failed to assemble fragmented name");
        }
    }
}
