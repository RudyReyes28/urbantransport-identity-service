package com.urbantransport.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.urbantransport.identity.config.JwtProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Carga (o genera) el par RSA con el que identity firma los JWT. La llave pública se deriva de la privada. */
@Slf4j
@Component
public class RsaKeyProvider {

    private static final String PEM_LINE_SEPARATOR = "\n";

    /** JWK con llave privada y pública; el kid es el thumbprint de la llave pública. */
    @Getter
    private final RSAKey rsaKey;

    public RsaKeyProvider(JwtProperties properties) {
        KeyPair keyPair = loadOrCreate(properties.privateKeyPath());
        try {
            this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey(keyPair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException exception) {
            throw new IllegalStateException("No se pudo construir el JWK de firma", exception);
        }
        log.info("Llave de firma JWT lista, kid={}", rsaKey.getKeyID());
    }

    /** PEM X.509 (SubjectPublicKeyInfo), el formato que consume el gateway. */
    public String getPublicKeyPem() {
        try {
            byte[] encoded = rsaKey.toRSAPublicKey().getEncoded();
            return "-----BEGIN PUBLIC KEY-----" + PEM_LINE_SEPARATOR
                    + Base64.getMimeEncoder(64, PEM_LINE_SEPARATOR.getBytes(StandardCharsets.US_ASCII)).encodeToString(encoded)
                    + PEM_LINE_SEPARATOR + "-----END PUBLIC KEY-----" + PEM_LINE_SEPARATOR;
        } catch (JOSEException exception) {
            throw new IllegalStateException("No se pudo exportar la llave pública", exception);
        }
    }

    private KeyPair loadOrCreate(String path) {
        try {
            if (path == null || path.isBlank()) {
                log.warn("identity.jwt.private-key-path vacío: se usa una llave en memoria; los tokens se invalidan al reiniciar");
                return generate();
            }
            Path file = Path.of(path);
            if (Files.exists(file)) {
                log.info("Cargando llave privada JWT desde {}", file.toAbsolutePath());
                return read(file);
            }
            KeyPair generated = generate();
            write(file, generated.getPrivate());
            log.warn("Llave privada JWT generada y guardada en {}", file.toAbsolutePath());
            return generated;
        } catch (IOException | java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo cargar la llave privada JWT", exception);
        }
    }

    private KeyPair generate() throws java.security.NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private KeyPair read(Path file) throws IOException, java.security.GeneralSecurityException {
        String base64 = Files.readString(file).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }

    private void write(Path file, PrivateKey privateKey) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String pem = "-----BEGIN PRIVATE KEY-----" + PEM_LINE_SEPARATOR
                + Base64.getMimeEncoder(64, PEM_LINE_SEPARATOR.getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded())
                + PEM_LINE_SEPARATOR + "-----END PRIVATE KEY-----" + PEM_LINE_SEPARATOR;
        Files.writeString(file, pem);
    }
}