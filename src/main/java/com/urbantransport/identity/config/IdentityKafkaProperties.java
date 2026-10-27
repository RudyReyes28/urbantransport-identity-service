package com.urbantransport.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.kafka")
public record IdentityKafkaProperties(String eventsTopic, String customerEventsTopic, String fleetEventsTopic) {
}