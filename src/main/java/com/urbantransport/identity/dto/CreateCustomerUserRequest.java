package com.urbantransport.identity.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record CreateCustomerUserRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 120) String fullName,
        @NotNull UUID customerId,
        @NotBlank @Size(max = 20) String nit) {
}