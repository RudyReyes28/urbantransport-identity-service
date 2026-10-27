package com.urbantransport.identity.integration;

import com.urbantransport.identity.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserApiIT extends AbstractIdentityIT {

    private Branch branchA;
    private Branch branchB;
    private String admin;     // SYSTEM_ADMIN
    private String adminOfA;  // BRANCH_ADMIN de la sucursal A

    @BeforeEach
    void createFixtures() throws Exception {
        branchA = createBranch("USR-A");
        branchB = createBranch("USR-B");
        admin = adminToken();
        adminOfA = login(branchA.adminEmail(), PASSWORD).access();
    }

    // ------------------------------------------------------------------ crear

    @Test
    @DisplayName("el SYSTEM_ADMIN crea personal de cualquier rol; el correo se normaliza y la clave se guarda con BCrypt")
    void systemAdminCreatesStaff() throws Exception {
        String response = responseBody(send(post("/users"), admin,
                userBody("Cajero.Mixto@IT.Test", Role.CASHIER, branchA.id()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("cajero.mixto@it.test"))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.branchId").value(branchA.id().toString()))
                .andExpect(jsonPath("$.active").value(true)));

        UUID id = UUID.fromString(read(response, "$.id"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id))
                .startsWith("$2");
        createUser(admin, "admin.b2@it.test", Role.BRANCH_ADMIN, branchB.id());
        createUser(admin, "chofer.nuevo@it.test", Role.DRIVER, null);
        createUser(admin, "admin.sistema2@it.test", Role.SYSTEM_ADMIN, null);
    }

    @Test
    @DisplayName("correo duplicado (sin distinguir mayúsculas) -> 409")
    void duplicateEmailIsRejected() throws Exception {
        createUser(admin, "cajero.dup@it.test", Role.CASHIER, branchA.id());

        send(post("/users"), admin, userBody("CAJERO.DUP@it.test", Role.CASHIER, branchA.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe un usuario con el correo cajero.dup@it.test"));
    }

    @Test
    @DisplayName("reglas de creación: clientes, sucursal obligatoria, sucursal que no aplica, inexistente o inactiva")
    void creationRules() throws Exception {
        send(post("/users"), admin, userBody("cli@it.test", Role.CUSTOMER, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los clientes se registran desde customer-service"));
        send(post("/users"), admin, userBody("sin.suc@it.test", Role.CASHIER, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("branchId es obligatorio para CASHIER"));
        send(post("/users"), admin, userBody("chofer.suc@it.test", Role.DRIVER, branchA.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("branchId solo aplica a BRANCH_ADMIN y CASHIER"));
        send(post("/users"), admin, userBody("fantasma@it.test", Role.CASHIER, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sucursal no encontrada"));

        send(patch("/branches/" + branchB.id() + "/deactivate"), admin, null).andExpect(status().isOk());
        send(post("/users"), admin, userBody("inactiva@it.test", Role.CASHIER, branchB.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La sucursal está inactiva"));
    }

    @Test
    @DisplayName("un BRANCH_ADMIN solo crea cajeros de su propia sucursal")
    void branchAdminCreatesOnlyOwnCashiers() throws Exception {
        // Sin branchId: se le asigna la suya
        send(post("/users"), adminOfA, userBody("cajero.a1@it.test", Role.CASHIER, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branchId").value(branchA.id().toString()));

        send(post("/users"), adminOfA, userBody("cajero.b1@it.test", Role.CASHIER, branchB.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Solo puedes crear personal en tu sucursal"));
        send(post("/users"), adminOfA, userBody("admin.a2@it.test", Role.BRANCH_ADMIN, branchA.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Solo puedes crear cajeros para tu sucursal"));
    }

    // ------------------------------------------------------------------ búsqueda

    @Test
    @DisplayName("búsqueda: filtros, texto libre, orden, paginación y límites de tamaño")
    void searchFiltersAndPagination() throws Exception {
        createUser(admin, "ana.srch@it.test", Role.CASHIER, branchA.id());
        UUID beto = createUser(admin, "beto.srch@it.test", Role.CASHIER, branchA.id());
        createUser(admin, "carla.srch@it.test", Role.CASHIER, branchA.id());
        String a = branchA.id().toString();

        // Paginación, orden por creación descendente
        send(get("/users").param("role", "CASHIER").param("branchId", a).param("size", "2"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].email").value("carla.srch@it.test"));
        send(get("/users").param("role", "CASHIER").param("branchId", a).param("size", "2").param("page", "1"),
                admin, null)
                .andExpect(jsonPath("$.content.length()").value(1));

        // Texto libre (correo o nombre, sin distinguir mayúsculas)
        send(get("/users").param("q", "BETO"), admin, null)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("beto.srch@it.test"));
        send(get("/users").param("q", "srch"), admin, null).andExpect(jsonPath("$.totalElements").value(3));

        // Estado
        send(patch("/users/" + beto + "/deactivate"), admin, null).andExpect(status().isOk());
        send(get("/users").param("active", "false").param("branchId", a), admin, null)
                .andExpect(jsonPath("$.totalElements").value(1));
        send(get("/users").param("active", "true").param("role", "CASHIER").param("branchId", a), admin, null)
                .andExpect(jsonPath("$.totalElements").value(2));

        // Límites del tamaño de página
        send(get("/users").param("size", "1000"), admin, null).andExpect(jsonPath("$.size").value(100));
        send(get("/users").param("size", "0"), admin, null).andExpect(jsonPath("$.size").value(1));
        send(get("/users").param("page", "-3"), admin, null).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @DisplayName("un BRANCH_ADMIN solo ve su sucursal aunque pida otra")
    void branchAdminSearchIsScopedToItsBranch() throws Exception {
        createUser(admin, "cajero.a@it.test", Role.CASHIER, branchA.id());
        createUser(admin, "cajero.b@it.test", Role.CASHIER, branchB.id());

        String response = responseBody(send(get("/users").param("branchId", branchB.id().toString()), adminOfA, null)
                .andExpect(status().isOk()));

        List<String> branchIds = read(response, "$.content[*].branchId");
        assertThat(branchIds).isNotEmpty().containsOnly(branchA.id().toString());
    }

    // ------------------------------------------------------------------ consultar y actualizar

    @Test
    @DisplayName("consultar por id respeta el alcance: otra sucursal responde como si no existiera")
    void findByIdRespectsScope() throws Exception {
        UUID ownCashier = createUser(admin, "propio@it.test", Role.CASHIER, branchA.id());
        UUID foreignCashier = createUser(admin, "ajeno@it.test", Role.CASHIER, branchB.id());

        send(get("/users/" + ownCashier), adminOfA, null).andExpect(status().isOk());
        send(get("/users/" + foreignCashier), adminOfA, null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Usuario no encontrado"));
        send(get("/users/" + foreignCashier), admin, null).andExpect(status().isOk());
        send(get("/users/" + UUID.randomUUID()), admin, null).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("actualizar: cambia nombre y correo; correo ajeno -> 409; clientes y otros admins no se gestionan aquí")
    void updateUser() throws Exception {
        UUID cashier = createUser(admin, "cajero.upd@it.test", Role.CASHIER, branchA.id());
        createUser(admin, "otro.upd@it.test", Role.CASHIER, branchA.id());

        send(put("/users/" + cashier), admin, Map.of("email", "Nuevo.Correo@IT.test", "fullName", "  Nombre Nuevo  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("nuevo.correo@it.test"))
                .andExpect(jsonPath("$.fullName").value("Nombre Nuevo"));
        // Su propio correo con otras mayúsculas no es un conflicto
        send(put("/users/" + cashier), admin, Map.of("email", "NUEVO.CORREO@it.test", "fullName", "Nombre Nuevo"))
                .andExpect(status().isOk());
        send(put("/users/" + cashier), admin, Map.of("email", "otro.upd@it.test", "fullName", "Nombre Nuevo"))
                .andExpect(status().isConflict());

        UUID customer = createCustomerUser("cliente.upd@it.test", UUID.randomUUID(), "1234567-8");
        send(put("/users/" + customer), admin, Map.of("email", "x@it.test", "fullName", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los clientes no se gestionan desde este endpoint"));

        UUID secondBranchAdmin = createUser(admin, "admin.a2.upd@it.test", Role.BRANCH_ADMIN, branchA.id());
        send(put("/users/" + secondBranchAdmin), adminOfA, Map.of("email", "y@it.test", "fullName", "Y"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Solo puedes gestionar a los cajeros de tu sucursal"));
    }

    // ------------------------------------------------------------------ activar / desactivar

    @Test
    @DisplayName("desactivar: bloquea el acceso, revoca sus sesiones y registra quién lo hizo; activar lo revierte")
    void deactivateAndActivate() throws Exception {
        UUID cashier = createUser(admin, "cajero.off@it.test", Role.CASHIER, branchA.id());
        Tokens tokens = login("cajero.off@it.test", PASSWORD);
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE LOWER(email) = ?", UUID.class, ADMIN_EMAIL);

        send(patch("/users/" + cashier + "/deactivate"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // Repetirlo es idempotente
        send(patch("/users/" + cashier + "/deactivate"), admin, null).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT deactivated_by FROM users WHERE id = ?", UUID.class, cashier))
                .isEqualTo(adminId);
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked = FALSE", cashier)).isZero();
        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh())).andExpect(status().isUnauthorized());
        send(post("/auth/login"), null, Map.of("email", "cajero.off@it.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());

        send(patch("/users/" + cashier + "/activate"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT deactivated_by FROM users WHERE id = ?", UUID.class, cashier)).isNull();
        login("cajero.off@it.test", PASSWORD);
    }

    @Test
    @DisplayName("nadie puede desactivar su propia cuenta")
    void selfDeactivationIsRejected() throws Exception {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE LOWER(email) = ?", UUID.class, ADMIN_EMAIL);

        send(patch("/users/" + adminId + "/deactivate"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No puedes desactivar tu propia cuenta"));
    }

    @Test
    @DisplayName("la sucursal siempre conserva al menos un BRANCH_ADMIN activo")
    void branchKeepsAtLeastOneAdmin() throws Exception {
        UUID second = createUser(admin, "admin.a2.keep@it.test", Role.BRANCH_ADMIN, branchA.id());

        send(patch("/users/" + branchA.adminId() + "/deactivate"), admin, null).andExpect(status().isOk());
        send(patch("/users/" + second + "/deactivate"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La sucursal debe conservar al menos un BRANCH_ADMIN activo"));
    }

    @Test
    @DisplayName("dos desactivaciones simultáneas no pueden dejar la sucursal sin administrador (bloqueo pesimista real)")
    void concurrentDeactivationsCannotLeaveBranchWithoutAdmin() throws Exception {
        UUID second = createUser(admin, "admin.a2.race@it.test", Role.BRANCH_ADMIN, branchA.id());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (UUID target : List.of(branchA.adminId(), second)) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await();
                return send(patch("/users/" + target + "/deactivate"), admin, null)
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("SELECT count(*) FROM users WHERE branch_id = ? AND role = 'BRANCH_ADMIN' AND active = TRUE",
                branchA.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ restablecer contraseña

    @Test
    @DisplayName("restablecer contraseña: cambia la clave, revoca sesiones, valida el largo y respeta el alcance")
    void resetPassword() throws Exception {
        UUID cashierA = createUser(admin, "cajero.rst@it.test", Role.CASHIER, branchA.id());
        UUID cashierB = createUser(admin, "cajero.rst.b@it.test", Role.CASHIER, branchB.id());
        Tokens tokens = login("cajero.rst@it.test", PASSWORD);

        send(patch("/users/" + cashierA + "/password"), admin, Map.of("newPassword", "corta"))
                .andExpect(status().isBadRequest());
        send(patch("/users/" + cashierB + "/password"), adminOfA, Map.of("newPassword", "NuevaClave123!"))
                .andExpect(status().isNotFound());

        send(patch("/users/" + cashierA + "/password"), adminOfA, Map.of("newPassword", "NuevaClave123!"))
                .andExpect(status().isNoContent());

        send(post("/auth/login"), null, Map.of("email", "cajero.rst@it.test", "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        login("cajero.rst@it.test", "NuevaClave123!");
        send(post("/auth/refresh"), null, Map.of("refreshToken", tokens.refresh())).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helper

    private static Map<String, Object> userBody(String email, Role role, UUID branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("fullName", "Persona " + email);
        body.put("role", role.name());
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        return body;
    }
}