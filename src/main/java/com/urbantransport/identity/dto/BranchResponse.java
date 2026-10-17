package com.urbantransport.identity.dto;

import com.urbantransport.identity.domain.BranchEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
public class BranchResponse {
    UUID id;
    String name;
    String code;
    String address;
    String department;
    BigDecimal latitude;
    BigDecimal longitude;
    boolean active;
    LocalDateTime createdAt;

    public static BranchResponse fromEntity(BranchEntity branch) {
        return new BranchResponse(
                branch.getId(),
                branch.getName(),
                branch.getCode(),
                branch.getAddress(),
                branch.getDepartment(),
                branch.getLatitude(),
                branch.getLongitude(),
                branch.isActive(),
                branch.getCreatedAt());
    }
}