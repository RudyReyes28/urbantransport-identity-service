package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.config.PasswordSetupProperties;
import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.PasswordSetupTokenEntity;
import com.urbantransport.identity.domain.Role;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordSetupServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SETUP_URL = "http://localhost:4200/set-password";
    private static final Duration TTL = Duration.ofHours(48);
    private static final String RAW_TOKEN = "token-de-prueba";
    private static final String EMAIL = "chofer@test.com";

    @Mock
    private PasswordSetupTokenRepository passwordSetupTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxService outboxService;

    private PasswordSetupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = serviceWithUrl(SETUP_URL);
    }

    private PasswordSetupServiceImpl serviceWithUrl(String url) {
        return new PasswordSetupServiceImpl(passwordSetupTokenRepository, userRepository, refreshTokenRepository,
                passwordEncoder, outboxService, new PasswordSetupProperties(url, TTL));
    }

    @Nested
    class UnusablePasswordHash {

        @Test
        void encodesARandomValueEveryTime() {
            when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "hash-" + invocation.getArgument(0));

            String first = service.unusablePasswordHash();
            String second = service.unusablePasswordHash();

            assertTrue(first.startsWith("hash-"));
            assertNotEquals(first, second);
        }
    }

    @Nested
    class Issue {

        @Test
        void invalidatesPreviousLinksStoresOnlyTheHashAndPublishesTheFullLink() {
            Instant before = Instant.now();

            service.issue(user(Role.DRIVER, true, null));

            InOrder order = inOrder(passwordSetupTokenRepository);
            order.verify(passwordSetupTokenRepository).invalidatePending(eq(USER_ID), any(Instant.class));
            ArgumentCaptor<PasswordSetupTokenEntity> saved = ArgumentCaptor.forClass(PasswordSetupTokenEntity.class);
            order.verify(passwordSetupTokenRepository).save(saved.capture());
            PasswordSetupRequestedPayload payload = recordedPayload();

            assertTrue(payload.getSetupUrl().startsWith(SETUP_URL + "?token="));
            String rawToken = payload.getSetupUrl().substring((SETUP_URL + "?token=").length());
            assertEquals(RefreshTokens.hash(rawToken), saved.getValue().getTokenHash());
            assertEquals(USER_ID, saved.getValue().getUserId());
            assertNull(saved.getValue().getUsedAt());
            assertEquals(saved.getValue().getExpiresAt(), payload.getExpiresAt());
            assertTrue(!payload.getExpiresAt().isBefore(before.plus(TTL))
                    && !payload.getExpiresAt().isAfter(Instant.now().plus(TTL)));
            assertEquals(USER_ID, payload.getUserId());
            assertEquals(EMAIL, payload.getEmail());
            assertEquals("Chofer Uno", payload.getFullName());
            assertEquals(Role.DRIVER, payload.getRole());
            assertEquals(PasswordSetupPurpose.ACCOUNT_CREATED, payload.getPurpose());
        }

        @Test
        void urlWithQuery_appendsTokenAsAnotherParameter() {
            serviceWithUrl("https://front.test/cuenta?lang=es").issue(user(Role.CUSTOMER, true, null));

            assertTrue(recordedPayload().getSetupUrl().startsWith("https://front.test/cuenta?lang=es&token="));
        }

        @Test
        void everyCall_generatesADifferentToken() {
            service.issue(user(Role.CASHIER, true, null));
            service.issue(user(Role.CASHIER, true, null));

            ArgumentCaptor<PasswordSetupTokenEntity> saved = ArgumentCaptor.forClass(PasswordSetupTokenEntity.class);
            verify(passwordSetupTokenRepository, times(2)).save(saved.capture());
            assertNotEquals(saved.getAllValues().get(0).getTokenHash(), saved.getAllValues().get(1).getTokenHash());
        }
    }

    @Nested
    class Resend {

        @Test
        void activeUser_receivesANewLinkMarkedAsResend() {
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.DRIVER, true, null)));

            service.resend(new ResendPasswordSetupRequest("  Chofer@Test.com "));

            verify(passwordSetupTokenRepository).invalidatePending(eq(USER_ID), any(Instant.class));
            verify(passwordSetupTokenRepository).save(any(PasswordSetupTokenEntity.class));
            assertEquals(PasswordSetupPurpose.RESEND_REQUESTED, recordedPayload().getPurpose());
        }

        @Test
        void userOfActiveBranch_receivesANewLink() {
            when(userRepository.findByEmailIgnoreCase(EMAIL))
                    .thenReturn(Optional.of(user(Role.CASHIER, true, branch(true))));

            service.resend(new ResendPasswordSetupRequest(EMAIL));

            assertEquals(Role.CASHIER, recordedPayload().getRole());
        }

        @Test
        void unknownEmail_doesNothingAndDoesNotRevealIt() {
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

            service.resend(new ResendPasswordSetupRequest(EMAIL));

            verifyNoInteractions(passwordSetupTokenRepository, outboxService);
        }

        @Test
        void inactiveUser_doesNothing() {
            when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(Role.DRIVER, false, null)));

            service.resend(new ResendPasswordSetupRequest(EMAIL));

            verifyNoInteractions(passwordSetupTokenRepository, outboxService);
        }

        @Test
        void userOfInactiveBranch_doesNothing() {
            when(userRepository.findByEmailIgnoreCase(EMAIL))
                    .thenReturn(Optional.of(user(Role.CASHIER, true, branch(false))));

            service.resend(new ResendPasswordSetupRequest(EMAIL));

            verifyNoInteractions(passwordSetupTokenRepository, outboxService);
        }
    }

    @Nested
    class Setup {

        @Test
        void validToken_setsPasswordMarksUsedAndRevokesSessions() {
            PasswordSetupTokenEntity token = token(Instant.now().plus(TTL), null);
            UserEntity user = user(Role.DRIVER, true, null);
            when(passwordSetupTokenRepository.findByTokenHash(RefreshTokens.hash(RAW_TOKEN))).thenReturn(Optional.of(token));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("NuevaClave123")).thenReturn("hash-nuevo");

            service.setup(new SetupPasswordRequest("  " + RAW_TOKEN + "  ", "NuevaClave123"));

            assertEquals("hash-nuevo", user.getPasswordHash());
            assertNotNull(token.getUsedAt());
            verify(refreshTokenRepository).revokeAllByUserId(USER_ID);
        }

        @Test
        void unknownToken_throwsBadRequest() {
            when(passwordSetupTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertApiError(HttpStatus.BAD_REQUEST, () -> service.setup(new SetupPasswordRequest(RAW_TOKEN, "NuevaClave123")));

            verifyNoInteractions(userRepository, passwordEncoder, refreshTokenRepository);
        }

        @Test
        void usedToken_throwsBadRequest() {
            PasswordSetupTokenEntity token = token(Instant.now().plus(TTL), Instant.now().minusSeconds(60));
            when(passwordSetupTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertApiError(HttpStatus.BAD_REQUEST, () -> service.setup(new SetupPasswordRequest(RAW_TOKEN, "NuevaClave123")));

            verifyNoInteractions(userRepository, passwordEncoder, refreshTokenRepository);
        }

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 3600})
        void expiredToken_throwsBadRequest(long secondsAgo) {
            PasswordSetupTokenEntity token = token(Instant.now().minusSeconds(secondsAgo), null);
            when(passwordSetupTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

            assertApiError(HttpStatus.BAD_REQUEST, () -> service.setup(new SetupPasswordRequest(RAW_TOKEN, "NuevaClave123")));

            assertNull(token.getUsedAt());
            verifyNoInteractions(userRepository, passwordEncoder, refreshTokenRepository);
        }

        @Test
        void inactiveUser_throwsConflictAndKeepsTokenUnused() {
            PasswordSetupTokenEntity token = token(Instant.now().plus(TTL), null);
            UserEntity user = user(Role.DRIVER, false, null);
            when(passwordSetupTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertApiError(HttpStatus.CONFLICT, () -> service.setup(new SetupPasswordRequest(RAW_TOKEN, "NuevaClave123")));

            assertNull(token.getUsedAt());
            assertEquals("hash-inicial", user.getPasswordHash());
            verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        }

        @Test
        void missingUser_throwsConflict() {
            when(passwordSetupTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(token(Instant.now().plus(TTL), null)));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.CONFLICT, () -> service.setup(new SetupPasswordRequest(RAW_TOKEN, "NuevaClave123")));

            verifyNoInteractions(passwordEncoder, refreshTokenRepository);
        }
    }

    private PasswordSetupRequestedPayload recordedPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).record(eq(EventTypes.AGGREGATE_USER), eq(USER_ID),
                eq(EventTypes.PASSWORD_SETUP_REQUESTED), payload.capture());
        return (PasswordSetupRequestedPayload) payload.getValue();
    }

    private static UserEntity user(Role role, boolean active, BranchEntity branch) {
        return UserEntity.builder()
                .id(USER_ID)
                .email(EMAIL)
                .fullName("Chofer Uno")
                .passwordHash("hash-inicial")
                .role(role)
                .branch(branch)
                .active(active)
                .build();
    }

    private static BranchEntity branch(boolean active) {
        return BranchEntity.builder()
                .id(UUID.fromString("33333333-3333-3333-3333-333333333333"))
                .name("Central")
                .code("CEN")
                .active(active)
                .build();
    }

    private static PasswordSetupTokenEntity token(Instant expiresAt, Instant usedAt) {
        return PasswordSetupTokenEntity.builder()
                .id(UUID.fromString("22222222-2222-2222-2222-222222222222"))
                .userId(USER_ID)
                .tokenHash(RefreshTokens.hash(RAW_TOKEN))
                .expiresAt(expiresAt)
                .usedAt(usedAt)
                .build();
    }

    private static void assertApiError(HttpStatus status, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
    }
}
