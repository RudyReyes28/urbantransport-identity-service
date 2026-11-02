package com.urbantransport.identity.dto;

import com.urbantransport.identity.domain.Role;
import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 120) String fullName,
        @NotNull Role role,
        UUID branchId) {
}