package com.urbantransport.identity.dto;

import com.urbantransport.identity.domain.Role;
import com.urbantransport.identity.domain.UserEntity;
import lombok.Value;

import java.util.UUID;

@Value
public class UserResponse {
    UUID id;
    String email;
    String fullName;
    Role role;
    UUID branchId;
    UUID customerId;
    String nit;
    boolean active;

    public static UserResponse fromEntity(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getBranch() == null ? null : user.getBranch().getId(),
                user.getCustomerId(),
                user.getNit(),
                user.isActive());
    }
}