package com.urbantransport.identity.integration;

import com.urbantransport.identity.security.RefreshTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordSetupIT extends AbstractIdentityIT {

    private static final String NEW_PASSWORD = "ClaveNueva123!";

    @Test
    void driverAccount_cannotLoginUntilThePasswordIsSetFromTheLink() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.link@it.test", "fullName", "Chofer Link"));
        String envelope = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PasswordSetupRequested'",
                String.class, userId);
        String setupUrl = read(envelope, "$.payload.setupUrl");
        String token = setupToken(userId);

        assertThat(setupUrl).isEqualTo("http://localhost:4200/set-password?token=" + token);
        assertThat(read(envelope, "$.payload.email").toString()).isEqualTo("chofer.link@it.test");
        assertThat(read(envelope, "$.payload.role").toString()).isEqualTo("DRIVER");
        assertThat(count("SELECT count(*) FROM password_setup_tokens WHERE user_id = ? AND token_hash = ?",
                userId, RefreshTokens.hash(token))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM password_setup_tokens WHERE token_hash = ?", token)).isZero();
        send(post("/auth/login"), null, Map.of("email", "chofer.link@it.test", "password", NEW_PASSWORD))
                .andExpect(status().isUnauthorized());

        send(post("/auth/password-setup"), null, Map.of("token", token, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());

        assertThat(login("chofer.link@it.test", NEW_PASSWORD).access()).isNotBlank();
        assertThat(count("SELECT count(*) FROM password_setup_tokens WHERE user_id = ? AND used_at IS NOT NULL",
                userId)).isEqualTo(1);
    }

    @Test
    void customerAccount_receivesItsOwnLink() throws Exception {
        UUID userId = createInternalUser("customer", Map.of("email", "cliente.link@it.test",
                "fullName", "Cliente Link", "customerId", UUID.randomUUID(), "nit", "1234567-8"));

        setupPassword(userId, NEW_PASSWORD);

        assertThat(login("cliente.link@it.test", NEW_PASSWORD).access()).isNotBlank();
    }

    @Test
    void usedToken_cannotBeUsedAgain() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.reuso@it.test", "fullName", "Chofer"));
        String token = setupToken(userId);
        setupPassword(userId, NEW_PASSWORD);

        send(post("/auth/password-setup"), null, Map.of("token", token, "newPassword", "OtraClave123!"))
                .andExpect(status().isBadRequest());

        assertThat(login("chofer.reuso@it.test", NEW_PASSWORD).access()).isNotBlank();
    }

    @Test
    void expiredToken_returnsBadRequest() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.vencido@it.test", "fullName", "Chofer"));
        jdbc.update("UPDATE password_setup_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?", userId);

        send(post("/auth/password-setup"), null, Map.of("token", setupToken(userId), "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest());

        send(post("/auth/login"), null, Map.of("email", "chofer.vencido@it.test", "password", NEW_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveAccount_returnsConflict() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.inactivo@it.test", "fullName", "Chofer"));
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", userId);

        send(post("/auth/password-setup"), null, Map.of("token", setupToken(userId), "newPassword", NEW_PASSWORD))
                .andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM password_setup_tokens WHERE user_id = ? AND used_at IS NULL",
                userId)).isEqualTo(1);
    }

    @Test
    void unknownToken_returnsBadRequest() throws Exception {
        send(post("/auth/password-setup"), null, Map.of("token", "no-existe", "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidBody_returnsValidationError() throws Exception {
        send(post("/auth/password-setup"), null, Map.of("token", "", "newPassword", "corta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("newPassword")))
                .andExpect(jsonPath("$.message").value(containsString("token")));
    }

    @Test
    void staffCreatedByAdmin_receivesTheLinkInsteadOfAPassword() throws Exception {
        Branch branch = createBranch("PWD-01");
        String response = responseBody(send(post("/users"), adminToken(), Map.of("email", "cajero.link@it.test",
                "fullName", "Cajero Link", "role", "CASHIER", "branchId", branch.id()))
                .andExpect(status().isCreated()));
        UUID cashierId = UUID.fromString(read(response, "$.id"));

        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PasswordSetupRequested'",
                cashierId)).isEqualTo(1);
        send(post("/auth/login"), null, Map.of("email", "cajero.link@it.test", "password", NEW_PASSWORD))
                .andExpect(status().isUnauthorized());

        setupPassword(cashierId, NEW_PASSWORD);

        assertThat(login("cajero.link@it.test", NEW_PASSWORD).access()).isNotBlank();
    }

    @Test
    void resend_replacesThePreviousLinkWithANewOne() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.reenvio@it.test", "fullName", "Chofer"));
        String firstToken = setupToken(userId);

        send(post("/auth/password-setup/resend"), null, Map.of("email", "CHOFER.REENVIO@it.test"))
                .andExpect(status().isNoContent());

        String secondToken = setupToken(userId);
        assertThat(secondToken).isNotEqualTo(firstToken);
        String envelope = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id = ? AND "
                + "event_type = 'PasswordSetupRequested' ORDER BY created_at DESC LIMIT 1", String.class, userId);
        assertThat(read(envelope, "$.payload.purpose").toString()).isEqualTo("RESEND_REQUESTED");
        send(post("/auth/password-setup"), null, Map.of("token", firstToken, "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest());
        send(post("/auth/password-setup"), null, Map.of("token", secondToken, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(login("chofer.reenvio@it.test", NEW_PASSWORD).access()).isNotBlank();
    }

    @Test
    void resend_afterThePasswordWasSet_allowsChangingIt() throws Exception {
        UUID userId = createDriverUser("chofer.olvido@it.test");

        send(post("/auth/password-setup/resend"), null, Map.of("email", "chofer.olvido@it.test"))
                .andExpect(status().isNoContent());
        setupPassword(userId, NEW_PASSWORD);

        assertThat(login("chofer.olvido@it.test", NEW_PASSWORD).access()).isNotBlank();
        send(post("/auth/login"), null, Map.of("email", "chofer.olvido@it.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resend_unknownOrInactiveEmail_answersTheSameWithoutSendingAnything() throws Exception {
        UUID userId = createInternalUser("driver", Map.of("email", "chofer.baja@it.test", "fullName", "Chofer"));
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", userId);
        int before = count("SELECT count(*) FROM outbox_events WHERE event_type = 'PasswordSetupRequested'");

        send(post("/auth/password-setup/resend"), null, Map.of("email", "nadie@it.test"))
                .andExpect(status().isNoContent());
        send(post("/auth/password-setup/resend"), null, Map.of("email", "chofer.baja@it.test"))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'PasswordSetupRequested'"))
                .isEqualTo(before);
    }

    @Test
    void resend_invalidEmail_returnsValidationError() throws Exception {
        send(post("/auth/password-setup/resend"), null, Map.of("email", "no-es-correo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("email")));
    }

    private UUID createInternalUser(String type, Map<String, Object> body) throws Exception {
        String response = responseBody(mockMvc.perform(post("/internal/users/" + type)
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()));
        return UUID.fromString(read(response, "$.id"));
    }
}
