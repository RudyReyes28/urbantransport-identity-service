package com.urbantransport.identity.config;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea el primer SYSTEM_ADMIN si no existe ninguno activo. La contraseña viene del entorno, nunca del SQL. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final BootstrapAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.countByRoleAndActiveTrue(Role.SYSTEM_ADMIN) > 0) {
            return;
        }
        if (properties.password() == null || properties.password().isBlank()) {
            log.warn("No hay SYSTEM_ADMIN y BOOTSTRAP_ADMIN_PASSWORD está vacío: no se creó el administrador inicial");
            return;
        }
        if (userRepository.existsByEmailIgnoreCase(properties.email())) {
            log.warn("No hay SYSTEM_ADMIN activo, pero el correo {} ya existe con otro rol o inactivo", properties.email());
            return;
        }

        UserEntity admin = UserEntity.builder()
                .email(properties.email().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(properties.password()))
                .fullName(properties.fullName())
                .role(Role.SYSTEM_ADMIN)
                .build();
        userRepository.save(admin);
        log.info("SYSTEM_ADMIN inicial creado: {}", admin.getEmail());
    }
}