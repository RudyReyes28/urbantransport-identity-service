package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.event.*;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.BranchRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.repository.UserSpecifications;
import com.urbantransport.identity.service.OutboxService;
import com.urbantransport.identity.service.UserService;
import com.urbantransport.identity.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        Role role = request.role();
        if (role == Role.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Los clientes se registran desde customer-service");
        }

        BranchEntity branch = null;
        boolean needsBranch = role == Role.BRANCH_ADMIN || role == Role.CASHIER;
        if (needsBranch) {
            if (request.branchId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "branchId es obligatorio para " + role);
            }
            branch = branchRepository.findById(request.branchId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
            if (!branch.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, "La sucursal está inactiva");
            }
        } else if (request.branchId() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "branchId solo aplica a BRANCH_ADMIN y CASHIER");
        }

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo " + email);
        }

        UserEntity user = userRepository.save(UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(role)
                .branch(branch)
                .build());

        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_CREATED, UserEventPayload.fromEntity(user));

        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(Role role, UUID branchId, Boolean active, String query,
                                             int page, int size) {
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(userRepository
                .findAll(UserSpecifications.filter(role, branchId, active, query), pageable)
                .map(UserResponse::fromEntity));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        return UserResponse.fromEntity(getOrThrow(id));
    }

    @Override
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        UserEntity user = getManaged(id);
        String email = normalizeEmail(request.email());
        if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo " + email);
        }
        user.setEmail(email);
        user.setFullName(request.fullName().trim());

        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_UPDATED, UserEventPayload.fromEntity(user));

        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public UserResponse activate(UUID id) {
        UserEntity user = getManaged(id);
        user.setActive(true);
        user.setDeactivatedBy(null);

        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_UPDATED, UserEventPayload.fromEntity(user));

        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public UserResponse deactivate(UUID id, UUID actorId) {
        UserEntity user = getManaged(id);
        if (user.getId().equals(actorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "No puedes desactivar tu propia cuenta");
        }
        if (user.isActive()) {
            assertNotLastAdmin(user);
            user.setActive(false);
            user.setDeactivatedBy(actorId);
            refreshTokenRepository.revokeAllByUserId(user.getId());

            outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_DEACTIVATED, UserEventPayload.fromEntity(user));
        }
        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest request) {
        UserEntity user = getManaged(id);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllByUserId(user.getId());
    }

    /** El usuario a desactivar está activo, así que aparece en la lista: si solo hay uno, es el último. */
    private void assertNotLastAdmin(UserEntity user) {
        if (user.getRole() == Role.SYSTEM_ADMIN
                && userRepository.findAllByRoleAndActiveTrue(Role.SYSTEM_ADMIN).size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Debe existir al menos un SYSTEM_ADMIN activo");
        }
        if (user.getRole() == Role.BRANCH_ADMIN
                && userRepository.findAllByBranchIdAndRoleAndActiveTrue(user.getBranch().getId(), Role.BRANCH_ADMIN)
                .size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "La sucursal debe conservar al menos un BRANCH_ADMIN activo");
        }
    }

    private UserEntity getOrThrow(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
    }

    /** Usuario modificable desde este módulo: los clientes se gestionan por la vía de customer-service. */
    private UserEntity getManaged(UUID id) {
        UserEntity user = getOrThrow(id);
        if (user.getRole() == Role.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Los clientes no se gestionan desde este endpoint");
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}