package org.example;

import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

public class App {

    private static final String DEFAULT_ISSUER_KEY_FILE = "issuer.key";

    public static void main(String[] args) {
        Options options = new Options();

        // Общие опции
        options.addOption("m", "mode", true, "Mode: 'server' or 'client'");
        options.addOption("h", "host", true, "Server host (default: localhost)");
        options.addOption("p", "port", true, "Server port (default: 8080)");

        // Опции сервера
        options.addOption("t", "threads", true, "Number of worker threads (default: 4)");
        options.addOption("k", "keysize", true, "RSA Key size (default: 8192)");
        options.addOption("i", "issuer", true, "Issuer Name (default: CN=MySecureCA)");
        
        // Опции клиента
        options.addOption("n", "name", true, "Client name for certificate");
        options.addOption("d", "delay", true, "Delay in seconds before reading response (slow client)");
        options.addOption("c", "crash", false, "Simulate client crash after sending request");

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String mode = cmd.getOptionValue("mode", "server");
            int port = Integer.parseInt(cmd.getOptionValue("port", "8080"));
            String host = cmd.getOptionValue("host", "localhost");

            if ("server".equalsIgnoreCase(mode)) {
                runServer(cmd, port);
            } else if ("client".equalsIgnoreCase(mode)) {
                runClient(cmd, host, port);
            } else {
                throw new ParseException("Unknown mode: " + mode);
            }

        } catch (ParseException e) {
            System.err.println("Argument error: " + e.getMessage());
            new HelpFormatter().printHelp("KeyGenApp", options);
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runServer(CommandLine cmd, int port) throws Exception {
        int threads = Integer.parseInt(cmd.getOptionValue("threads", "4"));
        int keySize = Integer.parseInt(cmd.getOptionValue("keysize", "8192"));
        String issuerName = cmd.getOptionValue("issuer", "CN=MySecureCA");

        System.out.println("Initializing Crypto Service...");
        
        Path issuerKeyPath = Paths.get(DEFAULT_ISSUER_KEY_FILE);
        KeyPair issuerKp;

        if (Files.exists(issuerKeyPath)) {
            System.out.println("Loading Issuer Key from " + issuerKeyPath.toAbsolutePath());
            issuerKp = CryptoService.loadKeyPairFromPem(issuerKeyPath);
        } else {
            System.out.println("Issuer Key not found. Generating new one to " + issuerKeyPath.toAbsolutePath());
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(4096); // Ключ CA
            issuerKp = kpg.generateKeyPair();
            CryptoService.saveKeyPairToPem(issuerKp, issuerKeyPath);
        }
        
        CryptoService cryptoService = new CryptoService(issuerKp, issuerName);

        NioServer server = new NioServer(port, threads, keySize, cryptoService);
        server.start();
    }

    private static void runClient(CommandLine cmd, String host, int port) throws ParseException {
        String name = cmd.getOptionValue("name");
        if (name == null) {
            throw new ParseException("Client name (-n) is required for client mode");
        }
        
        int delay = Integer.parseInt(cmd.getOptionValue("delay", "0"));
        boolean crash = cmd.hasOption("crash");

        new KeyGenerationClient().run(host, port, name, delay, crash);
    }
}
