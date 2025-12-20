package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class App {
    private static final String BASE_URL = "http://localhost:8080";
    
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // Phaser для ожидания завершения всех потоков
    private static final Phaser phaser = new Phaser(1); // 1 - регистрируем main

    private static final Set<String> visited = ConcurrentHashMap.newKeySet();
    private static final Queue<String> collectedMessages = new ConcurrentLinkedQueue<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NodeResponse(String message, List<String> successors) {}

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        System.out.println("Starting crawler on " + BASE_URL + "...");

        // Запуск
        crawlAsync("/");

        // Ждем завершения всех
        phaser.arriveAndAwaitAdvance();

        printResults(startTime);
    }

    private static void crawlAsync(String path) {
        String fullUrl = resolveUrl(BASE_URL, path);

        if (!visited.add(fullUrl)) {
            return;
        }

        phaser.register(); // Регистрируем новую задачу

        // Создаем виртуальный поток
        Thread.ofVirtual().start(() -> {
            try {
                processNode(fullUrl);
            } finally {
                phaser.arriveAndDeregister(); // Сообщаем о завершении
            }
        });
    }

    private static void processNode(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMinutes(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                NodeResponse data = mapper.readValue(response.body(), NodeResponse.class);
                
                if (data.message() != null) {
                    collectedMessages.add(data.message());
                }

                if (data.successors() != null) {
                    for (String childPath : data.successors()) {
                        crawlAsync(childPath);
                    }
                }
            } else {
                System.err.println("Error fetching " + url + ": Code " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("Exception processing " + url + ": " + e.getMessage());
        }
    }

    private static String resolveUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        } else if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private static void printResults(long startTime) {
        System.out.println("\n--- Results ---");
        List<String> sortedMessages = collectedMessages.stream()
                .sorted()
                .collect(Collectors.toList());

        for (String msg : sortedMessages) {
            System.out.println(msg);
        }
        System.out.println("----------------");
        System.out.println("Total messages: " + sortedMessages.size());
        System.out.println("Time taken: " + (System.currentTimeMillis() - startTime) + " ms");
    }
}
