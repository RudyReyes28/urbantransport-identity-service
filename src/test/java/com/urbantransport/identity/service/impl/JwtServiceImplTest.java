package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.JwtConfig;
import com.urbantransport.identity.config.JwtProperties;
import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.security.RsaKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceImplTest {

    private JwtServiceImpl jwtService;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties properties = new JwtProperties(
                "urban-transport-identity",
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                null
        );
        RsaKeyProvider keyProvider = new RsaKeyProvider(properties);
        JwtConfig jwtConfig = new JwtConfig();
        JwtEncoder jwtEncoder = jwtConfig.jwtEncoder(keyProvider);
        this.jwtDecoder = jwtConfig.jwtDecoder(keyProvider);
        this.jwtService = new JwtServiceImpl(jwtEncoder, properties);
    }

    @Test
    void shouldGenerateAccessTokenWithAllOptionalClaims() {
        UUID userId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        BranchEntity branch = BranchEntity.builder().id(branchId).build();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .email("cajero@urbantransport.com")
                .role(Role.CASHIER)
                .branch(branch)
                .customerId(customerId)
                .nit("12345678")
                .build();

        String token = jwtService.generateAccessToken(user);
        assertNotNull(token);

        Jwt decoded = jwtDecoder.decode(token);
        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals(List.of("CASHIER"), decoded.getClaimAsStringList("roles"));
        assertEquals(branchId.toString(), decoded.getClaimAsString("branchId"));
        assertEquals(customerId.toString(), decoded.getClaimAsString("customerId"));
        assertEquals("12345678", decoded.getClaimAsString("nit"));
        assertEquals("cajero@urbantransport.com", decoded.getClaimAsString("email"));
        assertEquals(1800L, jwtService.getAccessTokenTtlSeconds());
    }

    @Test
    void shouldGenerateAccessTokenWithoutOptionalClaims() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId)
                .email(null)
                .role(Role.SYSTEM_ADMIN)
                .branch(null)
                .customerId(null)
                .nit(null)
                .build();

        String token = jwtService.generateAccessToken(user);
        Jwt decoded = jwtDecoder.decode(token);

        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals(List.of("SYSTEM_ADMIN"), decoded.getClaimAsStringList("roles"));
        assertNull(decoded.getClaimAsString("branchId"));
        assertNull(decoded.getClaimAsString("customerId"));
        assertNull(decoded.getClaimAsString("nit"));
        assertNull(decoded.getClaimAsString("email"));
    }
}