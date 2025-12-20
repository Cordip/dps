package org.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NioServerTest {

    private NioServer server;
    private Thread serverThread;
    private CryptoService cryptoService;

    @BeforeEach
    void setUp() throws Exception {
        // 1. Подготовка криптографии (Issuer)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048); // Для тестов хватит 2048, чтобы быстро стартовать
        KeyPair issuerKp = kpg.generateKeyPair();
        cryptoService = new CryptoService(issuerKp, "CN=TestCA");

        // 2. Создаем сервер на порту 0 (система выберет свободный порт)
        // Для теста ставим keySize=1024, чтобы генерация шла доли секунды,
        // иначе тест зависнет на минуты.
        server = new NioServer(0, 2, 1024, cryptoService);

        // 3. Запускаем сервер в отдельном потоке
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                // Если остановлен штатно - игнорируем
                if (!e.getMessage().contains("Interrupted")) {
                    e.printStackTrace();
                }
            }
        });
        serverThread.start();

        // Даем время на запуск
        Thread.sleep(500);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.join(2000);
        }
    }

    @Test
    void testSingleClientRequest() throws Exception {
        int port = server.getPort();
        try (Socket client = new Socket("localhost", port)) {
            // Отправляем запрос: "Alice" + \0
            OutputStream out = client.getOutputStream();
            out.write("Alice".getBytes(StandardCharsets.US_ASCII));
            out.write(0); // Null terminator
            out.flush();

            // Читаем ответ
            DataInputStream in = new DataInputStream(client.getInputStream());
            
            // 1. Длина ключа
            int keyLen = in.readInt();
            assertTrue(keyLen > 0, "Key length should be positive");
            
            // 2. Байты ключа
            byte[] keyBytes = new byte[keyLen];
            in.readFully(keyBytes);
            
            // 3. Длина сертификата
            int certLen = in.readInt();
            assertTrue(certLen > 0, "Cert length should be positive");
            
            // 4. Байты сертификата
            byte[] certBytes = new byte[certLen];
            in.readFully(certBytes);

            System.out.println("Received Key: " + keyLen + " bytes, Cert: " + certLen + " bytes");

            // Проверка валидности ключа (опционально)
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            assertNotNull(pk);
        }
    }

    @Test
    void testCachingMechanism() throws Exception {
        int port = server.getPort();
        String clientName = "Bob";
        byte[] key1;
        byte[] key2;

        // Первый запрос
        try (Socket client1 = new Socket("localhost", port)) {
            OutputStream out = client1.getOutputStream();
            out.write(clientName.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.flush();

            DataInputStream in = new DataInputStream(client1.getInputStream());
            int len = in.readInt();
            key1 = new byte[len];
            in.readFully(key1);
            // Дочитываем остаток, чтобы корректно закрыть (не обязательно для теста, но культурно)
        }

        // Второй запрос с ТЕМ ЖЕ именем
        try (Socket client2 = new Socket("localhost", port)) {
            OutputStream out = client2.getOutputStream();
            out.write(clientName.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.flush();

            DataInputStream in = new DataInputStream(client2.getInputStream());
            int len = in.readInt();
            key2 = new byte[len];
            in.readFully(key2);
        }

        // Проверяем, что сервер вернул ОДИН И ТОТ ЖЕ приватный ключ (байты совпадают)
        assertArrayEquals(key1, key2, "Server should return cached keys for the same user name");
    }
    
    @Test
    void testConcurrentClients() throws Exception {
        int port = server.getPort();
        int clientCount = 10;
        
        // Запускаем 10 клиентов параллельно
        Thread[] threads = new Thread[clientCount];
        for (int i = 0; i < clientCount; i++) {
            int id = i;
            threads[i] = new Thread(() -> {
                try (Socket socket = new Socket("localhost", port)) {
                    OutputStream out = socket.getOutputStream();
                    String name = "User" + id;
                    out.write(name.getBytes(StandardCharsets.US_ASCII));
                    out.write(0);
                    out.flush();
                    
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int kLen = in.readInt();
                    assertTrue(kLen > 0);
                    // Пропускаем байты (skipBytes может не сработать надежно на сокетах, лучше читать)
                    in.readNBytes(kLen);
                    
                    int cLen = in.readInt();
                    assertTrue(cLen > 0);
                } catch (Exception e) {
                    fail("Client " + id + " failed: " + e.getMessage());
                }
            });
            threads[i].start();
        }
        
        // Ждем завершения
        for (Thread t : threads) {
            t.join();
        }
    }
}
