package com.payment.stablesettlement.interfaces.dto.pg;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PgCallbackRequest(
    @NotBlank(message = "pgTransactionId is required")
    String pgTransactionId,

    @NotNull(message = "result is required")
    PgResult result,

    String reason
) {

}
