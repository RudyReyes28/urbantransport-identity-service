package com.urbantransport.identity.dto;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {
}