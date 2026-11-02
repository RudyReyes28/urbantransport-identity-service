package com.urbantransport.identity.integration;

import com.jayway.jsonpath.JsonPath;
import com.urbantransport.identity.domain.Role;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Levanta identity completo: PostgreSQL real embebido (Flyway aplica V1..V3), Kafka embebido y seguridad real.
 * Sin @Transactional a propósito: se quiere ver el comportamiento transaccional real de la aplicación.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                AbstractIdentityIT.CUSTOMER_TOPIC, AbstractIdentityIT.FLEET_TOPIC,
                AbstractIdentityIT.CUSTOMER_TOPIC + ".DLT", AbstractIdentityIT.FLEET_TOPIC + ".DLT"})
public abstract class AbstractIdentityIT {

    public static final String EVENTS_TOPIC = "identity-events-it";
    public static final String CUSTOMER_TOPIC = "customer-events-it";
    public static final String FLEET_TOPIC = "fleet-events-it";

    protected static final String ADMIN_EMAIL = "admin@it.test";
    protected static final String ADMIN_PASSWORD = "Admin12345!";
    protected static final String INTERNAL_KEY = "it-internal-key";
    protected static final String PASSWORD = "Password123!";

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    protected record Tokens(String access, String refresh) {
    }

    protected record Branch(UUID id, String code, String adminEmail, UUID adminId) {
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JsonMapper jsonMapper;
    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("identity.kafka.events-topic", () -> EVENTS_TOPIC);
        registry.add("identity.kafka.customer-events-topic", () -> CUSTOMER_TOPIC);
        registry.add("identity.kafka.fleet-events-topic", () -> FLEET_TOPIC);
        registry.add("identity.bootstrap-admin.email", () -> ADMIN_EMAIL);
        registry.add("identity.bootstrap-admin.password", () -> ADMIN_PASSWORD);
        registry.add("identity.internal.api-key", () -> INTERNAL_KEY);
    }

    /** Deja la base limpia, conservando al administrador inicial que crea AdminBootstrapRunner. */
    @BeforeEach
    protected void resetDatabase() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM password_setup_tokens");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("UPDATE users SET deactivated_by = NULL");
        jdbc.update("DELETE FROM users WHERE LOWER(email) <> ?", ADMIN_EMAIL);
        jdbc.update("DELETE FROM branches");
        jdbc.update("UPDATE users SET active = TRUE WHERE LOWER(email) = ?", ADMIN_EMAIL);
    }

    // ------------------------------------------------------------------ helpers HTTP

    protected ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    protected static String responseBody(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ helpers de datos

    protected Tokens login(String email, String password) throws Exception {
        String response = responseBody(send(post("/auth/login"), null, Map.of("email", email, "password", password))
                .andExpect(status().isOk()));
        return new Tokens(read(response, "$.accessToken"), read(response, "$.refreshToken"));
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD).access();
    }

    protected static Map<String, Object> branchRequest(String code, String adminEmail) {
        return Map.of(
                "name", "Sucursal " + code,
                "code", code,
                "address", "Zona 1",
                "department", "Quetzaltenango",
                "latitude", new BigDecimal("14.834700"),
                "longitude", new BigDecimal("-91.518000"),
                "admin", Map.of("email", adminEmail, "fullName", "Admin " + code));
    }

    /** Crea una sucursal (y su BRANCH_ADMIN) usando al SYSTEM_ADMIN. */
    protected Branch createBranch(String code) throws Exception {
        String adminEmail = "admin." + code.toLowerCase() + "@it.test";
        String response = responseBody(send(post("/branches"), adminToken(), branchRequest(code, adminEmail))
                .andExpect(status().isCreated()));
        UUID adminId = UUID.fromString(read(response, "$.admin.id"));
        setupPassword(adminId, PASSWORD);
        return new Branch(UUID.fromString(read(response, "$.branch.id")), code, adminEmail, adminId);
    }

    protected UUID createUser(String token, String email, Role role, UUID branchId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("fullName", "Usuario " + email);
        body.put("role", role.name());
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        String response = responseBody(send(post("/users"), token, body).andExpect(status().isCreated()));
        UUID userId = UUID.fromString(read(response, "$.id"));
        setupPassword(userId, PASSWORD);
        return userId;
    }

    protected UUID createCustomerUser(String email, UUID customerId, String nit) throws Exception {
        Map<String, Object> body = Map.of("email", email, "fullName", "Cliente " + email,
                "customerId", customerId, "nit", nit);
        String response = responseBody(mockMvc.perform(post("/internal/users/customer")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()));
        UUID userId = UUID.fromString(read(response, "$.id"));
        setupPassword(userId, PASSWORD);
        return userId;
    }

    protected UUID createDriverUser(String email) throws Exception {
        Map<String, Object> body = Map.of("email", email, "fullName", "Chofer " + email);
        String response = responseBody(mockMvc.perform(post("/internal/users/driver")
                        .header("X-Internal-Key", INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()));
        UUID userId = UUID.fromString(read(response, "$.id"));
        setupPassword(userId, PASSWORD);
        return userId;
    }

    protected String setupToken(UUID userId) {
        String envelope = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at DESC LIMIT 1",
                String.class, userId, "PasswordSetupRequested");
        String setupUrl = read(envelope, "$.payload.setupUrl");
        return setupUrl.substring(setupUrl.indexOf("token=") + "token=".length());
    }

    protected void setupPassword(UUID userId, String password) throws Exception {
        send(post("/auth/password-setup"), null, Map.of("token", setupToken(userId), "newPassword", password))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ PostgreSQL embebido

    private static EmbeddedPostgres startPostgres() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // se cierra al terminar la JVM
                }
            }));
            return postgres;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}