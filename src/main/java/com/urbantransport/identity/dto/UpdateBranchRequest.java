package com.urbantransport.identity.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdateBranchRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String address,
        @Size(max = 60) String department,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
}