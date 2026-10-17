package com.urbantransport.identity.repository;

import com.urbantransport.identity.domain.BranchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<BranchEntity, UUID> {

    boolean existsByCodeIgnoreCase(String code);

    List<BranchEntity> findAllByOrderByNameAsc();

    List<BranchEntity> findByActiveOrderByNameAsc(boolean active);

}