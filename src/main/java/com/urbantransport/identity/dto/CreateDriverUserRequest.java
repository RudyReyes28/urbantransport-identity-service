package com.urbantransport.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDriverUserRequest(
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(max = 120) String fullName) {
}
