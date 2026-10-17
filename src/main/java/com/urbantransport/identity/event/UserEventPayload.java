package com.urbantransport.identity.event;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import lombok.Value;

import java.util.UUID;

@Value
public class UserEventPayload {
    UUID userId;
    String email;
    String fullName;
    Role role;
    UUID branchId;
    UUID customerId;
    boolean active;

    public static UserEventPayload fromEntity(UserEntity user) {
        return new UserEventPayload(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getBranch() == null ? null : user.getBranch().getId(),
                user.getCustomerId(), user.isActive());
    }
}