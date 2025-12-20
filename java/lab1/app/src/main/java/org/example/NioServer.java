package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;

public class NioServer {
    private final int port;
    private final ExecutorService workerPool;
    private final CryptoService cryptoService;
    private final int keySize;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean isRunning = true;

    // Кэш: Имя Клиента -> Future с результатом.
    private final ConcurrentHashMap<String, CompletableFuture<KeyGenerationResult>> cache = new ConcurrentHashMap<>();

    // Очередь ответов: Worker -> IO Thread
    private final Queue<ClientSession> responseQueue = new ConcurrentLinkedQueue<>();

    public NioServer(int port, int workerThreads, int keySize, CryptoService cryptoService) {
        this.port = port;
        this.keySize = keySize;
        this.cryptoService = cryptoService;
        this.workerPool = Executors.newFixedThreadPool(workerThreads);
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Server started on port " + port + " with " + ((ThreadPoolExecutor)workerPool).getCorePoolSize() + " worker threads.");

        while (isRunning) {
            processResponseQueue();

            if (selector.select() == 0) {
                continue;
            }

            // Обработка событий сети
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (!key.isValid()) continue;

                try {
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                } catch (IOException e) {
                    closeConnection(key);
                }
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        if (client == null) return;

        client.configureBlocking(false);
        ClientSession session = new ClientSession(client);
        
        client.register(selector, SelectionKey.OP_READ, session);
        System.out.println("New connection: " + client.getRemoteAddress());
    }

    private void read(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        ByteBuffer buffer = ByteBuffer.allocate(256);
        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            closeConnection(key);
            return;
        }

        buffer.flip();
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            boolean finished = session.appendNameByte(b);
            
            if (finished) {
                String clientName = session.getParsedName();
                System.out.println("Received request for name: " + clientName);

                key.interestOps(0); 
                session.setProcessing(true);

                handleGenerationRequest(clientName, session);
                
                // Если в буфере остались данные они потеряются в этой реализации.
                break;
            }
        }
    }

    private void handleGenerationRequest(String clientName, ClientSession session) {
        CompletableFuture<KeyGenerationResult> future = cache.computeIfAbsent(clientName, name -> {
            CompletableFuture<KeyGenerationResult> newFuture = new CompletableFuture<>();
            workerPool.submit(() -> {
                try {
                    System.out.println("Generating keys for " + name + " (Thread: " + Thread.currentThread().getName() + ")...");
                    long start = System.currentTimeMillis();
                    
                    var kp = cryptoService.generateRsaKeyPair(keySize);
                    var cert = cryptoService.issueCertificate(name, kp.getPublic());
                    
                    System.out.println("Generation done for " + name + " in " + (System.currentTimeMillis() - start) + "ms");
                    newFuture.complete(new KeyGenerationResult(name, kp, cert));
                } catch (Exception e) {
                    newFuture.completeExceptionally(e);
                }
            });
            return newFuture;
        });

        future.thenAccept(result -> {
            prepareResponse(session, result);
            
            responseQueue.add(session);
            selector.wakeup();
        }).exceptionally(ex -> {
            ex.printStackTrace();
            closeConnection(session.getChannel().keyFor(selector));
            return null;
        });
    }

    private void prepareResponse(ClientSession session, KeyGenerationResult result) {
        byte[] keyBytes = result.keyPair().getPrivate().getEncoded(); // PKCS#8
        byte[] certBytes = result.certificateDer(); // X.509 DER

        // Формат ответа: [4 byte keyLen][key bytes][4 byte certLen][cert bytes]
        int totalSize = 4 + keyBytes.length + 4 + certBytes.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(certBytes.length);
        buffer.put(certBytes);
        
        buffer.flip();
        session.setWriteBuffer(buffer);
    }

    // Выполняется в IO Thread
    private void processResponseQueue() {
        while (!responseQueue.isEmpty()) {
            ClientSession session = responseQueue.poll();
            if (session == null) break;

            SocketChannel channel = session.getChannel();
            SelectionKey key = channel.keyFor(selector);
            
            if (key != null && key.isValid()) {
                // Переключаем интерес на ЗАПИСЬ
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = session.getWriteBuffer();

        if (buffer != null) {
            channel.write(buffer);
            if (!buffer.hasRemaining()) {
                // Все записали
                System.out.println("Response sent to " + channel.getRemoteAddress());
                // Так как задача выполнена, закроем.
                closeConnection(key); 
            }
        }
    }

    private void closeConnection(SelectionKey key) {
        if (key == null) return;
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException e) {
            // ignore
        }
    }

    public void stop() {
        isRunning = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    public int getPort() {
        if (serverChannel != null && serverChannel.socket() != null) {
            return serverChannel.socket().getLocalPort();
        }
        return port;
    }
}
