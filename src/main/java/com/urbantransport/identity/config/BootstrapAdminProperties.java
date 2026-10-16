package com.urbantransport.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password, String fullName) {
}