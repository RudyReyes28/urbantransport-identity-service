package com.urbantransport.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.internal")
public record InternalProperties(String apiKey) {
}