package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.PasswordSetupTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordSetupTokenRepository extends JpaRepository<PasswordSetupTokenEntity, UUID> {

    Optional<PasswordSetupTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update PasswordSetupTokenEntity t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidatePending(@Param("userId") UUID userId, @Param("now") Instant now);
}
