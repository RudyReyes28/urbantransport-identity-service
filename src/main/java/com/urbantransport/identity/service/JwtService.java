package com.urbantransport.identity.service;

import com.urbantransport.identity.domain.UserEntity;

public interface JwtService {

    /** Firma un access token RS256 con los claims del contrato (sub, roles, branchId, customerId, nit, exp). */
    String generateAccessToken(UserEntity user);

    long getAccessTokenTtlSeconds();
}