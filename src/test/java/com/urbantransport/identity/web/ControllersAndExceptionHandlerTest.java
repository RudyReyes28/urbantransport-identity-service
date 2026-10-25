package com.urbantransport.identity.web;

import com.urbantransport.identity.config.JwtProperties;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.dto.*;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.exception.ErrorResponse;
import com.urbantransport.identity.exception.GlobalExceptionHandler;
import com.urbantransport.identity.security.Actor;
import com.urbantransport.identity.security.RsaKeyProvider;
import com.urbantransport.identity.service.AuthService;
import com.urbantransport.identity.service.BranchService;
import com.urbantransport.identity.service.InternalUserService;
import com.urbantransport.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ControllersAndExceptionHandlerTest {

    @Mock
    private AuthService authService;

    @Mock
    private BranchService branchService;

    @Mock
    private UserService userService;

    @Mock
    private InternalUserService internalUserService;

    @Test
    void authControllerShouldDelegateAllEndpoints() {
        AuthController controller = new AuthController(authService);
        UUID userId = UUID.randomUUID();
        Jwt jwt = buildJwt(userId, Role.SYSTEM_ADMIN, null);

        UserResponse userResp = new UserResponse(userId, "a@test.com", "Admin", Role.SYSTEM_ADMIN, null, null, null, true);
        TokenResponse tokenResp = TokenResponse.of("acc", "ref", 900L, userResp);

        LoginRequest loginReq = new LoginRequest("a@test.com", "12345678");
        RefreshRequest refreshReq = new RefreshRequest("ref");
        ChangePasswordRequest changePassReq = new ChangePasswordRequest("old", "new123456");

        when(authService.login(loginReq)).thenReturn(tokenResp);
        when(authService.refresh(refreshReq)).thenReturn(tokenResp);
        when(authService.me(userId)).thenReturn(userResp);

        assertEquals(tokenResp, controller.login(loginReq));
        assertEquals(tokenResp, controller.refresh(refreshReq));
        controller.logout(refreshReq);
        verify(authService).logout(refreshReq);

        assertEquals(userResp, controller.me(jwt));
        controller.changePassword(jwt, changePassReq);
        verify(authService).changePassword(userId, changePassReq);
    }

    @Test
    void branchControllerShouldHandleAllEndpointsAndRoleFiltering() {
        BranchController controller = new BranchController(branchService);
        UUID branchId = UUID.randomUUID();

        BranchResponse branchResp = new BranchResponse(
                branchId, "Central", "CEN", "Dir", "Dep",
                BigDecimal.ONE, BigDecimal.ONE, true, LocalDateTime.now());
        UserResponse adminResp = new UserResponse(
                UUID.randomUUID(), "b@test.com", "Admin", Role.BRANCH_ADMIN, branchId, null, null, true);
        BranchCreatedResponse createdResp = new BranchCreatedResponse(branchResp, adminResp);

        CreateBranchRequest createReq = new CreateBranchRequest(
                "Central", "CEN", "Dir", "Dep", BigDecimal.ONE, BigDecimal.ONE,
                new InitialAdminRequest("b@test.com", "12345678", "Admin"));
        UpdateBranchRequest updateReq = new UpdateBranchRequest(
                "Central 2", "Dir", "Dep", BigDecimal.ONE, BigDecimal.ONE);

        when(branchService.create(createReq)).thenReturn(createdResp);
        when(branchService.findById(branchId)).thenReturn(branchResp);
        when(branchService.update(branchId, updateReq)).thenReturn(branchResp);
        when(branchService.activate(branchId)).thenReturn(branchResp);
        when(branchService.deactivate(branchId)).thenReturn(branchResp);

        assertEquals(createdResp, controller.create(createReq));
        assertEquals(branchResp, controller.findById(branchId));
        assertEquals(branchResp, controller.update(branchId, updateReq));
        assertEquals(branchResp, controller.activate(branchId));
        assertEquals(branchResp, controller.deactivate(branchId));

        // 1. findAll como SYSTEM_ADMIN -> respeta el parámetro active enviado (ej. null o false)
        Authentication sysAdminAuth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")));
        when(branchService.findAll(null)).thenReturn(List.of(branchResp));
        assertEquals(1, controller.findAll(null, sysAdminAuth).size());
        verify(branchService).findAll(null);

        // 2. findAll como otro rol (ej. CASHIER) -> fuerza Boolean.TRUE
        Authentication cashierAuth = new UsernamePasswordAuthenticationToken(
                "cashier", null, List.of(new SimpleGrantedAuthority("ROLE_CASHIER")));
        when(branchService.findAll(Boolean.TRUE)).thenReturn(List.of(branchResp));
        assertEquals(1, controller.findAll(false, cashierAuth).size());
        verify(branchService).findAll(Boolean.TRUE);
    }

    @Test
    void userAndInternalAndPublicKeyControllersShouldDelegateAllCalls() {
        UserController userController = new UserController(userService);
        UUID adminId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Jwt jwt = buildJwt(adminId, Role.SYSTEM_ADMIN, null);

        UserResponse userResp = new UserResponse(targetId, "u@test.com", "User", Role.CASHIER, null, null, null, true);
        CreateUserRequest createReq = new CreateUserRequest("u@test.com", "12345678", "User", Role.CASHIER, UUID.randomUUID());
        UpdateUserRequest updateReq = new UpdateUserRequest("u2@test.com", "User 2");
        ResetPasswordRequest resetReq = new ResetPasswordRequest("newPass123");
        PageResponse<UserResponse> pageResp = new PageResponse<>(List.of(userResp), 0, 20, 1, 1);

        when(userService.create(any(Actor.class), eq(createReq))).thenReturn(userResp);
        when(userService.search(any(Actor.class), eq(Role.CASHIER), any(), eq(true), eq("q"), eq(0), eq(20)))
                .thenReturn(pageResp);
        when(userService.findById(any(Actor.class), eq(targetId))).thenReturn(userResp);
        when(userService.update(any(Actor.class), eq(targetId), eq(updateReq))).thenReturn(userResp);
        when(userService.activate(any(Actor.class), eq(targetId))).thenReturn(userResp);
        when(userService.deactivate(any(Actor.class), eq(targetId))).thenReturn(userResp);

        assertEquals(userResp, userController.create(jwt, createReq));
        assertEquals(pageResp, userController.search(jwt, Role.CASHIER, null, true, "q", 0, 20));
        assertEquals(userResp, userController.findById(jwt, targetId));
        assertEquals(userResp, userController.update(jwt, targetId, updateReq));
        assertEquals(userResp, userController.activate(jwt, targetId));
        assertEquals(userResp, userController.deactivate(jwt, targetId));
        userController.resetPassword(jwt, targetId, resetReq);
        verify(userService).resetPassword(any(Actor.class), eq(targetId), eq(resetReq));

        // InternalUserController
        InternalUserController internalController = new InternalUserController(internalUserService);
        CreateCustomerUserRequest custReq = new CreateCustomerUserRequest(
                "c@test.com", "12345678", "Cust", UUID.randomUUID(), "12345");
        CreateDriverUserRequest driverReq = new CreateDriverUserRequest("d@test.com", "12345678", "Driver");

        when(internalUserService.createCustomerUser(custReq)).thenReturn(userResp);
        when(internalUserService.createDriverUser(driverReq)).thenReturn(userResp);
        assertEquals(userResp, internalController.createCustomerUser(custReq));
        assertEquals(userResp, internalController.createDriverUser(driverReq));

        // PublicKeyController
        RsaKeyProvider keyProvider = new RsaKeyProvider(
                new JwtProperties("issuer", Duration.ofMinutes(15), Duration.ofDays(7), null));
        PublicKeyController publicKeyController = new PublicKeyController(keyProvider);
        assertTrue(publicKeyController.publicKey().contains("BEGIN PUBLIC KEY"));
        assertNotNull(publicKeyController.jwks().get("keys"));
    }

    @Test
    void globalExceptionHandlerShouldFormatAllExceptionTypes() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users");

        // 1. ApiException
        ResponseEntity<ErrorResponse> apiResp = handler.handleApi(
                new ApiException(HttpStatus.NOT_FOUND, "No existe"), request);
        assertEquals(404, apiResp.getStatusCode().value());
        assertEquals("No existe", apiResp.getBody().getMessage());
        assertEquals("/users", apiResp.getBody().getPath());
        assertNotNull(apiResp.getBody().getTimestamp());

        // 2. MethodArgumentNotValidException
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "req");
        bindingResult.addError(new FieldError("req", "email", "debe ser válido"));
        MethodArgumentNotValidException valEx = new MethodArgumentNotValidException((MethodParameter) null, bindingResult);

        ResponseEntity<ErrorResponse> valResp = handler.handleValidation(valEx, request);
        assertEquals(400, valResp.getStatusCode().value());
        assertTrue(valResp.getBody().getMessage().contains("email: debe ser válido"));

        // 3. HttpMessageNotReadableException
        HttpMessageNotReadableException unreadableEx = new HttpMessageNotReadableException(
                "JSON roto", (HttpInputMessage) null);
        ResponseEntity<ErrorResponse> unreadableResp = handler.handleUnreadable(unreadableEx, request);
        assertEquals(400, unreadableResp.getStatusCode().value());
        assertEquals("Cuerpo de la petición inválido", unreadableResp.getBody().getMessage());

        // 4. DataIntegrityViolationException
        ResponseEntity<ErrorResponse> integrityResp = handler.handleIntegrity(
                new DataIntegrityViolationException("duplicate key"), request);
        assertEquals(409, integrityResp.getStatusCode().value());
        assertEquals("El registro ya existe o viola una restricción", integrityResp.getBody().getMessage());
    }

    private Jwt buildJwt(UUID subject, Role role, UUID branchId) {
        Map<String, Object> claims = branchId == null
                ? Map.of("roles", List.of(role.name()))
                : Map.of("roles", List.of(role.name()), "branchId", branchId.toString());
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "RS256"), claims) {
            @Override
            public String getSubject() {
                return subject.toString();
            }
        };
    }
}