package com.urbantransport.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "identity.jwt")
public record JwtProperties(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl, String privateKeyPath) {
}