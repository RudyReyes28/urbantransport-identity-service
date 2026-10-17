package com.urbantransport.identity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateBranchRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "solo letras, números, guion y guion bajo") String code,
        @Size(max = 255) String address,
        @Size(max = 60) String department,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @Valid InitialAdminRequest admin) {
}