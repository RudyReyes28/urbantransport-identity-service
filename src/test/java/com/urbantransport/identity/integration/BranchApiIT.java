package com.urbantransport.identity.integration;

import com.urbantransport.identity.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BranchApiIT extends AbstractIdentityIT {

    @Test
    @DisplayName("crear sucursal: normaliza el código, crea su BRANCH_ADMIN y registra BranchCreated y UserCreated en el outbox")
    void createBranchCreatesAdminAndOutboxEvents() throws Exception {
        String response = responseBody(send(post("/branches"), adminToken(),
                branchRequest("occ-01", "admin.occ@it.test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branch.code").value("OCC-01"))
                .andExpect(jsonPath("$.branch.active").value(true))
                .andExpect(jsonPath("$.admin.role").value("BRANCH_ADMIN")));
        UUID branchId = UUID.fromString(read(response, "$.branch.id"));
        UUID adminId = UUID.fromString(read(response, "$.admin.id"));

        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, adminId))
                .startsWith("$2");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BranchCreated'",
                branchId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'UserCreated'",
                adminId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PasswordSetupRequested'",
                adminId)).isEqualTo(1);
        setupPassword(adminId, PASSWORD);
        login("admin.occ@it.test", PASSWORD);
    }

    @Test
    @DisplayName("código duplicado (sin distinguir mayúsculas) -> 409 y no se crea nada")
    void duplicateCodeIsRejected() throws Exception {
        createBranch("DUP-01");

        send(post("/branches"), adminToken(), branchRequest("dup-01", "otro.admin@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe una sucursal con el código DUP-01"));

        assertThat(count("SELECT count(*) FROM branches")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE email = 'otro.admin@it.test'")).isZero();
    }

    @Test
    @DisplayName("correo de administrador ya usado -> 409 y la sucursal no queda a medias")
    void duplicateAdminEmailLeavesNoPartialBranch() throws Exception {
        Branch existing = createBranch("EML-01");

        send(post("/branches"), adminToken(), branchRequest("EML-02", existing.adminEmail().toUpperCase()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ya existe un usuario con el correo " + existing.adminEmail()));
        send(post("/branches"), adminToken(), branchRequest("EML-03", ADMIN_EMAIL))
                .andExpect(status().isConflict());

        assertThat(count("SELECT count(*) FROM branches WHERE code IN ('EML-02', 'EML-03')")).isZero();
    }

    @Test
    @DisplayName("validación: código inválido, latitud fuera de rango y admin ausente -> 400")
    void createBranchValidation() throws Exception {
        Map<String, Object> body = new HashMap<>(branchRequest("X-01", "x@it.test"));
        body.put("code", "código inválido!");
        body.put("latitude", new BigDecimal("95"));
        body.remove("admin");

        String response = responseBody(send(post("/branches"), adminToken(), body)
                .andExpect(status().isBadRequest()));

        assertThat((String) read(response, "$.message")).contains("code").contains("latitude").contains("admin");
    }

    @Test
    @DisplayName("listado: el SYSTEM_ADMIN filtra por estado; el resto solo ve sucursales activas")
    void listFiltersByStatusAndHidesInactiveFromNonAdmins() throws Exception {
        Branch a = createBranch("LST-A");
        Branch b = createBranch("LST-B");
        send(patch("/branches/" + b.id() + "/deactivate"), adminToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        send(get("/branches"), adminToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("LST-A"));   // ordenadas por nombre
        send(get("/branches").param("active", "false"), adminToken(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("LST-B"));
        send(get("/branches").param("active", "true"), adminToken(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("LST-A"));

        // Un BRANCH_ADMIN pidiendo active=false no ve las inactivas
        String branchAdmin = login(a.adminEmail(), PASSWORD).access();
        send(get("/branches").param("active", "false"), branchAdmin, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("LST-A"));
    }

    @Test
    @DisplayName("consultar por id: existente 200, inexistente 404")
    void findById() throws Exception {
        Branch branch = createBranch("GET-01");

        send(get("/branches/" + branch.id()), adminToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("GET-01"));
        send(get("/branches/" + UUID.randomUUID()), adminToken(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sucursal no encontrada"));
    }

    @Test
    @DisplayName("actualizar sucursal: cambia los datos, conserva el código y emite BranchUpdated")
    void updateBranch() throws Exception {
        Branch branch = createBranch("UPD-01");
        Map<String, Object> body = Map.of("name", "Nuevo Nombre", "address", "Nueva dirección",
                "department", "Huehuetenango", "latitude", new BigDecimal("15.500000"),
                "longitude", new BigDecimal("-91.000000"));

        send(put("/branches/" + branch.id()), adminToken(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nuevo Nombre"))
                .andExpect(jsonPath("$.code").value("UPD-01"))
                .andExpect(jsonPath("$.department").value("Huehuetenango"));

        assertThat(jdbc.queryForObject("SELECT name FROM branches WHERE id = ?", String.class, branch.id()))
                .isEqualTo("Nuevo Nombre");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BranchUpdated'",
                branch.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("activar y desactivar emiten BranchUpdated con el estado nuevo")
    void activateAndDeactivateEmitEvents() throws Exception {
        Branch branch = createBranch("ACT-01");

        send(patch("/branches/" + branch.id() + "/deactivate"), adminToken(), null).andExpect(status().isOk());
        assertThat(latestBranchEventActive(branch.id())).isFalse();

        send(patch("/branches/" + branch.id() + "/activate"), adminToken(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(latestBranchEventActive(branch.id())).isTrue();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'BranchUpdated'",
                branch.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("sucursal desactivada: su personal no puede iniciar sesión, refrescar ni usar /auth/me")
    void deactivatedBranchBlocksItsStaff() throws Exception {
        Branch branch = createBranch("BLK-01");
        createUser(adminToken(), "cajero.blk@it.test", Role.CASHIER, branch.id());
        Tokens tokens = login("cajero.blk@it.test", PASSWORD);

        send(patch("/branches/" + branch.id() + "/deactivate"), adminToken(), null).andExpect(status().isOk());

        send(post("/auth/login"), null, Map.of("email", "cajero.blk@it.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh()))
                .andExpect(status().isUnauthorized());
        send(get("/auth/me"), tokens.access(), null).andExpect(status().isUnauthorized());
        assertThat(count("""
                SELECT count(*) FROM refresh_tokens r JOIN users u ON u.id = r.user_id
                WHERE u.email = 'cajero.blk@it.test' AND r.revoked = FALSE""")).isZero();

        send(patch("/branches/" + branch.id() + "/activate"), adminToken(), null).andExpect(status().isOk());
        login("cajero.blk@it.test", PASSWORD);
    }

    private boolean latestBranchEventActive(UUID branchId) {
        String payload = jdbc.queryForObject("""
                SELECT payload FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'BranchUpdated'
                ORDER BY created_at DESC LIMIT 1""", String.class, branchId);
        Boolean active = read(payload, "$.payload.active");
        return Boolean.TRUE.equals(active);
    }
}