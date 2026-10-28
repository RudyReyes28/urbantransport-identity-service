package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.dto.*;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.event.UserEventPayload;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.BranchRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.security.Actor;
import com.urbantransport.identity.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxService outboxService;

    private UserServiceImpl userService;
    private UUID branchId;
    private BranchEntity activeBranch;
    private Actor sysAdmin;
    private Actor branchAdmin;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userRepository, branchRepository, refreshTokenRepository, passwordEncoder, outboxService);
        branchId = UUID.randomUUID();
        activeBranch = BranchEntity.builder().id(branchId).name("Central").active(true).build();
        sysAdmin = new Actor(UUID.randomUUID(), Role.SYSTEM_ADMIN, null);
        branchAdmin = new Actor(UUID.randomUUID(), Role.BRANCH_ADMIN, branchId);
    }

    @Test
    void createShouldEnforceRoleAndBranchRules() {
        // 1. No permite crear CUSTOMER
        assertThrows(ApiException.class, () -> userService.create(sysAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cust", Role.CUSTOMER, null)));

        // 2. BRANCH_ADMIN intentando crear un rol distinto a CASHIER
        assertThrows(ApiException.class, () -> userService.create(branchAdmin,
                new CreateUserRequest("b@test.com", "12345678", "Admin", Role.BRANCH_ADMIN, branchId)));

        // 3. BRANCH_ADMIN sin sucursal asignada en el token
        Actor orphanBranchAdmin = new Actor(UUID.randomUUID(), Role.BRANCH_ADMIN, null);
        assertThrows(ApiException.class, () -> userService.create(orphanBranchAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cashier", Role.CASHIER, null)));

        // 4. BRANCH_ADMIN intentando crear cajero en otra sucursal
        assertThrows(ApiException.class, () -> userService.create(branchAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cashier", Role.CASHIER, UUID.randomUUID())));

        // 5. SYSTEM_ADMIN creando CASHIER sin branchId -> 400
        assertThrows(ApiException.class, () -> userService.create(sysAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cashier", Role.CASHIER, null)));

        // 6. Sucursal no existe -> 404
        when(branchRepository.findById(branchId)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> userService.create(branchAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cashier", Role.CASHIER, null)));

        // 7. Sucursal inactiva -> 409
        BranchEntity inactiveBranch = BranchEntity.builder().id(branchId).active(false).build();
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(inactiveBranch));
        assertThrows(ApiException.class, () -> userService.create(branchAdmin,
                new CreateUserRequest("c@test.com", "12345678", "Cashier", Role.CASHIER, branchId)));

        // 8. SYSTEM_ADMIN enviando branchId para SYSTEM_ADMIN o DRIVER -> 400
        assertThrows(ApiException.class, () -> userService.create(sysAdmin,
                new CreateUserRequest("s@test.com", "12345678", "Sys", Role.SYSTEM_ADMIN, branchId)));

        // 9. Correo duplicado -> 409
        when(branchRepository.findById(branchId)).thenReturn(Optional.of(activeBranch));
        when(userRepository.existsByEmailIgnoreCase("cashier@test.com")).thenReturn(true);
        assertThrows(ApiException.class, () -> userService.create(branchAdmin,
                new CreateUserRequest(" Cashier@Test.com ", "12345678", "Cashier", Role.CASHIER, null)));

        // 10. Creación exitosa con sucursal (BRANCH_ADMIN creando CASHIER) y sin sucursal (SYSTEM_ADMIN)
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserResponse cashierResp = userService.create(branchAdmin,
                new CreateUserRequest("cashier@test.com", "12345678", " Cashier ", Role.CASHIER, null));
        assertEquals(branchId, cashierResp.getBranchId());

        UserResponse sysResp = userService.create(sysAdmin,
                new CreateUserRequest("sys2@test.com", "12345678", " Sys 2 ", Role.SYSTEM_ADMIN, null));
        assertNull(sysResp.getBranchId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAndFindByIdShouldRespectActorScope() {
        UserEntity cashier = UserEntity.builder()
                .id(UUID.randomUUID()).email("c@test.com").fullName("Cashier")
                .role(Role.CASHIER).branch(activeBranch).active(true).build();

        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cashier)));

        PageResponse<UserResponse> page1 = userService.search(sysAdmin, Role.CASHIER, branchId, true, "cash", 0, 20);
        assertEquals(1, page1.getContent().size());

        PageResponse<UserResponse> page2 = userService.search(branchAdmin, null, null, null, "   ", -1, 500);
        assertEquals(1, page2.getContent().size());

        // findById en alcance y fuera de alcance
        when(userRepository.findById(cashier.getId())).thenReturn(Optional.of(cashier));
        assertEquals("c@test.com", userService.findById(sysAdmin, cashier.getId()).getEmail());
        assertEquals("c@test.com", userService.findById(branchAdmin, cashier.getId()).getEmail());

        Actor otherBranchAdmin = new Actor(UUID.randomUUID(), Role.BRANCH_ADMIN, UUID.randomUUID());
        assertThrows(ApiException.class, () -> userService.findById(otherBranchAdmin, cashier.getId()));

        UserEntity userWithoutBranch = UserEntity.builder().id(UUID.randomUUID()).role(Role.SYSTEM_ADMIN).build();
        when(userRepository.findById(userWithoutBranch.getId())).thenReturn(Optional.of(userWithoutBranch));
        assertThrows(ApiException.class, () -> userService.findById(branchAdmin, userWithoutBranch.getId()));
    }

    @Test
    void updateActivateDeactivateAndResetPasswordShouldCoverAllValidations() {
        UUID cashierId = UUID.randomUUID();
        UserEntity cashier = UserEntity.builder()
                .id(cashierId).email("old@test.com").fullName("Old")
                .role(Role.CASHIER).branch(activeBranch).active(true).build();
        when(userRepository.findById(cashierId)).thenReturn(Optional.of(cashier));

        // Update con el mismo correo y con un correo nuevo disponible / ocupado
        userService.update(branchAdmin, cashierId, new UpdateUserRequest("old@test.com", "New Name"));
        assertEquals("New Name", cashier.getFullName());

        when(userRepository.existsByEmailIgnoreCase("taken@test.com")).thenReturn(true);
        assertThrows(ApiException.class,
                () -> userService.update(branchAdmin, cashierId, new UpdateUserRequest("taken@test.com", "Name")));

        when(userRepository.existsByEmailIgnoreCase("new@test.com")).thenReturn(false);
        userService.update(branchAdmin, cashierId, new UpdateUserRequest("new@test.com", "Name"));
        assertEquals("new@test.com", cashier.getEmail());

        // Activate & ResetPassword
        userService.activate(branchAdmin, cashierId);
        assertTrue(cashier.isActive());

        when(passwordEncoder.encode("newPass123")).thenReturn("newHash");
        userService.resetPassword(branchAdmin, cashierId, new ResetPasswordRequest("newPass123"));
        verify(refreshTokenRepository).revokeAllByUserId(cashierId);

        // No se puede gestionar CUSTOMER ni otro BRANCH_ADMIN siendo BRANCH_ADMIN
        UserEntity customer = UserEntity.builder().id(UUID.randomUUID()).role(Role.CUSTOMER).build();
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        assertThrows(ApiException.class, () -> userService.activate(sysAdmin, customer.getId()));

        UserEntity peerBranchAdmin = UserEntity.builder()
                .id(UUID.randomUUID()).role(Role.BRANCH_ADMIN).branch(activeBranch).active(true).build();
        when(userRepository.findById(peerBranchAdmin.getId())).thenReturn(Optional.of(peerBranchAdmin));
        assertThrows(ApiException.class, () -> userService.activate(branchAdmin, peerBranchAdmin.getId()));

        // Deactivate: auto-desactivación prohibida
        UserEntity sysEntity = UserEntity.builder().id(sysAdmin.userId()).role(Role.SYSTEM_ADMIN).active(true).build();
        when(userRepository.findById(sysAdmin.userId())).thenReturn(Optional.of(sysEntity));
        assertThrows(ApiException.class, () -> userService.deactivate(sysAdmin, sysAdmin.userId()));

        // Deactivate: último SYSTEM_ADMIN y último BRANCH_ADMIN
        UserEntity otherSys = UserEntity.builder().id(UUID.randomUUID()).role(Role.SYSTEM_ADMIN).active(true).build();
        when(userRepository.findById(otherSys.getId())).thenReturn(Optional.of(otherSys));
        when(userRepository.findAllByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(List.of(otherSys));
        assertThrows(ApiException.class, () -> userService.deactivate(sysAdmin, otherSys.getId()));

        when(userRepository.findAllByRoleAndActiveTrue(Role.SYSTEM_ADMIN)).thenReturn(List.of(otherSys, sysEntity));
        userService.deactivate(sysAdmin, otherSys.getId());
        assertFalse(otherSys.isActive());

        // Si ya está inactivo, deactivate no vuelve a revocar
        userService.deactivate(sysAdmin, otherSys.getId());

        when(userRepository.findAllByBranchIdAndRoleAndActiveTrue(branchId, Role.BRANCH_ADMIN))
                .thenReturn(List.of(peerBranchAdmin));
        assertThrows(ApiException.class, () -> userService.deactivate(sysAdmin, peerBranchAdmin.getId()));

        when(userRepository.findAllByBranchIdAndRoleAndActiveTrue(branchId, Role.BRANCH_ADMIN))
                .thenReturn(List.of(peerBranchAdmin, UserEntity.builder().build()));
        userService.deactivate(sysAdmin, peerBranchAdmin.getId());
        assertFalse(peerBranchAdmin.isActive());
    }
}