package com.urbantransport.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "identity.password-setup")
public record PasswordSetupProperties(String url, Duration tokenTtl) {
}
