package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(Role role);

    long countByBranchIdAndRoleAndActiveTrue(UUID branchId, Role role);
}