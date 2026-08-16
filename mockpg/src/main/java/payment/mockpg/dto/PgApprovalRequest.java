package payment.mockpg.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PgApprovalRequest(
    @NotBlank(message = "paymentReference is required")
    String paymentReference,

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be positive")
    BigDecimal amount,

    @NotBlank(message = "currency is required")
    String currency
) {
}
