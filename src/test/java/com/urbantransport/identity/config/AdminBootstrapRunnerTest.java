package com.urbantransport.identity.config;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldDoNothingWhenActiveSystemAdminAlreadyExists() {
        BootstrapAdminProperties props = new BootstrapAdminProperties("admin@test.com", "secret", "Admin");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, userRepository, passwordEncoder);

        when(userRepository.countByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(1L);

        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldNotCreateAdminWhenPasswordIsNullOrBlank() {
        when(userRepository.countByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(0L);

        AdminBootstrapRunner nullPassRunner = new AdminBootstrapRunner(
                new BootstrapAdminProperties("admin@test.com", null, "Admin"), userRepository, passwordEncoder);
        nullPassRunner.run(new DefaultApplicationArguments());

        AdminBootstrapRunner blankPassRunner = new AdminBootstrapRunner(
                new BootstrapAdminProperties("admin@test.com", "   ", "Admin"), userRepository, passwordEncoder);
        blankPassRunner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldNotCreateAdminWhenEmailAlreadyExistsWithAnotherRole() {
        BootstrapAdminProperties props = new BootstrapAdminProperties("admin@test.com", "secret", "Admin");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, userRepository, passwordEncoder);

        when(userRepository.countByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(0L);
        when(userRepository.existsByEmailIgnoreCase("admin@test.com")).thenReturn(true);

        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldCreateInitialSystemAdminWhenNoneExists() {
        BootstrapAdminProperties props = new BootstrapAdminProperties("  Admin@Test.com ", "secret123", "Super Admin");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, userRepository, passwordEncoder);

        when(userRepository.countByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(0L);
        when(userRepository.existsByEmailIgnoreCase("  Admin@Test.com ")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed-pwd");

        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        UserEntity saved = captor.getValue();
        assertEquals("admin@test.com", saved.getEmail());
        assertEquals("hashed-pwd", saved.getPasswordHash());
        assertEquals("Super Admin", saved.getFullName());
        assertEquals(Role.SYSTEM_ADMIN, saved.getRole());
    }
}