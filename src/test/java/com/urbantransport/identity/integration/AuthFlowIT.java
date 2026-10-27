package com.urbantransport.identity.integration;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.security.RefreshTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends AbstractIdentityIT {

    private static final String INVALID_CREDENTIALS = "Credenciales inválidas";
    private static final String INVALID_REFRESH = "Refresh token inválido o vencido";

    @Autowired
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ login

    @Test
    @DisplayName("login devuelve tokens y guarda solo el hash del refresh token")
    void loginReturnsTokensAndStoresOnlyTheHash() throws Exception {
        String response = responseBody(send(post("/auth/login"), null,
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.role").value("SYSTEM_ADMIN")));

        String refresh = read(response, "$.refreshToken");
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", RefreshTokens.hash(refresh)))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", refresh)).isZero();
    }

    @Test
    @DisplayName("el correo no distingue mayúsculas")
    void loginIgnoresEmailCase() throws Exception {
        send(post("/auth/login"), null, Map.of("email", "ADMIN@IT.TEST", "password", ADMIN_PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("contraseña errónea y usuario inexistente responden igual (sin enumeración de usuarios)")
    void badCredentialsAreIndistinguishable() throws Exception {
        send(post("/auth/login"), null, Map.of("email", ADMIN_EMAIL, "password", "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS));
        send(post("/auth/login"), null, Map.of("email", "nadie@it.test", "password", "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("un usuario desactivado no puede iniciar sesión")
    void inactiveUserCannotLogin() throws Exception {
        Branch branch = createBranch("INA-01");
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", branch.adminId());

        send(post("/auth/login"), null, Map.of("email", branch.adminEmail(), "password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("validación: correo inválido y contraseña vacía -> 400 con los campos en el mensaje")
    void loginValidation() throws Exception {
        String response = responseBody(send(post("/auth/login"), null,
                Map.of("email", "no-es-correo", "password", ""))
                .andExpect(status().isBadRequest()));

        String message = read(response, "$.message");
        assertThat(message).contains("email").contains("password");
    }

    @Test
    @DisplayName("JSON mal formado -> 400 con el mensaje del GlobalExceptionHandler")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{no-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cuerpo de la petición inválido"))
                .andExpect(jsonPath("$.path").value("/auth/login"));
    }

    // ------------------------------------------------------------------ contenido del token y llave pública

    @Test
    @DisplayName("el access token trae los claims que usan el gateway y los demás servicios")
    void accessTokenClaims() throws Exception {
        Branch branch = createBranch("CLM-01");

        Jwt jwt = jwtDecoder.decode(login(branch.adminEmail(), PASSWORD).access());

        assertThat(jwt.getSubject()).isEqualTo(branch.adminId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("BRANCH_ADMIN");
        assertThat(jwt.getClaimAsString("branchId")).isEqualTo(branch.id().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo(branch.adminEmail());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("identity-it");
        assertThat(jwt.getExpiresAt()).isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(16 * 60));
    }

    @Test
    @DisplayName("/auth/public-key publica (en PEM) la llave que verifica los tokens, como la consume el gateway")
    void publicKeyVerifiesIssuedTokens() throws Exception {
        String pem = responseBody(mockMvc.perform(get("/auth/public-key"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)));
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----").contains("-----END PUBLIC KEY-----");

        Jwt verified = NimbusJwtDecoder.withPublicKey(parsePem(pem)).build().decode(login(ADMIN_EMAIL, ADMIN_PASSWORD).access());

        assertThat(verified.getClaimAsStringList("roles")).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("/.well-known/jwks.json expone solo la parte pública de la llave")
    void jwksExposesOnlyThePublicKey() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }

    @Test
    @DisplayName("el kid del JWKS coincide con el kid del header de los tokens (resource servers con jwk-set-uri)")
    void jwksKidMatchesTokenHeader() throws Exception {
        String jwks = responseBody(mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()));
        Jwt jwt = jwtDecoder.decode(adminToken());

        assertThat(jwt.getHeaders()).containsEntry("kid", read(jwks, "$.keys[0].kid"));
    }

    // ------------------------------------------------------------------ refresh y logout

    @Test
    @DisplayName("refresh rota el token: el anterior deja de servir")
    void refreshRotatesTheToken() throws Exception {
        Tokens first = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        String response = responseBody(send(post("/auth/refresh"), null, Map.of("refreshToken", first.refresh()))
                .andExpect(status().isOk()));

        assertThat((String) read(response, "$.refreshToken")).isNotEqualTo(first.refresh());
        send(post("/auth/refresh"), null, Map.of("refreshToken", first.refresh()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_REFRESH));
    }

    @Test
    @DisplayName("reutilizar un refresh token revocado revoca TODAS las sesiones, y eso queda guardado pese al 401")
    void refreshTokenReuseRevokesEverySession() throws Exception {
        Tokens first = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        Tokens otherSession = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String rotated = read(responseBody(send(post("/auth/refresh"), null, Map.of("refreshToken", first.refresh()))
                .andExpect(status().isOk())), "$.refreshToken");

        // Reutilización del token ya rotado
        send(post("/auth/refresh"), null, Map.of("refreshToken", first.refresh())).andExpect(status().isUnauthorized());

        // Ninguna sesión sobrevive: ni la otra, ni la recién rotada
        send(post("/auth/refresh"), null, Map.of("refreshToken", otherSession.refresh())).andExpect(status().isUnauthorized());
        send(post("/auth/refresh"), null, Map.of("refreshToken", rotated)).andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE revoked = FALSE")).isZero();
    }

    @Test
    @DisplayName("refresh token vencido o inventado -> 401")
    void expiredOrUnknownRefreshToken() throws Exception {
        Tokens tokens = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 hour'");

        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(INVALID_REFRESH));
        send(post("/auth/refresh"), null, Map.of("refreshToken", "inventado"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(INVALID_REFRESH));
    }

    @Test
    @DisplayName("logout (con access token) revoca el refresh token; repetirlo no falla")
    void logoutRevokesTheRefreshToken() throws Exception {
        Tokens tokens = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        send(post("/auth/logout"), tokens.access(), Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isNoContent());
        send(post("/auth/logout"), tokens.access(), Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isNoContent());
        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout sin access token -> 401 (hoy exige Bearer)")
    void logoutWithoutAccessTokenIsRejected() throws Exception {
        Tokens tokens = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        send(post("/auth/logout"), null, Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ /me y cambio de contraseña

    @Test
    @DisplayName("/auth/me devuelve al usuario y rechaza cuentas desactivadas aunque el JWT siga vigente")
    void meChecksTheAccountStatus() throws Exception {
        Branch branch = createBranch("ME-01");
        String access = login(branch.adminEmail(), PASSWORD).access();

        send(get("/auth/me"), access, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(branch.adminEmail()))
                .andExpect(jsonPath("$.role").value("BRANCH_ADMIN"))
                .andExpect(jsonPath("$.branchId").value(branch.id().toString()));

        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", branch.adminId());

        send(get("/auth/me"), access, null).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("cambiar contraseña: valida la actual, cambia la clave y revoca las sesiones")
    void changePassword() throws Exception {
        Branch branch = createBranch("PWD-01");
        createUser(adminToken(), "cajero.pwd@it.test", Role.CASHIER, branch.id());
        Tokens tokens = login("cajero.pwd@it.test", PASSWORD);

        send(post("/auth/change-password"), tokens.access(),
                Map.of("currentPassword", "incorrecta", "newPassword", "NuevaClave123!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La contraseña actual es incorrecta"));

        send(post("/auth/change-password"), tokens.access(),
                Map.of("currentPassword", PASSWORD, "newPassword", "NuevaClave123!"))
                .andExpect(status().isNoContent());

        send(post("/auth/login"), null, Map.of("email", "cajero.pwd@it.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        login("cajero.pwd@it.test", "NuevaClave123!");
        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private static RSAPublicKey parsePem(String pem) throws Exception {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}