package com.urbantransport.identity.event;

import com.urbantransport.identity.domain.BranchEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class BranchEventPayload {
    UUID branchId;
    String name;
    String code;
    BigDecimal latitude;
    BigDecimal longitude;
    boolean active;

    public static BranchEventPayload fromEntity(BranchEntity branch) {
        return new BranchEventPayload(branch.getId(), branch.getName(), branch.getCode(),
                branch.getLatitude(), branch.getLongitude(), branch.isActive());
    }
}