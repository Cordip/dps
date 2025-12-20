package org.example;

import java.security.KeyPair;

public record KeyGenerationResult(
        String clientName,
        KeyPair keyPair,
        byte[] certificateDer // Сертификат в формате DER
) {}
