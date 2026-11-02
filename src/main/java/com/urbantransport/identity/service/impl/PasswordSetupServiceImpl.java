package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.PasswordSetupProperties;
import com.urbantransport.identity.domain.PasswordSetupTokenEntity;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.dto.ResendPasswordSetupRequest;
import com.urbantransport.identity.dto.SetupPasswordRequest;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.event.PasswordSetupPurpose;
import com.urbantransport.identity.event.PasswordSetupRequestedPayload;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.PasswordSetupTokenRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.security.RefreshTokens;
import com.urbantransport.identity.service.OutboxService;
import com.urbantransport.identity.service.PasswordSetupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PasswordSetupServiceImpl implements PasswordSetupService {

    private static final String INVALID_TOKEN = "El enlace no es válido, ya se usó o ya venció";

    private final PasswordSetupTokenRepository passwordSetupTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxService outboxService;
    private final PasswordSetupProperties properties;

    @Override
    public String unusablePasswordHash() {
        return passwordEncoder.encode(RefreshTokens.generate());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UserEntity user) {
        issue(user, PasswordSetupPurpose.ACCOUNT_CREATED);
    }

    @Override
    @Transactional
    public void resend(ResendPasswordSetupRequest request) {
        userRepository.findByEmailIgnoreCase(request.email().trim().toLowerCase(Locale.ROOT))
                .filter(this::canSetPassword)
                .ifPresent(user -> issue(user, PasswordSetupPurpose.RESEND_REQUESTED));
    }

    @Override
    @Transactional
    public void setup(SetupPasswordRequest request) {
        Instant now = Instant.now();
        PasswordSetupTokenEntity token = passwordSetupTokenRepository
                .findByTokenHash(RefreshTokens.hash(request.token().trim()))
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, INVALID_TOKEN));

        UserEntity user = userRepository.findById(token.getUserId())
                .filter(UserEntity::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "La cuenta está inactiva"));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        token.setUsedAt(now);
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    private void issue(UserEntity user, PasswordSetupPurpose purpose) {
        Instant now = Instant.now();
        passwordSetupTokenRepository.invalidatePending(user.getId(), now);

        String rawToken = RefreshTokens.generate();
        Instant expiresAt = now.plus(properties.tokenTtl());
        passwordSetupTokenRepository.save(PasswordSetupTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(RefreshTokens.hash(rawToken))
                .expiresAt(expiresAt)
                .build());

        String setupUrl = UriComponentsBuilder.fromUriString(properties.url())
                .queryParam("token", rawToken)
                .build()
                .toUriString();
        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.PASSWORD_SETUP_REQUESTED,
                new PasswordSetupRequestedPayload(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                        purpose, setupUrl, expiresAt));
    }

    private boolean canSetPassword(UserEntity user) {
        return user.isActive() && (user.getBranch() == null || user.getBranch().isActive());
    }
}
