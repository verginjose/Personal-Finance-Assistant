package com.finance.query.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for PATCH /upsert/entries/{id}/amount
 * Allows correcting the amount of an existing transaction without resending all fields.
 */
public record PatchAmountRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
        BigDecimal amount
) {}
