package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revoked = true where t.user.id = :userId and t.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);
}