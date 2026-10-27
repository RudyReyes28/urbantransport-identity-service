package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.JwtProperties;
import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.RefreshTokenEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.dto.*;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.security.RefreshTokens;
import com.urbantransport.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthServiceImpl authService;
    private UserEntity activeUser;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), null);
        authService = new AuthServiceImpl(userRepository, refreshTokenRepository, passwordEncoder, jwtService, jwtProperties);

        activeUser = UserEntity.builder()
                .id(UUID.randomUUID())
                .email("admin@test.com")
                .passwordHash("hashed")
                .fullName("Admin")
                .role(Role.SYSTEM_ADMIN)
                .active(true)
                .build();
    }

    @Test
    void loginShouldSucceedForActiveUserWithNullOrActiveBranch() {
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("secret123", "hashed")).thenReturn(true);
        when(jwtService.generateAccessToken(activeUser)).thenReturn("jwt-token");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);

        TokenResponse response = authService.login(new LoginRequest("  admin@test.com ", "secret123"));

        assertEquals("jwt-token", response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals(900L, response.getExpiresIn());
        assertNotNull(response.getRefreshToken());
        verify(refreshTokenRepository).save(any(RefreshTokenEntity.class));

        // Con sucursal activa también debe autenticar
        activeUser.setBranch(BranchEntity.builder().id(UUID.randomUUID()).active(true).build());
        assertNotNull(authService.login(new LoginRequest("admin@test.com", "secret123")));
    }

    @Test
    void loginShouldFailWhenUserInvalidInactiveOrBranchInactive() {
        // 1. No existe
        when(userRepository.findByEmailIgnoreCase("none@test.com")).thenReturn(Optional.empty());
        ApiException ex1 = assertThrows(ApiException.class, () -> authService.login(new LoginRequest("none@test.com", "123")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex1.getStatus());

        // 2. Usuario inactivo
        activeUser.setActive(false);
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(activeUser));
        assertThrows(ApiException.class, () -> authService.login(new LoginRequest("admin@test.com", "secret123")));

        // 3. Usuario activo pero sucursal inactiva
        activeUser.setActive(true);
        activeUser.setBranch(BranchEntity.builder().id(UUID.randomUUID()).active(false).build());
        assertThrows(ApiException.class, () -> authService.login(new LoginRequest("admin@test.com", "secret123")));

        // 4. Contraseña incorrecta
        activeUser.setBranch(null);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        assertThrows(ApiException.class, () -> authService.login(new LoginRequest("admin@test.com", "wrong")));
    }

    @Test
    void refreshShouldHandleValidRevokedExpiredAndUnauthenticatableUsers() {
        String rawToken = "valid-refresh";
        String tokenHash = RefreshTokens.hash(rawToken);

        // 1. Token no encontrado
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> authService.refresh(new RefreshRequest(rawToken)));

        // 2. Token ya revocado -> revoca todas las sesiones del usuario y lanza 401
        RefreshTokenEntity revokedEntity = RefreshTokenEntity.builder()
                .user(activeUser)
                .tokenHash(tokenHash)
                .revoked(true)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(revokedEntity));
        assertThrows(ApiException.class, () -> authService.refresh(new RefreshRequest(rawToken)));
        verify(refreshTokenRepository).revokeAllByUserId(activeUser.getId());

        // 3. Token expirado -> lanza 401
        RefreshTokenEntity expiredEntity = RefreshTokenEntity.builder()
                .user(activeUser)
                .tokenHash(tokenHash)
                .revoked(false)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(expiredEntity));
        assertThrows(ApiException.class, () -> authService.refresh(new RefreshRequest(rawToken)));

        // 4. Usuario desactivado mientras tenía token -> revoca todas las sesiones y lanza 401
        activeUser.setActive(false);
        RefreshTokenEntity validEntity = RefreshTokenEntity.builder()
                .user(activeUser)
                .tokenHash(tokenHash)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(validEntity));
        assertThrows(ApiException.class, () -> authService.refresh(new RefreshRequest(rawToken)));
        verify(refreshTokenRepository, times(2)).revokeAllByUserId(activeUser.getId());

        // 5. Flujo exitoso -> revoca el actual y emite uno nuevo
        activeUser.setActive(true);
        when(jwtService.generateAccessToken(activeUser)).thenReturn("new-jwt");
        TokenResponse response = authService.refresh(new RefreshRequest(rawToken));
        assertTrue(validEntity.isRevoked());
        assertEquals("new-jwt", response.getAccessToken());
    }

    @Test
    void logoutMeAndChangePasswordShouldCoverAllBranches() {
        // Logout
        String raw = "logout-token";
        RefreshTokenEntity stored = RefreshTokenEntity.builder().revoked(false).build();
        when(refreshTokenRepository.findByTokenHash(RefreshTokens.hash(raw))).thenReturn(Optional.of(stored));
        authService.logout(new RefreshRequest(raw));
        assertTrue(stored.isRevoked());

        // Me exitoso y fallido
        when(userRepository.findById(activeUser.getId())).thenReturn(Optional.of(activeUser));
        UserResponse meResp = authService.me(activeUser.getId());
        assertEquals(activeUser.getEmail(), meResp.getEmail());

        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> authService.me(missingId));

        // ChangePassword: usuario no disponible, password actual incorrecta y cambio exitoso
        assertThrows(ApiException.class,
                () -> authService.changePassword(missingId, new ChangePasswordRequest("old", "newPass123")));

        when(passwordEncoder.matches("wrongOld", "hashed")).thenReturn(false);
        ApiException badPassEx = assertThrows(ApiException.class,
                () -> authService.changePassword(activeUser.getId(), new ChangePasswordRequest("wrongOld", "newPass123")));
        assertEquals(HttpStatus.BAD_REQUEST, badPassEx.getStatus());

        when(passwordEncoder.matches("correctOld", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("newPass123")).thenReturn("newHashed");
        authService.changePassword(activeUser.getId(), new ChangePasswordRequest("correctOld", "newPass123"));
        assertEquals("newHashed", activeUser.getPasswordHash());
        verify(refreshTokenRepository).revokeAllByUserId(activeUser.getId());
    }
}