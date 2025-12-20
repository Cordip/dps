package org.example;

import org.junit.jupiter.api.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @Test
    void testCryptoGeneration() throws Exception {
        // Создаем фейкового эмитента (Issuer) для тестов
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair issuerKp = kpg.generateKeyPair();

        CryptoService service = new CryptoService(issuerKp, "CN=TestCA");

        // Генерируем ключи клиента (2048 для скорости теста, в проде будет 8192)
        KeyPair clientKp = service.generateRsaKeyPair(2048);
        assertNotNull(clientKp);

        // Выпускаем сертификат
        byte[] certDer = service.issueCertificate("TestClient", clientKp.getPublic());
        assertNotNull(certDer);
        assertTrue(certDer.length > 0);

        // Проверяем PEM конвертацию
        String pem = CryptoService.toPem(certDer);
        assertTrue(pem.contains("BEGIN CERTIFICATE"));
        
        System.out.println("Generated Cert PEM:\n" + pem);
    }
}
