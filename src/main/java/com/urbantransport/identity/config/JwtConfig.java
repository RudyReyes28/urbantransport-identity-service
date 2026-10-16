package com.urbantransport.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.urbantransport.identity.security.RsaKeyProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(RsaKeyProvider keyProvider) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keyProvider.getRsaKey())));
    }

    @Bean
    public JwtDecoder jwtDecoder(RsaKeyProvider keyProvider) throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(keyProvider.getRsaKey().toRSAPublicKey()).build();
    }
}