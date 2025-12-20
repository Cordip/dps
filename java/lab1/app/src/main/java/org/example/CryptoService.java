package org.example;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.ByteArrayInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class CryptoService {

    private final KeyPair issuerKeyPair;
    private final X500Name issuerName;
    private static final Provider BC_PROVIDER = new org.bouncycastle.jce.provider.BouncyCastleProvider();

    static {
        Security.addProvider(BC_PROVIDER);
    }

    public CryptoService(KeyPair issuerKeyPair, String issuerDn) {
        this.issuerKeyPair = issuerKeyPair;
        this.issuerName = new X500Name(issuerDn);
    }

    /** 
     * Метод для загрузки ключа из файла
     */
    public static KeyPair loadKeyPairFromPem(Path path) throws IOException {
        try (FileReader reader = new FileReader(path.toFile());
             PEMParser pemParser = new PEMParser(reader)) {
            
            Object object = pemParser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            
            if (object instanceof PEMKeyPair) {
                // Формат PKCS#1 (-----BEGIN RSA PRIVATE KEY-----)
                return converter.getKeyPair((PEMKeyPair) object);
            
            } else if (object instanceof PrivateKeyInfo) {
                // Формат PKCS#8 (-----BEGIN PRIVATE KEY-----)
                PrivateKey pk = converter.getPrivateKey((PrivateKeyInfo) object);
                
                if (pk instanceof RSAPrivateCrtKey) {
                    try {
                        RSAPrivateCrtKey rsaPk = (RSAPrivateCrtKey) pk;
                        RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(rsaPk.getModulus(), rsaPk.getPublicExponent());
                        KeyFactory kf = KeyFactory.getInstance("RSA", "BC");
                        PublicKey pub = kf.generatePublic(pubSpec);
                        return new KeyPair(pub, pk);
                    } catch (Exception e) {
                        return new KeyPair(null, pk);
                    }
                }
                return new KeyPair(null, pk);
            } else {
                throw new IOException("File does not contain a valid KeyPair: " + path + 
                                      " (Found type: " + (object == null ? "null" : object.getClass().getSimpleName()) + ")");
            }
        }
    }

    /**
     * Метод для сохранения ключа в файл
     */ 
    public static void saveKeyPairToPem(KeyPair keyPair, Path path) throws IOException {
        try (FileWriter fileWriter = new FileWriter(path.toFile());
             PemWriter pemWriter = new PemWriter(fileWriter)) {
            // Сохраняем в формате PKCS#8
            pemWriter.writeObject(new PemObject("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        }
    }

    /**
     * Генерирует пару RSA ключей указанного размера.
     */
    public KeyPair generateRsaKeyPair(int keySize) throws NoSuchAlgorithmException, NoSuchProviderException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "BC");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    /**
     * Создает X.509 сертификат для переданного публичного ключа, подписанный ключом Issuer-а.
     */
    public byte[] issueCertificate(String subjectName, PublicKey clientPublicKey) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = new Date(now + 365L * 24 * 60 * 60 * 1000); // 1 год

        BigInteger serialNumber = BigInteger.valueOf(now);

        X500Name subject = new X500Name("CN=" + subjectName);
        SubjectPublicKeyInfo subPubKeyInfo = SubjectPublicKeyInfo.getInstance(clientPublicKey.getEncoded());

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                issuerName,
                serialNumber,
                startDate,
                endDate,
                subject,
                subPubKeyInfo
        );

        // Подпись сертификата приватным ключом сервера (Issuer)
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .setProvider("BC")
                .build(issuerKeyPair.getPrivate());

        X509CertificateHolder holder = certBuilder.build(signer);
        
        return holder.getEncoded();
    }
    
    /**
     * Хелпер для конвертации ключей/сертификатов в PEM формат
     */ 
    public static String toPem(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (PemWriter pw = new PemWriter(sw)) {
            if (obj instanceof PublicKey) {
                pw.writeObject(new PemObject("PUBLIC KEY", ((PublicKey) obj).getEncoded()));
            } else if (obj instanceof PrivateKey) {
                pw.writeObject(new PemObject("PRIVATE KEY", ((PrivateKey) obj).getEncoded()));
            } else if (obj instanceof byte[]) {
                // Предполагаем, что это encoded certificate
                pw.writeObject(new PemObject("CERTIFICATE", (byte[]) obj));
            }
        }
        return sw.toString();
    }
}
