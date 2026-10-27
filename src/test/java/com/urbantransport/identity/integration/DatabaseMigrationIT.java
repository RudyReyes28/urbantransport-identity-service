package com.urbantransport.identity.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationIT extends AbstractIdentityIT {

    @Test
    @DisplayName("Flyway aplicó todas las migraciones sin errores")
    void migrationsAreApplied() {
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success = FALSE")).isZero();
    }

    @Test
    @DisplayName("el SYSTEM_ADMIN inicial existe una sola vez y puede iniciar sesión")
    void bootstrapAdminExistsOnceAndCanLogin() throws Exception {
        assertThat(count("SELECT count(*) FROM users WHERE role = 'SYSTEM_ADMIN' AND LOWER(email) = ?", ADMIN_EMAIL))
                .isEqualTo(1);
        assertThat(login(ADMIN_EMAIL, ADMIN_PASSWORD).access()).isNotBlank();
    }

    @Test
    @DisplayName("un CASHIER sin sucursal viola ck_users_branch")
    void cashierWithoutBranchIsRejected() {
        assertViolation(() -> insertUser("sin.sucursal@it.test", "CASHIER", null, null, null));
    }

    @Test
    @DisplayName("un CUSTOMER sin customer_id o sin NIT viola las restricciones")
    void customerNeedsCustomerIdAndNit() {
        assertViolation(() -> insertUser("c1@it.test", "CUSTOMER", null, null, "123-4"));
        assertViolation(() -> insertUser("c2@it.test", "CUSTOMER", null, UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("un rol fuera del catálogo viola ck_users_role")
    void unknownRoleIsRejected() {
        assertViolation(() -> insertUser("rol@it.test", "HACKER", null, null, null));
    }

    @Test
    @DisplayName("el correo es único sin distinguir mayúsculas (índice sobre LOWER(email))")
    void emailIsCaseInsensitiveUnique() {
        insertUser("Duplicado@IT.test", "DRIVER", null, null, null);

        assertViolation(() -> insertUser("duplicado@it.test", "DRIVER", null, null, null));
    }

    @Test
    @DisplayName("un customer_id solo puede tener una cuenta (índice parcial)")
    void customerIdIsUnique() {
        UUID customerId = UUID.randomUUID();
        insertUser("cliente1@it.test", "CUSTOMER", null, customerId, "111-1");

        assertViolation(() -> insertUser("cliente2@it.test", "CUSTOMER", null, customerId, "222-2"));
    }

    @Test
    @DisplayName("el código de sucursal es único")
    void branchCodeIsUnique() {
        jdbc.update("INSERT INTO branches (id, name, code, latitude, longitude) VALUES (?, 'A', 'DUP-01', 14.8, -91.5)",
                UUID.randomUUID());

        assertViolation(() -> jdbc.update(
                "INSERT INTO branches (id, name, code, latitude, longitude) VALUES (?, 'B', 'DUP-01', 14.8, -91.5)",
                UUID.randomUUID()));
    }

    private void insertUser(String email, String role, UUID branchId, UUID customerId, String nit) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, branch_id, customer_id, nit)
                VALUES (?, ?, 'hash', 'Nombre', ?, ?, ?, ?)""",
                UUID.randomUUID(), email, role, branchId, customerId, nit);
    }

    private static void assertViolation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}