package com.urbantransport.identity.service.impl;

import com.urbantransport.identity.domain.BranchEntity;
import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import com.urbantransport.identity.event.*;
import com.urbantransport.identity.exception.ApiException;
import com.urbantransport.identity.repository.BranchRepository;
import com.urbantransport.identity.repository.UserRepository;
import com.urbantransport.identity.service.BranchService;
import com.urbantransport.identity.dto.*;
import com.urbantransport.identity.service.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BranchServiceImpl implements BranchService {

    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OutboxService outboxService;

    /** La sucursal y su primer administrador se guardan en la misma transacción. */
    @Override
    @Transactional
    public BranchCreatedResponse create(CreateBranchRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String adminEmail = request.admin().email().trim().toLowerCase(Locale.ROOT);

        if (branchRepository.existsByCodeIgnoreCase(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe una sucursal con el código " + code);
        }
        if (userRepository.existsByEmailIgnoreCase(adminEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un usuario con el correo " + adminEmail);
        }

        BranchEntity branch = branchRepository.save(BranchEntity.builder()
                .name(request.name().trim())
                .code(code)
                .address(request.address())
                .department(request.department())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .build());

        outboxService.record(EventTypes.AGGREGATE_BRANCH, branch.getId(), EventTypes.BRANCH_CREATED, BranchEventPayload.fromEntity(branch));

        UserEntity admin = userRepository.save(UserEntity.builder()
                .email(adminEmail)
                .passwordHash(passwordEncoder.encode(request.admin().password()))
                .fullName(request.admin().fullName().trim())
                .role(Role.BRANCH_ADMIN)
                .branch(branch)
                .build());
        outboxService.record(EventTypes.AGGREGATE_USER, admin.getId(), EventTypes.USER_CREATED, UserEventPayload.fromEntity(admin));

        return new BranchCreatedResponse(BranchResponse.fromEntity(branch), UserResponse.fromEntity(admin));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchResponse> findAll(Boolean active) {
        List<BranchEntity> branches = active == null
                ? branchRepository.findAllByOrderByNameAsc()
                : branchRepository.findByActiveOrderByNameAsc(active);
        return branches.stream().map(BranchResponse::fromEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BranchResponse findById(UUID id) {
        return BranchResponse.fromEntity(getOrThrow(id));
    }

    @Override
    @Transactional
    public BranchResponse update(UUID id, UpdateBranchRequest request) {
        BranchEntity branch = getOrThrow(id);
        branch.setName(request.name().trim());
        branch.setAddress(request.address());
        branch.setDepartment(request.department());
        branch.setLatitude(request.latitude());
        branch.setLongitude(request.longitude());

        outboxService.record(EventTypes.AGGREGATE_BRANCH, branch.getId(), EventTypes.BRANCH_UPDATED, BranchEventPayload.fromEntity(branch));

        return BranchResponse.fromEntity(branch);
    }

    @Override
    @Transactional
    public BranchResponse activate(UUID id) {
        BranchEntity branch = getOrThrow(id);
        branch.setActive(true);
        outboxService.record(EventTypes.AGGREGATE_BRANCH, branch.getId(), EventTypes.BRANCH_UPDATED, BranchEventPayload.fromEntity(branch));
        return BranchResponse.fromEntity(branch);
    }

    @Override
    @Transactional
    public BranchResponse deactivate(UUID id) {
        BranchEntity branch = getOrThrow(id);
        branch.setActive(false);
        outboxService.record(EventTypes.AGGREGATE_BRANCH, branch.getId(), EventTypes.BRANCH_UPDATED, BranchEventPayload.fromEntity(branch));
        return BranchResponse.fromEntity(branch);
    }

    private BranchEntity getOrThrow(UUID id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
    }
}