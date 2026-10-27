package com.urbantransport.identity.repository;


import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<UserEntity> filter(Role role, UUID branchId, Boolean active, String query) {
        return (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (branchId != null) {
                predicates.add(cb.equal(root.get("branch").get("id"), branchId));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("fullName")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}