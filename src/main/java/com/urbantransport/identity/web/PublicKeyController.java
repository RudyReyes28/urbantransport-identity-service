package com.urbantransport.identity.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.urbantransport.identity.security.RsaKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PublicKeyController {

    private final RsaKeyProvider keyProvider;

    /** Lo consume el gateway (PEM X.509). */
    @GetMapping(value = "/auth/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String publicKey() {
        return keyProvider.getPublicKeyPem();
    }

    /** Lo consumen los demás microservicios con spring.security.oauth2.resourceserver.jwt.jwk-set-uri. */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return new JWKSet(keyProvider.getRsaKey().toPublicJWK()).toJSONObject();
    }
}