package com.urbantransport.identity.security;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActorAndRefreshTokensTest {

    @Test
    void shouldCreateActorFromJwtWithAndWithoutBranch() {
        UUID userId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();

        Jwt adminJwt = buildJwt(userId, Map.of("roles", List.of("SYSTEM_ADMIN"), "branchId", "   "));
        Actor adminActor = Actor.from(adminJwt);
        assertEquals(userId, adminActor.userId());
        assertEquals(Role.SYSTEM_ADMIN, adminActor.role());
        assertNull(adminActor.branchId());
        assertTrue(adminActor.isSystemAdmin());

        Jwt branchJwt = buildJwt(userId, Map.of("roles", List.of("BRANCH_ADMIN"), "branchId", branchId.toString()));
        Actor branchActor = Actor.from(branchJwt);
        assertEquals(branchId, branchActor.branchId());
        assertFalse(branchActor.isSystemAdmin());
    }

    @Test
    void shouldThrowExceptionWhenJwtHasNoRoles() {
        UUID userId = UUID.randomUUID();
        Jwt emptyRolesJwt = buildJwt(userId, Map.of("roles", Collections.emptyList()));
        assertThrows(ApiException.class, () -> Actor.from(emptyRolesJwt));

        Jwt nullRolesJwt = buildJwt(userId, Map.of("other", "value"));
        assertThrows(ApiException.class, () -> Actor.from(nullRolesJwt));
    }

    @Test
    void shouldGenerateAndHashRefreshTokensDeterministically() {
        String token1 = RefreshTokens.generate();
        String token2 = RefreshTokens.generate();

        assertNotNull(token1);
        assertNotEquals(token1, token2);

        String hash1 = RefreshTokens.hash(token1);
        String hash1Again = RefreshTokens.hash(token1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash1Again);
        assertNotEquals(hash1, RefreshTokens.hash(token2));
    }

    private Jwt buildJwt(UUID subject, Map<String, Object> claims) {
        return new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "RS256"),
                claims
        ) {
            @Override
            public String getSubject() {
                return subject.toString();
            }
        };
    }
}