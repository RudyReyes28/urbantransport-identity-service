package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoriesAndSpecificationsIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void userSpecificationsAndRepositoryQueriesShouldFilterCorrectly() {
        BranchEntity branch = branchRepository.save(BranchEntity.builder()
                .name("Sucursal Occidente")
                .code("OCC-01")
                .latitude(new BigDecimal("14.8347"))
                .longitude(new BigDecimal("-91.5180"))
                .active(true)
                .build());

        UserEntity cashier = userRepository.save(UserEntity.builder()
                .email("cajero.xela@urbantransport.com")
                .passwordHash("hash")
                .fullName("Carlos Cajero")
                .role(Role.CASHIER)
                .branch(branch)
                .active(true)
                .build());

        // 1. Con todos los filtros activos en UserSpecifications
        List<UserEntity> matched = userRepository.findAll(
                UserSpecifications.filter(Role.CASHIER, branch.getId(), true, "carlos"));
        assertEquals(1, matched.size());
        assertEquals(cashier.getId(), matched.get(0).getId());

        // 2. Con todos los filtros nulos o query en blanco
        List<UserEntity> allWithBlankQuery = userRepository.findAll(
                UserSpecifications.filter(null, null, null, "   "));
        assertFalse(allWithBlankQuery.isEmpty());

        List<UserEntity> allWithNullQuery = userRepository.findAll(
                UserSpecifications.filter(null, null, null, null));
        assertFalse(allWithNullQuery.isEmpty());

        // 3. Consultas de BranchRepository y UserRepository
        assertTrue(branchRepository.existsByCodeIgnoreCase("occ-01"));
        assertFalse(branchRepository.findAllByOrderByNameAsc().isEmpty());
        assertFalse(branchRepository.findByActiveOrderByNameAsc(true).isEmpty());
        assertEquals(1, userRepository.findAllByBranchIdAndRoleAndActiveTrue(branch.getId(), Role.CASHIER).size());
    }

    @Test
    void outboxAndRefreshTokenModifyingQueriesShouldUpdateRecords() {
        UserEntity user = userRepository.save(UserEntity.builder()
                .email("token.user@urbantransport.com")
                .passwordHash("hash")
                .fullName("Token User")
                .role(Role.DRIVER)
                .active(true)
                .build());

        RefreshTokenEntity token = refreshTokenRepository.save(RefreshTokenEntity.builder()
                .user(user)
                .tokenHash("hash-123456")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .revoked(false)
                .build());

        int updated = refreshTokenRepository.revokeAllByUserId(user.getId());
        assertEquals(1, updated);

        UUID eventId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEventEntity.builder()
                .id(eventId)
                .aggregateType("User")
                .aggregateId(user.getId())
                .eventType("UserCreated")
                .payload("{}")
                .build());

        assertFalse(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc().isEmpty());
        outboxEventRepository.markPublished(eventId, LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        OutboxEventEntity reloaded = outboxEventRepository.findById(eventId).orElseThrow();
        assertNotNull(reloaded.getPublishedAt());
    }
}