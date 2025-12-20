package org.example;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KeyGenerationClient {

    public void run(String host, int port, String clientName, int delaySeconds, boolean crash) {
        System.out.println("Connecting to " + host + ":" + port + " as '" + clientName + "'...");
        
        try (Socket socket = new Socket(host, port)) {
            // Отправка запроса
            OutputStream out = socket.getOutputStream();
            out.write(clientName.getBytes(StandardCharsets.US_ASCII));
            out.write(0);
            out.flush();
            System.out.println("Request sent.");

            // Имитация аварийного завершения
            if (crash) {
                System.out.println("Simulating CRASH! Closing connection without reading response.");
                return;
            }

            if (delaySeconds > 0) {
                System.out.println("Simulating SLOW client. Sleeping for " + delaySeconds + " seconds...");
                Thread.sleep(delaySeconds * 1000L);
            }

            // Чтение ответа
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Читаем ключ
            int keyLen = in.readInt();
            System.out.println("Receiving Private Key (" + keyLen + " bytes)...");
            byte[] keyBytes = new byte[keyLen];
            in.readFully(keyBytes);

            // Читаем сертификат
            int certLen = in.readInt();
            System.out.println("Receiving Certificate (" + certLen + " bytes)...");
            byte[] certBytes = new byte[certLen];
            in.readFully(certBytes);

            saveToFile(clientName + ".key", keyBytes, "PRIVATE KEY");
            saveToFile(clientName + ".crt", certBytes, "CERTIFICATE");

            System.out.println("Done! Files saved: " + clientName + ".key, " + clientName + ".crt");

        } catch (EOFException e) {
            System.err.println("Error: Server closed connection unexpectedly (or generation took too long?).");
        } catch (Exception e) {
            System.err.println("Client Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveToFile(String filename, byte[] data, String pemHeader) throws IOException {
        Path path = Paths.get(filename);
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("-----BEGIN " + pemHeader + "-----\n");
            
            String base64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(data);
            writer.write(base64);
            
            writer.write("\n-----END " + pemHeader + "-----\n");
        }
    }
}
