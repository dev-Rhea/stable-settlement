package com.payment.stablesettlement.interfaces.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank(message = "Payment reference is required")
        String paymentReference,

        @NotNull(message = "Sender ID is required")
        Long senderId,

        @NotNull(message = "Receiver ID is required")
        Long receiverId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @DecimalMax(value = "999999999999999999.99", message = "Amount exceeds maximum")
        @Digits(integer = 18, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        String currency
) {
}
