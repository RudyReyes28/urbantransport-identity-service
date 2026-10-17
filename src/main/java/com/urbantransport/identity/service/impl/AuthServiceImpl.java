package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.JwtProperties;
import com.urbantransport.identity.domain.RefreshTokenEntity;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.security.RefreshTokens;
import com.urbantransport.identity.service.AuthService;
import com.urbantransport.identity.service.JwtService;
import com.urbantransport.identity.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String INVALID_CREDENTIALS = "Credenciales inválidas";
    private static final String INVALID_REFRESH = "Refresh token inválido o vencido";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(this::canAuthenticate)
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS));
        return issueTokens(user);
    }

    /** noRollbackFor: si detectamos reutilización, la revocación masiva debe quedar guardada aunque lancemos 401. */
    @Override
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(RefreshRequest request) {
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(RefreshTokens.hash(request.refreshToken()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH));
        UserEntity user = stored.getUser();

        if (stored.isRevoked()) {
            log.warn("Reutilización de refresh token revocado, se revocan todas las sesiones del usuario {}", user.getId());
            refreshTokenRepository.revokeAllByUserId(user.getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH);
        }
        if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH);
        }
        if (!canAuthenticate(user)) {
            refreshTokenRepository.revokeAllByUserId(user.getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, INVALID_REFRESH);
        }

        stored.setRevoked(true);
        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(RefreshTokens.hash(request.refreshToken()))
                .ifPresent(token -> token.setRevoked(true));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return userRepository.findById(userId)
                .filter(this::canAuthenticate)
                .map(UserResponse::fromEntity)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Usuario no disponible"));
    }

    private TokenResponse issueTokens(UserEntity user) {
        String rawRefreshToken = RefreshTokens.generate();
        refreshTokenRepository.save(RefreshTokenEntity.builder()
                .user(user)
                .tokenHash(RefreshTokens.hash(rawRefreshToken))
                .expiresAt(LocalDateTime.now().plus(jwtProperties.refreshTokenTtl()))
                .build());

        return TokenResponse.of(
                jwtService.generateAccessToken(user),
                rawRefreshToken,
                jwtService.getAccessTokenTtlSeconds(),
                UserResponse.fromEntity(user));
    }

    private boolean canAuthenticate(UserEntity user) {
        return user.isActive() && (user.getBranch() == null || user.getBranch().isActive());
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        UserEntity user = userRepository.findById(userId)
                .filter(this::canAuthenticate)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Usuario no disponible"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La contraseña actual es incorrecta");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }
}