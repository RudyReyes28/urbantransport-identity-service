package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(Role role);

    long countByBranchIdAndRoleAndActiveTrue(UUID branchId, Role role);

    /** Con bloqueo: dos desactivaciones simultáneas no pueden dejar a la sucursal (o al sistema) sin admin. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<UserEntity> findAllByRoleAndActiveTrue(Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<UserEntity> findAllByBranchIdAndRoleAndActiveTrue(UUID branchId, Role role);
}