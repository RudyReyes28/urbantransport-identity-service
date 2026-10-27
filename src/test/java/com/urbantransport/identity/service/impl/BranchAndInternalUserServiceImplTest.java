package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.dto.*;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.BranchRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchAndInternalUserServiceImplTest {

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OutboxService outboxService;

    private BranchServiceImpl branchService;
    private InternalUserServiceImpl internalUserService;

    @BeforeEach
    void setUp() {
        branchService = new BranchServiceImpl(branchRepository, userRepository, passwordEncoder, outboxService);
        internalUserService = new InternalUserServiceImpl(userRepository, passwordEncoder, outboxService);
    }

    @Test
    void branchCreateShouldValidateDuplicatesAndCreateBranchWithAdmin() {
        InitialAdminRequest adminReq = new InitialAdminRequest(" Admin@Branch.com ", "secret123", " Branch Admin ");
        CreateBranchRequest req = new CreateBranchRequest(
                " Sucursal Xela ", " xel-01 ", "Av. Las Américas", "Quetzaltenango",
                new BigDecimal("14.8347"), new BigDecimal("-91.5180"), adminReq);

        // 1. Código de sucursal duplicado
        when(branchRepository.existsByCodeIgnoreCase("XEL-01")).thenReturn(true);
        ApiException codeEx = assertThrows(ApiException.class, () -> branchService.create(req));
        assertEquals(HttpStatus.CONFLICT, codeEx.getStatus());

        // 2. Correo del administrador duplicado
        when(branchRepository.existsByCodeIgnoreCase("XEL-01")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("admin@branch.com")).thenReturn(true);
        assertThrows(ApiException.class, () -> branchService.create(req));

        // 3. Creación exitosa
        when(userRepository.existsByEmailIgnoreCase("admin@branch.com")).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(branchRepository.save(any(BranchEntity.class))).thenAnswer(inv -> {
            BranchEntity b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        BranchCreatedResponse created = branchService.create(req);
        assertEquals("XEL-01", created.getBranch().getCode());
        assertEquals("admin@branch.com", created.getAdmin().getEmail());
        assertEquals(Role.BRANCH_ADMIN, created.getAdmin().getRole());
        verify(outboxService).record(eq(EventTypes.AGGREGATE_BRANCH), any(), eq(EventTypes.BRANCH_CREATED), any());
        verify(outboxService).record(eq(EventTypes.AGGREGATE_USER), any(), eq(EventTypes.USER_CREATED), any());
    }

    @Test
    void branchFindUpdateActivateDeactivateShouldCoverAllPaths() {
        UUID id = UUID.randomUUID();
        BranchEntity entity = BranchEntity.builder()
                .id(id).name("Central").code("CEN")
                .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).active(true).build();

        // findAll con null y con boolean
        when(branchRepository.findAllByOrderByNameAsc()).thenReturn(List.of(entity));
        when(branchRepository.findByActiveOrderByNameAsc(true)).thenReturn(List.of(entity));
        assertEquals(1, branchService.findAll(null).size());
        assertEquals(1, branchService.findAll(true).size());

        // findById exitoso y no encontrado
        when(branchRepository.findById(id)).thenReturn(Optional.of(entity));
        assertEquals("CEN", branchService.findById(id).getCode());

        UUID missingId = UUID.randomUUID();
        when(branchRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> branchService.findById(missingId));

        // update, deactivate, activate
        UpdateBranchRequest updateReq = new UpdateBranchRequest(
                " Nueva Central ", "Calle 1", "Guatemala", BigDecimal.TEN, BigDecimal.TEN);
        BranchResponse updated = branchService.update(id, updateReq);
        assertEquals("Nueva Central", updated.getName());

        BranchResponse deactivated = branchService.deactivate(id);
        assertFalse(deactivated.isActive());

        BranchResponse activated = branchService.activate(id);
        assertTrue(activated.isActive());
        verify(outboxService, times(3)).record(eq(EventTypes.AGGREGATE_BRANCH), eq(id), eq(EventTypes.BRANCH_UPDATED), any());
    }

    @Test
    void internalUserServiceShouldCreateCustomerAndDriverAndValidateConflicts() {
        UUID customerId = UUID.randomUUID();
        CreateCustomerUserRequest custReq = new CreateCustomerUserRequest(
                " Cliente@Test.com ", "secret123", " Cliente Uno ", customerId, " 12345k ");

        // 1. Email duplicado en customer
        when(userRepository.existsByEmailIgnoreCase("cliente@test.com")).thenReturn(true);
        assertThrows(ApiException.class, () -> internalUserService.createCustomerUser(custReq));

        // 2. CustomerId ya tiene cuenta
        when(userRepository.existsByEmailIgnoreCase("cliente@test.com")).thenReturn(false);
        when(userRepository.existsByCustomerId(customerId)).thenReturn(true);
        assertThrows(ApiException.class, () -> internalUserService.createCustomerUser(custReq));

        // 3. Creación exitosa de customer
        when(userRepository.existsByCustomerId(customerId)).thenReturn(false);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        UserResponse custResp = internalUserService.createCustomerUser(custReq);
        assertEquals(Role.CUSTOMER, custResp.getRole());
        assertEquals("12345K", custResp.getNit());

        // 4. Creación de driver (conflicto de email y éxito)
        CreateDriverUserRequest driverReq = new CreateDriverUserRequest(" Driver@Test.com ", "secret123", " Chofer ");
        when(userRepository.existsByEmailIgnoreCase("driver@test.com")).thenReturn(true);
        assertThrows(ApiException.class, () -> internalUserService.createDriverUser(driverReq));

        when(userRepository.existsByEmailIgnoreCase("driver@test.com")).thenReturn(false);
        UserResponse driverResp = internalUserService.createDriverUser(driverReq);
        assertEquals(Role.DRIVER, driverResp.getRole());
    }
}