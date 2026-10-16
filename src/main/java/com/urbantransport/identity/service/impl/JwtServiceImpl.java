package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.JwtProperties;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    @Override
    public String generateAccessToken(UserEntity user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("roles", List.of(user.getRole().name()));

        if (user.getBranch() != null) {
            claims.claim("branchId", user.getBranch().getId().toString());
        }
        if (user.getCustomerId() != null) {
            claims.claim("customerId", user.getCustomerId().toString());
        }
        if (user.getNit() != null) {
            claims.claim("nit", user.getNit());
        }

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    @Override
    public long getAccessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}