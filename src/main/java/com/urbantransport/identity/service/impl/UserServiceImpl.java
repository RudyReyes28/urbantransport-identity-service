package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.event.EventTypes;
import com.urbantransport.identity.event.UserEventPayload;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.BranchRepository;
import com.urbantransport.identity.repository.RefreshTokenRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.repository.UserSpecifications;
import com.urbantransport.identity.security.Actor;
import com.urbantransport.identity.service.OutboxService;
import com.urbantransport.identity.service.PasswordSetupService;
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
    private final PasswordSetupService passwordSetupService;

    @Override
    @Transactional
    public UserResponse create(Actor actor, CreateUserRequest request) {
        Role role = request.role();
        if (role == Role.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Los clientes se registran desde customer-service");
        }

        UUID branchId = request.branchId();
        if (!actor.isSystemAdmin()) {
            // El administrador de sucursal solo crea cajeros, y siempre en su propia sucursal
            if (role != Role.CASHIER) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Solo puedes crear cajeros para tu sucursal");
            }
            UUID ownBranch = requireOwnBranch(actor);
            if (branchId != null && !branchId.equals(ownBranch)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Solo puedes crear personal en tu sucursal");
            }
            branchId = ownBranch;
        }

        BranchEntity branch = null;
        boolean needsBranch = role == Role.BRANCH_ADMIN || role == Role.CASHIER;
        if (needsBranch) {
            if (branchId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "branchId es obligatorio para " + role);
            }
            branch = branchRepository.findById(branchId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
            if (!branch.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, "La sucursal está inactiva");
            }
        } else if (branchId != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "branchId solo aplica a BRANCH_ADMIN y CASHIER");
        }

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo " + email);
        }


        UserEntity user = userRepository.save(UserEntity.builder()
                .email(email)
                .passwordHash(passwordSetupService.unusablePasswordHash())
                .fullName(request.fullName().trim())
                .role(role)
                .branch(branch)
                .build());

        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_CREATED, UserEventPayload.fromEntity(user));
        passwordSetupService.issue(user);
        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(Actor actor, Role role, UUID branchId, Boolean active, String query,
                                             int page, int size) {
        // El administrador de sucursal solo ve su sucursal, sin importar el filtro que mande
        UUID scopedBranch = actor.isSystemAdmin() ? branchId : requireOwnBranch(actor);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(userRepository
                .findAll(UserSpecifications.filter(role, scopedBranch, active, query), pageable)
                .map(UserResponse::fromEntity));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findById(Actor actor, UUID id) {
        UserEntity user = getOrThrow(id);
        assertInScope(actor, user);
        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public UserResponse update(Actor actor, UUID id, UpdateUserRequest request) {
        UserEntity user = getManaged(actor, id);
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
    public UserResponse activate(Actor actor, UUID id) {
        UserEntity user = getManaged(actor, id);
        user.setActive(true);
        user.setDeactivatedBy(null);
        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public UserResponse deactivate(Actor actor, UUID id) {
        UserEntity user = getManaged(actor, id);
        if (user.getId().equals(actor.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "No puedes desactivar tu propia cuenta");
        }
        if (user.isActive()) {
            assertNotLastAdmin(user);
            user.setActive(false);
            user.setDeactivatedBy(actor.userId());
            refreshTokenRepository.revokeAllByUserId(user.getId());
        }
        outboxService.record(EventTypes.AGGREGATE_USER, user.getId(), EventTypes.USER_DEACTIVATED, UserEventPayload.fromEntity(user));
        return UserResponse.fromEntity(user);
    }

    @Override
    @Transactional
    public void resetPassword(Actor actor, UUID id, ResetPasswordRequest request) {
        UserEntity user = getManaged(actor, id);
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

    /** Usuario modificable por este actor: los clientes se gestionan en customer-service. */
    private UserEntity getManaged(Actor actor, UUID id) {
        UserEntity user = getOrThrow(id);
        if (user.getRole() == Role.CUSTOMER) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Los clientes no se gestionan desde este endpoint");
        }
        assertInScope(actor, user);
        if (!actor.isSystemAdmin() && user.getRole() != Role.CASHIER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo puedes gestionar a los cajeros de tu sucursal");
        }
        return user;
    }

    /** Un administrador de sucursal solo "ve" usuarios de su sucursal; los demás responden como si no existieran. */
    private void assertInScope(Actor actor, UserEntity user) {
        if (actor.isSystemAdmin()) {
            return;
        }
        UUID ownBranch = requireOwnBranch(actor);
        if (user.getBranch() == null || !user.getBranch().getId().equals(ownBranch)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Usuario no encontrado");
        }
    }

    private UUID requireOwnBranch(Actor actor) {
        if (actor.branchId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Tu cuenta no tiene una sucursal asignada");
        }
        return actor.branchId();
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}