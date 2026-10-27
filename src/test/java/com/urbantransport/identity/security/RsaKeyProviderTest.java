package com.urbantransport.identity.security;

import com.urbantransport.identity.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RsaKeyProviderTest {

    @Test
    void shouldGenerateInMemoryKeyWhenPathIsNullOrBlank() {
        JwtProperties propsNull = new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), null);
        RsaKeyProvider providerNull = new RsaKeyProvider(propsNull);

        assertNotNull(providerNull.getRsaKey());
        assertNotNull(providerNull.getRsaKey().getKeyID());
        String pem = providerNull.getPublicKeyPem();
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertTrue(pem.trim().endsWith("-----END PUBLIC KEY-----"));

        JwtProperties propsBlank = new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), "   ");
        RsaKeyProvider providerBlank = new RsaKeyProvider(propsBlank);
        assertNotNull(providerBlank.getRsaKey());
    }

    @Test
    void shouldCreateAndReadKeyFileFromDisk(@TempDir Path tempDir) {
        Path keyFile = tempDir.resolve("keys/private.pem");
        JwtProperties props = new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), keyFile.toString());

        // 1. No existe: debe generarla y escribirla en disco
        RsaKeyProvider firstProvider = new RsaKeyProvider(props);
        assertTrue(Files.exists(keyFile));
        String firstKeyId = firstProvider.getRsaKey().getKeyID();

        // 2. Ya existe: debe leer la misma llave desde el archivo
        RsaKeyProvider secondProvider = new RsaKeyProvider(props);
        assertEquals(firstKeyId, secondProvider.getRsaKey().getKeyID());
        assertEquals(firstProvider.getPublicKeyPem(), secondProvider.getPublicKeyPem());
    }

    @Test
    void shouldThrowExceptionWhenKeyFileIsCorrupted(@TempDir Path tempDir) throws IOException {
        Path corruptedFile = tempDir.resolve("corrupted.pem");
        // Base64 válido ("dGVzdA=="), pero bytes inválidos para PKCS8EncodedKeySpec -> lanza InvalidKeySpecException -> entra al catch -> IllegalStateException
        Files.writeString(corruptedFile, "-----BEGIN PRIVATE KEY-----\ndGVzdA==\n-----END PRIVATE KEY-----");

        JwtProperties props = new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), corruptedFile.toString());

        assertThrows(IllegalStateException.class, () -> new RsaKeyProvider(props));
    }
}