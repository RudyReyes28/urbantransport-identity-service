package com.urbantransport.identity.security;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public record Actor(UUID userId, Role role, UUID branchId) {

    public static Actor from(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El token no tiene rol");
        }
        String branch = jwt.getClaimAsString("branchId");
        return new Actor(
                UUID.fromString(jwt.getSubject()),
                Role.valueOf(roles.get(0)),
                branch == null || branch.isBlank() ? null : UUID.fromString(branch));
    }

    public boolean isSystemAdmin() {
        return role == Role.SYSTEM_ADMIN;
    }
}