package com.urbantransport.identity.integration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.urbantransport.identity.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends AbstractIdentityIT {

    private static final UUID SOME_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private JwtEncoder jwtEncoder;

    // ------------------------------------------------------------------ rutas públicas y protegidas

    @ParameterizedTest
    @ValueSource(strings = {"/auth/public-key", "/.well-known/jwks.json", "/actuator/health", "/v3/api-docs"})
    @DisplayName("rutas públicas responden sin token")
    void publicEndpointsDoNotNeedAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin token, toda ruta protegida responde 401")
    void anonymousRequestsAreRejected() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/auth/me"),
                get("/branches"),
                get("/branches/" + SOME_ID),
                get("/users"),
                get("/users/" + SOME_ID),
                post("/branches"),
                post("/users"),
                put("/users/" + SOME_ID),
                patch("/users/" + SOME_ID + "/deactivate"),
                patch("/branches/" + SOME_ID + "/activate"),
                post("/auth/change-password"),
                post("/auth/logout"));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------ tokens inválidos

    @Test
    @DisplayName("token basura o con algoritmo 'none' -> 401")
    void garbageAndUnsignedTokensAreRejected() throws Exception {
        send(get("/auth/me"), "no.es.un-jwt", null).andExpect(status().isUnauthorized());
        send(get("/auth/me"), "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.", null).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token firmado con otra llave -> 401")
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).keyID("otra").generate();
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign)));

        String forged = token(foreignEncoder, SOME_ID, "SYSTEM_ADMIN", Instant.now().plusSeconds(600));

        send(get("/auth/me"), forged, null).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token vencido (firmado con la llave correcta) -> 401")
    void expiredTokenIsRejected() throws Exception {
        // Spring Security tolera 60 s de desfase de reloj, por eso se vence con 10 minutos de margen
        String expired = token(jwtEncoder, SOME_ID, "SYSTEM_ADMIN", Instant.now().minusSeconds(600));

        send(get("/auth/me"), expired, null).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ roles

    @Test
    @DisplayName("matriz de roles sobre sucursales y usuarios")
    void roleMatrix() throws Exception {
        Branch branch = createBranch("SEC-01");
        createUser(adminToken(), "cajero.sec@it.test", Role.CASHIER, branch.id());
        String branchAdmin = login(branch.adminEmail(), PASSWORD).access();
        String cashier = login("cajero.sec@it.test", PASSWORD).access();
        createCustomerUser("cliente.sec@it.test", UUID.randomUUID(), "1234567-8");
        String customer = login("cliente.sec@it.test", PASSWORD).access();
        createDriverUser("chofer.sec@it.test");
        String driver = login("chofer.sec@it.test", PASSWORD).access();

        // Crear sucursales: solo SYSTEM_ADMIN
        for (String token : List.of(branchAdmin, cashier, customer, driver)) {
            send(post("/branches"), token, branchRequest("SEC-99", "otro.admin@it.test"))
                    .andExpect(status().isForbidden());
        }
        // Listar usuarios: SYSTEM_ADMIN y BRANCH_ADMIN
        send(get("/users"), branchAdmin, null).andExpect(status().isOk());
        for (String token : List.of(cashier, customer, driver)) {
            send(get("/users"), token, null).andExpect(status().isForbidden());
        }
        // Consultar sucursales: cualquier usuario autenticado
        for (String token : List.of(branchAdmin, cashier, customer, driver)) {
            send(get("/branches"), token, null).andExpect(status().isOk());
        }
        // Nadie sin SYSTEM_ADMIN modifica sucursales
        send(put("/branches/" + branch.id()), branchAdmin,
                Map.of("name", "Nuevo", "latitude", 14.8, "longitude", -91.5))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ /internal/**

    @Test
    @DisplayName("/internal/** exige la clave compartida: sin clave 401, con JWT de usuario 403, clave errónea 401")
    void internalEndpointsRequireTheSharedKey() throws Exception {
        Map<String, Object> body = Map.of("email", "interno@it.test", "password", PASSWORD,
                "fullName", "Cliente Interno", "customerId", UUID.randomUUID(), "nit", "1234567-8");
        String url = "/internal/users/customer";

        send(post(url), null, body).andExpect(status().isUnauthorized());

        send(post(url).header("X-Internal-Key", "clave-incorrecta"), null, body)
                .andExpect(status().isUnauthorized());

        send(post(url), adminToken(), body).andExpect(status().isForbidden());

        send(post(url).header("X-Internal-Key", INTERNAL_KEY), null, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    // ------------------------------------------------------------------ correlation id

    @Test
    @DisplayName("X-Correlation-Id: se genera si no viene y se respeta si viene")
    void correlationIdIsGeneratedOrEchoed() throws Exception {
        String generated = mockMvc.perform(get("/auth/public-key"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(generated).isNotBlank();

        mockMvc.perform(get("/auth/public-key").header("X-Correlation-Id", "corr-123"))
                .andExpect(header().string("X-Correlation-Id", "corr-123"));
    }

    // ------------------------------------------------------------------ helper

    private static String token(JwtEncoder encoder, UUID subject, String role, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("identity-it")
                .subject(subject.toString())
                .issuedAt(Instant.now().minusSeconds(3600))
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}